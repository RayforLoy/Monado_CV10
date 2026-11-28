# SPDX-License-Identifier: BSL-1.0
"""Validate and embed the optional, locally supplied interaction render model."""
import hashlib
import json
import struct
import sys
from pathlib import Path


def main():
    src, dst = map(Path, sys.argv[1:3])
    prefix = "phone" if sys.argv[3:] == ["phone"] else "cf20"
    data = src.read_bytes()
    magic, version, length, json_length, chunk_type = struct.unpack_from("<4sIII4s", data)
    if (magic, version, length, chunk_type) != (b"glTF", 2, len(data), b"JSON"):
        raise ValueError("Expected a complete binary glTF 2.0 model")
    doc = json.loads(data[20:20 + json_length])
    scenes = doc.get("scenes", [])
    if not 0 <= doc.get("scene", -1) < len(scenes) or doc.get("extensionsRequired"):
        raise ValueError("Render model needs a default scene and no required extensions")
    for item in doc.get("buffers", []) + doc.get("images", []):
        if "uri" in item and not item["uri"].startswith("data:"):
            raise ValueError("External resources are not permitted in an interaction render model")

    # RFC 9562 application-defined UUIDv8, content-addressed across sessions.
    digest = bytearray(hashlib.sha256(data).digest()[:16])
    digest[6] = (digest[6] & 0x0F) | 0x80
    digest[8] = (digest[8] & 0x3F) | 0x80
    with dst.open("w", encoding="ascii", newline="\n") as out:
        out.write("static const XrUuidEXT " + prefix + "_uuid = {{")
        out.write(", ".join(hex(i) for i in digest))
        out.write("}};\nstatic const uint8_t " + prefix + "_glb[] = {\n")
        for start in range(0, len(data), 24):
            out.write(",".join(f"0x{i:02x}" for i in data[start:start + 24]) + ",\n")
        out.write("};\n")


if __name__ == "__main__":
    main()
