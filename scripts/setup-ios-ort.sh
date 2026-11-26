#!/usr/bin/env bash
set -euo pipefail
root="$(dirname "$(dirname "$(realpath "$0")")")"
target="$root/build/ios-ort"
checksum="756a78e0168f29840bc614b43aeb03e63673f44022e0221d21698a2c8ed747ef"
if [[ ! -f "$target/onnxruntime.xcframework/Info.plist" ]]; then
  download_dir="$(mktemp -d "${TMPDIR:-/tmp}/learnime-ios-ort.XXXXXX")"
  trap 'rm -rf "$download_dir"' EXIT
  archive="$download_dir/onnxruntime-ios-1.23.0.zip"
  if curl -fL --connect-timeout 20 --max-time 180 --retry 2 \
      https://download.onnxruntime.ai/pod-archive-onnxruntime-c-1.23.0.zip -o "$archive"; then
    echo "$checksum  $archive" | shasum -a 256 -c -
    mkdir -p "$target"
    unzip -q "$archive" -d "$target"
  else
    # Microsoft's same-version NuGet package also contains the Apple XCFramework.
    package="$download_dir/onnxruntime-1.23.0.nupkg"
    curl -fL --connect-timeout 20 --max-time 180 --retry 2 \
      https://api.nuget.org/v3-flatcontainer/microsoft.ml.onnxruntime/1.23.0/microsoft.ml.onnxruntime.1.23.0.nupkg \
      -o "$package"
    echo "cadbf21358de4b6002eddce08329465071271d52bb1483779f6f09670af281fc  $package" | shasum -a 256 -c -
    python3 - "$package" "$target" "$archive" <<'PYCODE'
from pathlib import Path
import sys
import zipfile

package, target, archive = map(Path, sys.argv[1:])
with zipfile.ZipFile(package) as source:
    archive.write_bytes(source.read("runtimes/ios/native/onnxruntime.xcframework.zip"))
    headers = target / "Headers"
    headers.mkdir(parents=True, exist_ok=True)
    for name in source.namelist():
        if name.startswith("build/native/include/") and name.endswith(".h"):
            (headers / Path(name).name).write_bytes(source.read(name))
    if "LICENSE" in source.namelist():
        (target / "LICENSE").write_bytes(source.read("LICENSE"))
PYCODE
    unzip -q "$archive" -d "$target"
  fi
fi
