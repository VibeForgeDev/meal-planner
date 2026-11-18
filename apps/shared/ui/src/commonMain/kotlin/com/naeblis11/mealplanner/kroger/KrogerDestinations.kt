package com.naeblis11.mealplanner.kroger

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.naeblis11.mealplanner.app.AppContainer
import com.naeblis11.mealplanner.ui.Routes
import com.naeblis11.mealplanner.ui.popIfCurrent

/** The device's Kroger (plan 2026-11-17); null where the app has none (tests that leave it out). */
val LocalKroger: ProvidableCompositionLocal<KrogerControls?> = staticCompositionLocalOf { null }

/**
 * Send to Kroger and Remembered products, always in MealPlannerApp's NavHost (its graph is built once); each reads
 * [LocalKroger] as it opens, which the phone provides only once it has been made off the main thread.
 */
fun NavGraphBuilder.krogerDestinations(nav: NavController, container: AppContainer) {
    composable(Routes.KROGER_SEND) { entry ->
        val kroger = LocalKroger.current ?: return@composable
        val vm: KrogerSendViewModel = viewModel { KrogerSendViewModel(kroger, container.shopping, container.krogerMatches) }
        val state by vm.state.collectAsStateWithLifecycle()
        val current by nav.currentBackStackEntryFlow.collectAsStateWithLifecycle(entry)
        KrogerSendScreen(
            state = state,
            holdsLeave = current.id == entry.id,
            actions = SendActions(
                search = { vm.search(it) },
                more = { (vm.state.value as? SendState.Reviewing)?.let { s -> vm.search(s.current.term, more = true) } },
                pick = vm::pick,
                setQuantity = vm::setQuantity,
                setQuantityFor = vm::setQuantityFor,
                setShowUnavailable = vm::setShowUnavailable,
                change = vm::change,
                next = { vm.next() },
                skip = { vm.next(skip = true) },
                back = vm::back,
                edit = vm::edit,
                setIncludePantry = vm::setIncludePantry,
                addToCart = vm::addToCart,
                checkOff = vm::checkOff,
                openSettings = dropUnlessResumed { nav.popBackStack(); nav.navigate(Routes.SETTINGS) },
                close = { popIfCurrent(nav, entry) },
            ),
        )
    }
    composable(Routes.KROGER_MATCHES) { entry ->
        val kroger = LocalKroger.current ?: return@composable
        val vm: KrogerMatchesViewModel = viewModel { KrogerMatchesViewModel(kroger, container.krogerMatches) }
        val state by vm.state.collectAsStateWithLifecycle()
        KrogerMatchesScreen(
            state = state,
            actions = MatchesActions(
                forget = vm::forget,
                openChange = vm::openChange,
                closeChange = vm::closeChange,
                search = vm::search,
                choose = vm::choose,
                back = { popIfCurrent(nav, entry) },
            ),
        )
    }
}
