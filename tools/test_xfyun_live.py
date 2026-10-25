# -*- coding: utf-8 -*-
"""直连讯飞真云实测 TTS 与 ISE 评测（重启服务前先在这里把协议跑通）。"""
import base64
import json
import sys
import wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from server import config, english

print("== 1. TTS ==")
url = english.xfyun_tts("Hello, I am Nono. Nice to meet you.")
f = config.TTS_DIR / Path(url).name
print("url:", url, "size:", f.stat().st_size)

print("== 2. 造评测用 PCM（直接向讯飞要 16k raw，绕开本机没有 ffmpeg）==")
from websockets.sync.client import connect  # noqa: E402
import hashlib, hmac  # noqa: E402
import urllib.parse  # noqa: E402
from email.utils import formatdate  # noqa: E402

TEXT = "book"
req = {
    "common": {"app_id": config.XFYUN_APPID},
    "business": {"aue": "raw", "vcn": config.XFYUN_TTS_VOICE,
                 "auf": "audio/L16;rate=16000", "speed": 50, "volume": 50, "pitch": 50},
    "data": {"status": 2, "text": base64.b64encode(TEXT.encode()).decode()},
}
pcm = bytearray()
with connect(english.TTS_WS_URL, additional_headers={"x-api-key": config.XFYUN_APIPASSWORD},
             open_timeout=10) as ws:
    ws.send(json.dumps(req))
    while True:
        msg = json.loads(ws.recv(timeout=30))
        assert msg.get("code", -1) == 0, msg
        d = msg.get("data") or {}
        if d.get("audio"):
            pcm += base64.b64decode(d["audio"])
        if d.get("status") == 2:
            break
print("pcm bytes:", len(pcm), "= %.2fs" % (len(pcm) / 32000.0))

# 包一个 wav 头走 _to_pcm 的 ffmpeg 路径 —— 但本机可能没有 ffmpeg，
# 所以评测测试直接对 PCM 做：monkeypatch _to_pcm 返回这份 pcm。
wav_path = Path(__file__).parent / "_eval_sample.wav"
with wave.open(str(wav_path), "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(16000)
    w.writeframes(bytes(pcm))

print("== 3. ISE 评测（单词题型）==")
english._to_pcm = lambda uri: pcm  # 跳过 ffmpeg
result = english.xfyun_evaluate(TEXT, "data:audio/wav;base64," + base64.b64encode(bytes(pcm)).decode())
print(json.dumps(result, ensure_ascii=False))

print("== 4. ISE 评测（句子题型）==")
req2 = dict(req)
req2["data"] = {"status": 2, "text": base64.b64encode(b"This is my book.").decode()}
pcm2 = bytearray()
with connect(english.TTS_WS_URL, additional_headers={"x-api-key": config.XFYUN_APIPASSWORD},
             open_timeout=10) as ws:
    ws.send(json.dumps(req2))
    while True:
        msg = json.loads(ws.recv(timeout=30))
        assert msg.get("code", -1) == 0, msg
        d = msg.get("data") or {}
        if d.get("audio"):
            pcm2 += base64.b64decode(d["audio"])
        if d.get("status") == 2:
            break
result2 = english.xfyun_evaluate("This is my book",
                                 "data:audio/wav;base64," + base64.b64encode(bytes(pcm2)).decode())
english._to_pcm = lambda uri: bytes(pcm2)
result2 = english.xfyun_evaluate("This is my book", "ignored")
print(json.dumps(result2, ensure_ascii=False))
print("ALL OK")
