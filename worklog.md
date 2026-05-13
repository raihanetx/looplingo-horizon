---
Task ID: 1
Agent: Main Agent (Super Z)
Task: Deep audit and production-ready fixes for LoopLingo-Horizon Android app

Work Log:
- Read all 20+ Kotlin source files in the project (GroqApiClient.kt 2343 lines, AudioPlaybackService.kt ~1000 lines, etc.)
- Read all layout XML files, build.gradle, AndroidManifest.xml
- Analyzed the full transcription pipeline architecture
- Identified that many previously reported "33 bugs" were already fixed in current codebase
- Found remaining issues: missing import, Android 14 crash, missing permissions, low max_tokens, no translation selector, no cache check
- Implemented all fixes across 6 files

Stage Summary:
- 10 code changes made across 6 files:
  1. VideoRepository.kt: Added missing `import javax.inject.Inject` (compile error fix)
  2. AudioPlaybackService.kt: Fixed `startForeground()` for Android 14+ with `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`
  3. MainFragment.kt: Added `READ_MEDIA_AUDIO` permission request alongside `READ_MEDIA_VIDEO` using `RequestMultiplePermissions`
  4. GroqApiClient.kt: Scaled translation `max_tokens` based on segment count (4096 + segments*50, max 16384)
  5. GroqApiClient.kt: Added more MIME type mappings (mpeg, mpga, opus) and warning for unknown extensions
  6. GroqApiClient.kt: Added size warning for large file copies in `copyContentUriToTempFile`
  7. PlaybackSettingsFragment.kt: Added translation language selector with 19 languages
  8. PlaybackSettingsFragment.kt: Added "cached transcriptions found" dialog before API call
  9. PlaybackSettingsFragment.kt: Extracted `startSubtitleGeneration()` and `loadCachedTranscriptions()` methods
  10. PlaybackSettingsViewModel.kt: Added `hasTranscriptions()` and `getTranscriptionCues()` methods
  11. fragment_playback_settings.xml: Added translation language AutoCompleteTextView
  12. ic_translate.xml: Created translate icon drawable
