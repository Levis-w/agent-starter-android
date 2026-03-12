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
        // 始终使用 MediaAudioType 以保证 48kHz 高音质底座
        // 具体的硬件降噪触发由 applyAudioState 动态控制
        val audioOptions = AudioOptions(
            audioOutputType = AudioType.MediaAudioType(),
            audioHandler = NoAudioHandler(),
            javaAudioDeviceModuleCustomizer = { builder ->
                builder
                    .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                    // 设置为 false 以避开 SDK 的自动逻辑，交由 applyAudioState 强制触发系统底层硬件
                    .setUseHardwareAcousticEchoCanceler(false)
                    .setUseHardwareNoiseSuppressor(false)
            }
        )
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
     * 切换音频模式（不重连房间，秒级切换音效）
     */
    suspend fun switchAudioMode(mode: AudioMode) {
        if (currentMode == mode) return
        
        Log.d("VoiceAssistant", "Instantly switching audio mode to: $mode")
        
        // 1. 物理层：瞬间切换 AudioManager 模式
        // 这会触发系统的硬件 AEC 挂载，同时保留 Media 通道的高采样率
        applyAudioState(mode)
        
        // 2. 更新状态（这会触发 UI 的 LaunchedEffect 从而更新音轨配置）
        currentMode = mode
        
        // 3. 守护任务：持续确认物理状态（防止系统自动重置）
        viewModelScope.launch {
            repeat(5) {
                applyAudioState(mode)
                delay(300)
            }
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
    }
    
    override fun onCleared() {
        super.onCleared()
        room.disconnect()
        room.release()
    }
}
