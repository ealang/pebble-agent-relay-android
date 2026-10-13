#!/usr/bin/env python3
"""Render the Pebble-side icons from the Android launcher drawable.

The launcher drawable (app/src/main/res/drawable/ic_launcher_foreground.xml)
is the single source for the artwork: the plane glyph, colours and watch
geometry live there. This script reads the glyph out of it, composes the
Pebble squircle around it and writes the PNGs the watch app and the Rebble
appstore listing need into art/pebble/:

  menu_icon~color.png   25x25  watch launcher menu, colour platforms
  menu_icon~bw.png      25x25  watch launcher menu, 1-bit platforms (black
                        rounded square, larger plane, no border, hard edges)
  store_icon_80.png     80x80  appstore "small icon"
  store_icon_144.png   144x144 appstore "large icon"
  companion_icon_144.png 144x144 appstore companion-app icon (the Android
                        launcher icon, circle-masked)
  launcher_preview.png 432x432 the Android icon as a launcher would show it

Needs cairosvg and Pillow:  python3 -m venv .venv && .venv/bin/pip install cairosvg pillow
Run from the repo root:     .venv/bin/python art/render-icons.py
"""
import io, math, re, xml.etree.ElementTree as ET
from pathlib import Path

import cairosvg
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
OUT = ROOT / "art/pebble"
A = "{http://schemas.android.com/apk/res/android}"

PEBBLE_BLUE = "#00AAFF"   # nearest entry in Pebble's 64-colour palette to Telegram's #2AABEE


def drawable_to_svg(xml_path: Path, background: str | None) -> tuple[str, str]:
    """Translate our (simple) VectorDrawable into SVG. Returns (svg, plane path)."""
    root = ET.parse(xml_path).getroot()
    vw, vh = root.get(A + "viewportWidth"), root.get(A + "viewportHeight")
    body, last_path = [], ""

    def path_el(p):
        nonlocal last_path
        last_path = p.get(A + "pathData")
        rule = ' fill-rule="evenodd"' if p.get(A + "fillType") == "evenOdd" else ""
        return f'<path fill="{p.get(A + "fillColor")}"{rule} d="{last_path}"/>'

    for el in root:
        if el.tag == "path":
            body.append(path_el(el))
        elif el.tag == "group":
            tx, ty = el.get(A + "translateX", "0"), el.get(A + "translateY", "0")
            sx, sy = el.get(A + "scaleX", "1"), el.get(A + "scaleY", "1")
            inner = "".join(path_el(p) for p in el if p.tag == "path")
            body.append(f'<g transform="translate({tx},{ty}) scale({sx},{sy})">{inner}</g>')
    bg = f'<rect width="{vw}" height="{vh}" fill="{background}"/>' if background else ""
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {vw} {vh}">{bg}{"".join(body)}</svg>'
    return svg, last_path


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


def squircle_svg(plane: str, circle, canvas: float = 25, fill: str = PEBBLE_BLUE) -> str:
    """Rounded square, white border, white plane. Proportions are those of the
    25px menu icon (corner radius 5, border 2, plane circle r=8.6); larger
    sizes scale with the canvas."""
    k = canvas / 25
    radius, border, plane_r = 5 * k, 2 * k, 8.6 * k
    cx, cy, r = circle
    s = plane_r / r
    tx, ty = canvas / 2 - cx * s, canvas / 2 - cy * s
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {canvas} {canvas}">'
        f'<rect width="{canvas}" height="{canvas}" rx="{radius}" fill="{fill}"/>'
        f'<rect x="{border / 2}" y="{border / 2}" width="{canvas - border}" height="{canvas - border}" '
        f'rx="{radius - border / 2}" fill="none" stroke="#FFFFFF" stroke-width="{border}"/>'
        f'<g transform="translate({tx:.4f},{ty:.4f}) scale({s:.5f})"><path fill="#FFFFFF" d="{plane}"/></g></svg>'
    )


def bw_svg(plane: str, circle, canvas: float = 25) -> str:
    """1-bit menu icon. The launcher background on bw watches is white, so the
    white border of the colour icon would vanish; instead use the whole canvas
    for a black rounded square and a larger plane (r=11) so the wing/body gap
    survives thresholding."""
    radius, plane_r = 5, 11
    cx, cy, r = circle
    s = plane_r / r
    tx, ty = canvas / 2 - cx * s, canvas / 2 - cy * s
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {canvas} {canvas}">'
        f'<rect width="{canvas}" height="{canvas}" rx="{radius}" fill="#000000"/>'
        f'<g transform="translate({tx:.4f},{ty:.4f}) scale({s:.5f})"><path fill="#FFFFFF" d="{plane}"/></g></svg>'
    )


def one_bit(img: Image.Image) -> Image.Image:
    """Threshold an anti-aliased render to pure black/white with a hard alpha
    edge, which is what the 1-bit platforms would do anyway, only less tidily."""
    r, _, _, a = img.split()
    ink = r.point(lambda v: 255 if v > 127 else 0)
    return Image.merge("RGBA", (ink, ink, ink, a.point(lambda v: 255 if v > 127 else 0)))


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    launcher_svg, plane = drawable_to_svg(RES / "drawable/ic_launcher_foreground.xml", launcher_background())
    circle = enclosing_circle(plane)

    circle_mask(render(launcher_svg, 432)).save(OUT / "launcher_preview.png")
    circle_mask(render(launcher_svg, 144)).save(OUT / "companion_icon_144.png")

    menu = render(squircle_svg(plane, circle), 25)
    menu.save(OUT / "menu_icon~color.png")
    one_bit(render(bw_svg(plane, circle), 25)).save(OUT / "menu_icon~bw.png")
    for size in (80, 144):
        render(squircle_svg(plane, circle, canvas=size), size).save(OUT / f"store_icon_{size}.png")

    for p in sorted(OUT.iterdir()):
        im = Image.open(p)
        print(f"{p.name:24} {im.size[0]}x{im.size[1]}")


if __name__ == "__main__":
    main()
