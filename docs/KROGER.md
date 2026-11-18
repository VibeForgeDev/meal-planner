# Kroger prices and cart

Both apps can look up Kroger prices for your shopping list and add the products you pick to your Kroger cart. You then
check out on kroger.com or in the Kroger app as usual. This is optional; the shopping list works the same without it.

## Setting it up (each PC and phone)

In **Settings > Kroger**:

1. **Sign in to Kroger.** Kroger's own sign-in page opens in your browser. Meal Planner never sees your Kroger
   password. Whoever signs in is whose cart gets the items. Each PC and phone signs in on its own; sign-ins never
   sync.
2. **Your store.** Type your ZIP code, press Find stores, and pick your store. Prices, stock and pickup or delivery
   are your store's. The store is remembered on that device.
3. **How you get your order:** Pickup or Delivery. Only products your store offers that way are shown.

## Send to Kroger

On the shopping list, press **Kroger**. The unchecked items, except those already in your pantry (a switch adds
them), come up one at a time:

- **The matches:** your store's products, closest to the item's name first. Each shows Kroger's picture of it, its
  description, size, price (with the sale price when there is one), stock and aisle, as Kroger gives them. Products the store doesn't offer for your
  pickup or delivery, and ones it says are out of stock, are hidden; **Show unavailable** shows them.
- **Search Kroger** searches again with other words; **More** loads more results.
- **Pick one** and set **How many** in its card (the summary can change it too), or **Skip** the item. **Back** goes to the previous item.
- **The summary** lists everything picked, each with **Change**. **Add N items to Kroger cart** sends them all at
  once. Nothing reaches your cart before you press it.
- **Afterwards**, **Check them off the list** ticks those items on your shopping list.

## Remembered products

What you pick for an item (say, "ground chuck") is remembered, with how many, and comes up already picked the next
time, marked **Last time**. You can still pick another. When that product is no longer available at your store, the
review says so and shows the closest ones it has.

**Settings > Kroger > Remembered products** lists every one, with its picture and aisle, **Change** and **Forget**.
They sync between your PC and phones like the rest of your library.

## Aisles on the shopping list

A remembered product also keeps the aisle your store shelves it in ("AISLE 12"), taken from Kroger each time the
product is looked up: when a send opens, when you pick it, and when Remembered products opens. Whenever that item is
on the shopping list again, its row says where, for example **Kroger: AISLE 12**, on the PC and on a phone that syncs
with it, so you can walk the store with it. A device that has chosen a store shows only that store's aisles; a phone
with no store chosen shows them all.

## What Kroger doesn't allow

Kroger's rules for apps shape what Meal Planner does:

- **No price history.** Prices are looked up each time and never saved, and are shown exactly as Kroger gives them.
  A remembered product keeps only Kroger's product number, your quantity and its aisle at your store (the owner's
  call, like remembering the store). Product pictures come from Kroger's own site (kroger.com) each time they are
  shown, are held in memory only while the app runs, and are never saved.
- **No reading your cart.** Meal Planner can only add to it, never see or remove what is in it; do that on
  kroger.com or in the Kroger app. It doesn't keep what it added once the send is done.
- **Only what you ask for.** Nothing goes into the cart without the summary's one button.

## The built-in client

Meal Planner talks to Kroger as a registered Kroger application. Releases carry one, so nobody else needs to
register. It is built in from the git-ignored `apps/kroger-client.properties`, made by the owner:

    clientId=<the application's client ID>
    clientSecret=<its client secret>
    environment=production

(`release.ps1` refuses to publish without it.) Kroger limits each application to about 10,000 product lookups and
5,000 cart adds a day, shared by every household using it.

**If Kroger ever stops accepting it**, or to test, Settings > Kroger > **Use your own Kroger client** takes another
application's client ID and secret (on the PC they are kept in the `.env` as `MEAL_PLANNER_KROGER_CLIENT_ID`,
`MEAL_PLANNER_KROGER_CLIENT_SECRET` and `MEAL_PLANNER_KROGER_ENVIRONMENT`). Register it at developer.kroger.com with:

- the scopes `product.compact` and `cart.basic:write`;
- the redirect URIs `http://localhost:5070/kroger` (the installed Windows app), `http://localhost:5071/kroger` (a
  preview window) and `mealplanner://kroger` (the phone).

**A test client.** Kroger registers an application either for production or for its certification (test)
environment, and its client only works in that one. Tick **It's a test (certification) client** for one (or put
`environment=certification` in `apps/kroger-client.properties`). Kroger's certification environment has no customer
accounts, so a test client can find stores and look up prices, but signing in and adding to a cart need a
production client.
