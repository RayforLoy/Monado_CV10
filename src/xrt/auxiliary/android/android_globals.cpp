// Copyright 2020, Collabora, Ltd.
// SPDX-License-Identifier: BSL-1.0
/*!
 * @file
 * @brief  Functions for Android-specific global state.
 * @author Rylie Pavlik <rylie.pavlik@collabora.com>
 * @ingroup aux_android
 */

#include "android_globals.h"

#include <android/native_window.h>
#include <mutex>
#include <stddef.h>
#include <utility>
#include <wrap/android.app.h>

/*!
 * @todo Do we need locking here?
 */
static struct
{
	struct _JavaVM *vm = nullptr;
	jni::Object activity = {};
	jni::Object context = {};
} android_globals;

static std::mutex window_mutex;
static ANativeWindow *cached_window = nullptr;

void
android_globals_store_vm_and_activity(struct _JavaVM *vm, void *activity)
{
	jni::init(vm);
	android_globals.vm = vm;
	android_globals.activity = jni::Object((jobject)activity);
}

void
android_globals_store_vm_and_context(struct _JavaVM *vm, void *context)
{
	jni::init(vm);
	android_globals.vm = vm;
	android_globals.context = jni::Object((jobject)context);
	if (android_globals_is_instance_of_activity(vm, context)) {
		android_globals.activity = jni::Object((jobject)context);
	}
}

bool
android_globals_is_instance_of_activity(struct _JavaVM *vm, void *obj)
{
	jni::init(vm);

	auto activity_cls = jni::Class(wrap::android::app::Activity::getTypeName());
	return JNI_TRUE == jni::env()->IsInstanceOf((jobject)obj, activity_cls.getHandle());
}

void
android_globals_store_window(struct _ANativeWindow *window)
{
	ANativeWindow *previous;
	{
		std::lock_guard<std::mutex> lock(window_mutex);
		previous = std::exchange(cached_window, reinterpret_cast<ANativeWindow *>(window));
	}
	if (previous != nullptr) {
		ANativeWindow_release(previous);
	}
}

struct _ANativeWindow *
android_globals_acquire_window()
{
	std::lock_guard<std::mutex> lock(window_mutex);
	if (cached_window != nullptr) {
		ANativeWindow_acquire(cached_window);
	}
	return reinterpret_cast<struct _ANativeWindow *>(cached_window);
}

void
android_globals_clear_window(struct _ANativeWindow *window)
{
	ANativeWindow *previous = nullptr;
	{
		std::lock_guard<std::mutex> lock(window_mutex);
		if (cached_window == reinterpret_cast<ANativeWindow *>(window)) {
			previous = std::exchange(cached_window, nullptr);
		}
	}
	if (previous != nullptr) {
		ANativeWindow_release(previous);
	}
}

struct _JavaVM *
android_globals_get_vm()
{
	return android_globals.vm;
}

void *
android_globals_get_activity()
{
	return android_globals.activity.getHandle();
}

void *
android_globals_get_context()
{
	return android_globals.context.isNull() ? android_globals.activity.getHandle()
	                                        : android_globals.context.getHandle();
}
