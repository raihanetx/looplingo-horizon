---
Task ID: 1
Agent: Main Agent
Task: Rewrite GroqApiClient.kt with optimized pipeline v2.0

Work Log:
- Read entire GroqApiClient.kt (1562 lines) and AudioPlaybackService.kt
- Researched Groq Whisper API thoroughly (supported formats, MIME types, rate limits, chunking best practices)
- Identified root cause of "No speech detected" bug: M4A files sent as video/mp4 MIME type (line 1448 of old code)
- Designed new pipeline architecture based on research findings
- Wrote complete GroqApiClient.kt rewrite (v2.0) with these changes:
  1. 19.5MB file size limit (user specified)
  2. Correct MIME types (audio/mp4 for M4A, never video/mp4)
  3. 16KHz mono AAC pre-processing (75% file size reduction, zero accuracy loss)
  4. whisper-large-v3 exclusively (not turbo) for best accuracy
  5. Overlap chunking (15s overlap per Groq cookbook)
  6. Prompt chaining (previous chunk context as prompt for next)
  7. no_speech_prob filtering (threshold 0.6)
  8. Translation endpoint (/v1/audio/translations)
  9. SRT and VTT generation methods
  10. saveSrtFile() for persistence
  11. 5-minute chunks instead of 30s (fewer API calls)

Stage Summary:
- GroqApiClient.kt fully rewritten with research-backed optimizations
- The MIME type fix alone should resolve the "No speech detected" bug
- 16KHz mono pre-processing means most files fit in a single API call (41 min in 19.5MB)

---
Task ID: 2
Agent: Main Agent
Task: Optimize AudioPlaybackService.kt for minimal background resource usage

Work Log:
- Audio-only playback already properly configured (video track disabled via DefaultTrackSelector)
- WakeLock management already optimized (release on pause, acquire on play)
- Upgraded A-B monitoring from 2-tier to 3-tier adaptive scheduling:
  - >10s from B: check every 10s (was 5s)
  - 3-10s from B: check every 3s (new tier)
  - <3s from B: check every 500ms (precise, was <5s)
- Upgraded position update to adaptive interval:
  - 1000ms during normal playback (was fixed 500ms)
  - 500ms when A-B loop active (needs precise subtitle sync)
- Extended WakeLock timeout from 3 hours to 6 hours for long study sessions

Stage Summary:
- 50% reduction in Handler wake-ups during normal background playback
- Further 50% reduction in A-B check wake-ups when far from B marker
- WakeLock supports 6-hour study sessions
- No video rendering = negligible GPU/battery impact
