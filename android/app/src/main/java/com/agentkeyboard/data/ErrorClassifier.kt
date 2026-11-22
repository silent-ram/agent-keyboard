package com.agentkeyboard.data

import com.openai.errors.OpenAIServiceException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
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

    /** 异常链中是否包含超时(OkHttp SocketTimeoutException,可能被包装) */
    fun isTimedOut(t: Throwable): Boolean = generateSequence(t as Throwable?) { it.cause }
        .any { it is SocketTimeoutException }

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
}
