"""Generate the Coospo BC200 sync launcher icon (Android vectors + SVG previews) from one definition."""
# Usage: python3 tools/gen_icon.py app/src/main/res <preview-dir>   (previews: rsvg-convert preview-*.svg)
import sys, os
out_res, out_prev = sys.argv[1], sys.argv[2]
BG = "#0B7A75"
W = 4.2  # stroke width in the 108 x 108 adaptive-icon viewport

def circle(cx, cy, r):
    return f"M{cx - r},{cy} a{r},{r} 0 1,0 {2 * r},0 a{r},{r} 0 1,0 {-2 * r},0"

rear, front, bb, seat, head = (36, 64), (72, 64), (52, 64), (47, 46), (64, 44)
fork_y = lambda y: head[0] + (front[0] - head[0]) * (y - head[1]) / (front[1] - head[1])
paths = [
    circle(*rear, 12.5),
    circle(*front, 12.5),
    # frame: chainstay, seatstay, seat tube (+ seatpost), top tube, down tube, head tube + fork
    f"M{rear[0]},{rear[1]} L{bb[0]},{bb[1]} L{seat[0]},{seat[1]} L{rear[0]},{rear[1]}",
    f"M{seat[0]},{seat[1]} L{46},{41.5}",
    f"M{seat[0]},{seat[1]} L{fork_y(46):.2f},46",
    f"M{bb[0]},{bb[1]} L{fork_y(49.5):.2f},49.5",
    f"M{head[0]},{head[1]} L{front[0]},{front[1]}",
    # saddle and handlebar
    "M41,41.5 L51.5,41.5",
    f"M{head[0]},{head[1]} L63,38.5 L69,38.5",
]
chainring = circle(*bb, 2.6)

def vector(stroke):
    p = "\n".join(
        f'    <path android:pathData="{d}" android:strokeColor="{stroke}" android:strokeWidth="{W}"\n'
        f'        android:strokeLineCap="round" android:strokeLineJoin="round" />' for d in paths)
    p += (f'\n    <path android:pathData="{chainring}" android:strokeColor="{stroke}" android:strokeWidth="3"\n'
          f'        android:fillColor="{stroke}" />')
    return ('<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated: bicycle for the BC200 Sync launcher icon. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp" android:height="108dp"\n'
            '    android:viewportWidth="108" android:viewportHeight="108">\n'
            '  <group android:translateY="-3.5">\n' + p + '\n  </group>\n</vector>\n')

def svg(mask):
    body = "".join(f'<path d="{d}" fill="none" stroke="#fff" stroke-width="{W}" stroke-linecap="round" stroke-linejoin="round"/>' for d in paths)
    body += f'<path d="{chainring}" fill="#fff" stroke="#fff" stroke-width="3"/>'
    clip = {"circle": '<circle cx="54" cy="54" r="54"/>', "squircle": '<rect x="0" y="0" width="108" height="108" rx="32"/>'}[mask]
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="432" height="432">'
            f'<clipPath id="m">{clip}</clipPath><g clip-path="url(#m)"><rect width="108" height="108" fill="{BG}"/><g transform="translate(0,-3.5)">{body}</g>'
            f'<circle cx="54" cy="54" r="33" fill="none" stroke="#ff0" stroke-width="0.3" stroke-dasharray="1,1" opacity="0.0"/></g></svg>')

d = os.path.join(out_res, "drawable"); os.makedirs(d, exist_ok=True)
open(os.path.join(d, "ic_launcher_foreground.xml"), "w").write(vector("#FFFFFFFF"))
v = os.path.join(out_res, "values"); os.makedirs(v, exist_ok=True)
open(os.path.join(v, "ic_launcher_colors.xml"), "w").write(
    f'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">{BG}</color>\n</resources>\n')
m = os.path.join(out_res, "mipmap-anydpi-v26"); os.makedirs(m, exist_ok=True)
adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@color/ic_launcher_background" />\n'
            '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
            '    <monochrome android:drawable="@drawable/ic_launcher_foreground" />\n</adaptive-icon>\n')
for n in ("ic_launcher.xml", "ic_launcher_round.xml"):
    open(os.path.join(m, n), "w").write(adaptive)
for mask in ("circle", "squircle"):
    open(os.path.join(out_prev, f"preview-{mask}.svg"), "w").write(svg(mask))
print("ok")
