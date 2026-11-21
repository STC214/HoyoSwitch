import os
import secrets
import shutil
import subprocess
import sys
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


def cleanup(*paths):
    for path in paths:
        try:
            path.unlink()
        except FileNotFoundError:
            pass


def publish_apk(signer, candidate, destination, *, release):
    flags = ('--print-certs',) if release else ()
    run(*signer, 'verify', '--verbose', *flags, candidate)
    destination.parent.mkdir(parents=True, exist_ok=True)
    candidate.replace(destination)


def release_credentials():
    """Returns (keystore, alias, password), creating a release key on first use."""
    RELEASE.mkdir(parents=True, exist_ok=True)
    if not RELEASE_KEY.exists():
        password = secrets.token_urlsafe(18)
        run_with_password('Generate release key', password, 'keytool', '-genkeypair',
                          '-storetype', 'PKCS12', '-keystore', RELEASE_KEY,
                          '-storepass:env', PASSWORD_ENV, '-alias', 'hoyoswitch', '-keyalg',
                          'RSA', '-keysize', '4096', '-validity', '10000', '-dname',
                          'CN=HoyoSwitch, O=STC, C=CN')
        RELEASE_PROPS.write_text(
            f'keystore={RELEASE_KEY}\nalias=hoyoswitch\nstorepass={password}\n',
            encoding='utf-8')
        print(f'Release keystore created: {RELEASE_KEY}', flush=True)
        print('BACK THIS FILE UP: losing it means no future updates.', flush=True)
    values = {}
    if not RELEASE_PROPS.exists():
        raise SystemExit(
            f'Release keystore exists but {RELEASE_PROPS} is missing; restore the password '
            'file (or delete the keystore to generate a new signing identity).')
    for line in RELEASE_PROPS.read_text(encoding='utf-8').splitlines():
        if '=' in line:
            name, value = line.split('=', 1)
            values[name.strip()] = value.strip()
    return RELEASE_KEY, values['alias'], values['storepass']


def main():
    release = '--release' in sys.argv
    OUTPUT.mkdir(exist_ok=True)
    res = OUTPUT / 'res'
    source_res = HERE / 'res'
    if source_res.is_dir():
        if res.exists():
            shutil.rmtree(res)
        shutil.copytree(source_res, res)
    drawable = res / 'drawable-nodpi'
    drawable.mkdir(parents=True, exist_ok=True)
    icon = HERE.parent / '0i.png'
    shutil.copyfile(icon, drawable / 'app_icon.png')
    flat = OUTPUT / 'res.zip'
    unsigned = OUTPUT / 'unsigned.apk'
    aligned = OUTPUT / 'aligned.apk'
    apk = DIST / ('account-switch-release.apk' if release else 'account-switch.apk')
    candidate = OUTPUT / ('signed-release.apk' if release else 'signed-debug.apk')
    key = OUTPUT / 'local-test.p12'
    classes = OUTPUT / 'classes'
    generated = OUTPUT / 'gen'

    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(parents=True)
    if generated.exists():
        shutil.rmtree(generated)
    generated.mkdir(parents=True)

    run(TOOLS / 'aapt2.exe', 'compile', '--no-crunch', '--dir', res, '-o', flat)
    manifest = HERE / 'AndroidManifest.xml'
    if release:
        # The source manifest stays debuggable for local testing; release builds must not be.
        text = manifest.read_text(encoding='utf-8')
        if 'android:debuggable="true"' not in text:
            raise SystemExit('expected android:debuggable="true" in AndroidManifest.xml')
        manifest = OUTPUT / 'AndroidManifest.release.xml'
        manifest.write_text(text.replace('android:debuggable="true"',
                                         'android:debuggable="false"'), encoding='utf-8')
    run(TOOLS / 'aapt2.exe', 'link', '-I', ANDROID, '--manifest', manifest,
        '--java', generated, '-o', unsigned, flat)

    sources = sorted(str(path) for path in SRC.rglob('*.java'))
    sources += sorted(str(path) for path in generated.rglob('*.java'))
    run('javac', '--release', '8', '-encoding', 'UTF-8', '-Xlint:-options', '-classpath', ANDROID,
        '-d', classes, *sources)

    compiled = sorted(str(path) for path in classes.rglob('*.class'))
    run('java', '-cp', TOOLS / 'lib' / 'd8.jar', 'com.android.tools.r8.D8', '--lib', ANDROID,
        '--min-api', '26', '--output', OUTPUT, *compiled)

    with ZipFile(unsigned, 'a') as archive:
        archive.write(OUTPUT / 'classes.dex', 'classes.dex')

    run(TOOLS / 'zipalign.exe', '-f', '4', unsigned, aligned)
    signer = ('java', '-jar', TOOLS / 'lib' / 'apksigner.jar')
    if release:
        keystore, alias, password = release_credentials()
        run_with_password('Sign release APK', password, *signer, 'sign', '--ks', keystore,
                          '--ks-key-alias', alias, '--ks-pass', f'env:{PASSWORD_ENV}',
                          '--v4-signing-enabled', 'false', '--out', candidate, aligned)
        publish_apk(signer, candidate, apk, release=True)
        cleanup(flat, unsigned, aligned)
        print(f'Release APK built: {apk}', flush=True)
        return
    if not key.exists():
        run('keytool', '-genkeypair', '-storetype', 'PKCS12', '-keystore', key, '-storepass',
            'android', '-alias', 'local', '-keyalg', 'RSA', '-keysize', '2048', '-validity',
            '3650', '-dname', 'CN=Local Prototype')
    run(*signer, 'sign', '--ks', key, '--ks-key-alias', 'local', '--ks-pass', 'pass:android',
        '--v4-signing-enabled', 'false', '--out', candidate, aligned)
    publish_apk(signer, candidate, apk, release=False)
    cleanup(flat, unsigned, aligned)
    print(f'APK built: {apk}', flush=True)


if __name__ == '__main__':
    main()
