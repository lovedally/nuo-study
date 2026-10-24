# 教材内容（课程数据）怎么来的、怎么改

> 2026-10-23 起，英语页的所有内容（8 个单元的单词、课文、句型、练习题）都来自
> `web/english/curriculum.json`。**改内容 = 改这个 JSON**，网页不吃代码不改版。

## 一句话现状

| | 覆盖 |
|---|---|
| 单元 | 8 / 8（Unit 1 Try your best … Unit 8 Then and now） |
| 单词 | 159 个，全部带英式音标 + 例句（来自教材附录 p78–80 官方生词表） |
| 课文 | 8 段（每单元 Story time 原文，共 125 行） |
| 句型 | 每单元 8 句（Grammar time 例句 + 课文长句） |
| 练习题 | 每单元 4 道（词汇/句型选择，带中文解析） |
| 配套音频 | 40 段，单元映射**已用 ASR 逐段验证**（track-01 = "Unit 1, Period 1"） |

## 数据从哪来（三步流水线）

```
公众号文章 115 张教材页图
      │  tools/read_pages.py       每页一次多模态调用 → 一页一个 JSON
      ▼
data/english/pages/*.json            page / section / title / text / words / sentences
      │  tools/build_curriculum.py  按单元切单词表、挑课文、抽句型（不调模型）
      ▼
web/english/curriculum.json          结构骨架（忠于教材）
      │  tools/enrich_curriculum.py 补音标 / 例句 / 提示 / 练习题（每单元一次调用）
      ▼
web/english/curriculum.json          最终数据（服务端只读，按 mtime 缓存）
```

- **页图来源**：微信公众号文章（`mp.weixin.qq.com`）。直接 curl 会被验证页拦，
  用本机 Chrome 无头模式渲染取 DOM 才能拿到全部 115 个 `data-src`：
  ```bash
  chrome --headless=new --virtual-time-budget=12000 --dump-dom "<文章URL>" > wx.html
  ```
  用户给的 PDF 只导出了 68 页（Uni1–5），**不是完整教材**，别拿它当数据源。
- **`build_curriculum.py` 与 `enrich_curriculum.py` 分开**：前者只做「忠于教材的搬运」，
  重跑不会丢掉后者生成/校对的音标与题目（脚本里有 `preserve_enrichment` 合并）。
- 读图与补内容都要调网关，**在 NAS 上跑**（key 在 `.env` 里，不出机器）。

## 数据结构

```json
{
  "version": 1,
  "source": "苏教译林版（2024年秋）三年级上册英语",
  "units": [{
    "unit": 1,
    "module": "Module 1 Good qualities",
    "title": "Try your best",
    "titleZh": "尽我所能",
    "bigQuestion": "How should you face new difficulties?",
    "pages": [8, 19],
    "status": "ready",                     // ready | partial | missing
    "words": [{"en": "try one's best", "zh": "尽某人最大的努力",
               "core": true, "phonetic": "/traɪ wʌnz best/",
               "example": "She wanted to try her best.", "tip": "…"}],
    "sentences": [{"en": "Two weeks ago, Su Hai wanted to play Mulan in a school play."}],
    "passage": {"title": "It was great!", "page": 13, "lines": ["…"]},
    "quiz": [{"q": "Su Hai wanted to ___ her best.", "options": ["try", "make", "do"],
              "answer": 0, "explain": "try one's best 是固定搭配。"}]
  }]
}
```

`status` 一定要照实写：界面按它显示「已录入 / 部分 / 待录入」，
把没内容的说成 `ready`，孩子点进去只会看到空白。

## 接口

| 路径 | 用途 |
|---|---|
| `GET /api/v1/english/curriculum` | 整本（网页一次拿全，换单元不再请求） |
| `GET /api/v1/english/curriculum/{n}` | 单个单元 |
| `GET /api/v1/english/units` | 精简列表（单元选择器用，不含单词正文） |

## 两个客户端怎么消费（2026-10-23 起都接了）

| | 网页 `web/english/` | 安卓 `feature-english` |
|---|---|---|
| 取数 | `fetch('/api/v1/english/curriculum')`，失败退回内置兜底 | `EnglishApi.curriculum()`（Retrofit + kotlinx.serialization） |
| 缓存 | 浏览器内存（一次拿全，换单元不请求） | **进程级** `CurriculumCache`：切到数学再切回来不重新请求 |
| 落库 | 无 | 无。内容一学期改一两次，为一个只读的 60 KB 文件引入 Room + 离线合并策略不值 |
| 字段容忍 | `d.units \|\| []` | DTO 全字段带默认值 + `ignoreUnknownKeys`，服务端加字段不会崩 |

安卓侧三件事值得记：

1. **`status` 必须如实展示**。`missing` 表示这个单元只有标题、正文还没录，
   单元选择器上会标「未录入」——不标的话孩子点进去看到空白会以为坏了。
2. **单元切换是纯派生值**，`remember(units, unitNo)` 包住查找，别每次重组都线性扫。
3. **教材里混着页边页码**（模型读图时读进来的，如 `3 Finally, it was time...`）。
   清洗在 `tools/build_curriculum.py` 的 `_clean_line()` 里做，**不要在 UI 层修** ——
   网页和 App 都会拿到脏数据，两处各修一遍就是两份规则。

## 改内容的两种场景

**1. 只是改某条数据（改错别字、补一个例句）**
直接编辑 `web/english/curriculum.json` → `NAS_PW=… python tools/deploy_nas.py` →
服务端按 mtime 自动重载，**不用重启容器**。

**2. 教材换了 / 要重新抽一遍**
```bash
# a) 取页图（浏览器导出或直接下载），放到一个目录
# b) 读图（在 NAS 上，key 不出机器）
python3 tools/read_pages.py /tmp/nuo_pages/img /tmp/nuo_pages/out
# c) 拉回本地放到 data/english/pages/
# d) 重建骨架 + 补内容（补内容这一步在 NAS 上跑）
python tools/build_curriculum.py
python3 tools/enrich_curriculum.py /tmp/curr.json   # 在 NAS 上
```
`build_curriculum.py` 里有两处**必须跟着教材改**的常量：
- `TITLE_ZH`（单元中文名，教材上只有英文）
- `MODULE`（模块归属）
单词表页码 `(110, 111, 112)` 和单元起始页也写死在脚本里 —— 教材换版要核对。
