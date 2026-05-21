package com.looplingo.horizon.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.looplingo.horizon.BuildConfig
import com.looplingo.horizon.R
import com.looplingo.horizon.api.GroqApiClient
import com.looplingo.horizon.api.GroqApiClient.ApiKeyException
import com.looplingo.horizon.api.GroqApiClient.Segment
import com.looplingo.horizon.api.GroqApiClient.SubtitleException
import com.looplingo.horizon.databinding.FragmentPlaybackSettingsBinding
import com.looplingo.horizon.model.SpeedPresets
import com.looplingo.horizon.model.SubtitleCue
import com.looplingo.horizon.playback.AudioPlaybackService
import com.looplingo.horizon.ui.viewmodel.PlaybackSettingsViewModel
import com.looplingo.horizon.util.TimeUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import timber.log.Timber
import javax.inject.Inject

/**
 * Now Playing / Playback Settings screen (v6 Tab-Based Redesign).
 *
 * Layout structure (matching physical device wireframe):
 *   ┌──────────────────────────────────────┐
 *   │  HEADER BLOCK (48dp)                │
 *   │  [←] [🎵] Track Title    [AB] [1x] │
 *   ├──────────────────────────────────────┤
 *   │  MAIN DISPLAY (flex-1)              │
 *   │  Switches between panels:           │
 *   │  - Clean: minimal view              │
 *   │  - Talk: dialogue list              │
 *   │  - Loop: A-B controls              │
 *   │  - Notes: saved items              │
 *   ├──────────────────────────────────────┤
 *   │  TAB NAV PILL (52dp)                │
 *   │  [Clean] [Talk] [Loop] [Notes]      │
 *   ├──────────────────────────────────────┤
 *   │  PLAYER FOOTER CARD (180dp)         │
 *   │  Title | Waveform | [⏪ ▶ ⏩]       │
 *   └──────────────────────────────────────┘
 */
@AndroidEntryPoint
class PlaybackSettingsFragment : Fragment() {

    private var _binding: FragmentPlaybackSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PlaybackSettingsViewModel by viewModels()
    private val args: PlaybackSettingsFragmentArgs by navArgs()

    private val positionHandler = Handler(Looper.getMainLooper())
    private var positionPollingRunnable: Runnable? = null
    private val POSITION_POLL_INTERVAL_MS = 500L

    @Inject lateinit var groqApiClient: GroqApiClient
    private var dialogueSegments: List<Segment> = emptyList()
    private var translatedTexts: Map<Int, String> = emptyMap()
    private var selectedSegmentIndex: Int = -1
    private var dialogueLoopCount: Int = 3
    private var isGeneratingSubtitles: Boolean = false
    private var subtitleGenerationJob: kotlinx.coroutines.Job? = null
    private val debugLog = StringBuilder()

    // Speed toggle state
    private var currentSpeedIndex: Int = SpeedPresets.ALL.indexOf(SpeedPresets.DEFAULT)
    private var loopCount: Int = 3

    // Tab state
    private var currentTab: Int = TAB_CLEAN

    private val securePrefsName = "looplingo_secure_prefs"
    private val keyGroqApiKey = "groq_api_key"
    private val keyLanguage = "whisper_language"
    private val keyTranslationLanguage = "translation_language"
    private var selectedLanguageCode = "auto"
    private var selectedTranslationCode = "none"
    private var cachedEncryptedPrefs: SharedPreferences? = null

    // Seek bar tracking
    private var isSeekBarTracking: Boolean = false

    companion object {
        const val TAB_CLEAN = 0
        const val TAB_TALK = 1
        const val TAB_LOOP = 2
        const val TAB_NOTES = 3
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlaybackSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val videoPath = args.videoPath
        if (videoPath.isBlank()) {
            findNavController().navigateUp()
            return
        }

        viewModel.loadConfigForVideo(videoPath)
        setupHeader()
        setupTabNavigation()
        setupTransportControls()
        setupSeekBar()
        setupSpeedToggle()
        setupLoopControls()
        setupTryLoopButton()
        setupApplyButton()
        setupClearButton()
        setupNowPlayingCard()
        setupSubtitleGeneration()
        setupDialogueLoopControls()
        setupObservers()

        // Hide debug log in release builds
        if (!BuildConfig.DEBUG) {
            binding.tvDebugLog.visibility = View.GONE
        }

        // Start on Clean tab
        switchTab(TAB_CLEAN)
    }

    // ══════════════════════════════════════════════════════════════════════
    // HEADER — Compact top bar with back, avatar, title, AB badge, speed
    // ══════════════════════════════════════════════════════════════════════

    private fun setupHeader() {
        binding.ivBack.setOnClickListener { findNavController().navigateUp() }
    }

    // ══════════════════════════════════════════════════════════════════════
    // TAB NAVIGATION — Clean / Talk / Loop / Notes
    // ══════════════════════════════════════════════════════════════════════

    private fun setupTabNavigation() {
        binding.tabCleanBtn.setOnClickListener { switchTab(TAB_CLEAN) }
        binding.tabTalkBtn.setOnClickListener { switchTab(TAB_TALK) }
        binding.tabLoopBtn.setOnClickListener { switchTab(TAB_LOOP) }
        binding.tabNotesBtn.setOnClickListener { switchTab(TAB_NOTES) }
    }

    private fun switchTab(tab: Int) {
        currentTab = tab

        // Update panel visibility
        binding.panelClean.visibility = if (tab == TAB_CLEAN) View.VISIBLE else View.GONE
        binding.panelTalk.visibility = if (tab == TAB_TALK) View.VISIBLE else View.GONE
        binding.panelLoop.visibility = if (tab == TAB_LOOP) View.VISIBLE else View.GONE
        binding.panelNotes.visibility = if (tab == TAB_NOTES) View.VISIBLE else View.GONE

        // Update tab styling (selected = pill bg, unselected = transparent)
        updateTabStyle(binding.tabCleanBtn, tab == TAB_CLEAN)
        updateTabStyle(binding.tabTalkBtn, tab == TAB_TALK)
        updateTabStyle(binding.tabLoopBtn, tab == TAB_LOOP)
        updateTabStyle(binding.tabNotesBtn, tab == TAB_NOTES)
    }

    private fun updateTabStyle(tabLayout: LinearLayout, isSelected: Boolean) {
        val iconView = tabLayout.getChildAt(0) as ImageView
        val textView = tabLayout.getChildAt(1) as TextView

        if (isSelected) {
            tabLayout.background = resources.getDrawable(R.drawable.bg_tab_indicator, null)
            iconView.imageTintList = resources.getColorStateList(R.color.colorOnPrimaryContainer, null)
            textView.setTextColor(resources.getColor(R.color.colorOnPrimaryContainer, null))
        } else {
            tabLayout.background = resources.getDrawable(android.R.attr.selectableItemBackgroundBorderless, requireContext().theme)
            iconView.imageTintList = resources.getColorStateList(R.color.colorOnSurfaceVariant, null)
            textView.setTextColor(resources.getColor(R.color.colorOnSurfaceVariant, null))
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // TRANSPORT CONTROLS — Player footer
    // ══════════════════════════════════════════════════════════════════════

    private fun setupTransportControls() {
        // Play/Pause
        binding.ivPlayPause.setOnClickListener {
            val isCurrentlyPlaying = AudioPlaybackService.isPlaying &&
                AudioPlaybackService.currentVideoPath == args.videoPath
            if (isCurrentlyPlaying) {
                AudioPlaybackService.togglePlayback(requireContext())
            } else {
                AudioPlaybackService.startService(requireContext(), args.videoPath)
            }
        }

        // Rewind 5s
        binding.ivRewind5.setOnClickListener {
            AudioPlaybackService.seekBackward(requireContext(), 5000L)
        }

        // Forward 5s
        binding.ivForward5.setOnClickListener {
            AudioPlaybackService.seekForward(requireContext(), 5000L)
        }
    }

    private fun setupSeekBar() {
        binding.seekBarPlayer.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val duration = AudioPlaybackService.durationMs
                        if (duration > 0) {
                            val newPos = (progress.toLong() * duration) / 1000
                            binding.tvCurrentPosition.text = formatMsToTime(newPos)
                        }
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    isSeekBarTracking = true
                }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    isSeekBarTracking = false
                    val duration = AudioPlaybackService.durationMs
                    if (duration > 0 && seekBar != null) {
                        val newPos = (seekBar.progress.toLong() * duration) / 1000
                        val videoPath = AudioPlaybackService.currentVideoPath
                        if (videoPath.isNotBlank()) {
                            AudioPlaybackService.seekToPosition(requireContext(), videoPath, newPos)
                        }
                    }
                }
            }
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // SPEED TOGGLE — Single button that cycles through presets
    // ══════════════════════════════════════════════════════════════════════

    private fun setupSpeedToggle() {
        binding.btnSpeedToggle.text = SpeedPresets.ALL[currentSpeedIndex].label
        binding.btnSpeedToggle.setOnClickListener {
            currentSpeedIndex = (currentSpeedIndex + 1) % SpeedPresets.ALL.size
            val preset = SpeedPresets.ALL[currentSpeedIndex]
            binding.btnSpeedToggle.text = preset.label
            AudioPlaybackService.setSpeed(requireContext(), preset.speed)
            Timber.d("Speed changed to %s (toggle)", preset.label)
        }
    }

    private fun updateSpeedToggle(speed: Float) {
        val index = SpeedPresets.ALL.indexOfFirst { kotlin.math.abs(it.speed - speed) < 0.001f }
        if (index >= 0) {
            currentSpeedIndex = index
            binding.btnSpeedToggle.text = SpeedPresets.ALL[index].label
        }
    }

    private fun getCurrentSpeed(): Float {
        return SpeedPresets.ALL.getOrNull(currentSpeedIndex)?.speed ?: SpeedPresets.DEFAULT.speed
    }

    // ══════════════════════════════════════════════════════════════════════
    // LOOP COUNT STEPPER
    // ══════════════════════════════════════════════════════════════════════

    private fun setupLoopControls() {
        binding.tvLoopCount.text = loopCount.toString()

        binding.btnLoopMinus.setOnClickListener {
            if (loopCount > 1) {
                loopCount--
                binding.tvLoopCount.text = loopCount.toString()
            }
        }

        binding.btnLoopPlus.setOnClickListener {
            if (loopCount < 10000) {
                loopCount++
                binding.tvLoopCount.text = loopCount.toString()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // TRY LOOP
    // ══════════════════════════════════════════════════════════════════════

    private fun setupTryLoopButton() {
        binding.btnTryLoop.setOnClickListener {
            val rangeStartMs = parseTimeToMs(binding.etRangeStart.text.toString())
            val rangeEndMs = parseTimeToMs(binding.etRangeEnd.text.toString())
            val effectiveLoopCount = loopCount

            var hasError = false
            if (rangeEndMs > 0 && rangeEndMs <= rangeStartMs) {
                binding.tilRangeEnd.error = getString(R.string.error_range_end_before_start)
                hasError = true
            } else {
                binding.tilRangeEnd.error = null
            }

            if (effectiveLoopCount < 1) {
                showSnackbar(getString(R.string.error_loop_count_minimum))
                hasError = true
            }

            if (hasError) return@setOnClickListener

            val isCurrentlyPlaying = AudioPlaybackService.isPlaying &&
                AudioPlaybackService.currentVideoPath == args.videoPath

            if (isCurrentlyPlaying) {
                AudioPlaybackService.setABLoop(
                    requireContext(), args.videoPath, rangeStartMs,
                    if (binding.etRangeEnd.text.isNullOrBlank()) -1L else rangeEndMs, effectiveLoopCount
                )
            } else {
                AudioPlaybackService.startService(requireContext(), args.videoPath)
                positionHandler.postDelayed({
                    AudioPlaybackService.setABLoop(
                        requireContext(), args.videoPath, rangeStartMs,
                        if (binding.etRangeEnd.text.isNullOrBlank()) -1L else rangeEndMs, effectiveLoopCount
                    )
                }, 1000)
            }
            showSnackbar(getString(R.string.loop_preview_active))
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // NOW PLAYING CARD — Real-time updates for header + footer
    // ══════════════════════════════════════════════════════════════════════

    private fun setupNowPlayingCard() {
        val title = args.videoPath.substringAfterLast("/").substringBeforeLast(".")
        binding.tvHeaderTitle.text = title
        binding.tvNowPlayingTitle.text = title
        binding.tvNowPlayingSubtitle.text = getString(R.string.clean_view_subtitle)
        binding.tvCleanTitle.text = title
        binding.tvCurrentPosition.text = "0:00"
        binding.tvDuration.text = "0:00"
        startPositionPolling()
    }

    private fun updateNowPlayingState() {
        val isCurrentlyPlaying = AudioPlaybackService.isPlaying &&
            AudioPlaybackService.currentVideoPath == args.videoPath

        // Update play/pause icon
        binding.ivPlayPause.setImageResource(
            if (isCurrentlyPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )

        // Update title in header, footer, and clean panel
        val title = if (AudioPlaybackService.currentVideoPath.isNotBlank()) {
            AudioPlaybackService.currentVideoPath.substringAfterLast("/").substringBeforeLast(".")
        } else {
            args.videoPath.substringAfterLast("/").substringBeforeLast(".")
        }
        binding.tvHeaderTitle.text = title
        binding.tvNowPlayingTitle.text = title
        binding.tvCleanTitle.text = title

        // Update position and duration
        val position = AudioPlaybackService.currentPositionMs
        val duration = AudioPlaybackService.durationMs

        binding.tvCurrentPosition.text = formatMsToTime(position)
        binding.tvDuration.text = if (duration > 0) formatMsToTime(duration) else "0:00"

        // Update seek bar (only if user is not dragging)
        if (!isSeekBarTracking && duration > 0) {
            val progress = ((position * 1000) / duration).toInt().coerceIn(0, 1000)
            binding.seekBarPlayer.progress = progress
        }

        // Show AB indicator if loop is active
        val hasABLoop = AudioPlaybackService.currentVideoPath == args.videoPath &&
            duration > 0
        binding.tvAbIndicator.visibility = if (hasABLoop) View.VISIBLE else View.GONE
    }

    private fun startPositionPolling() {
        stopPositionPolling()
        positionPollingRunnable = object : Runnable {
            override fun run() {
                try {
                    updateNowPlayingState()
                } catch (e: Exception) {
                    Timber.w(e, "Position polling error")
                }
                positionHandler.postDelayed(this, POSITION_POLL_INTERVAL_MS)
            }
        }
        positionHandler.post(positionPollingRunnable!!)
    }

    private fun stopPositionPolling() {
        positionPollingRunnable?.let { positionHandler.removeCallbacks(it) }
        positionPollingRunnable = null
    }

    // ══════════════════════════════════════════════════════════════════════
    // AI SUBTITLES — Groq STT with smart pipeline
    // ══════════════════════════════════════════════════════════════════════

    private fun getEncryptedPrefs(): SharedPreferences {
        cachedEncryptedPrefs?.let { return it }
        val masterKey = MasterKey.Builder(requireContext())
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            requireContext(),
            securePrefsName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        cachedEncryptedPrefs = prefs
        return prefs
    }

    private fun getGroqApiKey(): String {
        val prefs = getEncryptedPrefs()
        val savedKey = prefs.getString(keyGroqApiKey, "") ?: ""
        if (savedKey.isNotBlank()) return savedKey
        return BuildConfig.GROQ_API_KEY
    }

    private fun saveGroqApiKey(apiKey: String) {
        getEncryptedPrefs()
            .edit().putString(keyGroqApiKey, apiKey.trim()).apply()
    }

    private fun loadSavedApiKey() {
        val prefs = getEncryptedPrefs()
        val savedKey = prefs.getString(keyGroqApiKey, "") ?: ""
        if (savedKey.isNotBlank()) {
            binding.etGroqApiKey.setText(savedKey)
        } else if (BuildConfig.GROQ_API_KEY.isNotBlank()) {
            binding.etGroqApiKey.setText(BuildConfig.GROQ_API_KEY)
            saveGroqApiKey(BuildConfig.GROQ_API_KEY)
        }
    }

    private fun updateApiKeyBanner() {
        val apiKey = getGroqApiKey()
        binding.tvApiKeyBanner.visibility = if (apiKey.isBlank()) View.VISIBLE else View.GONE
    }

    private val TRANSLATION_LANGUAGES = listOf(
        "none" to "No Translation (subtitles only)",
        "bn" to "\u09AC\u09BE\u0982\u09B2\u09BE (Bengali)",
        "en" to "English",
        "hi" to "\u0939\u093F\u0928\u094D\u0926\u0940 (Hindi)",
        "ja" to "\u65E5\u672C\u8A9E (Japanese)",
        "ko" to "\uD55C\uAD6D\uC5B4 (Korean)",
        "zh" to "\u4E2D\u6587 (Chinese)",
        "ar" to "\u0627\u0644\u0639\u0631\u0628\u064A\u0629 (Arabic)",
        "es" to "Espa\u00F1ol (Spanish)",
        "fr" to "Fran\u00E7ais (French)",
        "de" to "Deutsch (German)",
        "pt" to "Portugu\u00EAs (Portuguese)",
        "ru" to "\u0420\u0443\u0441\u0441\u043A\u0438\u0439 (Russian)",
        "th" to "\u0E44\u0E17\u0E22 (Thai)",
        "vi" to "Ti\u1EBFng Vi\u1EC7t (Vietnamese)",
        "tr" to "T\u00FCrk\u00E7e (Turkish)",
        "id" to "Bahasa Indonesia",
        "ta" to "\u0BA4\u0BAE\u0BBF\u0BB4\u0BCD (Tamil)",
        "te" to "\u0C24\u0C46\u0C32\u0C41\u0C17\u0C41 (Telugu)",
        "ur" to "\u0627\u0631\u062F\u0648 (Urdu)"
    )

    private fun setupLanguageSelector() {
        val languages = GroqApiClient.SUPPORTED_LANGUAGES
        val displayNames = languages.map { it.second }

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, displayNames)
        binding.actvLanguage.setAdapter(adapter)

        val prefs = getEncryptedPrefs()
        val savedLangCode = prefs.getString(keyLanguage, "auto") ?: "auto"
        val savedDisplayName = languages.find { it.first == savedLangCode }?.second ?: displayNames[0]
        binding.actvLanguage.setText(savedDisplayName, false)
        selectedLanguageCode = savedLangCode

        binding.actvLanguage.setOnItemClickListener { _, _, position, _ ->
            val (code, _) = languages[position]
            selectedLanguageCode = code
            prefs.edit().putString(keyLanguage, code).apply()
            Timber.d("Language selected: %s (%s)", displayNames[position], code)
        }

        // Translation language selector
        val translationDisplayNames = TRANSLATION_LANGUAGES.map { it.second }
        val translationAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, translationDisplayNames)
        binding.actvTranslationLanguage.setAdapter(translationAdapter)

        selectedTranslationCode = prefs.getString(keyTranslationLanguage, "none") ?: "none"
        val savedTranslationDisplayName = TRANSLATION_LANGUAGES.find { it.first == selectedTranslationCode }?.second ?: translationDisplayNames[0]
        binding.actvTranslationLanguage.setText(savedTranslationDisplayName, false)

        binding.actvTranslationLanguage.setOnItemClickListener { _, _, position, _ ->
            val (code, _) = TRANSLATION_LANGUAGES[position]
            selectedTranslationCode = code
            prefs.edit().putString(keyTranslationLanguage, code).apply()
            Timber.d("Translation language selected: %s (%s)", translationDisplayNames[position], code)
        }
    }

    private fun getSelectedLanguageCode(): String {
        val displayText = binding.actvLanguage.text.toString()
        val match = GroqApiClient.SUPPORTED_LANGUAGES.find { it.second == displayText }
        return match?.first ?: "auto"
    }

    private fun getSelectedTranslationCode(): String {
        val displayText = binding.actvTranslationLanguage.text.toString()
        val match = TRANSLATION_LANGUAGES.find { it.second == displayText }
        return match?.first ?: "none"
    }

    private fun setupSubtitleGeneration() {
        loadSavedApiKey()
        updateApiKeyBanner()
        setupLanguageSelector()
        tryAutoLoadCachedSubtitles()

        binding.btnSaveApiKey.setOnClickListener {
            val key = binding.etGroqApiKey.text.toString().trim()
            if (key.isNotBlank()) {
                saveGroqApiKey(key)
                updateApiKeyBanner()
                showSnackbar(getString(R.string.groq_api_key_saved))
            } else {
                showSnackbar(getString(R.string.error_no_api_key))
            }
        }

        binding.etGroqApiKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val key = binding.etGroqApiKey.text.toString().trim()
                if (key.isNotBlank()) {
                    saveGroqApiKey(key)
                    updateApiKeyBanner()
                }
            }
        }

        binding.btnGenerateSubtitles.setOnClickListener {
            if (isGeneratingSubtitles) return@setOnClickListener

            if (dialogueSegments.isNotEmpty()) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.btn_regenerate_subtitles))
                    .setMessage("This will replace the current subtitles and use your Groq API credits. Continue?")
                    .setPositiveButton("Re-generate") { _, _ ->
                        triggerSubtitleGeneration()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return@setOnClickListener
            }

            triggerSubtitleGeneration()
        }
    }

    private fun tryAutoLoadCachedSubtitles() {
        val videoPath = args.videoPath
        binding.tvSubtitleStatus.visibility = View.VISIBLE
        binding.tvSubtitleStatus.text = getString(R.string.subtitle_loading_cached)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val cachedData = withContext(Dispatchers.IO) {
                    viewModel.getTranscriptionCuesWithMeta(videoPath)
                }
                if (cachedData.cues.isNotEmpty()) {
                    loadSubtitleCues(cachedData.cues, fromCache = true)

                    val currentTranslation = getSelectedTranslationCode()
                    val cachedTranslation = cachedData.translationLanguage ?: "none"
                    if (currentTranslation != cachedTranslation) {
                        val currentName = TRANSLATION_LANGUAGES.find { it.first == currentTranslation }?.second ?: currentTranslation
                        val cachedName = TRANSLATION_LANGUAGES.find { it.first == cachedTranslation }?.second ?: cachedTranslation
                        appendDebugLog("NOTE: Cached translation is $cachedName, but you selected $currentName.")
                        appendDebugLog("Click 'Generate' to re-generate with $currentName (uses API credits).")
                        binding.tvSubtitleStatus.text = "Cached: ${cachedData.cues.size} segments ($cachedName). Re-generate for $currentName?"
                    }
                } else {
                    binding.tvSubtitleStatus.text = getString(R.string.btn_generate_subtitles)
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to auto-load cached subtitles")
                binding.tvSubtitleStatus.text = getString(R.string.btn_generate_subtitles)
            }
        }
    }

    private fun triggerSubtitleGeneration() {
        val enteredKey = binding.etGroqApiKey.text.toString().trim()
        if (enteredKey.isNotBlank()) {
            saveGroqApiKey(enteredKey)
            updateApiKeyBanner()
        }

        val apiKey = getGroqApiKey()
        if (apiKey.isBlank()) {
            showSnackbar(getString(R.string.error_no_api_key))
            return
        }

        selectedLanguageCode = getSelectedLanguageCode()
        selectedTranslationCode = getSelectedTranslationCode()

        val videoPath = args.videoPath
        val contentUri = args.contentUri
        val effectivePath = if (contentUri.isNotBlank()) contentUri else videoPath
        if (videoPath.isBlank()) {
            showSnackbar(getString(R.string.error_invalid_video_path))
            return
        }

        startSubtitleGeneration(apiKey, effectivePath, videoPath)
    }

    private fun loadSubtitleCues(cues: List<SubtitleCue>, fromCache: Boolean = true) {
        dialogueSegments = cues.mapIndexed { index, cue ->
            Segment(
                id = index,
                text = cue.text.substringBefore("\n→"),
                startSec = cue.startMs / 1000.0,
                endSec = cue.endMs / 1000.0
            )
        }
        translatedTexts = cues.mapIndexedNotNull { index, cue ->
            val translationLine = cue.text.substringAfter("\n→ ", "")
            if (translationLine.isNotEmpty() && dialogueSegments.getOrNull(index) != null) {
                dialogueSegments[index].id to translationLine
            } else null
        }.toMap()
        selectedSegmentIndex = -1

        if (fromCache) {
            binding.tvSubtitleStatus.text = getString(R.string.subtitle_cached_loaded, cues.size)
            appendDebugLog("AUTO-LOADED: ${cues.size} segments from database (0 API calls, 0 credits)")
        } else {
            binding.tvSubtitleStatus.text = getString(R.string.subtitle_generated, cues.size)
            appendDebugLog("Loaded ${cues.size} cached transcriptions")
        }

        showDialogueList(dialogueSegments)

        // Auto-switch to Talk tab when subtitles are loaded
        switchTab(TAB_TALK)
    }

    private fun startSubtitleGeneration(apiKey: String, effectivePath: String, videoPath: String) {
        isGeneratingSubtitles = true
        debugLog.clear()
        binding.progressSubtitles.visibility = View.VISIBLE
        binding.tvSubtitleStatus.visibility = View.VISIBLE
        binding.tvSubtitleStatus.text = getString(R.string.subtitle_step_preparing)
        // Only show debug log in debug builds
        if (BuildConfig.DEBUG) {
            binding.tvDebugLog.visibility = View.VISIBLE
            binding.tvDebugLog.text = ""
        }
        binding.btnGenerateSubtitles.isEnabled = false

        appendDebugLog("=== TRANSCRIPTION STARTED ===")
        appendDebugLog("File: ${effectivePath.take(80)}")
        appendDebugLog("Language: $selectedLanguageCode → Translation: $selectedTranslationCode")
        appendDebugLog("API key: ${apiKey.take(10)}...${apiKey.takeLast(4)}")

        val wantsTranslation = selectedTranslationCode != "none"

        subtitleGenerationJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val segments: List<Segment>
                val finalTranslatedTexts: Map<Int, String>
                var finalTranslationLanguage: String?

                val mainHandler = Handler(Looper.getMainLooper())
                val onProgress = GroqApiClient.ProgressCallback { step ->
                    mainHandler.post {
                        if (_binding != null) {
                            binding.tvSubtitleStatus.text = step
                            appendDebugLog(step)
                        }
                    }
                }

                if (wantsTranslation) {
                    val result = withContext(Dispatchers.IO) {
                        groqApiClient.transcribeAndTranslate(
                            requireContext(), apiKey, effectivePath,
                            selectedLanguageCode, selectedTranslationCode,
                            onProgress
                        )
                    }
                    segments = result.segments
                    finalTranslatedTexts = result.translatedTexts
                    finalTranslationLanguage = result.targetLanguage
                } else {
                    appendDebugLog("Translation: DISABLED (subtitles only mode)")
                    segments = withContext(Dispatchers.IO) {
                        groqApiClient.transcribeAudio(
                            requireContext(), apiKey, effectivePath,
                            selectedLanguageCode, onProgress
                        )
                    }
                    finalTranslatedTexts = emptyMap()
                    finalTranslationLanguage = null
                }

                translatedTexts = finalTranslatedTexts
                dialogueSegments = segments
                selectedSegmentIndex = -1
                isGeneratingSubtitles = false
                binding.progressSubtitles.visibility = View.GONE

                if (wantsTranslation && finalTranslatedTexts.isEmpty()) {
                    appendDebugLog("WARNING: Translation API returned no results — saved transcription without translation")
                    finalTranslationLanguage = null
                }

                if (segments.isEmpty()) {
                    binding.tvSubtitleStatus.text = getString(R.string.subtitle_no_segments)
                    appendDebugLog("RESULT: No segments found")
                } else {
                    binding.tvSubtitleStatus.text = getString(R.string.subtitle_generated, segments.size)
                    appendDebugLog("SUCCESS: ${segments.size} segments found!")
                    if (finalTranslatedTexts.isNotEmpty()) {
                        appendDebugLog("Translation: ${finalTranslatedTexts.size} segments translated to $finalTranslationLanguage")
                    } else if (wantsTranslation) {
                        appendDebugLog("Translation: FAILED — transcription saved without translation")
                    } else {
                        appendDebugLog("Translation: None (subtitles only)")
                    }
                    for ((i, seg) in segments.take(5).withIndex()) {
                        val translation = finalTranslatedTexts[seg.id]
                        appendDebugLog("  [$i] ${formatMsToTime(seg.startMs)}-${formatMsToTime(seg.endMs)}: ${seg.text.take(50)}")
                        if (translation != null) {
                            appendDebugLog("       → $translation")
                        }
                    }
                    if (segments.size > 5) appendDebugLog("  ... and ${segments.size - 5} more")

                    viewModel.saveTranscription(
                        videoPath = args.videoPath,
                        segments = segments,
                        languageCode = selectedLanguageCode,
                        isTranslation = false,
                        translatedTexts = finalTranslatedTexts,
                        translationLanguage = finalTranslationLanguage
                    )
                    groqApiClient.saveSrtFile(requireContext(), segments, args.videoPath.substringAfterLast("/"), finalTranslatedTexts)
                    appendDebugLog("Transcriptions saved to database + SRT file")

                    showDialogueList(segments)

                    // Auto-switch to Talk tab when subtitles are ready
                    switchTab(TAB_TALK)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                isGeneratingSubtitles = false
                throw e
            } catch (e: ApiKeyException) {
                Timber.e(e, "API key is invalid/forbidden")
                isGeneratingSubtitles = false
                if (_binding != null) {
                    binding.progressSubtitles.visibility = View.GONE
                    binding.tvSubtitleStatus.text = "API KEY ERROR"
                }
                appendDebugLog("API KEY ERROR: ${e.message}")
                appendDebugLog("Go to console.groq.com → API Keys → Create new key")
            } catch (e: SubtitleException) {
                Timber.e(e, "Subtitle generation failed")
                isGeneratingSubtitles = false
                if (_binding != null) {
                    binding.progressSubtitles.visibility = View.GONE
                    binding.tvSubtitleStatus.text = e.message ?: getString(R.string.subtitle_error_short)
                }
                appendDebugLog("FAILED: ${e.message}")
            } catch (e: Exception) {
                Timber.e(e, "Failed to generate subtitles")
                isGeneratingSubtitles = false
                if (_binding != null) {
                    binding.progressSubtitles.visibility = View.GONE
                    binding.tvSubtitleStatus.text = getString(R.string.subtitle_error, e.message ?: "Unknown error")
                }
                appendDebugLog("ERROR: ${e.message}")
            } finally {
                isGeneratingSubtitles = false
                if (_binding != null) {
                    binding.btnGenerateSubtitles.isEnabled = true
                }
            }
        }
    }

    private fun appendDebugLog(line: String) {
        debugLog.append(line).append("\n")
        if (BuildConfig.DEBUG) {
            binding.tvDebugLog.text = debugLog.toString()
            val scrollAmount = binding.tvDebugLog.layout?.let { layout ->
                val lineCount = layout.lineCount
                if (lineCount > 0) layout.getLineTop(lineCount) - binding.tvDebugLog.height else 0
            } ?: 0
            if (scrollAmount > 0) binding.tvDebugLog.scrollTo(0, scrollAmount)
        }
    }

    private fun showDialogueList(segments: List<Segment>) {
        binding.rvDialogueList.visibility = View.VISIBLE
        binding.layoutDialogueLoopControls.visibility = View.VISIBLE

        binding.rvDialogueList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = DialogueAdapter(segments, translatedTexts) { segment, index ->
                selectedSegmentIndex = index
                onDialogueSegmentSelected(segment)
            }
        }
    }

    private fun onDialogueSegmentSelected(segment: Segment) {
        binding.etRangeStart.setText(formatMsToTime(segment.startMs))
        binding.etRangeEnd.setText(formatMsToTime(segment.endMs))

        val isCurrentlyPlaying = AudioPlaybackService.isPlaying &&
            AudioPlaybackService.currentVideoPath == args.videoPath

        if (isCurrentlyPlaying) {
            AudioPlaybackService.seekToPosition(requireContext(), args.videoPath, segment.startMs)
        }

        showSnackbar(getString(R.string.dialogue_selected, segment.text.take(30)))
    }

    // ══════════════════════════════════════════════════════════════════════
    // DIALOGUE LOOP CONTROLS
    // ══════════════════════════════════════════════════════════════════════

    private fun setupDialogueLoopControls() {
        binding.tvDialogueLoopCount.text = dialogueLoopCount.toString()

        binding.btnDialogueLoopMinus.setOnClickListener {
            if (dialogueLoopCount > 1) {
                dialogueLoopCount--
                binding.tvDialogueLoopCount.text = dialogueLoopCount.toString()
            }
        }

        binding.btnDialogueLoopPlus.setOnClickListener {
            if (dialogueLoopCount < 100) {
                dialogueLoopCount++
                binding.tvDialogueLoopCount.text = dialogueLoopCount.toString()
            }
        }

        binding.btnLoopDialogue.setOnClickListener {
            if (selectedSegmentIndex < 0 || selectedSegmentIndex >= dialogueSegments.size) {
                showSnackbar(getString(R.string.error_no_dialogue_selected))
                return@setOnClickListener
            }

            val segment = dialogueSegments[selectedSegmentIndex]
            val effectiveLoopCount = dialogueLoopCount

            val isCurrentlyPlaying = AudioPlaybackService.isPlaying &&
                AudioPlaybackService.currentVideoPath == args.videoPath

            if (isCurrentlyPlaying) {
                AudioPlaybackService.setABLoop(
                    requireContext(), args.videoPath,
                    segment.startMs, segment.endMs, effectiveLoopCount
                )
            } else {
                AudioPlaybackService.startService(requireContext(), args.videoPath)
                positionHandler.postDelayed({
                    AudioPlaybackService.setABLoop(
                        requireContext(), args.videoPath,
                        segment.startMs, segment.endMs, effectiveLoopCount
                    )
                }, 1000)
            }

            showSnackbar(getString(R.string.dialogue_loop_active, effectiveLoopCount, segment.text.take(20)))
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // BUTTONS & OBSERVERS
    // ══════════════════════════════════════════════════════════════════════

    private fun setupApplyButton() {
        binding.btnApply.setOnClickListener {
            val rangeStartMs = parseTimeToMs(binding.etRangeStart.text.toString())
            val rangeEndMs = parseTimeToMs(binding.etRangeEnd.text.toString())
            val effectiveLoopCount = loopCount
            val speed = getCurrentSpeed()

            var hasError = false
            if (rangeStartMs < 0) {
                binding.tilRangeStart.error = getString(R.string.error_range_start_negative)
                hasError = true
            } else {
                binding.tilRangeStart.error = null
            }

            if (rangeEndMs > 0 && rangeEndMs <= rangeStartMs) {
                binding.tilRangeEnd.error = getString(R.string.error_range_end_before_start)
                hasError = true
            } else {
                binding.tilRangeEnd.error = null
            }

            if (effectiveLoopCount < 1) {
                showSnackbar(getString(R.string.error_loop_count_minimum))
                hasError = true
            }

            if (hasError) return@setOnClickListener

            viewModel.updateConfig(
                rangeStartMs = rangeStartMs,
                rangeEndMs = if (binding.etRangeEnd.text.isNullOrBlank()) -1L else rangeEndMs,
                loopCount = effectiveLoopCount,
                speed = speed
            )
            viewModel.saveConfig()
        }
    }

    private fun setupClearButton() {
        binding.btnClear.setOnClickListener {
            binding.etRangeStart.setText("0:00")
            binding.etRangeEnd.setText("")
            loopCount = 3
            binding.tvLoopCount.text = loopCount.toString()
            binding.tilRangeStart.error = null
            binding.tilRangeEnd.error = null
            currentSpeedIndex = SpeedPresets.ALL.indexOf(SpeedPresets.DEFAULT)
            binding.btnSpeedToggle.text = SpeedPresets.DEFAULT.label
        }
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.config.collect { config ->
                config?.let {
                    binding.etRangeStart.setText(formatMsToTime(it.rangeStartMs))
                    if (it.rangeEndMs > 0) {
                        binding.etRangeEnd.setText(formatMsToTime(it.rangeEndMs))
                    } else {
                        binding.etRangeEnd.setText("")
                    }
                    loopCount = it.loopCount
                    binding.tvLoopCount.text = loopCount.toString()
                    updateSpeedToggle(it.speed)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isSaved.collect { saved ->
                if (saved) {
                    showSnackbar(getString(R.string.settings_saved))
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.saveError.collect { error ->
                error?.let {
                    showSnackbar(getString(R.string.error_save_failed))
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // UTILITY
    // ══════════════════════════════════════════════════════════════════════

    private fun formatMsToTime(ms: Long): String = TimeUtils.formatMsToTime(ms)

    /** Parses time strings like "1:23", "1:02:30", or raw seconds like "83". */
    private fun parseTimeToMs(text: String): Long {
        if (text.isBlank()) return 0L
        return try {
            val parts = text.trim().split(":")
            when (parts.size) {
                1 -> (parts[0].toLongOrNull() ?: 0L) * 1000L
                2 -> (parts[0].toLongOrNull() ?: 0L) * 60_000L + (parts[1].toLongOrNull() ?: 0L) * 1000L
                3 -> (parts[0].toLongOrNull() ?: 0L) * 3_600_000L +
                     (parts[1].toLongOrNull() ?: 0L) * 60_000L +
                     (parts[2].toLongOrNull() ?: 0L) * 1000L
                else -> 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    private fun showSnackbar(message: String) {
        view?.let { rootView ->
            Snackbar.make(rootView, message, Snackbar.LENGTH_LONG)
                .setBackgroundTint(resources.getColor(R.color.colorInverseSurface, null))
                .setTextColor(resources.getColor(R.color.colorInverseOnSurface, null))
                .show()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // INNER ADAPTER — Dialogue list with selection
    // ══════════════════════════════════════════════════════════════════════

    private inner class DialogueAdapter(
        private val segments: List<Segment>,
        private val translations: Map<Int, String>,
        private val onSegmentClick: (Segment, Int) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<DialogueAdapter.ViewHolder>() {

        private var selectedPos = -1

        inner class ViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val tvTimestamp: TextView = view.findViewById(R.id.tv_cue_timestamp)
            val tvText: TextView = view.findViewById(R.id.tv_cue_text)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_subtitle_cue, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val segment = segments[position]
            holder.tvTimestamp.text = "${formatMsToTime(segment.startMs)} - ${formatMsToTime(segment.endMs)}"

            val translation = translations[segment.id]
            holder.tvText.text = if (translation != null) {
                "${segment.text}\n→ $translation"
            } else {
                segment.text
            }

            holder.itemView.isSelected = position == selectedPos
            holder.itemView.setOnClickListener {
                val oldPos = selectedPos
                selectedPos = holder.bindingAdapterPosition
                notifyItemChanged(oldPos)
                notifyItemChanged(selectedPos)
                onSegmentClick(segment, selectedPos)
            }
        }

        override fun getItemCount() = segments.size
    }

    // ══════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    override fun onResume() {
        super.onResume()
        startPositionPolling()
    }

    override fun onPause() {
        super.onPause()
        stopPositionPolling()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopPositionPolling()
        subtitleGenerationJob?.cancel()
        _binding = null
    }
}
