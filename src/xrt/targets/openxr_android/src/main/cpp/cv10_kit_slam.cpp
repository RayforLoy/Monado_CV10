// SPDX-License-Identifier: GPL-3.0-or-later
#if defined(__ANDROID__) && defined(__aarch64__)
#include <jni.h>
#include <dlfcn.h>
#endif

#if defined(__aarch64__) || defined(CV10_SLAM_HOST_TEST)

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <new>
#include <vector>

namespace {

constexpr size_t parameter_size = 0x840;
constexpr size_t hand_parameter_size = 0x1c38;
constexpr int image_width = 640;
constexpr int image_height = 480;
constexpr int image_bytes = image_width * image_height;

constexpr char image_format[] = "fmt:yuv@res:480p@rot:0";
constexpr char stereo_format[] = "fmt:yuv@res:480p@rot:0@fmt:preyuv@res:480p@rot:0@fmt:feature@fmt:depth@rot:0";
constexpr char input_formats[] = "fmt:yuv@res:480p@rot:0;fmt:preyuv@res:480p@rot:0;fmt:feature;fmt:depth@rot:0;fmt:imu";
constexpr char output_formats[] = "fmt:pose@org:slam;fmt:predictpose@org:slam;fmt:cameraintrin@org:slam;"
                                  "fmt:pointcloud@org:slam;fmt:anchor@org:slam@type:update";

// These structures are the wire format of the OEM module's exported C interface.
struct Packet {
	char format[0x100] = {};
	uint64_t size;
	uint64_t timestamp_ns;
	void *payload;
	void *owner;

	template <size_t Length>
	Packet(const char (&type)[Length], uint64_t length, uint64_t time, void *contents, void *reference = nullptr)
	    : size(length), timestamp_ns(time), payload(contents), owner(reference)
	{
		static_assert(Length <= sizeof(format));
		std::memcpy(format, type, Length);
	}
};

static_assert(sizeof(Packet) == 0x120 && offsetof(Packet, owner) == 0x118);

struct Callbacks {
	void *context;
	int64_t (*set)(void *, const char *, size_t, const void *);
	int64_t (*get)(void *, const char *, size_t, void *);
	int (*retain)(void *, Packet *);
	int (*release)(void *, Packet *);
	int (*input)(void *, Packet *);
	int (*output)(void *, Packet *);
};

struct Module {
	uint64_t tag;
	const char *name;
	void *unused_10[2];
	void *(*create)(const char *, const char *);
	void (*destroy)(void *);
	int (*init)(void *);
	int (*start)(void *);
	void *unused_40[2];
	int (*stop)(void *);
	int (*deinit)(void *);
	int (*set)(void *, const char *, size_t, const void *);
	void *unused_68;
	int (*push)(void *, Packet *);
	int (*get)(void *, Packet *);
	void *unused_80[4];
	void (*register_callbacks)(const Callbacks *);
	void (*unregister_callbacks)();
};

static_assert(offsetof(Module, create) == 0x20 && offsetof(Module, push) == 0x70 &&
              offsetof(Module, register_callbacks) == 0xa0);

struct PacketOwner {
	std::atomic<unsigned> references{1};
#if defined(CV10_SLAM_HOST_TEST)
	inline static std::atomic<int> live{0};
	PacketOwner() { live.fetch_add(1, std::memory_order_relaxed); }
	virtual ~PacketOwner() { live.fetch_sub(1, std::memory_order_relaxed); }
#else
	virtual ~PacketOwner() = default;
#endif
};

int
retain(void *, Packet *packet)
{
	if (!packet || !packet->owner) return -1;
	static_cast<PacketOwner *>(packet->owner)->references.fetch_add(1, std::memory_order_relaxed);
	return 0;
}

int
release(void *, Packet *packet)
{
	if (!packet || !packet->owner) return -1;
	auto *owner = static_cast<PacketOwner *>(packet->owner);
	if (owner->references.fetch_sub(1, std::memory_order_acq_rel) == 1) delete owner;
	return 0;
}

int
ignore_packet(void *, Packet *)
{
	return 0;
}

struct ImuPayload {
	uint32_t kind;
	uint32_t reserved_04 = 0;
	double seconds;
	float axes[3];
	uint32_t reserved_1c = 0;
};

static_assert(sizeof(ImuPayload) == 0x20 && offsetof(ImuPayload, axes) == 0x10);

struct Imu : PacketOwner {
	ImuPayload payload;
	Packet packet;

	Imu(uint32_t kind, uint64_t ns, float x, float y, float z)
	    : payload{kind, 0, ns / 1e9, {x, y, z}},
	      packet{"fmt:imu", sizeof(payload), ns, &payload, this}
	{}
};

struct ImagePayload {
	uint32_t total_size = 0x60 + image_bytes;
	uint32_t width = image_width;
	uint32_t height = image_height;
	uint32_t stride = image_width;
	uint32_t frame;
	uint32_t reserved_14 = 0;
	uint32_t a_frame;
	uint32_t reserved_1c = 0;
	uint64_t reserved_20 = 0;
	double seconds;
	uint64_t reserved_30 = 0;
	double exposure_seconds;
	uint64_t reserved_40 = 0;
	uint8_t *pixels;
	uint8_t reserved_50[0x10] = {};

	ImagePayload(uint32_t number, uint64_t ns, int exposure_us, uint8_t *samples, bool a_frame)
	    : frame(number), a_frame(a_frame), seconds(ns / 1e9), exposure_seconds(exposure_us / 1e6), pixels(samples)
	{}
};

static_assert(sizeof(ImagePayload) == 0x60 && offsetof(ImagePayload, pixels) == 0x48);

struct StereoPayload {
	uint32_t flags = 3;
	uint32_t count = 2;
	uint32_t types = 0x101;
	uint32_t reserved_0c = 0;
	Packet *images[2];
	uint8_t reserved_20[0x58] = {};
};

static_assert(sizeof(StereoPayload) == 0x78 && offsetof(StereoPayload, images) == 0x10);

struct Stereo : PacketOwner {
	uint8_t pixels[2][image_bytes];
	ImagePayload images[2];
	Packet leaves[2];
	StereoPayload payload;
	Packet packet;

	Stereo(uint64_t ns, uint32_t frame, int exposure_us, bool a_frame = true)
	    : images{{frame, ns, exposure_us, pixels[0], a_frame}, {frame, ns, exposure_us, pixels[1], a_frame}},
	      leaves{{image_format, sizeof(ImagePayload), ns, &images[0], this},
	             {image_format, sizeof(ImagePayload), ns, &images[1], this}},
	      payload{3, 2, 0x101, 0, {&leaves[0], &leaves[1]}},
	      packet{stereo_format, sizeof(payload), ns, &payload, this}
	{}
};

int64_t
set_config(void *, const char *, size_t, const void *)
{
	return 0;
}

struct Session {
	const Module *module = nullptr;
	void *instance = nullptr;
	bool registered = false;
	bool initialized = false;
	bool started = false;
	std::array<uint8_t, parameter_size> parameters;
	Callbacks callbacks{this, set_config, get_config, retain, release, ignore_packet, ignore_packet};

	static int64_t
	get_config(void *context, const char *key, size_t size, void *value)
	{
		if (!key || !value) return -1;
		if (size == 1 && std::strcmp(key, "HSDK_CONFIG_MANAGER_IMPL_GET_CHECK_VALID") == 0) {
			*static_cast<uint8_t *>(value) = 1;
			return 1;
		}
		const auto &parameters = static_cast<Session *>(context)->parameters;
		if (size == parameters.size() && std::strcmp(key, "HSDK_CONFIG_MANAGER_IMPL_GET_SLAM_PARAMS") == 0) {
			std::memcpy(value, parameters.data(), size);
			return size;
		}
		// The OEM configuration permits a new session without saved runtime bias.
		return -1;
	}

	const char *
	open(const Module *interface, int &status)
	{
		module = interface;
		if (!module || module->tag != 0x00000001abcd2012ULL || !module->name ||
		    std::strcmp(module->name, "SLAM") != 0 || !module->create || !module->destroy ||
		    !module->init || !module->start || !module->stop || !module->deinit || !module->push ||
		    !module->get || !module->register_callbacks || !module->unregister_callbacks) {
			return "Unsupported SLAM module interface";
		}

		module->register_callbacks(&callbacks);
		registered = true;
		instance = module->create(input_formats, output_formats);
		if (!instance) return "SLAM module could not create a session";
		status = module->init(instance);
		if (status != 0) return "SLAM init";
		initialized = true;
		status = module->start(instance);
		if (status != 0) return "SLAM start";
		started = true;
		return nullptr;
	}

	int
	close()
	{
		int stop_status = started ? module->stop(instance) : 0;
		int deinit_status = initialized ? module->deinit(instance) : 0;
		if (instance) module->destroy(instance);
		if (registered) module->unregister_callbacks();
		instance = nullptr;
		started = initialized = registered = false;
		return stop_status != 0 ? stop_status : deinit_status;
	}

	~Session() { close(); }

	int
	push_imu(uint32_t kind, uint64_t ns, float x, float y, float z)
	{
		auto *sample = new (std::nothrow) Imu(kind, ns, x, y, z);
		if (!sample) return -12;
		int result = module->push(instance, &sample->packet);
		release(nullptr, &sample->packet);
		return result;
	}

	int
	push_stereo(Stereo *stereo)
	{
		int result = module->push(instance, &stereo->packet);
		release(nullptr, &stereo->packet);
		return result;
	}

	int
	pose(uint8_t (&pose)[0x38], uint64_t ns = 0)
	{
		Packet packet("fmt:pose@org:slam", sizeof(pose), ns, pose);
		return module->get(instance, &packet);
	}
};

struct HandImuPayload {
	uint8_t valid = 1;
	char side;
	uint8_t padding[6] = {};
	uint64_t ns;
	float axes[6];
	float quaternion[4] = {1, 0, 0, 0};
};

struct HandImu : PacketOwner {
	HandImuPayload payload;
	Packet packet;

	HandImu(bool right, uint64_t ns, const std::array<float, 6> &axes)
	    : payload{1, right ? '1' : '0', {}, ns, {axes[0], axes[1], axes[2], axes[3], axes[4], axes[5]}},
	      packet{"fmt:imu@org:controller", sizeof(payload), ns, &payload, this}
	{}
};

struct HandStereoPayload {
	uint32_t count = 2;
	uint32_t padding = 0;
	std::vector<ImagePayload *> images;
	alignas(8) uint8_t head_pose[0x38];
	const Module *head_interface;
	void *head_instance;
};

struct HandStereo : PacketOwner {
	uint8_t pixels[2][image_bytes];
	ImagePayload images[2];
	HandStereoPayload payload;
	Packet packet;

	HandStereo(uint64_t ns, uint32_t frame, int exposure_us, const uint8_t (&head_pose)[0x38],
	           const Session *head_session, bool a_frame = true)
	    : images{{frame, ns, exposure_us, pixels[0], a_frame}, {frame, ns, exposure_us, pixels[1], a_frame}},
	      payload{2, 0, {&images[0], &images[1]}, {}, head_session ? head_session->module : nullptr,
	              head_session ? head_session->instance : nullptr},
	      packet{image_format, sizeof(payload), ns, &payload, this}
	{
		std::memcpy(payload.head_pose, head_pose, sizeof(payload.head_pose));
	}
};

struct HandPose {
	char side;
	uint8_t padding[3];
	uint32_t state;
	float position[3];
	float quaternion[4];
	uint8_t extra[0x30];
};

static_assert(sizeof(HandImuPayload) == 0x38 && offsetof(HandImuPayload, axes) == 0x10);
static_assert(sizeof(HandStereoPayload) == 0x68 && offsetof(HandStereoPayload, head_pose) == 0x20);
static_assert(sizeof(HandPose) == 0x54 && offsetof(HandPose, quaternion) == 0x14);

template <size_t Capacity>
struct HandPackets {
	std::array<Packet *, Capacity> pending{};

	bool
	hold(Packet &packet)
	{
		for (auto &slot : pending) {
			if (slot && static_cast<PacketOwner *>(slot->owner)->references.load(std::memory_order_acquire) == 1) {
				release(nullptr, slot);
				slot = nullptr;
			}
			if (!slot) {
				slot = &packet;
				return true;
			}
		}
		return false;
	}

	unsigned
	clear_after_shutdown()
	{
		unsigned unreleased = 0;
		for (auto &slot : pending) {
			if (!slot) continue;
			auto *owner = static_cast<PacketOwner *>(slot->owner);
			unreleased += owner->references.load(std::memory_order_relaxed) > 1;
			delete owner;
			slot = nullptr;
		}
		return unreleased;
	}
};

struct HandSession {
	const Module *module = nullptr;
	void *instance = nullptr;
	bool registered = false;
	bool initialized = false;
	bool started = false;
	std::array<uint8_t, hand_parameter_size> parameters;
	Callbacks callbacks{this, set_config, get_config, retain, release, ignore_packet, ignore_packet};
	HandPackets<512> imu_inputs;
	HandPackets<32> stereo_inputs;
	unsigned unreleased_inputs = 0;

	static int64_t
	get_config(void *context, const char *key, size_t size, void *value)
	{
		if (!key || !value) return -1;
		if (size == 1 && std::strcmp(key, "HSDK_CONFIG_MANAGER_IMPL_GET_CHECK_VALID") == 0) {
			*static_cast<uint8_t *>(value) = 1;
			return 1;
		}
		const auto &parameters = static_cast<HandSession *>(context)->parameters;
		if (size != parameters.size() || std::strcmp(key, "HSDK_CONFIG_MANAGER_IMPL_GET_VR_6_DOF_HANDLER_CONFIG") != 0) {
			return -1;
		}
		std::memcpy(value, parameters.data(), size);
		return size;
	}

	const char *
	open(const Module *interface, int &status, const char *model = nullptr)
	{
		module = interface;
		if (!module || module->tag != 0x00000001abcd2012ULL || !module->name ||
		    std::strcmp(module->name, "VR6Dof") != 0 || !module->create || !module->destroy ||
		    !module->init || !module->start || !module->stop || !module->deinit || !module->push ||
		    !module->get || !module->register_callbacks || !module->unregister_callbacks || (model && !module->set)) {
			return "Unsupported hand-tracking interface";
		}
		module->register_callbacks(&callbacks);
		registered = true;
		instance = module->create("fmt:yuv@res:480p@rot:0;fmt:imu@org:helmet;fmt:imu@org:controller;fmt:pose@org:slam",
		                          "fmt:pose@org:vr6dof");
		if (!instance) return "Hand tracking could not create a session";
		if (model) {
			status = module->set(instance, "fmt:ai_path@org:vr6dof", std::strlen(model), model);
			if (status < 0) return "Hand tracking model path";
		}
		status = module->init(instance);
		if (status != 0) return "Hand tracking init";
		initialized = true;
		status = module->start(instance);
		if (status != 0) return "Hand tracking start";
		started = true;
		return nullptr;
	}

	int
	close()
	{
		int stop_status = started ? module->stop(instance) : 0;
		int deinit_status = initialized ? module->deinit(instance) : 0;
		if (instance) module->destroy(instance);
		if (registered) module->unregister_callbacks();
		// The PC sample keeps a reference to its last queued frame after the workers have stopped.
		unreleased_inputs += imu_inputs.clear_after_shutdown() + stereo_inputs.clear_after_shutdown();
		instance = nullptr;
		started = initialized = registered = false;
		return stop_status != 0 ? stop_status : deinit_status;
	}

	~HandSession() { close(); }

	int
	push_imu(bool right, uint64_t ns, const std::array<float, 6> &axes)
	{
		auto *sample = new (std::nothrow) HandImu(right, ns, axes);
		if (!sample) return -12;
		return push(imu_inputs, sample->packet);
	}

	int
	push_stereo(HandStereo *stereo)
	{
		return push(stereo_inputs, stereo->packet);
	}

	template <size_t Capacity>
	int
	push(HandPackets<Capacity> &pool, Packet &packet)
	{
		if (pool.hold(packet)) return module->push(instance, &packet);
		release(nullptr, &packet);
		return -11;
	}

	int
	poses(std::vector<HandPose> &data, uint64_t ns)
	{
		data.resize(2);
		Packet packet("fmt:pose@org:vr6dof", sizeof(data), ns, &data);
		return module->get(instance, &packet);
	}
};

} // namespace
#endif

#if defined(__ANDROID__) && defined(__aarch64__)
#include <mutex>

namespace {

struct AndroidSession : Session {
	void *library = nullptr;

	~AndroidSession()
	{
		close();
		if (library) dlclose(library);
	}
};

struct AndroidHands : HandSession {
	static constexpr const char *names[] = {"libc++_shared.so", "libhiai_ir.so", "libhiai.so", "libhcl.so",
	                                      "libhiai_hcl_model_runtime.so", "libVR6Dof.so"};
	std::array<void *, std::size(names)> libraries{};
	std::vector<HandPose> output;

	~AndroidHands()
	{
		close();
		for (auto library = libraries.rbegin(); library != libraries.rend(); ++library) {
			if (*library) dlclose(*library);
		}
	}
};

// The OEM callback table is process-global. A second Activity must not replace it.
std::mutex session_lock;
std::unique_ptr<AndroidSession> active_session;
std::unique_ptr<AndroidHands> active_hands;

void
throw_failure(JNIEnv *env, const char *message, int status = 0)
{
	char detail[512];
	if (status) {
		std::snprintf(detail, sizeof(detail), "%s returned %d", message, status);
		message = detail;
	}
	jclass error = env->FindClass("java/io/IOException");
	if (error) env->ThrowNew(error, message);
}

} // namespace

#define CV10_JNI(name) Java_org_freedesktop_monado_openxr_1runtime_Cv10KitSlam_##name

extern "C" JNIEXPORT jlong JNICALL
CV10_JNI(nativeOpen)(JNIEnv *env, jclass, jstring library, jbyteArray parameters)
{
	std::lock_guard<std::mutex> lock(session_lock);
	if (active_session || !library || !parameters || env->GetArrayLength(parameters) != parameter_size) {
		throw_failure(env, active_session ? "SLAM session is already open" : "Invalid SLAM configuration");
		return 0;
	}
	std::unique_ptr<AndroidSession> session(new (std::nothrow) AndroidSession);
	if (!session) {
		throw_failure(env, "Could not allocate SLAM session");
		return 0;
	}
	env->GetByteArrayRegion(parameters, 0, parameter_size, reinterpret_cast<jbyte *>(session->parameters.data()));
	if (env->ExceptionCheck()) return 0;
	const char *path = env->GetStringUTFChars(library, nullptr);
	if (!path) return 0;
	session->library = dlopen(path, RTLD_NOW | RTLD_LOCAL);
	if (!session->library) {
		throw_failure(env, dlerror());
		env->ReleaseStringUTFChars(library, path);
		return 0;
	}
	auto get_interface = reinterpret_cast<const Module *(*)()>(dlsym(session->library, "GetSlamInterface"));
	int status = 0;
	const char *error = session->open(get_interface ? get_interface() : nullptr, status);
	env->ReleaseStringUTFChars(library, path);
	if (error) {
		throw_failure(env, error, status);
		return 0;
	}
	active_session = std::move(session);
	return reinterpret_cast<jlong>(active_session.get());
}

extern "C" JNIEXPORT jint JNICALL
CV10_JNI(nativeImu)(JNIEnv *, jclass, jlong handle, jlong ns, jfloat ax, jfloat ay, jfloat az, jfloat gx, jfloat gy,
                  jfloat gz)
{
	std::lock_guard<std::mutex> lock(session_lock);
	auto *session = reinterpret_cast<Session *>(handle);
	if (!session || session != active_session.get() || ns <= 0) return -22;
	int accel = session->push_imu(0, ns, ax, ay, az);
	int gyro = session->push_imu(1, ns, gx, gy, gz);
	return accel != 0 ? accel : gyro;
}

extern "C" JNIEXPORT jint JNICALL
CV10_JNI(nativeStereo)(JNIEnv *env, jclass, jlong handle, jlong ns, jint frame, jint exposure, jboolean a_frame,
                      jbyteArray left, jbyteArray right)
{
	std::lock_guard<std::mutex> lock(session_lock);
	auto *session = reinterpret_cast<Session *>(handle);
	if (!session || session != active_session.get() || ns <= 0 || !left || !right ||
	    env->GetArrayLength(left) != image_bytes || env->GetArrayLength(right) != image_bytes) return -22;
	auto *stereo = new (std::nothrow) Stereo(ns, frame, exposure, a_frame);
	if (!stereo) return -12;
	env->GetByteArrayRegion(left, 0, image_bytes, reinterpret_cast<jbyte *>(stereo->pixels[0]));
	if (!env->ExceptionCheck()) {
		env->GetByteArrayRegion(right, 0, image_bytes, reinterpret_cast<jbyte *>(stereo->pixels[1]));
	}
	if (!env->ExceptionCheck()) return session->push_stereo(stereo);
	release(nullptr, &stereo->packet);
	return -22;
}

extern "C" JNIEXPORT jint JNICALL
CV10_JNI(nativePose)(JNIEnv *env, jclass, jlong handle, jbyteArray destination)
{
	std::lock_guard<std::mutex> lock(session_lock);
	auto *session = reinterpret_cast<Session *>(handle);
	alignas(8) uint8_t pose[0x38] = {};
	if (!session || session != active_session.get() || !destination || env->GetArrayLength(destination) != sizeof(pose)) {
		return -22;
	}
	int status = session->pose(pose);
	env->SetByteArrayRegion(destination, 0, sizeof(pose), reinterpret_cast<const jbyte *>(pose));
	return status;
}

extern "C" JNIEXPORT jint JNICALL
CV10_JNI(nativeClose)(JNIEnv *, jclass, jlong handle)
{
	std::lock_guard<std::mutex> lock(session_lock);
	if (!active_session || active_session.get() != reinterpret_cast<Session *>(handle)) return -22;
	active_hands.reset();
	int status = active_session->close();
	active_session.reset();
	return status;
}

#define CV10_HAND_JNI(name) Java_org_freedesktop_monado_openxr_1runtime_Cv10KitHands_##name

extern "C" JNIEXPORT jlong JNICALL
CV10_HAND_JNI(nativeOpen)(JNIEnv *env, jclass, jstring directory, jstring model, jbyteArray parameters)
{
	std::lock_guard<std::mutex> lock(session_lock);
	if (active_hands || !directory || !model || !parameters || env->GetArrayLength(parameters) != hand_parameter_size) {
		throw_failure(env, active_hands ? "Hand tracking is already open" : "Invalid hand tracking configuration");
		return 0;
	}
	std::unique_ptr<AndroidHands> hands(new (std::nothrow) AndroidHands);
	if (!hands) {
		throw_failure(env, "Could not allocate a hand tracking session");
		return 0;
	}
	env->GetByteArrayRegion(parameters, 0, hand_parameter_size, reinterpret_cast<jbyte *>(hands->parameters.data()));
	if (env->ExceptionCheck()) return 0;
	const char *root = env->GetStringUTFChars(directory, nullptr);
	if (!root) return 0;
	for (size_t i = 0; i < hands->libraries.size(); ++i) {
		char path[512];
		int size = std::snprintf(path, sizeof(path), "%s/%s", root, AndroidHands::names[i]);
		if (size < 0 || static_cast<size_t>(size) >= sizeof(path)) {
			throw_failure(env, "Hand tracking module path is too long");
			break;
		}
		hands->libraries[i] = dlopen(path, RTLD_NOW | RTLD_LOCAL);
		if (!hands->libraries[i]) {
			throw_failure(env, dlerror());
			break;
		}
	}
	env->ReleaseStringUTFChars(directory, root);
	if (env->ExceptionCheck()) return 0;
	const char *model_path = env->GetStringUTFChars(model, nullptr);
	if (!model_path) return 0;
	auto get_interface = reinterpret_cast<const Module *(*)()>(dlsym(hands->libraries.back(), "GetVR6DofInterface"));
	int status = 0;
	const char *error = hands->open(get_interface ? get_interface() : nullptr, status, model_path);
	env->ReleaseStringUTFChars(model, model_path);
	if (error) {
		throw_failure(env, error, status);
		return 0;
	}
	active_hands = std::move(hands);
	return reinterpret_cast<jlong>(active_hands.get());
}

extern "C" JNIEXPORT jint JNICALL
CV10_HAND_JNI(nativeImu)(JNIEnv *, jclass, jlong handle, jboolean right, jlong ns, jfloat ax, jfloat ay, jfloat az,
                       jfloat gx, jfloat gy, jfloat gz)
{
	std::lock_guard<std::mutex> lock(session_lock);
	if (!active_hands || active_hands.get() != reinterpret_cast<AndroidHands *>(handle) || ns <= 0) return -22;
	return active_hands->push_imu(right, ns, {ax, ay, az, gx, gy, gz});
}

extern "C" JNIEXPORT jint JNICALL
CV10_HAND_JNI(nativeStereo)(JNIEnv *env, jclass, jlong handle, jlong ns, jint frame, jint exposure, jboolean a_frame,
                           jbyteArray left, jbyteArray right, jbyteArray supplied_pose)
{
	std::lock_guard<std::mutex> lock(session_lock);
	if (!active_hands || active_hands.get() != reinterpret_cast<AndroidHands *>(handle) || ns <= 0 || !left || !right ||
	    env->GetArrayLength(left) != image_bytes || env->GetArrayLength(right) != image_bytes) return -22;
	alignas(8) uint8_t pose[0x38] = {};
	if (supplied_pose) {
		if (env->GetArrayLength(supplied_pose) != sizeof(pose)) return -22;
		env->GetByteArrayRegion(supplied_pose, 0, sizeof(pose), reinterpret_cast<jbyte *>(pose));
		if (env->ExceptionCheck()) return -22;
	} else {
		if (!active_session || active_session->pose(pose, ns) != 0) return 1;
		uint32_t state0 = 0, state1 = 0;
		std::memcpy(&state0, &pose[0x8], sizeof(state0));
		std::memcpy(&state1, &pose[0xc], sizeof(state1));
		if (!state0 || !state1 || !pose[0x30]) return 1;
	}
	try {
		auto *stereo = new HandStereo(ns, frame, exposure, pose, active_session.get(), a_frame);
		env->GetByteArrayRegion(left, 0, image_bytes, reinterpret_cast<jbyte *>(stereo->pixels[0]));
		if (!env->ExceptionCheck()) {
			env->GetByteArrayRegion(right, 0, image_bytes, reinterpret_cast<jbyte *>(stereo->pixels[1]));
		}
		if (!env->ExceptionCheck()) return active_hands->push_stereo(stereo);
		release(nullptr, &stereo->packet);
		return -22;
	} catch (const std::bad_alloc &) {
		return -12;
	}
}

extern "C" JNIEXPORT jint JNICALL
CV10_HAND_JNI(nativePoses)(JNIEnv *env, jclass, jlong handle, jlong ns, jbyteArray destination)
{
	std::lock_guard<std::mutex> lock(session_lock);
	if (!active_hands || active_hands.get() != reinterpret_cast<AndroidHands *>(handle) || ns <= 0 || !destination ||
	    env->GetArrayLength(destination) != 2 * sizeof(HandPose)) return -22;
	try {
		int status = active_hands->poses(active_hands->output, ns);
		if (active_hands->output.size() != 2) return -22;
		env->SetByteArrayRegion(destination, 0, 2 * sizeof(HandPose),
		                        reinterpret_cast<const jbyte *>(active_hands->output.data()));
		return status;
	} catch (const std::bad_alloc &) {
		return -12;
	}
}

extern "C" JNIEXPORT jint JNICALL
CV10_HAND_JNI(nativeClose)(JNIEnv *, jclass, jlong handle)
{
	std::lock_guard<std::mutex> lock(session_lock);
	if (!active_hands || active_hands.get() != reinterpret_cast<AndroidHands *>(handle)) return -22;
	int status = active_hands->close();
	active_hands.reset();
	return status;
}
#endif
