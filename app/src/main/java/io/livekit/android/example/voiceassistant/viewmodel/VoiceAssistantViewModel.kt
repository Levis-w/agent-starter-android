package io.livekit.android.example.voiceassistant.viewmodel

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.AudioAttributes // 新增
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides // 新增
import io.livekit.android.audio.NoAudioHandler // 核心：禁用 SDK 自动管理
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
    
    // ✅ 【最关键的修改】禁用 LiveKit 的自动音频处理器
    // 这让 LiveKit 变得像 Web 浏览器一样“笨”，不再自动强切通话模式
    val room: Room = LiveKit.create(
        appContext = application,
        overrides = LiveKitOverrides(
            audioHandler = NoAudioHandler() 
        )
    )

    fun switchAudioMode(switchToHiFi: Boolean) {
        isHiFiMode = switchToHiFi
        
        viewModelScope.launch(Dispatchers.IO) {
            // 1. 彻底断开当前音轨
            val localParticipant: LocalParticipant = room.localParticipant
            val trackPub = localParticipant.getTrackPublication(Track.Source.MICROPHONE)
            val oldTrack = trackPub?.track as? LocalAudioTrack
            
            if (oldTrack != null) {
                localParticipant.unpublishTrack(oldTrack)
                oldTrack.stop()
            }
            
            delay(400) // 等待硬件彻底释放

            // 2. 强行设置 AudioManager 模式
            // 因为禁用了 NoAudioHandler，现在这个设置不会被 SDK 改回去了
            if (switchToHiFi) {
                audioManager.mode = AudioManager.MODE_NORMAL
                audioManager.isSpeakerphoneOn = true
            } else {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = false
            }

            // 3. 创建新音轨
            val newAudioOptions = LocalAudioTrackOptions(
                echoCancellation = true,
                noiseSuppression = !switchToHiFi, 
                autoGainControl = !switchToHiFi
            )
            
            val newTrack = localParticipant.createAudioTrack("microphone", options = newAudioOptions)
            localParticipant.publishAudioTrack(newTrack)
            
            // 4. 【额外补刀】强制将音轨类型标记为媒体流
            delay(300)
            if (switchToHiFi) {
                audioManager.mode = AudioManager.MODE_NORMAL
                // 再次确保扬声器打开
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
