package com.itexpert120.attendanceManager.ui.components

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.io.File

/** A4 portrait width / height; landscape pages use the inverse. */
private const val A4_RATIO = 210f / 297f

/** Widest bitmap a page is rendered at, so a zoomed page stays sharp without huge bitmaps. */
private const val MAX_RENDER_WIDTH_PX = 2200

/**
 * Renders the pages of one PDF file. [PdfRenderer] allows a single open page at a time, so every render
 * goes through [mutex]; rendering runs on the IO dispatcher.
 */
internal class PdfPageRenderer(file: File) : AutoCloseable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    private val mutex = Mutex()
    private var closed = false

    val pageCount: Int get() = renderer.pageCount

    suspend fun render(index: Int, widthPx: Int): ImageBitmap? = mutex.withLock {
        if (closed) return@withLock null
        withContext(Dispatchers.IO) {
            renderer.openPage(index).use { page ->
                val width = widthPx.coerceIn(1, MAX_RENDER_WIDTH_PX)
                val height = (width.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                // PDF pages are transparent where nothing is drawn; paper is white.
                bitmap.eraseColor(AndroidColor.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap.asImageBitmap()
            }
        }
    }

    override fun close() {
        // Waits for a running render so the renderer is never closed under it.
        kotlinx.coroutines.runBlocking {
            mutex.withLock {
                closed = true
                renderer.close()
                descriptor.close()
            }
        }
    }
}

/**
 * The export sheet's preview: the pages side by side in a pager, each a scaled-down sheet of paper.
 * Tapping a page (or the expand button) opens [PdfFullScreenPreview] on that page.
 */
@Composable
internal fun PdfPreviewStrip(file: File, pages: Int, landscape: Boolean, title: String) {
    val renderer = rememberPdfRenderer(file) ?: return
    val pagerState = rememberPagerState { pages }
    var fullScreenPage by remember(file) { mutableStateOf<Int?>(null) }
    val pageRatio = if (landscape) 1f / A4_RATIO else A4_RATIO

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            HorizontalPager(
                state = pagerState,
                // Neighbouring pages peek in, which tells the user there is more to swipe to.
                contentPadding = PaddingValues(horizontal = if (pages > 1) 32.dp else 0.dp),
                pageSpacing = 12.dp,
                modifier = Modifier.fillMaxWidth().height(if (landscape) 220.dp else 320.dp),
            ) { index ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PdfPageImage(
                        renderer = renderer,
                        index = index,
                        description = "Page ${index + 1} of $pages. Tap to view full screen.",
                        modifier = Modifier
                            .aspectRatio(pageRatio, matchHeightConstraintsFirst = !landscape)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClickLabel = "View full screen") { fullScreenPage = index },
                    )
                }
            }
            FilledTonalIconButton(
                onClick = { fullScreenPage = pagerState.currentPage },
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(40.dp),
            ) { Icon(Icons.Rounded.OpenInFull, contentDescription = "View full screen") }
        }
        Text(
            if (pages == 1) "Tap the page to zoom" else "Page ${pagerState.currentPage + 1} of $pages · swipe for more, tap to zoom",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
    }

    fullScreenPage?.let { start ->
        PdfFullScreenPreview(
            renderer = renderer,
            pages = pages,
            startPage = start,
            ratio = pageRatio,
            title = title,
            onClose = { page ->
                fullScreenPage = null
                pagerState.requestScrollToPage(page)
            },
        )
    }
}

@Composable
private fun rememberPdfRenderer(file: File): PdfPageRenderer? {
    val renderer = remember(file) { runCatching { PdfPageRenderer(file) }.getOrNull() }
    DisposableEffect(renderer) { onDispose { renderer?.close() } }
    return renderer
}

/**
 * One page on white paper. The caller sizes it (with [Modifier.aspectRatio]); the page is rendered at the
 * laid-out width, rounded up to [RENDER_STEP_PX] so a pinch in progress doesn't re-render every frame.
 */
@Composable
private fun PdfPageImage(
    renderer: PdfPageRenderer,
    index: Int,
    description: String,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.background(Color.White).semantics { contentDescription = description }) {
        val laidOut = constraints.maxWidth
        val widthPx = ((laidOut + RENDER_STEP_PX - 1) / RENDER_STEP_PX) * RENDER_STEP_PX
        val bitmap by produceState<ImageBitmap?>(null, renderer, index, widthPx) { value = renderer.render(index, widthPx) }
        bitmap?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        }
    }
}

private const val RENDER_STEP_PX = 400
private const val MAX_ZOOM = 4f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val ZOOM_STEP = 1.5f

/**
 * Full-screen pages, one under the other, on a dark backdrop. Pinch or double-tap to zoom; zooming lays the
 * pages out wider, so the ordinary vertical and horizontal scrolling pans around a zoomed page.
 */
@Composable
private fun PdfFullScreenPreview(
    renderer: PdfPageRenderer,
    pages: Int,
    startPage: Int,
    ratio: Float,
    title: String,
    onClose: (page: Int) -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startPage)
    val horizontal = rememberScrollState()
    val scope = rememberCoroutineScope()
    var zoom by remember { mutableFloatStateOf(1f) }
    val close = { onClose(listState.firstVisibleItemIndex) }
    var viewportWidth by remember { mutableIntStateOf(0) }

    /** Zooms around the horizontal middle of the view. */
    fun setZoom(target: Float) {
        val old = zoom
        zoom = target.coerceIn(1f, MAX_ZOOM)
        val mid = horizontal.value + viewportWidth / 2f
        scope.launch { horizontal.scrollTo((mid * zoom / old - viewportWidth / 2f).toInt()) }
    }

    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BackHandler(onBack = close)
        Surface(color = Color(0xFF1B1B1F), contentColor = Color.White, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                    IconButton(onClick = close) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Close preview") }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(
                            "Page ${listState.firstVisibleItemIndex + 1} of $pages · ${(zoom * 100).roundToInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                    // Buttons as well as pinch / double-tap, for one-handed and accessible zooming.
                    IconButton(onClick = { setZoom(zoom / ZOOM_STEP) }, enabled = zoom > 1f) {
                        Icon(Icons.Rounded.ZoomOut, contentDescription = "Zoom out")
                    }
                    IconButton(onClick = { setZoom(zoom * ZOOM_STEP) }, enabled = zoom < MAX_ZOOM) {
                        Icon(Icons.Rounded.ZoomIn, contentDescription = "Zoom in")
                    }
                }
                BoxWithConstraints(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clipToBounds()
                        .pointerInput(Unit) {
                            // Initial pass: a two-finger pinch is taken before the scroll containers see it.
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.changes.count { it.pressed } >= 2) {
                                        val change = event.calculateZoom()
                                        if (change != 1f) setZoom(zoom * change)
                                        event.changes.forEach { it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = { tap ->
                                    val old = zoom
                                    zoom = if (zoom > 1f) 1f else DOUBLE_TAP_ZOOM
                                    scope.launch { horizontal.scrollTo(((horizontal.value + tap.x) * zoom / old - tap.x).toInt()) }
                                },
                            )
                        },
                ) {
                    viewportWidth = constraints.maxWidth
                    val pageWidth = (maxWidth - 32.dp) * zoom
                    Box(Modifier.fillMaxSize().horizontalScroll(horizontal, enabled = zoom > 1f)) {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.width(pageWidth + 32.dp).fillMaxHeight(),
                        ) {
                            items(pages, key = { it }) { index ->
                                PdfPageImage(
                                    renderer = renderer,
                                    index = index,
                                    description = "Page ${index + 1} of $pages",
                                    modifier = Modifier.width(pageWidth).aspectRatio(ratio),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
