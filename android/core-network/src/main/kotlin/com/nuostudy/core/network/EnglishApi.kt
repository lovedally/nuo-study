package com.nuostudy.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * 英语能力的接口（TTS / 转写 / 对话 / 评测）—— 2026-10-23 融合进来的那一半。
 *
 * ## 为什么不塞进 [StudyApi]
 *
 * 学业接口的返回体统一是 `Envelope{code,message,data}`（见 StudyApi 的类注释），
 * 而英语这几个端点**沿用的是原来那个 Node 服务的裸 JSON**：成功直接
 * `{"url": "..."}`、失败是 `{"error": "..."}`。这不是懒，是必须的 ——
 * 英语网页 `web/english/app.js` 是编译产物，读的就是这两个键。
 *
 * 硬把它们包进 Envelope 只有两种结果：服务端给两种形状各写一套（同一个能力两个
 * 实现，迟早不一致），或者改前端（改不动）。所以这里**单独一个 Retrofit 接口 +
 * 独立 DTO**，跟 StudyApi 平行，共用同一个 OkHttp（地址、令牌、设备号都由
 * DynamicUrlInterceptor 统一注入）。
 *
 * ## 路径
 *
 * 走 `/api/v1/english/…` 这套规范路径。老的 `/api/tts`、`/api/chat` 之类是
 * 兼容路径，留给英语网页，新代码不要再用。
 */
// ⚠️ 写 KDoc 时**不要**出现英文块注释的起始符（斜杠 + 星号）。Kotlin 的块注释可以嵌套，
//    在文档里写路径通配符会多开一层注释，把后面整个文件吞进去 —— 现象是 KSP 报
//    「EnglishApi could not be resolved」，而文件本身看起来毫无问题（2026-10-23 踩过）。
interface EnglishApi {

    /**
     * 四个能力各自配好了没。
     *
     * ⚠️ 这个接口**免鉴权**（网页那边拿不到令牌），所以它只报布尔值，
     *    绝不返回任何密钥片段 —— 服务端 english.py 里也是这么保证的。
     */
    @GET("api/v1/english/health")
    suspend fun health(): EnglishHealthDto

    /**
     * 文本 → 语音。返回的 [TtsResponse.url] 是**相对路径**
     * （`/generated-tts/<hash>.mp3`），要拼上服务端地址才能播。
     *
     * 服务端按 `sha256(voice|text)` 做磁盘缓存，同一句重复调用只是查一次文件，
     * 所以界面上可以放心地每次点击都请求。
     */
    @POST("api/v1/english/tts")
    suspend fun tts(@Body body: TtsRequest): TtsResponse

    /**
     * 整本教材（8 个单元、单词、课文、句型、练习），一次拿全约 60 KB。
     *
     * ⚠️ 这个接口**不带** `{code,data}` 信封，也**不套** Envelope —— 它和
     *    TTS 那几条一样是裸 JSON（网页 `app.js` 直接读 `units` 字段）。
     *    返回体里的字段全是可选的，服务端加字段不会让老客户端崩
     *    （`ignoreUnknownKeys = true` + 全量默认值）。
     *
     * 内容只有一份，在服务端 `web/english/curriculum.json`。客户端**不做本地库**：
     * 一是内容一学期改一两次，二是写进 Room 就得处理「教材更新了但孩子离线」
     * 该用哪一版的问题，为一个只读的 60KB 文件付这个代价不值。
     */
    @GET("api/v1/english/curriculum")
    suspend fun curriculum(): CurriculumDto

    /**
     * 跟读评测（讯飞语音评测，返回形状沿用腾讯智聆契约）。
     *
     * [EvaluateRequest.audio] 是 **data URI**（`data:audio/wav;base64,…`），
     * 服务端会自己用 ffmpeg 转成 16k 单声道 PCM 再送智聆 —— 客户端只要能给出
     * 任何 ffmpeg 认得的容器就行，不需要在这里做重采样。
     *
     * ⚠️ [EvaluateRequest.text] 必须是 **1~30 个英文单词**，服务端会校验并 422。
     */
    @POST("api/v1/english/evaluate")
    suspend fun evaluate(@Body body: EvaluateRequest): EvaluateResponse

    /** 语音转写（千问 ASR）。[TranscribeRequest.audio] 同样是 data URI。 */
    @POST("api/v1/english/transcribe")
    suspend fun transcribe(@Body body: TranscribeRequest): TranscribeResponse

    /**
     * 陪练对话。返回 `{text, audioUrl}`；[ChatResponse.audioUrl] 可能是空串
     * （TTS 失败时服务端刻意不整个失败，文本照样给）。
     */
    @POST("api/v1/english/chat")
    suspend fun chat(@Body body: ChatRequest): ChatResponse

    /**
     * 上报学习进度（**增量**：这次新增了多少，不是覆盖）。
     *
     * 服务端做加法，所以平板和网页可以同时上报，汇总到一起。
     * ⚠️ 正因为是加法，**同一次动作绝不能报两次** —— 去重必须在调用方做。
     */
    @POST("api/v1/english/progress")
    suspend fun reportProgress(@Body body: ProgressIn): SimpleOk

    /**
     * 读学情汇总。网页、平板、家长手机用的是**同一个接口**，
     * 所以「孩子在平板上学的」家长在手机上看得到。
     */
    @GET("api/v1/english/progress")
    suspend fun progress(@Query("days") days: Int = 7): ProgressSummary

    // 转写 / 对话 / 评测的客户端接入还没做（要动录音链路，见 feature-assistant 的
    // AudioEngine）。服务端已经就绪：POST api/v1/english/{transcribe,chat,evaluate}。
    // 这里**刻意不提前声明** —— 声明了却没人调，跟没声明一样，还多一份要维护的 DTO。
}

/** 四个能力各自配好了没。 */
@Serializable
data class EnglishServicesDto(
    val azureTts: Boolean = false,
    val tencentSoe: Boolean = false,
    val qwenChat: Boolean = false,
    /** 语音转写。服务端现在走**千问 ASR**（与对话共用一把 key），不需要 OpenAI key。 */
    val transcription: Boolean = false,
)

@Serializable
data class EnglishHealthDto(
    val ok: Boolean = false,
    val services: EnglishServicesDto = EnglishServicesDto(),
    val region: String = "",
    val voice: String = "",
    val chatModel: String = "",
    /** 例如 `qwen:qwen3-asr-flash` / `openai:gpt-transcribe`；空串＝没配。 */
    val transcribeEngine: String = "",
    val asrModel: String = "",
    /** 服务端有没有 ffmpeg —— 没有的话跟读评测和转写都会失败，界面上要如实说。 */
    val ffmpeg: Boolean = false,
)

@Serializable
data class TtsRequest(val text: String)

@Serializable
data class TtsResponse(val url: String = "")

// ---------------------------------------------------------------- 课程内容
//
// 全部字段都给默认值：服务端少一个字段、或把 null 写成缺失，客户端都还能跑。
// 这不只是防御 —— 教材是手工校对后「改 JSON 就上线」的，字段形态本来就会变。

@Serializable
data class CurriculumDto(
    val version: Int = 0,
    val source: String = "",
    val updatedAt: String = "",
    val units: List<CurriculumUnitDto> = emptyList(),
)

/**
 * 一个单元。
 *
 * [status] 是**必须**如实展示的：教材是分批录入的，`missing` 表示这个单元
 * 只有标题、正文还没录。列表里把它标成「未录入」而不是让孩子点进去看到空白。
 */
@Serializable
data class CurriculumUnitDto(
    val unit: Int = 0,
    /** 例如 `Module 1 Good qualities`。 */
    val module: String = "",
    /** 英文标题，如 `Try your best`。 */
    val title: String = "",
    /** 中文标题，如 `尽我所能`。 */
    val titleZh: String = "",
    val bigQuestion: String = "",
    /** `ready` 有全部内容 / `partial` 只有部分 / `missing` 只有标题。 */
    val status: String = "missing",
    val words: List<CurriculumWordDto> = emptyList(),
    val sentences: List<CurriculumSentenceDto> = emptyList(),
    val passage: CurriculumPassageDto? = null,
    val quiz: List<CurriculumQuizDto> = emptyList(),
) {
    /** 有正文可学 —— 界面据此决定是渲染内容还是渲染「未录入」。 */
    val ready: Boolean get() = status != "missing" && words.isNotEmpty()
}

@Serializable
data class CurriculumWordDto(
    val en: String = "",
    val zh: String = "",
    /** 教材生词表里带 `*` 的是非核心词（认得即可，不要求拼写）。 */
    val core: Boolean = true,
    val phonetic: String = "",
    val example: String = "",
    /** 记忆提示，例如「try 是尝试，best 是最好的」。可能为空。 */
    val tip: String = "",
)

@Serializable
data class CurriculumSentenceDto(val en: String = "")

@Serializable
data class CurriculumPassageDto(
    val title: String = "",
    val page: Int? = null,
    val lines: List<String> = emptyList(),
)

@Serializable
data class CurriculumQuizDto(
    val q: String = "",
    val options: List<String> = emptyList(),
    /** [options] 里的下标。越界时界面要能兜住，不要直接取。 */
    val answer: Int = 0,
    val explain: String = "",
)

// ---------------------------------------------------------------- 录音相关
//
// 这三个接口的字段名是**服务端的原始 JSON**，照抄不改名（用 @SerialName）。
// 改成驼峰反而要在中间加一层映射，而这一层以后一定会有人忘了映射新字段。
// 改成驼峰反而要在中间加一层映射，而这一层以后一定会有人忘了映射新字段。

@Serializable
data class EvaluateRequest(
    val text: String,
    /** data URI，见 [EnglishApi.evaluate]。 */
    val audio: String,
)

@Serializable
data class EvaluateResponse(val result: SoeResult = SoeResult())

/**
 * 评测结果（后端引擎是讯飞语音评测，但返回形状保持腾讯智聆的契约不变）。
 *
 * ⚠️ **单位不统一，展示前必须换**（这是最容易出错的地方）：
 *   · [SuggestedScore] / [PronAccuracy] —— 0~100
 *   · [PronFluency] / [PronCompletion] —— **0~1 的比例**，不是百分制。
 *     直接把 1.0 当「1 分」展示，孩子会看到「流利度 1 分」。
 */
@Serializable
data class SoeResult(
    @SerialName("SuggestedScore") val suggestedScore: Double = 0.0,
    @SerialName("PronAccuracy") val pronAccuracy: Double = 0.0,
    @SerialName("PronFluency") val pronFluency: Double = 0.0,
    @SerialName("PronCompletion") val pronCompletion: Double = 0.0,
    @SerialName("Words") val words: List<SoeWord> = emptyList(),
)

/**
 * 逐词结果。
 *
 * [matchTag]：0 读对 / 1 漏读 / 2 读错 / 3 多读（腾讯的原始枚举）。
 * [pronAccuracy] 在漏读的词上是 **-1**，不是 0 —— 展示时要判 `>= 0`，
 * 否则漏读的词会显示「准确度 -1」。
 */
@Serializable
data class SoeWord(
    @SerialName("Word") val word: String = "",
    @SerialName("MatchTag") val matchTag: Int = 0,
    @SerialName("PronAccuracy") val pronAccuracy: Double = -1.0,
    @SerialName("ReferenceWord") val referenceWord: String = "",
) {
    val correct: Boolean get() = matchTag == 0
    val missed: Boolean get() = matchTag == 1
    val wrong: Boolean get() = matchTag == 2
}

@Serializable
data class TranscribeRequest(val audio: String)

@Serializable
data class TranscribeResponse(val text: String = "")

@Serializable
data class ChatRequest(val messages: List<ChatTurn>)

@Serializable
data class ChatTurn(val role: String, val content: String)

@Serializable
data class ChatResponse(
    val text: String = "",
    /** 相对路径（`/generated-tts/xxx.mp3`），要拼服务端地址才能播；可能是空串。 */
    val audioUrl: String = "",
)

// ---------------------------------------------------------------- 学情进度

@Serializable
data class SimpleOk(val ok: Boolean = false)

/**
 * 一次进度上报（**增量**）。
 *
 * [day] 留空则用服务端日期；客户端传自己的日期是为了「晚上 11 点学的算哪天」
 * 按孩子设备的时钟算，而不是按 NAS 的时区。
 */
@Serializable
data class ProgressIn(
    val unit: Int,
    val day: String = "",
    val wordsSeen: Int = 0,
    val wordsKnown: Int = 0,
    val quizTotal: Int = 0,
    val quizCorrect: Int = 0,
    val speakCount: Int = 0,
    /** 这一次跟读的总分（0~100）。服务端累加后算平均。 */
    val speakScore: Double? = null,
)

@Serializable
data class ProgressSummary(
    val ok: Boolean = false,
    val student: ProgressStudent? = null,
    val range: ProgressRange? = null,
    val totals: ProgressTotals = ProgressTotals(),
    val daily: List<ProgressDay> = emptyList(),
    val units: List<ProgressUnit> = emptyList(),
)

@Serializable
data class ProgressStudent(
    val id: Int = 0,
    val name: String = "",
    val grade: Int = 0,
)

@Serializable
data class ProgressRange(
    val days: Int = 7,
    val from: String = "",
    val to: String = "",
)

@Serializable
data class ProgressTotals(
    val days: Int = 0,
    val wordsSeen: Int = 0,
    val wordsKnown: Int = 0,
    val quizTotal: Int = 0,
    val quizCorrect: Int = 0,
    /** 0~1 的比例，不是百分数。 */
    val quizAccuracy: Double = 0.0,
    val speakCount: Int = 0,
    /** 0~100 的平均分。 */
    val speakAvg: Double = 0.0,
)

@Serializable
data class ProgressDay(
    val day: String = "",
    val wordsSeen: Int = 0,
    val wordsKnown: Int = 0,
    val quizTotal: Int = 0,
    val quizCorrect: Int = 0,
    val quizAccuracy: Double = 0.0,
    val speakCount: Int = 0,
    val speakAvg: Double = 0.0,
)

@Serializable
data class ProgressUnit(
    val unit: Int = 0,
    val wordsSeen: Int = 0,
    val wordsKnown: Int = 0,
    val quizTotal: Int = 0,
    val quizCorrect: Int = 0,
    val quizAccuracy: Double = 0.0,
    val speakCount: Int = 0,
    val speakAvg: Double = 0.0,
)
