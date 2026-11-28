// SPDX-License-Identifier: BSL-1.0

#include "oxr_objects.h"
#include "oxr_api_funcs.h"
#include "oxr_api_verify.h"
#include "oxr_handle_base.h"
#include "oxr_logger.h"
#include "oxr_two_call.h"

#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

#include "cv10_phone_model.h"

#ifdef CV10_MODEL_EMBEDDED
#include "cv10_embedded_model.h"
#define CF20_MODEL_LENGTH sizeof(cf20_glb)
#else
#include "cv10_imported_model.h"
#endif

struct oxr_render_model
{
	struct oxr_handle_base handle;
	struct oxr_session *sess;
	struct oxr_render_model_lifetime *lifetime;
};

struct oxr_render_asset
{
	struct oxr_handle_base handle;
	struct oxr_session *sess;
	const uint8_t *data;
	uint32_t size;
};

#define OXR_XR_DEBUG_RENDER_MODEL (*(const uint64_t *)"oxrrmdl\0")
#define OXR_XR_DEBUG_RENDER_ASSET (*(const uint64_t *)"oxrrast\0")

#define VERIFY_MODEL(log, model, obj, func)                                                                            \
	OXR_VERIFY_AND_SET_AND_INIT(log, model, obj, oxr_render_model, RENDER_MODEL, func, obj->sess->sys->inst)
#define VERIFY_ASSET(log, asset, obj, func)                                                                            \
	OXR_VERIFY_AND_SET_AND_INIT(log, asset, obj, oxr_render_asset, RENDER_ASSET, func, obj->sess->sys->inst)

static XrResult
destroy_handle(struct oxr_logger *log, struct oxr_handle_base *hb)
{
	free(hb);
	return XR_SUCCESS;
}

void
oxr_render_model_lifetime_unref(struct oxr_render_model_lifetime *lifetime)
{
	if (lifetime != NULL && xrt_reference_dec_and_is_zero(&lifetime->reference)) {
		free(lifetime);
	}
}

static XrResult
destroy_model(struct oxr_logger *log, struct oxr_handle_base *hb)
{
	struct oxr_render_model *model = (struct oxr_render_model *)hb;
	if (model->lifetime != NULL) {
		os_mutex_lock(&model->sess->action_context.sync_actions_mutex);
		model->lifetime->live = false;
		os_mutex_unlock(&model->sess->action_context.sync_actions_mutex);
		oxr_render_model_lifetime_unref(model->lifetime);
	}
	return destroy_handle(log, hb);
}

struct observed_model
{
	struct xrt_device_id device;
	XrPath path;
	bool active;
};

static void
observe_hand(const struct oxr_action_cache *cache, XrPath path, bool active, struct observed_model *observed)
{
	static const enum xrt_input_name inputs[OXR_MODEL_COUNT] = {
	    [OXR_MODEL_CF20] = XRT_INPUT_CV10_CF20_MODEL_POSE,
	    [OXR_MODEL_PHONE] = XRT_INPUT_CV10_PHONE_MODEL_POSE,
	};
	for (size_t i = 0; i < cache->input_count; ++i) {
		const struct oxr_action_input *binding = &cache->inputs[i];
		const struct xrt_io_cache_device *dev = oxr_io_cache_find_device(binding->io_cache, binding->device_id);
		if (dev == NULL) {
			continue;
		}
		for (size_t k = 0; k < OXR_MODEL_COUNT; ++k) {
			const struct xrt_input *input = oxr_io_cache_find_input(binding->io_cache, dev, inputs[k]);
			if (input != NULL && input->active) {
				observed[k].device = binding->device_id;
				observed[k].path = path;
				observed[k].active |= active;
			}
		}
	}
}

XrResult
oxr_render_model_sync(struct oxr_logger *log, struct oxr_session *sess)
{
	static atomic_uint_fast64_t next_id = 1;
	struct oxr_session_action_context *ctx = &sess->action_context;
	struct observed_model observed[OXR_MODEL_COUNT] = {0};
	bool changed = false;
	os_mutex_lock(&ctx->sync_actions_mutex);
	for (size_t i = 0; i < ctx->action_set_attachment_count; ++i) {
		const struct oxr_action_set_attachment *set = &ctx->act_set_attachments[i];
		for (size_t j = 0; j < set->action_attachment_count; ++j) {
			const struct oxr_action_attachment *act = &set->act_attachments[j];
			const struct oxr_subaction_paths *requested = &set->requested_subaction_paths;
			observe_hand(&act->right, sess->sys->inst->path_cache.right,
			             act->act_ref->subaction_paths.right && (requested->any || requested->right),
			             observed);
			observe_hand(&act->left, sess->sys->inst->path_cache.left,
			             act->act_ref->subaction_paths.left && (requested->any || requested->left),
			             observed);
		}
	}
#ifndef CV10_MODEL_EMBEDDED
	if (observed[OXR_MODEL_CF20].device.val != XRT_DEVICE_ID_INVALID.val && cf20_glb == NULL) {
		observed[OXR_MODEL_CF20] = (struct observed_model){0};
	}
#endif
	for (size_t k = 0; k < OXR_MODEL_COUNT; ++k) {
		struct oxr_cv10_model_source *slot = &sess->render_models[k];
		const struct observed_model *source = &observed[k];
		if (source->device.val != slot->device.val || source->path != slot->user_path) {
			changed = true;
			slot->device = source->device;
			slot->user_path = source->path;
			slot->id = source->device.val == XRT_DEVICE_ID_INVALID.val
			               ? XR_NULL_RENDER_MODEL_ID_EXT
			               : atomic_fetch_add_explicit(&next_id, 1, memory_order_relaxed);
		}
		slot->subaction_active = source->active;
	}
	os_mutex_unlock(&ctx->sync_actions_mutex);

	if (changed) {
		return oxr_event_push_render_models_changed(log, sess->sys->inst);
	}
	return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL
oxr_xrEnumerateInteractionRenderModelIdsEXT(XrSession session,
                                            const XrInteractionRenderModelIdsEnumerateInfoEXT *info,
                                            uint32_t capacity,
                                            uint32_t *count,
                                            XrRenderModelIdEXT *ids)
{
	struct oxr_logger log;
	struct oxr_session *sess;
	OXR_VERIFY_SESSION_AND_INIT_LOG(&log, session, sess, "xrEnumerateInteractionRenderModelIdsEXT");
	OXR_VERIFY_SESSION_NOT_LOST(&log, sess);
	OXR_VERIFY_EXTENSION(&log, sess->sys->inst, EXT_interaction_render_model);
	OXR_VERIFY_ARG_TYPE_CAN_BE_NULL(&log, info, XR_TYPE_INTERACTION_RENDER_MODEL_IDS_ENUMERATE_INFO_EXT);
	OXR_VERIFY_TWO_CALL_ARRAY(&log, capacity, count, ids);
	OXR_VERIFY_SESSION_RUNNING(&log, sess);
	os_mutex_lock(&sess->action_context.sync_actions_mutex);
	XrRenderModelIdEXT current[OXR_MODEL_COUNT];
	uint32_t n = 0;
	for (size_t i = 0; i < OXR_MODEL_COUNT; ++i) {
		if (sess->render_models[i].id != XR_NULL_RENDER_MODEL_ID_EXT) {
			current[n++] = sess->render_models[i].id;
		}
	}
	os_mutex_unlock(&sess->action_context.sync_actions_mutex);
	OXR_TWO_CALL_HELPER(&log, capacity, count, ids, n, current, oxr_session_success_result(sess));
}

XRAPI_ATTR XrResult XRAPI_CALL
oxr_xrCreateRenderModelEXT(XrSession session, const XrRenderModelCreateInfoEXT *info, XrRenderModelEXT *renderModel)
{
	struct oxr_logger log;
	struct oxr_session *sess;
	OXR_VERIFY_SESSION_AND_INIT_LOG(&log, session, sess, "xrCreateRenderModelEXT");
	OXR_VERIFY_SESSION_NOT_LOST(&log, sess);
	OXR_VERIFY_EXTENSION(&log, sess->sys->inst, EXT_render_model);
	OXR_VERIFY_ARG_TYPE_AND_NOT_NULL(&log, info, XR_TYPE_RENDER_MODEL_CREATE_INFO_EXT);
	OXR_VERIFY_ARG_NOT_NULL(&log, renderModel);
	if (info->gltfExtensionCount > 0) {
		OXR_VERIFY_ARG_NOT_NULL(&log, info->gltfExtensions);
		for (uint32_t i = 0; i < info->gltfExtensionCount; ++i) {
			OXR_VERIFY_ARG_NOT_NULL(&log, info->gltfExtensions[i]);
		}
	}
	os_mutex_lock(&sess->action_context.sync_actions_mutex);
	enum oxr_cv10_model_kind kind = OXR_MODEL_COUNT;
	for (unsigned i = 0; i < OXR_MODEL_COUNT; ++i) {
		if (info->renderModelId != XR_NULL_RENDER_MODEL_ID_EXT && sess->render_models[i].id == info->renderModelId) {
			kind = (enum oxr_cv10_model_kind)i;
			break;
		}
	}
	os_mutex_unlock(&sess->action_context.sync_actions_mutex);
	if (kind == OXR_MODEL_COUNT) {
		return oxr_error(&log, XR_ERROR_RENDER_MODEL_ID_INVALID_EXT, "ID not enumerated for this session");
	}
	struct oxr_render_model *model;
	OXR_ALLOCATE_HANDLE_OR_RETURN(&log, model, OXR_XR_DEBUG_RENDER_MODEL, destroy_model, &sess->handle);
	model->sess = sess;
	model->lifetime = calloc(1, sizeof(*model->lifetime));
	if (model->lifetime == NULL) {
		oxr_handle_destroy(&log, &model->handle);
		return XR_ERROR_OUT_OF_MEMORY;
	}
	xrt_reference_inc(&model->lifetime->reference);
	model->lifetime->id = info->renderModelId;
	model->lifetime->kind = kind;
	model->lifetime->live = true;
	*renderModel = XRT_CAST_PTR_TO_OXR_HANDLE(XrRenderModelEXT, model);
	return oxr_session_success_result(sess);
}

XRAPI_ATTR XrResult XRAPI_CALL
oxr_xrDestroyRenderModelEXT(XrRenderModelEXT renderModel)
{
	struct oxr_logger log;
	struct oxr_render_model *model;
	VERIFY_MODEL(&log, renderModel, model, "xrDestroyRenderModelEXT");
	return oxr_handle_destroy(&log, &model->handle);
}

XRAPI_ATTR XrResult XRAPI_CALL
oxr_xrGetRenderModelPropertiesEXT(XrRenderModelEXT renderModel,
                                  const XrRenderModelPropertiesGetInfoEXT *info,
                                  XrRenderModelPropertiesEXT *properties)
{
	struct oxr_logger log;
	struct oxr_render_model *model;
	VERIFY_MODEL(&log, renderModel, model, "xrGetRenderModelPropertiesEXT");
	OXR_VERIFY_SESSION_NOT_LOST(&log, model->sess);
	OXR_VERIFY_ARG_TYPE_CAN_BE_NULL(&log, info, XR_TYPE_RENDER_MODEL_PROPERTIES_GET_INFO_EXT);
	OXR_VERIFY_ARG_TYPE_AND_NOT_NULL(&log, properties, XR_TYPE_RENDER_MODEL_PROPERTIES_EXT);
	enum oxr_cv10_model_kind kind = model->lifetime->kind;
	properties->cacheId = kind == OXR_MODEL_PHONE ? phone_uuid : cf20_uuid;
	properties->animatableNodeCount = 0;
	os_mutex_lock(&model->sess->action_context.sync_actions_mutex);
	model->sess->render_models[kind].issued_uuid = true;
	os_mutex_unlock(&model->sess->action_context.sync_actions_mutex);
	return oxr_session_success_result(model->sess);
}

XRAPI_ATTR XrResult XRAPI_CALL
oxr_xrCreateRenderModelSpaceEXT(XrSession session, const XrRenderModelSpaceCreateInfoEXT *info, XrSpace *space)
{
	struct oxr_logger log;
	struct oxr_session *sess;
	struct oxr_render_model *model;
	OXR_VERIFY_SESSION_AND_INIT_LOG(&log, session, sess, "xrCreateRenderModelSpaceEXT");
	OXR_VERIFY_SESSION_NOT_LOST(&log, sess);
	OXR_VERIFY_EXTENSION(&log, sess->sys->inst, EXT_render_model);
	OXR_VERIFY_ARG_TYPE_AND_NOT_NULL(&log, info, XR_TYPE_RENDER_MODEL_SPACE_CREATE_INFO_EXT);
	OXR_VERIFY_ARG_NOT_NULL(&log, space);
	VERIFY_MODEL(&log, info->renderModel, model, "xrCreateRenderModelSpaceEXT");
	if (model->sess != sess) {
		return oxr_error(&log, XR_ERROR_HANDLE_INVALID, "Render model belongs to a different session");
	}
	struct oxr_space *spc;
	XrResult ret = oxr_space_render_model_create(&log, sess, model->lifetime, &spc);
	if (XR_SUCCEEDED(ret)) {
		*space = oxr_space_to_openxr(spc);
	}
	return ret;
}

XRAPI_ATTR XrResult XRAPI_CALL
oxr_xrCreateRenderModelAssetEXT(XrSession session,
                                const XrRenderModelAssetCreateInfoEXT *info,
                                XrRenderModelAssetEXT *asset)
{
	struct oxr_logger log;
	struct oxr_session *sess;
	OXR_VERIFY_SESSION_AND_INIT_LOG(&log, session, sess, "xrCreateRenderModelAssetEXT");
	OXR_VERIFY_SESSION_NOT_LOST(&log, sess);
	OXR_VERIFY_EXTENSION(&log, sess->sys->inst, EXT_render_model);
	OXR_VERIFY_ARG_TYPE_AND_NOT_NULL(&log, info, XR_TYPE_RENDER_MODEL_ASSET_CREATE_INFO_EXT);
	OXR_VERIFY_ARG_NOT_NULL(&log, asset);
	os_mutex_lock(&sess->action_context.sync_actions_mutex);
	const uint8_t *data = NULL;
	uint32_t size = 0;
	if (sess->render_models[OXR_MODEL_PHONE].issued_uuid && memcmp(&phone_uuid, &info->cacheId, sizeof(phone_uuid)) == 0) {
		data = phone_glb;
		size = sizeof(phone_glb);
	} else if (sess->render_models[OXR_MODEL_CF20].issued_uuid && memcmp(&cf20_uuid, &info->cacheId, sizeof(cf20_uuid)) == 0) {
		data = cf20_glb;
		size = CF20_MODEL_LENGTH;
	}
	os_mutex_unlock(&sess->action_context.sync_actions_mutex);
	if (data == NULL) {
		return oxr_error(&log, XR_ERROR_RENDER_MODEL_ASSET_UNAVAILABLE_EXT,
		                 "Unknown cache ID for this session");
	}
	struct oxr_render_asset *obj;
	OXR_ALLOCATE_HANDLE_OR_RETURN(&log, obj, OXR_XR_DEBUG_RENDER_ASSET, destroy_handle, &sess->handle);
	obj->sess = sess;
	obj->data = data;
	obj->size = size;
	*asset = XRT_CAST_PTR_TO_OXR_HANDLE(XrRenderModelAssetEXT, obj);
	return oxr_session_success_result(sess);
}

XRAPI_ATTR XrResult XRAPI_CALL
oxr_xrDestroyRenderModelAssetEXT(XrRenderModelAssetEXT asset)
{
	struct oxr_logger log;
	struct oxr_render_asset *obj;
	VERIFY_ASSET(&log, asset, obj, "xrDestroyRenderModelAssetEXT");
	return oxr_handle_destroy(&log, &obj->handle);
}

XRAPI_ATTR XrResult XRAPI_CALL
oxr_xrGetRenderModelAssetDataEXT(XrRenderModelAssetEXT asset,
                                 const XrRenderModelAssetDataGetInfoEXT *info,
                                 XrRenderModelAssetDataEXT *buffer)
{
	struct oxr_logger log;
	struct oxr_render_asset *obj;
	VERIFY_ASSET(&log, asset, obj, "xrGetRenderModelAssetDataEXT");
	OXR_VERIFY_SESSION_NOT_LOST(&log, obj->sess);
	OXR_VERIFY_ARG_TYPE_CAN_BE_NULL(&log, info, XR_TYPE_RENDER_MODEL_ASSET_DATA_GET_INFO_EXT);
	OXR_VERIFY_ARG_TYPE_AND_NOT_NULL(&log, buffer, XR_TYPE_RENDER_MODEL_ASSET_DATA_EXT);
	const uint32_t size = obj->size;
	OXR_VERIFY_TWO_CALL_ARRAY(&log, buffer->bufferCapacityInput, &buffer->bufferCountOutput, buffer->buffer);
	OXR_TWO_CALL_CHECK_ONLY(&log, buffer->bufferCapacityInput, &buffer->bufferCountOutput, size,
	                        oxr_session_success_result(obj->sess));
	memcpy(buffer->buffer, obj->data, size);
	return oxr_session_success_result(obj->sess);
}

XRAPI_ATTR XrResult XRAPI_CALL
oxr_xrGetRenderModelAssetPropertiesEXT(XrRenderModelAssetEXT asset,
                                       const XrRenderModelAssetPropertiesGetInfoEXT *info,
                                       XrRenderModelAssetPropertiesEXT *properties)
{
	struct oxr_logger log;
	struct oxr_render_asset *obj;
	VERIFY_ASSET(&log, asset, obj, "xrGetRenderModelAssetPropertiesEXT");
	OXR_VERIFY_SESSION_NOT_LOST(&log, obj->sess);
	OXR_VERIFY_ARG_TYPE_CAN_BE_NULL(&log, info, XR_TYPE_RENDER_MODEL_ASSET_PROPERTIES_GET_INFO_EXT);
	OXR_VERIFY_ARG_TYPE_AND_NOT_NULL(&log, properties, XR_TYPE_RENDER_MODEL_ASSET_PROPERTIES_EXT);
	if (properties->nodePropertyCount != 0) {
		return oxr_error(&log, XR_ERROR_VALIDATION_FAILURE, "This model has zero animated nodes");
	}
	return oxr_session_success_result(obj->sess);
}

XRAPI_ATTR XrResult XRAPI_CALL
oxr_xrGetRenderModelStateEXT(XrRenderModelEXT renderModel,
                             const XrRenderModelStateGetInfoEXT *info,
                             XrRenderModelStateEXT *state)
{
	struct oxr_logger log;
	struct oxr_render_model *model;
	VERIFY_MODEL(&log, renderModel, model, "xrGetRenderModelStateEXT");
	OXR_VERIFY_SESSION_NOT_LOST(&log, model->sess);
	OXR_VERIFY_ARG_TYPE_AND_NOT_NULL(&log, info, XR_TYPE_RENDER_MODEL_STATE_GET_INFO_EXT);
	OXR_VERIFY_ARG_TYPE_AND_NOT_NULL(&log, state, XR_TYPE_RENDER_MODEL_STATE_EXT);
	if (info->displayTime <= 0) {
		return oxr_error(&log, XR_ERROR_TIME_INVALID, "Invalid displayTime");
	}
	if (state->nodeStateCount != 0) {
		return oxr_error(&log, XR_ERROR_VALIDATION_FAILURE, "This model has zero animated nodes");
	}
	return oxr_session_success_result(model->sess);
}

XRAPI_ATTR XrResult XRAPI_CALL
oxr_xrEnumerateRenderModelSubactionPathsEXT(XrRenderModelEXT renderModel,
                                            const XrInteractionRenderModelSubactionPathInfoEXT *info,
                                            uint32_t capacity,
                                            uint32_t *count,
                                            XrPath *paths)
{
	struct oxr_logger log;
	struct oxr_render_model *model;
	VERIFY_MODEL(&log, renderModel, model, "xrEnumerateRenderModelSubactionPathsEXT");
	struct oxr_session *sess = model->sess;
	OXR_VERIFY_SESSION_NOT_LOST(&log, sess);
	OXR_VERIFY_EXTENSION(&log, sess->sys->inst, EXT_interaction_render_model);
	OXR_VERIFY_ARG_TYPE_CAN_BE_NULL(&log, info, XR_TYPE_INTERACTION_RENDER_MODEL_SUBACTION_PATH_INFO_EXT);
	OXR_VERIFY_TWO_CALL_ARRAY(&log, capacity, count, paths);
	os_mutex_lock(&sess->action_context.sync_actions_mutex);
	const struct oxr_cv10_model_source *slot = &sess->render_models[model->lifetime->kind];
	uint32_t n = model->lifetime->id == slot->id && slot->subaction_active ? 1 : 0;
	XrPath user_path = slot->user_path;
	os_mutex_unlock(&sess->action_context.sync_actions_mutex);
	OXR_TWO_CALL_HELPER(&log, capacity, count, paths, n, &user_path, oxr_session_success_result(sess));
}

XRAPI_ATTR XrResult XRAPI_CALL
oxr_xrGetRenderModelPoseTopLevelUserPathEXT(XrRenderModelEXT renderModel,
                                            const XrInteractionRenderModelTopLevelUserPathGetInfoEXT *info,
                                            XrPath *topLevelUserPath)
{
	struct oxr_logger log;
	struct oxr_render_model *model;
	VERIFY_MODEL(&log, renderModel, model, "xrGetRenderModelPoseTopLevelUserPathEXT");
	struct oxr_session *sess = model->sess;
	OXR_VERIFY_SESSION_NOT_LOST(&log, sess);
	OXR_VERIFY_EXTENSION(&log, sess->sys->inst, EXT_interaction_render_model);
	OXR_VERIFY_ARG_TYPE_AND_NOT_NULL(&log, info, XR_TYPE_INTERACTION_RENDER_MODEL_TOP_LEVEL_USER_PATH_GET_INFO_EXT);
	OXR_VERIFY_ARG_NOT_NULL(&log, topLevelUserPath);
	if (info->topLevelUserPathCount > 0) {
		OXR_VERIFY_ARG_NOT_NULL(&log, info->topLevelUserPaths);
	}
	os_mutex_lock(&sess->action_context.sync_actions_mutex);
	const struct oxr_cv10_model_source *slot = &sess->render_models[model->lifetime->kind];
	XrPath user_path = model->lifetime->id == slot->id ? slot->user_path : XR_NULL_PATH;
	os_mutex_unlock(&sess->action_context.sync_actions_mutex);
	struct oxr_instance *inst = sess->sys->inst;
	XrPath matched = XR_NULL_PATH;
	for (uint32_t i = 0; i < info->topLevelUserPathCount; ++i) {
		XrPath path = info->topLevelUserPaths[i];
		for (uint32_t j = 0; j < i; ++j) {
			if (path == info->topLevelUserPaths[j]) {
				return oxr_error(&log, XR_ERROR_VALIDATION_FAILURE, "Duplicate top-level path");
			}
		}
		struct oxr_subaction_paths classified;
		if (path == XR_NULL_PATH || !oxr_classify_subaction_path(&inst->path_cache, path, &classified)) {
			// Core also reserves /user/treadmill, although it has no Monado action bindings.
			const char *name;
			size_t length;
			if (oxr_path_store_get_string(&inst->path_store, path, &name, &length) != XR_SUCCESS ||
			    strcmp(name, "/user/treadmill") != 0) {
				return oxr_error(&log, XR_ERROR_PATH_INVALID, "Expected a top-level /user path");
			}
		}
		if (path == user_path) {
			matched = path;
		}
	}
	*topLevelUserPath = matched;
	return oxr_session_success_result(sess);
}
