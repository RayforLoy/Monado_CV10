// SPDX-License-Identifier: GPL-3.0-or-later
#include "cv10_pose_models.h"

#include <math.h>

#include "math/m_api.h"

static const struct xrt_vec3 forward = {0, 0, -1};
static const struct xrt_vec3 up = {0, 1, 0};
static const struct xrt_quat identity = {0, 0, 0, 1};

void
cv10_head_model_apply(float eye_height, struct xrt_space_relation *head)
{
	if ((head->relation_flags & XRT_SPACE_RELATION_ORIENTATION_VALID_BIT) == 0) {
		return;
	}
	const struct xrt_vec3 neck_to_eyes = {0.0f, 0.075f, -0.0805f};
	struct xrt_vec3 rotated;
	math_quat_rotate_vec3(&head->pose.orientation, &neck_to_eyes, &rotated);
	head->pose.position =
	    (struct xrt_vec3){rotated.x, eye_height + (rotated.y - neck_to_eyes.y), rotated.z - neck_to_eyes.z};
	head->relation_flags &=
	    ~(XRT_SPACE_RELATION_POSITION_TRACKED_BIT | XRT_SPACE_RELATION_LINEAR_VELOCITY_VALID_BIT);
	head->relation_flags |= XRT_SPACE_RELATION_POSITION_VALID_BIT;
}

static float
clamp(float value, float low, float high)
{
	return fminf(fmaxf(value, low), high);
}

static float
angular_distance(struct xrt_quat a, struct xrt_quat b)
{
	float dot = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w;
	return 2.0f * acosf(fminf(fabsf(dot), 1.0f));
}

static void
update_torso(struct cv10_arm_model *model,
             int64_t sample_ns,
             const struct xrt_space_relation *head,
             struct xrt_quat orientation)
{
	if (sample_ns <= model->sample_ns) {
		return;
	}

	struct xrt_quat facing = model->sample_ns == 0 ? identity : model->torso;
	if ((head->relation_flags & XRT_SPACE_RELATION_ORIENTATION_VALID_BIT) != 0) {
		struct xrt_vec3 horizontal;
		math_quat_rotate_vec3(&head->pose.orientation, &forward, &horizontal);
		if (horizontal.x * horizontal.x + horizontal.z * horizontal.z > 0.000001f) {
			float yaw = atan2f(-horizontal.x, -horizontal.z);
			math_quat_from_angle_vector(yaw, &up, &facing);
		}
	}

	if (model->sample_ns == 0) {
		// Seed from the first available head heading rather than a zero direction.
		model->torso = facing;
	} else {
		float dt = (sample_ns - model->sample_ns) * 1e-9f;
		float speed = angular_distance(orientation, model->previous_orientation) / dt;
		float weight = clamp((speed - 0.2f) / 45.0f, 0.0f, 0.1f);
		struct xrt_quat next;
		math_quat_slerp(&model->torso, &facing, weight, &next);
		model->torso = next;
	}
	model->sample_ns = sample_ns;
	model->previous_orientation = orientation;
}

void
cv10_arm_model_apply(struct cv10_arm_model *model,
                     int64_t sample_ns,
                     const struct xrt_space_relation *head,
                     bool aim,
                     struct xrt_space_relation *hand)
{
	hand->relation_flags &= ~(XRT_SPACE_RELATION_POSITION_VALID_BIT | XRT_SPACE_RELATION_POSITION_TRACKED_BIT |
	                          XRT_SPACE_RELATION_LINEAR_VELOCITY_VALID_BIT);
	if ((head->relation_flags & XRT_SPACE_RELATION_POSITION_VALID_BIT) == 0) {
		return;
	}
	const struct xrt_quat orientation = hand->pose.orientation;
	update_torso(model, sample_ns, head, orientation);
	struct xrt_quat torso = model->sample_ns == 0 ? identity : model->torso;

	struct xrt_quat relative;
	math_quat_unrotate(&torso, &orientation, &relative);
	struct xrt_vec3 pointing;
	math_quat_rotate_vec3(&relative, &forward, &pointing);

	// The original unit is degrees. OpenXR uses -Z forward, so Z offsets are reflected.
	const float pi = 3.14159265358979323846f;
	float extension = clamp(asinf(clamp(pointing.y, -1.0f, 1.0f)) / (pi / 3.0f), 0.0f, 1.0f);
	struct xrt_quat swing;
	math_quat_from_vec_a_to_vec_b(&forward, &pointing, &swing);
	float turn = angular_distance(swing, identity) / pi;
	float blend = (1.0f - powf(turn, 6.0f)) * (0.4f + 0.24f * extension);

	// Unity Quaternion.Lerp normalizes the linearly blended quaternions.
	if (swing.w < 0.0f) {
		blend = -blend;
	}
	struct xrt_quat damped = {
	    swing.x * blend,
	    swing.y * blend,
	    swing.z * blend,
	    swing.w * blend + 1.0f - fabsf(blend),
	};
	math_quat_normalize(&damped);
	struct xrt_quat elbow, elbow_world;
	math_quat_unrotate(&damped, &relative, &elbow);
	math_quat_rotate(&torso, &elbow, &elbow_world);

	const struct xrt_vec3 elbow_offset = {
	    0.22f - 0.13f * extension,
	    -0.75f + 0.20f * extension,
	    0.26f - 0.10f * extension,
	};
	struct xrt_vec3 wrist_offset = {0, 0, -0.65f};
	if (aim) {
		// The CF20's ray is forward along the grip rotation, starting just above and ahead of the wrist.
		const struct xrt_vec3 ray_offset = {0, 0.020f, -0.069f};
		struct xrt_vec3 offset;
		math_quat_rotate_vec3(&orientation, &ray_offset, &offset);
		hand->pose.position = offset;
	} else {
		hand->pose.position = (struct xrt_vec3){0};
	}
	struct xrt_vec3 rotated_elbow, rotated_wrist;
	math_quat_rotate_vec3(&torso, &elbow_offset, &rotated_elbow);
	math_quat_rotate_vec3(&elbow_world, &wrist_offset, &rotated_wrist);
	math_vec3_accum(&rotated_elbow, &hand->pose.position);
	math_vec3_accum(&rotated_wrist, &hand->pose.position);
	math_vec3_accum(&head->pose.position, &hand->pose.position);
	hand->relation_flags |= XRT_SPACE_RELATION_POSITION_VALID_BIT;
}
