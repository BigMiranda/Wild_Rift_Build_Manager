"""Crops each item's icon from its shop screenshot into the backend's seed folder.

Reads backend/src/main/resources/seed/loja_<patch>.yml (each item names its screenshot in `captura`) and writes
    backend/src/main/resources/seed/icones/<slug>.png   (one per item, 96x96)
    backend/src/main/resources/seed/icones/manifest.json   {"<item name>": "<file>"}
Evolutions (Fimbulwinter, Muramana...) are shown further down in their base item's screenshot: the first icon frame
below the description panel's own icon is used for them.

Usage (from the repository root):  python tools/capturas_icones.py [loja_7_3.yml] [capturas/7.3]
Needs:  opencv-python-headless, numpy, pyyaml
"""
import json
import re
import sys
import unicodedata
from pathlib import Path

import cv2
import numpy as np
import yaml

ROOT = Path(__file__).resolve().parent.parent
SEED = ROOT / "backend" / "src" / "main" / "resources" / "seed"
OUT = SEED / "icones"
FALLBACK_BOX = (16, 110, 96, 96)  # x, y, w, h of the icon in a full-size description panel
SIZE = 96


def slug(name):
    plain = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", "-", plain.lower()).strip("-")


def load(path):
    return cv2.imdecode(np.fromfile(str(path), dtype=np.uint8), cv2.IMREAD_COLOR)


def icon_box(img):
    """The description panel's icon frame: the square edge contour nearest the top-left corner."""
    region = img[:320, :220]
    edges = cv2.dilate(cv2.Canny(cv2.cvtColor(region, cv2.COLOR_BGR2GRAY), 40, 120), np.ones((3, 3), np.uint8))
    contours, _ = cv2.findContours(edges, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    squares = []
    for c in contours:
        x, y, w, h = cv2.boundingRect(c)
        if 80 <= w <= 115 and 80 <= h <= 115 and abs(w - h) <= 8 and y > 60:
            squares.append((x, y, w, h))
    if not squares:
        return FALLBACK_BOX, False
    return min(squares, key=lambda b: (b[1], b[0])), True


def evolution_box(img):
    """Icon frame of the evolution block: first square below the top icon, in the left (description) column."""
    region = img[:, :460]
    edges = cv2.dilate(cv2.Canny(cv2.cvtColor(region, cv2.COLOR_BGR2GRAY), 40, 120), np.ones((3, 3), np.uint8))
    contours, _ = cv2.findContours(edges, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    squares = []
    for c in contours:
        x, y, w, h = cv2.boundingRect(c)
        if 80 <= w <= 115 and 80 <= h <= 115 and abs(w - h) <= 8 and y > 400:
            squares.append((x, y, w, h))
    return min(squares, key=lambda b: b[1]) if squares else None


def crop(img, box):
    x, y, w, h = box
    return cv2.resize(img[y:y + h, x:x + w], (SIZE, SIZE), interpolation=cv2.INTER_AREA)


def main():
    catalog = SEED / (sys.argv[1] if len(sys.argv) > 1 else "loja_7_3.yml")
    data = yaml.safe_load(catalog.read_text(encoding="utf-8"))
    shots = ROOT / (sys.argv[2] if len(sys.argv) > 2 else f"capturas/{data['patch']}")
    OUT.mkdir(exist_ok=True)
    manifest, guessed = {}, []
    for name, item in data["itens"].items():
        if not item.get("captura"):
            continue
        img = load(shots / item["captura"])
        (x, y, w, h), found = icon_box(img)
        if not found:
            guessed.append(name)
        file = f"{slug(name)}.png"
        cv2.imencode(".png", crop(img, (x, y, w, h)))[1].tofile(str(OUT / file))
        manifest[name] = file
        evo = item.get("evolucao")
        if evo:
            box = evolution_box(img)
            if box is None:
                guessed.append(evo["nome"] + " (sem ícone: usa o do item base)")
                continue
            evo_file = f"{slug(evo['nome'])}.png"
            cv2.imencode(".png", crop(img, box))[1].tofile(str(OUT / evo_file))
            manifest[evo["nome"]] = evo_file
    (OUT / "manifest.json").write_text(json.dumps(dict(sorted(manifest.items())), indent=2, ensure_ascii=False),
                                       encoding="utf-8")
    print(f"{len(manifest)} ícones em {OUT}; posição padrão usada em: {guessed or 'nenhum'}")


if __name__ == "__main__":
    main()
