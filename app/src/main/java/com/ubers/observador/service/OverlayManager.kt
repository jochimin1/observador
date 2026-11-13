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
        textScale = if (large) LARGE_SCALE else 1f
        removeNow()
        val view = buildCardView(analysis, quality, banner).apply {
            contentDescription = (banner?.let { it.text + ". " } ?: "") +
                "Oferta ${quality.label}, ${analysis.score} de 100. Toca para cerrar"
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
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = getStatusBarHeight() + (4 * context.resources.displayMetrics.density).toInt()
        }
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
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = getStatusBarHeight() + (4 * dp).toInt()
        }
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

        // Price + verdict row
        card.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "RD$%.2f".format(analysis.offer.price)
                textSize = 22f * textScale
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
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
            addView(TextView(context).apply {
                text = "✕"
                textSize = 18f * textScale
                setTextColor(Color.parseColor("#AAAAAA"))
                setPadding((12 * dp).toInt(), 0, (2 * dp).toInt(), 0)
            })
        })

        // km · min
        card.addView(TextView(context).apply {
            text = "%.1f km · %d min".format(analysis.totalKm, analysis.totalMin)
            textSize = 14f * textScale
            setTextColor(Color.parseColor("#CCCCCC"))
            setPadding(0, marginSm, 0, 0)
        })

        // RD$/km rate
        card.addView(TextView(context).apply {
            text = "RD$%.2f/km".format(analysis.rdpPerKm)
            textSize = 14f * textScale
            setTextColor(Color.parseColor("#CCCCCC"))
            setPadding(0, marginSm, 0, 0)
        })

        // Fase 8F: where from and where to, as Uber wrote them (one line each, cut with "…")
        listOfNotNull(
            analysis.offer.pickupAddress?.let { "▲ $it" },
            analysis.offer.dropoffAddress?.let { "▼ $it" }
        ).forEach { line ->
            card.addView(TextView(context).apply {
                text = line
                textSize = 13f * textScale
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = (280 * dp).toInt()
                setPadding(0, marginSm, 0, 0)
            })
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
