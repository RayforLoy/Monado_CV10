// SPDX-License-Identifier: BSL-1.0

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include <algorithm>
#include <array>
#include <chrono>
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
constexpr uint32_t pocket_width = 3840;
constexpr uint32_t pocket_height = 1080;
constexpr float panel_distance = 1.45f;
constexpr float pocket_distance = 1.0f;
constexpr float panel_width_m = 1.62f;
std::mutex session_mutex;

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
uniform vec2 aim;
uniform vec2 size;
out vec4 color;
void main() {
    vec2 position = uv * size;
    vec2 cursor = aim * size;
    vec2 origin = vec2(0.76, 1.02) * size;
    vec2 ray = cursor - origin;
    float along = clamp(dot(position - origin, ray) / dot(ray, ray), 0.0, 1.0);
    float rayDistance = length(position - origin - along * ray);
    float rayOpacity = (1.0 - smoothstep(0.7, 2.5, rayDistance)) * along * 0.32;
    float cursorDistance = length(position - cursor);
    float ring = 1.0 - smoothstep(1.7, 3.2, abs(cursorDistance - 9.0));
    float centerDot = 1.0 - smoothstep(2.2, 3.5, cursorDistance);
    vec3 panelColor = texture(panel, uv).rgb;
    color = vec4(mix(panelColor, vec3(0.34, 1.0, 0.75), max(rayOpacity, max(ring, centerDot))), 1.0);
}
)";

constexpr char capture_shader[] = R"(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 uv;
uniform samplerExternalOES panel;
uniform mat4 textureTransform;
out vec4 color;
void main() {
    vec2 source = (textureTransform * vec4(uv.x, 1.0 - uv.y, 0.0, 1.0)).xy;
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

	JNIEnv *const env;

private:
	const jobject state;
	jmethodID stop_method = nullptr;
	jmethodID ready_method = nullptr;
	jmethodID panel_method = nullptr;
	jmethodID aim_method = nullptr;
	jmethodID recenter_method = nullptr;
};

class DisplayCapture
{
public:
	DisplayCapture(JNIEnv *env, jobject capture) : env(env), capture(capture)
	{
		if (capture == nullptr) {
			return;
		}
		jclass cls = env->GetObjectClass(capture);
		start_method = env->GetMethodID(cls, "startCapture", "(III)Z");
		update_method = env->GetMethodID(cls, "updateCapture", "([F)Z");
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
	start(GLuint texture)
	{
		started = true;
		if (!env->CallBooleanMethod(capture, start_method, static_cast<jint>(texture),
		                            static_cast<jint>(pocket_width), static_cast<jint>(pocket_height))) {
			throw std::runtime_error("Cannot create the Pocket XR capture surface");
		}
	}

	bool
	update(GLint transform_uniform)
	{
		if (!env->CallBooleanMethod(capture, update_method, matrix)) {
			return false;
		}
		std::array<jfloat, 16> transform{};
		env->GetFloatArrayRegion(matrix, 0, transform.size(), transform.data());
		glUniformMatrix4fv(transform_uniform, 1, GL_FALSE, transform.data());
		return true;
	}

private:
	JNIEnv *const env;
	const jobject capture;
	jmethodID start_method = nullptr;
	jmethodID update_method = nullptr;
	jmethodID stop_method = nullptr;
	jfloatArray matrix = nullptr;
	bool started = false;
};

class Launcher
{
public:
	Launcher(JavaState &state, DisplayCapture &capture, bool capture_world_anchored)
	    : state(state), capture(capture), capture_world_anchored(capture_world_anchored)
	{}

	~Launcher()
	{
		state.set_ready(false);
		capture.stop();
		if (swapchain != XR_NULL_HANDLE) {
			xrDestroySwapchain(swapchain);
		}
		if (view_space != XR_NULL_HANDLE) {
			xrDestroySpace(view_space);
		}
		if (local_space != XR_NULL_HANDLE) {
			xrDestroySpace(local_space);
		}
		if (session != XR_NULL_HANDLE) {
			xrDestroySession(session);
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
		initialize_drawing();
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
		const char *extensions[] = {XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,
		                            XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME};
		XrInstanceCreateInfo instance_info{XR_TYPE_INSTANCE_CREATE_INFO};
		instance_info.next = &android_info;
		std::snprintf(instance_info.applicationInfo.applicationName,
		              sizeof(instance_info.applicationInfo.applicationName), "CV10 Apps");
		instance_info.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 0);
		instance_info.enabledExtensionCount = 2;
		instance_info.enabledExtensionNames = extensions;
		XrResult result = xrCreateInstance(&instance_info, &instance);
		if (!use_bundled_runtime && result == XR_ERROR_RUNTIME_UNAVAILABLE && !runtime_manifest.empty()) {
			__android_log_print(ANDROID_LOG_INFO, tag, "OpenXR runtime unavailable, trying bundled Monado CV10");
			runtime_property.value = runtime_manifest.c_str();
			check(initialize_loader(loader_header), "xrInitializeLoaderKHR(bundled Monado CV10)");
			result = xrCreateInstance(&instance_info, &instance);
		}
		check(result, "xrCreateInstance");

		XrInstanceProperties properties{XR_TYPE_INSTANCE_PROPERTIES};
		check(xrGetInstanceProperties(instance, &properties), "xrGetInstanceProperties");
		__android_log_print(ANDROID_LOG_INFO, tag, "Using OpenXR runtime %s%s", properties.runtimeName,
		                    use_bundled_runtime ? " (bundled Monado CV10)" : "");

		XrSystemGetInfo system_info{XR_TYPE_SYSTEM_GET_INFO};
		system_info.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
		check(xrGetSystem(instance, &system_info, &system_id), "xrGetSystem");

		uint32_t blend_count = 0;
		check(xrEnumerateEnvironmentBlendModes(instance, system_id, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
		                                       0, &blend_count, nullptr),
		      "xrEnumerateEnvironmentBlendModes");
		std::vector<XrEnvironmentBlendMode> modes(blend_count);
		check(xrEnumerateEnvironmentBlendModes(instance, system_id, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
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
		chain_info.width = capture ? pocket_width : panel_width;
		chain_info.height = capture ? pocket_height : panel_height;
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
			glViewport(0, 0, pocket_width, pocket_height);
			capture.start(panel_texture);
		} else {
			glUniform2f(glGetUniformLocation(program, "size"), panel_width, panel_height);
			aim_uniform = glGetUniformLocation(program, "aim");
			glTexStorage2D(target, 1, panel_format, panel_width, panel_height);
			glViewport(0, 0, panel_width, panel_height);
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
			state.set_ready(changed.state == XR_SESSION_STATE_FOCUSED);
			switch (changed.state) {
			case XR_SESSION_STATE_READY: {
				XrSessionBeginInfo begin_info{XR_TYPE_SESSION_BEGIN_INFO};
				begin_info.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
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
		if (!capture) {
			float x = 0.0f;
			float y = 0.0f;
			state.read_aim(x, y);
			glUniform2f(aim_uniform, (x + 1.0f) / 2.0f, (y + 1.0f) / 2.0f);
		}
		glDrawArrays(GL_TRIANGLES, 0, 3);
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		glFlush();

		const XrSwapchainImageReleaseInfo release{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
		check(xrReleaseSwapchainImage(swapchain, &release), "xrReleaseSwapchainImage");
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
		if (frame.shouldRender && capture && capture.update(transform_uniform)) {
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
					// Pocket XR's uncalibrated 44.2 by 25.8 degree view at one meter.
					quad.size = {0.812f, 0.458f};
				}
				end_info.layerCount = 2;
				end_info.layers = layers;
			}
		} else if (frame.shouldRender && !capture) {
			update_anchor(frame.predictedDisplayTime, panel_distance);
			if (anchor_valid) {
				auto &quad = quads[0];
				draw_panel();
				quad.space = local_space;
				quad.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
				quad.subImage.swapchain = swapchain;
				quad.subImage.imageRect.extent = {panel_width, panel_height};
				quad.pose = anchor;
				quad.size = {panel_width_m, panel_width_m * panel_height / panel_width};
				end_info.layerCount = 1;
				end_info.layers = layers;
			}
		}
		check(xrEndFrame(session, &end_info), "xrEndFrame");
	}

	JavaState &state;
	DisplayCapture &capture;
	const bool capture_world_anchored;
	XrInstance instance = XR_NULL_HANDLE;
	XrSystemId system_id = XR_NULL_SYSTEM_ID;
	XrSession session = XR_NULL_HANDLE;
	XrSpace local_space = XR_NULL_HANDLE;
	XrSpace view_space = XR_NULL_HANDLE;
	XrSwapchain swapchain = XR_NULL_HANDLE;
	XrEnvironmentBlendMode blend_mode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
	std::vector<XrSwapchainImageOpenGLESKHR> images;
	XrPosef anchor{};
	bool anchor_valid = false;
	bool running = false;
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
	GLint transform_uniform = -1;
};

jstring
run_launcher(JNIEnv *env,
             jobject activity,
             jobject application_context,
             jobject java_state,
             jobject java_capture,
             jstring java_runtime_manifest,
             bool capture_world_anchored,
             bool use_bundled_runtime)
{
	try {
		// A display configuration change can start a new Activity before the old worker exits.
		std::lock_guard<std::mutex> lock(session_mutex);
		JavaState state(env, java_state);
		DisplayCapture capture(env, java_capture);
		Launcher launcher(state, capture, capture_world_anchored);
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
	return run_launcher(env, activity, application_context, java_state, nullptr, java_runtime_manifest, false,
	                    use_bundled_runtime == JNI_TRUE);
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_freedesktop_monado_openxr_1runtime_Cv10PocketXrRenderer_nativeRun(JNIEnv *env,
                                                                           jobject /* renderer */,
                                                                           jobject activity,
                                                                           jobject application_context,
                                                                           jobject java_state,
                                                                           jobject java_capture,
                                                                           jstring java_runtime_manifest,
                                                                           jboolean world_anchored)
{
	return run_launcher(env, activity, application_context, java_state, java_capture, java_runtime_manifest,
	                    world_anchored == JNI_TRUE, true);
}
