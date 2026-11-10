#!/usr/bin/env python3
"""Generate original LearnIME artwork and platform icon resources (requires Pillow)."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
SCALE = 2
SIZE = 1024
image = Image.new("RGB", (SIZE * SCALE, SIZE * SCALE))
draw = ImageDraw.Draw(image)
for y in range(SIZE * SCALE):
    t = y / (SIZE * SCALE - 1)
    color = tuple(round(a * (1 - t) + b * t) for a, b in zip((31, 131, 234), (25, 71, 172)))
    draw.line((0, y, SIZE * SCALE, y), fill=color)

def box(rect, color, radius=0):
    coords = tuple(round(v * SCALE) for v in rect)
    draw.rounded_rectangle(coords, radius=radius * SCALE, fill=color)

def line(points, color, width):
    draw.line([(x * SCALE, y * SCALE) for x, y in points], fill=color, width=width * SCALE, joint="curve")

# Two keycaps joined by a translation arrow. Both glyphs are hand-drawn geometry.
box((192, 270, 548, 661), (13, 48, 130), 64)
box((476, 378, 832, 769), (13, 48, 130), 64)
box((192, 248, 548, 639), (255, 255, 255), 64)
box((476, 356, 832, 747), (220, 246, 252), 64)
# 中
box((275, 353, 467, 482), (24, 83, 183), 10)
box((301, 379, 441, 456), (255, 255, 255), 1)
box((355, 316, 384, 571), (24, 83, 183), 4)
# A
line([(562, 650), (650, 445), (674, 445), (763, 650)], (16, 101, 139), 29)
line([(595, 582), (730, 582)], (16, 101, 139), 25)
# Arrow
line([(216, 744), (363, 744), (408, 704)], (207, 240, 255), 20)
line([(358, 704), (408, 704), (408, 754)], (207, 240, 255), 20)
image = image.resize((SIZE, SIZE), Image.Resampling.LANCZOS)

catalog = ROOT / "iosApp/App/Assets.xcassets"
icon = catalog / "AppIcon.appiconset"
icon.mkdir(parents=True, exist_ok=True)
(catalog / "Contents.json").write_text(json.dumps({"info": {"author": "xcode", "version": 1}}, indent=2) + "\n")
image.save(icon / "AppIcon.png")
(icon / "Contents.json").write_text(json.dumps({
    "images": [{"filename": "AppIcon.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}],
    "info": {"author": "xcode", "version": 1}
}, indent=2) + "\n")

res = ROOT / "androidApp/src/main/res"
for density, size in {"mdpi":48, "hdpi":72, "xhdpi":96, "xxhdpi":144, "xxxhdpi":192}.items():
    folder = res / f"mipmap-{density}"
    folder.mkdir(parents=True, exist_ok=True)
    artwork = image.resize((size, size), Image.Resampling.LANCZOS).convert("RGBA")
    artwork.save(folder / "ic_launcher.png")
    mask = Image.new("L", (size, size))
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    artwork.putalpha(mask); artwork.save(folder / "ic_launcher_round.png")
adaptive = res / "mipmap-anydpi-v26"
adaptive.mkdir(parents=True, exist_ok=True)
for name in ("ic_launcher", "ic_launcher_round"):
    (adaptive / f"{name}.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
</adaptive-icon>
''')
print(f"Generated iOS and Android app icons in {catalog} and {res}")
