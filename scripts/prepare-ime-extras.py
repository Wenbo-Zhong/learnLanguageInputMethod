#!/usr/bin/env python3
"""Fetch pinned, licensed spelling/conversion/handwriting resources."""
import hashlib, io, json, pathlib, shutil, tarfile, urllib.request, zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
DATA = ROOT / "input-data" / "extras"
VENDOR = ROOT / "third_party"
DATA.mkdir(parents=True, exist_ok=True)
sources = [
    ("symspellpy-6.10.1", "https://files.pythonhosted.org/packages/c2/fa/7cf9f79a50d49c60d64be552a06291e6a8adcff733518575017622fbc80f/symspellpy-6.10.1-py3-none-any.whl", "a653a6c125839a21ada87c3e044d690fc3b39872efbd7b7270db3de9471315e1"),
    ("opencc-0.1.7", "https://files.pythonhosted.org/packages/30/6b/055b7806f320cc8f2cdf23c5f70221c0dc1683fca9ffaf76dfc2ad4b91b6/opencc_python_reimplemented-0.1.7-py2.py3-none-any.whl", "41b3b92943c7bed291f448e9c7fad4b577c8c2eae30fcfe5a74edf8818493aa6"),
    ("zinnia-0.06", "https://deb.debian.org/debian/pool/main/z/zinnia/zinnia_0.06.orig.tar.gz", "ece3af93f937282971634fd81d3e997f848e8cfa958220e26a4564ca064ac20b"),
    ("tegaki-zh-0.3", "https://deb.debian.org/debian/pool/main/t/tegaki-zinnia-simplified-chinese/tegaki-zinnia-simplified-chinese_0.3.orig.tar.gz", "63f29e7d7c7a71e94ab003353924304ab2a870a8e220a77800cf984c0620d41f"),
]
records = []
for name, url, sha in sources:
    archive = pathlib.Path("/tmp") / (name + ".archive")
    if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != sha:
        with urllib.request.urlopen(url, timeout=90) as source, archive.open("wb") as out:
            shutil.copyfileobj(source, out)
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == sha, name
    raw = archive.read_bytes()
    if name.startswith(("symspell", "opencc")):
        with zipfile.ZipFile(io.BytesIO(raw)) as z:
            for member in z.namelist():
                if name.startswith("symspell") and member.endswith("frequency_dictionary_en_82_765.txt"):
                    (DATA / "english-frequency.txt").write_bytes(z.read(member))
                if name.startswith("opencc") and member.endswith(("STCharacters.txt", "STPhrases.txt")):
                    (DATA / pathlib.Path(member).name).write_bytes(z.read(member))
                if "license" in member.lower() and not member.endswith("/"):
                    (DATA / (name + "-LICENSE.txt")).write_bytes(z.read(member))
    else:
        target = VENDOR / "zinnia" if name.startswith("zinnia") else pathlib.Path("/tmp/learnime-tegaki")
        target.mkdir(parents=True, exist_ok=True)
        with tarfile.open(fileobj=io.BytesIO(raw), mode="r:gz") as t:
            for member in t.getmembers():
                if not member.isfile():
                    continue
                if name.startswith("zinnia"):
                    rel = pathlib.PurePosixPath(member.name).parts[1:]
                    if not rel or ".." in rel: continue
                    dest = target.joinpath(*rel)
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_bytes(t.extractfile(member).read())
                elif member.name.endswith((".model", ".meta", "COPYING", "AUTHORS", "README")):
                    dest = DATA / ("tegaki-" + pathlib.Path(member.name).name if not member.name.endswith(".model") else "handwriting-zh_CN.model")
                    dest.write_bytes(t.extractfile(member).read())
    records.append(dict(name=name, url=url, sha256=sha))
(DATA / "SOURCES.json").write_text(json.dumps(records, indent=2) + "\n")
for p in sorted(DATA.iterdir()):
    print(p.name, p.stat().st_size, hashlib.sha256(p.read_bytes()).hexdigest())
