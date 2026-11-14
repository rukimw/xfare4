package lk.xfare.app

import android.accessibilityservice.AccessibilityService
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import lk.xfare.app.R

data class FareCalculation(
    val grossFare: Double,
    val totalDistanceKm: Double,
    val netFare: Double,
    val netRatePerKm: Double
)

internal object FareParser {
    private val FARE_REGEX =
        Regex("(?i)(?:rs\\.?|lkr|රු\\.?)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)(?!\\s*/\\s*km)")
    private val DISTANCE_REGEX =
        Regex("(?i)\\b(\\d+(?:[.,]\\d+)?)\\s*(km|m)(?![a-z])")

    fun extractFare(texts: List<String>): Double? {
        val fares = mutableListOf<Double>()
        for (line in texts) {
            val matches = FARE_REGEX.findAll(line)
            for (match in matches) {
                val raw = match.groupValues[1].replace(",", "")
                val value = raw.toDoubleOrNull() ?: continue
                if (value in 40.0..500000.0) {
                    fares.add(value)
                }
            }
        }
        return fares.maxOrNull()
    }

    fun extractTotalDistanceKm(texts: List<String>): Double? {
        val distancesKm = mutableListOf<Double>()
        for (line in texts) {
            val matches = DISTANCE_REGEX.findAll(line)
            for (match in matches) {
                val rawNumber = match.groupValues[1].replace(",", ".")
                val unit = match.groupValues[2].lowercase()
                val numeric = rawNumber.toDoubleOrNull() ?: continue
                val kmValue = if (unit == "m") numeric / 1000.0 else numeric
                if (kmValue in 0.05..400.0) {
                    distancesKm.add(kmValue)
                }
            }
        }
        if (distancesKm.isEmpty()) return null
        // Pickup distance + Trip distance (sum up to first 2 distinct offer distances if extra noise exists,
        // or sum all if 1-2 distance fields appear on the incoming ride card)
        return if (distancesKm.size <= 2) {
            distancesKm.sum()
        } else {
            distancesKm.take(2).sum()
        }
    }

    fun calculate(
        grossFare: Double,
        totalDistanceKm: Double,
        commissionPercent: Double
    ): FareCalculation? {
        if (grossFare <= 0.0 || totalDistanceKm <= 0.0) return null
        val clampedCommission = commissionPercent.coerceIn(0.0, 95.0)
        val netFare = grossFare * (1.0 - clampedCommission / 100.0)
        val netRatePerKm = netFare / totalDistanceKm
        return FareCalculation(
            grossFare = grossFare,
            totalDistanceKm = totalDistanceKm,
            netFare = netFare,
            netRatePerKm = netRatePerKm
        )
    }

    fun appLabelForPackage(packageName: String): String? {
        return when (packageName) {
            PKG_PICKME -> "PICKME"
            PKG_UBER -> "UBER"
            PKG_HELAGO -> "HELAGO"
            else -> null
        }
    }

    const val PKG_PICKME = "com.pickme.driver"
    const val PKG_UBER = "com.ubercab.driver"
    const val PKG_HELAGO = "lk.helago.driver"
}

class FareAccessibilityService : AccessibilityService() {

    private var overlayManager: OverlayManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastScanTimestampMs: Long = 0L
    private var lastOfferSignature: String = ""
    private var lastOfferShownAtMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        KeepAliveService.start(this)
        if (overlayManager == null) {
            overlayManager = OverlayManager(this)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        val appLabel = FareParser.appLabelForPackage(packageName) ?: return

        val now = System.currentTimeMillis()
        if (now - lastScanTimestampMs < SCAN_THROTTLE_MS) {
            return
        }
        lastScanTimestampMs = now

        val root = rootInActiveWindow ?: return
        val collectedTexts = mutableListOf<String>()
        try {
            collectLeafTexts(root, collectedTexts)
        } finally {
            try {
                @Suppress("DEPRECATION")
                root.recycle()
            } catch (_: Exception) {
            }
        }

        if (collectedTexts.isEmpty()) return

        val fare = FareParser.extractFare(collectedTexts) ?: return
        val totalDistanceKm = FareParser.extractTotalDistanceKm(collectedTexts) ?: return

        val prefs = FarePrefs.get(this)
        val commissionPercent = prefs.getFloat(
            FarePrefs.KEY_COMMISSION,
            FarePrefs.DEFAULT_COMMISSION
        ).toDouble()
        val greenThreshold = prefs.getFloat(
            FarePrefs.KEY_GREEN_THRESHOLD,
            FarePrefs.DEFAULT_GREEN_THRESHOLD
        ).toDouble()
        val beepEnabled = prefs.getBoolean(FarePrefs.KEY_BEEP_ENABLED, true)

        val calculation = FareParser.calculate(fare, totalDistanceKm, commissionPercent) ?: return

        val signature = "$packageName|${fare.toInt()}|${(totalDistanceKm * 10).toInt()}"
        if (signature == lastOfferSignature && (now - lastOfferShownAtMs) < OFFER_COOLDOWN_MS) {
            return
        }
        lastOfferSignature = signature
        lastOfferShownAtMs = now

        if (overlayManager == null) {
            overlayManager = OverlayManager(this)
        }
        overlayManager?.showBanner(
            sourceAppLabel = appLabel,
            netRatePerKm = calculation.netRatePerKm,
            netPayout = calculation.netFare,
            totalDistanceKm = calculation.totalDistanceKm
        )

        if (beepEnabled && calculation.netRatePerKm >= greenThreshold) {
            playGreenRideBeep()
        }
    }

    override fun onInterrupt() {
        overlayManager?.dismiss()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        overlayManager?.destroy()
        overlayManager = null
        super.onDestroy()
    }

    private fun collectLeafTexts(node: AccessibilityNodeInfo, output: MutableList<String>) {
        val childCount = node.childCount
        if (childCount == 0) {
            val nodeText = node.text?.toString()?.trim()
            if (!nodeText.isNullOrEmpty()) {
                output.add(nodeText)
            }
            val descText = node.contentDescription?.toString()?.trim()
            if (!descText.isNullOrEmpty() && descText != nodeText) {
                output.add(descText)
            }
            return
        }

        val nodeText = node.text?.toString()?.trim()
        if (!nodeText.isNullOrEmpty()) {
            output.add(nodeText)
        }

        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectLeafTexts(child, output)
            } finally {
                try {
                    @Suppress("DEPRECATION")
                    child.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun playGreenRideBeep() {
        try {
            val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 200)
            mainHandler.postDelayed({
                try {
                    toneGenerator.release()
                } catch (_: Exception) {
                }
            }, 450L)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val SCAN_THROTTLE_MS = 250L
        private const val OFFER_COOLDOWN_MS = 10000L
    }
}
