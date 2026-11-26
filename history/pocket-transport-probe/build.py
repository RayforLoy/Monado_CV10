import argparse
import os
from pathlib import Path
import subprocess
import tempfile
from zipfile import ZipFile


def run(*args):
    subprocess.run(args, check=True)


def main():
    parser = argparse.ArgumentParser(description='Build the Android Pocket XR display transport probe.')
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--platform', type=Path, help='Android 35 platform directory when separate from the SDK')
    parser.add_argument('--gradle-cache', type=Path,
                        default=Path.home() / '.gradle/caches/modules-2/files-2.1')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()

    source = Path(__file__).resolve().parent
    project = source.parent.parent
    platform = (args.platform or args.sdk / 'platforms/android-35') / 'android.jar'
    tools = args.sdk / 'build-tools/36.0.0'
    jdk = args.jdk / 'bin'
    suffix = '.exe' if os.name == 'nt' else ''
    aux = project / 'src/xrt/auxiliary/android/src/main'
    service = Path('org/freedesktop/monado/auxiliary')
    transport = (project / 'src/xrt/targets/openxr_android/src/main/java'
                 / 'org/freedesktop/monado/openxr_runtime/Cv10PocketXrTransport.kt')

    def dependency(name, version, extension='jar'):
        return next((args.gradle_cache / name / version).glob(f'*/*.{extension}'))

    stdlib = dependency('org.jetbrains.kotlin/kotlin-stdlib', '2.2.10')
    kotlin = [stdlib, dependency('org.jetbrains.kotlin/kotlin-compiler-embeddable', '2.2.10'),
              dependency('org.jetbrains.kotlin/kotlin-script-runtime', '2.2.10'),
              dependency('org.jetbrains.kotlin/kotlin-reflect', '1.6.10'),
              dependency('org.jetbrains.kotlin/kotlin-daemon-embeddable', '2.2.10'),
              dependency('org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm', '1.8.0'),
              dependency('org.jetbrains/annotations', '13.0')]
    annotations = dependency('androidx.annotation/annotation-jvm', '1.9.1')
    with tempfile.TemporaryDirectory() as staging:
        build = Path(staging)
        classes = build / 'classes'
        classes.mkdir()
        jars = []
        for module in ('aidl', 'api', 'provider', 'shared'):
            aar = dependency(f'dev.rikka.shizuku/{module}', '13.1.5', 'aar')
            jar = build / f'{module}.jar'
            with ZipFile(aar) as archive:
                jar.write_bytes(archive.read('classes.jar'))
            jars.append(jar)
        classpath = os.pathsep.join(map(str, [platform, annotations, stdlib, *jars, classes]))
        run(tools / f'aidl{suffix}', f'-p{platform.parent / "framework.aidl"}',
            f'-I{aux / "aidl"}', f'-o{build}', aux / 'aidl' / service / 'ICv10PocketXrDisplay.aidl')
        run(jdk / f'javac{suffix}', '--release', '17', '-cp', classpath, '-d', classes,
            build / service / 'ICv10PocketXrDisplay.java',
            aux / 'java' / service / 'Cv10PocketXrDisplayService.java')
        java = jdk / f'java{suffix}'
        run(java, '-cp', os.pathsep.join(map(str, kotlin)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
            '-no-stdlib', '-no-reflect', '-classpath', classpath, '-d', classes,
            transport, source / 'TransportProbeActivity.kt')
        run(java, '-cp', tools / 'lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '26', '--lib', platform,
            '--classpath', annotations, '--output', build, stdlib, *jars, *classes.rglob('*.class'))
        unsigned = build / 'unsigned.apk'
        run(tools / f'aapt2{suffix}', 'link', '-I', platform, '--manifest', source / 'AndroidManifest.xml',
            '-o', unsigned)
        with ZipFile(unsigned, 'a') as archive:
            archive.write(build / 'classes.dex', 'classes.dex')
        aligned = build / 'aligned.apk'
        run(tools / f'zipalign{suffix}', '-f', '4', unsigned, aligned)
        apksigner = tools / 'lib/apksigner.jar'
        run(java, '-jar', apksigner, 'sign', '--ks', Path.home() / '.android/debug.keystore',
            '--ks-pass', 'pass:android', '--out', args.output, aligned)
        run(java, '-jar', apksigner, 'verify', args.output)


if __name__ == '__main__':
    main()
