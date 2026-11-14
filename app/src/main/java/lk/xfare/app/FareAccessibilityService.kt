package lk.xfare.app

import android.accessibilityservice.AccessibilityService
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import lk.xfare.app.R

data class FareCalculation(
    val grossFare: Double,
    val totalDistanceKm: Double,
    val netFare: Double,
    val netRatePerKm: Double
)

internal object FareParser {
    private val PREFIXED_FARE_REGEX =
        Regex("(?:Rs\\.?|LKR|රු\\.?)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)(?!\\s*/\\s*km)", RegexOption.IGNORE_CASE)

    private val STANDALONE_FARE_REGEX =
        Regex("\\b([0-9]{1,3}(?:,[0-9]{3})+|[1-9][0-9]{2,5})(?:\\.[0-9]{1,2})?\\b")

    private val KM_DISTANCE_REGEX =
        Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(?:km|KM|Km)\\b")

    private val METER_DISTANCE_REGEX =
        Regex("\\b([0-9]{2,4})\\s*m(?![a-zA-Z])")

    private val WALLET_NOISE_KEYWORDS = listOf(
        "wallet", "balance", "cash out", "cashout", "credit", "owing", "due", "earnings", "payout history"
    )

    private val NON_FARE_LINE_KEYWORDS = listOf(
        "km", "min", "mins", "hr", "hrs", "sec", "rating", "★", "/km", "am", "pm"
    )

    private val RIDE_ACTION_KEYWORDS = listOf(
        "accept", "pickup", "drop", "trip radar", "match"
    )

    fun isTargetRidePackage(packageName: String?): Boolean {
        val pkg = packageName?.lowercase() ?: return false
        if (pkg == "lk.xfare.app") return false
        return pkg.contains("pickme") ||
            pkg.contains("ubercab") ||
            pkg.contains("uber") ||
            pkg.contains("helago")
    }

    fun isRideScreenByKeywords(texts: List<String>): Boolean {
        if (texts.isEmpty()) return false
        val combined = texts.joinToString(" ").lowercase()
        val hasRideKeyword = RIDE_ACTION_KEYWORDS.any { combined.contains(it) }
        val hasKm = combined.contains("km")
        val hasCurrency = combined.contains("rs") || combined.contains("lkr") || combined.contains("රු")
        return hasRideKeyword && hasKm && hasCurrency
    }

    fun resolveAppLabel(packageName: String?, texts: List<String>): String {
        val pkg = packageName?.lowercase().orEmpty()
        val combined = texts.joinToString(" ").lowercase()
        return when {
            pkg.contains("pickme") || combined.contains("pickme") -> "PICKME"
            pkg.contains("uber") || combined.contains("trip radar") || combined.contains("uber") -> "UBER"
            pkg.contains("helago") || combined.contains("helago") -> "HELAGO"
            else -> "RIDE OFFER"
        }
    }

    fun extractFare(texts: List<String>): Double? {
        val prefixedFares = mutableListOf<Double>()
        for (line in texts) {
            val lowerLine = line.lowercase()
            if (WALLET_NOISE_KEYWORDS.any { lowerLine.contains(it) }) continue

            for (match in PREFIXED_FARE_REGEX.findAll(line)) {
                val raw = match.groupValues[1].replace(",", "")
                val value = raw.toDoubleOrNull() ?: continue
                if (value in 50.0..500000.0) {
                    prefixedFares.add(value)
                }
            }
        }
        if (prefixedFares.isNotEmpty()) {
            return prefixedFares.maxOrNull()
        }

        // Fallback: standalone currency numbers near trip cards when Rs./LKR prefix is omitted
        val standaloneFares = mutableListOf<Double>()
        for (line in texts) {
            val lowerLine = line.lowercase()
            if (WALLET_NOISE_KEYWORDS.any { lowerLine.contains(it) }) continue
            if (NON_FARE_LINE_KEYWORDS.any { lowerLine.contains(it) }) continue
            if (line.contains(":")) continue

            for (match in STANDALONE_FARE_REGEX.findAll(line)) {
                val raw = match.value.replace(",", "")
                val value = raw.toDoubleOrNull() ?: continue
                if (value in 50.0..500000.0) {
                    standaloneFares.add(value)
                }
            }
        }
        return standaloneFares.maxOrNull()
    }

    fun extractTotalDistanceKm(texts: List<String>): Double? {
        val kmValues = mutableListOf<Double>()
        for (line in texts) {
            for (match in KM_DISTANCE_REGEX.findAll(line)) {
                val value = match.groupValues[1].toDoubleOrNull() ?: continue
                if (value in 0.05..400.0) {
                    kmValues.add(value)
                }
            }
        }

        if (kmValues.size < 2) {
            for (line in texts) {
                for (match in METER_DISTANCE_REGEX.findAll(line)) {
                    val meters = match.groupValues[1].toDoubleOrNull() ?: continue
                    if (meters in 50.0..999.0) {
                        kmValues.add(meters / 1000.0)
                    }
                }
            }
        }

        if (kmValues.isEmpty()) return null
        return kmValues.take(2).sum()
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
}

class FareAccessibilityService : AccessibilityService() {

    private var overlayManager: OverlayManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastScanTimestampMs: Long = 0L
    private var lastOfferSignature: String = ""
    private var lastOfferShownAtMs: Long = 0L
    private var lastDebugToastAtMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        KeepAliveService.start(this)
        if (overlayManager == null) {
            overlayManager = OverlayManager(this)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackage = event?.packageName?.toString()
        if (eventPackage == packageName) return

        val now = System.currentTimeMillis()
        if (now - lastScanTimestampMs < SCAN_THROTTLE_MS) {
            return
        }
        lastScanTimestampMs = now

        val collectedTexts = mutableListOf<String>()
        val candidatePackages = linkedSetOf<String>()
        if (!eventPackage.isNullOrBlank()) {
            candidatePackages.add(eventPackage)
        }

        val inspectedRoots = mutableListOf<AccessibilityNodeInfo>()
        try {
            val activeRoot = rootInActiveWindow
            if (activeRoot != null) {
                if (activeRoot.packageName?.toString() != packageName) {
                    inspectedRoots.add(activeRoot)
                } else {
                    recycleSafe(activeRoot)
                }
            }

            val windowList = windows
            if (windowList != null) {
                for (windowInfo in windowList) {
                    val winRoot = windowInfo.root ?: continue
                    val winPkg = winRoot.packageName?.toString()
                    if (winPkg == packageName) {
                        recycleSafe(winRoot)
                        continue
                    }
                    if (!winPkg.isNullOrBlank()) {
                        candidatePackages.add(winPkg)
                    }
                    if (inspectedRoots.none { it == winRoot }) {
                        inspectedRoots.add(winRoot)
                    } else {
                        recycleSafe(winRoot)
                    }
                }
            }

            for (root in inspectedRoots) {
                val rootPkg = root.packageName?.toString()
                if (!rootPkg.isNullOrBlank()) {
                    candidatePackages.add(rootPkg)
                }
                collectAllNodeTexts(root, collectedTexts, parentText = null)
            }
        } finally {
            for (root in inspectedRoots) {
                recycleSafe(root)
            }
        }

        if (collectedTexts.isEmpty()) return

        val matchedPackage = candidatePackages.firstOrNull { FareParser.isTargetRidePackage(it) }
        val keywordMatched = FareParser.isRideScreenByKeywords(collectedTexts)
        if (matchedPackage == null && !keywordMatched) {
            return
        }

        val effectivePackage = matchedPackage ?: candidatePackages.firstOrNull().orEmpty()
        val appLabel = FareParser.resolveAppLabel(effectivePackage, collectedTexts)

        val fare = FareParser.extractFare(collectedTexts)
        val totalDistanceKm = FareParser.extractTotalDistanceKm(collectedTexts)

        val prefs = FarePrefs.get(this)
        val debugEnabled = prefs.getBoolean(FarePrefs.KEY_DEBUG_READER, false)

        if (fare == null || totalDistanceKm == null) {
            if (debugEnabled) {
                Log.d(
                    TAG,
                    "Scanned [$appLabel] pkg=$effectivePackage nodes=${collectedTexts.size} fare=$fare km=$totalDistanceKm"
                )
                showDebugToastThrottled(
                    now,
                    "XFare Debug [$appLabel]: Fare=${fare?.toInt() ?: "?"} | KM=${totalDistanceKm ?: "?"}"
                )
            }
            return
        }

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

        if (debugEnabled) {
            val msg = "XFare Debug [$appLabel]: Rs.${fare.toInt()} | ${
                String.format(java.util.Locale.US, "%.1f", totalDistanceKm)
            }km -> Net Rs.${calculation.netRatePerKm.toInt()}/km"
            Log.d(TAG, msg)
            showDebugToastThrottled(now, msg)
        }

        val signature = "$appLabel|${fare.toInt()}|${(totalDistanceKm * 10).toInt()}"
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

    private fun collectAllNodeTexts(
        node: AccessibilityNodeInfo,
        output: MutableList<String>,
        parentText: String?
    ) {
        val nodeText = node.text?.toString()?.trim()
        if (!nodeText.isNullOrEmpty() && nodeText != parentText) {
            output.add(nodeText)
        }

        val descText = node.contentDescription?.toString()?.trim()
        if (!descText.isNullOrEmpty() && descText != nodeText && descText != parentText) {
            output.add(descText)
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectAllNodeTexts(child, output, nodeText ?: descText)
            } finally {
                recycleSafe(child)
            }
        }
    }

    private fun recycleSafe(node: AccessibilityNodeInfo) {
        try {
            @Suppress("DEPRECATION")
            node.recycle()
        } catch (_: Exception) {
        }
    }

    private fun showDebugToastThrottled(nowMs: Long, message: String) {
        if (nowMs - lastDebugToastAtMs < DEBUG_TOAST_THROTTLE_MS) return
        lastDebugToastAtMs = nowMs
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
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
        private const val TAG = "XFareReader"
        private const val SCAN_THROTTLE_MS = 250L
        private const val OFFER_COOLDOWN_MS = 10000L
        private const val DEBUG_TOAST_THROTTLE_MS = 2500L
    }
}
