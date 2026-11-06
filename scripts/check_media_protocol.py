"""Run synthetic media protocol checks using a JDK, without Android or remote services."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
java_home = Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else None
javac = str(java_home / 'bin' / 'javac') if java_home else shutil.which('javac')
java = str(java_home / 'bin' / 'java') if java_home else shutil.which('java')
assert javac and java, 'A JDK with javac and java is required'
source = root / 'app/src/main/java/com/hgtv/clean'
with tempfile.TemporaryDirectory(prefix='hgtv-media-check-') as workspace:
    work = Path(workspace)
    stub = work / 'org/json/JSONObject.java'
    stub.parent.mkdir(parents=True)
    # Only constructs typed model fixtures; Android's real JSON parsing is exercised by app validation.
    stub.write_text('''package org.json;
import java.util.*;
public class JSONObject {
    public static final Object NULL = new Object();
    private final Map<String,Object> values = new LinkedHashMap<>();
    public JSONObject put(String key, Object value) { values.put(key,value); return this; }
    public Object opt(String key) { return values.get(key); }
    public JSONObject optJSONObject(String key) { Object value=opt(key); return value instanceof JSONObject ? (JSONObject)value : null; }
    public Iterator<String> keys() { return values.keySet().iterator(); }
}''', encoding='utf-8')
    subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(work), str(stub),
                    str(source / 'PreparedMedia.java'), str(source / 'MediaAddress.java'),
                    str(root / 'tests/MediaProtocolCheck.java'), str(root / 'tests/MediaNetworkCheck.java')], check=True)
    subprocess.run([java, '-ea', '-cp', str(work), 'com.hgtv.clean.MediaProtocolCheck'], check=True)
    subprocess.run([java, '-ea', '-cp', str(work), 'com.hgtv.clean.MediaNetworkCheck'], check=True)
