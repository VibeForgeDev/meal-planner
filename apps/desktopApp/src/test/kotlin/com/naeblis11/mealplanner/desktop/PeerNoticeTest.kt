package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.desktop.peers.PeerMessages
import com.naeblis11.mealplanner.settings.CREATE_TOKEN
import com.naeblis11.mealplanner.settings.PeerControls
import com.naeblis11.mealplanner.settings.PeerNotice
import com.naeblis11.mealplanner.settings.SERVER_PANEL
import com.naeblis11.mealplanner.settings.ServerState
import com.naeblis11.mealplanner.settings.ServerStatus
import com.naeblis11.mealplanner.ui.MealPlannerApp
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Settings' look-out without a network: the notice it is told, and how often Settings asked it to look. */
class FakePeerControls(initial: PeerNotice?) : PeerControls {
    val flow = MutableStateFlow(initial)
    override val notice: StateFlow<PeerNotice?> = flow
    val browses = AtomicInteger()

    override fun browseNow() {
        browses.incrementAndGet()
    }
}

/** P6-R6, P6-R8: the notice atop Settings only (not above every screen), Alexa's section while yielding, and a look each time Settings opens. */
class PeerNoticeTest {
    // Outermost: the app and its database close only after the compose rule has disposed the composition.
    @get:Rule(order = 0)
    val closing = CloseAfterCompose({ tearDown() })

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val dir: File = Files.createTempDirectory("mp-peer-notice").toFile()
    private val app = DesktopApp(dir, settingsFactory = { MapSettings() })

    private fun tearDown() {
        app.close()
        dir.deleteRecursively()
    }

    @Test
    fun theNoticeIsOnlyAtTheTopOfSettings() {
        val text = PeerMessages.yielding("DEN")
        val peers = FakePeerControls(PeerNotice(text, PeerMessages.yieldAlexa("DEN")))
        compose.showAt(1000.dp) { MealPlannerApp(app.container, server = FakeControls(ServerStatus(ServerState.LISTENING, 5000)), peers = peers) }
        compose.waitForText("New recipe")
        compose.onAllNodesWithText(text).assertCountEquals(0)
        compose.tab("Settings").click()
        compose.waitForText(SERVER_PANEL)
        compose.onAllNodesWithText(text).assertCountEquals(1)
        compose.onNodeWithText(PeerMessages.yieldAlexa("DEN")).assertExists()
        compose.onNodeWithText(CREATE_TOKEN).assertIsNotEnabled()
    }

    @Test
    fun noNoticeUntilAnotherPcIsSeen() {
        val peers = FakePeerControls(null)
        compose.showAt(1000.dp) { MealPlannerApp(app.container, server = FakeControls(ServerStatus(ServerState.LISTENING, 5000)), peers = peers) }
        compose.waitForText("New recipe")
        compose.tab("Settings").click()
        compose.waitForText(SERVER_PANEL)
        compose.onAllNodesWithText(PeerMessages.keeping("DEN")).assertCountEquals(0)
        peers.flow.value = PeerNotice(PeerMessages.keeping("DEN"))
        compose.waitForText(PeerMessages.keeping("DEN"))
    }

    @Test
    fun openingSettingsLooksForOtherPcsAgain() {
        val peers = FakePeerControls(null)
        compose.showAt(1000.dp) { MealPlannerApp(app.container, peers = peers) }
        compose.waitForText("New recipe")
        compose.waitForIdle()
        check(peers.browses.get() == 0)
        compose.tab("Settings").click()
        compose.waitUntil(5_000) { peers.browses.get() >= 1 }
        // Once per opening, not once per recomposition: settled, it is still one.
        compose.waitForIdle()
        assertEquals(1, peers.browses.get())
        compose.tab("Recipes").click()
        compose.waitForText("New recipe")
        compose.tab("Settings").click()
        compose.waitUntil(5_000) { peers.browses.get() >= 2 }
        compose.waitForIdle()
        assertEquals(2, peers.browses.get())
    }
}
