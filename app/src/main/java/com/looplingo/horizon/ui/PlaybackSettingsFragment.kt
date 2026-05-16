package com.looplingo.horizon.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.looplingo.horizon.BuildConfig
import com.looplingo.horizon.R
import com.looplingo.horizon.api.GroqApiClient
import com.looplingo.horizon.api.GroqApiClient.ApiKeyException
import com.looplingo.horizon.api.GroqApiClient.Segment
import com.looplingo.horizon.api.GroqApiClient.SubtitleException
import com.looplingo.horizon.databinding.FragmentPlaybackSettingsBinding
import com.looplingo.horizon.model.LoopTemplate
import com.looplingo.horizon.model.SpeedPresets
import com.looplingo.horizon.model.SubtitleCue
import com.looplingo.horizon.playback.AudioPlaybackService
import com.looplingo.horizon.repository.LoopTemplateRepository
import com.looplingo.horizon.repository.TranscriptRepository
import com.looplingo.horizon.ui.viewmodel.PlaybackSettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.looplingo.horizon.util.TimeUtils
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject

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
    @Inject lateinit var transcriptRepository: TranscriptRepository
    @Inject lateinit var loopTemplateRepository: LoopTemplateRepository
    private var dialogueSegments: List<Segment> = emptyList()
    private var translatedTexts: Map<Int, String> = emptyMap()  // segment.id → translation
    private var selectedSegmentIndex: Int = -1
    private var dialogueLoopCount: Int = 3
    private var isGeneratingSubtitles: Boolean = false
    private var subtitleGenerationJob: kotlinx.coroutines.Job? = null
    private val debugLog = StringBuilder()
    private var lastActiveCueIndex: Int = -1  // Track last active cue for auto-scroll

    private val securePrefsName = "looplingo_secure_prefs"
    private val keyGroqApiKey = "groq_api_key"
    private val keyLanguage = "whisper_language"
    private val keyTranslationLanguage = "translation_language"
    private var selectedLanguageCode = "auto"
    private var selectedTranslationCode = "none"  // Default: No translation (saves credits!)

    // Cached EncryptedSharedPreferences — avoid re-creating on every read/write
    // (AES key derivation is expensive, ~50ms per creation)
    private var cachedEncryptedPrefs: SharedPreferences? = null

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
        setupToolbar()
        setupTransportControls()
        setupSpeedChips()
        setupTryLoopButton()
        setupApplyButton()
        setupClearButton()
        setupNowPlayingCard()
        setupSubtitleGeneration()
        setupDialogueLoopControls()
        setupLoopTemplateUI()
        setupObservers()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
    }

    // ══════════════════════════════════════════════════════════════════════
    // TRANSPORT CONTROLS
    // ══════════════════════════════════════════════════════════════════════

    private fun setupTransportControls() {
        binding.ivPlayPause.setOnClickListener {
            val isCurrentlyPlaying = AudioPlaybackService.isPlaying &&
                AudioPlaybackService.currentVideoPath == args.videoPath
            if (isCurrentlyPlaying) {
                AudioPlaybackService.togglePlayback(requireContext())
            } else {
                AudioPlaybackService.startService(requireContext(), args.videoPath)
            }
        }

        binding.ivStop.setOnClickListener {
            AudioPlaybackService.stopService(requireContext())
        }

        binding.ivRewind5.setOnClickListener {
            AudioPlaybackService.seekBackward(requireContext(), 5000L)
        }

        binding.ivForward5.setOnClickListener {
            AudioPlaybackService.seekForward(requireContext(), 5000L)
        }
    }

    private fun updateTransportControlState() {
        val isCurrentlyPlaying = AudioPlaybackService.isPlaying &&
            AudioPlaybackService.currentVideoPath == args.videoPath

        binding.ivPlayPause.setImageResource(
            if (isCurrentlyPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )

        if (isCurrentlyPlaying) {
            val title = AudioPlaybackService.currentVideoPath.substringAfterLast("/").substringBeforeLast(".")
            binding.tvNowPlayingTitle.text = title
            binding.tvNowPlayingPosition.text = formatMsToTime(AudioPlaybackService.currentPositionMs)
        } else if (AudioPlaybackService.currentVideoPath.isBlank()) {
            binding.tvNowPlayingTitle.text = args.videoPath.substringAfterLast("/").substringBeforeLast(".")
            binding.tvNowPlayingPosition.text = getString(R.string.now_playing_idle)
        }
    }

    private fun setupSpeedChips() {
        val chipGroup = binding.chipGroupSpeed
        chipGroup.removeAllViews()

        for (preset in SpeedPresets.ALL) {
            val chip = Chip(requireContext()).apply {
                text = preset.label
                isCheckable = true
                id = View.generateViewId()
                tag = preset.speed
            }
            chip.setOnClickListener {
                AudioPlaybackService.setSpeed(requireContext(), preset.speed)
                Timber.d("Speed changed to %s (instant)", preset.label)
            }
            chipGroup.addView(chip)
        }
    }

    private fun selectSpeedChip(speed: Float) {
        val chipGroup = binding.chipGroupSpeed
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? Chip ?: continue
            val chipSpeed = chip.tag as? Float ?: continue
            // Use tolerance-based comparison to avoid floating-point == issues
            chip.isChecked = (kotlin.math.abs(chipSpeed - speed) < 0.001f)
        }
    }

    private fun getSelectedSpeed(): Float {
        val chipId = binding.chipGroupSpeed.checkedChipId
        if (chipId == View.NO_ID) return SpeedPresets.DEFAULT.speed
        val chip = binding.chipGroupSpeed.findViewById<View>(chipId) as? Chip ?: return SpeedPresets.DEFAULT.speed
        return chip.tag as? Float ?: SpeedPresets.DEFAULT.speed
    }

    // ══════════════════════════════════════════════════════════════════════
    // TRY LOOP
    // ══════════════════════════════════════════════════════════════════════

    private fun setupTryLoopButton() {
        binding.btnTryLoop.setOnClickListener {
            val rangeStartMs = parseTimeToMs(binding.etRangeStart.text.toString())
            val rangeEndMs = parseTimeToMs(binding.etRangeEnd.text.toString())
            val loopCount = binding.etLoopCount.text.toString().toIntOrNull() ?: 1

            var hasError = false
            if (rangeEndMs > 0 && rangeEndMs <= rangeStartMs) {
                binding.tilRangeEnd.error = getString(R.string.error_range_end_before_start)
                hasError = true
            } else {
                binding.tilRangeEnd.error = null
            }

            if (loopCount < 1) {
                binding.tilLoopCount.error = getString(R.string.error_loop_count_minimum)
                hasError = true
            } else {
                binding.tilLoopCount.error = null
            }

            if (hasError) return@setOnClickListener

            val isCurrentlyPlaying = AudioPlaybackService.isPlaying &&
                AudioPlaybackService.currentVideoPath == args.videoPath

            if (isCurrentlyPlaying) {
                AudioPlaybackService.setABLoop(
                    requireContext(), args.videoPath, rangeStartMs,
                    if (binding.etRangeEnd.text.isNullOrBlank()) -1L else rangeEndMs, loopCount
                )
            } else {
                AudioPlaybackService.startService(requireContext(), args.videoPath)
                positionHandler.postDelayed({
                    AudioPlaybackService.setABLoop(
                        requireContext(), args.videoPath, rangeStartMs,
                        if (binding.etRangeEnd.text.isNullOrBlank()) -1L else rangeEndMs, loopCount
                    )
                }, 1000)
            }
            showSnackbar(getString(R.string.loop_preview_active))
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // AI SUBTITLES — Groq STT with smart pipeline
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Create or get encrypted SharedPreferences for storing sensitive data (API keys).
     * Uses AES256 encryption for both keys and values.
     */
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
            // Pre-fill from BuildConfig so user doesn't have to type it
            binding.etGroqApiKey.setText(BuildConfig.GROQ_API_KEY)
            saveGroqApiKey(BuildConfig.GROQ_API_KEY)
        }
    }

    private fun updateApiKeyBanner() {
        val apiKey = getGroqApiKey()
        binding.tvApiKeyBanner.visibility = if (apiKey.isBlank()) View.VISIBLE else View.GONE
    }

    /** Translation languages available for the Chat API translator.
     *  First option is "none" — disables translation entirely (saves API credits!). */
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

        // Load saved language preference
        val prefs = getEncryptedPrefs()
        val savedLangCode = prefs.getString(keyLanguage, "auto") ?: "auto"
        val savedDisplayName = languages.find { it.first == savedLangCode }?.second ?: displayNames[0]
        binding.actvLanguage.setText(savedDisplayName, false)
        selectedLanguageCode = savedLangCode

        // Save language when user selects one
        binding.actvLanguage.setOnItemClickListener { _, _, position, _ ->
            val (code, _) = languages[position]
            selectedLanguageCode = code
            prefs.edit().putString(keyLanguage, code).apply()
            Timber.d("Language selected: %s (%s)", displayNames[position], code)
        }

        // ── Translation language selector ──
        val translationDisplayNames = TRANSLATION_LANGUAGES.map { it.second }
        val translationAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, translationDisplayNames)
        binding.actvTranslationLanguage.setAdapter(translationAdapter)

        // Load saved translation language preference (default: none — saves credits)
        selectedTranslationCode = prefs.getString(keyTranslationLanguage, "none") ?: "none"
        val savedTranslationDisplayName = TRANSLATION_LANGUAGES.find { it.first == selectedTranslationCode }?.second ?: translationDisplayNames[0]
        binding.actvTranslationLanguage.setText(savedTranslationDisplayName, false)

        // Save translation language when user selects one
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

        // AUTO-LOAD: Check for cached transcriptions immediately on open.
        // If found, display them without making any API call — saves credits!
        tryAutoLoadCachedSubtitles()

        // Save API key button — explicit save action
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

        // Also save on focus lost
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

            // If subtitles are already showing, this is a "Re-generate" action.
            // Confirm before burning credits.
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

            // No existing subtitles — generate directly
            triggerSubtitleGeneration()
        }
    }

    /**
     * AUTO-LOAD: Automatically load cached transcriptions from Room DB
     * when the user opens the playback settings screen.
     *
     * This is the KEY credit-saving feature — if we already transcribed
     * this video before, we show the results instantly with ZERO API calls.
     * The user only needs to click "Generate" if no cache exists.
     */
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
                    // CACHE HIT — show subtitles immediately, no API call needed!
                    loadSubtitleCues(cachedData.cues, fromCache = true)

                    // Check if the cached translation language matches the user's current selection.
                    // If not, show a hint so the user knows they can re-generate with a different language.
                    // Normalize: null in DB = "none" (no translation), both mean the same thing.
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
                    // No cache — show "Generate" prompt
                    binding.tvSubtitleStatus.text = getString(R.string.btn_generate_subtitles)
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to auto-load cached subtitles")
                binding.tvSubtitleStatus.text = getString(R.string.btn_generate_subtitles)
            }
        }
    }

    /**
     * Trigger the subtitle generation process (API call to Groq).
     * Validates API key and resolves the video path before starting.
     */
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

    /**
     * Load subtitle cues into the UI — used both for auto-load (cache hit)
     * and for manual "Use Cached" action.
     *
     * @param cues The subtitle cues to display.
     * @param fromCache If true, shows "cached" status (no API call). If false, shows "generated".
     */
    private fun loadSubtitleCues(cues: List<SubtitleCue>, fromCache: Boolean = true) {
        // Convert SubtitleCue back to Segment for the dialogue list
        dialogueSegments = cues.mapIndexed { index, cue ->
            Segment(
                id = index,
                text = cue.text.substringBefore("\n→"),  // Remove translation part for segment text
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
            // Cache hit — show this clearly so the user knows NO API call was made
            binding.tvSubtitleStatus.text = getString(R.string.subtitle_cached_loaded, cues.size)
            appendDebugLog("AUTO-LOADED: ${cues.size} segments from database (0 API calls, 0 credits)")
        } else {
            binding.tvSubtitleStatus.text = getString(R.string.subtitle_generated, cues.size)
            appendDebugLog("Loaded ${cues.size} cached transcriptions")
        }

        showDialogueList(dialogueSegments)
    }

    /**
     * Start the actual subtitle generation process via Groq API.
     * Extracted from setupSubtitleGeneration to allow re-use from the
     * "use cached" dialog and direct invocation.
     */
    private fun startSubtitleGeneration(apiKey: String, effectivePath: String, videoPath: String) {
        isGeneratingSubtitles = true
        debugLog.clear()
        binding.progressSubtitles.visibility = View.VISIBLE
        binding.tvSubtitleStatus.visibility = View.VISIBLE
        binding.tvSubtitleStatus.text = getString(R.string.subtitle_step_preparing)
        binding.tvDebugLog.visibility = View.VISIBLE
        binding.tvDebugLog.text = ""
        binding.btnGenerateSubtitles.isEnabled = false

        appendDebugLog("=== TRANSCRIPTION STARTED ===")
        appendDebugLog("File: ${effectivePath.take(80)}")
        appendDebugLog("Language: $selectedLanguageCode → Translation: $selectedTranslationCode")
        appendDebugLog("API key: ${apiKey.take(10)}...${apiKey.takeLast(4)}")

        val wantsTranslation = selectedTranslationCode != "none"

        subtitleGenerationJob = viewLifecycleOwner.lifecycleScope.launch {
                try {
                    // Choose pipeline based on whether translation is needed:
                    // - "none" → transcription only (1 API call, saves credits!)
                    // - any language → transcription + translation (2 API calls)
                    val segments: List<Segment>
                    val finalTranslatedTexts: Map<Int, String>
                    var finalTranslationLanguage: String?

                    // Shared progress callback — safely updates UI from IO thread.
                    // Uses Handler.post() instead of launching a new coroutine per step.
                    // This is more efficient: 1 Handler message per step vs 1 Coroutine per step.
                    // Handler messages are ~10x lighter than coroutine launches.
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
                        // Full pipeline: transcribe + translate (2 API calls)
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
                        // Subtitles only — no translation (1 API call, saves credits!)
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
                    selectedSegmentIndex = -1  // Reset selection on new subtitles
                    isGeneratingSubtitles = false
                    binding.progressSubtitles.visibility = View.GONE

                    // BUG FIX: If translation was requested but failed silently (empty result),
                    // don't claim we have a translation — save translationLanguage as null.
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

                        // Persist transcriptions + translations to Room database
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
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Coroutine was cancelled (e.g., screen rotation) — don't touch UI, just rethrow
                    isGeneratingSubtitles = false
                    throw e
                } catch (e: ApiKeyException) {
                    // API key is dead — show clear message, no retry
                    Timber.e(e, "API key is invalid/forbidden")
                    isGeneratingSubtitles = false
                    if (_binding != null) {
                        binding.progressSubtitles.visibility = View.GONE
                        binding.tvSubtitleStatus.text = "✗ API KEY ERROR"
                    }
                    appendDebugLog("")
                    appendDebugLog("═══ API KEY ERROR ═══")
                    appendDebugLog(e.message ?: "API key error")
                    appendDebugLog("")
                    appendDebugLog("WHAT TO DO:")
                    appendDebugLog("1. Go to console.groq.com")
                    appendDebugLog("2. Click 'API Keys'")
                    appendDebugLog("3. Create a new key")
                    appendDebugLog("4. Paste it in the API key field above")
                    appendDebugLog("5. Click 'Save API Key'")
                } catch (e: SubtitleException) {
                    Timber.e(e, "Subtitle generation failed")
                    isGeneratingSubtitles = false
                    if (_binding != null) {
                        binding.progressSubtitles.visibility = View.GONE
                        binding.tvSubtitleStatus.text = e.message ?: getString(R.string.subtitle_error_short)
                    }
                    appendDebugLog("FAILED: ${e.message}")
                    appendDebugLog("Exception type: ${e.javaClass.simpleName}")
                    val lastResp = groqApiClient.getLastWhisperResponse()
                    if (lastResp.isNotBlank() && lastResp != "(null)") {
                        appendDebugLog("")
                        appendDebugLog("Last Whisper API response:")
                        appendDebugLog(lastResp.take(300))
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to generate subtitles")
                    isGeneratingSubtitles = false
                    if (_binding != null) {
                        binding.progressSubtitles.visibility = View.GONE
                        binding.tvSubtitleStatus.text = getString(R.string.subtitle_error, e.message ?: "Unknown error")
                    }
                    appendDebugLog("ERROR: ${e.message}")
                    appendDebugLog("Exception: ${e.javaClass.simpleName}")
                    val stackLines = e.stackTraceToString().lines().take(5)
                    for (line in stackLines) {
                        appendDebugLog("  $line")
                    }
                } finally {
                    isGeneratingSubtitles = false
                    if (_binding != null) {
                        binding.btnGenerateSubtitles.isEnabled = true
                    }
                }
        }
    }

    /** Append a line to the visible debug log panel */
    private fun appendDebugLog(line: String) {
        debugLog.append(line).append("\n")
        binding.tvDebugLog.text = debugLog.toString()
        // Auto-scroll to bottom
        val scrollAmount = binding.tvDebugLog.layout?.let { layout ->
            val lineCount = layout.lineCount
            if (lineCount > 0) layout.getLineTop(lineCount) - binding.tvDebugLog.height else 0
        } ?: 0
        if (scrollAmount > 0) binding.tvDebugLog.scrollTo(0, scrollAmount)
    }

    private fun showDialogueList(segments: List<Segment>) {
        binding.rvDialogueList.visibility = View.VISIBLE
        binding.layoutDialogueLoopControls.visibility = View.VISIBLE
        binding.btnStartDialogueAutoLoop.visibility = View.VISIBLE

        // Enable template creation now that subtitles are loaded
        binding.btnCreateDialogueTemplate.visibility = View.VISIBLE
        binding.tvTemplateStatus.text = getString(R.string.subtitle_generated, segments.size)

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
            val loopCount = dialogueLoopCount

            val isCurrentlyPlaying = AudioPlaybackService.isPlaying &&
                AudioPlaybackService.currentVideoPath == args.videoPath

            if (isCurrentlyPlaying) {
                AudioPlaybackService.setABLoop(
                    requireContext(), args.videoPath,
                    segment.startMs, segment.endMs, loopCount
                )
            } else {
                AudioPlaybackService.startService(requireContext(), args.videoPath)
                positionHandler.postDelayed({
                    AudioPlaybackService.setABLoop(
                        requireContext(), args.videoPath,
                        segment.startMs, segment.endMs, loopCount
                    )
                }, 1000)
            }

            showSnackbar(getString(R.string.dialogue_loop_active, loopCount, segment.text.take(20)))
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // LOOP TEMPLATES — Create, manage, and activate loop templates
    // ══════════════════════════════════════════════════════════════════════

    private fun setupLoopTemplateUI() {
        // Dialogue auto-loop button — starts sequential looping of all cues
        binding.btnStartDialogueAutoLoop.setOnClickListener {
            if (dialogueSegments.isEmpty()) {
                showSnackbar(getString(R.string.template_no_subtitles))
                return@setOnClickListener
            }
            startDialogueAutoLoop()
        }

        // Create dialogue repeat template
        binding.btnCreateDialogueTemplate.setOnClickListener {
            if (dialogueSegments.isEmpty()) {
                showSnackbar(getString(R.string.template_no_subtitles))
                return@setOnClickListener
            }
            createDialogueRepeatTemplate()
        }

        // Load existing templates for this video
        loadTemplates()
    }

    /**
     * Start the dialogue auto-loop mode.
     * Serializes subtitle cues as JSON and sends them to AudioPlaybackService
     * which will loop each dialogue × N times sequentially.
     */
    private fun startDialogueAutoLoop() {
        val videoPath = args.videoPath
        val isCurrentlyPlaying = AudioPlaybackService.isPlaying &&
            AudioPlaybackService.currentVideoPath == videoPath

        // Get cues from the transcript repository cache
        val cues = transcriptRepository.getSubtitlesForVideo(videoPath)
        if (cues.isEmpty()) {
            showSnackbar(getString(R.string.template_no_subtitles))
            return
        }

        // Serialize cues to JSON for the intent extra
        val cuesJson = JSONArray()
        for (cue in cues) {
            val obj = JSONObject().apply {
                put("index", cue.index)
                put("startMs", cue.startMs)
                put("endMs", cue.endMs)
                put("text", cue.text)
            }
            cuesJson.put(obj)
        }

        if (isCurrentlyPlaying) {
            AudioPlaybackService.setDialogueAutoLoop(
                requireContext(), videoPath, cuesJson.toString(), dialogueLoopCount
            )
        } else {
            AudioPlaybackService.startService(requireContext(), videoPath)
            positionHandler.postDelayed({
                AudioPlaybackService.setDialogueAutoLoop(
                    requireContext(), videoPath, cuesJson.toString(), dialogueLoopCount
                )
            }, 1000)
        }

        showSnackbar(getString(R.string.dialogue_auto_loop_active, 1, cues.size, dialogueLoopCount))
    }

    /**
     * Create a dialogue_repeat template and save it to the database.
     */
    private fun createDialogueRepeatTemplate() {
        val templateName = getString(R.string.template_dialogue_repeat, dialogueLoopCount)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val template = LoopTemplate(
                    videoPath = args.videoPath,
                    name = templateName,
                    type = "dialogue_repeat",
                    defaultLoopCount = dialogueLoopCount
                )
                loopTemplateRepository.saveTemplate(template)
                showSnackbar(getString(R.string.template_saved))
                loadTemplates()
            } catch (e: Exception) {
                Timber.e(e, "Failed to save template")
            }
        }
    }

    /**
     * Load templates for this video and display them in the template list.
     */
    private fun loadTemplates() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val templates = withContext(Dispatchers.IO) {
                    loopTemplateRepository.getTemplatesForVideo(args.videoPath)
                }
                displayTemplates(templates)
            } catch (e: Exception) {
                Timber.e(e, "Failed to load templates")
            }
        }
    }

    /**
     * Display templates in the template list container.
     */
    private fun displayTemplates(templates: List<LoopTemplate>) {
        val container = binding.layoutTemplateList
        container.removeAllViews()

        if (templates.isEmpty()) return

        for (template in templates) {
            val itemView = layoutInflater.inflate(R.layout.item_saved_timestamp, container, false)

            itemView.findViewById<TextView>(R.id.tv_timestamp_label).text = template.name
            val typeLabel = if (template.type == "dialogue_repeat") "Dialogue ×${template.defaultLoopCount}" else "Time-Range"
            itemView.findViewById<TextView>(R.id.tv_timestamp_range).text = typeLabel

            itemView.findViewById<View>(R.id.btn_use_timestamp).setOnClickListener {
                // Activate this template
                if (template.type == "dialogue_repeat") {
                    dialogueLoopCount = template.defaultLoopCount
                    binding.tvDialogueLoopCount.text = dialogueLoopCount.toString()
                    startDialogueAutoLoop()
                }
            }

            itemView.findViewById<View>(R.id.btn_delete_timestamp).setOnClickListener {
                viewLifecycleOwner.lifecycleScope.launch {
                    loopTemplateRepository.deleteTemplate(template.id)
                    showSnackbar(getString(R.string.template_deleted))
                    loadTemplates()
                }
            }

            container.addView(itemView)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // NOW PLAYING CARD
    // ══════════════════════════════════════════════════════════════════════

    private fun setupNowPlayingCard() {
        binding.tvNowPlayingTitle.text = args.videoPath.substringAfterLast("/").substringBeforeLast(".")
        binding.tvNowPlayingPosition.text = getString(R.string.now_playing_idle)
        startPositionPolling()
    }

    private fun startPositionPolling() {
        stopPositionPolling()
        positionPollingRunnable = object : Runnable {
            override fun run() {
                try {
                    updateTransportControlState()
                    updateSubtitleOverlay()
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
    // SUBTITLE OVERLAY — Real-time synced display during playback
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Update the subtitle overlay card with the active cue at the current
     * playback position. Called every 500ms from the position polling runnable.
     *
     * Uses TranscriptRepository.getActiveCue() for efficient binary search.
     * The overlay card is shown only when:
     *  - Audio is playing for this video
     *  - Subtitles are loaded in the cache
     */
    private fun updateSubtitleOverlay() {
        val isCurrentlyPlaying = AudioPlaybackService.isPlaying &&
            AudioPlaybackService.currentVideoPath == args.videoPath
        val position = AudioPlaybackService.currentPositionMs

        if (!isCurrentlyPlaying || dialogueSegments.isEmpty()) {
            binding.cardSubtitleOverlay.visibility = View.GONE
            return
        }

        val activeCue = transcriptRepository.getActiveCue(args.videoPath, position)

        if (activeCue != null) {
            binding.cardSubtitleOverlay.visibility = View.VISIBLE
            val (original, translation) = activeCue.splitOriginalAndTranslation()
            binding.tvSubtitleOriginal.text = original
            binding.tvSubtitleTranslation.text = translation ?: ""
            binding.tvSubtitleTranslation.visibility =
                if (translation != null) View.VISIBLE else View.GONE

            // Auto-scroll the dialogue RecyclerView to highlight the active cue
            val activeIndex = transcriptRepository.getActiveCueIndex(args.videoPath, position)
            if (activeIndex >= 0 && activeIndex != lastActiveCueIndex) {
                lastActiveCueIndex = activeIndex
                val layoutManager = binding.rvDialogueList.layoutManager as? LinearLayoutManager
                layoutManager?.scrollToPositionWithOffset(activeIndex, 0)
            }
        } else {
            // No active cue at current position — show placeholder
            binding.cardSubtitleOverlay.visibility = View.VISIBLE
            binding.tvSubtitleOriginal.text = getString(R.string.subtitle_no_active_cue)
            binding.tvSubtitleTranslation.text = ""
            binding.tvSubtitleTranslation.visibility = View.GONE
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // BUTTONS & OBSERVERS
    // ══════════════════════════════════════════════════════════════════════

    private fun setupApplyButton() {
        binding.btnApply.setOnClickListener {
            val rangeStartMs = parseTimeToMs(binding.etRangeStart.text.toString())
            val rangeEndMs = parseTimeToMs(binding.etRangeEnd.text.toString())
            val loopCount = binding.etLoopCount.text.toString().toIntOrNull() ?: 1
            val speed = getSelectedSpeed()

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

            if (loopCount < 1) {
                binding.tilLoopCount.error = getString(R.string.error_loop_count_minimum)
                hasError = true
            } else if (loopCount > 10000) {
                binding.tilLoopCount.error = getString(R.string.error_loop_count_maximum)
                hasError = true
            } else {
                binding.tilLoopCount.error = null
            }

            if (hasError) return@setOnClickListener

            viewModel.updateConfig(
                rangeStartMs = rangeStartMs,
                rangeEndMs = if (binding.etRangeEnd.text.isNullOrBlank()) -1L else rangeEndMs,
                loopCount = loopCount,
                speed = speed
            )
            viewModel.saveConfig()
        }
    }

    private fun setupClearButton() {
        binding.btnClear.setOnClickListener { viewModel.deleteConfig() }
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.config.collect { config ->
                        binding.etRangeStart.setText(formatMsToTime(config.rangeStartMs))
                        binding.etRangeEnd.setText(if (config.rangeEndMs <= 0) "" else formatMsToTime(config.rangeEndMs))
                        binding.etLoopCount.setText(config.loopCount.toString())
                        selectSpeedChip(config.speed)
                    }
                }

                launch {
                    viewModel.isSaved.collect { saved ->
                        if (saved) {
                            showSnackbar(getString(R.string.settings_saved))
                            findNavController().navigateUp()
                        }
                    }
                }

                launch {
                    viewModel.saveError.collect { errorMsg ->
                        errorMsg?.let {
                            showSnackbar(it) { viewModel.clearSaveError(); viewModel.saveConfig() }
                        }
                    }
                }

                launch {
                    viewModel.savedTimestamps.collect { timestamps ->
                        updateSavedTimestampsList(timestamps)
                    }
                }
            }
        }
    }

    private fun updateSavedTimestampsList(timestamps: List<com.looplingo.horizon.data.entity.SavedTimestampEntity>) {
        val container = binding.savedTimestampsContainer
        container.removeAllViews()

        if (timestamps.isEmpty()) {
            binding.layoutSavedTimestamps.visibility = View.GONE
            return
        }

        binding.layoutSavedTimestamps.visibility = View.VISIBLE

        for (ts in timestamps) {
            val itemView = layoutInflater.inflate(R.layout.item_saved_timestamp, container, false)
            itemView.findViewById<TextView>(R.id.tv_timestamp_label).text = ts.label
            itemView.findViewById<TextView>(R.id.tv_timestamp_range).text =
                "${formatMsToTime(ts.rangeStartMs)} → ${formatMsToTime(ts.rangeEndMs)} (x${ts.loopCount})"

            itemView.findViewById<View>(R.id.btn_use_timestamp).setOnClickListener {
                binding.etRangeStart.setText(formatMsToTime(ts.rangeStartMs))
                binding.etRangeEnd.setText(formatMsToTime(ts.rangeEndMs))
                binding.etLoopCount.setText(ts.loopCount.toString())
            }

            itemView.findViewById<View>(R.id.btn_delete_timestamp).setOnClickListener {
                viewModel.deleteTimestamp(ts)
            }

            container.addView(itemView)
        }
    }

    private fun parseTimeToMs(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val trimmed = text.trim()

        if (trimmed.contains(":")) {
            val parts = trimmed.split(":")
            if (parts.size == 2) {
                val minutes = parts[0].toLongOrNull() ?: 0L
                val seconds = parts[1].toLongOrNull() ?: 0L
                // Validate ranges: seconds must be 0-59
                val clampedSeconds = seconds.coerceIn(0, 59)
                return (minutes * 60 + clampedSeconds) * 1000
            }
            if (parts.size == 3) {
                val hours = parts[0].toLongOrNull() ?: 0L
                val minutes = parts[1].toLongOrNull() ?: 0L
                val seconds = parts[2].toLongOrNull() ?: 0L
                // Validate ranges: minutes and seconds must be 0-59
                val clampedMinutes = minutes.coerceIn(0, 59)
                val clampedSeconds = seconds.coerceIn(0, 59)
                return (hours * 3600 + clampedMinutes * 60 + clampedSeconds) * 1000
            }
        }

        val seconds = trimmed.toLongOrNull() ?: 0L
        return seconds * 1000
    }

    private fun formatMsToTime(ms: Long): String = TimeUtils.formatMsToTime(ms)

    private fun showSnackbar(message: String, action: (() -> Unit)? = null) {
        view?.let { rootView ->
            val snackbar = Snackbar.make(rootView, message, Snackbar.LENGTH_LONG)
            snackbar.setBackgroundTint(resources.getColor(R.color.colorInverseSurface, null))
            snackbar.setTextColor(resources.getColor(R.color.colorInverseOnSurface, null))
            snackbar.setActionTextColor(resources.getColor(R.color.colorInversePrimary, null))
            if (action != null) {
                snackbar.setAction(getString(R.string.retry)) { action() }
            }
            snackbar.show()
        }
    }

    override fun onPause() {
        super.onPause()
        // Stop position polling when fragment is backgrounded to save CPU/battery.
        // MainFragment correctly does this in onPause, but this fragment previously
        // didn't — the handler continued running even when the screen was off or
        // the user was in another app.
        stopPositionPolling()
    }

    override fun onResume() {
        super.onResume()
        // Restart position polling when fragment comes back to foreground.
        // Only restart if the binding is still available (view not destroyed).
        if (_binding != null) {
            startPositionPolling()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopPositionPolling()
        subtitleGenerationJob?.cancel()
        subtitleGenerationJob = null
        isGeneratingSubtitles = false
        cachedEncryptedPrefs = null  // Clear cached prefs to avoid leaking Context
        _binding = null
    }

    // ══════════════════════════════════════════════════════════════════════
    // DIALOGUE ADAPTER
    // ══════════════════════════════════════════════════════════════════════

    private inner class DialogueAdapter(
        private val segments: List<Segment>,
        private val translations: Map<Int, String>,
        private val onSegmentClick: (Segment, Int) -> Unit
    ) : RecyclerView.Adapter<DialogueAdapter.DialogueViewHolder>() {

        private var selectedPos = -1

        inner class DialogueViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTime: TextView = view.findViewById(R.id.tv_cue_timestamp)
            val tvText: TextView = view.findViewById(R.id.tv_cue_text)
            val tvTranslation: TextView = view.findViewById(R.id.tv_cue_translation)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DialogueViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_subtitle_cue, parent, false)
            return DialogueViewHolder(view)
        }

        override fun onBindViewHolder(holder: DialogueViewHolder, position: Int) {
            val segment = segments[position]
            holder.tvTime.text = "${formatMsToTime(segment.startMs)} → ${formatMsToTime(segment.endMs)}"

            // Show original text
            holder.tvText.text = segment.text

            // Show translation if available in a separate TextView
            val translation = translations[segment.id]
            if (translation != null) {
                holder.tvTranslation.text = "→ $translation"
                holder.tvTranslation.visibility = View.VISIBLE
            } else {
                holder.tvTranslation.text = ""
                holder.tvTranslation.visibility = View.GONE
            }

            holder.itemView.isSelected = (position == selectedPos)
            holder.itemView.setOnClickListener {
                val previousPos = selectedPos
                selectedPos = position
                // Only update the two changed items instead of the entire list
                if (previousPos >= 0 && previousPos < itemCount) {
                    notifyItemChanged(previousPos)
                }
                notifyItemChanged(position)
                onSegmentClick(segment, position)
            }
        }

        override fun getItemCount() = segments.size
    }
}
