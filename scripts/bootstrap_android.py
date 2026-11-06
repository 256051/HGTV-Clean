"""Install the two required SDK packages and a pinned Gradle in a local tools folder."""
import hashlib
import json
import os
from pathlib import Path
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

TOOLS = Path(os.environ['LOCALAPPDATA']) / 'Programs/REA-Android/build-tools'
SDK = TOOLS / 'sdk'
TOOLS.mkdir(parents=True, exist_ok=True)
opener = urllib.request.build_opener()


def download(url, destination, digest=None, algorithm='sha256'):
    if destination.exists() and (not digest or hashlib.new(algorithm, destination.read_bytes()).hexdigest() == digest):
        return
    temporary = destination.with_suffix('.part')
    with opener.open(url, timeout=120) as response, temporary.open('wb') as stream:
        while block := response.read(1024 * 1024):
            stream.write(block)
    if digest:
        assert hashlib.new(algorithm, temporary.read_bytes()).hexdigest() == digest, 'Download checksum mismatch'
    temporary.replace(destination)


def unpack(archive, destination):
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as bundle:
        for member in bundle.infolist():
            assert (destination / member.filename).resolve().is_relative_to(destination.resolve()), 'Unsafe archive path'
        bundle.extractall(destination)


with opener.open('https://dl.google.com/android/repository/repository2-3.xml', timeout=60) as response:
    document = ET.fromstring(response.read())
for path, output in [('platforms;android-36', SDK / 'platforms/android-36'), ('build-tools;35.0.0', SDK / 'build-tools/35.0.0')]:
    if (output / 'source.properties').exists():
        continue
    package = next(p for p in document.findall('remotePackage') if p.attrib['path'] == path)
    archives = package.find('archives').findall('archive')
    archive = next(a for a in archives if a.findtext('host-os') in ('windows', None))
    complete = archive.find('complete')
    name = complete.findtext('url')
    checksum = complete.find('checksum')
    destination = TOOLS / Path(name).name
    print('Downloading', name, flush=True)
    download('https://dl.google.com/android/repository/' + name, destination, checksum.text, checksum.attrib.get('type', 'sha1'))
    staging = TOOLS / ('unpack-' + output.name)
    unpack(destination, staging)
    roots = [p.parent for p in staging.rglob('source.properties')]
    assert len(roots) == 1
    output.parent.mkdir(parents=True, exist_ok=True)
    roots[0].rename(output)
    print('Installed', path, flush=True)

gradle_name = 'gradle-8.14.3-bin.zip'
gradle_zip = TOOLS / gradle_name
with opener.open('https://services.gradle.org/distributions/' + gradle_name + '.sha256', timeout=60) as response:
    gradle_digest = response.read().decode().strip().split()[0]
if not (TOOLS / 'gradle-8.14.3/bin/gradle.bat').exists():
    print('Downloading Gradle 8.14.3', flush=True)
    download('https://services.gradle.org/distributions/' + gradle_name, gradle_zip, gradle_digest)
    unpack(gradle_zip, TOOLS)
result = {'sdk': str(SDK), 'gradle': str(TOOLS / 'gradle-8.14.3/bin/gradle.bat')}
Path(__file__).resolve().parents[1].joinpath('local.properties').write_text('sdk.dir=' + str(SDK).replace('\\', '/').replace(':', '\\:') + '\n', encoding='utf-8')
print(json.dumps(result), flush=True)
