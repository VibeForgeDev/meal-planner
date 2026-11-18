package com.naeblis11.mealplanner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.InputStream
import org.jetbrains.skia.Image

/** The system file dialog: a file, or null when the user cancels. [mode] is FileDialog.LOAD or SAVE; [fileOrFilter] pre-fills the name box. */
fun interface FileChooser {
    fun choose(mode: Int, title: String, fileOrFilter: String?): File?
}

/** A modal Windows file dialog owned by [owner] (the main window), so it opens over the app and blocks it, never behind it. */
class AwtFileChooser(private val owner: Frame?) : FileChooser {
    override fun choose(mode: Int, title: String, fileOrFilter: String?): File? {
        val dialog = FileDialog(owner, title, mode)
        if (fileOrFilter != null) dialog.file = fileOrFilter
        dialog.isVisible = true
        val name = dialog.file ?: return null
        return File(dialog.directory, name)
    }
}

/** The file dialogs' chooser (P3-R5). Main provides one owned by its window; without that, a dialog has no owner. */
val LocalFileChooser: ProvidableCompositionLocal<FileChooser> = staticCompositionLocalOf { AwtFileChooser(null) }

@Composable
actual fun rememberOpenFile(guard: LaunchGuard, onPicked: (PickedFile) -> Unit): () -> Unit {
    val latest by rememberUpdatedState(onPicked)
    val chooser = LocalFileChooser.current
    return remember(guard, chooser) {
        {
            guard.launch {
                val file = try { chooser.choose(FileDialog.LOAD, "Import recipes", null) } finally { guard.done() }
                if (file != null) latest(PickedFile({ file.name }) { file.inputStream() })
            }
        }
    }
}

@Composable
actual fun rememberChooseFile(guard: LaunchGuard, title: String, filter: String, onPicked: (PickedFile) -> Unit): () -> Unit {
    val latest by rememberUpdatedState(onPicked)
    val chooser = LocalFileChooser.current
    return remember(guard, chooser, title, filter) {
        {
            guard.launch {
                // On Windows the file box doubles as the type filter.
                val file = try { chooser.choose(FileDialog.LOAD, title, filter) } finally { guard.done() }
                if (file != null) latest(PickedFile({ file.name }) { file.inputStream() })
            }
        }
    }
}

@Composable
actual fun rememberChoosePhoto(guard: LaunchGuard, onPicked: (open: () -> InputStream) -> Unit): () -> Unit {
    val latest by rememberUpdatedState(onPicked)
    val chooser = LocalFileChooser.current
    return remember(guard, chooser) {
        {
            guard.launch {
                // On Windows the file box doubles as the type filter.
                val file = try { chooser.choose(FileDialog.LOAD, "Choose a photo", "*.jpg;*.jpeg;*.png;*.webp;*.gif;*.bmp") } finally { guard.done() }
                if (file != null) latest { file.inputStream() }
            }
        }
    }
}

/** No camera to launch on the desktop: the screen hides "Take photo". */
@Composable
actual fun rememberTakePhoto(
    guard: LaunchGuard,
    cameraDir: File,
    onTaken: (open: () -> InputStream) -> Unit,
    onUnavailable: () -> Unit,
): (() -> Unit)? = null

@Composable
actual fun rememberSaveFile(guard: LaunchGuard, mimeType: String, onChosen: (SaveTarget) -> Unit): (suggestedName: String) -> Unit {
    val latest by rememberUpdatedState(onChosen)
    val chooser = LocalFileChooser.current
    return remember(guard, chooser) {
        { name ->
            guard.launch {
                val file = try { chooser.choose(FileDialog.SAVE, "Save backup", name) } finally { guard.done() }
                if (file != null) latest(SaveTarget(open = { file.outputStream() }, delete = { file.delete() }))
            }
        }
    }
}

/** The desktop has no permission prompts. */
@Composable
actual fun rememberCalendarPermissionRequest(onResult: (Boolean) -> Unit): () -> Unit {
    val latest by rememberUpdatedState(onResult)
    return remember { { latest(true) } }
}

@Composable
actual fun rememberAppSettingsOpener(onReturn: () -> Unit): () -> Unit {
    val latest by rememberUpdatedState(onReturn)
    return remember { { latest() } }
}

/** The desktop scans nothing: it is the PC that shows the code. */
@Composable
actual fun rememberCodeScanner(guard: LaunchGuard, onScanned: (String) -> Unit, onUnavailable: () -> Unit): (() -> Unit)? = null

/** The desktop has no Join a PC; there is nothing to open. */
@Composable
actual fun rememberWifiSettingsOpener(onReturn: () -> Unit): () -> Unit {
    val latest by rememberUpdatedState(onReturn)
    return remember { { latest() } }
}

/** No system Back on the desktop; screens offer their own Back/Cancel. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

@Composable
actual fun rememberAppVersion(): String = remember { System.getProperty("mealplanner.version") ?: "dev" }

actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? =
    try {
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    } catch (e: Exception) {
        null
    }

actual fun loadImageBitmap(file: File): ImageBitmap? =
    try {
        Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
    } catch (e: Exception) {
        null
    }

/** Nothing to do: the desktop doesn't sleep the display for an open app the way a phone does. */
@Composable
actual fun KeepScreenOn() = Unit

actual val dragToReorder: Boolean = true
