# -*- coding: utf-8 -*-
"""把教材页图压缩到适合多模态网关的尺寸（900-1000px，q80）。

原图 ~1MB/张直传会撞 Cloudflare 524 超时；实测 900px/q80 可稳定读图。
用法：python tools/shrink_pages.py [宽度]
输出：data/english/pages_img_s/NNN.jpg
"""
import pathlib
import sys

from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "data" / "english" / "pages_img"
DST = ROOT / "data" / "english" / "pages_img_s"
DST.mkdir(parents=True, exist_ok=True)
WIDTH = int(sys.argv[1]) if len(sys.argv) > 1 else 900

n = 0
for p in sorted(SRC.glob("*.jpg")):
    out = DST / p.name
    if out.exists() and out.stat().st_size > 10000:
        continue
    img = Image.open(p)
    img = img.resize((WIDTH, int(img.height * WIDTH / img.width)))
    img.convert("RGB").save(out, "JPEG", quality=80)
    n += 1
print("压缩 %d 张 -> %s" % (n, DST))
