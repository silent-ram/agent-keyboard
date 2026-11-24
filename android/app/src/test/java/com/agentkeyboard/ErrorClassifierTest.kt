package com.agentkeyboard.data

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 错误分类映射测试:优先级 401/403 > 超时 > 网络不可达 > 其他 */
class ErrorClassifierTest {

    @Test
    fun http401_mapsToBadKey_evenWhenOtherFlagsSet() {
        assertEquals(
            ErrorKind.BAD_KEY,
            ErrorClassifier.classify(statusCode = 401, connectivityFailure = false, timedOut = false),
        )
        assertEquals(
            ErrorKind.BAD_KEY,
            ErrorClassifier.classify(statusCode = 401, connectivityFailure = true, timedOut = true),
        )
    }

    @Test
    fun http403_mapsToBalance() {
        // M1 批次 3:403 按欠费/无权限归 BALANCE(部分服务商 403 = 欠费停服),401 仍为 BAD_KEY
        assertEquals(
            ErrorKind.BALANCE,
            ErrorClassifier.classify(statusCode = 403, connectivityFailure = false, timedOut = false),
        )
    }

    @Test
    fun http402_mapsToBalance() {
        assertEquals(
            ErrorKind.BALANCE,
            ErrorClassifier.classify(statusCode = 402, connectivityFailure = false, timedOut = false),
        )
    }

    @Test
    fun http429_mapsToRateLimit() {
        assertEquals(
            ErrorKind.RATE_LIMIT,
            ErrorClassifier.classify(statusCode = 429, connectivityFailure = false, timedOut = false),
        )
    }

    @Test
    fun timeout_mapsToTimeout() {
        assertEquals(
            ErrorKind.TIMEOUT,
            ErrorClassifier.classify(statusCode = null, connectivityFailure = false, timedOut = true),
        )
    }

    @Test
    fun connectivityFailure_mapsToNoNetwork() {
        assertEquals(
            ErrorKind.NO_NETWORK,
            ErrorClassifier.classify(statusCode = null, connectivityFailure = true, timedOut = false),
        )
    }

    @Test
    fun serverError_mapsToOther() {
        assertEquals(
            ErrorKind.OTHER,
            ErrorClassifier.classify(statusCode = 500, connectivityFailure = false, timedOut = false),
        )
    }

    @Test
    fun unknownError_mapsToOther() {
        assertEquals(
            ErrorKind.OTHER,
            ErrorClassifier.classify(statusCode = null, connectivityFailure = false, timedOut = false),
        )
    }

    @Test
    fun isConnectivityFailure_detectsDnsAndConnectFailures() {
        assertTrue(ErrorClassifier.isConnectivityFailure(UnknownHostException("api.deepseek.com")))
        assertTrue(ErrorClassifier.isConnectivityFailure(ConnectException("connection refused")))
        // 包装后仍可识别
        val wrapped = RuntimeException("outer", UnknownHostException("api.deepseek.com"))
        assertTrue(ErrorClassifier.isConnectivityFailure(wrapped))
        assertFalse(ErrorClassifier.isConnectivityFailure(IllegalStateException("模型返回空内容")))
    }

    @Test
    fun isTimedOut_detectsWrappedTimeout() {
        assertTrue(ErrorClassifier.isTimedOut(SocketTimeoutException("timed out")))
        assertTrue(ErrorClassifier.isTimedOut(RuntimeException("wrap", SocketTimeoutException("t"))))
        assertFalse(ErrorClassifier.isTimedOut(ConnectException("refused")))
    }

    @Test
    fun extractStatusCode_nonSdkException_returnsNull() {
        assertNull(ErrorClassifier.extractStatusCode(IllegalStateException("x")))
        assertNull(ErrorClassifier.extractStatusCode(RuntimeException("wrap", SocketTimeoutException("t"))))
    }

    @Test
    fun isRetryable_timeoutRateLimitAndTransientServerErrors() {
        val headers = com.openai.core.http.Headers.builder().build()
        assertTrue(ErrorClassifier.isRetryable(SocketTimeoutException("t")))
        assertTrue(ErrorClassifier.isRetryable(com.openai.errors.RateLimitException.builder().headers(headers).build()))
        for (code in listOf(500, 502, 503, 504)) {
            val e = com.openai.errors.InternalServerException.builder().statusCode(code).headers(headers).build()
            assertTrue("status $code", ErrorClassifier.isRetryable(e))
            // 包装后仍可识别
            assertTrue(ErrorClassifier.isRetryable(RuntimeException("wrap", e)))
        }
    }

    @Test
    fun isRetryable_callerErrorsAndUnknownFailuresAreNotRetried() {
        val headers = com.openai.core.http.Headers.builder().build()
        assertFalse(ErrorClassifier.isRetryable(com.openai.errors.UnauthorizedException.builder().headers(headers).build()))
        assertFalse(ErrorClassifier.isRetryable(com.openai.errors.BadRequestException.builder().headers(headers).build()))
        assertFalse(ErrorClassifier.isRetryable(RuntimeException("bad gateway")))
        assertFalse(ErrorClassifier.isRetryable(EmptyResponseException()))
    }

    @Test
    fun isEmptyResponse_detectsTypedEmptyResponse() {
        // 直抛 / 包装均可识别;异常对象不携带中文 message,UI 不会外泄原始信息
        assertTrue(ErrorClassifier.isEmptyResponse(EmptyResponseException()))
        assertTrue(
            ErrorClassifier.isEmptyResponse(RuntimeException("outer", EmptyResponseException())),
        )
        assertFalse(ErrorClassifier.isEmptyResponse(IllegalStateException("模型返回空内容")))
        assertFalse(ErrorClassifier.isConnectivityFailure(EmptyResponseException()))
    }
}
