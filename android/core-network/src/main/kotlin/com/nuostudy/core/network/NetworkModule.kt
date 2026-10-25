package com.nuostudy.core.network

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * 占位地址。Retrofit 构造时要求 baseUrl 合法，所以先给一个无害的占位，
 * 真正请求时由 [DynamicUrlInterceptor] 换成用户在设置里填的地址。
 *
 * 这样 Retrofit / OkHttp 实例可以做成单例，不必在地址变化时重建 ——
 * 重建会让所有在途请求全部失败。
 */
private const val PLACEHOLDER_BASE_URL = "http://localhost/"

internal class DynamicUrlInterceptor(private val endpoint: ServerEndpoint) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val base = endpoint.baseUrl()?.toHttpUrlOrNull()
            ?: return chain.request().notConfigured()
        val original = chain.request()

        // 替换 scheme/host/port，**并且把用户在「设置」里填的路径前缀补回去**。
        //
        // ⚠️ 早先只换了 scheme/host/port，路径前缀被静默丢掉：
        //    地址填成 `http://host:port/nuo`（反代到一个子路径）时，
        //    Retrofit 的 @GET("api/v1/x") 相对路径拼出来的是 `/api/v1/x`，
        //    于是请求打到 `http://host:port/api/v1/x` —— 404，而且日志上看
        //    完全像是服务端没这个接口，极难往「地址里的子路径没生效」上想。
        //    本机部署填的是根路径（不带子路径），所以现有环境看不出来。
        val basePath = base.encodedPath.trimEnd('/')          // "" 或 "/nuo"
        val originalPath = original.url.encodedPath           // 一定以 "/" 开头
        val newUrl: HttpUrl = original.url.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .apply {
                if (basePath.isNotEmpty() && !originalPath.startsWith("$basePath/")) {
                    encodedPath(basePath + originalPath)
                }
            }
            .build()

        val builder = original.newBuilder()
            .url(newUrl)
            .header("X-Device-Id", endpoint.deviceId())

        // 只在请求没自带 Accept 时给默认值：SSE（LiveSync）要求
        // Accept: text/event-stream，不能被这里的 application/json 覆盖
        if (original.header("Accept") == null) {
            builder.header("Accept", "application/json")
        }

        endpoint.familyToken()?.takeIf { it.isNotBlank() }?.let {
            builder.header("Authorization", "Bearer $it")
        }

        return chain.proceed(builder.build())
    }

    /**
     * 未配置服务端地址时的**失败软着陆**：返回一个合成的 599 响应，
     * 而不是抛异常。
     *
     * ⚠️ 这里曾经 `throw ApiException(...)` —— 在 OkHttp Dispatcher（enqueue）
     *    线程上，RuntimeException 会被包成 `Error` 直达未捕获处理器，
     *    **首装未配置时 App 一启动就崩**，设置页都进不去（实测 2026-10-25）。
     *    599 走的是正常响应通道：enqueue → onResponse(!isSuccessful)、
     *    suspend → HttpException、SSE → IOException("SSE HTTP 599")，
     *    每条路径都有各自的软处理，App 活着等家长去设置页填配置。
     *    599 是非标准码，语义 = "服务端地址未配置"。
     */
    private fun okhttp3.Request.notConfigured(): okhttp3.Response =
        okhttp3.Response.Builder()
            .request(this)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(599)
            .message("server not configured")
            .body(okhttp3.ResponseBody.create(null, ""))
            .build()
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * ⚠️ 超时按「最慢的那个请求」设，不要按平均值：
     *    识别本身是异步的（上传只返回 jobId），所以普通请求不需要长超时；
     *    真正慢的是**上传原图**（家长用手机拍的，2–5 MB），所以 writeTimeout 放宽到 60s。
     *    这不是「等等看」，是必须留出的物理传输时间。
     */
    private const val CONNECT_TIMEOUT_S = 10L
    private const val READ_TIMEOUT_S = 30L
    private const val WRITE_TIMEOUT_S = 60L

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        // 服务端加字段不能让老客户端崩溃。客户端与服务端是各自发版的。
        ignoreUnknownKeys = true
        // 服务端把 null 写成缺失、或类型轻微不符时，尽量用默认值兜住
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttp(endpoint: ServerEndpoint): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(DynamicUrlInterceptor(endpoint))
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        okhttp3.logging.HttpLoggingInterceptor().apply {
                            // ⚠️ 只能是 BASIC。BODY 会把作业原文、孩子姓名、
                            //    家庭令牌全部打进 logcat，单家庭项目同样不该这么干。
                            level = okhttp3.logging.HttpLoggingInterceptor.Level.BASIC
                        }
                    )
                }
            }
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideStudyApi(retrofit: Retrofit): StudyApi = retrofit.create(StudyApi::class.java)

    /**
     * 英语能力（TTS / 转写 / 对话 / 评测）。跟 [provideStudyApi] 共用同一个
     * Retrofit 与 OkHttp —— 地址切换、Bearer 令牌、`X-Device-Id` 都是拦截器
     * 统一注入的，多一个接口不存在「多一套配置」。
     */
    @Provides
    @Singleton
    fun provideEnglishApi(retrofit: Retrofit): EnglishApi =
        retrofit.create(EnglishApi::class.java)
}
