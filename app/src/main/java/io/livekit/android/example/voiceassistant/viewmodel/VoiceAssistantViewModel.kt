package io.livekit.android.example.voiceassistant.viewmodel

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import io.livekit.android.AudioOptions
import io.livekit.android.AudioType
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.example.voiceassistant.screen.VoiceAssistantRoute
import io.livekit.android.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class AudioMode {
    MEDIA_HIFI,
    CALL_SPEAKER,
    CALL_EARPIECE
}

data class TokenRequest(val mode: String, val identity_prefix: String = "my-phone")

data class TokenResponse(
    val token: String,
    val identity: String,
    val room: String,
    val url: String
)

class VoiceAssistantViewModel(application: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val routeArgs = savedStateHandle.toRoute<VoiceAssistantRoute>()
    private val startInCallMode = routeArgs.startInCallMode

    var currentMode by mutableStateOf(if (startInCallMode) AudioMode.CALL_SPEAKER else AudioMode.MEDIA_HIFI)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        .writeTimeout(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()
    private val apiBaseUrl = "http://192.168.6.233:8080"

    lateinit var tokenSource: io.livekit.android.token.TokenSource
    private var connectionUrl: String = ""
    private var connectionToken: String = ""

    var room: Room by mutableStateOf(createRoomInstance(currentMode))
        private set

    private fun createRoomInstance(mode: AudioMode): Room {
        val audioOptions = when (mode) {
            AudioMode.MEDIA_HIFI -> AudioOptions(
                audioOutputType = AudioType.MediaAudioType(),
                audioHandler = NoAudioHandler(),
                javaAudioDeviceModuleCustomizer = { builder ->
                    builder
                        .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                        .setUseHardwareAcousticEchoCanceler(false)
                        .setUseHardwareNoiseSuppressor(false)
                }
            )
            AudioMode.CALL_SPEAKER, AudioMode.CALL_EARPIECE -> AudioOptions(
                audioOutputType = AudioType.CallAudioType(),
                audioHandler = NoAudioHandler(),
                javaAudioDeviceModuleCustomizer = { builder ->
                    builder
                        .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                        .setUseHardwareAcousticEchoCanceler(true)
                        .setUseHardwareNoiseSuppressor(true)
                }
            )
        }
        return LiveKit.create(getApplication(), overrides = LiveKitOverrides(audioOptions = audioOptions))
    }

    private fun applyAudioState(mode: AudioMode) {
        when (mode) {
            AudioMode.MEDIA_HIFI -> {
                audioManager.mode = AudioManager.MODE_NORMAL
                audioManager.isSpeakerphoneOn = true
            }
            AudioMode.CALL_SPEAKER -> {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = true
            }
            AudioMode.CALL_EARPIECE -> {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = false
            }
        }
    }

    suspend fun fetchToken(mode: String): TokenResponse {
        return withContext(Dispatchers.IO) {
            try {
                val json = """{"mode": "$mode", "identity_prefix": "my-phone"}"""
                val request = Request.Builder()
                    .url("$apiBaseUrl/api/get-token")
                    .post(json.toRequestBody("application/json".toMediaType()))
                    .build()

                withTimeout(500) { // 稍微放宽一点点超时，确保稳定
                    val response = httpClient.newCall(request).execute()
                    if (!response.isSuccessful) {
                        throw Exception("Failed to get token: ${response.code}")
                    }
                    val responseBody = response.body?.string() ?: throw Exception("Empty response")
                    val jsonObject = JSONObject(responseBody)
                    TokenResponse(
                        token = jsonObject.getString("token"),
                        identity = jsonObject.getString("identity"),
                        room = jsonObject.getString("room"),
                        url = jsonObject.getString("url")
                    )
                }
            } catch (e: Exception) {
                Log.e("VoiceAssistant", "Failed to fetch token", e)
                throw e
            }
        }
    }

    // [修改] 切换逻辑不再获取 Token，直接复用
    suspend fun switchAudioMode(mode: AudioMode) {
        if (currentMode == mode) return
        
        Log.d("VoiceAssistant", "开始切换模式：$mode (复用现有 Token)")

        try {
            // 1. 应用硬件音频状态
            applyAudioState(mode)
            currentMode = mode

            // 2. 销毁旧 Room
            room.disconnect()
            room.release()

            // 3. 创建新 Room (重新初始化音频引擎)
            // 注意：tokenSource 已经在 init 中设置好了，这里直接复用
            room = createRoomInstance(mode)

            Log.d("VoiceAssistant", "模式切换完成")
        } catch (e: Exception) {
            Log.e("VoiceAssistant", "切换模式失败", e)
        }
    }

    init {
        Log.d("VoiceAssistant", "===== ViewModel 初始化 =====")
        
        viewModelScope.launch {
            try {
                // [1] 整个对话生命周期只在这里拿一次 Token
                val modeStr = if (currentMode == AudioMode.MEDIA_HIFI) "hardware" else "software"
                val response = fetchToken(modeStr)
                
                connectionUrl = response.url
                connectionToken = response.token
                
                // [2] 更新 TokenSource
                tokenSource = io.livekit.android.token.TokenSource.fromLiteral(connectionUrl, connectionToken)
                
                // [3] 应用初始音频硬件状态
                applyAudioState(currentMode)
                
                // [4] 只有从“媒体入口”进来，才跑原来的 1.5s 切换逻辑
                if (!startInCallMode) {
                    Log.d("VoiceAssistant", "媒体模式启动，等待 1500ms 后执行卡位切换...")
                    delay(1500)
                    switchAudioMode(AudioMode.CALL_SPEAKER)
                } else {
                    Log.d("VoiceAssistant", "通话模式启动，跳过自动切换逻辑")
                }
            } catch (e: Exception) {
                Log.e("VoiceAssistant", "初始化获取 Token 失败，回退至硬编码参数", e)
                // Fallback: 如果动态获取失败，使用路由传进来的原始参数
                connectionUrl = routeArgs.hardcodedUrl
                connectionToken = routeArgs.hardcodedToken
                tokenSource = if (routeArgs.sandboxId.isNotEmpty()) {
                    io.livekit.android.token.TokenSource.fromSandboxTokenServer(routeArgs.sandboxId)
                } else {
                    io.livekit.android.token.TokenSource.fromLiteral(connectionUrl, connectionToken)
                }
                applyAudioState(currentMode)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d("VoiceAssistant", "ViewModel 清理中...")
        room.disconnect()
        room.release()
    }
}