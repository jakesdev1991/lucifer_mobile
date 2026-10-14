package com.lucifer.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lucifer.mobile.ui.theme.LuciferTheme
import com.lucifer.mobile.ui.navigation.LuciferNavHost
import com.lucifer.mobile.omni_bridge.OmniBridge
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val omniBridge: OmniBridge by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            LuciferTheme {
                val navController = rememberNavController()
                LuciferNavHost(navController, omniBridge = omniBridge)
            }
        }

        // Initialize OmniBridge in background
        lifecycleScope.launch {
            omniBridge.initialize()
        }
    }

    override fun onStart() {
        super.onStart()
        ProcessLifecycleOwner.get().lifecycle.addObserver(omniBridge)
    }

    override fun onStop() {
        super.onStop()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(omniBridge)
    }
}