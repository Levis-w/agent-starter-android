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
import io.livekit.android.RoomOptions
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

class VoiceAssistantViewModel(application: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    var isHiFiMode by mutableStateOf(true)
    
    // ✅ 【2.23.5 终极配置】
    val room: Room = LiveKit.create(
        appContext = application,
        overrides = LiveKitOverrides(
            audioOptions = AudioOptions(
                audioOutputType = AudioType.MediaAudioType(), // 1. 强制输出走媒体通道
                audioHandler = NoAudioHandler(),     // 2. 彻底禁用 SDK 的自动切换
                disableCommunicationModeWorkaround = true, // 3. 禁用 6秒自动切回
                // 4. 【核心黑科技】强行修改硬件音源，安卓系统会彻底认为这不是电话
                javaAudioDeviceModuleCustomizer = { builder ->
                    builder
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setUseHardwareAcousticEchoCanceler(false)
                        .setUseHardwareNoiseSuppressor(false) 
                }
            )
        )
    )

    fun switchAudioMode(switchToHiFi: Boolean) {
        isHiFiMode = switchToHiFi
        
        viewModelScope.launch(Dispatchers.IO) {
            // 1. 设置系统路由
            if (switchToHiFi) {
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
