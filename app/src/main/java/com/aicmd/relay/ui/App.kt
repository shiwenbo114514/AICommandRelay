package com.aicmd.relay.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.aicmd.relay.ui.chat.ChatScreen
import com.aicmd.relay.ui.settings.SettingsScreen

@Composable
fun App(vm: ChatViewModel) {
    var screen by rememberSaveable { mutableStateOf("chat") }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(vm.toast) {
        vm.toast?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearToast()
        }
    }

    Scaffold(
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Crossfade(targetState = screen, animationSpec = tween(260)) { s ->
                when (s) {
                    "settings" -> SettingsScreen(vm, onBack = { screen = "chat" })
                    else -> ChatScreen(vm, onOpenSettings = { screen = "settings" })
                }
            }
        }
    }
}
