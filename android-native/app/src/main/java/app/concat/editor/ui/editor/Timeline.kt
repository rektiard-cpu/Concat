// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.ui.editor

import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.concat.editor.api.Clip
import app.concat.editor.api.ClipKind
import app.concat.editor.api.ProjectView
import app.concat.editor.media.MediaCache
import app.concat.editor.media.StripKey
import app.concat.editor.ui.icons.ConcatIcons
import app.concat.editor.ui.theme.ConcatColors
import app.concat.editor.ui.theme.Dimens
import app.concat.editor.ui.theme.Inter
import app.concat.editor.ui.theme.KindColors
import app.concat.editor.ui.theme.Motion
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface LaneTarget {
    data class Existing(val trackId: String) : LaneTarget
    data object NewTop : LaneTarget
    data object NewBottom : LaneTarget
}

private enum class FeatureGroup(val label: String) {
    Effects("Effects"),
    Sticker("Sticker"),
    Text("Text"),
    Overlay("Overlay"),
    Main("Video"),
    Audio("Audio"),
}

private data class DragPreview(
    val clipId: String,
    val start: Double,
    val duration: Double,
    val row: Int,
    val snappedAt: Double?,
)

private class FeatureLaneRow(
    val group: FeatureGroup,
    val trackId: String,
    val subLane: Int,
    val subLaneCount: Int,
    val expanded: Boolean,
    val top: Float,
    val height: Float,
) {
    val bottom get() = top + height
    val centre get() = top + height / 2f
    val main get() = group == FeatureGroup.Main
}

private class LaneLayout(
    val rows: List<FeatureLaneRow>,
    val clipSubLane: Map<String, Int>,
    val gap: Float,
    val contentHeight: Float,
) {
    val top get() = rows.firstOrNull()?.top ?: 0f
    val bottom get() = rows.lastOrNull()?.bottom ?: 0f
    val main get() = rows.firstOrNull { it.main }

    fun rowAt(y: Float): Int {
        if (rows.isEmpty()) return 0
        if (y < top - gap) return 0
        rows.forEachIndexed { i, row -> if (y < row.bottom + gap / 2f) return i }
        return rows.size - 1
    }

    fun rowForClip(clip: Clip, mainTrackId: String?): FeatureLaneRow? {
        val group = featureGroupOf(clip, mainTrackId)
        val sub = clipSubLane[clip.id] ?: 0
        return rows.firstOrNull { it.group == group && it.subLane == sub }
            ?: rows.firstOrNull { it.group == group }
    }

    fun rowIndexForClip(clip: Clip, mainTrackId: String?): Int {
        val target = rowForClip(clip, mainTrackId) ?: return 0
        return rows.indexOf(target).coerceAtLeast(0)
    }
}

private const val MIN_LENGTH = 1.0 / 60

private val COLOR_TEXT_IMPORTED = Color(0xFF2563EB)   // Biru: hasil import SRT/ASS
private val COLOR_TEXT_DEFAULT = Color(0xFFEC4899)    // Pink: teks default / add text biasa
private val COLOR_TEXT_DUPLICATE = Color(0xFFEAB308)  // Kuning: teks duplikat
private val COLOR_MARKER = Color(0xFF38BDF8)          // Biru terang: penanda irama / beat marker

fun mainTrackIndex(view: ProjectView): Int {
    val pictures = view.clips.filter { it.kind == ClipKind.Video || it.kind == ClipKind.Image }.map { it.trackId }.toSet()
    return view.tracks.indexOfFirst { it.id in pictures }.takeIf { it >= 0 } ?: 0
}

private fun featureGroupOf(clip: Clip, mainTrackId: String?): FeatureGroup = when {
    clip.kind == ClipKind.Text -> FeatureGroup.Text
    clip.kind == ClipKind.Shape -> FeatureGroup.Sticker
    clip.kind == ClipKind.Audio || clip.kind == ClipKind.SoundLayer -> FeatureGroup.Audio
    clip.kind == ClipKind.Layer || (clip.kind == ClipKind.Transition && clip.trackId != mainTrackId) -> FeatureGroup.Effects
    clip.trackId == mainTrackId -> FeatureGroup.Main
    else -> FeatureGroup.Overlay
}

private class TimelineGlyphs(
    val text: VectorPainter,
    val music: VectorPainter,
    val image: VectorPainter,
    val film: VectorPainter,
    val transition: VectorPainter,
    val sparkle: VectorPainter,
    val plus: VectorPainter,
    val volume: VectorPainter,
    val volumeOff: VectorPainter,
    val filter: VectorPainter,
    val shapes: VectorPainter,
)

/** Cache pengukuran teks agar playback timeline berjalan mulus tanpa alokasi ulang tiap frame. */
private class TextLayoutCache(private val measurer: TextMeasurer) {
    private val cache = LruCache<String, TextLayoutResult>(256)

    fun measure(text: String, style: TextStyle, maxWidth: Int = Int.MAX_VALUE): TextLayoutResult {
        val key = "$text|${style.fontSize.value}|${style.color.value}|$maxWidth"
        cache.get(key)?.let { return it }
        val measured = measurer.measure(
            text = text,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            constraints = if (maxWidth == Int.MAX_VALUE) Constraints() else Constraints(maxWidth = maxWidth.coerceAtLeast(1)),
        )
        cache.put(key, measured)
        return measured
    }
}

@Composable
fun Timeline(
    view: ProjectView,
    projectPath: String,
    media: MediaCache,
    position: Double,
    selected: String?,
    clipRoles: Map<String, String> = emptyMap(),
    markers: List<Double> = emptyList(),
    onScrub: (Double) -> Unit,
    onSelect: (String?) -> Unit,
    onMove: (clipId: String, start: Double, target: LaneTarget) -> Unit,
    onTrim: (clipId: String, edge: String, delta: Double) -> Unit,
    onAddMedia: () -> Unit,
    onTransition: (nextClipId: String) -> Unit,
    onMuteTrack: (trackId: String, muted: Boolean) -> Unit,
    onCover: () -> Unit,
    modifier: Modifier = Modifier,
    cover: ImageBitmap? = null,
) {
    var pxPerSecond by remember { mutableFloatStateOf(0f) }
    var drag by remember { mutableStateOf<DragPreview?>(null) }
    var fling by remember { mutableStateOf<Job?>(null) }
    var scrollY by remember { mutableStateOf<Float?>(null) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer()
    val textCache = remember(measurer) { TextLayoutCache(measurer) }
    val reusablePath = remember { Path() }
    val currentView by rememberUpdatedState(view)
    val currentPosition by rememberUpdatedState(position)
    val currentSelected by rememberUpdatedState(selected)
    val currentMarkers by rememberUpdatedState(markers)
    val scrub by rememberUpdatedState(onScrub)
    val choose by rememberUpdatedState(onSelect)
    val move by rememberUpdatedState(onMove)
    val trim by rememberUpdatedState(onTrim)
    val addMedia by rememberUpdatedState(onAddMedia)
    val transition by rememberUpdatedState(onTransition)
    val muteTrack by rememberUpdatedState(onMuteTrack)
    val openCover by rememberUpdatedState(onCover)

    val selection = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected != null) {
            selection.snapTo(0f)
            selection.animateTo(1f, Motion.tweenShort())
        }
    }
    val knob = remember { Animatable(0f) }

    val glyphs = TimelineGlyphs(
        text = rememberVectorPainter(ConcatIcons.TextMark),
        music = rememberVectorPainter(ConcatIcons.Music),
        image = rememberVectorPainter(ConcatIcons.Image),
        film = rememberVectorPainter(ConcatIcons.Film),
        transition = rememberVectorPainter(ConcatIcons.Transition),
        sparkle = rememberVectorPainter(ConcatIcons.Sparkle),
        plus = rememberVectorPainter(ConcatIcons.Plus),
        volume = rememberVectorPainter(ConcatIcons.Volume),
        volumeOff = rememberVectorPainter(ConcatIcons.VolumeOff),
        filter = rememberVectorPainter(ConcatIcons.Filter),
        shapes = rememberVectorPainter(ConcatIcons.Shapes),
    )

    Canvas(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                fun scaleNow() = if (pxPerSecond <= 0f) 64.dp.toPx() else pxPerSecond
                val snapPx = 10.dp.toPx()
                val handlePx = 16.dp.toPx()
                val handleWidth = HANDLE.toPx()

                fun snap(times: List<Double>, exclude: String, scale: Float, playhead: Double): Pair<Double, Int>? {
                    val points = ArrayList<Double>(currentView.clips.size * 2 + currentMarkers.size + 4)
                    points += playhead
                    points += 0.0
                    for (m in currentMarkers) points += m
                    for (clip in currentView.clips) {
                        if (clip.id == exclude) continue
                        points += clip.start
                        points += clip.end
                    }
                    var best: Pair<Double, Int>? = null
                    var bestDistance = snapPx / scale.toDouble()
                    times.forEachIndexed { which, time ->
                        for (point in points) {
                            val distance = abs(point - time)
                            if (distance < bestDistance) {
                                bestDistance = distance
                                best = point to which
                            }
                        }
                    }
                    return best
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fling?.cancel()
                    scope.launch { knob.animateTo(1f, Motion.knob()) }
                    try {
                        val view = currentView
                        val mainTrackId = view.tracks.getOrNull(mainTrackIndex(view))?.id
                        val expandedGroup = view.clip(currentSelected)
                            ?.let { featureGroupOf(it, mainTrackId) }
                            ?.takeIf { it != FeatureGroup.Main }
                        val scale = scaleNow()
                        val playhead = currentPosition
                        val layout = layoutFeatureLanes(view, mainTrackId, expandedGroup, size.height.toFloat(), scrollY, this)
                        val centre = size.width / 2f
                        fun timeAt(x: Float) = playhead + (x - centre) / scale
                        fun xOf(t: Double) = centre + ((t - playhead) * scale).toFloat()
                        val downRowIndex = layout.rowAt(down.position.y)
                        val downRow = layout.rows.getOrNull(downRowIndex)
                        val downTime = timeAt(down.position.x)

                        // Deteksi klip pada baris atau sub-baris yang diketuk
                        val matchingClips = if (downRow != null && down.position.y >= downRow.top && down.position.y <= downRow.bottom) {
                            view.clips.filter { clip ->
                                val sameGroup = featureGroupOf(clip, mainTrackId) == downRow.group
                                val sameSub = if (downRow.expanded) (layout.clipSubLane[clip.id] ?: 0) == downRow.subLane else true
                                sameGroup && sameSub && downTime >= clip.start && downTime < clip.end
                            }
                        } else {
                            emptyList()
                        }
                        val hit = when {
                            matchingClips.isEmpty() -> null
                            matchingClips.size == 1 -> matchingClips.first()
                            else -> {
                                val selIdx = matchingClips.indexOfFirst { it.id == currentSelected }
                                if (selIdx >= 0) matchingClips[(selIdx + 1) % matchingClips.size] else matchingClips.last()
                            }
                        }

                        val chosen = view.clip(currentSelected)
                        val edge = chosen?.let { clip ->
                            val row = layout.rowForClip(clip, mainTrackId) ?: return@let null
                            val inLane = down.position.y >= row.top - 6f && down.position.y <= row.bottom + 6f
                            val left = xOf(clip.start)
                            val right = xOf(clip.end)
                            when {
                                !inLane -> null
                                down.position.x in (left - handleWidth - handlePx / 2)..(left + handlePx / 2) &&
                                    down.position.x < (left + right) / 2 -> "start"
                                down.position.x in (right - handlePx / 2)..(right + handleWidth + handlePx / 2) -> "end"
                                else -> null
                            }
                        }
                        if (chosen != null && edge != null) {
                            down.consume()
                            val row = layout.rowIndexForClip(chosen, mainTrackId)
                            val (low, high) = trimRange(view, chosen, edge)
                            var delta = 0.0
                            var lastSnap: Double? = null
                            var atLimit = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                val wanted = ((change.position.x - down.position.x) / scale).toDouble()
                                val raw = wanted.coerceIn(low, high)
                                val limited = wanted != raw
                                if (limited && !atLimit) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                atLimit = limited
                                val moving = if (edge == "start") chosen.start + raw else chosen.end + raw
                                val snapped = snap(listOf(moving), chosen.id, scale, playhead)
                                if (snapped != null && snapped.first != lastSnap) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                lastSnap = snapped?.first
                                delta = snapped?.let { (point, _) -> (point - if (edge == "start") chosen.start else chosen.end).coerceIn(low, high) } ?: raw
                                drag = if (edge == "start") {
                                    DragPreview(chosen.id, chosen.start + delta, chosen.duration - delta, row, snapped?.first)
                                } else {
                                    DragPreview(chosen.id, chosen.start, chosen.duration + delta, row, snapped?.first)
                                }
                            }
                            drag = null
                            if (abs(delta) > 1e-4) trim(chosen.id, edge, delta)
                            return@awaitEachGesture
                        }

                        val slop = viewConfiguration.touchSlop
                        var outcome = Outcome.LongPress
                        var vertical = false
                        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } > 1) {
                                    outcome = Outcome.Pan
                                    break
                                }
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed) {
                                    outcome = Outcome.Tap
                                    break
                                }
                                val moved = change.position - down.position
                                if (moved.getDistance() > slop) {
                                    outcome = Outcome.Pan
                                    vertical = abs(moved.y) > abs(moved.x) * 0.85f
                                    break
                                }
                            }
                        }

                        if (outcome == Outcome.Tap) {
                            val tapped = buttonAt(view, layout, down.position, ::xOf, this)
                            when (tapped) {
                                is TimelineButton.Add -> addMedia()
                                is TimelineButton.Cut -> transition(tapped.nextClipId)
                                is TimelineButton.Mute -> muteTrack(tapped.trackId, tapped.mute)
                                TimelineButton.Cover -> openCover()
                                null -> {
                                    if (hit != null) {
                                        choose(hit.id)
                                    } else {
                                        choose(null)
                                    }
                                }
                            }
                            return@awaitEachGesture
                        }

                        if (outcome == Outcome.LongPress && hit != null) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            choose(hit.id)
                            val grab = downTime - hit.start
                            var start = hit.start
                            val row = layout.rowIndexForClip(hit, mainTrackId)
                            var lastSnap: Double? = null
                            drag = DragPreview(hit.id, start, hit.duration, row, null)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                val wanted = max(0.0, timeAt(change.position.x) - grab)
                                val snapped = snap(listOf(wanted, wanted + hit.duration), hit.id, scale, playhead)
                                if (snapped != null && snapped.first != lastSnap) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                lastSnap = snapped?.first
                                start = when {
                                    snapped == null -> wanted
                                    snapped.second == 0 -> snapped.first
                                    else -> snapped.first - hit.duration
                                }.coerceAtLeast(0.0)
                                drag = DragPreview(hit.id, start, hit.duration, row, snapped?.first)
                            }
                            drag = null
                            if (abs(start - hit.start) > 1e-4) {
                                move(hit.id, start, LaneTarget.Existing(hit.trackId))
                            }
                            return@awaitEachGesture
                        }

                        if (vertical) {
                            val viewportH = (size.height - RULER.toPx() - 12.dp.toPx()).coerceAtLeast(1f)
                            val overflow = max(0f, layout.contentHeight - viewportH)
                            var offset = (scrollY ?: autoScroll(layout, size.height.toFloat(), this)).coerceIn(0f, overflow)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                offset = (offset - (change.position.y - change.previousPosition.y)).coerceIn(0f, overflow)
                                scrollY = offset
                                change.consume()
                            }
                            return@awaitEachGesture
                        }

                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var at = playhead
                        var pinched = false
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                            if (event.changes.count { it.pressed } > 1) pinched = true
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) pxPerSecond = (scaleNow() * zoom).coerceIn(8.dp.toPx(), 3200.dp.toPx())
                            val pan = event.calculatePan()
                            if (pan.x != 0f) {
                                at = (at - pan.x / scaleNow()).coerceIn(0.0, currentView.duration)
                                scrub(at)
                            }
                            event.changes.firstOrNull { it.id == down.id }?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                            event.changes.forEach { it.consume() }
                        }
                        if (!pinched) {
                            val velocity = tracker.calculateVelocity().x
                            if (abs(velocity) > 200f) {
                                val from = at
                                val flingScale = scaleNow()
                                val end = currentView.duration
                                fling = scope.launch {
                                    animateDecay(0f, -velocity, FloatExponentialDecaySpec()) { value, _ ->
                                        scrub((from + value / flingScale).coerceIn(0.0, end))
                                    }
                                }
                            }
                        }
                    } finally {
                        scope.launch { knob.animateTo(0f, Motion.knob()) }
                    }
                }
            },
    ) {
        val scale = if (pxPerSecond <= 0f) 64.dp.toPx() else pxPerSecond
        val centre = size.width / 2f
        val mainTrackId = view.tracks.getOrNull(mainTrackIndex(view))?.id
        val expandedGroup = view.clip(selected)
            ?.let { featureGroupOf(it, mainTrackId) }
            ?.takeIf { it != FeatureGroup.Main }
        val layout = layoutFeatureLanes(view, mainTrackId, expandedGroup, size.height, scrollY, this)
        fun x(seconds: Double) = centre + ((seconds - position) * scale).toFloat()
        val leftVisibleTime = position - (centre + 40.dp.toPx()) / scale
        val rightVisibleTime = position + (centre + 40.dp.toPx()) / scale
        media.version

        drawRect(ConcatColors.page)
        val main = layout.main
        val moving = drag

        clipRect(top = RULER.toPx()) {
            // 1. Gambar wadah grup fitur (menyatu untuk semua sub-baris saat grup sedang expanded)
            val groupedRows = layout.rows.groupBy { it.group }
            val badgeRightByGroup = HashMap<FeatureGroup, Float>(groupedRows.size)

            for ((group, rowsInGroup) in groupedRows) {
                val firstRow = rowsInGroup.first()
                val lastRow = rowsInGroup.last()
                val isExpanded = firstRow.expanded && rowsInGroup.size > 1
                val bgColor = when {
                    firstRow.main -> ConcatColors.well
                    isExpanded -> ConcatColors.raised.copy(alpha = 0.55f)
                    else -> ConcatColors.launchPanel.copy(alpha = 0.6f)
                }
                drawRect(
                    color = bgColor,
                    topLeft = Offset(0f, firstRow.top),
                    size = Size(size.width, lastRow.bottom - firstRow.top),
                )
                if (isExpanded) {
                    drawRect(
                        color = ConcatColors.accent,
                        topLeft = Offset(0f, firstRow.top),
                        size = Size(3.dp.toPx(), lastRow.bottom - firstRow.top),
                    )
                }
                if (!firstRow.main) {
                    val badgeW = measureFeatureBadgeWidth(textCache, group, rowsInGroup.size, isExpanded)
                    badgeRightByGroup[group] = 6.dp.toPx() + badgeW + 6.dp.toPx()
                }
            }

            // 2. Gambar klip tiap baris / sub-baris fitur
            for (row in layout.rows) {
                val safeLeftForLabel = badgeRightByGroup[row.group] ?: 0f

                if (row.group == FeatureGroup.Audio && row.subLane == 0) {
                    for (clip in view.clips) {
                        if (clip.trackId != mainTrackId || clip.kind != ClipKind.Video) continue
                        if (clip.end < leftVisibleTime || clip.start > rightVisibleTime) continue
                        val file = view.mediaOf(clip)?.path ?: continue
                        val waveColor = if (clip.muted) ConcatColors.fgDim.copy(alpha = 0.45f) else KindColors.of(ClipKind.Audio).color
                        val box = Rect(x(clip.start) + 1f, row.top + 4.dp.toPx(), max(x(clip.start) + 3f, x(clip.end) - 1f), row.bottom - 4.dp.toPx())
                        drawWaveform(media, projectPath, file, clip, box, waveColor)
                    }
                }

                val laneClips = view.clips.filter { clip ->
                    featureGroupOf(clip, mainTrackId) == row.group &&
                        (!row.expanded || (layout.clipSubLane[clip.id] ?: 0) == row.subLane) &&
                        clip.end >= leftVisibleTime && clip.start <= rightVisibleTime
                }
                val orderedClips = if (!row.expanded && row.group == FeatureGroup.Text) {
                    laneClips.sortedWith(
                        compareBy<Clip> { it.id == selected }
                            .thenBy { clipRoles[it.id] != EditorViewModel.ROLE_IMPORTED }
                            .thenBy { it.start },
                    )
                } else {
                    laneClips
                }

                for (clip in orderedClips) {
                    val beingMoved = moving?.clipId == clip.id
                    drawClip(
                        textCache = textCache,
                        reusablePath = reusablePath,
                        glyphs = glyphs,
                        media = media,
                        projectPath = projectPath,
                        view = view,
                        clip = clip,
                        role = clipRoles[clip.id],
                        left = x(clip.start),
                        right = x(clip.end),
                        row = row,
                        alpha = if (beingMoved) 0.3f else 1f,
                        minSafeLabelLeft = safeLeftForLabel,
                    )
                }
            }

            if (main != null) drawMainExtras(textCache, reusablePath, glyphs, media, view, main, selected, cover, ::x)

            // 3. Gambar label/ikon fitur di bilah paling kiri tiap grup
            for ((group, rowsInGroup) in groupedRows) {
                val firstRow = rowsInGroup.first()
                if (!firstRow.main) {
                    val isExpanded = firstRow.expanded && rowsInGroup.size > 1
                    drawFeatureGutterBadge(textCache, glyphs, group, firstRow, rowsInGroup.size, isExpanded)
                }
            }

            // 4. Gambar garis vertikal penanda irama (Beat Markers)
            for (m in markers) {
                if (m < leftVisibleTime || m > rightVisibleTime) continue
                val mx = x(m)
                drawLine(
                    color = COLOR_MARKER.copy(alpha = 0.72f),
                    start = Offset(mx, RULER.toPx()),
                    end = Offset(mx, size.height),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                )
            }

            val chosen = view.clip(selected)
            if (chosen != null && moving?.clipId != chosen.id) {
                layout.rowForClip(chosen, mainTrackId)?.let { row ->
                    drawSelection(textCache, reusablePath, chosen, x(chosen.start), x(chosen.end), row, selection.value)
                }
            }

            if (moving != null) {
                val clip = view.clip(moving.clipId)
                if (clip != null && moving.row in layout.rows.indices) {
                    val row = layout.rows[moving.row]
                    val left = x(moving.start)
                    val right = x(moving.start + moving.duration)
                    val safeLeftForLabel = badgeRightByGroup[row.group] ?: 0f
                    drawClip(textCache, reusablePath, glyphs, media, projectPath, view, clip, clipRoles[clip.id], left, right, row, alpha = 0.92f, minSafeLabelLeft = safeLeftForLabel)
                    drawSelection(textCache, reusablePath, clip.copy(start = moving.start, duration = moving.duration), left, right, row, 1f)
                }
                moving.snappedAt?.let { at ->
                    drawLine(ConcatColors.accent, Offset(x(at), RULER.toPx()), Offset(x(at), size.height), strokeWidth = 1.5.dp.toPx())
                }
            }

            // 5. Indikator scrollbar vertikal di sisi kanan saat daftar track bisa digeser atas-bawah
            val viewportH = (size.height - RULER.toPx() - 12.dp.toPx()).coerceAtLeast(1f)
            val overflow = max(0f, layout.contentHeight - viewportH)
            if (overflow > 1f) {
                val currentOffset = (scrollY ?: autoScroll(layout, size.height, this)).coerceIn(0f, overflow)
                val barTop = RULER.toPx() + 6.dp.toPx()
                val trackH = (size.height - barTop - 6.dp.toPx()).coerceAtLeast(20f)
                val thumbH = (trackH * (viewportH / layout.contentHeight)).coerceIn(24.dp.toPx(), trackH)
                val thumbY = barTop + (trackH - thumbH) * (currentOffset / overflow)
                drawRoundRect(
                    color = ConcatColors.fgDim.copy(alpha = 0.65f),
                    topLeft = Offset(size.width - 4.dp.toPx(), thumbY),
                    size = Size(2.5.dp.toPx(), thumbH),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
            }
        }

        drawRuler(textCache, reusablePath, position, scale, centre, markers)

        val head = ConcatColors.accent
        val knobY = RULER.toPx() - 7.dp.toPx()
        drawLine(head, Offset(centre, knobY), Offset(centre, size.height), strokeWidth = 2.dp.toPx())
        val radius = (5 + 2.5f * knob.value).dp.toPx()
        drawCircle(head, radius, Offset(centre, knobY))
        drawCircle(ConcatColors.page, radius * 0.35f, Offset(centre, knobY))
    }
}

private val RULER = Dimens.ruler
private val SOUND_LANE = 42.dp
private val HANDLE = 14.dp
private val LANE_GAP = 6.dp
private val SUB_LANE_GAP = 4.dp

private enum class Outcome { Tap, Pan, LongPress }

private fun packSubLanes(clips: List<Clip>, startLane: Int = 0): Pair, Int> {
    if (clips.isEmpty()) return emptyMap<String, Int>() to max(1, startLane)
    val sorted = clips.sortedWith(compareBy<Clip> { it.start }.thenBy { it.id })
    val laneEnds = ArrayList<Double>()
    val result = HashMap<String, Int>(sorted.size)
    for (clip in sorted) {
        var assigned = -1
        for (i in laneEnds.indices) {
            if (laneEnds[i] <= clip.start + 1e-3) {
                assigned = i
                laneEnds[i] = max(laneEnds[i], clip.end)
                break
            }
        }
        if (assigned < 0) {
            assigned = laneEnds.size
            laneEnds.add(clip.end)
        }
        result[clip.id] = startLane + assigned
    }
    return result to (startLane + laneEnds.size)
}

private fun layoutFeatureLanes(
    view: ProjectView,
    mainTrackId: String?,
    expandedGroup: FeatureGroup?,
    height: Float,
    scrollY: Float?,
    density: Density,
): LaneLayout = with(density) {
    val gap = LANE_GAP.toPx()
    val subGap = SUB_LANE_GAP.toPx()
    val resolvedMainId = mainTrackId ?: view.tracks.firstOrNull()?.id.orEmpty()
    val clipsByGroup = view.clips.groupBy { featureGroupOf(it, mainTrackId) }
    val hasMainMedia = view.clips.any { it.trackId == resolvedMainId }

    val orderedGroups = buildList {
        if (clipsByGroup[FeatureGroup.Effects]?.isNotEmpty() == true) add(FeatureGroup.Effects)
        if (clipsByGroup[FeatureGroup.Sticker]?.isNotEmpty() == true) add(FeatureGroup.Sticker)
        add(FeatureGroup.Text)
        if (clipsByGroup[FeatureGroup.Overlay]?.isNotEmpty() == true) add(FeatureGroup.Overlay)
        add(FeatureGroup.Main)
        if (hasMainMedia || clipsByGroup[FeatureGroup.Audio]?.isNotEmpty() == true) add(FeatureGroup.Audio)
    }

    val clipSubLane = HashMap<String, Int>()
    val specs = ArrayList<FeatureLaneRow>()
    var cursorY = 0f

    for ((groupIdx, group) in orderedGroups.withIndex()) {
        if (groupIdx > 0) cursorY += gap
        val groupClips = clipsByGroup[group].orEmpty()
        val trackId = groupClips.firstOrNull()?.trackId ?: resolvedMainId
        val isExpanded = group == expandedGroup && group != FeatureGroup.Main
        val laneH = when (group) {
            FeatureGroup.Main -> Dimens.mainTrack.toPx()
            FeatureGroup.Audio -> SOUND_LANE.toPx()
            else -> Dimens.overlayLane.toPx()
        }

        val subLaneCount = if (isExpanded) {
            val startLane = if (group == FeatureGroup.Audio && hasMainMedia) 1 else 0
            val (assignments, count) = packSubLanes(groupClips, startLane)
            clipSubLane.putAll(assignments)
            count.coerceAtLeast(1)
        } else {
            groupClips.forEach { clipSubLane[it.id] = 0 }
            1
        }

        for (sub in 0 until subLaneCount) {
            if (sub > 0) cursorY += subGap
            specs.add(
                FeatureLaneRow(
                    group = group,
                    trackId = trackId,
                    subLane = sub,
                    subLaneCount = subLaneCount,
                    expanded = isExpanded && subLaneCount > 1,
                    top = cursorY,
                    height = laneH,
                ),
            )
            cursorY += laneH
        }
    }

    val total = cursorY
    val available = height - RULER.toPx() - 12.dp.toPx()
    val startOffset = if (total <= available) {
        RULER.toPx() + 8.dp.toPx() + (available - total) * 0.25f
    } else {
        val probe = LaneLayout(specs.map { shiftRow(it, RULER.toPx() + 8.dp.toPx()) }, clipSubLane, gap, total)
        val clampedScroll = (scrollY ?: autoScroll(probe, height, density)).coerceIn(0f, max(0f, total - available))
        RULER.toPx() + 8.dp.toPx() - clampedScroll
    }

    LaneLayout(
        rows = specs.map { shiftRow(it, startOffset) },
        clipSubLane = clipSubLane,
        gap = gap,
        contentHeight = total,
    )
}

private fun shiftRow(row: FeatureLaneRow, dy: Float) = FeatureLaneRow(
    group = row.group,
    trackId = row.trackId,
    subLane = row.subLane,
    subLaneCount = row.subLaneCount,
    expanded = row.expanded,
    top = row.top + dy,
    height = row.height,
)

private fun autoScroll(layout: LaneLayout, height: Float, density: Density): Float = with(density) {
    val main = layout.main ?: return 0f
    val viewportH = max(1f, height - RULER.toPx() - 12.dp.toPx())
    val overflow = max(0f, layout.contentHeight - viewportH)
    (main.centre - height * 0.55f).coerceIn(0f, overflow)
}

private sealed interface TimelineButton {
    data object Add : TimelineButton
    data object Cover : TimelineButton
    data class Cut(val nextClipId: String) : TimelineButton
    data class Mute(val trackId: String, val mute: Boolean) : TimelineButton
}

private fun buttonAt(
    view: ProjectView,
    layout: LaneLayout,
    at: Offset,
    x: (Double) -> Float,
    density: Density,
): TimelineButton? = with(density) {
    val main = layout.main ?: return null
    val reach = 10.dp.toPx()
    val clips = view.clips.filter { it.trackId == main.trackId }.sortedBy { it.start }
    if (abs(at.y - main.centre) < CUT_BUTTON.toPx() / 2 + reach) {
        for (i in 0 until clips.size - 1) {
            val a = clips[i]
            val b = clips[i + 1]
            if (abs(a.end - b.start) > 1e-3) continue
            if (abs(at.x - x(b.start)) < CUT_BUTTON.toPx() / 2 + reach / 2) return TimelineButton.Cut(b.id)
        }
    }
    if (at.y < main.top - reach || at.y > main.bottom + reach) return null
    val end = clips.maxOfOrNull { it.end } ?: 0.0
    val plusLeft = x(end) + 12.dp.toPx()
    if (at.x in (plusLeft - reach)..(plusLeft + PLUS.toPx() + reach)) return TimelineButton.Add
    val coverRight = x(0.0) - 12.dp.toPx()
    val coverLeft = coverRight - main.height
    if (at.x in coverLeft..coverRight) return TimelineButton.Cover
    val muteRight = coverLeft - 10.dp.toPx()
    if (at.x in (muteRight - 40.dp.toPx())..muteRight) {
        val videos = clips.filter { it.kind == ClipKind.Video }
        if (videos.isNotEmpty()) return TimelineButton.Mute(main.trackId, mute = videos.any { !it.muted })
    }
    null
}

private val CUT_BUTTON = 22.dp
private val PLUS = 40.dp

private fun trimRange(view: ProjectView, clip: Clip, edge: String): Pair<Double, Double> {
    val lane = view.clips.filter { it.trackId == clip.trackId && it.id != clip.id }
    val footage = clip.kind == ClipKind.Video || clip.kind == ClipKind.Audio
    val mediaLength = view.mediaOf(clip)?.duration
    return if (edge == "start") {
        val previousEnd = lane.filter { it.end <= clip.start + 1e-6 }.maxOfOrNull { it.end } ?: 0.0
        var low = previousEnd - clip.start
        if (footage) low = max(low, -clip.sourceStart / clip.speed.coerceAtLeast(1e-6))
        low to (clip.duration - MIN_LENGTH)
    } else {
        val nextStart = lane.filter { it.start >= clip.end - 1e-6 }.minOfOrNull { it.start }
        var high = (nextStart ?: Double.MAX_VALUE) - clip.end
        if (footage && mediaLength != null) {
            high = min(high, (mediaLength - clip.sourceStart) / clip.speed.coerceAtLeast(1e-6) - clip.duration)
        }
        -(clip.duration - MIN_LENGTH) to max(0.0, high)
    }
}

private fun ruleStyle() = TextStyle(color = ConcatColors.fgDim, fontSize = 9.sp, fontFamily = Inter, fontFeatureSettings = "tnum")

private fun DrawScope.drawRuler(
    textCache: TextLayoutCache,
    reusablePath: Path,
    position: Double,
    scale: Float,
    centre: Float,
    markers: List<Double>,
) {
    drawRect(ConcatColors.page, Offset.Zero, Size(size.width, RULER.toPx()))
    val step = when {
        scale > 1600.dp.toPx() -> 0.05
        scale > 900.dp.toPx() -> 0.1
        scale > 450.dp.toPx() -> 0.25
        scale > 200.dp.toPx() -> 0.5
        scale > 90.dp.toPx() -> 1.0
        scale > 40.dp.toPx() -> 2.0
        scale > 15.dp.toPx() -> 5.0
        else -> 10.0
    }
    val showMillis = step <= 1.0
    val first = floor((position - centre / scale) / step) * step
    val last = ceil((position + centre / scale) / step) * step
    var tick = max(0.0, first)
    val label = ruleStyle()
    val mid = 8.dp.toPx()
    while (tick <= last) {
        val at = centre + ((tick - position) * scale).toFloat()
        val totalMs = (tick * 1000.0).roundToInt().toLong()
        val seconds = totalMs / 1000L
        val millis = (totalMs % 1000L).toInt()
        val formatted = if (showMillis) {
            String.format(Locale.US, "%02d:%02d.%03d", seconds / 60, seconds % 60, millis)
        } else {
            String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
        }
        val text = textCache.measure(formatted, label)
        val x = at - text.size.width / 2f
        if (x >= 0f && x + text.size.width <= size.width && abs(at - centre) > text.size.width / 2f + 2.dp.toPx()) {
            drawText(text, topLeft = Offset(x, mid - text.size.height / 2f))
        }
        val half = centre + ((tick + step / 2 - position) * scale).toFloat()
        if (half in 0f..size.width) drawCircle(ConcatColors.fgDim, 1.2.dp.toPx(), Offset(half, mid))
        tick += step
    }

    val r = 4.5.dp.toPx()
    val my = RULER.toPx() - 5.dp.toPx()
    for (m in markers) {
        val mx = centre + ((m - position) * scale).toFloat()
        if (mx < -12f || mx > size.width + 12f) continue
        reusablePath.reset()
        reusablePath.moveTo(mx, my - r)
        reusablePath.lineTo(mx + r, my)
        reusablePath.lineTo(mx, my + r)
        reusablePath.lineTo(mx - r, my)
        reusablePath.close()
        drawPath(reusablePath, COLOR_MARKER)
    }
}

private fun badgeTextFor(group: FeatureGroup, subLaneCount: Int, isExpanded: Boolean): String =
    if (isExpanded && subLaneCount > 1) "${group.label} ($subLaneCount)" else group.label

private fun DrawScope.measureFeatureBadgeWidth(
    textCache: TextLayoutCache,
    group: FeatureGroup,
    subLaneCount: Int,
    isExpanded: Boolean,
): Float {
    val style = TextStyle(color = ConcatColors.fgMuted, fontSize = 9.sp, fontFamily = Inter, fontWeight = FontWeight.Medium)
    val laid = textCache.measure(badgeTextFor(group, subLaneCount, isExpanded), style)
    return 6.dp.toPx() * 2 + 11.dp.toPx() + 4.dp.toPx() + laid.size.width
}

/** Label + ikon kategori fitur di sisi paling kiri tiap grup (di atas dan bawah posisi Cover). */
private fun DrawScope.drawFeatureGutterBadge(
    textCache: TextLayoutCache,
    glyphs: TimelineGlyphs,
    group: FeatureGroup,
    firstRow: FeatureLaneRow,
    subLaneCount: Int,
    isExpanded: Boolean,
) {
    val icon = when (group) {
        FeatureGroup.Effects -> glyphs.sparkle
        FeatureGroup.Sticker -> glyphs.shapes
        FeatureGroup.Text -> glyphs.text
        FeatureGroup.Overlay -> glyphs.image
        FeatureGroup.Audio -> glyphs.music
        FeatureGroup.Main -> glyphs.film
    }
    val textColor = if (isExpanded) ConcatColors.accent else ConcatColors.fgMuted
    val style = TextStyle(color = textColor, fontSize = 9.sp, fontFamily = Inter, fontWeight = FontWeight.Medium)
    val laid = textCache.measure(badgeTextFor(group, subLaneCount, isExpanded), style)
    val iconSize = 11.dp.toPx()
    val padX = 6.dp.toPx()
    val padY = 3.dp.toPx()
    val w = padX * 2 + iconSize + 4.dp.toPx() + laid.size.width
    val h = max(iconSize, laid.size.height.toFloat()) + padY * 2
    val left = 6.dp.toPx()
    val top = firstRow.centre - h / 2f

    drawRoundRect(
        color = ConcatColors.raised.copy(alpha = 0.94f),
        topLeft = Offset(left, top),
        size = Size(w, h),
        cornerRadius = CornerRadius(6.dp.toPx()),
    )
    drawRoundRect(
        color = if (isExpanded) ConcatColors.accent.copy(alpha = 0.8f) else ConcatColors.lineStrong.copy(alpha = 0.7f),
        topLeft = Offset(left, top),
        size = Size(w, h),
        cornerRadius = CornerRadius(6.dp.toPx()),
        style = Stroke(1.dp.toPx()),
    )
    glyph(icon, Offset(left + padX, top + (h - iconSize) / 2f), iconSize, if (isExpanded) ConcatColors.accent else ConcatColors.fg)
    drawText(laid, topLeft = Offset(left + padX + iconSize + 4.dp.toPx(), top + (h - laid.size.height) / 2f))
}

private fun DrawScope.glyph(painter: VectorPainter, topLeft: Offset, size: Float, tint: Color) {
    translate(topLeft.x, topLeft.y) {
        with(painter) { draw(Size(size, size), colorFilter = ColorFilter.tint(tint)) }
    }
}

private fun kindGlyph(glyphs: TimelineGlyphs, clip: Clip): VectorPainter = when (clip.kind) {
    ClipKind.Text -> glyphs.text
    ClipKind.Shape -> glyphs.shapes
    ClipKind.Audio, ClipKind.SoundLayer -> glyphs.music
    ClipKind.Image -> glyphs.image
    ClipKind.Video -> glyphs.film
    ClipKind.Transition -> glyphs.transition
    ClipKind.Layer -> glyphs.filter
    ClipKind.Other -> glyphs.shapes
}

private fun DrawScope.drawClip(
    textCache: TextLayoutCache,
    reusablePath: Path,
    glyphs: TimelineGlyphs,
    media: MediaCache,
    projectPath: String,
    view: ProjectView,
    clip: Clip,
    role: String?,
    left: Float,
    right: Float,
    row: FeatureLaneRow,
    alpha: Float,
    minSafeLabelLeft: Float = 0f,
) {
    if (right < 0 || left > size.width) return
    val kind = KindColors.of(clip.kind)
    val boxColor = if (clip.kind == ClipKind.Text) {
        when (role) {
            EditorViewModel.ROLE_IMPORTED -> COLOR_TEXT_IMPORTED
            EditorViewModel.ROLE_DUPLICATE -> COLOR_TEXT_DUPLICATE
            else -> COLOR_TEXT_DEFAULT
        }
    } else {
        kind.color
    }
    val contentTint = if (clip.kind == ClipKind.Text && role == EditorViewModel.ROLE_DUPLICATE) {
        Color(0xFF18181B)
    } else {
        Color.White
    }
    val inset = if (row.main) 0f else 1.dp.toPx()
    val box = Rect(left + 1f, row.top + inset, max(left + 3f, right - 1f), row.bottom - inset)
    val corner = CornerRadius((if (row.main) 6 else 5).dp.toPx())
    reusablePath.reset()
    reusablePath.addRoundRect(RoundRect(box, corner))
    val file = view.mediaOf(clip)?.path
    clipPath(reusablePath) {
        when {
            (clip.kind == ClipKind.Video || clip.kind == ClipKind.Image) && file != null -> {
                drawRect(kind.well.copy(alpha = alpha), box.topLeft, box.size)
                drawFilmstrip(media, file, clip, box, alpha)
                if (!row.main) {
                    drawRect(Color.Black.copy(alpha = 0.25f * alpha), box.topLeft, box.size)
                }
            }
            clip.kind == ClipKind.Audio && file != null -> {
                drawRect(kind.well.copy(alpha = alpha), box.topLeft, box.size)
                val wave = Rect(box.left, box.top + 13.dp.toPx(), box.right, box.bottom - 1.dp.toPx())
                drawWaveform(media, projectPath, file, clip, wave, kind.color.copy(alpha = alpha))
            }
            else -> {
                drawRect(boxColor.copy(alpha = 0.94f * alpha), box.topLeft, box.size)
            }
        }
    }

    val width = box.width
    val glyphSize = 14.dp.toPx()
    val minScreenX = if (row.subLane == 0) minSafeLabelLeft else 6.dp.toPx()
    val clipLeftOnScreen = max(box.left + 6.dp.toPx(), minScreenX)
    if (!row.main && width > 22.dp.toPx() && clipLeftOnScreen + glyphSize < box.right - 4.dp.toPx()) {
        val sound = clip.kind == ClipKind.Audio
        val size = if (sound) 10.dp.toPx() else glyphSize
        val lineY = if (sound) box.top + 7.dp.toPx() else row.centre
        var labelLeft = clipLeftOnScreen
        glyph(kindGlyph(glyphs, clip), Offset(labelLeft, lineY - size / 2f), size, contentTint.copy(alpha = alpha))
        labelLeft += size + 4.dp.toPx()
        val room = (box.right - labelLeft - 6.dp.toPx()).toInt()
        if (room > 12.dp.toPx()) {
            val laid = textCache.measure(
                text = clip.name.ifBlank { clip.kind.name },
                style = TextStyle(color = contentTint.copy(alpha = if (sound) 0.85f * alpha else alpha), fontSize = if (sound) 9.sp else 11.sp, fontFamily = Inter),
                maxWidth = room,
            )
            drawText(laid, topLeft = Offset(labelLeft, lineY - laid.size.height / 2f))
        }
    }

    var badgeX = box.left + 4.dp.toPx()
    val badgeY = box.top + 3.dp.toPx()
    if (row.main && abs(clip.speed - 1.0) > 1e-3 && width > 36.dp.toPx()) {
        badgeX += drawBadge(textCache, "%.1fx".format(clip.speed), Offset(max(badgeX, 4.dp.toPx()), badgeY)) + 4.dp.toPx()
    }
    if (row.main && clip.muted && width > 28.dp.toPx()) {
        val s = 12.dp.toPx()
        drawRoundRect(Color.Black.copy(alpha = 0.55f), Offset(max(badgeX, 4.dp.toPx()), badgeY), Size(s + 6.dp.toPx(), s + 4.dp.toPx()), CornerRadius(4.dp.toPx()))
        glyph(glyphs.volumeOff, Offset(max(badgeX, 4.dp.toPx()) + 3.dp.toPx(), badgeY + 2.dp.toPx()), s, Color.White)
    }
    if (clip.videoEffects.isNotEmpty() && clip.kind != ClipKind.Transition && width > 18.dp.toPx()) {
        drawCircle(ConcatColors.accent, 3.dp.toPx(), Offset(box.right - 7.dp.toPx(), box.top + 7.dp.toPx()))
    }
}

private fun DrawScope.drawBadge(textCache: TextLayoutCache, text: String, at: Offset, background: Color = Color.Black.copy(alpha = 0.6f)): Float {
    val laid = textCache.measure(text, TextStyle(color = Color.White, fontSize = 9.sp, fontFamily = Inter, fontFeatureSettings = "tnum"))
    val pad = 4.dp.toPx()
    val w = laid.size.width + pad * 2
    drawRoundRect(background, at, Size(w, laid.size.height + 2.dp.toPx()), CornerRadius(4.dp.toPx()))
    drawText(laid, topLeft = Offset(at.x + pad, at.y + 1.dp.toPx()))
    return w
}

private fun DrawScope.drawFilmstrip(media: MediaCache, file: String, clip: Clip, box: Rect, alpha: Float) {
    val tile = box.height
    val tilesWanted = ceil(box.width / tile).toInt().coerceAtLeast(1)
    val count = MediaCache.bucket(tilesWanted).coerceAtMost(60)
    val from = if (clip.kind == ClipKind.Image) 0.0 else clip.sourceStart
    val to = if (clip.kind == ClipKind.Image) 0.0 else clip.sourceStart + clip.duration * clip.speed
    val key = StripKey(file, round2(from), round2(to), count, tile.roundToInt().coerceIn(16, 240))
    val strip: ImageBitmap = media.strip(key) ?: return
    val stripCount = max(1, (strip.width.toFloat() / max(1, strip.height) + 0.5f).toInt()).let { guess ->
        if (strip.width % count == 0 && strip.width / count > 0) count else guess
    }
    val srcTileWidth = strip.width / stripCount
    val srcSide = min(srcTileWidth, strip.height)
    val first = max(0, floor((0f - box.left) / tile).toInt())
    val last = min(tilesWanted - 1, ceil((size.width - box.left) / tile).toInt())
    for (i in first..last) {
        val dstLeft = box.left + i * tile
        val centreAt = ((i * tile + tile / 2f) / box.width).coerceIn(0f, 0.9999f)
        val j = (centreAt * stripCount).toInt().coerceIn(0, stripCount - 1)
        val srcX = j * srcTileWidth + (srcTileWidth - srcSide) / 2
        val srcY = (strip.height - srcSide) / 2
        drawImage(
            strip,
            srcOffset = IntOffset(srcX, srcY),
            srcSize = IntSize(srcSide, srcSide),
            dstOffset = IntOffset(dstLeft.roundToInt(), box.top.roundToInt()),
            dstSize = IntSize(ceil(tile).toInt() + 1, box.height.roundToInt()),
            alpha = alpha,
        )
    }
}

private fun round2(value: Double) = (value * 100).roundToInt() / 100.0

private fun DrawScope.drawWaveform(media: MediaCache, projectPath: String, file: String, clip: Clip, box: Rect, colour: Color) {
    val peaks = media.peaks(projectPath, file) ?: return
    if (peaks.max.isEmpty()) return
    val mid = box.center.y
    val half = box.height * 0.44f
    val step = 2.dp.toPx()
    val scale = box.width / clip.duration.coerceAtLeast(1e-6)
    var x = max(box.left, 0f)
    val end = min(box.right, size.width)
    while (x < end) {
        val t = clip.sourceStart + (x - box.left) / scale * clip.speed
        val i = (t * peaks.perSecond).toInt()
        if (i in peaks.max.indices) {
            val top = mid - peaks.max[i].coerceIn(0f, 1f) * half
            val bottom = mid - peaks.min.getOrElse(i) { -peaks.max[i] }.coerceIn(-1f, 0f) * half
            drawLine(colour, Offset(x, top), Offset(x, max(bottom, top + 1f)), strokeWidth = step * 0.65f)
        }
        x += step
    }
}

private fun DrawScope.drawSelection(
    textCache: TextLayoutCache,
    reusablePath: Path,
    clip: Clip,
    left: Float,
    right: Float,
    row: FeatureLaneRow,
    alpha: Float,
) {
    if (right < -HANDLE.toPx() || left > size.width + HANDLE.toPx()) return
    val stroke = 3.dp.toPx()
    val top = row.top - stroke / 2f
    val bottom = row.bottom + stroke / 2f
    val lime = ConcatColors.accent.copy(alpha = alpha)
    drawRoundRect(
        lime,
        Offset(left, top),
        Size(max(2f, right - left), bottom - top),
        CornerRadius(2.dp.toPx()),
        style = Stroke(width = stroke),
    )
    val handle = HANDLE.toPx()
    val radius = CornerRadius(6.dp.toPx())
    val white = Color.White.copy(alpha = alpha)
    val grip = ConcatColors.page.copy(alpha = alpha)
    val height = bottom - top + stroke
    val y = top - stroke / 2f
    reusablePath.reset()
    reusablePath.addRoundRect(RoundRect(Rect(left - handle, y, left, y + height), topLeft = radius, bottomLeft = radius))
    drawPath(reusablePath, white)
    reusablePath.reset()
    reusablePath.addRoundRect(RoundRect(Rect(right, y, right + handle, y + height), topRight = radius, bottomRight = radius))
    drawPath(reusablePath, white)
    val gripHalf = min(8.dp.toPx(), height * 0.22f)
    val mid = row.centre
    drawLine(grip, Offset(left - handle / 2f, mid - gripHalf), Offset(left - handle / 2f, mid + gripHalf), 2.dp.toPx(), cap = StrokeCap.Round)
    drawLine(grip, Offset(right + handle / 2f, mid - gripHalf), Offset(right + handle / 2f, mid + gripHalf), 2.dp.toPx(), cap = StrokeCap.Round)

    if (right - left > 34.dp.toPx()) {
        val durLabel = String.format(Locale.US, "%.3fs", clip.duration)
        val laid = textCache.measure(durLabel, TextStyle(color = Color.White, fontSize = 9.sp, fontFamily = Inter, fontFeatureSettings = "tnum"))
        val pad = 4.dp.toPx()
        val bx = max(left + 4.dp.toPx(), 4.dp.toPx()).coerceAtMost(right - laid.size.width - pad * 2 - 4.dp.toPx())
        val by = row.bottom - laid.size.height - 5.dp.toPx()
        if (row.height >= 40.dp.toPx()) {
            drawRoundRect(Color.Black.copy(alpha = 0.65f * alpha), Offset(bx, by), Size(laid.size.width + pad * 2, laid.size.height + 2.dp.toPx()), CornerRadius(4.dp.toPx()))
            drawText(laid, topLeft = Offset(bx + pad, by + 1.dp.toPx()), alpha = alpha)
        }
    }
}

private fun DrawScope.drawMainExtras(
    textCache: TextLayoutCache,
    reusablePath: Path,
    glyphs: TimelineGlyphs,
    media: MediaCache,
    view: ProjectView,
    main: FeatureLaneRow,
    selected: String?,
    cover: ImageBitmap?,
    x: (Double) -> Float,
) {
    val clips = view.clips.filter { it.trackId == main.trackId }.sortedBy { it.start }

    val coverRight = x(0.0) - 12.dp.toPx()
    val side = main.height
    val coverLeft = coverRight - side
    if (coverRight > 0) {
        val rect = Rect(coverLeft, main.top, coverRight, main.bottom)
        reusablePath.reset()
        reusablePath.addRoundRect(RoundRect(rect, CornerRadius(6.dp.toPx())))
        clipPath(reusablePath) {
            drawRect(ConcatColors.raised, rect.topLeft, rect.size)
            val first = clips.firstOrNull { it.kind == ClipKind.Video || it.kind == ClipKind.Image }
            val file = first?.let { view.mediaOf(it)?.path }
            if (cover != null) {
                drawImage(
                    cover,
                    dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                    dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
                )
            } else if (first != null && file != null) {
                drawFilmstrip(media, file, first.copy(duration = first.duration.coerceAtMost(0.5)), rect, 1f)
            }
            drawRect(Color.Black.copy(alpha = 0.72f), Offset(rect.left, rect.bottom - 16.dp.toPx()), Size(rect.width, 16.dp.toPx()))
        }
        drawRoundRect(ConcatColors.lineStrong, rect.topLeft, rect.size, CornerRadius(6.dp.toPx()), style = Stroke(1.dp.toPx()))
        val laid = textCache.measure("Cover", TextStyle(color = Color.White, fontSize = 9.sp, fontFamily = Inter))
        drawText(laid, topLeft = Offset(rect.center.x - laid.size.width / 2f, rect.bottom - 8.dp.toPx() - laid.size.height / 2f))

        val videos = clips.filter { it.kind == ClipKind.Video }
        if (videos.isNotEmpty()) {
            val muted = videos.all { it.muted }
            val r = 15.dp.toPx()
            val cx = coverLeft - 10.dp.toPx() - r
            if (cx + r > 0) {
                drawCircle(ConcatColors.raised, r, Offset(cx, main.centre - 6.dp.toPx()))
                val g = 16.dp.toPx()
                glyph(if (muted) glyphs.volumeOff else glyphs.volume, Offset(cx - g / 2, main.centre - 6.dp.toPx() - g / 2), g, if (muted) ConcatColors.fgMuted else ConcatColors.fg)
                val label = textCache.measure(if (muted) "Muted" else "Sound", TextStyle(color = ConcatColors.fgMuted, fontSize = 8.sp, fontFamily = Inter))
                drawText(label, topLeft = Offset(cx - label.size.width / 2f, main.centre + r - 4.dp.toPx()))
            }
        }
    }

    val transitions = view.clips.filter { it.kind == ClipKind.Transition }
    for (i in 0 until clips.size - 1) {
        val a = clips[i]
        val b = clips[i + 1]
        if (abs(a.end - b.start) > 1e-3) continue
        if (a.id == selected || b.id == selected) continue
        val cx = x(b.start)
        if (cx < -20 || cx > size.width + 20) continue
        val s = CUT_BUTTON.toPx()
        val has = transitions.any { it.start <= b.start + 1e-3 && it.end >= b.start - 1e-3 }
        drawRoundRect(
            if (has) ConcatColors.accent else Color.White,
            Offset(cx - s / 2, main.centre - s / 2),
            Size(s, s),
            CornerRadius(5.dp.toPx()),
        )
        drawRoundRect(ConcatColors.page.copy(alpha = 0.5f), Offset(cx - s / 2, main.centre - s / 2), Size(s, s), CornerRadius(5.dp.toPx()), style = Stroke(1.dp.toPx()))
        val g = 14.dp.toPx()
        glyph(glyphs.transition, Offset(cx - g / 2, main.centre - g / 2), g, ConcatColors.onAccent)
    }

    val end = clips.maxOfOrNull { it.end } ?: 0.0
    val plusLeft = x(end) + 12.dp.toPx()
    if (plusLeft < size.width) {
        val s = PLUS.toPx()
        drawRoundRect(Color.White, Offset(plusLeft, main.centre - s / 2), Size(s, s), CornerRadius(8.dp.toPx()))
        val g = 22.dp.toPx()
        glyph(glyphs.plus, Offset(plusLeft + (s - g) / 2, main.centre - g / 2), g, ConcatColors.page)
        if (clips.isEmpty()) {
            val hint = textCache.measure("Add media", TextStyle(color = ConcatColors.fgMuted, fontSize = 13.sp, fontFamily = Inter))
            val hx = plusLeft + s + 10.dp.toPx()
            if (hx + hint.size.width <= size.width) drawText(hint, topLeft = Offset(hx, main.centre - hint.size.height / 2f))
        }
    }
}
