package com.taskpilot.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.taskpilot.app.data.StepType
import com.taskpilot.app.ui.screens.EditorScreen
import com.taskpilot.app.ui.screens.HomeScreen
import com.taskpilot.app.ui.screens.StepEditorScreen

sealed interface Screen {
    data object Home : Screen
    data class Editor(val wfId: String) : Screen
    data class StepEdit(val wfId: String, val stepId: String?, val newType: StepType?, val insertAt: Int) : Screen
}

private fun Screen.encode(): String = when (this) {
    Screen.Home -> "H"
    is Screen.Editor -> "E|$wfId"
    is Screen.StepEdit -> "S|$wfId|${stepId ?: ""}|${newType?.name ?: ""}|$insertAt"
}

private fun decode(s: String): Screen {
    val p = s.split('|')
    return when (p[0]) {
        "E" -> Screen.Editor(p[1])
        "S" -> Screen.StepEdit(
            p[1],
            p[2].ifBlank { null },
            p[3].ifBlank { null }?.let { n -> StepType.entries.firstOrNull { it.name == n } },
            p[4].toIntOrNull() ?: -1,
        )
        else -> Screen.Home
    }
}

class Nav(private val stack: SnapshotStateList<Screen>) {
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun replace(s: Screen) { if (stack.size > 1) stack.removeAt(stack.lastIndex); stack.add(s) }
}

val LocalSnack = staticCompositionLocalOf { SnackbarHostState() }

@Composable
fun AppRoot() {
    val stack = rememberSaveable(
        saver = listSaver<SnapshotStateList<Screen>, String>(
            save = { list -> list.map { it.encode() } },
            restore = { saved -> mutableStateListOf<Screen>().apply { addAll(saved.map { decode(it) }) } },
        ),
    ) { mutableStateListOf<Screen>(Screen.Home) }
    val nav = remember(stack) { Nav(stack) }
    val snack = remember { SnackbarHostState() }

    BackHandler(enabled = stack.size > 1) { nav.pop() }

    CompositionLocalProvider(LocalSnack provides snack) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = stack.size to stack.last(),
                    transitionSpec = {
                        if (targetState.first >= initialState.first) {
                            (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut())
                        } else {
                            (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
                        }
                    },
                    label = "nav",
                ) { (_, screen) ->
                    when (screen) {
                        Screen.Home -> HomeScreen(nav)
                        is Screen.Editor -> EditorScreen(screen.wfId, nav)
                        is Screen.StepEdit -> StepEditorScreen(screen, nav)
                    }
                }
                SnackbarHost(
                    snack,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 80.dp),
                )
            }
        }
    }
}
