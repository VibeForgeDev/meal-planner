# Windows desktop app

The desktop app runs the same screens as the Android app, laid out for a PC
window, and lives in the system tray. Its recipe library is a folder of Open Recipe Format files.
While it runs it answers the Chrome extension and Alexa (through Home Assistant) on port 5000, and
sends the week's meals to a Google calendar.
It installs for your Windows account from an MSI (below). It does not yet sync phones; that is
planned as LAN sync (docs/superpowers/specs/2026-10-10-lan-sync-design.md).

## Install, update, uninstall

Meal Planner installs from an MSI file for your Windows account only. It needs no administrator
rights and asks for no folder. Double-click `MealPlanner-<version>.msi`.

- **"Windows protected your PC."** The MSI isn't code-signed, so SmartScreen may stop it. Choose
  **More info**, check the file name, then choose **Run anyway**.
- It installs into `%LOCALAPPDATA%\Meal-Planner` (with a hyphen; `%LOCALAPPDATA%\Meal Planner`, with a space,
  holds the app's database and log, your Alexa token and Google sign-in, and the installer never touches it), adds **Meal Planner** to the Start menu
  (in a Meal Planner folder) and puts a shortcut on the desktop. You can delete the desktop shortcut.
- The first time it runs it turns on **Start with Windows** (Settings), so from then on it starts in
  the tray when you sign in.

### The firewall prompt

On its first run, Windows Defender Firewall may ask whether to allow Meal Planner on your networks.
Allow **Private networks** only: untick Public, then choose **Allow access**. The installer adds no
firewall rule itself, because an install with no administrator rights can't.

If you dismissed the prompt, you can add the rules the app needs, on the Private profile only, for the installed program only:
- inbound TCP 5000, for the Chrome extension's and Home Assistant's calls;
- inbound TCP 5001, for the phones and PCs that sync with this one;
- UDP 5353, so other Meal Planner PCs can find this one.

This is optional. Open PowerShell as administrator, signed in as yourself, and run:

    $exe = "$env:LOCALAPPDATA\Meal-Planner\Meal Planner.exe"; New-NetFirewallRule -DisplayName 'Meal Planner (TCP 5000)' -Direction Inbound -Action Allow -Profile Private -Protocol TCP -LocalPort 5000 -Program $exe | Out-Null; New-NetFirewallRule -DisplayName 'Meal Planner sync (TCP 5001)' -Direction Inbound -Action Allow -Profile Private -Protocol TCP -LocalPort 5001 -Program $exe | Out-Null; New-NetFirewallRule -DisplayName 'Meal Planner (mDNS UDP 5353)' -Direction Inbound -Action Allow -Profile Private -Protocol UDP -LocalPort 5353 -Program $exe | Out-Null

If you approve with another account's password, `$env:LOCALAPPDATA` is that account's: write the
path out instead, as `C:\Users\<you>\AppData\Local\Meal-Planner\Meal Planner.exe`. To
remove the rules again:

    Remove-NetFirewallRule -DisplayName 'Meal Planner (TCP 5000)', 'Meal Planner sync (TCP 5001)', 'Meal Planner (mDNS UDP 5353)'

### Controlled folder access

If **Ransomware protection > Controlled folder access** is on in Windows Security, Windows stops
apps it doesn't know from saving to Documents, and Meal Planner is one of them until you allow it.
The app still starts, because only your recipes and photos are in Documents (see **Where it keeps
things**), and it still shows the recipes already there, but it can't save. Meal Planner never
writes to Documents just to test it, so you see this only when it really needed to save there (its
first run's folders, a recipe, a photo, an import). Then a banner above every screen, and the top
of Settings, say, for as long as the app runs:

"Windows blocked Meal Planner from saving to Documents\Meal Planner (Controlled folder access). To
fix it: Windows Security > Virus & threat protection > Ransomware protection > Allow an app through
Controlled folder access > add Meal Planner."

Saving a recipe says the same. Windows Security also shows a notification ("Unauthorized changes
blocked") naming `Meal Planner.exe`. Windows can report this block to the app either as "access
denied" or as "path not found"; Meal Planner treats a "not found" as the block when the folder it was
saving into (or making its folder in) is there, as `Documents` is, and as an ordinary failure ("Windows
couldn't find part of the path") when that folder is really missing, such as an offline drive.

The quickest fix is the button under the banner (and in Settings, under **Folders**): **Allow Meal
Planner (asks for admin)**. Windows asks for permission once (the usual "Do you want to allow this
app to make changes" prompt, for Windows PowerShell); say Yes, and Meal Planner adds itself to the
allowed apps, checks the folder again, and the banner goes. If you say No, it says "Not allowed.
Nothing changed." If Windows still blocks the folder afterwards, restart Meal Planner and try again.
Only the installed app shows the button, and only for this block, and only when it runs from exactly
`%LOCALAPPDATA%\Meal-Planner\Meal Planner.exe` with nothing but letters, digits, spaces and
`\ : . _ ( ) -` in that path (otherwise use the manual steps below). Allowing it trusts whatever
program is at that path from then on, not only this copy of Meal Planner. On a work- or
school-managed PC the organisation may control this setting, so the button (and the manual steps)
may not help; ask whoever manages the PC.

If the button can't do it, it says so ("Couldn't change the setting (code N)...", or the Windows
error it got); allow the app
yourself instead, following the banner's steps: choose **Add an allowed app**, then **Browse all
apps**, and pick `%LOCALAPPDATA%\Meal-Planner\Meal Planner.exe`. Or, from PowerShell opened as
administrator, signed in as yourself:

    Add-MpPreference -ControlledFolderAccessAllowedApplications "$env:LOCALAPPDATA\Meal-Planner\Meal Planner.exe"

Then choose **Check again** at the top of Settings: it tries one save, and the banner goes if that
works. If the block stopped Meal Planner's very first run, Check again (or the next start) also makes
the `recipes` and `recipe-images` folders it couldn't make then. The banner otherwise stays until Meal Planner quits; the next time it starts it shows no
banner until a save is refused again. As for the firewall, an approval with another account's
password makes `$env:LOCALAPPDATA` that account's: write the path out instead.

A save that fails for any other reason says why instead, for example "Couldn't save to
Documents\Meal Planner: There is not enough space on the disk". A recipe file that is open in
another program, being synced by OneDrive, or read-only says "Couldn't save to Documents\Meal
Planner: the file may be open or read-only".

If Meal Planner ever fails to start at all, it says so in a small window naming its log,
`%LOCALAPPDATA%\Meal Planner\.cache\meal-planner.log`; what went wrong is at the end of that file.

### Update

Meal Planner looks for a newer version on GitHub when it starts, at most once a day. When it finds
one, a notice above every screen says so, and so does the tray icon's tooltip if it started in the
tray. Nothing installs by itself: choose **See update** (or **Settings** > **Updates**), then
**Install**. Meal Planner asks first; choose **Install and close** and it downloads the installer
into `%LOCALAPPDATA%\Meal Planner\updates`, checks it against the signed release list, closes, and
the installer runs. Follow the installer, then open Meal Planner from the Start menu. A download
that doesn't match the list is deleted and nothing is installed.

**Settings** > **Updates** also has **Check for updates automatically** (on by default; off, it
never looks at start) and **Check for updates**, which looks now. A failed check says so quietly
and changes nothing. The check contacts only github.com and GitHub's download hosts, over https.
Only the installed app checks; the preview (`:desktopApp:run`) never does.

If the path to the Meal Planner folder has a comma or a quotation mark in it, Meal Planner can't
hand the installer over, and **Settings** > **Updates** says so; update by hand as below instead.

To update by hand: quit Meal Planner (tray icon > **Quit**), then run the newer MSI. Either way the
upgrade replaces the installed program and keeps everything else: your library, your settings, the
Alexa token and the Google sign-in. Windows only installs a newer version over an older one.
Releases are made as docs/RELEASING.md says.

If Meal Planner was still running in the tray while you installed (or uninstalled and installed
again), the copy in the tray is the old version. The next time you open it (from the tray or the
Start menu) it says "Meal Planner has been updated. Restart to use the new version." **Restart
now** closes it as Quit does (asking first about an unsaved edit) and opens the new version. If
there is no Restart now button, quit Meal Planner from the tray and open it again. If you
uninstalled it without installing it again yet, it says "Meal Planner was removed or is being
updated" instead: quit it from the tray, then open it again once it is installed.

If **Install and close** closes Meal Planner and nothing installs, open Meal Planner again and download the
installer from the releases page (https://github.com/VibeForgeDev/meal-planner/releases), then run it
as above.

### Uninstall

First turn **Start with Windows** off in Settings, then quit the app. Uninstalling doesn't remove
that entry, and Windows would try to start a missing program at every sign-in. Then uninstall from
Settings > Apps > Installed apps > Meal Planner.

If you already uninstalled, remove the entry from PowerShell:

    Remove-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name 'Meal Planner' -ErrorAction SilentlyContinue

Uninstalling removes `%LOCALAPPDATA%\Meal-Planner` and the shortcuts only. It never touches:
- `Documents\Meal Planner`: your recipes and photos;
- `%LOCALAPPDATA%\Meal Planner`: the app's database (calendar, pantry, shopping list) and log, the
  Alexa token and the Google sign-in;
- the settings in the registry.

A later install picks up where you left off. Delete those yourself if you want them gone.

## Where it keeps things

Only your library is in Documents: the recipe files and their photos. The app's own data (its
database, log and scratch files) is in `%LOCALAPPDATA%\Meal Planner`, beside the Alexa token and the
Google sign-in, where Windows' Controlled folder access never stops it. Settings shows both under
**Folders** ("Your recipes: ..." and "App data: ..."), each with **Open folder**.

| What | Where |
|---|---|
| Recipes, the source of truth | `Documents\Meal Planner\recipes\*.yaml` (only `.yaml`; `.yml` is ignored) |
| Photos | `Documents\Meal Planner\recipe-images\` (`<uuid>.jpg` and `<uuid>_thumb.jpg`) |
| The app's database: an index of the recipes it can rebuild, plus the calendar, pantry and shopping list | `%LOCALAPPDATA%\Meal Planner\mealplanner-app.db` |
| Scratch files (import staging, camera captures) and the lock that keeps a second copy from starting; safe to empty while the app is closed | `%LOCALAPPDATA%\Meal Planner\.cache\` |
| The log: what the app reports (the folder watcher, closing, Start with Windows, a start that failed); rolled at 1 MB, with the two before it kept as `meal-planner.log.1` and `.2` | `%LOCALAPPDATA%\Meal Planner\.cache\meal-planner.log` |
| Settings | the registry, under `HKEY_CURRENT_USER\Software\JavaSoft\Prefs\com\naeblis11\mealplanner` |
| Start with Windows | `HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run`, value `Meal Planner` (the installed app only) |
| The installed program (replaced by an update, removed by uninstalling) | `%LOCALAPPDATA%\Meal-Planner\` |
| The Alexa voice token (`MEAL_PLANNER_API_TOKEN`); Settings can create it, and a line edited in by hand works too | `%LOCALAPPDATA%\Meal Planner\.env` (or `MEAL_PLANNER_HOME\.env`); `:desktopApp:run` keeps its own in `apps\desktopApp\build\preview-data\.env` |
| A Google OAuth client used instead of the built-in one (`MEAL_PLANNER_GCAL_CLIENT_ID`, `MEAL_PLANNER_GCAL_CLIENT_SECRET`): edited in by hand on a build with a built-in client (restart the app), or written by Settings' **Choose the client file** on a build without one; most PCs have none here | the same `.env` |
| The Google sign-in (a refresh token), sealed with Windows DPAPI so only your Windows account on this PC can read it | `google-token.dat` beside that `.env` |
| A downloaded update: the MSI, checked against the signed release list before it starts; deleted a day later | `%LOCALAPPDATA%\Meal Planner\updates\` |
| The update check's switch and when it last looked | the registry, with the other settings |
| The chosen Google calendar, and the account's address | the registry, with the other settings |

The library is found in this order: the `mealplanner.dataDir` Java property, the
`MEAL_PLANNER_DATA_DIR` environment variable, then `Meal Planner` in your Documents folder (a
Documents folder redirected into OneDrive is honoured). The app data is the `.env`'s folder:
`MEAL_PLANNER_HOME`, else `%LOCALAPPDATA%\Meal Planner`; `MEAL_PLANNER_DATA_DIR` doesn't move it.
The `mealplanner.dataDir` property (the preview, the tests and the smoke check) puts both in that
one folder. A first run (no `mealplanner-app.db` in the app data yet) makes the database there and
the library's `recipes` and `recipe-images` folders in Documents, empty. After that, a `recipes`
folder that has gone missing (deleted, or on an offline drive) is not made again, not even by a
save: the Recipes screen says "The recipe folder is missing or can't be read", the recipes already
indexed stay, and nothing can be saved: saving a recipe already in the app says "soup.yaml is
missing from the recipe folder, so this recipe can't be saved. Put the file back, or see Needs
attention.", and only saving a new recipe says "The recipe folder is missing". The app looks for the
folder every 30 seconds (which is also how it notices, within that time, a folder renamed, moved or
deleted to the Recycle Bin while it runs): once it is back and has stopped changing, it is indexed
and watched again, within about a minute.

You can edit, add or delete recipe files in Explorer or any text editor while the app runs. A new or
changed file is picked up within a second. A file you delete leaves the app a few seconds later
(along with its planned meals), once the app has looked twice and found it still gone; while it
waits, the recipe can't be saved.

When many recipe files go missing at once (more than half of them and more than three, or all of
them, as when the library is moved out in Explorer or a folder comes back before its files), the
app asks first instead: nothing is removed, the Recipes screen says "Some recipe files are missing
from the folder", and Needs attention says how many, for example "5 recipe files are missing from
the recipe folder. If you moved them, put them back. If you deleted them on purpose, choose Remove
them from the app." Put the files back and the message goes. **Remove them from the app** first
asks, saying how many recipes and how many planned meals would go ("Remove 5 recipes from the
app? 2 planned meals use them and will be removed too."); **Keep them** changes nothing, and
**Remove** removes the recipes it counted whose files are still missing, with their planned meals.
A file that comes back while it asks stays, and one that goes missing while it asks is not removed
by it.

The database remembers which recipe folder it was made from. If Meal Planner finds a different one
(the library moved, or `MEAL_PLANNER_DATA_DIR` now names another folder), it removes nothing at all:
new and changed files are taken in as usual, but a recipe whose file isn't in the new folder stays,
the Recipes screen says "The recipe folder has changed; nothing is removed until you confirm", and
Needs attention says "The recipe folder changed from <old> to <new>. Nothing is removed until you
confirm." While it says so, a file in the new folder is matched to a recipe by its `recipe_uuid`
only, so a file that happens to share an old recipe's name comes in as a new recipe. **Use the new
folder (removes nothing)** makes it the folder from then on and removes nothing: the recipes missing
from it wait under **Remove them from the app**, which asks first, with how many recipes and planned
meals would go, however few they are. **Point back** says how to put the old folder back instead.
A recipe waiting there can't be saved or rated ("This recipe is waiting under Needs attention...").
It can still be deleted, through the usual dialog that says how many planned meals go with it. The
app never saves a recipe over a file that now holds another recipe, and never deletes one there
("This recipe's file now holds another recipe; nothing was saved.").

A file with no `recipe_uuid` gets one added as a single line at the
top, and nothing else in it changes. Saving in the app rewrites that recipe's file. If the file
changed outside the app while you had the recipe open in Edit, Save refuses rather than overwrite
your hand edit: close the form and open it again to see the change. Deleting a recipe in the app
moves its YAML file to the Recycle Bin, but its photo is deleted permanently (unless another recipe
uses the same photo).

### Backups

Back up both folders. The recipes and photos are in `Documents\Meal Planner` (`recipes` and
`recipe-images`), so OneDrive or File History already copies them if it copies your Documents. The
meal calendar, the pantry and the shopping list are in the database,
`%LOCALAPPDATA%\Meal Planner\mealplanner-app.db`, which those don't copy. With Meal Planner closed
(**Quit** in the tray), this copies the database into your Documents folder, dated:

    Copy-Item "$env:LOCALAPPDATA\Meal Planner\mealplanner-app.db" "$([Environment]::GetFolderPath('MyDocuments'))\mealplanner-app-$(Get-Date -Format yyyy-MM-dd).db"

If Controlled folder access refuses PowerShell that copy, name another folder in place of the second
path. Settings' **Export backup** zips the recipes and photos only. Keep `.env` and `google-token.dat`
out of shared backups: the token in `.env` answers Alexa, and the sign-in only works on this PC
anyway.

## In the tray

Meal Planner keeps running when you close its window: it is the household's hub, so it stays in the
system tray (the first time, Windows says so in a notification). Double-click the tray icon, or
right-click it and choose **Open Meal Planner**, to bring the window back; **Quit** there closes the
app for real. If a recipe edit has unsaved changes, Quit first brings the window back and asks
"Discard changes?": **Keep editing** cancels the quit, **Discard** quits. Quit asks too while an
import is open on its review ("Leave this import?": **Keep reviewing** or **Leave**), as do the rail
and the tabs; recipes still waiting to be reviewed are dropped when the app quits. Signing out of
Windows or shutting it down usually closes it the same careful way, but can't ask, so unsaved edits
are lost.
Starting it again while it runs brings the open window forward instead of starting a second copy
(there is one copy per app data folder). Started with `--minimized`, as Windows starts it at
sign-in, it begins in the tray with no window.

Where Windows offers no system tray, there is nothing to hide in: closing the window quits (asking
about unsaved changes the same way), and `--minimized` opens the window as usual.

**Start with Windows** (in Settings) starts it in the tray when you sign in. It is on unless you
turn it off: every time the installed app starts, it writes the per-user entry named "Meal
Planner" under `HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run`, pointing it at
the launcher it was started from (so it follows the app if an update moves it). Turn it off in
Settings and it stays off. The preview from `:desktopApp:run` never writes that entry, so its
switch is greyed out.

## Wide and narrow windows

At 840 dp wide and 480 dp high and up (a maximised window on most PCs) the app is laid out as
DESIGN.md describes: a rail down the left (Recipes, Calendar, Pantry, Shopping, Settings);
Recipes as a list beside the open recipe, which you also edit there; the Calendar as the whole week
in seven columns of breakfast, lunch and dinner; the shopping list's aisles in two or three
columns; and the pantry in two. A narrower or shorter window gets the phone layout, with the tabs
along the bottom; a recipe or an edit open beside the list then fills the window, unsaved changes
and all, until you go back. An Android tablet that size gets the wide layout too; phones never do,
even on their side.

## Recipe page and editors

These are shared with the Android app:
- **Recipe page.** An ingredient already in your pantry shows **In pantry**; any other shows
  **+ Pantry**, which adds it to the pantry in one click. **Assign to calendar** plans the recipe at
  the servings the page is scaled to. **More > Category** edits the category in place. On the recipe
  list, small stars only show a recipe's rating; it is changed on the recipe page, where the current
  star clears it.
- **Instructions.** **Split here** cuts a step in two at the cursor, as does Ctrl+Enter (Cmd+Enter
  on a Mac) in the step's field.
- **Reordering.** On the PC, each ingredient and step row has a drag handle, used with the primary
  mouse button: drag the row to move it, and press Escape to cancel and leave the order as it was.
  Up and Down stay on every row, and are all the phone shows.

## Needs attention

A recipe file the app can't take as it is stays out of the library and appears under **Needs
attention**: a banner on the Recipes screen ("2 recipe files need attention") opens the list. A file
lands there when it isn't valid YAML, lacks `recipe_name`, `ingredients` or `steps`, isn't UTF-8, or
has the same `recipe_uuid` as another file. For a duplicate, **Assign new ID** gives the file a new
`recipe_uuid` (one line changed) and both recipes are kept. Everything else is fixed in the file:
**Open recipe folder** opens it in Explorer, and the app notices the save. Amounts are never listed
here: an ingredient with no amount ("salt and pepper") is normal, and one the app can't read
(`a pinch`) is kept as written and highlighted in **Edit** and in an import's review.

## Bringing in recipe files

Recipes you already have as Open Recipe Format files move by copying them. With Meal Planner closed
(**Quit** in the tray), copy the `.yaml` files into `Documents\Meal Planner\recipes\` and, if you
have their photos, those into `Documents\Meal Planner\recipe-images\`. Then start Meal Planner: it
indexes the files it finds. A file whose name is already in the folder replaces that recipe, so copy
into an empty folder or check the names first. If Meal Planner has never run on this PC, this
PowerShell line makes the two folders first (it changes nothing when they are there);
`GetFolderPath('MyDocuments')` finds your Documents folder even when it is redirected into OneDrive:

    New-Item -ItemType Directory -Force "$([Environment]::GetFolderPath('MyDocuments'))\Meal Planner\recipes", "$([Environment]::GetFolderPath('MyDocuments'))\Meal Planner\recipe-images" | Out-Null

Only the recipe files come over: the meal calendar, the pantry and the shopping list start empty,
and the Google sign-in is made here (see **Google Calendar** below). A single file, or a Meal Master
(`.mmf`) file, can also come in through the app's own import, which shows a review first.

## Chrome extension and Alexa

While it runs, the app answers on port 5000 with the requests and replies the Chrome extension
and Home Assistant expect. Home Assistant needs only its three addresses pointed at this PC (see
**Alexa** below).

**Chrome extension.** Its address stays `http://127.0.0.1:5000`, the extension's default; if you
changed it in the extension's options, set it back. Send a recipe and Meal Planner comes forward
with it open on the import review, the same review as a file import: a recipe already in your
library is a duplicate, skipped unless you change its title, and nothing is saved until you choose
Confirm. The extension also opens a browser tab saying the recipe is in Meal Planner; you can close
it. A recipe sent while you are reviewing another import waits, and opens when you finish (up to 20
can wait; a 21st is refused with "Meal Planner already has recipes waiting for review", so review
those and send it again). One sent while you are editing a recipe with unsaved changes waits too:
your edit is left alone, and the recipe opens once you save or cancel it. While one waits, the window
says "A recipe from Chrome is waiting." above every screen. A Quit that leaves a review doesn't open
the next one; recipes still waiting are dropped when the app quits.

The photo is downloaded from the recipe's page: a JPEG, PNG, WebP or GIF of up to 10 MB, within 10
seconds, through at most 3 redirects, and only from an `http` or `https` address. One of more than
20,000 pixels on a side or 50 million in all is refused, and a large one is shrunk like any photo
you add. If the photo can't be had, the recipe comes in without it and the extension says "Could
not download photo".

Only the extension, in a browser on this PC, can send recipes; a web page can't. The app answers
`403` to a caller on another device, and to any request whose `Origin` is not the extension's
(`chrome-extension://...`), including a page open in your own browser on this PC. A request with no
`Origin` at all, such as `curl`, is accepted. The recipe must be sent as JSON, with
`Content-Type: application/json` as the extension sends it (with `curl`, add
`-H "Content-Type: application/json"`); anything else is answered `415`. A caller on another device
is told to send from a browser on this PC, with the extension set to `http://127.0.0.1:5000`. A
recipe that is too big or too long is refused with a message the extension shows:

- a request over 5 MB: "That recipe is too large to send.";
- a page address over 8 KB (8192 characters): "That page's address is too long. Remove the part
  after ? and send it again.";
- a yield or author over 2000 characters: "That recipe has a field that is too long to import.";
- a title, ingredient line or step over 2000 characters, or more than 500 ingredient lines or
  steps, is answered as a recipe with no name, ingredients or steps: "Missing name, ingredients, or
  steps.".

**Alexa.** Home Assistant calls `/api/voice/shopping-list`, `/api/voice/pantry` and
`/api/voice/meal` with the token in its `secrets.yaml`. The token is the app's own: open Settings
and choose **Create a token** under "Chrome extension and Alexa". It shows
a line, `meal_planner_auth: "Bearer ..."`, once, with a **Copy** button that copies the whole line:
put it in Home Assistant's `secrets.yaml` and restart Home Assistant. Choose **Done** when you have
it; Done also clears the clipboard if it still holds the line. Leaving Settings while the line is
showing drops it from the screen for good (the clipboard keeps it, so you can still paste it). If
you left Settings while the token was being made, it is waiting for you and shows when you come
back. Once it is gone you can't see it again: choose **Make a new token** instead. That replaces the
token in use, so Home Assistant stops working until you paste the new line into its `secrets.yaml`
and restart it; Settings says so beside the button.

Settings keeps the token as `MEAL_PLANNER_API_TOKEN` in `%LOCALAPPDATA%\Meal Planner\.env`, and a
line edited in there by hand works too (restart the app). It must be 16 characters or more
(Settings makes 64); a shorter one is ignored, the app says so in its log, and Alexa is treated as
not set up. A token with quotes around it (`MEAL_PLANNER_API_TOKEN="..."`) is read without them.

Home Assistant's three `rest_command` URLs in `alexa/home-assistant.yaml` must point at this PC:
its `.local` name (`http://<pc-name>.local:5000/...`) or its LAN address, with port 5000. The
shipped file names `your-pc-name.local`, a placeholder, so change the host in all three. Nothing else in Home Assistant changes: the secret, the intents
and the payloads stay as they are. `alexa/SETUP.md` walks through it.

The app listens on the home network only while a token is set up (Settings then says "Listening on
port 5000 on your home network"); without one it listens on this PC alone. The first time it
listens on the network, Windows may ask whether to allow Meal Planner: allow it on private networks
only (see "The firewall prompt" above). If you edit the `.env` by hand, restart the app.

**Port 5000 in use.** Before it listens, the app checks port 5000 on every interface. If anything
holds it, whether on `0.0.0.0` or on `127.0.0.1` only, the window shows "Port 5000 is in use; the
Chrome extension and Alexa won't reach Meal Planner. Is another program using it?", the tray says
so once, and Settings shows it too.
Everything else works. To see what holds the port, run `netstat -ano | findstr :5000` in PowerShell
and look up the last number in Task Manager's Details tab (the PID column), then stop that program:
the app tries the port again every minute and takes it on its own.

## Google Calendar

Settings, under "Google Calendar", sends a week's meals to one of your Google calendars. Choose
**Sign in with Google**: your browser opens on Google's page, where you allow Meal Planner. Settings
then lists the account's calendars you can write to: choose the one to send meals to (**Choose
another calendar** changes it later). The Calendar's **Send this week to Google Calendar** then
sends the week shown, and only ever changes the meals it sent. Choosing another calendar leaves the
events already sent to the old one where they are; choose the old one again and its events are
updated in place, not added a second time.

The sign-in is kept on this PC only, sealed with Windows' own encryption in `google-token.dat`
beside the `.env` in `%LOCALAPPDATA%\Meal Planner\`. **Sign out** forgets it here without telling
Google, and forgets the chosen calendar too (the next account may not have it); **Revoke access at Google**, after asking, also tells Google to stop honouring it, which
stops calendar sending from any other copy that uses the same Google client. Your
browser gets Google's answer on a one-time address on this PC (127.0.0.1), so nothing is opened to
the network; if no browser opens, Settings shows the address to open by hand. Meal Planner asks only
to write events and to list your calendars.

**What a send does.** Breakfast goes at 7am, lunch at noon and dinner at 6pm, an hour each, marked
*free*, and each event's description has the servings and the ingredients (no link back to the
recipe). It only ever writes:
- meals added since the last send become events, changed ones are updated, and removed ones take
  their event with them;
- unchanged meals are not sent again, so pressing it twice does nothing the second time;
- only events it recorded, or that carry its own ids, are ever changed; other weeks, and everything
  else on the calendar, are never touched or read.

Each meal's event id comes from your household, the day and the slot, so a send that finds the event
already there (its record lost, say) updates it instead of adding a second. An event you delete by
hand comes back the next time that meal changes. The household's id is kept in `mealplanner-app.db`;
phase 2, which lets PCs pair, will share it. Until then two PCs running Meal Planner are two
households; the app finds the other PC on the network and only the older household's PC sends (see
**Another Meal Planner PC on the network**).

**What it says when it can't send**:
- "Sign in to Google in Settings first.";
- "Sign in to Google again in Settings." (access was removed at Google, or a sign-in made while the
  consent screen was in Testing is over 7 days old);
- "The Google calendar you chose can't be found any more. Choose one again in Settings.";
- "Couldn't reach Google, so the week wasn't sent. ...".

A meal Google refuses is listed ("1 meal could not be sent: Dinner on Monday, Sep 28. Send the week
again to try it again.") and the rest are sent. The app talks to Google only over https, to
`accounts.google.com`, `oauth2.googleapis.com` and `www.googleapis.com`, and gives up on a request
after 15 seconds. The phone's "Send this week" is unchanged: it still writes to a calendar on the
phone.

A build made with the Google client (see **Building with your Google client**) has it built in, so
that is all a new PC needs, and Settings has no button for choosing a client file. To use another
client on such a PC, put it in the `.env` by hand as `MEAL_PLANNER_GCAL_CLIENT_ID` and
`MEAL_PLANNER_GCAL_CLIENT_SECRET`, then restart the app. A build made without one asks for the client
file first: Settings says how to make an OAuth client of type Desktop app in the Google Cloud
console, and **Choose the client file** keeps it in those same two lines. A client in those two
lines, however it got there, is used instead of the built-in one, and Settings shows its id.

## Another Meal Planner PC on the network

Until phase 2 lets PCs pair, two PCs running Meal Planner are two separate households, each with its
own household id. If both sent the same week to one Google calendar, every meal would be there twice.
So each PC says on the home network that it is running (mDNS, service `_mealplanner._tcp`, UDP port
5353), and looks for the others. It announces after its startup sync and looks for about five seconds,
then looks again whenever you open Settings and just before each **Send this week** to Google
Calendar, and never otherwise. What a PC announces is its PC name,
a hash of the household id (never the id), when the household was created, a random id for this
install and the app's version. What another PC announces is never written to the log.

It announces only on a home network: never on Hyper-V, WSL, Docker, VirtualBox or VMware adapters, or
on VPN adapters (Tailscale, WireGuard, ZeroTier, TAP-Windows, Wintun). While no home network is found
(a laptop that started before its Wi-Fi was up), it tries again every minute and whenever you open
Settings; once the announce works it stops trying.

When it finds another PC, a line above every screen, repeated at the top of Settings, says which case
it is:
- **"Another PC on this network (NAME) runs Meal Planner. Until they can sync, only that PC sends to
  Google Calendar and answers Alexa."** The other PC's household is older, so this PC steps back:
  - Send this week sends nothing, and the Calendar says instead: "Another PC on this network (NAME)
    runs Meal Planner. Until they can sync, only that PC sends to Google Calendar." Signing in to
    Google still works.
  - The server listens on this PC only, so Home Assistant can't reach it for Alexa.
  - Settings' Alexa section says Alexa is answered on the other PC, and both **Create a token** and
    **Make a new token** are disabled. A token already set up is kept.
  - The Chrome extension, recipes, the calendar, pantry and shopping list work as before.
- **"Another PC on this network (NAME) runs Meal Planner as a separate household. This PC keeps
  Google Calendar and Alexa."** This PC's household is the older, and nothing changes here.
- **"Another copy of this household runs on NAME."** The other PC has this household's id (a copy
  restored from a backup, say). Nothing is switched off; it is a notice only.

The older household is the one created first; both PCs work it out the same way, so they always
agree. The newer PC steps back at its first look that finds the older one: at its start (the look ends
about five seconds after it starts, and until then it keeps Google and Alexa), when you open Settings,
or just before a send. So a newer PC that started before the older one came up still steps back
before it sends anything to Google; that send takes up to about six seconds longer while it looks,
and the Calendar shows "Sending to your calendar..." meanwhile. Until such a look, it still answers
Alexa. The older PC likewise learns of the newer one at its next look. When
the other PC is no longer seen the next time this one looks, the notice goes and this PC sends to
Google and answers Alexa again, if it has a token. A PC switched off without quitting Meal Planner
says no goodbye, so it may still be counted for up to about an hour, until the other PCs' mDNS
caches forget it.

Windows may ask whether to allow the app on your networks the first time it runs: allow **Private
networks** (see "The firewall prompt" above). Without that, other PCs can't see this one. If a PC isn't seen even so, check whether the
name or description of its home network adapter (Device Manager, Network adapters) contains one of the
words above, such as "Docker" or "VMware": the app skips such an adapter. Phase 2 replaces this
notice: a second PC will join the first one's household, and only the household's master PC will
answer Alexa.

## Phones and PCs

Settings > **Phones and PCs** pairs a phone (or another PC) with this one, so they can sync over your
home network.

### Pairing a device

1. On this PC, press **Pair a device**. For five minutes, Settings shows a QR code and a 6-digit code.
2. On the phone, open Settings > **Join a PC** and scan the code.
3. If the phone can't scan, type the 6-digit code instead.

Five wrong codes close the window; press **Pair a device** again for a new one.

### The sync port

Sync uses its own port, **TCP 5001**, encrypted with TLS 1.2 or 1.3. The certificate is one this PC makes for
itself the first time you pair:
- It is kept in the app data folder: `sync-tls.p12`, with its password sealed by Windows in
  `sync-tls.key`.
- The phone remembers it when it pairs, so it never syncs with another machine pretending to be this PC.

The port is open on your home network only while a device is paired or the pairing window is open. It
stays closed while another household's Meal Planner PC is the one answering (see "Another Meal Planner
PC on the network"). The first time it opens, Windows' firewall may ask: allow **Private networks** only
(see "The firewall prompt"). If another program holds port 5001, Settings says so and Meal Planner tries
again every minute.

### Paired devices

The list shows each paired device, when it last synced, and **Remove**. A removed device keeps its data
but stops syncing until you pair it again.

### If the certificate is remade

Meal Planner makes a new certificate when it can't read the old one: the app data folder was copied
from another PC or Windows user, or a file was damaged. Settings then says "This PC's sync certificate
was remade; pair your devices again." Every paired device now refuses to sync with this PC, as it
should. Pair each one again from Settings > Join a PC.

### A second PC

A second Meal Planner PC can join this one, so both have the same recipes, plan, pantry and shopping list.
The PC you join is the household's **main PC**; it keeps answering Alexa and pairing phones.

1. On the main PC, press **Pair a device** in Settings > Phones and PCs.
2. On the second PC, open Settings > **Join a household**, press **Type code** and type the 6-digit code.
   A first run of a new install also offers "Found NAME's Meal Planner on this network." with **Join**,
   or **Keep this PC separate**, which puts the offer away for good.
3. The first sync asks **Merge** (keep this PC's recipes and combine them with the main PC's) or
   **Replace** (make this PC a copy of the main PC, after saving a backup; its recipe files go to the
   Recycle Bin).

After that the second PC syncs when you press **Sync** and once each time it starts. Its recipes are still
YAML files in its own `Documents\Meal Planner\recipes`. While it is joined:
- it doesn't answer Alexa (Settings says which PC does) and makes no token;
- it doesn't pair phones (pair them with the main PC);
- the Chrome extension works on it as before;
- Google Calendar is set up on each PC on its own; both send the same events, never duplicates.

**Leave** (Settings > Join a household) stops syncing. The PC keeps its data and becomes a household of
its own, as does a PC you **Remove** from the main PC's list.

If two PCs of one household both act as the main PC (one restored from the other's backup, say), both say
"NAME is also set up as this household's main PC. Make one of them join the other in Settings." and stop
syncing with phones until one joins the other.

#### Making another PC the main PC

On the main PC, Settings > Phones and PCs lists each joined PC with **Make it the main PC**. After you
confirm, press **Sync** on that PC within ten minutes. It takes over: the paired phones and PCs, the sync
certificate (so phones keep their pairing and find it by themselves) and the Alexa token. The old main PC
then syncs with it as a joined PC, with its data. Both PCs need Meal Planner 1.4.0 or later.

Alexa: if Home Assistant called the old main PC, change its three `rest_command` URLs to the new PC's name
(see "Chrome extension and Alexa"). Google Calendar is set up on each PC on its own and is unaffected.

If the other PC doesn't finish (it was switched off mid-way, say), the main PC keeps its role after 30
seconds and nothing changes; a hand-over cut short on the other PC is finished when it starts again.

Checking it on real PCs (owner only, after the build):
1. On a second PC, install the build: Settings > Join a household finds the main PC, the typed code pairs,
   and the first sync asks Merge or Replace.
2. An edit on either PC reaches the other after Sync (or the second PC's restart).
3. The second PC's Alexa section names the main PC; Home Assistant still reaches the main PC only.
4. Leave on the second PC: it keeps its recipes, and neither PC shows the two-main-PCs notice.
5. Two main PCs of one household (the main PC's database restored on another PC): both show the notice.
6. Hand-over: on the main PC, Make it the main PC on the second PC, then Sync there: it says it is the main
   PC now; the phone syncs and shows the new PC's name; the old main PC syncs with the new one; Alexa works
   once Home Assistant's URLs point at the new PC.

### Scan rather than type

Scanning the QR code is the safer way to pair. In principle, a typed code can be guessed by another
machine on your home network that intercepts the phone's connection during the five minutes the window
is open. The QR code carries the certificate's fingerprint, so it can't be intercepted that way.

## Run it

`:desktopApp:run` always uses a throwaway preview folder, `apps\desktopApp\build\preview-data`, for
both its library and its app data, with its own settings, never your Documents folder or
`%LOCALAPPDATA%\Meal Planner`:

    $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\apps\gradlew.bat -p apps :desktopApp:run

The preview's server listens on port 5055, not 5000 (the `mealplanner.port` Java property, which
`run` sets), so it never takes the port of the installed app you may have running. To try
the Chrome extension against the preview, set the extension's address to `http://127.0.0.1:5055`
in its options, and back to `http://127.0.0.1:5000` afterwards.

The preview carries the Google client too when you build with one (below), and its Sign in with
Google then signs in to Google for real; its sign-in is kept in the preview folder.

The preview does not announce itself or look for other PCs unless you ask with
`"-Pmealplanner.peers=on"`; the installed app always does. A preview's household is older than any
later install, so a preview left running with peers on would make an installed app nearby stand
aside. To try two PCs on one, run a first preview with peers on, and a second beside it in another
terminal, on its own folder (`apps\desktopApp\build\preview-<name>`) and port. It never takes port
5000, and `run` refuses it. Quote every `-P` argument for PowerShell 5.1:

    .\apps\gradlew.bat -p apps :desktopApp:run "-Pmealplanner.peers=on"
    .\apps\gradlew.bat -p apps :desktopApp:run "-Pmealplanner.preview=peercheck" "-Pmealplanner.port=5056" "-Pmealplanner.peers=on"

Both previews share the preview's small settings (the tray message, the chosen calendar). Use a name
not used before for the second, so its household is the newer and it is the one that steps back.

## Building with your Google client

Every build carries the Google OAuth client named in `apps\google-client.properties`, so the people
who install it only sign in. You make the client once, in your own Google Cloud project:

1. In the Google Cloud console (console.cloud.google.com), choose or create a project and enable
   the Google Calendar API.
2. Set up the OAuth consent screen (External) and add every Google account that will sign in to
   its test users. While the screen is in Testing, Google ends a sign-in after 7 days; publish it to
   keep sign-ins.
3. Under Credentials, create an OAuth client ID of type **Desktop app** and download its JSON file.
   Keep it outside the repo.
4. Create `apps\google-client.properties` (git-ignored) with either the file's path:

       clientJson=C:\Users\<you>\Downloads\client_secret_<...>.json

   or the client's two values, from the console:

       clientId=<client id>
       clientSecret=<client secret>

   Write the path as it is, backslashes and all, without quotes; a relative one is from the
   folder the properties file is in (`apps`).

**Changing the client later signs every installed PC out of Google.** A sign-in belongs to the
client that made it, so after an update built with another client each PC shows Settings > Sign in
again, and only then sends. Change it only when you have to.

The build checks the client (both values present, printable, no spaces) and writes it into the app
as a `google-client.json` resource. A bad one fails the build, saying what is wrong but never the
value. For one build with another client, add the property to the Gradle command, for example:

    .\apps\gradlew.bat -p apps :desktopApp:run "-Pmealplanner.googleClient=C:\path\to\google-client.properties"

Keep the quotes in PowerShell: without them it splits the argument at the dot and Gradle never sees
the property. That file has the same form and must exist, and a relative path there is from `apps`,
not from the folder your shell is in. With neither the property nor the file, the
build still succeeds and has no client, and Settings asks for the client file. Google treats a
Desktop app's client secret as not confidential, since it ships inside the app, but keep both files
out of the repo all the same: the public export (`tools/export_public.py`, in the private working
repository, outside the public one) refuses a tracked
`google-client.properties` or `google-client.json`.

## Build the installer

The installer is a per-user MSI made by Compose Desktop's packaging (jpackage), with its own Java
runtime inside. Building it is a developer step, on a PC with the repo.

**Once: a full JDK.** Android Studio's JDK builds and tests everything else, but it has no
`jpackage`. Install Eclipse Temurin 25 (Windows asks for an administrator's approval):

    winget install --id EclipseAdoptium.Temurin.25.JDK -e

Then point `MEAL_PLANNER_PACKAGING_JDK` at its folder. In PowerShell, this finds the newest
Temurin 25 by its version number (as the smoke check does, so `jdk-25.0.10` beats `jdk-25.0.9`)
and keeps it for your account (open a new PowerShell window afterwards):

    $jdk = (Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-25*' | Where-Object { Test-Path (Join-Path $_.FullName 'bin\jpackage.exe') } | Sort-Object { $v = $_.Name -replace '^jdk-(\d+(\.\d+){0,3}).*$', '$1'; if ($v -notmatch '\.') { $v += '.0' }; [version]$v } | Select-Object -Last 1).FullName; [Environment]::SetEnvironmentVariable('MEAL_PLANNER_PACKAGING_JDK', $jdk, 'User')

**Run it again after every Temurin update.** An update installs into a new folder named after
its version and usually removes the old one, so the variable would point at a folder that is gone; the
build and the smoke check then stop and say so.

While it is set, `:desktopApp:run` uses that JDK too. Without it, a packaging build stops with
"Building the app image or the installer needs a full JDK with jpackage".

**WiX needs nothing.** The first packaging build downloads WiX Toolset 3.11.2 by itself (about
30 MB from github.com), so you install no WiX. **If WiX 4 or 5 is already installed**, with its
`wix.exe` on your PATH (WiX's own installer or `dotnet tool install --global wix`), jpackage
prefers it to the downloaded WiX 3.11: it tries WiX 4 and later first, and the build only puts
WiX 3.11 at the front of the PATH, it doesn't take the other off. The MSI is then built by WiX 4/5,
with its own tables and custom actions, and the inspection below may reject it until those are
reviewed. To build with WiX 3.11, take `wix.exe`'s folder off the PATH in that PowerShell window
first.

**Build it.** In PowerShell, from the repo root:

    $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\apps\gradlew.bat -p apps :desktopApp:packageMsi

The MSI is `apps\desktopApp\build\compose\binaries\main\msi\Meal Planner-<version>.msi`. Its version
is `mealplanner.desktopVersion` in `apps\gradle.properties`: raise it for every release, as
MAJOR.MINOR.BUILD (MAJOR and MINOR up to 255, BUILD up to 65535), because an MSI only replaces an
older version. Like every build, it carries the Google client from `apps\google-client.properties`
(above).

**The automatic inspection.** Every `packageMsi` ends with `inspectMsi`, which runs
`apps\desktopApp\inspect-msi.ps1`. It reads the MSI's tables without installing anything, and checks
the name, version and upgrade code, that the install is per user, the shortcuts, and above all the
install folder: `%LOCALAPPDATA%\Meal-Planner` (one level: WiX's ICE64 check rejects the two-level
per-user folder jpackage would make), never `%LOCALAPPDATA%\Meal Planner`. That is
the secrets folder, and uninstalling removes the install folder with everything in it. The
inspection fails closed: anything it can't show is safe, it rejects. Every table must be on its
allowlist and every custom action one of the few harmless kinds, so a table or an action it doesn't
know fails too. A rejected MSI is renamed `Meal Planner-<version>.msi.rejected` and the build
fails, and so is an MSI left behind by a `packageMsi` that failed; **never install or hand out a
`.rejected` file.** You can run the inspection on its own with
`powershell -NoProfile -ExecutionPolicy Bypass -File .\apps\desktopApp\inspect-msi.ps1`.

**Refused tasks.** `packageMsi` and `createDistributable` are the packaging tasks to use. The build
refuses:
- `runDistributable` (and `runReleaseDistributable`): it would run the packaged app as the installed
  one, on your real `Documents\Meal Planner` and port 5000, and write the Start with Windows entry.
  Use the smoke check below instead.
- The release variants (`packageReleaseMsi` and the like): they run ProGuard, which this app isn't
  set up for.
- `packageMsi` without `inspectMsi` (`-x inspectMsi`): every MSI is read back before it can be
  handed out.

`.\apps\gradlew.bat -p apps :desktopApp:checkPackagingConfig` checks the launcher's options and the
MSI's settings in seconds, without jpackage; `check` runs it too.

**The smoke check of the packaged app.** Run it before handing out an installer:

    powershell -NoProfile -ExecutionPolicy Bypass -File .\apps\desktopApp\smoke-packaged.ps1

It builds the app image (`createDistributable`, not the MSI) with the JDK in
`MEAL_PLANNER_PACKAGING_JDK` (it stops if that folder has no `jpackage`), or, when the variable
isn't set, the newest Temurin 25 it finds, and checks the launcher's options and the runtime's
modules. Then it
runs `Meal Planner.exe` once, for about half a minute, on throwaway folders under `%TEMP%` and a free
port; its window shows meanwhile. It checks that:
- `/healthz` answers on 127.0.0.1 and the window opens;
- the app's self-check passes: the server, WebP photos, JNA (DPAPI), TLS, the HTTP modules, JmDNS,
  and the launcher finding its own `Meal Planner.exe`;
- the log names no missing class;
- the app quits by itself, cleanly, with nothing left running.

Its hard rules:
- It never writes the Start with Windows entry, which it compares before and after.
- It never touches `Documents\Meal Planner` or `%LOCALAPPDATA%\Meal Planner`.
- It never uses port 5000.
- It never announces itself on the network.
- It never checks for updates (it never contacts GitHub).

Because it runs on its own data folder, the app uses the preview's Java settings node,
`HKCU\Software\JavaSoft\Prefs\com\naeblis11\mealplanner\preview`, which `:desktopApp:run` shares:
the run reads it, and creates it if it isn't there. The installed app's own settings, in the
`...\mealplanner` node above it, are neither read nor changed.

It passes these settings through `JAVA_TOOL_OPTIONS`: `-Dmealplanner.startWithWindows=off`,
`-Dmealplanner.peers=off`, `-Dmealplanner.updates=off`, `-Dmealplanner.selfCheck=on`, `-Dmealplanner.dataDir`,
`-Dmealplanner.port` and `-Djava.io.tmpdir`. The JVM reads that variable before the launcher's own
options, so it can add settings but never undo `installed=true`; the app therefore lets an explicit
`off` win over `installed=true`. If the image's launcher options aren't exactly the installed app's,
the script stops before it starts anything, and names the option.

`-SkipBuild` reuses the last app image, and `-Keep` keeps the temp folder. A failed run keeps it
anyway and says where.

## Test it

    $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\apps\gradlew.bat -p apps :shared:core:desktopTest :shared:data:desktopTest :desktopApp:test
