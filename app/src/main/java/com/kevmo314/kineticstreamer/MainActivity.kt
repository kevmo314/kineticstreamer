package com.kevmo314.kineticstreamer

import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.datastore.preferences.preferencesDataStore
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.kevmo314.kineticstreamer.ui.theme.KineticStreamerTheme
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    companion object {
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
    }

    private val Context.dataStore by preferencesDataStore(name = "kineticstreamer")

    @ExperimentalMaterial3Api
    @ExperimentalPermissionsApi
    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        super.onCreate(savedInstanceState)

        val settings = Settings(dataStore)

        setContent {
            val navController = rememberNavController()

            KineticStreamerTheme {
                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            settings = settings,
                            navigateToSettings = {
                            navController.navigate("settings")
                        })
                    }
                    composable("settings") {
                        SettingsScreen(
                            settings = settings,
                            navigateBack = {
                                navController.popBackStack()
                            },
                            navigateTo = {
                                navController.navigate(it)
                            })
                    }
                    composable("settings/recording") {
                        RecordingSettingsScreen(settings = settings, navigateBack = {
                            navController.popBackStack()
                        })
                    }
                    composable("settings/output/add") {
                        AddOutputScreen(
                            navigateBack = { navController.popBackStack() },
                            navigateTo = { navController.navigate(it) }
                        )
                    }
                    composable("settings/output/whip") {
                        AddWhipOutputScreen(
                            onSave = { config ->
                                kotlinx.coroutines.runBlocking {
                                    val current = settings.outputConfigurations.first()
                                    settings.setOutputConfigurations(current + config)
                                }
                            },
                            navigateBack = {
                                navController.popBackStack("settings", inclusive = false)
                            }
                        )
                    }
                    composable("settings/output/srt") {
                        AddSrtOutputScreen(
                            onSave = { config ->
                                kotlinx.coroutines.runBlocking {
                                    val current = settings.outputConfigurations.first()
                                    settings.setOutputConfigurations(current + config)
                                }
                            },
                            navigateBack = {
                                navController.popBackStack("settings", inclusive = false)
                            }
                        )
                    }
                    composable("settings/output/rtmp") {
                        AddRtmpOutputScreen(
                            onSave = { config ->
                                kotlinx.coroutines.runBlocking {
                                    val current = settings.outputConfigurations.first()
                                    settings.setOutputConfigurations(current + config)
                                }
                            },
                            navigateBack = {
                                navController.popBackStack("settings", inclusive = false)
                            }
                        )
                    }
                    composable("settings/output/rtsp") {
                        AddRtspOutputScreen(
                            onSave = { config ->
                                kotlinx.coroutines.runBlocking {
                                    val current = settings.outputConfigurations.first()
                                    settings.setOutputConfigurations(current + config)
                                }
                            },
                            navigateBack = {
                                navController.popBackStack("settings", inclusive = false)
                            }
                        )
                    }
                    composable("settings/output/disk") {
                        AddDiskOutputScreen(
                            onSave = { config ->
                                kotlinx.coroutines.runBlocking {
                                    val current = settings.outputConfigurations.first()
                                    settings.setOutputConfigurations(current + config)
                                }
                            },
                            navigateBack = {
                                navController.popBackStack("settings", inclusive = false)
                            }
                        )
                    }
                }
            }
        }
    }
}
