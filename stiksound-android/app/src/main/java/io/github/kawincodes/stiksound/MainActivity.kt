package io.github.kawincodes.stiksound

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kawincodes.stiksound.media.Outputs
import io.github.kawincodes.stiksound.ui.EditorScreen
import io.github.kawincodes.stiksound.ui.ResultScreen
import io.github.kawincodes.stiksound.ui.StikSoundTheme

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) vm.handleShare(intent)
        setContent {
            StikSoundTheme {
                StikSoundApp(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        vm.handleShare(intent)
    }
}

@Composable
fun StikSoundApp(vm: EditorViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showAbout by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.message?.id) {
        val message = state.message ?: return@LaunchedEffect
        vm.consumeMessage(message.id)
        snackbar.showSnackbar(context.getString(message.resId, *message.args))
    }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.saveResult() else vm.showMessage(UiMessage(R.string.error_save_permission))
    }

    val result = state.result
    if (result != null) {
        BackHandler { vm.closeResult() }
        ResultScreen(
            result = result,
            saved = state.savedToGallery,
            snackbar = snackbar,
            onSave = {
                val needsPermission = Outputs.needsLegacyStoragePermission &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                    PackageManager.PERMISSION_GRANTED
                if (needsPermission) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else vm.saveResult()
            },
            onShare = {
                val chooser = Outputs.shareIntent(context, result.file, result.kind, context.getString(R.string.share))
                context.startActivity(chooser)
            },
            onBack = vm::closeResult,
        )
    } else {
        EditorScreen(state, vm, snackbar, onAbout = { showAbout = true })
    }

    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about_title, BuildConfig.VERSION_NAME)) },
        text = { Text(stringResource(R.string.about_body)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = {
            TextButton(onClick = { uriHandler.openUri("https://github.com/SilverFeit/StikSound") }) {
                Text(stringResource(R.string.about_upstream))
            }
        },
    )
}
