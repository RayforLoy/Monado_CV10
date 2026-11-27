// SPDX-License-Identifier: GPL-3.0-or-later
#include "cv10_controller.h"
#include "cv10_pose_models.h"
#include "cv10_device.h"

#include <stdbool.h>
#include <stdio.h>

#include "android/android_globals.h"
#include "math/m_api.h"
#include "math/m_relation_history.h"
#include "os/os_threading.h"
#include "os/os_time.h"
#include "util/u_device.h"
#include "util/u_logging.h"
#include "util/u_time.h"

#define CV10_CONTROLLER_READER "org.freedesktop.monado.openxr_runtime.Cv10ControllerReader"
#define CV10_REPORT_TIMEOUT_NS (250 * U_TIME_1MS_IN_NS)

enum cv10_controller_input
{
	CV10_SELECT,
	CV10_MENU,
	CV10_GRIP,
	CV10_AIM,
	CV10_INPUT_COUNT,
};

struct cv10_controller
{
	struct xrt_device base;
	struct xrt_device *head;
	struct cv10_arm_model arm;
	struct os_thread_helper worker;
	struct m_relation_history *history;
	int64_t report_ns;
	int64_t pose_ns;
	int64_t select_ns;
	int64_t menu_ns;
	bool select;
	bool menu;
	bool orientation_valid;
	bool phone;
	float cf20_yaw;
	bool kit;
	bool right;
};

static bool
clear_exception(JNIEnv *env)
{
	if (!(*env)->ExceptionCheck(env)) {
		return false;
	}
	(*env)->ExceptionDescribe(env);
	(*env)->ExceptionClear(env);
	return true;
}

static bool
controller_configured(void)
{
	JavaVM *vm = (JavaVM *)android_globals_get_vm();
	jobject context = android_globals_get_context();
	JNIEnv *env = NULL;
	if (vm == NULL || context == NULL) {
		return false;
	}
	jint result = (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6);
	bool attached = result == JNI_EDETACHED && (*vm)->AttachCurrentThread(vm, &env, NULL) == JNI_OK;
	if (!attached && result != JNI_OK) {
		return false;
	}

	bool configured = false;
	if ((*env)->PushLocalFrame(env, 16) == JNI_OK) {
		jclass cls = cv10_load_java_class(env, context, CV10_CONTROLLER_READER);
		if (cls != NULL) {
			jmethodID check = (*env)->GetStaticMethodID(env, cls, "isConfigured", "(Landroid/content/Context;)Z");
			if (check != NULL) {
				configured = (*env)->CallStaticBooleanMethod(env, cls, check, context);
			}
		}
		configured = !clear_exception(env) && configured;
		(*env)->PopLocalFrame(env, NULL);
	} else {
		clear_exception(env);
	}
	if (attached) {
		(*vm)->DetachCurrentThread(vm);
	}
	return configured;
}

static bool
report_is_fresh(int64_t timestamp)
{
	int64_t now = os_monotonic_get_ns();
	return timestamp > 0 && timestamp <= now && now - timestamp <= CV10_REPORT_TIMEOUT_NS;
}

static void
update_report(struct cv10_controller *d, int64_t timestamp, int64_t pose_ns, const float *values)
{
	os_thread_helper_lock(&d->worker);
	if (!report_is_fresh(timestamp)) {
		if (d->report_ns > 0) {
			m_relation_history_clear(d->history);
		}
		d->report_ns = 0;
		d->pose_ns = 0;
		d->orientation_valid = false;
		d->arm = (struct cv10_arm_model){0};
		d->select = false;
		d->menu = false;
	} else {
		const bool phone = !d->kit && values[7] != 0;
		const float yaw = (!d->kit && !phone) ? values[8] : 0.0f;
		if (d->phone != phone || d->cf20_yaw != yaw) {
			m_relation_history_clear(d->history);
			d->pose_ns = 0;
			d->report_ns = 0;
			d->phone = phone;
			d->cf20_yaw = yaw;
			d->arm = (struct cv10_arm_model){0};
		}
		const bool select = values[5] != 0;
		const bool menu = values[6] != 0;
		if (d->report_ns == 0 || d->select != select) {
			d->select_ns = timestamp;
		}
		if (d->report_ns == 0 || d->menu != menu) {
			d->menu_ns = timestamp;
		}
		d->select = select;
		d->menu = menu;
		d->report_ns = timestamp;
		bool valid = values[4] != 0 && report_is_fresh(pose_ns);
		if (valid && pose_ns > d->pose_ns) {
			struct xrt_space_relation relation = XRT_SPACE_RELATION_ZERO;
			relation.pose.orientation = (struct xrt_quat){values[0], values[1], values[2], values[3]};
			math_quat_normalize(&relation.pose.orientation);
			relation.relation_flags = XRT_SPACE_RELATION_ORIENTATION_VALID_BIT |
			                          XRT_SPACE_RELATION_ORIENTATION_TRACKED_BIT |
			                          XRT_SPACE_RELATION_POSITION_VALID_BIT;
			if (d->kit) {
				relation.pose.position = (struct xrt_vec3){values[7], values[8], values[9]};
				relation.relation_flags |= XRT_SPACE_RELATION_POSITION_TRACKED_BIT;
			} else if (phone) {
				// Include the old standalone right-hand origin offset in the shared floor origin.
				relation.pose.position = (struct xrt_vec3){0.40f, 1.00f, -0.85f};
			} else if (yaw != 0.0f) {
				const struct xrt_vec3 up = {0, 1, 0};
				struct xrt_quat alignment, aligned;
				math_quat_from_angle_vector(yaw, &up, &alignment);
				math_quat_rotate(&alignment, &relation.pose.orientation, &aligned);
				relation.pose.orientation = aligned;
			}
			m_relation_history_push(d->history, &relation, pose_ns);
			d->pose_ns = pose_ns;
		} else if (!valid && d->orientation_valid) {
			m_relation_history_clear(d->history);
			d->pose_ns = 0;
			d->arm = (struct cv10_arm_model){0};
		}
		d->orientation_valid = valid;
	}
	os_thread_helper_unlock(&d->worker);
}

static void
kit_controller_reports(struct cv10_controller *d, JNIEnv *env, jobject context)
{
	jclass cls = cv10_load_java_class(env, context, CV10_KIT_TRACKING_SERVICE);
	if (clear_exception(env) || !cls) return;
	jmethodID read = (*env)->GetStaticMethodID(env, cls, "readHand", "(Z[F[J)J");
	if (!read) return;
	jfloatArray buffer = (*env)->NewFloatArray(env, 10);
	if (!buffer) return;
	jlongArray pose_time = (*env)->NewLongArray(env, 1);
	if (!pose_time) return;
	while (os_thread_helper_is_running(&d->worker)) {
		int64_t timestamp = (*env)->CallStaticLongMethod(env, cls, read, (jboolean)d->right, buffer, pose_time);
		if (clear_exception(env)) break;
		if (report_is_fresh(timestamp)) {
			float values[10];
			jlong pose_ns;
			(*env)->GetFloatArrayRegion(env, buffer, 0, 10, values);
			if (clear_exception(env)) break;
			(*env)->GetLongArrayRegion(env, pose_time, 0, 1, &pose_ns);
			if (clear_exception(env)) break;
			update_report(d, timestamp, pose_ns, values);
		} else {
			update_report(d, 0, 0, NULL);
		}
		os_nanosleep(4 * U_TIME_1MS_IN_NS);
	}
	update_report(d, 0, 0, NULL);
}

static void *
controller_worker(void *ptr)
{
	struct cv10_controller *d = ptr;
	JavaVM *vm = (JavaVM *)android_globals_get_vm();
	jobject context = android_globals_get_context();
	JNIEnv *env = NULL;
	if (vm == NULL || context == NULL || (*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK) {
		return NULL;
	}
	if (d->kit) {
		kit_controller_reports(d, env, context);
		goto done;
	}

	jclass cls = cv10_load_java_class(env, context, CV10_CONTROLLER_READER);
	if (cls == NULL) {
		goto done;
	}
	jmethodID open = (*env)->GetStaticMethodID(env, cls, "open",
	                                         "(Landroid/content/Context;)Lorg/freedesktop/monado/"
	                                         "openxr_runtime/Cv10ControllerReader;");
	if (open == NULL) {
		goto done;
	}
	jmethodID read = (*env)->GetMethodID(env, cls, "read", "([F)J");
	if (read == NULL) {
		goto done;
	}
	jmethodID close = (*env)->GetMethodID(env, cls, "close", "()V");
	if (close == NULL) {
		goto done;
	}
	jfloatArray buffer = (*env)->NewFloatArray(env, 9);
	if (buffer == NULL) {
		goto done;
	}
	jobject reader = (*env)->CallStaticObjectMethod(env, cls, open, context);
	if (clear_exception(env) || reader == NULL) {
		goto done;
	}
	int64_t previous = 0;
	while (os_thread_helper_is_running(&d->worker)) {
		int64_t timestamp = (*env)->CallLongMethod(env, reader, read, buffer);
		if (clear_exception(env)) {
			break;
		}
		int64_t current = report_is_fresh(timestamp) ? timestamp : 0;
		if (current != previous) {
			if (current == 0) {
				update_report(d, 0, 0, NULL);
			} else {
				float values[9];
				(*env)->GetFloatArrayRegion(env, buffer, 0, 9, values);
				if (clear_exception(env)) {
					break;
				}
				update_report(d, current, current, values);
			}
		}
		previous = current;
		os_nanosleep((current > 0 ? 4 : 20) * U_TIME_1MS_IN_NS);
	}
	update_report(d, 0, 0, NULL);
	(*env)->CallVoidMethod(env, reader, close);

done:
	clear_exception(env);
	(*vm)->DetachCurrentThread(vm);
	return NULL;
}

static xrt_result_t
controller_update_inputs(struct xrt_device *xdev)
{
	struct cv10_controller *d = (struct cv10_controller *)xdev;
	os_thread_helper_lock(&d->worker);
	bool fresh = report_is_fresh(d->report_ns);
	xdev->inputs[CV10_SELECT].active = fresh;
	xdev->inputs[CV10_SELECT].timestamp = d->select_ns;
	xdev->inputs[CV10_SELECT].value.boolean = fresh && d->select;
	xdev->inputs[CV10_MENU].active = fresh;
	xdev->inputs[CV10_MENU].timestamp = d->menu_ns;
	xdev->inputs[CV10_MENU].value.boolean = fresh && d->menu;
	bool tracked = fresh && d->orientation_valid && report_is_fresh(d->pose_ns);
	xdev->inputs[CV10_GRIP].active = tracked;
	xdev->inputs[CV10_GRIP].timestamp = d->pose_ns;
	xdev->inputs[CV10_AIM].active = tracked;
	xdev->inputs[CV10_AIM].timestamp = d->pose_ns;
	os_thread_helper_unlock(&d->worker);
	return XRT_SUCCESS;
}

static xrt_result_t
controller_pose(struct xrt_device *xdev, enum xrt_input_name name, int64_t at_ns, struct xrt_space_relation *out)
{
	struct cv10_controller *d = (struct cv10_controller *)xdev;
	*out = (struct xrt_space_relation)XRT_SPACE_RELATION_ZERO;
	out->pose.orientation.w = 1;
	if (name != XRT_INPUT_SIMPLE_GRIP_POSE && name != XRT_INPUT_SIMPLE_AIM_POSE) {
		return XRT_ERROR_INPUT_UNSUPPORTED;
	}
	os_thread_helper_lock(&d->worker);
	if (report_is_fresh(d->report_ns) && d->orientation_valid && report_is_fresh(d->pose_ns)) {
		int64_t query = at_ns > d->pose_ns ? d->pose_ns : at_ns;
		if (m_relation_history_get(d->history, query, out) == M_RELATION_HISTORY_RESULT_INVALID) {
			out->pose.orientation.w = 1;
		} else if (!d->kit && !d->phone) {
			struct xrt_space_relation head = XRT_SPACE_RELATION_ZERO;
			if (xrt_device_get_tracked_pose(d->head, XRT_INPUT_GENERIC_HEAD_POSE, at_ns, &head) != XRT_SUCCESS) {
				head = (struct xrt_space_relation)XRT_SPACE_RELATION_ZERO;
			}
			cv10_arm_model_apply(&d->arm, query, &head, name == XRT_INPUT_SIMPLE_AIM_POSE, out);
		}
	}
	os_thread_helper_unlock(&d->worker);
	return XRT_SUCCESS;
}

static void
controller_destroy(struct xrt_device *xdev)
{
	struct cv10_controller *d = (struct cv10_controller *)xdev;
	os_thread_helper_destroy(&d->worker);
	m_relation_history_destroy(&d->history);
	u_device_free(xdev);
}

static struct xrt_device *
controller_create(struct xrt_tracking_origin *origin, struct xrt_device *head, bool right)
{
	struct cv10_controller *d =
	    U_DEVICE_ALLOCATE(struct cv10_controller, origin ? 0 : U_DEVICE_ALLOC_TRACKING_NONE, CV10_INPUT_COUNT, 0);
	if (d == NULL) {
		return NULL;
	}
	d->head = head;
	d->kit = head == NULL;
	d->right = right;
	struct xrt_device *xdev = &d->base;
	xdev->name = XRT_DEVICE_SIMPLE_CONTROLLER;
	xdev->device_type = right ? XRT_DEVICE_TYPE_RIGHT_HAND_CONTROLLER : XRT_DEVICE_TYPE_LEFT_HAND_CONTROLLER;
	xdev->supported.orientation_tracking = true;
	xdev->tracking_origin = origin;
	if (d->kit) {
		xdev->supported.position_tracking = true;
		snprintf(xdev->str, sizeof(xdev->str), "Huawei CV10 6DoF %s controller", right ? "right" : "left");
		snprintf(xdev->serial, sizeof(xdev->serial), "Huawei-CV10-Kit-%s", right ? "right" : "left");
	} else {
		snprintf(xdev->str, sizeof(xdev->str), "CV10 Right Controller");
		snprintf(xdev->serial, sizeof(xdev->serial), "CV10-Right");
	}
	u_device_populate_function_pointers(xdev, controller_pose, controller_destroy);
	xdev->update_inputs = controller_update_inputs;
	xdev->inputs[CV10_SELECT].name = XRT_INPUT_SIMPLE_SELECT_CLICK;
	xdev->inputs[CV10_MENU].name = XRT_INPUT_SIMPLE_MENU_CLICK;
	xdev->inputs[CV10_GRIP].name = XRT_INPUT_SIMPLE_GRIP_POSE;
	xdev->inputs[CV10_AIM].name = XRT_INPUT_SIMPLE_AIM_POSE;
	for (size_t i = 0; i < CV10_INPUT_COUNT; i++) {
		xdev->inputs[i].active = false;
	}

	if (os_thread_helper_init(&d->worker) != 0) {
		u_device_free(xdev);
		return NULL;
	}
	m_relation_history_create(&d->history);
	if (d->history == NULL || os_thread_helper_start(&d->worker, controller_worker, d) != 0) {
		controller_destroy(xdev);
		return NULL;
	}
	U_LOG_I("Configured %s as an OpenXR controller", xdev->str);
	return xdev;
}

struct xrt_device *
cv10_controller_create(struct xrt_device *head)
{
	return controller_configured() ? controller_create(head->tracking_origin, head, true) : NULL;
}

struct xrt_device *
cv10_kit_controller_create(struct xrt_tracking_origin *origin, bool right)
{
	return controller_create(origin, NULL, right);
}
