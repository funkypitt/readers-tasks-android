package com.freedomfighter.readerstasks.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.freedomfighter.readerstasks.R
import com.freedomfighter.readerstasks.data.Credentials
import java.io.File

/**
 * This app's section of the Reader's credentials file: saved where the person chooses, then
 * offered to the share menu (a Telegram chat, kDrive, Drive, e-mail…); imported from any file the
 * picker reaches. The desktop apps read and write the same file.
 */
object CredentialsShare {
    const val AUTHORITY = "com.freedomfighter.readerstasks.credentials"

    private fun dir(context: Context) = File(context.cacheDir, "credentials")

    /** The copy kept for the share menu holds the password: gone at the next export and at the next start. */
    fun clear(context: Context) { dir(context).listFiles()?.forEach { it.delete() } }

    /** Writes the text to the chosen document and keeps the copy to share. False when the document could not be written. */
    fun save(context: Context, uri: Uri, name: String, text: String): Boolean {
        clear(context)
        val written = runCatching {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }.isSuccess
        if (written) File(dir(context).apply { mkdirs() }, name).writeText(text)
        return written
    }

    /** Hands the copy of the last saved export to the share menu. */
    fun share(context: Context, name: String, mime: String, chooserTitle: String) {
        val file = File(dir(context), name)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(context, AUTHORITY, file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(name, uri)
        context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}

/** An export in progress: the rows set [saved] once the file is written, the page shows the menu that offers to share it. */
class CredentialsExport(val fileName: String) {
    var saved by mutableStateOf(false)
}

@Composable
fun rememberCredentialsExport(shortName: String) = remember(shortName) { CredentialsExport(Credentials.fileName(shortName)) }

@Composable
fun CredentialsRows(section: String, export: CredentialsExport, keys: Set<String>, hint: String, current: () -> Map<String, String>, onImport: (Map<String, String>) -> Unit) {
    val context = LocalContext.current
    val typo = LocalTypo.current
    var message by remember { mutableStateOf<String?>(null) }
    val imported = stringResource(R.string.credentials_imported)
    val notFile = stringResource(R.string.credentials_not_file)
    val nothing = stringResource(R.string.credentials_nothing, stringResource(R.string.app_name))
    val empty = stringResource(R.string.credentials_empty)
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val values = current().filterValues { it.isNotBlank() }
        val ok = CredentialsShare.save(context, uri, export.fileName, Credentials.build(section, values))
        if (ok) export.saved = true else message = context.getString(R.string.credentials_export_failed, export.fileName)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        message = try {
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: throw Credentials.NotCredentials()
            onImport(Credentials.read(text, section, keys))
            imported
        } catch (e: Credentials.NotCredentials) { notFile } catch (e: Credentials.NothingFor) { nothing } catch (e: Exception) { e.message?.let { context.getString(R.string.credentials_unreadable, it) } ?: notFile }
    }
    TextRow(stringResource(R.string.export_credentials), secondary = hint, size = typo.title) {
        val values = current().filterValues { it.isNotBlank() }
        if (values.isEmpty()) message = empty
        else runCatching { save.launch(export.fileName); message = null }.onFailure { message = context.getString(R.string.credentials_export_failed, it.message ?: it.javaClass.simpleName) }
    }
    TextRow(stringResource(R.string.import_credentials), size = typo.title) {
        picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
    }
    message?.let { Small(it, Modifier.padding(horizontal = rowPadH).padding(bottom = 10.dp), maxLines = 3) }
}

/** Shown at page level, above the scrolling column: the file is saved, and may be shared right away. */
@Composable
fun CredentialsExportMenu(export: CredentialsExport) {
    if (!export.saved) return
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.share_export)
    TextMenu(stringResource(R.string.export_saved, export.fileName), listOf(MenuItem(shareTitle) {
        CredentialsShare.share(context, export.fileName, "application/json", shareTitle)
    }), onDismiss = { export.saved = false })
}
