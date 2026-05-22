# Worklog — Horizon Loop v6.1.0 Premium Dark UI Redesign

## Date: 2024-03-05

### Summary
Complete UI redesign of the Now Playing screen (PlaybackSettingsFragment) with a premium dark theme, emerald mint accent, tab pill navigation, and waveform seek bar.

### Files Modified

1. **app/build.gradle** — Version bump: versionCode 39→42, versionName "5.4.0"→"6.1.0"

2. **app/src/main/res/values/colors.xml** — Added brand palette:
   - brand_bg (#080b11), brand_card (#0f131c), brand_accent (#10b981), brand_accent_dark (#059669)
   - Glass surfaces: glass_bg, glass_border, glass_pill_bg, glass_pill_border
   - Slate text shades: slate_100 through slate_800
   - Waveform colors: waveform_played, waveform_unplayed
   - Loop card colors: loop_play_bg, loop_play_border, loop_delete_bg

3. **app/src/main/res/values-night/colors.xml** — Same brand palette added (dark-only design)

4. **app/src/main/res/values/dimens.xml** — Added v6.1.0 dimensions:
   - footer_card_corner_radius, footer_card_margin
   - waveform_height, transport_rewind_size, transport_play_size, transport_icon_size, transport_play_icon_size
   - header_height, header_icon_size, header_avatar_size
   - tab_pill_height

5. **app/src/main/res/layout/fragment_playback_settings.xml** — Complete rewrite:
   - RelativeLayout root with brand_bg background
   - Compact header bar (back + profile avatar + name/role + notification + loop + speed)
   - FrameLayout with 4 switchable panels (Clean / Dialogue / Loop / Notes)
   - Clean panel: centered EN+BN text + subtitle generation controls
   - Dialogue panel: RecyclerView + dialogue loop controls
   - Loop panel: saved loops header + add loop form with glass styling
   - Notes panel: saved notes header + add note form with glass styling
   - Tab navigation pill (super-glass-pill background)
   - Floating footer card (super-glass background) with waveform seekbar + transport controls
   - Hidden compatibility views for existing IDs

6. **app/src/main/java/com/looplingo/horizon/ui/PlaybackSettingsFragment.kt** — Major update:
   - New setupHeader() for back/profile/loop cycle/speed buttons
   - New setupTabs() with tab switching and long-press for form toggling
   - New switchTab() and updateTabStyle() with accent/slate colors
   - setupWaveformSeekBar() replacing standard SeekBar with WaveformSeekBar
   - updateNowPlayingState() now updates footer title, subtitle, waveform progress
   - updateCleanView() shows current dialogue EN+BN text
   - updateFooterSubtitle() shows loop state + tab + speed
   - Header speed button cycles speed presets with visual feedback
   - Header loop cycle button cycles through repeat modes (off/all/one/infinite)
   - All existing functionality preserved (subtitles, AB loop, dialogue loop, etc.)

7. **app/src/main/java/com/looplingo/horizon/ui/WaveformSeekBar.kt** — New custom View:
   - Draws ~30 vertical bars with varying heights
   - Played bars: brand-accent (#10b981)
   - Unplayed bars: zinc-700 (#3f3f46)
   - Tap-to-seek support
   - progress property (0-1000)
   - setWaveformData(heights: IntArray) method
   - onSeekListener callback

### Files Created (New Drawables)

8. **bg_super_glass.xml** — Rectangle, fill #0f131c, stroke 1dp #1a2230, corners 16dp
9. **bg_super_glass_pill.xml** — Rectangle, fill #06090e, stroke 1dp #1a2230, corners 12dp
10. **bg_play_button_accent.xml** — Oval, fill brand-accent, size 48dp
11. **bg_waveform_bar.xml** — Rectangle, fill waveform_unplayed, corners 2dp
12. **bg_waveform_bar_played.xml** — Rectangle, fill waveform_played, corners 2dp
13. **ic_fast_rewind.xml** — Material vector icon
14. **ic_fast_forward.xml** — Material vector icon
15. **ic_person.xml** — Material vector icon
16. **ic_notifications.xml** — Material vector icon
17. **ic_repeat.xml** — Material vector icon
18. **ic_speed.xml** — Material vector icon
19. **ic_delete.xml** — Material vector icon
20. **ic_repeat_one.xml** — Material vector icon
21. **ic_all_inclusive.xml** — Material vector icon
22. **bg_loop_play_circle.xml** — Oval, fill loop_play_bg, stroke loop_play_border
23. **bg_loop_delete_circle.xml** — Oval, fill loop_delete_bg
24. **bg_loop_card.xml** — Rectangle, fill brand_card, stroke #0dffffff, corners 8dp
25. **bg_icon_ripple_accent.xml** — Ripple with accent color
26. **bg_profile_avatar.xml** — Oval, fill brand_accent, size 32dp
27. **bg_tab_divider.xml** — Rectangle, fill slate_800, size 1dp×12dp

### Compatibility Notes
- All existing view IDs preserved (either in visible layout or hidden compatibility views)
- Hidden views: iv_skip_previous, iv_skip_next, iv_stop, seek_bar_player, toolbar, card_bottom_player, card_now_playing, layout_saved_timestamps, saved_timestamps_container, btn_speed_toggle, tv_now_playing_title
- DialogueAdapter inner class unchanged
- All existing functionality (subtitle generation, AB loops, dialogue loops, speed, etc.) preserved
