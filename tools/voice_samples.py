#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""生成「发音试听页」：同一句话用不同音色/语速各合成一遍，摆在一起听。

2026-10-25 起后端换成讯飞在线合成（原 Azure 的音色名全部失效）。
⚠️ 讯飞的发音人是**按应用授权**的：当前只授权了 xiaoyan，
其他候选（x_Catherine / x_John / x4_EnUk_Ashleigh_assist 等）合成会报
11200 licc failed —— 亮哥在讯飞控制台「发音人授权管理」添加后重跑本脚本即可。

用法（本机）：

  .venv python tools/voice_samples.py "She wanted to try her best."

产物：
  web/english/voice-samples/*.mp3   各组合的音频
  web/english/voice-samples/index.html  试听页（带当前配置标记）
"""
import html
import pathlib
import sys
from urllib.parse import unquote, urlparse

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from server import config, english  # noqa: E402

SENTENCE = (sys.argv[1] if len(sys.argv) > 1
            else "She wanted to try her best. It was her first school play.")
OUT = config.ENGLISH_WEB_DIR / "voice-samples"
OUT.mkdir(parents=True, exist_ok=True)

# 组合：先放「当前线上用的」，再放备选。语速 0~100，50 = 正常。
_CUR = (config.XFYUN_TTS_VOICE, config.XFYUN_TTS_SPEED)
VARIANTS = [
    ("current", "① 现在线上用的：%s · 语速 %s" % _CUR, *_CUR),
    ("xiaoyan-normal", "② xiaoyan · 语速 50（标准）", "xiaoyan", "50"),
    ("xiaoyan-slow", "③ xiaoyan · 语速 40（更慢，跟读用）", "xiaoyan", "40"),
    # ↓ 这些要先在讯飞控制台授权发音人才能出声，授权前会报 11200
    ("catherine", "④ x_Catherine（英语女声，需控制台授权）", "x_Catherine", "50"),
    ("john", "⑤ x_John（英语男声，需控制台授权）", "x_John", "50"),
    ("ashleigh", "⑥ x4_EnUk_Ashleigh_assist（英音，需控制台授权）",
     "x4_EnUk_Ashleigh_assist", "50"),
]

rows = []
for slug, label, voice, speed in VARIANTS:
    try:
        url = english.xfyun_tts(SENTENCE, voice=voice, speed=speed)
    except Exception as exc:                      # noqa: BLE001
        print("失败 %s: %s" % (slug, exc), flush=True)
        continue
    file_name = unquote(urlparse(url).path).rsplit("/", 1)[-1]
    target = OUT / ("%s.mp3" % slug)
    src = config.TTS_DIR / file_name
    target.write_bytes(src.read_bytes())
    rows.append((slug, label, voice, speed,
                 "/english/voice-samples/%s.mp3" % slug))
    print("ok %-12s %s" % (slug, label), flush=True)

page = """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<title>发音试听 · 诺诺英语</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
body{margin:0;padding:32px;background:#f7f4ea;color:#24352e;font-family:"Microsoft YaHei",system-ui,sans-serif}
h1{font:800 26px Georgia;margin:0 0 6px}p.lead{color:#6b7a72;margin:0 0 26px}
.item{background:#fff;border-radius:16px;padding:16px 20px;margin-bottom:12px;display:flex;gap:18px;align-items:center;flex-wrap:wrap}
.item b{font-size:15px}
.meta{color:#8a9a92;font-size:12px;margin-top:4px}
audio{flex:1;min-width:260px}
.tag{background:#2d725e;color:#fff;border-radius:99px;padding:3px 10px;font-size:11px}
a{color:#2d725e}
</style></head><body>
<h1>发音试听</h1>
<p class="lead">同一句：《%s》—— 听完告诉我用哪一版，改 <code>XFYUN_TTS_VOICE</code> /
<code>XFYUN_TTS_SPEED</code> 就行。<br>
<span style="color:#a05a2c">讯飞发音人按应用授权：未授权的候选会静默失败，控制台添加后重跑本脚本。</span><br>
<a href="/english/">← 回英语主页</a></p>
%s
</body></html>""" % (
    html.escape(SENTENCE),
    "\n".join(
        '<div class="item"><div><b>%s</b>%s<div class="meta">%s · speed=%s</div></div>'
        '<audio controls preload="metadata" src="%s"></audio></div>'
        % (html.escape(label), ' <span class="tag">在用</span>' if slug == "current" else "",
           html.escape(voice), html.escape(speed), path)
        for slug, label, voice, speed, path in rows),
)
(OUT / "index.html").write_text(page, encoding="utf-8")

# 自检：页面里引用的音频必须**真的能取到**。
# 上一版就是「页面生成成功、音频 404」，截图看不出来（播放器只显示 0:00）——
# 所以这里直接把每个 src 打出来，部署脚本/人眼一看就知道有没有错。
print("--- 页面引用的音频 ---")
for slug, label, voice, speed, path in rows:
    print("  %s  %s  %d KB" % (path, "存在" if (OUT / ("%s.mp3" % slug)).exists() else "缺失",
                               (OUT / ("%s.mp3" % slug)).stat().st_size // 1024))
print("试听页：/english/voice-samples/  共 %d 组" % len(rows))
