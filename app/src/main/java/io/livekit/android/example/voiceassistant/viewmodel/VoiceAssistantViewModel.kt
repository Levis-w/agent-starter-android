package io.livekit.android.example.voiceassistant.viewmodel

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.util.Log
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class AudioMode {
    MEDIA_HIFI,
    CALL_SPEAKER,
    CALL_EARPIECE
}

data class TokenRequest(val mode: String, val identity_prefix: String = "my-phone")

data class TokenResponse(
    val token: String,
    val identity: String,
    val room: String,
    val url: String
)

class VoiceAssistantViewModel(application: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // 1. 提前解析路由参数
    private val routeArgs = savedStateHandle.toRoute<VoiceAssistantRoute>()
    private val startInCallMode = routeArgs.startInCallMode

    // 2. 根据参数初始化当前模式
    var currentMode by mutableStateOf(if (startInCallMode) AudioMode.CALL_SPEAKER else AudioMode.MEDIA_HIFI)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        .writeTimeout(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()
    private val apiBaseUrl = "http://192.168.6.233:8080"

    lateinit var tokenSource: io.livekit.android.token.TokenSource
    private var connectionUrl: String = ""
    private var connectionToken: String = ""

    // 3. 根据参数初始化 Room
    var room: Room by mutableStateOf(createRoomInstance(if (startInCallMode) AudioMode.CALL_SPEAKER else AudioMode.MEDIA_HIFI))
        private set

    private fun createRoomInstance(mode: AudioMode): Room {
        val audioOptions = when (mode) {
            AudioMode.MEDIA_HIFI -> AudioOptions(
                audioOutputType = AudioType.MediaAudioType(),
                audioHandler = NoAudioHandler(),
                javaAudioDeviceModuleCustomizer = { builder ->
                    builder
                        .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                        .setUseHardwareAcousticEchoCanceler(false)
                        .setUseHardwareNoiseSuppressor(false)
                }
            )
            AudioMode.CALL_SPEAKER, AudioMode.CALL_EARPIECE -> AudioOptions(
                audioOutputType = AudioType.CallAudioType(),
                audioHandler = NoAudioHandler(),
                javaAudioDeviceModuleCustomizer = { builder ->
                    builder
                        .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                        .setUseHardwareAcousticEchoCanceler(true)
                        .setUseHardwareNoiseSuppressor(true)
                }
            )
        }
        return LiveKit.create(getApplication(), overrides = LiveKitOverrides(audioOptions = audioOptions))
    }

    private fun applyAudioState(mode: AudioMode) {
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
    }

    suspend fun fetchToken(mode: String): TokenResponse {
        return withContext(Dispatchers.IO) {
            try {
                val json = """{"mode": "$mode", "identity_prefix": "my-phone"}"""
                val request = Request.Builder()
                    .url("$apiBaseUrl/api/get-token")
                    .post(json.toRequestBody("application/json".toMediaType()))
                    .build()

                withTimeout(50) {
                    val response = httpClient.newCall(request).execute()
                    if (!response.isSuccessful) {
                        throw Exception("Failed to get token: ${response.code}")
                    }
                    val responseBody = response.body?.string() ?: throw Exception("Empty response")
                    val jsonObject = JSONObject(responseBody)
                    TokenResponse(
                        token = jsonObject.getString("token"),
                        identity = jsonObject.getString("identity"),
                        room = jsonObject.getString("room"),
                        url = jsonObject.getString("url")
                    )
                }
            } catch (e: Exception) {
                Log.e("VoiceAssistant", "Failed to fetch token", e)
                throw e
            }
        }
    }

    suspend fun switchAudioMode(mode: AudioMode) {
        val startTime = System.currentTimeMillis()
        Log.d("VoiceAssistant", "========== 开始切换模式：$mode ==========")

        if (currentMode == mode) {
            Log.d("VoiceAssistant", "已经是目标模式，跳过切换")
            return
        }

        try {
            Log.d("VoiceAssistant", "[1] 开始获取 token...")
            val modeStr = if (mode == AudioMode.MEDIA_HIFI) "hardware" else "software"

            val tokenStart = System.currentTimeMillis()
            val tokenResponse = fetchToken(mode = modeStr)
            Log.d("VoiceAssistant", "[1] 获取 token 完成，耗时：${System.currentTimeMillis() - tokenStart}ms")
            Log.d("VoiceAssistant", "    identity: ${tokenResponse.identity}")

            Log.d("VoiceAssistant", "[2] 更新全局 token...")
            // Note: Assuming this is a helper function in your project
            // io.livekit.android.example.voiceassistant.updateToken(tokenResponse.token)

            Log.d("VoiceAssistant", "[3] 更新连接信息...")
            connectionUrl = tokenResponse.url
            connectionToken = tokenResponse.token

            Log.d("VoiceAssistant", "[4] 更新 TokenSource...")
            tokenSource = io.livekit.android.token.TokenSource.fromLiteral(connectionUrl, connectionToken)

            Log.d("VoiceAssistant", "[5] 应用音频状态...")
            val audioStart = System.currentTimeMillis()
            applyAudioState(mode)
            Log.d("VoiceAssistant", "[5] 音频状态完成，耗时：${System.currentTimeMillis() - audioStart}ms")

            Log.d("VoiceAssistant", "[6] 更新当前模式...")
            currentMode = mode

            Log.d("VoiceAssistant", "[7] 销毁旧 room...")
            val roomStart = System.currentTimeMillis()
            room.disconnect()
            Log.d("VoiceAssistant", "    disconnect 耗时：${System.currentTimeMillis() - roomStart}ms")

            room.release()
            Log.d("VoiceAssistant", "[7] 创建新 room...")
            room = createRoomInstance(mode)
            Log.d("VoiceAssistant", "[7] room 完成，耗时：${System.currentTimeMillis() - roomStart}ms")

            Log.d("VoiceAssistant", "========== 切换成功！总耗时：${System.currentTimeMillis() - startTime}ms ==========")

        } catch (e: Exception) {
            Log.e("VoiceAssistant", "========== 切换失败，使用 fallback ==========")
            Log.e("VoiceAssistant", "错误：${e.message}")
            currentMode = mode
            applyAudioState(mode)
            viewModelScope.launch {
                delay(800)
                Log.d("VoiceAssistant", "fallback 完成，总耗时：${System.currentTimeMillis() - startTime}ms")
            }
        }
    }

    init {
        Log.d("VoiceAssistant", "===== ViewModel 初始化 =====")
        // 【已修复】从 routeArgs 中获取 hardcodedUrl 和 hardcodedToken
        connectionUrl = routeArgs.hardcodedUrl
        connectionToken = routeArgs.hardcodedToken

        // 【新增】如果是直接进入电话模式，立刻设置系统 AudioManager 硬件状态
        if (startInCallMode) {
            applyAudioState(AudioMode.CALL_SPEAKER)
        }

        // 【已修复】从 routeArgs 中获取参数来创建 TokenSource
        tokenSource = if (routeArgs.sandboxId.isNotEmpty()) {
            io.livekit.android.token.TokenSource.fromSandboxTokenServer(routeArgs.sandboxId)
        } else {
            io.livekit.android.token.TokenSource.fromLiteral(routeArgs.hardcodedUrl, routeArgs.hardcodedToken)
        }

        // 【修改】只有不以通话模式启动（媒体模式启动），才走原代码的延迟 1.5 秒切换逻辑
        if (!startInCallMode) {
            viewModelScope.launch {
                Log.d("VoiceAssistant", "等待 1500ms 后开始自动切换...")
                delay(1500)
                Log.d("VoiceAssistant", "开始自动切换到 CALL_SPEAKER...")
                switchAudioMode(AudioMode.CALL_SPEAKER)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d("VoiceAssistant", "ViewModel 清理中...")
        room.disconnect()
        room.release()
        Log.d("VoiceAssistant", "ViewModel 已清理")
    }
}import androidx.compose.ui.graphics.Color
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
import io.livekit.android.example.voiceassistant.viewmodel.AudioMode
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

// 确保这里的路由定义与你项目中的保持一致
@Serializable
data class VoiceAssistantRoute(
    val sandboxId: String,
    val hardcodedUrl: String,
    val hardcodedToken: String,
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
    var requestedAudio by remember { mutableStateOf(true) } // Turn on audio by default.
    var requestedVideo by remember { mutableStateOf(false) }

    requirePermissions(requestedAudio, requestedVideo)

    val canEnableMic by rememberCanEnableMic()
    val canEnableVideo by rememberCanEnableCamera()

    val context = LocalContext.current

    androidx.compose.runtime.key(viewModel.room) {
        val session = rememberSession(
            tokenSource = viewModel.tokenSource,
            options = SessionOptions(
                room = viewModel.room
            )
        )
        SessionScope(session = session) { session ->

            LaunchedEffect(canEnableMic) {
                if (!canEnableMic) {
                    return@LaunchedEffect
                }

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

            // ========================= 【关键修改处】 =========================
            // 这个 LaunchedEffect 现在会同时处理开启和关闭麦克风的逻辑
            LaunchedEffect(canEnableMic, requestedAudio) {
                session.waitUntilConnected()
                val localParticipant = room.localParticipant

                // 同样根据 ViewModel 的当前模式决定音频参数
                val audioOptions = if (viewModel.currentMode == AudioMode.MEDIA_HIFI) {
                    LocalAudioTrackOptions(
                        echoCancellation = true,
                        noiseSuppression = true,
                        autoGainControl = false,
                        highPassFilter = true,
                        typingNoiseDetection = true
                    )
                } else {
                    LocalAudioTrackOptions(
                        echoCancellation = false, // 硬件处理模式
                        noiseSuppression = false,
                        autoGainControl = false,
                        highPassFilter = false,
                        typingNoiseDetection = false
                    )
                }

                // 使用 setMicrophoneEnabled 方法。
                // 当 requestedAudio 为 true 时，它会使用你提供的 audioOptions 来发布音轨。
                // 当 requestedAudio 为 false 时，它会自动取消发布音轨，从而实现静音。
                localParticipant.setMicrophoneEnabled(
                    enabled = canEnableMic && requestedAudio,
                    options = audioOptions,
                    name = "microphone"
                )
            }
            // ======================= 【修改结束】 =========================

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

                var message by rememberSaveable {
                    mutableStateOf("")
                }
                ChatBar(
                    value = message,
                    onValueChange = { message = it },
                    onChatSend = { msg ->
                        coroutineScope.launch {
                            sessionMessages.send(msg)
                        }
                        message = ""
                    },
                    modifier = Modifier.layoutId(LAYOUT_ID_CHAT_BAR)
                )

                AgentVisualization(
                    agent = agent,
                    modifier = Modifier
                        .layoutId(LAYOUT_ID_AGENT)
                        .clip(RoundedCornerShape(8.dp))
                )

                val context = LocalContext.current
                val screenSharePermissionLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                        val resultCode = result.resultCode
                        val data = result.data
                        if (resultCode != Activity.RESULT_OK || data == null) {
                            return@rememberLauncherForActivityResult
                        }
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
                    currentMode = viewModel.currentMode,
                    onAudioModeChange = { mode ->
                        coroutineScope.launch {
                            viewModel.switchAudioMode(mode)
                        }
                    },
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


private const val LAYOUT_ID_AGENT = "agentVisualizer"
private const val LAYOUT_ID_CHAT_LOG = "chatLog"
private const val LAYOUT_ID_CONTROL_BAR = "controlBar"
private const val LAYOUT_ID_CHAT_BAR = "chatBar"
private const val LAYOUT_ID_CAMERA = "camera"
private const val LAYOUT_ID_SCREENSHARE = "screenshare"

private fun getConstraints(chatVisible: Boolean, cameraVisible: Boolean, screenShareVisible: Boolean) = ConstraintSet {
    val (agentVisualizer, chatLog, controlBar, chatBar, camera, screenShare) = createRefsFor(
        LAYOUT_ID_AGENT,
        LAYOUT_ID_CHAT_LOG,
        LAYOUT_ID_CONTROL_BAR,
        LAYOUT_ID_CHAT_BAR,
        LAYOUT_ID_CAMERA,
        LAYOUT_ID_SCREENSHARE,
    )
    val chatTopGuideline = createGuidelineFromTop(0.2f)

    constrain(chatLog) {
        top.linkTo(chatTopGuideline)
        bottom.linkTo(chatBar.top)
        start.linkTo(parent.start)
        end.linkTo(parent.end)
        width = Dimension.fillToConstraints
        height = Dimension.fillToConstraints
    }

    constrain(chatBar) {
        bottom.linkTo(controlBar.top, 16.dp)
        start.linkTo(parent.start, 16.dp)
        end.linkTo(parent.end, 16.dp)
        width = Dimension.fillToConstraints
        height = Dimension.wrapContent
    }

    constrain(controlBar) {
        bottom.linkTo(parent.bottom, 10.dp)
        start.linkTo(parent.start, 16.dp)
        end.linkTo(parent.end, 16.dp)

        width = Dimension.fillToConstraints
        height = Dimension.value(60.dp)
    }

    if (chatVisible) {
        val chain = createHorizontalChain(agentVisualizer, screenShare, camera, chainStyle = ChainStyle.Spread)

        constrain(chain) {
            start.linkTo(parent.start)
            end.linkTo(parent.end)
        }

        fun ConstrainScope.itemConstraints(visible: Boolean = true) {
            top.linkTo(parent.top)
            bottom.linkTo(chatTopGuideline)
            width = Dimension.percent(0.3f)
            height = Dimension.fillToConstraints
            visibility = if (visible) Visibility.Visible else Visibility.Gone
        }
        constrain(agentVisualizer) {
            itemConstraints()
        }
        constrain(camera) {
            itemConstraints(cameraVisible)
        }
        constrain(screenShare) {
            itemConstraints(screenShareVisible)
        }
    } else {
        constrain(agentVisualizer) {
            top.linkTo(parent.top)
            bottom.linkTo(parent.bottom)
            start.linkTo(parent.start)
            end.linkTo(parent.end)
            height = Dimension.fillToConstraints
            width = Dimension.fillToConstraints
        }
        constrain(camera) {
            end.linkTo(parent.end, 16.dp)
            bottom.linkTo(controlBar.top, 16.dp)
            width = Dimension.percent(0.25f)
            height = Dimension.percent(0.2f)
            visibility = if (cameraVisible) Visibility.Visible else Visibility.Gone
        }
        constrain(screenShare) {
            if (cameraVisible) {
                end.linkTo(camera.start, 16.dp)
            } else {
                end.linkTo(parent.end, 16.dp)
            }
            bottom.linkTo(controlBar.top, 16.dp)
            width = Dimension.percent(0.25f)
            height = Dimension.percent(0.2f)
            visibility = if (screenShareVisible) Visibility.Visible else Visibility.Gone
        }
    }
}
