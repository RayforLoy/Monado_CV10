// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include "xrt/xrt_device.h"

static inline void
cv10_set_button(struct xrt_input *input, bool pressed, int64_t timestamp)
{
	if (!input->active || input->value.boolean != pressed) {
		input->timestamp = timestamp;
	}
	input->value.boolean = pressed;
	input->active = true;
}

static inline void
cv10_update_touchpad(struct xrt_input *click, struct xrt_input *touch, struct xrt_input *xy,
                     bool pressed, bool touched, struct xrt_vec2 position, int64_t timestamp)
{
	cv10_set_button(click, pressed, timestamp);
	cv10_set_button(touch, touched, timestamp);
	if (!touched) {
		position.x = 0.0f;
		position.y = 0.0f;
	}
	if (!xy->active || xy->value.vec2.x != position.x || xy->value.vec2.y != position.y) {
		xy->timestamp = timestamp;
	}
	xy->value.vec2 = position;
	xy->active = true;
}
