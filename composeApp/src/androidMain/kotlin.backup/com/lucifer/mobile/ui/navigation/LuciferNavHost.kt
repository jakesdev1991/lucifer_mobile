package com.lucifer.mobile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHost
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lucifer.mobile.ui.chat.ChatScreen
import com.lucifer.mobile.ui.skills.SkillsScreen
import com.lucifer.mobile.ui.terminal.TerminalScreen
import com.lucifer.mobile.ui.wallet.WalletScreen
import com.lucifer.mobile.ui.browser.BrowserScreen
import com.lucifer.mobile.ui.settings.SettingsScreen
import com.lucifer.mobile.omni_bridge.OmniBridge

@Composable
fun LuciferNavHost(
    navController: NavHostController,
    omniBridge: OmniBridge
) {
    NavHost(navController, startDestination = "chat") {
        composable("chat") {
            ChatScreen(
                onNavigateToTerminal = { navController.navigate("terminal") },
                onNavigateToWallet = { navController.navigate("wallet") },
                onNavigateToBrowser = { navController.navigate("browser") },
                onNavigateToSkills = { navController.navigate("skills") },
                onNavigateToSettings = { navController.navigate("settings") }
            )
        }
        composable("terminal") {
            TerminalScreen(
                onNavigateBack = { navController.navigateUp() }
            )
        }
        composable("wallet") {
            WalletScreen(
                onNavigateBack = { navController.navigateUp() }
            )
        }
        composable("browser") {
            BrowserScreen(
                onNavigateBack = { navController.navigateUp() }
            )
        }
        composable("skills") {
            SkillsScreen(
                onNavigateBack = { navController.navigateUp() }
            )
        }
        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.navigateUp() }
            )
        }
    }
}

enum class LuciferDestination(
    val route: String,
    val label: String,
    val icon: String,
    val horseman: Horseman
) {
    CHAT("chat", "Chat", "ic_chat", Horseman.CONQUEST),
    TERMINAL("terminal", "Terminal", "ic_terminal", Horseman.WAR),
    WALLET("wallet", "Wallet", "ic_wallet", Horseman.FAMINE),
    BROWSER("browser", "Browser", "ic_browser", Horseman.DEATH),
    SKILLS("skills", "Skills", "ic_skills", Horseman.CONQUEST),
    SETTINGS("settings", "Settings", "ic_settings", Horseman.DEATH)
}

enum class Horseman {
    CONQUEST,  // Planning, synthesis, architecture
    WAR,       // Red teaming, threat modeling
    FAMINE,    // Low-budget, latency-constrained
    DEATH      // Invalid assumption detection, termination
}