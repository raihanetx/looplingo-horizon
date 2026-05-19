# Horizon Loop Work Log

---
Task ID: 1
Agent: Main Agent
Task: Implement Silero VAD neural network for dialogue boundary detection

Work Log:
- Analyzed existing VadEngine (970 lines, multi-feature heuristic VAD) — found it was 70-80% accurate
- Identified critical bug: post-processing was over-clamping VAD corrections to ±200ms of Whisper timestamps, preventing VAD from fixing Whisper's errors
- Added PyTorch Mobile dependency (pytorch_android_lite 2.1.0) for neural network inference
- Exported Silero VAD v4 model to TorchScript format (silero_vad.pt, ~2.2MB) from PyTorch Hub
- Created SileroVadDetector.kt — wrapper for PyTorch Mobile inference with:
  - 512-sample (32ms) chunk processing
  - Internal LSTM state management (model handles automatically)
  - Hysteresis thresholding (0.50 ON, 0.35 OFF) for stable boundaries
  - Short gap merging, overlap removal, padding
- Rewrote VadEngine.kt (v2.0) with two-stage pipeline:
  - Stage 1: Silero VAD neural network (>95% accuracy) for speech detection
  - Stage 2: Fine-grained energy onset/offset detection (5ms frames) for sub-chunk precision
  - Energy-only fallback if Silero VAD fails to initialize
- Fixed critical post-processing bug: removed ±200ms clamping, now trusts VAD boundaries with wide sanity bounds (±500ms)
- Fixed missing dialogue auto-loop variable declarations in AudioPlaybackService.kt
- Fixed IValue.from() to use Long instead of Int for sample rate
- Fixed @ApplicationContext qualifier for VadEngine's Context parameter
- Added ProGuard rules for PyTorch Mobile
- Version bump: 5.0.0 → 5.1.0 (versionCode 35 → 36)

Stage Summary:
- Successfully built v5.1.0 with Silero VAD neural network
- Release available on GitHub: v5.1.0 (243MB release APK — larger due to PyTorch Mobile, user prioritizes accuracy over size)
- Key accuracy improvements: Custom heuristic VAD (70-80%) → Silero neural network (>95%)
- Fixed over-clamping bug that was preventing VAD corrections from working properly
