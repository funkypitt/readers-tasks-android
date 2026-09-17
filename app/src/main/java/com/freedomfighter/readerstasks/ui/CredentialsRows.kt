package com.freedomfighter.readerstasks.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
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
 * This app's section of the Reader's credentials file: exported through the share sheet (a
 * Telegram chat, kDrive, Drive, e-mail…), imported from any file the picker reaches. The desktop
 * apps read and write the same file.
 */
object CredentialsShare {
    const val AUTHORITY = "com.freedomfighter.readerstasks.credentials"

    private fun dir(context: Context) = File(context.cacheDir, "credentials")

    /** The shared copy holds the password: gone at the next export and at the next start. */
    fun clear(context: Context) { dir(context).listFiles()?.forEach { it.delete() } }

    fun share(context: Context, section: String, shortName: String, values: Map<String, String>) {
        clear(context)
        val file = File(dir(context).apply { mkdirs() }, "readers-credentials-$shortName.json")
        file.writeText(Credentials.build(section, values))
        val uri = FileProvider.getUriForFile(context, AUTHORITY, file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        context.startActivity(Intent.createChooser(send, null))
    }
}

@Composable
fun CredentialsRows(section: String, shortName: String, keys: Set<String>, hint: String, current: () -> Map<String, String>, onImport: (Map<String, String>) -> Unit) {
    val context = LocalContext.current
    val typo = LocalTypo.current
    var message by remember { mutableStateOf<String?>(null) }
    val imported = stringResource(R.string.credentials_imported)
    val notFile = stringResource(R.string.credentials_not_file)
    val nothing = stringResource(R.string.credentials_nothing, stringResource(R.string.app_name))
    val empty = stringResource(R.string.credentials_empty)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        message = try {
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: throw Credentials.NotCredentials()
            onImport(Credentials.read(text, section, keys))
            imported
        } catch (e: Credentials.NotCredentials) { notFile } catch (e: Credentials.NothingFor) { nothing } catch (e: Exception) { e.message ?: notFile }
    }
    TextRow(stringResource(R.string.export_credentials), secondary = hint, size = typo.title) {
        val values = current().filterValues { it.isNotBlank() }
        if (values.isEmpty()) message = empty
        else runCatching { CredentialsShare.share(context, section, shortName, values); message = null }.onFailure { message = it.message }
    }
    TextRow(stringResource(R.string.import_credentials), size = typo.title) {
        picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
    }
    message?.let { Small(it, Modifier.padding(horizontal = rowPadH).padding(bottom = 10.dp), maxLines = 3) }
}
