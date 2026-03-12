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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

enum class AudioMode {
    MEDIA_HIFI,
    CALL_SPEAKER,
    CALL_EARPIECE
}

// Token 请求/响应数据类
data class TokenRequest(val mode: String, val identity_prefix: String = "my-phone")
data class TokenResponse(val token: String, val identity: String, val room: String, val url: String)

class VoiceAssistantViewModel(application: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    var currentMode by mutableStateOf(AudioMode.MEDIA_HIFI)
    
    // HTTP 客户端
    private val httpClient = OkHttpClient()
    
    // 服务器 API 地址
    private val apiBaseUrl = "http://192.168.6.233:8080"
    
    // 显式引用 SDK 的 TokenSource 避免冲突
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

    /**
     * 从服务器获取动态 token
     */
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

    /**
     * 切换音频模式（带动态 token）
     */
    suspend fun switchAudioMode(mode: AudioMode) {
        if (currentMode == mode) return
        
        try {
            Log.d("VoiceAssistant", "Switching audio mode to: $mode")
            
            // 1. 获取新 token（带新 identity）
            val modeStr = if (mode == AudioMode.MEDIA_HIFI) "hardware" else "software"
            val tokenResponse = fetchToken(mode = modeStr)
            
            Log.d("VoiceAssistant", "Got new token, identity: ${tokenResponse.identity}")
            
            // 2. 更新全局 token（TokenExt.kt 中的 hardcodedToken）
            io.livekit.android.example.voiceassistant.updateToken(tokenResponse.token)
            
            // 3. 更新连接信息
            connectionUrl = tokenResponse.url
            connectionToken = tokenResponse.token
            
            // 4. 更新 SDK 的 TokenSource
            tokenSource = io.livekit.android.token.TokenSource.fromLiteral(connectionUrl, connectionToken)
            
            // 5. 应用音频状态
            applyAudioState(mode)
            
            // 6. 更新当前模式
            currentMode = mode
            
            // 7. 销毁旧 room 并创建新 room
            room.disconnect()
            room.release()
            room = createRoomInstance(mode)
            
            Log.d("VoiceAssistant", "Successfully switched to $modeStr mode")
            
            
        } catch (e: Exception) {
            Log.e("VoiceAssistant", "Failed to switch audio mode", e)
            // 降级处理：使用旧配置重试
            currentMode = mode
            applyAudioState(mode)
        }
    }
    
    init {
        val (sandboxId, url, token) = savedStateHandle.toRoute<VoiceAssistantRoute>()
        connectionUrl = url
        connectionToken = token
        
        // 初始化 SDK 格式的 TokenSource
        tokenSource = if (sandboxId.isNotEmpty()) {
            io.livekit.android.token.TokenSource.fromSandboxTokenServer(sandboxId)
        } else {
            io.livekit.android.token.TokenSource.fromLiteral(url, token)
        }
        viewModelScope.launch {
        delay(100)
        switchAudioMode(AudioMode.CALL_SPEAKER)
    }
}
    
    override fun onCleared() {
        super.onCleared()
        room.disconnect()
        room.release()
    }
}
