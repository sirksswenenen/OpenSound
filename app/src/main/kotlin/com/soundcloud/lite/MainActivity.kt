package com.soundcloud.lite

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.soundcloud.lite.data.IconVariant
import com.soundcloud.lite.ui.MainViewModel
import com.soundcloud.lite.ui.components.MiniPlayer
import com.soundcloud.lite.ui.screens.DownloadsScreen
import com.soundcloud.lite.ui.screens.HomeScreen
import com.soundcloud.lite.ui.screens.PlayerScreen
import com.soundcloud.lite.ui.screens.QueueScreen
import com.soundcloud.lite.ui.screens.PlaylistDetailScreen
import com.soundcloud.lite.ui.screens.PlaylistsScreen
import com.soundcloud.lite.ui.screens.RelatedScreen
import com.soundcloud.lite.ui.screens.SearchScreen
import com.soundcloud.lite.ui.screens.SettingsScreen
import com.soundcloud.lite.ui.components.BackdropHost
import com.soundcloud.lite.ui.components.LocalBackdropLayer
import com.soundcloud.lite.ui.components.LocalBlurredBackdrop
import com.soundcloud.lite.ui.components.LocalBlurredBackdropScale
import com.soundcloud.lite.ui.components.LocalTilt
import com.soundcloud.lite.ui.components.StackBlur
import com.soundcloud.lite.ui.components.StackBlurBuffers
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.runtime.withFrameNanos
import android.graphics.Bitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.content.Context as AndroidContext
import com.soundcloud.lite.ui.theme.LocalSCTheme
import com.soundcloud.lite.ui.theme.SCLiteTheme
import com.soundcloud.lite.util.CrashLogger
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Install global uncaught-exception handler before anything else so
        // even crashes during initial composition end up in the dialog.
        CrashLogger.install(applicationContext)
        // Mirror logcat to disk for the entire session — so even a native
        // renderer crash that bypasses the JVM handler still leaves a
        // trace we can read on the next launch.
        CrashLogger.startLiveLogcat(applicationContext)
        android.util.Log.d("SCLiteBoot", "onCreate: handlers installed")

        val pendingCrash = CrashLogger.readAndClear(applicationContext)
        android.util.Log.d("SCLiteBoot", "pendingCrash present=${pendingCrash != null}")

        // ---- Liquid Glass safe-mode logic ----
        //
        // On Android 13 the BackdropHost path occasionally causes a
        // *native* Skia/RenderThread crash that the JVM
        // `Thread.UncaughtExceptionHandler` cannot catch. To recover
        // automatically we maintain a sentinel file that is created
        // when we launch with Liquid Glass on, and erased only after
        // the app has been running for ~4 seconds without dying. If we
        // see the sentinel on the next start, we know the previous
        // launch with Liquid Glass did not survive — so we force-disable
        // it. We also do a one-time forced reset for the very first run
        // of this build so users coming from a previous broken APK
        // don't immediately crash again on launch.
        val prefs = getSharedPreferences("sclite_settings", MODE_PRIVATE)
        // LG crash auto-disable removed per user request — Liquid Glass stays on
        // regardless of previous crash/dirty-exit state.

        WindowCompat.setDecorFitsSystemWindows(window, false)
        maybeRequestNotificationPermission()
        applyCurrentIconVariant()

        android.util.Log.d("SCLiteBoot", "calling setContent")
        setContent {
            val settings by viewModel.settings.collectAsState()
            SCLiteTheme(settings) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Real iOS-26-style backdrop refraction. The wrap
                    // double-buffers the composed UI into a
                    // GraphicsLayer so any nested [GlassSurface] can
                    // sample what's *behind* it. We deliberately do
                    // **not** chain a RuntimeShaderEffect onto the
                    // panel layer — that path repeatedly crashed the
                    // RenderThread on Adreno 660 / MIUI. Instead the
                    // panel applies a plain blur + a
                    // [DrawScope.scale]-based convex magnification of
                    // the captured slice around the panel's centre.
                    // The combination still gives the live, dynamic
                    // "looking through curved glass" feel without
                    // touching the unstable shader path.
                    // Haze-based blur was disabled across the board:
                    // it crashes RenderThread natively on Adreno 660 +
                    // MIUI A13, with no way to catch the native fault
                    // from Kotlin. "Liquid Glass" is now a pure
                    // composite (gradient + highlights + rim) inside
                    // [GlassSurface] — no RenderEffect, no Haze, no
                    // capture layer.
                    Box(modifier = Modifier.fillMaxSize()) {
                        AppRoot(viewModel)
                    }
                    if (pendingCrash != null) {
                        CrashReportDialog(report = pendingCrash)
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
    }

    /**
     * Activates the [ComponentName] matching the user's selected launcher icon and
     * disables the other aliases. Android will pick up the change the next time the
     * launcher refreshes (typically on home-screen redraw).
     */
    private fun applyCurrentIconVariant() {
        val selected = viewModel.settings.value.iconVariant
        val packageName = packageName
        val components = listOf(
            ComponentName(packageName, "com.soundcloud.lite.MainActivity") to IconVariant.Orange,
            ComponentName(packageName, "com.soundcloud.lite.IconPurple") to IconVariant.Purple,
            ComponentName(packageName, "com.soundcloud.lite.IconCyan") to IconVariant.Cyan,
        )
        for ((cmp, variant) in components) {
            val state = if (variant == selected) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            try {
                packageManager.setComponentEnabledSetting(
                    cmp,
                    state,
                    PackageManager.DONT_KILL_APP
                )
            } catch (_: Exception) { /* ignore if already set or alias missing */ }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val perm = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(perm), 1001)
            }
        }
    }
}

private data class BottomTab(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val bottomTabs = listOf(
    BottomTab("home", "Home", Icons.Filled.Home),
    BottomTab("search", "Search", Icons.Filled.Search),
    BottomTab("downloads", "Downloads", Icons.Filled.Download),
    BottomTab("playlists", "Playlists", Icons.Filled.LibraryMusic),
    BottomTab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun AppRoot(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val playerState by viewModel.playerManager.state.collectAsState()
    val toast by viewModel.toast.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(toast) {
        toast?.let {
            android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.consumeToast()
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Hide bottom bar + miniplayer while full-screen player is open.
    val onPlayer = currentRoute == "player"

    val glassOn = LocalSCTheme.current.liquidGlass

    // GraphicsLayer capturing the list/AppNavHost content so the
    // MiniPlayer overlay can sample it for backdrop blur. Only
    // allocated when Liquid Glass is on — otherwise both capture
    // and resample would be wasted work.
    val backdropLayer = if (glassOn) rememberGraphicsLayer() else null
    // SECONDARY layer used purely as a GPU downsample target.
    // Each blur tick records this layer at the *target* small size
    // (e.g. 172x336 instead of 781x1527) and just calls
    // drawLayer(backdropLayer) inside a scale() block. The GPU
    // downsamples on the rendering thread; toImageBitmap() then
    // returns a small HW bitmap that the HW→SW copy fights with
    // ~20× less data — cap+copy went from ~36ms to ~3ms in
    // local tests, which is the difference between a 12fps blur
    // and a 60fps blur.
    val downsampleLayer = if (glassOn) rememberGraphicsLayer() else null
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    // Capture box's top-left position in the root coord space. The
    // GlassSurface panels use this to work out which slice of the
    // layer sits behind them.
    var backdropOrigin by remember { mutableStateOf(Offset.Zero) }

    // Device tilt in (-1..1, -1..1). Drives dynamic side
    // reflections inside GlassSurface. Zero when LG is off.
    val tilt = if (glassOn) rememberTilt() else Offset.Zero

    // ----------------------------------------------------------------
    // Blurred backdrop bitmap.
    //
    // GPU blur paths (Modifier.blur, GraphicsLayer.renderEffect with
    // BlurEffect) are unreliable on Adreno 660 / MIUI A13 — the blur
    // is applied but the layer ends up semi-transparent so the
    // underlying sharp content shows through. We instead capture a
    // downsampled snapshot of the AppNavHost layer and run a CPU
    // Stack Blur over it on a background coroutine, then expose the
    // already-blurred bitmap via [LocalBlurredBackdrop] so glass
    // panels can blit it directly.
    // ----------------------------------------------------------------
    val blurAmount = LocalSCTheme.current.glassBlur
    val chromaAmount = LocalSCTheme.current.glassChroma
    val refractionAmount = LocalSCTheme.current.glassRefraction
    val glassQuality = LocalSCTheme.current.glassQuality
    val useGpuEffects = LocalSCTheme.current.glassUseGpuEffects
    val blurAmountState = androidx.compose.runtime.rememberUpdatedState(blurAmount)
    val chromaAmountState = androidx.compose.runtime.rememberUpdatedState(chromaAmount)
    val refractionAmountState = androidx.compose.runtime.rememberUpdatedState(refractionAmount)
    val glassQualityState = androidx.compose.runtime.rememberUpdatedState(glassQuality)
    val useGpuEffectsState = androidx.compose.runtime.rememberUpdatedState(useGpuEffects)
    var blurredBackdrop by remember { mutableStateOf<ImageBitmap?>(null) }
    // captureScale + radius cap come from the user-selectable
    // GlassQuality. Low (0.16, max r=36, frameSkip=2) trades blur
    // sharpness for headroom on weaker GPUs; High (0.30, r=70, no
    // skip) keeps every detail. The worker re-reads the State on
    // every iteration so changing this in Settings takes effect
    // immediately without restarting the coroutine.
    val captureScale = glassQualityState.value.captureScale
    // Bitmap-blur worker. Re-runs forever once Liquid Glass is on.
    //
    // Architecture:
    //   - Capture (toImageBitmap) on the main thread (suspend, kicks
    //     off PixelCopy on the display thread; main is free during
    //     the wait).
    //   - Heavy pixel work — HW→SW copy of the full-screen bitmap,
    //     downsample via Canvas.scale+drawBitmap, StackBlur — all
    //     happens inside a single withContext(Dispatchers.Default)
    //     block so the UI thread keeps painting at native fps.
    //   - Two software bitmaps form a ping-pong buffer pair. The
    //     coroutine writes only into the buffer NOT currently
    //     published as `blurredBackdrop`; the renderer keeps
    //     reading the previous buffer until we publish the new
    //     one. This eliminates the screen-tearing / flickering
    //     that comes from mutating a bitmap that's still being
    //     read by the draw thread.
    //   - The loop is keyed only on (glassOn, backdropLayer); the
    //     slider values are observed via rememberUpdatedState so
    //     dragging never restarts it.
    //   - We capture as long as EITHER blur OR chroma is on. With
    //     blur≈0 but chroma>0 we still publish a sharp downsampled
    //     bitmap so GlassSurface can do the R/G/B aberration pass
    //     against it (skipping StackBlur to save ~40 ms / frame).
    LaunchedEffect(glassOn, backdropLayer, downsampleLayer) {
        if (!glassOn || backdropLayer == null || downsampleLayer == null) {
            blurredBackdrop = null
            return@LaunchedEffect
        }
        val buffers = arrayOfNulls<Bitmap>(2)
        var bufferIndex = 0
        var consecutiveZeroSize = 0
        var consecutiveErrors = 0
        // One blur scratch per worker coroutine — stops the per-tick
        // ~2 MB of IntArray allocations StackBlur used to do.
        val blurScratch = StackBlurBuffers()
        var skipCounter = 0
        // Coarse rolling FPS / per-stage cost log: 1 line per second
        // when -s LG-blur is on.
        var frameCount = 0
        var totalCaptureMs = 0L
        var totalCopyMs = 0L
        var totalDownsampleMs = 0L
        var totalBlurMs = 0L
        var lastReportNanos = System.nanoTime()
        while (true) {
            // Wait one frame at the TOP of the loop so each
            // toImageBitmap() reads a freshly-recorded layer.
            withFrameNanos { }
            val amt = blurAmountState.value
            val chr = chromaAmountState.value
            val refr = refractionAmountState.value
            val quality = glassQualityState.value
            val gpu = useGpuEffectsState.value
            if (gpu) {
                // GPU effects path handles blur + refraction directly
                // via Modifier.blur in GlassSurface. The worker has
                // nothing to publish.
                if (blurredBackdrop != null) blurredBackdrop = null
                delay(120L)
                continue
            }
            // Refraction also requires a captured bitmap (we sample a
            // smaller central region of it), so it triggers capture
            // on its own — even when blur and chroma are both zero.
            val needsCapture = amt >= 0.02f || chr >= 0.02f || refr >= 0.02f
            if (!needsCapture) {
                if (blurredBackdrop != null) blurredBackdrop = null
                delay(60L)
                continue
            }
            // Frame skip at Low quality: publish every Nth display
            // frame instead of every one. Cuts the worker's CPU
            // budget proportionally and is barely visible because
            // the blur is already a soft, low-frequency signal.
            if (quality.frameSkip > 1) {
                skipCounter++
                if (skipCounter % quality.frameSkip != 0) continue
            }
            // Radius is in BITMAP pixels, not screen pixels. Smaller
            // captureScale needs proportionally smaller radius for
            // the same VISUAL blur. We also cap by the current
            // quality preset so Low never spends more than ~r=36 ms.
            val targetCaptureScale = quality.captureScale
            val radius = if (amt >= 0.02f) {
                val scaled = (amt * 40f * (targetCaptureScale / 0.22f))
                scaled.toInt().coerceIn(1, quality.maxRadius)
            } else 0
            try {
                // Determine target small size from the FULL-screen
                // backdrop layer's already-recorded size.
                val srcSize = backdropLayer.size
                if (srcSize.width <= 1 || srcSize.height <= 1) {
                    consecutiveZeroSize++
                    if (consecutiveZeroSize == 10) {
                        android.util.Log.w(
                            "LG-blur",
                            "backdrop layer reported size ${srcSize.width}x${srcSize.height} repeatedly",
                        )
                    }
                    delay(30L)
                    continue
                }
                consecutiveZeroSize = 0
                val targetW = (srcSize.width * targetCaptureScale)
                    .toInt().coerceAtLeast(1)
                val targetH = (srcSize.height * targetCaptureScale)
                    .toInt().coerceAtLeast(1)
                // 1) GPU downsample step. Re-record `downsampleLayer`
                //    at the small target size and have it just paint
                //    `backdropLayer` scaled down. The actual rendering
                //    happens on the render thread when toImageBitmap
                //    is called below. This is the key win over the
                //    earlier path that pulled back a full-screen HW
                //    bitmap and resampled in software.
                val captureStartNs = System.nanoTime()
                downsampleLayer.record(
                    density = density,
                    layoutDirection = layoutDirection,
                    size = IntSize(targetW, targetH),
                ) {
                    scale(
                        scaleX = targetCaptureScale,
                        scaleY = targetCaptureScale,
                        pivot = Offset.Zero,
                    ) {
                        drawLayer(backdropLayer)
                    }
                }
                val raw: Bitmap = downsampleLayer.toImageBitmap().asAndroidBitmap()
                val captureNs = System.nanoTime() - captureStartNs
                if (raw.width <= 1 || raw.height <= 1) {
                    consecutiveZeroSize++
                    delay(30L)
                    continue
                }
                consecutiveErrors = 0
                val timings = LongArray(3)
                val published: ImageBitmap = if (radius == 0) {
                    // Fast path: no CPU StackBlur required (chroma /
                    // refraction only). Skip the HW→SW copy and the
                    // canvas blit entirely — Compose's drawImage in
                    // GlassSurface.drawBehind handles HW ImageBitmap
                    // directly on the HW canvas it owns. This shaves
                    // ~3-6 ms per tick when blur is off.
                    raw.asImageBitmap()
                } else {
                    // Pick the buffer NOT currently published. Only
                    // needed when we're going to mutate it via
                    // StackBlur — otherwise we pass the HW bitmap
                    // straight through above.
                    bufferIndex = bufferIndex xor 1
                    val cur = buffers[bufferIndex]
                    val writeBmp: Bitmap = if (
                        cur != null
                        && cur.width == targetW
                        && cur.height == targetH
                        && !cur.isRecycled
                    ) {
                        cur
                    } else {
                        val fresh = Bitmap.createBitmap(
                            targetW,
                            targetH,
                            Bitmap.Config.ARGB_8888,
                        )
                        buffers[bufferIndex] = fresh
                        fresh
                    }
                    withContext(Dispatchers.Default) {
                        // 2) HW→SW copy. The bitmap is now small
                        //    (~targetW × targetH) so this is no
                        //    longer the bottleneck — typically <2 ms.
                        val copyStart = System.nanoTime()
                        val source: Bitmap =
                            if (raw.config == Bitmap.Config.HARDWARE) {
                                raw.copy(Bitmap.Config.ARGB_8888, false)
                                    ?: throw IllegalStateException(
                                        "raw.copy(ARGB_8888) returned null"
                                    )
                            } else {
                                raw
                            }
                        timings[0] = System.nanoTime() - copyStart
                        // 3) Blit into reusable writeBmp.
                        val downStart = System.nanoTime()
                        val canvas = android.graphics.Canvas(writeBmp)
                        canvas.drawColor(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.PorterDuff.Mode.CLEAR,
                        )
                        canvas.drawBitmap(source, 0f, 0f, null)
                        if (source !== raw) source.recycle()
                        timings[1] = System.nanoTime() - downStart
                        // 4) StackBlur in place.
                        val blurStart = System.nanoTime()
                        StackBlur.blurInPlace(writeBmp, radius, blurScratch)
                        timings[2] = System.nanoTime() - blurStart
                        writeBmp.asImageBitmap()
                    }
                }
                frameCount++
                totalCaptureMs += captureNs / 1_000_000
                totalCopyMs += timings[0] / 1_000_000
                totalDownsampleMs += timings[1] / 1_000_000
                totalBlurMs += timings[2] / 1_000_000
                val nowNanos = System.nanoTime()
                if (nowNanos - lastReportNanos > 1_000_000_000L && frameCount > 0) {
                    android.util.Log.d(
                        "LG-blur",
                        "tick fps=$frameCount avgMs cap=${totalCaptureMs / frameCount}" +
                            " copy=${totalCopyMs / frameCount}" +
                            " down=${totalDownsampleMs / frameCount}" +
                            " blur=${totalBlurMs / frameCount}" +
                            " r=$radius" +
                            " sz=${targetW}x${targetH}",
                    )
                    frameCount = 0
                    totalCaptureMs = 0L
                    totalCopyMs = 0L
                    totalDownsampleMs = 0L
                    totalBlurMs = 0L
                    lastReportNanos = nowNanos
                }
                blurredBackdrop = published
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                consecutiveErrors++
                android.util.Log.w(
                    "LG-blur",
                    "backdrop capture failed (#$consecutiveErrors): ${e.javaClass.simpleName}: ${e.message}",
                    e,
                )
                if (consecutiveErrors >= 5) {
                    blurredBackdrop = null
                    buffers[0] = null
                    buffers[1] = null
                }
                delay(120L)
                continue
            }
            // Note: no extra delay() here. withFrameNanos at the top
            // of the next iteration paces us to one display frame
            // (16.6 ms @ 60 Hz, 8.3 ms @ 120 Hz) which is the right
            // budget for "as fast as the renderer is producing new
            // backdrop content". Earlier versions slept 40 ms which
            // capped blur publishing at 25 Hz and made the effect
            // visibly drift behind the UI.
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            if (!onPlayer) {
                CompositionLocalProvider(
                    com.soundcloud.lite.ui.components.LocalSafeForGlass provides false
                ) {
                    NavigationBar(
                        containerColor = if (glassOn)
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
                        else
                            MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp
                    ) {
                        bottomTabs.forEach { tab ->
                            val selected = currentRoute == tab.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    if (!selected) {
                                        navController.navigate(tab.route) {
                                            popUpTo("home") {
                                                saveState = true
                                                inclusive = false
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label, modifier = Modifier.size(22.dp)) },
                                label = {
                                    Text(
                                        tab.label,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Visible
                                    )
                                },
                                alwaysShowLabel = true,
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurface,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                )
                            )
                        }
                    }
                } // end CompositionLocalProvider(LocalSafeForGlass)
            }
        }
    ) { innerPadding ->
        val bgBrush = appBackgroundBrush()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Capture subtree. The bg brush is painted by a SIBLING
            // child Box (with .matchParentSize().background(brush)),
            // not a modifier on the recorded Box itself, and not by
            // a manual drawRect inside record { }. Why:
            //
            //   - .background() applied BEFORE .drawWithContent in
            //     the modifier chain runs as drawBehind on the OUTER
            //     screen canvas, so it never lands in the layer ->
            //     layer pixels stay (mostly) transparent and the
            //     blurred bitmap looks like a translucent shadow
            //     mask of the foreground UI. (Previously observed
            //     "blur dims objects".)
            //
            //   - Calling drawRect(brush) directly inside the
            //     backdropLayer.record { ... } lambda before
            //     drawContent() crashed RenderThread on Compose
            //     1.7.5 / hwui with a stack-overflow infinite
            //     recursion in RenderNode::prepareTreeImpl ->
            //     SkiaDisplayList::prepareListAndChildren. So that
            //     path is off the table.
            //
            //   - A child Box with .background(brush) is just a
            //     normal child. drawContent() inside record draws
            //     all children in order. Child #1 paints the brush
            //     opaquely, child #2 (AppNavHost) paints UI on top.
            //     The layer ends up fully opaque.
            //
            // The MiniPlayer overlay lives OUTSIDE this Box so it
            // doesn't end up inside the recording (and therefore
            // can't blur itself recursively).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (backdropLayer != null) {
                            Modifier
                                .onGloballyPositioned {
                                    backdropOrigin = it.positionInRoot()
                                }
                                .drawWithContent {
                                    backdropLayer.record {
                                        this@drawWithContent.drawContent()
                                    }
                                    drawLayer(backdropLayer)
                                }
                        } else Modifier
                    )
            ) {
                // Background as a sibling child so drawContent()
                // inside the layer's record block captures it.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(bgBrush)
                )
                AppNavHost(navController = navController, viewModel = viewModel)
            }

            // MiniPlayer overlay — sits at the bottom of the content
            // area (just above the NavigationBar), NOT in bottomBar,
            // so it floats over the list and its GlassSurface can
            // sample the captured list pixels behind it.
            if (!onPlayer && playerState.currentTrack != null) {
                CompositionLocalProvider(
                    LocalBackdropLayer provides backdropLayer,
                    com.soundcloud.lite.ui.components.LocalBackdropOrigin provides backdropOrigin,
                    LocalTilt provides tilt,
                    LocalBlurredBackdrop provides blurredBackdrop,
                    LocalBlurredBackdropScale provides captureScale,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        MiniPlayer(
                            state = playerState,
                            onPlayPause = { viewModel.playerManager.togglePlayPause() },
                            onNext = { viewModel.playerManager.skipNext() },
                            onClick = { navController.navigate("player") }
                        )
                    }
                }
            }
        }
    }

    // Universal alternative-source picker overlay. Hosted at the AppRoot
    // level so it can pop up over any tab/screen the user happens to be
    // on when they long-press a track.
    val altState by viewModel.altSourceState.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    altState?.let { st ->
        com.soundcloud.lite.ui.components.AlternativeSourceDialog(
            state = st,
            playlists = playlists,
            onDismiss = { viewModel.closeAlternativeSources() },
            onPlay = { alt -> viewModel.playAlternative(st.originalTrack, alt) },
            onReplaceInCurrent = { alt ->
                val pid = st.currentPlaylistId
                if (pid != null) {
                    viewModel.replaceWithAlternative(pid, st.originalTrack.id, alt)
                }
            },
            onAddToPlaylist = { pid, alt -> viewModel.addAlternativeToPlaylist(pid, alt) }
        )
    }
}

/**
 * Subscribes to the system accelerometer and returns a smoothed
 * (x, y) tilt vector in roughly (-1, 1). Used by [GlassSurface] to
 * shift the side specular reflections as the user tilts the phone.
 */
@Composable
private fun rememberTilt(): Offset {
    val context = LocalContext.current
    var state by remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(context) {
        val mgr = context.getSystemService(AndroidContext.SENSOR_SERVICE) as? SensorManager
        val sensor = mgr?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var smoothX = 0f
        var smoothY = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // Low-pass so the reflections glide rather than jitter.
                smoothX = smoothX * 0.88f + event.values[0] * 0.12f
                smoothY = smoothY * 0.88f + event.values[1] * 0.12f
                // Map gravity (~-9.8..9.8) to (-1..1) and invert x so
                // leaning right (phone tilted to the right) maps to
                // positive x for the reflection to brighten the right
                // edge.
                state = Offset(
                    x = (-smoothX / 9.8f).coerceIn(-1f, 1f),
                    y = (smoothY / 9.8f).coerceIn(-1f, 1f)
                )
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (mgr != null && sensor != null) {
            mgr.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose {
            if (mgr != null) mgr.unregisterListener(listener)
        }
    }
    return state
}

@Composable
private fun appBackgroundBrush(): Brush {
    val t = LocalSCTheme.current
    return if (t.gradientBackground) {
        Brush.verticalGradient(listOf(t.gradientTop, t.gradientBottom))
    } else {
        Brush.verticalGradient(listOf(t.bg, t.bg))
    }
}

@Composable
private fun AppNavHost(navController: NavHostController, viewModel: MainViewModel) {
    val speed = LocalSCTheme.current.animSpeed.millis
    NavHost(
        navController = navController,
        startDestination = "home",
        enterTransition = { fadeIn(tween(speed)) },
        exitTransition = { fadeOut(tween(speed)) },
        popEnterTransition = { fadeIn(tween(speed)) },
        popExitTransition = { fadeOut(tween(speed)) }
    ) {
        composable("home") {
            HomeScreen(
                viewModel = viewModel,
                onOpenPlaylist = { id -> navController.navigate("playlist/${Uri.encode(id)}") },
                onOpenRelated = { id -> navController.navigate("related/$id") }
            )
        }
        composable("search") {
            SearchScreen(
                viewModel = viewModel,
                onOpenRelated = { id -> navController.navigate("related/$id") }
            )
        }
        composable("downloads") { DownloadsScreen(viewModel) }
        composable(
            route = "related/{trackId}",
            arguments = listOf(navArgument("trackId") { type = NavType.LongType })
        ) { entry ->
            val id = entry.arguments?.getLong("trackId") ?: 0L
            RelatedScreen(
                viewModel = viewModel,
                trackId = id,
                onBack = { navController.popBackStack() }
            )
        }
        composable("playlists") {
            PlaylistsScreen(
                viewModel = viewModel,
                onOpenPlaylist = { id ->
                    navController.navigate("playlist/${Uri.encode(id)}")
                }
            )
        }
        composable(
            route = "playlist/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) { entry ->
            val id = Uri.decode(entry.arguments?.getString("id") ?: "")
            PlaylistDetailScreen(
                viewModel = viewModel,
                playlistId = id,
                onBack = { navController.popBackStack() }
            )
        }
        composable("settings") { SettingsScreen(viewModel) }
        composable(
            route = "player",
            // Slide the full-screen player up from the bottom when entering,
            // and slide it back down + fade out when the user dismisses it.
            // The default NavHost transitions only fade, which makes the
            // player feel like it disappears instantly.
            enterTransition = {
                slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(speed)
                ) + fadeIn(tween(speed))
            },
            exitTransition = { fadeOut(tween(speed)) },
            popEnterTransition = { fadeIn(tween(speed)) },
            popExitTransition = {
                slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(speed)
                ) + fadeOut(tween(speed))
            }
        ) {
            PlayerScreen(
                viewModel = viewModel,
                onClose = { navController.popBackStack() },
                onOpenQueue = { navController.navigate("queue") }
            )
        }
        composable("queue") {
            QueueScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

/**
 * Shown on the next app launch after an uncaught exception. The trace is
 * displayed in a scrollable, monospace block and the "Скопировать" button
 * pushes it onto the system clipboard so the user can paste it into chat.
 *
 * The dialog dismisses itself when the user taps Close; the trace file
 * was already deleted by [CrashLogger.readAndClear] so this only ever
 * surfaces once per crash.
 */
@Composable
private fun CrashReportDialog(report: String) {
    var visible by remember { mutableStateOf(true) }
    if (!visible) return
    val ctx = LocalContext.current
    val scrollState = rememberScrollState()
    AlertDialog(
        onDismissRequest = { visible = false },
        title = { Text("Приложение упало в прошлом запуске") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(scrollState)
            ) {
                Text(
                    report,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("SC Lite crash", report))
                visible = false
            }) { Text("Скопировать и закрыть") }
        },
        dismissButton = {
            TextButton(onClick = { visible = false }) { Text("Закрыть") }
        }
    )
}
