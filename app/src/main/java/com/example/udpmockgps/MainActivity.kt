package com.example.udpmockgps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.udpmockgps.ui.MainScreen
import com.example.udpmockgps.ui.UdpMockGpsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            UdpMockGpsTheme {
                MainScreen()
            }
        }
    }
}
