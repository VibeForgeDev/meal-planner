package com.naeblis11.mealplanner.settings

/**
 * The privacy policy, shown in Settings and published as `docs/PRIVACY.md`.
 *
 * The two copies are kept word for word the same by `tests/test_privacy_policy.py`, so this is the one place to edit.
 *
 * Two rules the test also pins, because both are easy to undo by accident:
 *
 * - **It says what happens, never how.** No folders, ports, protocols, hosts, libraries or the name of whatever
 *   service hosts a release. A privacy policy has to tell the reader which third parties see anything and what
 *   leaves the device; it does not have to hand anyone a map of the build. Vague about the plumbing, exact about
 *   the data.
 * - **Nothing personal.** No names, accounts, handles, addresses or machine names. The only contact is the site.
 */
object PrivacyPolicy {

    const val UPDATED = "Last updated 17 November 2026"

    /** The whole policy in a sentence, shown before the reader opens the rest. */
    const val SUMMARY =
        "Meal Planner is made by VibeForgeDev. It has no accounts, no adverts and no analytics, it keeps what you " +
            "enter on your own devices, and it sends none of it to us."

    /** Heading to body, in the order they are shown and published. */
    val SECTIONS: List<Pair<String, String>> = listOf(
        "What the app keeps, and where" to
            "Your recipes, photos, meal plans, pantry and shopping list are kept on the device you entered them on, " +
                "and in any backup you choose to make. They are not uploaded, and no one else can reach them through " +
                "the app.",

        "What we receive" to
            "Nothing. VibeForgeDev runs no service that your data passes through, receives no information from the " +
                "app, and has no way to see your recipes, plans or lists. Nothing is sold or shared with anyone, " +
                "because nothing is collected in the first place.",

        "When the app goes online" to
            "The app works without a connection, and goes online only for things you ask for: to check whether a " +
                "newer version is available and download one when you choose to install it, to send meals to a " +
                "calendar you have connected, to fetch a photo for a recipe you are importing from a website, to " +
                "look up Kroger prices and add the products you pick to your Kroger cart, and to exchange your " +
                "library with your own devices. Each of those sends only what the task needs. None of them sends " +
                "anything to us.",

        "Your own devices" to
            "You can pair your devices so they share one library. That exchange goes directly between your own " +
                "devices over an encrypted connection; it does not pass through us or any other company.",

        "Calendars you connect" to
            "Connecting a calendar is optional. The app adds, changes and removes only the meal entries it created " +
                "itself, and reads nothing else in your calendar. You can disconnect at any time, and you can also " +
                "withdraw the app's access from the calendar account's own settings.",

        "Google Calendar" to
            "Where the calendar you connect is a Google one, you sign in on Google's own page and the app never sees " +
                "your password. It asks only to list your calendars, so you can choose one, and to manage events in " +
                "the calendar you chose. Meal Planner's use of information received from Google APIs follows the " +
                "Google API Services User Data Policy, including the Limited Use requirements.",

        "Kroger" to
            "Checking Kroger prices and adding items to a Kroger cart are optional. You sign in on Kroger's own page " +
                "and the app never sees your password. When you use them, the app sends Kroger the store you chose " +
                "and the words of the items you look up, and adds to your Kroger cart only the products you pick and " +
                "confirm. Kroger handles that under its own privacy policy. The app keeps the store you chose and, " +
                "for each item on your list, which product you picked and the aisle your store shelves it in, so it " +
                "can suggest it next time and show the aisle on your shopping list. Product pictures are fetched from " +
                "Kroger when shown and are not saved. It keeps no prices and nothing about your cart.",

        "Other software you connect" to
            "You can let other software you run yourself send recipes or spoken commands to the app. Those " +
                "connections stay on your own device or your own home network.",

        "Scanning a pairing code" to
            "On a phone, pairing by scanning a code uses the code scanner built into Google Play services. The app " +
                "removes that component's own reporting. Google Play services, which runs the scanner, may still " +
                "record that it was used. You can pair by typing a code instead.",

        "Children" to
            "Meal Planner is not directed at children, and it collects no information about anyone who uses it.",

        "Changes" to
            "If this policy changes, the new version travels with the app and is shown here. The date above is when " +
                "it last changed.",

        "Contact" to
            "Questions about this policy: vibeforgedev.com",
    )
}
