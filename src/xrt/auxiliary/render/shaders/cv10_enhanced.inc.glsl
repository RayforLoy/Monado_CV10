// SPDX-License-Identifier: BSL-1.0

vec3 cv10_enhance_tap(sampler2D image, vec2 red_uv, vec2 green_uv, vec2 blue_uv,
                      vec2 offset, vec2 uv_min, vec2 uv_max)
{
	return vec3(
		textureLod(image, clamp(red_uv + offset, uv_min, uv_max), 0.0).r,
		textureLod(image, clamp(green_uv + offset, uv_min, uv_max), 0.0).g,
		textureLod(image, clamp(blue_uv + offset, uv_min, uv_max), 0.0).b);
}

vec3 cv10_enhance(sampler2D image, vec3 rgb, vec2 red_uv, vec2 green_uv, vec2 blue_uv,
                  vec4 eye_rect)
{
	// The OEM filter fades to ordinary chromatic sampling at 0.15 eye-relative UV.
	float radius = length((green_uv - eye_rect.xy) / eye_rect.zw - vec2(0.5));
	if (radius >= 0.15) {
		return rgb;
	}

	// Preserve RGB distortion coordinates and keep taps inside the eye subimage.
	vec2 span = abs(eye_rect.zw);
	vec2 half_texel = min(0.5 / vec2(textureSize(image, 0)), span * 0.5);
	vec2 uv_min = min(eye_rect.xy, eye_rect.xy + eye_rect.zw) + half_texel;
	vec2 uv_max = max(eye_rect.xy, eye_rect.xy + eye_rect.zw) - half_texel;
	vec2 dx = vec2(0.000644 * span.x, 0.0);
	vec2 dy = vec2(0.0, 0.000644 * span.y);
	vec3 neighbours = cv10_enhance_tap(image, red_uv, green_uv, blue_uv, -dx, uv_min, uv_max)
	                + cv10_enhance_tap(image, red_uv, green_uv, blue_uv,  dx, uv_min, uv_max)
	                + cv10_enhance_tap(image, red_uv, green_uv, blue_uv, -dy, uv_min, uv_max)
	                + cv10_enhance_tap(image, red_uv, green_uv, blue_uv,  dy, uv_min, uv_max);
	vec3 enhanced = clamp(3.0 * rgb - 0.5 * neighbours, 0.0, 1.0);

	// The OEM center pass also adjusts shadows, highlights, saturation and vibrance.
	float lightness = dot(enhanced, vec3(0.33));
	float shadows_base = pow(lightness, 1.0 / 1.1);
	float highlights_base = pow(1.0 - lightness, 1.0 / 1.3);
	float shadows = clamp(shadows_base - 0.76 * shadows_base * shadows_base - lightness, 0.0, 1.0);
	float highlights = clamp(1.0 - highlights_base + 0.9 * highlights_base * highlights_base - lightness, -1.0, 0.0);
	if (lightness >= 0.0001) {
		enhanced = clamp(enhanced * (lightness + shadows + highlights) / lightness, 0.0, 1.0);
	}
	vec3 grey = vec3(dot(enhanced, vec3(0.2125, 0.7154, 0.0721)));
	enhanced = clamp(mix(grey, enhanced, 1.1), 0.0, 1.0);
	float peak = max(enhanced.r, max(enhanced.g, enhanced.b));
	float vibrance = -0.15 * (peak - dot(enhanced, vec3(1.0 / 3.0)));
	enhanced = clamp(mix(enhanced, vec3(peak), vibrance), 0.0, 1.0);
	return mix(enhanced, rgb, radius / 0.15);
}
