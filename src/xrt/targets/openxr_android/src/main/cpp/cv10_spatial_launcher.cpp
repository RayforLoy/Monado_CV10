// SPDX-License-Identifier: BSL-1.0

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <android/bitmap.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <jni.h>
#include <linux/sync_file.h>
#include <media/NdkImageReader.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/system_properties.h>
#include <time.h>
#include <unistd.h>

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#define XR_USE_TIMESPEC
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include "xrt/xrt_cv10_android_stream.h"

#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <cinttypes>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr char tag[] = "Cv10SpatialLauncher";
constexpr uint32_t panel_width = 1280;
constexpr uint32_t panel_height = 720;
// Preserve the menu's scale while giving the laser a transparent border.
constexpr uint32_t canvas_width = 2048;
constexpr uint32_t canvas_height = 1280;
constexpr uint32_t pocket_width = 3840;
constexpr uint32_t pocket_height = 1080;
// The Pocket XR companion uses the symmetric OEM CV10 frustum at 47.5 degrees per side.
constexpr float cv10_half_fov = 47.5f * 0.017453292519943295f;
constexpr XrFovf cv10_fov = {-cv10_half_fov, cv10_half_fov, cv10_half_fov, -cv10_half_fov};
constexpr float cv10_focus_scale = 0.4f;
constexpr int32_t cv10_context_eye_limit = 1600;
constexpr int32_t cv10_default_focus_eye_size = 1280;
constexpr float panel_distance = 1.45f;
constexpr float pocket_distance = 1.0f;
constexpr XrExtent2Df pocket_size = {0.812f, 0.458f};
constexpr float pocket_wide_scale = 1.3f;
constexpr XrExtent2Df pocket_wide_size = {pocket_size.width * pocket_wide_scale,
                                          pocket_size.height * pocket_wide_scale};
constexpr float panel_width_m = 1.62f;
std::mutex session_mutex;

int64_t
monotonic_ns()
{
	timespec now{};
	clock_gettime(CLOCK_MONOTONIC, &now);
	return static_cast<int64_t>(now.tv_sec) * 1'000'000'000 + now.tv_nsec;
}

// Matches LauncherControllerMode in Cv10SpatialLauncherActivity.kt.
enum class ControllerMode : jint
{
	Phone,
	Cf20,
	Cf20Waiting,
	PhoneFallback,
};

constexpr char vertex_shader[] = R"(#version 300 es
out vec2 uv;
void main() {
    vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    uv = vec2(corner.x, 1.0 - corner.y);
    gl_Position = vec4(2.0 * corner - 1.0, 0.0, 1.0);
}
)";

constexpr char fragment_shader[] = R"(#version 300 es
precision highp float;
in vec2 uv;
uniform sampler2D panel;
uniform vec3 phoneAim;
uniform vec3 controllerAim;
uniform vec2 panelSize;
uniform vec2 canvasSize;
out vec4 color;
float pointerOpacity(vec2 position, vec2 cursor, vec2 origin) {
    vec2 ray = cursor - origin;
    float along = clamp(dot(position - origin, ray) / max(dot(ray, ray), 0.0001), 0.0, 1.0);
    float rayDistance = length(position - origin - along * ray);
    float rayOpacity = (1.0 - smoothstep(0.7, 2.5, rayDistance)) * along * 0.32;
    float cursorDistance = length(position - cursor);
    float ring = 1.0 - smoothstep(1.7, 3.2, abs(cursorDistance - 9.0));
    float centerDot = 1.0 - smoothstep(2.2, 3.5, cursorDistance);
    return max(rayOpacity, max(ring, centerDot));
}
void main() {
    vec2 position = (uv - 0.5) * canvasSize + 0.5 * panelSize;
    vec2 panelUv = position / panelSize;
    vec4 background = vec4(0.0);
    if (all(greaterThanEqual(panelUv, vec2(0.0))) && all(lessThan(panelUv, vec2(1.0)))) {
        background = vec4(texture(panel, panelUv).rgb, 1.0);
    }
    color = background;
    if (controllerAim.z > 0.0) {
        float opacity = pointerOpacity(position, controllerAim.xy * panelSize, vec2(1.12, 1.18) * panelSize);
        color = vec4(vec3(1.0, 0.77, 0.27) * opacity, opacity) + color * (1.0 - opacity);
    } else if (phoneAim.z > 0.0) {
        float opacity = pointerOpacity(position, phoneAim.xy * panelSize, vec2(0.76, 1.32) * panelSize);
        color = vec4(vec3(0.34, 1.0, 0.75) * opacity, opacity) + color * (1.0 - opacity);
    }
}
)";

constexpr char capture_shader[] = R"(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 uv;
uniform samplerExternalOES panel;
uniform mat4 textureTransform;
uniform vec4 sourceRect;
out vec4 color;
void main() {
    vec2 inputUv = sourceRect.xy + vec2(uv.x, 1.0 - uv.y) * sourceRect.zw;
    vec2 source = (textureTransform * vec4(inputUv, 0.0, 1.0)).xy;
    color = vec4(texture(panel, source).rgb, 1.0);
}
)";

void
check(XrResult result, const char *operation)
{
	if (XR_FAILED(result)) {
		throw std::runtime_error(std::string(operation) + " failed with OpenXR result " +
		                         std::to_string(result));
	}
}

void
check_egl(EGLBoolean success, const char *operation)
{
	if (!success) {
		throw std::runtime_error(std::string(operation) + " failed with EGL error " +
		                         std::to_string(eglGetError()));
	}
}

XrQuaternionf
multiply(XrQuaternionf a, XrQuaternionf b)
{
	return {a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
	        a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
	        a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
	        a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z};
}

XrQuaternionf
inverse_rotation(XrQuaternionf q)
{
	return {-q.x, -q.y, -q.z, q.w};
}

XrQuaternionf
normalize(XrQuaternionf q)
{
	const float norm = std::sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w);
	return {q.x / norm, q.y / norm, q.z / norm, q.w / norm};
}

bool
rotation_near(XrQuaternionf a, XrQuaternionf b, float angle_degrees)
{
	const float sign = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w < 0.0f ? -1.0f : 1.0f;
	const float dx = a.x - sign * b.x;
	const float dy = a.y - sign * b.y;
	const float dz = a.z - sign * b.z;
	const float dw = a.w - sign * b.w;
	const float distance = 2.0f * std::sin(angle_degrees * 0.00436332313f);
	return dx * dx + dy * dy + dz * dz + dw * dw <= distance * distance;
}

GLuint
compile_shader(GLenum type, const char *source)
{
	const GLuint shader = glCreateShader(type);
	glShaderSource(shader, 1, &source, nullptr);
	glCompileShader(shader);
	GLint compiled = GL_FALSE;
	glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
	if (compiled != GL_TRUE) {
		char message[512] = {};
		glGetShaderInfoLog(shader, sizeof(message), nullptr, message);
		glDeleteShader(shader);
		throw std::runtime_error(std::string("Launcher shader compilation failed: ") + message);
	}
	return shader;
}

class JavaState
{
public:
	JavaState(JNIEnv *env, jobject state) : env(env), state(state)
	{
		jclass cls = env->GetObjectClass(state);
		stop_method = env->GetMethodID(cls, "shouldStop", "()Z");
		ready_method = env->GetMethodID(cls, "updateReady", "(Z)V");
		panel_method = env->GetMethodID(cls, "takePanel", "()Landroid/graphics/Bitmap;");
		aim_method = env->GetMethodID(cls, "aimBits", "()J");
		recenter_method = env->GetMethodID(cls, "recenterGeneration", "()I");
		controller_click_method = env->GetMethodID(cls, "controllerClick", "(FF)V");
		controller_back_method = env->GetMethodID(cls, "controllerBack", "()V");
		controller_mode_method = env->GetMethodID(cls, "controllerMode", "()I");
		env->DeleteLocalRef(cls);
	}

	bool
	should_stop() const
	{
		return env->CallBooleanMethod(state, stop_method);
	}

	void
	set_ready(bool ready) const
	{
		env->CallVoidMethod(state, ready_method, static_cast<jboolean>(ready));
	}

	jobject
	take_panel() const
	{
		return env->CallObjectMethod(state, panel_method);
	}

	void
	read_aim(float &x, float &y) const
	{
		const auto bits = static_cast<uint64_t>(env->CallLongMethod(state, aim_method));
		const uint32_t x_bits = static_cast<uint32_t>(bits >> 32);
		const uint32_t y_bits = static_cast<uint32_t>(bits);
		std::memcpy(&x, &x_bits, sizeof(x));
		std::memcpy(&y, &y_bits, sizeof(y));
	}

	jint
	recenter_generation() const
	{
		return env->CallIntMethod(state, recenter_method);
	}

	void
	controller_click(float x, float y) const
	{
		env->CallVoidMethod(state, controller_click_method, static_cast<jfloat>(x), static_cast<jfloat>(y));
	}

	void
	controller_back() const
	{
		env->CallVoidMethod(state, controller_back_method);
	}

	ControllerMode
	controller_mode() const
	{
		return static_cast<ControllerMode>(env->CallIntMethod(state, controller_mode_method));
	}

	JNIEnv *const env;

private:
	const jobject state;
	jmethodID stop_method = nullptr;
	jmethodID ready_method = nullptr;
	jmethodID panel_method = nullptr;
	jmethodID aim_method = nullptr;
	jmethodID recenter_method = nullptr;
	jmethodID controller_click_method = nullptr;
	jmethodID controller_back_method = nullptr;
	jmethodID controller_mode_method = nullptr;
};

class AndroidStream
{
public:
	struct Frame
	{
		int64_t timestamp = 0;
		bool pending_fence = false;
		int64_t fence_wait_ns = 0;
	};

	bool
	initialize(JNIEnv *env, XrInstance instance, uint32_t width, uint32_t height, jobject &surface)
	{
		get_buffer_id = reinterpret_cast<GetBufferId>(dlsym(RTLD_DEFAULT, "AHardwareBuffer_getId"));
		PFN_xrVoidFunction import = nullptr;
		PFN_xrVoidFunction poll_image = nullptr;
		if (get_buffer_id == nullptr ||
		    XR_FAILED(xrGetInstanceProcAddr(instance, "xrCv10ImportAndroidStreamImageMONADO", &import)) ||
		    XR_FAILED(xrGetInstanceProcAddr(instance, "xrCv10PollAndroidStreamImageMONADO", &poll_image)) ||
		    import == nullptr || poll_image == nullptr) {
			return false;
		}
		import_image = reinterpret_cast<PFN_xrCv10ImportAndroidStreamImageMONADO>(import);
		poll_swapchain = reinterpret_cast<PFN_xrCv10PollAndroidStreamImageMONADO>(poll_image);
		this->width = static_cast<int32_t>(width);
		this->height = static_cast<int32_t>(height);
		char fence_setting[PROP_VALUE_MAX]{};
		trace_fences = __system_property_get("debug.cv10.pocket_fence_trace", fence_setting) > 0 &&
		               std::strcmp(fence_setting, "1") == 0;
		wait_for_pending_fence = __system_property_get("debug.cv10.pocket_fence_wait", fence_setting) > 0 &&
		                         std::strcmp(fence_setting, "1") == 0;
		const uint64_t usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
		ANativeWindow *window = nullptr;
		if (AImageReader_newWithUsage(this->width, this->height, AIMAGE_FORMAT_RGBA_8888, usage,
		                             max_images, &reader) != AMEDIA_OK ||
		    AImageReader_getWindow(reader, &window) != AMEDIA_OK) {
			return false;
		}
		surface = ANativeWindow_toSurface(env, window);
		return surface != nullptr;
	}

	~AndroidStream()
	{
		// Launcher destroys the OpenXR session synchronously before releasing any held producer image.
		pending.discard();
		ready.discard();
		for (const auto &slot : slots) {
			if (slot.image != nullptr) {
				AImage_delete(slot.image);
			}
		}
		if (reader != nullptr) {
			AImageReader_delete(reader);
		}
	}

	Frame
	update(XrSession session)
	{
		constexpr int64_t wait_budget_ns = 500'000;
		const int64_t wait_deadline_ns = wait_for_pending_fence ? monotonic_ns() + wait_budget_ns : 0;
		int64_t fence_wait_ns = 0;
		uint32_t held = 0;
		for (auto &slot : slots) {
			if (slot.image != nullptr && &slot != current && poll_swapchain(slot.swapchain) == XR_SUCCESS) {
				AImage_delete(slot.image);
				slot.image = nullptr;
			}
			held += slot.image != nullptr;
		}
		promote_ready();
		// Keep the newest completed image even if the following producer frame is still on the GPU.
		// Acquire each image individually so that dropping a frame preserves its matching fence.
		for (uint32_t attempt = 0; attempt < max_images; ++attempt) {
			const uint32_t acquired = held + (pending.image != nullptr) + (ready.image != nullptr);
			if (acquired >= max_images) {
				break;
			}
			PendingImage next{};
			const media_status_t result = AImageReader_acquireNextImageAsync(reader, &next.image, &next.fence);
			if (result == AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE || result == AMEDIA_IMGREADER_MAX_IMAGES_ACQUIRED) {
				break;
			}
			if (result != AMEDIA_OK) {
				throw std::runtime_error("Cannot acquire a Pocket XR hardware buffer: " + std::to_string(result));
			}
			if (trace_fences) {
				next.acquired_ns = monotonic_ns();
			}
			pending.discard();
			pending = next;
			promote_ready();
		}
		promote_ready();
		if (wait_for_pending_fence && current != nullptr && ready.image == nullptr && pending.fence >= 0) {
			// Only spend the remaining read budget when reusing the last image is the alternative.
			const int64_t wait_start_ns = monotonic_ns();
			if (wait_start_ns < wait_deadline_ns) {
				promote_ready(wait_deadline_ns - wait_start_ns);
				fence_wait_ns = monotonic_ns() - wait_start_ns;
			}
		}
		if (ready.image == nullptr) {
			return {timestamp, pending.image != nullptr, fence_wait_ns};
		}

		AImageCropRect crop{};
		AHardwareBuffer *buffer = nullptr;
		int64_t stamp = 0;
		uint64_t id = 0;
		if (AImage_getCropRect(ready.image, &crop) != AMEDIA_OK || crop.left != 0 || crop.top != 0 ||
		    crop.right != width || crop.bottom != height || AImage_getTimestamp(ready.image, &stamp) != AMEDIA_OK ||
		    AImage_getHardwareBuffer(ready.image, &buffer) != AMEDIA_OK) {
			throw std::runtime_error("Unexpected crop or metadata in the Pocket XR stream image");
		}
		if (get_buffer_id(buffer, &id) != 0 || id == 0) {
			throw std::runtime_error("Cannot identify the Pocket XR hardware buffer");
		}

		auto match = std::find_if(slots.begin(), slots.end(), [id](const auto &slot) { return slot.id == id; });
		if (match == slots.end()) {
			match = std::find_if(slots.begin(), slots.end(), [](const auto &slot) { return slot.id == 0; });
			if (match == slots.end()) {
				match = std::find_if(slots.begin(), slots.end(), [](const auto &slot) { return slot.image == nullptr; });
			}
			if (match == slots.end()) {
				throw std::runtime_error("Pocket XR hardware-buffer cache exhausted");
			}
			if (match->swapchain != XR_NULL_HANDLE) {
				check(xrDestroySwapchain(match->swapchain), "xrDestroySwapchain(stream)");
				*match = {};
			}
			check(import_image(session, buffer, &match->swapchain), "xrCv10ImportAndroidStreamImageMONADO");
			match->id = id;
			__android_log_print(ANDROID_LOG_INFO, tag, "CV10_STREAM import buffer=%" PRIu64 " %dx%d", id,
			                    width, height);
		}
		if (match->image != nullptr) {
			throw std::runtime_error("Pocket XR reused a buffer before release");
		}
		match->image = ready.image;
		ready = {};
		current = &*match;
		timestamp = stamp;
		return {timestamp, pending.image != nullptr, fence_wait_ns};
	}

	XrSwapchain
	swapchain() const
	{
		return current != nullptr ? current->swapchain : XR_NULL_HANDLE;
	}

private:
	struct PendingImage
	{
		AImage *image = nullptr;
		int fence = -1;
		int64_t acquired_ns = 0;

		void
		discard()
		{
			if (image != nullptr) {
				AImage_deleteAsync(image, fence);
				*this = {};
			}
		}
	};

	void
	trace_ready(const PendingImage &image) const
	{
		const int64_t ready_ns = monotonic_ns();
		uint64_t signal_ns = 0;
		if (image.fence >= 0) {
			// A merged sync file is ready only after its last child fence signals.
			std::array<sync_fence_info, 8> children{};
			sync_file_info info{};
			if (ioctl(image.fence, SYNC_IOC_FILE_INFO, &info) == 0 && info.num_fences > 0 &&
			    info.num_fences <= children.size()) {
				info.sync_fence_info = reinterpret_cast<uintptr_t>(children.data());
				if (ioctl(image.fence, SYNC_IOC_FILE_INFO, &info) == 0 && info.status == 1) {
					for (uint32_t i = 0; i < info.num_fences; ++i) {
						signal_ns = std::max(signal_ns, static_cast<uint64_t>(children[i].timestamp_ns));
					}
				}
			}
		}
		int64_t stamp = 0;
		if (AImage_getTimestamp(image.image, &stamp) == AMEDIA_OK) {
			__android_log_print(ANDROID_LOG_INFO, tag,
			                    "CV10_ACQUIRE stamp=%" PRId64 " acquired=%" PRId64 " observed=%" PRId64
			                    " signal=%" PRIu64 " fence=%d",
			                    stamp, image.acquired_ns, ready_ns, signal_ns, image.fence >= 0);
		}
	}

	void
	promote_ready(int64_t timeout_ns = 0)
	{
		if (pending.image == nullptr) {
			return;
		}
		if (pending.fence >= 0) {
			pollfd fd{pending.fence, POLLIN, 0};
			const timespec timeout{0, static_cast<long>(timeout_ns)};
			const int result = timeout_ns == 0 ? poll(&fd, 1, 0) : ppoll(&fd, 1, &timeout, nullptr);
			if (result == 0 || (result < 0 && errno == EINTR)) {
				return;
			}
			if (result < 0 || (fd.revents & POLLIN) == 0) {
				throw std::runtime_error("Pocket XR acquire fence failed");
			}
		}
		if (trace_fences) {
			trace_ready(pending);
		}
		if (pending.fence >= 0) {
			close(pending.fence);
			pending.fence = -1;
		}
		ready.discard();
		ready = pending;
		pending = {};
	}

	using GetBufferId = int (*)(const AHardwareBuffer *, uint64_t *);
	GetBufferId get_buffer_id = nullptr;

	struct Slot
	{
		uint64_t id = 0;
		XrSwapchain swapchain = XR_NULL_HANDLE;
		AImage *image = nullptr;
	};
	static constexpr uint32_t max_images = 5;
	std::array<Slot, max_images + 1> slots{};
	AImageReader *reader = nullptr;
	PendingImage pending{};
	PendingImage ready{};
	Slot *current = nullptr;
	PFN_xrCv10ImportAndroidStreamImageMONADO import_image = nullptr;
	PFN_xrCv10PollAndroidStreamImageMONADO poll_swapchain = nullptr;
	int64_t timestamp = 0;
	int32_t width = 0;
	int32_t height = 0;
	bool trace_fences = false;
	bool wait_for_pending_fence = false;
};

class DisplayCapture
{
public:
	struct FramePose
	{
		jlong time_ns = 0;
		jlong epoch = 0;
		XrQuaternionf center{};
		XrQuaternionf top{};
		XrQuaternionf bottom{};
		XrQuaternionf reference{};
	};

	DisplayCapture(JNIEnv *env, jobject capture) : env(env), capture(capture)
	{
		if (capture == nullptr) {
			return;
		}
		jclass cls = env->GetObjectClass(capture);
		start_method = env->GetMethodID(cls, "startCapture", "(III)Z");
		start_stream_method = env->GetMethodID(cls, "startStreamCapture", "(Landroid/view/Surface;I)Z");
		update_method = env->GetMethodID(cls, "updateCapture", "([F[J[F)J");
		latch_method = env->GetMethodID(cls, "latchCaptureTimestamp", "(J[J[F)J");
		stop_method = env->GetMethodID(cls, "stopCapture", "()V");
		env->DeleteLocalRef(cls);
		matrix = env->NewFloatArray(16);
	}

	~DisplayCapture()
	{
		stop();
		if (matrix != nullptr) {
			env->DeleteLocalRef(matrix);
		}
		if (orientation_array != nullptr) {
			env->DeleteLocalRef(orientation_array);
			env->DeleteLocalRef(time_array);
		}
	}

	void
	enable_frame_poses()
	{
		time_array = env->NewLongArray(2);
		orientation_array = env->NewFloatArray(16);
	}

	const FramePose &
	frame_pose() const
	{
		return pose;
	}

	void
	stop()
	{
		if (started) {
			env->CallVoidMethod(capture, stop_method);
			started = false;
		}
	}

	explicit operator bool() const { return capture != nullptr; }

	void
	start(GLuint texture, uint32_t width, uint32_t height)
	{
		started = true;
		if (!env->CallBooleanMethod(capture, start_method, static_cast<jint>(texture),
		                            static_cast<jint>(width), static_cast<jint>(height))) {
			throw std::runtime_error("Cannot create the Pocket XR capture surface");
		}
	}

	bool
	start_stream(AndroidStream &stream, XrInstance instance, uint32_t width, uint32_t height, jint focus_eye_size)
	{
		jobject surface = nullptr;
		if (!stream.initialize(env, instance, width, height, surface)) {
			return false;
		}
		started = env->CallBooleanMethod(capture, start_stream_method, surface, focus_eye_size);
		env->DeleteLocalRef(surface);
		return started;
	}

	jlong
	update_stream(int64_t timestamp)
	{
		if (timestamp != 0 && timestamp != latched_timestamp) {
			env->CallLongMethod(capture, latch_method, static_cast<jlong>(timestamp), time_array, orientation_array);
			latch_pose(timestamp);
		}
		return timestamp;
	}

	jlong
	update(GLint transform_uniform)
	{
		const auto timestamp = env->CallLongMethod(capture, update_method, matrix, time_array, orientation_array);
		if (timestamp != 0 && timestamp != latched_timestamp) {
			std::array<jfloat, 16> transform{};
			env->GetFloatArrayRegion(matrix, 0, transform.size(), transform.data());
			glUniformMatrix4fv(transform_uniform, 1, GL_FALSE, transform.data());
			latch_pose(timestamp);
		}
		return timestamp;
	}

private:
	void
	latch_pose(jlong timestamp)
	{
		latched_timestamp = timestamp;
		if (time_array == nullptr) {
			return;
		}
		std::array<jlong, 2> time{};
		env->GetLongArrayRegion(time_array, 0, time.size(), time.data());
		pose.time_ns = time[0];
		pose.epoch = time[1];
		if (pose.time_ns != 0) {
			std::array<jfloat, 16> q{};
			env->GetFloatArrayRegion(orientation_array, 0, q.size(), q.data());
			pose.center = normalize({q[0], q[1], q[2], q[3]});
			pose.top = normalize({q[4], q[5], q[6], q[7]});
			pose.bottom = normalize({q[8], q[9], q[10], q[11]});
			pose.reference = normalize({q[12], q[13], q[14], q[15]});
		}
	}

	JNIEnv *const env;
	const jobject capture;
	jmethodID start_method = nullptr;
	jmethodID start_stream_method = nullptr;
	jmethodID update_method = nullptr;
	jmethodID latch_method = nullptr;
	jmethodID stop_method = nullptr;
	jfloatArray matrix = nullptr;
	jlongArray time_array = nullptr;
	jfloatArray orientation_array = nullptr;
	FramePose pose{};
	jlong latched_timestamp = 0;
	bool started = false;
};

class Launcher
{
public:
	Launcher(JavaState &state,
	         DisplayCapture &capture,
	         bool capture_world_anchored,
	         bool capture_wide_canvas,
	         uint32_t optical_eye_size,
	         bool direct_capture,
	         bool producer_quad_supported)
	    : state(state), capture(capture), capture_world_anchored(capture_world_anchored),
	      capture_cv10_optics(optical_eye_size != 0), capture_width(capture_cv10_optics ? 2 * optical_eye_size : pocket_width),
	      capture_height(capture_cv10_optics ? optical_eye_size : pocket_height),
	      capture_size(capture_wide_canvas ? pocket_wide_size : pocket_size), direct_capture(direct_capture),
	      producer_quad_supported(producer_quad_supported)
	{
		if (capture_cv10_optics) {
			__android_log_print(ANDROID_LOG_INFO, tag, "CV10 stereo projection %u x %u, 95 x 95 degrees per eye",
			                    capture_width, capture_height);
		} else if (capture) {
			__android_log_print(ANDROID_LOG_INFO, tag, "Stereo canvas %.3f x %.3f m at %.1f m (anchored: %s)",
			                    capture_size.width, capture_size.height, pocket_distance,
			                    capture_world_anchored ? "yes" : "no");
		}
	}

	~Launcher()
	{
		if (convert_from_monotonic != nullptr) {
			__android_log_print(ANDROID_LOG_INFO, tag, "Pocket XR late projection: %u corrected, %u unchanged",
			                    warped_frames, unwarped_frames);
		}
		state.set_ready(false);
		if (capture_cv10_optics && copied_frames != 0) {
			__android_log_print(ANDROID_LOG_DEBUG, tag, "Pocket XR optical capture: %u copied, %u reused",
			                    copied_frames, reused_frames);
		}
		if (using_android_stream) {
			__android_log_print(ANDROID_LOG_INFO, tag, "CV10_STREAM submitted %u new, %u reused, %u copied",
			                    streamed_frames, reused_frames, copied_frames);
		}
		capture.stop();
		if (swapchain != XR_NULL_HANDLE) {
			xrDestroySwapchain(swapchain);
		}
		if (view_space != XR_NULL_HANDLE) {
			xrDestroySpace(view_space);
		}
		if (controller_space != XR_NULL_HANDLE) {
			xrDestroySpace(controller_space);
		}
		if (local_space != XR_NULL_HANDLE) {
			xrDestroySpace(local_space);
		}
		if (session != XR_NULL_HANDLE) {
			xrDestroySession(session);
		}
		if (controller_actions != XR_NULL_HANDLE) {
			xrDestroyActionSet(controller_actions);
		}
		if (instance != XR_NULL_HANDLE) {
			xrDestroyInstance(instance);
		}
		if (display != EGL_NO_DISPLAY) {
			if (eglGetCurrentContext() == context && context != EGL_NO_CONTEXT) {
				glDeleteFramebuffers(1, &framebuffer);
				glDeleteTextures(1, &panel_texture);
				glDeleteProgram(program);
				eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
			}
			if (surface != EGL_NO_SURFACE) {
				eglDestroySurface(display, surface);
			}
			if (context != EGL_NO_CONTEXT) {
				eglDestroyContext(display, context);
			}
			eglTerminate(display);
			eglReleaseThread();
		}
	}

	void
	run(jobject activity, jobject application_context, const std::string &runtime_manifest, bool use_bundled_runtime)
	{
		initialize_xr(activity, application_context, runtime_manifest, use_bundled_runtime);
		initialize_egl();
		initialize_session();
		if (!using_android_stream) {
			initialize_drawing();
		}
		while (!state.should_stop() && consume_events()) {
			if (running) {
				draw_frame();
			} else {
				std::this_thread::sleep_for(std::chrono::milliseconds(10));
			}
		}
	}

private:
	void
	initialize_xr(jobject activity,
	              jobject application_context,
	              const std::string &runtime_manifest,
	              bool use_bundled_runtime)
	{
		JavaVM *vm = nullptr;
		if (state.env->GetJavaVM(&vm) != JNI_OK) {
			throw std::runtime_error("Cannot obtain the Android Java VM for OpenXR");
		}
		PFN_xrVoidFunction function = nullptr;
		check(xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR", &function),
		      "xrGetInstanceProcAddr(xrInitializeLoaderKHR)");
		const auto initialize_loader = reinterpret_cast<PFN_xrInitializeLoaderKHR>(function);
		// The CV10 headset needs its compositor's per-eye panel orientation.
		// Clear any previous override when using the system-selected runtime.
		if (use_bundled_runtime && runtime_manifest.empty()) {
			throw std::runtime_error("Cannot prepare the bundled Monado CV10 runtime");
		}
		XrLoaderInitPropertyValueEXT runtime_property{
		    "XR_RUNTIME_JSON", use_bundled_runtime ? runtime_manifest.c_str() : ""};
		XrLoaderInitInfoPropertiesEXT loader_properties{XR_TYPE_LOADER_INIT_INFO_PROPERTIES_EXT};
		loader_properties.propertyValueCount = 1;
		loader_properties.propertyValues = &runtime_property;
		XrLoaderInitInfoAndroidKHR loader_info{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
		loader_info.next = &loader_properties;
		loader_info.applicationVM = vm;
		loader_info.applicationContext = application_context;
		const auto *loader_header = reinterpret_cast<const XrLoaderInitInfoBaseHeaderKHR *>(&loader_info);
		check(initialize_loader(loader_header), "xrInitializeLoaderKHR");

		XrInstanceCreateInfoAndroidKHR android_info{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
		android_info.applicationVM = vm;
		android_info.applicationActivity = activity;
		std::vector<const char *> extensions{XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,
		                                     XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME};
		char trace_setting[PROP_VALUE_MAX]{};
		const bool request_trace = capture_cv10_optics && use_bundled_runtime &&
		                           __system_property_get("debug.xrt.CV10_PRESENT_TRACE", trace_setting) > 0 &&
		                           std::strcmp(trace_setting, "true") == 0;
		trace_late_projection = request_trace;
		char warp_setting[PROP_VALUE_MAX]{};
		const bool request_warp = capture_cv10_optics && use_bundled_runtime &&
		                          __system_property_get("debug.cv10.pocket_atw", warp_setting) > 0 &&
		                          std::strcmp(warp_setting, "1") == 0;
		if (request_trace || request_warp) {
			uint32_t count = 0;
			if (XR_SUCCEEDED(xrEnumerateInstanceExtensionProperties(nullptr, 0, &count, nullptr))) {
				std::vector<XrExtensionProperties> available(count, {XR_TYPE_EXTENSION_PROPERTIES});
				if (XR_SUCCEEDED(xrEnumerateInstanceExtensionProperties(nullptr, count, &count,
				                                                        available.data()))) {
					for (const auto &extension : available) {
						if (std::strcmp(extension.extensionName,
						                XR_KHR_CONVERT_TIMESPEC_TIME_EXTENSION_NAME) == 0) {
							extensions.push_back(XR_KHR_CONVERT_TIMESPEC_TIME_EXTENSION_NAME);
							break;
						}
					}
				}
			}
		}
		XrInstanceCreateInfo instance_info{XR_TYPE_INSTANCE_CREATE_INFO};
		instance_info.next = &android_info;
		std::snprintf(instance_info.applicationInfo.applicationName,
		              sizeof(instance_info.applicationInfo.applicationName), "CV10 Apps");
		char quad_setting[PROP_VALUE_MAX]{};
		const bool request_quad = capture_cv10_optics &&
		                          __system_property_get("debug.cv10.pocket_quad", quad_setting) > 0 &&
		                          std::strcmp(quad_setting, "1") == 0;
		instance_info.applicationInfo.apiVersion = request_quad ? XR_MAKE_VERSION(1, 1, 0) : XR_MAKE_VERSION(1, 0, 0);
		instance_info.enabledExtensionCount = extensions.size();
		instance_info.enabledExtensionNames = extensions.data();
		XrResult result = xrCreateInstance(&instance_info, &instance);
		if (!use_bundled_runtime && result == XR_ERROR_RUNTIME_UNAVAILABLE && !runtime_manifest.empty()) {
			__android_log_print(ANDROID_LOG_INFO, tag, "OpenXR runtime unavailable, trying bundled Monado CV10");
			runtime_property.value = runtime_manifest.c_str();
			check(initialize_loader(loader_header), "xrInitializeLoaderKHR(bundled Monado CV10)");
			result = xrCreateInstance(&instance_info, &instance);
		}
		if (request_quad && result == XR_ERROR_API_VERSION_UNSUPPORTED) {
			instance_info.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 0);
			result = xrCreateInstance(&instance_info, &instance);
		}
		check(result, "xrCreateInstance");
		if (extensions.size() > 2 && request_trace) {
			PFN_xrVoidFunction convert = nullptr;
			if (XR_SUCCEEDED(xrGetInstanceProcAddr(instance, "xrConvertTimeToTimespecTimeKHR", &convert))) {
				convert_time = reinterpret_cast<PFN_xrConvertTimeToTimespecTimeKHR>(convert);
			}
		}
		if (extensions.size() > 2 && request_warp) {
			PFN_xrVoidFunction convert = nullptr;
			if (XR_SUCCEEDED(xrGetInstanceProcAddr(instance, "xrConvertTimespecTimeToTimeKHR", &convert))) {
				convert_from_monotonic = reinterpret_cast<PFN_xrConvertTimespecTimeToTimeKHR>(convert);
				capture.enable_frame_poses();
			}
		}

		XrInstanceProperties properties{XR_TYPE_INSTANCE_PROPERTIES};
		check(xrGetInstanceProperties(instance, &properties), "xrGetInstanceProperties");
		__android_log_print(ANDROID_LOG_INFO, tag, "Using OpenXR runtime %s%s", properties.runtimeName,
		                    use_bundled_runtime ? " (bundled Monado CV10)" : "");

		XrSystemGetInfo system_info{XR_TYPE_SYSTEM_GET_INFO};
		system_info.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
		check(xrGetSystem(instance, &system_info, &system_id), "xrGetSystem");

		if (request_quad) {
			uint32_t count = 0;
			check(xrEnumerateViewConfigurations(instance, system_id, 0, &count, nullptr),
			      "xrEnumerateViewConfigurations");
			std::vector<XrViewConfigurationType> configurations(count);
			check(xrEnumerateViewConfigurations(instance, system_id, count, &count, configurations.data()),
			      "xrEnumerateViewConfigurations");
			constexpr auto quad = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO_WITH_FOVEATED_INSET;
			if (std::find(configurations.begin(), configurations.end(), quad) != configurations.end()) {
				view_configuration = quad;
				projection_count = 4;
			} else {
				__android_log_print(ANDROID_LOG_INFO, tag, "CV10 four views unavailable; using stereo");
			}
		}
		uint32_t blend_count = 0;
		check(xrEnumerateEnvironmentBlendModes(instance, system_id, view_configuration,
		                                       0, &blend_count, nullptr),
		      "xrEnumerateEnvironmentBlendModes");
		std::vector<XrEnvironmentBlendMode> modes(blend_count);
		check(xrEnumerateEnvironmentBlendModes(instance, system_id, view_configuration,
		                                       blend_count, &blend_count, modes.data()),
		      "xrEnumerateEnvironmentBlendModes");
		if (modes.empty()) {
			throw std::runtime_error("Runtime has no stereo environment blend mode");
		}
		if (std::find(modes.begin(), modes.end(), blend_mode) == modes.end()) {
			blend_mode = modes.front();
		}
	}

	void
	initialize_egl()
	{
		display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
		if (display == EGL_NO_DISPLAY) {
			throw std::runtime_error("EGL default display is unavailable");
		}
		check_egl(eglInitialize(display, nullptr, nullptr), "eglInitialize");
		check_egl(eglBindAPI(EGL_OPENGL_ES_API), "eglBindAPI");
		const EGLint config_attributes[] = {EGL_SURFACE_TYPE,
		                                    EGL_PBUFFER_BIT,
		                                    EGL_RENDERABLE_TYPE,
		                                    EGL_OPENGL_ES3_BIT,
		                                    EGL_RED_SIZE,
		                                    8,
		                                    EGL_GREEN_SIZE,
		                                    8,
		                                    EGL_BLUE_SIZE,
		                                    8,
		                                    EGL_ALPHA_SIZE,
		                                    8,
		                                    EGL_NONE};
		EGLint config_count = 0;
		check_egl(eglChooseConfig(display, config_attributes, &config, 1, &config_count), "eglChooseConfig");
		if (config_count == 0) {
			throw std::runtime_error("EGL has no OpenGL ES 3 pbuffer configuration");
		}
		const EGLint context_attributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
		context = eglCreateContext(display, config, EGL_NO_CONTEXT, context_attributes);
		if (context == EGL_NO_CONTEXT) {
			throw std::runtime_error("Cannot create the OpenGL ES 3 context");
		}
		const EGLint surface_attributes[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
		surface = eglCreatePbufferSurface(display, config, surface_attributes);
		if (surface == EGL_NO_SURFACE) {
			throw std::runtime_error("Cannot create the EGL pbuffer");
		}
		check_egl(eglMakeCurrent(display, surface, surface, context), "eglMakeCurrent");
	}

	void
	set_projection_rects(int32_t producer_focus_size)
	{
		const auto eye_size = static_cast<int32_t>(capture_height);
		const auto context_size = projection_count == 4 ? std::min(eye_size, cv10_context_eye_limit) : eye_size;
		const auto focus_size = producer_focus_size > 0
		                            ? producer_focus_size
		                            : static_cast<int32_t>(std::lround(eye_size * cv10_focus_scale));
		for (int32_t eye = 0; eye < 2; ++eye) {
			projection_rects[eye] = {{eye * context_size, 0}, {context_size, context_size}};
			projection_rects[eye + 2] = {
			    {eye * context_size + (context_size - focus_size) / 2, context_size}, {focus_size, focus_size}};
		}
	}

	void
	initialize_session()
	{
		PFN_xrVoidFunction function = nullptr;
		check(xrGetInstanceProcAddr(instance, "xrGetOpenGLESGraphicsRequirementsKHR", &function),
		      "xrGetInstanceProcAddr(xrGetOpenGLESGraphicsRequirementsKHR)");
		XrGraphicsRequirementsOpenGLESKHR requirements{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
		check(reinterpret_cast<PFN_xrGetOpenGLESGraphicsRequirementsKHR>(function)(instance, system_id,
		                                                                           &requirements),
		      "xrGetOpenGLESGraphicsRequirementsKHR");
		GLint major_version = 0;
		GLint minor_version = 0;
		glGetIntegerv(GL_MAJOR_VERSION, &major_version);
		glGetIntegerv(GL_MINOR_VERSION, &minor_version);
		if (XR_MAKE_VERSION(major_version, minor_version, 0) < requirements.minApiVersionSupported) {
			throw std::runtime_error("OpenGL ES version is below the runtime requirement");
		}

		XrGraphicsBindingOpenGLESAndroidKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
		binding.display = display;
		binding.config = config;
		binding.context = context;
		XrSessionCreateInfo session_info{XR_TYPE_SESSION_CREATE_INFO};
		session_info.next = &binding;
		session_info.systemId = system_id;
		check(xrCreateSession(instance, &session_info, &session), "xrCreateSession");

		XrReferenceSpaceCreateInfo space_info{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
		space_info.poseInReferenceSpace.orientation.w = 1.0f;
		space_info.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
		check(xrCreateReferenceSpace(session, &space_info, &local_space), "xrCreateReferenceSpace(LOCAL)");
		space_info.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW;
		check(xrCreateReferenceSpace(session, &space_info, &view_space), "xrCreateReferenceSpace(VIEW)");

		if (!capture) {
			try {
				initialize_controller();
			} catch (const std::runtime_error &error) {
				__android_log_print(ANDROID_LOG_WARN, tag, "Controller actions unavailable: %s", error.what());
			}
		}

		char stream_setting[PROP_VALUE_MAX]{};
		char atlas_setting[PROP_VALUE_MAX]{};
		const bool stream_atlas = projection_count == 4 && producer_quad_supported &&
		                          __system_property_get("debug.cv10.pocket_quad_atlas", atlas_setting) > 0 &&
		                          std::strcmp(atlas_setting, "1") == 0;
		if (direct_capture && capture_cv10_optics && (projection_count == 2 || stream_atlas) &&
		    __system_property_get("debug.cv10.pocket_zero_copy", stream_setting) > 0 &&
		    std::strcmp(stream_setting, "1") == 0) {
			const uint32_t stream_width = stream_atlas ? 2 * cv10_context_eye_limit : capture_width;
			const uint32_t stream_height =
			    stream_atlas ? cv10_context_eye_limit + cv10_default_focus_eye_size : capture_height;
			const int32_t focus_eye_size = stream_atlas ? cv10_default_focus_eye_size : 0;
			using_android_stream =
			    capture.start_stream(android_stream, instance, stream_width, stream_height, focus_eye_size);
			__android_log_print(ANDROID_LOG_INFO, tag, "CV10_STREAM %s",
			                    using_android_stream ? "enabled" : "unavailable, using GLES copy");
			if (using_android_stream) {
				set_projection_rects(focus_eye_size);
				if (stream_atlas) {
					__android_log_print(ANDROID_LOG_INFO, tag,
					                    "CV10 producer four-view atlas %ux%u: context %d, center %d per eye",
					                    stream_width, stream_height, cv10_context_eye_limit, focus_eye_size);
				}
				return;
			}
		}
		if (capture_cv10_optics) {
			set_projection_rects(0);
		}

		uint32_t format_count = 0;
		check(xrEnumerateSwapchainFormats(session, 0, &format_count, nullptr), "xrEnumerateSwapchainFormats");
		std::vector<int64_t> formats(format_count);
		check(xrEnumerateSwapchainFormats(session, format_count, &format_count, formats.data()),
		      "xrEnumerateSwapchainFormats");
		if (std::find(formats.begin(), formats.end(), GL_SRGB8_ALPHA8) != formats.end()) {
			panel_format = GL_SRGB8_ALPHA8;
		} else if (std::find(formats.begin(), formats.end(), GL_RGBA8) == formats.end()) {
			throw std::runtime_error("Runtime does not provide an RGBA8 OpenGL ES swapchain format");
		}

		XrSwapchainCreateInfo chain_info{XR_TYPE_SWAPCHAIN_CREATE_INFO};
		chain_info.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
		chain_info.format = panel_format;
		chain_info.sampleCount = 1;
		chain_info.width = capture ? capture_width : canvas_width;
		chain_info.height = capture ? capture_height : canvas_height;
		if (projection_count == 4) {
			chain_info.width = 2 * projection_rects[0].extent.width;
			chain_info.height = projection_rects[0].extent.height + projection_rects[2].extent.height;
			__android_log_print(ANDROID_LOG_INFO, tag,
			                    "CV10 four-view atlas %ux%u from %ux%u: context %dx%d, center %dx%d per eye",
			                    chain_info.width, chain_info.height, capture_width, capture_height,
			                    projection_rects[0].extent.width, projection_rects[0].extent.height,
			                    projection_rects[2].extent.width, projection_rects[2].extent.height);
		}
		if (capture_cv10_optics) {
			GLint texture_size = 0;
			glGetIntegerv(GL_MAX_TEXTURE_SIZE, &texture_size);
			if (texture_size <= 0 || std::max(chain_info.width, chain_info.height) > static_cast<uint32_t>(texture_size)) {
				throw std::runtime_error("CV10 optical render size exceeds the GPU texture limit");
			}
		}
		chain_info.faceCount = 1;
		chain_info.arraySize = 1;
		chain_info.mipCount = 1;
		check(xrCreateSwapchain(session, &chain_info, &swapchain), "xrCreateSwapchain");
		uint32_t image_count = 0;
		check(xrEnumerateSwapchainImages(swapchain, 0, &image_count, nullptr), "xrEnumerateSwapchainImages");
		images.resize(image_count, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
		check(xrEnumerateSwapchainImages(swapchain, image_count, &image_count,
		                                 reinterpret_cast<XrSwapchainImageBaseHeader *>(images.data())),
		      "xrEnumerateSwapchainImages");
	}

	void
	initialize_controller()
	{
		XrActionSetCreateInfo set_info{XR_TYPE_ACTION_SET_CREATE_INFO};
		std::snprintf(set_info.actionSetName, sizeof(set_info.actionSetName), "launcher_controller");
		std::snprintf(set_info.localizedActionSetName, sizeof(set_info.localizedActionSetName), "Launcher controller");
		check(xrCreateActionSet(instance, &set_info, &controller_actions), "xrCreateActionSet");

		const auto path = [this](const char *name) {
			XrPath result = XR_NULL_PATH;
			check(xrStringToPath(instance, name, &result), "xrStringToPath");
			return result;
		};
		const XrPath hand = path("/user/hand/right");
		const auto create = [&](XrActionType type, const char *name, const char *label, XrAction &action) {
			XrActionCreateInfo info{XR_TYPE_ACTION_CREATE_INFO};
			info.actionType = type;
			std::snprintf(info.actionName, sizeof(info.actionName), "%s", name);
			std::snprintf(info.localizedActionName, sizeof(info.localizedActionName), "%s", label);
			info.countSubactionPaths = 1;
			info.subactionPaths = &hand;
			check(xrCreateAction(controller_actions, &info, &action), "xrCreateAction");
		};
		create(XR_ACTION_TYPE_POSE_INPUT, "aim", "Aim", controller_pose);
		create(XR_ACTION_TYPE_BOOLEAN_INPUT, "select", "Select", controller_select);
		create(XR_ACTION_TYPE_BOOLEAN_INPUT, "back", "Back", controller_menu);

		const XrActionSuggestedBinding inputs[] = {
		    {controller_pose, path("/user/hand/right/input/aim/pose")},
		    {controller_select, path("/user/hand/right/input/select/click")},
		    {controller_menu, path("/user/hand/right/input/menu/click")},
		};
		XrInteractionProfileSuggestedBinding bindings{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
		bindings.interactionProfile = path("/interaction_profiles/khr/simple_controller");
		bindings.countSuggestedBindings = sizeof(inputs) / sizeof(inputs[0]);
		bindings.suggestedBindings = inputs;
		check(xrSuggestInteractionProfileBindings(instance, &bindings), "xrSuggestInteractionProfileBindings");

		XrActionSpaceCreateInfo aim_space{XR_TYPE_ACTION_SPACE_CREATE_INFO};
		aim_space.action = controller_pose;
		aim_space.subactionPath = hand;
		aim_space.poseInActionSpace.orientation.w = 1.0f;
		check(xrCreateActionSpace(session, &aim_space, &controller_space), "xrCreateActionSpace");
		XrSessionActionSetsAttachInfo attach{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
		attach.countActionSets = 1;
		attach.actionSets = &controller_actions;
		check(xrAttachSessionActionSets(session, &attach), "xrAttachSessionActionSets");
		controller_hand = hand;
	}

	XrActionStateBoolean
	read_controller_button(XrAction action)
	{
		XrActionStateGetInfo request{XR_TYPE_ACTION_STATE_GET_INFO};
		request.action = action;
		request.subactionPath = controller_hand;
		XrActionStateBoolean button{XR_TYPE_ACTION_STATE_BOOLEAN};
		if (XR_FAILED(xrGetActionStateBoolean(session, &request, &button))) {
			button.isActive = XR_FALSE;
		}
		return button;
	}

	void
	update_controller(XrTime time)
	{
		const auto mode = state.controller_mode();
		if (mode != controller_mode) {
			controller_buttons_armed = false;
			controller_calibrated = false;
			controller_mode = mode;
		}
		controller_visible = false;
		if (controller_hand == XR_NULL_PATH || !focused || controller_mode == ControllerMode::Cf20Waiting ||
		    controller_mode == ControllerMode::PhoneFallback) {
			controller_calibrated = false;
			controller_buttons_armed = false;
			return;
		}
		const XrActiveActionSet active{controller_actions, controller_hand};
		XrActionsSyncInfo sync{XR_TYPE_ACTIONS_SYNC_INFO};
		sync.countActiveActionSets = 1;
		sync.activeActionSets = &active;
		if (xrSyncActions(session, &sync) != XR_SUCCESS) {
			controller_calibrated = false;
			controller_buttons_armed = false;
			return;
		}
		const auto select = read_controller_button(controller_select);
		const auto back = read_controller_button(controller_menu);
		if (!select.isActive && !back.isActive) {
			controller_buttons_armed = false;
		} else if (!select.currentState && !back.currentState) {
			// Discard a button already held when focus or the controller connection returns.
			controller_buttons_armed = true;
		}
		if (controller_buttons_armed && back.isActive && back.changedSinceLastSync && back.currentState) {
			state.controller_back();
			return;
		}
		XrSpaceLocation aim{XR_TYPE_SPACE_LOCATION};
		if (XR_FAILED(xrLocateSpace(controller_space, local_space, time, &aim)) ||
		    (aim.locationFlags & XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT) == 0) {
			controller_calibrated = false;
			return;
		}
		const XrQuaternionf q = aim.pose.orientation;
		if (!controller_calibrated) {
			controller_center = q;
			controller_calibrated = true;
			__android_log_print(ANDROID_LOG_INFO, tag, "OpenXR controller aim acquired and centered");
		}

		// CF20 tracks orientation. Take the first direction as the center of the anchored menu.
		const XrQuaternionf c = controller_center;
		const XrQuaternionf rotation = {
		    c.w * q.x - c.x * q.w - c.y * q.z + c.z * q.y,
		    c.w * q.y + c.x * q.z - c.y * q.w - c.z * q.x,
		    c.w * q.z - c.x * q.y + c.y * q.x - c.z * q.w,
		    c.w * q.w + c.x * q.x + c.y * q.y + c.z * q.z,
		};
		const float forward_z = 1.0f - 2.0f * (rotation.x * rotation.x + rotation.y * rotation.y);
		if (forward_z <= 0.05f) {
			return;
		}
		const float forward_x = -2.0f * (rotation.x * rotation.z + rotation.w * rotation.y);
		const float forward_y = -2.0f * (rotation.y * rotation.z - rotation.w * rotation.x);
		const float scale = 2.0f * panel_distance / (panel_width_m * forward_z);
		controller_x = forward_x * scale;
		controller_y = -forward_y * scale * panel_width / panel_height;
		controller_visible = true;
		if (controller_buttons_armed && select.isActive && select.changedSinceLastSync && select.currentState) {
			__android_log_print(ANDROID_LOG_DEBUG, tag, "OpenXR controller select at %.3f, %.3f",
			                    controller_x, controller_y);
			state.controller_click(controller_x, controller_y);
		}
	}

	void
	initialize_drawing()
	{
		program = glCreateProgram();
		const GLuint vertex = compile_shader(GL_VERTEX_SHADER, vertex_shader);
		glAttachShader(program, vertex);
		glDeleteShader(vertex);
		const GLuint fragment = compile_shader(GL_FRAGMENT_SHADER, capture ? capture_shader : fragment_shader);
		glAttachShader(program, fragment);
		glDeleteShader(fragment);
		glLinkProgram(program);
		GLint linked = GL_FALSE;
		glGetProgramiv(program, GL_LINK_STATUS, &linked);
		if (linked != GL_TRUE) {
			char message[512] = {};
			glGetProgramInfoLog(program, sizeof(message), nullptr, message);
			throw std::runtime_error(std::string("Launcher shader linking failed: ") + message);
		}
		glUseProgram(program);
		glUniform1i(glGetUniformLocation(program, "panel"), 0);
		glGenFramebuffers(1, &framebuffer);
		glGenTextures(1, &panel_texture);
		const GLenum target = capture ? GL_TEXTURE_EXTERNAL_OES : GL_TEXTURE_2D;
		glBindTexture(target, panel_texture);
		glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(target, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		if (capture) {
			transform_uniform = glGetUniformLocation(program, "textureTransform");
			source_rect_uniform = glGetUniformLocation(program, "sourceRect");
			glUniform4f(source_rect_uniform, 0.0f, 0.0f, 1.0f, 1.0f);
			glViewport(0, 0, capture_width, capture_height);
			capture.start(panel_texture, capture_width, capture_height);
		} else {
			glUniform2f(glGetUniformLocation(program, "panelSize"), panel_width, panel_height);
			glUniform2f(glGetUniformLocation(program, "canvasSize"), canvas_width, canvas_height);
			aim_uniform = glGetUniformLocation(program, "phoneAim");
			controller_aim_uniform = glGetUniformLocation(program, "controllerAim");
			glTexStorage2D(target, 1, panel_format, panel_width, panel_height);
			glViewport(0, 0, canvas_width, canvas_height);
		}
	}

	bool
	consume_events()
	{
		for (;;) {
			XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};
			const XrResult result = xrPollEvent(instance, &event);
			if (result == XR_EVENT_UNAVAILABLE) {
				return true;
			}
			check(result, "xrPollEvent");
			if (event.type == XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING) {
				return false;
			}
			if (event.type != XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
				continue;
			}
			const auto &changed = reinterpret_cast<const XrEventDataSessionStateChanged &>(event);
			if (changed.session != session) {
				continue;
			}
			__android_log_print(ANDROID_LOG_INFO, tag, "OpenXR session state %d", changed.state);
			focused = changed.state == XR_SESSION_STATE_FOCUSED;
			state.set_ready(focused);
			switch (changed.state) {
			case XR_SESSION_STATE_READY: {
				XrSessionBeginInfo begin_info{XR_TYPE_SESSION_BEGIN_INFO};
				begin_info.primaryViewConfigurationType = view_configuration;
				check(xrBeginSession(session, &begin_info), "xrBeginSession");
				running = true;
				break;
			}
			case XR_SESSION_STATE_STOPPING:
				check(xrEndSession(session), "xrEndSession");
				running = false;
				break;
			case XR_SESSION_STATE_EXITING:
			case XR_SESSION_STATE_LOSS_PENDING: return false;
			default: break;
			}
		}
	}

	void
	update_anchor(XrTime time, float distance)
	{
		const jint generation = state.recenter_generation();
		if (anchor_valid && recent_generation == generation) {
			return;
		}
		XrSpaceLocation head{XR_TYPE_SPACE_LOCATION};
		check(xrLocateSpace(view_space, local_space, time, &head), "xrLocateSpace");
		if ((head.locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) == 0) {
			return;
		}
		const XrQuaternionf q = head.pose.orientation;
		const float forward_x = -2.0f * (q.x * q.z + q.w * q.y);
		const float forward_y = 2.0f * (q.w * q.x - q.y * q.z);
		const float forward_z = 2.0f * (q.x * q.x + q.y * q.y) - 1.0f;
		const float yaw = std::atan2(-forward_x, -forward_z);
		const float pitch = std::asin(std::clamp(forward_y, -0.996f, 0.996f));
		const float sin_yaw = std::sin(yaw / 2.0f);
		const float cos_yaw = std::cos(yaw / 2.0f);
		const float sin_pitch = std::sin(pitch / 2.0f);
		const float cos_pitch = std::cos(pitch / 2.0f);
		anchor.orientation = {cos_yaw * sin_pitch, sin_yaw * cos_pitch, -sin_yaw * sin_pitch,
		                      cos_yaw * cos_pitch};
		const XrVector3f origin = (head.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0
		                              ? head.pose.position
		                              : XrVector3f{};
		anchor.position = {origin.x - distance * std::sin(yaw) * std::cos(pitch),
		                   origin.y + distance * std::sin(pitch),
		                   origin.z - distance * std::cos(yaw) * std::cos(pitch)};
		anchor_valid = true;
		recent_generation = generation;
		controller_calibrated = false;
		if (capture_world_anchored) {
			__android_log_print(ANDROID_LOG_INFO, tag, "Anchored Pocket XR canvas (recenter %d)", generation);
		} else if (generation != 0) {
			__android_log_print(ANDROID_LOG_DEBUG, tag, "Recentered 3D launcher (%d)", generation);
		}
	}

	void
	upload_panel()
	{
		jobject bitmap = state.take_panel();
		if (bitmap == nullptr) {
			return;
		}
		AndroidBitmapInfo info{};
		void *pixels = nullptr;
		bool uploaded = false;
		if (AndroidBitmap_getInfo(state.env, bitmap, &info) == ANDROID_BITMAP_RESULT_SUCCESS &&
		    info.width == panel_width && info.height == panel_height &&
		    info.format == ANDROID_BITMAP_FORMAT_RGBA_8888 &&
		    AndroidBitmap_lockPixels(state.env, bitmap, &pixels) == ANDROID_BITMAP_RESULT_SUCCESS) {
			glPixelStorei(GL_UNPACK_ROW_LENGTH, info.stride / 4);
			glBindTexture(GL_TEXTURE_2D, panel_texture);
			glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, panel_width, panel_height, GL_RGBA, GL_UNSIGNED_BYTE,
			                pixels);
			glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
			AndroidBitmap_unlockPixels(state.env, bitmap);
			uploaded = true;
		}
		state.env->DeleteLocalRef(bitmap);
		if (!uploaded) {
			throw std::runtime_error("Could not upload the Android launcher panel");
		}
	}

	void
	draw_panel()
	{
		if (!capture) {
			upload_panel();
		}
		uint32_t index = 0;
		const XrSwapchainImageAcquireInfo acquire{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
		check(xrAcquireSwapchainImage(swapchain, &acquire, &index), "xrAcquireSwapchainImage");
		XrSwapchainImageWaitInfo wait{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
		wait.timeout = XR_INFINITE_DURATION;
		check(xrWaitSwapchainImage(swapchain, &wait), "xrWaitSwapchainImage");
		glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, images.at(index).image, 0);
		if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
			throw std::runtime_error("OpenXR swapchain framebuffer is incomplete");
		}
		if (capture_cv10_optics) {
			// Every submitted rectangle below is fully overwritten, so skip reloading old tile contents.
			constexpr GLenum color_attachment = GL_COLOR_ATTACHMENT0;
			glInvalidateFramebuffer(GL_FRAMEBUFFER, 1, &color_attachment);
		}
		if (!capture) {
			float x = 0.0f;
			float y = 0.0f;
			state.read_aim(x, y);
			const bool phone_visible =
			    controller_mode == ControllerMode::Phone || controller_mode == ControllerMode::PhoneFallback;
			glUniform3f(aim_uniform, (x + 1.0f) / 2.0f, (y + 1.0f) / 2.0f, phone_visible ? 1.0f : 0.0f);
			glUniform3f(controller_aim_uniform, (controller_x + 1.0f) / 2.0f, (controller_y + 1.0f) / 2.0f,
			            controller_visible ? 1.0f : 0.0f);
		}
		if (projection_count == 4) {
			for (uint32_t i = 0; i < projection_count; ++i) {
				const auto &rect = projection_rects[i];
				const float extent = i < 2 ? 1.0f : cv10_focus_scale;
				const float inset = (1.0f - extent) / 2.0f;
				glViewport(rect.offset.x, rect.offset.y, rect.extent.width, rect.extent.height);
				glUniform4f(source_rect_uniform, ((i % 2) + inset) / 2.0f, inset, extent / 2.0f, extent);
				glDrawArrays(GL_TRIANGLES, 0, 3);
			}
		} else {
			glDrawArrays(GL_TRIANGLES, 0, 3);
		}
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		glFlush();

		const XrSwapchainImageReleaseInfo release{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
		check(xrReleaseSwapchainImage(swapchain, &release), "xrReleaseSwapchainImage");
	}

	bool
	locate_projection_views(XrTime display_time, std::array<XrCompositionLayerProjectionView, 4> &projection_views)
	{
		XrViewLocateInfo info{XR_TYPE_VIEW_LOCATE_INFO};
		info.viewConfigurationType = view_configuration;
		info.space = view_space;
		info.displayTime = display_time;
		XrViewState view_state{XR_TYPE_VIEW_STATE};
		XrView views[] = {{XR_TYPE_VIEW}, {XR_TYPE_VIEW}, {XR_TYPE_VIEW}, {XR_TYPE_VIEW}};
		uint32_t count = 0;
		check(xrLocateViews(session, &info, &view_state, projection_count, &count, views), "xrLocateViews(VIEW)");
		if (count != projection_count || (view_state.viewStateFlags & XR_VIEW_STATE_ORIENTATION_VALID_BIT) == 0) {
			return false;
		}
		const float focus_half_fov = std::atan(cv10_focus_scale * std::tan(cv10_half_fov));
		for (uint32_t eye = 0; eye < count; ++eye) {
			auto &view = projection_views[eye];
			view.type = XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW;
			view.pose = views[eye].pose;
			view.fov = eye < 2 ? cv10_fov : XrFovf{-focus_half_fov, focus_half_fov, focus_half_fov, -focus_half_fov};
			view.subImage.swapchain = using_android_stream ? android_stream.swapchain() : swapchain;
			view.subImage.imageRect = projection_rects[eye];
		}
		return true;
	}

	bool
	locate_head(XrTime time, XrPosef &head) const
	{
		XrSpaceLocation location{XR_TYPE_SPACE_LOCATION};
		constexpr XrSpaceLocationFlags valid = XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT |
		                                       XR_SPACE_LOCATION_ORIENTATION_VALID_BIT |
		                                       XR_SPACE_LOCATION_POSITION_VALID_BIT;
		if (XR_FAILED(xrLocateSpace(view_space, local_space, time, &location)) ||
		    (location.locationFlags & valid) != valid) {
			return false;
		}
		head = location.pose;
		return true;
	}

	bool
	set_late_projection(XrTime display_time, jlong stamp, std::array<XrCompositionLayerProjectionView, 4> &views)
	{
		const auto &pose = capture.frame_pose();
		if (pose.time_ns <= 0) {
			return false;
		}
		if (warp_epoch != pose.epoch) {
			if (warp_epoch >= 0) {
				recenter_stamp = stamp;
			}
			warp_epoch = pose.epoch;
		}
		// One projection cannot describe source rows that used visibly different camera poses.
		if (stamp == recenter_stamp || !rotation_near(pose.center, pose.top, 0.025f) ||
		    !rotation_near(pose.center, pose.bottom, 0.025f)) {
			return false;
		}
		const timespec monotonic{static_cast<time_t>(pose.time_ns / 1'000'000'000),
		                        static_cast<long>(pose.time_ns % 1'000'000'000)};
		XrTime source_time = 0;
		XrPosef reference{}, predicted{};
		if (XR_FAILED(convert_from_monotonic(instance, &monotonic, &source_time)) ||
		    source_time < display_time - 48'000'000 || source_time > display_time + 48'000'000 ||
		    !locate_head(source_time, reference) || !locate_head(display_time, predicted)) {
			return false;
		}
		// Transform the actual Pocket render orientation from its unsmoothed reference into LOCAL.
		// The shared pose timestamp supplies the runtime side of that reference. The compositor then
		// locates the display pose at scanout, including on repeats of this captured image.
		const auto source = normalize(multiply(reference.orientation, multiply(inverse_rotation(pose.reference), pose.center)));
		if (!rotation_near(source, predicted.orientation, 12.0f)) {
			return false;
		}
		for (uint32_t i = 0; i < projection_count; ++i) {
			auto &eye = views[i].pose;
			const auto offset = multiply(multiply(source, {eye.position.x, eye.position.y, eye.position.z, 0.0f}),
			                             inverse_rotation(source));
			eye.orientation = multiply(source, eye.orientation);
			eye.position = {reference.position.x + offset.x, reference.position.y + offset.y,
			                reference.position.z + offset.z};
		}
		if (trace_late_projection) {
			const auto target = predicted.orientation;
			__android_log_print(ANDROID_LOG_DEBUG, tag,
			                    "CV10_ATW stamp=%lld pose=%lld epoch=%lld source=%g,%g,%g,%g target=%g,%g,%g,%g",
			                    static_cast<long long>(stamp), static_cast<long long>(pose.time_ns),
			                    static_cast<long long>(pose.epoch), source.x, source.y, source.z, source.w,
			                    target.x, target.y, target.z, target.w);
		}
		return true;
	}

	void
	draw_frame()
	{
		const XrFrameWaitInfo wait_info{XR_TYPE_FRAME_WAIT_INFO};
		XrFrameState frame{XR_TYPE_FRAME_STATE};
		check(xrWaitFrame(session, &wait_info, &frame), "xrWaitFrame");
		const XrFrameBeginInfo begin_info{XR_TYPE_FRAME_BEGIN_INFO};
		check(xrBeginFrame(session, &begin_info), "xrBeginFrame");

		XrFrameEndInfo end_info{XR_TYPE_FRAME_END_INFO};
		end_info.displayTime = frame.predictedDisplayTime;
		end_info.environmentBlendMode = blend_mode;

		XrCompositionLayerQuad quads[] = {{XR_TYPE_COMPOSITION_LAYER_QUAD}, {XR_TYPE_COMPOSITION_LAYER_QUAD}};
		const XrCompositionLayerBaseHeader *layers[] = {
		    reinterpret_cast<const XrCompositionLayerBaseHeader *>(&quads[0]),
		    reinterpret_cast<const XrCompositionLayerBaseHeader *>(&quads[1]),
		};
		XrCompositionLayerProjection projection{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
		std::array<XrCompositionLayerProjectionView, 4> projection_views{};
		jlong capture_timestamp = 0;
		int64_t stream_begin_ns = 0;
		int64_t stream_end_ns = 0;
		bool stream_pending_fence = false;
		int64_t stream_fence_wait_ns = 0;
		if (frame.shouldRender && capture) {
			if (using_android_stream) {
				stream_begin_ns = convert_time != nullptr ? monotonic_ns() : 0;
				const auto stream_frame = android_stream.update(session);
				stream_pending_fence = stream_frame.pending_fence;
				stream_fence_wait_ns = stream_frame.fence_wait_ns;
				capture_timestamp = capture.update_stream(stream_frame.timestamp);
				stream_end_ns = convert_time != nullptr ? monotonic_ns() : 0;
			} else {
				capture_timestamp = capture.update(transform_uniform);
			}
		}
		if (capture_timestamp != 0) {
			if (capture_cv10_optics) {
				if (locate_projection_views(frame.predictedDisplayTime, projection_views)) {
					// OpenXR uses the last released image until a new capture reaches the swapchain.
					if (capture_timestamp != submitted_capture_timestamp) {
						if (using_android_stream) {
							++streamed_frames;
						} else {
							draw_panel();
							++copied_frames;
						}
						submitted_capture_timestamp = capture_timestamp;
					} else {
						++reused_frames;
					}
					projection.space = view_space;
					if (convert_from_monotonic != nullptr) {
						if (set_late_projection(frame.predictedDisplayTime, capture_timestamp, projection_views)) {
							projection.space = local_space;
							++warped_frames;
						} else {
							++unwarped_frames;
						}
					}
					projection.viewCount = projection_count;
					projection.views = projection_views.data();
					layers[0] = reinterpret_cast<const XrCompositionLayerBaseHeader *>(&projection);
					end_info.layerCount = 1;
					end_info.layers = layers;
				}
			} else {
				if (capture_world_anchored) {
					update_anchor(frame.predictedDisplayTime, pocket_distance);
				}
				if (!capture_world_anchored || anchor_valid) {
					draw_panel();
					// The capture is either Pocket XR's stereo canvas or a side-by-side PC video.
					constexpr int32_t eye_width = pocket_width / 2;
					const XrPosef view_pose = {{0.0f, 0.0f, 0.0f, 1.0f}, {0.0f, 0.0f, -pocket_distance}};
					for (int eye = 0; eye < 2; ++eye) {
						auto &quad = quads[eye];
						quad.space = capture_world_anchored ? local_space : view_space;
						quad.eyeVisibility = eye == 0 ? XR_EYE_VISIBILITY_LEFT : XR_EYE_VISIBILITY_RIGHT;
						quad.subImage.swapchain = swapchain;
						quad.subImage.imageRect = {{eye * eye_width, 0}, {eye_width, pocket_height}};
						quad.pose = capture_world_anchored ? anchor : view_pose;
						// Pocket XR applies the display size to the projection tangents, not the texture.
						quad.size = capture_size;
					}
					end_info.layerCount = 2;
					end_info.layers = layers;
				}
			}
		} else if (frame.shouldRender && !capture) {
			update_anchor(frame.predictedDisplayTime, panel_distance);
			if (anchor_valid) {
				update_controller(frame.predictedDisplayTime);
				auto &quad = quads[0];
				draw_panel();
				quad.space = local_space;
				quad.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
				quad.subImage.swapchain = swapchain;
				quad.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
				quad.subImage.imageRect.extent = {canvas_width, canvas_height};
				quad.pose = anchor;
				quad.size = {panel_width_m * canvas_width / panel_width,
				             panel_width_m * canvas_height / panel_width};
				end_info.layerCount = 1;
				end_info.layers = layers;
			}
		}
		check(xrEndFrame(session, &end_info), "xrEndFrame");
		if (convert_time && end_info.layerCount > 0 && capture_timestamp != 0) {
			timespec time{};
			if (XR_SUCCEEDED(convert_time(instance, end_info.displayTime, &time))) {
				const auto layer_time = static_cast<long long>(time.tv_sec) * 1'000'000'000 + time.tv_nsec;
				__android_log_print(ANDROID_LOG_INFO, tag,
				                    "CV10_SOURCE stamp=%lld layer=%lld stream_begin=%" PRId64
				                    " stream_end=%" PRId64 " pending_fence=%d fence_wait=%" PRId64,
				                    static_cast<long long>(capture_timestamp), layer_time, stream_begin_ns,
				                    stream_end_ns, stream_pending_fence, stream_fence_wait_ns);
			}
		}
	}

	JavaState &state;
	DisplayCapture &capture;
	const bool capture_world_anchored;
	const bool capture_cv10_optics;
	const uint32_t capture_width;
	const uint32_t capture_height;
	const XrExtent2Df capture_size;
	const bool direct_capture;
	const bool producer_quad_supported;
	AndroidStream android_stream;
	bool using_android_stream = false;
	jlong submitted_capture_timestamp = 0;
	PFN_xrConvertTimeToTimespecTimeKHR convert_time = nullptr;
	PFN_xrConvertTimespecTimeToTimeKHR convert_from_monotonic = nullptr;
	jlong warp_epoch = -1;
	jlong recenter_stamp = 0;
	uint32_t warped_frames = 0;
	uint32_t unwarped_frames = 0;
	bool trace_late_projection = false;
	uint32_t copied_frames = 0;
	uint32_t streamed_frames = 0;
	uint32_t reused_frames = 0;
	XrInstance instance = XR_NULL_HANDLE;
	XrSystemId system_id = XR_NULL_SYSTEM_ID;
	XrSession session = XR_NULL_HANDLE;
	XrSpace local_space = XR_NULL_HANDLE;
	XrSpace view_space = XR_NULL_HANDLE;
	XrActionSet controller_actions = XR_NULL_HANDLE;
	XrAction controller_pose = XR_NULL_HANDLE;
	XrAction controller_select = XR_NULL_HANDLE;
	XrAction controller_menu = XR_NULL_HANDLE;
	XrSpace controller_space = XR_NULL_HANDLE;
	XrPath controller_hand = XR_NULL_PATH;
	XrSwapchain swapchain = XR_NULL_HANDLE;
	XrViewConfigurationType view_configuration = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
	uint32_t projection_count = 2;
	std::array<XrRect2Di, 4> projection_rects{};
	XrEnvironmentBlendMode blend_mode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
	std::vector<XrSwapchainImageOpenGLESKHR> images;
	XrPosef anchor{};
	bool anchor_valid = false;
	bool running = false;
	bool focused = false;
	XrQuaternionf controller_center{};
	ControllerMode controller_mode = ControllerMode::Phone;
	bool controller_buttons_armed = false;
	bool controller_calibrated = false;
	bool controller_visible = false;
	float controller_x = 0.0f;
	float controller_y = 0.0f;
	jint recent_generation = 0;
	EGLDisplay display = EGL_NO_DISPLAY;
	EGLContext context = EGL_NO_CONTEXT;
	EGLSurface surface = EGL_NO_SURFACE;
	EGLConfig config = nullptr;
	GLenum panel_format = GL_RGBA8;
	GLuint panel_texture = 0;
	GLuint framebuffer = 0;
	GLuint program = 0;
	GLint aim_uniform = -1;
	GLint controller_aim_uniform = -1;
	GLint transform_uniform = -1;
	GLint source_rect_uniform = -1;
};

jstring
run_launcher(JNIEnv *env,
             jobject activity,
             jobject application_context,
             jobject java_state,
             jobject java_capture,
             jstring java_runtime_manifest,
             bool capture_world_anchored,
             bool capture_wide_canvas,
             uint32_t optical_eye_size,
             bool use_bundled_runtime,
             bool direct_capture,
             bool producer_quad_supported)
{
	try {
		// A display configuration change can start a new Activity before the old worker exits.
		std::lock_guard<std::mutex> lock(session_mutex);
		JavaState state(env, java_state);
		DisplayCapture capture(env, java_capture);
		Launcher launcher(state, capture, capture_world_anchored, capture_wide_canvas, optical_eye_size, direct_capture,
		                  producer_quad_supported);
		if (!state.should_stop()) {
			std::string runtime_manifest;
			if (java_runtime_manifest != nullptr) {
				const char *path = env->GetStringUTFChars(java_runtime_manifest, nullptr);
				if (path == nullptr) {
					return nullptr;
				}
				runtime_manifest = path;
				env->ReleaseStringUTFChars(java_runtime_manifest, path);
			}
			launcher.run(activity, application_context, runtime_manifest, use_bundled_runtime);
		}
		return nullptr;
	} catch (const std::exception &error) {
		return env->NewStringUTF(error.what());
	}
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_org_freedesktop_monado_openxr_1runtime_Cv10SpatialLauncherActivity_nativeRun(JNIEnv *env,
                                                                                  jobject activity,
                                                                                  jobject application_context,
                                                                                  jobject java_state,
                                                                                  jstring java_runtime_manifest,
                                                                                  jboolean use_bundled_runtime)
{
	return run_launcher(env, activity, application_context, java_state, nullptr, java_runtime_manifest, false, false, 0,
	                    use_bundled_runtime == JNI_TRUE, false, false);
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_freedesktop_monado_openxr_1runtime_Cv10PocketXrRenderer_nativeRun(JNIEnv *env,
                                                                           jobject /* renderer */,
                                                                           jobject activity,
                                                                           jobject application_context,
                                                                           jobject java_state,
                                                                           jobject java_capture,
                                                                           jstring java_runtime_manifest,
                                                                           jboolean world_anchored,
                                                                           jboolean wide_canvas,
                                                                           jint optical_eye_size,
                                                                           jboolean direct_capture,
                                                                           jboolean producer_quad_supported)
{
	return run_launcher(env, activity, application_context, java_state, java_capture, java_runtime_manifest,
	                    world_anchored == JNI_TRUE, wide_canvas == JNI_TRUE, optical_eye_size, true,
	                    direct_capture == JNI_TRUE, producer_quad_supported == JNI_TRUE);
}
