package com.nuostudy.feature.english

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuostudy.core.network.ChatRequest
import com.nuostudy.core.network.ChatTurn
import com.nuostudy.core.network.CurriculumUnitDto
import com.nuostudy.core.network.EnglishApi
import com.nuostudy.core.network.EvaluateRequest
import com.nuostudy.core.network.ProgressIn
import com.nuostudy.core.network.ProgressSummary
import com.nuostudy.core.network.ServerEndpoint
import com.nuostudy.core.network.SoeResult
import com.nuostudy.core.network.TranscribeRequest
import com.nuostudy.core.network.TtsRequest
import com.nuostudy.feature.english.audio.VoiceRecorder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDate
import javax.inject.Inject

/**
 * 英语页的网络、课程内容、录音与播放状态。
 *
 * ## 为什么为这件事单独建一个 ViewModel
 *
 * 改造前这里是一个写死的常量：`private const val SERVER = "http://192.168.1.10:4173"`。
 * 那时英语是一个**独立服务**（NAS 上的 Node 容器，4173）。2026-10-23 融合之后
 * 英语和学业在同一个服务、同一个端口，家长在「设置」页填的地址就是唯一的地址 ——
 * 这时再留一个写死的 4173 会变成最坏的那种 bug：**设置页改了、学业通了、
 * 只有英语还连老端口**，而且看起来像是「英语接口坏了」。
 *
 * 所以这里从 [ServerEndpoint] 读地址（就是设置页那份，内置默认 8092）。
 *
 * ## 课程内容为什么缓存在 companion 里
 *
 * [hiltViewModel] 拿到的实例跟着**页签**走：从「英语」切到「数学」再切回来，
 * ViewModel 会重建、`init` 会重跑。课程 JSON 有 60 KB，改成每次进页面都请求一次
 * 就是「每切一次学科转一次圈」。所以缓存必须是**进程级**的（[CurriculumCache]），
 * 而不是实例级的。
 */
@HiltViewModel
class EnglishViewModel @Inject constructor(
    private val api: EnglishApi,
    private val endpoint: ServerEndpoint,
) : ViewModel() {

    /** 「云服务 x/3 已配置」那一行。失败时直接说连不上，不装成「0/3」。 */
    var cloud by mutableStateOf("正在检查云服务…")
        private set

    /** 一次性提示（TTS 失败等）。UI 显示完调用 [consumeNotice] 清掉。 */
    var notice by mutableStateOf("")
        private set

    /** 四个能力里到底哪几个可用 —— 界面据此决定按钮是灰的还是能点的。 */
    var services by mutableStateOf(EnglishServices())
        private set

    /**
     * health 是否**已经查过**（成功或失败都算）。
     *
     * ⚠️ 没有它就会误伤：`services` 的初始值全是 false，进页面立刻点「听示范」
     *    会命中 `!services.azureTts` 分支，弹一句「服务端还没配 Azure 语音密钥」——
     *    而实际只是 health 还没回来。把「未知」当成「没有」是最容易骗人的那种提示。
     */
    var healthLoaded by mutableStateOf(false)
        private set

    /** 教材单元。空列表 = 还没加载出来，界面要显示加载态而不是空白。 */
    var units by mutableStateOf<List<CurriculumUnitDto>>(emptyList())
        private set

    /** 课程加载失败的原因。非空时界面给「重试」，不要静默停在加载中。 */
    var curriculumError by mutableStateOf("")
        private set

    var curriculumLoading by mutableStateOf(false)
        private set

    /** 答错的题。只在本次使用内累积 —— 不做持久化，关掉 App 就清空。 */
    var mistakes by mutableStateOf<List<Mistake>>(emptyList())
        private set

    /** 今天听过多少段录音（课文原音 + 示范音都算）。学习报告用。 */
    var audioPlays by mutableStateOf(0)
        private set

    /** 已经翻过的单词（按 `en` 去重）。学习报告用。 */
    var wordsSeen by mutableStateOf<Set<String>>(emptySet())
        private set

    // ---------------------------------------------------------------- 录音

    private val recorder = VoiceRecorder()

    /** 是否正在录。UI 据此显示「松手结束」。 */
    var recording by mutableStateOf(false)
        private set

    /** 录音已经持续多少毫秒（每 150ms 刷一次）。 */
    var recordMs by mutableIntStateOf(0)
        private set

    /**
     * 这次录音是给谁用的：`"eval"`（跟读评测）/ `"chat"`（自由对话）/ `""`（没在录）。
     *
     * ⚠️ 必须有它：跟读和对话用的是**同一个录音器**，UI 上又是两个按钮。
     *    只靠 `recording` 判断的话，按住「跟读」时「按住说话」也会显示成录音中，
     *    孩子会以为两个都在录。更要紧的是——如果在录 A 的时候松手到 B 上，
     *    没有用途标记就分不清这段音频该送去评测还是转写。
     */
    var recordPurpose by mutableStateOf("")
        private set

    /** 上传 + 识别/评测中。期间禁用按钮，避免重复提交。 */
    var busy by mutableStateOf(false)
        private set

    /** 最近一次跟读评测的结果。null = 还没评过。 */
    var evalResult by mutableStateOf<SoeResult?>(null)
        private set

    /** 被评测的那句话 —— 结果卡片要和它一起显示，否则孩子不知道在评哪句。 */
    var evalTarget by mutableStateOf("")
        private set

    /** 对话记录（含孩子说的和 AI 回的）。 */
    var turns by mutableStateOf<List<ChatTurn>>(emptyList())
        private set

    /** 对话区的一句话状态（录音中/识别中/在回复）。 */
    var dialogStatus by mutableStateOf("")
        private set

    private var timerJob: Job? = null

    // ---------------------------------------------------------------- 学情（跨端同步）

    /**
     * 最近 7 天的学情汇总，来自服务端 —— **网页、平板、家长手机读的是同一份**。
     *
     * null = 还没加载出来（界面显示「正在读取…」而不是一堆 0，
     * 一堆 0 会被家长理解成「孩子什么都没学」）。
     */
    var progress by mutableStateOf<ProgressSummary?>(null)
        private set

    var progressLoading by mutableStateOf(false)
        private set

    var progressError by mutableStateOf("")
        private set

    /**
     * 待上报的**增量**（按单元分桶）。
     *
     * 为什么不每个动作发一个请求：翻一个单词就是一次，一个单元 20 多个词 +
     * 4 道题 + 若干次跟读，全发就是几十个请求。攒 2.5 秒再发一次足够实时，
     * 又能把「连续翻 10 个词」压成一个请求。
     *
     * ⚠️ 服务端是**加法**，所以这些数字只能在成功发出后清掉；失败了要加回去，
     *    否则家长看到的学情会莫名其妙少一截。
     */
    private val pendingProgress = mutableMapOf<Int, PendingProgress>()
    private var flushJob: Job? = null

    private class PendingProgress {
        var wordsSeen = 0
        var wordsKnown = 0
        var quizTotal = 0
        var quizCorrect = 0
        var speakCount = 0
        var speakScoreSum = 0.0

        val isEmpty: Boolean
            get() = wordsSeen == 0 && wordsKnown == 0 && quizTotal == 0 &&
                quizCorrect == 0 && speakCount == 0
    }

    private var player: MediaPlayer? = null

    data class EnglishServices(
        val azureTts: Boolean = false,
        val tencentSoe: Boolean = false,
        val qwenChat: Boolean = false,
        val transcription: Boolean = false,
        val ffmpeg: Boolean = false,
    )

    init {
        refreshCloud()
        loadCurriculum()
    }

    fun refreshCloud() {
        cloud = "正在检查云服务…"
        viewModelScope.launch {
            runCatching { api.health() }
                .onSuccess { h ->
                    healthLoaded = true
                    services = EnglishServices(
                        azureTts = h.services.azureTts,
                        tencentSoe = h.services.tencentSoe,
                        qwenChat = h.services.qwenChat,
                        transcription = h.services.transcription,
                        ffmpeg = h.ffmpeg,
                    )
                    val ready = listOf(h.services.azureTts, h.services.qwenChat,
                        h.services.tencentSoe).count { it }
                    cloud = buildString {
                        append("云服务 $ready/3 已配置")
                        if (!h.ffmpeg) append(" · 缺少 ffmpeg")
                    }
                }
                .onFailure {
                    healthLoaded = true
                    // 连不上时**不要**报「0/3」：那看起来像「key 没配」，
                    // 而真正的原因是服务端地址不通，两件事的排查方向完全相反。
                    cloud = "暂时连不上服务端"
                }
        }
    }

    /**
     * 拉课程。
     *
     * 有进程级缓存时**先上屏、再后台刷新**：孩子看到的是立刻有内容，
     * 而教材真改过的话下一次进来就是新的。
     *
     * ⚠️ 一定要 `Dispatchers.IO`。Retrofit 的 suspend 调用本身会切线程，
     *    但 `Json` 解析 60 KB 发生在回调线程之外的那一段；更关键的是**第一次**
     *    调用会把 OkHttp / kotlinx.serialization 的初始化带上主线程
     *    （实测切页首帧 200~550ms，有一部分是它）。整段丢进 IO 最省心。
     */
    fun loadCurriculum(force: Boolean = false) {
        if (curriculumLoading) return
        val cached = CurriculumCache.units
        if (cached != null && !force) {
            units = cached
            curriculumError = ""
        }
        curriculumLoading = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { api.curriculum().units }
            }
            curriculumLoading = false
            result
                .onSuccess { list ->
                    if (list.isEmpty()) {
                        curriculumError = "服务端返回的课程是空的（检查 curriculum.json 是否部署）"
                    } else {
                        units = list
                        CurriculumCache.units = list
                        curriculumError = ""
                    }
                }
                .onFailure { e ->
                    // 有缓存就继续用缓存，只把错误留在 `curriculumError` 里
                    // （界面上有内容时不该因为一次刷新失败就整页变成错误页）。
                    if (units.isEmpty()) {
                        curriculumError = "课程加载失败：${friendly(e)}"
                    }
                }
        }
    }

    /** 播课文原音。传相对路径（`audio/track-01.mp3`），由这里补全成完整地址。 */
    fun playRelative(path: String) = play(absolute(path))

    /** 点「听标准示范」：先让服务端合成，再播返回的地址。 */
    fun speak(text: String) {
        if (text.isBlank()) return
        // 只有**确认过**没配才拦。health 还没回来时直接把请求发出去，
        // 让服务端说真话 —— 总比「明明配了却说没配」好。
        if (healthLoaded && !services.azureTts) {
            // 不去发那个注定失败的请求：报错会是一句「尚未配置 XFYUN_APPID /
            // XFYUN_APIPASSWORD」，对家长来说不如直接说该去做什么。
            // （services key 名沿用 azureTts 是历史契约，背后已是讯飞合成。）
            notice = "服务端还没配置讯飞语音合成，先在 .env 里补 XFYUN_APPID / XFYUN_APIPASSWORD"
            return
        }
        viewModelScope.launch {
            runCatching { api.tts(TtsRequest(text)) }
                .onSuccess { result ->
                    val url = result.url
                    if (url.isNotBlank()) {
                        play(absolute(url))
                    } else {
                        notice = "语音合成没有返回地址"
                    }
                }
                .onFailure { notice = "语音合成失败：${friendly(it)}" }
        }
    }

    // ---------------------------------------------------------------- 录音 / 评测

    /**
     * 开始录音。
     *
     * @param purpose `"eval"`（跟读评测）或 `"chat"`（自由对话），只用于 UI 区分按钮态。
     * @return false 表示麦克风起不来（没有权限 / 被别的 App 占着 / 已经在录了）。
     *         UI 要把这个 false 变成一句人话，不能什么都不做。
     */
    fun startRecording(purpose: String): Boolean {
        if (recording || busy) return false
        val ok = recorder.start()
        if (!ok) return false
        recording = true
        recordPurpose = purpose
        recordMs = 0
        // 计时器每 150ms 更新一次。只影响界面上那个「0.0 秒」的小文本，
        // 重组范围很小；用 16ms 去刷才是真的浪费。
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (recording) {
                delay(150)
                recordMs = recorder.durationMs()
            }
        }
        return true
    }

    /** 放弃录音（点了取消、或页面被切走）。 */
    fun cancelRecording() {
        recording = false
        recordPurpose = ""
        timerJob?.cancel()
        recorder.cancel()
        recordMs = 0
    }

    /**
     * 停止录音并送去**跟读评测**。
     *
     * @param unit 当前单元（学情要按单元归属）
     * @param refText 要跟读的句子。服务端限制 1~30 个单词。
     */
    fun stopAndEvaluate(unit: Int, refText: String) {
        if (!recording) return
        recording = false
        recordPurpose = ""
        timerJob?.cancel()
        recordMs = 0

        val wav = recorder.stop()
        if (wav == null) {
            notice = "没录到声音，按住按钮把整句说完再松手"
            return
        }
        evalTarget = refText
        evalResult = null
        busy = true
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    api.evaluate(EvaluateRequest(text = refText, audio = wavDataUri(wav)))
                }
            }
            busy = false
            res.onSuccess { r ->
                evalResult = r.result
                // 0 分通常是「没录到声音 / 麦克风被占」，不是一次学习行为 ——
                // 报上去只会把家长看到的平均分拉下来，还不明所以。
                if (r.result.suggestedScore > 0) {
                    queueProgress(unit) { p ->
                        p.speakCount += 1
                        p.speakScoreSum += r.result.suggestedScore
                    }
                }
            }.onFailure { notice = "跟读评测失败：${friendly(it)}" }
        }
    }

    /**
     * 停止录音并送去**自由对话**：转写 → 带上下文问千问 → 播回复音频。
     */
    fun stopAndChat() {
        if (!recording) return
        recording = false
        recordPurpose = ""
        timerJob?.cancel()
        recordMs = 0

        val wav = recorder.stop()
        if (wav == null) {
            notice = "没录到声音，按住按钮说完再松手"
            return
        }
        dialogStatus = "正在识别…"
        busy = true
        viewModelScope.launch {
            val heard = withContext(Dispatchers.IO) {
                runCatching { api.transcribe(TranscribeRequest(wavDataUri(wav))).text }
            }
            heard.onFailure {
                busy = false
                dialogStatus = ""
                notice = "语音识别失败：${friendly(it)}"
            }.onSuccess { text ->
                if (text.isBlank()) {
                    busy = false
                    dialogStatus = ""
                    notice = "没听清，靠近一点再说一次"
                    return@onSuccess
                }
                // 先上屏孩子说的这句：后面千问要几秒，不能让界面空着等人
                val next = turns + ChatTurn("user", text)
                turns = next
                dialogStatus = "小智正在想…"
                val reply = withContext(Dispatchers.IO) {
                    runCatching { api.chat(ChatRequest(next)) }
                }
                busy = false
                dialogStatus = ""
                reply.onSuccess { r ->
                    if (r.text.isNotBlank()) {
                        turns = turns + ChatTurn("assistant", r.text)
                        // audioUrl 可能是空串（服务端 TTS 失败时刻意不整个失败）
                        if (r.audioUrl.isNotBlank()) play(absolute(r.audioUrl))
                    }
                }.onFailure { notice = "对话失败：${friendly(it)}" }
            }
        }
    }

    /** 清空对话。 */
    fun clearChat() {
        turns = emptyList()
        dialogStatus = ""
    }

    /**
     * 直接用**文字**发一句（不录音）。
     *
     * 用途是「示例问题」：孩子刚进页面、还不知道能说什么的时候，点一句现成的
     * 就能开始对话 —— 比逼他先对着麦克风想词友善得多。
     */
    fun sendMessage(text: String) {
        val content = text.trim()
        if (content.isEmpty() || busy) return
        val next = turns + ChatTurn("user", content)
        turns = next
        dialogStatus = "小智正在想…"
        busy = true
        viewModelScope.launch {
            val reply = withContext(Dispatchers.IO) {
                runCatching { api.chat(ChatRequest(next)) }
            }
            busy = false
            dialogStatus = ""
            reply.onSuccess { r ->
                if (r.text.isNotBlank()) {
                    turns = turns + ChatTurn("assistant", r.text)
                    if (r.audioUrl.isNotBlank()) play(absolute(r.audioUrl))
                }
            }.onFailure { notice = "对话失败：${friendly(it)}" }
        }
    }

    fun consumeNotice() {
        notice = ""
    }

    /**
     * 从 UI 层发一条一次性提示（例如权限被拒）。
     *
     * 做成方法而不是让 UI 直接赋值 [notice]：那边是 `private set`，
     * 只有这一个入口能让「谁能改提示」可追溯。
     */
    fun showNotice(message: String) {
        notice = message
    }

    /** 记一道错题。同一题重复答错只留第一次的那条，避免列表里刷屏。 */
    fun recordMistake(m: Mistake) {
        if (mistakes.none { it.question == m.question }) {
            mistakes = mistakes + m
        }
    }

    /**
     * 翻过某个单词。
     *
     * 用 Set 天然去重（同一个词翻两次不重复计数）；**同时上报服务端**，
     * 否则家长手机上看到的永远是 0 —— 这正是这次「学情同步」要解决的问题。
     */
    fun markWordSeen(unit: Int, en: String) {
        if (en.isNotBlank() && en !in wordsSeen) {
            wordsSeen = wordsSeen + en
            queueProgress(unit) { it.wordsSeen += 1 }
        }
    }

    /** 点了「认识」。它比「翻过」是更强的信号，所以单独记一个数。 */
    fun markWordKnown(unit: Int) {
        queueProgress(unit) { it.wordsKnown += 1 }
    }

    /**
     * 答了一道题。
     *
     * 和 [recordMistake] 是两件事：错题本只要错的题，而正确率需要**总题数** ——
     * 只报错题的话，家长看到的正确率永远是 0%。
     */
    fun recordAnswer(unit: Int, correct: Boolean) {
        queueProgress(unit) { p ->
            p.quizTotal += 1
            if (correct) p.quizCorrect += 1
        }
    }

    // ---------------- 学情：攒批 + 上报 ----------------

    private fun queueProgress(unit: Int, block: (PendingProgress) -> Unit) {
        if (unit <= 0) return
        block(pendingProgress.getOrPut(unit) { PendingProgress() })
        flushJob?.cancel()
        flushJob = viewModelScope.launch {
            delay(2500)   // 攒一会儿：连续翻词/答题不该一个动作一个请求
            flushProgress()
        }
    }

    /** 立刻把攒着的增量发出去。切页、离开时调一次，别让最后几个动作丢掉。 */
    fun flushProgress() {
        flushJob?.cancel()
        flushJob = null
        if (pendingProgress.isEmpty()) return

        // 先拷走再清空：发送期间新的动作会写进新的桶里，不会和这一批混在一起
        val batch = pendingProgress.toMap()
        pendingProgress.clear()

        viewModelScope.launch {
            batch.forEach { (unit, p) ->
                if (p.isEmpty) return@forEach
                val body = ProgressIn(
                    unit = unit,
                    day = LocalDate.now().toString(),
                    wordsSeen = p.wordsSeen,
                    wordsKnown = p.wordsKnown,
                    quizTotal = p.quizTotal,
                    quizCorrect = p.quizCorrect,
                    speakCount = p.speakCount,
                    // 服务端累加后算平均，这里报「这一次的均分」
                    speakScore = if (p.speakCount > 0) p.speakScoreSum / p.speakCount else null,
                )
                val ok = withContext(Dispatchers.IO) {
                    runCatching { api.reportProgress(body) }
                }.isSuccess
                if (!ok) {
                    // 失败就把这批加回去，等下一次 flush 一起发
                    val again = pendingProgress.getOrPut(unit) { PendingProgress() }
                    again.wordsSeen += p.wordsSeen
                    again.wordsKnown += p.wordsKnown
                    again.quizTotal += p.quizTotal
                    again.quizCorrect += p.quizCorrect
                    again.speakCount += p.speakCount
                    again.speakScoreSum += p.speakScoreSum
                }
            }
        }
    }

    /** 读最近 N 天的学情（三端同一个接口）。 */
    fun loadProgress(days: Int = 7) {
        if (progressLoading) return
        progressLoading = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { api.progress(days) } }
            progressLoading = false
            result
                .onSuccess {
                    progress = it
                    progressError = ""
                }
                .onFailure { progressError = friendly(it) }
        }
    }

    fun stop() {
        player?.let { runCatching { it.release() } }
        player = null
    }

    override fun onCleared() {
        // ⚠️ 一定要收录音：ViewModel 被清掉时麦克风还开着的话，**没有任何报错**，
        //    表现是「别的 App 录音没声音」，家长根本不会想到是这个 App 占着。
        runCatching { recorder.cancel() }
        timerJob?.cancel()
        stop()
        super.onCleared()
    }

    // ---------------- 内部 ----------------

    private fun wavDataUri(wav: ByteArray): String =
        "data:audio/wav;base64," + Base64.encodeToString(wav, Base64.NO_WRAP)

    private fun absolute(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = endpoint.baseUrl()?.trimEnd('/') ?: return path  // 未配置时原样返回，让播放报错
        return base + if (path.startsWith("/")) path else "/$path"
    }

    /**
     * 把网络异常翻译成家长看得懂的一句话。
     *
     * 服务端的错误体是 `{"error": "讯飞评测需要配置 XFYUN_APPID…"}` ——
     * 这句本来就能直接给人看，比 `HTTP 502` 有用得多。
     */
    private fun friendly(t: Throwable): String = when (t) {
        is HttpException -> {
            val raw = runCatching { t.response()?.errorBody()?.string() }.getOrNull()
            val msg = runCatching { raw?.let { JSONObject(it).optString("error") } }.getOrNull()
            if (!msg.isNullOrBlank()) msg else "服务端返回 ${t.code()}"
        }
        is UnknownHostException, is ConnectException, is SocketTimeoutException ->
            "连不上服务端（检查设置里的地址，或 NAS 是否在跑）"
        is IOException -> "网络中断：${t.message ?: "未知"}"
        else -> t.message ?: "未知错误"
    }

    /**
     * 播一个地址。
     *
     * **复用同一个 MediaPlayer**，不每次 new + release。
     * 每次新建都要走一遍 native 的 `MediaPlayer()` 构造 + `setAudioAttributes` +
     * 析构 —— 在 RK3566 这类设备上一轮约 30~80ms，全在**主线程**。表现就是
     * 「点播放时界面顿一下」。复用之后只有第一次付这个成本。
     *
     * ⚠️⚠️ **`reset()` 会把所有 listener 清空**，所以监听器必须每次播放前重设。
     *    2026-10-23 重构复用逻辑时只在创建那段设了一次
     *    `setOnPreparedListener { it.start() }`，还顺手把它丢了 →
     *    `prepareAsync()` 完成后**没有任何人调 start()**，
     *    表现是「点了没声音、也不报错、什么提示都没有」。
     *    这类 bug 靠看界面完全看不出来，只能靠 logcat 里 MediaPlayer 的空转。
     */
    private fun play(url: String) {
        val mp = player ?: MediaPlayer().also { player = it }

        // reset() 之前的实例可能正在播、也可能刚报错，一律先打回 Idle
        runCatching { mp.reset() }.onFailure {
            // reset 都失败说明实例已废，丢掉换一个重来
            runCatching { mp.release() }
            player = null
            play(url)
            return
        }

        // reset() 之后这些设置全部要重来（audio attributes 也保险重设一次）
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
        )
        // ★ 这一行是「有没有声音」的关键：prepareAsync 成功后必须自己 start
        mp.setOnPreparedListener { it.start() }
        mp.setOnCompletionListener { /* 播完停住，实例留着复用 */ }
        mp.setOnErrorListener { _, what, extra ->
            notice = "音频播放失败（what=$what extra=$extra）"
            true
        }

        try {
            mp.setDataSource(url)
            mp.prepareAsync()
            // 只在真的开始准备时才计数：上面的 reset 失败会递归重来，
            // 计在这里就不会重复 +1。
            audioPlays += 1
        } catch (e: Exception) {
            notice = "音频地址无法播放：$url"
            runCatching { mp.reset() }
        }
    }
}

/**
 * 一道错题。
 *
 * 只存展示需要的最小信息（题面 / 孩子选的 / 正确的 / 解析），
 * 不存整题对象 —— 错题本要跟着课程 JSON 一起变的话，教材一改就全对不上了。
 */
data class Mistake(
    val unit: Int,
    val question: String,
    val chosen: String,
    val correct: String,
    val explain: String,
)

/**
 * 进程级课程缓存。
 *
 * 只存一份、只读、整个 App 生命周期有效 —— 不需要失效策略，因为
 * [EnglishViewModel.loadCurriculum] 每次进页面都会在后台刷新它。
 */
private object CurriculumCache {
    @Volatile
    var units: List<CurriculumUnitDto>? = null
}
