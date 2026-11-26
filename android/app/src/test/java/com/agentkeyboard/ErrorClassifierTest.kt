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

    private fun rateLimitWith(retryAfter: String?): com.openai.errors.RateLimitException {
        val b = com.openai.core.http.Headers.builder()
        if (retryAfter != null) b.put("Retry-After", retryAfter)
        return com.openai.errors.RateLimitException.builder().headers(b.build()).build()
    }

    @Test
    fun extractRetryAfterMs_parsesSecondsAndWrapped() {
        org.junit.Assert.assertEquals(3000L, ErrorClassifier.extractRetryAfterMs(rateLimitWith("3")))
        org.junit.Assert.assertEquals(0L, ErrorClassifier.extractRetryAfterMs(rateLimitWith(" 0 ")))
        org.junit.Assert.assertEquals(5000L, ErrorClassifier.extractRetryAfterMs(RuntimeException("wrap", rateLimitWith("5"))))
    }

    @Test
    fun extractRetryAfterMs_missingInvalidOrHttpDate_returnsNull() {
        assertNull(ErrorClassifier.extractRetryAfterMs(rateLimitWith(null)))
        assertNull(ErrorClassifier.extractRetryAfterMs(rateLimitWith("abc")))
        assertNull(ErrorClassifier.extractRetryAfterMs(rateLimitWith("-1")))
        assertNull(ErrorClassifier.extractRetryAfterMs(rateLimitWith("Wed, 21 Oct 2015 07:28:00 GMT")))
        assertNull(ErrorClassifier.extractRetryAfterMs(SocketTimeoutException("t")))
    }

    /** 整次请求上限(callTimeout)到点抛的是 InterruptedIOException("timeout"),不是 SocketTimeoutException */
    @Test
    fun callTimeoutInterruptedIOException_isTimedOutAndRetryable() {
        val e = RuntimeException("wrap", java.io.InterruptedIOException("timeout"))
        assertTrue(ErrorClassifier.isTimedOut(e))
        assertTrue(ErrorClassifier.isRetryable(e))
        assertEquals(ErrorKind.TIMEOUT, ErrorClassifier.kind(e))
    }

    @Test
    fun kind_matchesClassifyForThrowable() {
        assertEquals(ErrorKind.RATE_LIMIT, ErrorClassifier.kind(rateLimitWith(null)))
        assertEquals(ErrorKind.NO_NETWORK, ErrorClassifier.kind(UnknownHostException("dns")))
        assertEquals(ErrorKind.TIMEOUT, ErrorClassifier.kind(SocketTimeoutException("t")))
        assertEquals(
            ErrorKind.BAD_KEY,
            ErrorClassifier.kind(
                com.openai.errors.UnauthorizedException.builder()
                    .headers(com.openai.core.http.Headers.builder().build())
                    .build()
            ),
        )
    }

    /** 模拟 SDK 从服务端错误体解析出的异常(与真实链路同一类型,ErrorObject 即错误体) */
    private fun serviceError(
        status: Int,
        message: String,
        code: String? = null,
        type: String? = null,
    ): com.openai.errors.OpenAIServiceException {
        // SDK 的 ErrorObject 构造时四个字段都要显式给出(可以是空),与真实反序列化的结果一致
        val error = com.openai.models.ErrorObject.builder()
            .message(message)
            .code(java.util.Optional.ofNullable(code))
            .param(java.util.Optional.empty())
            .type(type?.let { com.openai.core.JsonField.of(it) } ?: com.openai.core.JsonField.ofNullable<String>(null))
        return com.openai.errors.UnexpectedStatusCodeException.builder()
            .statusCode(status)
            .headers(com.openai.core.http.Headers.builder().build())
            .error(error.build())
            .build()
    }

    @Test
    fun serviceError_exposesStatusAndIdentifierCodesOnly() {
        val limited = serviceError(429, "Request too large", code = "rate_limit_exceeded", type = "insufficient_quota")
        val detail = ErrorClassifier.serviceError(limited)!!
        assertEquals(429, detail.httpStatus)
        assertEquals("rate_limit_exceeded", detail.code)
        assertEquals("insufficient_quota", detail.type)
        // 包装后仍可识别
        assertEquals("rate_limit_exceeded", ErrorClassifier.serviceError(RuntimeException("wrap", limited))!!.code)

        // 不是标识符的错误码(可能夹带请求内容)不取;message 一律不取
        val loose = serviceError(400, "echoes the request", code = "bad value for messages[3]")
        assertNull(ErrorClassifier.serviceError(loose)!!.code)
        assertNull("服务端没给 type 时读取会抛,不能让分类再炸一次", ErrorClassifier.serviceError(loose)!!.type)
        // 数字错误码也是标识符
        assertEquals("1113", ErrorClassifier.serviceError(serviceError(400, "输入内容超长", code = "1113"))!!.code)
        // 没有 code 时界面回退到 type
        val typed = ErrorClassifier.serviceError(serviceError(400, "x", type = "invalid_request_error"))!!
        assertEquals("invalid_request_error", typed.code ?: typed.type)
        assertNull(ErrorClassifier.serviceError(IllegalStateException("x")))
    }

    @Test
    fun contextLength_detectedOnClientErrorStatusOnly() {
        val text = "This model's maximum context length is 128000 tokens. However, you requested 131000 tokens"
        val tooLong = serviceError(400, text, code = "context_length_exceeded")
        assertTrue(ErrorClassifier.isContextLengthExceeded(tooLong))
        assertTrue(ErrorClassifier.isContextLengthExceeded(RuntimeException("wrap", tooLong)))
        assertFalse("服务端故障不算上下文超长", ErrorClassifier.isContextLengthExceeded(serviceError(500, text)))
        assertFalse(ErrorClassifier.isContextLengthExceeded(serviceError(400, "Invalid value for 'model'")))
        assertFalse(ErrorClassifier.isContextLengthExceeded(SocketTimeoutException("timeout")))
    }

    @Test
    fun contextLimit_readsTheSmallestTokenCountTheServerReports() {
        assertEquals(
            128_000,
            ErrorClassifier.contextLimit(
                serviceError(400, "This model's maximum context length is 128000 tokens. However, you requested 131000 tokens"),
            )!!,
        )
        assertEquals(
            98_304,
            ErrorClassifier.contextLimit(serviceError(400, "输入内容超长:当前输入 200000 个token，最多支持 98304 个token"))!!,
        )
        assertNull("报文里没有上限数字", ErrorClassifier.contextLimit(serviceError(400, "上下文长度超限")))
        assertNull(ErrorClassifier.contextLimit(serviceError(400, "Invalid value for 'model'")))
    }
}
