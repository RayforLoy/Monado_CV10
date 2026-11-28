// SPDX-License-Identifier: GPL-3.0-or-later
#include "catch_amalgamated.hpp"
#include "android/cv10_touchpad.h"

TEST_CASE("CV10 touchpad keeps contact, slide and confirm independent", "[cv10]")
{
	xrt_input click{}, touch{}, xy{};
	auto feed = [&](bool hold, bool contact, float x, float y, int64_t time) {
		cv10_update_touchpad(&click, &touch, &xy, hold, contact, {x, y}, time);
	};
	feed(false, false, 0, 0, 1);
	feed(false, true, 0, 0, 2);
	CHECK(touch.active);
	CHECK(touch.value.boolean);
	CHECK_FALSE(click.value.boolean);
	feed(false, true, -0.75f, 0.50f, 3);
	CHECK(xy.value.vec2.x == -0.75f);
	CHECK(xy.value.vec2.y == 0.50f);
	CHECK(xy.timestamp == 3);
	CHECK(touch.timestamp == 2);
	CHECK(click.timestamp == 1);
	feed(false, false, -0.75f, 0.50f, 4);
	CHECK_FALSE(click.value.boolean);
	CHECK_FALSE(touch.value.boolean);
	CHECK(xy.value.vec2.x == 0.0f);
	CHECK(xy.value.vec2.y == 0.0f);

	feed(false, true, 0.2f, -0.6f, 5);
	feed(true, true, 0.2f, -0.6f, 6);
	CHECK(click.active);
	CHECK(click.value.boolean);
	CHECK(click.timestamp == 6);
	CHECK(touch.timestamp == 5);
	CHECK(xy.timestamp == 5);
	feed(true, true, 0.2f, -0.6f, 7);
	CHECK(click.timestamp == 6);
	feed(false, false, 0, 0, 8);
	CHECK_FALSE(click.value.boolean);
	CHECK(click.timestamp == 8);
}
