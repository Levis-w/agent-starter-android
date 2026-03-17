package io.livekit.android.example.voiceassistant.screen

import android.app.Activity
import android.content.Context.MEDIA_PROJECTION_SERVICE
import android.media.projection.MediaProjectionManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ChainStyle
import androidx.constraintlayout.compose.ConstrainScope
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.ConstraintSet
import androidx.constraintlayout.compose.Dimension
import androidx.constraintlayout.compose.Visibility
import androidx.constraintlayout.compose.layoutId
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import io.livekit.android.annotations.Beta
import io.livekit.android.compose.local.SessionScope
import io.livekit.android.compose.local.requireRoom
import io.livekit.android.compose.state.SessionOptions
import io.livekit.android.compose.state.rememberAgent
import io.livekit.android.compose.state.rememberLocalMedia
import io.livekit.android.compose.state.rememberSession
import io.livekit.android.compose.state.rememberSessionMessages
import io.livekit.android.compose.ui.VideoTrackView
import io.livekit.android.example.voiceassistant.rememberCanEnableCamera
import io.livekit.android.example.voiceassistant.rememberCanEnableMic
import io.livekit.android.example.voiceassistant.requirePermissions
import io.livekit.android.example.voiceassistant.ui.AgentVisualization
import io.livekit.android.example.voiceassistant.ui.ChatBar
import io.livekit.android.example.voiceassistant.ui.ChatLog
import io.livekit.android.example.voiceassistant.ui.ControlBar
import io.livekit.android.example.voiceassistant.viewmodel.VoiceAssistantViewModel
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class VoiceAssistantRoute(
    val sandboxId: String,
    val hardcodedUrl: String,
    val hardcodedToken: String,
    // 【修改】添加此参数，以便从按钮进入时区分模式
    val startInCallMode: Boolean = false
)

@Composable
fun VoiceAssistantScreen(
    viewModel: VoiceAssistantViewModel,
    onEndCall: () -> Unit,
) {
    VoiceAssistant(
        viewModel = viewModel,
        modifier = Modifier.fillMaxSize(),
        onEndCall = onEndCall
    )
}

@OptIn(Beta::class, ExperimentalPermissionsApi::class)
@Composable
fun VoiceAssistant(
    viewModel: VoiceAssistantViewModel,
    modifier: Modifier = Modifier,
    onEndCall: () -> Unit
) {
    var requestedAudio by remember { mutableStateOf(true) } 
    var requestedVideo by remember { mutableStateOf(false) }

    requirePermissions(requestedAudio, requestedVideo)

    val canEnableMic by rememberCanEnableMic()
    val canEnableVideo by rememberCanEnableCamera()
    val context = LocalContext.current

    // 【关键修改】使用 key(viewModel.room)
    // 当 ViewModel 切换模式并创建新 Room 时，key 会变化，
    // 这会导致 key 内部的所有状态（包括 session 和 localMedia）重置，保证逻辑正确。
    key(viewModel.room) {
        val session = rememberSession(
            tokenSource = viewModel.tokenSource,
            options = SessionOptions(
                room = viewModel.room
            )
        )

        SessionScope(session = session) { session ->

            LaunchedEffect(canEnableMic) {
                if (!canEnableMic) return@LaunchedEffect
                val result = session.start()
                if (result.isFailure) {
                    Toast.makeText(context, "Error connecting to the session.", Toast.LENGTH_SHORT).show()
                    onEndCall()
                }
            }

            DisposableEffect(Unit) {
                onDispose {
                    session.end()
                }
            }

            val room = requireRoom()
            var chatVisible by remember { mutableStateOf(false) }

            val localMedia = rememberLocalMedia()
            val isMicEnabled by localMedia::isMicrophoneEnabled
            val isCameraEnabled by localMedia::isCameraEnabled
            val isScreenShareEnabled by localMedia::isScreenShareEnabled

            // 【完全保留原代码的静音逻辑】
            LaunchedEffect(canEnableMic, requestedAudio) {
                session.waitUntilConnected()
                localMedia.setMicrophoneEnabled(canEnableMic && requestedAudio)
            }

            LaunchedEffect(canEnableVideo, requestedVideo) {
                session.waitUntilConnected()
                localMedia.setCameraEnabled(canEnableVideo && requestedVideo)
            }

            val sessionMessages = rememberSessionMessages()
            val agent = rememberAgent()

            val constraints = getConstraints(chatVisible, isCameraEnabled, isScreenShareEnabled)
            ConstraintLayout(
                constraintSet = constraints,
                modifier = modifier,
                animateChangesSpec = spring()
            ) {
                val coroutineScope = rememberCoroutineScope { Dispatchers.IO }

                ChatLog(
                    room = room,
                    messages = sessionMessages.messages,
                    modifier = Modifier.layoutId(LAYOUT_ID_CHAT_LOG)
                )

                var message by rememberSaveable { mutableStateOf("") }
                ChatBar(
                    value = message,
                    onValueChange = { message = it },
                    onChatSend = { msg ->
                        coroutineScope.launch { sessionMessages.send(msg) }
                        message = ""
                    },
                    modifier = Modifier.layoutId(LAYOUT_ID_CHAT_BAR)
                )

                val agentBorderAlpha by animateFloatAsState(if (chatVisible) 1f else 0f, label = "agentBorderAlpha")
                AgentVisualization(
                    agent = agent,
                    modifier = Modifier
                        .layoutId(LAYOUT_ID_AGENT)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = agentBorderAlpha), RoundedCornerShape(8.dp))
                )

                val screenSharePermissionLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                        val resultCode = result.resultCode
                        val data = result.data
                        if (resultCode != Activity.RESULT_OK || data == null) return@rememberLauncherForActivityResult
                        coroutineScope.launch {
                            requestedVideo = false
                            localMedia.setScreenShareEnabled(true, ScreenCaptureParams(data))
                        }
                    }

                ControlBar(
                    isMicEnabled = isMicEnabled,
                    onMicClick = { requestedAudio = !requestedAudio },
                    localAudioTrack = localMedia.microphoneTrack,
                    isCameraEnabled = isCameraEnabled,
                    onCameraClick = {
                        requestedVideo = !requestedVideo
                        if (requestedVideo) {
                            coroutineScope.launch { localMedia.setScreenShareEnabled(false) }
                        }
                    },
                    isScreenShareEnabled = isScreenShareEnabled,
                    onScreenShareClick = {
                        if (!isScreenShareEnabled) {
                            val mediaProjectionManager = context.getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            screenSharePermissionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                        } else {
                            coroutineScope.launch { localMedia.setScreenShareEnabled(false) }
                        }
                    },
                    isChatEnabled = chatVisible,
                    onChatClick = { chatVisible = !chatVisible },
                    onExitClick = onEndCall,
                    // 【关键】如果你在 ControlBar 中实现了模式切换按钮，请确保 viewModel.switchAudioMode 被正常调用
                    modifier = Modifier.layoutId(LAYOUT_ID_CONTROL_BAR)
                )

                val cameraAlpha by animateFloatAsState(targetValue = if (isCameraEnabled) 1f else 0f, label = "Camera Alpha")
                Box(
                    modifier = Modifier
                        .layoutId(LAYOUT_ID_CAMERA)
                        .clickable { localMedia.switchCamera() }
                        .clip(RoundedCornerShape(8.dp))
                        .alpha(cameraAlpha)
                ) {
                    VideoTrackView(
                        trackReference = localMedia.cameraTrack,
                        modifier = Modifier.fillMaxSize()
                    )

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 8.dp, bottom = 8.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                            .fillMaxWidth(.35f)
                            .aspectRatio(1f)
                    ) {
                        Icon(
                            Icons.Default.Cameraswitch,
                            tint = Color.White.copy(alpha = 0.7f),
                            contentDescription = "Flip Camera",
                            modifier = Modifier.fillMaxSize(0.6f)
                        )
                    }
                }

                val screenShareAlpha by animateFloatAsState(targetValue = if (isScreenShareEnabled) 1f else 0f, label = "Screen Share Alpha")
                VideoTrackView(
                    trackReference = localMedia.screenShareTrack,
                    modifier = Modifier
                        .layoutId(LAYOUT_ID_SCREENSHARE)
                        .clip(RoundedCornerShape(8.dp))
                        .alpha(screenShareAlpha)
                )
            }
        }
    }
}

// ... 下面的 getConstraints 和 LAYOUT_ID 完全保持官方原样不变