import os
import shutil
import subprocess
from pathlib import Path
from zipfile import ZipFile

HERE = Path(__file__).resolve().parent
SDK = Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or r'F:\Android\Sdk')
TOOLS = SDK / 'build-tools' / '36.0.0'
ANDROID = SDK / 'platforms' / 'android-36' / 'android.jar'
OUTPUT = HERE / 'build'
SRC = HERE / 'src'


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def main():
    OUTPUT.mkdir(exist_ok=True)
    res = OUTPUT / 'res' / 'drawable-nodpi'
    res.mkdir(parents=True, exist_ok=True)
    icon = HERE.parent / '0i.png'
    shutil.copyfile(icon, res / 'app_icon.png')
    flat = OUTPUT / 'res.zip'
    unsigned = OUTPUT / 'unsigned.apk'
    aligned = OUTPUT / 'aligned.apk'
    apk = OUTPUT / 'account-switch.apk'
    key = OUTPUT / 'local-test.p12'
    classes = OUTPUT / 'classes'

    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(parents=True)

    run(TOOLS / 'aapt2.exe', 'compile', '--no-crunch', '--dir', res.parent, '-o', flat)
    run(TOOLS / 'aapt2.exe', 'link', '-I', ANDROID, '--manifest', HERE / 'AndroidManifest.xml',
        '-o', unsigned, flat)

    sources = sorted(str(path) for path in SRC.rglob('*.java'))
    run('javac', '--release', '8', '-encoding', 'UTF-8', '-Xlint:-options', '-classpath', ANDROID,
        '-d', classes, *sources)

    compiled = sorted(str(path) for path in classes.rglob('*.class'))
    run('java', '-cp', TOOLS / 'lib' / 'd8.jar', 'com.android.tools.r8.D8', '--lib', ANDROID,
        '--min-api', '26', '--output', OUTPUT, *compiled)

    with ZipFile(unsigned, 'a') as archive:
        archive.write(OUTPUT / 'classes.dex', 'classes.dex')

    run(TOOLS / 'zipalign.exe', '-f', '4', unsigned, aligned)
    if not key.exists():
        run('keytool', '-genkeypair', '-storetype', 'PKCS12', '-keystore', key, '-storepass',
            'android', '-alias', 'local', '-keyalg', 'RSA', '-keysize', '2048', '-validity',
            '3650', '-dname', 'CN=Local Prototype')
    signer = ('java', '-jar', TOOLS / 'lib' / 'apksigner.jar')
    run(*signer, 'sign', '--ks', key, '--ks-pass', 'pass:android', '--out', apk, aligned)
    run(*signer, 'verify', '--verbose', apk)
    print(f'APK built: {apk}', flush=True)


if __name__ == '__main__':
    main()
