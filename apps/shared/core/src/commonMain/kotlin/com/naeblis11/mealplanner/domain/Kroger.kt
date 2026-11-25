package com.naeblis11.mealplanner.domain

import java.util.Locale

/** How the household gets its Kroger order: Kroger's own modality names, sent with every item added to the cart. */
enum class KrogerModality(val wire: String, val label: String) {
    PICKUP("PICKUP", "Pickup"),
    DELIVERY("DELIVERY", "Delivery"),
    ;

    companion object {
        fun fromWire(value: String?): KrogerModality = entries.firstOrNull { it.wire == value } ?: PICKUP
    }
}

/** Kroger's stock levels, as its Products API names them; null when it gave none. */
enum class KrogerStock(val wire: String, val label: String) {
    HIGH("HIGH", "In stock"),
    LOW("LOW", "Low stock"),
    OUT("TEMPORARILY_OUT_OF_STOCK", "Out of stock"),
    ;

    companion object {
        fun fromWire(value: String?): KrogerStock? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * One product at the chosen store, as Kroger's Products API returned it (kroger/KrogerApi). Kroger's acceptable use
 * says to show it exactly as returned and never store it, so nothing here is ever written anywhere: only [productId]
 * and [upc] are kept, as a remembered match (KrogerMatch), with its [aisle] (owner, 2026-11-17). [regular] and
 * [promo] are Kroger's numbers, null when the store gave none; [curbside] and [delivery] say whether the store offers it
 * that way. [aisle] is where the store shelves it, as Kroger names it ("Aisle 12"); [imageUrl] is Kroger's picture of
 * it (on a kroger.com host only), fetched each time it is shown and never saved.
 */
data class KrogerProduct(
    val productId: String,
    val upc: String,
    val description: String,
    val brand: String?,
    val size: String?,
    val regular: Double?,
    val promo: Double?,
    val stock: KrogerStock?,
    val instore: Boolean,
    val curbside: Boolean,
    val delivery: Boolean,
    val aisle: String? = null,
    val imageUrl: String? = null,
    /** Its page on kroger.com, to look at it more closely (owner, 2026-11-25); only an https kroger.com address. */
    val pageUrl: String? = null,
) {
    /** Whether the household can get it from the store the way it orders, and the store doesn't say it's out. */
    fun availableFor(modality: KrogerModality): Boolean {
        val offered = when (modality) {
            KrogerModality.PICKUP -> curbside
            KrogerModality.DELIVERY -> delivery
        }
        return offered && stock != KrogerStock.OUT
    }

    /** Whether Kroger gives a promo price below the regular one; the review then shows both. */
    val onSale: Boolean get() = promo != null && promo > 0.0 && regular != null && promo < regular
}

/**
 * A remembered pick: the shopping list's name for an item, and the Kroger product the household chose for it. [aisle]
 * is where [aisleStoreId]'s store shelved it when last looked up (owner, 2026-11-17), shown on the shopping list.
 */
data class KrogerMatch(
    val nameKey: String,
    val productId: String,
    val upc: String,
    val quantity: Int,
    val aisle: String? = null,
    val aisleStoreId: String? = null,
)

/**
 * The shopping list's Kroger aisles (owner, 2026-11-17): each remembered match's aisle by its name key, for the store
 * this device uses ([storeId]); a device with no store chosen (a phone that only syncs) shows them whatever the store.
 */
fun krogerAisles(matches: List<KrogerMatch>, storeId: String?): Map<String, String> =
    matches.mapNotNull { m ->
        val aisle = m.aisle?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (storeId != null && m.aisleStoreId != storeId) null else m.nameKey to aisle
    }.toMap()

/** "$4.99": Kroger's number with two decimals; never rounded to anything else, never changed. */
fun krogerPrice(value: Double): String = "$" + String.format(Locale.ROOT, "%.2f", value)

/**
 * The search for a shopping list row (plan 2026-11-17, "The send"): its name without what doesn't help Kroger's search,
 * which matches the words given. The row's amount and unit are separate fields and never searched.
 */
object KrogerQuery {
    // Preparation words a store's product names don't carry ("2 cups chopped onion" is bought as an onion).
    private val PREP = setOf(
        "chopped", "diced", "minced", "sliced", "crushed", "cubed", "beaten", "softened", "melted", "cooked",
        "uncooked", "drained", "rinsed", "divided", "packed", "finely", "roughly", "coarsely", "freshly", "thinly",
        "large", "medium", "small", "optional", "undrained", "undiluted", "boiling", "cold", "warm", "hot",
    )

    /**
     * The words to search for: what is before a comma, without anything in brackets, the first of "x or y", and
     * without the [PREP] words. Never empty for a row that has a name: when nothing would be left, the name as it is.
     */
    fun term(name: String): String {
        val noBrackets = name.replace(Regex("""\([^)]*\)?"""), " ").replace(Regex("""\[[^\]]*\]?"""), " ")
        val beforeComma = noBrackets.substringBefore(',')
        val first = Regex("""(?i)(^| )or( |$)""").split(beforeComma).first()
        val words = words(first).filter { it.lowercase(Locale.ROOT) !in PREP }
        return words.joinToString(" ").ifEmpty { words(name).joinToString(" ") }
    }

    /** A row's remembered-match key: its name trimmed, with runs of spaces made one and A-Z folded to a-z only. */
    fun nameKey(name: String): String = words(name).joinToString(" ") { word ->
        buildString { word.forEach { c -> append(if (c in 'A'..'Z') c + 32 else c) } }
    }

    private fun words(text: String): List<String> = text.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }
}

/**
 * The order the review shows Kroger's results in (plan 2026-11-17): only what the store offers the way the household
 * orders (unless [showUnavailable]), closest to the row's words first. Closeness is how many of the term's words the
 * product's description and brand hold, then difflib's ratio of the two; then stock (in stock before low), then
 * Kroger's own order, since its fuzzy search orders differently between calls.
 */
object KrogerRanking {
    fun rank(
        term: String,
        products: List<KrogerProduct>,
        modality: KrogerModality,
        showUnavailable: Boolean = false,
    ): List<KrogerProduct> {
        val wanted = normalized(term).split(' ').filter { it.isNotEmpty() }.toSet()
        val query = normalized(term)
        return products.withIndex()
            .filter { showUnavailable || it.value.availableFor(modality) }
            .sortedWith(
                compareByDescending<IndexedValue<KrogerProduct>> { it.value.availableFor(modality) }
                    .thenByDescending { overlap(wanted, it.value) }
                    .thenByDescending { PyDifflib.ratio(query, normalized(it.value.description)) }
                    .thenBy { stockOrder(it.value.stock) }
                    .thenBy { it.index },
            )
            .map { it.value }
    }

    /** The share of [wanted] words that the product's description and brand hold (a plural "onions" counts as "onion"). */
    fun overlap(wanted: Set<String>, product: KrogerProduct): Double {
        if (wanted.isEmpty()) return 0.0
        val have = normalized("${product.description} ${product.brand.orEmpty()}").split(' ').filter { it.isNotEmpty() }
            .flatMap { listOf(it, singular(it)) }.toSet()
        return wanted.count { it in have || singular(it) in have }.toDouble() / wanted.size
    }

    private fun stockOrder(stock: KrogerStock?): Int = when (stock) {
        KrogerStock.HIGH -> 0
        null -> 1
        KrogerStock.LOW -> 2
        KrogerStock.OUT -> 3
    }

    private fun singular(word: String): String = when {
        word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
        word.length > 3 && word.endsWith("es") && (word.endsWith("oes") || word.endsWith("shes") || word.endsWith("ches")) -> word.dropLast(2)
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
        else -> word
    }

    // Lower case a-z and 0-9 only, everything else a space, runs of spaces one.
    private fun normalized(text: String): String {
        val out = StringBuilder()
        for (c in text) {
            val lower = if (c in 'A'..'Z') c + 32 else c
            out.append(if (lower in 'a'..'z' || lower in '0'..'9') lower else ' ')
        }
        return out.toString().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }
}
