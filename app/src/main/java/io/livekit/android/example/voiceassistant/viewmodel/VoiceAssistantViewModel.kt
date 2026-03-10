package io.livekit.android.example.voiceassistant.viewmodel

import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
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
import io.livekit.android.room.participant.LocalParticipant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class VoiceAssistantViewModel(application: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    var isHiFiMode by mutableStateOf(true)
    
    val room: Room = LiveKit.create(
        appContext = application,
        options = RoomOptions(
            audioCaptureOptions = AudioCaptureOptions(
                echoCancellation = true,
                noiseSuppression = false,
                autoGainControl = false
            )
        )
    )
    
    fun switchAudioMode(switchToHiFi: Boolean) {
        isHiFiMode = switchToHiFi
        
        // 1. 先命令上层的 AudioManager
        if (isHiFiMode) {
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = true
        } else {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false
        }
        
        // 2. 准备新的轨道配置，这是“欺骗”系统的关键
        val newAudioOptions = LocalAudioTrackOptions(
            // 根据目标模式，动态开关这些关键参数
            echoCancellation = true, // 回声消除始终需要
            noiseSuppression = !isHiFiMode, // 只有在通话模式才开启系统降噪
            autoGainControl = !isHiFiMode   // 只有在通话模式才开启自动增益
        )
        
        // 3. 命令 LiveKit SDK 重置音频管道
        viewModelScope.launch(Dispatchers.IO) {
            val localParticipant: LocalParticipant? = room.localParticipant
            // 停止当前的麦克风轨道，这会释放底层的 AudioRecord 资源
            localParticipant?.unpublishTrack(localParticipant.microphoneTrack)
            
            // 等待一小会，确保资源完全释放
            kotlinx.coroutines.delay(200) 
            
            // 使用新的配置，创建一个全新的麦克风轨道并发布
            // 这个操作会强制安卓系统用我们新指定的参数重新初始化音频管道
            val newTrack: LocalAudioTrack = localParticipant?.createAudioTrack(options = newAudioOptions) ?: return@launch
            localParticipant.publishAudioTrack(newTrack)
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
