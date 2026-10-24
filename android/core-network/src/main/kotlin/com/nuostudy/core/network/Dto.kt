package com.nuostudy.core.network

import kotlinx.serialization.Serializable

// =============================================================================
// 传输对象（DTO）。字段名与 docs/api/openapi.yaml 一一对应，**不要在这里改名** ——
// 契约是前后端唯一的握手文件，客户端擅自改名会直接对不上。
//
// ⚠️ 枚举一律用 Int 而不是 Kotlin enum：服务端将来新增取值（比如多一种作业类型），
//    老客户端遇到未知数字只会显示得不好看，而用 enum 会直接反序列化崩溃。
//    展示用的中文名放在 core-ui 的映射表里，别写进 DTO。
// =============================================================================

// ---------------- 系统与家庭 ----------------
@Serializable
data class PingData(
    val service: String,
    val version: String,
    val model: String? = null,
    val promptVersion: String? = null,
)

@Serializable
data class FamilyDto(
    val id: Int = 1,
    val name: String? = null,
    val timezone: String = "Asia/Shanghai",
    val note: String? = null,
)

@Serializable
data class DeviceDto(
    val id: Int = 0,
    // ⚠️ 这里**不能**写 @SerialName("device_id")。服务端出口统一走 store.camelize()，
    //    送到客户端的键是 `deviceId`（snake_case 只存在于 SQLite 列名里）。
    //    挂上 @SerialName("device_id") 后，用这一个 DTO 同时做请求体（POST /devices，
    //    服务端读 body.deviceId）和响应体，两端键名就都对不上：
    //    响应里找不到 `device_id` → 必填字段缺失直接抛 MissingFieldException，
    //    请求体发出去的是 `device_id` → 服务端 pydantic 读不到，落库成空。
    val deviceId: String = "",
    val label: String? = null,
    val role: Int = 1,
    val formFactor: String? = null,
    val platform: String? = null,
    val lastSyncAt: String? = null,
)

@Serializable
data class StudentDto(
    val id: Int,
    val name: String,
    val grade: Int = 3,
    val textbookVer: String = "RJ",
    val avatarUrl: String? = null,
    val dailyLimitMin: Int = 90,
)

// ---------------- 截图上传与识别 ----------------
@Serializable
data class CaptureUploadResult(
    val captureId: Int,
    val jobId: Int,
    val imageHash: String,
    val storageKey: String,
    /** true = 这张图之前传过，服务端复用了已有记录（可能直接命中识别缓存，0 token） */
    val dedup: Boolean = false,
    val note: String? = null,
)

@Serializable
data class RecognitionJobDto(
    val jobId: Int,
    val captureId: Int,
    /** 0 排队 / 1 识别中 / 2 成功 / 3 失败 */
    val status: Int,
    val statusName: String? = null,
    val cacheHit: Boolean = false,
    val retryCount: Int = 0,
    val maxRetry: Int = 3,
    val error: String? = null,
    val nextRetryAt: String? = null,
    val tokens: Int? = null,
    val durationMs: Int? = null,
    val createdAt: String? = null,
    val finishedAt: String? = null,
    /** 识别原始草稿（仅 status=2 时有）。字段是动态的，保持 JsonObject 不强行建模。 */
    val draft: kotlinx.serialization.json.JsonObject? = null,
    val assignments: List<AssignmentDto> = emptyList(),
)

// ---------------- 作业单 ----------------
@Serializable
data class AssignmentDto(
    val id: Int,
    val studentId: Int = 0,
    val assignDate: String,
    val title: String? = null,
    val subject: Int = 9,
    val needSubmit: Boolean = true,
    /** 1 手工录入 / 2 截图识别 */
    val source: Int = 2,
    /** 0 草稿（待家长确认）/ 1 已发布 / 2 已完成 / 3 已归档 */
    val status: Int,
    val statusName: String? = null,
    val confirmedAt: String? = null,
    val items: List<AssignmentItemDto> = emptyList(),
)

@Serializable
data class AssignmentItemDto(
    val id: Int,
    val seq: Int,
    val subject: Int = 9,
    val itemType: Int = 1,
    val title: String = "",
    val requirement: String? = null,
    /**
     * ⚠️ 作答方式，直接决定界面上出现什么控件：
     *  0 = 无需作答（如「每天默读 15 分钟」这类打卡项）
     *  1 = 自绘键盘   2 = 手写画布   3 = 录音   4 = 拍照
     * 孩子端渲染时**必须**按它分流，别默认给一个输入框。
     */
    val answerMode: Int = 1,
    val estMinutes: Int? = null,
    val imageKey: String? = null,
    val questionId: Int? = null,
    /** 0 待做 / 1 已做 / 2 已批改 */
    val status: Int = 0,
)

// ---------------- 调试：学生端实时画面 ----------------
/**
 * 服务端此刻收到了哪些设备的画面。
 *
 * ⚠️ 与 [AssignmentDto] 那批不同，这是**测试期调试接口**的返回体，
 *    不在 docs/api/openapi.yaml 的产品契约里（服务端把它挂在 `api/v1/debug/` 前缀下）。
 *    服务端重启后内存里的帧就没了 → 这个列表会是空的，那不是故障。
 */
@Serializable
data class FramesStateDto(
    val frames: List<FrameInfoDto> = emptyList(),
    val devices: Int = 0,
    val serverTime: Double = 0.0,
    /** 服务端抓整屏通道的实况。用来在切换按钮上告诉家长「这条路通不通」。 */
    val adb: AdbStateDto? = null,
)

@Serializable
data class FrameInfoDto(
    val deviceId: String = "",
    /** 学生端自己填的名字，形如「孩子端 <平板型号>」。多设备时靠它区分。 */
    val label: String? = null,
    /**
     * 采集通道。[SOURCE_APP]＝孩子端 App 用 PixelCopy 截的自己的窗口；
     * [SOURCE_ADB]＝服务端用无线 adb 抓的整屏。
     *
     * ⚠️ **必须拿它跟当前选的通道比对**再显示 label/seq：两条通道各有一份帧，
     *    `frames` 是按时间倒序混在一起的。取 `firstOrNull()` 会有一半概率
     *    拿到另一条通道的元信息，表现是「画面换了但帧号/设备名没换」。
     */
    val source: String = MirrorSource.APP,
    /** 服务端自增帧号。**用它判断「画面有没有在动」**，比时间戳可靠。 */
    val seq: Int = 0,
    val at: Double = 0.0,
    /** 这一帧是多久以前到的（秒）。服务端算好给的，客户端只负责显示。 */
    val ageSec: Double = 0.0,
    val kb: Double = 0.0,
    /** 内存里还留着这个设备的多少帧。 */
    val kept: Int = 0,
)

@Serializable
data class AdbStateDto(
    /** 服务端配好了 adb 目标地址与二进制。false 时切过去只会等一个 503。 */
    val enabled: Boolean = false,
    val target: String? = null,
    /** 抓帧线程正在跑（有别人在看时才会跑一会儿，之后自己停）。 */
    val running: Boolean = false,
    val grabs: Int = 0,
    val failures: Int = 0,
    val lastError: String? = null,
)

/**
 * 实时画面的采集通道。
 *
 * 用**字符串常量**而不是 enum，因为它要同时出现在三个地方：URL 查询参数、
 * DataStore 存的偏好、服务端返回的 JSON。enum 在这三处都得各自转一次，
 * 而每转一次就多一个「转错了但编译通过」的机会。
 */
object MirrorSource {
    /** 孩子端 App 自己用 PixelCopy 截窗口。零权限，但只看得到 App 的界面。 */
    const val APP = "app"
    /** 服务端用无线 adb 抓整屏。能看到系统弹窗与桌面，代价是要在 NAS 上放一份 adb。 */
    const val ADB = "adb"

    /** 界面上的顺序就是这个 —— 「App 内截图」在前，因为它是零配置的那条。 */
    val ALL = listOf(APP, ADB)

    fun isValid(source: String?): Boolean = source in ALL

    /** 认不出来的一律当 [APP]：这是唯一一条不需要任何额外配置的通道。 */
    fun orDefault(source: String?): String = if (isValid(source)) source!! else APP
}
