# -*- coding: utf-8 -*-
"""HTTP 全链路冒烟：health / tts / evaluate（走真实 8092 服务）。"""
import base64
import json
import sys
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

BASE = "http://127.0.0.1:8092"


def post(path, payload):
    req = urllib.request.Request(
        BASE + path, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"}, method="POST")
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            body = json.loads(r.read().decode())
            return r.status, body, time.time() - t0
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode()), time.time() - t0


with urllib.request.urlopen(BASE + "/api/health", timeout=10) as r:
    print("health:", r.read().decode())

code, body, dt = post("/api/tts", {"text": "Good morning, class!"})
print("tts: http=%s %.2fs url=%s" % (code, dt, body.get("url") or body))
if body.get("url"):
    with urllib.request.urlopen(BASE + body["url"], timeout=10) as r:
        print("  audio fetch: %d bytes, %s" % (r.headers and len(r.read()), r.headers.get("Content-Type")))

# 用讯飞合成一段 16k wav 当「孩子的跟读录音」，走完整 _to_pcm → ISE 链路
from server import config, english  # noqa: E402
from websockets.sync.client import connect  # noqa: E402

SENT = "Hello, I am Nono."
req = {
    "common": {"app_id": config.XFYUN_APPID},
    "business": {"aue": "raw", "vcn": config.XFYUN_TTS_VOICE,
                 "auf": "audio/L16;rate=16000", "speed": 50, "volume": 50, "pitch": 50},
    "data": {"status": 2, "text": base64.b64encode(SENT.encode()).decode()},
}
pcm = bytearray()
with connect(english.TTS_WS_URL, additional_headers={"x-api-key": config.XFYUN_APIPASSWORD},
             open_timeout=10) as ws:
    ws.send(json.dumps(req))
    while True:
        msg = json.loads(ws.recv(timeout=30))
        d = msg.get("data") or {}
        if d.get("audio"):
            pcm += base64.b64decode(d["audio"])
        if d.get("status") == 2:
            break

import wave  # noqa: E402
wav_path = Path(config.TMP_DIR)
wav_path.mkdir(parents=True, exist_ok=True)
wav_file = wav_path / "_smoke_eval.wav"
with wave.open(str(wav_file), "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(16000)
    w.writeframes(bytes(pcm))
data_uri = "data:audio/wav;base64," + base64.b64encode(wav_file.read_bytes()).decode()

code, body, dt = post("/api/evaluate", {"text": SENT, "audio": data_uri})
print("evaluate(句子): http=%s %.2fs" % (code, dt))
print(json.dumps(body, ensure_ascii=False, indent=1))

code, body, dt = post("/api/v1/english/evaluate", {"text": "book", "audio": data_uri})
print("evaluate(单词,规范路径): http=%s %.2fs" % (code, dt))
print(json.dumps(body, ensure_ascii=False))
wav_file.unlink(missing_ok=True)
