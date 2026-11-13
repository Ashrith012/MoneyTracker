# Validation results — 12 November 2026

## Completed checks

| Check | Result |
|---|---|
| Core Kotlin JUnit suite | **22 passed; 0 failures, 0 errors** |
| Backend TypeScript build | **Passed** with Better Auth 1.7.21 |
| Backend SMS adapter tests | **5 passed; 0 failures** |
| Android debug APK build | **Passed** (`:app:assembleDebug`) |
| Android instrumented test APK build | **Passed** (`:app:assembleDebugAndroidTest`); compiled, not executed |
| Android lint | **0 errors, 18 warnings** |
| Static website | All local links in 3 HTML pages resolve |

Built with JDK 17.0.21, Gradle 8.13, Android SDK 35 on Windows. The APK is package `in.moneytrack.app`, version 0.1.0, minimum Android 9/API 28, target API 35, with ARM64, ARMv7, x86 and x86_64 native libraries. It uses a debug signing key, not production signing. APK integrity hashes are in the adjacent SHA256SUMS.txt delivery file.

The provided APK has no live API endpoint or Firebase project configured. Choose **Explore synthetic demo** to use it without credentials. No real bank parser is enabled.

## What the executed tests cover

The core suite checks exact minor units, unsupported/OTP/promotional/request rejection, malformed amounts, missing dates/references, idempotent ingestion, pending-to-successful updates, delayed status evidence, conservative matching, account separation, internal transfers, failed/pending exclusion, partial refunds, reversals, duplicate exclusion, category refunds, balance movements, forecast coverage and calculations, Indian currency formatting, and local assistant responses.

The backend suite checks Indian mobile normalization, payload delivery to an injected HTTPS gateway, rejection of plaintext transport, delivery failures, and Twilio template substitution. All SMS test transports are mocks; no SMS was sent.

## Not executed in this environment

- **Device runtime and instrumented tests:** no usable emulator/device session; ADB startup failed because the Windows sandbox could not create its Android configuration directory. The three instrumented tests compile and cover encrypted database/user isolation, notification-key deduplication, interrupted import replay, and authorization revocation. The included CI config can run them on an API 35 emulator.
- **Live integrations:** Google OAuth, Gmail service calls, real OTP delivery, Supabase migrations, consent writes and account deletion require the operator's configured services. No successful live integration is claimed.
- **Deployment:** Fly.io and Cloudflare Pages/DNS were not deployed. GitHub Actions was not run during this initial local validation; a later successful cloud APK build is documented in GITHUB_BUILD_RESULTS.md.
- **Visual device/browser QA and release signing:** not performed. This is a debug source preview, not a production certification.

## Remaining lint warnings

The remaining warnings concern pinned older stable dependency versions and Kotlin/SharedPreferences style suggestions. Synchronous preference commits are intentional for durable key/session changes. No lint baseline or disabled error checks were used to hide errors. See `validation/android-lint.html` for the full report and `validation/core-junit.xml` for JUnit evidence.

The build initially exposed and corrected paise rounding, ambiguous Room/model imports, non-observed Compose StateFlow reads, and missing Android 12 backup exclusions. Windows-specific build-tool issues were resolved using an isolated debug-keystore override and resource-based API URL configuration.

See README.md, SETUP.md and SUPPORTED_FORMATS.md for integration status and known limitations.
