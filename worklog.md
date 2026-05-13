---
Task ID: 1
Agent: Main Agent
Task: Deep re-analyze entire LoopLingo Horizon app and fix all issues for production readiness

Work Log:
- Read all 31 Kotlin source files, AndroidManifest.xml, and build.gradle
- Identified the codebase was already partially fixed in previous sessions (MIME types, whisper-large-v3, SubtitleCue, DB migrations, Hilt injection)
- Found remaining critical bugs and missing features
- Fixed missing READ_MEDIA_AUDIO permission in AndroidManifest.xml
- Fixed 3gp MIME type (video/3gpp → audio/3gpp) in GroqApiClient.getCorrectMediaType()
- Cached EncryptedSharedPreferences in PlaybackSettingsFragment (was recreating AES keys on every call)
- Added transcribeAndTranslate() method to GroqApiClient for one-flow transcription + translation
- Added translateSegmentsViaChat() using Groq Chat API (llama-3.3-70b-versatile) for Bangla translation
- Added TranscriptionWithTranslation data class with segments + translatedTexts map
- Updated TranscriptionEntity with translatedText and translationLanguage columns
- Added DB migration v6→v7 for new columns
- Updated TranscriptRepository.saveTranscriptions() to accept translatedTexts and translationLanguage
- Updated PlaybackSettingsViewModel.saveTranscription() to pass through translation data
- Updated PlaybackSettingsFragment to use transcribeAndTranslate() instead of just transcribeAudio()
- Updated DialogueAdapter to display original text + translation side by side
- Updated toSubtitleCue() to include translation text when available

Stage Summary:
- 8 files modified with production-ready fixes
- Key feature: English transcription + Bangla translation in ONE flow (2 API calls total)
- All DB migrations properly defined (v1→v7)
- No chunking removal needed - it only triggers for very large files (>1hr after FLAC preprocessing)
