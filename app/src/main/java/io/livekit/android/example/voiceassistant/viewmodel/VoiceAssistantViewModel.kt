package io.livekit.android.example.voiceassistant.viewmodel

import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.toRoute
import io.livekit.android.LiveKit
import io.livekit.android.example.voiceassistant.screen.VoiceAssistantRoute
import io.livekit.android.room.Room
import io.livekit.android.token.TokenSource
import io.livekit.android.token.cached
import io.livekit.android.room.RoomOptions
import io.livekit.android.room.track.AudioCaptureOptions
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.Track
import io.livekit.android.room.participant.LocalParticipant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay


class VoiceAssistantViewModel(application: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    var isHiFiMode by mutableStateOf(true)
    val room: Room = LiveKit.create(application)
    
    fun switchAudioMode(switchToHiFi: Boolean) {
        isHiFiMode = switchToHiFi

        viewModelScope.launch(Dispatchers.IO) {
            // 1. 设置 AudioManager 路由
            if (switchToHiFi) {
                audioManager.mode = AudioManager.MODE_NORMAL
                audioManager.isSpeakerphoneOn = true
            } else {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = false
            }

            // 2. 模拟 Web 端的“断开连接”：卸载当前的麦克风轨道
            val localParticipant = room.localParticipant
            val oldTrack = localParticipant.getTrackPublication(Track.Source.MICROPHONE)?.track as? LocalAudioTrack
            
            if (oldTrack != null) {
                localParticipant.unpublishTrack(oldTrack)
                oldTrack.stop() // 彻底停止旧的硬件占用
            }

            // 3. 关键的等待：让系统释放 AudioRecord 资源
            delay(300) 

            // 4. 定义新的硬件采集参数（这步最关键）
            val newOptions = LocalAudioTrackOptions(
                echoCancellation = true, // 始终开启回声消除
                // 如果是媒体模式，强行关掉降噪和增益（防止系统判定为通话）
                noiseSuppression = !switchToHiFi, 
                autoGainControl = !switchToHiFi
            )

            // 5. 重新发布轨道：相当于 Web 端的“重连并获取新流”
            val newTrack = localParticipant.createAudioTrack("microphone", newOptions)
            localParticipant.publishAudioTrack(newTrack)
            
            // 6. 再次确认模式（防止 SDK 内部再次抢占）
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
