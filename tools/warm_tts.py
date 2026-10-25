# -*- coding: utf-8 -*-
"""把教材里**所有会被点到的句子**预先合成进服务端缓存。

## 为什么要有这一步

讯飞 TTS 的冷合成实测（2026-10-25，本机 → 讯飞云）：

    命中缓存  6~7 ms
    冷合成    **1.5 秒左右**

也就是说，如果什么都不做，孩子每点到一句**没听过的**课文，都要干等 1 秒多。
而教材的内容是**有限的、已知的** —— 全部提前合成一次（约几分钟），
之后任何一句都是毫秒级。这不是「缓存优化」，是**把在线合成变成离线资源**。

## 用法

    # 本机
    python tools/warm_tts.py --base http://127.0.0.1:8092

## 什么时候要重跑

- 改了教材内容（`curriculum.json`）→ 只有新增的句子会真合成，其余秒回。
- 改了音色 / 语速 / 后端格式 → **缓存键变了，全部要重合成**。
  这几项都在 `server/english.py` 的缓存键里，见那里的注释。
- ⚠️ 讯飞免费额度：每天 500 次基础 + 试用包（与国美云 xiaokouyu 同一应用，
  共享额度）。一次性预热几十条没问题，整本教材重刷注意分两天。
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

DEFAULT_BASE = "http://192.168.1.10:8092"
DEFAULT_CURRICULUM = Path(__file__).resolve().parent.parent / "web" / "english" / "curriculum.json"

# 单条最长等待。课文里最长的一行也就 200 字符，讯飞正常 1~2 秒出结果。
TIMEOUT_S = 60

# 并发数。讯飞免费档的并发上限不高（实测 2~3 路稳），调大容易撞限流。
WORKERS = 3


def collect_texts(curriculum: dict) -> list[str]:
    """收集所有会被孩子点到的文本。顺序无关，最后统一去重。"""
    out: list[str] = []
    for unit in curriculum.get("units") or []:
        passage = unit.get("passage") or {}
        out += [str(x) for x in (passage.get("lines") or [])]
        out += [str(s.get("en") or "") for s in (unit.get("sentences") or [])]
        for w in unit.get("words") or []:
            out.append(str(w.get("en") or ""))
            out.append(str(w.get("example") or ""))

    seen: set[str] = set()
    clean: list[str] = []
    for t in out:
        t = " ".join(t.split())
        # 服务端会拒绝空串和 >800 字符的文本，这里提前过滤，免得刷一堆失败
        if t and len(t) <= 800 and t not in seen:
            seen.add(t)
            clean.append(t)
    return clean


def synth(base: str, text: str) -> tuple[str, str]:
    """合成一条。返回 (状态, 明细)，状态是 ok / fail。"""
    body = json.dumps({"text": text}).encode("utf-8")
    req = urllib.request.Request(
        base.rstrip("/") + "/api/v1/english/tts",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_S) as r:
            payload = json.loads(r.read().decode("utf-8"))
        url = payload.get("url") or ""
        if not url:
            return "fail", "没有返回 url"
        return "ok", "%.2fs" % (time.time() - t0)
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:160]
        return "fail", "HTTP %s %s" % (e.code, detail)
    except Exception as e:  # noqa: BLE001 - 批量任务里一条失败不该中断整批
        return "fail", "%s: %s" % (type(e).__name__, e)


def main() -> int:
    ap = argparse.ArgumentParser(description="预热英语 TTS 缓存（把所有教材句子合成一遍）")
    ap.add_argument("--base", default=DEFAULT_BASE, help="服务端地址")
    ap.add_argument("--curriculum", default=str(DEFAULT_CURRICULUM), help="课程 JSON 路径")
    ap.add_argument("--workers", type=int, default=WORKERS, help="并发数（默认 6）")
    ap.add_argument("--dry-run", action="store_true", help="只列出要合成的文本数，不发请求")
    args = ap.parse_args()

    path = Path(args.curriculum)
    if not path.exists():
        print("[x] 课程文件不存在：%s" % path, file=sys.stderr)
        return 1
    texts = collect_texts(json.loads(path.read_text(encoding="utf-8")))
    print("待处理 %d 条（课文行 / 句型 / 单词 / 例句，已去重）" % len(texts))

    if args.dry_run:
        for t in texts[:10]:
            print("  ·", t[:70])
        return 0

    t_all = time.time()
    done = ok = fail = 0
    slow: list[tuple[float, str]] = []

    with ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
        futures = {pool.submit(synth, args.base, t): t for t in texts}
        for fut in as_completed(futures):
            text = futures[fut]
            status, detail = fut.result()
            done += 1
            if status == "ok":
                ok += 1
                # 记录「真的花时间」的那些（>1s 说明是新合成，不是命中）
                cost = float(detail.rstrip("s")) if detail.endswith("s") else 0.0
                if cost > 1.0:
                    slow.append((cost, text[:60]))
            else:
                fail += 1
                print("  [x] %s ← %s" % (detail, text[:60]), file=sys.stderr)
            if done % 50 == 0 or done == len(texts):
                print("  %d/%d  成功 %d  失败 %d  已用 %.0fs"
                      % (done, len(texts), ok, fail, time.time() - t_all))

    elapsed = time.time() - t_all
    print("\n完成：%d 条，成功 %d，失败 %d，共 %.1f 秒" % (done, ok, fail, elapsed))
    if slow:
        print("\n本次真正新合成的 %d 条（其余命中缓存，6ms 级）：" % len(slow))
        for cost, text in sorted(slow, reverse=True)[:8]:
            print("  %.1fs  %s" % (cost, text))
    return 0 if fail == 0 else 2


if __name__ == "__main__":
    raise SystemExit(main())
