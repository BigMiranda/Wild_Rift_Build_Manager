"""Extracts recipes ("Árvore de Construção") and "Fabrica" lists from Wild Rift shop screenshots by icon matching.

Every item detail print has the item's own icon at a fixed spot of the description panel. Those crops form an icon
library (one per print); every library icon is searched in every print (grayscale template matching at a few
scales, since tree icons are drawn slightly smaller), keeping the best icon per spot. In a print, the item's own icon found
in the right panel is the tree root: icons above it are "Fabrica" (what it builds into), the first row below it are
its direct components (repeated icons = quantity).

Output: JSON keyed by print file name, referencing other prints by file name (names are attached later, when the
transcribed catalog maps each print to an item).

Usage:  python tools/capturas_receitas.py capturas/7.3 [out.json] [--debug DIR]
Needs:  opencv-python-headless, numpy, pillow  (use a virtualenv; nothing here is needed by the app at runtime)
"""
import json
import sys
from pathlib import Path

import cv2
import numpy as np

OWN_ICON_BOX = (16, 110, 112, 206)   # x0, y0, x1, y1 of the item icon in the description panel
INNER = 10                            # ignore the tier-colored border, it differs between panel and tree
ROW_TOLERANCE = 30                    # px (full resolution) for icons on the same tree row


def load(path):
    img = cv2.imdecode(np.fromfile(str(path), dtype=np.uint8), cv2.IMREAD_COLOR)
    return img


SCALE = 0.4                           # search at reduced resolution (icons are ~90 px, plenty of detail left)
ICON_SCALES = (0.93, 0.97, 1.0)       # tree / "Fabrica" icons are drawn slightly smaller than the panel icon
THRESHOLD = 0.78


def gray_small(img, factor=SCALE):
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    return cv2.resize(g, None, fx=factor, fy=factor, interpolation=cv2.INTER_AREA)


def library(files):
    """{print name: [template per icon scale]} from the icon of each print's description panel."""
    lib = {}
    for f in files:
        img = load(f)
        x0, y0, x1, y1 = OWN_ICON_BOX
        icon = img[y0 + INNER:y1 - INNER, x0 + INNER:x1 - INNER]
        lib[f.name] = [gray_small(icon, SCALE * k) for k in ICON_SCALES]
    return lib


def find_all(img, lib):
    """Best library icon for every spot matching above THRESHOLD (any icon scale), non-max suppressed."""
    hay = gray_small(img)
    hits = []
    for name, templates in lib.items():
        for tpl in templates:
            if tpl.shape[0] > hay.shape[0] or tpl.shape[1] > hay.shape[1]:
                continue
            res = cv2.matchTemplate(hay, tpl, cv2.TM_CCOEFF_NORMED)
            ys, xs = np.where(res >= THRESHOLD)
            half = tpl.shape[0] / 2
            for x, y in zip(xs, ys):
                hits.append((float(res[y, x]), name, (x + half) / SCALE, (y + half) / SCALE))
    hits.sort(reverse=True)
    kept = []
    for sc, name, x, y in hits:
        if all(abs(x - kx) > 55 or abs(y - ky) > 55 for _, _, kx, ky in kept):
            kept.append((sc, name, x, y))
    return [{"file": n, "score": round(sc, 3), "x": int(x), "y": int(y)} for sc, n, x, y in kept]


def weak_root(img, templates, hits, minimum=0.35):
    """Best match of the print's own icon outside the description panel and away from identified icons."""
    hay = gray_small(img)
    best = None
    for tpl in templates:
        res = cv2.matchTemplate(hay, tpl, cv2.TM_CCOEFF_NORMED)
        half = tpl.shape[0] / 2
        x0 = int((OWN_ICON_BOX[2] + 40) * SCALE)       # right of the description panel's icon column
        res[:, :x0] = -1
        _, val, _, (x, y) = cv2.minMaxLoc(res)
        cx, cy = (x + half) / SCALE, (y + half) / SCALE
        if val >= minimum and all(abs(cx - h["x"]) > 55 or abs(cy - h["y"]) > 55 for h in hits):
            if best is None or val > best["score"]:
                best = {"file": "", "score": round(float(val), 3), "x": int(cx), "y": int(cy)}
    return best


def analyse(path, lib):
    img = load(path)
    hits = [h for h in find_all(img, lib)
            if not (h["x"] < OWN_ICON_BOX[2] + 20 and h["y"] < OWN_ICON_BOX[3] + 20)]  # skip the panel's own icon
    roots = [h for h in hits if h["file"] == path.name]
    out = {"raiz_encontrada": bool(roots), "fabrica": [], "receita": [], "niveis": [], "acertos": hits}
    if not roots:
        # The root is drawn darkened with a padlock for items the player cannot buy yet: accept a weak match.
        weak = weak_root(img, lib[path.name], hits)
        if weak is None:
            return out
        out["raiz_fraca"] = weak["score"]
        roots = [weak]
    root = min(roots, key=lambda h: h["y"])  # topmost occurrence = tree root
    out["fabrica"] = sorted({h["file"] for h in hits if h["y"] < root["y"] - 40})
    below = sorted((h for h in hits if h["y"] > root["y"] + 40 and abs(h["x"] - root["x"]) < 700), key=lambda h: h["y"])
    rows = []
    for h in below:
        if rows and abs(h["y"] - rows[-1][0]["y"]) <= ROW_TOLERANCE:
            rows[-1].append(h)
        else:
            rows.append([h])
    out["niveis"] = [[h["file"] for h in sorted(r, key=lambda h: h["x"])] for r in rows]
    if rows:
        counts = {}
        for h in rows[0]:
            counts[h["file"]] = counts.get(h["file"], 0) + 1
        out["receita"] = [[f, n] for f, n in counts.items()]
    return out


def main():
    folder = Path(sys.argv[1])
    out_path = Path(sys.argv[2]) if len(sys.argv) > 2 and not sys.argv[2].startswith("--") else folder / "_receitas.json"
    debug = Path(sys.argv[sys.argv.index("--debug") + 1]) if "--debug" in sys.argv else None
    files = sorted(p for p in folder.glob("*.png") if not p.name.startswith("Aba"))
    lib = library(files)
    result = {}
    for i, f in enumerate(files, 1):
        result[f.name] = analyse(f, lib)
        print(f"[{i}/{len(files)}] {f.name}: receita={len(result[f.name]['receita'])} fabrica={len(result[f.name]['fabrica'])}"
              f"{'' if result[f.name]['raiz_encontrada'] else '  (raiz NÃO encontrada)'}", flush=True)
        if debug:
            debug.mkdir(parents=True, exist_ok=True)
            img = load(f)
            for h in result[f.name]["acertos"]:
                cv2.rectangle(img, (h["x"] - 45, h["y"] - 45), (h["x"] + 45, h["y"] + 45), (0, 255, 255), 2)
                cv2.putText(img, h["file"][:14], (h["x"] - 44, h["y"] + 60), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 255, 255), 1)
            cv2.imencode(".png", img)[1].tofile(str(debug / f.name))
    out_path.write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    print("->", out_path)


if __name__ == "__main__":
    main()
