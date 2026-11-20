package frb.axeron.manager.ui.screen

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import frb.axeron.manager.ui.util.LocalTabScrollStates
import frb.axeron.manager.ui.component.AxSwitch
import frb.axeron.manager.ui.util.LocalCapsuleInset
import frb.axeron.manager.ui.component.AppIcon
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import frb.axeron.manager.ui.component.riseIn
import androidx.compose.foundation.lazy.itemsIndexed
import frb.axeron.manager.R
import frb.axeron.manager.ui.component.RoundedCheckbox
import frb.axeron.manager.ui.component.SearchAppBar
import frb.axeron.manager.ui.theme.cardContainerColor
import frb.axeron.manager.ui.component.UseLifecycle
import frb.axeron.manager.ui.viewmodel.AppsViewModel
import frb.axeron.manager.ui.viewmodel.ViewModelGlobal
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.foundation.clickable
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.widget.Toast
import frb.axeron.manager.ui.component.RootlessAppDialog
import frb.axeron.manager.ui.component.TerminalAppDialog
import androidx.compose.material.icons.outlined.Terminal
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import frb.axeron.manager.data.AxshLauncher
import androidx.compose.runtime.rememberCoroutineScope
import android.os.Build
import android.content.ClipboardManager
import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment

@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun PrivilegeScreen(
    navigator: DestinationsNavigator,
    viewModelGlobal: ViewModelGlobal
) {
    val privilegeViewModel = viewModelGlobal.privilegeViewModel
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(LocalTabScrollStates.current?.privilegeBar ?: rememberTopAppBarState())
    val listState = LocalTabScrollStates.current?.privilege ?: rememberLazyListState()

    UseLifecycle(
        {
            privilegeViewModel.loadInstalledApps(false)
        }
    )

    Scaffold(
        topBar = {
            SearchAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.privilege_manager),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                searchLabel = stringResource(R.string.search_label_apps),
                searchText = privilegeViewModel.search,
                onSearchTextChange = { privilegeViewModel.search = it },
                onClearClick = { privilegeViewModel.search = "" },
                action = {
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                imageVector = Icons.Filled.FilterList,
                                contentDescription = stringResource(R.string.privilege_filter)
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            shape = RoundedCornerShape(24.dp),
                            containerColor = cardContainerColor(),
                            tonalElevation = 0.dp
                        ) {
                            DropdownMenuItem(
                                // clip sebelum clickable -> highlight saat ditekan/ditahan ikut
                                // melengkung (full pill), bukan persegi panjang.
                                modifier = Modifier
                                    .padding(horizontal = 8.dp)
                                    .clip(RoundedCornerShape(16.dp)),
                                text = { Text(stringResource(R.string.privilege_filter_shizuku)) },
                                leadingIcon = {
                                    RoundedCheckbox(checked = privilegeViewModel.shizukuOnly)
                                },
                                onClick = {
                                    privilegeViewModel.updateShizukuOnly(!privilegeViewModel.shizukuOnly)
                                }
                            )
                            DropdownMenuItem(
                                modifier = Modifier
                                    .padding(horizontal = 8.dp)
                                    .clip(RoundedCornerShape(16.dp)),
                                text = { Text(stringResource(R.string.privilege_filter_termux)) },
                                leadingIcon = {
                                    RoundedCheckbox(checked = privilegeViewModel.termuxOnly)
                                },
                                onClick = {
                                    privilegeViewModel.updateTermuxOnly(!privilegeViewModel.termuxOnly)
                                }
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
//                onBackClick = {
//                    navigator.popBackStack()
//                },
            )
        }
    ) { paddingValues ->
        PullToRefreshBox(
            modifier = Modifier.padding(paddingValues),
            isRefreshing = privilegeViewModel.isRefreshing,
            onRefresh = {
                privilegeViewModel.loadInstalledApps()
            }
        ) {
            val keyTracker = remember { PrivilegeKeyTracker() }
            val sourceList = privilegeViewModel.privilegeList
            val keyedList = remember(sourceList) { keyTracker.keyed(sourceList) }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    bottom = LocalCapsuleInset.current + 16.dp
                )
            ) {
                itemsIndexed(
                    keyedList,
                    key = { _, it -> it.key },
                    contentType = { _, _ -> "privilege_app" }
                ) { index, keyed ->
                    val app = keyed.app
                    val uid = app.packageInfo.applicationInfo!!.uid
                    var showSettings by remember(app.packageName) { mutableStateOf(false) }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                            // Rises in on the app's entrance (first rows only; later ones just appear).
                            .riseIn(index = index.coerceAtMost(8), distance = 32.dp)
                            .padding(start = 8.dp, end = 22.dp, top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(enabled = app.isAdded) { showSettings = true }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            AppIcon(
                                packageInfo = app.packageInfo,
                                contentDescription = app.label,
                                modifier = Modifier
                                    .padding(4.dp)
                                    .width(48.dp)
                                    .height(48.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (app.hasTermuxLib) {
                                IconButton(
                                    onClick = { showSettings = true },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Terminal,
                                        contentDescription = stringResource(R.string.privilege_open_terminal)
                                    )
                                }
                            }
                            if (app.isAdded && !app.hasTermuxLib) {
                                Box(
                                    modifier = Modifier.size(40.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = stringResource(R.string.privilege_app_settings),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        if (app.isAdded) {
                            VerticalDivider(
                                modifier = Modifier
                                    .padding(horizontal = 8.dp)
                                    .height(28.dp)
                                    .width(DividerDefaults.Thickness),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        } else {
                            Spacer(Modifier.width(8.dp))
                        }
                        AxSwitch(
                            checked = app.isAdded,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    privilegeViewModel.grant(uid)
                                } else {
                                    showSettings = false
                                    privilegeViewModel.revoke(uid)
                                }
                            }
                        )
                    }

                    val launchScope = rememberCoroutineScope()
                    if (app.hasTermuxLib && showSettings) {
                        val context = LocalContext.current
                        val failedText = stringResource(R.string.privilege_open_terminal_failed)
                        val copiedText = stringResource(R.string.privilege_axsh_copied)
                        val notReadyText = stringResource(R.string.privilege_axsh_not_ready)

                        TerminalAppDialog(
                            app = app,
                            showRootless = privilegeViewModel.isRootMode,
                            initialRootless = privilegeViewModel.axshRootless(uid),
                            onRootlessChange = { privilegeViewModel.setAxshRootless(uid, it) },
                            onDismissRequest = { showSettings = false },
                            onLaunch = {
                                showSettings = false
                                launchScope.launch {
                                    val ready = withContext(Dispatchers.IO) {
                                        AxshLauncher.allowManageStorage(context, app.packageName)
                                        AxshLauncher.ensure(context)
                                    }
                                    if (!ready) {
                                        Toast.makeText(context, notReadyText, Toast.LENGTH_SHORT).show()
                                        return@launch
                                    }
                                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                                    clipboard?.setPrimaryClip(
                                        ClipData.newPlainText("axsh", AxshLauncher.RUN_COMMAND)
                                    )
                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                                    }
                                    val intent = context.packageManager
                                        .getLaunchIntentForPackage(app.packageName)
                                    if (intent == null) {
                                        Toast.makeText(context, failedText, Toast.LENGTH_SHORT).show()
                                    } else {
                                        runCatching {
                                            context.startActivity(
                                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        }.onFailure {
                                            Toast.makeText(context, failedText, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        )
                    } else if (!app.hasTermuxLib && app.isAdded && showSettings) {
                        RootlessAppDialog(
                            app = app,
                            showRootless = privilegeViewModel.isRootMode,
                            initialRootless = privilegeViewModel.axshRootless(uid),
                            onRootlessChange = { privilegeViewModel.setAxshRootless(uid, it) },
                            onDismissRequest = { showSettings = false }
                        )
                    }
                }
            }

        }
    }
}

private class KeyedApp(val key: String, val app: AppsViewModel.AppInfo)

private class PrivilegeKeyTracker {
    private val generations = HashMap<String, Int>()
    private var previousIds: Set<String> = emptySet()

    fun keyed(apps: List<AppsViewModel.AppInfo>): List<KeyedApp> {
        val ids = apps.map { it.packageName + it.uid }
        val currentIds = ids.toHashSet()
        for (id in previousIds) {
            if (id !in currentIds) generations[id] = (generations[id] ?: 0) + 1
        }
        previousIds = currentIds
        return apps.mapIndexed { index, app ->
            val id = ids[index]
            KeyedApp("$id#${generations[id] ?: 0}", app)
        }
    }
}
