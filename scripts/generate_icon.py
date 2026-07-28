#!/usr/bin/env python3
"""
Generate the LightFastread launcher icon.

Design language is taken from gi-os/LightFog: heavy white line art, round caps,
on a full-bleed pure-black square. LightFog draws a folded map with a dashed
route running to a ringed pin. This is the reading equivalent - an open book
with dashed lines of text, the current word ringed - so the two tools read as
siblings on the device.

Measured from LightFog's icon.png (1024x1024):
  outline stroke   30px  (2.93% of canvas)
  detail stroke    24px  (2.34%)
  ink bounding box 0.616 wide x 0.458 tall, optically centred

Geometry is defined once, in a 1024-unit design space, and emitted as both an
SVG (rasterised to the mipmap WebPs) and an Android VectorDrawable (the adaptive
icon's foreground). One source of truth, so the two can't drift.

Usage:  python3 scripts/generate_icon.py
Needs:  pip install cairosvg pillow
"""

import io
import os
import subprocess
import sys

S = 1024                      # design space
OUTLINE = 30                  # book outline stroke
DETAIL = 24                   # text lines and focal ring stroke

# --- book -----------------------------------------------------------------
SPINE_X = 512
SPINE_TOP, SPINE_BOT = 312, 746
OUTER_L, OUTER_R = 198, 826
OUTER_TOP, OUTER_BOT = 352, 706
SAG = 14                      # how far the leaf edges bow. LightFog's map
                              # panels are near-straight, so keep this small.

def page(sign):
    """One leaf of the book. sign=-1 left, +1 right."""
    ox = SPINE_X + sign * (OUTER_L - SPINE_X) * -1 if sign > 0 else OUTER_L
    if sign > 0:
        ox = OUTER_R
    # Top edge sags away from the spine, bottom edge mirrors it, so the leaf
    # reads as paper under its own weight rather than a flat rectangle.
    cx1 = SPINE_X + sign * 120
    cx2 = ox - sign * 120
    return (
        f"M {SPINE_X},{SPINE_TOP} "
        f"C {cx1},{SPINE_TOP - SAG} {cx2},{OUTER_TOP - SAG} {ox},{OUTER_TOP} "
        f"L {ox},{OUTER_BOT} "
        f"C {cx2},{OUTER_BOT + SAG} {cx1},{SPINE_BOT + SAG} {SPINE_X},{SPINE_BOT} "
        f"Z"
    )

BOOK = [page(-1), page(1), f"M {SPINE_X},{SPINE_TOP} L {SPINE_X},{SPINE_BOT}"]

# --- lines of text --------------------------------------------------------
# Left leaf: three dashed rows. Dashes are emitted as explicit segments because
# VectorDrawable has no stroke-dasharray, and hand-placing them keeps the raster
# and the vector byte-identical in shape.
def dashes(x0, x1, y, dash, gap):
    segs, x = [], x0
    while x < x1:
        x2 = min(x + dash, x1)
        if x2 - x > dash * 0.45:          # drop a runt dash at the end
            segs.append(f"M {x:.0f},{y} L {x2:.0f},{y}")
        x += dash + gap
    return segs

TEXT = []
for y in (424, 512, 600):
    TEXT += dashes(258, 454, y, 64, 36)

# The right leaf holds a single ringed word. The asymmetry is the idea: a page of
# text on the left, collapsed to one focal word on the right. The ring echoes the
# pins LightFog's dashed route runs between.
# Ring plus a centred dot: a focal reticle. A horizontal bar inside the ring was
# the first attempt and read as a "no entry" glyph at small sizes.
#
# The dot is a *filled* circle, not a zero-length round-capped stroke. SVG renders
# the latter as a dot, but Android's VectorDrawable is not required to and Skia
# drops it, which would have shipped an empty ring to the device while the
# preview PNGs looked correct.
FOCAL_C = (672, 512)
FOCAL_R = 82
FOCAL_DOT = 30

def svg(size, bg="#000000", pad=0.0):
    """pad shrinks the art toward the centre, for the adaptive-icon safe zone."""
    k = 1.0 - pad
    off = S * pad / 2
    body = []
    for d in BOOK:
        body.append(f'<path d="{d}" fill="none" stroke="#FFFFFF" '
                    f'stroke-width="{OUTLINE}" stroke-linecap="round" stroke-linejoin="round"/>')
    for d in TEXT:
        body.append(f'<path d="{d}" fill="none" stroke="#FFFFFF" '
                    f'stroke-width="{DETAIL}" stroke-linecap="round"/>')
    body.append(f'<circle cx="{FOCAL_C[0]}" cy="{FOCAL_C[1]}" r="{FOCAL_R}" '
                f'fill="none" stroke="#FFFFFF" stroke-width="{DETAIL}"/>')
    body.append(f'<circle cx="{FOCAL_C[0]}" cy="{FOCAL_C[1]}" r="{FOCAL_DOT}" fill="#FFFFFF"/>')
    rect = f'<rect width="{S}" height="{S}" fill="{bg}"/>' if bg else ""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
            f'viewBox="0 0 {S} {S}">{rect}'
            f'<g transform="translate({off},{off}) scale({k})">' + "".join(body) + "</g></svg>")


def vector_drawable():
    """Adaptive-icon foreground: 108dp viewport, art inside the 72dp safe zone."""
    k = 72.0 / 108.0 * (108.0 / S)          # design units -> dp, shrunk to safe zone
    off = (108.0 - S * k) / 2.0
    def conv(d):
        # Scale every coordinate from the 1024 design space into 108dp.
        out, num = [], ""
        for ch in d:
            if ch.isdigit() or ch == ".":
                num += ch
            else:
                if num:
                    out.append(f"{float(num) * k + off:.3f}")
                    num = ""
                out.append(ch)
        if num:
            out.append(f"{float(num) * k + off:.3f}")
        return "".join(out)

    paths = []
    for d in BOOK:
        paths.append(f'''    <path
        android:pathData="{conv(d)}"
        android:strokeColor="#FFFFFF"
        android:strokeWidth="{OUTLINE * k:.3f}"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />''')
    for d in TEXT:
        paths.append(f'''    <path
        android:pathData="{conv(d)}"
        android:strokeColor="#FFFFFF"
        android:strokeWidth="{DETAIL * k:.3f}"
        android:strokeLineCap="round" />''')
    cx, cy, r = FOCAL_C[0] * k + off, FOCAL_C[1] * k + off, FOCAL_R * k
    circle = (f"M {cx - r:.3f},{cy:.3f} "
              f"a {r:.3f},{r:.3f} 0 1,0 {2 * r:.3f},0 "
              f"a {r:.3f},{r:.3f} 0 1,0 {-2 * r:.3f},0 Z")
    paths.append(f'''    <path
        android:pathData="{circle}"
        android:strokeColor="#FFFFFF"
        android:strokeWidth="{DETAIL * k:.3f}" />''')
    dr = FOCAL_DOT * k
    dot = (f"M {cx - dr:.3f},{cy:.3f} "
           f"a {dr:.3f},{dr:.3f} 0 1,0 {2 * dr:.3f},0 "
           f"a {dr:.3f},{dr:.3f} 0 1,0 {-2 * dr:.3f},0 Z")
    paths.append(f'''    <path
        android:pathData="{dot}"
        android:fillColor="#FFFFFF" />''')

    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp"\n'
            '    android:height="108dp"\n'
            '    android:viewportWidth="108"\n'
            '    android:viewportHeight="108">\n'
            + "\n".join(paths) + "\n</vector>\n")


BACKGROUND = '''<?xml version="1.0" encoding="utf-8"?>
<!-- Pure black. On the Light Phone III's OLED these pixels are simply off. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#000000"
        android:pathData="M0,0h108v108h-108z" />
</vector>
'''

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def main():
    import cairosvg
    from PIL import Image

    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
    res = os.path.join(root, "app/src/main/res")

    def render(size, round_mask=False):
        png = cairosvg.svg2png(bytestring=svg(size).encode(),
                               output_width=size * 4, output_height=size * 4)
        im = Image.open(io.BytesIO(png)).convert("RGBA")
        if round_mask:
            from PIL import ImageDraw
            mask = Image.new("L", im.size, 0)
            ImageDraw.Draw(mask).ellipse((0, 0, im.size[0] - 1, im.size[1] - 1), fill=255)
            im.putalpha(mask)
        return im.resize((size, size), Image.LANCZOS)

    for d, px in DENSITIES.items():
        for name, rnd in (("ic_launcher", False), ("ic_launcher_round", True)):
            out = os.path.join(res, f"mipmap-{d}", f"{name}.webp")
            render(px, rnd).save(out, "WEBP", lossless=True, quality=100)
            print("wrote", os.path.relpath(out, root))

    fg = os.path.join(res, "drawable/ic_launcher_foreground.xml")
    open(fg, "w").write(vector_drawable())
    print("wrote", os.path.relpath(fg, root))

    bg = os.path.join(res, "drawable/ic_launcher_background.xml")
    open(bg, "w").write(BACKGROUND)
    print("wrote", os.path.relpath(bg, root))

    # Reference art, same role as LightFog's assets/images/icon.png
    ref = os.path.join(root, "docs/icon.png")
    os.makedirs(os.path.dirname(ref), exist_ok=True)
    render(1024).convert("RGB").save(ref)
    print("wrote", os.path.relpath(ref, root))


if __name__ == "__main__":
    main()
