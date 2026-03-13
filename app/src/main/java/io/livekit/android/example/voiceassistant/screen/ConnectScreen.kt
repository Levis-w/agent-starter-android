package io.livekit.android.example.voiceassistant.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.livekit.android.example.voiceassistant.R
import io.livekit.android.example.voiceassistant.hardcodedToken
import io.livekit.android.example.voiceassistant.hardcodedUrl
import io.livekit.android.example.voiceassistant.sandboxID
import io.livekit.android.example.voiceassistant.ui.theme.Blue500
import kotlinx.serialization.Serializable

@Serializable
object ConnectRoute

@Composable
fun ConnectScreen(
    navigateToVoiceAssistant: (VoiceAssistantRoute) -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painter = painterResource(R.drawable.connect_icon), contentDescription = "Connect icon")

            Spacer(Modifier.size(16.dp))
            Text(
                text = buildAnnotatedString {
                    append("Start a call to chat with your voice agent. Need help getting set up?\nCheck out the ")
                    withLink(
                        LinkAnnotation.Url(
                            "https://docs.livekit.io/agents/start/voice-ai/",
                            TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline))
                        )
                    ) {
                        append("Voice AI quickstart.")
                    }
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(0.8f)
            )

            var hasError by rememberSaveable { mutableStateOf(false) }
            var isConnecting by remember { mutableStateOf(false) }

            Spacer(Modifier.size(8.dp))

            AnimatedVisibility(hasError) {
                Text(
                    text = "Error connecting. Make sure your agent is properly configured and try again.",
                    color = Color.Red,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(0.8f)
                )
            }

            Spacer(Modifier.size(24.dp))

            // --- 按钮 1：静态/硬编码模式 ---
            Button(
                colors = ButtonDefaults.buttonColors(
                    containerColor = Blue500,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(20),
                onClick = {
                    val route = VoiceAssistantRoute(
                        sandboxId = sandboxID,
                        hardcodedUrl = hardcodedUrl,
                        hardcodedToken = hardcodedToken,
                        isDynamic = false // 使用硬编码逻辑
                    )
                    navigateToVoiceAssistant(route)
                }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "START CALL (STATIC)",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp,
                        )
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // --- 按钮 2：动态模式 (8080端口) ---
            Button(
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50), // 绿色区分
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(20),
                onClick = {
                    // 动态模式进入后，ViewModel 里的 switchAudioMode 会去请求后端
                    val route = VoiceAssistantRoute(
                        sandboxId = "", 
                        hardcodedUrl = "", 
                        hardcodedToken = "",
                        isDynamic = true // 开启动态获取逻辑
                    )
                    navigateToVoiceAssistant(route)
                }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedVisibility(isConnecting) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                trackColor = Color.Gray,
                            )
                            Spacer(Modifier.size(8.dp))
                        }
                    }
                    Text(
                        text = if (isConnecting) "CONNECTING" else "DYNAMIC START (8080)",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp,
                        )
                    )
                }
            }
        }
    }
}
