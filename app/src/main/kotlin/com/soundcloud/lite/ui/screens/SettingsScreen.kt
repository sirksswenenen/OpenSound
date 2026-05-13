package com.soundcloud.lite.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soundcloud.lite.data.AnimSpeed
import com.soundcloud.lite.data.GlassQuality
import com.soundcloud.lite.data.IconVariant
import com.soundcloud.lite.data.ThemePreset
import com.soundcloud.lite.ui.MainViewModel
import com.soundcloud.lite.ui.components.GlassSurface

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            "Settings",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Section("Theme preset") {
            ThemePreset.values().forEach { preset ->
                val swatchColor = if (preset == ThemePreset.Custom) {
                    Color(settings.customAccent)
                } else {
                    Color(preset.primary)
                }
                SelectableRow(
                    title = preset.displayName,
                    selected = settings.themePreset == preset,
                    trailing = {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(swatchColor)
                                .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                        )
                    },
                    onClick = {
                        viewModel.updateSettings { it.copy(themePreset = preset) }
                    }
                )
            }
        }

        if (settings.themePreset == ThemePreset.Custom) {
            Section("Custom colours") {
                Text(
                    "Mix RGB sliders. Accent colour applies to icons, buttons and " +
                        "active highlights; gradient top/bottom paint the background.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                ColorPickerRow(
                    label = "Accent (icons / buttons)",
                    color = settings.customAccent,
                    onColorChange = { c -> viewModel.updateSettings { it.copy(customAccent = c) } }
                )
                ColorPickerRow(
                    label = "Gradient top",
                    color = settings.customGradientTop,
                    onColorChange = { c -> viewModel.updateSettings { it.copy(customGradientTop = c) } }
                )
                ColorPickerRow(
                    label = "Gradient bottom",
                    color = settings.customGradientBottom,
                    onColorChange = { c -> viewModel.updateSettings { it.copy(customGradientBottom = c) } }
                )
            }
        }

        Section("Background") {
            ToggleRow(
                title = "Gradient background",
                subtitle = "Fade from a tinted top to the base background",
                checked = settings.gradientBackground,
                onCheckedChange = { v -> viewModel.updateSettings { it.copy(gradientBackground = v) } }
            )
        }

        Section("Glass (iOS-style frosted)") {
            ToggleRow(
                title = "Enable glassmorphism",
                subtitle = "Translucent surfaces with gradient highlight",
                checked = settings.glassEnabled,
                onCheckedChange = { v -> viewModel.updateSettings { it.copy(glassEnabled = v) } }
            )
            ToggleRow(
                title = "Dark glass variant",
                subtitle = "Low-luminance tint for dark themes",
                checked = settings.glassDark,
                onCheckedChange = { v -> viewModel.updateSettings { it.copy(glassDark = v) } }
            )
            ToggleRow(
                title = "Liquid glass",
                subtitle = "Real backdrop blur + chromatic aberration + tilt reflections on the mini-player.",
                checked = settings.liquidGlass,
                onCheckedChange = { v -> viewModel.updateSettings { it.copy(liquidGlass = v) } }
            )
            if (settings.liquidGlass) {
                GlassSliderRow(
                    label = "Blur",
                    value = settings.glassBlur,
                    onChange = { v -> viewModel.updateSettings { it.copy(glassBlur = v) } }
                )
                GlassSliderRow(
                    label = "Tint",
                    value = settings.glassTint,
                    onChange = { v -> viewModel.updateSettings { it.copy(glassTint = v) } }
                )
                GlassSliderRow(
                    label = "Chromatic aberration",
                    value = settings.glassChroma,
                    onChange = { v -> viewModel.updateSettings { it.copy(glassChroma = v) } }
                )
                GlassSliderRow(
                    label = "Side reflections",
                    value = settings.glassReflection,
                    onChange = { v -> viewModel.updateSettings { it.copy(glassReflection = v) } }
                )
                GlassSliderRow(
                    label = "Corner radius",
                    value = settings.glassRadius / 40f,
                    displayValue = "${settings.glassRadius.toInt()} dp",
                    onChange = { v -> viewModel.updateSettings { it.copy(glassRadius = (v * 40f)) } }
                )
                GlassSliderRow(
                    label = "Refraction",
                    value = settings.glassRefraction,
                    onChange = { v -> viewModel.updateSettings { it.copy(glassRefraction = v) } }
                )
                GlassQuality.values().forEach { q ->
                    SelectableRow(
                        title = "Glass quality: ${q.displayName}",
                        subtitle = when (q) {
                            GlassQuality.Low ->
                                "Smaller capture, less blur fidelity. Use on weaker GPUs " +
                                    "or when other effects feel laggy."
                            GlassQuality.Balanced ->
                                "Default. Good tradeoff for most devices."
                            GlassQuality.High ->
                                "Larger capture, sharper blur. Best on flagship GPUs " +
                                    "(may lower fps on older phones)."
                        },
                        selected = settings.glassQuality == q,
                        onClick = { viewModel.updateSettings { it.copy(glassQuality = q) } }
                    )
                }
                ToggleRow(
                    title = "GPU effects (experimental)",
                    subtitle = "Run blur and refraction on the GPU instead of " +
                        "CPU StackBlur. Tracks the live UI at 60 fps but " +
                        "chromatic aberration is ignored. Disable if the " +
                        "panel looks transparent or flickers.",
                    checked = settings.glassUseGpuEffects,
                    onCheckedChange = { v ->
                        viewModel.updateSettings { it.copy(glassUseGpuEffects = v) }
                    },
                )
                ColorPickerRow(
                    label = "Glass tint colour",
                    color = settings.glassTintColor,
                    onColorChange = { c -> viewModel.updateSettings { it.copy(glassTintColor = c) } }
                )
            }
        }

        Section("Animation speed") {
            AnimSpeed.values().forEach { speed ->
                SelectableRow(
                    title = speed.displayName,
                    subtitle = when (speed) {
                        AnimSpeed.Instant -> "0 ms (no transitions)"
                        AnimSpeed.Fast -> "120 ms"
                        AnimSpeed.Normal -> "250 ms"
                    },
                    selected = settings.animationSpeed == speed,
                    onClick = { viewModel.updateSettings { it.copy(animationSpeed = speed) } }
                )
            }
        }

        Section("SoundCloud account (optional)") {
            OutlinedTextField(
                value = settings.soundCloudClientId,
                onValueChange = { v -> viewModel.updateSettings { it.copy(soundCloudClientId = v.trim()) } },
                label = { Text("client_id") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "SoundCloud client_id — required for SC trending and direct streaming. " +
                    "Get it from DevTools → Network → any api-v2.soundcloud.com request → " +
                    "?client_id=XXXX in the URL. If left blank the app tries a list of known IDs automatically.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            OutlinedTextField(
                value = settings.soundCloudOAuthToken,
                onValueChange = { v -> viewModel.updateSettings { it.copy(soundCloudOAuthToken = v.trim()) } },
                label = { Text("OAuth token (optional, for Go+)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "OAuth token unlocks full-length streams for Go+ subscribers. " +
                    "Get it from DevTools → Network → Authorization: OAuth <token>.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        Section("App icon") {
            IconVariant.values().forEach { variant ->
                SelectableRow(
                    title = variant.displayName,
                    selected = settings.iconVariant == variant,
                    onClick = { viewModel.updateSettings { it.copy(iconVariant = variant) } }
                )
            }
            Text(
                "Icon switch is applied by the launcher on next redraw. You may need to close and reopen the launcher.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        Section("Diagnostics") {
            DiagnosticsRow()
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DiagnosticsRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var dialog by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    androidx.compose.material3.Button(
        onClick = {
            val log = try {
                val pid = android.os.Process.myPid().toString()
                val proc = Runtime.getRuntime().exec(
                    arrayOf("logcat", "-d", "-t", "400", "--pid=$pid")
                )
                proc.inputStream.bufferedReader().use { it.readText() }
            } catch (t: Throwable) {
                "logcat unavailable: ${t.message}"
            }
            dialog = log.ifBlank { "logcat вернул пустоту" }
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Показать debug-лог (logcat)") }

    Text(
        "Если что-то ведёт себя странно — нажми кнопку, скопируй текст из диалога и пришли его. Лог содержит только этот процесс.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp)
    )

    val payload = dialog
    if (payload != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Debug log") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        payload,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText("SC Lite log", payload)
                    )
                    dialog = null
                }) { Text("Скопировать и закрыть") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { dialog = null }) {
                    Text("Закрыть")
                }
            }
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title.uppercase(),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            content()
        }
    }
}

@Composable
private fun SelectableRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.size(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.size(12.dp))
            trailing()
        }
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
}

/**
 * RGB color picker row. Three sliders (red, green, blue) drive an opaque
 * ARGB Long that gets persisted via [onColorChange]. Used for the
 * [com.soundcloud.lite.data.ThemePreset.Custom] palette.
 */
@Composable
private fun ColorPickerRow(
    label: String,
    color: Long,
    onColorChange: (Long) -> Unit
) {
    val argb = color.toInt()
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(color))
                    .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
            )
            Spacer(Modifier.width(12.dp))
            Text(label, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(
                "#%02X%02X%02X".format(r, g, b),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = r.toFloat(),
            onValueChange = { v -> onColorChange(packArgb(v.toInt(), g, b)) },
            valueRange = 0f..255f,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFE05050),
                activeTrackColor = Color(0xFFE05050),
                inactiveTrackColor = Color(0xFFE05050).copy(alpha = 0.25f)
            )
        )
        Slider(
            value = g.toFloat(),
            onValueChange = { v -> onColorChange(packArgb(r, v.toInt(), b)) },
            valueRange = 0f..255f,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF50C070),
                activeTrackColor = Color(0xFF50C070),
                inactiveTrackColor = Color(0xFF50C070).copy(alpha = 0.25f)
            )
        )
        Slider(
            value = b.toFloat(),
            onValueChange = { v -> onColorChange(packArgb(r, g, v.toInt())) },
            valueRange = 0f..255f,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF5080E0),
                activeTrackColor = Color(0xFF5080E0),
                inactiveTrackColor = Color(0xFF5080E0).copy(alpha = 0.25f)
            )
        )
    }
}

@Composable
private fun GlassSliderRow(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    displayValue: String = "${(value * 100).toInt()}%",
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(displayValue, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onChange,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
            )
        )
    }
}

private fun packArgb(r: Int, g: Int, b: Int): Long {
    val rr = r.coerceIn(0, 255).toLong()
    val gg = g.coerceIn(0, 255).toLong()
    val bb = b.coerceIn(0, 255).toLong()
    return 0xFF000000L or (rr shl 16) or (gg shl 8) or bb
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
}
