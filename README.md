# 诺诺学习 · 小学三年级家庭自学系统

给自己家孩子用的一套学习系统：**家长拍张照就能录作业，孩子在平板上做题，英语有教材同步的听说读写**，
所有数据存在自家的 NAS 上，网页 / 平板 / 手机三端看到的是同一份。

不是商业产品，不上架应用商店，**单家庭私有部署**。代码公开主要是给自己留个版本记录，也欢迎参考思路。

---

## 功能

### 孩子端 · 安卓平板（横屏）

- **作业**：家长发布过来的作业单，逐题作答（自绘键盘 / 手写画布，锁外层滑动）
- **学习**：语文 / 数学 / 英语三个学科
- **英语**（内容来自苏教译林三年级上册，8 个单元）
  - **今日学习**：本单元概览 + 四个任务入口
  - **同步课程**：课文逐句点读（服务端 TTS，英式发音）+ 40 段教材配套原音
  - **单词卡片**：159 个词，音标 / 释义 / 例句 / 记忆提示，翻面 + 「认识 / 再练一次」
  - **句型挑战**：64 道题，答错标红 + 正确项标绿 + 中文解析，错题自动进错题本
  - **AI 口语**：按住跟读 → 腾讯智聆逐词批改（总分 / 准确 / 流利 / 完整）；
    按住说话 → 转写 → 千问陪练 → 语音回复
  - **错题本 / 学习报告**：报告读服务端汇总，和网页、家长手机是同一份
- 顶栏可收起（做题时多给内容 60dp），侧栏在 8 寸屏上 7 项全部首屏可见

### 家长端 · 安卓手机（竖屏）

- **作业录入**：拍照 → 多模态识别成结构化作业单 → 人工确认/修正 → 发布给孩子
- **实时画面**：看孩子平板此刻的界面（局域网，App 在前台时每 3 秒一帧）
- **学情**：最近 7 天的跟读均分、答题正确率、翻过/掌握的单词、按单元分布
- **设置**：服务端地址 + 家庭令牌（有家长口令保护）

### 英语网页 · 浏览器，不用装 App

打开 `http://<你的服务器>:8092/english/` 即可，功能与平板上的英语页对齐：
8 个单元、单词卡、课文点读、句型挑战、跟读评测、自由对话、错题本、学习报告。

### 服务端 · 一个进程

FastAPI + SQLite。作业识别走队列 + Worker（模型调用异步，失败重试/退避/僵尸回收）；
英语能力（TTS / 转写 / 对话 / 评测）融合在同一个服务里，不再单独起容器。

---

## 快速开始

### 1. 起服务端（Docker，推荐）

```bash
git clone <this-repo> && cd primary-study-app

cp .env.example .env        # 然后填下面的密钥
docker compose -f deploy/docker-compose.yml up -d --build
```

`.env` 里**必须**改的一项：

```ini
FAMILY_TOKEN=改成你自己的随机串      # 客户端连服务端用的令牌，默认值等于没有防护
```

按需填的能力（不填也能跑，只是对应功能不可用，`/api/health` 会如实报 false）：

```ini
# 语音合成（课文点读 / 示范音）—— 微软 Azure
AZURE_SPEECH_KEY=
AZURE_SPEECH_REGION=eastasia
AZURE_TTS_VOICE=en-GB-SoniaNeural

# 跟读评测 —— 腾讯智聆
TENCENT_APP_ID=
TENCENT_SECRET_ID=
TENCENT_SECRET_KEY=

# 陪练对话 + 语音转写 —— 阿里云百炼（千问）
QWEN_API_KEY=
QWEN_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
QWEN_CHAT_MODEL=qwen-flash
```

启动后：

```bash
curl http://localhost:8092/api/health          # 四个能力各自配好没
# 浏览器打开 http://localhost:8092/english/   # 英语网页
# 浏览器打开 http://localhost:8092/docs       # 接口文档
```

**首次启动建议预热语音缓存**（否则孩子每点一句没听过的课文要等 4 秒合成）：

```bash
python tools/warm_tts.py --base http://localhost:8092
```

> 493 条内容一次合成约 3 分钟；之后任何一句都是 6ms。

### 2. 装 App

两个 APK 由 GitHub Actions 自动构建：

**Actions → 最新一次 `Android CI` → Artifacts → 下载 `apk`**，解压得到：

| 文件 | 装到哪 |
|---|---|
| `app-student-perf.apk` | 孩子平板 |
| `app-parent-perf.apk` | 家长手机 |

> `perf` 档 = `debug` 去掉 `debuggable`（同签名、同包名，可直接覆盖安装）。
> **一定要装 perf 档**：`debuggable=true` 的包 ART 拒绝对它做 AOT 编译，
> 只能解释执行 —— 实测切页长帧 550ms vs 90ms 的差距。

也可以自己编：

```bash
cd android
./gradlew :app-student:assemblePerf :app-parent:assemblePerf
# 产物在 app-*/build/outputs/apk/perf/
```

装完建议做一次 AOT 编译（冷启动 1.2s → 0.9s，滚动更顺）：

```bash
adb shell cmd package compile -m speed -f com.nuostudy.student.debug
```

### 3. 首次配置

App 启动后进「设置」，填**服务端地址**和**家庭令牌**（就是 `.env` 里的 `FAMILY_TOKEN`）。

想免填（自己家长期用）就把它烧进 APK —— 但**别提交**：

```properties
# android/local.properties（已被 .gitignore 挡住）
nuo.baseUrl=http://192.168.1.10:8092/
nuo.familyToken=你的令牌
```

---

## 目录结构

```
server/                  FastAPI 服务端
  api.py                 HTTP 路由（薄层）
  store.py               所有 SQL 与业务规则
  queue.py / worker.py   识别队列：入队/领取/租约/退避/僵尸回收
  recognizer.py          模型调用（无 DB、无 HTTP，可单测）
  english.py             英语四项能力（TTS / 转写 / 对话 / 评测）
  curriculum.py          课程数据读取（只读 JSON）
  english_progress.py    学情：三端共用的那一份
db/schema.sql            SQLite 建表（18 张）+ 迁移说明
web/english/             英语网页（含 curriculum.json 与 40 段课文录音）
data/english/pages/      教材 115 页的逐页抽取结果（课程数据的源头）
android/                 安卓工程（13 个 Gradle 模块）
  app-student/           孩子端 · 平板横屏
  app-parent/            家长端 · 手机竖屏
  core-{ui,network,data,sync}/
  feature-{homework,capture,learning,chinese,math,english,report,assistant,settings}/
tools/                   部署 / 内容生产 / 诊断脚本
docs/                    设计文档（客户端、服务端、识别评测、课程数据、英语融合）
```

---

## 本地开发

### 服务端

```bash
python -m venv .venv && .venv/bin/pip install -r requirements.txt
python run_server.py --port 8092          # 单进程：HTTP + 队列消费线程
python server/smoke_test.py               # 端到端冒烟（36 项断言）
```

### 安卓

```bash
cd android
./gradlew :app-student:assemblePerf       # 孩子端
./gradlew :app-parent:assemblePerf        # 家长端
```

需要 JDK 17 + Android SDK（`android/local.properties` 里 `sdk.dir` 指过去）。

### 教材内容

课程数据不是硬编码的，来自教材页图 → 结构化 JSON 的三步流水线：

```bash
python tools/read_pages.py <页图目录> <输出目录>   # 逐页多模态抽取（可断点续跑）
python tools/build_curriculum.py                  # 组骨架（单词表/课文/句型）
python tools/enrich_curriculum.py                 # 补音标/例句/练习题（在服务端跑，key 不出机器）
```

改内容 = 改 `web/english/curriculum.json`，服务端按 mtime 自动重载，**不用重启**。
详见 [`docs/curriculum.md`](docs/curriculum.md)。

---

## 设计要点

**单家庭，所以很多企业级的东西被有意去掉了**：无多租户、无注册登录、无短信、无 RBAC、无审核后台。
认证退化成 `.env` 里一个 `FAMILY_TOKEN`，所有设备共用。

**几个踩过坑、值得记的决定**：

| 决定 | 原因 |
|---|---|
| 识别模型必须**关思考** | 开着会把 `max_tokens` 吃光，返回空正文；关掉单页 10.1s → 3.7s |
| 英语能力**融合进一个服务** | 原来是独立 Node 容器（4173），两套 `.env`、两份部署；合并后一个进程一个端口 |
| 语音**预生成**而不是实时合成 | 冷合成 4.1 秒 vs 命中缓存 6ms；教材内容是有限的、已知的，不该在线等 |
| 学情上报用**增量加法** | 平板和网页可同时上报，汇总到一起不会互相覆盖 |
| 客户端**不做离线课程库** | 内容一学期改一两次，为一个只读的 60KB 文件引入 Room + 合并策略不值 |

更多细节见 [`docs/`](docs/)：`client-design.md`（导航/横竖屏/离线策略）、
`server-design.md`（队列可靠性/接口清单）、`recognition-eval.md`（识别可行性实测）、
`curriculum.md`（课程数据流水线）、`merge-english.md`（英语服务融合记录）。

---

## 已知限制

- **教材只覆盖三年级上册**（苏教译林 2024 秋），下册和语文数学的内容层还没做深
- **跟读评测需要腾讯智聆**，不配 key 时按钮是灰的（界面上会如实写）
- **家长端的「作业」链路**依赖识别 Worker，模型密钥要自己配
- 孩子端 App **故意不要相机权限**（拍照是家长端的事），只在朗读题时申请录音
- `debuggable` 的包**没法 AOT**，性能档请装 `perf`

---

## 许可

个人自用项目，未附开源许可证。代码可参考，但不提供任何担保。
