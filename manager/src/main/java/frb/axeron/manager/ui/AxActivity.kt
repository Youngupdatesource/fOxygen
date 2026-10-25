@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package frb.axeron.manager.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.animation.ObjectAnimator
import android.provider.Settings
import android.view.View
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.splashscreen.SplashScreenViewProvider
import frb.axeron.manager.ui.component.SplashIntroHost
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import kotlin.math.abs
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.util.lerp
import kotlin.math.floor
import kotlin.math.min
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import frb.axeron.manager.ui.component.LocalBannerAnimationAllowed
import frb.axeron.manager.ui.screen.HomeScreen
import frb.axeron.manager.ui.screen.PrivilegeScreen
import frb.axeron.manager.ui.screen.SettingsScreen
import frb.axeron.manager.ui.screen.plugin.PluginScreen
import frb.axeron.manager.ui.util.LocalCapsuleInset
import frb.axeron.manager.ui.util.LocalTabScrollStates
import frb.axeron.manager.ui.util.TabScrollStates
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ramcosta.composedestinations.DestinationsNavHost
import com.ramcosta.composedestinations.animations.NavHostAnimatedDestinationStyle
import com.ramcosta.composedestinations.generated.NavGraphs
import com.ramcosta.composedestinations.generated.destinations.ActivateScreenDestination
import com.ramcosta.composedestinations.generated.destinations.ExecutePluginActionScreenDestination
import com.ramcosta.composedestinations.generated.destinations.FlashScreenDestination
import com.ramcosta.composedestinations.generated.destinations.HomeScreenDestination
import com.ramcosta.composedestinations.generated.destinations.QuickShellScreenDestination
import com.ramcosta.composedestinations.generated.destinations.SettingsEditorScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.ramcosta.composedestinations.navigation.dependency
import com.ramcosta.composedestinations.utils.isRouteOnBackStackAsState
import com.ramcosta.composedestinations.utils.rememberDestinationsNavigator
import frb.axeron.api.AxeronInfo
import frb.axeron.manager.R
import frb.axeron.manager.ui.navigation.BottomBarDestination
import frb.axeron.manager.ui.screen.FlashIt
import frb.axeron.manager.ui.theme.AxManagerTheme
import frb.axeron.manager.ui.theme.capsuleContainerColor
import frb.axeron.manager.ui.util.LocalSnackbarHost
import frb.axeron.manager.ui.util.LocaleHelper
import frb.axeron.manager.ui.viewmodel.ActivateViewModel
import frb.axeron.manager.ui.viewmodel.AppsViewModel
import frb.axeron.manager.ui.viewmodel.PluginViewModel
import frb.axeron.manager.ui.viewmodel.PrivilegeViewModel
import frb.axeron.manager.ui.viewmodel.SettingsViewModel
import frb.axeron.manager.ui.viewmodel.ViewModelGlobal
import frb.axeron.server.PluginInstaller

class AxActivity : ComponentActivity() {

    companion object {
        const val OPEN_QUICK_SHELL = "AxManager.QUICK_SHELL"

        /** The longest the splash screen is kept waiting for the first screen. */
        private const val SPLASH_MAX_MS = 1000L

        /** The intro plays once per process: not on a restart of the activity, not on reopening. */
        @Volatile
        private var introShown = false
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLanguage(newBase))
    }

    var zipUri by mutableStateOf<List<PluginInstaller>?>(emptyList())

    val shortcut by lazy {
        ShortcutInfoCompat.Builder(this, "quick-shell")
            .setShortLabel(getString(R.string.quick_shell_short))
            .setLongLabel(getString(R.string.quick_shell))
            .setDisabledMessage(getString(R.string.quick_shell_not_supported))
            .setIcon(IconCompat.createWithResource(this, R.drawable.terminal))
            .setIntent(
                Intent(this, AxActivity::class.java).apply {
                    action = OPEN_QUICK_SHELL
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
            .build()
    }

    private var intentState: Intent? by mutableStateOf(null)

    /**
     * Turns true once the first screen has been composed. Until then the splash screen stays up,
     * so the app opens onto its content instead of a blank frame.
     */
    @Volatile
    private var contentReady = false

    /** True once the system splash is gone and the Compose intro (SplashIntroHost) may run. */
    private var introStarted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The intro is for a plain launch from the launcher: not for a restored activity, not for
        // a shortcut or a zip being opened, and not when the user switched animations off.
        val action = intent?.action
        val animationsOn = Settings.Global.getFloat(
            contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) > 0f
        val intro = savedInstanceState == null && !introShown && animationsOn &&
            (action == null || action == Intent.ACTION_MAIN)

        // Before super.onCreate: swaps the splash theme for the app's (postSplashScreenTheme).
        installSplashScreen().apply {
            setKeepOnScreenCondition { !contentReady }
            setOnExitAnimationListener { hideSystemSplash(it, intro) }
        }
        // The splash must never be able to hold the user behind a screen that does not come up.
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({ contentReady = true }, SPLASH_MAX_MS)
        // If the system never reports its splash gone, the intro still starts.
        if (intro) handler.postDelayed({ introStarted = true }, SPLASH_MAX_MS + 600)

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // The shortcut survives restarts. Pushing it is a blocking call to the system's shortcut
        // service, and building it touches resources: neither belongs in front of the first frame.
        lifecycleScope.launch(Dispatchers.Default) {
            runCatching { ShortcutManagerCompat.pushDynamicShortcut(applicationContext, shortcut) }
        }

        intentState = intent

        setContent {
            AxManagerTheme { settings ->
                SplashIntroHost(
                    enabled = intro,
                    started = introStarted,
                    onFinished = { introShown = true }
                ) {
                    MainScreen(settings)
                }
                LaunchedEffect(Unit) { contentReady = true }
            }
        }
    }

    /**
     * The system splash only has to melt away. With the intro, SplashIntroHost already draws the
     * same icon in the same place underneath (same circle, same colour), so a short fade is
     * invisible and the intro carries on from there; without it a slightly longer fade is used.
     */
    private fun hideSystemSplash(provider: SplashScreenViewProvider, intro: Boolean) {
        if (intro) introStarted = true
        ObjectAnimator.ofFloat(provider.view, View.ALPHA, 1f, 0f).apply {
            duration = if (intro) 140L else 220L
            doOnEnd { provider.remove() }
            start()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intentState = intent
        Log.i("AxActivity", "onNewIntent")
    }

    @Composable
    fun MainScreen(settingsViewModel: SettingsViewModel) {
        val snackBarHostState = remember { SnackbarHostState() }
        val navController = rememberNavController()
        val navigator = navController.rememberDestinationsNavigator()
        val currentDestination = navController.currentBackStackEntryAsState().value?.destination

        val activateViewModel: ActivateViewModel = viewModel<ActivateViewModel>()

        val appsViewModel: AppsViewModel = viewModel<AppsViewModel>()
        val privilegeViewModel: PrivilegeViewModel = viewModel<PrivilegeViewModel>()
        val pluginViewModel: PluginViewModel = viewModel<PluginViewModel>()

        val axeronInfo = activateViewModel.axeronInfo

        LaunchedEffect(intentState) {
            if (intentState == null) return@LaunchedEffect
            Log.i("AxActivity", "intent: $intentState")
            when (intentState!!.action) {
                OPEN_QUICK_SHELL -> {
                    if (axeronInfo.isRunning()) {
                        navigator.navigate(HomeScreenDestination()) {
                            popUpTo(NavGraphs.root) {
                                saveState = true
                            }
                        }
                        if (navController.currentDestination?.route == HomeScreenDestination.route) {
                            navigator.navigate(QuickShellScreenDestination())
                        }
                    }
                }

                Intent.ACTION_VIEW -> {
                    if (intentState!!.data?.scheme == "content") {
                        zipUri =
                            intent.data?.let { uri ->
                                arrayListOf(PluginInstaller(uri))
                            } ?: run {
                                val uris: ArrayList<Uri>? =
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        intent.getParcelableArrayListExtra("uris", Uri::class.java)
                                    } else {
                                        @Suppress("DEPRECATION")
                                        intent.getParcelableArrayListExtra("uris")
                                    }

                                uris
                                    ?.map { PluginInstaller(it) }
                                    ?.toCollection(arrayListOf())
                                    ?: arrayListOf()
                            }
                        if (!zipUri.isNullOrEmpty()) {
                            navigator.navigate(
                                FlashScreenDestination(
                                    flashIt = FlashIt.FlashPlugins(zipUri!!)
                                )
                            )
                            zipUri = null
                        }
                    }
                }
            }

            intentState = null
        }

        LaunchedEffect(axeronInfo) {
            if (axeronInfo.isRunning()) {
                pluginViewModel.fetchModuleList()
                appsViewModel.loadInstalledApps()
                privilegeViewModel.loadInstalledApps()
            }
        }

        val viewModelGlobal = remember {
            ViewModelGlobal(
                settingsViewModel = settingsViewModel,
                appsViewModel = appsViewModel,
                activateViewModel = activateViewModel,
                pluginViewModel = pluginViewModel,
                privilegeViewModel = privilegeViewModel
            )
        }

        val bottomBarRoutes = remember {
            BottomBarDestination.entries.map { it.direction.route }.toSet()
        }
        val swipe = remember { TabSwipeState() }
        val navTransitions = remember(bottomBarRoutes) {
            createNavTransitions(bottomBarRoutes) { swipe.skipNavTransition }
        }

        // Urutan tab yang sedang tampil di bottom bar (tab needAxeron disembunyikan saat server mati).
        val swipeTabs = BottomBarDestination.entries.filter { axeronInfo.isRunning() || !it.needAxeron }
        val currentTabIndex = swipeTabs.indexOfFirst { it.direction.route == currentDestination?.route }

        val showBottomBar = when (currentDestination?.route) {
            ActivateScreenDestination.route -> false // Hide for Activate
            FlashScreenDestination.route -> false // Hide for Flash
            ExecutePluginActionScreenDestination.route -> false // Hide for ExecutePluginAction
            else -> true
        }

        // --- floating nav capsule ---
        // Kapsul hanya meredup saat QuickShell atau Settings Editor sedang terbuka. Di layar lain
        // selalu terang penuh (tidak ada redup otomatis saat idle / saat konten di-scroll).
        val dimCapsule = currentDestination?.route.let {
            it == QuickShellScreenDestination.route || it == SettingsEditorScreenDestination.route
        }
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        // Semua layar yang menampilkan kapsul: konten tembus sampai tepi bawah layar dan kapsul
        // melayang di atasnya. Ruang kapsul dikirim lewat LocalCapsuleInset; tiap layar memakainya
        // sebagai padding bawah konten scroll / FAB / snackbar.
        val capsuleInset = if (showBottomBar) CapsuleReserve + navInset else 0.dp

        // Posisi scroll empat layar tab dipegang di sini, dipakai bersama oleh layar asli dan
        // preview geser, supaya preview tidak pernah mulai dari atas lalu melompat.
        val homeScroll = rememberScrollState()
        val privilegeList = rememberLazyListState()
        val pluginList = rememberLazyListState()
        val settingsScroll = rememberScrollState()
        // Keadaan header (warna saat konten di-scroll) juga dibagi, supaya header preview sama
        // dengan header layar asli dan tidak "berkedip" saat serah-terima.
        val homeBar = rememberTopAppBarState()
        val privilegeBar = rememberTopAppBarState()
        val pluginBar = rememberTopAppBarState()
        val settingsBar = rememberTopAppBarState()
        val tabScroll = remember(
            homeScroll, privilegeList, pluginList, settingsScroll,
            homeBar, privilegeBar, pluginBar, settingsBar
        ) {
            TabScrollStates(
                homeScroll, privilegeList, pluginList, settingsScroll,
                homeBar, privilegeBar, pluginBar, settingsBar
            )
        }

        // Geser kiri/kanan antar tab: konten mengikuti jari, tab tetangga ikut terlihat (preview),
        // lepas jari -> pindah kalau cukup jauh atau cukup cepat, kalau tidak balik ke semula.
        val swipeScope = rememberCoroutineScope()
        val density = LocalDensity.current
        val flingThresholdPx = with(density) { 600.dp.toPx() }
        val currentRoute = rememberUpdatedState(currentDestination?.route)
        val nextTab = swipeTabs.getOrNull(currentTabIndex + 1)?.takeIf { currentTabIndex >= 0 }
        val prevTab = swipeTabs.getOrNull(currentTabIndex - 1)?.takeIf { currentTabIndex >= 0 }
        val dragNeighbor by remember(swipeTabs, currentTabIndex) {
            derivedStateOf {
                val o = swipe.offsetPx
                when {
                    currentTabIndex < 0 -> null
                    o < 0f -> swipeTabs.getOrNull(currentTabIndex + 1)
                    o > 0f -> swipeTabs.getOrNull(currentTabIndex - 1)
                    else -> null
                }
            }
        }
        val previewTab = swipe.hold ?: dragNeighbor
        val blockTouches by remember {
            derivedStateOf { swipe.hold != null || swipe.offsetPx != 0f || swipe.settling }
        }
        val dragState = rememberDraggableState { delta ->
            swipe.onDrag(delta, hasNext = nextTab != null, hasPrev = prevTab != null)
        }

        Box {
            Scaffold(
                contentWindowInsets = WindowInsets()
            ) { innerPadding ->
                CompositionLocalProvider(
                    LocalSnackbarHost provides snackBarHostState,
                    LocalCapsuleInset provides capsuleInset,
                    LocalTabScrollStates provides tabScroll
                ) {
                    Box(
                        modifier = Modifier
                            .padding(innerPadding)
                            .then(
                                if (showBottomBar) {
                                    // Nav bar sudah ditangani kapsul + LocalCapsuleInset, jadi
                                    // Scaffold di tiap layar tidak perlu menambah padding bawah lagi.
                                    Modifier.consumeWindowInsets(
                                        WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
                                    )
                                } else Modifier
                            )
                            .onSizeChanged { swipe.width = it.width.toFloat() }
                            // Dipasang di parent, jadi komponen anak yang sudah memakai drag
                            // horizontal (slider, dll) tetap menang.
                            .draggable(
                                state = dragState,
                                orientation = Orientation.Horizontal,
                                enabled = showBottomBar && currentTabIndex >= 0,
                                onDragStarted = { swipe.beginDrag() },
                                onDragStopped = { velocity ->
                                    if (!swipe.handingOff && swipe.offsetPx != 0f) {
                                        val goingNext = swipe.offsetPx < 0f
                                        val target = if (goingNext) nextTab else prevTab
                                        val targetIndex = if (goingNext) currentTabIndex + 1 else currentTabIndex - 1
                                        swipe.job = swipeScope.launch {
                                            swipe.settle(
                                                velocity = velocity,
                                                flingThresholdPx = flingThresholdPx,
                                                target = target,
                                                targetIndex = targetIndex,
                                                routeNow = { currentRoute.value },
                                                navigateTo = { tab ->
                                                    navigator.navigate(tab.direction) {
                                                        popUpTo(NavGraphs.root) { saveState = true }
                                                        launchSingleTop = true
                                                        restoreState = true
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { translationX = swipe.offsetPx }
                        ) {
                            DestinationsNavHost(
                                navGraph = NavGraphs.root,
                                navController = navController,
                                dependenciesContainerBuilder = {
                                    dependency(viewModelGlobal)
                                },
                                defaultTransitions = navTransitions
                            )
                        }

                        // Preview tab tetangga: ikut bergeser bersama jari, menempel di sisinya.
                        previewTab?.let { tab ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        translationX = when {
                                            swipe.hold != null -> 0f
                                            swipe.offsetPx < 0f -> swipe.offsetPx + size.width
                                            else -> swipe.offsetPx - size.width
                                        }
                                    }
                            ) {
                                TabPreview(tab, navigator, viewModelGlobal)
                            }
                        }

                        if (blockTouches) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(Unit) {}
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = showBottomBar && !imeVisible,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                BottomBar(
                    navController,
                    navigator,
                    activateViewModel.axeronInfo,
                    pluginViewModel.pluginUpdateCount,
                    dimCapsule,
                    swipe
                )
            }
        }
    }

    /** Salinan tab tetangga yang hanya hidup selama jari menggeser (atau sesaat saat serah-terima). */
    @Composable
    private fun TabPreview(
        tab: BottomBarDestination,
        navigator: DestinationsNavigator,
        viewModelGlobal: ViewModelGlobal
    ) {
        // A peek under a moving page: still frames only, no GIF decoding while swiping.
        CompositionLocalProvider(LocalBannerAnimationAllowed provides false) {
            when (tab) {
                BottomBarDestination.Home -> HomeScreen(navigator, viewModelGlobal)
                BottomBarDestination.Privilege -> PrivilegeScreen(navigator, viewModelGlobal)
                BottomBarDestination.Plugin -> PluginScreen(navigator, viewModelGlobal)
                BottomBarDestination.Settings -> SettingsScreen(navigator, viewModelGlobal)
            }
        }
    }

    /**
     * Transisi navigasi ala Axora:
     * - pindah antar tab bottom bar: slide horizontal (spring) searah indeks tab
     * - masuk sub-screen: slide dari kanan; kembali: scale-out + fade
     */
    private fun createNavTransitions(
        bottomBarRoutes: Set<String>,
        skip: () -> Boolean
    ): NavHostAnimatedDestinationStyle {
        fun tabIndex(route: String?): Int =
            BottomBarDestination.entries.indexOfFirst { it.direction.route == route }

        return object : NavHostAnimatedDestinationStyle() {
            override val enterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = enter@{
                if (skip()) return@enter EnterTransition.None
                val targetRoute = targetState.destination.route
                if (targetRoute !in bottomBarRoutes) {
                    slideInHorizontally(initialOffsetX = { it })
                } else {
                    val from = tabIndex(initialState.destination.route)
                    val to = tabIndex(targetRoute)
                    if (from != -1 && to != -1) {
                        val spec = spring<IntOffset>(dampingRatio = 0.8f, stiffness = 300f)
                        if (to > from) slideInHorizontally(spec) { width -> width }
                        else slideInHorizontally(spec) { width -> -width }
                    } else {
                        fadeIn(animationSpec = tween(340))
                    }
                }
            }

            override val exitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = exit@{
                if (skip()) return@exit ExitTransition.None
                val initialRoute = initialState.destination.route
                val targetRoute = targetState.destination.route
                if (initialRoute in bottomBarRoutes && targetRoute !in bottomBarRoutes) {
                    slideOutHorizontally(targetOffsetX = { -it / 4 }) + fadeOut()
                } else if (initialRoute in bottomBarRoutes && targetRoute in bottomBarRoutes) {
                    val from = tabIndex(initialRoute)
                    val to = tabIndex(targetRoute)
                    if (from != -1 && to != -1) {
                        val spec = spring<IntOffset>(dampingRatio = 0.8f, stiffness = 300f)
                        if (to > from) slideOutHorizontally(spec) { width -> -width }
                        else slideOutHorizontally(spec) { width -> width }
                    } else {
                        fadeOut(animationSpec = tween(340))
                    }
                } else {
                    fadeOut(animationSpec = tween(340))
                }
            }

            override val popEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = popEnter@{
                if (skip()) return@popEnter EnterTransition.None
                if (targetState.destination.route in bottomBarRoutes) {
                    slideInHorizontally(initialOffsetX = { -it / 4 }) + fadeIn()
                } else {
                    fadeIn(animationSpec = tween(340))
                }
            }

            override val popExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = popExit@{
                if (skip()) return@popExit ExitTransition.None
                if (initialState.destination.route !in bottomBarRoutes) {
                    scaleOut(targetScale = 0.9f) + fadeOut()
                } else {
                    fadeOut(animationSpec = tween(340))
                }
            }
        }
    }

    @Composable
    private fun BottomBar(
        navController: NavHostController,
        navigator: DestinationsNavigator,
        axeronServerInfo: AxeronInfo,
        moduleUpdateCount: Int,
        dimmed: Boolean,
        swipe: TabSwipeState
    ) {
        // Saat [dimmed] (QuickShell / Settings Editor terbuka) kapsul meredup: badan dan isinya
        // memudar ke 0,85. Ukuran tidak berubah dan shadow dibiarkan penuh (alpha-nya sengaja
        // tidak dipasang di layer Surface, supaya shadow tidak ikut memudar).
        val fade by animateFloatAsState(
            targetValue = if (dimmed) 0.85f else 1f,
            animationSpec = tween(durationMillis = 350),
            label = "capsuleFade"
        )
        // 1f = normal, 0f = meredup. Area tab terpilih (pill + ikon + label) meredup lebih kuat
        // daripada badan kapsul, supaya tidak terlalu kontras.
        val activeAmount by animateFloatAsState(
            targetValue = if (dimmed) 0f else 1f,
            animationSpec = tween(durationMillis = 350),
            label = "capsuleActiveAmount"
        )

        Box(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(bottom = CapsuleGap)
        ) {
            Surface(
                shape = CircleShape,
                color = capsuleContainerColor().copy(alpha = fade),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shadowElevation = CapsuleShadow
            ) {
                val tabs = BottomBarDestination.entries.filter {
                    axeronServerInfo.isRunning() || !it.needAxeron
                }
                // key(): state tiap tab tetap stabil saat tab muncul/hilang (server hidup/mati).
                val selectedStates = tabs.map { tab ->
                    key(tab) { navController.isRouteOnBackStackAsState(tab.direction) }
                }
                val selectedIndex = selectedStates.indexOfFirst { it.value }
                val maxIdx = (tabs.size - 1).coerceAtLeast(0).toFloat()

                // Posisi indikator = indeks tab (0f..n-1f) + progres geser jari. Spring dipakai saat
                // tab berganti lewat tap; saat digeser/di-hold indikator langsung mengikuti jari.
                // Dibaca di fase layout/draw, tanpa recomposition per frame.
                LaunchedEffect(selectedIndex) {
                    if (selectedIndex < 0) return@LaunchedEffect
                    val to = selectedIndex.toFloat()
                    val from = swipe.pill.value
                    val restingAtTab = from == floor(from)
                    if (restingAtTab && abs(to - from) >= 1f && !swipe.scrubbing && swipe.offsetPx == 0f) {
                        // Pindah lewat tap: hanya tab asal (menyempit) dan tab tujuan (melebar)
                        // yang berubah; tab yang "dilewati" tidak ikut melebar, tidak ikut
                        // disorot, dan labelnya tidak digambar. Jauh lebih ringan di perangkat
                        // low-end daripada meluncur melewati tiap ikon.
                        swipe.tapFrom = from.toInt()
                        swipe.tapTo = selectedIndex
                        try {
                            swipe.pill.animateTo(
                                targetValue = to,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = 650f
                                )
                            )
                        } finally {
                            swipe.tapFrom = -1
                            swipe.tapTo = -1
                        }
                    } else {
                        swipe.pill.animateTo(
                            targetValue = to,
                            animationSpec = spring(
                                dampingRatio = 0.72f,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        )
                    }
                }
                // Digeser/di-hold: ikon di bawah indikator ikut disorot (preview nyambung).
                // Tap: sorotan langsung pindah ke tujuan, tanpa menyorot ikon yang dilewati.
                val highlighted by remember(selectedIndex, maxIdx) {
                    derivedStateOf {
                        when {
                            selectedIndex < 0 -> -1
                            swipe.isTapping -> swipe.tapTo
                            else -> swipe.position(maxIdx).roundToInt()
                        }
                    }
                }
                val goTo: (BottomBarDestination) -> Unit = { destination ->
                    navigator.navigate(destination.direction) {
                        popUpTo(NavGraphs.root) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
                val scope = rememberCoroutineScope()
                val haptic = LocalHapticFeedback.current
                val tabsNow = rememberUpdatedState(tabs)
                val selectedNow = rememberUpdatedState(selectedIndex)
                val grabScale by animateFloatAsState(
                    targetValue = if (swipe.scrubbing) 1.1f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "capsuleGrab"
                )
                val pillAlpha by animateFloatAsState(
                    targetValue = if (selectedIndex >= 0) 1f else 0f,
                    animationSpec = tween(durationMillis = 200),
                    label = "capsulePillAlpha"
                )

                // Lebar tiap item = lebar ikon + (label * seberapa "terpilih" item itu).
                val density = LocalDensity.current
                val textMeasurer = rememberTextMeasurer()
                val labelStyle = MaterialTheme.typography.labelLarge
                val labels = tabs.map { stringResource(id = it.labelId) }
                val metrics = remember(labels, labelStyle, density) {
                    with(density) {
                        val maxLabel = CapsuleLabelMax.toPx().toInt()
                        CapsuleMetrics(
                            basePx = CapsuleItemWidth.toPx(),
                            gapPx = CapsuleItemGap.toPx(),
                            extras = FloatArray(labels.size) { i ->
                                val w = textMeasurer.measure(
                                    text = labels[i],
                                    style = labelStyle,
                                    softWrap = false,
                                    maxLines = 1
                                ).size.width
                                CapsuleLabelGap.toPx() + w.coerceAtMost(maxLabel)
                            }
                        )
                    }
                }
                val pillColor = MaterialTheme.colorScheme.secondaryContainer
                // Posisi jari -> indeks indikator kontinu (indikator berpusat di bawah jari).
                val scrubIndexAt: (Float) -> Float = { x ->
                    metrics.pAtX(x, swipe, maxIdx)
                }
                val finishScrub: () -> Unit = {
                    val list = tabsNow.value
                    val from = swipe.scrubPos
                    val idx = from.roundToInt().coerceIn(0, (list.size - 1).coerceAtLeast(0))
                    scope.launch {
                        // Lanjut dari posisi jari tanpa lompat, lalu mendarat di tab tujuan.
                        swipe.pill.snapTo(from)
                        swipe.scrubbing = false
                        list.getOrNull(idx)?.let { dest ->
                            if (idx != selectedNow.value) goTo(dest)
                            swipe.pill.animateTo(
                                targetValue = idx.toFloat(),
                                animationSpec = spring(
                                    dampingRatio = 0.72f,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                        }
                    }
                }
                val cancelScrub: () -> Unit = {
                    val back = selectedNow.value.coerceAtLeast(0).toFloat()
                    scope.launch {
                        swipe.pill.snapTo(swipe.scrubPos)
                        swipe.scrubbing = false
                        swipe.pill.animateTo(
                            targetValue = back,
                            animationSpec = spring(
                                dampingRatio = 0.72f,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .graphicsLayer { this.alpha = fade }
                        .padding(6.dp)
                        // Pegang tab terpilih (pill) lalu geser kiri/kanan: lepas = pindah ke tab di
                        // bawahnya. Menggeser pill langsung "mengambilnya" tanpa menunggu; tertahan
                        // sebentar (CapsuleGrabDelayMs) juga mengambilnya (getar) agar siap digeser.
                        // Sentuhan di ikon lain / tap biasa tetap ditangani CapsuleItem.
                        .pointerInput(metrics) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (selectedNow.value < 0) return@awaitEachGesture
                                val startX = down.position.x
                                val slack = 6.dp.toPx()
                                val pillL = metrics.pillLeft(swipe, maxIdx)
                                val pillR = metrics.pillRight(swipe, maxIdx)
                                if (startX < pillL - slack || startX > pillR + slack) {
                                    return@awaitEachGesture
                                }

                                // Fase 1: bergeser melewati touch slop (langsung ambil) atau tertahan
                                // CapsuleGrabDelayMs (ambil). Jari diangkat lebih dulu = tap biasa.
                                var released = false
                                var lastX = startX
                                withTimeoutOrNull(CapsuleGrabDelayMs) {
                                    var done = false
                                    while (!done) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        if (change == null || change.changedToUpIgnoreConsumed()) {
                                            released = true
                                            done = true
                                        } else {
                                            lastX = change.position.x
                                            if (abs(lastX - startX) > viewConfiguration.touchSlop) {
                                                done = true
                                            }
                                        }
                                    }
                                }
                                if (released) return@awaitEachGesture

                                // Fase 2: pill dipegang. Posisinya tetap di bawah jari seperti saat
                                // disentuh (tidak melompat ke tengah jari).
                                swipe.job?.cancel()
                                val grabOffset = (pillL + pillR) / 2f - startX
                                swipe.scrubPos = scrubIndexAt(lastX + grabOffset)
                                swipe.scrubbing = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                var handled = false
                                try {
                                    while (!handled) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        when {
                                            change == null -> {
                                                handled = true
                                                cancelScrub()
                                            }
                                            change.changedToUpIgnoreConsumed() -> {
                                                change.consume()
                                                handled = true
                                                finishScrub()
                                            }
                                            else -> {
                                                change.consume()
                                                swipe.scrubPos = scrubIndexAt(change.position.x + grabOffset)
                                            }
                                        }
                                    }
                                } finally {
                                    if (!handled) cancelScrub()
                                }
                            }
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(CapsuleItemGap),
                        modifier = Modifier.drawBehind {
                            // Indikator terpilih: satu pill yang meluncur dan melebar mengikuti
                            // item yang sedang "terpilih" (ikon + label).
                            if (metrics.count == 0) return@drawBehind
                            val l = metrics.pillLeft(swipe, maxIdx)
                            val r = metrics.pillRight(swipe, maxIdx)
                            val h = size.height * grabScale
                            val w = (r - l) * grabScale
                            val cx = (l + r) / 2f
                            drawRoundRect(
                                color = pillColor,
                                topLeft = Offset(cx - w / 2f, (size.height - h) / 2f),
                                size = Size(w, h),
                                cornerRadius = CornerRadius(h / 2f, h / 2f),
                                alpha = pillAlpha * lerp(CapsuleIdlePillAlpha, 1f, activeAmount)
                            )
                        }
                    ) {
                        tabs.forEachIndexed { index, destination ->
                            CapsuleItem(
                                destination = destination,
                                label = labels[index],
                                selected = index == highlighted,
                                badgeCount = if (destination == BottomBarDestination.Plugin) moduleUpdateCount else 0,
                                contentAlpha = { isSelected ->
                                    if (isSelected) lerp(CapsuleIdleContentAlpha, CapsuleSelectedAlpha, activeAmount)
                                    else lerp(CapsuleIdleIconAlpha, CapsuleIconAlpha, activeAmount)
                                },
                                widthPx = {
                                    if (selectedIndex < 0) metrics.basePx.roundToInt()
                                    else metrics.width(index, swipe, maxIdx).roundToInt()
                                },
                                expansion = {
                                    if (selectedIndex < 0) 0f else swipe.expansion(index, maxIdx)
                                },
                                onClick = {
                                    // Klik yang menyusul setelah hold-geser tidak boleh ikut dieksekusi.
                                    if (!swipe.scrubbing) {
                                        if (index == selectedIndex) {
                                            navigator.popBackStack(destination.direction, false)
                                        }
                                        goTo(destination)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Satu item kapsul: ikon di kiri (posisinya tetap), label muncul di kanan ikon saat item
     * terpilih. Lebar item mengikuti [widthPx]; label bergeser dari ikon ke kanan sambil
     * memudar masuk (dipotong oleh batas item yang ikut melebar).
     */
    @Composable
    private fun CapsuleItem(
        destination: BottomBarDestination,
        label: String,
        selected: Boolean,
        badgeCount: Int,
        contentAlpha: (selected: Boolean) -> Float,
        widthPx: () -> Int,
        expansion: () -> Float,
        onClick: () -> Unit
    ) {
        val tint by animateColorAsState(
            targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            animationSpec = tween(durationMillis = 250),
            label = "capsuleItemTint"
        )
        // "Pop" kecil pada ikon yang baru dipilih.
        val pop by animateFloatAsState(
            targetValue = if (selected) 1.12f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "capsuleItemPop"
        )
        val labelColor = MaterialTheme.colorScheme.onSecondaryContainer
        val slidePx = with(LocalDensity.current) { 14.dp.toPx() }

        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .layout { measurable, _ ->
                    val w = widthPx()
                    val h = CapsuleItemHeight.roundToPx()
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(w, h) { placeable.place(0, 0) }
                }
                .clip(CircleShape)
                .clickable(onClick = onClick)
        ) {
            Box(modifier = Modifier.padding(start = CapsuleIconStart)) {
                val iconContent: @Composable () -> Unit = {
                    Crossfade(
                        targetState = selected,
                        animationSpec = tween(durationMillis = 180),
                        label = "capsuleItemIcon"
                    ) { isSelected ->
                        Icon(
                            imageVector = if (isSelected) destination.iconSelected else destination.iconNotSelected,
                            contentDescription = label,
                            tint = tint,
                            modifier = Modifier.graphicsLayer {
                                scaleX = pop
                                scaleY = pop
                                // Ikon diredupkan sedikit (lebih kuat saat kapsul idle) supaya tidak terlalu kontras.
                                alpha = contentAlpha(isSelected)
                            }
                        )
                    }
                }
                if (badgeCount > 0) {
                    BadgedBox(badge = { Badge { Text(badgeCount.toString()) } }) { iconContent() }
                } else {
                    iconContent()
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = labelColor,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .padding(start = CapsuleIconStart + CapsuleIconSize + CapsuleLabelGap)
                    // Ukuran asli teks dipertahankan; yang terlihat dibatasi lebar item.
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .graphicsLayer {
                        val e = expansion()
                        alpha = e * contentAlpha(true)
                        translationX = -(1f - e) * slidePx
                    }
            )
        }
    }
}

/**
 * State geser antar tab. [offsetPx] = pergeseran konten (negatif = menuju tab berikutnya),
 * [pill] = posisi indikator kapsul (indeks tab), [hold] = tab tujuan yang dipertahankan
 * sebagai preview sesaat sampai NavHost benar-benar menampilkannya.
 */
@Stable
private class TabSwipeState {
    var offsetPx by mutableFloatStateOf(0f)
    var width by mutableFloatStateOf(0f)
    var hold by mutableStateOf<BottomBarDestination?>(null)
    val pill = Animatable(0f)

    /** Animasi lepas-jari sedang jalan; boleh disela jari berikutnya kapan saja. */
    var settling by mutableStateOf(false)

    /** Serah-terima ke tab asli (beberapa frame saja); satu-satunya fase yang tidak bisa disela. */
    var handingOff = false
    var job: Job? = null
    private var generation = 0

    /** Dibaca di transisi NavHost; true = pindah tab tanpa animasi (animasinya sudah dari jari). */
    var skipNavTransition = false

    /** Hold-lalu-geser pada kapsul: pill mengikuti jari, lepas = buka tab yang dituju. */
    var scrubbing by mutableStateOf(false)
    var scrubPos by mutableFloatStateOf(0f)

    /** Animasi tap di kapsul: dari tab [tapFrom] ke [tapTo] (-1 = tidak sedang tap). */
    var tapFrom by mutableIntStateOf(-1)
    var tapTo by mutableIntStateOf(-1)

    val isTapping: Boolean
        get() = tapFrom >= 0 && tapTo >= 0 && tapFrom != tapTo && !scrubbing && offsetPx == 0f

    private fun tapT(): Float = ((pill.value - tapFrom) / (tapTo - tapFrom).toFloat()).coerceIn(0f, 1f)

    /** Seberapa "terpilih" (melebar + label tampak) item [index]: 0f..1f. */
    fun expansion(index: Int, maxIndex: Float): Float =
        if (isTapping) {
            when (index) {
                tapTo -> tapT()
                tapFrom -> 1f - tapT()
                else -> 0f
            }
        } else {
            max(0f, 1f - abs(position(maxIndex) - index))
        }

    /** Dua item yang diapit indikator dan pecahan di antaranya (untuk menggambar pill). */
    fun spanFrom(maxIndex: Float): Int =
        if (isTapping) tapFrom else floor(position(maxIndex)).toInt().coerceIn(0, maxIndex.toInt())

    fun spanTo(maxIndex: Float): Int =
        if (isTapping) tapTo else min(spanFrom(maxIndex) + 1, maxIndex.toInt())

    fun spanFraction(maxIndex: Float): Float =
        if (isTapping) tapT() else (position(maxIndex) - spanFrom(maxIndex)).coerceIn(0f, 1f)

    /** Posisi indikator kapsul sebagai indeks kontinu: hold-geser > tap/spring + progres geser konten. */
    fun position(maxIndex: Float): Float =
        (if (scrubbing) scrubPos else pill.value + progress).coerceIn(0f, maxIndex)

    /** Progres geser -1f..1f (positif = menuju tab berikutnya), dipakai pill. */
    val progress: Float
        get() = if (width > 0f) (-offsetPx / width).coerceIn(-1f, 1f) else 0f

    /** Jari baru menyentuh saat animasi sebelumnya belum selesai: batalkan, lanjut dari posisi sekarang. */
    fun beginDrag() {
        if (!handingOff) job?.cancel()
    }

    fun onDrag(delta: Float, hasNext: Boolean, hasPrev: Boolean) {
        if (handingOff || width <= 0f) return
        val raw = offsetPx + delta
        val allowed = if (raw < 0f) hasNext else hasPrev
        offsetPx = if (allowed) {
            raw.coerceIn(-width, width)
        } else {
            // Tidak ada tab di sisi itu: tahan, hanya menggeser sedikit (efek karet).
            (offsetPx + delta * 0.25f).coerceIn(-width * 0.12f, width * 0.12f)
        }
    }

    suspend fun settle(
        velocity: Float,
        flingThresholdPx: Float,
        target: BottomBarDestination?,
        targetIndex: Int,
        routeNow: () -> String?,
        navigateTo: (BottomBarDestination) -> Unit
    ) {
        val mine = ++generation
        settling = true
        try {
            val w = width
            if (w <= 0f) {
                offsetPx = 0f
                return
            }
            val toLeft = offsetPx < 0f
            val farEnough = abs(offsetPx) > w * 0.33f
            val flung = abs(offsetPx) > 8f && abs(velocity) > flingThresholdPx &&
                (velocity < 0f) == toLeft
            // visibilityThreshold dalam px: tanpa ini spring Float baru "selesai" di 0,01 px,
            // ekor animasi yang tak terlihat itu yang bikin pindah tab terasa menunggu.
            val spec = spring<Float>(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = 800f,
                visibilityThreshold = 1f
            )

            if (target == null || !(farEnough || flung)) {
                animate(offsetPx, 0f, velocity, spec) { v, _ -> offsetPx = v }
                offsetPx = 0f
                return
            }

            animate(offsetPx, if (toLeft) -w else w, velocity, spec) { v, _ -> offsetPx = v }

            // Serah-terima (tidak bisa dibatalkan): preview tujuan menutupi layar di posisi 0,
            // NavHost pindah tab tanpa animasi, preview dilepas setelah tab asli tampil.
            withContext(NonCancellable) {
                handingOff = true
                try {
                    settling = false
                    hold = target
                    skipNavTransition = true
                    pill.snapTo(targetIndex.toFloat())
                    offsetPx = 0f
                    navigateTo(target)
                    withTimeoutOrNull(400) {
                        snapshotFlow { routeNow() }.first { it == target.direction.route }
                    }
                    repeat(2) { withFrameNanos { } }
                } finally {
                    hold = null
                    skipNavTransition = false
                    handingOff = false
                }
            }
        } finally {
            if (generation == mine) settling = false
        }
    }
}

// Ukuran & posisi kapsul nav. CapsuleGap = jarak kapsul dari tepi bawah (di atas nav bar sistem)
// supaya benar-benar melayang; CapsuleReserve = ruang yang disisakan untuk konten di bawahnya.
// Ukuran kapsul nav. CapsuleItemWidth = lebar item saat hanya ikon; item terpilih melebar
// untuk label. CapsuleGap = jarak kapsul dari tepi bawah (di atas nav bar sistem) supaya
// benar-benar melayang; CapsuleReserve = ruang yang dipakai konten sebagai padding bawah.
private val CapsuleItemWidth = 58.dp
private val CapsuleItemHeight = 56.dp
private val CapsuleItemGap = 4.dp
private val CapsuleIconSize = 24.dp
private val CapsuleIconStart = (CapsuleItemWidth - CapsuleIconSize) / 2
private val CapsuleLabelGap = 8.dp
private val CapsuleLabelMax = 96.dp
private val CapsuleGap = 24.dp
private val CapsuleShadow = 10.dp

// Tertahan selama ini di atas pill terpilih = pill "diambil" dan siap digeser (menggeser lebih dulu
// langsung mengambilnya tanpa menunggu).
private const val CapsuleGrabDelayMs = 140L

// Keterlihatan isi kapsul (1f = tidak diredupkan). Aktif: ikon biasa 0,75, ikon + label terpilih 0,92.
// Idle: pill terpilih 0,45, ikon + label terpilih 0,7, ikon biasa 0,6.
private const val CapsuleIdlePillAlpha = 0.45f
private const val CapsuleIdleContentAlpha = 0.7f
private const val CapsuleIdleIconAlpha = 0.6f
private const val CapsuleSelectedAlpha = 0.92f
private const val CapsuleIconAlpha = 0.75f
private val CapsuleReserve = CapsuleItemHeight + 12.dp + CapsuleGap

/**
 * Geometri kapsul. Item ke-i melebar sebesar extras[i] * expansion(i); indikator = interpolasi
 * tepi dua item yang diapitnya. Semua dihitung dari [TabSwipeState] (mode tap atau mode jari).
 */
private class CapsuleMetrics(
    val basePx: Float,
    val gapPx: Float,
    val extras: FloatArray
) {
    val count: Int get() = extras.size

    fun width(index: Int, sw: TabSwipeState, maxIdx: Float): Float =
        basePx + sw.expansion(index, maxIdx) * extras[index]

    fun left(index: Int, sw: TabSwipeState, maxIdx: Float): Float {
        var x = 0f
        for (j in 0 until index) x += width(j, sw, maxIdx) + gapPx
        return x
    }

    private fun right(index: Int, sw: TabSwipeState, maxIdx: Float): Float =
        left(index, sw, maxIdx) + width(index, sw, maxIdx)

    fun pillLeft(sw: TabSwipeState, maxIdx: Float): Float {
        val i0 = sw.spanFrom(maxIdx).coerceIn(0, count - 1)
        val i1 = sw.spanTo(maxIdx).coerceIn(0, count - 1)
        return lerp(left(i0, sw, maxIdx), left(i1, sw, maxIdx), sw.spanFraction(maxIdx))
    }

    fun pillRight(sw: TabSwipeState, maxIdx: Float): Float {
        val i0 = sw.spanFrom(maxIdx).coerceIn(0, count - 1)
        val i1 = sw.spanTo(maxIdx).coerceIn(0, count - 1)
        return lerp(right(i0, sw, maxIdx), right(i1, sw, maxIdx), sw.spanFraction(maxIdx))
    }

    /** Kebalikan dari pusat indikator: posisi x (px, relatif ke tepi kiri baris item) -> indeks kontinu. */
    fun pAtX(x: Float, sw: TabSwipeState, maxIdx: Float): Float {
        if (count <= 1) return 0f
        val c = FloatArray(count) { left(it, sw, maxIdx) + width(it, sw, maxIdx) / 2f }
        if (x <= c[0]) return 0f
        if (x >= c[count - 1]) return (count - 1).toFloat()
        var i = 0
        while (i < count - 2 && x >= c[i + 1]) i++
        return i + (x - c[i]) / (c[i + 1] - c[i])
    }
}
