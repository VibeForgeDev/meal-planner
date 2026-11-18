# Meal Planner

A recipe library, weekly meal calendar, pantry, and pantry-aware shopping list for one household. Recipes are plain [Open Recipe Format](https://github.com/open-recipe-format) YAML files you own. It comes as a Windows desktop app and a standalone Android app, plus a Chrome extension that sends recipes from websites to the Windows app and an optional Alexa skill. There is no server to run and no cloud copy of your data.

## Features

- **Recipe library** — browse, search, and categorize recipes stored as YAML files. Import `.yaml`/`.yml` files, legacy Meal Master (`.mmf`) files, a backup zip, or a recipe straight from a website through the companion [Chrome extension](chrome-extension/). Every import is reviewed before anything is saved.
- **Ingredient editing** — add, edit, delete, and reorganize a recipe's ingredients into named sections (e.g. "Bechamel Sauce", "Bolognese Sauce"), including moving ingredients between sections.
- **Unit conversion** — ingredient amounts are normalized to US customary units at import time, with fraction-exact arithmetic — no floating-point rounding drift. Amounts the parser can't understand are flagged for a quick manual correction instead of silently guessing.
- **Meal calendar** — assign recipes to a date and meal slot for the week ahead, and send the week to a Google calendar.
- **Pantry** — track what you already have on hand.
- **Shopping list** — a running cart: add a week's assigned meals to it (as many weeks as you like) and it crosses them against your pantry, automatically combining matching ingredients (with unit conversion) so you don't see "2 tbsp butter" and "1 cup butter" as two separate lines. Nothing leaves the list until you check it off, remove it, or clear the list.
- **Recipe photos** — attach a photo to a recipe (added by hand or pulled in by the Chrome extension).
- **Windows app** — lives in the tray, keeps its library in `Documents\Meal Planner`, answers the Chrome extension and Alexa while it runs, and updates itself from this repository's signed releases. See [docs/WINDOWS.md](docs/WINDOWS.md).
- **Android app** — a standalone version for one phone (recipes, calendar, pantry, shopping list) with no server, going online only to check GitHub for its own updates; libraries move between it and the Windows app as a backup zip. See [docs/ANDROID.md](docs/ANDROID.md).
- **Kroger (optional)** — look up your Kroger store's prices for the shopping list, pick a product for each item (remembered for next time), and add them to your Kroger cart, on the PC and the phone. See [docs/KROGER.md](docs/KROGER.md).
- **AI assistants (optional)** — an MCP server in the Windows app lets an assistant on the same PC (Claude Code, Claude Desktop) read and add recipes, plan meals, and change the pantry and shopping list. See [docs/MCP.md](docs/MCP.md).
- **Voice control (optional)** — an Alexa skill ("Alexa, ask my chef to add milk to the cart / add olive oil to the pantry / plan tacos for dinner on Thursday") bridged through your home's Home Assistant to the Windows app. See [alexa/SETUP.md](alexa/SETUP.md).

## Install

- **Windows:** download `MealPlanner-<version>.msi` from this repository's **Releases** page and double-click it. It installs for your Windows account only, with no administrator rights. [docs/WINDOWS.md](docs/WINDOWS.md) covers the firewall prompt, Controlled folder access, updates, where your data lives, and bringing in recipe files you already have.
- **Android (8.0 or newer):** download `MealPlanner-<version>.apk` from the same Releases page on the phone; see [docs/ANDROID.md](docs/ANDROID.md).
- **Chrome extension:** see [below](#chrome-extension).
- **Alexa:** see [alexa/SETUP.md](alexa/SETUP.md).
- **Kroger prices and cart:** see [docs/KROGER.md](docs/KROGER.md).
- **AI assistants (MCP):** see [docs/MCP.md](docs/MCP.md).

[docs/PRIVACY.md](docs/PRIVACY.md) says what the apps send where (in short: nothing to us).

## Requirements

Using the apps needs only a Windows PC and, optionally, an Android phone. This is the full list of tools the apps, the integrations and the tests may use.

| Requirement | Needed for | How to get it |
|---|---|---|
| **Windows 10 or 11** | Running the Windows app | — |
| **Android phone, 8.0 or newer** (optional) | Running the Android app | Download the APK from this repository's Releases page; see [docs/ANDROID.md](docs/ANDROID.md) |
| **Google Chrome** (or another Chromium browser) | The recipe-import browser extension | [google.com/chrome](https://www.google.com/chrome/) |
| **Home Assistant** (optional) | The Alexa skill | [home-assistant.io](https://www.home-assistant.io/), reachable by Alexa (for example with a [Nabu Casa](https://www.nabucasa.com/) subscription); see [alexa/SETUP.md](alexa/SETUP.md) |
| **Kroger account** (optional) | Kroger prices for the shopping list and adding to your Kroger cart | Sign in from either app's Settings > Kroger; see [docs/KROGER.md](docs/KROGER.md). Releases carry the Kroger application it talks to |
| **Kroger developer application** (maintainers) | Building the releases' built-in Kroger client (`apps/kroger-client.properties`) | Register one at [developer.kroger.com](https://developer.kroger.com/); see [docs/KROGER.md](docs/KROGER.md), "The built-in client" |
| **Google account** (optional) | Sending the week's meals to a Google calendar | Sign in from the Windows app's Settings; see [docs/WINDOWS.md](docs/WINDOWS.md), "Google Calendar" |
| **JmDNS** 3.6.3 (bundled in the Windows app) | Finding other Meal Planner PCs on the home network (mDNS, UDP port 5353), so only one household's PC sends to Google Calendar and answers Alexa | Nothing to install: Gradle fetches it (`org.jmdns:jmdns`, Apache License 2.0) |
| **ktor-network-tls-certificates** 3.6.0 (bundled in the Windows app) | Making the self-signed certificate of the sync port (HTTPS on TCP 5001) that phones and other PCs pin when they pair | Nothing to install: Gradle fetches it (`io.ktor:ktor-network-tls-certificates`, Apache License 2.0) |
| **QR Code generator** 1.8.0 by Project Nayuki (bundled in the Windows app) | Drawing the pairing QR code in Settings > Phones and PCs | Nothing to install: Gradle fetches it (`io.nayuki:qrcodegen`, MIT License) |
| **Google code scanner** (`play-services-code-scanner`, bundled in the Android app) | Scanning the PC's pairing QR code in the Android app's Settings > Join a PC; Google Play services runs the scanner, so the app has no camera permission | Nothing to install: Gradle fetches it (`com.google.android.gms:play-services-code-scanner`, Android SDK License); the phone needs Google Play services, or the code can be typed instead |
| **Android Studio** (developers) | Building, testing and signing the Android app in `apps/androidApp` (its `keytool` makes the release keystore). It bundles the JDK the build needs (JDK 17 or newer; the current bundle is JDK 25); that same JDK (`jbr`) also builds, tests and runs the Windows desktop app in `apps/desktopApp`; only building its installer needs the full JDK below. On first sync, Android Studio (or the Gradle build) downloads **Android SDK Platform 37**, which the build targets; install it from SDK Manager if prompted | [developer.android.com/studio](https://developer.android.com/studio) or `winget install Google.AndroidStudio` |
| **A full JDK 25 with jpackage** (developers, building the Windows installer only) | Making the desktop app's MSI (`:desktopApp:packageMsi`) and the app image its smoke check runs (`apps/desktopApp/smoke-packaged.ps1`); Android Studio's JDK has no `jpackage` | `winget install --id EclipseAdoptium.Temurin.25.JDK -e` (Eclipse Temurin 25), then point `MEAL_PLANNER_PACKAGING_JDK` at its folder; see [docs/WINDOWS.md](docs/WINDOWS.md), "Build the installer". The build downloads WiX 3.11.2 itself, so there is nothing else to install |
| **Node.js 20+ (LTS)** (developers) | Running the extension's JavaScript unit tests only — *not* needed to use the extension | [nodejs.org](https://nodejs.org/) or `winget install OpenJS.NodeJS.LTS`; open a new terminal afterwards |
| **Python 3.11+** (developers) | The Alexa skill's tests and code (`alexa/lambda_function.py` runs on Amazon's Python), the public export (`tools/export_public.py`, the owner's private tooling kept outside the public repository) and the release scripts' tests; no app runs on Python | [python.org](https://www.python.org/downloads/) or `winget install Python.Python.3.12` |
| **Python packages** (`requirements-dev.txt`, developers) | PyYAML for the Alexa asset tests; Pillow for the icon script (`tools/make_icons.py`, which renders `icon.svg` with a headless Microsoft Edge, built into Windows, or Chrome, and makes every app icon from it) | `pip install -r requirements-dev.txt`, preferably in a `.venv` |
| **Git** (developers) | Cloning the project | [git-scm.com](https://git-scm.com/) or `winget install Git.Git` |
| **GitHub CLI** (maintainers, optional) | Publishing a release of the Windows and Android apps: `tools/release.ps1` (in the private working repository) runs `gh release create` (see [docs/RELEASING.md](docs/RELEASING.md)) | [cli.github.com](https://cli.github.com/) or `winget install GitHub.cli` |

### Chrome extension

The `chrome-extension/` directory holds an unpacked Chrome extension that extracts a recipe (via its page's Schema.org JSON-LD data) from any recipe website and sends it to the Windows app running on the same PC. To install it:

1. Go to `chrome://extensions`, enable Developer Mode.
2. Click "Load unpacked" and select the `chrome-extension/` directory.
3. With Meal Planner running, open any recipe page and click the extension icon. The extension assumes `http://127.0.0.1:5000`, the app's address; the Windows app comes forward with the recipe open on its import review.

## Building and running tests

The Kotlin apps' tests, shared, desktop and Android (requires Android Studio — see [Requirements](#requirements)); the one-liner points `JAVA_HOME` at Android Studio's bundled JDK:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\apps\gradlew.bat -p apps :shared:core:desktopTest :shared:data:desktopTest :desktopApp:test :desktopApp:checkPackagingConfig :androidApp:testDebugUnitTest :releaseTool:test
```

A preview of the Windows app on a throwaway data folder (port 5055, never your Documents):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\apps\gradlew.bat -p apps :desktopApp:run
```

The Chrome extension's JSON-LD extraction logic (requires Node.js):

```bash
node --test "chrome-extension/**/*.test.js"
```

The Alexa skill's assets and forwarder, the public export and the release scripts (Python):

```bash
python -m unittest discover -s tests
```

[docs/WINDOWS.md](docs/WINDOWS.md) and [docs/ANDROID.md](docs/ANDROID.md) cover building the installer and a signed APK; [docs/RELEASING.md](docs/RELEASING.md) covers publishing a release.

## Project structure

| File/dir | Responsibility |
|---|---|
| `apps/` | The Kotlin apps (Compose Multiplatform): `shared/core` (the `domain` package: amounts, units, aisles, the shopping-list merge, the ORF and Meal Master parsers, voice and web-recipe rules), `shared/data` (Room, repositories, backup, calendar rules, photos, the recipe folder sync, updates), `shared/ui` (screens and ViewModels behind the `ui/Platform.kt` expect/actual seam), `androidApp` ([docs/ANDROID.md](docs/ANDROID.md)), `desktopApp` ([docs/WINDOWS.md](docs/WINDOWS.md)) and `releaseTool` (signs a release's update list) |
| `chrome-extension/` | The companion recipe-import Chrome extension |
| `alexa/` | The Alexa skill: its interaction model, the Alexa-hosted forwarder code (`lambda_function.py` + `config.py` template) that adds the Home Assistant token, the Home Assistant YAML that bridges it to the Windows app, and setup steps |
| `tests/` | The Python tests (Alexa assets and forwarder, public export, release scripts) |
| `tests/fixtures/parity/`, `tests/fixtures/mealmaster/` | Golden cases the Kotlin tests check: generated by the retired Python server, now frozen as the reference |
| `tools/` | The owner's private tooling, kept outside the public repository: the release scripts (`release.ps1`, `release-key.ps1`; the owner runs them) and the public export (`export_public.py`) |
| `docs/` | User and maintainer guides; `docs/superpowers/` holds the design specs and plans, past ones included |
| `DESIGN.md`, `PRODUCT.md` | The visual system (its tokens are the apps' theme) and what the product is |

## License

Meal Planner is released under the [MIT License](LICENSE).
