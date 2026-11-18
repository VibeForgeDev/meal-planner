package com.naeblis11.mealplanner.kroger

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.MainDispatcherRule
import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.KrogerMatchRepository
import com.naeblis11.mealplanner.data.ShoppingItemEntity
import com.naeblis11.mealplanner.data.ShoppingRepository
import com.naeblis11.mealplanner.domain.KrogerMatch
import com.naeblis11.mealplanner.domain.KrogerModality
import com.naeblis11.mealplanner.domain.KrogerProduct
import com.naeblis11.mealplanner.domain.KrogerStock
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Kroger for the screens, in memory: a store's products, what the cart got, and how often each call ran. */
class FakeKrogerControls(
    initial: KrogerStatus = KrogerStatus(clientReady = true, signedIn = true, store = KrogerStore("01400943", "Kroger", "1 Main St")),
) : KrogerControls {
    private val _status = MutableStateFlow(initial)
    override val status: StateFlow<KrogerStatus> = _status

    val products = mutableListOf<KrogerProduct>()
    val cart = mutableListOf<KrogerCartItem>()
    val searches = mutableListOf<Pair<String, Int>>()
    var lookups = 0
    var failCart: IOException? = null

    override fun signIn(onUrl: (String) -> Unit) {
        _status.value = _status.value.copy(signedIn = true)
    }

    override fun cancelSignIn() = Unit

    override fun signOut() {
        _status.value = _status.value.copy(signedIn = false)
    }

    override fun stores(zip: String): List<KrogerStore> = listOf(KrogerStore("01400943", "Kroger", "1 Main St"))

    override fun chooseStore(store: KrogerStore) {
        _status.value = _status.value.copy(store = store)
    }

    override fun setModality(modality: KrogerModality) {
        _status.value = _status.value.copy(modality = modality)
    }

    override fun search(term: String, start: Int): List<KrogerProduct> {
        searches += term to start
        val words = term.lowercase().split(' ')
        return products.filter { p -> words.any { p.description.lowercase().contains(it) } }.drop(start).take(10)
    }

    override fun products(ids: Collection<String>): List<KrogerProduct> {
        lookups++
        return products.filter { it.productId in ids }
    }

    override fun addToCart(items: List<KrogerCartItem>) {
        failCart?.let { throw it }
        cart += items
    }

    override fun useClient(id: String, secret: String, certification: Boolean) = Unit

    override fun forgetClient() = Unit

    fun product(id: String, description: String, curbside: Boolean = true, stock: KrogerStock = KrogerStock.HIGH, aisle: String? = null) =
        KrogerProduct(id, "u$id", description, "Kroger", "1 lb", 4.99, null, stock, instore = true, curbside = curbside, delivery = true, aisle = aisle)
            .also { products += it }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KrogerSendViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var shopping: ShoppingRepository
    private lateinit var matches: KrogerMatchRepository
    private val kroger = FakeKrogerControls()
    private val made = mutableListOf<KrogerSendViewModel>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
        shopping = ShoppingRepository(db, Dispatchers.Unconfined)
        matches = KrogerMatchRepository(db, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        made.forEach { it.viewModelScope.cancel() }
        db.close()
    }

    private fun vm() = KrogerSendViewModel(kroger, shopping, matches, Dispatchers.Unconfined).also { made += it }

    private suspend fun row(name: String, amount: String? = null, unit: String? = null, inPantry: Boolean = false, checked: Boolean = false) =
        db.shoppingDao().insert(ShoppingItemEntity(name = name, amount = amount, unit = unit, inPantry = inPantry, checked = checked))

    private fun reviewing(vm: KrogerSendViewModel) = vm.state.value as SendState.Reviewing

    // Room answers on its own threads: wait for the state, outside the test's virtual time.
    private suspend fun await(vm: KrogerSendViewModel, ok: (SendState) -> Boolean): SendState =
        withContext(Dispatchers.Default) { withTimeout(5_000) { vm.state.first(ok) } }

    // Loaded, and the item shown searched when nothing was remembered for it.
    private suspend fun loaded(vm: KrogerSendViewModel): KrogerSendViewModel = vm.also {
        await(it) { s -> s is SendState.NotReady || (s is SendState.Reviewing && (s.current.pick != null || s.current.rememberedGone || s.current.searched)) }
    }

    @Test
    fun withoutASignInOrAStoreTheSendSaysWhatIsMissing() = runTest {
        val notSignedIn = FakeKrogerControls(KrogerStatus(clientReady = true))
        assertEquals(KrogerMessages.NOT_SIGNED_IN, (KrogerSendViewModel(notSignedIn, shopping, matches, Dispatchers.Unconfined).also { made += it }.state.value as SendState.NotReady).message)
        val noStore = FakeKrogerControls(KrogerStatus(clientReady = true, signedIn = true))
        assertEquals(KrogerMessages.NO_STORE, (KrogerSendViewModel(noStore, shopping, matches, Dispatchers.Unconfined).also { made += it }.state.value as SendState.NotReady).message)
        val test = FakeKrogerControls(KrogerStatus(clientReady = true, signedIn = false, certification = true))
        assertEquals(KrogerMessages.CERTIFICATION_NO_CART, (KrogerSendViewModel(test, shopping, matches, Dispatchers.Unconfined).also { made += it }.state.value as SendState.NotReady).message)
    }

    @Test
    fun theUncheckedRowsAreReviewedOneNameAtATimeWithoutThePantrysUnlessAsked() = runTest {
        row("Ground chuck", "1 1/2", "lb")
        row("ground chuck", "1", "lb")
        row("Salt", inPantry = true)
        row("Bread", checked = true)
        kroger.product("1", "Kroger Ground Chuck")
        val vm = loaded(vm())
        val s = reviewing(vm)
        assertEquals(listOf("ground chuck"), s.items.map { it.nameKey })
        assertEquals("1 1/2 lb Ground chuck + 1 lb ground chuck", s.current.label)
        assertEquals(1, s.pantryCount)
        // Nothing remembered: searched as it comes up.
        assertEquals(listOf("Ground chuck" to 0), kroger.searches)
        assertEquals(listOf("1"), s.current.shown(KrogerModality.PICKUP).map { it.productId })
        vm.setIncludePantry(true)
        await(vm) { it is SendState.Reviewing && it.items.size == 2 }
        assertEquals(listOf("ground chuck", "salt"), reviewing(vm).items.map { it.nameKey })
    }

    @Test
    fun aRememberedMatchComesUpPickedAndOneGoneFromTheStoreIsNot() = runTest {
        row("Ground chuck")
        row("Onion")
        val chuck = kroger.product("1", "Kroger Ground Chuck")
        kroger.product("2", "Yellow Onion", curbside = false)
        kroger.product("3", "White Onion")
        matches.keep(listOf(KrogerMatch("ground chuck", "1", "u1", 3), KrogerMatch("onion", "2", "u2", 2)))
        val vm = loaded(vm())
        val first = reviewing(vm).current
        assertEquals(chuck, first.pick)
        assertEquals(3, first.quantity)
        assertTrue(kroger.searches.isEmpty())
        assertEquals(1, kroger.lookups)
        vm.next()
        await(vm) { it is SendState.Reviewing && it.index == 1 && it.current.searched }
        val second = reviewing(vm).current
        assertTrue(second.rememberedGone)
        assertNull(second.pick)
        assertEquals(listOf("3"), second.shown(KrogerModality.PICKUP).map { it.productId })
    }

    @Test
    fun pickingAProductLeavesTheListInItsOrder() = runTest {
        row("Onion")
        kroger.product("1", "Yellow Onion")
        kroger.product("2", "White Onion")
        kroger.product("3", "Red Onion")
        matches.keep(listOf(KrogerMatch("onion", "1", "u1", 1)))
        val vm = loaded(vm())
        vm.change()
        await(vm) { it is SendState.Reviewing && it.current.searched }
        val before = reviewing(vm).current.shown(KrogerModality.PICKUP).map { it.productId }
        assertEquals("1", before.first())
        vm.pick(reviewing(vm).current.shown(KrogerModality.PICKUP).last())
        assertEquals(before, reviewing(vm).current.shown(KrogerModality.PICKUP).map { it.productId })
        vm.pick(reviewing(vm).current.shown(KrogerModality.PICKUP)[1])
        assertEquals(before, reviewing(vm).current.shown(KrogerModality.PICKUP).map { it.productId })
    }

    @Test
    fun theStoresAislesAreKeptForTheRememberedProductsAndForNewPicks() = runTest {
        row("Ground chuck")
        row("Onion")
        kroger.product("1", "Kroger Ground Chuck", aisle = "AISLE 12")
        kroger.product("3", "White Onion", aisle = "Produce")
        matches.keep(listOf(KrogerMatch("ground chuck", "1", "u1", 1)))
        val vm = loaded(vm())
        // Looked up as the send opened: the remembered product's aisle at this store.
        assertEquals(KrogerMatch("ground chuck", "1", "u1", 1, "AISLE 12", "01400943"), matches.forKeys(listOf("ground chuck")).values.single())
        vm.next()
        await(vm) { it is SendState.Reviewing && it.index == 1 && it.current.searched }
        vm.pick(reviewing(vm).current.shown(KrogerModality.PICKUP).first())
        vm.next()
        vm.addToCart()
        await(vm) { it is SendState.Done }
        assertEquals(KrogerMatch("onion", "3", "u3", 1, "Produce", "01400943"), matches.forKeys(listOf("onion")).values.single())
    }

    @Test
    fun theCartGetsEveryPickAtOnceAndOnlyThenAreTheyRemembered() = runTest {
        val chuckRow = row("Ground chuck")
        row("Onion")
        kroger.product("1", "Kroger Ground Chuck")
        val vm = loaded(vm())
        vm.pick(reviewing(vm).current.shown(KrogerModality.PICKUP).first())
        vm.setQuantity(2)
        vm.next()
        vm.next(skip = true)
        val summary = vm.state.value as SendState.Summary
        assertEquals(listOf("ground chuck"), summary.picked.map { it.nameKey })
        assertEquals(listOf("onion"), summary.skipped.map { it.nameKey })
        assertTrue(matches.all().isEmpty())
        // How many can still change on the summary.
        vm.setQuantityFor("ground chuck", 3)
        assertEquals(3, (vm.state.value as SendState.Summary).picked.single().quantity)

        kroger.failCart = IOException("Kroger isn't answering right now.")
        vm.addToCart()
        await(vm) { it is SendState.Summary && it.error != null }
        assertEquals("Kroger isn't answering right now.", (vm.state.value as SendState.Summary).error)
        assertTrue(matches.all().isEmpty())

        kroger.failCart = null
        vm.addToCart()
        await(vm) { it is SendState.Done }
        assertEquals(listOf(KrogerCartItem("u1", 3)), kroger.cart)
        assertEquals(listOf(KrogerMatch("ground chuck", "1", "u1", 3)), matches.all())
        val done = vm.state.value as SendState.Done
        assertEquals(listOf(chuckRow), done.rowIds)
        vm.checkOff()
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (!db.shoppingDao().allInIdOrder().first { it.id == chuckRow }.checked) delay(10) } }
        assertFalse(db.shoppingDao().allInIdOrder().first { it.name == "Onion" }.checked)
    }

    @Test
    fun theSummaryCanGoBackToAnItemAndKeepsWhatWasPicked() = runTest {
        row("Ground chuck")
        row("Onion")
        kroger.product("1", "Kroger Ground Chuck")
        kroger.product("3", "White Onion")
        val vm = loaded(vm())
        vm.pick(kroger.products[0])
        vm.next()
        vm.pick(kroger.products[1])
        vm.next()
        vm.edit("ground chuck")
        assertEquals(0, reviewing(vm).index)
        assertEquals("1", reviewing(vm).current.pick!!.productId)
        vm.next()
        assertEquals("3", reviewing(vm).current.pick!!.productId)
    }

    @Test
    fun nothingToBuyOffersThePantrysRows() = runTest {
        row("Salt", inPantry = true)
        val vm = loaded(vm())
        val s = vm.state.value as SendState.NotReady
        assertEquals(KrogerSendViewModel.ALL_IN_PANTRY, s.message)
        assertTrue(s.offerPantry)
        vm.setIncludePantry(true)
        await(vm) { it is SendState.Reviewing }
        assertEquals(listOf("salt"), reviewing(vm).items.map { it.nameKey })
        assertEquals(shopping.observe().first().size, 1)
    }
}
