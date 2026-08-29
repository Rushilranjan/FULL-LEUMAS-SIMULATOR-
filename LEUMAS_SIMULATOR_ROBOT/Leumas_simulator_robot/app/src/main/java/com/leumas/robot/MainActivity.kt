package com.leumas.robot

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leumas.robot.ble.BlePermissions
import com.leumas.robot.ui.MainScreen
import com.leumas.robot.ui.RobotViewModel
import com.leumas.robot.ui.theme.LeumasColors
import com.leumas.robot.ui.theme.LeumasRobotTheme

class MainActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* The UI reflects the outcome; scanning simply stays unavailable if denied. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // An unattended demo must never sleep mid-sequence.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // The 3-D scene renders on a media-overlay surface behind the Compose content. Clear the
        // window's own (opaque) backdrop so that surface is visible through the transparent Compose
        // overlay. (Compositing here is flagged in the plan as needing on-device confirmation.)
        window.setBackgroundDrawableResource(android.R.color.transparent)

        // Draw edge to edge and hide the system bars. The screen edges are load-bearing here:
        // calibration points sit on them and the arm reaches past them, so a system bar cropping
        // the arena would break the illusion.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        if (!BlePermissions.allGranted(this)) {
            requestPermissions.launch(BlePermissions.REQUIRED)
        }

        setContent {
            LeumasRobotTheme {
                val vm: RobotViewModel = viewModel()
                // No opaque background here: the 3-D scene ([RobotGlView]) is a media-overlay
                // surface sitting behind the Compose content, so painting a solid colour over the
                // whole window would hide it. The scene supplies its own dark clear colour.
                Box(Modifier.fillMaxSize()) {
                    MainScreen(vm)
                }
            }
        }
    }
}
