#!/usr/bin/env python3
"""Small HWPX visual smoke checker.

The checker inspects HWPX XML and BinData directly. It is intentionally generic:
callers choose a logical page number, optional z-order, and minimum pixel size.

Example:
  python3 scripts/visual_regression/check_hwpx_image_smoke.py out.hwpx \
    --logical-page 21 --z-order 155 --min-width 150 --min-height 150
"""

import argparse
import re
import sys
import tempfile
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

from PIL import Image


NS = {
    "hp": "http://www.hancom.co.kr/hwpml/2011/paragraph",
    "hc": "http://www.hancom.co.kr/hwpml/2011/core",
}


def iter_page_paragraphs(root):
    page = 1
    paras = []
    for para in root.findall("hp:p", NS):
        paras.append(para)
        if para.find(".//hp:secPr", NS) is not None:
            yield page, paras
            page += 1
            paras = []
    if paras:
        yield page, paras


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("hwpx")
    ap.add_argument("--logical-page", type=int, required=True)
    ap.add_argument("--z-order", type=str)
    ap.add_argument("--min-width", type=int, default=1)
    ap.add_argument("--min-height", type=int, default=1)
    args = ap.parse_args()

    hwpx = Path(args.hwpx)
    if not hwpx.exists():
        raise SystemExit(f"HWPX not found: {hwpx}")

    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)
        with zipfile.ZipFile(hwpx) as z:
            z.extractall(tmp)
        section_paths = sorted((tmp / "Contents").glob("section*.xml"),
                               key=lambda p: int(re.search(r"section(\d+)\.xml", p.name).group(1)))
        found = []
        for section_path in section_paths:
            root = ET.parse(section_path).getroot()
            for page, paras in iter_page_paragraphs(root):
                if page != args.logical_page:
                    continue
                for para in paras:
                    for pic in para.findall(".//hp:pic", NS):
                        if args.z_order is not None and pic.get("zOrder") != args.z_order:
                            continue
                        img = pic.find(".//hc:img", NS)
                        if img is None:
                            continue
                        bid = img.get("binaryItemIDRef")
                        image_path = tmp / "BinData" / f"{bid}.png"
                        if not image_path.exists():
                            continue
                        with Image.open(image_path) as im:
                            w, h = im.size
                        found.append((bid, pic.get("zOrder"), w, h))

        passing = [r for r in found if r[2] >= args.min_width and r[3] >= args.min_height]
        if passing:
            for bid, z, w, h in passing:
                print(f"PASS {bid} z={z} size={w}x{h}")
            return

        detail = ", ".join(f"{bid}:z={z}:{w}x{h}" for bid, z, w, h in found) or "none"
        print(f"FAIL no matching image >= {args.min_width}x{args.min_height}; found {detail}",
              file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
