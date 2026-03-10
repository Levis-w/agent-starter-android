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

const val hardcodedToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJleHAiOjIwODg1MjA3ODgsImlkZW50aXR5IjoibXktcGhvbmUiLCJpc3MiOiJBUElOeWVTekh3endwcUciLCJuYW1lIjoibXktcGhvbmUiLCJuYmYiOjE3NzMxNjA3ODgsInJvb21Db25maWciOnsiYWdlbnRzIjpbeyJhZ2VudE5hbWUiOiJ4aWFvbWVuZy1hZ2VudCJ9XX0sInN1YiI6Im15LXBob25lIiwidmlkZW8iOnsicm9vbSI6Im15LXByaXZhdGUtcm9vbSIsInJvb21Kb2luIjp0cnVlfX0.xQhY6fYm0ehIu-oeZMDKLg4vHeioYvvQPxfcril1pRM"

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
