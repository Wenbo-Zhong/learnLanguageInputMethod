#!/usr/bin/env python3
"""Download the official Apache-2.0 Chinese mobile speech model."""
import hashlib, json, pathlib, shutil, urllib.request, zipfile
root = pathlib.Path(__file__).resolve().parents[1]
archive = pathlib.Path("/tmp/learnime-vosk-cn-0.22.zip")
url = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
if not archive.is_file() or not zipfile.is_zipfile(archive):
    offset = archive.stat().st_size if archive.exists() else 0
    request = urllib.request.Request(url, headers={"Range": f"bytes={offset}-"} if offset else {})
    with urllib.request.urlopen(request, timeout=180) as source, archive.open("ab" if offset and source.status == 206 else "wb") as out:
        shutil.copyfileobj(source, out)
sha = hashlib.sha256(archive.read_bytes()).hexdigest()
assert sha == "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba", "Model checksum changed"
record = root / "input-data/extras/VOSK-SOURCE.json"
if record.exists():
    assert json.loads(record.read_text())["sha256"] == sha, "Model checksum changed"
with zipfile.ZipFile(archive) as z:
    target = root / "input-data/extras/vosk-cn"
    target.mkdir(parents=True, exist_ok=True)
    for info in z.infolist():
        rel = pathlib.PurePosixPath(info.filename).parts[1:]
        if info.is_dir() or not rel or ".." in rel: continue
        dest = target.joinpath(*rel); dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(z.read(info))
record.write_text(json.dumps(dict(name="vosk-model-small-cn-0.22", url=url, sha256=sha,
    license="Apache-2.0", license_source="https://alphacephei.com/vosk/models"), indent=2) + "\n")
# The official model catalog lists Apache-2.0; include the complete license.
shutil.copyfile(root / "input-data/extras/opencc-0.1.7-LICENSE.txt",
    root / "input-data/extras/vosk-Apache-2.0.txt")
print(sha, archive.stat().st_size)
