# -*- coding: utf-8 -*-
"""SQLite 连接与时间工具。

⚠️ 三个 PRAGMA 必须**每个连接**都设：DDL 里写的那几行只对建库那一次连接生效。
   foreign_keys 默认 OFF —— 不设则所有 REFERENCES 形同虚设。
"""
from __future__ import annotations

import sqlite3
import threading
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path

from . import config

# UTC ISO8601 毫秒文本，形如 2026-10-09T00:20:02.498Z
# 好处：字典序 == 时间序，可直接 ORDER BY；无时区歧义
ISO_FMT = "%Y-%m-%dT%H:%M:%S.%f"


def iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime(ISO_FMT)[:-3] + "Z"


def now_iso() -> str:
    return iso(datetime.now(timezone.utc))


def iso_after(seconds: int) -> str:
    return iso(datetime.now(timezone.utc) + timedelta(seconds=seconds))


def parse_iso(s: str) -> datetime:
    return datetime.strptime(s[:23], ISO_FMT).replace(tzinfo=timezone.utc)


def connect(path: Path | None = None) -> sqlite3.Connection:
    p = Path(path or config.DB_FILE)
    p.parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(p, timeout=10, isolation_level=None)
    con.row_factory = sqlite3.Row
    con.execute("PRAGMA foreign_keys = ON")
    con.execute("PRAGMA journal_mode = WAL")
    con.execute("PRAGMA busy_timeout = 5000")
    con.execute("PRAGMA synchronous = NORMAL")
    return con


def init_db(path: Path | None = None) -> Path:
    """按 schema.sql 建库（幂等：已存在则只补基础数据）。"""
    p = Path(path or config.DB_FILE)
    fresh = not p.exists()
    if fresh:
        p.parent.mkdir(parents=True, exist_ok=True)
        con = sqlite3.connect(p)
        con.executescript(config.SCHEMA_FILE.read_text(encoding="utf-8"))
        con.commit()
        con.close()
    con = connect(p)
    try:
        if not con.execute("SELECT 1 FROM student LIMIT 1").fetchone():
            con.execute("INSERT INTO student (name, grade) VALUES (?, 3)", ("孩子",))
        if not con.execute("SELECT 1 FROM device LIMIT 1").fetchone():
            con.execute(
                "INSERT INTO device (device_id, label, role, form_factor) VALUES (?,?,?,?)",
                ("dev-parent-01", "家长手机", 2, "phone"))
        _migrate(con)
    finally:
        con.close()
    return p


def _migrate(con: sqlite3.Connection) -> None:
    """幂等的表结构增补 —— **每次启动都跑**。

    ⚠️ 为什么需要它：`schema.sql` 只在**库文件不存在时**执行一次
    （`init_db` 里的 `if fresh`）。所以「给已经部署好的 NAS 库加一张表」
    只能靠这里，否则旧库永远缺表，而且报错是 `no such table`，
    看上去像「代码没部署」。

    规则：只允许 `CREATE TABLE IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS` /
    `ALTER TABLE ... ADD COLUMN`（且要用 try 包住，SQLite 不支持
    `ADD COLUMN IF NOT EXISTS`）。**不要**在这里写改字段类型、删列之类的危险操作。
    """
    con.executescript("""
    -- 英语学习进度（网页 / 平板 / 家长端共用同一份）。
    -- 按「学生 × 日期 × 单元」聚合，客户端上报**增量**，服务端做加法。
    -- 为什么不存明细事件：英语的学习行为是高频低价值的（翻一个词、答一道题），
    -- 一天几十上百条，存明细三个月就是几万行，而家长只看汇总。
    -- 真要追溯某次跟读，服务端日志里有（POST /evaluate 的评分）。
    CREATE TABLE IF NOT EXISTS english_progress (
        student_id      INTEGER NOT NULL,
        day             TEXT    NOT NULL,          -- 本地日期 YYYY-MM-DD
        unit            INTEGER NOT NULL,          -- 单元号 1..8
        words_seen      INTEGER NOT NULL DEFAULT 0,
        words_known     INTEGER NOT NULL DEFAULT 0,
        quiz_total      INTEGER NOT NULL DEFAULT 0,
        quiz_correct    INTEGER NOT NULL DEFAULT 0,
        speak_count     INTEGER NOT NULL DEFAULT 0,
        speak_score_sum REAL    NOT NULL DEFAULT 0, -- 累加总分，均值在读取时算
        updated_at      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
        PRIMARY KEY (student_id, day, unit)
    );
    CREATE INDEX IF NOT EXISTS idx_engprog_day ON english_progress(student_id, day);
    """)


@contextmanager
def tx(con: sqlite3.Connection):
    """显式事务。BEGIN IMMEDIATE 用于队列领取，避免两个 Worker 抢到同一条任务。

    ⚠️ **可重入**：已经在事务里时不再 BEGIN，直接复用外层事务。
       连接是 `isolation_level=None`（自动提交模式），事务完全由显式 BEGIN 控制，
       所以嵌套 BEGIN 会直接炸：
           sqlite3.OperationalError: cannot start a transaction within a transaction
       而「复用一个事务」在语义上也是对的 —— 内层失败时外层应该整体回滚，
       内层 COMMIT 提前提交反而会把外层的后续操作切成两半。
    """
    if con.in_transaction:
        yield con
        return
    con.execute("BEGIN IMMEDIATE")
    try:
        yield con
        con.execute("COMMIT")
    except Exception:
        con.execute("ROLLBACK")
        raise


# ---------------------------------------------------------------- 线程独占连接
_local = threading.local()


def thread_conn() -> sqlite3.Connection:
    """返回**当前线程独占**的连接，供 HTTP 层使用。

    ⚠️ 踩过的坑：不能把一个「请求级连接」通过 FastAPI 依赖注入传给同步端点。
    FastAPI 会把同步依赖和同步端点都丢进 anyio 线程池，**两者不保证在同一个线程**，
    于是 sqlite3 直接抛：
        ProgrammingError: SQLite objects created in a thread can only be used in that same thread
    线程池的线程是复用的，所以按线程缓存连接既正确又省掉每请求重连的开销。
    （worker.py 里则是每个消费线程显式 connect() 一次。）
    """
    c = getattr(_local, "con", None)
    if c is None:
        c = connect()
        _local.con = c
    return c


def close_thread_conn() -> None:
    c = getattr(_local, "con", None)
    if c is not None:
        c.close()
        _local.con = None


def db_version(con: sqlite3.Connection) -> int:
    return con.execute("PRAGMA user_version").fetchone()[0]
