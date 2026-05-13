package com.soundcloud.lite.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemePreset(
    val displayName: String,
    val bg: Long,
    val surface: Long,
    val surfaceVariant: Long,
    val primary: Long,
    val gradientTop: Long,
    val gradientBottom: Long
) {
    OrangeNight(
        displayName = "Orange Night",
        bg = 0xFF0A0A0F,
        surface = 0xFF17171F,
        surfaceVariant = 0xFF23232F,
        primary = 0xFFFF5500,
        gradientTop = 0xFF1A0A05,
        gradientBottom = 0xFF0A0A0F
    ),
    Midnight(
        displayName = "Midnight Blue",
        bg = 0xFF050714,
        surface = 0xFF0B1024,
        surfaceVariant = 0xFF141B3B,
        primary = 0xFF5B8CFF,
        gradientTop = 0xFF0A1033,
        gradientBottom = 0xFF050714
    ),
    PurpleHaze(
        displayName = "Purple Haze",
        bg = 0xFF08050F,
        surface = 0xFF130B22,
        surfaceVariant = 0xFF21143A,
        primary = 0xFFB15CFF,
        gradientTop = 0xFF2A0F44,
        gradientBottom = 0xFF08050F
    ),
    CyberCyan(
        displayName = "Cyber Cyan",
        bg = 0xFF050E10,
        surface = 0xFF0A1C1F,
        surfaceVariant = 0xFF123036,
        primary = 0xFF39E0D5,
        gradientTop = 0xFF0C2A30,
        gradientBottom = 0xFF050E10
    ),
    PureBlack(
        displayName = "Pure Black (AMOLED)",
        bg = 0xFF000000,
        surface = 0xFF0A0A0A,
        surfaceVariant = 0xFF161616,
        primary = 0xFFFF5500,
        gradientTop = 0xFF0A0A0A,
        gradientBottom = 0xFF000000
    ),
    /**
     * Placeholder values — when this preset is selected, [AppSettings] custom
     * colour fields override these at theme-resolution time. The values below
     * are only used as a starting point when the user first opens the custom
     * pickers.
     */
    Custom(
        displayName = "Custom",
        bg = 0xFF0A0A0F,
        surface = 0xFF17171F,
        surfaceVariant = 0xFF23232F,
        primary = 0xFFFF5500,
        gradientTop = 0xFF1A0A05,
        gradientBottom = 0xFF0A0A0F
    );
}

enum class AnimSpeed(val displayName: String, val millis: Int) {
    Instant("Instant", 0),
    Fast("Fast", 120),
    Normal("Normal", 250);
}

/**
 * Legacy. We used to support a Cobalt API fallback for downloads; v0.5.0
 * uses SoundCloud's `streams` endpoint exclusively. The enum is kept (with
 * a single member) so persisted settings still deserialize.
 */
enum class DownloadSource(val displayName: String) {
    Auto("SoundCloud stream");
}

enum class IconVariant(val displayName: String, val aliasSuffix: String?) {
    Orange("Orange (default)", null),
    Purple("Purple", "Purple"),
    Cyan("Cyan", "Cyan");
}

/**
 * Quality preset for Liquid Glass real-time blur. Controls the
 * downsample factor used when capturing the screen for blur,
 * the StackBlur radius cap, and how often the blur worker
 * publishes a new frame. Lower quality = faster, less detailed.
 */
enum class GlassQuality(
    val displayName: String,
    val captureScale: Float,
    val maxRadius: Int,
    val frameSkip: Int,
) {
    /** Smallest backdrop snapshot, slowest publish rate — leaves the
     * UI thread the most headroom on weaker GPUs. */
    Low("Performance", 0.13f, 24, 3),
    /** Default. */
    Balanced("Balanced", 0.22f, 50, 1),
    /** Largest backdrop snapshot, sharpest blur. Costs more GPU and
     * may lower fps on slower devices. */
    High("Quality", 0.30f, 70, 1);
}

data class AppSettings(
    val themePreset: ThemePreset = ThemePreset.OrangeNight,
    val glassEnabled: Boolean = true,
    val glassDark: Boolean = true,
    /**
     * Adds an iOS 26-style chromatic-aberration "rainbow" lip to glass panel
     * edges via an AGSL [android.graphics.RuntimeShader]. Requires Android 13
     * (API 33) — on older versions the toggle is a no-op.
     */
    val liquidGlass: Boolean = false,
    /** Blur strength for Liquid Glass backdrop sampling (0..1). */
    val glassBlur: Float = 0.55f,
    /** Tint alpha scale for Liquid Glass (0..1, mapped internally to 0..0.5). */
    val glassTint: Float = 0.30f,
    /** Side reflection intensity in response to device tilt (0..1). */
    val glassReflection: Float = 0.55f,
    /** Chromatic aberration strength (0..1). */
    val glassChroma: Float = 0.25f,
    /** Corner radius for Liquid Glass panels, in dp (0..40). */
    val glassRadius: Float = 20f,
    /**
     * Lens-style refraction / magnification of the backdrop visible through
     * a glass panel (0..1). At 0 the panel shows the live (blurred) image
     * as-is; higher values progressively magnify the centre region of the
     * captured bitmap so the panel reads like a slightly convex lens.
     */
    val glassRefraction: Float = 0.20f,
    /** Real-time blur quality preset. */
    val glassQuality: GlassQuality = GlassQuality.Balanced,
    /**
     * Experimental: when true, use the GPU's `Modifier.blur` /
     * `RenderEffect.createBlurEffect` instead of the CPU StackBlur
     * pipeline. Eliminates the per-frame HW→SW round-trip so the
     * blur tracks the live UI at 60 fps. Chromatic aberration is
     * not supported in GPU mode and is silently ignored.
     */
    val glassUseGpuEffects: Boolean = false,
    /** Tint color for Liquid Glass, packed ARGB (alpha channel ignored; alpha comes from glassTint). */
    val glassTintColor: Long = 0xFF1A0037L,
    val animationSpeed: AnimSpeed = AnimSpeed.Fast,
    val downloadSource: DownloadSource = DownloadSource.Auto,
    val soundCloudOAuthToken: String = "",
    val soundCloudClientId: String = "",
    val iconVariant: IconVariant = IconVariant.Orange,
    val gradientBackground: Boolean = true,
    /**
     * Custom-theme colour overrides applied only when [themePreset] equals
     * [ThemePreset.Custom]. ARGB packed in the low 32 bits of each Long.
     */
    val customAccent: Long = 0xFFFF5500,
    val customGradientTop: Long = 0xFF1A0A05,
    val customGradientBottom: Long = 0xFF0A0A0F
)

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences("sclite_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings

    fun current(): AppSettings = _settings.value

    private fun load(): AppSettings = AppSettings(
        themePreset = enumSafe<ThemePreset>(prefs.getString("theme", null)) ?: ThemePreset.OrangeNight,
        glassEnabled = prefs.getBoolean("glass_enabled", true),
        glassDark = prefs.getBoolean("glass_dark", true),
        liquidGlass = prefs.getBoolean("liquid_glass", false),
        glassBlur = prefs.getFloat("glass_blur", 0.55f),
        glassTint = prefs.getFloat("glass_tint", 0.30f),
        glassReflection = prefs.getFloat("glass_reflection", 0.55f),
        glassChroma = prefs.getFloat("glass_chroma", 0.25f),
        glassRadius = prefs.getFloat("glass_radius", 20f),
        glassRefraction = prefs.getFloat("glass_refraction", 0.20f),
        glassQuality = enumSafe<GlassQuality>(prefs.getString("glass_quality", null)) ?: GlassQuality.Balanced,
        glassUseGpuEffects = prefs.getBoolean("glass_gpu_effects", false),
        glassTintColor = prefs.getLong("glass_tint_color", 0xFF1A0037L),
        animationSpeed = enumSafe<AnimSpeed>(prefs.getString("anim_speed", null)) ?: AnimSpeed.Fast,
        downloadSource = enumSafe<DownloadSource>(prefs.getString("dl_source", null)) ?: DownloadSource.Auto,
        soundCloudOAuthToken = prefs.getString("sc_oauth_token", "") ?: "",
        soundCloudClientId = prefs.getString("sc_client_id", "") ?: "",
        iconVariant = enumSafe<IconVariant>(prefs.getString("icon_variant", null)) ?: IconVariant.Orange,
        gradientBackground = prefs.getBoolean("gradient_bg", true),
        customAccent = prefs.getLong("custom_accent", 0xFFFF5500),
        customGradientTop = prefs.getLong("custom_grad_top", 0xFF1A0A05),
        customGradientBottom = prefs.getLong("custom_grad_bottom", 0xFF0A0A0F)
    )

    fun update(block: (AppSettings) -> AppSettings) {
        val next = block(_settings.value)
        prefs.edit()
            .putString("theme", next.themePreset.name)
            .putBoolean("glass_enabled", next.glassEnabled)
            .putBoolean("glass_dark", next.glassDark)
            .putBoolean("liquid_glass", next.liquidGlass)
            .putFloat("glass_blur", next.glassBlur)
            .putFloat("glass_tint", next.glassTint)
            .putFloat("glass_reflection", next.glassReflection)
            .putFloat("glass_chroma", next.glassChroma)
            .putFloat("glass_radius", next.glassRadius)
            .putFloat("glass_refraction", next.glassRefraction)
            .putString("glass_quality", next.glassQuality.name)
            .putBoolean("glass_gpu_effects", next.glassUseGpuEffects)
            .putLong("glass_tint_color", next.glassTintColor)
            .putString("anim_speed", next.animationSpeed.name)
            .putString("dl_source", next.downloadSource.name)
            .putString("sc_oauth_token", next.soundCloudOAuthToken)
            .putString("sc_client_id", next.soundCloudClientId)
            .putString("icon_variant", next.iconVariant.name)
            .putBoolean("gradient_bg", next.gradientBackground)
            .putLong("custom_accent", next.customAccent)
            .putLong("custom_grad_top", next.customGradientTop)
            .putLong("custom_grad_bottom", next.customGradientBottom)
            .apply()
        _settings.value = next
    }

    private inline fun <reified E : Enum<E>> enumSafe(name: String?): E? {
        if (name == null) return null
        return try {
            enumValueOf<E>(name)
        } catch (_: Exception) {
            null
        }
    }
}
