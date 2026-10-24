#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""给课程骨架补「音标 / 例句 / 记忆提示 / 练习题」。

为什么单独一步：`build_curriculum.py` 只做**忠于教材**的结构搬运（单词表、课文、
大问题），不需要模型；而音标、例句、练习题必须生成。分开的好处是 —— 重跑结构
不会把已经校对的生成内容冲掉，反之亦然。

在配好 key 的机器上跑（key 从仓库根 .env 或 NUO_ENV 指定的 .env 读，不出机器）：

  python3 tools/enrich_curriculum.py <curriculum.json> [--only 1,2]

每个单元一次调用，要求模型输出严格 JSON；失败重试 2 次，单元级失败不影响其它单元。
"""
from __future__ import annotations

import json
import random
import os
import pathlib
import sys
import time
import urllib.error
import urllib.request

ENV_FILE = os.environ.get(
    "NUO_ENV",
    str(pathlib.Path(__file__).resolve().parents[1] / ".env"))
ENV = {}
for line in open(ENV_FILE, encoding="utf-8"):
    s = line.strip()
    if s and not s.startswith("#") and "=" in s:
        k, v = s.split("=", 1)
        ENV[k.strip()] = v.strip().strip('"').strip("'")

KEY = ENV["QWEN_API_KEY"]
BASE = ENV["QWEN_BASE_URL"].rstrip("/")
MODEL = ENV.get("QWEN_CHAT_MODEL", "qwen-flash")

PROMPT = """你在为小学三年级英语学习工具准备数据（学生刚接触英语一两个月，认字母、会说最简单的问候语）。下面是一个单元的真实教材内容。

单元：Unit {unit} {title}
大问题：{bigq}
课文标题：{ptitle}
课文原文：
{passage}

本单元生词（来自教材附录，`core` 表示是否核心词）：
{words}

请只输出一个 JSON 对象（不要 markdown 代码块、不要解释）：
{{"words":[{{"en":"原样返回这个单词","zh":"中文释义（已有英文就沿用，缺的补上）",
   "phonetic":"英式音标，形如 /ˈhɒnɪst/（给孩子家长参考）","example":"含该词的一句英文例句",
   "tip":"一句中文记忆提示，可空字符串"}}],
 "quiz":[{{"q":"英文题干，用 ___ 表示空格","options":["选项A","选项B","选项C"],
   "answer":0,"explain":"一句中文解析"}}]}}

硬性要求：
1. words 必须**逐个覆盖**给出的所有单词，不增不减，顺序不变。
2. phonetic 用英式（RP）音标；短语给整体音标。
3. example 优先用课文原文里含这个词的句子；课文里没有就写一句**三年级上册水平**的简单句：
   不超过 6 个单词、只用本册及更早的词汇（问候、名字、家人、数字 1-10、颜色、can/do 句式）。
4. quiz 出 4 道，只考本单元的词汇与句型，**难度压到三年级上册**：
   题干尽量短（一句话）、选项单词优先；考「Hello/Good morning/What's your name/
   This is.../She's my mother/Happy Birthday」这类句型即可，不要考时态、比较级等超纲语法。
5. 全部内容用简体中文解释，题目和选项里的英语必须地道、无语法错误。"""


def ask(prompt: str) -> dict:
    body = {
        "model": MODEL,
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": 3000,
        "temperature": 0.2,
        # 关思考：这一步是「按格式改写」，不需要推演，关了快 3 倍
        "enable_thinking": False,
    }
    last = None
    for attempt in range(3):
        req = urllib.request.Request(
            BASE + "/chat/completions", data=json.dumps(body).encode(),
            headers={"Authorization": "Bearer " + KEY, "Content-Type": "application/json"},
            method="POST")
        try:
            with urllib.request.urlopen(req, timeout=180) as r:
                j = json.loads(r.read().decode())
            text = (j["choices"][0]["message"].get("content") or "").strip()
            if text.startswith("```"):
                text = text.split("```")[1]
                text = text[text.find("{"):]
            s, e = text.find("{"), text.rfind("}")
            return json.loads(text[s:e + 1])
        except Exception as exc:      # noqa: BLE001
            last = exc
            time.sleep(2 + attempt * 3)
    raise RuntimeError("调用失败：%s: %s" % (type(last).__name__, last))


def shuffle_quiz(quiz: list[dict], unit_no: int) -> list[dict]:
    """打乱每题选项顺序（模型爱把正确答案放第一个，孩子会发现「永远选 A」）。

    用 (unit, 题号) 做种子 —— 确定性洗牌，重跑 enrich 不会变来变去。
    """
    for i, q in enumerate(quiz):
        rng = random.Random("quiz|%d|%d|%s" % (unit_no, i, q["q"]))
        opts = list(q["options"])
        rng.shuffle(opts)
        q["answer"] = opts.index(q["options"][q["answer"]])
        q["options"] = opts
    return quiz


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    path = pathlib.Path(sys.argv[1])
    only = None
    if "--only" in sys.argv:
        only = {int(x) for x in sys.argv[sys.argv.index("--only") + 1].split(",")}

    data = json.loads(path.read_text(encoding="utf-8"))
    for u in data["units"]:
        no = int(u["unit"])
        if only and no not in only:
            continue
        words = u.get("words") or []
        if not words:
            print("Unit %d 没有单词，跳过" % no, flush=True)
            continue
        prompt = PROMPT.format(
            unit=no, title=u.get("title", ""), bigq=u.get("bigQuestion", ""),
            ptitle=(u.get("passage") or {}).get("title", ""),
            passage="\n".join((u.get("passage") or {}).get("lines") or [])[:4000],
            words=json.dumps([{"en": w["en"], "zh": w.get("zh", ""), "core": w.get("core")}
                              for w in words], ensure_ascii=False),
        )
        try:
            got = ask(prompt)
        except Exception as exc:      # noqa: BLE001
            print("Unit %d 失败：%s" % (no, exc), flush=True)
            continue
        by_en = {str(w.get("en", "")).lower(): w for w in (got.get("words") or [])}
        filled = 0
        for w in words:
            g = by_en.get(w["en"].lower())
            if not g:
                continue
            for k in ("phonetic", "example", "tip"):
                if g.get(k):
                    w[k] = str(g[k]).strip()
            if not w.get("zh") and g.get("zh"):
                w["zh"] = str(g["zh"]).strip()
                filled += 1
        quiz = []
        for q in (got.get("quiz") or [])[:6]:
            opts = [str(o) for o in (q.get("options") or [])][:4]
            if len(opts) < 2 or not q.get("q"):
                continue
            try:
                ans = int(q.get("answer", 0))
            except Exception:
                ans = 0
            quiz.append({"q": str(q["q"]), "options": opts,
                         "answer": max(0, min(ans, len(opts) - 1)),
                         "explain": str(q.get("explain", ""))})
        u["quiz"] = shuffle_quiz(quiz, no)
        u["status"] = "ready" if quiz and words else "partial"
        print("Unit %d %-22s 音标 %d/%d 题 %d 补中文 %d"
              % (no, u.get("title", ""),
                 sum(1 for w in words if w.get("phonetic")), len(words), len(quiz), filled),
              flush=True)
        path.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
