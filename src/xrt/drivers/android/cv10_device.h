// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <jni.h>
#include <stdbool.h>

#define CV10_KIT_TRACKING_SERVICE "org.freedesktop.monado.openxr_runtime.Cv10KitTrackingService"

struct xrt_device;
struct xrt_device *
cv10_device_create(void);

bool
cv10_kit_mode(const char *method_name);

jclass
cv10_load_java_class(JNIEnv *env, jobject context, const char *class_name);
