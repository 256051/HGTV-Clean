"""Run built Android tests on one explicitly selected device; offline is the default."""
import argparse
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--live', action='store_true', help='Read live catalogue, account, media and QR endpoints; never confirm QR or re-register')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
apk = root / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
assert apk.is_file(), 'Build :app:assembleDebugAndroidTest first'
adb = [args.adb, '-s', args.serial]
subprocess.run([*adb, 'install', '-r', '-t', str(apk)], check=True)
result = subprocess.run([*adb, 'shell', 'am', 'instrument', '-w', '-e', 'offline', 'false' if args.live else 'true',
                         'com.hgtv.clean.test/com.hgtv.clean.ProtocolChecks'], check=True, text=True, encoding='utf-8', capture_output=True)
print(result.stdout)
import json
line = next(line for line in result.stdout.splitlines() if line.startswith('INSTRUMENTATION_RESULT: results='))
checks = json.loads(line.split('=', 1)[1])
assert checks and all(value == 'passed' for value in checks.values()), checks
