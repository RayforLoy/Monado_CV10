// Copyright 2019-2024, Collabora, Ltd.
// SPDX-License-Identifier: BSL-1.0
/*!
 * @file
 * @brief  Re-assemble a collection of composition layers submitted for a frame.
 *
 * @author Rylie Pavlik <rylie.pavlik@collabora.com>
 * @author Jakob Bornecrantz <jakob@collabora.com>
 * @author Lubosz Sarnecki <lubosz.sarnecki@collabora.com>
 * @ingroup comp_util
 */

#include "comp_layer_accum.h"
#include "util/u_misc.h"
#include "xrt/xrt_compositor.h"
#include "xrt/xrt_limits.h"

// Shared implementation of accumulating a layer with only a single swapchain image.
static xrt_result_t
push_single_swapchain_layer(struct comp_layer_accum *cla, struct xrt_swapchain *xsc, const struct xrt_layer_data *data)
{
	if (xsc->android_stream) {
		return XRT_ERROR_INVALID_ARGUMENT;
	}
	uint32_t layer_id = cla->layer_count;

	struct comp_layer *layer = &cla->layers[layer_id];
	U_ZERO_ARRAY(layer->sc_array);
	layer->sc_array[0] = xsc;
	layer->data = *data;

	cla->layer_count++;

	return XRT_SUCCESS;
}

struct xrt_swapchain *
comp_layer_get_swapchain(const struct comp_layer *cl, uint32_t swapchain_index)
{

	return cl->sc_array[swapchain_index];
}

struct xrt_swapchain *
comp_layer_get_depth_swapchain(const struct comp_layer *cl, uint32_t swapchain_index)
{

	assert(cl->data.type == XRT_LAYER_PROJECTION_DEPTH);
	return cl->sc_array[XRT_MAX_VIEWS + swapchain_index];
}

xrt_result_t
comp_layer_accum_begin(struct comp_layer_accum *cla, const struct xrt_layer_frame_data *data)
{
	comp_layer_accum_release_android_streams(cla);
	cla->data = *data;
	cla->layer_count = 0;

	return XRT_SUCCESS;
}

void
comp_layer_accum_release_android_streams(struct comp_layer_accum *cla)
{
	for (uint32_t i = 0; i < cla->android_stream_count; i++) {
		xrt_swapchain_dec_image_use(cla->android_streams[i], 0);
		xrt_swapchain_reference(&cla->android_streams[i], NULL);
	}
	cla->android_stream_count = 0;
}

static void
retain_android_stream(struct comp_layer_accum *cla, struct xrt_swapchain *xsc)
{
	for (uint32_t i = 0; i < cla->android_stream_count; i++) {
		if (cla->android_streams[i] == xsc) {
			return;
		}
	}
	assert(cla->android_stream_count < ARRAY_SIZE(cla->android_streams));
	xrt_swapchain_inc_image_use(xsc, 0);
	xrt_swapchain_reference(&cla->android_streams[cla->android_stream_count++], xsc);
}

xrt_result_t
comp_layer_accum_projection(struct comp_layer_accum *cla,
                            struct xrt_swapchain *xsc[XRT_MAX_VIEWS],
                            const struct xrt_layer_data *data)
{
	for (uint32_t i = 0; i < data->view_count; i++) {
		const struct xrt_sub_image *sub = &data->proj.v[i].sub;
		if (xsc[i]->android_stream && (sub->image_index != 0 || sub->array_index != 0)) {
			return XRT_ERROR_INVALID_ARGUMENT;
		}
	}

	uint32_t layer_id = cla->layer_count;

	struct comp_layer *layer = &cla->layers[layer_id];
	assert(ARRAY_SIZE(layer->sc_array) >= data->view_count);
	U_ZERO_ARRAY(layer->sc_array);
	for (uint32_t i = 0; i < data->view_count; ++i) {
		layer->sc_array[i] = xsc[i];
		if (xsc[i]->android_stream) {
			retain_android_stream(cla, xsc[i]);
		}
	}
	layer->data = *data;

	cla->layer_count++;

	return XRT_SUCCESS;
}

xrt_result_t
comp_layer_accum_projection_depth(struct comp_layer_accum *cla,
                                  struct xrt_swapchain *xsc[XRT_MAX_VIEWS],
                                  struct xrt_swapchain *d_xsc[XRT_MAX_VIEWS],
                                  const struct xrt_layer_data *data)
{
	for (uint32_t i = 0; i < data->view_count; i++) {
		if (xsc[i]->android_stream || d_xsc[i]->android_stream) {
			return XRT_ERROR_INVALID_ARGUMENT;
		}
	}

	uint32_t layer_id = cla->layer_count;

	struct comp_layer *layer = &cla->layers[layer_id];
	U_ZERO_ARRAY(layer->sc_array);
	for (uint32_t i = 0; i < data->view_count; ++i) {
		layer->sc_array[i] = xsc[i];
		layer->sc_array[XRT_MAX_VIEWS + i] = d_xsc[i];
	}
	layer->data = *data;

	cla->layer_count++;

	return XRT_SUCCESS;
}

xrt_result_t
comp_layer_accum_quad(struct comp_layer_accum *cla, struct xrt_swapchain *xsc, const struct xrt_layer_data *data)
{
	return push_single_swapchain_layer(cla, xsc, data);
}

xrt_result_t
comp_layer_accum_cube(struct comp_layer_accum *cla, struct xrt_swapchain *xsc, const struct xrt_layer_data *data)
{
	return push_single_swapchain_layer(cla, xsc, data);
}

xrt_result_t
comp_layer_accum_cylinder(struct comp_layer_accum *cla, struct xrt_swapchain *xsc, const struct xrt_layer_data *data)
{
	return push_single_swapchain_layer(cla, xsc, data);
}

xrt_result_t
comp_layer_accum_equirect1(struct comp_layer_accum *cla, struct xrt_swapchain *xsc, const struct xrt_layer_data *data)
{
	return push_single_swapchain_layer(cla, xsc, data);
}

xrt_result_t
comp_layer_accum_equirect2(struct comp_layer_accum *cla, struct xrt_swapchain *xsc, const struct xrt_layer_data *data)
{
	return push_single_swapchain_layer(cla, xsc, data);
}
