#!/usr/bin/env python3
"""Generate launcher icon PNGs (legacy + adaptive) from the master design image.

Outputs, per density:
  mipmap-<dpi>/ic_launcher.png          rounded-square legacy icon
  mipmap-<dpi>/ic_launcher_round.png    circular legacy icon
  mipmap-<dpi>/ic_launcher_foreground.png  adaptive foreground (white glyph, transparent bg)
  mipmap-<dpi>/ic_launcher_background.png  adaptive background (gradient, no glyph)
"""
import os
from PIL import Image, ImageDraw, ImageChops

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "design", "icon-source.png")
RES = os.path.join(HERE, "..", "app", "src", "main", "res")

# (density tag, legacy px for 48dp, adaptive px for 108dp)
DENSITIES = [
    ("mdpi", 48, 108),
    ("hdpi", 72, 162),
    ("xhdpi", 96, 216),
    ("xxhdpi", 144, 324),
    ("xxxhdpi", 192, 432),
]


def glyph_alpha(src):
    """White glyph -> opaque white with luminance alpha; gradient -> transparent."""
    rgb = src.convert("RGB")
    w, h = rgb.size
    px = rgb.load()
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    opx = out.load()
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            lum = min(r, g, b)  # white has high min-channel; gradient indigo has low
            a = 0
            if lum > 200:
                a = 255
            elif lum > 120:
                a = int((lum - 120) / 80.0 * 255)
            if a > 0:
                opx[x, y] = (255, 255, 255, a)
    return out


def rounded_mask(size, radius):
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius, fill=255)
    return mask


def circle_mask(size):
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    d.ellipse([0, 0, size - 1, size - 1], fill=255)
    return mask


def main():
    src = Image.open(SRC).convert("RGB")
    glyph = glyph_alpha(src)

    for tag, legacy, adaptive in DENSITIES:
        d = os.path.join(RES, "mipmap-" + tag)
        os.makedirs(d, exist_ok=True)

        leg = src.resize((legacy, legacy), Image.LANCZOS)
        leg_rgba = leg.convert("RGBA")

        # Rounded-square legacy icon.
        rr = rounded_mask(legacy, max(6, int(legacy * 0.20)))
        im = Image.new("RGBA", (legacy, legacy), (0, 0, 0, 0))
        im.paste(leg_rgba, (0, 0), rr)
        im.save(os.path.join(d, "ic_launcher.png"))

        # Circular legacy icon.
        cm = circle_mask(legacy)
        im = Image.new("RGBA", (legacy, legacy), (0, 0, 0, 0))
        im.paste(leg_rgba, (0, 0), cm)
        im.save(os.path.join(d, "ic_launcher_round.png"))

        # Adaptive layers at 108dp.
        bg = src.resize((adaptive, adaptive), Image.LANCZOS).convert("RGBA")
        bg.save(os.path.join(d, "ic_launcher_background.png"))
        fg = glyph.resize((adaptive, adaptive), Image.LANCZOS)
        fg.save(os.path.join(d, "ic_launcher_foreground.png"))

    print("done")


if __name__ == "__main__":
    main()
