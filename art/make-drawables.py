#!/usr/bin/env python3
"""Write the generated drawables: the launcher (foreground + monochrome
layers) and the notification status icon. Both are a Pebble Time 2 seen
face-on with the Telegram paper plane on its glass, through a slight
magnifier lens.

The body and glass polygons in art/pt2_outline.json were traced, in mm and
centred on the origin, from Core Devices' published dimension drawing
(github.com/coredevices/hardware, "Pebble 2 Duo and Pebble Time 2 -
dimensions.PDF"). This script scales them onto the 108-unit adaptive-icon
canvas, adds straps with bulged sides, a darker rim round the glass, a
sheen across its upper-left corner and the plane, then pushes every point
through a radial lens (centre magnified 1.15x, edge squeezed to meet the
unchanged outside) and writes

  app/src/main/res/drawable/ic_launcher_foreground.xml
  app/src/main/res/drawable/ic_launcher_monochrome.xml

The status icon is the same watch zoomed in on the 24 dp grid: the body
without its lug horns (real side profile, round corners, flat top at the
case edge between the horns) spans the full height, no straps, the glass
shrunk so the frame is 1.5 dp at the sides, the plane 74% of the glass
width, all through a 1.15x lens. Alpha only, since Android re-tints small icons. Writes

  app/src/main/res/drawable/ic_stat_watch.xml

Run from the repo root, then art/render-icons.py for the previews and the
Pebble PNGs. Standard library only.
"""
import json, math
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res/drawable"
OUTLINE = json.load(open(ROOT / "art/pt2_outline.json"))

C = 54.0              # canvas centre
BODY_W = 38           # body width in canvas units (108 = full icon, 66 = safe zone)
HORN_IN_MM = 11.04    # strap half-width: it sits between the lug horns, 22.1 mm
SAFE_R = 33           # adaptive-icon safe circle radius
GLASS_SCALE = 0.88    # glass outline shrunk towards its centre, leaving a bezel
RIM_SCALE = 1.05      # rim: the glass outline scaled up
STRAP_TAPER = 0.86    # strap end width / strap root width
STRAP_SIDE = 1.6      # how far the strap sides bulge out (control point offset)
PLANE_H = 20.8        # plane height in canvas units, before the lens
PLANE_DX = -1.0       # nudge left: the glyph's ink sits right of its box centre
LENS_S, LENS_R = 0.15, 36
SHEEN = "M20,20H70L20,70Z"

STAT_C = 12.0         # status icon: 24 dp grid centre
STAT_BODY_H = 24      # body height incl. horns, dp
STAT_FRAME = 1.5      # frame width at the sides, dp (glass is shrunk to leave this)
STAT_TOP_MM = 21.42   # case edge between the horns, mm from centre (horns reach 23.27)
STAT_CORNER_MM = 6    # corner radius of the hornless body, mm
STAT_PLANE = 0.74     # plane width after the lens, as a fraction of the glass width
STAT_PLANE_DX = -0.4
STAT_LENS_S = 0.15    # lens strength; radius = STAT_BODY_H / 2

BG = "#2B3440"                                  # @color/ic_launcher_background
BODY_C, STRAP_C, MARK_C = "#FFFFFF", "#0B0F14", "#FFFFFF"
BLUE, BLUE_RIM, BLUE_SHEEN = "#2AABEE", "#1E8FCF", "#8ED5F8"

# Material Design Icons "telegram" (Apache-2.0, see ui/AppIcons.kt), 24-unit box.
PLANE = ("M9.78,18.65L10.06,14.42L17.74,7.5C18.08,7.19 17.67,7.04 17.22,7.31L7.74,13.3L3.64,12C2.76,11.75 2.75,11.14 "
         "3.84,10.7L19.81,4.54C20.54,4.21 21.24,4.72 20.96,5.84L18.24,18.65C18.05,19.56 17.5,19.78 16.74,19.36L12.6,16.3"
         "L10.61,18.23C10.38,18.46 10.19,18.65 9.78,18.65Z")


# ---------------------------------------------------------------- paths as point lists
def parse(d, step=0.6):
    """Absolute M/L/H/V/Q/C/Z path -> list of subpaths, each a list of (x, y)
    with curves flattened and lines subdivided every `step` units, so the
    lens can bend them."""
    import re
    toks = re.findall(r"[MLHVQCZ]|-?\d*\.?\d+(?:e-?\d+)?", d)
    subs, cur, i, cmd = [], [], 0, None
    x = y = 0.0

    def line_to(nx, ny):
        nonlocal x, y
        n = max(1, int(math.dist((x, y), (nx, ny)) / step))
        cur.extend((x + (nx - x) * t / n, y + (ny - y) * t / n) for t in range(1, n + 1))
        x, y = nx, ny

    def bezier(ctrl, n=24):
        nonlocal x, y
        pts = [(x, y)] + ctrl
        for s in range(1, n + 1):
            u = s / n
            p = pts
            while len(p) > 1:
                p = [((1 - u) * a[0] + u * b[0], (1 - u) * a[1] + u * b[1]) for a, b in zip(p, p[1:])]
            cur.append(p[0])
        x, y = ctrl[-1]

    while i < len(toks):
        t = toks[i]
        if t.isalpha():
            cmd = t; i += 1
            if cmd == "Z":
                if cur: subs.append(cur); cur = []
                continue
        nums = lambda k: [float(v) for v in toks[i:i + k]]
        if cmd == "M":
            if cur: subs.append(cur)
            x, y = nums(2); i += 2; cur = [(x, y)]; cmd = "L"
        elif cmd == "L":
            nx, ny = nums(2); i += 2; line_to(nx, ny)
        elif cmd == "H":
            (nx,) = nums(1); i += 1; line_to(nx, y)
        elif cmd == "V":
            (ny,) = nums(1); i += 1; line_to(x, ny)
        elif cmd == "Q":
            v = nums(4); i += 4; bezier([(v[0], v[1]), (v[2], v[3])])
        elif cmd == "C":
            v = nums(6); i += 6; bezier([(v[0], v[1]), (v[2], v[3]), (v[4], v[5])])
        else:
            raise ValueError(cmd)
    if cur: subs.append(cur)
    return subs


def emit(subs):
    return "".join("M" + "L".join(f"{px:.2f},{py:.2f}" for px, py in s) + "Z" for s in subs)


def transform(subs, scale=1.0, dx=0.0, dy=0.0, about=(C, C)):
    return [[(about[0] + (px - about[0]) * scale + dx, about[1] + (py - about[1]) * scale + dy) for px, py in s]
            for s in subs]


def lens(p, c=C, R=LENS_R, s=LENS_S):
    """Radial magnifier about (c, c): inside R, r -> R*g(r/R) with
    g(x) = (1+s)x/(1+sx), so the centre is magnified (1+s)x and the edge
    squeezed to meet the unchanged outside without a seam."""
    dx, dy = p[0] - c, p[1] - c
    r = math.hypot(dx, dy)
    if r == 0 or r >= R:
        return p
    x = r / R
    k = R * (1 + s) * x / (1 + s * x) / r
    return (c + dx * k, c + dy * k)


def warped(subs):
    return emit([[lens(p) for p in s] for s in subs])


# ---------------------------------------------------------------- the watch
def poly_path(points, k, scale=1.0, c=C):
    return "M" + "L".join(f"{c + x * scale * k:.2f},{c + y * scale * k:.2f}" for x, y in points) + "Z"


def extent(subs, axis=0):
    vs = [p[axis] for s in subs for p in s]
    return min(vs), max(vs)


def strap_path(k):
    """Both straps: from under the lug horns outwards, sides bulging, ending
    inside the safe circle."""
    w0 = HORN_IN_MM * k
    w1 = w0 * STRAP_TAPER
    yb = max(p[1] for p in OUTLINE["body"]) * k * 0.9
    ye = math.floor(math.sqrt(SAFE_R ** 2 - w1 ** 2) * 10) / 10
    d = ""
    for s in (-1, 1):
        y = lambda v: C + s * v
        ax, bx, cx, dx = C - w0, C + w0, C + w1, C - w1
        ym = (yb + ye) / 2
        rc, lc = (C + (w0 + w1) / 2 + STRAP_SIDE, y(ym)), (C - (w0 + w1) / 2 - STRAP_SIDE, y(ym))
        d += (f"M{ax:.2f},{y(yb):.2f}L{bx:.2f},{y(yb):.2f}"
              f"Q{rc[0]:.2f},{rc[1]:.2f} {cx:.2f},{y(ye):.2f}"
              f"Q{C:.2f},{y(ye):.2f} {dx:.2f},{y(ye):.2f}"
              f"Q{lc[0]:.2f},{lc[1]:.2f} {ax:.2f},{y(yb):.2f}Z")
    return d


def plane_subs():
    """The plane flattened, scaled to PLANE_H and centred on its box."""
    subs = parse(PLANE, step=0.15)
    xs = [p[0] for s in subs for p in s]; ys = [p[1] for s in subs for p in s]
    k = PLANE_H / (max(ys) - min(ys))
    cx, cy = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
    return [[(C + PLANE_DX + (px - cx) * k, C + (py - cy) * k) for px, py in s] for s in subs]


# ------------------------------------------------------------ status icon
def side_x(y):
    """x of the real right-hand body side at height y (mm, linear interpolation)."""
    pts = sorted((py, px) for px, py in OUTLINE["body"] if px > 12 and abs(py) < STAT_TOP_MM - 0.3)
    for (y0, x0), (y1, x1) in zip(pts, pts[1:]):
        if y0 <= y <= y1:
            return x0 + (x1 - x0) * (y - y0) / (y1 - y0)
    return pts[0][1] if y < pts[0][0] else pts[-1][1]


def hornless_body(R=STAT_CORNER_MM, n=24):
    """The case without lug horns: flat top at STAT_TOP_MM, circular corners
    of radius R into the real side profile. Right half top to bottom, mirrored."""
    half = [(0.0, -STAT_TOP_MM)]
    cx, cy = side_x(-STAT_TOP_MM + R) - R, -STAT_TOP_MM + R
    for i in range(n + 1):
        a = math.pi / 2 * (1 - i / n)
        half.append((cx + R * math.cos(a), cy - R * math.sin(a)))
    for i in range(1, 60):
        y = -STAT_TOP_MM + R + i * 2 * (STAT_TOP_MM - R) / 60
        half.append((side_x(y), y))
    cx, cy = side_x(STAT_TOP_MM - R) - R, STAT_TOP_MM - R
    for i in range(n + 1):
        a = -math.pi / 2 * i / n
        half.append((cx + R * math.cos(a), cy - R * math.sin(a)))
    half.append((0.0, STAT_TOP_MM))
    return half + [(-x, y) for x, y in reversed(half[1:-1])]


def stat_drawable():
    body_mm = hornless_body()
    body_h_mm = max(p[1] for p in body_mm) - min(p[1] for p in body_mm)
    body_w_mm = max(p[0] for p in body_mm) - min(p[0] for p in body_mm)
    glass_w_mm = max(p[0] for p in OUTLINE["glass"]) - min(p[0] for p in OUTLINE["glass"])
    k = STAT_BODY_H / body_h_mm
    glass_scale = (body_w_mm * k - 2 * STAT_FRAME) / (glass_w_mm * k)
    warp = lambda subs: [[lens(p, STAT_C, STAT_BODY_H / 2, STAT_LENS_S) for p in s] for s in subs]

    body = warp(parse(poly_path(body_mm, k, c=STAT_C), step=0.3))
    glass = warp(parse(poly_path(OUTLINE["glass"], k, glass_scale, c=STAT_C), step=0.3))

    # Plane centred on the grid, pre-scaled so its width *after* the lens is STAT_PLANE of the glass.
    gl, gr = extent(glass)
    target = STAT_PLANE * (gr - gl)
    raw = parse(PLANE, step=0.1)
    (x0, x1), (y0, y1) = extent(raw), extent(raw, 1)
    mx, my, w = (x0 + x1) / 2, (y0 + y1) / 2, target
    for _ in range(4):
        kp = w / (x1 - x0)
        plane = warp([[(STAT_C + STAT_PLANE_DX + (x - mx) * kp, STAT_C + (y - my) * kp) for x, y in s] for s in raw])
        lo, hi = extent(plane)
        w *= target / (hi - lo)

    return ('<?xml version="1.0" encoding="utf-8"?>\n' + f"""<!--
  Status-bar icon: the launcher's watch zoomed in and without its lug horns,
  body {STAT_BODY_H} dp tall with the glass cut out and the plane left standing
  in it, through a {1 + STAT_LENS_S:g}x lens. Notification small icons are reduced to their alpha
  channel and re-tinted, so it is a flat white shape on transparency.
  Generated by art/make-drawables.py; edit there, not here.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="#FFFFFF">
    <path
        android:fillColor="#FFFFFFFF"
        android:fillType="evenOdd"
        android:pathData="{emit(body) + emit(glass) + emit(plane)}" />
</vector>
""")


def main():
    k = BODY_W / (max(p[0] for p in OUTLINE["body"]) - min(p[0] for p in OUTLINE["body"]))
    face = parse(poly_path(OUTLINE["glass"], k, GLASS_SCALE))
    straps_w, body_w = warped(parse(strap_path(k))), warped(parse(poly_path(OUTLINE["body"], k)))
    rim_w, face_w, sheen_w = warped(transform(face, RIM_SCALE)), warped(face), warped(parse(SHEEN))
    plane_w = warped(plane_subs())

    hdr = '<?xml version="1.0" encoding="utf-8"?>\n'
    vec = ('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    android:width="108dp"\n    android:height="108dp"\n'
           '    android:viewportWidth="108"\n    android:viewportHeight="108">\n')

    def path(fill, d, comment, extra="", ind="    "):
        return (f'{ind}<!-- {comment} -->\n{ind}<path\n{ind}    android:fillColor="{fill}"{extra}\n'
                f'{ind}    android:pathData="{d}" />\n')

    fg = (hdr + f"""<!--
  Launcher art: a Pebble Time 2 seen face-on with the Telegram paper plane
  on Telegram-blue glass. Body {BODY_C}, straps {STRAP_C} with bulged sides,
  darker blue rim round the glass and a light sheen across its upper-left
  corner, all seen through a {1 + LENS_S:g}x magnifier lens (radius {LENS_R}).
  Background ({BG}) comes from @color/ic_launcher_background.
  Generated by art/make-drawables.py; edit there, not here.
-->
""" + vec
          + path(STRAP_C, straps_w, "straps")
          + path(BODY_C, body_w, "watch body (Pebble Time 2 outline incl. lug horns)")
          + path(BLUE_RIM, rim_w, "rim: the glass outline scaled up")
          + path(BLUE, face_w, "glass")
          + f'    <group>\n        <clip-path android:pathData="{face_w}" />\n'
          + path(BLUE_SHEEN, sheen_w, "sheen, clipped to the glass", '\n            android:fillAlpha="0.45"', ind="        ")
          + '    </group>\n'
          + path(MARK_C, plane_w, 'paper plane: Material Design Icons "telegram" (Apache-2.0, see ui/AppIcons.kt)')
          + '</vector>\n')

    # Themed icons take only alpha: straps, then the body with the glass cut
    # out and the plane filled back in (even-odd over three nested outlines).
    mono = (hdr + """<!--
  Themed-icon layer (Android 13+): launchers take only the alpha and tint it.
  Watch silhouette with the glass cut out and the plane left standing in it.
  Geometry (including the lens) matches ic_launcher_foreground.
  Generated by art/make-drawables.py; edit there, not here.
-->
""" + vec
            + path("#FFFFFF", straps_w, "straps")
            + path("#FFFFFF", body_w + face_w + plane_w, "body, glass cut out, plane left standing",
                   '\n        android:fillType="evenOdd"')
            + '</vector>\n')

    (RES / "ic_launcher_foreground.xml").write_text(fg)
    (RES / "ic_launcher_monochrome.xml").write_text(mono)
    (RES / "ic_stat_watch.xml").write_text(stat_drawable())
    print(f"drawables -> {RES.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
