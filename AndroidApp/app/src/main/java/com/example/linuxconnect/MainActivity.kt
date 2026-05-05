package com.example.linuxconnect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.linuxconnect.model.ServerInfo
import com.example.linuxconnect.ui.DiscoveryScreen
import com.example.linuxconnect.ui.RcControlScreen
import com.example.linuxconnect.ui.theme.LinuxConnectTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LinuxConnectTheme {
                var selectedServer by remember { mutableStateOf<ServerInfo?>(null) }

                val current = selectedServer
                if (current == null) {
                    DiscoveryScreen(onServerSelected = { selectedServer = it })
                } else {
                    RcControlScreen(
                        server = current,
                        onBack = { selectedServer = null },
                    )
                }
            }
        }
    }
}
