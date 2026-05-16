# Task: LoopLingo Horizon v3.0.0 Implementation

## Summary of All Changes Made

### 1. Layout XML Changes

#### `fragment_playback_settings.xml`
- Added subtitle overlay card (`card_subtitle_overlay`) with `tv_subtitle_original` and `tv_subtitle_translation` below the "Now Playing" card
- Added "Start Auto-Loop" button (`btn_start_dialogue_auto_loop`) in the AI Subtitles section
- Added Loop Templates section with `tv_template_status`, `btn_create_dialogue_template`, and `layout_template_list`

#### `mini_player.xml`
- Added `tv_mini_subtitle` TextView between title row and seek bar for showing active subtitle

#### `item_subtitle_cue.xml`
- Added `tv_cue_translation` TextView for showing Bangla translation separately below the original text
- Wrapped text views in a vertical LinearLayout

#### `strings.xml`
- Added string resources for: live subtitle, loop templates, dialogue auto-loop, template CRUD operations

### 2. SubtitleCue Model Update
- Added `splitOriginalAndTranslation()` method
- Added `originalText`, `translationText`, and `hasTranslation` properties

### 3. PlaybackSettingsFragment Changes
- Injected `TranscriptRepository` and `LoopTemplateRepository` via Hilt
- Added `updateSubtitleOverlay()` method called from position polling (every 500ms)
- Shows/hides subtitle overlay card based on playback state and cue availability
- Auto-scrolls dialogue RecyclerView to highlight active cue
- Updated `DialogueAdapter` to use separate `tv_cue_text` and `tv_cue_translation` TextViews
- Added `setupLoopTemplateUI()` with template creation/activation/deletion
- Added `startDialogueAutoLoop()` which serializes cues to JSON and sends to AudioPlaybackService
- Added `createDialogueRepeatTemplate()`, `loadTemplates()`, `displayTemplates()`

### 4. MainFragment Changes
- Injected `TranscriptRepository` via Hilt
- In mini player polling, calls `transcriptRepository.getActiveCue()` and updates `tv_mini_subtitle`
- Shows/hides mini subtitle based on active cue availability

### 5. LoopTemplate Entities + DAO + DB Migration
- Created `LoopTemplateEntity` (Room entity with id, videoPath, name, type, defaultLoopCount, createdAt)
- Created `LoopTemplateRangeEntity` (Room entity with FK to LoopTemplateEntity, startMs, endMs, loopCount)
- Created `LoopTemplateDao` with full CRUD operations
- Created `LoopTemplate` and `LoopTemplateRange` domain models
- Created `LoopTemplateRepository` with save/delete/get operations and entity-domain mapping
- Added MIGRATION_7_8 in DatabaseModule creating both new tables
- Bumped AppDatabase version to 8
- Added `loopTemplateDao()` abstract method and provider

### 6. AudioPlaybackService Dialogue Auto-Loop
- Added `ACTION_SET_DIALOGUE_AUTO_LOOP` and `EXTRA_DIALOGUE_CUES_JSON` constants
- Added dialogue auto-loop state variables (cues list, current index/iteration, active flag)
- Added `setDialogueAutoLoop()` static method
- Added `handleDialogueAutoLoop()` which parses JSON cues, sets up A-B loop for first cue
- Added `advanceToNextDialogueCue()` which advances to next cue after current cue completes its loops
- Modified `checkABPositionAndReschedule()` to call `advanceToNextDialogueCue()` when dialogue auto-loop is active

### 7. Version Bump
- versionCode: 25 → 26
- versionName: "2.1.1" → "3.0.0"
