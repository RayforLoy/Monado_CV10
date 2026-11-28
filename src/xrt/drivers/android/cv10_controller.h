// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <stdbool.h>

struct xrt_device;
struct xrt_tracking_origin;

struct xrt_device *
cv10_controller_create(struct xrt_device *head, bool phone);

struct xrt_system_devices;
void
cv10_install_controller_roles(struct xrt_system_devices *xsysd);

struct xrt_device *
cv10_kit_controller_create(struct xrt_tracking_origin *origin, bool right);
