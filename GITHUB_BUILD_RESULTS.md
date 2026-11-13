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

## OTP onboarding update

The OTP onboarding update built successfully in [run 43376013182](https://github.com/Ashrith012/MoneyTracker/actions/runs/43376013182), from commit `74f0e98551be28cf07dafc5de0f858c0eb4a44b4`. All **26 core tests passed**, including four new phone-input regression tests.

[Download the updated APK artifact](https://github.com/Ashrith012/MoneyTracker/actions/runs/43376013182/artifacts/14015055235).

When no HTTPS API URL is configured, onboarding explicitly identifies this as a demo build and hides unavailable OTP fields. Configured builds accept valid Indian mobile numbers with or without +91 and give clear consent/input feedback.

**Real SMS delivery is not enabled by this UI update.** Deploy the Better Auth backend with Supabase and an SMS provider, set the GitHub repository variable `MONEYTRACK_API_URL` to that backend's HTTPS URL, and rebuild. Keep provider/database secrets on the backend, never in the APK or repository variables. See SETUP.md.
