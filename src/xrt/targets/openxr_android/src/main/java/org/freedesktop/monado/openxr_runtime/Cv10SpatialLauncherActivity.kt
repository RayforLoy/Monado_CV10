// SPDX-License-Identifier: BSL-1.0

package org.freedesktop.monado.openxr_runtime

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import androidx.annotation.Keep
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.freedesktop.monado.auxiliary.MonadoView

/** An offline OpenXR app picker that runs in a separate client process. */
class Cv10SpatialLauncherActivity : Activity() {
    private lateinit var controls: TextView
    private lateinit var input: Cv10LaserInput
    private var apps = emptyList<Cv10AppEntry>()
    private var xrOnly = false
    private var page = 0
    private var visible = false
    private var closing = false
    private var startupFailed = false
    private var startupPromptShown = false
    private var pendingLaunch: Cv10AppEntry? = null
    private var renderState: SpatialLauncherState? = null
    private var hitTargets = emptyList<HitTarget>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controls = object : TextView(this) {
            private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(136, 188, 232)
                strokeCap = Paint.Cap.ROUND
                strokeWidth = 3 * resources.displayMetrics.density
            }

            override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
                invalidate()
                return super.onApplyWindowInsets(insets)
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                if (!::input.isInitialized || renderState?.ready != true) return
                val regions = input.gestureRegions()
                val left = regions.leftInset + regions.backBandWidth / 2
                val right = width - regions.rightInset - regions.backBandWidth / 2
                val bottom = height - regions.bottomInset - regions.recenterBandHeight / 2
                val guideHalfLength = 18 * resources.displayMetrics.density
                canvas.drawLine(left, height / 2f - guideHalfLength, left, height / 2f + guideHalfLength, guidePaint)
                canvas.drawLine(right, height / 2f - guideHalfLength, right, height / 2f + guideHalfLength, guidePaint)
                val center = (left + right) / 2
                canvas.drawLine(center - guideHalfLength, bottom, center + guideHalfLength, bottom, guidePaint)
            }
        }.apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(PHONE_BACKGROUND)
            textSize = 20f
            val margin = (24 * resources.displayMetrics.density).toInt()
            setPadding(margin, margin, margin, margin)
        }
        setContentView(controls)
        input =
            Cv10LaserInput(
                view = controls,
                onAim = { x, y -> renderState?.setAim(x, y) },
                onConfirm = ::confirm,
                onBack = { close() },
                onRecenter = { renderState?.recenter() },
            )
        controls.setOnTouchListener(input)
        controls.setOnClickListener {
            when {
                renderState != null -> input.confirm()
                startupFailed -> showStartupOptions()
                else -> startRenderer()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        apps = Cv10AppCatalog.load(this)
        xrOnly = apps.any { it.isXr }
        page = 0
        startRenderer()
    }

    override fun onResume() {
        super.onResume()
        input.start()
    }

    override fun onPause() {
        input.stop()
        super.onPause()
    }

    override fun onStop() {
        visible = false
        renderState?.stop = true
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && visible && startupFailed && !startupPromptShown && !isFinishing) {
            showStartupOptions()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() = close()

    private fun startRenderer() {
        if (renderState != null || closing) return
        startupFailed = false
        startupPromptShown = false
        val state =
            SpatialLauncherState {
                runOnUiThread {
                    if (renderState?.ready == true && !closing) {
                        controls.setText(R.string.cv10_spatial_phone_controls)
                    }
                }
            }
        renderState = state
        controls.setText(R.string.cv10_spatial_opening)
        controls.postDelayed(
            {
                if (renderState === state && visible && !state.ready && !closing) {
                    controls.setText(R.string.cv10_spatial_waiting)
                }
            },
            STARTUP_NOTICE_MILLIS,
        )
        updatePanel()
        Thread(
                {
                    val failure =
                        try {
                            System.loadLibrary("cv10-spatial-launcher")
                            val manifest = Cv10BundledRuntime.manifestPath(applicationContext, "cv10-spatial-runtime.json")
                            val useBundledRuntime = MonadoView.findCv10(applicationContext) != null
                            nativeRun(applicationContext, state, manifest, useBundledRuntime)
                        } catch (error: LinkageError) {
                            Log.e(TAG, "OpenXR launcher native library unavailable", error)
                            error.message ?: error.toString()
                        }
                    state.takePanel()?.recycle()
                    runOnUiThread { rendererEnded(state, failure) }
                },
                "Cv10SpatialLauncher",
            )
            .start()
    }

    private fun rendererEnded(state: SpatialLauncherState, failure: String?) {
        if (renderState !== state) return
        renderState = null
        if (isDestroyed) return
        if (!visible) {
            closing = false
            pendingLaunch = null
            return
        }
        if (closing) {
            val app = pendingLaunch
            pendingLaunch = null
            closing = false
            if (app == null || Cv10AppCatalog.launch(this, app)) finish() else startRenderer()
        } else if (state.stop) {
            // The Activity came to the foreground while the previous session was still closing.
            startRenderer()
        } else {
            if (failure != null) Log.e(TAG, failure)
            startupFailed = true
            controls.setText(R.string.cv10_spatial_unavailable)
            if (hasWindowFocus() && !isFinishing) showStartupOptions()
        }
    }

    private fun showStartupOptions() {
        startupPromptShown = true
        AlertDialog.Builder(this)
            .setMessage(R.string.cv10_spatial_unavailable)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.cv10_spatial_retry) { _, _ -> startRenderer() }
            .setPositiveButton(R.string.cv10_spatial_open_broker) { _, _ ->
                val intent = Intent(Intent.ACTION_MAIN).setClassName(
                    "org.khronos.openxr.runtime_broker",
                    "org.khronos.openxr.runtime_broker.MainActivity",
                )
                try {
                    startActivity(intent)
                } catch (error: ActivityNotFoundException) {
                    Log.w(TAG, "OpenXR Runtime Broker activity is unavailable", error)
                    controls.setText(R.string.cv10_spatial_broker_missing)
                }
            }
            .show()
    }

    private fun close(app: Cv10AppEntry? = null) {
        if (closing) return
        val state = renderState
        if (state != null) {
            if (app?.isXr == true) holdRuntimeDuringLaunch()
            closing = true
            pendingLaunch = app
            state.stop = true
        } else {
            finish()
        }
    }

    private fun holdRuntimeDuringLaunch() {
        val context = applicationContext
        val service = Intent(MONADO_CONNECT_ACTION).setPackage(packageName)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) = Unit

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        // Use the same binding as the XR clients so the refresh lease stays active on handoff.
        if (context.bindService(service, connection, Context.BIND_AUTO_CREATE)) {
            Handler(Looper.getMainLooper()).postDelayed(
                { context.unbindService(connection) },
                XR_APP_HANDOFF_MILLIS,
            )
        }
    }

    private fun confirm(x: Float, y: Float) {
        if (renderState?.ready != true || closing || x !in -1f..1f || y !in -1f..1f) return
        val canvasX = (x + 1f) * PANEL_WIDTH / 2f
        val canvasY = (y + 1f) * PANEL_HEIGHT / 2f
        hitTargets.firstOrNull { it.bounds.contains(canvasX, canvasY) }?.action?.invoke()
    }

    private fun updatePanel() {
        val state = renderState ?: return
        val entries = if (xrOnly) apps.filter { it.isXr } else apps
        val lastPage = ((entries.size - 1) / PAGE_SIZE).coerceAtLeast(0)
        page = page.coerceIn(0, lastPage)
        val bitmap = Bitmap.createBitmap(PANEL_WIDTH, PANEL_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val targets = mutableListOf<HitTarget>()

        fun rounded(rect: RectF, radius: Float, background: Int) {
            paint.color = background
            canvas.drawRoundRect(rect, radius, radius, paint)
        }

        fun label(
            value: String,
            x: Float,
            y: Float,
            maxWidth: Float,
            size: Float,
            color: Int,
            bold: Boolean = false,
        ) {
            paint.color = color
            paint.textSize = size
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val text = TextUtils.ellipsize(value, paint, maxWidth, TextUtils.TruncateAt.END)
            canvas.drawText(text, 0, text.length, x, y - (paint.ascent() + paint.descent()) / 2, paint)
        }

        fun button(value: Int, rect: RectF, active: Boolean, action: (() -> Unit)? = null) {
            val fill = if (active) ACCENT else if (action != null) TILE else DISABLED
            rounded(rect, 17f, fill)
            label(
                getString(value),
                rect.left + 18f,
                rect.centerY(),
                rect.width() - 36f,
                28f,
                if (active) DARK_TEXT else if (action != null) Color.WHITE else MUTED,
                true,
            )
            if (action != null) targets.add(HitTarget(rect, action))
        }

        canvas.drawColor(BACKGROUND)
        label(getString(R.string.cv10_spatial_title), 48f, 65f, 440f, 52f, Color.WHITE, true)
        button(R.string.cv10_spatial_xr_apps, RectF(516f, 31f, 738f, 99f), xrOnly) {
            xrOnly = true
            page = 0
            updatePanel()
        }
        button(R.string.cv10_spatial_all_apps, RectF(756f, 31f, 980f, 99f), !xrOnly) {
            xrOnly = false
            page = 0
            updatePanel()
        }
        button(R.string.cv10_spatial_close, RectF(1000f, 31f, 1232f, 99f), false) { close() }

        if (entries.isEmpty()) {
            label(getString(R.string.cv10_spatial_empty), 48f, 330f, 1184f, 38f, MUTED)
        }
        entries.drop(page * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, app ->
            val tileWidth = 582f
            val tileHeight = 128f
            val left = if (entries.size == 1) (PANEL_WIDTH - tileWidth) / 2f else 48f + (index % 2) * 602f
            val top = if (entries.size == 1) (PANEL_HEIGHT - tileHeight) / 2f else 132f + (index / 2) * 147f
            val rect = RectF(left, top, left + tileWidth, top + tileHeight)
            rounded(rect, 20f, TILE)
            val icon = try {
                packageManager.getActivityIcon(app.component)
            } catch (_: PackageManager.NameNotFoundException) {
                packageManager.defaultActivityIcon
            }
            icon.setBounds((left + 30f).toInt(), (top + 31f).toInt(), (left + 96f).toInt(), (top + 97f).toInt())
            icon.draw(canvas)
            val textLeft = left + 122f
            val textRight = if (app.isXr) rect.right - 116f else rect.right - 26f
            if (app.isXr) {
                val badge = RectF(rect.right - 98f, top + 38f, rect.right - 28f, top + 90f)
                rounded(badge, 13f, ACCENT)
                label("XR", badge.left + 18f, badge.centerY(), 42f, 25f, DARK_TEXT, true)
            }
            label(app.label, textLeft, rect.centerY(), textRight - textLeft, 40f, Color.WHITE, true)
            targets.add(HitTarget(rect) { close(app) })
        }

        val previous: (() -> Unit)? = if (page > 0) ({ page--; updatePanel() }) else null
        val next: (() -> Unit)? = if (page < lastPage) ({ page++; updatePanel() }) else null
        button(R.string.cv10_spatial_previous, RectF(48f, 613f, 278f, 683f), false, previous)
        label("${page + 1} / ${lastPage + 1}", 590f, 648f, 150f, 29f, MUTED)
        button(R.string.cv10_spatial_next, RectF(1002f, 613f, 1232f, 683f), false, next)

        hitTargets = targets
        state.updatePanel(bitmap)
    }

    @Keep
    private external fun nativeRun(
        context: Context,
        state: SpatialLauncherState,
        runtimeManifest: String?,
        useBundledRuntime: Boolean,
    ): String?

    private data class HitTarget(val bounds: RectF, val action: () -> Unit)

    private companion object {
        private const val TAG = "Cv10SpatialLauncher"
        private const val PANEL_WIDTH = 1280
        private const val PANEL_HEIGHT = 720
        private const val PAGE_SIZE = 6
        private const val STARTUP_NOTICE_MILLIS = 15_000L
        private const val MONADO_CONNECT_ACTION = "org.freedesktop.monado.ipc.CONNECT"
        private const val XR_APP_HANDOFF_MILLIS = 3_000L
        private val BACKGROUND = Color.rgb(18, 24, 36)
        private val PHONE_BACKGROUND = Color.rgb(14, 19, 28)
        private val TILE = Color.rgb(42, 55, 77)
        private val DISABLED = Color.rgb(27, 36, 51)
        private val MUTED = Color.rgb(185, 203, 225)
        private val ACCENT = Color.rgb(147, 231, 207)
        private val DARK_TEXT = Color.rgb(10, 40, 34)
    }
}

/** Single-writer render commands shared by the Android UI and the OpenXR frame loop. */
@Keep
internal class SpatialLauncherState(private val onReady: () -> Unit) {
    private val panel = AtomicReference<Bitmap>()
    private val packedAim = AtomicLong()
    private val recenterCount = AtomicInteger()

    @Volatile var stop = false
    @Volatile var ready = false
        private set

    fun updatePanel(bitmap: Bitmap) {
        panel.getAndSet(bitmap)?.recycle()
    }

    fun takePanel(): Bitmap? = panel.getAndSet(null)

    fun setAim(x: Float, y: Float) {
        val xBits = x.toRawBits().toLong()
        val yBits = y.toRawBits().toLong()
        packedAim.set((xBits shl 32) or (yBits and 0xffffffffL))
    }

    fun aimBits(): Long = packedAim.get()

    fun recenter() {
        recenterCount.incrementAndGet()
    }

    fun recenterGeneration(): Int = recenterCount.get()

    fun shouldStop(): Boolean = stop

    fun updateReady(ready: Boolean) {
        this.ready = ready
        if (ready) onReady()
    }
}
