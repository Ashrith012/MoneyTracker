# Verified GitHub APK build

Verified on 13 November 2026.

- Repository: https://github.com/Ashrith012/MoneyTracker
- Workflow: `.github/workflows/build-apk.yml` (**Build Android APK**)
- Successful run: https://github.com/Ashrith012/MoneyTracker/actions/runs/43202364379
- Built application commit: `67a8a66db9e2811cc11f8d72597c604873738d45`
- APK artifact: https://github.com/Ashrith012/MoneyTracker/actions/runs/43202364379/artifacts/13936255997
- Unit-test report artifact: https://github.com/Ashrith012/MoneyTracker/actions/runs/43202364379/artifacts/13936665092

GitHub completed Java/Android SDK setup, Kotlin unit tests, the debug APK build, and artifact upload successfully. The workflow explicitly selects supported SDK packages, avoiding the retired `tools` package requested by the setup action's default configuration.

Download **MoneyTrack-debug-APK** from the run's Artifacts section. Unzip it and install `MoneyTrack-debug.apk` on an Android 9+ phone. Choose **Explore synthetic demo**. The artifact includes `SHA256SUMS.txt` for integrity checking. GitHub sign-in may be required to download artifacts; artifacts are retained for 30 days.

For another build, open **Actions → Build Android APK → Run workflow**. No Android Studio, local Android SDK, or live service credentials are needed for the demo. GitHub-hosted runner usage is subject to your account's Actions limits.

This is a debug APK, not a production-signed Play Store release. Real bank-email parsing remains disabled pending validated formats; live Gmail/OTP access requires the service configuration in SETUP.md. Device runtime tests were not part of this APK workflow; the separate CI workflow provides the emulator job.
