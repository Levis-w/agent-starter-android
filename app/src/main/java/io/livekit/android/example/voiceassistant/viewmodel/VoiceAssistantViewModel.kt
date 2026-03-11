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
import io.livekit.android.token.TokenSource
import io.livekit.android.token.cached
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 三种音频模式
 */
enum class AudioMode {
    MEDIA_HIFI,      // 媒体通道 + WebRTC 软件 AEC（默认）
    CALL_SPEAKER,    // 通话模式 + 扬声器 + 硬件 AEC
    CALL_EARPIECE    // 通话模式 + 听筒 + 硬件 AEC
}

class VoiceAssistantViewModel(application: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    var currentMode by mutableStateOf(AudioMode.MEDIA_HIFI)
    
    // ✅ 【三种模式配置】
    val room: Room = LiveKit.create(
        appContext = application,
        overrides = LiveKitOverrides(
            audioOptions = AudioOptions(
                // 默认媒体通道模式
                audioOutputType = AudioType.MediaAudioType(),
                audioHandler = NoAudioHandler(),
                disableCommunicationModeWorkaround = true,
                // 关闭硬件 AEC/NS，使用 WebRTC 软件 AEC
                javaAudioDeviceModuleCustomizer = { builder ->
                    builder
                        .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                        .setUseHardwareAcousticEchoCanceler(false)
                        .setUseHardwareNoiseSuppressor(false)
                }
            )
        )
    )

    /**
     * 切换到指定音频模式
     */
    fun switchAudioMode(mode: AudioMode) {
        currentMode = mode
        
        viewModelScope.launch(Dispatchers.IO) {
            when (mode) {
                AudioMode.MEDIA_HIFI -> {
                    // 媒体通道模式 - WebRTC 软件 AEC
                    audioManager.mode = AudioManager.MODE_NORMAL
                    audioManager.isSpeakerphoneOn = true
                }
                AudioMode.CALL_SPEAKER -> {
                    // 通话模式 - 扬声器 - 硬件 AEC
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager.isSpeakerphoneOn = true
                }
                AudioMode.CALL_EARPIECE -> {
                    // 通话模式 - 听筒 - 硬件 AEC
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager.isSpeakerphoneOn = false
                }
            }

            // 1. 停止并取消发布旧轨道
            val localParticipant: LocalParticipant = room.localParticipant
            val trackPub = localParticipant.getTrackPublication(Track.Source.MICROPHONE)
            val oldTrack = trackPub?.track as? LocalAudioTrack
            
            if (oldTrack != null) {
                localParticipant.unpublishTrack(oldTrack)
                oldTrack.stop()
            }
            
            // 2. 关键等待：给系统足够的时间去重置底层硬件 Session
            delay(500) 

            // 3. 根据模式配置轨道参数
            val newAudioOptions = when (mode) {
                AudioMode.MEDIA_HIFI -> {
                    // 媒体模式：开启 WebRTC 软件 AEC/NS，关闭 AGC
                    LocalAudioTrackOptions(
                        echoCancellation = true,
                        noiseSuppression = true,
                        autoGainControl = false,
                        highPassFilter = true,
                        typingNoiseDetection = true
                    )
                }
                AudioMode.CALL_SPEAKER, AudioMode.CALL_EARPIECE -> {
                    // 通话模式：使用硬件 AEC（SDK 默认），关闭软件处理
                    LocalAudioTrackOptions(
                        echoCancellation = false,
                        noiseSuppression = false,
                        autoGainControl = false,
                        highPassFilter = false,
                        typingNoiseDetection = false
                    )
                }
            }
            
            // 4. 重新发布新轨道
            val newTrack = localParticipant.createAudioTrack("microphone", options = newAudioOptions)
            localParticipant.publishAudioTrack(newTrack)
            
            // 5. 二次强制补刀，每 500ms 改一次，连改 3 次，彻底压制
            launch {
                repeat(3) {
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
                    delay(500)
                }
            }
        }
    }
    
    val tokenSource: TokenSource
    init {
        val (sandboxId, url, token) = savedStateHandle.toRoute<VoiceAssistantRoute>()
        tokenSource = if (sandboxId.isNotEmpty()) {
            TokenSource.fromSandboxTokenServer(sandboxId = sandboxId).cached()
        } else {
            TokenSource.fromLiteral(url, token).cached()
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        room.disconnect()
        room.release()
    }
}
                audioManager.mode = AudioManager.MODE_NORMAL
                audioManager.isSpeakerphoneOn = true
            } else {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = false
            }

            // 2. 彻底重置轨道（复刻 Web 端“断开重连”逻辑）
            val localParticipant: LocalParticipant = room.localParticipant
            val trackPub = localParticipant.getTrackPublication(Track.Source.MICROPHONE)
            val oldTrack = trackPub?.track as? LocalAudioTrack
            
            if (oldTrack != null) {
                localParticipant.unpublishTrack(oldTrack)
                oldTrack.stop()
            }
            
            // 3. 关键等待：给系统足够的时间去重置底层硬件 Session
            delay(500) 

            // 4. 配置硬件采集：媒体模式下必须关闭 NS/AGC 才能绕过系统“通话”判定
            val newAudioOptions = LocalAudioTrackOptions(
                echoCancellation = !switchToHiFi, 
                noiseSuppression = !switchToHiFi, 
                autoGainControl = !switchToHiFi
            )
            
            // 5. 重新发布新轨道
            val newTrack = localParticipant.createAudioTrack("microphone", options = newAudioOptions)
            localParticipant.publishAudioTrack(newTrack)
            
            // 6. 二次强制补刀，每 500ms 改一次，连改 3 次，彻底压制
            launch {
                repeat(3) {
                    if (switchToHiFi) {
                        audioManager.mode = AudioManager.MODE_NORMAL
                        audioManager.isSpeakerphoneOn = true
                    }
                    delay(500)
                }
            }
        }
    }
    
    val tokenSource: TokenSource
    init {
        val (sandboxId, url, token) = savedStateHandle.toRoute<VoiceAssistantRoute>()
        tokenSource = if (sandboxId.isNotEmpty()) {
            TokenSource.fromSandboxTokenServer(sandboxId = sandboxId).cached()
        } else {
            TokenSource.fromLiteral(url, token).cached()
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        room.disconnect()
        room.release()
    }
}
