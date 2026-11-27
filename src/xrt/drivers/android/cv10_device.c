// SPDX-License-Identifier: GPL-3.0-or-later
#include <stdbool.h>
#include "cv10_device.h"
#include "android/android_globals.h"
#include "math/m_api.h"
#include "math/m_imu_3dof.h"
#include "math/m_relation_history.h"
#include "os/os_threading.h"
#include "os/os_time.h"
#include "util/u_device.h"
#include "util/u_distortion_mesh.h"
#include "util/u_logging.h"
#include "util/u_tracking_origin.h"
#include <jni.h>
#include <stdio.h>
#include <math.h>

struct cv10_device
{
	struct xrt_device base;
	struct os_thread_helper worker;
	struct m_relation_history *history;
	struct m_imu_3dof fusion;
	struct os_thread_helper kit_worker;
	struct m_relation_history *kit_history;
};

static bool
jni_error(JNIEnv *env)
{
	if (!(*env)->ExceptionCheck(env)) return false;
	(*env)->ExceptionDescribe(env);
	(*env)->ExceptionClear(env);
	return true;
}

jclass
cv10_load_java_class(JNIEnv *env, jobject context, const char *class_name)
{
	jclass ctx_class = (*env)->GetObjectClass(env, context);
	if (!ctx_class) return NULL;
	jmethodID get_loader = (*env)->GetMethodID(env, ctx_class, "getClassLoader", "()Ljava/lang/ClassLoader;");
	if (!get_loader) return NULL;
	jobject loader = (*env)->CallObjectMethod(env, context, get_loader);
	if (jni_error(env) || !loader) return NULL;
	jclass loader_class = (*env)->GetObjectClass(env, loader);
	if (!loader_class) return NULL;
	jmethodID load = (*env)->GetMethodID(env, loader_class, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
	if (!load) return NULL;
	jstring name = (*env)->NewStringUTF(env, class_name);
	if (!name) return NULL;
	return (jclass)(*env)->CallObjectMethod(env, loader, load, name);
}

bool
cv10_kit_mode(const char *method_name)
{
	JavaVM *vm = (JavaVM *)android_globals_get_vm();
	jobject context = android_globals_get_context();
	JNIEnv *env = NULL;
	if (!vm || !context) return false;
	bool attached = (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) == JNI_EDETACHED;
	if (attached && (*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK) return false;
	bool requested = false;
	if (env && (*env)->PushLocalFrame(env, 16) == JNI_OK) {
		jclass cls = cv10_load_java_class(env, context, CV10_KIT_TRACKING_SERVICE);
		if (!jni_error(env) && cls) {
			jmethodID method = (*env)->GetStaticMethodID(env, cls, method_name, "()Z");
			if (method) requested = (*env)->CallStaticBooleanMethod(env, cls, method);
			if (jni_error(env)) requested = false;
		}
		(*env)->PopLocalFrame(env, NULL);
	}
	if (env) jni_error(env);
	if (attached) (*vm)->DetachCurrentThread(vm);
	return requested;
}

static void *
cv10_kit_worker(void *ptr)
{
	struct cv10_device *d = ptr;
	JavaVM *vm = (JavaVM *)android_globals_get_vm();
	jobject context = android_globals_get_context();
	JNIEnv *env = NULL;
	if (!vm || !context || (*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK) return NULL;
	jclass cls = cv10_load_java_class(env, context, CV10_KIT_TRACKING_SERVICE);
	if (jni_error(env) || !cls) goto done;
	jmethodID read = (*env)->GetStaticMethodID(env, cls, "readPose", "([F)J");
	if (!read) goto done;
	jfloatArray buffer = (*env)->NewFloatArray(env, 7);
	if (!buffer) goto done;

	int64_t previous = 0;
	while (os_thread_helper_is_running(&d->kit_worker)) {
		int64_t time_ns = (*env)->CallStaticLongMethod(env, cls, read, buffer);
		if (jni_error(env)) break;
		if (time_ns > previous) {
			float v[7];
			(*env)->GetFloatArrayRegion(env, buffer, 0, 7, v);
			if (jni_error(env)) break;
			struct xrt_space_relation relation = XRT_SPACE_RELATION_ZERO;
			relation.pose.position = (struct xrt_vec3){v[0], v[1], v[2]};
			relation.pose.orientation = (struct xrt_quat){v[3], v[4], v[5], v[6]};
			relation.relation_flags = XRT_SPACE_RELATION_ORIENTATION_VALID_BIT |
			                          XRT_SPACE_RELATION_ORIENTATION_TRACKED_BIT |
			                          XRT_SPACE_RELATION_POSITION_VALID_BIT |
			                          XRT_SPACE_RELATION_POSITION_TRACKED_BIT;
			m_relation_history_push(d->kit_history, &relation, time_ns);
			previous = time_ns;
		} else if (time_ns == 0 && previous != 0) {
			m_relation_history_clear(d->kit_history);
			previous = 0;
		}
		os_nanosleep(10000000);
	}
done:
	m_relation_history_clear(d->kit_history);
	jni_error(env);
	(*vm)->DetachCurrentThread(vm);
	return NULL;
}

static void *
cv10_worker(void *ptr)
{
	struct cv10_device *d = ptr;
	JavaVM *vm = (JavaVM *)android_globals_get_vm();
	jobject context = android_globals_get_context();
	JNIEnv *env = NULL;
	if (!vm || !context || (*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK) {
		U_LOG_E("CV10 JNI context unavailable");
		return NULL;
	}
	jclass cls = cv10_load_java_class(env, context, "org.freedesktop.monado.openxr_runtime.Cv10UsbReader");
	if (jni_error(env) || !cls) goto done;
	jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "(Landroid/content/Context;)V");
	if (!ctor) goto done;
	jmethodID read = (*env)->GetMethodID(env, cls, "read", "([F)J");
	if (!read) goto done;
	jmethodID close = (*env)->GetMethodID(env, cls, "close", "()V");
	if (!close) goto done;
	jfloatArray output = (*env)->NewFloatArray(env, 6);
	if (!output) goto done;
	jobject reader = (*env)->NewObject(env, cls, ctor, context);
	if (jni_error(env) || !reader) goto done;

	int64_t previous = 0, offset = 0, last_mapped = 0, last_log = 0;
	unsigned long long sample_count = 0;
	float peak_rad_s = 0;
	while (os_thread_helper_is_running(&d->worker)) {
		int64_t sample_ns = (*env)->CallLongMethod(env, reader, read, output);
		if (jni_error(env)) break;
		if (sample_ns == 0) continue;
		if (sample_ns < 0 || (previous && (sample_ns <= previous || sample_ns - previous > 50000000))) {
			m_relation_history_clear(d->history);
			m_imu_3dof_reset(&d->fusion);
			previous = 0;
			last_mapped = 0;
			peak_rad_s = 0;
			last_log = 0;
			if (sample_ns < 0) continue;
		}
		float values[6];
		(*env)->GetFloatArrayRegion(env, output, 0, 6, values);
		if (jni_error(env)) break;
		const int64_t arrival = os_monotonic_get_ns();
		const int64_t observed_offset = arrival - sample_ns;
		// A USB arrival bounds sample time from above. Follow the low-latency
		// envelope, allowing gradual clock drift without integrating USB jitter.
		if (!previous || observed_offset < offset) offset = observed_offset;
		else {
			int64_t drift = (sample_ns - previous) / 1000;
			offset += observed_offset - offset < drift ? observed_offset - offset : drift;
		}
		int64_t mapped = sample_ns + offset;
		if (mapped <= last_mapped) mapped = last_mapped + 1;
		const struct xrt_vec3 accel = {values[0], values[1], values[2]};
		const struct xrt_vec3 gyro = {values[3], values[4], values[5]};
		m_imu_3dof_update(&d->fusion, sample_ns, &accel, &gyro);
		struct xrt_space_relation rel = XRT_SPACE_RELATION_ZERO;
		rel.pose.orientation = d->fusion.rot;
		math_quat_rotate_vec3(&rel.pose.orientation, &gyro, &rel.angular_velocity);
		rel.relation_flags = XRT_SPACE_RELATION_ORIENTATION_VALID_BIT | XRT_SPACE_RELATION_ORIENTATION_TRACKED_BIT |
		                     XRT_SPACE_RELATION_ANGULAR_VELOCITY_VALID_BIT | XRT_SPACE_RELATION_POSITION_VALID_BIT;
		m_relation_history_push(d->history, &rel, mapped);
		if (!previous) U_LOG_I("CV10 3DoF tracking initialized from USB, first mapped sample %lld ns", (long long)mapped);
		sample_count++;
		float speed = sqrtf(gyro.x*gyro.x + gyro.y*gyro.y + gyro.z*gyro.z);
		if (speed > peak_rad_s) peak_rad_s = speed;
		if (arrival - last_log >= 2000000000) {
			U_LOG_I("CV10 USB pose samples=%llu q_xyzw=(%.4f,%.4f,%.4f,%.4f) "
			        "gyro_rad_s=(%.3f,%.3f,%.3f) peak_rad_s=%.3f",
			        sample_count, rel.pose.orientation.x, rel.pose.orientation.y, rel.pose.orientation.z,
			        rel.pose.orientation.w, gyro.x, gyro.y, gyro.z, peak_rad_s);
			last_log = arrival;
			peak_rad_s = 0;
		}
		previous = sample_ns;
		last_mapped = mapped;
	}
	(*env)->CallVoidMethod(env, reader, close);
done:
	jni_error(env);
	(*vm)->DetachCurrentThread(vm);
	return NULL;
}

static xrt_result_t
cv10_pose(struct xrt_device *xdev, enum xrt_input_name name, int64_t at_ns, struct xrt_space_relation *out)
{
	struct cv10_device *d = (struct cv10_device *)xdev;
	*out = (struct xrt_space_relation)XRT_SPACE_RELATION_ZERO;
	out->pose.orientation.w = 1;
	if (name != XRT_INPUT_GENERIC_HEAD_POSE) return XRT_ERROR_INPUT_UNSUPPORTED;
	int64_t latest = 0;
	struct xrt_space_relation last;
	int64_t now = os_monotonic_get_ns();
	if (d->kit_history && m_relation_history_get_latest(d->kit_history, &latest, &last) && now - latest <= 250000000) {
		*out = last;
		if (at_ns < latest) {
			struct xrt_space_relation historic;
			if (m_relation_history_get(d->kit_history, at_ns, &historic) != M_RELATION_HISTORY_RESULT_INVALID) {
				*out = historic;
			}
		}
		return XRT_SUCCESS;
	}
	if (!m_relation_history_get_latest(d->history, &latest, &last) || now - latest > 250000000)
		return XRT_SUCCESS;
	// Cap prediction even if a client requests a distant future pose.
	int64_t query = at_ns > latest + 50000000 ? latest + 50000000 : at_ns;
	m_relation_history_get(d->history, query, out);
	if (d->kit_history) out->relation_flags &= ~XRT_SPACE_RELATION_POSITION_VALID_BIT;
	return XRT_SUCCESS;
}

static void
cv10_destroy(struct xrt_device *xdev)
{
	struct cv10_device *d = (struct cv10_device *)xdev;
	if (d->kit_history) {
		os_thread_helper_destroy(&d->kit_worker);
		m_relation_history_destroy(&d->kit_history);
	}
	os_thread_helper_destroy(&d->worker);
	m_relation_history_destroy(&d->history);
	m_imu_3dof_close(&d->fusion);
	u_device_free(xdev);
}

static xrt_result_t
cv10_get_view_poses(struct xrt_device *xdev,
                    const struct xrt_vec3 *default_eye_relation,
                    int64_t at_ns,
                    enum xrt_view_type view_type,
                    uint32_t view_count,
                    struct xrt_space_relation *out_head_relation,
                    struct xrt_fov *out_fovs,
                    struct xrt_pose *out_poses)
{
	struct xrt_vec3 eye_relation = *default_eye_relation;
	const int ipd_mm = android_globals_get_cv10_ipd_mm();
	if (ipd_mm > 0) {
		eye_relation.x = (float)ipd_mm / 1000.0f;
	}

	return u_device_get_view_poses(xdev, &eye_relation, at_ns, view_type, view_count, out_head_relation, out_fovs,
	                               out_poses);
}

struct xrt_device *
cv10_device_create(void)
{
	struct cv10_device *d = U_DEVICE_ALLOCATE(struct cv10_device, U_DEVICE_ALLOC_HMD | U_DEVICE_ALLOC_TRACKING_NONE, 1, 0);
	if (!d) return NULL;
	struct xrt_device *xdev = &d->base;
	xdev->name = XRT_DEVICE_GENERIC_HMD;
	xdev->device_type = XRT_DEVICE_TYPE_HMD;
	snprintf(xdev->str, sizeof(xdev->str), "Huawei VR Glass CV10 (USB IMU)");
	snprintf(xdev->serial, sizeof(xdev->serial), "Huawei-CV10-USB");
	u_device_populate_function_pointers(xdev, cv10_pose, cv10_destroy);
	xdev->get_view_poses = cv10_get_view_poses;
	xdev->get_visibility_mask = u_device_get_visibility_mask;
	xdev->inputs[0].name = XRT_INPUT_GENERIC_HEAD_POSE;
	xdev->supported.orientation_tracking = true;
	xdev->supported.position_tracking = false;
	xdev->hmd->view_count = 2;
	// A symmetric test projection without lens calibration. FOV is provisional.
	struct u_device_simple_info optics = {
	    .display = {3200, 1600, 0.128f, 0.064f},
	    .lens_horizontal_separation_meters = 0.064f, .lens_vertical_position_meters = 0.032f,
	    .fov = {1.3962634f, 1.3962634f},
	};
	if (!u_device_setup_split_side_by_side(xdev, &optics)) { u_device_free(xdev); return NULL; }
	xdev->hmd->views[0].rot = u_device_rotation_left;
	xdev->hmd->views[1].rot = u_device_rotation_right;
	xdev->hmd->screens[0].nominal_frame_interval_ns = 11111111;
	u_distortion_mesh_set_none(xdev);
	if (os_thread_helper_init(&d->worker) != 0) { u_device_free(xdev); return NULL; }
	m_imu_3dof_init(&d->fusion, M_IMU_3DOF_USE_GRAVITY_DUR_20MS);
	m_relation_history_create(&d->history);
	if (!d->history || os_thread_helper_start(&d->worker, cv10_worker, d) != 0) {
		cv10_destroy(xdev);
		return NULL;
	}
	if (cv10_kit_mode("trackingRequested") && os_thread_helper_init(&d->kit_worker) == 0) {
		m_relation_history_create(&d->kit_history);
		if (d->kit_history && os_thread_helper_start(&d->kit_worker, cv10_kit_worker, d) == 0) {
			xdev->supported.position_tracking = true;
			u_tracking_origin_init(xdev->tracking_origin, XRT_TRACKING_TYPE_EXTERNAL_SLAM, "Huawei CV10 locator");
		} else {
			os_thread_helper_destroy(&d->kit_worker);
			m_relation_history_destroy(&d->kit_history);
		}
	}
	return xdev;
}
