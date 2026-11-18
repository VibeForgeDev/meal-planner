package com.naeblis11.mealplanner.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** A file the user picked. [name] ("box.mmf") and [open] are called off the main thread. */
class PickedFile(val name: () -> String, val open: () -> InputStream)

/** Where the user chose to save a file; [delete] removes it when the write failed. */
class SaveTarget(val open: () -> OutputStream, val delete: () -> Unit)

/** The system file picker, for importing. Launches through [guard], so a double tap opens one. */
@Composable
expect fun rememberOpenFile(guard: LaunchGuard, onPicked: (PickedFile) -> Unit): () -> Unit

/** The system file picker for one kind of file: [title] on the dialog and, on Windows, [filter] in its name box ("*.json"). */
@Composable
expect fun rememberChooseFile(guard: LaunchGuard, title: String, filter: String, onPicked: (PickedFile) -> Unit): () -> Unit

/** The system photo picker. */
@Composable
expect fun rememberChoosePhoto(guard: LaunchGuard, onPicked: (open: () -> InputStream) -> Unit): () -> Unit

/** The camera, writing into [cameraDir]; null where there is no camera to launch (the desktop). */
@Composable
expect fun rememberTakePhoto(
    guard: LaunchGuard,
    cameraDir: File,
    onTaken: (open: () -> InputStream) -> Unit,
    onUnavailable: () -> Unit,
): (() -> Unit)?

/** The system "save as" dialog; the returned function takes the suggested file name. */
@Composable
expect fun rememberSaveFile(guard: LaunchGuard, mimeType: String, onChosen: (SaveTarget) -> Unit): (suggestedName: String) -> Unit

/** Asks for the calendar permissions; [onResult] gets true when all were granted (always true where there are none). */
@Composable
expect fun rememberCalendarPermissionRequest(onResult: (Boolean) -> Unit): () -> Unit

/** Opens this app's page in the system settings; [onReturn] runs when the user is back. */
@Composable
expect fun rememberAppSettingsOpener(onReturn: () -> Unit): () -> Unit

/**
 * Google's code scanner (LAN sync 2c). Google Play services runs it, so the app needs no camera permission. [onScanned]
 * gets the code's text; [onUnavailable] runs when this phone can't scan. It launches through [guard]. Null where there
 * is no scanner (the desktop).
 */
@Composable
expect fun rememberCodeScanner(guard: LaunchGuard, onScanned: (String) -> Unit, onUnavailable: () -> Unit): (() -> Unit)?

/** Opens the system's Wi-Fi settings (Join a PC); [onReturn] runs when the user is back. */
@Composable
expect fun rememberWifiSettingsOpener(onReturn: () -> Unit): () -> Unit

/** The system Back gesture or button, where the platform has one. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)

/** The app's version name, for Settings > About. */
@Composable
expect fun rememberAppVersion(): String

/** Decodes a photo file; null when it is missing or not an image. Blocking: call off the main thread. */
expect fun loadImageBitmap(file: File): ImageBitmap?

/** Decodes a picture's bytes (Kroger's product pictures); null when they are not an image. Blocking. */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?

/** Keeps the screen awake while this is in the composition (a recipe is read while cooking); nothing where screens don't sleep. */
@Composable
expect fun KeepScreenOn()

/** P5-R9: rows drag to reorder by a handle where there is a mouse (the desktop); the phone keeps Up and Down. */
expect val dragToReorder: Boolean
