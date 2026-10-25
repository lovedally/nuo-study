# -*- coding: utf-8 -*-
"""配置：从 .env 读取，全部路径以项目根为基准（避免受 cwd 影响）。"""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENV_FILE = ROOT / ".env"


def load_env(path: Path) -> dict:
    """极简 dotenv。容忍 KEY="VALUE" 的引号写法。"""
    env: dict[str, str] = {}
    if not path.exists():
        return env
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        v = v.strip()
        if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
            v = v[1:-1]
        env[k.strip()] = v
    return env


ENV = load_env(ENV_FILE)


def env(key: str, default: str = "") -> str:
    """优先环境变量，其次 .env 文件（便于 Docker/CI 覆盖）。"""
    import os

    return os.environ.get(key) or ENV.get(key) or default


def _env_opt(key: str, default: str = "") -> str:
    """跟 [env] 一样，但**显式留空就算留空**（不回落默认值）。

    专门给「留空 = 关掉」这类开关用（比如 TTS 的语速/音调）。
    `env()` 的语义是「空 == 没设置」，对路径和密钥是对的，对开关是错的。
    """
    import os

    if key in os.environ:
        return os.environ[key]
    if key in ENV:
        return ENV[key]
    return default


def _path(key: str, default: Path) -> Path:
    v = env(key)
    if not v:
        return default
    p = Path(v)
    return p if p.is_absolute() else (ROOT / p)


# ---------------------------------------------------------------- 路径
SCHEMA_FILE = ROOT / "db" / "schema.sql"
PROMPT_FILE = ROOT / "docs" / "prompts" / "homework_extract.v1.md"
DB_FILE = _path("APP_DB", ROOT / "data" / "app.db")
IMAGE_DIR = _path("IMAGE_DIR", ROOT / "data" / "images")
# ---------------------------------------------------------------- 学生端实时画面
# 镜像帧的落盘目录（只存每台设备的最新一帧，见 mirror.py）
FRAME_DIR = _path("FRAME_DIR", ROOT / "data" / "frames")

# adb 抓整屏通道（见 mirror.py 的 AdbGrabber）。这一路是**服务端主动**去平板截屏，
# 与孩子端 App 自己的 PixelCopy 通道并存，家长端可切换看哪一条。
#   ADB_TARGET 留空 = 该通道整体关闭（家长端切过去会得到明确提示，而不是空等）。
# ⚠️ 平板地址写进配置是必然的妥协：服务端没法自动发现「哪台设备是孩子的平板」。
#    家用 DHCP 一般会一直给同一个地址；换了地址就改这里并重启服务。
ADB_BIN = env("ADB_BIN", str(ROOT / ".tools" / "platform-tools" / "adb"))
ADB_TARGET = env("ADB_TARGET", "")
ADB_INTERVAL = float(env("ADB_INTERVAL", "3"))        # 抓帧间隔（秒）
ADB_IDLE_STOP = float(env("ADB_IDLE_STOP", "45"))     # 多久没人取帧就停掉抓帧线程（秒）
ADB_MAX_WIDTH = int(env("ADB_MAX_WIDTH", "900"))      # 服务端缩图宽度，0 = 不缩
ADB_JPEG_QUALITY = int(env("ADB_JPEG_QUALITY", "78"))

# ---------------------------------------------------------------- 认证
FAMILY_TOKEN = env("FAMILY_TOKEN", "dev-family-token-change-me")

# ---------------------------------------------------------------- 模型
VISION_BASE_URL = env("VISION_BASE_URL", "").rstrip("/")
VISION_API_KEY = env("VISION_API_KEY", "")
VISION_MODEL = env("VISION_MODEL", "")
VISION_MAX_TOKENS = int(env("VISION_MAX_TOKENS", "32000"))
VISION_TIMEOUT = int(env("VISION_TIMEOUT", "180"))
# auto / on / off。off 实际发送 reasoning_effort="none"
VISION_THINKING = env("VISION_THINKING", "off")
PROMPT_VERSION = "hw-extract-v1"

# ---------------------------------------------------------------- 英语（2026-10-23 融合）
# 这块能力原本跑在**另一个服务**上：`D:\nuonuo_study` 那个 Node 单文件服务，
# NAS 上 4173 端口、容器名 nuonuo-english。融合后跟着本服务一起起停，
# 目录也统一收到 data/ 下 —— 备份 data/ 就把 TTS 缓存一起备了，不用记两处。
ENGLISH_WEB_DIR = _path("ENGLISH_WEB_DIR", ROOT / "web" / "english")
# 教材内容（单元 / 单词 / 课文 / 练习）。放 web 目录下是为了让「英语网页」和
# 「课程数据」一起部署 —— 少一个同步目标就少一次「网页更新了但数据没更新」。
CURRICULUM_FILE = _path("CURRICULUM_FILE", ENGLISH_WEB_DIR / "curriculum.json")
TTS_DIR = _path("TTS_DIR", ROOT / "data" / "tts")
# ffmpeg 转 16k PCM 的中转目录。刻意跟 data/tmp 分开：那个目录是识别链路的暂存，
# 有自己的清理节奏，混在一起会让「谁在漏文件」变得难查。
TMP_DIR = _path("ENGLISH_TMP_DIR", ROOT / "data" / "tmp")

# 讯飞开放平台（2026-10-25 起替换 Azure TTS 与腾讯智聆评测）。
# 凭据与国美云 xiaokouyu 是**同一个应用**（APPID 2016284b）：
#   · 在线语音合成 → 点读 / 对话朗读（wss://tts-api.xfyun.cn/v2/tts）
#   · 语音评测流式版 → 跟读打分（wss://ise-api.xfyun.cn/v2/open-ise）
# ⚠️ TTS 的鉴权与另外两个不同：WebSocket 握手头 `x-api-key` 传 **APIPassword**，
#    APIKey/APISecret 只给评测用。三个服务缺哪个，对应功能灰置，互不影响。
# ⚠️ 该应用目前只授权了 xiaoyan 一个合成发音人（实测 x4_enus_lucy_education /
#    x_Catherine / x_John 均报 11200 licc failed）。想换英语发音人要先在讯飞
#    控制台「发音人授权管理」添加，改这里即可 —— 缓存键含 voice，不会串音。
XFYUN_APPID = env("XFYUN_APPID", "")
XFYUN_APIKEY = env("XFYUN_APIKEY", "")
XFYUN_APISECRET = env("XFYUN_APISECRET", "")
XFYUN_APIPASSWORD = env("XFYUN_APIPASSWORD", "")
XFYUN_TTS_VOICE = env("XFYUN_TTS_VOICE", "xiaoyan")
# 语速 0~100，50 = 正常。留空有意义（= 用服务端默认），所以走 _env_opt。
XFYUN_TTS_SPEED = _env_opt("XFYUN_TTS_SPEED", "50")

# 千问：**只**负责生成陪练对话文本（阿里云百炼的 OpenAI 兼容接口）
QWEN_API_KEY = env("QWEN_API_KEY", "")
QWEN_BASE_URL = env("QWEN_BASE_URL",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1").rstrip("/")
QWEN_CHAT_MODEL = env("QWEN_CHAT_MODEL", "qwen-flash")

# 语音转写（自由对话的录音）。**三条路，按优先级**：
#   1. 千问 realtime WebSocket（官方协议，流式）—— 见 english.py 的 _qwen_asr_realtime
#   2. 千问 chat/completions 单次识别（无 WS 时的兜底）
#   3. OpenAI 转写（只有千问完全没配时才用）
# 都不配也不影响跟读评测（那是腾讯智聆，另一条链路）。
#
# ⚠️ 老 .env 里的 `QWEN_ASR_WS_URL` 曾经是**死配置**（老 Node 代码从没读过它），
#    现在真的用上了：把它指向你的网关，否则默认走阿里云官方域名。
# 用哪个引擎认：`http`（默认）或 `realtime`。实测 3.8 秒音频 http=601ms、
# realtime=5945ms —— realtime 按音频时长逐帧推，天然慢一个数量级，它换来的是
# 「边说边出字幕」。我们是一次性请求/响应，用不上，所以默认 http。
# 想做实时字幕再把它改成 realtime（两条路都会互相兜底，改错配置不会导致不可用）。
QWEN_ASR_ENGINE = env("QWEN_ASR_ENGINE", "http")
QWEN_ASR_WS_URL = env("QWEN_ASR_WS_URL", "wss://dashscope.aliyuncs.com/api-ws/v1/realtime")
# realtime 模型名（带 -realtime 后缀）。留空 = 不用 WS，直接走 HTTP 单次识别。
QWEN_ASR_MODEL = env("QWEN_ASR_MODEL", "qwen3-asr-flash-realtime")
# HTTP 兜底用的模型名 —— 注意**不是**带 -realtime 的那个：实测网关对
# qwen3-asr-flash-realtime 发 chat/completions 只回 {"status_name":"Success"}，没有文本。
QWEN_ASR_HTTP_MODEL = env("QWEN_ASR_HTTP_MODEL", "qwen3-asr-flash")
# 语种提示。孩子说的是英语，给 "en" 能明显减少「中文谐音」式的误识别。
QWEN_ASR_LANGUAGE = env("QWEN_ASR_LANGUAGE", "en")
# 非 VAD 模式单次提交的音频上限（官方建议累加不超过 60s）。超了改走 HTTP。
QWEN_ASR_MAX_SECONDS = float(env("QWEN_ASR_MAX_SECONDS", "55"))
# 每包 PCM 之间的间隔（秒）。0.1 秒一包、间隔 0.01 约等于 10 倍速推流，
# 实测这个网关能正确收完；调到 0 会偶发截断，别省这点时间。
QWEN_ASR_PACE = float(env("QWEN_ASR_PACE", "0.01"))

# 一般留空 = 跟 QWEN_BASE_URL 同一个网关；只有 ASR 走了别的网关时才需要单独填。
QWEN_ASR_BASE_URL = env("QWEN_ASR_BASE_URL", "").rstrip("/")
OPENAI_API_KEY = env("OPENAI_API_KEY", "")
OPENAI_TRANSCRIBE_MODEL = env("OPENAI_TRANSCRIBE_MODEL", "gpt-transcribe")

# 容器里就是 `ffmpeg`（deploy/Dockerfile 会装）；Windows 本机没装的话，
# 四件事里只有「跟读评测」不可用，另外三个照常。
# 本机部署（2026-10-25）：用 imageio-ffmpeg 自带的静态版，拷在 .tools/ 下。
# 相对路径以**项目根**为基准解析 —— shutil.which 对相对路径是按 cwd 找的，
# 换个目录启动服务就会「明明配了却说找不到」。
FFMPEG_BIN = env("FFMPEG_BIN", "ffmpeg")
_ffmpeg_local = ROOT / FFMPEG_BIN   # 绝对路径时 pathlib 会原样保留
if FFMPEG_BIN not in ("ffmpeg", "ffprobe") and _ffmpeg_local.exists():
    FFMPEG_BIN = str(_ffmpeg_local)

# ---------------------------------------------------------------- 队列
WORKER_CONCURRENCY = int(env("WORKER_CONCURRENCY", "1"))   # 实测会撞 429，默认单并发
LEASE_SECONDS = int(env("WORKER_LEASE_SECONDS", "300"))    # 单次识别租约
POLL_INTERVAL = float(env("WORKER_POLL_INTERVAL", "1.0"))  # 空队列轮询间隔
MAX_UPLOAD_BYTES = int(env("MAX_UPLOAD_BYTES", str(12 * 1024 * 1024)))
