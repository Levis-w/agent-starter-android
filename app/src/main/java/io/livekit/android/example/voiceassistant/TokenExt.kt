package io.livekit.android.example.voiceassistant

/**
 * 定义 TokenSource 密封类，用于区分是使用 Sandbox 还是硬编码的 URL/Token
 */
sealed class TokenSource {
    data class Sandbox(val id: String) : TokenSource()
    data class Static(val url: String, val token: String) : TokenSource()
}

// 1. Sandbox ID，保持为空
const val sandboxID = ""

// 2. 你的硬编码配置
const val hardcodedUrl = "wss://openclaw.levis.xin"

const val hardcodedToken =
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJleHAiOjIwODg1MTI2NzgsImlkZW50aXR5IjoibXktcGhvbmUiLCJpc3MiOiJB" +
    "UElOeWVTekh3endwcUciLCJuYW1lIjoibXktcGhvbmUiLCJuYmYiOjE3NzMxNTI2NzgsInN1YiI6Im15LXBob25lIiwidmlkZW8iOn" +
    "sicm9vbSI6Im15LXByaXZhdGUtcm9vbSIsInJvb21Kb2luIjp0cnVlfX0.tf7HZmqnGEElnWyX4_N5GtAwf_AmxxKf2KJZZBX62fI"

/**
 * 获取连接来源的函数
 */
fun getTokenSource(): TokenSource {
    return if (sandboxID.isNotEmpty()) {
        TokenSource.Sandbox(sandboxID)
    } else {
        // 使用你上面定义的 hardcodedUrl 和 hardcodedToken
        TokenSource.Static(hardcodedUrl, hardcodedToken)
    }
}
