package com.example.samsung_remote_test

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.samsung_remote_test.ui.SamsungRemoteApp
import com.example.samsung_remote_test.ui.theme.Samsung_remote_testTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Samsung_remote_testTheme {
                SamsungRemoteApp()
            }
        }
    }
}