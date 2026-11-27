// Copyright 2017, James Sarrett.
// Copyright 2017, Bastiaan Olij.
// Copyright 2019, Collabora, Ltd.
// SPDX-License-Identifier: BSL-1.0
// Author: James Sarrett <jsarrett@gmail.com>
// Author: Bastiaan Olij <mux213@gmail.com>
// Author: Lubosz Sarnecki <lubosz.sarnecki@collabora.com>
// Author: Pete Black <pete.black@collabora.com>

#version 450
#extension GL_GOOGLE_include_directive : require

#include "cv10_enhanced.inc.glsl"


layout (binding = 0) uniform sampler2D tex_sampler;
layout (binding = 1, std140) uniform Config
{
	vec4 vertex_rot;
	vec4 post_transform;
	vec4 pre_transform;
	mat4 transform_scanout_begin;
	mat4 transform_scanout_end;
	vec4 enhanced_render_mode;
} ubo;

layout (location = 0)  in vec2 in_ruv;
layout (location = 1)  in vec2 in_guv;
layout (location = 2)  in vec2 in_buv;
layout (location = 0) out vec4 out_color;


void main()
{
	float r = texture(tex_sampler, in_ruv).x;
	float g = texture(tex_sampler, in_guv).y;
	float b = texture(tex_sampler, in_buv).z;

	vec3 rgb = vec3(r, g, b);
	if (ubo.enhanced_render_mode.x > 0.0) {
		rgb = cv10_enhance(tex_sampler, rgb, in_ruv, in_guv, in_buv, ubo.post_transform);
	}
	out_color = vec4(rgb, 1.0);
}
