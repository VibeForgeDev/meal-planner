package com.naeblis11.mealplanner.ui

import java.time.LocalDate

object Routes {
    const val RECIPES = "recipes"
    const val RECIPE = "recipe/{id}"
    const val EDIT = "recipe/{id}/edit"
    const val NEW = "new-recipe"
    const val IMPORT = "import"
    const val SETTINGS = "settings"
    const val CALENDAR = "calendar"
    const val PANTRY = "pantry"
    const val SHOPPING = "shopping"
    const val ASSIGN = "assign/{date}/{slot}"
    const val NEEDS_ATTENTION = "needs-attention"
    const val KROGER_SEND = "kroger-send"
    const val KROGER_MATCHES = "kroger-matches"

    fun recipe(id: Long) = "recipe/$id"

    fun edit(id: Long) = "recipe/$id/edit"

    fun assign(date: LocalDate, slot: String) = "assign/$date/$slot"
}
