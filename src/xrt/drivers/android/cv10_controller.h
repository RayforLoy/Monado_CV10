// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <stdbool.h>

struct xrt_device;
struct xrt_tracking_origin;

struct xrt_device *
cv10_controller_create(struct xrt_device *head);

struct xrt_device *
cv10_kit_controller_create(struct xrt_tracking_origin *origin, bool right);
