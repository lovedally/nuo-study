# -*- coding: utf-8 -*-
"""测试 codex2api 网关：文本对话 + 多模态读图。结果写入 data/tmp/gw_test.txt"""
import base64
import json
import pathlib
import urllib.request
import urllib.error

ROOT = pathlib.Path(__file__).resolve().parents[1]
KEY = "sk-284ecdb11d6a7746fc694e485ae75969e0bcd0ab8ea0fa0efb562b433b1e7e37"
BASE = "https://www.codex2api.com/v1"
MODEL = "gpt-6.1-sol"
OUT = ROOT / "data" / "tmp" / "gw_test.txt"

log = []


def post(body):
    req = urllib.request.Request(
        BASE + "/chat/completions", data=json.dumps(body).encode(),
        headers={"Authorization": "Bearer " + KEY, "Content-Type": "application/json"},
        method="POST")
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return {"_http": e.code, "_body": e.read().decode(errors="replace")[:500]}
    except Exception as e:
        return {"_err": "%s: %s" % (type(e).__name__, e)}


# 1) 纯文本
r = post({"model": MODEL, "messages": [{"role": "user", "content": "reply with exactly: OK"}],
          "max_tokens": 20})
if r.get("_http") or r.get("_err"):
    log.append("TEXT FAIL: %s" % json.dumps(r, ensure_ascii=False)[:400])
else:
    log.append("TEXT OK: %s" % (r["choices"][0]["message"].get("content") or "")[:80])

# 2) 读图（教材第 9 页）
img = (ROOT / "data" / "english" / "pages_img" / "009.jpg").read_bytes()
b64 = base64.b64encode(img).decode()
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
r = post(body)
if r.get("_http") or r.get("_err"):
    log.append("VISION FAIL: %s" % json.dumps(r, ensure_ascii=False)[:400])
else:
    content = r["choices"][0]["message"].get("content") or ""
    usage = r.get("usage", {})
    log.append("VISION OK: %s | usage=%s" % (content[:200], json.dumps(usage)))

OUT.write_text("\n".join(log), encoding="utf-8")
print("written", OUT)
