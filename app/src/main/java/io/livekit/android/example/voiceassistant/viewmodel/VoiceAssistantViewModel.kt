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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    var currentMode by mutableStateOf(AudioMode.MEDIA_HIFI)
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(500, TimeUnit.MILLISECONDS)
        .readTimeout(500, TimeUnit.MILLISECONDS)
        .writeTimeout(500, TimeUnit.MILLISECONDS)
        .build()
    
    private val apiBaseUrl = "http://192.168.6.233:8080"
    
    // 显式引用 SDK 的 TokenSource 避免冲突
    lateinit var tokenSource: io.livekit.android.token.TokenSource
    private var connectionUrl: String = ""
    private var connectionToken: String = ""

    var room: Room by mutableStateOf(createRoomInstance(AudioMode.MEDIA_HIFI))
        private set

    private fun createRoomInstance(mode: AudioMode): Room {
        val audioOptions = when (mode) {
            AudioMode.MEDIA_HIFI -> AudioOptions(
                audioOutputType = AudioType.MediaAudioType(),
                audioHandler = NoAudioHandler(),
                javaAudioDeviceModuleCustomizer = { builder ->
                    builder
                        .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
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

    private suspend fun fetchToken(mode: String): TokenResponse {
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
        
        // ========== 特殊处理 CALL_SPEAKER 模式 ==========
        if (mode == AudioMode.CALL_SPEAKER) {
            val isFromEarpiece = currentMode == AudioMode.CALL_EARPIECE
            
            if (isFromEarpiece) {
                // 从听筒切回：先重建 MEDIA_HIFI 房间
                Log.d("VoiceAssistant", "从听筒切回，先重建 MEDIA_HIFI 房间...")
                room.disconnect()
                room.release()
                room = createRoomInstance(AudioMode.MEDIA_HIFI)
                
                // 等待 1.5 秒再 fallback
                delay(1500)
            }
            
            // 强制 fallback（不获取 token）
            Log.d("VoiceAssistant", "CALL_SPEAKER 模式，强制 fallback...")
            currentMode = mode
            applyAudioState(mode)
            
            Log.d("VoiceAssistant", "========== 切换成功！总耗时：${System.currentTimeMillis() - startTime}ms ==========")
            return
        }
        // ========== CALL_SPEAKER 处理结束 ==========
        
        // 其他模式走正常流程（获取 token）
        try {
            Log.d("VoiceAssistant", "[1] 开始获取 token...")
            val modeStr = if (mode == AudioMode.MEDIA_HIFI) "hardware" else "software"
            val tokenStart = System.currentTimeMillis()
            val tokenResponse = fetchToken(mode = modeStr)
            Log.d("VoiceAssistant", "[1] 获取 token 完成，耗时：${System.currentTimeMillis() - tokenStart}ms")
            Log.d("VoiceAssistant", "  identity: ${tokenResponse.identity}")
            
            Log.d("VoiceAssistant", "[2] 更新全局 token...")
            io.livekit.android.example.voiceassistant.updateToken(tokenResponse.token)
            
            Log.d("VoiceAssistant", "[3] 更新连接信息...")
            connectionUrl = tokenResponse.url
            connectionToken = tokenResponse.token
            
            Log.d("VoiceAssistant", "[4] 更新 TokenSource...")
            tokenSource = io.livekit.android.token.TokenSource.fromLiteral(connectionUrl, connectionToken)
            
            Log.d("VoiceAssistant", "[5] 创建新 room...")
            val newRoom = createRoomInstance(mode)
            
            Log.d("VoiceAssistant", "[6] 销毁旧 room...")
            val roomStart = System.currentTimeMillis()
            room.disconnect()
            Log.d("VoiceAssistant", "  disconnect 耗时：${System.currentTimeMillis() - roomStart}ms")
            room.release()
            
            Log.d("VoiceAssistant", "[7] 更新为新 room...")
            room = newRoom
            
            Log.d("VoiceAssistant", "[8] 延迟 1.5 秒...")
            delay(1500)
            
            Log.d("VoiceAssistant", "[9] 应用音频状态...")
            val audioStart = System.currentTimeMillis()
            applyAudioState(mode)
            Log.d("VoiceAssistant", "[9] 音频状态完成，耗时：${System.currentTimeMillis() - audioStart}ms")
            
            Log.d("VoiceAssistant", "[10] 更新当前模式...")
            currentMode = mode
            
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
        val (sandboxId, url, token) = savedStateHandle.toRoute<VoiceAssistantRoute>()
        connectionUrl = url
        connectionToken = token
        
        // 初始化 SDK 格式的 TokenSource
        tokenSource = if (sandboxId.isNotEmpty()) {
            io.livekit.android.token.TokenSource.fromSandboxTokenServer(sandboxId)
        } else {
            io.livekit.android.token.TokenSource.fromLiteral(url, token)
        }
        
        viewModelScope.launch {
            Log.d("VoiceAssistant", "等待 1.5 秒后开始自动切换...")
            delay(1500)
            Log.d("VoiceAssistant", "开始自动切换到 CALL_SPEAKER...")
            switchAudioMode(AudioMode.CALL_SPEAKER)
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        Log.d("VoiceAssistant", "ViewModel 清理中...")
        room.disconnect()
        room.release()
        Log.d("VoiceAssistant", "ViewModel 已清理")
    }
}
