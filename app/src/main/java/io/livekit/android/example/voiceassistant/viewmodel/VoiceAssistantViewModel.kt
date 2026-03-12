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
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.Track
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
    var currentMode by mutableStateOf(AudioMode.MEDIA_HIFI)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        .writeTimeout(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()
    private val apiBaseUrl = "http://192.168.6.233:8080"

    lateinit var tokenSource: io.livekit.android.token.TokenSource
    private var connectionUrl: String = ""
    private var connectionToken: String = ""

    var room: Room by mutableStateOf(createRoomInstance(AudioMode.MEDIA_HIFI))
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

                withTimeout(50) {
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

    suspend fun switchAudioMode(mode: AudioMode) {
        val startTime = System.currentTimeMillis()
        Log.d("VoiceAssistant", "========== 开始切换模式：$mode ==========")

        if (currentMode == mode) {
            Log.d("VoiceAssistant", "已经是目标模式，跳过切换")
            return
        }

        try {
            Log.d("VoiceAssistant", "[1] 开始获取 token...")
            val modeStr = if (mode == AudioMode.MEDIA_HIFI) "hardware" else "software"

            val tokenStart = System.currentTimeMillis()
            val tokenResponse = fetchToken(mode = modeStr)
            Log.d("VoiceAssistant", "[1] 获取 token 完成，耗时：${System.currentTimeMillis() - tokenStart}ms")
            Log.d("VoiceAssistant", "    identity: ${tokenResponse.identity}")

            Log.d("VoiceAssistant", "[2] 更新全局 token...")
            io.livekit.android.example.voiceassistant.updateToken(tokenResponse.token)

            Log.d("VoiceAssistant", "[3] 更新连接信息...")
            connectionUrl = tokenResponse.url
            connectionToken = tokenResponse.token

            Log.d("VoiceAssistant", "[4] 更新 TokenSource...")
            tokenSource = io.livekit.android.token.TokenSource.fromLiteral(connectionUrl, connectionToken)

            Log.d("VoiceAssistant", "[5] 应用音频状态...")
            val audioStart = System.currentTimeMillis()
            applyAudioState(mode)
            Log.d("VoiceAssistant", "[5] 音频状态完成，耗时：${System.currentTimeMillis() - audioStart}ms")

            Log.d("VoiceAssistant", "[6] 更新当前模式...")
            currentMode = mode

            Log.d("VoiceAssistant", "[7] 销毁旧 room...")
            val roomStart = System.currentTimeMillis()
            room.disconnect()
            Log.d("VoiceAssistant", "    disconnect 耗时：${System.currentTimeMillis() - roomStart}ms")

            room.release()
            Log.d("VoiceAssistant", "[7] 创建新 room...")
            room = createRoomInstance(mode)
            Log.d("VoiceAssistant", "[7] room 完成，耗时：${System.currentTimeMillis() - roomStart}ms")

            Log.d("VoiceAssistant", "========== 切换成功！总耗时：${System.currentTimeMillis() - startTime}ms ==========")

        } catch (e: Exception) {
            Log.e("VoiceAssistant", "========== 切换失败，使用 fallback ==========")
            Log.e("VoiceAssistant", "错误：${e.message}")
            currentMode = mode
            applyAudioState(mode)
            viewModelScope.launch {
                delay(500)
            Log.d("VoiceAssistant", "fallback 完成，总耗时：${System.currentTimeMillis() - startTime}ms")
        }
      }
    }
    init {
        Log.d("VoiceAssistant", "===== ViewModel 初始化 =====")
        val (sandboxId, url, token) = savedStateHandle.toRoute<VoiceAssistantRoute>()
        connectionUrl = url
        connectionToken = token

        tokenSource = if (sandboxId.isNotEmpty()) {
            io.livekit.android.token.TokenSource.fromSandboxTokenServer(sandboxId)
        } else {
            io.livekit.android.token.TokenSource.fromLiteral(url, token)
        }

        viewModelScope.launch {
            Log.d("VoiceAssistant", "等待 100ms 后开始自动切换...")
            delay(100)
            Log.d("VoiceAssistant", "开始自动切换到 CALL_SPEAKER...")
            switchAudioMode(AudioMode.CALL_SPEAKER)
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d("VoiceAssistant", "ViewModel 清理中...")
        room.disconnect()
        room.release()
        Log.d("VoiceAssistant", "ViewModel 已清理")
    }
}
