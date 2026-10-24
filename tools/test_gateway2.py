# -*- coding: utf-8 -*-
"""压缩页图后重测网关读图。"""
import base64
import io
import json
import pathlib
import urllib.request
import urllib.error

from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parents[1]
KEY = "sk-284ecdb11d6a7746fc694e485ae75969e0bcd0ab8ea0fa0efb562b433b1e7e37"
BASE = "https://www.codex2api.com/v1"
MODEL = "gpt-6.1-sol"
OUT = ROOT / "data" / "tmp" / "gw_test2.txt"

src = Image.open(ROOT / "data" / "english" / "pages_img" / "009.jpg")
log = []
for width, q in [(900, 80), (700, 75)]:
    img = src.resize((width, int(src.height * width / src.width)))
    buf = io.BytesIO()
    img.convert("RGB").save(buf, "JPEG", quality=q)
    b64 = base64.b64encode(buf.getvalue()).decode()
    body = {
        "model": MODEL,
        "messages": [{"role": "user", "content": [
            {"type": "text", "text": "这是英语教材的一页。只输出一个 JSON 对象："
             '{"page": 页脚页码数字, "section": "板块名", "title": "页面主标题", '
             '"first_line": "图中第一句英文原文"}，不要其他文字。'},
            {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + b64}},
        ]}],
        "max_tokens": 300,
    }
    req = urllib.request.Request(
        BASE + "/chat/completions", data=json.dumps(body).encode(),
        headers={"Authorization": "Bearer " + KEY, "Content-Type": "application/json"},
        method="POST")
    try:
        with urllib.request.urlopen(req, timeout=240) as r:
            j = json.loads(r.read().decode())
        log.append("w%d q%d OK %dKB: %s" % (width, q, len(b64) // 1024,
                   (j["choices"][0]["message"].get("content") or "")[:220]))
    except urllib.error.HTTPError as e:
        log.append("w%d q%d HTTP %d: %s" % (width, q, e.code, e.read().decode(errors="replace")[:200]))
    except Exception as e:
        log.append("w%d q%d ERR %s: %s" % (width, q, type(e).__name__, str(e)[:150]))

OUT.write_text("\n".join(log), encoding="utf-8")
print("\n".join(log))
