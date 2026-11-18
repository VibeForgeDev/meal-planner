package com.naeblis11.mealplanner.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.savedstate.read
import com.naeblis11.mealplanner.app.AppContainer
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.domain.Week
import com.naeblis11.mealplanner.domain.krogerAisles
import com.naeblis11.mealplanner.kroger.LocalKroger
import com.naeblis11.mealplanner.pantry.PantryScreen
import com.naeblis11.mealplanner.pantry.PantryViewModel
import com.naeblis11.mealplanner.plan.AssignMealScreen
import com.naeblis11.mealplanner.plan.AssignMealViewModel
import com.naeblis11.mealplanner.plan.MealPlanScreen
import com.naeblis11.mealplanner.plan.MealPlanViewModel
import com.naeblis11.mealplanner.settings.GoogleControls
import com.naeblis11.mealplanner.shopping.ShoppingScreen
import com.naeblis11.mealplanner.shopping.ShoppingViewModel
import java.io.File
import java.time.LocalDate

/** The meal plan, pantry and shopping list destinations, added to MealPlannerApp's NavHost. [google]: the desktop's Google send; Android's goes to a phone calendar. */
fun NavGraphBuilder.plannerDestinations(nav: NavController, container: AppContainer, google: GoogleControls? = null) {
    composable(Routes.CALENDAR) {
        val vm: MealPlanViewModel = viewModel {
            MealPlanViewModel(
                container.plans,
                container.shopping,
                createSavedStateHandle(),
                sendWeek = google?.let { controls -> controls::sendWeek } ?: container.calendarSync::sendWeek,
                calendarPicks = google?.picks ?: container.calendarChoice.picks,
            )
        }
        val week by vm.week.collectAsStateWithLifecycle()
        val message by vm.message.collectAsStateWithLifecycle()
        val error by vm.error.collectAsStateWithLifecycle()
        val adding by vm.adding.collectAsStateWithLifecycle()
        val sending by vm.sending.collectAsStateWithLifecycle()
        val sendProblem by vm.sendProblem.collectAsStateWithLifecycle()
        val openRecipe = dropUnlessResumedWith { id: Long -> nav.navigate(Routes.recipe(id)) }
        val assign = dropUnlessResumedWith { target: Pair<LocalDate, String> -> nav.navigate(Routes.assign(target.first, target.second)) }
        val openAppSettings = rememberAppSettingsOpener(vm::appSettingsClosed)
        MealPlanScreen(
            week = week,
            message = message,
            error = error,
            adding = adding,
            sending = sending,
            sendProblem = sendProblem,
            onMessageShown = vm::messageShown,
            onPrevious = vm::previousWeek,
            onNext = vm::nextWeek,
            onThisWeek = vm::thisWeek,
            onAddToShoppingList = vm::addWeekToShoppingList,
            onSend = vm::sendWeekToCalendar,
            onOpenSettings = dropUnlessResumed { nav.navigate(Routes.SETTINGS) },
            onOpenAppSettings = openAppSettings,
            onOpenRecipe = openRecipe,
            onAssign = { date, slot -> assign(date to slot) },
            onRemove = vm::remove,
            thumbnail = { image -> thumbnailFile(container, image) },
            wide = LocalWideLayout.current,
        )
    }
    composable(Routes.PANTRY) {
        val vm: PantryViewModel = viewModel { PantryViewModel(container.pantry, container.shopping) }
        val state by vm.state.collectAsStateWithLifecycle()
        val message by vm.message.collectAsStateWithLifecycle()
        val error by vm.error.collectAsStateWithLifecycle()
        PantryScreen(
            state = state,
            message = message,
            error = error,
            onMessageShown = vm::messageShown,
            onAdd = vm::add,
            onSetOnHand = vm::setOnHand,
            onSave = vm::save,
            onToggleMatch = vm::toggleExactMatch,
            onAddToShoppingList = vm::addToShoppingList,
            onDelete = vm::delete,
            wide = LocalWideLayout.current,
        )
    }
    composable(Routes.SHOPPING) {
        val vm: ShoppingViewModel = viewModel { ShoppingViewModel(container.shopping) }
        val state by vm.state.collectAsStateWithLifecycle()
        val message by vm.message.collectAsStateWithLifecycle()
        val error by vm.error.collectAsStateWithLifecycle()
        val adding by vm.adding.collectAsStateWithLifecycle()
        // Shown once Kroger has a client here; the send itself says what is missing (a sign-in, a store).
        val kroger = LocalKroger.current
        val krogerStatus = kroger?.status?.collectAsStateWithLifecycle()?.value
        val krogerReady = krogerStatus?.clientReady == true
        // Each remembered product's aisle at this device's store, under its shopping row (owner, 2026-11-17).
        val matchList by remember { container.krogerMatches.observe() }.collectAsStateWithLifecycle(emptyList())
        val storeId = krogerStatus?.store?.locationId
        val krogerAisles = remember(matchList, storeId) { krogerAisles(matchList, storeId) }
        val openKroger = dropUnlessResumed { nav.navigate(Routes.KROGER_SEND) }
        ShoppingScreen(
            state = state,
            message = message,
            error = error,
            adding = adding,
            onMessageShown = vm::messageShown,
            onCheck = vm::setChecked,
            onAddThisWeek = vm::addThisWeek,
            onAddItem = vm::addItem,
            onSetAisle = vm::setAisle,
            onRemove = vm::remove,
            onClear = vm::clear,
            wide = LocalWideLayout.current,
            onSendToKroger = if (krogerReady) openKroger else null,
            krogerAisles = krogerAisles,
        )
    }
    composable(
        Routes.ASSIGN,
        arguments = listOf(navArgument("date") { type = NavType.StringType }, navArgument("slot") { type = NavType.StringType }),
    ) { entry ->
        val date = LocalDate.parse(entry.arguments!!.read { getString("date") })
        val slot = entry.arguments!!.read { getString("slot") }
        val vm: AssignMealViewModel = viewModel { AssignMealViewModel(date, slot, container.plans, container.recipes) }
        val query by vm.query.collectAsStateWithLifecycle()
        val servings by vm.servings.collectAsStateWithLifecycle()
        val results by vm.results.collectAsStateWithLifecycle()
        val error by vm.error.collectAsStateWithLifecycle()
        val assigned by vm.assigned.collectAsStateWithLifecycle()
        // Leaves once, with this composition's NavController, even if recreated mid-save.
        LaunchedEffect(assigned) { if (assigned) popIfCurrent(nav, entry) }
        AssignMealScreen(
            slot = slot,
            dayLabel = Week.dayLabel(date),
            query = query,
            servings = servings,
            results = results,
            error = error,
            onQueryChange = vm::setQuery,
            onServingsChange = vm::setServings,
            onPick = vm::assign,
            onBack = dropUnlessResumed { nav.popBackStack() },
            thumbnail = { summary -> thumbnailFile(container, summary.imageFilename) },
        )
    }
}

/** A recipe photo's thumbnail file, if it has a safe photo name. */
private fun thumbnailFile(container: AppContainer, imageFilename: String?): File? =
    imageFilename?.takeIf { RecipeRepository.isSafeImageName(it) }?.let { container.recipes.imageFile(RecipeRepository.thumbName(it)) }
