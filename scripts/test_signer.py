"""Compile and run protocol checks in a temporary JVM workspace without real credentials."""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
java_home = Path(os.environ['JAVA_HOME'])
properties = dict(line.split('=', 1) for line in (root / 'local.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
sdk = Path(properties['sdk.dir'].replace('\\:', ':').replace('\\\\', '\\'))
android = sdk / 'platforms/android-36/android.jar'
source = root / 'app/src/main/java/com/hgtv/clean'
with tempfile.TemporaryDirectory(prefix='hgtv-protocol-check-') as directory:
    work = Path(directory)
    shim = work / 'android/util/Base64.java'
    shim.parent.mkdir(parents=True)
    shim.write_text('''package android.util;
public final class Base64 {
 public static final int NO_WRAP=2, DEFAULT=0;
 public static String encodeToString(byte[] bytes,int flags){return java.util.Base64.getEncoder().encodeToString(bytes);}
 public static byte[] decode(String value,int flags){return java.util.Base64.getDecoder().decode(value);}
}''')
    files = [source / (name + '.java') for name in ['RequestSigner', 'SignatureCipher', 'ProtocolTables', 'ProtocolHttp', 'DeviceRegistration']]
    subprocess.run([str(java_home / 'bin/javac.exe'), '-encoding', 'UTF-8', '-cp', str(android), '-d', str(work), str(shim), *map(str,files), str(root / 'scripts/tests/ProtocolSignerChecks.java')], check=True)
    fixture = root / 'scripts/tests/signer-reference.txt'
    arguments = [str(fixture), str(work / 'synthetic-output.txt'), str(root / 'scripts/tests/signer-reference-vectors.txt')] if fixture.exists() else []
    subprocess.run([str(java_home / 'bin/java.exe'), '-cp', str(work) + os.pathsep + str(android), 'com.hgtv.clean.ProtocolSignerChecks', *arguments], check=True)
