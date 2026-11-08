package frb.axeron.manager.ui.screen

import frb.axeron.manager.ui.util.ServerRestart
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Parcelable
import android.provider.OpenableColumns
import android.util.Log
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.runtime.produceState
import androidx.lifecycle.viewmodel.compose.viewModel
import frb.axeron.manager.ui.component.TerminalOptionsSheet
import frb.axeron.manager.ui.util.ClipboardUtil
import frb.axeron.manager.ui.viewmodel.CommandRunViewModel
import frb.axeron.manager.ui.viewmodel.CommandRunViewModel.Stream
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import com.fox2code.androidansi.ktx.parseAsAnsiAnnotatedString
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import frb.axeron.manager.ui.component.AxSwitch
import frb.axeron.api.Axeron
import frb.axeron.api.AxeronCommandSession
import frb.axeron.api.AxeronPluginService
import frb.axeron.api.AxeronPluginService.flashPlugin
import frb.axeron.api.core.AxeronSettings
import frb.axeron.api.core.Starter
import frb.axeron.api.utils.AnsiFilter
import frb.axeron.manager.R
import frb.axeron.manager.ui.theme.cardContainerColor
import frb.axeron.manager.ui.component.AxSnackBarHost
import frb.axeron.manager.ui.component.KeyEventBlocker
import frb.axeron.manager.ui.component.rememberLoadingDialog
import frb.axeron.manager.ui.component.resolveDisplayName
import frb.axeron.manager.ui.theme.GREEN
import frb.axeron.manager.ui.theme.ORANGE
import frb.axeron.manager.ui.theme.RED
import frb.axeron.manager.ui.util.LocalSnackbarHost
import frb.axeron.manager.ui.viewmodel.ViewModelGlobal
import frb.axeron.server.PluginInstaller
import frb.axeron.shared.AxeronApiConstant
import frb.axeron.shared.PathHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class FlashingStatus {
    IDLE,
    FLASHING,
    SUCCESS,
    FAILED
}

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun Uri.getFileName(context: Context): String {
    val contentResolver = context.contentResolver
    val cursor = contentResolver.query(this, null, null, null, null)
    return cursor?.use {
        val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (it.moveToFirst() && nameIndex != -1) {
            it.getString(nameIndex)
        } else {
            this.lastPathSegment ?: "unknown.zip"
        }
    } ?: (this.lastPathSegment ?: "unknown.zip")
}

@Parcelize
sealed class FlashIt : Parcelable {
    data class FlashPlugins(val installers: List<PluginInstaller>) : FlashIt()

    data object FlashUninstall : FlashIt()
}

@ExperimentalMaterial3Api
@Composable
fun InstallDialog(
    confirm: Boolean = false,
    flashIt: FlashIt,
    onConfirm: (FlashIt) -> Unit,
    onDismiss: () -> Unit,
) {
    if (flashIt is FlashIt.FlashPlugins && confirm) {
        ModalBottomSheet(
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            onDismissRequest = onDismiss
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {

                Text(
                    text = stringResource(R.string.ask_install_plugin),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.ask_install_plugin_msg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                val installers = remember {
                    mutableStateListOf<PluginInstaller>().apply {
                        addAll(flashIt.installers)
                    }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    items(installers.size) { index ->
                        val pluginInstaller = installers[index]

                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = cardContainerColor()
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {

                                // ICON
                                Icon(
                                    imageVector = Icons.Outlined.Extension,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )

                                Spacer(modifier = Modifier.width(12.dp))

                                // TEXT INFO
                                Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = pluginInstaller.uri.resolveDisplayName(LocalContext.current),
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = if (pluginInstaller.autoEnable)
                                            stringResource(R.string.auto_enable_plugin)
                                        else
                                            stringResource(R.string.manual_enable_plugin),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                // SWITCH
                                AxSwitch(
                                    checked = pluginInstaller.autoEnable,
                                    onCheckedChange = { checked ->
                                        installers[index] =
                                            pluginInstaller.copy(autoEnable = checked)
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            onConfirm(FlashIt.FlashPlugins(installers))
                        }
                    ) {
                        Text(stringResource(R.string.install))
                    }
                }
            }
        }

    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun FlashScreen(
    navigator: DestinationsNavigator,
    viewModelGlobal: ViewModelGlobal,
    flashIt: FlashIt,
    finishIntent: Boolean = false
) {
    val pluginViewModel = viewModelGlobal.pluginViewModel
    val context = LocalContext.current
    val activity = context.findActivity()

    val developerOptionsEnabled = AxeronSettings.getEnableDeveloperOptions()

    // The installer is laid out exactly like the plugin action screen (ExecutePluginActionScreen):
    // status card, terminal panel, Copy log / Close at the bottom. The command and its output
    // live in the same view model, so they survive rotation and never run twice.
    val runner: CommandRunViewModel = viewModel()

    // What the user has confirmed to run. Reset (uninstall) needs no confirmation.
    var pendingFlashIt by rememberSaveable { mutableStateOf<FlashIt?>(null) }
    LaunchedEffect(flashIt) {
        if (flashIt is FlashIt.FlashUninstall && pendingFlashIt == null) pendingFlashIt = flashIt
    }

    val flashing = when (val state = runner.state) {
        CommandRunViewModel.State.Running -> FlashingStatus.FLASHING
        is CommandRunViewModel.State.Finished ->
            if (state.success) FlashingStatus.SUCCESS else FlashingStatus.FAILED

        CommandRunViewModel.State.Idle ->
            if (pendingFlashIt != null) FlashingStatus.FLASHING else FlashingStatus.IDLE
    }
    val finished = runner.state is CommandRunViewModel.State.Finished

    var wrap by rememberSaveable { mutableStateOf(true) }
    var showOptions by remember { mutableStateOf(false) }
    if (showOptions) TerminalOptionsSheet(onDismissRequest = { showOptions = false })

    val view = LocalView.current
    DisposableEffect(flashing) {
        view.keepScreenOn = flashing == FlashingStatus.FLASHING
        onDispose {
            view.keepScreenOn = false
        }
    }

    BackHandler(enabled = flashing == FlashingStatus.FLASHING) {
        // Disable back button if flashing is running
    }

    BackHandler(enabled = flashing != FlashingStatus.FLASHING) {
        navigator.popBackStack()
        if (finishIntent) activity?.finish()
    }

    InstallDialog(
        confirm = flashing == FlashingStatus.IDLE,
        flashIt = flashIt,
        onConfirm = {
            pendingFlashIt = it
        },
        onDismiss = {
            navigator.popBackStack()
            if (finishIntent) activity?.finish()
        }
    )

    val errorSaveLog = stringResource(R.string.log_error_code)

    // Saved with the screen: true once the run has begun. After the process was killed and the
    // screen restored, the run is not started again (an install or a reset must not repeat on
    // its own); it is shown as interrupted instead of spinning forever.
    var hasStarted by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(pendingFlashIt) {
        val target = pendingFlashIt ?: return@LaunchedEffect
        Log.d("FlashScreen", "flashing: $flashing")
        if (hasStarted && runner.state == CommandRunViewModel.State.Idle) {
            runner.start { terminal ->
                terminal.appendLine(Stream.NOTICE, "! interrupted")
                -1
            }
            return@LaunchedEffect
        }
        hasStarted = true
        // start() ignores a second call, so a recreated screen does not run it again.
        runner.start { terminal ->
            val result = flashIt(
                target,
                // Each callback is one line of output (same as the old text view, which added
                // the line break itself).
                onStdout = { terminal.write(Stream.STDOUT, it + "\n") },
                onStderr = { terminal.write(Stream.STDERR, it + "\n") }
            )
            if (result.code != 0) {
                terminal.appendLine(Stream.NOTICE, errorSaveLog.format(result.code, result.err).trim())
            }
            result.code
        }
    }

    val scope = rememberCoroutineScope()
    // The bottom button runs a suspend action and then leaves; a second tap must not repeat it.
    var closing by remember { mutableStateOf(false) }
    val snackBarHost = LocalSnackbarHost.current
    val logSaved = stringResource(R.string.log_saved_to)
    val logFailed = stringResource(R.string.failed_to_save_log)
    val copied = stringResource(R.string.copied)
    val reigniteLoading = rememberLoadingDialog()

    // What the status card shows under the state: the zips being installed, or what is reset.
    val subtitle by produceState(initialValue = "", flashIt) {
        value = when (flashIt) {
            is FlashIt.FlashPlugins -> withContext(Dispatchers.IO) {
                flashIt.installers.joinToString(", ") { it.uri.resolveDisplayName(context) }
            }

            FlashIt.FlashUninstall -> context.getString(R.string.reset_path)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (flashIt) {
                            is FlashIt.FlashPlugins -> stringResource(R.string.install)
                            FlashIt.FlashUninstall -> stringResource(R.string.reset_path)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = dropUnlessResumed {
                            pluginViewModel.markNeedRefresh()
                            navigator.popBackStack()
                            if (finishIntent) activity?.finish()
                        },
                        enabled = flashing != FlashingStatus.FLASHING
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
                actions = {
                    IconButton(onClick = { showOptions = true }) {
                        Icon(
                            Icons.Outlined.FormatSize,
                            contentDescription = stringResource(R.string.terminal_view_options)
                        )
                    }
                    IconToggleButton(checked = wrap, onCheckedChange = { wrap = it }) {
                        Icon(
                            Icons.AutoMirrored.Filled.WrapText,
                            contentDescription = stringResource(R.string.command_word_wrap)
                        )
                    }
                    IconButton(
                        enabled = finished,
                        onClick = {
                            scope.launch {
                                runCatching { runner.saveLog("fOxygen_install_log") }
                                    .onSuccess { snackBarHost.showSnackbar(logSaved.format(it)) }
                                    .onFailure { snackBarHost.showSnackbar(logFailed.format(it.message)) }
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.save))
                    }
                },
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = finished,
                enter = fadeIn() + expandVertically()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            // Android 13+ shows its own clipboard confirmation.
                            // The clipboard is a binder transaction (~1 MB): copy the tail.
                            val log = runner.terminal.text(lastLines = COPY_MAX_LINES)
                            if (ClipboardUtil.put(context, log) &&
                                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                            ) {
                                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.activation_copy_log))
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = !closing,
                        onClick = {
                            closing = true
                            scope.launch {
                                // Same actions the floating button had: after a successful
                                // install the server is re-ignited, after a reset it is
                                // restarted; a failed run just closes.
                                if (flashing == FlashingStatus.SUCCESS) {
                                    when (flashIt) {
                                        is FlashIt.FlashPlugins -> reigniteLoading.withLoading {
                                            AxeronPluginService.igniteSuspendService()
                                        }

                                        FlashIt.FlashUninstall -> reigniteLoading.withLoading {
                                            ServerRestart.launch()
                                        }
                                    }
                                }
                                pluginViewModel.markNeedRefresh()
                                navigator.popBackStack()
                                if (finishIntent) activity?.finish()
                            }
                        }
                    ) {
                        if (flashing == FlashingStatus.SUCCESS) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            stringResource(
                                when {
                                    flashing != FlashingStatus.SUCCESS -> R.string.close
                                    flashIt is FlashIt.FlashUninstall -> R.string.restart_and_close
                                    else -> R.string.re_ignite_and_close
                                }
                            ),
                            maxLines = 1
                        )
                    }
                }
            }
        },
        snackbarHost = { AxSnackBarHost(snackBarHost) }
    ) { innerPadding ->
        KeyEventBlocker {
            it.key == Key.VolumeDown || it.key == Key.VolumeUp
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .padding(bottom = if (finished) 0.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CommandRunStatusCard(runner = runner, subtitle = subtitle)
            CommandRunOutput(
                runner = runner,
                wrap = wrap,
                showStderr = developerOptionsEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }

}

private const val COPY_MAX_LINES = 5_000

suspend fun flashModulesSequentially(
    installers: List<PluginInstaller>,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): AxeronPluginService.FlashResult {
    for (installer in installers) {
        flashPlugin(installer, onStdout, onStderr).apply {
            if (code != 0) {
                return AxeronPluginService.FlashResult(code, err, showReboot)
            }
        }
    }
    return AxeronPluginService.FlashResult(0, "", true)
}

suspend fun flashIt(
    flashIt: FlashIt,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): AxeronPluginService.FlashResult {
    return when (flashIt) {
        is FlashIt.FlashPlugins -> {
            flashModulesSequentially(flashIt.installers, onStdout, onStderr)
        }

        is FlashIt.FlashUninstall -> AxeronPluginService.resetManagerNative(onStdout, onStderr)
    }
}
