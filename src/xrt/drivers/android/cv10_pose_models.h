// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include "xrt/xrt_defines.h"

struct cv10_arm_model
{
	struct xrt_quat torso;
	struct xrt_quat previous_orientation;
	int64_t sample_ns;
};

#define CV10_DEFAULT_EYE_HEIGHT_M 1.50f

/** Translate the grip origin to the CF20 ray origin in local controller coordinates. */
void
cv10_cf20_apply_aim_offset(struct xrt_space_relation *hand);

/** Huawei's neck pivot, with floor-relative eye height at the neutral orientation. */
void
cv10_head_model_apply(float eye_height, struct xrt_space_relation *head);

/**
 * Place the CF20 wrist using Huawei's orientation-only arm geometry.
 * Head and controller poses must be in the same tracking origin.
 */
void
cv10_arm_model_apply(struct cv10_arm_model *model,
                     int64_t sample_ns,
                     const struct xrt_space_relation *head,
                     bool right,
                     struct xrt_space_relation *hand);
