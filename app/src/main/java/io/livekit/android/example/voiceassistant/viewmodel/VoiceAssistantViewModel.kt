package io.livekit.android.example.voiceassistant.viewmodel

import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import io.livekit.android.AudioOptions // ✅ 官方文档中的类
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.NoAudioHandler // ✅ 禁用 SDK 自动管理
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
    
    // ✅ 【核心手术点】按照你提供的 2.23.5 官方文档参数进行配置
    val room: Room = LiveKit.create(
        appContext = application,
        overrides = LiveKitOverrides(
            audioOptions = AudioOptions(
                audioHandler = NoAudioHandler(), // 1. 废掉 SDK 的音频管理器
                disableCommunicationModeWorkaround = true // 2. 禁用 6 秒自动重置通话模式的机制
            )
        )
    )

    /**
     * 【重置音频管道切换连招】
     */
    fun switchAudioMode(switchToHiFi: Boolean) {
        isHiFiMode = switchToHiFi
        
        viewModelScope.launch(Dispatchers.IO) {
            // 1. 设置系统音频路由（现在 SDK 不会来抢夺控制权了）
            if (switchToHiFi) {
                audioManager.mode = AudioManager.MODE_NORMAL
                audioManager.isSpeakerphoneOn = true
            } else {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = false
            }

            // 2. 模拟 Web 端的流断开重连：卸载当前麦克风
            val localParticipant: LocalParticipant = room.localParticipant
            val trackPub = localParticipant.getTrackPublication(Track.Source.MICROPHONE)
            val oldTrack = trackPub?.track as? LocalAudioTrack
            
            if (oldTrack != null) {
                localParticipant.unpublishTrack(oldTrack)
                oldTrack.stop()
            }
            
            // 3. 关键等待：给系统足够的时间去重置底层的状态机
            delay(500) 

            // 4. 创建新硬件参数：媒体模式下必须关掉 NS/AGC 才能骗过系统
            val newAudioOptions = LocalAudioTrackOptions(
                echoCancellation = !switchToHiFi,
                noiseSuppression = !switchToHiFi, 
                autoGainControl = !switchToHiFi
            )
            
            // 5. 重新发布轨道
            val newTrack = localParticipant.createAudioTrack("microphone", options = newAudioOptions)
            localParticipant.publishAudioTrack(newTrack)
            
            // 6. 二次补刀，确保稳稳锁定在媒体模式
            delay(300)
            if (switchToHiFi) {
                audioManager.mode = AudioManager.MODE_NORMAL
                audioManager.isSpeakerphoneOn = true
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
