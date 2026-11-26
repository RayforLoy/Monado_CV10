#!/usr/bin/env python3
# SPDX-License-Identifier: BSL-1.0

import argparse
import io
import os
from pathlib import Path
import struct
import subprocess
import tempfile
from zipfile import ZIP_STORED, ZipFile


POCKET_SOURCE = "a15941723f72ac3308291de711bc6071e4278293"
POCKET_RUNTIME_VERSION = b"v25.1.0-1070-gc865e3f57\0"
ROOT = Path(__file__).resolve().parent


def validate_runtime(path):
    data = path.read_bytes()
    if not data.startswith(b"\x7fELF\x02\x01") or struct.unpack_from("<H", data, 18)[0] != 183:
        raise ValueError("The Pocket XR client must be an arm64 ELF library")
    if POCKET_RUNTIME_VERSION not in data:
        raise ValueError("The library does not have the supported Pocket XR IPC version")

    headers, = struct.unpack_from("<Q", data, 32)
    header_size, count = struct.unpack_from("<HH", data, 54)
    nodelete = False
    for index in range(count):
        header = headers + index * header_size
        kind, _, offset, _, _, size, _, alignment = struct.unpack_from("<IIQQQQQQ", data, header)
        if kind == 1 and alignment < 16384:  # PT_LOAD
            raise ValueError("The library has a load segment aligned below 16 KiB")
        if kind == 2:  # PT_DYNAMIC
            for entry in range(offset, offset + size, 16):
                tag, flags = struct.unpack_from("<QQ", data, entry)
                if tag == 0:
                    break
                if tag == 0x6FFFFFFB:  # DT_FLAGS_1
                    nodelete = bool(flags & 8)  # DF_1_NODELETE
    if not nodelete:
        raise ValueError("Rebuild the Pocket XR client with -z,nodelete")


def run(*args):
    subprocess.run(args, check=True)


def main():
    parser = argparse.ArgumentParser(description="Package the optional Pocket XR client for Monado CV10")
    parser.add_argument("--pocket-source", type=Path, required=True, help="Pocket XR Git repository with tag v1.6.31")
    parser.add_argument("--runtime", type=Path, required=True, help="Source-built libopenxr_monado.so")
    parser.add_argument("--kotlin-stdlib", type=Path, required=True, help="Kotlin stdlib 2.0.0 JAR")
    parser.add_argument("--platform", type=Path, required=True, help="Android 35 android.jar")
    parser.add_argument("--build-tools", type=Path, required=True, help="Android Build Tools 36 directory")
    parser.add_argument("--keystore", type=Path, required=True, help="Keystore also used to sign the CV10 preview")
    parser.add_argument("--key-alias", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    validate_runtime(args.runtime)
    suffix = ".exe" if os.name == "nt" else ""
    jdk = Path(os.environ["JAVA_HOME"]) / "bin" if "JAVA_HOME" in os.environ else None
    java = jdk / ("java" + suffix) if jdk else "java"
    javac = jdk / ("javac" + suffix) if jdk else "javac"
    aapt = args.build_tools / ("aapt2" + suffix)
    zipalign = args.build_tools / ("zipalign" + suffix)
    apksigner = args.build_tools / "lib/apksigner.jar"

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=args.output.parent) as directory:
        staging = Path(directory)
        jars = []
        for artifact in ("monado-auxiliary", "monado-ipc"):
            aar = subprocess.check_output(("git", "-C", args.pocket_source, "show",
                                           f"{POCKET_SOURCE}:app/libs/monado/{artifact}.aar"))
            jar = staging / f"{artifact}.jar"
            with ZipFile(io.BytesIO(aar)) as source:
                jar.write_bytes(source.read("classes.jar"))
            jars.append(jar)

        run(javac, "--release", "11", "-cp", args.platform, "-d", staging, ROOT / "RuntimeDiscoveryService.java")
        service = staging / "org/cv10/monado/pocket_client/RuntimeDiscoveryService.class"
        run(java, "-cp", args.build_tools / "lib/d8.jar", "com.android.tools.r8.D8", "--min-api", "26",
            "--lib", args.platform, "--output", staging, service, *jars, args.kotlin_stdlib)
        unsigned = staging / "unsigned.apk"
        aligned = staging / "aligned.apk"
        signed = staging / "signed.apk"
        run(aapt, "link", "-I", args.platform, "--manifest", ROOT / "AndroidManifest.xml", "-o", unsigned)
        with ZipFile(unsigned, "a", ZIP_STORED) as archive:
            archive.write(staging / "classes.dex", "classes.dex")
            archive.write(args.runtime, "lib/arm64-v8a/libopenxr_monado.so")
        run(zipalign, "-f", "-P", "16", "4", unsigned, aligned)
        signing = ["sign", "--ks", args.keystore, "--ks-key-alias", args.key_alias]
        if "CV10_POCKET_KEYSTORE_PASSWORD" in os.environ:
            signing += ["--ks-pass", "env:CV10_POCKET_KEYSTORE_PASSWORD"]
        if "CV10_POCKET_KEY_PASSWORD" in os.environ:
            signing += ["--key-pass", "env:CV10_POCKET_KEY_PASSWORD"]
        run(java, "-jar", apksigner, *signing, "--out", signed, aligned)
        run(java, "-jar", apksigner, "verify", signed)
        run(zipalign, "-c", "-P", "16", "4", signed)
        signed.replace(args.output)


if __name__ == "__main__":
    main()
