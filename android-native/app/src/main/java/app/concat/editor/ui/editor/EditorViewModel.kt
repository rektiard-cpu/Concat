// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.ui.editor

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.concat.editor.api.AppliedEffect
import app.concat.editor.api.ClipBounds
import app.concat.editor.api.ClipKind
import app.concat.editor.api.Commands
import app.concat.editor.api.JobTracker
import app.concat.editor.api.ProjectView
import app.concat.editor.api.ShapeCard
import app.concat.editor.api.errorMessage
import app.concat.editor.api.wasCancelled
import app.concat.editor.concat
import app.concat.editor.engine.PreviewBridge
import app.concat.editor.export.EXPORT_FPS_OPTIONS
import app.concat.editor.export.ExportFps
import app.concat.editor.export.ExportPreset
import app.concat.editor.subtitle.SubtitleCodec
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class CustomFont(
    val label: String,
    val family: String,
    val weight: Double = 400.0,
    val italic: Boolean = false,
    val fileName: String,
    val path: String,
)

data class EditorUi(
    val view: ProjectView? = null,
    val selected: String? = null,
    val position: Double = 0.0,
    val playing: Boolean = false,
    /** The preview draws the slower way: the phone's graphics failed under it. */
    val previewSlower: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    /** The clip a reverse is being written for, and how far it is (0..1). */
    val reversing: Pair<String, Float>? = null,
    /** Each title's and shape's block as the engine painted it, for the transform box. */
    val bounds: Map<String, ClipBounds> = emptyMap(),
    /** The Stickers page's cards, once the engine has said them. */
    val stickers: List<ShapeCard> = emptyList(),
    /** The cover the project names, painted; none while it names none (the timeline shows its first frame). */
    val cover: Bitmap? = null,
    /** Peran klip teks ("imported", "duplicate") untuk pewarnaan timeline & batch edit. */
    val clipRoles: Map<String, String> = emptyMap(),
    /** Daftar font custom (.ttf/.otf) yang diimpor dari penyimpanan HP. */
    val customFonts: List<CustomFont> = emptyList(),
    /** Daftar warna hex favorit yang disimpan user secara permanen. */
    val savedColors: List<String> = emptyList(),
    /** Penanda titik irama / beat markers (dalam detik) untuk snap timestamp subtitle. */
    val markers: List<Double> = emptyList(),
)

/**
 * One open project: its state as the API last replied, the playhead, the
 * selection and the preview. Every edit is `edit.apply` (or undo/redo),
 * then `preview.refresh()`, and the reply is the new state, so the screen
 * never guesses. Engine calls run on the IO dispatcher, one edit at a time.
 */
class EditorViewModel(application: Application, val path: String) : AndroidViewModel(application) {
    private val app = application.concat
    private val api = app.api
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _ui = MutableStateFlow(
        EditorUi(
            clipRoles = loadClipRoles(),
            customFonts = scanCustomFonts(),
            savedColors = loadSavedColors(),
            markers = loadMarkers(),
        ),
    )
    val ui: StateFlow<EditorUi> = _ui
    val exports get() = app.exports
    val models get() = app.models

    @Volatile private var preview: PreviewBridge? = null
    /** The surface waiting for the preview, when it came first. */
    private var pending: Triple<Surface, Int, Int>? = null
    private val edits = Mutex()
    private val seeks = Channel<Double>(Channel.CONFLATED)
    @Volatile private var scrubbedAt = 0L
    private var closed = false

    init {
        viewModelScope.launch {
            try {
                val view = api.open(path)
                _ui.update { it.copy(view = view) }
                val made = withContext(Dispatchers.IO) { api.engine.preview(path).also { it.refresh() } }
                preview = made
                pending?.let { (surface, width, height) -> made.attachSurface(surface, width, height, MODE) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "could not open $path", error)
                say(error.message ?: "The project would not open.")
            }
        }
        viewModelScope.launch {
            ui.map { it.view }.distinctUntilChanged().collectLatest { view ->
                if (view == null) return@collectLatest
                try {
                    val bounds = api.bounds(path)
                    _ui.update { it.copy(bounds = bounds) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "clips.bounds failed", error)
                }
            }
        }
        viewModelScope.launch {
            ui.map { it.view?.cover?.toString() }.distinctUntilChanged().collectLatest { named ->
                if (named == null) {
                    _ui.update { it.copy(cover = null) }
                    return@collectLatest
                }
                try {
                    val file = File(getApplication<Application>().cacheDir, "cover-${path.hashCode()}.png")
                    api.projectCover(path, file.absolutePath, COVER_SIDE, COVER_SIDE)
                    val painted = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath) }
                    _ui.update { it.copy(cover = painted) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "project.cover failed", error)
                }
            }
        }
        viewModelScope.launch {
            try {
                val cards = api.shapes()
                _ui.update { it.copy(stickers = cards) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "shapes.list failed", error)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            for (seconds in seeks) preview?.seek(seconds)
        }
        viewModelScope.launch {
            while (true) {
                val shown = preview
                if (shown != null && SystemClock.uptimeMillis() - scrubbedAt > 250) {
                    val (at, playing, slower) = withContext(Dispatchers.IO) {
                        Triple(shown.position(), shown.isPlaying(), shown.slower())
                    }
                    val current = _ui.value
                    if (abs(current.position - at) > 1e-4 || current.playing != playing || current.previewSlower != slower) {
                        _ui.update { it.copy(position = at, playing = playing, previewSlower = slower) }
                    }
                }
                delay(if (_ui.value.playing) 24 else 250)
            }
        }
    }

    // -- the preview's surface, from the SurfaceHolder callbacks (main thread) --

    fun surfaceChanged(surface: Surface, width: Int, height: Int) {
        pending = Triple(surface, width, height)
        preview?.attachSurface(surface, width, height, MODE)
    }

    fun surfaceDestroyed() {
        pending = null
        preview?.detachSurface()
    }

    // -- transport & frame stepping --

    fun togglePlay() {
        val shown = preview ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (shown.isPlaying()) shown.pause() else shown.play()
            _ui.update { it.copy(playing = shown.isPlaying()) }
        }
    }

    fun pause() {
        val shown = preview ?: return
        viewModelScope.launch(Dispatchers.IO) {
            shown.pause()
            _ui.update { it.copy(playing = false) }
        }
    }

    /** Moves the playhead to [seconds] at once, the frame as soon as the engine can. */
    fun scrubTo(seconds: Double) {
        val duration = _ui.value.view?.duration ?: 0.0
        val at = seconds.coerceIn(0.0, duration)
        scrubbedAt = SystemClock.uptimeMillis()
        if (_ui.value.playing) pause()
        _ui.update { it.copy(position = at) }
        seeks.trySend(at)
    }

    /** Maju atau mundur tepat 1 frame sesuai FPS proyek (mis. 1/30s = 33.3ms). */
    fun stepFrame(deltaFrames: Int) {
        val view = _ui.value.view ?: return
        val fps = view.fps.takeIf { it > 0.0 } ?: 30.0
        val step = deltaFrames.toDouble() / fps
        scrubTo((_ui.value.position + step).coerceIn(0.0, view.duration))
    }

    // -- beat / irama markers --

    /** Menambah atau menghapus penanda irama pada posisi playhead aktif. */
    fun toggleMarker() {
        val view = _ui.value.view ?: return
        val fps = view.fps.takeIf { it > 0.0 } ?: 30.0
        val tolerance = 0.6 / fps
        val at = _ui.value.position.coerceIn(0.0, view.duration)
        val existing = _ui.value.markers.toMutableList()
        val nearIdx = existing.indexOfFirst { abs(it - at) <= tolerance }
        if (nearIdx >= 0) {
            existing.removeAt(nearIdx)
        } else {
            existing.add(at)
            existing.sort()
        }
        persistMarkers(existing)
        _ui.update { it.copy(markers = existing) }
    }

    fun clearMarkers() {
        persistMarkers(emptyList())
        _ui.update { it.copy(markers = emptyList()) }
        say("Semua penanda irama dihapus.")
    }

    private fun loadMarkers(): List<Double> {
        val file = File(path, MARKERS_FILE)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { arr.optDouble(it) }.filter { !it.isNaN() && it >= 0.0 }.sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistMarkers(list: List<Double>) {
        try {
            val arr = JSONArray().apply { list.forEach { put(it) } }
            File(path, MARKERS_FILE).writeText(arr.toString())
        } catch (error: Exception) {
            Log.w(TAG, "failed saving markers", error)
        }
    }

    fun select(clipId: String?) = _ui.update { it.copy(selected = clipId) }

    // -- edits --

    private fun change(label: String, body: suspend () -> ProjectView?) {
        viewModelScope.launch {
            edits.withLock {
                _ui.update { it.copy(busy = true) }
                try {
                    val view = body()
                    if (view != null) {
                        withContext(Dispatchers.IO) { preview?.refresh() }
                        _ui.update { ui ->
                            ui.copy(
                                view = view,
                                selected = ui.selected?.takeIf { view.clip(it) != null },
                                position = ui.position.coerceAtMost(view.duration),
                            )
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "$label failed", error)
                    say(error.message ?: "$label did not work.")
                } finally {
                    _ui.update { it.copy(busy = false) }
                }
            }
        }
    }

    private suspend fun apply(command: JSONObject) = api.apply(path, command)

    /** Mengirim perintah teks dan otomatis menghapus field `fontPath` jika engine hanya menerima `fontFamily`. */
    private suspend fun applyTextSafe(command: JSONObject): ProjectView {
        return try {
            apply(command)
        } catch (error: Exception) {
            val stripped = stripFontPathFromCommand(command)
            if (stripped != null) apply(stripped) else throw error
        }
    }

    private fun stripFontPathFromCommand(cmd: JSONObject): JSONObject? {
        val copy = JSONObject(cmd.toString())
        var changed = false
        copy.optJSONObject("style")?.let { style ->
            if (style.has("fontPath")) {
                style.remove("fontPath")
                changed = true
            }
        }
        copy.optJSONObject("patch")?.optJSONObject("text")?.let { text ->
            if (text.has("fontPath")) {
                text.remove("fontPath")
                changed = true
            }
        }
        copy.optJSONArray("commands")?.let { arr ->
            for (i in 0 until arr.length()) {
                val sub = arr.optJSONObject(i) ?: continue
                sub.optJSONObject("patch")?.optJSONObject("text")?.let { text ->
                    if (text.has("fontPath")) {
                        text.remove("fontPath")
                        changed = true
                    }
                }
                sub.optJSONObject("style")?.let { style ->
                    if (style.has("fontPath")) {
                        style.remove("fontPath")
                        changed = true
                    }
                }
            }
        }
        return if (changed) copy else null
    }

    fun edit(label: String, body: suspend () -> ProjectView?) = change(label, body)

    fun move(clipId: String, start: Double, target: LaneTarget) {
        val at = start.coerceAtLeast(0.0)
        change("Move") {
            when (target) {
                is LaneTarget.Existing -> apply(Commands.moveClips(listOf(Triple(clipId, at, target.trackId))))
                LaneTarget.NewTop, LaneTarget.NewBottom ->
                    apply(Commands.moveClipsToNewTrack(listOf(clipId to at), bottom = target == LaneTarget.NewBottom))
            }
        }
    }

    fun trim(clipId: String, edge: String, delta: Double) {
        change("Trim") { apply(Commands.trimClip(clipId, edge, delta, ripple = false)) }
    }

    fun placeSound(file: String, at: Double, label: String) {
        change(label) {
            val after = api.import(path, file)
            val id = after.createdId ?: after.media.firstOrNull { it.path == file || File(it.path).name == File(file).name }?.id
                ?: return@change after
            val view = apply(Commands.addClipsInSequence(listOf(id), at))
            view.createdId?.let { created -> _ui.update { it.copy(selected = created) } }
            view
        }
    }

    // -- effects, filters, transitions --

    fun applyPackage(kind: String, packageId: String, name: String) {
        if (kind == "transition") {
            val clip = selectedClip()
            if (clip?.kind == ClipKind.Transition) {
                val patch = JSONObject().put("name", name)
                    .put("videoEffects", org.json.JSONArray().put(AppliedEffect.fresh(packageId)))
                change("Transition") { apply(Commands.updateClip(clip.id, patch)) }
                return
            }
            val at = clip?.start ?: _ui.value.position
            val start = (at - 0.5).coerceAtLeast(0.0)
            change("Transition") {
                val view = apply(Commands.addTransitionClip(packageId, name, start, duration = 1.0))
                view.createdId?.let { id -> _ui.update { it.copy(selected = id) } }
                view
            }
            return
        }
        val clip = selectedClip() ?: return say("Select a clip first.")
        val field = if (kind == "audio") "filters" else "videoEffects"
        val chain = (if (kind == "audio") clip.filters else clip.videoEffects).map { it.raw }
        change(name) { apply(Commands.setChain(clip.id, field, chain + AppliedEffect.fresh(packageId))) }
    }

    private val liveChains = Channel<Triple<String, String, List<JSONObject>>>(Channel.CONFLATED)
    private val liveText = Channel<Pair<String, JSONObject>>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            for ((clipId, field, chain) in liveChains) {
                edits.withLock {
                    try {
                        val view = apply(Commands.setChain(clipId, field, chain))
                        withContext(Dispatchers.IO) { preview?.refresh() }
                        _ui.update { it.copy(view = view) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w(TAG, "live parameter failed", error)
                    }
                }
            }
        }
        viewModelScope.launch {
            for ((clipId, style) in liveText) {
                edits.withLock {
                    try {
                        val existing = _ui.value.view?.clip(clipId) ?: return@withLock
                        val lane = _ui.value.view?.tracks?.firstOrNull { it.id == existing.trackId }
                        val captions = lane?.captions
                        val view = if (lane != null && captions != null) {
                            val words = Commands.updateClip(existing.id, JSONObject().put("text", style))
                            val shared = JSONObject(style.toString()).put("content", "")
                            val look = JSONObject(captions.toString()).put("style", shared)
                            applyTextSafe(Commands.batch(listOf(words, Commands.setCaptionLook(lane.id, look))))
                        } else {
                            applyTextSafe(Commands.updateClip(existing.id, JSONObject().put("text", style)))
                        }
                        withContext(Dispatchers.IO) { preview?.refresh() }
                        _ui.update { it.copy(view = view) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w(TAG, "live text failed", error)
                    }
                }
            }
        }
    }

    fun previewText(clipId: String, style: JSONObject) {
        liveText.trySend(clipId to style)
    }

    fun setLink(clipId: String, field: String, index: Int, link: JSONObject) {
        val clip = _ui.value.view?.clip(clipId) ?: return
        val chain = (if (field == "filters") clip.filters else clip.videoEffects).map { it.raw }.toMutableList()
        if (index !in chain.indices) return
        chain[index] = link
        liveChains.trySend(Triple(clipId, field, chain))
    }

    fun removeLink(clipId: String, field: String, index: Int) {
        val clip = _ui.value.view?.clip(clipId) ?: return
        val chain = (if (field == "filters") clip.filters else clip.videoEffects).map { it.raw }.toMutableList()
        if (index !in chain.indices) return
        chain.removeAt(index)
        change("Remove") { apply(Commands.setChain(clipId, field, chain)) }
    }

    fun removeLinks(clipId: String, links: List<Pair<String, Int>>) {
        val clip = _ui.value.view?.clip(clipId) ?: return
        if (links.isEmpty()) return
        val commands = listOf("videoEffects", "filters").mapNotNull { field ->
            val gone = links.filter { it.first == field }.map { it.second }.toSet()
            if (gone.isEmpty()) return@mapNotNull null
            val chain = (if (field == "filters") clip.filters else clip.videoEffects).map { it.raw }
            Commands.setChain(clipId, field, chain.filterIndexed { i, _ -> i !in gone })
        }
        change("Remove") { apply(if (commands.size == 1) commands[0] else Commands.batch(commands)) }
    }

    fun undo() = change("Undo") { api.undo(path) }
    fun redo() = change("Redo") { api.redo(path) }

    fun split() {
        val clip = selectedClip() ?: return
        change("Split") { apply(Commands.splitClips(listOf(clip.id), _ui.value.position)) }
    }

    fun delete() {
        val clip = selectedClip() ?: return
        val ripple = clip.kind != ClipKind.Text
        _ui.update { it.copy(selected = null) }
        change("Delete") { apply(Commands.removeClips(listOf(clip.id), ripple = ripple)) }
    }

    fun duplicate() {
        val clip = selectedClip() ?: return
        if (clip.kind == ClipKind.Text) {
            change("Duplicate") {
                val baseText = clip.text?.optString("content")?.ifBlank { clip.name } ?: clip.name
                val clonedStyle = JSONObject(clip.text?.toString() ?: "{}").put("content", baseText)
                val afterAdd = applyTextSafe(Commands.addTextClip(clip.start, clonedStyle, duration = clip.duration, trackId = null))
                val newId = afterAdd.createdId
                val finalView = if (newId != null && (clip.scale != 1.0 || clip.offsetX != 0.0 || clip.offsetY != 0.0 || clip.rotation != 0.0)) {
                    apply(Commands.setClipTransform(newId, clip.scale, clip.offsetX, clip.offsetY, clip.rotation))
                } else {
                    afterAdd
                }
                if (newId != null) {
                    setClipRoles(mapOf(newId to ROLE_DUPLICATE))
                    _ui.update { it.copy(selected = newId) }
                }
                finalView
            }
            return
        }
        change("Duplicate") {
            val view = apply(Commands.duplicateClips(listOf(clip.id)))
            view.createdId?.let { id -> _ui.update { it.copy(selected = id) } }
            view
        }
    }

    fun freeze() {
        val clip = selectedClip() ?: return
        val at = _ui.value.position
        val edge = 1.0 / (_ui.value.view?.fps ?: 30.0)
        if (at <= clip.start + edge || at >= clip.end - edge) return say("Move the playhead inside the clip to freeze it.")
        change("Freeze") {
            val view = api.clipsFreeze(path, clip.id, at)
            view.clips.firstOrNull { it.trackId == clip.trackId && abs(it.start - at) < 1e-6 }
                ?.let { hold -> _ui.update { it.copy(selected = hold.id) } }
            view
        }
    }

    fun replace(uri: Uri?) {
        val clip = selectedClip() ?: return
        if (uri == null) return
        change("Replace") {
            val file = withContext(Dispatchers.IO) { copyIn(uri) }
            api.clipsReplace(path, clip.id, file.absolutePath)
        }
    }

    private val reverses = JobTracker("reverse", ::onReverse)

    init {
        viewModelScope.launch { api.rawEvents.collect { reverses.offer(it) } }
    }

    fun reverse() {
        val clip = selectedClip() ?: return
        if (_ui.value.reversing != null) return say("A reverse is already running.")
        _ui.update { it.copy(reversing = clip.id to 0f) }
        reverses.expect()
        viewModelScope.launch {
            try {
                reverses.start(api.clipsReverse(path, clip.id))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reverses.stop()
                _ui.update { it.copy(reversing = null) }
                say(error.message ?: "The reverse did not start.")
            }
        }
    }

    fun cancelReverse() {
        val job = reverses.job ?: return
        viewModelScope.launch {
            try {
                api.reverseCancel(job)
            } catch (error: Exception) {
                Log.w(TAG, "reverse cancel failed", error)
            }
        }
    }

    private fun onReverse(event: JSONObject) {
        when (event.optString("event")) {
            "reverse.progress" -> _ui.update { ui ->
                ui.copy(reversing = ui.reversing?.copy(second = event.optDouble("fraction", 0.0).toFloat()))
            }
            "reverse.done" -> {
                reverses.stop()
                _ui.update { it.copy(reversing = null) }
                change("Reverse") { api.clipsUseReversed(path, event) }
            }
            "reverse.failed" -> {
                reverses.stop()
                _ui.update { it.copy(reversing = null) }
                if (!event.wasCancelled()) say(event.errorMessage())
            }
        }
    }

    fun setSpeed(speed: Double) {
        val clip = selectedClip() ?: return
        change("Speed") { apply(Commands.setClipSpeed(clip.id, speed)) }
    }

    fun setVolume(volume: Double) {
        val clip = selectedClip() ?: return
        change("Volume") { apply(Commands.updateClip(clip.id, JSONObject().put("volume", volume))) }
    }

    fun setOpacity(opacity: Double) {
        val clip = selectedClip() ?: return
        change("Opacity") { apply(Commands.updateClip(clip.id, JSONObject().put("opacity", opacity.coerceIn(0.0, 1.0)))) }
    }

    fun setFades(fadeIn: Double, fadeOut: Double) {
        val clip = selectedClip() ?: return
        change("Fade") { apply(Commands.updateClip(clip.id, JSONObject().put("fadeIn", fadeIn).put("fadeOut", fadeOut))) }
    }

    fun setLook(label: String, patch: JSONObject) {
        val clip = selectedClip() ?: return
        change(label) { apply(Commands.updateClip(clip.id, patch)) }
    }

    fun toggleMute() {
        val clip = selectedClip() ?: return
        change(if (clip.muted) "Unmute" else "Mute") { apply(Commands.updateClip(clip.id, JSONObject().put("muted", !clip.muted))) }
    }

    fun setTrackSoundMuted(trackId: String, muted: Boolean) {
        val clips = _ui.value.view?.clips?.filter { it.trackId == trackId && it.kind == ClipKind.Video }.orEmpty()
        if (clips.isEmpty()) return
        change(if (muted) "Mute" else "Unmute") {
            apply(Commands.batch(clips.map { Commands.updateClip(it.id, JSONObject().put("muted", muted)) }))
        }
    }

    fun flip() {
        val clip = selectedClip() ?: return
        change("Flip") { apply(Commands.updateClip(clip.id, JSONObject().put("flipH", !clip.flipH))) }
    }

    fun setTransform(clipId: String, scale: Double, offsetX: Double, offsetY: Double, rotation: Double) {
        change("Move") {
            val roles = _ui.value.clipRoles
            val isImported = roles[clipId] == ROLE_IMPORTED
            val targets = if (isImported) {
                _ui.value.view?.clips
                    ?.filter { it.kind == ClipKind.Text && roles[it.id] == ROLE_IMPORTED }
                    ?.map { it.id }
                    .orEmpty()
            } else {
                listOf(clipId)
            }
            if (targets.size <= 1) {
                apply(Commands.setClipTransform(clipId, scale, offsetX, offsetY, rotation))
            } else {
                apply(Commands.batch(targets.map { id -> Commands.setClipTransform(id, scale, offsetX, offsetY, rotation) }))
            }
        }
    }

    fun resetTransform() {
        val clip = selectedClip() ?: return
        val defaultY = if (_ui.value.clipRoles[clip.id] == ROLE_IMPORTED) SubtitleCodec.DEFAULT_SUBTITLE_OFFSET_Y else 0.0
        setTransform(clip.id, 1.0, 0.0, defaultY, 0.0)
    }

    fun toggleKeyframe() {
        val clip = selectedClip() ?: return
        if (clip.duration <= 0) return
        val at = ((_ui.value.position - clip.start) / clip.duration).coerceIn(0.0, 1.0)
        val tolerance = 0.5 / ((_ui.value.view?.fps ?: 30.0) * clip.duration)
        val properties = listOf(
            "scale" to clip.scale, "offsetX" to clip.offsetX, "offsetY" to clip.offsetY,
            "rotation" to clip.rotation, "opacity" to clip.opacity,
        )
        if (clip.keyedAt(at, tolerance)) {
            val commands = clip.keys.flatMap { (property, ats) ->
                ats.filter { abs(it - at) <= tolerance }.map { Commands.clearClipKey(clip.id, property, it) }
            }
            change("Keyframe") { apply(Commands.batch(commands)) }
        } else {
            change("Keyframe") { apply(Commands.batch(properties.map { (name, value) -> Commands.setClipKey(clip.id, name, at, value) })) }
        }
    }

    fun setAspect(width: Int, height: Int) {
        val view = _ui.value.view ?: return
        if (view.width == width && view.height == height) return
        change("Aspect") { api.setVideo(path, width, height, view.rateNum, view.rateDen) }
    }

    fun setBackground(background: JSONObject) {
        val view = _ui.value.view ?: return
        change("Background") { apply(Commands.setTimelineBackground(view.timelineId, background)) }
    }

    fun rename(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        change("Rename") {
            api.rename(path, clean)
            api.get(path)
        }
    }

    fun selectAtPlayhead(trackId: String?) {
        val view = _ui.value.view ?: return
        val at = _ui.value.position
        val clip = view.clips.firstOrNull { (trackId == null || it.trackId == trackId) && at >= it.start && at < it.end }
            ?: view.clips.filter { trackId == null || it.trackId == trackId }.minByOrNull { abs(it.start - at) }
        select(clip?.id)
    }

    fun saveText(clipId: String?, style: JSONObject) {
        val content = style.optString("content").trim()
        if (content.isEmpty()) return
        val cleanStyle = JSONObject(style.toString()).put("content", content)
        val viewNow = _ui.value.view
        val existing = viewNow?.clip(clipId)
        val lane = existing?.let { clip -> viewNow.tracks.firstOrNull { it.id == clip.trackId } }
        val captions = lane?.captions
        val roles = _ui.value.clipRoles
        val isImported = existing != null && roles[existing.id] == ROLE_IMPORTED

        change("Text") {
            if (existing != null && lane != null && captions != null) {
                val words = Commands.updateClip(existing.id, JSONObject().put("text", cleanStyle))
                val shared = JSONObject(cleanStyle.toString()).put("content", "")
                val look = JSONObject(captions.toString()).put("style", shared)
                applyTextSafe(Commands.batch(listOf(words, Commands.setCaptionLook(lane.id, look))))
            } else if (existing != null && isImported && viewNow != null) {
                val importedClips = viewNow.clips.filter { it.kind == ClipKind.Text && roles[it.id] == ROLE_IMPORTED }
                val commands = importedClips.map { target ->
                    val targetContent = if (target.id == existing.id) {
                        content
                    } else {
                        target.text?.optString("content")?.ifBlank { target.name } ?: target.name
                    }
                    val targetStyle = JSONObject(cleanStyle.toString()).put("content", targetContent)
                    Commands.updateClip(target.id, JSONObject().put("text", targetStyle))
                }
                val result = applyTextSafe(if (commands.size == 1) commands[0] else Commands.batch(commands))
                if (importedClips.size > 1) {
                    say("Gaya diterapkan ke ${importedClips.size} subtitle impor.")
                }
                result
            } else if (existing != null) {
                applyTextSafe(Commands.updateClip(existing.id, JSONObject().put("text", cleanStyle)))
            } else {
                val view = applyTextSafe(Commands.addTextClip(_ui.value.position, cleanStyle))
                view.createdId?.let { id -> _ui.update { it.copy(selected = id) } }
                view
            }
        }
    }

    // -- custom fonts & saved color palette --

    fun importFont(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    val filesDir = getApplication<Application>().filesDir
                    val resolver = getApplication<Application>().contentResolver
                    var rawName = "custom-font-${System.currentTimeMillis()}.ttf"
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) rawName = it.getString(0) ?: rawName
                    }
                    val lower = rawName.lowercase(Locale.ROOT)
                    if (!lower.endsWith(".ttf") && !lower.endsWith(".otf") && !lower.endsWith(".ttc")) {
                        throw IOException("Pilih file font berformat .ttf atau .otf.")
                    }
                    val fontsDir = File(filesDir, "fonts").apply { mkdirs() }
                    val xdgFontsDir = File(filesDir, ".local/share/fonts").apply { mkdirs() }
                    val safeName = rawName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    val dest = File(fontsDir, safeName)
                    resolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IOException("Tidak dapat membuka file font.")
                    dest.copyTo(File(xdgFontsDir, safeName), overwrite = true)
                    parseCustomFont(dest)
                }
                _ui.update { it.copy(customFonts = scanCustomFonts()) }
                say("Font \"${imported.label}\" ditambahkan. Tutup & buka ulang aplikasi jika font baru belum tampil di preview.")
            } catch (error: Exception) {
                Log.w(TAG, "import font failed", error)
                say(error.message ?: "Gagal mengimpor font.")
            }
        }
    }

    fun saveColorSwatch(hex: String) {
        val clean = normalizeHex(hex) ?: return
        val current = _ui.value.savedColors.toMutableList()
        if (current.any { it.equals(clean, ignoreCase = true) }) {
            say("Warna $clean sudah tersimpan.")
            return
        }
        current.add(0, clean)
        persistSavedColors(current)
        _ui.update { it.copy(savedColors = current) }
        say("Warna $clean disimpan.")
    }

    fun removeColorSwatch(hex: String) {
        val updated = _ui.value.savedColors.filterNot { it.equals(hex, ignoreCase = true) }
        persistSavedColors(updated)
        _ui.update { it.copy(savedColors = updated) }
    }

    private fun scanCustomFonts(): List<CustomFont> {
        val dir = File(getApplication<Application>().filesDir, "fonts")
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".ttf", true) || it.name.endsWith(".otf", true) || it.name.endsWith(".ttc", true)) }
            ?.sortedBy { it.name.lowercase(Locale.ROOT) }
            ?.map { file -> parseCustomFont(file) }
            .orEmpty()
    }

    /**
     * Membaca tabel OpenType `name` untuk mengekstrak:
     * - Typographic Family (nameId=16, mis. "Satoshi" untuk fontdb Rust)
     * - Subfamily / Style (nameId=17 / nameId=2, mis. "Black", "Bold", "Italic")
     */
    private fun parseCustomFont(file: File): CustomFont {
        var typographicFamily: String? = null
        var legacyFamily: String? = null
        var subfamily: String? = null
        try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() >= 12) {
                    raf.seek(4)
                    val numTables = raf.readUnsignedShort()
                    raf.skipBytes(6)
                    var nameOffset = -1L
                    for (i in 0 until numTables) {
                        val tagBytes = ByteArray(4)
                        raf.readFully(tagBytes)
                        val tag = String(tagBytes, StandardCharsets.US_ASCII)
                        raf.skipBytes(4)
                        val offset = raf.readInt().toLong() and 0xFFFFFFFFL
                        raf.skipBytes(4)
                        if (tag == "name") {
                            nameOffset = offset
                            break
                        }
                    }
                    if (nameOffset > 0 && nameOffset + 6 < raf.length()) {
                        raf.seek(nameOffset + 2)
                        val count = raf.readUnsignedShort()
                        val stringOffset = raf.readUnsignedShort()
                        for (i in 0 until count) {
                            val platformId = raf.readUnsignedShort()
                            val encodingId = raf.readUnsignedShort()
                            raf.skipBytes(2)
                            val nameId = raf.readUnsignedShort()
                            val length = raf.readUnsignedShort()
                            val offset = raf.readUnsignedShort()
                            if ((nameId == 16 || nameId == 1 || nameId == 17 || nameId == 2) && length > 0) {
                                val savedPos = raf.filePointer
                                val strPos = nameOffset + stringOffset + offset
                                if (strPos + length <= raf.length()) {
                                    raf.seek(strPos)
                                    val buf = ByteArray(length)
                                    raf.readFully(buf)
                                    val decoded = if (platformId == 3 || platformId == 0 || encodingId == 1) {
                                        String(buf, StandardCharsets.UTF_16BE).trim()
                                    } else {
                                        String(buf, StandardCharsets.UTF_8).trim()
                                    }
                                    if (decoded.isNotBlank()) {
                                        when (nameId) {
                                            16 -> if (platformId == 3 || typographicFamily == null) typographicFamily = decoded
                                            1 -> if (platformId == 3 || legacyFamily == null) legacyFamily = decoded
                                            17 -> if (platformId == 3 || subfamily == null) subfamily = decoded
                                            2 -> if (subfamily == null) subfamily = decoded
                                        }
                                    }
                                }
                                raf.seek(savedPos)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        val resolvedFamily = (typographicFamily ?: legacyFamily ?: file.nameWithoutExtension).trim()
        val hint = "${subfamily.orEmpty()} ${file.nameWithoutExtension}".lowercase(Locale.ROOT)
        val weight = when {
            "black" in hint || "heavy" in hint -> 900.0
            "extrabold" in hint || "ultrabold" in hint -> 800.0
            "bold" in hint -> 700.0
            "semibold" in hint || "demibold" in hint -> 600.0
            "medium" in hint -> 500.0
            "light" in hint -> 300.0
            "thin" in hint -> 200.0
            else -> 400.0
        }
        val italic = "italic" in hint || "oblique" in hint
        val displayLabel = if (!subfamily.isNullOrBlank() && !subfamily.equals("Regular", true) && !resolvedFamily.contains(subfamily, true)) {
            "$resolvedFamily $subfamily"
        } else {
            legacyFamily ?: resolvedFamily
        }
        return CustomFont(
            label = displayLabel,
            family = resolvedFamily,
            weight = weight,
            italic = italic,
            fileName = file.name,
            path = file.absolutePath,
        )
    }

    private fun loadSavedColors(): List<String> {
        val raw = prefs.getString(KEY_SAVED_COLORS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { normalizeHex(arr.optString(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistSavedColors(colors: List<String>) {
        val arr = JSONArray().apply { colors.forEach { put(it) } }
        prefs.edit().putString(KEY_SAVED_COLORS, arr.toString()).apply()
    }

    private fun normalizeHex(raw: String): String? {
        val clean = raw.trim().removePrefix("#")
        if (clean.length != 6 && clean.length != 8) return null
        if (!clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return "#${clean.lowercase(Locale.ROOT)}"
    }

    private fun loadClipRoles(): Map<String, String> {
        val file = File(path, ROLES_FILE)
        if (!file.exists()) return emptyMap()
        return try {
            val json = JSONObject(file.readText())
            buildMap {
                for (key in json.keys()) {
                    val value = json.optString(key)
                    if (value.isNotEmpty()) put(key, value)
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun setClipRoles(entries: Map<String, String>) {
        if (entries.isEmpty()) return
        val merged = _ui.value.clipRoles + entries
        _ui.update { it.copy(clipRoles = merged) }
        try {
            val json = JSONObject().apply { merged.forEach { (k, v) -> put(k, v) } }
            File(path, ROLES_FILE).writeText(json.toString())
        } catch (error: Exception) {
            Log.w(TAG, "failed saving clip roles", error)
        }
    }

    // -- eyedropper & subtitles --

    suspend fun grabEyedropperFrame(): Bitmap? = withContext(Dispatchers.IO) {
        val view = _ui.value.view ?: return@withContext null
        val tall = view.height >= view.width
        val w = if (tall) 360 else 640
        val h = (w * view.height.toDouble() / view.width.coerceAtLeast(1)).toInt().coerceAtLeast(2)
        val out = File(getApplication<Application>().cacheDir, "eyedropper-${path.hashCode()}.png")
        try {
            api.previewFrameTo(path, _ui.value.position, out.absolutePath, w, h)
            BitmapFactory.decodeFile(out.absolutePath)
        } catch (error: Exception) {
            Log.w(TAG, "eyedropper frame failed", error)
            null
        }
    }

    fun importSubtitles(uri: Uri?) {
        if (uri == null) return
        val view = _ui.value.view ?: return
        change("Import Subtitles") {
            val (rawText, fileName) = withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                var name = "subtitle.srt"
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) name = it.getString(0) ?: name
                }
                val text = resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: throw IOException("Tidak dapat membaca file subtitle.")
                text to name
            }
            val cues = SubtitleCodec.parse(rawText, fileName, view.height)
            if (cues.isEmpty()) {
                say("Tidak ada baris subtitle yang terbaca.")
                return@change null
            }
            val beforeIds = view.clips.filter { it.kind == ClipKind.Text }.map { it.id }.toSet()
            val cmds = SubtitleCodec.buildImportCommands(cues)
            val afterCreate = apply(Commands.batch(cmds))
            val importedClips = afterCreate.clips.filter { it.kind == ClipKind.Text && it.id !in beforeIds }

            val finalView = if (importedClips.isNotEmpty()) {
                setClipRoles(importedClips.associate { it.id to ROLE_IMPORTED })
                val moveBottomCmds = importedClips.map { clip ->
                    Commands.setClipTransform(
                        clipId = clip.id,
                        scale = clip.scale,
                        offsetX = SubtitleCodec.DEFAULT_SUBTITLE_OFFSET_X,
                        offsetY = SubtitleCodec.DEFAULT_SUBTITLE_OFFSET_Y,
                        rotation = clip.rotation,
                    )
                }
                apply(Commands.batch(moveBottomCmds))
            } else {
                afterCreate
            }
            say("Berhasil impor ${cues.size} baris subtitle di posisi bawah.")
            finalView
        }
    }

    fun exportSubtitles(uri: Uri?, asAss: Boolean) {
        if (uri == null) return
        val view = _ui.value.view ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val content = if (asAss) {
                    SubtitleCodec.exportAss(view)
                } else {
                    SubtitleCodec.exportSrt(view)
                }
                getApplication<Application>().contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                    it.write(content)
                } ?: throw IOException("Gagal membuka tujuan simpan.")
                say(if (asAss) "File .ASS berhasil diekspor." else "File .SRT berhasil diekspor.")
            } catch (error: Exception) {
                Log.w(TAG, "export subtitles failed", error)
                say(error.message ?: "Gagal mengekspor subtitle.")
            }
        }
    }

    // -- cover --

    fun setCoverFrame() {
        val at = _ui.value.position
        change("Cover") { apply(Commands.setCover(time = at)) }
    }

    fun setCoverPicture(uri: Uri?) {
        if (uri == null) return
        change("Cover") {
            val file = withContext(Dispatchers.IO) { copyIn(uri) }
            apply(Commands.setCover(picture = file.absolutePath))
        }
    }

    fun resetCover() {
        if (_ui.value.view?.cover == null) return
        change("Cover") { apply(Commands.setCover()) }
    }

    fun addSticker(card: ShapeCard, name: String) {
        change("Sticker") {
            val view = apply(Commands.addShapeClip(_ui.value.position, card.style, name))
            view.createdId?.let { id -> _ui.update { it.copy(selected = id) } }
            view
        }
    }

    fun import(uris: List<Uri>, audio: Boolean) {
        if (uris.isEmpty()) return
        change("Import") {
            var view: ProjectView? = null
            val ids = mutableListOf<String>()
            for (uri in uris) {
                val file = withContext(Dispatchers.IO) { copyIn(uri) }
                val after = api.import(path, file.absolutePath)
                view = after
                val id = after.createdId ?: after.media.firstOrNull { it.path == file.absolutePath || File(it.path).name == file.name }?.id
                if (id != null) ids += id
            }
            if (ids.isEmpty()) return@change view
            val start = if (audio) _ui.value.position else view?.pictureEnd ?: 0.0
            apply(Commands.addClipsInSequence(ids, start))
        }
    }

    fun importOverlay(uris: List<Uri>) {
        if (uris.isEmpty()) return
        change("Overlay") {
            var view: ProjectView? = null
            for (uri in uris) {
                val file = withContext(Dispatchers.IO) { copyIn(uri) }
                val after = api.import(path, file.absolutePath)
                val id = after.createdId ?: after.media.firstOrNull { it.path == file.absolutePath || File(it.path).name == file.name }?.id
                    ?: continue
                val lane = apply(Commands.addTrack(bottom = false))
                val trackId = lane.createdId ?: lane.tracks.last().id
                view = apply(Commands.addClip(id, trackId, _ui.value.position, ripple = false))
                view.createdId?.let { created -> _ui.update { it.copy(selected = created) } }
            }
            view
        }
    }

    private fun copyIn(uri: Uri): File {
        val resolver = getApplication<Application>().contentResolver
        var name = "picked-${System.currentTimeMillis()}"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
        val target = File(app.mediaDir, "${System.currentTimeMillis()}-${name.replace('/', '_')}")
        val input = resolver.openInputStream(uri) ?: throw IOException("The provider would not open $uri")
        input.use { stream -> target.outputStream().use { stream.copyTo(it, 1 shl 20) } }
        return target
    }

    // -- export --

    fun export(preset: ExportPreset, fps: ExportFps = EXPORT_FPS_OPTIONS[0]) {
        val view = _ui.value.view ?: return
        pause()
        viewModelScope.launch {
            try {
                api.save(path)
            } catch (error: Exception) {
                Log.w(TAG, "save before export failed", error)
            }
            exports.start(path, view.name.ifBlank { "Concat" }, view.width, view.height, preset, fps)
                .onFailure { say(it.message ?: "The export did not start.") }
        }
    }

    // -- lifecycle --

    fun save() {
        if (closed) return
        app.scope.launch {
            try {
                api.save(path)
            } catch (error: Exception) {
                Log.w(TAG, "save failed", error)
            }
        }
    }

    fun close(done: () -> Unit) {
        if (closed) return done()
        closed = true
        val shown = preview
        preview = null
        viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    shown?.pause()
                    shown?.release()
                    writeCard()
                    api.close(path, save = true)
                } catch (error: Exception) {
                    Log.w(TAG, "close failed", error)
                }
            }
            done()
        }
    }

    private suspend fun writeCard() {
        val view = _ui.value.view ?: return
        val folder = File(path)
        try {
            File(folder, CARD).writeText(
                JSONObject().put("duration", view.duration).put("clips", view.clips.size)
                    .put("savedAt", System.currentTimeMillis()).toString(),
            )
        } catch (error: Exception) {
            Log.w(TAG, "no card for $path", error)
        }
        if (view.clips.isEmpty()) {
            File(folder, THUMBNAIL).delete()
            return
        }
        val tall = view.height >= view.width
        val width = if (tall) 270 else 480
        val height = (width * view.height.toDouble() / view.width.coerceAtLeast(1)).toInt()
        try {
            api.projectCover(path, File(folder, THUMBNAIL).absolutePath, width, height)
        } catch (error: Exception) {
            Log.w(TAG, "no thumbnail for $path: ${error.message}")
        }
    }

    override fun onCleared() {
        val shown = preview
        preview = null
        if (shown != null) app.scope.launch(Dispatchers.IO) { shown.release() }
    }

    fun dismissMessage() = _ui.update { it.copy(message = null) }
    private fun say(message: String) = _ui.update { it.copy(message = message) }
    private fun selectedClip() = _ui.value.view?.clip(_ui.value.selected)

    companion object {
        private const val TAG = "concat-editor"
        private const val MODE = "vulkan"
        private const val PREFS_NAME = "concat_editor_prefs"
        private const val KEY_SAVED_COLORS = "saved_colors"
        const val ROLE_IMPORTED = "imported"
        const val ROLE_DUPLICATE = "duplicate"
        const val ROLES_FILE = ".concat-clip-roles.json"
        const val MARKERS_FILE = ".concat-markers.json"
        const val THUMBNAIL = ".concat-thumb.png"
        const val CARD = ".concat-card.json"
        private const val COVER_SIDE = 160
    }
}
