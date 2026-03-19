package io.livekit.android.example.voiceassistant

import android.os.Build // 🌟 新增导入 Build 以判断 Android 版本
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState

/**
 * Handles requesting the required permissions if needed.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun requirePermissions(microphone: Boolean, camera: Boolean): MultiplePermissionsState {
    val permissionsState = rememberMultiplePermissionsState(
        listOfNotNull(
            if (microphone) android.Manifest.permission.RECORD_AUDIO else null,
            if (camera) android.Manifest.permission.CAMERA else null,
            // 🌟 【核心修改】如果需要麦克风，且系统是 Android 12 (API 31) 及以上，同时申请蓝牙连接权限
            if (microphone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.Manifest.permission.BLUETOOTH_CONNECT
            } else null
        )
    )

    DisposableEffect(camera, microphone) {
        if (!permissionsState.allPermissionsGranted) {
            permissionsState.launchMultiplePermissionRequest()
        }
        onDispose { /* do nothing */ }
    }
    return permissionsState
}

/**
 * @return true if the camera permission is granted.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun rememberCanEnableCamera(): State<Boolean> {
    val permissionState = rememberPermissionState(
        android.Manifest.permission.CAMERA
    )
    return remember {
        derivedStateOf {
            permissionState.status.isGranted
        }
    }
}

/**
 * @return true if both enabled is true and the mic permission is granted.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun rememberCanEnableMic(): State<Boolean> {
    val micPermissionState = rememberPermissionState(
        android.Manifest.permission.RECORD_AUDIO
    )
    return remember(micPermissionState) {
        derivedStateOf {
            micPermissionState.status.isGranted
        }
    }
}