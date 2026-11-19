import os
from pathlib import Path
import shutil
import subprocess
from zipfile import ZipFile

HERE = Path(__file__).resolve().parent
SDK = Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or r'F:\Android\Sdk')
TOOLS = SDK / 'build-tools' / '36.0.0'
ANDROID = SDK / 'platforms' / 'android-36' / 'android.jar'
OUTPUT = HERE / 'build'


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def main():
    OUTPUT.mkdir(exist_ok=True)
    res = OUTPUT / 'res' / 'drawable-nodpi'
    res.mkdir(parents=True, exist_ok=True)
    icon = HERE.parent / '0i.png'
    shutil.copyfile(icon, res / 'app_icon.png')
    flat, unsigned, aligned, apk, key = (
        OUTPUT / name for name in ('res.zip', 'unsigned.apk', 'aligned.apk', 'switch-fixture.apk', 'local-test.p12')
    )
    run(TOOLS / 'aapt2.exe', 'compile', '--no-crunch', '--dir', res.parent, '-o', flat)
    run(TOOLS / 'aapt2.exe', 'link', '-I', ANDROID, '--manifest', HERE / 'AndroidManifest.xml', '-o', unsigned, flat)
    run('javac', '--release', '8', '-encoding', 'UTF-8', '-Xlint:-options', '-classpath', ANDROID,
        '-d', OUTPUT, HERE / 'MainActivity.java', HERE / 'SwitchActivity.java')
    run('java', '-cp', TOOLS / 'lib' / 'd8.jar', 'com.android.tools.r8.D8', '--lib', ANDROID,
        '--min-api', '23', '--output', OUTPUT, OUTPUT / 'dev/hoyoswitch/fixture/MainActivity.class',
        OUTPUT / 'dev/hoyoswitch/fixture/SwitchActivity.class')
    with ZipFile(unsigned, 'a') as archive:
        archive.write(OUTPUT / 'classes.dex', 'classes.dex')
    run(TOOLS / 'zipalign.exe', '-f', '4', unsigned, aligned)
    if not key.exists():
        run('keytool', '-genkeypair', '-storetype', 'PKCS12', '-keystore', key, '-storepass', 'android',
            '-alias', 'fixture', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650', '-dname', 'CN=Local Fixture')
    signer = ('java', '-jar', TOOLS / 'lib' / 'apksigner.jar')
    run(*signer, 'sign', '--ks', key, '--ks-pass', 'pass:android', '--out', apk, aligned)
    run(*signer, 'verify', '--verbose', apk)
    with ZipFile(apk) as archive:
        assets = [name for name in archive.namelist() if name.endswith('/app_icon.png')]
        if len(assets) != 1 or archive.read(assets[0]) != icon.read_bytes():
            raise RuntimeError('APK icon differs from the provided source')
    print(f'APK and original icon verified: {apk}', flush=True)


if __name__ == '__main__':
    main()
