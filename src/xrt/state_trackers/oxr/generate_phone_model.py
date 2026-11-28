# SPDX-License-Identifier: BSL-1.0
"""Generate the original, self-contained CV10 phone controller GLB in grip-local metres."""
import json
import math
import struct
import sys
from pathlib import Path


def build():
    data = bytearray()
    views, accessors, materials, primitives = [], [], [], []

    def accessor(rows, component, type_name, component_type, target):
        data.extend(b"\0" * (-len(data) % 4))
        offset = len(data)
        for row in rows:
            data.extend(struct.pack("<" + component * len(row), *row))
        views.append(dict(buffer=0, byteOffset=offset, byteLength=len(data) - offset, target=target))
        accessors.append(dict(bufferView=len(views)-1, componentType=component_type, count=len(rows),
                              type=type_name, min=list(map(min, zip(*rows))), max=list(map(max, zip(*rows)))))
        return len(accessors) - 1

    def rounded_panel(name, width, height, radius, y, back, front, color):
        # Counter-clockwise rounded outline on XY. +Y is up, +Z is the touch surface facing the user.
        ring = []
        for quadrant, (sx, sy) in enumerate(((1, 1), (-1, 1), (-1, -1), (1, -1))):
            for step in range(5):
                angle = (quadrant + step / 4) * math.pi / 2
                ring.append((sx * (width / 2 - radius) + radius * math.cos(angle),
                             y + sy * (height / 2 - radius) + radius * math.sin(angle)))
        positions, normals, indices = [], [], []
        for z, sign in ((front, 1), (back, -1)):
            base = len(positions)
            positions.append((0, y, z))
            positions.extend((px, py, z) for px, py in ring)
            normals.extend([(0, 0, sign)] * (len(ring) + 1))
            for i in range(len(ring)):
                a, b = base + 1 + i, base + 1 + (i + 1) % len(ring)
                indices.extend((base, a, b) if sign == 1 else (base, b, a))
        for i, (ax, ay) in enumerate(ring):
            bx, by = ring[(i + 1) % len(ring)]
            length = math.hypot(bx - ax, by - ay)
            if length < 1e-9:
                continue
            base = len(positions)
            positions.extend(((ax, ay, back), (bx, by, back), (bx, by, front), (ax, ay, front)))
            normals.extend([((by - ay) / length, (ax - bx) / length, 0)] * 4)
            indices.extend(base + k for k in (0, 1, 2, 0, 2, 3))
        p = accessor(positions, "f", "VEC3", 5126, 34962)
        n = accessor(normals, "f", "VEC3", 5126, 34962)
        idx = accessor([(i,) for i in indices], "H", "SCALAR", 5123, 34963)
        materials.append(dict(name=name, pbrMetallicRoughness=dict(
            baseColorFactor=[*color, 1], metallicFactor=0.15, roughnessFactor=0.72)))
        primitives.append(dict(attributes=dict(POSITION=p, NORMAL=n), indices=idx,
                               material=len(materials)-1, mode=4))

    rounded_panel("Frame", 0.073, 0.149, 0.008, 0, -0.0045, 0.0035, (0.24, 0.30, 0.36))
    rounded_panel("Bezel", 0.071, 0.147, 0.007, 0, 0.00345, 0.0041, (0.055, 0.072, 0.095))
    rounded_panel("Touch surface", 0.064, 0.128, 0.005, 0, 0.00405, 0.0045, (0.035, 0.35, 0.46))
    rounded_panel("Earpiece", 0.016, 0.002, 0.0008, 0.068, 0.00408, 0.00455, (0.49, 0.57, 0.65))
    rounded_panel("Gesture bar", 0.026, 0.0024, 0.001, -0.057, 0.00445, 0.00465, (0.75, 0.92, 0.94))
    data.extend(b"\0" * (-len(data) % 4))
    doc = dict(asset=dict(version="2.0", generator="Monado CV10"), scene=0, scenes=[dict(nodes=[0])],
               nodes=[dict(name="Phone", mesh=0)], meshes=[dict(primitives=primitives)], materials=materials,
               accessors=accessors, bufferViews=views, buffers=[dict(byteLength=len(data))])
    header = json.dumps(doc, separators=(",", ":")).encode("ascii")
    header += b" " * (-len(header) % 4)
    return (struct.pack("<4sIII4s", b"glTF", 2, 28 + len(header) + len(data), len(header), b"JSON")
            + header + struct.pack("<I4s", len(data), b"BIN\0") + data)


if __name__ == "__main__":
    Path(sys.argv[1]).write_bytes(build())
