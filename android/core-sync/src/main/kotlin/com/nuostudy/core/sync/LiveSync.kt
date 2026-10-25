package com.nuostudy.core.sync

import com.nuostudy.core.data.repo.AssignmentRepository
import com.nuostudy.core.network.ServerEndpoint
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「家长一发布，孩子端秒级可见」的实时通道（SSE）。
 *
 * 之前孩子端看到新作业只有三条路：冷启动催一次、手动点刷新、6 小时周期任务。
 * 家长晚自习前发布一条作业，孩子端可能要等很久 —— 这条通道补的就是这个空档：
 * App **前台可见**期间挂一条 SSE 长连接到服务端 `/api/v1/events`，
 * 家长一发布/删除/修正，服务端广播事件，这里收到就立刻全量拉取。
 *
 * 四条设计决定，别在重构时丢掉：
 *
 * 1. **只在 App 前台挂连接**（MainActivity.onStart/onStop 控制）。
 *    退后台反正看不见界面，长连接只会白耗电、白占服务端 —— 那个时段
 *    有 6 小时周期任务兜底。也正因如此，这里**不需要前台 Service**。
 *
 * 2. **只信全量拉取，不信事件内容**。事件体只有 type/action/id（见服务端
 *    events.py），收到后走标准 `repo.refresh()` —— 刷新是幂等的，事件丢了
 *    顶多晚一点，绝不会出现「事件说删了、其实没删」的两套事实。
 *
 * 3. **单飞去重**。「全部发布」会一瞬间广播 N 条事件；正在刷新期间再来的
 *    事件只标一个脏标记，刷完这轮发现还脏就再来一轮 —— 永远最多两轮，
 *    而不是跟着 N 条事件硬刷 N 次。
 *
 * 4. **掉线静默退避重连**（1s → 2s → … → 30s 封顶）。这条通道是「尽力而为」，
 *    失败不该打扰孩子（弹个「连接断开」比连不上更糟），兜底还有周期任务。
 *
 * 实现说明：用的是**阻塞读 + 独立线程**，而不是协程 Flow —— SSE 的读是
 * 无限阻塞的，协程取消打断不了阻塞读，还得绕 call.cancel() 补一刀；
 * 一个 daemon 线程 + AtomicBoolean 开关反而最少坑。读取阻塞期间服务端
 * 后续事件会排在 TCP 缓冲里，天然形成背压。
 */
@Singleton
class LiveSync @Inject constructor(
    private val client: OkHttpClient,
    private val endpoint: ServerEndpoint,
    private val repo: AssignmentRepository,
) {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    /**
     * 连接「代际」。
     *
     * ⚠️ 光靠 [running] 挡不住 stop→start 的竞态：Activity 快速切后台再回前台时，
     *    `stop()` 刚把 running 置 false、旧线程还阻塞在 readUtf8Line() 里没来得及
     *    观察，`start()` 已经把 running 又置回 true 了 —— 旧线程醒来一看 running
     *    还是 true，就继续读，于是**同时挂着两条 SSE**（服务端看到两个订阅者，
     *    前端每次变更刷新两遍）。
     *    每次 start/stop 都自增代际，线程只认自己出生那一刻的号，对不上就必须退出。
     */
    private val generation = AtomicInteger(0)

    @Volatile private var call: Call? = null

    private val refreshLock = Any()
    @Volatile private var refreshing = false
    @Volatile private var dirty = false

    /** 前台可见时调用（MainActivity.onStart）。重复调用安全。 */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        val gen = generation.incrementAndGet()
        worker = Thread({ eventLoop(gen) }, "assignment-events").apply {
            isDaemon = true
            start()
        }
    }

    /** 退到后台时调用（MainActivity.onStop）。断开连接，循环随即退出。 */
    fun stop() {
        running.set(false)
        // 先抬代际再 cancel：这样即使紧跟着的 start() 把 running 置回 true，
        // 旧线程的两处循环条件也一定为假。
        generation.incrementAndGet()
        call?.cancel()
        worker = null
    }

    private fun eventLoop(gen: Int) {
        var backoffMs = INITIAL_BACKOFF_MS
        while (alive(gen)) {
            try {
                // buildCall 必须在 try 里：服务端地址未配置时它会抛
                // IOException（首装尚未在设置页填地址/令牌就是这种状态）。
                // 放在 try 外会让这条线程以未捕获异常直接杀掉整个进程 ——
                // 表现为「装完 App 一打开就闪退」，连设置页都进不去。
                val c = buildCall()
                call = c
                c.execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("SSE HTTP ${resp.code}")
                    backoffMs = INITIAL_BACKOFF_MS   // 每次连上就把退避重置回 1s
                    val source = resp.body?.source()
                        ?: throw IOException("SSE 响应没有内容")
                    while (alive(gen)) {
                        // 阻塞读一行。流断开时返回 null 或直接抛 IOException。
                        val line = source.readUtf8Line()
                            ?: throw IOException("SSE 连接被对端关闭")
                        if (line.startsWith("data:") && line.contains(EVENT_TYPE)) {
                            onEvent()
                        }
                        // hello / keep-alive 注释帧 / 其他事件：一律忽略
                    }
                }
            } catch (e: Exception) {
                if (!alive(gen)) break   // stop() / 换代 触发的 cancel，正常收摊
                // 掉线 / 服务端重启 / 局域网抖动：静默，走下面的退避重连
            }
            // 可中断的退避等待（stop() 最多 200ms 内退出循环）
            var waited = 0L
            while (alive(gen) && waited < backoffMs) {
                Thread.sleep(SLEEP_TICK_MS)
                waited += SLEEP_TICK_MS
            }
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /** 本线程是否仍是「当前那一代」且整体仍在跑。 */
    private fun alive(gen: Int): Boolean = running.get() && gen == generation.get()

    /**
     * 收到变更事件 → 立刻全量拉取。刷新失败**不重试**：这条线程还要负责
     * 重连，卡在这里通道就死了 —— 下一个事件或周期任务自然会再来。
     */
    private fun onEvent() {
        synchronized(refreshLock) {
            if (refreshing) {
                dirty = true
                return
            }
            refreshing = true
        }
        try {
            while (true) {
                dirty = false
                runBlocking {
                    repo.refresh()
                    repo.purgeOld()
                }
                if (!dirty) break
            }
        } catch (_: Exception) {
            // 刷新失败：不重试、不上抛，通道的职责是保持连接
        } finally {
            synchronized(refreshLock) { refreshing = false }
        }
    }

    /**
     * 构造 SSE 请求。鉴权与设备标识交给共享的 DynamicUrlInterceptor（它对
     * SSE 请求一样生效），这里只负责 URL 和 Accept；Accept 已在 NetworkModule
     * 里改为「请求自带时不覆盖」，SSE 的 text/event-stream 能安全通过。
     *
     * 读超时必须关掉（SSE 平时 20 秒才来一条保活帧）；newBuilder 共享连接池，
     * 不影响普通请求的 30s 读超时。
     */
    private fun buildCall(): Call {
        val base = endpoint.baseUrl() ?: throw IOException("服务端地址未配置")
        val sseClient = client.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        return sseClient.newCall(
            Request.Builder()
                .url(base.trimEnd('/') + "/api/v1/events")
                .header("Accept", "text/event-stream")
                .build()
        )
    }

    private companion object {
        const val EVENT_TYPE = "assignments.changed"
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val SLEEP_TICK_MS = 200L
    }
}
