#!/usr/bin/env python3
"""Apply idempotent legacy Android launcher masks to the existing Nazelha artwork.

The canonical adaptive foreground remains unchanged. This only adjusts alpha at
legacy icon edges; it does not add text or recreate the generated brand mark.
Requires Pillow. Run from any working directory.
"""
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"


def mask_icon(path: Path, circular: bool) -> None:
    image = Image.open(path).convert("RGBA")
    width, height = image.size
    if width != height:
        raise ValueError(f"Launcher icon must be square: {path}")
    scale = 4
    mask = Image.new("L", (width * scale, height * scale), 0)
    draw = ImageDraw.Draw(mask)
    inset = max(1, round(width * 0.025)) * scale
    box = (inset, inset, width * scale - inset - 1, height * scale - inset - 1)
    if circular:
        draw.ellipse(box, fill=255)
    else:
        draw.rounded_rectangle(box, radius=round(width * scale * 0.22), fill=255)
    mask = mask.resize(image.size, Image.Resampling.LANCZOS)
    image.putalpha(ImageChops.darker(image.getchannel("A"), mask))
    image.save(path, optimize=True)
    print(f"{path.relative_to(ROOT)}: {width}x{height}, circular={circular}")


if __name__ == "__main__":
    for folder in sorted(RES.glob("mipmap-*dpi")):
        for name, circular in (("ic_launcher.png", False), ("ic_launcher_round.png", True)):
            path = folder / name
            if path.is_file():
                mask_icon(path, circular)
