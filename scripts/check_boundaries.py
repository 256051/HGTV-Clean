"""Verify source-only protocol, account isolation and packaged-code boundaries offline."""
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile

root = Path(__file__).resolve().parents[1]
java = root / 'app/src/main/java/com/hgtv/clean'
files = list(java.glob('*.java'))
source = '\n'.join(file.read_text(encoding='utf-8') for file in files)
manifest = (root / 'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
for forbidden in ('DexClassLoader', 'InMemoryDexClassLoader', 'Class.forName', 'java.lang.reflect',
                  '/sdcard/.hgtv', 'SessionBootstrap', 'com.hgtv.star', 'platform-126'):
    assert forbidden not in source, forbidden
for forbidden in ('AD_ID', 'READ_EXTERNAL_STORAGE', 'WRITE_EXTERNAL_STORAGE', 'HgtvApplication'):
    assert forbidden not in manifest, forbidden
for forbidden in ('send_code', 'sms_login', 'read_progress/upload', 'subscribe/add', 'subscribe/delete', 'user/logout'):
    assert forbidden not in source, forbidden
assert 'android:allowBackup="false"' in manifest
api = (java / 'PlatformApi.java').read_text(encoding='utf-8')
assert 'clean-platform-v1' in api and 'qr_account' in api and 'registered_device' in api
assert 'synchronized (active)' in api and 'validateAccount(record)' in api
assert 'if (!active.get() || closed.get() || Thread.currentThread().isInterrupted())' in api
player = (java / 'PlayerScreen.java').read_text(encoding='utf-8')
assert 'generation != request' in player and 'postDelayed(timeout, 20000)' in player
assert 'new ForwardingPlayer(player)' in player and 'onInterceptTouchEvent(MotionEvent event)' in player
assert 'setControllerShowTimeoutMs(4000)' in player and 'header.setVisibility(visibility)' in player
assert not list((root / 'app/src/main').rglob('*.dex'))
assert not (java / 'Compatibility.java').exists()
assert 'prepare_compat' not in (root / 'build.ps1').read_text(encoding='utf-8')


def classes(data):
    assert data.startswith(b'dex\n'), 'DEX format'
    def u32(offset):
        return struct.unpack_from('<I', data, offset)[0]
    strings = []
    for index in range(u32(56)):
        offset = u32(u32(60) + index * 4)
        while data[offset] & 128:
            offset += 1
        offset += 1
        end = data.index(0, offset)
        strings.append(data[offset:end].decode('utf-8', errors='replace'))
    types = [strings[u32(u32(68) + index * 4)] for index in range(u32(64))]
    return [types[u32(u32(100) + index * 32)] for index in range(u32(96))]


apk = root / 'app/build/outputs/apk/debug/app-debug.apk'
packaged = []
if apk.exists():
    with zipfile.ZipFile(apk) as bundle:
        for member in bundle.namelist():
            assert 'platform-126' not in member
            assert not (member.startswith('assets/') and member.endswith(('.dex', '.jar', '.apk')))
            data = bundle.read(member)
            assert hashlib.sha256(data).hexdigest() != 'e63acd37a6a98b9c9c8618f7b285c4f1c09b273ab1d2c2cfa3d18bac9f507271'
            if re.fullmatch(r'classes[0-9]*\.dex', member):
                packaged.extend(classes(data))
        assert packaged, 'compiled classes'
        assert not any(name.startswith(('LE0/', 'LF0/', 'LG0/', 'LI0/', 'Lcom/hgtv/star/')) for name in packaged)
        for name in ('PlatformApi', 'AuthApi', 'PreparedMedia', 'RequestSigner', 'DeviceRegistration'):
            assert 'Lcom/hgtv/clean/' + name + ';' in packaged, name
        assert not any('ProtocolChecks;' in name for name in packaged), 'test runner leaked into client'
print(json.dumps({'boundaries': 'passed', 'protocol': 'source-built', 'legacy_engine_present': False,
                  'apk_checked': apk.exists(), 'own_classes': sum(name.startswith('Lcom/hgtv/clean/') for name in packaged),
                  'check_is_offline': True}))
