// SPDX-License-Identifier: BSL-1.0
#pragma once

#include <openxr/openxr.h>

#include <cmath>

namespace cv10_launcher {

inline XrVector3f
operator+(XrVector3f a, XrVector3f b)
{
	return {a.x + b.x, a.y + b.y, a.z + b.z};
}

inline XrVector3f
operator-(XrVector3f a, XrVector3f b)
{
	return {a.x - b.x, a.y - b.y, a.z - b.z};
}

inline XrVector3f
operator*(XrVector3f v, float s)
{
	return {v.x * s, v.y * s, v.z * s};
}

inline float
dot(XrVector3f a, XrVector3f b)
{
	return a.x * b.x + a.y * b.y + a.z * b.z;
}

inline XrVector3f
cross(XrVector3f a, XrVector3f b)
{
	return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x};
}

inline XrVector3f
unit(XrVector3f v)
{
	const float length = std::sqrt(dot(v, v));
	return length > 0.00001f ? v * (1.0f / length) : XrVector3f{};
}

inline XrVector3f
rotate(XrQuaternionf q, XrVector3f v)
{
	const XrVector3f axis{q.x, q.y, q.z};
	const XrVector3f t = cross(axis, v) * 2.0f;
	return v + t * q.w + cross(axis, t);
}

inline XrQuaternionf
conjugate(XrQuaternionf q)
{
	return {-q.x, -q.y, -q.z, q.w};
}

inline XrQuaternionf
multiply(XrQuaternionf a, XrQuaternionf b)
{
	const XrVector3f v{a.x, a.y, a.z};
	const XrVector3f w{b.x, b.y, b.z};
	const auto axis = w * a.w + v * b.w + cross(v, w);
	return {axis.x, axis.y, axis.z, a.w * b.w - dot(v, w)};
}

enum class PointerSource
{
	Phone,
	Cf20,
	Kit,
};

struct Aim
{
	PointerSource source;
	XrVector3f origin;
	XrVector3f forward;
	XrVector3f up;
};

struct PanelHit
{
	XrVector3f point{};
	float x = 0;
	float y = 0;
	bool valid = false;
};

inline PanelHit
intersect_panel(const Aim &aim, XrPosef panel, float width, float height)
{
	XrVector3f origin = aim.origin;
	XrVector3f direction = aim.forward;
	if (aim.source == PointerSource::Kit) {
		// HVRRaycasterBase.GetRay with the 6DoF controller scale reset to zero.
		origin = origin - aim.forward * 0.002f - aim.up * 0.040f;
		direction = aim.forward * 1.002f - aim.up * 0.993f;
	}
	const auto inverse = conjugate(panel.orientation);
	const auto from = rotate(inverse, origin - panel.position);
	const auto ray = rotate(inverse, direction);
	if (ray.z >= -0.00001f || from.z <= 0.0f) {
		return {};
	}
	const float distance = -from.z / ray.z;
	const auto hit = from + ray * distance;
	const float x = hit.x * 2.0f / width;
	const float y = -hit.y * 2.0f / height;
	return {origin + direction * distance, x, y, std::abs(x) <= 1.0f && std::abs(y) <= 1.0f};
}

struct Pointer
{
	XrVector3f beam_start{};
	XrVector3f beam_end{};
	XrVector3f ring_center{};
	float ring_size = 0;
	bool near_hit = false;
};

inline Pointer
pointer_geometry(const Aim &aim, const PanelHit &hit)
{
	XrVector3f direction = aim.forward;
	XrVector3f start = aim.origin;
	switch (aim.source) {
	case PointerSource::Phone: start = start + aim.forward * 0.0644f; break;
	case PointerSource::Cf20: start = start + aim.forward * 0.069f + aim.up * 0.020f; break;
	case PointerSource::Kit:
		start = start - aim.forward * 0.010f - aim.up * 0.034f;
		direction = direction - aim.up;
		break;
	}
	constexpr float far_distance = 7.0f;
	const float distance = std::sqrt(dot(hit.point - aim.origin, hit.point - aim.origin));
	if (hit.valid && distance < far_distance) {
		return {start, aim.origin + direction * (0.8f * distance) + aim.up * 0.020f, hit.point, 0.24f, true};
	}
	const auto far_point = aim.origin + direction * far_distance;
	return {start, far_point, far_point, 0.06f, false};
}

} // namespace cv10_launcher
