// Copyright 2020, Collabora, Ltd.
// SPDX-License-Identifier: BSL-1.0
/*!
 * @file
 * @brief  Functions for Android-specific global state.
 * @author Rylie Pavlik <rylie.pavlik@collabora.com>
 * @ingroup aux_android
 */

#pragma once

#include <xrt/xrt_config_os.h>

#ifdef XRT_OS_ANDROID

#ifdef __cplusplus
extern "C" {
#endif

struct _JNIEnv;
struct _JavaVM;
struct _ANativeWindow;

/*!
 * Store the Java VM pointer and the android.app.Activity jobject.
 */
void
android_globals_store_vm_and_activity(struct _JavaVM *vm, void *activity);


/*!
 * Store the Java VM pointer and the android.content.Context jobject.
 */
void
android_globals_store_vm_and_context(struct _JavaVM *vm, void *context);


/*!
 * Is the provided jobject an instance of android.app.Activity?
 */
bool
android_globals_is_instance_of_activity(struct _JavaVM *vm, void *obj);

/*!
 * Retrieve the Java VM pointer previously stored, if any.
 */
struct _JavaVM *
android_globals_get_vm(void);

/*!
 * Retrieve the android.app.Activity jobject previously stored, if any.
 *
 * For usage, cast the return value to jobject - a typedef whose definition
 * differs between C (a void *) and C++ (a pointer to an empty class)
 */
void *
android_globals_get_activity(void);

/*!
 * Retrieve the android.content.Context jobject previously stored, if any.
 *
 * Since android.app.Activity is a sub-class of android.content.Context, the
 * activity jobject will be returned if it has been set but the context has not.
 *
 * For usage, cast the return value to jobject - a typedef whose definition
 * differs between C (a void *) and C++ (a pointer to an empty class)
 */
void *
android_globals_get_context(void);

//! CV10 application eye separation in millimetres, or zero to use the default.
int
android_globals_get_cv10_ipd_mm(void);

void
android_globals_set_cv10_ipd_mm(int ipd_mm);

//! Take ownership of the supplied ANativeWindow reference.
void
android_globals_store_window(struct _ANativeWindow *window);

//! Acquire an ANativeWindow reference. The caller releases it.
struct _ANativeWindow *
android_globals_acquire_window(void);

//! Release the cached reference only when it still refers to the supplied window.
void
android_globals_clear_window(struct _ANativeWindow *window);

#ifdef __cplusplus
}
#endif

#endif // XRT_OS_ANDROID
