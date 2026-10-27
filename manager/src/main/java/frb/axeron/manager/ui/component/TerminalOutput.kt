package frb.axeron.manager.ui.component

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import kotlinx.coroutines.channels.Channel
import android.os.Build
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fox2code.androidansi.ktx.parseAsAnsiAnnotatedString
import frb.axeron.manager.R
import frb.axeron.manager.ui.util.ClipboardUtil
import frb.axeron.manager.ui.util.TerminalBuffer
import frb.axeron.manager.ui.util.TerminalPrefs
import androidx.compose.ui.graphics.TransformOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext

/** Default text style for command output, scaled by [TerminalPrefs.textScale]. */
@Composable
fun terminalTextStyle(scale: Float = TerminalPrefs.textScale): TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp * scale,
    lineHeight = 16.sp * scale,
    color = MaterialTheme.colorScheme.onSurfaceVariant
)

/**
 * Renders a [TerminalBuffer]: line numbers, follow-the-bottom, a fast-scroll thumb for huge
 * output and a side pill (top / go to line[:column] / bottom).
 *
 * Index based: only the visible lines are read from the buffer, so a million spooled lines
 * scroll like twenty. View options (line numbers, text scale, unlimited lines) come from
 * [TerminalPrefs].
 *
 * @param wrap soft-wrap long lines; when false the text pans horizontally while the line
 * numbers stay put.
 * @param filter hides lines by kind (e.g. stderr outside developer mode). Remember it.
 * @param color per-kind base colour; ANSI colours in the text still win.
 * @param searchOpen shows the find bar (query, current/total, previous / next). Search is only
 * offered when [onSearchOpenChange] is set, so the owner needs no top bar button for it.
 *
 * Text selection is built in: long-press selects a word, the handles extend it (the list
 * scrolls while a handle rests near an edge) and the bar on top offers Copy / Select all.
 * The selection is anchored to buffer positions, so it covers lines that are not composed.
 */
@Composable
fun <K : Any> TerminalOutput(
    buffer: TerminalBuffer<K>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    wrap: Boolean = true,
    filter: ((K) -> Boolean)? = null,
    color: @Composable (K) -> Color = { MaterialTheme.colorScheme.onSurfaceVariant },
    jumpButtonPadding: PaddingValues = PaddingValues(0.dp),
    /** Center the nav pill on a 56dp FAB that sits at the bottom end (16dp margin). */
    jumpButtonAlignWithFab: Boolean = false,
    state: LazyListState = rememberLazyListState(),
    searchOpen: Boolean = false,
    onSearchOpenChange: ((Boolean) -> Unit)? = null,
) {
    val showLineNumbers = TerminalPrefs.lineNumbers
    val style = terminalTextStyle()

    // The storage mode follows the shared preference, also for output already on screen.
    val unlimited = TerminalPrefs.unlimited
    LaunchedEffect(buffer, unlimited) { buffer.setUnlimited(unlimited) }

    val index = remember(buffer, filter) { filter?.let { FilterIndex(it) } }
    val view by remember(buffer, index) {
        derivedStateOf {
            val snap = buffer.snapshot
            index?.view(snap, buffer) ?: LineView(snap.size, snap.head, null, 0)
        }
    }
    val version = buffer.snapshot.version

    // ---- scroll to the end ----
    val density = LocalDensity.current
    var containerHeight by remember { mutableIntStateOf(0) }
    // Between the top and bottom padding: where the lines are read.
    val areaPx = (containerHeight - with(density) { contentPadding.calculateTopPadding().toPx() } -
            with(density) { contentPadding.calculateBottomPadding().toPx() }).coerceAtLeast(0f)
    val lineHeightPx = with(density) { style.lineHeight.toPx() }
    val currentArea by rememberUpdatedState(areaPx)
    val currentLineHeight by rememberUpdatedState(lineHeightPx)

    /**
     * The last line at the bottom of the list. One scroll with a negative offset (the list fills
     * the lines above it), not a jump to the very end with a huge offset: that drops every line
     * on screen and composes them all again, on each batch of a flood.
     */
    suspend fun scrollToEnd() {
        val info = state.layoutInfo
        val total = info.totalItemsCount
        if (total == 0) return
        // A wrapped last line can be taller than one line; its height is known once it is shown.
        val lastHeight = info.visibleItemsInfo.lastOrNull()
            ?.takeIf { it.index == total - 1 }?.size?.toFloat() ?: currentLineHeight
        state.scrollToItem(total - 1, -(currentArea - lastHeight).coerceAtLeast(0f).roundToInt())
    }

    // ---- follow mode ----
    var follow by remember { mutableStateOf(true) }
    // Held scroll thumb: it decides where the list is, following must not fight it.
    var thumbDragging by remember { mutableStateOf(false) }
    val atBottom by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index == info.totalItemsCount - 1 &&
                    last.offset + last.size <= info.viewportEndOffset + 8
        }
    }
    val dragged by state.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged, atBottom, thumbDragging) {
        if (thumbDragging) return@LaunchedEffect
        if (atBottom) follow = true
        else if (dragged) follow = false
    }
    // Once per published snapshot (throttled by the buffer), not per chunk. Jump instantly:
    // rapid output would keep cancelling an animated scroll. Keyed on the list's own item count
    // too: the snapshot can arrive before the list has laid the new lines out, and then the
    // scroll above would land one batch short for good once the output stops.
    LaunchedEffect(state, buffer) {
        snapshotFlow {
            Triple(state.layoutInfo.totalItemsCount, buffer.snapshot.version, follow && !thumbDragging)
        }.collect { (total, _, following) ->
            if (following && total > 0) scrollToEnd()
        }
    }

    // ---- horizontal panning (no-wrap) ----
    val pan = remember { HorizontalPan() }
    LaunchedEffect(wrap) { pan.reset() }
    LaunchedEffect(buffer) {
        snapshotFlow { buffer.snapshot.isEmpty }.collect { if (it) pan.reset() }
    }
    val panState = rememberScrollableState { delta ->
        val old = pan.offset
        pan.scrollTo(old - delta)
        old - pan.offset
    }

    // ---- gutter ----
    val measurer = rememberTextMeasurer()
    val charWidth = remember(style, density) { measurer.measure("0", style).size.width }
    val digits = maxOf(3, buffer.snapshot.tail.toString().length)
    val gutterWidth = with(density) { (charWidth * digits).toDp() }
    val gutterStyle = style.copy(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
        textAlign = TextAlign.End
    )

    // ---- go to line ----
    var highlight by remember { mutableStateOf<Mark?>(null) }
    LaunchedEffect(highlight) {
        if (highlight != null) {
            delay(2500)
            highlight = null
        }
    }
    val rowHighlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val columnHighlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    var showGoTo by remember { mutableStateOf(false) }
    var pillExpanded by remember { mutableStateOf(false) }
    var pillTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // ---- find in output ----
    val searchEnabled = onSearchOpenChange != null
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf(emptyList<TerminalBuffer.Match>()) }
    var current by remember { mutableIntStateOf(-1) }
    val matchHighlight = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)
    val currentHighlight = MaterialTheme.colorScheme.primary
    val onCurrentHighlight = MaterialTheme.colorScheme.onPrimary
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Closing the bar (from anywhere) forgets the query, so highlights disappear with it.
    LaunchedEffect(searchOpen) { if (!searchOpen) query = "" }

    /** Scrolls [match] into view with some context above, panning to it when not wrapping. */
    suspend fun reveal(match: TerminalBuffer.Match) {
        val v = view
        if (v.count == 0) return
        follow = false
        state.scrollToItem((v.indexOf(match.position) - 3).coerceAtLeast(0))
        if (!wrap) {
            withFrameNanos { } // let the line measure its width first
            pan.scrollTo(match.start * charWidth - pan.viewport / 3f)
        }
    }

    // Re-runs as output streams in; collectLatest drops a search that new output made stale.
    LaunchedEffect(buffer, filter, query) {
        if (query.isEmpty()) {
            matches = emptyList()
            current = -1
            return@LaunchedEffect
        }
        var fresh = true
        snapshotFlow { buffer.snapshot.version }.collectLatest {
            delay(if (fresh) 200 else 400) // typing / live-output debounce
            val found = withContext(Dispatchers.Default) {
                val job = coroutineContext
                buffer.search(query, filter ?: { true }, limit = SEARCH_LIMIT, stop = { !job.isActive })
            }
            matches = found
            if (fresh) {
                // Start from the first match on screen or below it, like find-in-page.
                val top = view.let { v ->
                    if (v.count == 0) 0L else v.positionAt(state.firstVisibleItemIndex.coerceIn(0, v.count - 1))
                }
                current = found.indexOfFirst { it.position >= top }.let { if (it < 0) 0 else it }
                found.getOrNull(current)?.let { reveal(it) }
                fresh = false
            } else {
                current = current.coerceAtMost(found.lastIndex)
            }
        }
    }

    fun step(delta: Int) {
        if (matches.isEmpty()) return
        current = Math.floorMod(current + delta, matches.size)
        val target = matches[current]
        scope.launch { reveal(target) }
    }

    fun closeSearch() {
        keyboard?.hide()
        focusManager.clearFocus()
        onSearchOpenChange?.invoke(false)
    }
    BackHandler(enabled = searchOpen && searchEnabled) { closeSearch() }

    // ---- text selection ----
    // Anchored to buffer positions, not to composed lines (a SelectionContainer only knows the
    // lines on screen and breaks when its anchors scroll away): it survives any scroll, and Copy
    // reads every line in between from the buffer, composed or not.
    var selection by remember { mutableStateOf<TextSelection?>(null) }
    val registry = remember { LineRegistry() }
    val drag = remember { SelectionDrag(scope, state, registry) }
    val selectionColors = LocalTextSelectionColors.current
    SideEffect {
        with(density) {
            drag.topEdge = (contentPadding.calculateTopPadding() + 32.dp).toPx()
            drag.bottomInset = (contentPadding.calculateBottomPadding() + 32.dp).toPx()
        }
    }
    // Positions are reused after a clear or screen clear.
    LaunchedEffect(buffer) {
        snapshotFlow { buffer.snapshot.generation }.collect { selection = null }
    }
    BackHandler(enabled = selection != null) { selection = null }
    // Whoever opens the find bar (the nav pill, the owner), the bar on top must be the find bar.
    LaunchedEffect(searchOpen) { if (searchOpen) selection = null }

    /** Starts dragging one end of the selection; the other end stays put. */
    fun beginHandleDrag(isStart: Boolean, finger: Offset): Offset? {
        val sel = selection ?: return null
        val mine = if (isStart) sel.start else sel.end
        val other = if (isStart) sel.end else sel.start
        val cursor = registry.cursorAt(mine) ?: return null
        drag.start(cursor.center) { selection = TextSelection(other, it) }
        // Keep the finger's distance to the cursor, so the hit point stays on the line itself.
        return cursor.center - finger
    }

    val context = LocalContext.current
    val copied = stringResource(R.string.copied)
    val truncatedText = stringResource(R.string.terminal_copy_truncated, COPY_LIMIT)
    fun copySelection() {
        val sel = selection ?: return
        val v = view
        val start = sel.start
        val end = sel.end
        var truncated = false
        val text = buildString {
            var first = true
            var i = v.indexOf(start.position)
            while (i < v.count) {
                val position = v.positionAt(i++)
                if (position > end.position) break
                val line = buffer.lineAt(position)?.text?.toAnsiString()?.text ?: continue
                if (!first) append('\n')
                first = false
                sel.rangeIn(position, line.length)?.let { append(line, it.start, it.end) }
                if (length >= COPY_LIMIT) {
                    setLength(COPY_LIMIT)
                    truncated = true
                    break
                }
            }
        }
        if (!ClipboardUtil.put(context, text)) return
        selection = null
        when {
            truncated -> Toast.makeText(context, truncatedText, Toast.LENGTH_LONG).show()
            // Android 13+ shows its own clipboard confirmation.
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ->
                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
        }
    }

    val canScroll by remember {
        derivedStateOf { state.canScrollBackward || state.canScrollForward }
    }
    val showScrollbar = canScroll && view.count > SCROLLBAR_MIN_LINES

    Box(
        modifier
            .onSizeChanged { containerHeight = it.height }
            .onPlaced { registry.container = it }
    ) {
        LazyColumn(
            state = state,
            contentPadding = contentPadding,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (wrap) Modifier
                    else Modifier.scrollable(panState, Orientation.Horizontal)
                )
                // A tap clears the selection; a long press is only there so it isn't a tap.
                .pointerInput(registry) {
                    detectTapGestures(onLongPress = {}, onTap = { selection = null })
                }
                // Long press selects the word under it, dragging on extends it.
                .pointerInput(registry) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                        val word = registry.anchorAt(press.position)?.let(registry::wordAt)
                            ?: return@awaitEachGesture
                        follow = false
                        selection = word
                        drag.start(press.position) {
                            selection = if (it < word.start) TextSelection(word.end, it)
                            else TextSelection(word.start, it)
                        }
                        // Ours from here, read before the list sees it so it doesn't scroll.
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes
                                .firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                            drag.moveTo(change.position)
                        }
                        drag.stop()
                    }
                }
        ) {
            val shown = view
            items(
                count = shown.count,
                key = { shown.positionAt(it) },
                contentType = { 0 }
            ) { i ->
                val position = shown.positionAt(i)
                // Re-read on every publish: an open line (progress bar) changes in place.
                val line = remember(position, version) { buffer.lineAt(position) }
                if (line == null) {
                    // Gone before the next snapshot (clear, screen clear): keep one line's
                    // height. A zero-height item makes a scrolling list compose every
                    // remaining item in one frame to fill the viewport (ANR on big output).
                    BasicText("", style = style)
                    return@items
                }
                // Where this line is laid out, for mapping touches to columns.
                val entry = remember(position) { LineRegistry.Entry() }
                DisposableEffect(position) {
                    registry.lines[position] = entry
                    onDispose { if (registry.lines[position] === entry) registry.lines.remove(position) }
                }
                val mark = highlight?.takeIf { it.line == line.number }
                val currentStart = matches.getOrNull(current)
                    ?.takeIf { it.position == position }?.start ?: -1
                val parsed = remember(line.text) { line.text.toAnsiString() }
                val selected = selection?.rangeIn(position, parsed.length)
                val text = remember(parsed, mark, query, currentStart, selected) {
                    var t = parsed
                    if (mark?.column != null) t = t.withColumnMark(mark.column - 1, columnHighlight)
                    if (query.isNotEmpty()) {
                        t = t.withMatches(
                            query, matchHighlight, currentStart,
                            SpanStyle(background = currentHighlight, color = onCurrentHighlight)
                        )
                    }
                    if (selected != null) t = t.withSelection(selected, selectionColors.backgroundColor)
                    t
                }
                val marked = mark != null || currentStart >= 0
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (marked) Modifier.background(rowHighlight) else Modifier)
                ) {
                    if (showLineNumbers) {
                        BasicText(
                            text = line.number.toString(),
                            style = gutterStyle,
                            maxLines = 1,
                            modifier = Modifier.width(gutterWidth)
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    BasicText(
                        text = text,
                        style = style.copy(color = color(line.kind)),
                        softWrap = wrap,
                        onTextLayout = { entry.layout = it },
                        modifier = Modifier
                            .weight(1f)
                            .then(if (wrap) Modifier else Modifier.horizontalPan(pan))
                            // After the pan: these coordinates move with the text.
                            .onPlaced { entry.coords = it }
                    )
                }
            }
        }

        if (selection != null) {
            listOf(true, false).forEach { isStart ->
                var grab by remember { mutableStateOf(Offset.Zero) }
                SelectionHandle(
                    isStart = isStart,
                    color = selectionColors.handleColor,
                    container = { registry.container },
                    cursor = {
                        // Read while placing, so the handle follows scrolling and panning.
                        state.layoutInfo
                        pan.offset
                        selection?.let { registry.cursorAt(if (isStart) it.start else it.end) }
                    },
                    onDown = { finger -> beginHandleDrag(isStart, finger)?.let { grab = it } },
                    onMove = { finger -> drag.moveTo(finger + grab) },
                    onUp = { drag.stop() }
                )
            }
        }

        if (showScrollbar) {
            FastScrollbar(
                state = state,
                count = view.count,
                onDragStart = {
                    follow = false
                    thumbDragging = true
                },
                onDragEnd = { thumbDragging = false },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(contentPadding.let {
                        PaddingValues(top = it.calculateTopPadding(), bottom = it.calculateBottomPadding())
                    })
            )
        }

        // Nav capsule: small and dimmed by default; a tap opens it, and it folds back after a
        // moment without interaction or as soon as the user scrolls the output (it would
        // otherwise sit on top of the text being read).
        LaunchedEffect(state) {
            state.interactionSource.interactions.collect {
                if (it is DragInteraction.Start) pillExpanded = false
            }
        }
        LaunchedEffect(pillTick) {
            if (pillTick == 0) return@LaunchedEffect
            pillExpanded = true
            delay(PILL_IDLE_MS)
            snapshotFlow { state.isScrollInProgress }.first { !it }
            pillExpanded = false
        }

        val currentLine by remember {
            derivedStateOf {
                val v = view
                if (v.count == 0) null
                else v.positionAt(state.firstVisibleItemIndex.coerceIn(0, v.count - 1)) + 1
            }
        }

        // One slot for the bars on top, so they swap instead of stacking: the selection bar
        // wins while text is selected, and the find bar comes back once it is gone.
        val topBar = when {
            selection != null -> TopBar.Selection
            searchOpen && searchEnabled -> TopBar.Search
            else -> TopBar.None
        }
        AnimatedContent(
            targetState = topBar,
            transitionSpec = {
                val origin = TransformOrigin(0.5f, 0f)
                // The old bar is gone before the new one fades in: no overlap in between.
                (fadeIn(tween(160, delayMillis = 90)) +
                        scaleIn(tween(160, delayMillis = 90), initialScale = 0.92f, transformOrigin = origin))
                    .togetherWith(
                        fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.92f, transformOrigin = origin)
                    )
                    .using(SizeTransform(clip = false))
            },
            contentAlignment = Alignment.TopCenter,
            label = "terminalTopBar",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = contentPadding.calculateTopPadding())
                .padding(horizontal = 4.dp)
        ) { bar ->
            when (bar) {
                TopBar.Selection -> TerminalSelectionBar(
                    onCopy = { copySelection() },
                    onSelectAll = {
                        val v = view
                        if (v.count > 0) {
                            selection = TextSelection(
                                Anchor(v.positionAt(0), 0),
                                Anchor(v.positionAt(v.count - 1), Int.MAX_VALUE)
                            )
                        }
                    },
                    onClose = { selection = null }
                )

                TopBar.Search -> TerminalSearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    current = current,
                    total = matches.size,
                    capped = matches.size >= SEARCH_LIMIT,
                    onPrevious = { step(-1) },
                    onNext = { step(1) },
                    onClose = { closeSearch() }
                )

                TopBar.None -> Box(Modifier.size(0.dp))
            }
        }

        AnimatedVisibility(
            visible = view.count > 0 && (canScroll || searchEnabled),
            enter = fadeIn() + scaleIn(initialScale = 0.7f, transformOrigin = TransformOrigin(1f, 1f)),
            exit = fadeOut() + scaleOut(targetScale = 0.7f, transformOrigin = TransformOrigin(1f, 1f)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(jumpButtonPadding)
                .then(
                    if (jumpButtonAlignWithFab) {
                        // Same 56dp column as a FloatingActionButton: the pill is centered on it
                        // however wide the line number gets, so the two line up.
                        Modifier
                            .padding(end = 16.dp, bottom = 12.dp)
                            .width(56.dp)
                            .wrapContentWidth(Alignment.CenterHorizontally, unbounded = true)
                    } else {
                        Modifier
                            .padding(12.dp)
                            // Stay clear of the scrollbar thumb.
                            .padding(end = if (showScrollbar) 14.dp else 0.dp)
                    }
                )
        ) {
            TerminalNavPill(
                currentLine = currentLine,
                following = follow,
                canScrollUp = state.canScrollBackward,
                expanded = pillExpanded,
                emphasized = thumbDragging || (searchOpen && searchEnabled),
                searchEnabled = searchEnabled,
                onExpand = { pillTick++ },
                onSearch = {
                    pillTick++
                    // The find bar may already be "open" behind the selection bar: no change
                    // of searchOpen would follow, so drop the selection right here.
                    selection = null
                    onSearchOpenChange?.invoke(true)
                },
                onTop = {
                    pillTick++
                    follow = false
                    scope.launch { state.scrollToItem(0) }
                },
                onGoTo = {
                    pillTick++
                    showGoTo = true
                },
                onBottom = {
                    pillTick++
                    follow = true
                    scope.launch { scrollToEnd() }
                }
            )
        }
    }

    if (showGoTo && view.count > 0) {
        val v = view
        GoToLineDialog(
            first = v.positionAt(0) + 1,
            last = v.positionAt(v.count - 1) + 1,
            onDismiss = { showGoTo = false },
            onGo = { target, column ->
                showGoTo = false
                val i = v.indexOf(target - 1)
                follow = false
                scope.launch {
                    // A few lines of context above the target.
                    state.scrollToItem((i - 3).coerceAtLeast(0))
                    highlight = Mark(v.positionAt(i) + 1, column)
                    if (!wrap) {
                        withFrameNanos { } // let the target line measure its width first
                        val x = column?.let { (it - 1) * charWidth - pan.viewport / 3f } ?: 0f
                        pan.scrollTo(x)
                    }
                }
            }
        )
    }
}

private const val SCROLLBAR_MIN_LINES = 200
private const val PILL_IDLE_MS = 2500L

/** Matches kept per search; enough to step through, bounded for million-line output. */
private const val SEARCH_LIMIT = 10_000

/** Longest copy: the clipboard goes over binder, which fails past about 1 MB. */
private const val COPY_LIMIT = 200_000

/** Which buffer positions are on screen, in order. */
@Immutable
private class LineView(
    val count: Int,
    private val head: Long,
    private val positions: LongArray?,
    private val start: Int,
) {
    fun positionAt(i: Int): Long = if (positions == null) head + i else positions[start + i]

    /** Index of the first shown line at or after [position], clamped to the view. */
    fun indexOf(position: Long): Int {
        if (count == 0) return 0
        if (positions == null) return (position - head).coerceIn(0, count - 1L).toInt()
        var lo = 0
        var hi = count - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (positions[start + mid] < position) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

/**
 * Positions of the lines passing a filter, extended incrementally per snapshot: only new lines
 * are scanned, so filtering stays cheap with a million lines. Rebuilt when positions were
 * dropped or reused (snapshot generation).
 */
private class FilterIndex<K : Any>(private val filter: (K) -> Boolean) {
    private var positions = LongArray(1024)
    private var size = 0
    private var start = 0
    private var scanned = 0L
    private var generation = -1

    fun view(snap: TerminalBuffer.Snapshot, buffer: TerminalBuffer<K>): LineView {
        if (generation != snap.generation) {
            // New array: views handed out earlier keep reading the old one.
            positions = LongArray(1024)
            size = 0
            start = 0
            scanned = snap.head
            generation = snap.generation
        }
        if (scanned < snap.head) scanned = snap.head
        if (scanned < snap.tail) {
            buffer.forEachKind(scanned, snap.tail) { position, kind ->
                if (filter(kind)) {
                    if (size == positions.size) positions = positions.copyOf(size * 2)
                    positions[size++] = position
                }
            }
            scanned = snap.tail
        }
        while (start < size && positions[start] < snap.head) start++
        return LineView(size - start, snap.head, positions, start)
    }
}

/**
 * Draggable thumb on the right edge; maps the touch position straight to a line.
 *
 * A move only asks for a scroll: the latest target goes into a conflated channel and ONE
 * collector applies it, at most once per frame. Launching a scroll per touch event queued them
 * up behind each other (every one composes a whole screen of new lines), so the list kept
 * catching up with positions the finger had left long ago. The gesture is not keyed on the
 * line count either: output that keeps arriving would restart it and drop the finger.
 */
@Composable
private fun FastScrollbar(
    state: LazyListState,
    count: Int,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val thumbHeight = 44.dp
    val thumbPx = with(density) { thumbHeight.toPx() }
    var trackPx by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val currentCount by rememberUpdatedState(count)
    val currentDragStart by rememberUpdatedState(onDragStart)
    val currentDragEnd by rememberUpdatedState(onDragEnd)

    val targets = remember { Channel<Int>(Channel.CONFLATED) }
    LaunchedEffect(state, targets) {
        for (target in targets) {
            state.scrollToItem(target)
            withFrameNanos { } // next frame at the earliest: never two screens of text per frame
        }
    }
    // Removed mid-drag (output cleared, list too short now): don't leave the owner holding on.
    DisposableEffect(Unit) {
        onDispose { if (dragging) currentDragEnd() }
    }

    val scrollFraction by remember(count) {
        derivedStateOf {
            val visible = state.layoutInfo.visibleItemsInfo.size
            val max = (count - visible).coerceAtLeast(1)
            (state.firstVisibleItemIndex.toFloat() / max).coerceIn(0f, 1f)
        }
    }
    val fraction = if (dragging) dragFraction else scrollFraction
    val active = dragging || state.isScrollInProgress
    val alpha by animateFloatAsState(if (active) 0.9f else 0.35f, label = "thumbAlpha")
    val width by animateDpAsState(if (dragging) 8.dp else 4.dp, label = "thumbWidth")
    val thumbColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(22.dp)
            .onSizeChanged { trackPx = it.height }
            .pointerInput(thumbPx) {
                fun jump(y: Float) {
                    val range = (trackPx - thumbPx).coerceAtLeast(1f)
                    dragFraction = ((y - thumbPx / 2) / range).coerceIn(0f, 1f)
                    targets.trySend((dragFraction * (currentCount - 1)).roundToInt().coerceAtLeast(0))
                }
                detectVerticalDragGestures(
                    onDragStart = {
                        dragging = true
                        currentDragStart()
                        jump(it.y)
                    },
                    onDragEnd = {
                        dragging = false
                        currentDragEnd()
                    },
                    onDragCancel = {
                        dragging = false
                        currentDragEnd()
                    }
                ) { change, _ ->
                    change.consume()
                    jump(change.position.y)
                }
            }
    ) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, (fraction * (trackPx - thumbPx).coerceAtLeast(0f)).roundToInt()) }
                .padding(end = 4.dp)
                .width(width)
                .height(thumbHeight)
                .clip(CircleShape)
                .background(thumbColor.copy(alpha = alpha))
        )
    }
}

private enum class TopBar { None, Selection, Search }

/** Shown while text is selected: copy / select all / clear. */
@Composable
private fun TerminalSelectionBar(
    onCopy: () -> Unit,
    onSelectAll: () -> Unit,
    onClose: () -> Unit,
) {
    val content = MaterialTheme.colorScheme.onSecondaryContainer
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = content,
        shadowElevation = 3.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(44.dp)
                .padding(horizontal = 2.dp)
        ) {
            TextButton(onClick = onCopy) {
                Icon(Icons.Outlined.ContentCopy, null, tint = content, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.copy), color = content)
            }
            TextButton(onClick = onSelectAll) {
                Icon(Icons.Filled.SelectAll, null, tint = content, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.terminal_select_all), color = content)
            }
            IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
            }
        }
    }
}

/**
 * One end of the selection, hanging under its cursor; dragged to move that end. Not placed
 * while its line is off screen (the selection itself stays).
 */
@Composable
private fun SelectionHandle(
    isStart: Boolean,
    color: Color,
    container: () -> LayoutCoordinates?,
    /** The cursor of this end in container coordinates; read while placing. */
    cursor: () -> Rect?,
    onDown: (finger: Offset) -> Unit,
    onMove: (finger: Offset) -> Unit,
    onUp: () -> Unit,
) {
    val down by rememberUpdatedState(onDown)
    val move by rememberUpdatedState(onMove)
    val up by rememberUpdatedState(onUp)
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // The point sits at the cursor: top-end corner for the start, top-start for the end.
    val shape = if (isStart) RoundedCornerShape(50, 0, 50, 50) else RoundedCornerShape(0, 50, 50, 50)

    fun toContainer(local: Offset): Offset? {
        val box = container()?.takeIf { it.isAttached } ?: return null
        val self = coords?.takeIf { it.isAttached } ?: return null
        return box.localPositionOf(self, local)
    }

    Box(
        Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(placeable.width, placeable.height) {
                    val at = cursor() ?: return@layout
                    val bounds = container()?.size ?: return@layout
                    if (at.bottom < 0 || at.bottom > bounds.height || at.left < 0 || at.left > bounds.width) {
                        return@layout
                    }
                    val x = if (isStart) at.left - placeable.width else at.left
                    placeable.place(x.roundToInt(), at.bottom.roundToInt())
                }
            }
            .onPlaced { coords = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val first = awaitFirstDown()
                    first.consume()
                    toContainer(first.position)?.let(down)
                    drag(first.id) { change ->
                        change.consume()
                        toContainer(change.position)?.let(move)
                    }
                    up()
                }
            }
            .size(22.dp)
            .background(color, shape)
    )
}

/** A column in a line; [offset] counts the text without ANSI codes, as it is laid out. */
@Immutable
private data class Anchor(val position: Long, val offset: Int) : Comparable<Anchor> {
    override fun compareTo(other: Anchor): Int =
        compareValuesBy(this, other, { it.position }, { it.offset })
}

/** From [fixed] to [moving], in either order: dragging one end past the other just flips it. */
@Immutable
private data class TextSelection(val fixed: Anchor, val moving: Anchor) {
    val start: Anchor get() = minOf(fixed, moving)
    val end: Anchor get() = maxOf(fixed, moving)

    /** The selected part of the line at [position] of [length] characters, if any. */
    fun rangeIn(position: Long, length: Int): TextRange? {
        val s = start
        val e = end
        if (position < s.position || position > e.position) return null
        val from = if (position == s.position) s.offset.coerceIn(0, length) else 0
        val to = if (position == e.position) e.offset.coerceIn(0, length) else length
        return if (from < to) TextRange(from, to) else null
    }
}

/** Where the composed lines are laid out, so a touch point maps to a buffer position and column. */
private class LineRegistry {
    class Entry {
        var coords: LayoutCoordinates? = null
        var layout: TextLayoutResult? = null
    }

    /** Composed lines only, by buffer position. */
    val lines = HashMap<Long, Entry>()
    var container: LayoutCoordinates? = null

    /** The column under [point] (container coordinates) in the nearest composed line. */
    fun anchorAt(point: Offset): Anchor? {
        val box = container?.takeIf { it.isAttached } ?: return null
        var best: Anchor? = null
        var bestDistance = Float.MAX_VALUE
        for ((position, entry) in lines) {
            val coords = entry.coords?.takeIf { it.isAttached } ?: continue
            val layout = entry.layout ?: continue
            val local = coords.localPositionOf(box, point)
            val height = coords.size.height.toFloat()
            val distance = when {
                local.y < 0 -> -local.y
                local.y > height -> local.y - height
                else -> 0f
            }
            if (distance < bestDistance) {
                bestDistance = distance
                val y = local.y.coerceIn(0f, (height - 1).coerceAtLeast(0f))
                best = Anchor(position, layout.getOffsetForPosition(Offset(local.x, y)))
            }
        }
        return best
    }

    /** The cursor at [anchor] in container coordinates, or null when its line isn't composed. */
    fun cursorAt(anchor: Anchor): Rect? {
        val box = container?.takeIf { it.isAttached } ?: return null
        val entry = lines[anchor.position] ?: return null
        val coords = entry.coords?.takeIf { it.isAttached } ?: return null
        val layout = entry.layout ?: return null
        val rect = layout.getCursorRect(anchor.offset.coerceIn(0, layout.layoutInput.text.length))
        return Rect(box.localPositionOf(coords, rect.topLeft), rect.size)
    }

    /** The word at [anchor], or the one character there when it isn't in a word. */
    fun wordAt(anchor: Anchor): TextSelection {
        val layout = lines[anchor.position]?.layout
        val length = layout?.layoutInput?.text?.length ?: 0
        if (layout == null || length == 0) return TextSelection(anchor, anchor)
        val word = layout.getWordBoundary(anchor.offset.coerceIn(0, length - 1))
        val start = word.start
        val end = if (word.end > start) word.end else (start + 1).coerceAtMost(length)
        return TextSelection(Anchor(anchor.position, start), Anchor(anchor.position, end))
    }
}

/**
 * Moves one end of the selection to the finger, scrolling the list while the finger rests near
 * its top or bottom edge, so a selection can run past the screen.
 */
private class SelectionDrag(
    private val scope: CoroutineScope,
    private val state: LazyListState,
    private val registry: LineRegistry,
) {
    /** Scrolling starts above [topEdge] and within [bottomInset] of the bottom, in px. */
    var topEdge = 0f
    var bottomInset = 0f

    private var point: Offset? = null
    private var mover: ((Anchor) -> Unit)? = null
    private var job: Job? = null

    fun start(at: Offset, move: (Anchor) -> Unit) {
        point = at
        mover = move
        job?.cancel()
        job = scope.launch {
            while (true) {
                withFrameNanos { }
                val p = point ?: break
                val height = registry.container?.size?.height ?: break
                val bottomEdge = height - bottomInset
                val over = when {
                    p.y < topEdge -> p.y - topEdge
                    p.y > bottomEdge -> p.y - bottomEdge
                    else -> 0f
                }
                if (over != 0f) {
                    state.scrollBy((over / 2).coerceIn(-MAX_STEP, MAX_STEP))
                    update()
                }
            }
        }
    }

    fun moveTo(at: Offset) {
        point = at
        update()
    }

    fun stop() {
        point = null
        mover = null
        job?.cancel()
        job = null
    }

    private fun update() {
        val p = point ?: return
        registry.anchorAt(p)?.let { mover?.invoke(it) }
    }

    private companion object {
        /** Fastest auto-scroll, px per frame. */
        const val MAX_STEP = 60f
    }
}

/** Find bar: query, "current/total" and previous / next / close. */
@Composable
private fun TerminalSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    current: Int,
    total: Int,
    capped: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val content = MaterialTheme.colorScheme.onSecondaryContainer
    val textStyle = MaterialTheme.typography.bodyMedium.copy(color = content)

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(44.dp)
                .padding(start = 14.dp, end = 2.dp)
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = content.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onNext() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.terminal_search_hint),
                                style = textStyle,
                                color = content.copy(alpha = 0.6f),
                                maxLines = 1
                            )
                        }
                        inner()
                    }
                }
            )
            if (query.isNotEmpty()) {
                Text(
                    text = if (total == 0) "0/0"
                    else "${current + 1}/$total${if (capped) "+" else ""}",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = if (total == 0) MaterialTheme.colorScheme.error else content,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            IconButton(onClick = onPrevious, enabled = total > 0, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.terminal_search_previous)
                )
            }
            IconButton(onClick = onNext, enabled = total > 0, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.terminal_search_next)
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
            }
        }
    }
}

/**
 * Navigation capsule. Folded (default) it is a small dimmed chip with the current line, so it
 * covers as little output as possible; [expanded] unfolds search / top / go-to / bottom with a
 * soft size animation and full opacity. The owner folds it again after a moment without
 * interaction or when the output is scrolled.
 */
@Composable
private fun TerminalNavPill(
    currentLine: Long?,
    following: Boolean,
    canScrollUp: Boolean,
    expanded: Boolean,
    emphasized: Boolean,
    searchEnabled: Boolean,
    onExpand: () -> Unit,
    onSearch: () -> Unit,
    onTop: () -> Unit,
    onGoTo: () -> Unit,
    onBottom: () -> Unit,
) {
    val alpha by animateFloatAsState(
        targetValue = if (expanded || emphasized) 1f else 0.88f,
        animationSpec = tween(durationMillis = 320),
        label = "pillAlpha"
    )
    val elevation by animateDpAsState(
        targetValue = if (expanded) 8.dp else 3.dp,
        animationSpec = tween(durationMillis = 280),
        label = "pillElevation"
    )
    val content = MaterialTheme.colorScheme.onSecondaryContainer
    val sizeSpec = spring<IntSize>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
    val enter = fadeIn(tween(220)) + expandVertically(spring(stiffness = Spring.StiffnessMediumLow))
    val exit = fadeOut(tween(140)) + shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow))

    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = elevation,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
        modifier = Modifier.graphicsLayer { this.alpha = alpha }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.animateContentSize(sizeSpec)
        ) {
            AnimatedVisibility(visible = expanded, enter = enter, exit = exit) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (searchEnabled) {
                        IconButton(onClick = onSearch) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = stringResource(R.string.terminal_search)
                            )
                        }
                    }
                    IconButton(onClick = onTop, enabled = canScrollUp) {
                        Icon(
                            Icons.Filled.KeyboardDoubleArrowUp,
                            contentDescription = stringResource(R.string.terminal_scroll_top)
                        )
                    }
                }
            }
            // Always visible: folded, a tap opens the capsule; opened, a tap goes to a line.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable { if (expanded) onGoTo() else onExpand() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                AnimatedVisibility(visible = expanded, enter = enter, exit = exit) {
                    Text(
                        text = stringResource(R.string.terminal_line_short),
                        style = MaterialTheme.typography.labelSmall,
                        color = content.copy(alpha = 0.85f)
                    )
                }
                Text(
                    text = currentLine?.toString() ?: "–",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = content
                )
            }
            AnimatedVisibility(visible = expanded, enter = enter, exit = exit) {
                IconButton(onClick = onBottom) {
                    Icon(
                        Icons.Filled.KeyboardDoubleArrowDown,
                        contentDescription = stringResource(R.string.terminal_scroll_bottom),
                        // Highlighted while new output is not being followed.
                        tint = if (following) content else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

private val GOTO_REGEX = Regex("""^(\d+)(?:\s*[:.,\s]\s*(\d+))?$""")

@Composable
private fun GoToLineDialog(
    first: Long,
    last: Long,
    onDismiss: () -> Unit,
    onGo: (line: Long, column: Int?) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val parsed = remember(input) {
        GOTO_REGEX.matchEntire(input.trim())?.let { m ->
            val line = m.groupValues[1].toLongOrNull() ?: return@let null
            val column = m.groupValues[2].toIntOrNull()?.takeIf { it > 0 }
            line to column
        }
    }
    val submit = { parsed?.let { onGo(it.first, it.second) } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.terminal_goto_title)) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                placeholder = { Text("100:20") },
                isError = input.isNotBlank() && parsed == null,
                supportingText = { Text(stringResource(R.string.terminal_goto_range, first, last)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier.focusRequester(focus)
            )
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = parsed != null) {
                Text(stringResource(R.string.terminal_goto))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

private data class Mark(val line: Long, val column: Int?)

/**
 * Shared horizontal offset for every line. Lines read [offset] only while placing, so panning
 * re-places the visible lines without recomposing or re-measuring the list.
 */
@Stable
private class HorizontalPan {
    var offset by mutableFloatStateOf(0f)
        private set

    /** Widest line measured so far; plain fields, written during layout. */
    var contentWidth = 0
    var viewport = 0

    fun scrollTo(x: Float) {
        offset = x.coerceIn(0f, (contentWidth - viewport).coerceAtLeast(0).toFloat())
    }

    fun reset() {
        contentWidth = 0
        offset = 0f
    }
}

private fun Modifier.horizontalPan(pan: HorizontalPan) = clipToBounds().layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
    if (placeable.width > pan.contentWidth) pan.contentWidth = placeable.width
    pan.viewport = width
    layout(width, placeable.height) {
        placeable.placeRelative(-pan.offset.roundToInt(), 0)
    }
}

private const val ESC = '\u001B'

/** Most lines carry no ANSI codes; skip the parser for those. */
private fun String.toAnsiString(): AnnotatedString =
    if (indexOf(ESC) < 0) AnnotatedString(this) else parseAsAnsiAnnotatedString()

/**
 * Marks every occurrence of [query] (ignoring case) with [color]; the one starting at
 * [currentStart] gets [currentStyle] instead.
 */
private fun AnnotatedString.withMatches(
    query: String,
    color: Color,
    currentStart: Int,
    currentStyle: SpanStyle
): AnnotatedString {
    var i = text.indexOf(query, ignoreCase = true)
    if (i < 0) return this
    val source = this
    return buildAnnotatedString {
        append(source)
        while (i >= 0) {
            val style = if (i == currentStart) currentStyle else SpanStyle(background = color)
            addStyle(style, i, i + query.length)
            i = source.text.indexOf(query, i + query.length, ignoreCase = true)
        }
    }
}

private fun AnnotatedString.withSelection(range: TextRange, color: Color): AnnotatedString {
    val source = this
    return buildAnnotatedString {
        append(source)
        addStyle(SpanStyle(background = color), range.start, range.end)
    }
}

private fun AnnotatedString.withColumnMark(index: Int, color: Color): AnnotatedString {
    if (index !in indices) return this
    val source = this
    return buildAnnotatedString {
        append(source)
        addStyle(SpanStyle(background = color), index, index + 1)
    }
}
