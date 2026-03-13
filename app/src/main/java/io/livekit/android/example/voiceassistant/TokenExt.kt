package io.livekit.android.example.voiceassistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 定义 TokenSource 密封类，用于区分是使用 Sandbox 还是硬编码的 URL/Token
 */
sealed class TokenSource {
    data class Sandbox(val id: String) : TokenSource()
    data class Static(val url: String, val token: String) : TokenSource()
}

// 1. Sandbox ID，保持为空
const val sandboxID = ""

// 2. 服务器地址（不变）
const val hardcodedUrl = "wss://openclaw.levis.xin"

// 3. 硬编码 token（初始值，后续会动态更新）
// 改成 var 而不是 const val，允许运行时修改
var hardcodedToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJleHAiOjIwODg3MzM3NTUsImlkZW50aXR5IjoibXktcGhvbmUiLCJpc3MiOiJBUElOeWVTekh3endwcUciLCJuYW1lIjoibXktcGhvbmUiLCJuYmYiOjE3NzMzNzM3NTUsInN1YiI6Im15LXBob25lIiwidmlkZW8iOnsicm9vbSI6InJvb20tMjAyNjAzMTMwMzQ5MTUiLCJyb29tSm9pbiI6dHJ1ZX19.P-vpaeRMoD2t8_ZKO7SCRZM4DNRELMo0NBTTL1FcG9w"

// 4. 添加 StateFlow 用于观察 token 变化
val tokenFlow: MutableStateFlow<String> = MutableStateFlow(hardcodedToken)

/**
 * 更新 token（供外部调用）
 * @param newToken 新的 JWT token
 */
fun updateToken(newToken: String) {
    hardcodedToken = newToken
    tokenFlow.value = newToken
}

/**
 * 获取连接来源的函数
 */
fun getTokenSource(): TokenSource {
    return if (sandboxID.isNotEmpty()) {
        TokenSource.Sandbox(sandboxID)
    } else {
        // 使用动态更新的 hardcodedToken
        TokenSource.Static(hardcodedUrl, hardcodedToken)
    }
}
