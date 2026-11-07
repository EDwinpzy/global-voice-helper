## Agent Quick Card

- **Official source entry:** `settings.gradle` → `:app`; `app/` is the Android app and
  `voice-core/` is its library. Both belong to the same release APK.
- **Build:** `.\gradlew.bat :app:assembleDebug`
- **Test:** `.\gradlew.bat :voice-core:testDebugUnitTest :app:testDebugUnitTest`
- **Static check:** `.\gradlew.bat :voice-core:lintDebug :app:lintDebug`
- **Generated / ignored:** `.gradle/`, `.kotlin/`, `**/build/`, `**/.cxx/`,
  `local.properties`, `*.apk`, `*.aab`, `*.jks`, `*.keystore`, `.env`, `.env.*`,
  captured audio, screenshots and local data files
- **Release / signing gate:** Manual only; preserve `com.dongpiandi.voicehelper` and the
  signing identity, use replacement installs, never uninstall to bypass signature mismatch.
- **Do not modify:** `main` directly (use `codex/<topic>` branches), generated outputs,
  signing material, `local.properties`, device credentials.

## Project boundaries

- **Official product:** `app/` (`:app`) with `voice-core/` (`:voice-core`) as the only
  Android release product.
- **Experimental / legacy / verification entries:** `app/src/debug/` and
  `app/src/testDebug/` are debug-only QA/benchmark sources, not a separate release entry.
- **Multi-stack boundary:** Android-only Gradle project; `app/` and `voice-core/` are
  compiled into the same APK.

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
