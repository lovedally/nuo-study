-- =============================================================================
-- 小学三年级家庭自学 App · SQLite DDL
-- 场景：单家庭私有部署 / 无教师端 / 无多租户
-- 作业来源 = 家长上传老师作业截图 + 多模态识别
-- 版本：v2.0（PostgreSQL → SQLite）
-- =============================================================================
-- 方言映射（从 v1.x 的 PG 版迁移而来）
--   BIGSERIAL PRIMARY KEY   → INTEGER PRIMARY KEY AUTOINCREMENT
--   VARCHAR(n) / CHAR(64)   → TEXT            （SQLite 不强制长度，长度约束交给应用层）
--   SMALLINT / INT          → INTEGER
--   BOOLEAN                 → INTEGER (0/1)   —— SQLite 无布尔类型
--   NUMERIC(5,2)            → REAL
--   JSONB                   → TEXT            （存 JSON 字符串，用 json_extract() 查询）
--   TIMESTAMPTZ DEFAULT now() → TEXT DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
--   DATE                    → TEXT (yyyy-MM-dd)
--
-- ⚠️ 时间统一存 UTC 的 ISO8601 文本，形如 2026-10-09T00:19:04.123Z
--    好处：字典序 == 时间序，可直接 ORDER BY；无时区歧义；SQLite 日期函数可解析。
--    展示层再按 family.timezone 转本地时间。
--
-- ⚠️ 三件必须在应用层「每个连接」都做的事（写进 DDL 只对本次连接生效）：
--    1. PRAGMA foreign_keys = ON;   —— 默认是 OFF！不设则所有 REFERENCES 形同虚设
--    2. PRAGMA journal_mode = WAL;  —— 允许「一写多读」，识别 Worker 写时不阻塞 App 读
--    3. PRAGMA busy_timeout = 5000; —— 避免瞬时并发直接抛 SQLITE_BUSY
-- =============================================================================

PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;
PRAGMA busy_timeout = 5000;
PRAGMA synchronous = NORMAL;

-- 供将来做迁移判断用：SELECT * FROM pragma_user_version;
-- v2: recognition_job 补 next_retry_at / leased_until / max_retry（队列可靠性）
-- v3: assignment 补 need_submit；answer_mode 增加 0=无需作答
-- v4: assignment 补 subject，唯一键 title → subject（title 会变，不能当身份）
PRAGMA user_version = 4;

BEGIN;

-- ---------------------------------------------------------------------------
-- 1. 家庭与身份
-- ---------------------------------------------------------------------------

-- 家庭配置：单行表，CHECK 约束保证只能有一行
CREATE TABLE family (
    id          INTEGER  PRIMARY KEY CHECK (id = 1),
    name        TEXT     NOT NULL DEFAULT '我家',
    timezone    TEXT     NOT NULL DEFAULT 'Asia/Shanghai',
    note        TEXT,
    created_at  TEXT     NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
INSERT INTO family (id, name) VALUES (1, '我家');

-- 学生档案：家里可能有多个孩子（兄弟姐妹）
CREATE TABLE student (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT    NOT NULL,
    grade           INTEGER NOT NULL DEFAULT 3,           -- 本期三年级（2026-10 改造，grade=3）
    textbook_ver    TEXT    NOT NULL DEFAULT 'RJ',        -- RJ统编/PEP...
    avatar_url      TEXT,
    daily_limit_min INTEGER NOT NULL DEFAULT 90,          -- 每日可用时长（自家自律用，非合规强制）
    sort_no         INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

-- 已授权设备：手机/平板多端共享同一个 FAMILY_TOKEN，此表仅用于标记与推送
CREATE TABLE device (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id    TEXT    NOT NULL UNIQUE,
    label        TEXT,                                    -- 如「孩子的平板」
    role         INTEGER NOT NULL DEFAULT 1,              -- 1孩子端 2家长端
    platform     TEXT    NOT NULL DEFAULT 'android',
    form_factor  TEXT    NOT NULL DEFAULT 'tablet',       -- tablet横屏 / phone竖屏
    push_token   TEXT,
    last_sync_at TEXT,
    created_at   TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

-- ---------------------------------------------------------------------------
-- 2. 作业录入：截图 → 识别 → 确认
-- ---------------------------------------------------------------------------

-- 上传的作业截图（私有部署，原图长期保留：复习时可与老师原话对照）
CREATE TABLE capture (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    student_id  INTEGER NOT NULL REFERENCES student(id),
    device_id   TEXT,
    image_hash  TEXT    NOT NULL,                         -- sha256(压缩后字节)，去重与缓存键
    storage_key TEXT    NOT NULL,                         -- 本地文件相对路径（如 images/ab/abcd….jpg）
    width       INTEGER,
    height      INTEGER,
    byte_size   INTEGER,
    status      INTEGER NOT NULL DEFAULT 1,               -- 1已上传 2已识别 3已确认
    created_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX idx_capture_hash    ON capture(image_hash);
CREATE INDEX idx_capture_student ON capture(student_id, created_at DESC);

-- 识别结果缓存：同一张图只识别一次，避免自己重复拍/重复传时重复付费
CREATE TABLE recognition_cache (
    image_hash     TEXT    PRIMARY KEY,
    raw_result     TEXT    NOT NULL,                      -- JSON：多模态模型原始输出
    model          TEXT    NOT NULL,                      -- 如 qwen-vl-max
    prompt_version TEXT    NOT NULL,                      -- 提示词版本；升级后可批量重识别
    hit_count      INTEGER NOT NULL DEFAULT 0,
    created_at     TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

-- 识别任务：**这张表同时就是队列**。
-- 单家庭场景不需要 Redis/Celery，SQLite 表 + BEGIN IMMEDIATE 原子领取足够。
-- 三个字段是队列能可靠工作的前提，缺一个都会出问题：
--   next_retry_at —— 退避后何时可再领取（没有它，429 限流时会疯狂重试）
--   leased_until  —— 租约到期时间（没有它，Worker 崩溃后任务永远卡在 status=1）
--   max_retry     —— 重试上限（没有它，坏图会无限烧钱）
CREATE TABLE recognition_job (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    capture_id   INTEGER NOT NULL REFERENCES capture(id),
    student_id   INTEGER NOT NULL REFERENCES student(id),
    status       INTEGER NOT NULL DEFAULT 0,              -- 0排队 1识别中 2成功 3失败 4已放弃
    cache_hit    INTEGER NOT NULL DEFAULT 0,              -- 0/1
    result       TEXT,                                    -- JSON：结构化草稿（学科/题项/要求）
    error_msg    TEXT,
    retry_count  INTEGER NOT NULL DEFAULT 0,
    max_retry    INTEGER NOT NULL DEFAULT 3,
    next_retry_at TEXT,                                   -- 退避：此时间之前不可领取
    leased_until TEXT,                                    -- 租约：过期即可被其他 Worker 回收
    cost_tokens  INTEGER,
    duration_ms  INTEGER,
    started_at   TEXT,
    created_at   TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    finished_at  TEXT,
    CHECK (status BETWEEN 0 AND 4)
);
CREATE INDEX idx_job_status ON recognition_job(status, created_at);
CREATE INDEX idx_job_ready  ON recognition_job(status, next_retry_at);

-- 识别任务 ←→ 作业单（多对多）
-- 为什么需要：① 一次识别可能产出多张作业单（一张图里两位老师）
--             ② 同一张作业单可能被多次识别（重识别覆盖草稿）
-- 没有它，「这条草稿是哪张截图识别出来的」查不出来，
-- 家长端的「对照老师原话」和修正记录的提示词归因都会断链。
CREATE TABLE job_assignment (
    job_id        INTEGER NOT NULL REFERENCES recognition_job(id) ON DELETE CASCADE,
    assignment_id INTEGER NOT NULL REFERENCES assignment(id) ON DELETE CASCADE,
    created_at    TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    PRIMARY KEY (job_id, assignment_id)
);
CREATE INDEX idx_job_assign ON job_assignment(assignment_id);

-- 家长对识别结果的修正记录：优化提示词的唯一依据，务必留档
CREATE TABLE recognition_correction (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id       INTEGER NOT NULL REFERENCES recognition_job(id),
    device_id    TEXT,
    field_path   TEXT    NOT NULL,                        -- 如 items[2].subject
    before_value TEXT,
    after_value  TEXT,
    created_at   TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

-- ---------------------------------------------------------------------------
-- 3. 题库与知识点（识别沉淀 + 手工录入）
-- ---------------------------------------------------------------------------

CREATE TABLE knowledge_point (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    parent_id INTEGER REFERENCES knowledge_point(id),
    subject   INTEGER NOT NULL,                           -- 1语文 2数学 3英语
    grade     INTEGER NOT NULL DEFAULT 3,
    name      TEXT    NOT NULL,
    code      TEXT    NOT NULL UNIQUE,                    -- 如 MATH6-CIRCLE-AREA
    path      TEXT    NOT NULL,                           -- 物化路径 /1/7/23/，便于查子树
    sort_no   INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_kp_subject ON knowledge_point(subject, grade);

CREATE TABLE question (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    subject        INTEGER NOT NULL,                      -- 1语文 2数学 3英语
    grade          INTEGER NOT NULL DEFAULT 3,
    qtype          INTEGER NOT NULL,                      -- 1选择 2填空 3计算 4解答 5阅读 6作文 7听写
    stem           TEXT    NOT NULL,                      -- 题干
    stem_assets    TEXT,                                  -- JSON：配图/音频的文件相对路径
    difficulty     INTEGER NOT NULL DEFAULT 3,            -- 1-5
    source         INTEGER NOT NULL DEFAULT 2,            -- 1手工 2识别沉淀 3外部导入
    origin_capture INTEGER REFERENCES capture(id),        -- 溯源：来自哪张作业截图
    status         INTEGER NOT NULL DEFAULT 1,            -- 1启用 0停用
    created_at     TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX idx_question_subject ON question(subject, grade, qtype);

-- 答案：模型生成与家长确认分开存，用 origin 区分可信度
CREATE TABLE question_answer (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    question_id INTEGER NOT NULL REFERENCES question(id) ON DELETE CASCADE,
    answer_text TEXT    NOT NULL,
    is_primary  INTEGER NOT NULL DEFAULT 1,               -- 0/1
    origin      INTEGER NOT NULL DEFAULT 2                -- 1人工确认 2模型生成
);
CREATE INDEX idx_answer_question ON question_answer(question_id);

-- 题目 ←→ 知识点（多对多；没有这张表，一学期后做不出任何学情分析）
CREATE TABLE question_knowledge (
    question_id INTEGER NOT NULL REFERENCES question(id) ON DELETE CASCADE,
    kp_id       INTEGER NOT NULL REFERENCES knowledge_point(id),
    PRIMARY KEY (question_id, kp_id)
);

-- ---------------------------------------------------------------------------
-- 4. 作业单与作业项
-- ---------------------------------------------------------------------------

-- 一个孩子一天的**某一科**作业单。
-- ⚠️ 身份键刻意用 (student_id, assign_date, subject) —— **不能用 title**。
--    踩过：家长把草稿标题改成「英语作业（已核对）」后重识别，title 变了导致唯一键不匹配，
--    于是又插入一条重复作业单（2 条变 3 条）。title 是展示属性，不是身份。
--    领域上也说得通：家长的心智就是「今天的语文作业」，一天一科一张单。
CREATE TABLE assignment (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    student_id          INTEGER NOT NULL REFERENCES student(id),
    assign_date         TEXT    NOT NULL,                 -- yyyy-MM-dd
    subject             INTEGER NOT NULL DEFAULT 9,       -- 1语文 2数学 3英语 9其他
    title               TEXT,                             -- 如「10月9日 语文作业」
    -- 截图里大量出现「无需提交」/「需要提交」标签（如英语 App 里的作业单），
    -- 不存这个字段孩子端就没法区分「要做」和「要做且要交」
    need_submit         INTEGER NOT NULL DEFAULT 1,       -- 1需提交 0无需提交
    source              INTEGER NOT NULL DEFAULT 2,       -- 1手工录入 2截图识别
    status              INTEGER NOT NULL DEFAULT 0,       -- 0草稿(待确认) 1已发布 2已完成 3已归档
    confirmed_by_device TEXT,                             -- 哪台设备确认的，便于排查
    confirmed_at        TEXT,
    created_at          TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    UNIQUE (student_id, assign_date, subject)
);
CREATE INDEX idx_assignment_student ON assignment(student_id, assign_date DESC);

-- 作业项：能结构化的关联题库题目，不能结构化的落成打卡任务
CREATE TABLE assignment_item (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    assignment_id INTEGER NOT NULL REFERENCES assignment(id) ON DELETE CASCADE,
    seq           INTEGER NOT NULL,
    subject       INTEGER NOT NULL,                       -- 1语文 2数学 3英语 9其他
    -- 1客观题(可自动批改) 2主观题(模型初评) 3听读打卡 4线下实践
    item_type     INTEGER NOT NULL,
    question_id   INTEGER REFERENCES question(id),        -- 可空：打卡类无题目
    title         TEXT    NOT NULL,                       -- 题干或任务描述
    requirement   TEXT,                                   -- 老师附加要求，如「写在作业本上」
    image_key     TEXT,                                   -- 原图裁切片段，便于对照老师原话
    est_minutes   INTEGER,                                -- 预估耗时，用于排每日计划
    status        INTEGER NOT NULL DEFAULT 0,             -- 0待做 1已做 2已批改
    sort_no       INTEGER NOT NULL DEFAULT 0,
    -- 孩子端横屏作答需要：作答方式 0无需作答 1键盘 2手写 3录音 4拍照
    -- 0 是真实存在的类别：「每天默写不少于15分钟」这类过程性要求，App 内不需要作答动作
    answer_mode   INTEGER NOT NULL DEFAULT 1
                  CHECK (answer_mode BETWEEN 0 AND 4)
);
CREATE INDEX idx_item_assignment ON assignment_item(assignment_id, sort_no);

-- ---------------------------------------------------------------------------
-- 5. 自主学习（学习板块）与作答
-- ---------------------------------------------------------------------------

-- 自主学习会话：语文/数学/英语板块里的练习，不由老师布置，**不属于任何 assignment**。
-- 没有这张表，「孩子自己在数学板块练了 20 道口算」这类行为无处记录，学情会缺一半。
CREATE TABLE practice_session (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    student_id    INTEGER NOT NULL REFERENCES student(id),
    subject       INTEGER NOT NULL,                       -- 1语文 2数学 3英语
    -- 1知识点专项 2错题重做 3口算闯关 4单词记忆 5跟读评测 6古诗默写
    mode          INTEGER NOT NULL DEFAULT 1,
    kp_id         INTEGER REFERENCES knowledge_point(id),  -- 专项练习针对的知识点，可空
    item_count    INTEGER NOT NULL DEFAULT 0,             -- 计划题数
    correct_count INTEGER NOT NULL DEFAULT 0,
    duration_ms   INTEGER,
    started_at    TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    finished_at   TEXT
);
CREATE INDEX idx_practice_student ON practice_session(student_id, started_at DESC);

-- 作答记录：数据量最大的表。
-- ⚠️ 作业与学习是两条来源，但统一落在这张表 —— 学情分析只读这一张，不做 UNION。
CREATE TABLE answer_record (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    student_id          INTEGER NOT NULL REFERENCES student(id),
    assignment_item_id  INTEGER REFERENCES assignment_item(id) ON DELETE CASCADE,  -- 来源A：老师作业
    practice_session_id INTEGER REFERENCES practice_session(id),                   -- 来源B：自主学习
    question_id         INTEGER REFERENCES question(id),
    answer_text         TEXT,
    media_keys          TEXT,                             -- JSON：录音/手写图片的文件相对路径
    is_correct          INTEGER,                          -- 0/1/NULL(待批)
    score               REAL,
    grading_mode        INTEGER,                          -- 1自动 2模型初评 3家长确认
    duration_ms         INTEGER,
    client_req_id       TEXT    NOT NULL,                 -- 幂等键：离线补传防重
    answered_at         TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    UNIQUE (client_req_id),
    -- 有且仅有一个来源非空：一次作答不可能既是老师作业又是自主练习
    CHECK (
        (assignment_item_id IS NOT NULL AND practice_session_id IS NULL) OR
        (assignment_item_id IS NULL AND practice_session_id IS NOT NULL)
    )
);
CREATE INDEX idx_answer_student  ON answer_record(student_id, answered_at DESC);
CREATE INDEX idx_answer_item     ON answer_record(assignment_item_id);
CREATE INDEX idx_answer_practice ON answer_record(practice_session_id);

-- 错题本：由 answer_record 派生并冗余，避免每次全表聚合
CREATE TABLE wrong_book (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    student_id     INTEGER NOT NULL REFERENCES student(id),
    question_id    INTEGER NOT NULL REFERENCES question(id),
    first_wrong_at TEXT    NOT NULL,
    last_wrong_at  TEXT    NOT NULL,
    wrong_count    INTEGER NOT NULL DEFAULT 1,
    review_stage   INTEGER NOT NULL DEFAULT 0,            -- 复习阶段 0-5
    next_review_at TEXT,                                  -- 下次复习时间
    mastered       INTEGER NOT NULL DEFAULT 0,            -- 0/1
    UNIQUE (student_id, question_id)
);
CREATE INDEX idx_wrong_review ON wrong_book(student_id, mastered, next_review_at);

-- 知识点掌握度：学生 × 知识点 滚动更新，家长端「进步曲线」的数据源
CREATE TABLE mastery (
    student_id  INTEGER NOT NULL REFERENCES student(id),
    kp_id       INTEGER NOT NULL REFERENCES knowledge_point(id),
    total_cnt   INTEGER NOT NULL DEFAULT 0,
    correct_cnt INTEGER NOT NULL DEFAULT 0,
    score       REAL    NOT NULL DEFAULT 0,               -- 0.0 - 1.0
    updated_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    PRIMARY KEY (student_id, kp_id)
);

-- 学情报告：周期快照，避免家长端每次实时算
CREATE TABLE study_report (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    student_id   INTEGER NOT NULL REFERENCES student(id),
    period_type  INTEGER NOT NULL,                        -- 1周 2月
    period_start TEXT    NOT NULL,                        -- yyyy-MM-dd
    payload      TEXT    NOT NULL,                        -- JSON：完成率/正确率/弱项知识点
    created_at   TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    UNIQUE (student_id, period_type, period_start)
);

COMMIT;

-- =============================================================================
-- 运维备忘
--
-- 【热备份】不要在应用运行时直接 cp 数据库文件（WAL 下会漏数据），用：
--   sqlite3 data/app.db "VACUUM INTO 'backup/app-20261009.db'"
--
-- 【必备索引自检】SQLite 没有 pg_stat_statements，用：
--   EXPLAIN QUERY PLAN SELECT ... ;   看是否走了索引
--
-- 【JSON 查询】用 json_extract，例如查识别结果里语文的题目数：
--   SELECT json_extract(result, '$.items') FROM recognition_job WHERE id = 1;
--
-- 【迁移版本】改表结构时同步 PRAGMA user_version，应用启动时读它决定是否执行迁移。
--
-- 【容量预估】一个孩子一学期：约 500 条作答、2000 道题、几百张图。
--   库本体 < 50MB，图片走文件系统不进口袋。单文件数据库绰绰有余。
-- =============================================================================
