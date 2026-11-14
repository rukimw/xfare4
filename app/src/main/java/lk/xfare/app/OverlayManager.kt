package lk.xfare.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import lk.xfare.app.R

object FarePrefs {
    const val PREFS_NAME = "xfare_lk_prefs"
    const val KEY_COMMISSION = "commission_percent"
    const val KEY_GREEN_THRESHOLD = "green_threshold"
    const val KEY_AMBER_THRESHOLD = "amber_threshold"
    const val KEY_BEEP_ENABLED = "green_ride_beep_enabled"
    const val KEY_OVERLAY_Y = "overlay_y_position"

    const val DEFAULT_COMMISSION = 15.0f
    const val DEFAULT_GREEN_THRESHOLD = 120.0f
    const val DEFAULT_AMBER_THRESHOLD = 85.0f
    const val DEFAULT_OVERLAY_Y = 160

    fun get(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}

class OverlayManager(private val context: Context) {

    private val appContext: Context = context.applicationContext
    private val windowManager: WindowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs: SharedPreferences = FarePrefs.get(appContext)
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
    private val touchSlop: Int = ViewConfiguration.get(appContext).scaledTouchSlop

    private var bannerView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var initialY: Int = 0
    private var initialTouchY: Float = 0f
    private var isDragging: Boolean = false

    private val autoDismissRunnable = Runnable {
        dismissInternal()
    }

    fun showBanner(
        sourceAppLabel: String,
        netRatePerKm: Double,
        netPayout: Double,
        totalDistanceKm: Double
    ) {
        mainHandler.post {
            showBannerInternal(sourceAppLabel, netRatePerKm, netPayout, totalDistanceKm)
        }
    }

    fun dismiss() {
        mainHandler.post {
            dismissInternal()
        }
    }

    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dismissInternal()
        } else {
            mainHandler.post { dismissInternal() }
        }
    }

    private fun showBannerInternal(
        sourceAppLabel: String,
        netRatePerKm: Double,
        netPayout: Double,
        totalDistanceKm: Double
    ) {
        if (!Settings.canDrawOverlays(appContext)) {
            return
        }

        mainHandler.removeCallbacks(autoDismissRunnable)

        val view = bannerView ?: createBannerView().also { createdView ->
            bannerView = createdView
            val params = createLayoutParams()
            layoutParams = params
            try {
                windowManager.addView(createdView, params)
            } catch (_: Exception) {
                bannerView = null
                layoutParams = null
                return
            }
        }

        val greenThreshold = prefs.getFloat(
            FarePrefs.KEY_GREEN_THRESHOLD,
            FarePrefs.DEFAULT_GREEN_THRESHOLD
        ).toDouble()
        val amberThreshold = prefs.getFloat(
            FarePrefs.KEY_AMBER_THRESHOLD,
            FarePrefs.DEFAULT_AMBER_THRESHOLD
        ).toDouble()

        val bannerColor: Int
        val tierLabel: String
        when {
            netRatePerKm >= greenThreshold -> {
                bannerColor = COLOR_GREEN
                tierLabel = "GREEN RIDE"
            }
            netRatePerKm >= amberThreshold -> {
                bannerColor = COLOR_AMBER
                tierLabel = "AMBER RIDE"
            }
            else -> {
                bannerColor = COLOR_RED
                tierLabel = "RED RIDE"
            }
        }

        val rootContainer = view.findViewById<LinearLayout>(R.id.overlay_root)
        val background = (rootContainer.background?.mutate() as? GradientDrawable)
            ?: GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18f * appContext.resources.displayMetrics.density
            }
        background.setColor(bannerColor)
        rootContainer.background = background

        val symbols = DecimalFormatSymbols(Locale.US)
        val rateFormatter = DecimalFormat("#,##0", symbols)
        val payoutFormatter = DecimalFormat("#,##0", symbols)
        val kmFormatter = DecimalFormat("0.0", symbols)

        view.findViewById<TextView>(R.id.tv_overlay_app_tag).text =
            "$sourceAppLabel • $tierLabel"
        view.findViewById<TextView>(R.id.tv_overlay_net_rate).text =
            "Rs. ${rateFormatter.format(netRatePerKm)}/km"
        view.findViewById<TextView>(R.id.tv_overlay_net_payout).text =
            "Net: Rs. ${payoutFormatter.format(netPayout)}"
        view.findViewById<TextView>(R.id.tv_overlay_total_km).text =
            "${kmFormatter.format(totalDistanceKm)} km"

        mainHandler.postDelayed(autoDismissRunnable, AUTO_DISMISS_MS)
    }

    private fun createBannerView(): View {
        val inflater = LayoutInflater.from(appContext)
        val view = inflater.inflate(R.layout.overlay_fare_banner, null)

        val closeButton = view.findViewById<ImageButton>(R.id.btn_close_overlay)
        closeButton.setOnClickListener {
            dismissInternal()
        }

        val rootContainer = view.findViewById<LinearLayout>(R.id.overlay_root)
        rootContainer.setOnTouchListener(
            View.OnTouchListener { touchedView: View, event: MotionEvent ->
                val currentParams: WindowManager.LayoutParams =
                    layoutParams ?: return@OnTouchListener false

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialY = currentParams.y
                        initialTouchY = event.rawY
                        isDragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaY: Float = event.rawY - initialTouchY
                        if (abs(deltaY) > touchSlop) {
                            isDragging = true
                        }
                        val screenHeight: Int = appContext.resources.displayMetrics.heightPixels
                        val bannerHeight: Int = touchedView.height
                        val maxY: Int = (screenHeight - bannerHeight).coerceAtLeast(0)
                        val updatedY: Int = (initialY + deltaY.toInt()).coerceIn(0, maxY)
                        currentParams.y = updatedY

                        val hostView: View = bannerView ?: return@OnTouchListener false
                        try {
                            if (hostView.isAttachedToWindow) {
                                windowManager.updateViewLayout(hostView, currentParams)
                            }
                        } catch (_: Exception) {
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isDragging) {
                            prefs.edit().putInt(FarePrefs.KEY_OVERLAY_Y, currentParams.y).apply()
                        } else {
                            touchedView.performClick()
                        }
                        true
                    }
                    else -> false
                }
            }
        )

        return view
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val bannerWidth: Int = (appContext.resources.displayMetrics.widthPixels * 0.92f).toInt()
        val savedY: Int = prefs.getInt(FarePrefs.KEY_OVERLAY_Y, FarePrefs.DEFAULT_OVERLAY_Y)

        return WindowManager.LayoutParams(
            bannerWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = savedY
        }
    }

    private fun dismissInternal() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        val view = bannerView ?: return
        try {
            if (view.isAttachedToWindow) {
                windowManager.removeViewImmediate(view)
            }
        } catch (_: Exception) {
        } finally {
            bannerView = null
            layoutParams = null
        }
    }

    companion object {
        private const val AUTO_DISMISS_MS = 8000L
        private val COLOR_GREEN = Color.parseColor("#1B5E20")
        private val COLOR_AMBER = Color.parseColor("#F57F17")
        private val COLOR_RED = Color.parseColor("#B71C1C")
    }
}
