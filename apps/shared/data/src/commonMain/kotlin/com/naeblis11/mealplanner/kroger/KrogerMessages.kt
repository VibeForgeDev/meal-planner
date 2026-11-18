package com.naeblis11.mealplanner.kroger

/** What the Kroger screens say, as fixed sentences (plan 2026-11-17). None ever holds a token, a code or a secret. */
object KrogerMessages {
    const val NO_CLIENT = "Kroger isn't set up in this copy of Meal Planner. Add a Kroger client in Settings."
    const val CLIENT_REFUSED = "Kroger refused this copy of Meal Planner's Kroger client. Set up a new one in Settings."
    const val SIGN_IN_AGAIN = "Your Kroger sign-in has ended. Sign in to Kroger again in Settings."
    const val SIGN_IN_FAILED = "Kroger didn't finish the sign-in. Try again."
    const val SIGN_IN_CANCELLED = "The Kroger sign-in was cancelled."
    const val SIGN_IN_TIMED_OUT = "The Kroger sign-in took too long. Try again."
    const val SIGN_IN_WAITING = "A Kroger sign-in is already waiting for the browser."
    const val SIGN_IN_PORT_BUSY = "Another program is using the port Kroger's sign-in comes back to on this PC. Close it and try again."
    const val NOT_SIGNED_IN = "Sign in to Kroger in Settings first."
    const val NO_STORE = "Choose your Kroger store in Settings first."
    const val CART_NOT_ALLOWED = "Kroger didn't allow adding to your cart. Sign in to Kroger again in Settings."
    const val LIMIT = "Kroger's daily limit for Meal Planner is used up. Try again tomorrow."
    const val UNAVAILABLE = "Kroger isn't answering right now. Try again in a few minutes."
    const val NO_TOKEN = "Kroger's answer was missing its sign-in. Try again."
    const val BAD_ZIP = "Enter a five-digit ZIP code."
    const val SHORT_TERM = "Search for at least three letters."
    const val OFFLINE = "Kroger can't be reached. Check the internet connection and try again."
    const val TOKEN_UNREADABLE = "The saved Kroger sign-in can't be read here. Sign in to Kroger again."
    const val TOKEN_NOT_SAVED = "The Kroger sign-in couldn't be kept safe, so it wasn't saved. Try again."
    const val CERTIFICATION_NO_CART =
        "This Kroger client is a test (certification) one: it can find stores and prices, but signing in and the cart need a production client."
    const val BAD_CLIENT = "That isn't a Kroger client id and secret. Copy both from your application at developer.kroger.com."
}
