# -*- coding: utf-8 -*-
"""英语学习能力：语音合成 / 语音转写 / AI 对话 / 跟读评测。

这块能力原本是 `D:\\nuonuo_study` 里那个**独立的 Node 单文件服务**
（`sites/nuonuo-english-study-2026/server.mjs`，在 NAS 上跑 4173 端口、容器名
`nuonuo-english`）。2026-10-23 整体融合进本服务，理由都很实际：

  · 孩子端 App 原来要连两个地址（8092 学业 / 4173 英语），家里换个网络就得改两处；
  · 两套 .env、两个进程、两份日志、两条部署链路 —— 出问题时第一件事是判断「谁挂了」；
  · 英语网页和 App 用的是同一份数据目录，没必要各养一份缓存。

## 行为契约与老服务逐字对齐

网页前端 `web/english/app.js` 是编译产物，路径写死在里面（`/api/tts`、`/api/chat`、
`/api/evaluate`、`/api/transcribe`、`/api/health`），并且**失败时读的是 `result.error`**，
不是 FastAPI 默认的 `detail`。所以本模块：

  1. 老路径**原样保留**（api.py 把同一个 router 注册两次，见 `include_router`）；
  2. 报错一律 `{"error": "..."}`（见 [EnglishError] 的处理器）；
  3. 成功体字段（`url` / `text` / `audioUrl` / `result` / `services`）一个都不改名。

新增的是 `/api/v1/english/*` 这套规范路径 —— 安卓端和以后的新代码走它。
两条路径指向**同一份实现**，不存在「旧接口欠维护」这种情况。

## 四个别「顺手优化」掉的地方

  1. **千问 `max_tokens=180`**：三年级口语陪练只回一两句；给多了它会开始整段讲课文，
     孩子还没听懂上一句，屏幕已经滚过去三行。
  2. **腾讯智聆签名要对「原始值」排序后 HMAC-SHA1**，签完再做 URL 编码。
     先编码再签名 100% 报签名错误，且报错信息不会告诉你是这里错了。
  3. **评测音频必须是 16k 单声道 s16le PCM**；浏览器给的是 webm/opus，
     必须过一次 ffmpeg。少了这步智聆会返回一个「识别为空」的成功响应。
  4. **TTS 结果按 `sha256(voice|text)` 落盘缓存**：一节课会重复点同一句话几十次，
     不缓存既费钱又慢（首次 300–800ms，命中不到 5ms）。
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import re
import shutil
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from typing import Any

from fastapi import APIRouter
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel

from . import config

# 微软语音的输出格式。
#
# ⚠️ 96kbps → 48kbps（2026-10-23）：24kHz 采样的**语音**，48kbps 已经听不出差别，
#    而文件体积直接减半（一句课文 90KB → 45KB）。局域网带宽不是瓶颈，
#    但 MediaPlayer 需要先缓冲一段才能起播，文件小一半等于起播快一截。
# ⚠️ 改这一行会让**所有缓存失效**（缓存键里有它），必须重跑 tools/warm_tts.py，
#    否则全部退回 4 秒的冷合成。
AUDIO_FORMAT = "audio-24khz-48kbitrate-mono-mp3"

# ---------------------------------------------------------------- 错误
class EnglishError(Exception):
    """业务错误。http 决定状态码，message 会原样进 `{"error": ...}`。"""

    def __init__(self, message: str, http: int = 400):
        super().__init__(message)
        self.message, self.http = message, http


def _err(message: str, http: int = 400) -> JSONResponse:
    return JSONResponse(status_code=http, content={"error": message})


# ---------------------------------------------------------------- 通用
# 只有 64 位十六进制 + .mp3 的缓存文件才允许取 —— 这是防 ../ 穿越的**唯一**入口。
TTS_FILE_RE = re.compile(r"^[0-9a-f]{64}\.mp3$")
DATA_URI_RE = re.compile(r"^data:([^;]+);base64,(.+)$", re.S)

TUTOR_PROMPT = (
    "You are Nono's friendly British English speaking partner. Nono is a Chinese "
    "third-grade learner using the 2024 Yilin Press textbook. Use very simple "
    "CEFR pre-A1/A1 vocabulary (greetings, names, family, numbers, colours). "
    "Ask only one question at a time. Reply in one or two short "
    "sentences. Keep the conversation mainly in English. If Nono is stuck, give one "
    "short Chinese hint. Correct at most one grammar or word-choice issue per turn, "
    "gently. Do not score pronunciation; pronunciation is assessed by a separate "
    "engine. Avoid mature, frightening, commercial, political, or personal-data topics."
)


def _open(req: urllib.request.Request, timeout: float) -> bytes:
    """发一个请求并返回响应体。HTTPError 也在这里转成中文可读的 EnglishError。"""
    opener = urllib.request.build_opener(
        urllib.request.HTTPSHandler(context=ssl.create_default_context()))
    try:
        with opener.open(req, timeout=timeout) as r:
            return r.read()
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        # 上游把真正的错误原因放在 body 里，只回状态码等于让人去猜。
        raise EnglishError("上游服务返回 HTTP %s：%s" % (e.code, body[:300]), 502)
    except Exception as e:  # 超时 / DNS / 连接被重置
        raise EnglishError("连接上游服务失败（%s: %s）" % (type(e).__name__, e), 502)


def _post_json(url: str, payload: dict, headers: dict[str, str],
               timeout: float = 60) -> dict:
    req = urllib.request.Request(
        url, data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", **headers}, method="POST")
    raw = _open(req, timeout)
    try:
        return json.loads(raw.decode("utf-8"))
    except Exception:
        raise EnglishError("上游返回的不是 JSON：%s" % raw[:200].decode("utf-8", "replace"),
                           502)


def _decode_audio(value: Any) -> tuple[str, bytes]:
    """`data:audio/webm;base64,...` → (mime, 原始字节)。"""
    m = DATA_URI_RE.match(str(value or ""))
    if not m:
        raise EnglishError("录音数据格式无效")
    try:
        return m.group(1), base64.b64decode(m.group(2), validate=False)
    except Exception:
        raise EnglishError("录音数据无法解码")


def _audio_ext(mime: str) -> str:
    """与 Node 版完全一致的三分支映射，别加更多分支。

    多出来的分支没有意义：ffmpeg 本来就能靠内容嗅探容器格式，扩展名只影响临时文件名。
    """
    if "webm" in mime:
        return "webm"
    if "wav" in mime:
        return "wav"
    return "mp3"


def _xml_escape(value: str) -> str:
    return (str(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("'", "&apos;").replace('"', "&quot;"))


# ---------------------------------------------------------------- 1. 语音合成
def azure_tts(text: str, voice: str | None = None, rate: str | None = None,
              pitch: str | None = None) -> str:
    """文本 → `/generated-tts/<sha256>.mp3`（磁盘缓存，同一句只合成一次）。

    voice / rate / pitch 三个参数平时都用 config 里的值，只有
    tools/voice_samples.py 试听对比时会传不同组合（所以缓存键里必须带上它们，
    否则改了音色还会命中旧音频 —— 试听页会出现「六个选项听起来一样」）。
    """
    text = (text or "").strip()
    if not text or len(text) > 800:
        raise EnglishError("文本长度需为1至800字符")
    key = config.AZURE_SPEECH_KEY
    if not key:
        raise EnglishError("尚未配置 AZURE_SPEECH_KEY")

    voice = voice or config.AZURE_TTS_VOICE
    rate = rate if rate is not None else config.AZURE_TTS_RATE
    pitch = pitch if pitch is not None else config.AZURE_TTS_PITCH
    region = config.AZURE_SPEECH_REGION or "eastasia"
    # ⚠️ 缓存键必须把**所有影响音频内容的参数**都算进去，包括输出格式。
    #    少一个就会出现「改了参数但听起来没变」，而且因为命中缓存连日志都没有，
    #    极难往「缓存没失效」上想。（voice/rate/pitch 已经踩过一次，格式加进来同理。）
    cache_key = hashlib.sha256(
        ("%s|%s|%s|%s|%s" % (voice, rate, pitch, AUDIO_FORMAT, text)).encode("utf-8")).hexdigest()
    target = config.TTS_DIR / (cache_key + ".mp3")
    if target.exists() and target.stat().st_size > 0:
        return "/generated-tts/" + target.name

    config.TTS_DIR.mkdir(parents=True, exist_ok=True)
    # prosody 只在真的设了值时才写：空串会让 SSML 变成 rate=""，微软会直接拒。
    inner = _xml_escape(text)
    attrs = (" rate=\"%s\"" % _xml_escape(rate) if rate else "") + \
            (" pitch=\"%s\"" % _xml_escape(pitch) if pitch else "")
    body = ('<prosody%s>%s</prosody>' % (attrs, inner)) if attrs else inner
    ssml = ('<speak version="1.0" xml:lang="en-GB"><voice name="%s">%s</voice></speak>'
            % (_xml_escape(voice), body))
    req = urllib.request.Request(
        "https://%s.tts.speech.microsoft.com/cognitiveservices/v1" % region,
        data=ssml.encode("utf-8"),
        headers={
            "Ocp-Apim-Subscription-Key": key,
            "Content-Type": "application/ssml+xml",
            "X-Microsoft-OutputFormat": AUDIO_FORMAT,
            "User-Agent": "NuonuoEnglish/1.0",
        },
        method="POST")
    audio = _open(req, timeout=60)

    # 先写 .part 再 rename：合成到一半进程被杀，不会留下一个 0 字节的 mp3
    # 被后续请求当成「已缓存」而永远播不出声。
    part = target.with_suffix(".mp3.part")
    part.write_bytes(audio)
    part.replace(target)
    return "/generated-tts/" + target.name


# ---------------------------------------------------------------- 2. 语音转写
def _multipart(fields: list[tuple[str, str]],
               files: list[tuple[str, str, str, bytes]]) -> tuple[bytes, str]:
    """手搓 multipart/form-data —— 只为不为了一个上传接口引入 requests。"""
    boundary = "----nuonuo" + uuid.uuid4().hex
    out = bytearray()
    for name, value in fields:
        out += ("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n"
                % (boundary, name)).encode("utf-8")
        out += value.encode("utf-8") + b"\r\n"
    for name, filename, ctype, blob in files:
        out += ("--%s\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\n"
                "Content-Type: %s\r\n\r\n" % (boundary, name, filename, ctype)).encode("utf-8")
        out += blob + b"\r\n"
    out += ("--%s--\r\n" % boundary).encode("utf-8")
    return bytes(out), "multipart/form-data; boundary=" + boundary


def transcribe(audio_uri: str) -> str:
    """录音 → 英文文本。

    **千问 ASR 优先，OpenAI 兜底**；千问内部再分两层：官方 realtime WebSocket →
    chat/completions 单次识别。

    历史（别再走一遍）：老 Node 服务只实现了 OpenAI 那条，但 `.env` 里配的却是
    `QWEN_ASR_MODEL` / `QWEN_ASR_WS_URL` —— 配置和代码各说各话，于是
    `transcription` 长期是 false，看起来像「key 没配」，其实是那段代码不存在。
    """
    if config.QWEN_API_KEY:
        return _qwen_transcribe(audio_uri)
    if config.OPENAI_API_KEY:
        return _openai_transcribe(audio_uri)
    raise EnglishError(
        "尚未配置语音转写：在 .env 里配 QWEN_API_KEY（千问 ASR，推荐）"
        "或 OPENAI_API_KEY")


def _warn(message: str) -> None:
    """降级时留个痕。走 stderr，docker logs 里能看到；不打断请求。"""
    print("[english] " + message, file=sys.stderr, flush=True)


# realtime 的音频分包大小：3200 字节 = 16k 单声道 0.1 秒，和官方样例一致。
ASR_CHUNK = 3200


def _qwen_transcribe(audio_uri: str) -> str:
    """千问 ASR：按 `QWEN_ASR_ENGINE` 选引擎，**两个方向都互为兜底**。

    默认 `http`，原因是一条实测数据（2026-10-23，3.8 秒音频，同一个网关）：

        HTTP chat/completions   601ms
        realtime WebSocket     5945ms     ← 慢 10 倍

    慢不是实现问题：realtime 协议本来就是**按音频时长逐帧推**的（服务端要等
    声学特征按时间轴对齐才出终稿），3.8 秒语音天然就是 4~6 秒。它换来的价值是
    「边说边出字幕」——而我们的 /transcribe 是一次性请求/响应，中间结果全丢了。
    所以默认走 HTTP；哪天要做「实时字幕」再把 ENGINE 调成 realtime。
    """
    ws_ready = bool(config.QWEN_ASR_WS_URL and config.QWEN_ASR_MODEL)

    if config.QWEN_ASR_ENGINE == "realtime" and ws_ready:
        try:
            return _realtime_with_guards(audio_uri)
        except EnglishError as exc:
            _warn("realtime ASR 失败（%s），退回 HTTP 单次识别" % exc.message)
            return _qwen_transcribe_http(audio_uri, exc)

    try:
        return _qwen_transcribe_http(audio_uri, None)
    except EnglishError as exc:
        # HTTP 挂了（网关不支持 input_audio、格式不认等）→ 反向试 realtime。
        if not ws_ready:
            raise
        _warn("HTTP 单次识别失败（%s），改试 realtime" % exc.message)
        try:
            return _realtime_with_guards(audio_uri)
        except EnglishError as exc2:
            raise EnglishError("千问 ASR 两种方式都失败：http=%s / realtime=%s"
                               % (exc.message, exc2.message), 502)


def _realtime_with_guards(audio_uri: str) -> str:
    """realtime 前的两道前置检查：能不能转 PCM、音频是不是太长。"""
    try:
        pcm = _to_pcm(audio_uri)
    except EnglishError as exc:
        # 没有 ffmpeg / 音频解不开 —— HTTP 那条会退回「原始字节直接发」，
        # 说不定还能用，所以这里直接抛给上层去走 HTTP。
        raise EnglishError("转 PCM 失败：%s" % exc.message, 500)
    seconds = len(pcm) / 32000.0
    if seconds > config.QWEN_ASR_MAX_SECONDS:
        raise EnglishError("录音 %.0f 秒超过 realtime 单次上限（%.0f 秒）"
                           % (seconds, config.QWEN_ASR_MAX_SECONDS), 400)
    return _qwen_asr_realtime(pcm)


def _qwen_asr_realtime(pcm: bytes) -> str:
    """官方 realtime 协议（OpenAI-Realtime 风格事件）。

    流程与官方样例逐条对应：
      connect ?model=<realtime 模型>
      → session.update{modalities:[text], input_audio_format:pcm, sample_rate:16000,
                       input_audio_transcription{language}, turn_detection:None}
      → 等 session.updated（不等就发音频，服务端会当成「还没建会话」丢掉）
      → 每 3200 字节一包 input_audio_buffer.append(base64)
      → input_audio_buffer.commit（turn_detection=None 时**必须手动 commit**）
      → conversation.item.input_audio_transcription.text      ← 流式中间结果，文本在 stash
      → conversation.item.input_audio_transcription.completed ← 终稿，文本在 transcript

    ⚠️ `.text` 事件里的 `text` 字段是**空的**，字幕在 `stash` 里；终稿才叫 `transcript`。
       照名字猜会拿到一串空字符串，然后以为模型没识别出来。
    """
    try:
        from websockets.sync.client import connect
    except ImportError:
        raise EnglishError(
            "服务端缺少 websockets 库，千问 realtime ASR 不可用。请在 NAS 上执行 "
            "`pip install -r requirements.txt`（或部署时带 --install）。", 500)

    url = "%s?model=%s" % (config.QWEN_ASR_WS_URL, config.QWEN_ASR_MODEL)
    headers = {"Authorization": "Bearer " + config.QWEN_API_KEY,
               "OpenAI-Beta": "realtime=v1"}
    deadline = time.time() + len(pcm) / 32000.0 + 25
    partial, final = "", ""

    def eid() -> str:
        return "event_" + uuid.uuid4().hex[:12]

    try:
        with connect(url, additional_headers=headers, open_timeout=15,
                     close_timeout=5) as ws:
            ws.send(json.dumps({
                "event_id": eid(), "type": "session.update",
                "session": {
                    "modalities": ["text"],
                    "input_audio_format": "pcm",
                    "sample_rate": 16000,
                    "input_audio_transcription": {"language": config.QWEN_ASR_LANGUAGE},
                    # None = 不用服务端 VAD，由我们 commit 决定「说完了」
                    "turn_detection": None,
                },
            }))
            while True:
                msg = json.loads(ws.recv(timeout=15))
                kind = msg.get("type", "")
                if kind == "session.updated":
                    break
                if kind == "error":
                    raise EnglishError("千问 realtime ASR 建会话失败：%s"
                                       % json.dumps(msg, ensure_ascii=False)[:300], 502)

            for i in range(0, len(pcm), ASR_CHUNK):
                ws.send(json.dumps({
                    "event_id": eid(), "type": "input_audio_buffer.append",
                    "audio": base64.b64encode(pcm[i:i + ASR_CHUNK]).decode("ascii"),
                }))
                if config.QWEN_ASR_PACE > 0:
                    time.sleep(config.QWEN_ASR_PACE)
            ws.send(json.dumps({"event_id": eid(),
                                "type": "input_audio_buffer.commit"}))

            while time.time() < deadline:
                remaining = deadline - time.time()
                try:
                    msg = json.loads(ws.recv(timeout=max(0.5, remaining)))
                except Exception:
                    break
                kind = msg.get("type", "")
                if kind.endswith("input_audio_transcription.text"):
                    partial = msg.get("stash") or msg.get("text") or partial
                elif kind.endswith("input_audio_transcription.completed"):
                    final = msg.get("transcript") or partial
                    break
                elif kind == "error" or kind.endswith(".failed"):
                    raise EnglishError("千问 realtime ASR 出错：%s"
                                       % json.dumps(msg, ensure_ascii=False)[:300], 502)
    except EnglishError:
        raise
    except Exception as exc:
        raise EnglishError("千问 realtime ASR 连接失败（%s: %s）"
                           % (type(exc).__name__, exc), 502)

    text = (final or partial).strip()
    if not text:
        raise EnglishError("千问 realtime ASR 没有返回文本", 502)
    return text


def _asr_audio_uri(audio_uri: str) -> str:
    """HTTP 兜底路径用：统一转成 16k 单声道 wav。

    浏览器给的是 webm/opus、安卓大概率给 m4a/aac，而 chat/completions 这条
    对容器格式的支持参差不齐（mp3 实测可以，webm 没验证过）。转一次 wav 的代价
    是 100~300ms，换的是「不管客户端录成什么都能转」。
    ffmpeg 不可用时**退回原样发送**：至少 mp3 这类常见格式还能用。
    """
    mime, blob = _decode_audio(audio_uri)
    try:
        wav = _ffmpeg(audio_uri, ext=_audio_ext(mime),
                      args=["-ac", "1", "-ar", "16000", "-f", "wav"], out_ext="wav")
        return "data:audio/wav;base64," + base64.b64encode(wav).decode("ascii")
    except EnglishError:
        return "data:%s;base64,%s" % (mime, base64.b64encode(blob).decode("ascii"))


def _qwen_transcribe_http(audio_uri: str, first_error: "EnglishError | None") -> str:
    base = (config.QWEN_ASR_BASE_URL or config.QWEN_BASE_URL).rstrip("/")
    try:
        result = _post_json(
            base + "/chat/completions",
            {
                "model": config.QWEN_ASR_HTTP_MODEL,
                "messages": [{
                    "role": "user",
                    "content": [{
                        "type": "input_audio",
                        "input_audio": {"data": _asr_audio_uri(audio_uri)},
                    }],
                }],
                "stream": False,
            },
            {"Authorization": "Bearer " + config.QWEN_API_KEY},
        )
    except EnglishError as exc:
        # 两条都挂了 → 把第一条的原因也带上，否则只看到 HTTP 那条，会误以为
        # 「一直走的就是 HTTP」，排查方向直接跑偏。
        if first_error:
            raise EnglishError("千问 ASR 两种方式都失败：realtime=%s / http=%s"
                               % (first_error.message, exc.message), 502)
        raise
    choices = result.get("choices") or []
    text = ((choices[0] or {}).get("message") or {}).get("content") if choices else ""
    text = "" if text is None else str(text).strip()
    if not text:
        err = result.get("error")
        detail = err.get("message") if isinstance(err, dict) else None
        raise EnglishError("千问 ASR 没有返回文本%s" % ("：" + detail if detail else ""), 502)
    return text


def _openai_transcribe(audio_uri: str) -> str:
    key = config.OPENAI_API_KEY
    mime, blob = _decode_audio(audio_uri)
    ext = _audio_ext(mime)
    body, ctype = _multipart(
        fields=[
            ("model", config.OPENAI_TRANSCRIBE_MODEL),
            # prompt 用来把识别结果往「三年级教材话题」上锚一下，
            # 否则孩子说 Chinese-style English 时会被转成毫不相干的词。
            ("prompt", "A third-grade English learner discussing greetings, names, friends, "
                       "family, birthdays, numbers 1-10, and simple daily routines."),
        ],
        files=[("file", "speech." + ext, mime, blob)],
    )
    req = urllib.request.Request(
        "https://api.openai.com/v1/audio/transcriptions", data=body,
        headers={"Authorization": "Bearer " + key, "Content-Type": ctype},
        method="POST")
    result = _post_json_bytes(req)
    return str(result.get("text") or "")


def _post_json_bytes(req: urllib.request.Request) -> dict:
    raw = _open(req, timeout=90)
    try:
        return json.loads(raw.decode("utf-8"))
    except Exception:
        raise EnglishError("转写服务返回的不是 JSON：%s" % raw[:200].decode("utf-8", "replace"),
                           502)


# ---------------------------------------------------------------- 3. AI 对话
def qwen_chat(messages: list[dict]) -> tuple[str, str]:
    """返回 (回答文本, 该文本的 TTS 地址)。"""
    key = config.QWEN_API_KEY
    if not key:
        raise EnglishError("尚未配置 QWEN_API_KEY")
    clean = [
        {"role": "assistant" if m.get("role") == "assistant" else "user",
         "content": str(m.get("content") or "")[:1000]}
        for m in (messages or [])[-10:]
    ]
    result = _post_json(
        config.QWEN_BASE_URL.rstrip("/") + "/chat/completions",
        {
            "model": config.QWEN_CHAT_MODEL,
            "messages": [{"role": "system", "content": TUTOR_PROMPT}] + clean,
            "max_tokens": 180,
            "temperature": 0.6,
        },
        {"Authorization": "Bearer " + key},
    )
    choices = (result.get("choices") or [])
    text = ((choices[0] or {}).get("message") or {}).get("content") if choices else None
    if not text:
        # 与识别链路同一个教训：网关会在 200 里回错误体，别直接下标取。
        err = result.get("error")
        detail = err.get("message") if isinstance(err, dict) else None
        raise EnglishError("AI 没有返回可显示的回答%s" % ("：" + detail if detail else ""), 502)

    # TTS 失败**不让整个对话失败**：孩子能看到英文回复比「什么都弹不出来」有意义得多。
    try:
        audio_url = azure_tts(text)
    except EnglishError:
        audio_url = ""
    return text, audio_url


# ---------------------------------------------------------------- 4. 跟读评测（腾讯智聆）
def _ffmpeg(audio_uri: str, ext: str, args: list[str], out_ext: str) -> bytes:
    """浏览器录音 → 任意 ffmpeg 目标格式（PCM / wav 都用它）。

    统一到一个函数是因为这两个用途（智聆要 s16le PCM、千问 ASR 要 wav）本来就要
    同一套「临时文件 + 超时 + 清理」。分成两份迟早有一份忘了 unlink 而把 data/tmp 撑爆。
    """
    _, blob = _decode_audio(audio_uri)
    if not shutil.which(config.FFMPEG_BIN):
        raise EnglishError(
            "服务端缺少 %s，无法转换录音格式。容器镜像需要装 ffmpeg（见 deploy/Dockerfile）。"
            % config.FFMPEG_BIN, 500)
    config.TMP_DIR.mkdir(parents=True, exist_ok=True)
    token = uuid.uuid4().hex
    src = config.TMP_DIR / ("%s.%s" % (token, ext))
    dst = config.TMP_DIR / ("%s.%s" % (token, out_ext))
    try:
        src.write_bytes(blob)
        proc = subprocess.run(
            [config.FFMPEG_BIN, "-y", "-loglevel", "error", "-i", str(src)] + args + [str(dst)],
            capture_output=True, timeout=60)
        if proc.returncode != 0:
            raise EnglishError("录音转换失败：%s"
                               % proc.stderr.decode("utf-8", "replace")[:300])
        return dst.read_bytes()
    finally:
        for p in (src, dst):
            try:
                p.unlink()
            except OSError:
                pass


def _to_pcm(audio_uri: str) -> bytes:
    """任意浏览器录音 → 16k / 单声道 / s16le PCM（腾讯智聆要的就是这个）。"""
    return _ffmpeg(audio_uri, ext=_audio_ext(_decode_audio(audio_uri)[0]),
                   args=["-ac", "1", "-ar", "16000", "-f", "s16le"], out_ext="pcm")


def tencent_url(ref_text: str, voice_id: str) -> str:
    appid = config.TENCENT_APP_ID
    secret_id = config.TENCENT_SECRET_ID
    secret_key = config.TENCENT_SECRET_KEY
    if not (appid and secret_id and secret_key):
        raise EnglishError("腾讯智聆需要配置 TENCENT_APP_ID、TENCENT_SECRET_ID 和 "
                           "TENCENT_SECRET_KEY")
    timestamp = int(time.time())
    params = {
        "eval_mode": 1,
        "expired": timestamp + 3600,
        "nonce": uuid.uuid4().int % 10 ** 9,
        "rec_mode": 1,
        "ref_text": ref_text,
        "score_coeff": 1.0,
        "secretid": secret_id,
        "sentence_info_enabled": 1,
        "server_engine_type": "16k_en",
        "text_mode": 0,
        "timestamp": timestamp,
        "voice_format": 0,
        "voice_id": voice_id,
    }
    # ⚠️ 签名用**原始值**（不编码）拼 query；编码只用在最后拼 URL 那一步。
    keys = sorted(params)
    raw = "soe.cloud.tencent.com/soe/api/%s?%s" % (
        appid, "&".join("%s=%s" % (k, params[k]) for k in keys))
    signature = base64.b64encode(
        hmac.new(secret_key.encode("utf-8"), raw.encode("utf-8"),
                 hashlib.sha1).digest()).decode("ascii")
    query = "&".join("%s=%s" % (urllib.parse.quote(str(k), safe=""),
                                urllib.parse.quote(str(params[k]), safe=""))
                     for k in keys)
    return ("wss://soe.cloud.tencent.com/soe/api/%s?%s&signature=%s"
            % (appid, query, urllib.parse.quote(signature, safe="")))


def tencent_evaluate(ref_text: str, audio_uri: str) -> dict:
    ref_text = (ref_text or "").strip()
    if not ref_text or not (1 <= len(ref_text.split()) <= 30):
        raise EnglishError("跟读文本需为1至30个英文单词")
    pcm = _to_pcm(audio_uri)
    voice_id = str(uuid.uuid4())
    url = tencent_url(ref_text, voice_id)

    try:
        from websockets.sync.client import connect
    except ImportError:
        # 刻意不在模块顶层 import：没装 websockets 时，另外三个能力照常可用。
        raise EnglishError(
            "服务端缺少 websockets 库，跟读评测不可用。请在 NAS 上执行 "
            "`pip install -r requirements.txt`（或部署时带 --install）。", 500)

    latest: dict | None = None
    sent = False
    deadline = time.time() + 30
    try:
        with connect(url, open_timeout=10, close_timeout=3) as ws:
            while True:
                remaining = deadline - time.time()
                if remaining <= 0:
                    raise EnglishError("腾讯智聆评测超时", 504)
                message = ws.recv(timeout=remaining)
                try:
                    msg = json.loads(message)
                except Exception:
                    continue
                if msg.get("code") not in (0, None):
                    raise EnglishError("腾讯智聆错误 %s: %s"
                                       % (msg.get("code"), msg.get("message")), 502)
                # 握手成功的标志：message=success 且**没有** message_id
                # （带 message_id 的是中间分片，提前送音频会被直接丢弃）。
                if not sent and msg.get("message") == "success" and not msg.get("message_id"):
                    ws.send(pcm)
                    ws.send(json.dumps({"type": "end"}))
                    sent = True
                if "result" in msg:
                    result = msg["result"]
                    try:
                        latest = json.loads(result) if isinstance(result, str) else result
                    except Exception:
                        latest = None
                if msg.get("final") == 1:
                    break
    except EnglishError:
        raise
    except Exception as e:
        raise EnglishError("无法连接腾讯智聆评测服务（%s: %s）" % (type(e).__name__, e), 502)
    return latest or {"SuggestedScore": 0, "Words": []}


# ---------------------------------------------------------------- 路由
router = APIRouter()


class TtsIn(BaseModel):
    text: str = ""


class TranscribeIn(BaseModel):
    audio: str = ""


class ChatIn(BaseModel):
    messages: list[dict] = []


class EvaluateIn(BaseModel):
    text: str = ""
    audio: str = ""


@router.get("/health")
def health() -> dict:
    """四个能力各自到底有没有配好 —— App「AI 口语」页开头那句提示就靠它。

    ⚠️ 只报「配了没配」，**绝不含任何密钥片段**：这个接口是免鉴权的。
    """
    # 转写认两把 key 里任意一把（千问优先），所以这里不是单看 OPENAI_API_KEY。
    if config.QWEN_API_KEY:
        if (config.QWEN_ASR_ENGINE == "realtime" and config.QWEN_ASR_WS_URL
                and config.QWEN_ASR_MODEL):
            transcribe_engine = "qwen-realtime:" + config.QWEN_ASR_MODEL
        else:
            transcribe_engine = "qwen-http:" + config.QWEN_ASR_HTTP_MODEL
    elif config.OPENAI_API_KEY:
        transcribe_engine = "openai:" + config.OPENAI_TRANSCRIBE_MODEL
    else:
        transcribe_engine = ""
    return {
        "ok": True,
        "services": {
            "azureTts": bool(config.AZURE_SPEECH_KEY),
            "tencentSoe": bool(config.TENCENT_APP_ID and config.TENCENT_SECRET_ID
                               and config.TENCENT_SECRET_KEY),
            "qwenChat": bool(config.QWEN_API_KEY),
            # 千问 ASR 与对话共用一把 key，所以这两项通常一起为 true
            "transcription": bool(transcribe_engine),
        },
        "region": config.AZURE_SPEECH_REGION or "eastasia",
        "voice": config.AZURE_TTS_VOICE,
        "chatModel": config.QWEN_CHAT_MODEL,
        # 具体走哪个引擎 —— 「为什么 transcription 是 true」不用再去猜配置
        "transcribeEngine": transcribe_engine,
        "asrModel": (config.QWEN_ASR_MODEL if transcribe_engine.startswith("qwen-realtime")
                     else config.QWEN_ASR_HTTP_MODEL if transcribe_engine.startswith("qwen-http")
                     else config.OPENAI_TRANSCRIBE_MODEL if transcribe_engine else ""),
        "ffmpeg": bool(shutil.which(config.FFMPEG_BIN)),
    }


@router.post("/tts")
def tts_endpoint(body: TtsIn):
    return {"url": azure_tts(body.text)}


@router.post("/transcribe")
def transcribe_endpoint(body: TranscribeIn):
    return {"text": transcribe(body.audio)}


@router.post("/chat")
def chat_endpoint(body: ChatIn):
    text, audio_url = qwen_chat(body.messages)
    return {"text": text, "audioUrl": audio_url}


@router.post("/evaluate")
def evaluate_endpoint(body: EvaluateIn):
    return {"result": tencent_evaluate(body.text, body.audio)}


# 老网页里的 `url` 就是这个前缀（`/generated-tts/<sha>.mp3`），路径不能改。
media_router = APIRouter()


@media_router.get("/generated-tts/{name}")
def generated_tts(name: str):
    if not TTS_FILE_RE.match(name):
        return _err("Not found", 404)
    target = config.TTS_DIR / name
    if not target.is_file():
        return _err("Not found", 404)
    # 缓存文件的哈希键里已经含了 voice 与文本，内容永不变化 → 可以长缓存。
    return FileResponse(target, media_type="audio/mpeg",
                        headers={"Cache-Control": "private, max-age=31536000, immutable"})
