package lk.xfare.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import lk.xfare.app.R

class MainActivity : ComponentActivity() {

    private lateinit var tvOverallBadge: TextView
    private lateinit var tvStatusOverlay: TextView
    private lateinit var tvStatusAccessibility: TextView
    private lateinit var tvStatusService: TextView
    private lateinit var tvStatusBattery: TextView

    private lateinit var etCommission: EditText
    private lateinit var etGreenThreshold: EditText
    private lateinit var etAmberThreshold: EditText
    private lateinit var switchGreenBeep: Switch
    private lateinit var switchDebugReader: Switch

    private var overlayManager: OverlayManager? = null
    private var testSampleIndex: Int = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        KeepAliveService.start(this)
        refreshSystemStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        applyEdgeToEdgeInsets()
        bindViews()
        loadSavedSettings()
        setupListeners()
        registerBackPressMoveToBackground()

        overlayManager = OverlayManager(this)
        KeepAliveService.start(this)
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        KeepAliveService.start(this)
        refreshSystemStatus()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        overlayManager?.destroy()
        overlayManager = null
        super.onDestroy()
    }

    private fun applyEdgeToEdgeInsets() {
        val scrollView = findViewById<ScrollView>(R.id.main_scroll)
        val basePadding = (16 * resources.displayMetrics.density).toInt()
        val container = findViewById<LinearLayout>(R.id.main_container)
        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            container.setPadding(
                basePadding + bars.left,
                basePadding + bars.top,
                basePadding + bars.right,
                basePadding + bars.bottom
            )
            insets
        }
    }

    private fun bindViews() {
        tvOverallBadge = findViewById(R.id.tv_overall_badge)
        tvStatusOverlay = findViewById(R.id.tv_status_overlay)
        tvStatusAccessibility = findViewById(R.id.tv_status_accessibility)
        tvStatusService = findViewById(R.id.tv_status_service)
        tvStatusBattery = findViewById(R.id.tv_status_battery)

        etCommission = findViewById(R.id.et_commission)
        etGreenThreshold = findViewById(R.id.et_green_threshold)
        etAmberThreshold = findViewById(R.id.et_amber_threshold)
        switchGreenBeep = findViewById(R.id.switch_green_beep)
        switchDebugReader = findViewById(R.id.switch_debug_reader)
    }

    private fun loadSavedSettings() {
        val prefs = FarePrefs.get(this)
        etCommission.setText(
            prefs.getFloat(FarePrefs.KEY_COMMISSION, FarePrefs.DEFAULT_COMMISSION).toString()
        )
        etGreenThreshold.setText(
            prefs.getFloat(FarePrefs.KEY_GREEN_THRESHOLD, FarePrefs.DEFAULT_GREEN_THRESHOLD)
                .toString()
        )
        etAmberThreshold.setText(
            prefs.getFloat(FarePrefs.KEY_AMBER_THRESHOLD, FarePrefs.DEFAULT_AMBER_THRESHOLD)
                .toString()
        )
        switchGreenBeep.isChecked = prefs.getBoolean(FarePrefs.KEY_BEEP_ENABLED, true)
        switchDebugReader.isChecked = prefs.getBoolean(FarePrefs.KEY_DEBUG_READER, false)
    }

    private fun setupListeners() {
        switchGreenBeep.setOnCheckedChangeListener { _, isChecked ->
            FarePrefs.get(this).edit()
                .putBoolean(FarePrefs.KEY_BEEP_ENABLED, isChecked)
                .apply()
        }

        switchDebugReader.setOnCheckedChangeListener { _, isChecked ->
            FarePrefs.get(this).edit()
                .putBoolean(FarePrefs.KEY_DEBUG_READER, isChecked)
                .apply()
        }

        findViewById<Button>(R.id.btn_save_settings).setOnClickListener {
            saveFareSettings(showFeedback = true)
        }

        findViewById<Button>(R.id.btn_test_overlay).setOnClickListener {
            saveFareSettings(showFeedback = false)
            triggerTestOverlayBanner()
        }

        findViewById<View>(R.id.btn_overlay_permission).setOnClickListener {
            openOverlayPermissionSettings()
        }

        findViewById<View>(R.id.btn_accessibility_reader).setOnClickListener {
            openAccessibilitySettings()
        }

        findViewById<View>(R.id.btn_battery_saver).setOnClickListener {
            openBatteryOptimizationSettings()
        }

        findViewById<View>(R.id.btn_xiaomi_autostart).setOnClickListener {
            openXiaomiAutostartSettings()
        }

        findViewById<View>(R.id.btn_miui_popup).setOnClickListener {
            openMiuiPopupPermissionSettings()
        }

        findViewById<View>(R.id.btn_dev_email).setOnClickListener {
            openDeveloperEmail()
        }
    }

    private fun registerBackPressMoveToBackground() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    moveTaskToBack(true)
                }
            }
        )
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun saveFareSettings(showFeedback: Boolean): Boolean {
        val commission = etCommission.text.toString().trim().toFloatOrNull()
        val green = etGreenThreshold.text.toString().trim().toFloatOrNull()
        val amber = etAmberThreshold.text.toString().trim().toFloatOrNull()

        if (commission == null || green == null || amber == null ||
            commission < 0f || commission > 90f || amber <= 0f || green < amber
        ) {
            if (showFeedback) {
                Toast.makeText(this, R.string.msg_settings_invalid, Toast.LENGTH_SHORT).show()
            }
            return false
        }

        FarePrefs.get(this).edit()
            .putFloat(FarePrefs.KEY_COMMISSION, commission)
            .putFloat(FarePrefs.KEY_GREEN_THRESHOLD, green)
            .putFloat(FarePrefs.KEY_AMBER_THRESHOLD, amber)
            .putBoolean(FarePrefs.KEY_BEEP_ENABLED, switchGreenBeep.isChecked)
            .putBoolean(FarePrefs.KEY_DEBUG_READER, switchDebugReader.isChecked)
            .apply()

        if (showFeedback) {
            Toast.makeText(this, R.string.msg_settings_saved, Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun triggerTestOverlayBanner() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.msg_grant_overlay_first, Toast.LENGTH_LONG).show()
            openOverlayPermissionSettings()
            return
        }

        val prefs = FarePrefs.get(this)
        val commission = prefs.getFloat(
            FarePrefs.KEY_COMMISSION,
            FarePrefs.DEFAULT_COMMISSION
        ).toDouble()
        val greenThreshold = prefs.getFloat(
            FarePrefs.KEY_GREEN_THRESHOLD,
            FarePrefs.DEFAULT_GREEN_THRESHOLD
        ).toDouble()
        val amberThreshold = prefs.getFloat(
            FarePrefs.KEY_AMBER_THRESHOLD,
            FarePrefs.DEFAULT_AMBER_THRESHOLD
        ).toDouble()

        val sampleDistanceKm = 8.5
        val multiplier = 1.0 - (commission / 100.0)
        val targetRate: Double
        val sourceLabel: String
        val tierName: String

        when (testSampleIndex % 3) {
            0 -> {
                targetRate = greenThreshold + 24.0
                sourceLabel = "PICKME TEST"
                tierName = "Green"
            }
            1 -> {
                targetRate = (amberThreshold + greenThreshold) / 2.0
                sourceLabel = "UBER TEST"
                tierName = "Amber"
            }
            else -> {
                targetRate = (amberThreshold * 0.72).coerceAtLeast(35.0)
                sourceLabel = "HELAGO TEST"
                tierName = "Red"
            }
        }
        testSampleIndex++

        val grossFare = (targetRate * sampleDistanceKm) / multiplier.coerceAtLeast(0.1)
        val calc = FareParser.calculate(grossFare, sampleDistanceKm, commission) ?: return

        overlayManager?.showBanner(
            sourceAppLabel = sourceLabel,
            netRatePerKm = calc.netRatePerKm,
            netPayout = calc.netFare,
            totalDistanceKm = calc.totalDistanceKm
        )

        if (prefs.getBoolean(FarePrefs.KEY_BEEP_ENABLED, true) &&
            calc.netRatePerKm >= greenThreshold
        ) {
            playTestBeep()
        }

        Toast.makeText(
            this,
            getString(R.string.msg_test_banner_shown, tierName),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun playTestBeep() {
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

    private fun refreshSystemStatus() {
        val overlayGranted = Settings.canDrawOverlays(this)
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        val serviceRunning = KeepAliveService.isRunning
        val batteryUnrestricted = isIgnoringBatteryOptimizations()

        val emerald = ContextCompat.getColor(this, R.color.accent_emerald)
        val warning = ContextCompat.getColor(this, R.color.status_warning)
        val danger = ContextCompat.getColor(this, R.color.status_danger)

        tvStatusOverlay.text =
            getString(if (overlayGranted) R.string.status_granted else R.string.status_missing)
        tvStatusOverlay.setTextColor(if (overlayGranted) emerald else danger)

        tvStatusAccessibility.text =
            getString(if (accessibilityEnabled) R.string.status_enabled else R.string.status_disabled)
        tvStatusAccessibility.setTextColor(if (accessibilityEnabled) emerald else danger)

        tvStatusService.text =
            getString(if (serviceRunning) R.string.status_running else R.string.status_stopped)
        tvStatusService.setTextColor(if (serviceRunning) emerald else warning)

        tvStatusBattery.text =
            getString(if (batteryUnrestricted) R.string.status_unrestricted else R.string.status_restricted)
        tvStatusBattery.setTextColor(if (batteryUnrestricted) emerald else warning)

        val ready = overlayGranted && accessibilityEnabled
        tvOverallBadge.text =
            getString(if (ready) R.string.status_active else R.string.status_setup_needed)
        tvOverallBadge.setTextColor(if (ready) emerald else warning)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = ComponentName(this, FareAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val component = splitter.next()
            if (component.equals(expectedComponent, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openOverlayPermissionSettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (_: Exception) {
            openAppDetailsFallback()
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            openAppDetailsFallback()
        }
    }

    private fun openBatteryOptimizationSettings() {
        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                openAppDetailsFallback()
            }
        }
    }

    private fun openXiaomiAutostartSettings() {
        try {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            }
            startActivity(intent)
        } catch (_: Exception) {
            openAppDetailsFallback()
        }
    }

    private fun openMiuiPopupPermissionSettings() {
        try {
            val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                putExtra("extra_pkgname", packageName)
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val genericMiuiIntent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    putExtra("extra_pkgname", packageName)
                }
                startActivity(genericMiuiIntent)
            } catch (_: Exception) {
                openAppDetailsFallback()
            }
        }
    }

    private fun openAppDetailsFallback() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
            Toast.makeText(this, R.string.msg_fallback_app_details, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
        }
    }

    private fun openDeveloperEmail() {
        try {
            val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:rukimw@gmail.com")
                putExtra(Intent.EXTRA_SUBJECT, "X FareLK v2.1 Pro Inquiry")
            }
            startActivity(emailIntent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.msg_no_email_client, Toast.LENGTH_LONG).show()
        }
    }
}
