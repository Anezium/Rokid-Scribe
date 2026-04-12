package com.rokidscribe

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.rokidscribe.spp.RecordingOffer
import com.rokidscribe.spp.SppPacketUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private enum class Screen { HOME, DETAIL, SETTINGS }
    private enum class TransportMode { SPP, WIFI_LAN }
    private enum class ExportFormat { TXT, PDF }
    private data class ScreenMotion(
        val enterX: Float = 0f,
        val enterY: Float = 0f,
        val exitX: Float = 0f,
        val exitY: Float = 0f,
    )

    private val screenInterpolator = FastOutSlowInInterpolator()
    private val screenEnterDurationMs = 300L
    private val screenExitDurationMs = 250L
    private val screenEnterDelayMs = 60L
    private val detailRevealDurationMs = 260L

    // ── Screens ──
    private lateinit var screenHome: View
    private lateinit var screenDetail: View
    private lateinit var screenSettings: View
    private var currentScreen = Screen.HOME

    // ── Home ──
    private lateinit var viewStatusDot: View
    private lateinit var tvDeviceStatus: TextView
    private lateinit var btnSettings: Button
    private lateinit var btnImport: Button
    private lateinit var btnDebugImport: Button
    private lateinit var importProgress: View
    private lateinit var tvImportStatus: TextView
    private lateinit var tvNotesHeader: TextView
    private lateinit var notesScrollView: ScrollView
    private lateinit var notesList: LinearLayout
    private lateinit var emptyState: View
    private lateinit var tvStatusState: TextView
    private lateinit var tvStatusBt: TextView
    private lateinit var phaseViews: List<TextView>
    private var phaseLabels = listOf("PAIR", "QUEUE", "LAN", "SAVE", "OK")

    // ── Detail: full player (no transcript) ──
    private lateinit var playerCardFull: View
    private lateinit var btnPlayFull: Button
    private lateinit var seekBarFull: SeekBar
    private lateinit var tvCurrentTimeFull: TextView
    private lateinit var tvTotalTimeFull: TextView

    // ── Detail: compact player (with transcript) ──
    private lateinit var playerCardCompact: View
    private lateinit var btnPlayCompact: Button
    private lateinit var seekBarCompact: SeekBar
    private lateinit var tvCurrentTimeCompact: TextView
    private lateinit var tvTotalTimeCompact: TextView

    // ── Detail: shared ──
    private lateinit var btnBack: Button
    private lateinit var btnRenameNote: Button
    private lateinit var btnDeleteNote: Button
    private lateinit var tvDetailTitle: TextView
    private lateinit var tvDetailMeta: TextView
    private lateinit var detailBadgeRow: View
    private lateinit var tvDetailProvider: TextView
    private lateinit var tvDetailSpeakers: TextView
    private lateinit var pendingTranscriptState: View
    private lateinit var tvNoTranscript: TextView
    private lateinit var btnTranscribe: Button
    private lateinit var btnRetryWithLanguage: Button
    private lateinit var transcriptScrollView: ScrollView
    private lateinit var tvTranscriptText: TextView
    private lateinit var actionBar: View
    private lateinit var btnCopy: Button
    private lateinit var btnExportTxt: Button
    private lateinit var btnExportPdf: Button

    // ── Settings ──
    private lateinit var btnSettingsBack: Button
    private lateinit var providerSpinner: Spinner
    private lateinit var tvProviderHint: TextView
    private lateinit var tvApiKeyLabel: TextView
    private lateinit var inputApiKey: EditText
    private lateinit var tvApiKeyHint: TextView
    private lateinit var transportSpinner: Spinner
    private lateinit var tvTransportHint: TextView
    private lateinit var btnQuotaRefresh: Button
    private lateinit var tvQuotaSummary: TextView
    private lateinit var tvQuotaFootnote: TextView
    private lateinit var quotaRowsContainer: LinearLayout
    private lateinit var deepgramUsageConfig: View
    private lateinit var inputDeepgramProjectId: EditText
    private lateinit var tvDeepgramProjectHint: TextView

    // ── Services ──
    private lateinit var phoneBluetoothClient: PhoneBluetoothClient
    private lateinit var recordingStore: PhoneRecordingStore
    private lateinit var settingsStore: ScribeSettingsStore
    private lateinit var elevenLabsClient: ElevenLabsTranscriptionClient
    private lateinit var assemblyAiClient: AssemblyAiTranscriptionClient
    private lateinit var speechmaticsClient: SpeechmaticsTranscriptionClient
    private lateinit var deepgramClient: DeepgramTranscriptionClient
    private lateinit var groqClient: GroqTranscriptionClient
    private lateinit var transcriptExportManager: TranscriptExportManager
    private lateinit var queueProbeSession: QueueProbeSession
    private lateinit var sppImportSession: SppRecordingImportSession
    private lateinit var wifiImportSession: WifiLanRecordingImportSession

    // ── State ──
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var localActionJob: Job? = null
    private var quotaRefreshJob: Job? = null
    private var currentTranscriptProvider = TranscriptProvider.ELEVENLABS
    private var currentMode = TransportMode.WIFI_LAN
    private var discoveredDevices: List<BluetoothDevice> = emptyList()
    private var localRecordings: List<LocalRecording> = emptyList()
    private var selectedRecording: LocalRecording? = null
    private var selectedRecordingPath: String? = null
    private var selectedDeviceAddress: String? = null
    private var pendingAction: (() -> Unit)? = null
    private var isBusy = false
    private var currentPhase = -1
    private var dotPulseAnimator: ObjectAnimator? = null
    private val importLogHistory = mutableListOf<String>()


    // ── Audio player ──
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false
    private val playerHandler = Handler(Looper.getMainLooper())
    private val playerUpdateRunnable = object : Runnable {
        override fun run() {
            if (mediaPlayer == null || !isPlaying) return
            updatePlayerProgress()
            if (mediaPlayer != null && isPlaying) {
                playerHandler.postDelayed(this, 500)
            }
        }
    }

    // Currently active player views (full or compact)
    private var activePlayBtn: Button? = null
    private var activeSeekBar: SeekBar? = null
    private var activeCurrentTime: TextView? = null
    private var activeTotalTime: TextView? = null
    private var isUserSeeking = false
    private var playbackCompleted = false

    private val requiredPermissions: Array<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (requiredPermissions.all { grants[it] == true || hasPermission(it) }) {
                consumePendingAction()
            } else {
                Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
            }
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (isBluetoothEnabled()) consumePendingAction()
            else Toast.makeText(this, R.string.enable_bluetooth, Toast.LENGTH_SHORT).show()
        }

    private val debugImportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) {
                return@registerForActivityResult
            }
            importDebugFiles(uris)
        }

    // ═══════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        initServices()
        currentTranscriptProvider = loadSavedTranscriptProvider()
        currentMode = loadSavedTransportMode()
        setupListeners()
        setupBackNavigation()

        selectedDeviceAddress = settingsStore.getSelectedDeviceAddress().takeIf { it.isNotBlank() }
        refreshPairedDevices()
        refreshModeUi()
        refreshLocalLibrary()
        refreshStatusBar()
    }

    override fun onResume() {
        super.onResume()
        refreshPairedDevices()
        if (currentScreen == Screen.DETAIL && selectedRecording != null) {
            if (!renderDetailScreen()) {
                navigateTo(Screen.HOME)
            }
        }
    }

    override fun onPause() {
        persistApiKey()
        super.onPause()
    }

    override fun onDestroy() {
        dotPulseAnimator?.cancel()
        quotaRefreshJob?.cancel()
        releasePlayer()
        queueProbeSession.cleanup()
        sppImportSession.cleanup()
        wifiImportSession.cleanup()
        localActionJob?.cancel()
        activityScope.cancel()
        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════════════════
    // View binding
    // ═══════════════════════════════════════════════════════════════

    private fun bindViews() {
        screenHome = findViewById(R.id.screenHome)
        screenDetail = findViewById(R.id.screenDetail)
        screenSettings = findViewById(R.id.screenSettings)

        // Home
        viewStatusDot = findViewById(R.id.viewStatusDot)
        tvDeviceStatus = findViewById(R.id.tvDeviceStatus)
        btnSettings = findViewById(R.id.btnSettings)
        btnImport = findViewById(R.id.btnImport)
        btnDebugImport = findViewById(R.id.btnDebugImport)
        importProgress = findViewById(R.id.importProgress)
        tvImportStatus = findViewById(R.id.tvImportStatus)
        tvNotesHeader = findViewById(R.id.tvNotesHeader)
        notesScrollView = findViewById(R.id.notesScrollView)
        notesList = findViewById(R.id.notesList)
        emptyState = findViewById(R.id.emptyState)
        tvStatusState = findViewById(R.id.tvStatusState)
        tvStatusBt = findViewById(R.id.tvStatusBt)
        importProgress.visibility = View.GONE
        phaseViews = listOf(
            findViewById(R.id.phasePair),
            findViewById(R.id.phaseQueue),
            findViewById(R.id.phaseTransfer),
            findViewById(R.id.phaseSave),
            findViewById(R.id.phaseDone),
        )

        // Detail: full player
        playerCardFull = findViewById(R.id.playerCardFull)
        btnPlayFull = findViewById(R.id.btnPlayFull)
        seekBarFull = findViewById(R.id.seekBarFull)
        tvCurrentTimeFull = findViewById(R.id.tvCurrentTimeFull)
        tvTotalTimeFull = findViewById(R.id.tvTotalTimeFull)

        // Detail: compact player
        playerCardCompact = findViewById(R.id.playerCardCompact)
        btnPlayCompact = findViewById(R.id.btnPlayCompact)
        seekBarCompact = findViewById(R.id.seekBarCompact)
        tvCurrentTimeCompact = findViewById(R.id.tvCurrentTimeCompact)
        tvTotalTimeCompact = findViewById(R.id.tvTotalTimeCompact)

        // Detail: shared
        btnBack = findViewById(R.id.btnBack)
        btnRenameNote = findViewById(R.id.btnRenameNote)
        btnDeleteNote = findViewById(R.id.btnDeleteNote)
        tvDetailTitle = findViewById(R.id.tvDetailTitle)
        tvDetailMeta = findViewById(R.id.tvDetailMeta)
        detailBadgeRow = findViewById(R.id.detailBadgeRow)
        tvDetailProvider = findViewById(R.id.tvDetailProvider)
        tvDetailSpeakers = findViewById(R.id.tvDetailSpeakers)
        pendingTranscriptState = findViewById(R.id.pendingTranscriptState)
        tvNoTranscript = findViewById(R.id.tvNoTranscript)
        btnTranscribe = findViewById(R.id.btnTranscribe)
        btnRetryWithLanguage = findViewById(R.id.btnRetryWithLanguage)
        transcriptScrollView = findViewById(R.id.transcriptScrollView)
        tvTranscriptText = findViewById(R.id.tvTranscriptText)
        actionBar = findViewById(R.id.actionBar)
        btnCopy = findViewById(R.id.btnCopy)
        btnExportTxt = findViewById(R.id.btnExportTxt)
        btnExportPdf = findViewById(R.id.btnExportPdf)

        // Settings
        btnSettingsBack = findViewById(R.id.btnSettingsBack)
        providerSpinner = findViewById(R.id.providerSpinner)
        tvProviderHint = findViewById(R.id.tvProviderHint)
        tvApiKeyLabel = findViewById(R.id.tvApiKeyLabel)
        inputApiKey = findViewById(R.id.inputApiKey)
        tvApiKeyHint = findViewById(R.id.tvApiKeyHint)
        transportSpinner = findViewById(R.id.transportSpinner)
        tvTransportHint = findViewById(R.id.tvTransportHint)
        btnQuotaRefresh = findViewById(R.id.btnQuotaRefresh)
        tvQuotaSummary = findViewById(R.id.tvQuotaSummary)
        tvQuotaFootnote = findViewById(R.id.tvQuotaFootnote)
        quotaRowsContainer = findViewById(R.id.quotaRowsContainer)
        deepgramUsageConfig = findViewById(R.id.deepgramUsageConfig)
        inputDeepgramProjectId = findViewById(R.id.inputDeepgramProjectId)
        tvDeepgramProjectHint = findViewById(R.id.tvDeepgramProjectHint)

        dotPulseAnimator = ObjectAnimator.ofFloat(viewStatusDot, "alpha", 1f, 0.15f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }
    }

    private fun initServices() {
        phoneBluetoothClient = PhoneBluetoothClient(this)
        recordingStore = PhoneRecordingStore(this)
        settingsStore = ScribeSettingsStore(this)
        elevenLabsClient = ElevenLabsTranscriptionClient()
        assemblyAiClient = AssemblyAiTranscriptionClient()
        speechmaticsClient = SpeechmaticsTranscriptionClient()
        deepgramClient = DeepgramTranscriptionClient()
        groqClient = GroqTranscriptionClient()
        transcriptExportManager = TranscriptExportManager(this)
        queueProbeSession = QueueProbeSession(this, ::updateStatus, ::setBusy)
        sppImportSession = SppRecordingImportSession(this, ::updateStatus, ::setBusy)
        wifiImportSession = WifiLanRecordingImportSession(this, ::updateStatus, ::setBusy)
    }

    private fun setupListeners() {
        // Home
        btnSettings.setOnClickListener { navigateTo(Screen.SETTINGS) }
        btnImport.setOnClickListener { startImport() }
        btnDebugImport.setOnClickListener { startDebugImportPicker() }
        tvDeviceStatus.setOnClickListener { showDeviceChooserIfNeeded() }

        // Detail
        btnBack.setOnClickListener { navigateTo(Screen.HOME) }
        btnRenameNote.setOnClickListener { showRenameNoteDialog() }
        btnDeleteNote.setOnClickListener { confirmDeleteNote() }
        btnPlayFull.setOnClickListener { togglePlayPause() }
        btnPlayCompact.setOnClickListener { togglePlayPause() }
        btnTranscribe.setOnClickListener { transcribeCurrentNote() }
        btnRetryWithLanguage.setOnClickListener { showLanguageRetryChooser() }
        btnCopy.setOnClickListener { copyTranscriptToClipboard() }
        btnExportTxt.setOnClickListener { exportTranscript(ExportFormat.TXT) }
        btnExportPdf.setOnClickListener { exportTranscript(ExportFormat.PDF) }

        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.let { player ->
                        val pos = (progress.toLong() * player.duration / 100).toInt()
                        player.seekTo(pos)
                        playbackCompleted = false
                        activeCurrentTime?.text = formatDuration(pos.toLong())
                    }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                isUserSeeking = true
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isUserSeeking = false
                updatePlayerProgress(force = true)
            }
        }
        seekBarFull.setOnSeekBarChangeListener(seekListener)
        seekBarCompact.setOnSeekBarChangeListener(seekListener)

        // Settings
        btnSettingsBack.setOnClickListener {
            persistApiKey()
            navigateTo(Screen.HOME)
        }
        btnQuotaRefresh.setOnClickListener { refreshQuotaCard() }
        setupTranscriptProviderSpinner()
        setupTransportSpinner()
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (currentScreen) {
                    Screen.DETAIL -> navigateTo(Screen.HOME)
                    Screen.SETTINGS -> { persistApiKey(); navigateTo(Screen.HOME) }
                    Screen.HOME -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })
    }

    // ═══════════════════════════════════════════════════════════════
    // Navigation
    // ═══════════════════════════════════════════════════════════════

    private fun navigateTo(screen: Screen) {
        val fromScreen = currentScreen
        if (screen == fromScreen) {
            when (screen) {
                Screen.HOME -> refreshLocalLibrary()
                Screen.DETAIL -> if (!renderDetailScreen()) {
                    selectedRecording = null
                    selectedRecordingPath = null
                    navigateTo(Screen.HOME)
                }
                Screen.SETTINGS -> refreshQuotaCard()
            }
            return
        }

        if (screen != Screen.DETAIL) releasePlayer()

        when (screen) {
            Screen.HOME -> refreshLocalLibrary()
            Screen.DETAIL -> {
                if (!renderDetailScreen()) {
                    selectedRecording = null
                    selectedRecordingPath = null
                    navigateTo(Screen.HOME)
                    return
                }
            }
            Screen.SETTINGS -> Unit
        }

        currentScreen = screen
        refreshDetailActionButtons()
        animateScreenNavigation(fromScreen, screen)
        if (screen == Screen.SETTINGS) {
            refreshQuotaCard()
        }
    }

    private fun animateScreenNavigation(fromScreen: Screen, toScreen: Screen) {
        val fromView = screenViewFor(fromScreen)
        val toView = screenViewFor(toScreen)

        if (fromView === toView) {
            showOnlyScreen(toView)
            return
        }

        listOf(screenHome, screenDetail, screenSettings).forEach { view ->
            view.animate().cancel()
            view.clearAnimation()
            if (view !== fromView && view !== toView) {
                resetScreenTransform(view)
                view.visibility = View.GONE
            }
        }

        val motion = screenMotion(fromScreen, toScreen)
        fromView.visibility = View.VISIBLE
        resetScreenTransform(fromView)

        toView.apply {
            visibility = View.VISIBLE
            alpha = 0f
            translationX = motion.enterX
            translationY = motion.enterY
            bringToFront()
        }

        fromView.animate()
            .alpha(0f)
            .translationX(motion.exitX)
            .translationY(motion.exitY)
            .setDuration(screenExitDurationMs)
            .setInterpolator(screenInterpolator)
            .withEndAction {
                resetScreenTransform(fromView)
                fromView.visibility = View.GONE
            }
            .start()

        toView.animate()
            .alpha(1f)
            .translationX(0f)
            .translationY(0f)
            .setStartDelay(screenEnterDelayMs)
            .setDuration(screenEnterDurationMs)
            .setInterpolator(screenInterpolator)
            .start()
    }

    private fun screenViewFor(screen: Screen): View = when (screen) {
        Screen.HOME -> screenHome
        Screen.DETAIL -> screenDetail
        Screen.SETTINGS -> screenSettings
    }

    private fun showOnlyScreen(visibleScreen: View) {
        listOf(screenHome, screenDetail, screenSettings).forEach { view ->
            view.animate().cancel()
            resetScreenTransform(view)
            view.visibility = if (view === visibleScreen) View.VISIBLE else View.GONE
        }
    }

    private fun screenMotion(fromScreen: Screen, toScreen: Screen): ScreenMotion {
        val shift = dp(18f)
        return when {
            fromScreen == Screen.HOME && toScreen == Screen.DETAIL ->
                ScreenMotion(enterX = shift, exitX = -shift)
            fromScreen == Screen.DETAIL && toScreen == Screen.HOME ->
                ScreenMotion(enterX = -shift, exitX = shift)
            fromScreen == Screen.HOME && toScreen == Screen.SETTINGS ->
                ScreenMotion(enterY = shift, exitY = -shift)
            fromScreen == Screen.SETTINGS && toScreen == Screen.HOME ->
                ScreenMotion(enterY = -shift, exitY = shift)
            else ->
                ScreenMotion(enterX = shift, exitX = -shift)
        }
    }

    private fun resetScreenTransform(view: View) {
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
    }

    private fun animateSectionEntrance(view: View, startOffsetDp: Float) {
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = dp(startOffsetDp)
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay((startOffsetDp * 2).toLong())
            .setDuration(detailRevealDurationMs)
            .setInterpolator(screenInterpolator)
            .start()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    // ═══════════════════════════════════════════════════════════════
    // Home screen
    // ═══════════════════════════════════════════════════════════════

    private fun refreshLocalLibrary() {
        localRecordings = recordingStore.listRecordings()
        notesList.removeAllViews()

        if (localRecordings.isEmpty()) {
            notesScrollView.visibility = View.GONE
            tvNotesHeader.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
        } else {
            emptyState.visibility = View.GONE
            tvNotesHeader.visibility = View.VISIBLE
            notesScrollView.visibility = View.VISIBLE
            tvNotesHeader.text = getString(R.string.notes_header)

            for (recording in localRecordings) {
                addNoteCard(recording)
            }
        }

        refreshDeviceStatusBar()
    }

    private fun addNoteCard(recording: LocalRecording) {
        val card = LayoutInflater.from(this).inflate(R.layout.item_note_card, notesList, false)

        val accent = card.findViewById<View>(R.id.noteAccent)
        val title = card.findViewById<TextView>(R.id.noteTitle)
        val duration = card.findViewById<TextView>(R.id.noteDuration)
        val date = card.findViewById<TextView>(R.id.noteDate)
        val status = card.findViewById<TextView>(R.id.noteStatus)
        val provider = card.findViewById<TextView>(R.id.noteProvider)
        val speakers = card.findViewById<TextView>(R.id.noteSpeakers)

        title.text = recording.displayTitle
        duration.text = formatDuration(recording.durationMs)
        date.text = formatRelativeDate(recording.createdAtEpochMs)

        if (recording.transcript != null) {
            status.text = "TRANSCRIBED"
            status.setTextColor(ContextCompat.getColor(this, R.color.phosphor_primary))
            status.setBackgroundResource(R.drawable.bg_badge_active)
            provider.text = compactProviderLabel(recording.transcript.providerId)
            provider.visibility = View.VISIBLE
            recording.transcript.speakerBadgeText()?.let { badge ->
                speakers.text = badge
                speakers.visibility = View.VISIBLE
            } ?: run {
                speakers.visibility = View.GONE
            }
            accent.setBackgroundColor(ContextCompat.getColor(this, R.color.phosphor_dim))
        } else {
            status.text = "PENDING"
            status.setTextColor(ContextCompat.getColor(this, R.color.phosphor_text_mid))
            status.setBackgroundResource(R.drawable.bg_badge)
            provider.visibility = View.GONE
            speakers.visibility = View.GONE
            accent.setBackgroundColor(ContextCompat.getColor(this, R.color.phosphor_stroke))
        }

        card.setOnClickListener {
            selectedRecording = recording
            selectedRecordingPath = recording.metadataPath
            navigateTo(Screen.DETAIL)
        }

        notesList.addView(card)
    }

    private fun refreshDeviceStatusBar() {
        val device = currentSelectedDevice()
        if (device != null) {
            val name = device.name?.takeIf { it.isNotBlank() } ?: "GLASSES"
            val mode = if (currentMode == TransportMode.WIFI_LAN) "LAN" else "BT"
            val suffix = if (discoveredDevices.size > 1) getString(R.string.device_switch_hint) else ""
            tvDeviceStatus.text = getString(R.string.device_connected, name.uppercase(), mode) + suffix
            tvDeviceStatus.setTextColor(ContextCompat.getColor(this, R.color.phosphor_text_mid))
        } else {
            tvDeviceStatus.text = getString(R.string.device_none)
            tvDeviceStatus.setTextColor(ContextCompat.getColor(this, R.color.phosphor_text_ghost))
        }
    }

    private fun showDeviceChooserIfNeeded() {
        if (discoveredDevices.isEmpty() || isBusy) {
            return
        }

        val labels = discoveredDevices.map { device ->
            val name = device.name?.takeIf { it.isNotBlank() } ?: getString(R.string.unknown_device_short)
            "$name (${device.address})"
        }
        val selectedIndex = discoveredDevices.indexOfFirst { it.address == currentSelectedDevice()?.address }
            .takeIf { it >= 0 }
            ?: 0

        AlertDialog.Builder(this, R.style.PhosphorDialog)
            .setTitle(R.string.device_picker_title)
            .setSingleChoiceItems(labels.toTypedArray(), selectedIndex) { dialog, which ->
                rememberSelectedDevice(discoveredDevices[which])
                refreshDeviceStatusBar()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun rememberSelectedDevice(device: BluetoothDevice) {
        selectedDeviceAddress = device.address
        settingsStore.setSelectedDeviceAddress(device.address)
    }

    // ═══════════════════════════════════════════════════════════════
    // Import
    // ═══════════════════════════════════════════════════════════════

    private fun startImport() {
        phaseLabels = when (currentMode) {
            TransportMode.SPP -> listOf("PAIR", "QUEUE", "BT", "SAVE", "OK")
            TransportMode.WIFI_LAN -> listOf("PAIR", "QUEUE", "LAN", "SAVE", "OK")
        }
        runWithPrerequisites {
            resetPhases()
            refreshPairedDevices()
            val device = currentSelectedDevice() ?: run {
                if (discoveredDevices.isEmpty()) {
                    Toast.makeText(this, R.string.select_device, Toast.LENGTH_LONG).show()
                } else {
                    showDeviceChooserIfNeeded()
                }
                return@runWithPrerequisites
            }
            rememberSelectedDevice(device)
            when (currentMode) {
                TransportMode.SPP -> sppImportSession.importAllPending(device)
                TransportMode.WIFI_LAN -> wifiImportSession.importAllPending(device)
            }
        }
    }

    private fun startDebugImportPicker() {
        if (isBusy || localActionJob != null) {
            return
        }
        phaseLabels = listOf("LOAD", "META", "COPY", "SAVE", "OK")
        resetPhases()
        debugImportLauncher.launch(arrayOf("audio/*", "video/*"))
    }

    private fun updateStatus(status: String) {
        importLogHistory.add("> $status")
        if (importLogHistory.size > 8) {
            importLogHistory.removeAt(0)
        }
        tvImportStatus.text = importLogHistory.joinToString("\n")
        inferPhase(status)
    }

    private fun inferPhase(status: String) {
        val lower = status.lowercase()
        val phase = when {
            lower.contains("import complete") -> 4
            lower.contains("saved ") || lower.contains("saved on the phone") -> 3
            lower.contains("wi-fi") || lower.contains("bluetooth") || lower.contains("importing") || lower.contains("copying") -> 2
            lower.contains("pending") || lower.contains("queue") || lower.contains("metadata") || lower.contains("reading") -> 1
            lower.contains("connecting") || lower.contains("loaded") -> 0
            else -> return
        }
        if (phase >= currentPhase) setPhase(phase)
    }

    private fun setPhase(index: Int) {
        currentPhase = index
        val ghost = ContextCompat.getColor(this, R.color.phosphor_text_ghost)
        val primary = ContextCompat.getColor(this, R.color.phosphor_primary)
        val mid = ContextCompat.getColor(this, R.color.phosphor_text_mid)

        for (i in phaseViews.indices) {
            val label = phaseLabels[i]
            when {
                i < index -> { phaseViews[i].text = "[x] $label"; phaseViews[i].setTextColor(mid) }
                i == index -> { phaseViews[i].text = "[>] $label"; phaseViews[i].setTextColor(primary) }
                else -> { phaseViews[i].text = "[ ] $label"; phaseViews[i].setTextColor(ghost) }
            }
        }
    }

    private fun resetPhases() {
        currentPhase = -1
        val ghost = ContextCompat.getColor(this, R.color.phosphor_text_ghost)
        for (i in phaseViews.indices) {
            phaseViews[i].text = "[ ] ${phaseLabels[i]}"
            phaseViews[i].setTextColor(ghost)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Detail screen
    // ═══════════════════════════════════════════════════════════════

    private fun currentRecordingOrNull(): LocalRecording? {
        val currentPath = selectedRecordingPath ?: selectedRecording?.metadataPath ?: return null
        val fresh = recordingStore.findRecordingByMetadataPath(currentPath)
        selectedRecording = fresh
        selectedRecordingPath = fresh?.metadataPath
        return fresh
    }

    private fun defaultRecordingTitle(recording: LocalRecording): String =
        recording.sourceFileName.substringBeforeLast('.', recording.sourceFileName)

    private fun renderDetailScreen(animateContent: Boolean = false): Boolean {
        val fresh = currentRecordingOrNull() ?: run {
            return false
        }

        releasePlayer(clearActiveViews = false)

        tvDetailTitle.text = fresh.displayTitle
        tvDetailMeta.text = getString(
            R.string.note_meta,
            fresh.sourceFileName,
            formatSize(fresh.sizeBytes),
        )
        val transcriptProviderId = fresh.transcript?.providerId
        val speakerBadge = fresh.transcript?.speakerBadgeText()
        if (transcriptProviderId != null) {
            tvDetailProvider.text = detailProviderLabel(transcriptProviderId)
            tvDetailProvider.visibility = View.VISIBLE
        } else {
            tvDetailProvider.visibility = View.GONE
        }
        if (speakerBadge != null) {
            tvDetailSpeakers.text = speakerBadge
            tvDetailSpeakers.visibility = View.VISIBLE
        } else {
            tvDetailSpeakers.visibility = View.GONE
        }
        detailBadgeRow.visibility =
            if (transcriptProviderId != null || speakerBadge != null) View.VISIBLE else View.GONE

        val hasTranscript = fresh.transcript != null
        val transcriptStateChanged = hasTranscript != (transcriptScrollView.visibility == View.VISIBLE)

        // Toggle player layout
        if (hasTranscript) {
            playerCardFull.visibility = View.GONE
            playerCardCompact.visibility = View.VISIBLE
            activePlayBtn = btnPlayCompact
            activeSeekBar = seekBarCompact
            activeCurrentTime = tvCurrentTimeCompact
            activeTotalTime = tvTotalTimeCompact
        } else {
            playerCardFull.visibility = View.VISIBLE
            playerCardCompact.visibility = View.GONE
            activePlayBtn = btnPlayFull
            activeSeekBar = seekBarFull
            activeCurrentTime = tvCurrentTimeFull
            activeTotalTime = tvTotalTimeFull
        }

        activeTotalTime?.text = formatDuration(fresh.durationMs)
        activeCurrentTime?.text = getString(R.string.time_zero)
        activeSeekBar?.progress = 0
        activePlayBtn?.text = getString(R.string.btn_play)

        // Transcript state
        if (hasTranscript) {
            pendingTranscriptState.visibility = View.GONE
            transcriptScrollView.visibility = View.VISIBLE
            tvTranscriptText.text = fresh.transcript!!.displayText()
            actionBar.visibility = View.VISIBLE
            btnRetryWithLanguage.visibility = View.GONE
        } else {
            pendingTranscriptState.visibility = View.VISIBLE
            transcriptScrollView.visibility = View.GONE
            actionBar.visibility = View.GONE
            renderPendingTranscriptState(fresh)
        }

        preparePlayer(fresh)
        refreshDetailActionButtons()
        if (animateContent && transcriptStateChanged) {
            if (hasTranscript) {
                animateSectionEntrance(playerCardCompact, 14f)
                animateSectionEntrance(transcriptScrollView, 20f)
                animateSectionEntrance(actionBar, 24f)
            } else {
                animateSectionEntrance(playerCardFull, 14f)
                animateSectionEntrance(pendingTranscriptState, 20f)
            }
        }
        return true
    }

    private fun renderPendingTranscriptState(recording: LocalRecording) {
        val issue = recording.transcriptIssue
            ?.takeIf { it.providerId == currentTranscriptProvider.name }

        if (issue?.kind == TranscriptIssueKind.LANGUAGE_DETECTION) {
            tvNoTranscript.text = buildString {
                append(getString(R.string.no_transcript_language_issue, providerDisplayName(currentTranscriptProvider)))
                issue.requestedLanguageLabel?.let { label ->
                    append("\n\n> LAST TRY: ")
                    append(label.uppercase(Locale.getDefault()))
                }
                if (issue.message.isNotBlank()) {
                    append("\n> ")
                    append(issue.message)
                }
            }
            btnRetryWithLanguage.visibility = View.VISIBLE
            btnRetryWithLanguage.text = getString(R.string.btn_retry_language)
        } else {
            tvNoTranscript.text = getString(R.string.no_transcript)
            btnRetryWithLanguage.visibility = View.GONE
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Audio player
    // ═══════════════════════════════════════════════════════════════

    private fun preparePlayer(recording: LocalRecording) {
        val file = File(recording.localAudioPath)
        if (!file.exists()) {
            btnPlayFull.isEnabled = false
            btnPlayCompact.isEnabled = false
            activeCurrentTime?.text = getString(R.string.time_zero)
            activeTotalTime?.text = formatDuration(recording.durationMs)
            return
        }

        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                FileInputStream(file).use { input ->
                    setDataSource(input.fd)
                    prepare()
                }
            }
            playbackCompleted = false
            isUserSeeking = false
            player.setOnCompletionListener {
                this.isPlaying = false
                playbackCompleted = true
                activePlayBtn?.text = getString(R.string.btn_play)
                playerHandler.removeCallbacks(playerUpdateRunnable)
                activeSeekBar?.progress = 100
                activeCurrentTime?.text = formatDuration(player.duration.toLong())
            }
            player.setOnErrorListener { _, _, _ ->
                releasePlayer()
                btnPlayFull.isEnabled = false
                btnPlayCompact.isEnabled = false
                Toast.makeText(this, R.string.player_error, Toast.LENGTH_SHORT).show()
                true
            }
            mediaPlayer = player
            btnPlayFull.isEnabled = true
            btnPlayCompact.isEnabled = true
            activeSeekBar?.progress = 0
            activeCurrentTime?.text = getString(R.string.time_zero)
            activeTotalTime?.text = formatDuration(player.duration.toLong())
        } catch (e: Exception) {
            btnPlayFull.isEnabled = false
            btnPlayCompact.isEnabled = false
            Toast.makeText(this, R.string.player_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun togglePlayPause() {
        val player = mediaPlayer ?: return
        if (isPlaying) {
            player.pause()
            isPlaying = false
            activePlayBtn?.text = getString(R.string.btn_play)
            playerHandler.removeCallbacks(playerUpdateRunnable)
        } else {
            val duration = player.duration.coerceAtLeast(0)
            if (playbackCompleted || (duration > 0 && player.currentPosition >= duration - 500)) {
                player.seekTo(0)
                activeSeekBar?.progress = 0
                activeCurrentTime?.text = getString(R.string.time_zero)
                playbackCompleted = false
            }
            player.start()
            isPlaying = true
            activePlayBtn?.text = getString(R.string.btn_pause)
            playerHandler.post(playerUpdateRunnable)
        }
    }

    private fun updatePlayerProgress(force: Boolean = false) {
        val player = mediaPlayer ?: return
        if (!force && (!isPlaying || isUserSeeking)) return
        val pos = player.currentPosition
        val dur = player.duration.coerceAtLeast(1)
        activeSeekBar?.progress = (pos.toLong() * 100 / dur).toInt()
        activeCurrentTime?.text = formatDuration(pos.toLong())
    }

    private fun releasePlayer(clearActiveViews: Boolean = true) {
        playerHandler.removeCallbacks(playerUpdateRunnable)
        isPlaying = false
        isUserSeeking = false
        playbackCompleted = false
        mediaPlayer?.release()
        mediaPlayer = null
        if (clearActiveViews) {
            activePlayBtn = null
            activeSeekBar = null
            activeCurrentTime = null
            activeTotalTime = null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Transcription
    // ═══════════════════════════════════════════════════════════════

    private fun showRenameNoteDialog() {
        if (isBusy || localActionJob != null) return
        val recording = currentRecordingOrNull() ?: return
        val input = EditText(this).apply {
            hint = getString(R.string.rename_note_hint)
            setSingleLine()
            inputType =
                android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(recording.displayName ?: recording.displayTitle)
            setSelection(text.length)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(20f).toInt(),
                dp(8f).toInt(),
                dp(20f).toInt(),
                0,
            )
            addView(
                input,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        AlertDialog.Builder(this, R.style.PhosphorDialog)
            .setTitle(R.string.rename_note_title)
            .setMessage(R.string.rename_note_message)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val requestedTitle = input.text.toString().trim()
                val nextDisplayName = requestedTitle
                    .takeIf { it.isNotBlank() }
                    ?.takeUnless { it == defaultRecordingTitle(recording) }
                renameNote(recording, nextDisplayName)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renameNote(
        recording: LocalRecording,
        displayName: String?,
    ) {
        val currentDisplayName = recording.displayName?.trim()?.takeIf { it.isNotBlank() }
        if (currentDisplayName == displayName) {
            return
        }

        localActionJob = activityScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    recordingStore.updateRecordingDisplayName(recording, displayName)
                }
                selectedRecording = updated
                selectedRecordingPath = updated.metadataPath
                tvDetailTitle.text = updated.displayTitle
                refreshLocalLibrary()
                Toast.makeText(
                    this@MainActivity,
                    if (updated.displayName != null) {
                        R.string.rename_note_saved
                    } else {
                        R.string.rename_note_reset
                    },
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (error: Exception) {
                Log.e("RokidScribe", "Rename note failed", error)
                Toast.makeText(this@MainActivity, R.string.rename_note_failed, Toast.LENGTH_LONG).show()
            } finally {
                localActionJob = null
                refreshDetailActionButtons()
            }
        }
        refreshDetailActionButtons()
    }

    private fun showLanguageRetryChooser() {
        if (isBusy || localActionJob != null) return
        currentRecordingOrNull() ?: return

        val options = ManualTranscriptLanguage.entries.toTypedArray()
        AlertDialog.Builder(this, R.style.PhosphorDialog)
            .setTitle(R.string.language_retry_title)
            .setItems(options.map(ManualTranscriptLanguage::label).toTypedArray()) { _, which ->
                transcribeCurrentNote(options[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun transcribeCurrentNote(retryLanguage: ManualTranscriptLanguage? = null) {
        if (isBusy || localActionJob != null) return
        val recording = currentRecordingOrNull() ?: return
        val provider = currentTranscriptProvider
        val apiKey = inputApiKey.text.toString().trim()
        if (apiKey.isBlank()) {
            Toast.makeText(this, R.string.missing_api_key, Toast.LENGTH_LONG).show()
            navigateTo(Screen.SETTINGS)
            return
        }

        persistApiKey(apiKey)
        setBusy(true)
        btnTranscribe.isEnabled = false
        btnRetryWithLanguage.isEnabled = false

        val spinnerChars = listOf("|", "/", "-", "\\")
        val spinnerJob = activityScope.launch {
            var i = 0
            while (true) {
                btnTranscribe.text = "> TRANSCRIBING [ ${spinnerChars[i % spinnerChars.size]} ]"
                i++
                kotlinx.coroutines.delay(150)
            }
        }

        localActionJob = activityScope.launch {
            try {
                val response = transcriptionClientFor(provider)
                    .transcribe(apiKey, recording.audioFile, retryLanguage?.codeFor(provider))
                    .getOrElse { throw it }
                withContext(Dispatchers.IO) {
                    recordingStore.writeTranscript(recording, response)
                }
                selectedRecording = recordingStore.findRecordingByMetadataPath(recording.metadataPath)
                selectedRecordingPath = recording.metadataPath
                Toast.makeText(
                    this@MainActivity,
                    "Transcript ready via ${providerDisplayName(provider)} (${response.wordCount} words)",
                    Toast.LENGTH_SHORT,
                ).show()
                renderDetailScreen(animateContent = true)
            } catch (error: Exception) {
                Log.e("RokidScribe", "Transcription failed via ${provider.name}", error)
                val transcriptIssue = TranscriptIssueDetector.detectLanguageIssue(
                    provider = provider,
                    error = error,
                    requestedLanguage = retryLanguage,
                )
                withContext(Dispatchers.IO) {
                    if (transcriptIssue != null) {
                        recordingStore.writeTranscriptIssue(recording, transcriptIssue)
                    } else {
                        recordingStore.clearTranscriptIssue(recording)
                    }
                }
                selectedRecording = recordingStore.findRecordingByMetadataPath(recording.metadataPath)
                selectedRecordingPath = recording.metadataPath
                renderDetailScreen()
                Toast.makeText(
                    this@MainActivity,
                    if (transcriptIssue != null) {
                        getString(R.string.transcription_failed_language_retry)
                    } else {
                        "Transcription failed: ${error.message}"
                    },
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                spinnerJob.cancel()
                btnTranscribe.text = getString(R.string.btn_transcribe)
                localActionJob = null
                setBusy(false)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Clipboard & Export
    // ═══════════════════════════════════════════════════════════════

    private fun copyTranscriptToClipboard() {
        val text = currentRecordingOrNull()?.transcript?.displayText() ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Transcript", text))
        Toast.makeText(this, R.string.transcript_copied, Toast.LENGTH_SHORT).show()
    }

    private fun exportTranscript(format: ExportFormat) {
        if (isBusy || localActionJob != null) return
        val recording = currentRecordingOrNull() ?: return
        val transcript = recording.transcript ?: recordingStore.readTranscript(recording) ?: run {
            Toast.makeText(this, R.string.transcript_missing, Toast.LENGTH_SHORT).show()
            return
        }

        setBusy(true)
        localActionJob = activityScope.launch {
            try {
                val exported = withContext(Dispatchers.IO) {
                    when (format) {
                        ExportFormat.TXT -> transcriptExportManager.exportTxt(recording, transcript)
                        ExportFormat.PDF -> transcriptExportManager.exportPdf(recording, transcript)
                    }
                }.getOrElse { throw it }
                Toast.makeText(
                    this@MainActivity,
                    "${format.name} saved: ${exported.locationLabel}",
                    Toast.LENGTH_LONG,
                ).show()
            } catch (error: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "${format.name} export failed: ${error.message}",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                localActionJob = null
                setBusy(false)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Delete note
    // ═══════════════════════════════════════════════════════════════

    private fun confirmDeleteNote() {
        val recording = currentRecordingOrNull() ?: return
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_terminal_prompt, null)
        val dialog = AlertDialog.Builder(this, R.style.PhosphorDialog)
            .setView(dialogView)
            .create()
            
        dialogView.findViewById<Button>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }
        dialogView.findViewById<Button>(R.id.btnConfirm).setOnClickListener {
            dialog.dismiss()
            deleteNote(recording)
        }
        
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun deleteNote(recording: LocalRecording) {
        releasePlayer()
        setBusy(true)
        localActionJob = activityScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    recordingStore.deleteRecording(recording)
                }
                selectedRecording = null
                selectedRecordingPath = null
                Toast.makeText(this@MainActivity, R.string.note_deleted, Toast.LENGTH_SHORT).show()
                navigateTo(Screen.HOME)
            } finally {
                localActionJob = null
                setBusy(false)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Settings
    // ═══════════════════════════════════════════════════════════════

    private fun setupTranscriptProviderSpinner() {
        val labels = TranscriptProvider.entries.map(::providerDisplayName)
        val adapter = ArrayAdapter(this, R.layout.spinner_item, labels)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        providerSpinner.adapter = adapter
        providerSpinner.setSelection(TranscriptProvider.entries.indexOf(currentTranscriptProvider), false)
        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val selectedProvider = TranscriptProvider.entries[pos]
                if (selectedProvider == currentTranscriptProvider) {
                    bindProviderUi()
                    return
                }
                persistApiKey()
                currentTranscriptProvider = selectedProvider
                settingsStore.setSelectedTranscriptProvider(selectedProvider.name)
                bindProviderUi()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        bindProviderUi()
    }

    private fun bindProviderUi() {
        tvApiKeyLabel.text = "${providerDisplayName(currentTranscriptProvider)} API KEY"
        inputApiKey.setText(settingsStore.getApiKey(currentTranscriptProvider))
        inputApiKey.hint = providerApiKeyHintValue(currentTranscriptProvider)
        tvApiKeyHint.text = providerApiKeyHint(currentTranscriptProvider)
        tvProviderHint.text = getString(R.string.provider_hint_text)
        if (currentTranscriptProvider == TranscriptProvider.DEEPGRAM) {
            deepgramUsageConfig.visibility = View.VISIBLE
            inputDeepgramProjectId.setText(settingsStore.getDeepgramProjectId())
            tvDeepgramProjectHint.text = getString(R.string.deepgram_project_id_hint)
        } else {
            deepgramUsageConfig.visibility = View.GONE
        }
        if (currentScreen == Screen.SETTINGS && inputApiKey.text.toString().trim().isNotBlank()) {
            refreshQuotaCard()
        } else {
            renderQuotaMissingKey()
        }
    }

    private fun loadSavedTranscriptProvider(): TranscriptProvider {
        val savedProvider = settingsStore.getSelectedTranscriptProvider()
        return TranscriptProvider.entries.firstOrNull { it.name == savedProvider }
            ?: TranscriptProvider.ELEVENLABS
    }

    private fun providerDisplayName(provider: TranscriptProvider): String = when (provider) {
        TranscriptProvider.ELEVENLABS -> getString(R.string.provider_name_elevenlabs)
        TranscriptProvider.ASSEMBLYAI -> getString(R.string.provider_name_assemblyai)
        TranscriptProvider.SPEECHMATICS -> getString(R.string.provider_name_speechmatics)
        TranscriptProvider.DEEPGRAM -> getString(R.string.provider_name_deepgram)
        TranscriptProvider.GROQ -> getString(R.string.provider_name_groq)
    }

    private fun providerForId(providerId: String?): TranscriptProvider? {
        if (providerId.isNullOrBlank()) {
            return null
        }
        return TranscriptProvider.entries.firstOrNull { it.name == providerId }
    }

    private fun detailProviderLabel(providerId: String?): String {
        val provider = providerForId(providerId)
        return if (provider != null) {
            providerDisplayName(provider)
        } else {
            providerId?.replace('_', ' ')?.uppercase(Locale.getDefault()).orEmpty()
        }
    }

    private fun compactProviderLabel(providerId: String?): String {
        return when (providerForId(providerId)) {
            TranscriptProvider.ELEVENLABS -> "11L"
            TranscriptProvider.ASSEMBLYAI -> "AAI"
            TranscriptProvider.SPEECHMATICS -> "SM"
            TranscriptProvider.DEEPGRAM -> "DG"
            TranscriptProvider.GROQ -> "GROQ"
            null -> providerId?.take(4)?.uppercase(Locale.getDefault()).orEmpty()
        }
    }

    private fun providerApiKeyHintValue(provider: TranscriptProvider): String = when (provider) {
        TranscriptProvider.ELEVENLABS -> getString(R.string.hint_api_key_elevenlabs)
        TranscriptProvider.ASSEMBLYAI -> getString(R.string.hint_api_key_assemblyai)
        TranscriptProvider.SPEECHMATICS -> getString(R.string.hint_api_key_speechmatics)
        TranscriptProvider.DEEPGRAM -> getString(R.string.hint_api_key_deepgram)
        TranscriptProvider.GROQ -> getString(R.string.hint_api_key_groq)
    }

    private fun providerApiKeyHint(provider: TranscriptProvider): String = when (provider) {
        TranscriptProvider.ELEVENLABS -> getString(R.string.api_key_hint_elevenlabs)
        TranscriptProvider.ASSEMBLYAI -> getString(R.string.api_key_hint_assemblyai)
        TranscriptProvider.SPEECHMATICS -> getString(R.string.api_key_hint_speechmatics)
        TranscriptProvider.DEEPGRAM -> getString(R.string.api_key_hint_deepgram)
        TranscriptProvider.GROQ -> getString(R.string.api_key_hint_groq)
    }

    private fun transcriptionClientFor(provider: TranscriptProvider): TranscriptionProviderClient = when (provider) {
        TranscriptProvider.ELEVENLABS -> elevenLabsClient
        TranscriptProvider.ASSEMBLYAI -> assemblyAiClient
        TranscriptProvider.SPEECHMATICS -> speechmaticsClient
        TranscriptProvider.DEEPGRAM -> deepgramClient
        TranscriptProvider.GROQ -> groqClient
    }

    private fun refreshQuotaCard() {
        val provider = currentTranscriptProvider
        val apiKey = inputApiKey.text.toString().trim()
        if (apiKey.isBlank()) {
            quotaRefreshJob?.cancel()
            quotaRefreshJob = null
            renderQuotaMissingKey()
            return
        }
        if (quotaRefreshJob?.isActive == true) {
            return
        }

        persistApiKey(apiKey)
        setQuotaLoading(true)
        quotaRefreshJob = activityScope.launch {
            try {
                val usage = when (provider) {
                    TranscriptProvider.DEEPGRAM ->
                        deepgramClient.fetchUsage(apiKey, inputDeepgramProjectId.text.toString().trim())
                            .getOrElse { throw it }
                    else ->
                        transcriptionClientFor(provider).fetchUsage(apiKey).getOrElse { throw it }
                }
                renderQuotaUsage(usage)
            } catch (error: Exception) {
                if (error is UnsupportedOperationException) {
                    renderQuotaUnavailable(error)
                } else {
                    renderQuotaError(error)
                }
            } finally {
                setQuotaLoading(false)
                quotaRefreshJob = null
            }
        }
    }

    private fun renderQuotaMissingKey() {
        setQuotaLoading(false)
        quotaRowsContainer.removeAllViews()
        quotaRowsContainer.visibility = View.GONE
        tvQuotaSummary.visibility = View.VISIBLE
        tvQuotaSummary.text = getString(R.string.quota_missing_api_key)
        tvQuotaFootnote.text = providerApiKeyHint(currentTranscriptProvider)
    }

    private fun setQuotaLoading(loading: Boolean) {
        btnQuotaRefresh.isEnabled = !loading
        btnQuotaRefresh.text = if (loading) "SYNC..." else getString(R.string.btn_refresh)
        if (loading) {
            quotaRowsContainer.removeAllViews()
            quotaRowsContainer.visibility = View.GONE
            tvQuotaSummary.visibility = View.VISIBLE
            tvQuotaSummary.text = getString(R.string.quota_loading)
            tvQuotaFootnote.text = getString(R.string.quota_status_ready)
        }
    }

    private fun renderQuotaUsage(usage: ProviderUsage) {
        tvQuotaSummary.visibility = View.GONE
        quotaRowsContainer.visibility = View.VISIBLE
        quotaRowsContainer.removeAllViews()
        val labelWidth = usage.rows.maxOfOrNull { it.first.length } ?: 0
        usage.rows.forEachIndexed { index, (label, value) ->
            quotaRowsContainer.addView(buildQuotaRow(label, value, labelWidth, index > 0))
        }
        tvQuotaFootnote.text = usage.footnote
    }

    private fun renderQuotaUnavailable(error: UnsupportedOperationException) {
        quotaRowsContainer.removeAllViews()
        quotaRowsContainer.visibility = View.GONE
        tvQuotaSummary.visibility = View.VISIBLE
        tvQuotaSummary.text = buildString {
            appendLine("PLAN  ${providerDisplayName(currentTranscriptProvider)}")
            append("USAGE ${error.message?.replace('\n', ' ')?.trim().orEmpty().ifBlank { getString(R.string.quota_limit_unavailable) }}")
        }
        tvQuotaFootnote.text = getString(R.string.quota_status_unavailable)
    }

    private fun renderQuotaError(error: Exception) {
        quotaRowsContainer.removeAllViews()
        quotaRowsContainer.visibility = View.GONE
        tvQuotaSummary.visibility = View.VISIBLE
        tvQuotaSummary.text = buildString {
            appendLine("> Unable to load quota.")
            append(error.message?.replace('\n', ' ')?.trim().orEmpty().ifBlank { "Unknown error" })
        }
        tvQuotaFootnote.text = getString(R.string.quota_status_ready)
    }

    private fun buildQuotaRow(
        label: String,
        value: String,
        labelWidth: Int,
        addTopMargin: Boolean,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (addTopMargin) {
                    topMargin = dp(6f).toInt()
                }
            }
        }

        val labelView = TextView(this).apply {
            text = label
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.phosphor_dim))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setEms(labelWidth + 1)
        }

        val valueView = TextView(this).apply {
            text = value
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.phosphor_text_bright))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }

        row.addView(labelView)
        row.addView(valueView)
        return row
    }

    private fun setupTransportSpinner() {
        val labels = listOf(
            getString(R.string.transport_mode_wifi_lan),
            getString(R.string.transport_mode_spp_slow),
        )
        val adapter = ArrayAdapter(this, R.layout.spinner_item, labels)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        transportSpinner.adapter = adapter
        transportSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                currentMode = if (pos == 0) TransportMode.WIFI_LAN else TransportMode.SPP
                settingsStore.setPreferredTransportMode(currentMode.name)
                refreshModeUi()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        transportSpinner.setSelection(
            if (currentMode == TransportMode.WIFI_LAN) 0 else 1,
            false,
        )
    }

    private fun loadSavedTransportMode(): TransportMode {
        val savedMode = settingsStore.getPreferredTransportMode()
        return TransportMode.entries.firstOrNull { it.name == savedMode } ?: TransportMode.WIFI_LAN
    }

    private fun refreshModeUi() {
        when (currentMode) {
            TransportMode.SPP -> {
                tvTransportHint.text = getString(R.string.transport_hint_text_spp)
                phaseLabels = listOf("PAIR", "QUEUE", "BT", "SAVE", "OK")
            }
            TransportMode.WIFI_LAN -> {
                tvTransportHint.text = getString(R.string.transport_hint_text_wifi)
                phaseLabels = listOf("PAIR", "QUEUE", "LAN", "SAVE", "OK")
            }
        }
        resetPhases()
        refreshDeviceStatusBar()
    }

    private fun refreshPairedDevices() {
        val devices = phoneBluetoothClient.getPairedDevices()
        discoveredDevices = devices
        if (devices.isEmpty()) {
            selectedDeviceAddress = null
            settingsStore.clearSelectedDeviceAddress()
        } else if (selectedDeviceAddress !in devices.map { it.address }) {
            val autoCandidate = devices.firstOrNull(phoneBluetoothClient::isImportCandidate)
            if (autoCandidate != null) {
                rememberSelectedDevice(autoCandidate)
            } else {
                selectedDeviceAddress = null
                settingsStore.clearSelectedDeviceAddress()
            }
        }
        refreshDeviceStatusBar()
    }

    // ═══════════════════════════════════════════════════════════════
    // Busy state
    // ═══════════════════════════════════════════════════════════════

    private fun setBusy(busy: Boolean) {
        isBusy = busy
        btnImport.isEnabled = !busy
        btnDebugImport.isEnabled = !busy
        transportSpinner.isEnabled = !busy
        inputApiKey.isEnabled = !busy
        if (::inputDeepgramProjectId.isInitialized) {
            inputDeepgramProjectId.isEnabled = !busy
        }
        refreshDetailActionButtons()

        if (busy) {
            importLogHistory.clear()
            tvImportStatus.text = ""
            btnImport.text = getString(R.string.busy)
            btnImport.setTextColor(ContextCompat.getColor(this, R.color.phosphor_dim))
            btnImport.setBackgroundResource(R.drawable.btn_outline_green)
            importProgress.visibility = View.VISIBLE
            tvStatusState.text = getString(R.string.busy)
            tvStatusState.setTextColor(ContextCompat.getColor(this, R.color.phosphor_mid))
            dotPulseAnimator?.start()
        } else {
            btnImport.text = getString(R.string.btn_import)
            btnImport.setTextColor(resources.getColor(R.color.phosphor_bg, theme))
            btnImport.setBackgroundResource(R.drawable.btn_primary_green)
            tvStatusState.text = getString(R.string.ready)
            tvStatusState.setTextColor(ContextCompat.getColor(this, R.color.phosphor_primary))
            dotPulseAnimator?.cancel()
            viewStatusDot.alpha = 1f

            if (currentScreen == Screen.HOME) {
                refreshLocalLibrary()
                // Auto-dismiss the console after a short delay
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isBusy) {
                        importProgress.visibility = View.GONE
                    }
                }, 4000)
            } else {
                importProgress.visibility = View.GONE
            }
        }
        refreshStatusBar()
    }

    private fun refreshStatusBar() {
        tvStatusBt.text = if (isBluetoothEnabled()) "BT: ON" else "BT: OFF"
    }

    private fun refreshDetailActionButtons() {
        val hasSelectedRecording = currentScreen == Screen.DETAIL && selectedRecordingPath != null
        val detailActionEnabled = !isBusy && localActionJob == null && hasSelectedRecording
        btnRenameNote.isEnabled = detailActionEnabled
        btnDeleteNote.isEnabled = detailActionEnabled
        btnTranscribe.isEnabled = !isBusy && localActionJob == null
        btnRetryWithLanguage.isEnabled =
            !isBusy && localActionJob == null && btnRetryWithLanguage.visibility == View.VISIBLE
    }

    // ═══════════════════════════════════════════════════════════════
    // Prerequisites
    // ═══════════════════════════════════════════════════════════════

    private fun runWithPrerequisites(action: () -> Unit) {
        if (isBusy || localActionJob != null) return
        pendingAction = action
        when {
            !hasAllPermissions() -> permissionLauncher.launch(requiredPermissions)
            !isBluetoothEnabled() -> {
                enableBluetoothLauncher.launch(
                    Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE),
                )
            }
            else -> consumePendingAction()
        }
    }

    private fun consumePendingAction() {
        val action = pendingAction ?: return
        pendingAction = null
        action()
    }

    private fun persistApiKey(value: String? = null) {
        settingsStore.setApiKey(currentTranscriptProvider, value ?: inputApiKey.text.toString())
        settingsStore.setSelectedTranscriptProvider(currentTranscriptProvider.name)
        settingsStore.setDeepgramProjectId(
            if (::inputDeepgramProjectId.isInitialized) inputDeepgramProjectId.text.toString() else "",
        )
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions.all(::hasPermission)

    private fun hasPermission(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun isBluetoothEnabled(): Boolean {
        val mgr = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
        return mgr.adapter?.isEnabled == true
    }

    private fun currentSelectedDevice(): BluetoothDevice? {
        if (discoveredDevices.isEmpty()) return null
        return discoveredDevices.firstOrNull { it.address == selectedDeviceAddress }
    }

    // ═══════════════════════════════════════════════════════════════
    // Formatting
    // ═══════════════════════════════════════════════════════════════

    private fun formatRelativeDate(epochMs: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = epochMs }
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))

        return when {
            now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) -> "Today $timeStr"
            now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR) == 1 -> "Yesterday $timeStr"
            else -> SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(epochMs))
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val total = (durationMs / 1000L).coerceAtLeast(0L)
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0L) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private fun formatSize(sizeBytes: Long): String {
        val k = 1024.0
        val m = k * k
        return when {
            sizeBytes >= m -> String.format(Locale.US, "%.1fMB", sizeBytes / m)
            sizeBytes >= k -> String.format(Locale.US, "%.1fKB", sizeBytes / k)
            else -> "${sizeBytes}B"
        }
    }

    private fun importDebugFiles(uris: List<Uri>) {
        if (uris.isEmpty() || isBusy || localActionJob != null) {
            return
        }

        setBusy(true)
        updateStatus("Loaded ${uris.size} local file(s) for debug import.")

        localActionJob = activityScope.launch {
            try {
                var importedCount = 0
                uris.forEachIndexed { index, uri ->
                    val ordinal = index + 1
                    updateStatus("Reading metadata $ordinal/${uris.size}")
                    updateStatus("Copying local file $ordinal/${uris.size}")
                    val recording = withContext(Dispatchers.IO) {
                        importDebugUri(uri, ordinal)
                    }
                    importedCount += 1
                    updateStatus("Saved on the phone: ${recording.sourceFileName}")
                }
                updateStatus("Debug import complete. $importedCount recording(s) saved.")
                Toast.makeText(
                    this@MainActivity,
                    "Debug import complete: $importedCount file(s) saved.",
                    Toast.LENGTH_LONG,
                ).show()
            } catch (error: Exception) {
                Log.e("RokidScribe", "Debug import failed", error)
                updateStatus("Debug import failed: ${error.message}")
                Toast.makeText(
                    this@MainActivity,
                    "Debug import failed: ${error.message}",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                setBusy(false)
                localActionJob = null
            }
        }
    }

    private suspend fun importDebugUri(uri: Uri, ordinal: Int): LocalRecording {
        val resolvedName = ensureImportFileName(resolveImportDisplayName(uri), contentResolver.getType(uri))
        val tempFile = File(cacheDir, "debug-import-${System.currentTimeMillis()}-$ordinal.tmp")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            } ?: error("Unable to open the selected file.")

            val md5Hex = SppPacketUtils.calculateMd5(tempFile).toHexString()
            val offer = RecordingOffer(
                id = "debug-${System.currentTimeMillis()}-$ordinal",
                fileName = resolvedName,
                sizeBytes = tempFile.length(),
                durationMs = readDurationMs(tempFile),
                createdAtEpochMs = System.currentTimeMillis(),
                md5Hex = md5Hex,
            )
            val targetFile = recordingStore.createTargetFile(offer)
            tempFile.copyTo(targetFile, overwrite = true)
            return recordingStore.writeMetadata(targetFile, offer, "DEBUG IMPORT")
        } finally {
            tempFile.delete()
        }
    }

    private fun resolveImportDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (columnIndex >= 0) {
                    return cursor.getString(columnIndex).orEmpty()
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "debug-note.m4a"
    }

    private fun ensureImportFileName(displayName: String, mimeType: String?): String {
        val trimmed = displayName.trim().ifBlank { "debug-note" }
        if (trimmed.contains('.')) {
            return trimmed
        }

        val extension = when (mimeType?.lowercase(Locale.getDefault())) {
            "audio/mpeg" -> "mp3"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/mp4", "video/mp4", "audio/x-m4a" -> "m4a"
            "audio/aac" -> "aac"
            "audio/ogg" -> "ogg"
            "audio/webm", "video/webm" -> "webm"
            else -> "m4a"
        }
        return "$trimmed.$extension"
    }

    private fun readDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            runCatching {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?: 0L
            }.getOrDefault(0L)
        } finally {
            retriever.release()
        }
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte)
    }
}
