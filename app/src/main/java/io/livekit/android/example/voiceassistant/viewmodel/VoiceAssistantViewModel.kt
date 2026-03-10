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
import io.livekit.android.LiveKit
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
    
    // UI 状态变量
    var isHiFiMode by mutableStateOf(true)
    
    // ✅ 适配 2.23.5：直接使用默认创建，避开所有不确定的 Options 类路径
    // 我们通过后面的 switchAudioMode 连招来接管音频质量
    val room: Room = LiveKit.create(application)

    /**
     * 【核心连招：强制重置音频管道】
     * 逻辑：切换系统路由 -> 停用并卸载当前轨道 -> 以新硬件参数重开轨道
     */
    fun switchAudioMode(switchToHiFi: Boolean) {
        isHiFiMode = switchToHiFi
        
        viewModelScope.launch(Dispatchers.IO) {
            // 1. 设置系统音频模式
            if (switchToHiFi) {
                audioManager.mode = AudioManager.MODE_NORMAL
                audioManager.isSpeakerphoneOn = true
            } else {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = false
            }
            
            // 2. 找到当前正在运行的麦克风轨道并将其“断开”
            val localParticipant: LocalParticipant = room.localParticipant
            // 2.23.5 版本获取轨道的方式
            val trackPub = localParticipant.getTrackPublication(Track.Source.MICROPHONE)
            val oldTrack = trackPub?.track as? LocalAudioTrack
            
            if (oldTrack != null) {
                // 卸载轨道：这等同于 Web 端的流重置，会强迫系统释放 AudioRecord
                localParticipant.unpublishTrack(oldTrack)
                oldTrack.stop() 
            }
            
            // 3. 关键等待：给 Android 底层硬件状态机 400ms 时间彻底重置
            delay(400) 
            
            // 4. 定义全新的硬件采集选项（针对 2.23.5 版本）
            // 如果是媒体模式(HiFi)，关闭降噪和增益，强制系统进入高质量采集
            val newAudioOptions = LocalAudioTrackOptions(
                echoCancellation = true,
                noiseSuppression = !switchToHiFi, 
                autoGainControl = !switchToHiFi
            )
            
            // 5. 重新创建并发布轨道
            // 这是 2.23.5 的标准方法名和参数
            val newTrack = localParticipant.createAudioTrack("microphone", options = newAudioOptions)
            localParticipant.publishAudioTrack(newTrack)
            
            // 6. 二次强制锁定（防止部分国产机型在开启轨道时自动回切通话模式）
            delay(200)
            if (switchToHiFi) {
                audioManager.mode = AudioManager.MODE_NORMAL
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
