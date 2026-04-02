package io.livekit.android.example.voiceassistant.ui
import androidx.compose.material3.IconButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.outlined.PresentToAll
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhoneEnabled
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.tooling.preview.Preview
import io.livekit.android.annotations.Beta
import io.livekit.android.compose.types.TrackReference
import io.livekit.android.compose.ui.audio.AudioBarVisualizer
import io.livekit.android.example.voiceassistant.viewmodel.AudioMode

private val buttonModifier = Modifier
    .width(40.dp)
    .height(40.dp)

@Composable
private fun Modifier.enabledButtonModifier(enabled: Boolean): Modifier {
    return if (enabled) {
        this
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
    } else {
        this
    }
}

@OptIn(Beta::class)
@Composable
fun ControlBar(
    isMicEnabled: Boolean,
    onMicClick: () -> Unit,
    localAudioTrack: TrackReference?,
    isCameraEnabled: Boolean,
    onCameraClick: () -> Unit,
    isScreenShareEnabled: Boolean,
    onScreenShareClick: () -> Unit,
    isChatEnabled: Boolean,
    onChatClick: () -> Unit,
    onExitClick: () -> Unit,
    currentMode: AudioMode = AudioMode.MEDIA_HIFI,
    onAudioModeChange: (AudioMode) -> Unit,
    ambientEnabled: Boolean = true,
    typingEnabled: Boolean = false,
    onAmbientToggle: (Boolean) -> Unit = {},
    onTypingToggle: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var audioModeMenuExpanded by remember { mutableStateOf(false) }
    var soundMenuExpanded by remember { mutableStateOf(false) }
    
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
            .padding(horizontal = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(onClick = onMicClick)
                .height(40.dp)
                .width(100.dp)
                .enabledButtonModifier(isMicEnabled)
        ) {
            Spacer(Modifier.size(4.dp))
            
            val micIcon = if (isMicEnabled) {
                Icons.Default.Mic
            } else {
                Icons.Default.MicOff
            }
            Icon(
                micIcon,
                "Toggle Microphone",
                modifier = Modifier.size(20.dp)
            )
            
            Spacer(Modifier.size(4.dp))
            
              if (isMicEnabled && localAudioTrack != null) {
                  AudioBarVisualizer(
                      audioTrackRef = localAudioTrack,
                      modifier = Modifier
                          .fillMaxWidth()
                          .height(12.dp),
                      barCount = 4
                 )
            } else {
                Spacer(Modifier.weight(1f))
            }
            
            Spacer(Modifier.size(4.dp))
        }
        
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { audioModeMenuExpanded = true }
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 6.dp)
            ) {
                val modeIcon = when (currentMode) {
                    AudioMode.MEDIA_HIFI -> Icons.Default.MusicNote
                    AudioMode.CALL_SPEAKER -> Icons.Default.VolumeUp
                    AudioMode.CALL_EARPIECE -> Icons.Default.PhoneEnabled
                }
                Icon(
                    modeIcon,
                    "Current Audio Mode",
                    modifier = Modifier.size(20.dp)
                )
                Icon(
                    Icons.Default.ExpandMore,
                    "Select Audio Mode",
                    modifier = Modifier.size(16.dp)
                )
            }
            
            DropdownMenu(
                expanded = audioModeMenuExpanded,
                onDismissRequest = { audioModeMenuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("音乐模式") },
                    onClick = {
                        onAudioModeChange(AudioMode.MEDIA_HIFI)
                        audioModeMenuExpanded = false
                    },
                    leadingIcon = {
                        Icon(Icons.Default.MusicNote, null)
                    }
                )
                DropdownMenuItem(
                    text = { Text("扬声器") },
                    onClick = {
                        onAudioModeChange(AudioMode.CALL_SPEAKER)
                        audioModeMenuExpanded = false
                    },
                    leadingIcon = {
                        Icon(Icons.Default.VolumeUp, null)
                    }
                )
                DropdownMenuItem(
                    text = { Text("听筒") },
                    onClick = {
                        onAudioModeChange(AudioMode.CALL_EARPIECE)
                        audioModeMenuExpanded = false
                    },
                    leadingIcon = {
                        Icon(Icons.Default.PhoneEnabled, null)
                    }
                )
            }
        }

        Spacer(Modifier.size(4.dp))

        // Sound Menu (背景音 + 键盘音)
        Box {
            IconButton(
                onClick = { soundMenuExpanded = true },
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Icon(
                    Icons.Default.MusicNote,
                    "Sound Settings",
                    modifier = Modifier.size(20.dp)
                )
            }

            DropdownMenu(
                expanded = soundMenuExpanded,
                onDismissRequest = { soundMenuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.MusicNote, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("背景音")
                            Spacer(Modifier.weight(1f))
                            Text(if (ambientEnabled) "开" else "关")
                        }
                    },
                    onClick = {
                        onAmbientToggle(!ambientEnabled)
                        soundMenuExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Keyboard, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("键盘音")
                            Spacer(Modifier.weight(1f))
                            Text(if (typingEnabled) "开" else "关")
                        }
                    },
                    onClick = {
                        onTypingToggle(!typingEnabled)
                        soundMenuExpanded = false
                    }
                )
            }
        }
        
        Spacer(Modifier.size(4.dp))
        
        IconButton(
            onClick = onCameraClick,
            modifier = Modifier
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .enabledButtonModifier(isCameraEnabled)
        ) {
            val cameraIcon = if (isCameraEnabled) {
                Icons.Default.Videocam
            } else {
                Icons.Default.VideocamOff
            }
            Icon(
                cameraIcon,
                "Toggle Camera",
                modifier = Modifier.size(20.dp)
            )
        }
        
        Spacer(Modifier.size(4.dp))
        
        IconButton(
            onClick = onScreenShareClick,
            modifier = Modifier
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .enabledButtonModifier(!isScreenShareEnabled)
        ) {
            Icon(
                Icons.Outlined.PresentToAll,
                "Toggle Screen Share",
                modifier = Modifier.size(20.dp)
            )
        }
        
        Spacer(Modifier.size(4.dp))
        
        IconButton(
            onClick = onChatClick,
            modifier = Modifier
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Chat,
                "Toggle Chat",
                modifier = Modifier.size(20.dp)
            )
        }
        
        Spacer(Modifier.size(4.dp))
        
        IconButton(
            onClick = onExitClick,
            modifier = Modifier
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.error)
        ) {
            Icon(
                Icons.Default.CallEnd,
                "End Call",
                modifier = Modifier.size(20.dp),
                tint = Color.White
            )
        }
    }
}

@Preview
@Composable
fun ControlBarPreview() {
    MaterialTheme {
        ControlBar(
            isMicEnabled = true,
            onMicClick = {},
            localAudioTrack = null,
            isCameraEnabled = false,
            onCameraClick = {},
            isScreenShareEnabled = false,
            onScreenShareClick = {},
            isChatEnabled = false,
            onChatClick = {},
            onExitClick = {},
            currentMode = AudioMode.MEDIA_HIFI,
            onAudioModeChange = {}
        )
    }
}
