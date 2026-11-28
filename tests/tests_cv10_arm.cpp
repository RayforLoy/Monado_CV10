// SPDX-License-Identifier: GPL-3.0-or-later
#include "catch_amalgamated.hpp"
#include "math/m_api.h"
extern "C" {
#include "android/cv10_pose_models.h"
}

TEST_CASE("CV10 orientation-only wrists move on either shoulder", "[cv10]")
{
	xrt_space_relation head{};
	head.pose.orientation.w = 1;
	head.pose.position.y = 1.5f;
	head.relation_flags = static_cast<xrt_space_relation_flags>(
	    XRT_SPACE_RELATION_POSITION_VALID_BIT | XRT_SPACE_RELATION_ORIENTATION_VALID_BIT);
	const xrt_vec3 x = {1, 0, 0};
	for (bool right : {false, true}) {
		cv10_arm_model arm{};
		xrt_space_relation hand{};
		hand.pose.orientation.w = 1;
		cv10_arm_model_apply(&arm, 1000000000, &head, right, &hand);
		auto neutral = hand.pose.position;
		CHECK((right ? neutral.x : -neutral.x) > 0.20f);
		math_quat_from_angle_vector(0.65f, &x, &hand.pose.orientation);
		cv10_arm_model_apply(&arm, 1100000000, &head, right, &hand);
		CHECK(hand.pose.position.y > neutral.y + 0.1f);
		CHECK((hand.relation_flags & XRT_SPACE_RELATION_POSITION_VALID_BIT) != 0);
		CHECK((hand.relation_flags & XRT_SPACE_RELATION_POSITION_TRACKED_BIT) == 0);
	}
}
