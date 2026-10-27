package frb.axeron.manager.ui.screen

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DoNotTouch
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Output
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import frb.axeron.manager.ui.theme.sheetContainerColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fox2code.androidansi.ktx.parseAsAnsiAnnotatedString
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import frb.axeron.manager.ui.util.LocalCapsuleInset
import frb.axeron.api.Axeron
import frb.axeron.api.utils.AnsiFilter
import frb.axeron.manager.R
import frb.axeron.manager.ui.component.TerminalOutput
import frb.axeron.manager.ui.component.TerminalOptionsCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import frb.axeron.manager.ui.util.TerminalBuffer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.derivedStateOf
import frb.axeron.manager.ui.component.AxSnackBarHost
import frb.axeron.manager.ui.component.CheckBoxText
import frb.axeron.manager.ui.component.KeyEventBlocker
import frb.axeron.manager.ui.component.SettingsItem
import frb.axeron.manager.ui.component.SettingsItemExpanded
import frb.axeron.manager.ui.util.LocalSnackbarHost
import frb.axeron.manager.ui.util.PrefsEnumHelper
import frb.axeron.manager.ui.viewmodel.QuickShellViewModel
import frb.axeron.manager.ui.viewmodel.ViewModelGlobal
import frb.axeron.shared.AxeronApiConstant
import frb.axeron.shared.PathHelper
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun QuickShellScreen(navigator: DestinationsNavigator, viewModelGlobal: ViewModelGlobal) {
    val viewModel: QuickShellViewModel = viewModel()
    val running = viewModel.isRunning

    val listState = rememberLazyListState()
    val hasLogs by remember { derivedStateOf { !viewModel.terminal.isEmpty } }

    var previousIndex by remember { mutableIntStateOf(0) }
    var previousScrollOffset by remember { mutableIntStateOf(0) }

    // FAB visibility
    var fabVisible by remember { mutableStateOf(true) }

    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose {
            view.keepScreenOn = false
        }
    }

    LaunchedEffect(listState, viewModel.isRunning) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                if (!viewModel.isRunning) {  // hanya update FAB kalau sedang tidak running
                    if (index > previousIndex || (index == previousIndex && offset > previousScrollOffset)) {
                        fabVisible = false
                    } else if (index < previousIndex || offset < previousScrollOffset) {
                        fabVisible = true
                    }
                }
                previousIndex = index
                previousScrollOffset = offset
            }
    }

    val scope = rememberCoroutineScope()
    val snackBarHost = LocalSnackbarHost.current

    var showExtraDialog by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }

    // Nothing left to search once the output is cleared.
    LaunchedEffect(hasLogs) { if (!hasLogs) showSearch = false }

    ExtraSettings(
        showExtraDialog,
        viewModel,
    ) {
        showExtraDialog = false
    }


    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.quick_shell),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
                actions = {
                    // Disabled icons keep the toolbar's own icon colour at 60% (not Material's 38%):
                    // on the light theme 38% faded stop and clear into the bar and into each other.
                    val barIconColors = IconButtonDefaults.iconButtonColors(
                        disabledContentColor = LocalContentColor.current.copy(alpha = 0.6f)
                    )
                    IconButton(
                        onClick = {
                            viewModel.stop()
                        },
                        enabled = running,
                        colors = barIconColors,
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                    }
                    IconButton(
                        onClick = {
                            viewModel.clear()
                        },
                        enabled = hasLogs,
                        colors = barIconColors,
                    ) {
                        Icon(Icons.Filled.ClearAll, contentDescription = null)
                    }
                    IconButton(
                        onClick = {
                            showExtraDialog = true
                        }
                    ) {
                        Icon(Icons.Outlined.MoreVert, null)
                    }
                },
                colors = frb.axeron.manager.ui.theme.axTopAppBarColors()
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = fabVisible && hasLogs,
                modifier = Modifier.padding(bottom = LocalCapsuleInset.current),
                enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            ) {
                val context = LocalContext.current
                FloatingActionButton(shape = CircleShape, onClick = {
                    scope.launch {
                        saveLogsToDownload(context, viewModel.terminal, snackBarHost)
                    }
                }) {
                    Icon(Icons.Default.Save, contentDescription = stringResource(R.string.save))
                }
            }
        },
        snackbarHost = {
            Box(modifier = Modifier.padding(bottom = LocalCapsuleInset.current)) {
                AxSnackBarHost(hostState = snackBarHost)
            }
        }
    ) { paddingValues ->
        val context = LocalContext.current
        val focusManager = LocalFocusManager.current
        var keyboardVisible by remember { mutableStateOf(false) }
        // The IME also opens for the search bar; only the command field should grow the card.
        var commandFocused by remember { mutableStateOf(false) }
        val expandCommand = keyboardVisible && commandFocused
        val keyboardController = LocalSoftwareKeyboardController.current

        KeyEventBlocker {
            val prefs = PrefsEnumHelper<QuickShellViewModel.KeyEventType>("block_")
            when (it.key) {
                Key.VolumeUp -> prefs.loadState(
                    context,
                    QuickShellViewModel.KeyEventType.VOLUME_UP,
                    true
                )

                Key.VolumeDown -> prefs.loadState(
                    context,
                    QuickShellViewModel.KeyEventType.VOLUME_DOWN,
                    true
                )

                else -> false
            }
        }

        KeyboardVisibilityListener(
            onKeyboardState = { visible ->
                keyboardVisible = visible
                if (!visible) {
                    focusManager.clearFocus()
                }
            }
        )

        Box(
            Modifier
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .fillMaxSize()
        ) {


            // Visible kinds come from the output filter; re-read when the settings sheet closes.
            val visibleTypes = remember(showExtraDialog) {
                val prefs = PrefsEnumHelper<QuickShellViewModel.OutputType>("output_")
                QuickShellViewModel.OutputType.entries
                    .filter { prefs.loadState(context, it, true) }
                    .toSet()
            }
            val filter = remember(visibleTypes) {
                if (visibleTypes.size == QuickShellViewModel.OutputType.entries.size) null
                else { type: QuickShellViewModel.OutputType -> type in visibleTypes }
            }

            // Lift the nav pill above the Save Log button while that button is on screen.
            val jumpButtonBottom by animateDpAsState(
                targetValue = (if (fabVisible && hasLogs) 68.dp else 12.dp) + LocalCapsuleInset.current,
                label = "jumpButtonBottom"
            )

            TerminalOutput(
                buffer = viewModel.terminal,
                state = listState,
                wrap = false,
                filter = filter,
                color = { it.color() },
                // Top clears the command card.
                contentPadding = PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    top = 70.dp,
                    bottom = 22.dp + LocalCapsuleInset.current
                ),
                jumpButtonPadding = PaddingValues(bottom = jumpButtonBottom),
                jumpButtonAlignWithFab = true,
                searchOpen = showSearch,
                onSearchOpenChange = { showSearch = it },
                modifier = Modifier.fillMaxSize()
            )

            @SuppressLint("ConfigurationScreenWidthHeight")
            val screen = LocalConfiguration.current.screenHeightDp
            val paddingHeight = (screen * 0.52).dp


            ElevatedCard(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.elevatedCardColors().copy(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
//                    containerColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .run {
                        if (expandCommand) {
                            padding(bottom = paddingHeight)
                        } else {
                            padding(bottom = 0.dp)
                        }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    TextField(
                        value = viewModel.commandText,
                        onValueChange = {
                            viewModel.setCommand(it)
                        },
                        label = {
                            Text(viewModel.execMode)
                        },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            lineHeight = MaterialTheme.typography.bodyLarge.fontSize,
                            lineHeightStyle = LineHeightStyle(
                                alignment = LineHeightStyle.Alignment.Center,
                                trim = LineHeightStyle.Trim.Both
                            ),
                            fontFamily = FontFamily.Monospace
                        ),
                        maxLines = if (expandCommand) Int.MAX_VALUE else 1,
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,   // garis saat fokus
                            unfocusedIndicatorColor = Color.Transparent, // garis saat tidak fokus
                            disabledIndicatorColor = Color.Transparent,   // garis saat disabled
                            focusedContainerColor = Color.Transparent,   // ⬅ ini penting
                            unfocusedContainerColor = Color.Transparent, // ⬅ ini juga
                            disabledContainerColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { commandFocused = it.hasFocus }
                            .animateContentSize(
                                animationSpec = tween(
                                    durationMillis = 250,
                                    easing = LinearOutSlowInEasing
                                )
                            )
                    )

                    IconButton(
                        onClick = {
                            viewModel.runShell()
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 12.dp)
                            .padding(vertical = 4.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_exec),
                            contentDescription = stringResource(R.string.exec),
                            modifier = Modifier.size(38.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

        }
    }
}

@Composable
fun KeyboardVisibilityListener(
    onKeyboardState: (visible: Boolean) -> Unit
) {
    val insets = WindowInsets.ime
    val imeHeight = insets.getBottom(LocalDensity.current)

    val imeVisible = imeHeight > 0
    val prevImeVisible = remember { mutableStateOf(imeVisible) }

    LaunchedEffect(imeVisible) {
        if (imeVisible != prevImeVisible.value) {
            onKeyboardState(imeVisible)
            prevImeVisible.value = imeVisible
        }
    }
}


/**
 * Sits between the bottom sheet and its scrolling content. When the list is already at its end,
 * the rest of an upward drag or fling is swallowed here instead of being handed to the sheet,
 * whose settle animation otherwise kicks in and makes the content bounce back and forth when it
 * is only slightly taller than the sheet. Downward leftovers still reach the sheet, so
 * swipe-to-dismiss keeps working from the top of the list.
 */
private val SwallowUpwardLeftover = object : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset = if (available.y < 0f) Offset(0f, available.y) else Offset.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        if (available.y < 0f) Velocity(0f, available.y) else Velocity.Zero
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtraSettings(
    showDialog: Boolean,
    quickShellViewModel: QuickShellViewModel,
    onDismissRequest: () -> Unit
) {
    if (showDialog) {
        ModalBottomSheet(
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = sheetContainerColor(),
            onDismissRequest = onDismissRequest
        ) {
            val context: Context = LocalContext.current
            val outputOption = QuickShellViewModel.OutputType.entries
            val keyEventOption = QuickShellViewModel.KeyEventType.entries

            // SettingsItem carries its own 16dp gutter.
            // A handful of fixed rows: a plain scrolling Column instead of a LazyColumn, so the
            // content height is deterministic. SwallowUpwardLeftover keeps the leftover of an
            // upward drag/fling at the end of the list from reaching the sheet (see below).
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .nestedScroll(SwallowUpwardLeftover)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                run {
                    val checkedOutputStates = remember {
                        mutableStateMapOf<QuickShellViewModel.OutputType, Boolean>().apply {
                            outputOption.forEach {
                                put(
                                    it,
                                    PrefsEnumHelper<QuickShellViewModel.OutputType>("output_")
                                        .loadState(context, it, true)
                                )
                            }
                        }
                    }

                    SettingsItemExpanded(
                        label = stringResource(R.string.output_filter),
                        description = stringResource(R.string.filter_output_desc),
                        iconVector = Icons.Outlined.Output
                    ) { _, expanded ->
                        AnimatedVisibility(
                            visible = expanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column(Modifier.padding(bottom = 12.dp)) {
                                outputOption.forEach { type ->
                                    val isChecked = checkedOutputStates[type] ?: false
                                    CheckBoxText(
                                        label = stringResource(type.labelId),
                                        checked = isChecked
                                    ) {
                                        checkedOutputStates[type] = it
                                        PrefsEnumHelper<QuickShellViewModel.OutputType>("output_")
                                            .saveState(context, type, it)
                                    }
                                }
                            }
                        }
                    }
                }

                run {
                    val checkedSaveStates = remember {
                        mutableStateMapOf<QuickShellViewModel.OutputType, Boolean>().apply {
                            outputOption.forEach {
                                put(
                                    it,
                                    PrefsEnumHelper<QuickShellViewModel.OutputType>("save_")
                                        .loadState(context, it, true)
                                )
                            }
                        }
                    }

                    SettingsItemExpanded(
                        label = stringResource(R.string.save_log_filter),
                        description = stringResource(R.string.filter_save_log_desc),
                        iconVector = Icons.Outlined.Save,
                    ) { _, expanded ->
                        AnimatedVisibility(
                            visible = expanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column(Modifier.padding(bottom = 12.dp)) {
                                outputOption.forEach { type ->
                                    val isChecked = checkedSaveStates[type] ?: false
                                    CheckBoxText(
                                        label = stringResource(type.labelId),
                                        checked = isChecked
                                    ) {
                                        checkedSaveStates[type] = it
                                        PrefsEnumHelper<QuickShellViewModel.OutputType>("save_")
                                            .saveState(context, type, it)
                                    }
                                }
                            }
                        }
                    }
                }

                run {
                    val blockedKeyEventStates = remember {
                        mutableStateMapOf<QuickShellViewModel.KeyEventType, Boolean>().apply {
                            keyEventOption.forEach {
                                put(
                                    it,
                                    PrefsEnumHelper<QuickShellViewModel.KeyEventType>("block_")
                                        .loadState(context, it, true)
                                )
                            }
                        }
                    }

                    SettingsItemExpanded(
                        label = stringResource(R.string.block_key_event),
                        description = stringResource(R.string.block_key_event_desc),
                        iconVector = Icons.Outlined.DoNotTouch,
                    ) { _, expanded ->
                        AnimatedVisibility(
                            visible = expanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column(Modifier.padding(bottom = 12.dp)) {
                                keyEventOption.forEach { type ->
                                    val isChecked = blockedKeyEventStates[type] ?: false
                                    CheckBoxText(
                                        label = stringResource(type.labelId),
                                        checked = isChecked
                                    ) {
                                        blockedKeyEventStates[type] = it
                                        PrefsEnumHelper<QuickShellViewModel.KeyEventType>("block_")
                                            .saveState(context, type, it)
                                    }
                                }
                            }
                        }
                    }
                }

                run {
                    TerminalOptionsCard()
                }

                run {
                    SettingsItem(
                        iconVector = Icons.Filled.Security,
                        label = stringResource(R.string.shell_restriction),
                        description = stringResource(R.string.shell_restriction_desc),
                        checked = quickShellViewModel.isShellRestrictionEnabled,
                        onSwitchChange = { quickShellViewModel.setShellRestriction(it) }
                    )
                }

                run {
                    SettingsItem(
                        iconVector = Icons.Outlined.Bolt,
                        label = stringResource(R.string.compat_mode),
                        description = stringResource(R.string.compat_mode_desc),
                        checked = quickShellViewModel.isCompatModeEnabled,
                        onSwitchChange = { quickShellViewModel.setCompatMode(it) }
                    )
                }

                // Only the root server can lower a command to the shell level.
                if (quickShellViewModel.isRootMode) {
                    run {
                        SettingsItem(
                            iconVector = Icons.Outlined.BugReport,
                            label = stringResource(R.string.rootless_debugging),
                            description = stringResource(R.string.rootless_debugging_desc),
                            checked = quickShellViewModel.isRootlessEnabled,
                            onSwitchChange = { quickShellViewModel.setRootless(it) }
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun QuickShellViewModel.OutputType.color(): Color = when (this) {
    QuickShellViewModel.OutputType.TYPE_COMMAND -> MaterialTheme.colorScheme.primary
    QuickShellViewModel.OutputType.TYPE_STDIN -> MaterialTheme.colorScheme.tertiary
    QuickShellViewModel.OutputType.TYPE_STDERR,
    QuickShellViewModel.OutputType.TYPE_THROW -> MaterialTheme.colorScheme.error
    QuickShellViewModel.OutputType.TYPE_START,
    QuickShellViewModel.OutputType.TYPE_EXIT -> MaterialTheme.colorScheme.outline
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

suspend fun saveLogsToDownload(
    context: Context,
    terminal: TerminalBuffer<QuickShellViewModel.OutputType>,
    snackbar: SnackbarHostState
) {
    val logSaved = context.getString(R.string.log_saved_to)
    val logFailed = context.getString(R.string.failed_to_save_log)
    if (terminal.isEmpty) return
    val format = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
    val date = format.format(Date())

    val baseDir = PathHelper.getPath(AxeronApiConstant.folder.PARENT_LOG)
    if (!baseDir.exists()) {
        baseDir.mkdirs()
    }

    val file = File(baseDir, "QuickShell_log_${date}.log")

    try {
        val prefs = PrefsEnumHelper<QuickShellViewModel.OutputType>("save_")
        val saved = QuickShellViewModel.OutputType.entries
            .filter { prefs.loadState(context, it, true) }
            .toSet()
        // Streamed line by line off the main thread: unlimited output can exceed the heap.
        withContext(Dispatchers.IO) {
            Axeron.newFileService().getStreamSession(file.absolutePath, true, false)
                .outputStream.use { terminal.writeTo(it) { type -> type in saved } }
        }

        snackbar.showSnackbar(logSaved.format(file.absolutePath))
    } catch (e: Exception) {
        snackbar.showSnackbar(logFailed.format(e.message))
    }
}



