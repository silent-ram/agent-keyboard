package com.agentkeyboard.data

import com.openai.errors.OpenAIServiceException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** 错误卡分类(M0 四类 + M1 顺带:429 限流 / 402·403 余额) */
enum class ErrorKind { NO_NETWORK, BAD_KEY, TIMEOUT, OTHER, RATE_LIMIT, BALANCE }

/**
 * 模型返回空响应(无选项/内容为空)。类型化异常,便于 UI 选用专用文案
 * (空响应文案);不携带底层中文 message,防止 UI 误外泄。
 */
class EmptyResponseException : RuntimeException()

/**
 * 失败分类(纯函数):优先级 401/403 密钥无效 > 超时 > 网络不可达 > 其他。
 *
 * 无网络不主动查询 ConnectivityManager(需要 ACCESS_NETWORK_STATE 权限,M0 不申请),
 * 而是依据请求抛出的连接类异常判定,分类效果一致。
 */
object ErrorClassifier {

    fun classify(statusCode: Int?, connectivityFailure: Boolean, timedOut: Boolean): ErrorKind = when {
        statusCode == 429 -> ErrorKind.RATE_LIMIT
        statusCode == 402 -> ErrorKind.BALANCE
        statusCode == 401 -> ErrorKind.BAD_KEY
        statusCode == 403 -> ErrorKind.BALANCE // 部分服务商 403 = 欠费停服
        timedOut -> ErrorKind.TIMEOUT
        connectivityFailure -> ErrorKind.NO_NETWORK
        else -> ErrorKind.OTHER
    }

    /** 从异常链中提取 HTTP 状态码(openai-java 的 OpenAIServiceException 带 statusCode()) */
    fun extractStatusCode(t: Throwable): Int? = generateSequence(t as Throwable?) { it.cause }
        .filterIsInstance<OpenAIServiceException>()
        .firstOrNull()
        ?.statusCode()

    /** 可重试的 HTTP 状态:请求超时、限流、服务端临时故障。401/403/400 等调用方错误重试也不会成功。 */
    private val RETRYABLE_STATUS = setOf(408, 429, 500, 502, 503, 504)

    /**
     * 是否值得退避重试:超时、服务端临时性错误状态,或无状态码的偶发连接失败(连接重置/DNS 抖动)。
     * 带 4xx(除 408/429)状态码的一律不重试:密钥/余额/请求体错误重试也不会成功。
     */
    fun isRetryable(t: Throwable): Boolean {
        val status = extractStatusCode(t)
        if (status != null) return status in RETRYABLE_STATUS
        return isTimedOut(t) || isConnectivityFailure(t)
    }

    /**
     * 从异常链中提取 Retry-After(秒数形式)并换算为毫秒;缺失/非数字/HTTP 日期格式返回 null,
     * 由调用方回退到指数退避。上限由调用方按剩余预算截断。
     */
    fun extractRetryAfterMs(t: Throwable): Long? = runCatching {
        generateSequence(t as Throwable?) { it.cause }
            .filterIsInstance<OpenAIServiceException>()
            .firstOrNull()
            ?.headers()
            ?.values("Retry-After")
            ?.firstOrNull()
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it >= 0 }
            ?.times(1000)
    }.getOrNull()

    /**
     * 异常链中是否包含超时(可能被包装):读超时是 OkHttp 的 SocketTimeoutException;
     * 整次请求上限(callTimeout)到点抛的是 InterruptedIOException("timeout"),不是它的子类,一并计入。
     */
    fun isTimedOut(t: Throwable): Boolean = generateSequence(t as Throwable?) { it.cause }
        .any { it is InterruptedIOException }

    /** 一步到位的错误分类(诊断/trace 用):按状态码、超时、网络不可达依次判定 */
    fun kind(t: Throwable): ErrorKind =
        classify(extractStatusCode(t), isConnectivityFailure(t), isTimedOut(t))

    /** 网络不可达类失败:DNS 解析失败 / 连接被拒 / 无路由 / 连接被重置 / TLS 握手失败 */
    fun isConnectivityFailure(t: Throwable): Boolean = generateSequence(t as Throwable?) { it.cause }
        .any {
            it is UnknownHostException ||
                it is ConnectException ||
                it is NoRouteToHostException ||
                it is PortUnreachableException ||
                it is SocketException || // Connection reset 等(ConnectException 是其子类,显式列出语义不变)
                it is SSLException
        }

    /** OTHER 类细分:空响应走专用文案,其余一律 generic 文案,不外泄原始 message */
    fun isEmptyResponse(t: Throwable): Boolean = generateSequence(t as Throwable?) { it.cause }
        .any { it is EmptyResponseException }

    /** 服务端拒绝请求时可安全展示的事实:状态码,以及错误体里的错误码/类型标识符 */
    data class ServiceError(val httpStatus: Int, val code: String?, val type: String?)

    /**
     * 取模型服务错误的状态码与错误码/类型。只取标识符字符(字母数字和 _ . : -),
     * 不取 message:服务端的 message 可能回显请求内容,不进界面、诊断信息与日志。
     * code/type 在服务端没返回时,SDK 的读取方法本身会抛异常,所以各自兜住。
     */
    fun serviceError(t: Throwable): ServiceError? {
        val service = serviceException(t) ?: return null
        fun identifier(read: () -> String?): String? = runCatching(read).getOrNull()?.takeIf(::isIdentifier)
        return ServiceError(
            httpStatus = service.statusCode(),
            code = identifier { service.code().orElse(null) },
            type = identifier { service.type().orElse(null) },
        )
    }

    /**
     * 服务端因上下文超长拒绝请求。各家 OpenAI 兼容端点的 4xx 文案不同,按关键词识别;
     * 文案只用于判定和取上限,不对外展示。识别错了的代价只是多一次收紧重发。
     */
    fun isContextLengthExceeded(t: Throwable): Boolean {
        val service = serviceException(t) ?: return false
        if (service.statusCode() !in CONTEXT_LENGTH_STATUS) return false
        return CONTEXT_LENGTH_HINT.containsMatchIn(errorText(t, service))
    }

    /**
     * 从「上下文超长」报文里取真实上限:带 token 单位的数字与「最多/上限」后面的数字取最小值
     * (报文里通常同时出现上限与本次请求数,本次请求只会比上限大)。取不到返回 null。
     */
    fun contextLimit(t: Throwable): Int? {
        val service = serviceException(t) ?: return null
        if (service.statusCode() !in CONTEXT_LENGTH_STATUS) return null
        val text = errorText(t, service)
        if (!CONTEXT_LENGTH_HINT.containsMatchIn(text)) return null
        return (TOKEN_COUNT.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() } +
            UPPER_BOUND.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() })
            .filter { it in CONTEXT_LIMIT_RANGE }
            .minOrNull()
    }

    private val CONTEXT_LENGTH_STATUS = setOf(400, 413, 422, 431)
    private val CONTEXT_LIMIT_RANGE = 1_024..100_000_000
    private val IDENTIFIER = Regex("[A-Za-z0-9_.:-]{1,64}")
    private val CONTEXT_LENGTH_HINT = Regex(
        "(?i)context[_ ]?(?:length|window)|maximum context|too many tokens|prompt is too long|" +
            "input (?:is )?too long|exceeds? the (?:model's )?(?:maximum|context|input)|上下文|超长|过长|输入长度",
    )
    private val TOKEN_COUNT = Regex("(?<![\\d.])(\\d{4,9})\\s*(?:个)?\\s*tokens?", RegexOption.IGNORE_CASE)
    private val UPPER_BOUND = Regex(
        "(?:最多支持|上限|at most|maximum of|up to|limited to|limit is)\\s*[:：]?\\s*(\\d{4,9})",
        RegexOption.IGNORE_CASE,
    )

    private fun serviceException(t: Throwable): OpenAIServiceException? =
        generateSequence(t as Throwable?) { it.cause }.filterIsInstance<OpenAIServiceException>().firstOrNull()

    /**
     * 服务端错误原文,只用于关键词判定与取上限,不展示、不进日志:
     * - 异常链上的 message:SDK 构造服务异常时写成「状态码: 服务端文案」(实测 `404: Model "…" is not available …`);
     * - SDK 解析出的错误体:body() 不是 JSON,是错误对象的字符串形式(`{code=…, message=…, type=…}`)。
     */
    private fun errorText(t: Throwable, service: OpenAIServiceException): String =
        runCatching { service.body().toString() }.getOrDefault("") + " " +
            generateSequence(t as Throwable?) { it.cause }.joinToString(" ") { it.message.orEmpty() }

    private fun isIdentifier(value: String): Boolean = IDENTIFIER.matches(value)
}
