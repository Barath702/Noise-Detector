package com.example.noisedetector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.noisedetector.notify.AppNotifications
import com.example.noisedetector.ui.cyber.CyberFootstepRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppNotifications.ensureChannels(this)
        setContent {
            CyberFootstepRoot(this)
        }
    }
}
