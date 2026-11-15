package com.ubers.observador.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.ubers.observador.domain.OfferQuality
import com.ubers.observador.domain.models.OfferAnalysis
import com.ubers.observador.domain.sectors.SectorBanner

class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null

    /** Main thread: a real cancel affordance is required before scheduling or executing a close. */
    val isShowing: Boolean get() = overlayView != null

    /** 1.37.0: called when the driver taps the floating card (it stops a pending close). */
    var onTap: (() -> Unit)? = null

    /** 1.33.1: true when Android refused the last card (BadTokenException: the service connection is not valid). */
    @Volatile var lastShowFailed = false
        private set

    /** Fase 8G: the Sol theme draws the card's text 15 % bigger, for the glare. */
    private var textScale = 1f

    /** 1.50.0: figures, position and text size chosen in Ajustes › Lectura y avisos (kept up to date by the service). */
    @Volatile var layout = com.ubers.observador.domain.OverlayLayout()

    private companion object {
        const val TAG = "OverlayManager"
        const val LARGE_SCALE = 1.15f
    }

    /** Safe to call from any thread; runs immediately when already on the main thread. */
    fun show(
        analysis: OfferAnalysis,
        quality: OfferQuality,
        durationSeconds: Int,
        large: Boolean = false,
        banner: SectorBanner? = null
    ) = runOnMain {
        textScale = (if (large) LARGE_SCALE else 1f) * layout.textSize.scale
        removeNow()
        val view = buildCardView(analysis, quality, banner).apply {
            contentDescription = (banner?.let { it.text + ". " } ?: "") +
                "Oferta ${quality.label}, ${analysis.score} de 100. " +
                layout.fields.flatMap { it.text(analysis) }.joinToString(". ") + ". Toca para cerrar"
            setOnClickListener {
                // 1.37.0: a tap on the card also stops any pending close
                onTap?.invoke()
                removeNow()
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Touchable on purpose: a tap on the card closes it. The window is only as big as
            // the card, and NOT_TOUCH_MODAL lets every touch outside it reach Uber.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { place(this) }
        try {
            windowManager.addView(view, params)
            lastShowFailed = false
            overlayView = view
            view.post {
                val d = context.resources.displayMetrics.density
                Log.d(TAG, "Card size: ${view.width}x${view.height} px = " +
                    "%.0fx%.0f dp".format(view.width / d, view.height / d))
            }
        } catch (e: Exception) {
            lastShowFailed = true
            Log.e(TAG, "Could not add overlay window", e)
            return@runOnMain
        }
        val autoDismiss = Runnable { removeNow() }
        dismissRunnable = autoDismiss
        handler.postDelayed(autoDismiss, durationSeconds * 1000L)
    }

    /** Safe to call from any thread. */
    fun dismiss() = runOnMain { removeNow() }

    /** Replaces stale scores with a readable explanation; never creates a numerical offer. */
    fun showUnreadable(reason: String, durationSeconds: Int = 5) = runOnMain {
        removeNow()
        val dp = context.resources.displayMetrics.density
        val view = TextView(context).apply {
            text = "No pude leer esta oferta\n$reason\nToca para cerrar"
            contentDescription = text
            textSize = 16f
            setTextColor(Color.WHITE)
            maxWidth = (320 * dp).toInt()
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply { setColor(Color.rgb(100, 51, 0)); cornerRadius = 16 * dp }
            setOnClickListener { onTap?.invoke(); removeNow() }
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { place(this) }
        try {
            windowManager.addView(view, params)
            overlayView = view
            lastShowFailed = false
            Runnable { removeNow() }.also { dismissRunnable = it; handler.postDelayed(it, durationSeconds * 1000L) }
        } catch (e: Exception) { lastShowFailed = true; Log.w(TAG, "Could not show reading notice", e) }
    }

    // Removal must be synchronous. Posting it from show() queued it behind show() itself,
    // so it ran right after addView and deleted the card that had just been created.
    private fun removeNow() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Overlay was already removed", e)
            }
        }
        overlayView = null
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    /** 1.50.0: top (below the status bar), centre, or bottom (above the navigation bar). */
    private fun place(params: WindowManager.LayoutParams) {
        val dp = context.resources.displayMetrics.density
        when (layout.position) {
            com.ubers.observador.domain.OverlayPosition.TOP -> {
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                params.y = getStatusBarHeight() + (4 * dp).toInt()
            }
            com.ubers.observador.domain.OverlayPosition.CENTER -> {
                params.gravity = Gravity.CENTER
                params.y = 0
            }
            com.ubers.observador.domain.OverlayPosition.BOTTOM -> {
                params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                params.y = navigationBarHeight() + (16 * dp).toInt()
            }
        }
    }

    /** The real navigation bar of this screen (Android 11+, the app's minimum), 0 when hidden. */
    private fun navigationBarHeight(): Int = runCatching {
        windowManager.currentWindowMetrics.windowInsets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
    }.getOrDefault(0)

    private fun getStatusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    @SuppressLint("SetTextI18n")
    private fun buildCardView(analysis: OfferAnalysis, quality: OfferQuality, banner: SectorBanner?): View {
        val dp = context.resources.displayMetrics.density
        val padding = (12 * dp).toInt()
        val marginSm = (4 * dp).toInt()

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F0000000"))
                cornerRadius = 16 * dp
            }
        }

        // 1.35.0: care sector band on top, orange to warn, red to avoid. The score stays as it is:
        // the sector is one more fact about the card, not a different price
        banner?.let { b ->
            card.addView(TextView(context).apply {
                text = b.text
                textSize = 14f * textScale
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = (300 * dp).toInt()
                setPadding((10 * dp).toInt(), marginSm, (10 * dp).toInt(), marginSm)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (b.avoid) "#C62828" else "#E65100"))
                    cornerRadius = 8 * dp
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (6 * dp).toInt() }
            })
        }

        // Stack the header at large font scales so the primary figure keeps usable width.
        val fields = layout.fields.ifEmpty { com.ubers.observador.domain.OverlayLayout.DEFAULT_FIELDS }
        val first = fields.first()
        val stacked = context.resources.configuration.fontScale * textScale > 1.5f
        card.addView(LinearLayout(context).apply {
            orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = first.text(analysis).joinToString("\n").ifEmpty { "—" }
                textSize = (if (first == com.ubers.observador.domain.OverlayField.ADDRESSES) 15f else 22f) * textScale
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = if (stacked) LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    else LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                maxWidth = (300 * dp).toInt()
            })
            // Filled badge with the label spelled out: color alone isn't enough in direct
            // sunlight or for color-blind drivers
            addView(TextView(context).apply {
                text = "${analysis.score} · ${quality.label}"
                textSize = 15f * textScale
                setTextColor(quality.foregroundArgb)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding((10 * dp).toInt(), marginSm, (10 * dp).toInt(), marginSm)
                background = GradientDrawable().apply {
                    setColor(quality.backgroundArgb)
                    cornerRadius = 8 * dp
                }
            })
            // Close hint: the whole card is tappable, this just tells the driver it can be closed
            // 1.50.0: at least 48 dp, so it can be hit with a glove; the whole card closes too
            addView(TextView(context).apply {
                text = "✕"
                textSize = 18f * textScale
                setTextColor(Color.parseColor("#AAAAAA"))
                gravity = Gravity.CENTER
                minWidth = (48 * dp).toInt()
                minHeight = (48 * dp).toInt()
                contentDescription = "Cerrar tarjeta"
                setOnClickListener { onTap?.invoke(); removeNow() }
            })
        })

        // 1.50.0: the other chosen figures, in the chosen order (addresses one line each, cut with "…")
        fields.drop(1).forEach { f ->
            f.text(analysis).forEach { line ->
                card.addView(TextView(context).apply {
                    text = line
                    textSize = (if (f == com.ubers.observador.domain.OverlayField.ADDRESSES) 13f else 14f) * textScale
                    setTextColor(Color.parseColor(if (f == com.ubers.observador.domain.OverlayField.ADDRESSES) "#FFFFFF" else "#CCCCCC"))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    maxWidth = (300 * dp).toInt()
                    setPadding(0, marginSm, 0, 0)
                })
            }
        }

        // Score bar
        card.addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = analysis.score
            progressDrawable.setColorFilter(quality.backgroundArgb, android.graphics.PorterDuff.Mode.SRC_IN)
            layoutParams = LinearLayout.LayoutParams((220 * dp).toInt(), (8 * dp).toInt()).also {
                it.topMargin = marginSm
            }
        })

        return card
    }
}
