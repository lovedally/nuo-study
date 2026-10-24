#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""从教材页抽取结果生成 `web/english/curriculum.json`。

## 数据从哪来

`data/english/pages/*.json` —— 每一页一次多模态抽取的产物（见 tools/read_pages.py），
字段：page / section / title / text / words / sentences。

## 这份脚本做的三件事

1. **单词表按单元切**：教材 p78–80 是官方「按单元顺序」的生词表（Word lists I），格式
   `*word 中文 (页码)`，带 `*` 的是非核心词。比从课文里猜单词可靠得多，所以用它。
   （p80 下半转入按字母排序的 Word lists II，解析时截断，见 parse_words。）
2. **单元边界**：以 `Unit opener` 页的页码为准（2024 版三年级上册为 8/16/24/32/42/50/58/66），
   一段区间的所有页都属于这个单元。别信每页自带的 `unit` 字段 —— 模型经常把
   跨页的课文归错。
3. **课文取 Story time**：每个单元选「有标题的那个 Story time 页」+ 它的下一页。
   课文是给孩子跟读用的，所以要连续段落；`Fun time`/`Grammar time` 的碎片不要。

音标和练习题**不在这里生成**（要调用模型，见 tools/enrich_curriculum.py），
本脚本产出的是「结构正确、内容忠于教材」的骨架。
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PAGES_DIR = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "data" / "english" / "pages"
OUT = Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / "web" / "english" / "curriculum.json"

WORD_RE = re.compile(r"^(?P<star>\*)?(?P<en>[^(]+?)\s+(?P<zh>.+?)\s*\((?P<page>\d+)\)\s*$")
UNIT_RE = re.compile(r"^Unit\s+(\d+)$")

# 单元中文名（教材页上没有，按 Big question 与单元主题意译）
# 苏教译林三年级上册（2024 秋新版）
TITLE_ZH = {
    1: "你好",
    2: "你叫什么名字",
    3: "你是苏海吗",
    4: "这是我的朋友",
    5: "她是我妈妈",
    6: "他是你爷爷吗",
    7: "生日快乐",
    8: "我能为你做这些",
}
MODULE = {1: "Module 1 Getting to know each other", 2: "Module 1 Getting to know each other",
          3: "Module 1 Getting to know each other", 4: "Module 1 Getting to know each other",
          5: "Module 2 My family and me", 6: "Module 2 My family and me",
          7: "Module 2 My family and me", 8: "Module 2 My family and me"}

# 官方生词表（Word lists I，按单元顺序）所在印刷页码。
# 三年级上册：p78 起按单元顺序，p80 上半是 Unit 8 收尾，p80 下半转入按字母排序的 II 部分。
WORD_LIST_PAGES = (78, 79, 80)

# 单元 → 印刷页码区间（2024 版三年级上册，与 unit_ranges 的开篇页一致）。
# 词表条目自带「该词首次出现的页码」，用页码反推归属比依赖 OCR 读出
# 「Unit N」标题行可靠得多 —— 实测模型经常把词表页眉的小图标行漏掉。
WORD_UNIT_RANGES = {1: (8, 15), 2: (16, 23), 3: (24, 31), 4: (32, 41),
                    5: (42, 49), 6: (50, 57), 7: (58, 65), 8: (66, 77)}


def load_pages() -> list[dict]:
    pages = []
    for f in sorted(PAGES_DIR.glob("*.json")):
        j = json.loads(f.read_text(encoding="utf-8"))
        if j.get("page"):
            pages.append(j)
    pages.sort(key=lambda j: j["page"])
    return pages


def parse_words(pages: list[dict]) -> dict[int, list[dict]]:
    """从官方生词表按单元切。带 `*` 的是非核心词，仍收进来但标记 core=False。

    ⚠️ 不能用一个固定正则去切「英文 + 中文 + (页码)」：
       · 模型可能漏掉中文列（六年级实测：p112 整页输出 `*both (71)`），按
         「必须有中文」切会整页丢光；
       · 还有 `colour (AmE color) (72)` 这种英文自带括号的条目，`[^(]+` 会在
         "colour " 处截断。
    所以改成：先剥掉行尾的 `(页码)`，再按「第一个中文字符」切英文/中文；
    没有中文就整行当英文，中文留空（后面的 enrich 步骤会补）。

    ⚠️ 单元归属**按条目页码反推**（WORD_UNIT_RANGES），不要依赖 text 里出现
       「Unit N」标题行 —— 三年级词表页眉带绿叶图标，实测模型经常整行漏掉，
       全部词会被判成 unit=None 而丢失（2026-10-23 实测踩到）。「Unit N」行
       仍保留为显式校准；II（按字母排序）分隔后停止收集，防止字母序词混入。
    """
    out: dict[int, list[dict]] = {}
    seen: dict[int, set] = {}

    def unit_of(page_no: int, hinted: int | None) -> int | None:
        for u, (lo, hi) in WORD_UNIT_RANGES.items():
            if lo <= page_no <= hi:
                return u
        return hinted  # 页码越界（OCR 误读）时退回最近一次显式标题

    unit = None
    for j in pages:
        if j["page"] not in WORD_LIST_PAGES:
            continue
        for raw in j.get("text") or []:
            line = raw.strip()
            # Word lists I 之后是按字母排序的 II 部分：见到 II 分隔就停，
            # 否则字母序的词会全部错记进 Unit 8。
            if re.match(r"^(II|Ⅲ|III)$", line) or line.startswith("说明"):
                unit = None
                continue
            m = UNIT_RE.match(line)
            if m:
                unit = int(m.group(1))
                out.setdefault(unit, [])
                seen.setdefault(unit, set())
                continue
            if not line:
                continue
            pm = re.search(r"\((\d+)\)\s*$", line)
            if not pm:
                continue
            body = line[:pm.start()].strip()
            page_no = int(pm.group(1))
            core = not body.startswith("*")
            body = body.lstrip("*").strip()
            idx = next((i for i, ch in enumerate(body) if "\u4e00" <= ch <= "\u9fff"), None)
            if idx is None:
                en, zh = body, ""
            else:
                en, zh = body[:idx].strip(), body[idx:].strip()
            if not en:
                continue
            u = unit_of(page_no, unit)
            if u is None:
                continue
            out.setdefault(u, [])
            seen.setdefault(u, set())
            if en.lower() in seen[u]:          # Word lists I 内部不去重会出重复卡
                continue
            seen[u].add(en.lower())
            out[u].append({"en": en, "zh": zh, "page": page_no, "core": core})
    return out


def unit_ranges(pages: list[dict]) -> list[tuple[int, int, int]]:
    """单元边界 = `Unit opener` 页的页码。

    ⚠️ 抽取会把「Big question」那半页也标成 `Unit opener`（它确实是单元开篇的一部分），
       不去掉的话 8 个单元会变成 16 个 —— 每个单元被劈成「标题页」和「大问题页」两段，
       于是课文、单词全部错位。判定依据：标题就是 "Big question" 的一律跳过。
    """
    openers = [j["page"] for j in pages
               if j.get("section") == "Unit opener"
               and (j.get("title") or "").strip()
               and "big question" not in (j.get("title") or "").strip().lower()]
    out = []
    for i, start in enumerate(openers):
        # 三年级上册正文到 p77（Learning tips）结束，Word lists 从 p78 起
        end = (openers[i + 1] - 1) if i + 1 < len(openers) else 77
        out.append((i + 1, start, end))
    return out


def big_question(pages: list[dict], start: int) -> str:
    for j in pages:
        if j["page"] in (start, start + 1):
            for line in j.get("text") or []:
                if line.strip().endswith("?"):
                    return line.strip()
    return ""


def _clean_line(line: str) -> str:
    """去掉粘在句首的教材页码。

    模型读图时会把页边页码一起读进正文行，于是课文里出现
    `2 Su Hai was afraid of acting...` / `3 Finally, it was time...`。
    孩子看到的是一段带着莫名其妙数字的英文，点读时 TTS 还会把 "two" 念出来。

    判定：行首是 1~120 的整数 + 空白 + 正文。教材总共约 90 页，页码不会越界；
    而正文里不会有「孤立数字开头」的句子（那是页码，不是内容）。
    """
    s = line.strip()
    m = re.match(r"^(\d{1,3})\s+(\S.*)$", s)
    if m and int(m.group(1)) <= 120:
        return m.group(2).strip()
    return s


def passage(pages: list[dict], start: int, end: int, unit_title: str = "") -> dict:
    """取该单元「真正的 Story time 课文」+ 它的下一页。

    ⚠️ 不能简单取「第一个有标题的 Story time 页」：教材把跨两页的课文里，
       前一半那页的标题常常就是 `Unit 6` 这种页眉（模型照抄成 title），
       真正的课文标题在下一页（`Take care of the Earth`）。所以：
         · 标题形如 `Unit N...` 的一律不算「真标题」；
         · 候选里按「本页 + 下一页」的总行数取最大，避免挑到只有两行的残页；
         · 下一页**不限制板块** —— 课文常常溢到下一页而那一页被标成 Cartoon/Other。
    ⚠️ 三年级（2024 新版）的 Story time 是漫画格，**没有独立课文标题** ——
       所以不再强制「有真标题才入选」：无标题的 Story time 页同样作候选，
       课文标题缺位时用单元名（unit_title）补。
    """
    by_page = {j["page"]: j for j in pages if start <= j["page"] <= end}

    def real_title(t: str) -> bool:
        t = (t or "").strip()
        return bool(t) and not re.match(r"^Unit\s*\d+\b", t, re.I) and t.lower() != "cartoon time"

    best = None
    for p in sorted(by_page):
        j = by_page[p]
        if j.get("section") != "Story time":
            continue
        lines = [_clean_line(ln) for ln in (j.get("text") or []) if ln.strip()]
        nxt = by_page.get(p + 1)
        extra = [_clean_line(ln) for ln in ((nxt or {}).get("text") or []) if ln.strip()]
        score = len(lines) + len(extra)
        title = (j.get("title") or "").strip()
        if not real_title(title):
            title = unit_title
        if best is None or score > best[0]:
            best = (score, p, title, lines + extra)
    if not best:
        return {"title": "", "page": None, "lines": []}
    return {"title": best[2], "page": best[1], "lines": best[3]}


def sentences(pages: list[dict], start: int, end: int) -> list[dict]:
    """句型优先取 Grammar time 的例句，不够再补 Story time 里的长句。

    ⚠️ 三年级句子极短（"Hi, I'm Bobby."、"This is Sam."），按六年级的
       「>14 字符且 ≥3 空格」会全军覆没 —— 放宽到「>6 字符且 ≥1 空格」。
       三年级没有独立 Grammar time 页，主要靠 Story/Cartoon time 兜。
    """
    def good(line: str) -> bool:
        s = line.strip()
        return (len(s) > 6 and " " in s and s[-1] in ".?!" and
                not any(ch in s for ch in "　（）【】") and s.count(" ") >= 1)

    picked: list[str] = []
    for j in pages:
        if not (start <= j["page"] <= end):
            continue
        if j.get("section") == "Grammar time":
            picked += [ln.strip() for ln in (j.get("text") or []) if good(ln)]
    for j in pages:
        if not (start <= j["page"] <= end):
            continue
        if j.get("section") in ("Story time", "Cartoon time"):
            picked += [ln.strip() for ln in (j.get("text") or []) if good(ln)]
    seen, out = set(), []
    for s in picked:
        k = s.lower()
        if k in seen:
            continue
        seen.add(k)
        out.append({"en": s})
    return out[:8]


def preserve_enrichment(data: dict, out: Path) -> dict:
    """把上一次生成的音标 / 例句 / 提示 / 练习题搬回来。

    ⚠️ 这一步很关键：`enrich_curriculum.py` 要调 8 次模型（约 1 分钟、花钱），
       而结构重建可能只是因为换了一页课文。没有这个合并，每次重跑结构都会把
       已经校对过的内容清空，人就不敢再动结构了 —— 最后一定是「结构错了也将就用」。
       合并按 `unit + en` 精确匹配：教材单词变了就吃新数据，没变就保留。
    """
    if not out.exists():
        return data
    try:
        old = json.loads(out.read_text(encoding="utf-8"))
    except Exception:
        return data
    old_units = {int(u.get("unit") or 0): u for u in (old.get("units") or [])}
    for u in data["units"]:
        prev = old_units.get(int(u["unit"]))
        if not prev:
            continue
        prev_words = {str(w.get("en", "")).lower(): w for w in (prev.get("words") or [])}
        for w in u["words"]:
            p = prev_words.get(w["en"].lower())
            if not p:
                continue
            for k in ("phonetic", "example", "tip"):
                if not w.get(k) and p.get(k):
                    w[k] = p[k]
            if not w.get("zh") and p.get("zh"):
                w["zh"] = p["zh"]
        if not u.get("quiz") and prev.get("quiz"):
            u["quiz"] = prev["quiz"]
        if u.get("quiz") and u.get("words"):
            u["status"] = "ready"
    return data


def main() -> int:
    pages = load_pages()
    if not pages:
        print("没有页数据：%s" % PAGES_DIR, file=sys.stderr)
        return 2
    words = parse_words(pages)
    units = []
    for no, start, end in unit_ranges(pages):
        opener = next((j for j in pages if j["page"] == start), {})
        title = (opener.get("title") or "").strip()
        pw = [w for w in words.get(no, []) if w["page"] <= end] or words.get(no, [])
        units.append({
            "unit": no,
            "module": MODULE.get(no, ""),
            "title": title,
            "titleZh": TITLE_ZH.get(no, ""),
            "bigQuestion": big_question(pages, start),
            "pages": [start, end],
            "status": "ready" if pw else "partial",
            "words": [{"en": w["en"], "zh": w["zh"], "core": w["core"],
                       "phonetic": "", "example": "", "tip": ""} for w in pw],
            "sentences": sentences(pages, start, end),
            "passage": passage(pages, start, end, title),
            "quiz": [],
        })
    data = {
        "version": 1,
        "source": "苏教译林版（2024年秋）三年级上册英语",
        "updatedAt": "2026-10-23",
        "note": "单词取自教材附录 p78-80 官方生词表（Word lists I）；课文取自该单元 Story time；"
                "音标与练习题由 tools/enrich_curriculum.py 补充。",
        "units": units,
    }
    data = preserve_enrichment(data, OUT)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    print("写出 %s" % OUT)
    for u in units:
        print("  Unit %d %-22s 词 %2d 句型 %d 课文 %2d 行 题 %d"
              % (u["unit"], u["title"], len(u["words"]), len(u["sentences"]),
                 len(u["passage"]["lines"]), len(u["quiz"])))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
