// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.concat.editor.api.AppliedEffect
import app.concat.editor.api.Clip
import app.concat.editor.api.ClipKind
import app.concat.editor.api.Commands
import app.concat.editor.api.PackageInfo
import app.concat.editor.api.ProjectView
import app.concat.editor.ui.components.ChoiceChip
import app.concat.editor.ui.components.ConcatIcon
import app.concat.editor.ui.components.GhostButton
import app.concat.editor.ui.components.PanelLabel
import app.concat.editor.ui.components.ToolPanel
import app.concat.editor.ui.components.ValueSlider
import app.concat.editor.ui.icons.ConcatIcons
import app.concat.editor.ui.theme.ConcatColors
import app.concat.editor.ui.theme.ConcatType
import app.concat.editor.ui.theme.Radii
import app.concat.editor.ui.theme.Spacing
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import org.json.JSONObject

private val SPEEDS = listOf(0.25, 0.5, 1.0, 1.5, 2.0, 3.0, 5.0)

/** Speed on a log rail from 0.1x to 10x, with the usual stops as chips. */
@Composable
fun SpeedPanel(clip: Clip, onApply: (Double) -> Unit, onClose: () -> Unit) {
    var rail by remember(clip.id) { mutableFloatStateOf(log2(clip.speed).toFloat()) }
    ToolPanel("Speed", onClose = onClose, onConfirm = onClose) {
        ValueSlider(
            "Speed",
            rail,
            log2(0.1).toFloat()..log2(10.0).toFloat(),
            { "%.2fx".format(2.0.pow(it.toDouble())) },
            onChange = { rail = it },
            onDone = { onApply(roundSpeed(2.0.pow(rail.toDouble()))) },
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            for (speed in SPEEDS) {
                ChoiceChip(
                    if (speed < 1) "${speed}x" else "${speed.toInt()}${if (speed % 1.0 != 0.0) ".5" else ""}x",
                    selected = abs(clip.speed - speed) < 1e-3,
                    onClick = {
                        rail = log2(speed).toFloat()
                        onApply(speed)
                    },
                )
            }
        }
        PanelLabel("Duration ${"%.1f".format(clip.duration)}s at ${"%.2f".format(clip.speed)}x")
    }
}

private fun log2(value: Double) = ln(value.coerceAtLeast(1e-3)) / ln(2.0)
private fun roundSpeed(value: Double) = (value * 100).toInt() / 100.0

/** The clip's gain, 0 to 200%. */
@Composable
fun VolumePanel(clip: Clip, onApply: (Double) -> Unit, onMute: () -> Unit, onClose: () -> Unit) {
    var value by remember(clip.id) { mutableFloatStateOf(clip.volume.toFloat()) }
    ToolPanel("Volume", onClose = onClose, onConfirm = onClose) {
        ValueSlider("Volume", value, 0f..2f, { "${(it * 100).toInt()}%" }, onChange = { value = it }, onDone = { onApply(value.toDouble()) })
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            for (preset in listOf(0.0, 0.5, 1.0, 1.5, 2.0)) {
                ChoiceChip("${(preset * 100).toInt()}%", selected = abs(clip.volume - preset) < 1e-3, onClick = {
                    value = preset.toFloat()
                    onApply(preset)
                })
            }
        }
        if (clip.kind == ClipKind.Video) {
            ChoiceChip(if (clip.muted) "Muted: tap for sound" else "Mute this clip", selected = clip.muted, onClick = onMute)
        }
    }
}

/** How see-through a picture or title is. */
@Composable
fun OpacityPanel(clip: Clip, onApply: (Double) -> Unit, onClose: () -> Unit) {
    var value by remember(clip.id) { mutableFloatStateOf(clip.opacity.toFloat()) }
    ToolPanel("Opacity", onClose = onClose, onConfirm = onClose) {
        ValueSlider("Opacity", value, 0f..1f, { "${(it * 100).toInt()}%" }, onChange = { value = it }, onDone = { onApply(value.toDouble()) })
    }
}

/** Sound ramps in and out. */
@Composable
fun FadePanel(clip: Clip, onApply: (Double, Double) -> Unit, onClose: () -> Unit) {
    val most = (clip.duration / 2).coerceIn(0.1, 5.0).toFloat()
    var fadeIn by remember(clip.id) { mutableFloatStateOf(clip.fadeIn.toFloat().coerceAtMost(most)) }
    var fadeOut by remember(clip.id) { mutableFloatStateOf(clip.fadeOut.toFloat().coerceAtMost(most)) }
    ToolPanel("Fade", onClose = onClose, onConfirm = onClose) {
        ValueSlider("Fade in", fadeIn, 0f..most, { "%.1fs".format(it) }, onChange = { fadeIn = it }, onDone = { onApply(fadeIn.toDouble(), fadeOut.toDouble()) })
        ValueSlider("Fade out", fadeOut, 0f..most, { "%.1fs".format(it) }, onChange = { fadeOut = it }, onDone = { onApply(fadeIn.toDouble(), fadeOut.toDouble()) })
    }
}

val TEXT_COLOURS = listOf(
    "#ffffff", "#000000", "#f4dad4", "#dd8c96", "#2e4060",
    "#cbf53f", "#ffd60a", "#ff9f0a", "#ff375f", "#bf5af2", "#0a84ff", "#30d158",
)

private val BUILTIN_FONTS = listOf("Hanken Grotesk", "sans-serif", "serif", "monospace")

fun colourOf(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: Exception) {
    Color.White
}

private fun hexFromColor(color: Color): String {
    val argb = color.toArgb()
    return String.format(Locale.US, "#%06x", argb and 0xFFFFFF)
}

private fun hsvOfHex(hex: String): FloatArray {
    val hsv = FloatArray(3)
    try {
        android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(hex), hsv)
    } catch (_: Exception) {
        hsv[0] = 0f
        hsv[1] = 0f
        hsv[2] = 1f
    }
    return hsv
}

private enum class TextTab { Text, Font, Fill, Outline, Box }
private enum class ColorTarget { Fill, Outline, Box }

/**
 * Panel editor teks real-time: mendukung gaya huruf, impor custom font (.ttf/.otf),
 * color palette spektrum penuh + save swatches, outline/stroke, drop shadow,
 * background plate, dan Eyedropper langsung dari frame video.
 */
@Composable
fun TextPanel(
    existing: Clip?,
    customFonts: List<CustomFont>,
    savedColors: List<String>,
    eyedropperActive: Boolean,
    pickedHex: String?,
    onImportFont: () -> Unit,
    onSaveColor: (String) -> Unit,
    onRemoveColor: (String) -> Unit,
    onToggleEyedropper: (Boolean) -> Unit,
    onLiveChange: (JSONObject) -> Unit,
    onSave: (JSONObject) -> Unit,
    onClose: () -> Unit,
    caption: Boolean = false,
) {
    val style = existing?.text
    var tab by remember { mutableStateOf(TextTab.Text) }
    var colorTarget by remember { mutableStateOf(ColorTarget.Fill) }

    var content by remember(existing?.id) { mutableStateOf(style?.optString("content") ?: "") }
    var size by remember(existing?.id) { mutableFloatStateOf((style?.optDouble("fontSize", 0.07) ?: 0.07).toFloat()) }
    var fontFamily by remember(existing?.id) { mutableStateOf(style?.optString("fontFamily", "Hanken Grotesk")?.ifBlank { "Hanken Grotesk" } ?: "Hanken Grotesk") }
    var colour by remember(existing?.id) { mutableStateOf(style?.optString("color", "#ffffff") ?: "#ffffff") }
    var weight by remember(existing?.id) { mutableStateOf(style?.optDouble("fontWeight", 700.0) ?: 700.0) }
    var italic by remember(existing?.id) { mutableStateOf(style?.optBoolean("italic", false) ?: false) }

    var strokeColour by remember(existing?.id) { mutableStateOf(style?.optString("strokeColor", "#000000") ?: "#000000") }
    var strokeWidth by remember(existing?.id) { mutableFloatStateOf((style?.optDouble("strokeWidth", 0.0) ?: 0.0).toFloat()) }
    var shadow by remember(existing?.id) { mutableStateOf(style?.optBoolean("shadow", true) ?: true) }
    var plateColour by remember(existing?.id) {
        mutableStateOf(style?.optString("plate")?.takeIf { it.startsWith("#") })
    }

    fun buildStyle(): JSONObject = Commands.textStyle(
        content = content.ifEmpty { " " },
        fontSize = size.toDouble(),
        color = colour,
        base = style,
        fontWeight = weight,
        italic = italic,
        strokeColor = strokeColour,
        strokeWidth = strokeWidth.toDouble(),
        shadow = shadow,
        plate = plateColour,
        clearPlate = plateColour == null,
        fontFamily = fontFamily,
    )

    fun pushLive() {
        if (existing != null && content.isNotBlank()) {
            onLiveChange(buildStyle())
        }
    }

    LaunchedEffect(pickedHex) {
        if (pickedHex != null && eyedropperActive) {
            when (colorTarget) {
                ColorTarget.Fill -> colour = pickedHex
                ColorTarget.Outline -> {
                    strokeColour = pickedHex
                    if (strokeWidth <= 0f) strokeWidth = 0.005f
                }
                ColorTarget.Box -> plateColour = pickedHex
            }
            pushLive()
        }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(existing?.id) { if (existing == null) focus.requestFocus() }

    fun save() {
        onToggleEyedropper(false)
        if (content.isNotBlank()) onSave(buildStyle())
        onClose()
    }

    ToolPanel(
        title = if (existing == null) "Add text" else if (caption) "Caption" else "Text",
        onClose = {
            onToggleEyedropper(false)
            onClose()
        },
        onConfirm = ::save,
    ) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            ChoiceChip("Text", selected = tab == TextTab.Text, onClick = { tab = TextTab.Text; onToggleEyedropper(false) })
            ChoiceChip("Font", selected = tab == TextTab.Font, onClick = { tab = TextTab.Font; onToggleEyedropper(false) })
            ChoiceChip("Color", selected = tab == TextTab.Fill, onClick = { tab = TextTab.Fill; colorTarget = ColorTarget.Fill })
            ChoiceChip("Outline", selected = tab == TextTab.Outline, onClick = { tab = TextTab.Outline; colorTarget = ColorTarget.Outline })
            ChoiceChip("Box & Shadow", selected = tab == TextTab.Box, onClick = { tab = TextTab.Box; colorTarget = ColorTarget.Box })
        }

        when (tab) {
            TextTab.Text -> {
                if (caption) PanelLabel("Perubahan teks & gaya langsung terlihat di preview.")
                BasicTextField(
                    value = content,
                    onValueChange = {
                        content = it
                        pushLive()
                    },
                    textStyle = ConcatType.title.copy(color = ConcatColors.fg),
                    cursorBrush = SolidColor(ConcatColors.accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    decorationBox = { field ->
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.md)).background(ConcatColors.launchField)
                                .padding(horizontal = Spacing.md, vertical = 10.dp),
                        ) {
                            if (content.isEmpty()) Text("Ketik teks...", style = ConcatType.title, color = ConcatColors.fgDim)
                            field()
                        }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    ChoiceChip("Regular", selected = weight < 600 && !italic, onClick = { weight = 400.0; italic = false; pushLive() })
                    ChoiceChip("Bold", selected = weight >= 600 && weight < 800, onClick = { weight = 700.0; pushLive() })
                    ChoiceChip("Black", selected = weight >= 800, onClick = { weight = 900.0; pushLive() })
                    ChoiceChip("Italic", selected = italic, onClick = { italic = !italic; pushLive() })
                }
                ValueSlider(
                    "Size",
                    size,
                    0.02f..0.25f,
                    { "${(it * 100).toInt()}" },
                    onChange = { size = it; pushLive() },
                )
            }

            TextTab.Font -> {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PanelLabel("Pilih font bawaan atau impor .ttf / .otf dari HP")
                    GhostButton("+ Import Font", onImportFont, icon = ConcatIcons.Import, height = 30.dp)
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    for (builtIn in BUILTIN_FONTS) {
                        ChoiceChip(
                            builtIn,
                            selected = fontFamily.equals(builtIn, ignoreCase = true),
                            onClick = {
                                fontFamily = builtIn
                                pushLive()
                            },
                        )
                    }
                    for (custom in customFonts) {
                        ChoiceChip(
                            custom.family,
                            selected = fontFamily.equals(custom.family, ignoreCase = true),
                            onClick = {
                                fontFamily = custom.family
                                pushLive()
                            },
                        )
                    }
                }
                if (customFonts.isEmpty()) {
                    PanelLabel("Belum ada custom font. Tap + Import Font untuk memilih file font dari penyimpanan.")
                }
            }

            TextTab.Fill -> {
                FullColorPalette(
                    selectedHex = colour,
                    savedColors = savedColors,
                    eyedropperActive = eyedropperActive && colorTarget == ColorTarget.Fill,
                    onToggleEyedropper = {
                        colorTarget = ColorTarget.Fill
                        onToggleEyedropper(!eyedropperActive)
                    },
                    onSaveColor = { onSaveColor(colour) },
                    onRemoveColor = onRemoveColor,
                    onPick = { hex ->
                        colour = hex
                        pushLive()
                    },
                )
            }

            TextTab.Outline -> {
                ValueSlider(
                    "Outline thickness",
                    strokeWidth,
                    0f..0.025f,
                    { if (it <= 0.0002f) "Off" else "%.1f".format(it * 1000f) },
                    onChange = { strokeWidth = it; pushLive() },
                )
                FullColorPalette(
                    selectedHex = strokeColour,
                    savedColors = savedColors,
                    eyedropperActive = eyedropperActive && colorTarget == ColorTarget.Outline,
                    onToggleEyedropper = {
                        colorTarget = ColorTarget.Outline
                        onToggleEyedropper(!eyedropperActive)
                    },
                    onSaveColor = { onSaveColor(strokeColour) },
                    onRemoveColor = onRemoveColor,
                    onPick = { hex ->
                        strokeColour = hex
                        if (strokeWidth <= 0f) strokeWidth = 0.005f
                        pushLive()
                    },
                )
            }

            TextTab.Box -> {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    ChoiceChip(
                        if (shadow) "Shadow: On" else "Shadow: Off",
                        selected = shadow,
                        onClick = { shadow = !shadow; pushLive() },
                    )
                    ChoiceChip(
                        "No Box",
                        selected = plateColour == null,
                        onClick = { plateColour = null; pushLive() },
                    )
                }
                FullColorPalette(
                    selectedHex = plateColour ?: "#000000",
                    savedColors = savedColors,
                    eyedropperActive = eyedropperActive && colorTarget == ColorTarget.Box,
                    onToggleEyedropper = {
                        colorTarget = ColorTarget.Box
                        onToggleEyedropper(!eyedropperActive)
                    },
                    onSaveColor = { onSaveColor(plateColour ?: "#000000") },
                    onRemoveColor = onRemoveColor,
                    onPick = { hex ->
                        plateColour = hex
                        pushLive()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FullColorPalette(
    selectedHex: String,
    savedColors: List<String>,
    eyedropperActive: Boolean,
    onToggleEyedropper: () -> Unit,
    onSaveColor: () -> Unit,
    onRemoveColor: (String) -> Unit,
    onPick: (String) -> Unit,
) {
    val initialHsv = remember(selectedHex) { hsvOfHex(selectedHex) }
    var hue by remember(selectedHex) { mutableFloatStateOf(initialHsv[0]) }
    var sat by remember(selectedHex) { mutableFloatStateOf(initialHsv[1]) }
    var value by remember(selectedHex) { mutableFloatStateOf(initialHsv[2]) }
    var showSpectrum by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (eyedropperActive) {
            PanelLabel("Geser jari di atas video preview untuk mengambil warna.")
        }

        // Baris 1: Eyedropper + Preview Warna Aktif + Hex + Tombol Palette Spektrum + Save Warna
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(32.dp).clip(CircleShape)
                    .background(if (eyedropperActive) ConcatColors.accent else ConcatColors.raised)
                    .border(1.dp, if (eyedropperActive) ConcatColors.accent else ConcatColors.lineStrong, CircleShape)
                    .clickable { onToggleEyedropper() },
                contentAlignment = Alignment.Center,
            ) {
                ConcatIcon(
                    ConcatIcons.Wand,
                    tint = if (eyedropperActive) ConcatColors.onAccent else ConcatColors.fg,
                    size = 16.dp,
                )
            }

            Box(
                Modifier.size(28.dp).clip(CircleShape)
                    .background(colourOf(selectedHex))
                    .border(1.5.dp, Color.White, CircleShape),
            )

            Text(
                selectedHex.uppercase(Locale.US),
                style = ConcatType.badge,
                color = ConcatColors.fg,
            )

            Spacer(Modifier.weight(1f))

            ChoiceChip(
                if (showSpectrum) "Tutup Palette" else "Palette",
                selected = showSpectrum,
                onClick = { showSpectrum = !showSpectrum },
            )

            ChoiceChip(
                "+ Save",
                selected = false,
                onClick = onSaveColor,
            )
        }

        // Spectrum Color Picker (2D Saturation/Value + Hue Slider)
        if (showSpectrum) {
            val pureHueColor = Color.hsv(hue, 1f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .clip(RoundedCornerShape(Radii.md))
                    .border(1.dp, ConcatColors.lineStrong, RoundedCornerShape(Radii.md))
                    .pointerInput(hue) {
                        detectTapGestures { offset ->
                            val s = (offset.x / size.width).coerceIn(0f, 1f)
                            val v = (1f - offset.y / size.height).coerceIn(0f, 1f)
                            sat = s
                            value = v
                            onPick(hexFromColor(Color.hsv(hue, s, v)))
                        }
                    }
                    .pointerInput(hue) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            val s = (change.position.x / size.width).coerceIn(0f, 1f)
                            val v = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                            sat = s
                            value = v
                            onPick(hexFromColor(Color.hsv(hue, s, v)))
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Brush.horizontalGradient(listOf(Color.White, pureHueColor)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val cx = sat * size.width
                    val cy = (1f - value) * size.height
                    drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(cx, cy), style = Stroke(width = 2.dp.toPx()))
                    drawCircle(Color.Black, radius = 5.dp.toPx(), center = Offset(cx, cy), style = Stroke(width = 1.dp.toPx()))
                }
            }

            // Hue Rainbow Slider
            val rainbow = remember {
                listOf(
                    Color.hsv(0f, 1f, 1f),
                    Color.hsv(60f, 1f, 1f),
                    Color.hsv(120f, 1f, 1f),
                    Color.hsv(180f, 1f, 1f),
                    Color.hsv(240f, 1f, 1f),
                    Color.hsv(300f, 1f, 1f),
                    Color.hsv(360f, 1f, 1f),
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .clip(CircleShape)
                    .border(1.dp, ConcatColors.lineStrong, CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val h = ((offset.x / size.width).coerceIn(0f, 1f)) * 360f
                            hue = h
                            onPick(hexFromColor(Color.hsv(h, sat.coerceAtLeast(0.15f), value.coerceAtLeast(0.15f))))
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            val h = ((change.position.x / size.width).coerceIn(0f, 1f)) * 360f
                            hue = h
                            onPick(hexFromColor(Color.hsv(h, sat.coerceAtLeast(0.15f), value.coerceAtLeast(0.15f))))
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Brush.horizontalGradient(rainbow))
                    val hx = (hue / 360f) * size.width
                    drawCircle(Color.White, radius = 9.dp.toPx(), center = Offset(hx, size.height / 2f), style = Stroke(width = 2.5.dp.toPx()))
                }
            }
        }

        // Baris 2: Saved Colors (tahan lama untuk hapus)
        if (savedColors.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Saved:", style = ConcatType.caption, color = ConcatColors.fgMuted)
                for (hex in savedColors) {
                    val picked = hex.equals(selectedHex, ignoreCase = true)
                    Box(
                        Modifier.size(28.dp).clip(CircleShape)
                            .border(if (picked) 2.dp else 1.dp, if (picked) ConcatColors.accent else ConcatColors.lineStrong, CircleShape)
                            .padding(if (picked) 3.dp else 0.dp)
                            .clip(CircleShape)
                            .background(colourOf(hex))
                            .combinedClickable(
                                onClick = { onPick(hex) },
                                onLongClick = { onRemoveColor(hex) },
                            ),
                    )
                }
            }
        }

        // Baris 3: Swatch Preset Bawaan
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (hex in TEXT_COLOURS) {
                val picked = hex.equals(selectedHex, ignoreCase = true)
                Box(
                    Modifier.size(28.dp).clip(CircleShape)
                        .border(if (picked) 2.dp else 1.dp, if (picked) ConcatColors.accent else ConcatColors.lineStrong, CircleShape)
                        .padding(if (picked) 3.dp else 0.dp)
                        .clip(CircleShape)
                        .background(colourOf(hex))
                        .clickable { onPick(hex) },
                )
            }
        }
    }
}

@Composable
fun PackagePanel(
    shelf: Shelf,
    catalogue: CatalogueViewModel,
    editor: EditorViewModel,
    onMore: () -> Unit,
    onClose: () -> Unit,
) {
    val state by catalogue.ui.collectAsStateWithLifecycle()
    val editorUi by editor.ui.collectAsStateWithLifecycle()
    val clip = editorUi.view?.clip(editorUi.selected)
    val kinds = shelf.kinds(clip)
    LaunchedEffect(Unit) { catalogue.load() }
    val offered = state.packages.filter { it.kind in kinds }
    val links = clip?.let { c ->
        buildList {
            if ("audio" in kinds) c.filters.forEachIndexed { i, link -> add(Triple("filters", i, link)) }
            c.videoEffects.forEachIndexed { i, link ->
                val kind = catalogue.find(link.id)?.kind
                if (c.kind == ClipKind.Transition || kind in kinds || (kind == null && "effect" in kinds)) add(Triple("videoEffects", i, link))
            }
        }
    }.orEmpty()
    val applied = links.map { it.third.id }.toSet()

    ToolPanel(
        shelf.title,
        onClose = onClose,
        onConfirm = onClose,
        actions = { GhostButton("All", onMore, icon = ConcatIcons.Search, height = 32.dp, modifier = Modifier.padding(end = Spacing.sm)) },
        padding = androidx.compose.foundation.layout.PaddingValues(vertical = Spacing.xs),
    ) {
        when {
            !state.loaded -> PanelLabel("Loading...", Modifier.padding(horizontal = Spacing.lg))
            state.error != null -> PanelLabel(state.error ?: "", Modifier.padding(horizontal = Spacing.lg))
            clip == null && shelf != Shelf.Transitions -> PanelLabel("Select a clip to apply one.", Modifier.padding(horizontal = Spacing.lg))
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.lg),
        ) {
            if (shelf != Shelf.Transitions) {
                item(key = "none") {
                    PackageTileSmall(
                        name = "None",
                        tint = null,
                        selected = links.isEmpty(),
                        enabled = clip != null && links.isNotEmpty(),
                    ) {
                        clip?.let { c -> editor.removeLinks(c.id, links.map { it.first to it.second }) }
                    }
                }
            }
            items(offered, key = { it.id }) { pkg ->
                PackageTileSmall(
                    name = pkg.name,
                    tint = tileColour(pkg),
                    selected = pkg.id in applied,
                    enabled = clip != null || shelf == Shelf.Transitions,
                ) { editor.applyPackage(pkg.kind, pkg.id, pkg.name) }
            }
        }
        val newest = links.maxByOrNull { if (it.first == "videoEffects") 1000 + it.second else it.second }
        val pkg = newest?.let { catalogue.find(it.third.id) }
        val knob = pkg?.params?.firstOrNull { it.key == pkg.intensity } ?: pkg?.params?.firstOrNull { it.type == "float" || it.type == "int" }
        if (clip != null && newest != null && pkg != null && knob != null) {
            LinkStrength(clip, newest.first, newest.second, newest.third, pkg, knob, editor)
        }
    }
}

@Composable
private fun LinkStrength(
    clip: Clip,
    field: String,
    index: Int,
    link: AppliedEffect,
    pkg: PackageInfo,
    knob: app.concat.editor.api.ParamInfo,
    editor: EditorViewModel,
) {
    var value by remember(clip.id, field, index, link.id) { mutableFloatStateOf((link.params[knob.key] ?: knob.default).toFloat()) }
    val low = knob.min.toFloat()
    val high = knob.max.toFloat().coerceAtLeast(low + 1e-3f)
    Column(Modifier.padding(horizontal = Spacing.lg)) {
        ValueSlider(
            if (knob.key == pkg.intensity) pkg.name else "${pkg.name} ${knob.label.lowercase()}",
            value,
            low..high,
            { if (knob.type == "int" || knob.step >= 1) "%.0f%s".format(it, knob.unit) else "%.2f%s".format(it, knob.unit) },
            onChange = {
                value = it
                editor.setLink(clip.id, field, index, link.withParam(knob.key, it.toDouble()))
            },
        )
    }
}

private fun tileColour(pkg: PackageInfo): Brush {
    val hue = (pkg.id.hashCode() and 0xff) * 360f / 256f
    return Brush.linearGradient(listOf(Color.hsv(hue, 0.5f, 0.7f), Color.hsv((hue + 40f) % 360f, 0.65f, 0.3f)))
}

@Composable
private fun PackageTileSmall(name: String, tint: Brush?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val shape = RoundedCornerShape(Radii.md)
        Box(
            Modifier.size(64.dp).clip(shape)
                .then(if (tint != null) Modifier.background(tint) else Modifier.background(ConcatColors.raised))
                .border(if (selected) 2.dp else 0.dp, if (selected) ConcatColors.accent else Color.Transparent, shape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (tint == null) {
                ConcatIcon(ConcatIcons.EyeOff, tint = ConcatColors.fgMuted, size = 22.dp)
            } else if (selected) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(ConcatColors.accent), contentAlignment = Alignment.Center) {
                    ConcatIcon(ConcatIcons.Check, tint = ConcatColors.onAccent, size = 14.dp)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            name,
            style = ConcatType.caption,
            color = if (selected) ConcatColors.accent else if (enabled) ConcatColors.fg else ConcatColors.fgDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

enum class AspectChoice(val label: String, val width: Int, val height: Int) {
    Portrait("9:16", 1080, 1920),
    Landscape("16:9", 1920, 1080),
    Square("1:1", 1080, 1080),
    Feed("4:5", 1080, 1350),
    Classic("3:4", 1080, 1440),
    Cinema("21:9", 2520, 1080),
}

@Composable
fun AspectPanel(view: ProjectView, onPick: (AspectChoice) -> Unit, onClose: () -> Unit) {
    ToolPanel("Aspect ratio", onClose = onClose, onConfirm = onClose) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            for (choice in AspectChoice.entries) {
                val picked = view.width * choice.height == view.height * choice.width
                Column(
                    Modifier.clip(RoundedCornerShape(Radii.md)).clickable { onPick(choice) }.padding(Spacing.xs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val tall = 44.dp
                    val ratio = choice.width.toFloat() / choice.height
                    val w = if (ratio >= 1) tall else tall * ratio
                    val h = if (ratio >= 1) tall / ratio else tall
                    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.size(w, h).clip(RoundedCornerShape(4.dp))
                                .border(2.dp, if (picked) ConcatColors.accent else ConcatColors.fgMuted, RoundedCornerShape(4.dp)),
                        )
                    }
                    Text(choice.label, style = ConcatType.caption, color = if (picked) ConcatColors.accent else ConcatColors.fg)
                }
            }
        }
    }
}

private val BACKGROUNDS = listOf("#000000", "#ffffff", "#1e1e22", "#cbf53f", "#ff375f", "#0a84ff", "#ffd60a", "#bf5af2")

@Composable
fun BackgroundPanel(onPick: (JSONObject) -> Unit, onClose: () -> Unit) {
    ToolPanel("Background", onClose = onClose, onConfirm = onClose) {
        PanelLabel("Behind the main track, where the picture does not fill the frame.")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(Radii.sm)).background(ConcatColors.raised)
                    .clickable { onPick(JSONObject().put("kind", "blur").put("amount", 0.6)) },
                contentAlignment = Alignment.Center,
            ) { Text("Blur", style = ConcatType.caption, color = ConcatColors.fg) }
            for (hex in BACKGROUNDS) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(Radii.sm)).background(colourOf(hex))
                        .border(1.dp, ConcatColors.lineStrong, RoundedCornerShape(Radii.sm))
                        .clickable { onPick(JSONObject().put("kind", "color").put("color", hex)) },
                )
            }
        }
    }
}
