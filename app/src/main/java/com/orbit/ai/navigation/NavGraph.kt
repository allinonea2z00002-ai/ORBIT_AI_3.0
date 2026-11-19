package com.orbit.ai.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.orbit.ai.ui.screens.*
import com.orbit.ai.viewmodel.ChatViewModel
import com.orbit.ai.voice.WakeBus

@Composable
fun OrbitNavHost(chat: ChatViewModel, wake: WakeBus) {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }

    // Commands such as "organize my downloads" ask the UI to open a screen.
    LaunchedEffect(Unit) { chat.navEvents.collect { route -> nav.navigate(route) } }

    NavHost(
        navController = nav,
        startDestination = "home",
        enterTransition = { fadeIn(tween(280)) + slideInHorizontally(tween(280)) { it / 12 } },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(280)) },
        popExitTransition = { fadeOut(tween(200)) + slideOutHorizontally(tween(280)) { it / 12 } }
    ) {
        composable("home") { HomeScreen(chat, wake) { route -> nav.navigate(route) } }
        composable("chat") { ChatScreen(chat, back) }
        composable(
            "files?organize={organize}",
            arguments = listOf(navArgument("organize") { type = NavType.BoolType; defaultValue = false })
        ) { entry -> FilesScreen(entry.arguments?.getBoolean("organize") ?: false, back) }
        composable("intel") { IntelScreen(back) }
        composable("automation") { AutomationScreen(back) }
        composable("productivity") { ProductivityScreen(back) }
        composable("memory") { MemoryScreen(back) }
        composable("settings") { SettingsScreen(back, openMemory = { nav.navigate("memory") }) }
    }
}
