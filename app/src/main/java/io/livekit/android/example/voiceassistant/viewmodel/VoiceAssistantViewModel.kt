package io.livekit.android.example.voiceassistant.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.toRoute
import io.livekit.android.LiveKit
import io.livekit.android.example.voiceassistant.screen.VoiceAssistantRoute
import io.livekit.android.token.TokenSource
import io.livekit.android.token.cached
import android.media.AudioManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.livekit.android.room.RoomOptions
import io.livekit.android.room.AudioOptions
import io.livekit.android.room.AudioType
/**
 * This ViewModel handles holding onto the Room object, so that it is
 * maintained across configuration changes, such as rotation.
 */
class VoiceAssistantViewModel(application: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    var isHiFiMode by mutableStateOf(true)
    val room = LiveKit.create(
        context = application,
        options = RoomOptions(
                      audioOptions = AudioOptions(audioOutputType = AudioType.Media)
        )
    )
    fun toggleAudioMode() {
        isHiFiMode = !isHiFiMode
        if (isHiFiMode) {
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = true
        } else {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false
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
