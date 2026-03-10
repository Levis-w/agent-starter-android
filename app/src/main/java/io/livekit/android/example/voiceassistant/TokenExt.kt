package io.livekit.android.example.voiceassistant

// TODO: Add your Sandbox ID here
const val sandboxID = ""

// NOTE: If you prefer not to use LiveKit Sandboxes for testing, you can generate your
// tokens manually by visiting https://cloud.livekit.io/projects/p_/settings/keys
// and using one of your API Keys to generate a token with custom TTL and permissions.
const val hardcodedUrl = "wss://openclaw.levis.xin"
const val hardcodedToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJleHAiOjIwODg1MTI2NzgsImlkZW50aXR5IjoibXktcGhvbmUiLCJpc3MiOiJBUElOeWVTekh3endwcUciLCJuYW1lIjoibXktcGhvbmUiLCJuYmYiOjE3NzMxNTI2NzgsInN1YiI6Im15LXBob25lIiwidmlkZW8iOnsicm9vbSI6Im15LXByaXZhdGUtcm9vbSIsInJvb21Kb2luIjp0cnVlfX0.tf7HZmqnGEElnWyX4_N5GtAwf_AmxxKf2KJZZBX62fI"

fun getTokenSource(): TokenSource {
    return if (sandboxID.isNotEmpty()) {
        TokenSource.Sandbox(sandboxID)
    } else {
        // 注意：这里必须填 hardcodedUrl 和 hardcodedToken，不能只写 url 和 token
        TokenSource.Manual(
            url = hardcodedUrl,
            token = hardcodedToken
        )
    }
}
