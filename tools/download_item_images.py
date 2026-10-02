"""Downloads item icons once so the app works offline.

Reads backend/src/main/resources/seed/items_<patch>.yml and writes, next to it:
  images/<slug>.<ext>   one icon per base item name (variants such as "X (Passive)" share the icon)
  images/manifest.json  {"<base item name>": "<file name>"}

For each item it tries, in order: the official Wild Rift wiki icon built from the item name, then the image URL of
the reference data set. Some hosts (Fandom/wikia) refuse direct downloads, hence the fallback.

Usage (from the repository root):  python tools/download_item_images.py [items_7_3.yml]
Only needs the Python standard library + PyYAML.
"""
import json
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path

import yaml

SEED = Path(__file__).resolve().parent.parent / "backend" / "src" / "main" / "resources" / "seed"
OUT = SEED / "images"
EXT_BY_TYPE = {"image/png": "png", "image/webp": "webp", "image/jpeg": "jpg", "image/gif": "gif"}


def base_name(name: str) -> str:
    """'Amaranth's Twinguard (Endurance)' -> 'Amaranth's Twinguard'"""
    return re.sub(r"\s*\(.*\)\s*$", "", name).strip()


def slug(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")


def wr_wiki_url(name: str) -> str:
    file = urllib.parse.quote(name.replace(" ", "_") + "_WR_item.png")
    return f"https://wiki.leagueoflegends.com/en-us/images/thumb/{file}/80px-{file}"


def fetch(url: str):
    req = urllib.request.Request(url, headers={"User-Agent": "curl/8.0"})
    try:
        with urllib.request.urlopen(req, timeout=20) as res:
            ctype = res.headers.get_content_type()
            if res.status == 200 and ctype in EXT_BY_TYPE:
                return res.read(), EXT_BY_TYPE[ctype]
    except Exception:  # noqa: BLE001 - any failure just means "try the next source"
        pass
    return None


def main():
    items_file = SEED / (sys.argv[1] if len(sys.argv) > 1 else "items_7_3.yml")
    items = yaml.safe_load(items_file.read_text(encoding="utf-8"))
    OUT.mkdir(exist_ok=True)
    manifest_path = OUT / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {}

    # first reference URL seen for each base name
    sources = {}
    for item in items:
        sources.setdefault(base_name(item["name"]), item.get("image"))

    missing = []
    for name, ref_url in sorted(sources.items()):
        if name in manifest and (OUT / manifest[name]).exists():
            continue
        candidates = [wr_wiki_url(name)] + ([ref_url] if ref_url else [])
        for url in candidates:
            got = fetch(url)
            if got:
                data, ext = got
                file = f"{slug(name)}.{ext}"
                (OUT / file).write_bytes(data)
                manifest[name] = file
                print(f"ok   {name} <- {url}")
                break
        else:
            missing.append(name)
            print(f"FAIL {name}")

    manifest_path.write_text(json.dumps(dict(sorted(manifest.items())), indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"\n{len(manifest)} icons in {OUT}; {len(missing)} missing: {missing}")


if __name__ == "__main__":
    main()
