// Copyright 2018-2023, Collabora, Ltd.
// Copyright 2026, NVIDIA CORPORATION.
// SPDX-License-Identifier: BSL-1.0
/*!
 * @file
 * @brief  Holds swapchain related functions.
 * @author Jakob Bornecrantz <jakob@collabora.com>
 * @ingroup oxr_main
 */

#include "util/u_debug.h"
#include "util/u_misc.h"

#include "oxr_chain.h"
#include "oxr_conversions.h"
#include "oxr_objects.h"
#include "oxr_logger.h"
#include "oxr_handle_base.h"
#include "oxr_swapchain_common.h"
#include "oxr_xret.h"

#if defined(XRT_GRAPHICS_BUFFER_HANDLE_IS_AHARDWAREBUFFER) && defined(OXR_HAVE_KHR_opengl_es_enable)
#include "oxr_api_funcs.h"
#include "oxr_api_verify.h"
#include "util/u_handles.h"
#include <android/hardware_buffer.h>
#include <vulkan/vulkan_core.h>
#endif


/*
 *
 * Conversion functions.
 *
 */

static enum xrt_swapchain_create_flags
convert_create_flags(XrSwapchainCreateFlags xr_flags)
{
	enum xrt_swapchain_create_flags flags = 0;

	if ((xr_flags & XR_SWAPCHAIN_CREATE_PROTECTED_CONTENT_BIT) != 0) {
		flags |= XRT_SWAPCHAIN_CREATE_PROTECTED_CONTENT;
	}
	if ((xr_flags & XR_SWAPCHAIN_CREATE_STATIC_IMAGE_BIT) != 0) {
		flags |= XRT_SWAPCHAIN_CREATE_STATIC_IMAGE;
	}

	return flags;
}


/*
 *
 * Internal API functions.
 *
 */

static XrResult
acquire_image(struct oxr_logger *log,
              struct oxr_swapchain *sc,
              const XrSwapchainImageAcquireInfo *acquireInfo,
              uint32_t *out_index)
{
	CHECK_OXR_RET(oxr_swapchain_common_acquire(log, sc, out_index));

	return oxr_session_success_result(sc->sess);
}

static XrResult
implicit_wait_image(struct oxr_logger *log, struct oxr_swapchain *sc, const XrSwapchainImageWaitInfo *waitInfo)
{
	struct xrt_swapchain *xsc = (struct xrt_swapchain *)sc->swapchain;
	xrt_result_t xret;

	CHECK_OXR_RET(oxr_swapchain_verify_wait_state(log, sc));
	CHECK_OXR_RET(oxr_swapchain_common_wait(log, sc, waitInfo->timeout));

	// Check and grab the index.
	if (sc->inflight.index < 0) {
		return oxr_error(log, XR_ERROR_RUNTIME_FAILURE, "Invalid state: sc->inflight.index < 0");
	}
	uint32_t index = (uint32_t)sc->inflight.index;

	// Okay to transition here for all APIs except Vulkan, who has it's own implementation of this function.
	xret = xrt_swapchain_barrier_image(xsc, XRT_BARRIER_TO_COMP, index);
	OXR_CHECK_XRET(log, sc->sess, xret, xrt_swapchain_barrier_image);

	return oxr_session_success_result(sc->sess);
}

static XrResult
implicit_release_image(struct oxr_logger *log, struct oxr_swapchain *sc, const XrSwapchainImageReleaseInfo *releaseInfo)
{
	struct xrt_swapchain *xsc = (struct xrt_swapchain *)sc->swapchain;
	xrt_result_t xret;

	// Error checking.
	if (!sc->inflight.yes) {
		return oxr_error(log, XR_ERROR_CALL_ORDER_INVALID, "No swapchain images waited on");
	}

	// Check and grab the index.
	if (sc->inflight.index < 0) {
		return oxr_error(log, XR_ERROR_RUNTIME_FAILURE, "Invalid state: sc->inflight.index < 0");
	}
	uint32_t index = (uint32_t)sc->inflight.index;

	if (sc->images[index].state != OXR_IMAGE_STATE_WAITED) {
		return oxr_error(log, XR_ERROR_CALL_ORDER_INVALID, "No swapchain images waited on");
	}

	// Need to do a automatic transition here.
	xret = xrt_swapchain_barrier_image(xsc, XRT_BARRIER_TO_COMP, index);
	OXR_CHECK_XRET(log, sc->sess, xret, xrt_swapchain_barrier_image);

	CHECK_OXR_RET(oxr_swapchain_common_release(log, sc));

	return oxr_session_success_result(sc->sess);
}

static XrResult
destroy(struct oxr_logger *log, struct oxr_swapchain *sc)
{
	// It is not safe to do transitions here for some Graphics APIs, and
	// the ipc layer has to be robust enough to handle a disconnect.

	// Drop our reference, does NULL checking.
	xrt_swapchain_reference(&sc->swapchain, NULL);

	return XR_SUCCESS;
}


/*
 *
 * Handle function.
 *
 */

static XrResult
destroy_handle(struct oxr_logger *log, struct oxr_handle_base *hb)
{
	struct oxr_swapchain *sc = (struct oxr_swapchain *)hb;

	XrResult ret = sc->destroy(log, sc);
	free(sc);
	return ret;
}

#if defined(XRT_GRAPHICS_BUFFER_HANDLE_IS_AHARDWAREBUFFER) && defined(OXR_HAVE_KHR_opengl_es_enable)
XRAPI_ATTR XrResult XRAPI_CALL
oxr_xrCv10ImportAndroidStreamImageMONADO(XrSession session, struct AHardwareBuffer *buffer, XrSwapchain *out_swapchain)
{
	struct oxr_session *sess;
	struct oxr_logger log;
	OXR_VERIFY_SESSION_AND_INIT_LOG(&log, session, sess, "xrCv10ImportAndroidStreamImageMONADO");
	OXR_VERIFY_SESSION_NOT_LOST(&log, sess);
	OXR_VERIFY_ARG_NOT_NULL(&log, buffer);
	OXR_VERIFY_ARG_NOT_NULL(&log, out_swapchain);
	*out_swapchain = XR_NULL_HANDLE;
	if (sess->create_swapchain != oxr_swapchain_gl_create || sess->compositor == NULL ||
	    sess->compositor->import_swapchain == NULL) {
		return XR_ERROR_FEATURE_UNSUPPORTED;
	}

	AHardwareBuffer_Desc desc;
	AHardwareBuffer_describe(buffer, &desc);
	struct xrt_swapchain_create_info info = {
	    .create = XRT_SWAPCHAIN_CREATE_ANDROID_STREAM,
	    .bits = XRT_SWAPCHAIN_USAGE_SAMPLED,
	    .format = VK_FORMAT_R8G8B8A8_UNORM,
	    .sample_count = 1,
	    .width = desc.width,
	    .height = desc.height,
	    .face_count = 1,
	    .array_size = 1,
	    .mip_count = 1,
	};
	struct oxr_swapchain *sc = NULL;
	OXR_ALLOCATE_HANDLE_OR_RETURN(&log, sc, OXR_XR_DEBUG_SWAPCHAIN, destroy_handle, &sess->handle);
	sc->destroy = destroy;
	sc->sess = sess;
	sc->width = desc.width;
	sc->height = desc.height;
	sc->face_count = 1;
	sc->array_layer_count = 1;
	struct xrt_image_native image = {.handle = u_graphics_buffer_ref(buffer)};
	xrt_result_t xret = xrt_comp_import_swapchain(sess->compositor, &info, &image, 1, &sc->swapchain);
	if (xret != XRT_SUCCESS) {
		u_graphics_buffer_unref(&image.handle);
		oxr_handle_destroy(&log, &sc->handle);
		return oxr_error(&log, XR_ERROR_RUNTIME_FAILURE, "Android stream image import failed (%d)", xret);
	}

	// The offscreen producer already controls availability via its acquire and release fences.
	sc->released.yes = true;
	sc->released.index = 0;
	*out_swapchain = oxr_swapchain_to_openxr(sc);
	return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL
oxr_xrCv10PollAndroidStreamImageMONADO(XrSwapchain swapchain)
{
	struct oxr_swapchain *sc;
	struct oxr_logger log;
	OXR_VERIFY_SWAPCHAIN_AND_INIT_LOG(&log, swapchain, sc, "xrCv10PollAndroidStreamImageMONADO");
	if (!sc->swapchain->android_stream) {
		return XR_ERROR_VALIDATION_FAILURE;
	}
	const xrt_result_t xret = xrt_swapchain_wait_image(sc->swapchain, 0, 0);
	if (xret == XRT_TIMEOUT) {
		return XR_TIMEOUT_EXPIRED;
	}
	OXR_CHECK_XRET(&log, sc->sess, xret, cv10_wait_stream_image);
	return XR_SUCCESS;
}
#endif


/*
 *
 * 'Exported' functions.
 *
 */

XrResult
oxr_swapchain_common_acquire(struct oxr_logger *log, struct oxr_swapchain *sc, uint32_t *out_index)
{
	uint32_t index;

	if (sc->acquired.num >= sc->swapchain->image_count) {
		return oxr_error(log, XR_ERROR_CALL_ORDER_INVALID, "All images have been acquired");
	}

	if (sc->is_static && (sc->released.yes || sc->images[0].state != OXR_IMAGE_STATE_READY)) {
		return oxr_error(log, XR_ERROR_CALL_ORDER_INVALID, "Can only acquire once on a static swapchain");
	}

	struct xrt_swapchain *xsc = (struct xrt_swapchain *)sc->swapchain;

	xrt_result_t xret = xrt_swapchain_acquire_image(xsc, &index);
	OXR_CHECK_XRET(log, sc->sess, xret, xrt_swapchain_acquire_image);

	if (sc->images[index].state != OXR_IMAGE_STATE_READY) {
		return oxr_error(log, XR_ERROR_RUNTIME_FAILURE,
		                 "Internal xrt_swapchain_acquire_image call returned non-ready image.");
	}

	sc->acquired.num++;
	u_index_fifo_push(&sc->acquired.fifo, index);
	sc->images[index].state = OXR_IMAGE_STATE_ACQUIRED;

	// If the compositor is reusing the image,
	// mark it as invalid to use in xrEndFrame.
	if (sc->released.index == (int)index) {
		sc->released.yes = false;
		sc->released.index = -1;
	}

	*out_index = index;

	return XR_SUCCESS;
}

XrResult
oxr_swapchain_common_wait(struct oxr_logger *log, struct oxr_swapchain *sc, XrDuration timeout)
{
	uint32_t index = UINT32_MAX;
	if (u_index_fifo_peek(&sc->acquired.fifo, &index) != 0) {
		return oxr_error(log, XR_ERROR_RUNTIME_FAILURE, "u_index_fifo_peek: failed!");
	}
	assert(index < INT32_MAX);

	struct xrt_swapchain *xsc = (struct xrt_swapchain *)sc->swapchain;

	xrt_result_t xret = xrt_swapchain_wait_image(xsc, timeout, index);
	if (xret == XRT_TIMEOUT) {
		oxr_warn(log, "call to xrt_swapchain_wait_image timeout");
		return XR_TIMEOUT_EXPIRED;
	}

	if (u_index_fifo_pop(&sc->acquired.fifo, &index) != 0) {
		return oxr_error(log, XR_ERROR_RUNTIME_FAILURE, "u_index_fifo_pop: failed!");
	}

	OXR_CHECK_XRET(log, sc->sess, xret, xrt_swapchain_wait_image);

	// The app can only wait on one image.
	sc->inflight.yes = true;
	sc->inflight.index = (int)index;
	sc->images[index].state = OXR_IMAGE_STATE_WAITED;

	return XR_SUCCESS;
}

XrResult
oxr_swapchain_common_release(struct oxr_logger *log, struct oxr_swapchain *sc)
{
	struct xrt_swapchain *xsc = (struct xrt_swapchain *)sc->swapchain;

	// Check and grab the index.
	if (sc->inflight.index < 0) {
		return oxr_error(log, XR_ERROR_RUNTIME_FAILURE, "Invalid state: sc->inflight.index < 0");
	}
	uint32_t index = (uint32_t)sc->inflight.index;

	// Clear inflight.
	sc->inflight.yes = false;
	sc->inflight.index = -1;

	xrt_result_t xret = xrt_swapchain_release_image(xsc, index);
	OXR_CHECK_XRET(log, sc->sess, xret, xrt_swapchain_release_image);

	// Only decerement here.
	sc->acquired.num--;

	// Overwrite the old released image, with new.
	sc->released.yes = true;
	sc->released.index = index;
	sc->images[index].state = OXR_IMAGE_STATE_READY;

	return XR_SUCCESS;
}

XrResult
oxr_swapchain_common_create(struct oxr_logger *log,
                            struct oxr_session *sess,
                            const XrSwapchainCreateInfo *createInfo,
                            struct oxr_swapchain **out_swapchain)
{
	xrt_result_t xret = XRT_SUCCESS;

	struct xrt_swapchain_create_info info = {
	    .create = convert_create_flags(createInfo->createFlags),
	    .bits = xr_swapchain_usage_to_xrt(createInfo->usageFlags),
	    .format = createInfo->format,
	    .sample_count = createInfo->sampleCount,
	    .width = createInfo->width,
	    .height = createInfo->height,
	    .face_count = createInfo->faceCount,
	    .array_size = createInfo->arraySize,
	    .mip_count = createInfo->mipCount,
	};

#ifdef OXR_HAVE_KHR_vulkan_swapchain_format_list
	const XrVulkanSwapchainFormatListCreateInfoKHR *format_list = NULL;
	if (sess->sys->inst->extensions.KHR_vulkan_swapchain_format_list) {
		format_list = OXR_GET_INPUT_FROM_CHAIN(createInfo, XR_TYPE_VULKAN_SWAPCHAIN_FORMAT_LIST_CREATE_INFO_KHR,
		                                       XrVulkanSwapchainFormatListCreateInfoKHR);
	}

	if (format_list) {
		// Check in oxr_api_swapchain.c verification.
		assert((createInfo->usageFlags & XR_SWAPCHAIN_USAGE_MUTABLE_FORMAT_BIT) != 0);

		if (format_list->viewFormatCount > ARRAY_SIZE(info.formats)) {
			/* Hardcoded limit of 8 formats */
			return oxr_error(log, XR_ERROR_RUNTIME_FAILURE, "Too many formats");
		}

		info.format_count = format_list->viewFormatCount;
		memcpy(info.formats, format_list->viewFormats, sizeof(uint32_t) * info.format_count);
	}
#endif

	struct xrt_swapchain *xsc = NULL; // Has to be NULL.
	xret = xrt_comp_create_swapchain(sess->compositor, &info, &xsc);
	if (xret == XRT_ERROR_SWAPCHAIN_FLAG_VALID_BUT_UNSUPPORTED) {
		return oxr_error(log, XR_ERROR_FEATURE_UNSUPPORTED,
		                 "Specified swapchain creation flag is valid, "
		                 "but not supported");
	}
	if (xret == XRT_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED) {
		return oxr_error(log, XR_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED,
		                 "Specified swapchain format is not supported");
	}
	if (xret != XRT_SUCCESS) {
		return oxr_error(log, XR_ERROR_RUNTIME_FAILURE, "Failed to create swapchain");
	}
	assert(xsc != NULL);

	struct oxr_swapchain *sc = NULL;
	OXR_ALLOCATE_HANDLE_OR_RETURN(log, sc, OXR_XR_DEBUG_SWAPCHAIN, destroy_handle, &sess->handle);
	sc->sess = sess;
	sc->swapchain = xsc;
	sc->width = createInfo->width;
	sc->height = createInfo->height;
	sc->array_layer_count = createInfo->arraySize;
	sc->face_count = createInfo->faceCount;
	sc->is_static = (createInfo->createFlags & XR_SWAPCHAIN_CREATE_STATIC_IMAGE_BIT) != 0;

	// Functions.
	sc->wait_image = implicit_wait_image;
	sc->release_image = implicit_release_image;
	sc->acquire_image = acquire_image;
	sc->destroy = destroy;

	*out_swapchain = sc;

	return XR_SUCCESS;
}
