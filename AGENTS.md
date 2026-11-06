# Repository Instructions

- Use PowerShell on Windows.
- Build with JDK 17:
  `.\gradlew.bat :voice-core:testDebugUnitTest :app:testDebugUnitTest :voice-core:lintDebug :app:lintDebug :app:assembleDebug --rerun-tasks`
- Preserve the `com.dongpiandi.voicehelper` application ID and signing identity.
  Use replacement installs; do not uninstall to bypass signature mismatch.
- Keep the app capable of running as a 32-bit `armeabi-v7a` process on S621.
- Never place API keys, Base URLs, device credentials, captured audio, APKs, or local
  SDK paths in tracked files or logs.
- Input-editor dictation must not start the LLM, TTS, or factory assistant UI.
- The no-editor assistant path may use the custom model and overlay, but recognition
  must continue to use the factory AIUI engine.
- Verify JVM tests, lint, APK build, and real-device behavior separately. Injected PCM
  is not a substitute for physical remote microphone acceptance.
