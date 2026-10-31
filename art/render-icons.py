#!/usr/bin/env python3
"""Render the previews and the Pebble-side icons.

The launcher drawable (app/src/main/res/drawable/ic_launcher_foreground.xml,
written by art/make-launcher-drawable.py) is translated to SVG and rendered
as a launcher would show it. The Pebble icons use the same paper-plane glyph
(make-launcher-drawable.PLANE, without the lens) on a rounded square. Writes
into art/pebble/:

  menu_icon~color.png   25x25  watch launcher menu, colour platforms. The
                        launcher shows app icons there as greyscale, so it is
                        drawn in grey: black border, light-grey interior,
                        black plane, transparent corners.
  menu_icon~bw.png      25x25  same with a white interior, hard 1-bit edges,
                        for the black-and-white platforms (inverting mode)
  store_icon_80.png     80x80  appstore "small icon"
  store_icon_144.png   144x144 appstore "large icon"
  companion_icon_144.png 144x144 appstore companion-app icon (the Android
                        launcher icon, circle-masked)
  launcher_preview.png 432x432 the Android icon as a launcher would show it

Needs cairosvg and Pillow:  python3 -m venv .venv && .venv/bin/pip install cairosvg pillow
Run from the repo root:     .venv/bin/python art/render-icons.py
"""
import importlib.util, io, math, xml.etree.ElementTree as ET
from pathlib import Path

import cairosvg
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
OUT = ROOT / "art/pebble"
A = "{http://schemas.android.com/apk/res/android}"

PEBBLE_BLUE = "#00AAFF"   # nearest entry in Pebble's 64-colour palette to Telegram's #2AABEE

_spec = importlib.util.spec_from_file_location("mld", Path(__file__).with_name("make-launcher-drawable.py"))
mld = importlib.util.module_from_spec(_spec); _spec.loader.exec_module(mld)
PLANE = mld.PLANE


def drawable_to_svg(xml_path: Path, background: str | None) -> str:
    """Translate our (simple) VectorDrawable into SVG: paths, groups with a
    translate/scale or a clip-path, fillAlpha and fillType."""
    root = ET.parse(xml_path).getroot()
    vw, vh = root.get(A + "viewportWidth"), root.get(A + "viewportHeight")
    body, clips = [], 0

    def path_el(p, extra=""):
        rule = ' fill-rule="evenodd"' if p.get(A + "fillType") == "evenOdd" else ""
        alpha = f' fill-opacity="{p.get(A + "fillAlpha")}"' if p.get(A + "fillAlpha") else ""
        return f'<path fill="{p.get(A + "fillColor")}"{rule}{alpha}{extra} d="{p.get(A + "pathData")}"/>'

    for el in root:
        if el.tag == "path":
            body.append(path_el(el))
        elif el.tag == "group":
            clip = el.find("clip-path")
            if clip is not None:
                clips += 1
                body.append(f'<clipPath id="c{clips}"><path d="{clip.get(A + "pathData")}"/></clipPath>')
                body += [path_el(p, f' clip-path="url(#c{clips})"') for p in el if p.tag == "path"]
            else:
                tx, ty = el.get(A + "translateX", "0"), el.get(A + "translateY", "0")
                sx, sy = el.get(A + "scaleX", "1"), el.get(A + "scaleY", "1")
                inner = "".join(path_el(p) for p in el if p.tag == "path")
                body.append(f'<g transform="translate({tx},{ty}) scale({sx},{sy})">{inner}</g>')
    bg = f'<rect width="{vw}" height="{vh}" fill="{background}"/>' if background else ""
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {vw} {vh}">{bg}{"".join(body)}</svg>'


def launcher_background() -> str:
    colors = ET.parse(RES / "values/colors.xml").getroot()
    return next(c.text for c in colors if c.get("name") == "ic_launcher_background")


def render(svg: str, size: int) -> Image.Image:
    data = cairosvg.svg2png(bytestring=svg.encode(), output_width=size, output_height=size)
    return Image.open(io.BytesIO(data)).convert("RGBA")


def circle_mask(img: Image.Image) -> Image.Image:
    m = Image.new("L", img.size, 0)
    ImageDraw.Draw(m).ellipse([0, 0, img.size[0] - 1, img.size[1] - 1], fill=255)
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.paste(img, mask=m)
    return out


def enclosing_circle(path: str, units: float = 24, res: int = 40) -> tuple[float, float, float]:
    """Centre and radius of (approximately) the smallest circle around the glyph's ink."""
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {units} {units}"><path d="{path}"/></svg>'
    alpha = render(svg, int(units * res)).split()[3]
    pts = [(x / res, y / res) for y in range(alpha.height) for x in range(alpha.width) if alpha.getpixel((x, y)) > 128]
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    best = (math.inf, 0.0, 0.0)
    cx0, cy0 = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
    for i in range(-20, 21):
        for j in range(-20, 21):
            cx, cy = cx0 + i * 0.05, cy0 + j * 0.05
            r = max(math.hypot(x - cx, y - cy) for x, y in pts[::7])
            if r < best[0]:
                best = (r, cx, cy)
    return best[1], best[2], best[0]


def squircle_svg(plane: str, circle, canvas: float = 25, fill: str = PEBBLE_BLUE,
                 border: float = 2, border_color: str = "#FFFFFF", plane_fill: str = "#FFFFFF",
                 plane_r: float = 8.6, radius: float = 5) -> str:
    """Rounded square with a border and a plane. Proportions are given in
    25px units and scale with the canvas."""
    k = canvas / 25
    radius, border, plane_r = radius * k, border * k, plane_r * k
    cx, cy, r = circle
    s = plane_r / r
    tx, ty = canvas / 2 - cx * s, canvas / 2 - cy * s
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {canvas} {canvas}">'
        f'<rect width="{canvas}" height="{canvas}" rx="{radius}" fill="{border_color}"/>'
        f'<rect x="{border}" y="{border}" width="{canvas - 2 * border}" height="{canvas - 2 * border}" '
        f'rx="{radius - border}" fill="{fill}"/>'
        f'<g transform="translate({tx:.4f},{ty:.4f}) scale({s:.5f})"><path fill="{plane_fill}" d="{plane}"/></g></svg>'
    )


def menu_svg(plane: str, circle, interior: str) -> str:
    """25px launcher icon: black border and plane on the given interior.
    Colour watches render app menu icons as greyscale, bw watches as
    inverting 1-bit, so colour would be lost anyway."""
    return squircle_svg(plane, circle, fill=interior, border_color="#000000", plane_fill="#000000")


def one_bit(img: Image.Image) -> Image.Image:
    """Threshold an anti-aliased render to pure black/white with a hard alpha
    edge, which is what the 1-bit platforms would do anyway, only less tidily."""
    r, _, _, a = img.split()
    ink = r.point(lambda v: 255 if v > 127 else 0)
    return Image.merge("RGBA", (ink, ink, ink, a.point(lambda v: 255 if v > 127 else 0)))


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    launcher_svg = drawable_to_svg(RES / "drawable/ic_launcher_foreground.xml", launcher_background())
    plane, circle = PLANE, enclosing_circle(PLANE)

    # A launcher shows only the centre 72 of the 108 canvas, then masks that.
    for size, name in ((432, "launcher_preview.png"), (144, "companion_icon_144.png")):
        full = render(launcher_svg, size * 3 // 2)
        o = size // 4
        circle_mask(full.crop((o, o, o + size, o + size))).save(OUT / name)

    render(menu_svg(plane, circle, "#AAAAAA"), 25).save(OUT / "menu_icon~color.png")
    one_bit(render(menu_svg(plane, circle, "#FFFFFF"), 25)).save(OUT / "menu_icon~bw.png")
    for size in (80, 144):
        render(squircle_svg(plane, circle, canvas=size), size).save(OUT / f"store_icon_{size}.png")

    for p in sorted(OUT.glob("*.png")):
        im = Image.open(p)
        print(f"{p.name:24} {im.size[0]}x{im.size[1]}")


if __name__ == "__main__":
    main()
