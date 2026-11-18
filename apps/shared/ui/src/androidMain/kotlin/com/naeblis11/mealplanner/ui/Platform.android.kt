package com.naeblis11.mealplanner.ui

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.FileProvider
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.naeblis11.mealplanner.calendar.CalendarPermissions
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

@Composable
actual fun rememberOpenFile(guard: LaunchGuard, onPicked: (PickedFile) -> Unit): () -> Unit {
    val context = LocalContext.current
    // The application's resolver: a lambda a ViewModel keeps must never hold the Activity.
    val resolver = remember(context) { context.applicationContext.contentResolver }
    val latest by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        guard.done()
        if (uri != null) {
            latest(PickedFile({ displayName(resolver, uri) }) { resolver.openInputStream(uri) ?: throw IOException("Could not open the file.") })
        }
    }
    return remember(guard, launcher) { { guard.launch { launcher.launch(arrayOf("*/*")) } } }
}

/** Android's document picker shows every file; the title and filter are the desktop's. */
@Composable
actual fun rememberChooseFile(guard: LaunchGuard, title: String, filter: String, onPicked: (PickedFile) -> Unit): () -> Unit =
    rememberOpenFile(guard, onPicked)

@Composable
actual fun rememberChoosePhoto(guard: LaunchGuard, onPicked: (open: () -> InputStream) -> Unit): () -> Unit {
    val context = LocalContext.current
    val resolver = remember(context) { context.applicationContext.contentResolver }
    val latest by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        guard.done()
        if (uri != null) latest { resolver.openInputStream(uri) ?: throw IOException("Could not open that photo.") }
    }
    return remember(guard, launcher) {
        { guard.launch { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }
    }
}

@Composable
actual fun rememberTakePhoto(
    guard: LaunchGuard,
    cameraDir: File,
    onTaken: (open: () -> InputStream) -> Unit,
    onUnavailable: () -> Unit,
): (() -> Unit)? {
    val context = LocalContext.current
    val taken by rememberUpdatedState(onTaken)
    val unavailable by rememberUpdatedState(onUnavailable)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        guard.done()
        val file = File(cameraDir, CAPTURE)
        if (saved) {
            // Read once, then delete, so a later capture can never reuse this file.
            taken { ByteArrayInputStream(file.readBytes().also { file.delete() }) }
        } else {
            file.delete()
        }
    }
    return remember(guard, launcher, cameraDir, context) {
        {
            val file = File(cameraDir, CAPTURE)
            file.delete()
            try {
                guard.launch { launcher.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file)) }
            } catch (e: ActivityNotFoundException) {
                unavailable()
            }
        }
    }
}

@Composable
actual fun rememberSaveFile(guard: LaunchGuard, mimeType: String, onChosen: (SaveTarget) -> Unit): (suggestedName: String) -> Unit {
    val context = LocalContext.current
    val resolver = remember(context) { context.applicationContext.contentResolver }
    val latest by rememberUpdatedState(onChosen)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { uri ->
        guard.done()
        if (uri != null) {
            latest(
                SaveTarget(
                    open = { resolver.openOutputStream(uri) ?: throw IOException("Could not open the backup file.") },
                    delete = { DocumentsContract.deleteDocument(resolver, uri) },
                ),
            )
        }
    }
    return remember(guard, launcher) { { name -> guard.launch { launcher.launch(name) } } }
}

/** Shows the system dialog for the calendar permissions, once at a time; [onResult] gets true when both were granted. */
@Composable
actual fun rememberCalendarPermissionRequest(onResult: (Boolean) -> Unit): () -> Unit {
    val guard = rememberLaunchGuard()
    val latest by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        guard.done()
        latest(granted.isNotEmpty() && granted.values.all { it })
    }
    return remember(guard, launcher) { { guard.launch { launcher.launch(CalendarPermissions.ALL) } } }
}

/** Opens this app's page in the system settings (to allow a refused permission); [onReturn] runs when the user is back. */
@Composable
actual fun rememberAppSettingsOpener(onReturn: () -> Unit): () -> Unit {
    val packageName = LocalContext.current.packageName
    val guard = rememberLaunchGuard()
    val latest by rememberUpdatedState(onReturn)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        guard.done()
        latest()
    }
    return remember(guard, launcher, packageName) {
        {
            try {
                guard.launch {
                    launcher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                }
            } catch (e: ActivityNotFoundException) {
                // No system settings app to open (not a real phone): nothing to do.
            }
        }
    }
}

/**
 * Google's code scanner for QR codes. Its UI is Play services'; the app holds no camera permission. A phone without
 * Play services (or whose scanner fails) is "unavailable". The user backing out is not a failure.
 */
@Composable
actual fun rememberCodeScanner(guard: LaunchGuard, onScanned: (String) -> Unit, onUnavailable: () -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val scanned by rememberUpdatedState(onScanned)
    val unavailable by rememberUpdatedState(onUnavailable)
    return remember(guard, context) {
        {
            guard.launch {
                val task = try {
                    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                    GmsBarcodeScanning.getClient(context, options).startScan()
                } catch (e: RuntimeException) {
                    null
                }
                if (task == null) {
                    guard.done()
                    unavailable()
                } else {
                    task.addOnSuccessListener { barcode ->
                        guard.done()
                        // A code that isn't text is no pairing code: PairingQr says so.
                        scanned(barcode.rawValue.orEmpty())
                    }
                    task.addOnCanceledListener { guard.done() }
                    task.addOnFailureListener { e ->
                        guard.done()
                        if ((e as? MlKitException)?.errorCode != MlKitException.CODE_SCANNER_CANCELLED) unavailable()
                    }
                }
            }
        }
    }
}

/** Opens the system's Wi-Fi settings; [onReturn] runs when the user is back. */
@Composable
actual fun rememberWifiSettingsOpener(onReturn: () -> Unit): () -> Unit {
    val guard = rememberLaunchGuard()
    val latest by rememberUpdatedState(onReturn)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        guard.done()
        latest()
    }
    return remember(guard, launcher) {
        {
            try {
                guard.launch { launcher.launch(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            } catch (e: ActivityNotFoundException) {
                // No Wi-Fi settings to open (not a real phone): nothing to do.
            }
        }
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)

@Composable
actual fun rememberAppVersion(): String {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    return remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" }
}

actual fun loadImageBitmap(file: File): ImageBitmap? = BitmapFactory.decodeFile(file.path)?.asImageBitmap()

actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

@Composable
actual fun KeepScreenOn() {
    val hostView = LocalView.current
    DisposableEffect(hostView) {
        hostView.keepScreenOn = true
        onDispose { hostView.keepScreenOn = false }
    }
}

private const val CAPTURE = "capture.jpg"

/** The picked file's name ("box.mmf"), for the reader's type check and the messages. */
private fun displayName(resolver: ContentResolver, uri: Uri): String =
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment ?: "import"

actual val dragToReorder: Boolean = false
