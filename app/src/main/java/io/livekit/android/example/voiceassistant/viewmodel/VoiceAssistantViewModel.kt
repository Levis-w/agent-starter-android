package io.livekit.android.example.voiceassistant.viewmodel

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
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

enum class AudioMode {
    MEDIA_HIFI,
    CALL_SPEAKER,
    CALL_EARPIECE
}

class VoiceAssistantViewModel(application: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    var currentMode by mutableStateOf(AudioMode.MEDIA_HIFI)
    
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
                        .setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
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

    fun switchAudioMode(mode: AudioMode) {
        if (currentMode == mode) return
        currentMode = mode
        
        // 核心：只更新 room 实例并应用物理状态
        applyAudioState(mode)
        room = createRoomInstance(mode)

        // 守护任务：持续确认物理状态（防止系统自动重置）
        viewModelScope.launch {
            repeat(10) {
                applyAudioState(mode)
                delay(500)
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
