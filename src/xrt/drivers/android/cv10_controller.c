// SPDX-License-Identifier: GPL-3.0-or-later
#include "cv10_controller.h"
#include "cv10_cf20_pose_cache.h"
#include "cv10_pose_models.h"
#include "cv10_touchpad.h"
#include "cv10_device.h"

#include <stdbool.h>
#include <stdio.h>
#include <string.h>

#include "xrt/xrt_system.h"
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
// 0..14: orientation, buttons, source, yaw, fixed position flag and XYZ, link/Home epochs.
// Phone samples: 0..3 calibrated ray, 8..11 physical grip, 14 center epoch, 15 touch, 16/17 XY.
// 15..19: raw battery, button mask, trackpad X/Y, role mode; physical CF20 only.
#define CV10_CF20_REPORT_FLOATS 20

enum cv10_controller_input
{
	CV10_SELECT,
	CV10_MENU,
	CV10_GRIP,
	CV10_AIM,
	CV10_MODEL,
	CV10_PAD_CLICK, CV10_PAD_TOUCH, CV10_PAD_XY,
	// Physical CF20 inputs only from here on.
	CV10_HOME, CV10_BACK, CV10_VOLUME_UP, CV10_VOLUME_DOWN, CV10_TRIGGER, CV10_TRIGGER_VALUE,
	CV10_INPUT_COUNT,
};

struct cv10_controller
{
	struct xrt_device base;
	struct xrt_device *head;
	struct cv10_arm_model arm;
	struct cv10_cf20_pose_cache grip_cache;
	struct xrt_vec3 fixed_origin;
	uint16_t link_epoch;
	uint16_t home_epoch;
	struct os_thread_helper worker;
	struct m_relation_history *history;
	struct m_relation_history *phone_grip_history;
	int64_t report_ns;
	int64_t pose_ns;
	int64_t select_ns;
	int64_t menu_ns;
	bool select;
	bool menu;
	bool orientation_valid;
	bool phone;
	int battery_percent;
	struct xrt_input samples[CV10_INPUT_COUNT];
	int mode;
	struct xrt_quat previous_orientation;
	struct xrt_system_roles roles;
	unsigned cf20_index, phone_index;
	xrt_result_t (*static_get_roles)(struct xrt_system_devices *, struct xrt_system_roles *);
	bool fixed_position;
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
report_is_fresh(int64_t timestamp)
{
	int64_t now = os_monotonic_get_ns();
	return timestamp > 0 && timestamp <= now && now - timestamp <= CV10_REPORT_TIMEOUT_NS;
}

static void
clear_pose_history_locked(struct cv10_controller *d)
{
	m_relation_history_clear(d->history);
	if (d->phone_grip_history) m_relation_history_clear(d->phone_grip_history);
	d->pose_ns = 0;
	d->arm = (struct cv10_arm_model){0};
	cv10_cf20_pose_cache_clear(&d->grip_cache);
}

static void
update_pad(struct cv10_controller *d, bool pressed, bool touched, float x, float y, int64_t ns)
{
	cv10_update_touchpad(&d->samples[CV10_PAD_CLICK],
	                     &d->samples[CV10_PAD_TOUCH],
	                     &d->samples[CV10_PAD_XY],
	                     pressed, touched, (struct xrt_vec2){x, y}, ns);
}

static void
update_cf20_inputs(struct cv10_controller *d, const float *values, int64_t ns)
{
	unsigned buttons = (unsigned)values[16];
	bool trigger = (buttons & 0x01) != 0;
	cv10_set_button(&d->samples[CV10_TRIGGER], trigger, ns);
	cv10_set_button(&d->samples[CV10_VOLUME_DOWN], (buttons & 0x04) != 0, ns);
	cv10_set_button(&d->samples[CV10_VOLUME_UP], (buttons & 0x08) != 0, ns);
	cv10_set_button(&d->samples[CV10_HOME], (buttons & 0x10) != 0, ns);
	cv10_set_button(&d->samples[CV10_BACK], (buttons & 0x20) != 0, ns);
	struct xrt_input *value = &d->samples[CV10_TRIGGER_VALUE];
	value->value.vec1.x = trigger ? 1.0f : 0.0f;
	value->timestamp = d->samples[CV10_TRIGGER].timestamp;
	value->active = true;
	const bool touched = values[17] != 0 || values[18] != 0;
	update_pad(d, (buttons & 0x02) != 0, touched, values[17] / 127.5f - 1, 1 - values[18] / 127.5f, ns);
	d->battery_percent = (int)values[15];
	d->mode = (int)values[19];
}

static void
update_report(struct cv10_controller *d, int64_t timestamp, int64_t pose_ns, const float *values)
{
	os_thread_helper_lock(&d->worker);
	if (!report_is_fresh(timestamp)) {
		if (d->report_ns > 0) {
			clear_pose_history_locked(d);
			memset(d->samples, 0, sizeof(d->samples));
		}
		d->report_ns = 0;
		d->orientation_valid = false;
		d->select = false;
		d->menu = false;
	} else {
		const bool phone = d->phone;
		// Touch and button events can arrive between two orientation callbacks.
		const int64_t input_ns = phone ? os_monotonic_get_ns() : timestamp;
		if (phone) {
			update_pad(d, values[5] != 0, values[15] != 0, values[16], values[17], input_ns);
		} else if (!d->kit) {
			update_cf20_inputs(d, values, input_ns);
		}
		const float yaw = (!d->kit && !phone) ? values[8] : 0.0f;
		const bool fixed_position = !d->kit && !phone && values[9] != 0;
		const uint16_t link_epoch = (!d->kit && !phone) ? (uint16_t)values[13] : 0;
		const uint16_t home_epoch = !d->kit ? (uint16_t)values[14] : 0;
		const bool moved_origin = fixed_position &&
		    (d->fixed_origin.x != values[10] || d->fixed_origin.y != values[11] || d->fixed_origin.z != values[12]);
		if (d->cf20_yaw != yaw || d->fixed_position != fixed_position || moved_origin ||
		    d->link_epoch != link_epoch || d->home_epoch != home_epoch) {
			clear_pose_history_locked(d);
			d->report_ns = 0;
			d->fixed_position = fixed_position;
			d->cf20_yaw = yaw;
			d->link_epoch = link_epoch;
			d->home_epoch = home_epoch;
			if (fixed_position) {
				d->fixed_origin = (struct xrt_vec3){values[10], values[11], values[12]};
			}
		}
		const bool select = values[5] != 0;
		const bool menu = values[6] != 0;
		if (d->report_ns == 0 || d->select != select) {
			d->select_ns = input_ns;
		}
		if (d->report_ns == 0 || d->menu != menu) {
			d->menu_ns = input_ns;
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
				struct xrt_space_relation body = relation;
				body.pose.orientation = (struct xrt_quat){values[8], values[9], values[10], values[11]};
				math_quat_normalize(&body.pose.orientation);
				m_relation_history_push(d->phone_grip_history, &body, pose_ns);
			} else {
				if (fixed_position) {
					relation.pose.position = d->fixed_origin;
					if (!d->right) relation.pose.position.x *= -1;
				}
				if (yaw != 0.0f) {
					const struct xrt_vec3 up = {0, 1, 0};
					struct xrt_quat alignment, aligned;
					math_quat_from_angle_vector(yaw, &up, &alignment);
					math_quat_rotate(&alignment, &relation.pose.orientation, &aligned);
					relation.pose.orientation = aligned;
				}
			}
			// Global angular velocity from two complete, yaw-aligned orientations. The OEM
			// raw gyro bytes also carry calibration bits and do not establish the OpenXR axes.
			const int64_t dt_ns = pose_ns - d->pose_ns;
			if (!d->kit && !phone && d->pose_ns > 0 && dt_ns >= 1000000 && dt_ns <= 100000000) {
				// q and -q represent the same orientation; use the nearest hemisphere.
				struct xrt_quat q = relation.pose.orientation;
				if (math_quat_dot(&d->previous_orientation, &q) < 0) {
					relation.pose.orientation = (struct xrt_quat){-q.x, -q.y, -q.z, -q.w};
				}
				math_quat_finite_difference(&d->previous_orientation, &relation.pose.orientation,
				                            (float)dt_ns * 1e-9f, &relation.angular_velocity);
				relation.relation_flags |= XRT_SPACE_RELATION_ANGULAR_VELOCITY_VALID_BIT;
			}
			d->previous_orientation = relation.pose.orientation;
			m_relation_history_push(d->history, &relation, pose_ns);
			d->pose_ns = pose_ns;
		} else if (!valid && d->orientation_valid) {
			clear_pose_history_locked(d);
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
	if (d->phone) {
		jclass phone = cv10_load_java_class(env, context, "org.freedesktop.monado.openxr_runtime.Cv10PhoneControllerService");
		if (!phone || clear_exception(env)) goto done;
		jmethodID read = (*env)->GetStaticMethodID(env, phone, "read", "([F)J");
		if (!read) goto done;
		jfloatArray buffer = (*env)->NewFloatArray(env, CV10_CF20_REPORT_FLOATS);
		if (!buffer) goto done;
		int64_t previous = 0;
		while (os_thread_helper_is_running(&d->worker)) {
			int64_t ns = (*env)->CallStaticLongMethod(env, phone, read, buffer);
			if (clear_exception(env)) break;
			if (!report_is_fresh(ns)) ns = 0;
			if (ns != 0 || previous != 0) {
				float values[CV10_CF20_REPORT_FLOATS];
				(*env)->GetFloatArrayRegion(env, buffer, 0, CV10_CF20_REPORT_FLOATS, values);
				if (clear_exception(env)) break;
				update_report(d, ns, ns, values);
			}
			previous = ns;
			os_nanosleep((ns > 0 ? 4 : 20) * U_TIME_1MS_IN_NS);
		}
		update_report(d, 0, 0, NULL);
		goto done;
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
	jmethodID current_address = (*env)->GetMethodID(env, cls, "isCurrent", "()Z");
	if (!current_address) goto done;
	jmethodID close = (*env)->GetMethodID(env, cls, "close", "()V");
	if (close == NULL) {
		goto done;
	}
	jfloatArray buffer = (*env)->NewFloatArray(env, CV10_CF20_REPORT_FLOATS);
	if (buffer == NULL) {
		goto done;
	}
	jobject reader = (*env)->CallStaticObjectMethod(env, cls, open, context);
	if (clear_exception(env) || reader == NULL) {
		goto done;
	}
	int64_t previous = 0, next_address_check = 0;
	while (os_thread_helper_is_running(&d->worker)) {
		int64_t now = os_monotonic_get_ns();
		if (now >= next_address_check) {
			next_address_check = now + U_TIME_1S_IN_NS;
			bool same = (*env)->CallBooleanMethod(env, reader, current_address);
			if (clear_exception(env)) break;
			if (!same) {
				(*env)->CallVoidMethod(env, reader, close);
				(*env)->DeleteLocalRef(env, reader);
				if (clear_exception(env)) goto done;
				update_report(d, 0, 0, NULL);
				reader = (*env)->CallStaticObjectMethod(env, cls, open, context);
				if (clear_exception(env) || !reader) goto done;
			}
		}
		int64_t timestamp = (*env)->CallLongMethod(env, reader, read, buffer);
		if (clear_exception(env)) {
			break;
		}
		int64_t current = report_is_fresh(timestamp) ? timestamp : 0;
		if (current != 0) {
			// Calibration and Home epochs can change between two BLE notifications.
			float values[CV10_CF20_REPORT_FLOATS];
			(*env)->GetFloatArrayRegion(env, buffer, 0, CV10_CF20_REPORT_FLOATS, values);
			if (clear_exception(env)) {
				break;
			}
			update_report(d, current, current, values);
		} else if (previous != 0) {
			update_report(d, 0, 0, NULL);
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
	if (!d->kit) {
		xdev->inputs[CV10_MODEL].active = d->phone ? tracked : fresh;
		xdev->inputs[CV10_MODEL].timestamp = d->pose_ns;
	}
	if (!d->kit) {
		const unsigned end = xdev->input_count;
		for (unsigned i = CV10_PAD_CLICK; i < end; ++i) {
			const struct xrt_input *value = &d->samples[i];
			xdev->inputs[i].active = fresh && value->active;
			xdev->inputs[i].timestamp = value->timestamp;
			xdev->inputs[i].value = value->value;
		}
	}
	os_thread_helper_unlock(&d->worker);
	return XRT_SUCCESS;
}

static void
get_hand_pose_locked(struct cv10_controller *d, int64_t at_ns, struct cv10_hand_pose *pose)
{
	struct xrt_space_relation *out = &pose->grip;
	const int64_t now_ns = os_monotonic_get_ns();
	if (!d->kit && cv10_cf20_pose_cache_get(&d->grip_cache, at_ns, now_ns, CV10_REPORT_TIMEOUT_NS, pose)) {
		return;
	}

	const int64_t query = at_ns > d->pose_ns ? d->pose_ns : at_ns;
	if (m_relation_history_get(d->history, query, out) == M_RELATION_HISTORY_RESULT_INVALID) {
		out->pose.orientation.w = 1;
		return;
	}
	pose->aim_orientation = out->pose.orientation;
	if (!d->kit && !d->fixed_position) {
		struct xrt_space_relation head = XRT_SPACE_RELATION_ZERO;
		if (xrt_device_get_tracked_pose(d->head, XRT_INPUT_GENERIC_HEAD_POSE, at_ns, &head) != XRT_SUCCESS) {
			head = (struct xrt_space_relation)XRT_SPACE_RELATION_ZERO;
		}
		cv10_arm_model_apply(&d->arm, query, &head, d->right, out);
	}
	if (d->phone) {
		struct xrt_space_relation body;
		if (m_relation_history_get(d->phone_grip_history, query, &body) == M_RELATION_HISTORY_RESULT_INVALID) {
			out->relation_flags = 0;
			return;
		}
		out->pose.orientation = body.pose.orientation;
	}
	if (!d->kit) {
		// Pin the estimated wrist and both orientations for all spaces at this display time.
		cv10_cf20_pose_cache_put(&d->grip_cache, at_ns, now_ns, pose);
	}
}

static xrt_result_t
controller_pose(struct xrt_device *xdev, enum xrt_input_name name, int64_t at_ns, struct xrt_space_relation *out)
{
	struct cv10_controller *d = (struct cv10_controller *)xdev;
	*out = (struct xrt_space_relation)XRT_SPACE_RELATION_ZERO;
	out->pose.orientation.w = 1;
	const bool model = !d->kit && name == (d->phone ? XRT_INPUT_CV10_PHONE_MODEL_POSE
	                                                : XRT_INPUT_CV10_CF20_MODEL_POSE);
	if (!model && name != XRT_INPUT_SIMPLE_GRIP_POSE && name != XRT_INPUT_SIMPLE_AIM_POSE) {
		return XRT_ERROR_INPUT_UNSUPPORTED;
	}
	os_thread_helper_lock(&d->worker);
	if (report_is_fresh(d->report_ns) && d->orientation_valid && report_is_fresh(d->pose_ns)) {
		struct cv10_hand_pose pose = {.grip = *out, .aim_orientation = {0, 0, 0, 1}};
		get_hand_pose_locked(d, at_ns, &pose);
		*out = pose.grip;
		if (name == XRT_INPUT_SIMPLE_AIM_POSE) {
			out->pose.orientation = pose.aim_orientation;
			if (!d->kit && !d->phone && (out->relation_flags & XRT_SPACE_RELATION_POSITION_VALID_BIT)) {
				cv10_cf20_apply_aim_offset(out);
			}
		}
	}
	os_thread_helper_unlock(&d->worker);
	return XRT_SUCCESS;
}

static xrt_result_t
controller_battery_status(struct xrt_device *xdev, bool *present, bool *charging, float *charge)
{
	struct cv10_controller *d = (struct cv10_controller *)xdev;
	os_thread_helper_lock(&d->worker);
	*present = report_is_fresh(d->report_ns) && d->battery_percent >= 0 && d->battery_percent <= 100;
	*charging = false;
	*charge = *present ? d->battery_percent * 0.01f : 0.0f;
	os_thread_helper_unlock(&d->worker);
	return XRT_SUCCESS;
}

static void
controller_destroy(struct xrt_device *xdev)
{
	struct cv10_controller *d = (struct cv10_controller *)xdev;
	os_thread_helper_destroy(&d->worker);
	m_relation_history_destroy(&d->history);
	m_relation_history_destroy(&d->phone_grip_history);
	u_device_free(xdev);
}

static struct xrt_device *
controller_create(struct xrt_tracking_origin *origin, struct xrt_device *head, bool right, bool phone)
{
	const bool cf20 = head != NULL && !phone;
	unsigned input_count = cf20 ? CV10_INPUT_COUNT : phone ? CV10_HOME : CV10_MODEL;
	struct cv10_controller *d =
	    U_DEVICE_ALLOCATE(struct cv10_controller, origin ? 0 : U_DEVICE_ALLOC_TRACKING_NONE, input_count, 0);
	if (d == NULL) {
		return NULL;
	}
	d->head = head;
	d->phone = phone;
	d->kit = head == NULL;
	d->right = right;
	struct xrt_device *xdev = &d->base;
	xdev->name = cf20 ? XRT_DEVICE_HUAWEI_CONTROLLER : phone ? XRT_DEVICE_CV10_PHONE : XRT_DEVICE_SIMPLE_CONTROLLER;
	xdev->device_type = right ? XRT_DEVICE_TYPE_RIGHT_HAND_CONTROLLER : XRT_DEVICE_TYPE_LEFT_HAND_CONTROLLER;
	xdev->supported.orientation_tracking = true;
	xdev->tracking_origin = origin;
	if (d->kit) {
		xdev->supported.position_tracking = true;
		snprintf(xdev->str, sizeof(xdev->str), "Huawei CV10 6DoF %s controller", right ? "right" : "left");
		snprintf(xdev->serial, sizeof(xdev->serial), "Huawei-CV10-Kit-%s", right ? "right" : "left");
	} else {
		snprintf(xdev->str, sizeof(xdev->str), "%s", cf20 ? "HUAWEI CF20" : "CV10 Phone Controller");
		snprintf(xdev->serial, sizeof(xdev->serial), "%s", cf20 ? "Huawei-CV10-CF20" : "Huawei-CV10-Phone");
	}
	u_device_populate_function_pointers(xdev, controller_pose, controller_destroy);
	xdev->update_inputs = controller_update_inputs;
	xdev->inputs[CV10_SELECT].name = XRT_INPUT_SIMPLE_SELECT_CLICK;
	xdev->inputs[CV10_MENU].name = XRT_INPUT_SIMPLE_MENU_CLICK;
	xdev->inputs[CV10_GRIP].name = XRT_INPUT_SIMPLE_GRIP_POSE;
	xdev->inputs[CV10_AIM].name = XRT_INPUT_SIMPLE_AIM_POSE;
	if (phone) {
		xdev->inputs[CV10_MODEL].name = XRT_INPUT_CV10_PHONE_MODEL_POSE;
		xdev->inputs[CV10_PAD_CLICK].name = XRT_INPUT_DAYDREAM_TOUCHPAD_CLICK;
		xdev->inputs[CV10_PAD_TOUCH].name = XRT_INPUT_DAYDREAM_TOUCHPAD_TOUCH;
		xdev->inputs[CV10_PAD_XY].name = XRT_INPUT_DAYDREAM_TOUCHPAD;
	}
	if (cf20) {
		xdev->supported.battery_status = true;
		xdev->get_battery_status = controller_battery_status;
		xdev->inputs[CV10_MODEL].name = XRT_INPUT_CV10_CF20_MODEL_POSE;
		xdev->inputs[CV10_HOME].name = XRT_INPUT_HUAWEI_HOME_CLICK;
		xdev->inputs[CV10_BACK].name = XRT_INPUT_HUAWEI_BACK_CLICK;
		xdev->inputs[CV10_VOLUME_UP].name = XRT_INPUT_HUAWEI_VOLUME_UP_CLICK;
		xdev->inputs[CV10_VOLUME_DOWN].name = XRT_INPUT_HUAWEI_VOLUME_DOWN_CLICK;
		xdev->inputs[CV10_TRIGGER].name = XRT_INPUT_HUAWEI_TRIGGER_CLICK;
		xdev->inputs[CV10_TRIGGER_VALUE].name = XRT_INPUT_HUAWEI_TRIGGER_VALUE;
		xdev->inputs[CV10_PAD_CLICK].name = XRT_INPUT_HUAWEI_TRACKPAD_CLICK;
		xdev->inputs[CV10_PAD_TOUCH].name = XRT_INPUT_HUAWEI_TRACKPAD_TOUCH;
		xdev->inputs[CV10_PAD_XY].name = XRT_INPUT_HUAWEI_TRACKPAD;
	}
	for (size_t i = 0; i < input_count; i++) {
		xdev->inputs[i].active = false;
	}

	if (os_thread_helper_init(&d->worker) != 0) {
		u_device_free(xdev);
		return NULL;
	}
	m_relation_history_create(&d->history);
	if (phone) m_relation_history_create(&d->phone_grip_history);
	if (d->history == NULL || (phone && d->phone_grip_history == NULL) ||
	    os_thread_helper_start(&d->worker, controller_worker, d) != 0) {
		controller_destroy(xdev);
		return NULL;
	}
	U_LOG_I("Configured %s as an OpenXR controller", xdev->str);
	return xdev;
}

static xrt_result_t
controller_roles(struct xrt_system_devices *xsysd, struct xrt_system_roles *out)
{
	struct cv10_controller *cf = NULL;
	for (unsigned i = 0; i < xsysd->static_xdev_count; ++i) {
		if (xsysd->static_xdevs[i]->name == XRT_DEVICE_HUAWEI_CONTROLLER) {
			cf = (struct cv10_controller *)xsysd->static_xdevs[i];
			break;
		}
	}
	if (cf == NULL) return XRT_ERROR_DEVICE_CREATION_FAILED;
	xrt_result_t result = cf->static_get_roles(xsysd, out);
	if (result != XRT_SUCCESS) return result;

	struct cv10_controller *phone = (struct cv10_controller *)xsysd->static_xdevs[cf->phone_index];
	// Always take the locks in source order. Workers only take their own lock.
	os_thread_helper_lock(&cf->worker);
	os_thread_helper_lock(&phone->worker);
	bool cf_ready = report_is_fresh(cf->report_ns);
	bool phone_ready = report_is_fresh(phone->report_ns);
	bool swap = cf->mode == 2;
	int32_t left = -1, right = -1;
	if (cf_ready) {
		if (swap) left = (int32_t)cf->cf20_index;
		else right = (int32_t)cf->cf20_index;
	}
	if (phone_ready) {
		if (right < 0) right = (int32_t)cf->phone_index;
		else left = (int32_t)cf->phone_index;
	}
	if (left != cf->roles.left || right != cf->roles.right) {
		cf->roles.generation_id++;
		cf->roles.left = left;
		cf->roles.right = right;
		bool cf_right = !swap;
		bool phone_right = right == (int32_t)cf->phone_index;
		if (cf->right != cf_right) {
			cf->right = cf_right;
			clear_pose_history_locked(cf);
		}
		if (phone_ready && phone->right != phone_right) {
			phone->right = phone_right;
			clear_pose_history_locked(phone);
		}
	}
	out->left = left;
	out->right = right;
	out->generation_id = cf->roles.generation_id;
	os_thread_helper_unlock(&phone->worker);
	os_thread_helper_unlock(&cf->worker);
	return XRT_SUCCESS;
}

void
cv10_install_controller_roles(struct xrt_system_devices *xsysd)
{
	struct cv10_controller *cf = NULL, *phone = NULL;
	unsigned cf_index = 0, phone_index = 0;
	for (unsigned i = 0; i < xsysd->static_xdev_count; ++i) {
		struct xrt_device *xdev = xsysd->static_xdevs[i];
		if (xdev->update_inputs != controller_update_inputs) continue;
		struct cv10_controller *d = (struct cv10_controller *)xdev;
		if (xdev->name == XRT_DEVICE_HUAWEI_CONTROLLER) {
			cf = d;
			cf_index = i;
		} else if (d->phone) {
			phone = d;
			phone_index = i;
		}
	}
	if (cf == NULL || phone == NULL) return;
	cf->roles = (struct xrt_system_roles)XRT_SYSTEM_ROLES_INIT;
	cf->roles.generation_id = 1;
	cf->cf20_index = cf_index;
	cf->phone_index = phone_index;
	cf->static_get_roles = xsysd->get_roles;
	xsysd->get_roles = controller_roles;
}

struct xrt_device *
cv10_controller_create(struct xrt_device *head, bool phone)
{
	return controller_create(head->tracking_origin, head, !phone, phone);
}

struct xrt_device *
cv10_kit_controller_create(struct xrt_tracking_origin *origin, bool right)
{
	return controller_create(origin, NULL, right, false);
}
