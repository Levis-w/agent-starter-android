package io.livekit.android.example.voiceassistant.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.livekit.android.annotations.Beta
import io.livekit.android.compose.state.Agent
import io.livekit.android.compose.ui.ScaleType
import io.livekit.android.compose.ui.VideoTrackView
import io.livekit.android.compose.ui.audio.VoiceAssistantBarVisualizer
import io.livekit.android.example.voiceassistant.ui.anim.CircleReveal
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

val revealSpringSpec = spring<Float>(stiffness = Spring.StiffnessVeryLow)
val hideSpringSpec = spring<Float>(stiffness = Spring.StiffnessMedium)

@OptIn(Beta::class)
@Composable
fun AgentVisualization(
    agent: Agent,
    modifier: Modifier = Modifier,
    isConnected: Boolean = true
) {

    val videoTrack = agent.videoTrack
    val audioTrack = agent.audioTrack

    var hasFirstFrameRendered by remember(videoTrack) { mutableStateOf(false) }
    
    // 关键优化：连上房间后1秒内强制显示，确保即便Agent轨道未就绪也能进入律动状态
    var timerRevealed by remember { mutableStateOf(false) }
    LaunchedEffect(isConnected) {
        if (isConnected) {
            delay(1000)
            timerRevealed = true
        } else {
            timerRevealed = false
        }
    }

    // 如果有视频，等第一帧；如果只有音频（语音助手模式），只要音频轨道出现就显示；或者连接后1秒超时强制显示
    val revealed = remember(videoTrack, audioTrack, hasFirstFrameRendered, timerRevealed) {
        timerRevealed || (videoTrack != null && hasFirstFrameRendered) || (videoTrack == null && audioTrack != null)
    }

    Box(modifier = modifier) {
        if (videoTrack != null) {
            VideoTrackView(
                trackReference = videoTrack,
                scaleType = ScaleType.FitInside,
                onFirstFrameRendered = { hasFirstFrameRendered = true },
                modifier = Modifier
                    .fillMaxSize(),
            )
        }
        CircleReveal(
            revealed = revealed,
            content = {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    val density = LocalDensity.current
                    var width by remember { mutableIntStateOf(0) }
                    var height by remember { mutableIntStateOf(0) }

                    val barWidth by remember {
                        derivedStateOf {
                            val widthDp = width / density.density
                            return@derivedStateOf max(1, (widthDp * 0.157f).roundToInt()).dp
                        }
                    }
                    val barMinHeightPercent by remember {
                        derivedStateOf {
                            val widthPx = barWidth.value * density.density
                            return@derivedStateOf max(0f, (widthPx / height))
                        }
                    }
                    
                    // 关键修复：使用 key(audioTrack) 确保在重连后，可视化器能正确绑定到新音轨
                    androidx.compose.runtime.key(agent.audioTrack) {
                        VoiceAssistantBarVisualizer(
                            agentState = agent.agentState,
                            audioTrackRef = agent.audioTrack,
                            barCount = 5,
                            minHeight = barMinHeightPercent,
                            barWidth = barWidth,
                            brush = SolidColor(MaterialTheme.colorScheme.onBackground),
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            modifier = Modifier
                                .fillMaxWidth(0.75f)
                                .fillMaxHeight(0.22f)
                                .onSizeChanged { size ->
                                    width = size.width
                                    height = size.height
                                }
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            animationSpec = if (revealed) revealSpringSpec else hideSpringSpec,
        )
    }
}
