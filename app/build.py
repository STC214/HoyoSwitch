import argparse
import os
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZipFile

HERE = Path(__file__).resolve().parent
SDK = Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or r'F:\Android\Sdk')
TOOLS = SDK / 'build-tools' / '36.0.0'
ANDROID = SDK / 'platforms' / 'android-36' / 'android.jar'
OUTPUT = HERE / 'build'
DIST = HERE.parent / 'dist'
SRC = HERE / 'src'
RELEASE = HERE / 'keystore'
RELEASE_KEY = RELEASE / 'hoyoswitch-release.p12'
RELEASE_PROPS = RELEASE / 'keystore.txt'
PASSWORD_ENV = 'HOYOSWITCH_RELEASE_STOREPASS'
ANDROID_NS = 'http://schemas.android.com/apk/res/android'
# The published signing identity. A freshly generated keystore (e.g. a clean clone without the
# ignored app/keystore folder) must never silently replace the real release with a new identity.
EXPECTED_CERT_SHA256 = '81a5fc64529c23f1eef5a9de9cf32d6f6d71684aaf655c33f0adae36f59f9079'
DONATION_IMAGES = {
    'donation_wechat.png': 'mm_reward_qrcode_1794446017527.png',
    'donation_alipay.jpg': '1794446392439.jpg',
}


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def run_with_password(step, password, *args):
    env = dict(os.environ, **{PASSWORD_ENV: password})
    try:
        result = subprocess.run([str(arg) for arg in args], env=env,
                                capture_output=True, text=True, errors='replace')
    except OSError as error:
        message = str(error).replace(password, '[redacted]')
        raise SystemExit(f'{step}: {message}') from None
    if result.returncode != 0:
        diagnostics = '\n'.join(part.strip() for part in (result.stderr, result.stdout) if part.strip())
        diagnostics = diagnostics.replace(password, '[redacted]')
        suffix = f'\n{diagnostics}' if diagnostics else ''
        raise SystemExit(f'{step} failed (exit {result.returncode}){suffix}')


def publish_apk(signer, candidate, destination, *, release):
    if release:
        verify_release_certificate(signer, candidate)
    else:
        run(*signer, 'verify', '--verbose', candidate)
    destination.parent.mkdir(parents=True, exist_ok=True)
    candidate.replace(destination)


def verify_release_certificate(signer, candidate):
    """Refuse to publish an APK that is not signed by the pinned release identity."""
    result = subprocess.run(
        [str(arg) for arg in (*signer, 'verify', '--verbose', '--print-certs', candidate)],
        capture_output=True, text=True, errors='replace')
    if result.returncode != 0:
        diagnostics = '\n'.join(
            part.strip() for part in (result.stdout, result.stderr) if part.strip())
        raise SystemExit(f'APK signature verification failed\n{diagnostics}')
    digests = [value.lower() for value in re.findall(
        r'^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$',
        result.stdout, re.MULTILINE)]
    if digests != [EXPECTED_CERT_SHA256]:
        raise SystemExit(
            'Release APK was signed by an unexpected key '
            f'(SHA-256: {", ".join(digests) or "unknown"}); refusing to publish.\n'
            'If this is intentional, update EXPECTED_CERT_SHA256 in app/build.py.')
    print(f'Release certificate verified: {EXPECTED_CERT_SHA256}', flush=True)


def copy_donation_images(drawable):
    for output, source in DONATION_IMAGES.items():
        shutil.copyfile(HERE.parent / 'QRcode' / source, drawable / output)


def release_credentials():
    """Returns the existing release credentials without creating or replacing them."""
    if not RELEASE_KEY.is_file() or not RELEASE_PROPS.is_file():
        raise SystemExit('Release signing files are missing; restore the original keystore and '
                         'its password file before building a release.')
    values = {}
    for line in RELEASE_PROPS.read_text(encoding='utf-8').splitlines():
        if '=' in line:
            name, value = line.split('=', 1)
            values[name.strip()] = value.strip()
    alias = values.get('alias')
    password = values.get('storepass')
    if not alias or not password:
        raise SystemExit('Release signing credentials must include a non-empty alias and storepass.')
    return RELEASE_KEY, alias, password


def write_release_manifest(source, destination):
    tree = ET.parse(source)
    root = tree.getroot()
    apps = root.findall('application')
    if root.tag != 'manifest' or len(apps) != 1:
        raise SystemExit('Expected exactly one application in the Android manifest.')
    ET.register_namespace('android', ANDROID_NS)
    apps[0].set(f'{{{ANDROID_NS}}}debuggable', 'false')
    tree.write(destination, encoding='utf-8', xml_declaration=True)


def ensure_debug_key(key, work):
    if key.is_file():
        return
    candidate = work / 'debug-key.p12'
    run('keytool', '-genkeypair', '-storetype', 'PKCS12', '-keystore', candidate, '-storepass',
        'android', '-alias', 'local', '-keyalg', 'RSA', '-keysize', '2048', '-validity',
        '3650', '-dname', 'CN=Local Prototype')
    try:
        # Install a complete key without replacing a key published by another build.
        if os.name == 'nt':
            candidate.rename(key)
        else:
            os.link(candidate, key)
    except FileExistsError:
        if not key.is_file():
            raise


def main(argv=None):
    parser = argparse.ArgumentParser(description='Build the Android APK.', allow_abbrev=False)
    parser.add_argument('--release', action='store_true', help='Use the original release signing key.')
    release = parser.parse_args(argv).release
    credentials = release_credentials() if release else None
    OUTPUT.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='build-', dir=OUTPUT) as work:
        build_apk(Path(work), credentials)


def build_apk(work, credentials):
    release = credentials is not None
    res = work / 'res'
    source_res = HERE / 'res'
    if source_res.is_dir():
        shutil.copytree(source_res, res)
    drawable = res / 'drawable-nodpi'
    drawable.mkdir(parents=True, exist_ok=True)
    icon = HERE.parent / '0i.png'
    shutil.copyfile(icon, drawable / 'app_icon.png')
    copy_donation_images(drawable)
    flat = work / 'res.zip'
    unsigned = work / 'unsigned.apk'
    aligned = work / 'aligned.apk'
    apk = DIST / ('account-switch-release.apk' if release else 'account-switch.apk')
    candidate = work / 'signed.apk'
    key = OUTPUT / 'local-test.p12'
    classes = work / 'classes'
    generated = work / 'gen'

    classes.mkdir()
    generated.mkdir()

    run(TOOLS / 'aapt2.exe', 'compile', '--no-crunch', '--dir', res, '-o', flat)
    manifest = HERE / 'AndroidManifest.xml'
    if release:
        release_manifest = work / 'AndroidManifest.release.xml'
        write_release_manifest(manifest, release_manifest)
        manifest = release_manifest
    run(TOOLS / 'aapt2.exe', 'link', '-I', ANDROID, '--manifest', manifest,
        '--java', generated, '-o', unsigned, flat)

    sources = sorted(str(path) for path in SRC.rglob('*.java'))
    sources += sorted(str(path) for path in generated.rglob('*.java'))
    run('javac', '--release', '8', '-encoding', 'UTF-8', '-Xlint:-options', '-classpath', ANDROID,
        '-d', classes, *sources)

    compiled = sorted(str(path) for path in classes.rglob('*.class'))
    run('java', '-cp', TOOLS / 'lib' / 'd8.jar', 'com.android.tools.r8.D8', '--lib', ANDROID,
        '--min-api', '26', '--output', work, *compiled)

    with ZipFile(unsigned, 'a') as archive:
        archive.write(work / 'classes.dex', 'classes.dex')

    run(TOOLS / 'zipalign.exe', '-f', '4', unsigned, aligned)
    signer = ('java', '-jar', TOOLS / 'lib' / 'apksigner.jar')
    if release:
        keystore, alias, password = credentials
        run_with_password('Sign release APK', password, *signer, 'sign', '--ks', keystore,
                          '--ks-key-alias', alias, '--ks-pass', f'env:{PASSWORD_ENV}',
                          '--v4-signing-enabled', 'false', '--out', candidate, aligned)
        publish_apk(signer, candidate, apk, release=True)
        print(f'Release APK built: {apk}', flush=True)
        return
    ensure_debug_key(key, work)
    run(*signer, 'sign', '--ks', key, '--ks-key-alias', 'local', '--ks-pass', 'pass:android',
        '--v4-signing-enabled', 'false', '--out', candidate, aligned)
    publish_apk(signer, candidate, apk, release=False)
    print(f'APK built: {apk}', flush=True)


if __name__ == '__main__':
    main()
