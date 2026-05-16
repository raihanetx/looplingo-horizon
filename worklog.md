---
Task ID: 1
Agent: Super Z (Main)
Task: Implement v3.0.0 features - Real-time subtitle display, Loop templates, Dialogue auto-loop

Work Log:
- Analyzed entire codebase (6,000+ lines across 15+ files) to understand current architecture
- Identified critical gap: subtitles generated but never displayed during playback
- Implemented real-time subtitle overlay in PlaybackSettingsFragment (tv_subtitle_original + tv_subtitle_translation)
- Added mini player subtitle display (tv_mini_subtitle) in MainFragment
- Added SubtitleCue.splitOriginalAndTranslation() helper for clean English/translation separation
- Updated DialogueAdapter to show translation in separate tv_cue_translation TextView
- Created LoopTemplate entity + LoopTemplateRange entity with FK cascade
- Created LoopTemplateDao with full CRUD + Flow support
- Created LoopTemplateRepository with entity-domain mapping
- Implemented DB migration v7→v8 (loop_templates + loop_template_ranges tables)
- Implemented dialogue-by-dialogue auto-loop in AudioPlaybackService
  - New ACTION_SET_DIALOGUE_AUTO_LOOP action
  - Cues serialized as JSON, passed via intent
  - A-B monitor auto-advances to next cue after N loops complete
- Added loop template UI: Create template, Start Auto-Loop, template list with use/delete
- Updated CI workflow to build debug + release APKs
- Pushed v3.0.0 to GitHub, verified APK in releases

Stage Summary:
- v3.0.0 (versionCode 26) pushed to GitHub with tag v3.0.0
- Release APK (3.3MB) available at https://github.com/raihanetx/looplingo-horizon/releases/tag/v3.0.0
- All features implemented: subtitle display, dialogue auto-loop, loop templates, time-range templates
- DB schema: v8 with loop_templates + loop_template_ranges tables
