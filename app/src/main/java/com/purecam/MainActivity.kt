package com.purecam

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.purecam.ui.CameraApp
import com.purecam.ui.CameraViewModel
import com.purecam.ui.PureCamTheme

class MainActivity : ComponentActivity() {
    private val vm: CameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Status bar stays visible (time, battery…) with light icons over the dark camera UI.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            PureCamTheme {
                CameraApp(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    override fun onPause() {
        vm.onPause()
        super.onPause()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        vm.onUserInteraction()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (vm.onKeyDown(keyCode, event.repeatCount)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (vm.consumesKeyUp(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }
}
