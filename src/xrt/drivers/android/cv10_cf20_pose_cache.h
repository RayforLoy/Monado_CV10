// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include "xrt/xrt_defines.h"

// Each device owns its cache; callers hold the device's worker mutex.
// Applications can query interleaved display times, including while loading a model.
#define CV10_CF20_POSE_CACHE_CAPACITY 64

struct cv10_hand_pose
{
	struct xrt_space_relation grip;
	// The phone has a calibrated ray independent of the physical chassis orientation.
	struct xrt_quat aim_orientation;
};

struct cv10_cf20_pose_cache
{
	struct {
		int64_t display_ns;
		int64_t sampled_ns;
		struct cv10_hand_pose pose;
	} frames[CV10_CF20_POSE_CACHE_CAPACITY];
	unsigned count;
	unsigned next;
};

static inline void
cv10_cf20_pose_cache_clear(struct cv10_cf20_pose_cache *cache)
{
	cache->count = 0;
	cache->next = 0;
}

static inline bool
cv10_cf20_pose_cache_get(const struct cv10_cf20_pose_cache *cache, int64_t display_ns, int64_t now_ns,
                         int64_t max_age_ns, struct cv10_hand_pose *out)
{
	for (unsigned i = 0; i < cache->count; ++i) {
		unsigned index = (cache->next + CV10_CF20_POSE_CACHE_CAPACITY - 1 - i) % CV10_CF20_POSE_CACHE_CAPACITY;
		if (now_ns - cache->frames[index].sampled_ns > max_age_ns) {
			break;
		}
		if (cache->frames[index].display_ns == display_ns) {
			*out = cache->frames[index].pose;
			return true;
		}
	}
	return false;
}

static inline void
cv10_cf20_pose_cache_put(struct cv10_cf20_pose_cache *cache, int64_t display_ns, int64_t now_ns,
                         const struct cv10_hand_pose *pose)
{
	cache->frames[cache->next].display_ns = display_ns;
	cache->frames[cache->next].sampled_ns = now_ns;
	cache->frames[cache->next].pose = *pose;
	cache->next = (cache->next + 1) % CV10_CF20_POSE_CACHE_CAPACITY;
	if (cache->count < CV10_CF20_POSE_CACHE_CAPACITY) {
		cache->count++;
	}
}
