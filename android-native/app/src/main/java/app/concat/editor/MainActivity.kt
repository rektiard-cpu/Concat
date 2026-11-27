// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.concat.editor.engine.Wakefulness
import app.concat.editor.ui.EngineFailedScreen
import app.concat.editor.ui.editor.EditorScreen
import app.concat.editor.ui.projects.ProjectsScreen
import app.concat.editor.ui.settings.SettingsScreen
import app.concat.editor.ui.theme.ConcatTheme
import kotlinx.coroutines.launch

/** Three screens: the projects, the editor on one of them, and settings. */
class MainActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences

    private val orientationListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_AUTO_LANDSCAPE) applyOrientationPreference()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        applyOrientationPreference()
        prefs.registerOnSharedPreferenceChangeListener(orientationListener)

        // Light bar icons over the dark theme, whatever the phone's own setting.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // For a run driven over adb: --es open <project folder> [--es tool captions|speech]
        val opening = intent.getStringExtra("open")
        val openingTool = intent.getStringExtra("tool")
        val failure = concat.engineFailure
        if (failure != null) {
            setContent { ConcatTheme { EngineFailedScreen(failure) } }
            return
        }
        Smoke.run(concat)
        // The screen stays on while the engine runs a job (#695).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Wakefulness.held.collect { held ->
                    if (held) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }
        setContent {
            ConcatTheme {
                var open by rememberSaveable { mutableStateOf(opening) }
                var tool by rememberSaveable { mutableStateOf(openingTool) }
                // A new editor each time a project opens, even the same one again.
                var session by rememberSaveable { mutableIntStateOf(0) }
                var settings by rememberSaveable { mutableStateOf(false) }
                val path = open
                if (path == null && settings) {
                    SettingsScreen(onBack = { settings = false })
                } else if (path == null) {
                    ProjectsScreen(
                        onOpen = { project, startTool ->
                            session += 1
                            tool = startTool
                            open = project
                        },
                        onSettings = { settings = true },
                    )
                } else {
                    EditorScreen(path, session, onClosed = {
                        open = null
                        tool = null
                    }, startTool = tool)
                }
            }
        }
    }

    override fun onDestroy() {
        if (::prefs.isInitialized) {
            prefs.unregisterOnSharedPreferenceChangeListener(orientationListener)
        }
        super.onDestroy()
    }

    private fun applyOrientationPreference() {
        val autoLandscape = prefs.getBoolean(KEY_AUTO_LANDSCAPE, true)
        requestedOrientation = if (autoLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    companion object {
        const val PREFS_NAME = "concat_editor_prefs"
        const val KEY_AUTO_LANDSCAPE = "auto_landscape"
    }
}
