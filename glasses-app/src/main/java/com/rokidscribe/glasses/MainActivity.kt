package com.rokidscribe.glasses

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private companion object {
        private const val MAX_LOG_LINES = 3
        private const val RECORDING_FADE_DURATION_MS = 220L
        private const val SWIPE_MIN_DISTANCE_PX = 120f
        private const val SWIPE_DOMINANCE = 1.2f
        private val VISUALIZER_DURATIONS_MS = listOf(300L, 400L, 500L, 400L, 300L)
        private val VISUALIZER_SCALES = listOf(1.1f, 2.3f, 3.4f, 2.5f, 1.4f)
    }

    private enum class SelectionZone {
        RECORDER,
        WIFI,
    }

    private lateinit var hudTitle: TextView
    private lateinit var recordBadge: View
    private lateinit var recordDot: View
    private lateinit var recordTimeText: TextView
    private lateinit var centerStatusText: TextView
    private lateinit var centerSubText: TextView
    private lateinit var progressText: TextView
    private lateinit var transferProgress: ProgressBar
    private lateinit var hintText: TextView
    private lateinit var logText: TextView
    private lateinit var actionContainer: View
    private lateinit var wifiActionButton: TextView
    private lateinit var hudCenter: View
    private lateinit var dividerView: View
    private lateinit var visualizerBars: List<View>

    private lateinit var runtime: GlassesRuntime.Session
    private lateinit var repository: RecordingRepository
    private lateinit var recorder: GlassesRecorder
    private lateinit var bluetoothServer: GlassesBluetoothServer
    private lateinit var gestureDetector: GestureDetector

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val logLines = mutableListOf<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val visualizerAnimators = mutableListOf<ObjectAnimator>()
    private var recordingTickerJob: Job? = null
    private var recordDotPulseAnimator: ObjectAnimator? = null
    private var inlineMessage: String? = null
    private var isTransferInProgress = false
    private var selectionZone = SelectionZone.RECORDER
    private var pendingCountCache = 0

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasAllPermissions()) {
                inlineMessage = null
                startRuntime()
            } else {
                inlineMessage = getString(R.string.status_permissions_missing)
                appendLog(getString(R.string.status_permissions_missing))
                refreshSummary()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        configureWindow()
        bindViews()

        runtime = GlassesRuntime.get(this)
        repository = runtime.repository
        recorder = runtime.recorder
        bluetoothServer = runtime.bluetoothServer
        bluetoothServer.setCallbacks(::handleServerStatus, ::handleTransferProgress)

        gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    onTapAction()
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float,
                ): Boolean {
                    if (e1 == null) return false
                    val deltaX = e2.x - e1.x
                    val deltaY = e2.y - e1.y
                    if (abs(deltaX) < SWIPE_MIN_DISTANCE_PX) return false
                    if (abs(deltaX) < abs(deltaY) * SWIPE_DOMINANCE) return false

                    if (deltaX > 0f) {
                        onSwipeRight()
                    } else {
                        onSwipeLeft()
                    }
                    return true
                }
            },
        )

        appendLog(getString(R.string.log_waiting))
        refreshSummary(immediate = true)

        if (hasAllPermissions()) {
            startRuntime()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    override fun onResume() {
        super.onResume()
        bluetoothServer.setCallbacks(::handleServerStatus, ::handleTransferProgress)
        if (hasAllPermissions()) {
            startRuntime()
        }
        syncRecordingUi()
        refreshSummary(immediate = true)
    }

    override fun onDestroy() {
        recordingTickerJob?.cancel()
        stopRecordDotPulse()
        stopVisualizer()
        uiScope.cancel()
        bluetoothServer.clearCallbacks()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            -> {
                onSwipeLeft()
                true
            }

            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_SPACE,
            -> {
                onTapAction()
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_NAVIGATE_NEXT,
            -> {
                onSwipeRight()
                true
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        updateScreenTimeoutBehavior(recording = false)
    }

    private fun bindViews() {
        hudTitle = findViewById(R.id.hudTitle)
        recordBadge = findViewById(R.id.recordBadge)
        recordDot = findViewById(R.id.recordDot)
        recordTimeText = findViewById(R.id.recordTimeText)
        centerStatusText = findViewById(R.id.centerStatusText)
        centerSubText = findViewById(R.id.centerSubText)
        progressText = findViewById(R.id.progressText)
        transferProgress = findViewById(R.id.transferProgress)
        hintText = findViewById(R.id.hintText)
        logText = findViewById(R.id.logText)
        actionContainer = findViewById(R.id.actionContainer)
        wifiActionButton = findViewById(R.id.wifiActionButton)
        hudCenter = findViewById(R.id.hudCenter)
        dividerView = findViewById(R.id.dividerView)
        visualizerBars =
            listOf(
                findViewById(R.id.visBar1),
                findViewById(R.id.visBar2),
                findViewById(R.id.visBar3),
                findViewById(R.id.visBar4),
                findViewById(R.id.visBar5),
            )
        visualizerBars.forEach { bar ->
            bar.post { bar.pivotY = bar.height.toFloat() }
        }
    }

    private fun startRuntime() {
        bluetoothServer.start()
        inlineMessage = null
        refreshSummary()
    }

    private fun onSwipeLeft() {
        if (recorder.isRecording() || isTransferInProgress) {
            return
        }

        selectionZone = SelectionZone.RECORDER
        refreshSummary()
    }

    private fun onSwipeRight() {
        if (recorder.isRecording() || isTransferInProgress) {
            return
        }

        selectionZone = SelectionZone.WIFI
        refreshSummary()
    }

    private fun onTapAction() {
        if (recorder.isRecording()) {
            toggleRecording()
            return
        }

        if (isTransferInProgress) {
            return
        }

        when (selectionZone) {
            SelectionZone.RECORDER -> toggleRecording()
            SelectionZone.WIFI -> openWifiSettings()
        }
    }

    private fun toggleRecording() {
        if (!hasAllPermissions()) {
            permissionLauncher.launch(requiredPermissions)
            return
        }

        if (recorder.isRecording()) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        runCatching {
            selectionZone = SelectionZone.RECORDER
            recorder.start()
            inlineMessage = null
            isTransferInProgress = false
            progressText.visibility = View.GONE
            transferProgress.visibility = View.GONE
            appendLog(getString(R.string.log_recording_started))
            startRecordingTicker(recorder.recordingStartedAtEpochMs() ?: System.currentTimeMillis())
            refreshSummary()
        }.onFailure { error ->
            inlineMessage = getString(R.string.status_record_failed)
            appendLog("Recording failed: ${error.message}")
            refreshSummary()
        }
    }

    private fun stopRecording() {
        runCatching {
            val descriptor = recorder.stop()
            selectionZone = SelectionZone.RECORDER
            recordingTickerJob?.cancel()
            recordTimeText.text = formatDuration(0L)
            progressText.visibility = View.GONE
            transferProgress.visibility = View.GONE
            isTransferInProgress = false
            inlineMessage = null
            appendLog("Saved ${descriptor.fileName}")
            refreshSummary()
        }.onFailure { error ->
            recordingTickerJob?.cancel()
            recordTimeText.text = formatDuration(0L)
            progressText.visibility = View.GONE
            transferProgress.visibility = View.GONE
            isTransferInProgress = false
            inlineMessage = getString(R.string.status_record_failed)
            appendLog("Stop failed: ${error.message}")
            refreshSummary()
        }
    }

    private fun syncRecordingUi() {
        val startedAt = recorder.recordingStartedAtEpochMs()
        if (startedAt != null) {
            startRecordingTicker(startedAt)
        } else {
            recordingTickerJob?.cancel()
            recordTimeText.text = formatDuration(0L)
        }
    }

    private fun startRecordingTicker(startedAt: Long) {
        recordingTickerJob?.cancel()
        val elapsedAtStart = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        recordTimeText.text = formatDuration(elapsedAtStart)
        recordingTickerJob = uiScope.launch {
            while (isActive && recorder.isRecording()) {
                val elapsedMs = System.currentTimeMillis() - startedAt
                recordTimeText.text = formatDuration(elapsedMs)
                delay(500L)
            }
        }
    }

    private fun handleServerStatus(message: String) {
        runOnUiThread {
            appendLog(message)
            refreshSummary()
        }
    }

    private fun handleTransferProgress(
        sentBytes: Long,
        totalBytes: Long,
    ) {
        runOnUiThread {
            if (sentBytes <= 0L || totalBytes <= 0L) {
                isTransferInProgress = false
                selectionZone = SelectionZone.RECORDER
                progressText.visibility = View.GONE
                transferProgress.visibility = View.GONE
                refreshSummary(refreshPendingCount = true)
                return@runOnUiThread
            }

            val percent = ((sentBytes * 100L) / totalBytes.coerceAtLeast(1L)).toInt()
            isTransferInProgress = true
            progressText.visibility = View.VISIBLE
            progressText.text = "$percent%"
            transferProgress.visibility = View.VISIBLE
            transferProgress.progress = percent
            refreshSummary(refreshPendingCount = false)
        }
    }

    private fun refreshSummary(
        immediate: Boolean = false,
        refreshPendingCount: Boolean = true,
    ) {
        if (refreshPendingCount) {
            pendingCountCache = repository.listPending().size
        }
        val pendingCount = pendingCountCache
        val isRecording = recorder.isRecording()

        if (isRecording) {
            selectionZone = SelectionZone.RECORDER
        }

        centerStatusText.text =
            when {
                isRecording -> getString(R.string.title_recording)
                pendingCount == 0 -> getString(R.string.summary_no_pending)
                else -> resources.getQuantityString(R.plurals.title_pending_count, pendingCount, pendingCount)
            }

        centerSubText.text =
            when {
                isRecording -> ""
                inlineMessage != null -> inlineMessage
                isTransferInProgress -> getString(R.string.summary_importing_phone)
                pendingCount > 0 -> getString(R.string.summary_waiting_import)
                else -> getString(R.string.summary_ready_to_capture)
            }

        hintText.text =
            when {
                isRecording -> getString(R.string.hint_tap_stop)
                selectionZone == SelectionZone.WIFI -> getString(R.string.hint_open_wifi)
                else -> getString(R.string.hint_tap_record)
            }

        actionContainer.visibility = View.VISIBLE
        updateActionUi()
        applyRecordingMode(isRecording, immediate)
    }

    private fun updateActionUi() {
        val isRecording = recorder.isRecording()
        val actionsEnabled = !isRecording && !isTransferInProgress
        val recorderSelected = selectionZone == SelectionZone.RECORDER && actionsEnabled
        val wifiSelected = selectionZone == SelectionZone.WIFI && actionsEnabled

        wifiActionButton.text = getString(R.string.action_open_wifi)
        wifiActionButton.isActivated = wifiSelected
        wifiActionButton.alpha = if (actionsEnabled) 1f else 0.45f

        centerStatusText.alpha =
            when {
                isRecording -> 1f
                recorderSelected -> 1f
                else -> 0.5f
            }
        centerSubText.alpha =
            when {
                isRecording -> 1f
                recorderSelected -> 1f
                else -> 0.55f
            }
        actionContainer.alpha = if (isRecording) 0f else 1f
    }

    private fun applyRecordingMode(
        recording: Boolean,
        immediate: Boolean = false,
    ) {
        updateScreenTimeoutBehavior(recording)
        val duration = if (immediate) 0L else RECORDING_FADE_DURATION_MS
        val titleAlpha = if (recording) 0.1f else 1f
        val centerAlpha = if (recording) 0f else 1f
        val centerScale = if (recording) 0.95f else 1f
        val recordAlpha = if (recording) 1f else 0f
        val bottomAlpha = if (recording) 0f else 1f
        val hintAlpha = if (recording) 0.5f else 1f

        hudTitle.animate()
            .alpha(titleAlpha)
            .setDuration(duration)
            .start()

        recordBadge.animate()
            .alpha(recordAlpha)
            .setDuration(duration)
            .start()

        hudCenter.animate()
            .alpha(centerAlpha)
            .scaleX(centerScale)
            .scaleY(centerScale)
            .setDuration(duration)
            .start()

        dividerView.animate()
            .alpha(bottomAlpha)
            .setDuration(duration)
            .start()

        logText.animate()
            .alpha(bottomAlpha)
            .setDuration(duration)
            .start()

        hintText.animate()
            .alpha(hintAlpha)
            .setDuration(duration)
            .start()

        hintText.setTextColor(
            ContextCompat.getColor(
                this,
                if (recording) {
                    R.color.hud_dim
                } else {
                    R.color.hud_text_mid
                },
            ),
        )

        if (recording) {
            startRecordDotPulse()
            startVisualizer()
        } else {
            stopRecordDotPulse()
            stopVisualizer()
        }
    }

    private fun updateScreenTimeoutBehavior(recording: Boolean) {
        if (recording) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun startRecordDotPulse() {
        if (recordDotPulseAnimator?.isRunning == true) {
            return
        }

        recordDotPulseAnimator =
            ObjectAnimator.ofFloat(recordDot, View.ALPHA, 1f, 0.35f).apply {
                duration = 900L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
                start()
            }
    }

    private fun stopRecordDotPulse() {
        recordDotPulseAnimator?.cancel()
        recordDotPulseAnimator = null
        recordDot.alpha = 1f
    }

    private fun startVisualizer() {
        if (visualizerAnimators.isNotEmpty()) {
            return
        }

        visualizerBars.forEachIndexed { index, bar ->
            bar.pivotY = bar.height.toFloat()
            val animator =
                ObjectAnimator.ofFloat(bar, View.SCALE_Y, 0.8f, VISUALIZER_SCALES[index]).apply {
                    duration = VISUALIZER_DURATIONS_MS[index]
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    startDelay = index * 80L
                    interpolator = LinearInterpolator()
                }
            visualizerAnimators += animator
            animator.start()
        }
    }

    private fun stopVisualizer() {
        visualizerAnimators.forEach { it.cancel() }
        visualizerAnimators.clear()
        visualizerBars.forEach { bar ->
            bar.scaleY = 1f
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions.all(::hasPermission)

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun appendLog(message: String) {
        val timestamp = timeFormat.format(Date())
        val lastMessage = logLines.lastOrNull()?.substringAfter("] ", missingDelimiterValue = "")
        if ((message == getString(R.string.log_waiting) && logLines.isNotEmpty()) || lastMessage == message) {
            return
        }
        logLines.add("[$timestamp] $message")
        while (logLines.size > MAX_LOG_LINES) {
            logLines.removeAt(0)
        }
        logText.text = logLines.joinToString("\n")
    }

    private fun openWifiSettings() {
        appendLog(getString(R.string.log_opening_wifi))

        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(
                    Intent("android.settings.panel.action.WIFI")
                        .setPackage("com.android.settings"),
                )
            }
            add(
                Intent(Settings.ACTION_WIFI_SETTINGS)
                    .setPackage("com.android.settings"),
            )
            add(
                Intent()
                    .setComponent(
                        ComponentName(
                            "com.android.settings",
                            "com.android.settings.Settings\$WifiSettingsActivity",
                        ),
                    ),
            )
            add(Intent(Settings.ACTION_WIFI_SETTINGS))
        }

        for (candidate in candidates) {
            candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(candidate)
                return
            } catch (_: ActivityNotFoundException) {
                // Try next intent.
            } catch (_: SecurityException) {
                // Try next intent.
            } catch (_: RuntimeException) {
                // Try next intent.
            }
        }

        inlineMessage = getString(R.string.status_wifi_unavailable)
        appendLog(getString(R.string.status_wifi_unavailable))
        refreshSummary()
    }
}
