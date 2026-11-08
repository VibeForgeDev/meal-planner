# Releasing Meal Planner

The Windows app and the Android app update themselves from this repository's GitHub Releases. Each
release carries the Windows installer (`MealPlanner-<version>.msi`), the Android app
(`MealPlanner-<versionName>.apk`), `latest.json`, which lists both with their sizes and SHA-256,
and `latest.json.sig`, its signature. An installed app shows an update only when that signature
verifies against the release key's public half, which is built into the app, and the version is
newer than its own. Nothing installs until someone presses **Install**.

This page is for the person who makes releases. Making one needs this repository on a Windows PC,
Android Studio, the full JDK for the installer (docs/WINDOWS.md, "Build the installer"), the
Android keystore (docs/ANDROID.md, "Building a signed release"), your Google client file
(docs/WINDOWS.md, "Building with your Google client") and the GitHub CLI (`gh`), signed in with
`gh auth login` to an account that can publish to `naeblis11/meal-planner`. The scripts this page
names (`tools/release-key.ps1`, `tools/release.ps1` and `tools/export_public.py`) are the owner's
private tooling, kept in the private working repository and outside the public repository.

Publish the Google consent screen to production first (Google Cloud console, OAuth consent screen),
or family members will be sent through Google's "unverified app" and test-user limits.

**Run both scripts in a Windows PowerShell console window.** Never pipe them, never capture their
output (`$x = .\tools\release.ps1`), and never run them in PowerShell ISE: the password prompts
need a real console. Windows blocks scripts by default, so the commands below start each one through
`powershell -NoProfile -ExecutionPolicy Bypass -File`, which lifts that for the one run only.

## Once: the release key

The release key signs `latest.json`. It is an ECDSA P-256 key, kept beside the Android keystore in
`%LOCALAPPDATA%\Meal Planner\release-key\release-key.p12` and never in the repository. Make it
once, from the repository folder, on a clean `master`:

    powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\release-key.ps1

`keytool` asks you for a new password (6 characters or more) twice, and once more to export the
public certificate. The script then writes the key's public half into
`apps\shared\core\src\commonMain\kotlin\com\naeblis11\mealplanner\update\ReleaseKeyData.kt`.
Commit that file: from then on every build of the apps carries the key, and checks for updates.
Builds made before it never check.

The script refuses to run off `master` or with uncommitted changes, refuses to make a second key,
and refuses to run while the apps already trust one.

### Back up the key

Back up the whole `%LOCALAPPDATA%\Meal Planner\release-key` folder and its password now, somewhere
safe and separate from the PC (a password manager can hold both). **If the key is lost, no
installed copy will accept a later release**, because each trusts only the key it was built with.
Family installs can't update until they reinstall by hand, once, from a build with a new key.

On a new PC, restore the folder to the same place before you release.

If the key really is lost for good: set `PUBLIC_KEY` in `ReleaseKeyData.kt` back to `""`, run
`.\tools\release-key.ps1` to make a new key, commit, release, and install that release by hand on
every PC and phone. From then on they trust the new key.

## Each release

1. Raise the versions in `apps\gradle.properties`:
   - `mealplanner.androidVersionCode`: up by one, every release (Android and the update check
     compare it; it also names the release, `release-<versionCode>`).
   - `mealplanner.androidVersionName`: what people see on the phone (`1.0.1`, `1.1.0`, ...).
   - `mealplanner.desktopVersion`: the Windows version, `MAJOR.MINOR.BUILD`. Raise it when the
     Windows app changed: Windows installs an MSI only over an older version.
2. Commit on `master` and push it.
3. **Export to the public repository, review it and push public master** (`tools/export_public.py`)
   before the next step. If anyone committed to public directly, bring those commits into the
   private repo first (`git format-patch` there, `git am` here), or the export removes them. A clean
   export writes `EXPORTED_FROM` with the private commit it came from; the release's tag is created
   on public master, and the script refuses unless that file on public master is exactly the commit
   it is building.
4. From the repository folder, in a Windows PowerShell console window:

       powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\release.ps1

   It checks `master`, that nothing is uncommitted, pulls `master` (fast-forward only), checks that
   public master's `EXPORTED_FROM` names this commit, then the release key, the packaging JDK, that
   `apps\keystore.properties` and `apps\google-client.properties` exist (it never reads them; it
   refuses without either, because releases carry the built-in Google client), `gh`'s sign-in, and
   that the release's tag isn't on GitHub yet, neither as a release nor as a leftover git tag (a
   bare tag would make `gh release create` ignore where the tag should land: delete it or raise the
   version). It runs the Kotlin tests, builds the MSI (read back
   by its inspector) and the signed APK, stages them with `latest.json` in a new folder under
   `%TEMP%`, and signs `latest.json`: the release tool asks for the release key's password itself
   (it isn't shown). It refuses to sign with a key the apps don't trust.
5. It shows the versions, file names, sizes and SHA-256 values and where the tag lands, then asks
   `Publish to naeblis11/meal-planner? (y/N)`. Only `y` publishes, with `gh release create`, as
   the repository's latest release. Anything else leaves the staged files in `%TEMP%`.

The script never pushes code; step 3 is yours.

`-SkipTests` skips step 4's tests when you have just run them on the same commit.

### The short routine (with Claude)

Say "bump the version and release". Claude does steps 1 to 3: raises the versions, runs the test
sets, commits and pushes `master`, ports any commits made directly on public, exports into its own
clone of the public repository, checks the scan and pushes public master. It never runs either
script or touches the release key. Then you run, in a Windows PowerShell console window:

    powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\release.ps1 -SkipTests

and type the release key's password and `y` (on the same line as the question).

## What people see

Both apps check when they open, at most once a day, and **Settings** > **Updates** has **Check for
updates** (looks now) and **Check for updates automatically** (on by default; the owner or a family
member can turn it off). A failed check says so quietly and changes nothing.

- **Windows:** when there is an update, a notice above every screen and the tray icon's tooltip say
  so. **See update** (or **Settings** > **Updates**) then **Install**. The app asks first; on
  **Install and close** it downloads the MSI, checks it against `latest.json`, closes, and the
  installer runs. They then open Meal Planner from the Start menu. If they open a recipe editor or
  an import review while it downloads, it doesn't close on them: the notice says the update is ready
  and to save or leave what they're editing, then **Install**. If Install closes the app and nothing
  installs, they open it again and download the installer from the releases page. A folder path
  with a comma or quotation mark can't take updates, and Settings says so.
- **Windows, installed by hand:** someone who runs a downloaded MSI themselves while Meal Planner is
  still running in the tray sees that copy notice it was replaced and offer **Restart now**. **Install
  and close** never needs this, because it closes the app first.
- **Android:** the same check when the app opens. **Install** downloads and checks the APK, then
  Android's own install screen takes over. The first time, Android asks them to allow Meal Planner
  to install unknown apps (**Open settings**, **Allow from this source**, back, **Install**
  again). An update that finished downloading while they were in another app waits until they come
  back. Android refuses an APK not signed with the same Android key as the installed app.

Installs made before the first release with the update check (Windows 1.0.0, Android 1.0.0) have
no check: update those by hand once.

## The release list

`latest.json` (format 1) as the release tool writes it:

    {
        "format": 1,
        "tag": "release-2",
        "desktop": { "version": "1.0.1", "file": "MealPlanner-1.0.1.msi", "size": 94371840, "sha256": "<64 hex>" },
        "android": { "versionName": "1.0.1", "versionCode": 2, "file": "MealPlanner-1.0.1.apk", "size": 12582912, "sha256": "<64 hex>" }
    }

`latest.json.sig` is the `SHA256withECDSA` signature of those exact bytes, in base64, on one line.
The apps fetch both from `https://github.com/VibeForgeDev/meal-planner/releases/latest/download/`, and
the files from the release named by `tag`. They contact only `github.com` and GitHub's download
hosts (`release-assets.githubusercontent.com`, `objects.githubusercontent.com`), over https.
