package com.kevmo314.kineticstreamer

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import com.kevmo314.kineticstreamer.ui.theme.KineticStreamerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Drives the real settings -> add output -> edit output -> delete navigation for every output
 * type. Editing the rtmp/rtsp/disk outputs used to crash because those routes didn't exist.
 */
@OptIn(ExperimentalMaterial3Api::class)
class OutputNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val file = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "${UUID.randomUUID()}.preferences_pb",
    )
    private val settings = Settings(PreferenceDataStoreFactory.create(scope = scope) { file })

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    private fun launchSettings() {
        composeRule.setContent {
            val navController = rememberNavController()
            KineticStreamerTheme {
                NavHost(navController, startDestination = "settings") {
                    composable("settings") {
                        SettingsScreen(
                            settings = settings,
                            streamingService = null,
                            navigateBack = { navController.popBackStack() },
                            navigateTo = { navController.navigate(it) },
                        )
                    }
                    composable("settings/output") {
                        AddOutputScreen(
                            navigateTo = { navController.navigate(it) },
                            navigateBack = { navController.popBackStack() },
                        )
                    }
                    outputRoutes(settings, scope) { navController.popBackStack("settings", false) }
                }
            }
        }
    }

    private fun fill(label: String, value: String) {
        composeRule.onNode(hasSetTextAction() and hasText(label)).performTextInput(value)
    }

    private fun click(text: String) {
        composeRule.onNodeWithText(text).performScrollTo().performClick()
    }

    private fun waitForUrls(vararg urls: String) {
        composeRule.waitUntil(5_000) {
            runBlocking { settings.outputConfigurations.first().map { it.url } } == urls.toList() &&
                urls.all { composeRule.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    private fun waitForNoOutputs() {
        composeRule.waitUntil(5_000) { runBlocking { settings.outputConfigurations.first() }.isEmpty() }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Add Output").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun addEditDelete(type: OutputType, formTitle: String, fields: Map<String, String>, expectedUrl: String) {
        launchSettings()

        click("Add Output")
        click(type.title)
        fields.forEach { (label, value) -> fill(label, value) }
        composeRule.onNodeWithContentDescription("Save").performClick()
        waitForUrls(expectedUrl)

        // Edit: open the saved output, check the form round-trips it, and save without changes.
        click(expectedUrl)
        composeRule.onNodeWithText("Edit $formTitle").assertExists()
        composeRule.onNodeWithContentDescription("Save").performClick()
        waitForUrls(expectedUrl)
        assertEquals(listOf(true), runBlocking { settings.outputConfigurations.first().map { it.enabled } })

        // Delete.
        click(expectedUrl)
        composeRule.onNodeWithContentDescription("Delete").performClick()
        waitForNoOutputs()
    }

    @Test
    fun whip() = addEditDelete(
        OutputType.WHIP, "WHIP Output",
        mapOf("WHIP Endpoint URL" to "https://example.com/whip", "Bearer Token (optional)" to "tok;en:1"),
        "whip://https://example.com/whip?token=tok%3Ben%3A1",
    )

    @Test
    fun srt() = addEditDelete(
        OutputType.SRT, "SRT Output",
        mapOf("Host" to "srt.example.com", "Stream ID (optional)" to "a;b"),
        "srt://srt.example.com:9000?streamid=a;b",
    )

    @Test
    fun rist() = addEditDelete(
        OutputType.RIST, "RIST Output",
        mapOf("Host" to "rist.example.com", "CNAME (optional)" to "cn"),
        "rist://rist.example.com:1968?cname=cn",
    )

    @Test
    fun rtmp() = addEditDelete(
        OutputType.RTMP, "RTMP Output",
        mapOf("RTMP URL" to "rtmp://a.example.com/live", "Stream Key" to "k;ey"),
        "rtmp://a.example.com/live/k;ey",
    )

    @Test
    fun rtsp() = addEditDelete(OutputType.RTSP, "RTSP Server", emptyMap(), "rtsp://localhost:8554/stream")

    @Test
    fun disk() = addEditDelete(OutputType.DISK, "Disk Output", emptyMap(), "file://recording")
}
