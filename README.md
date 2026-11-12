# MoneyTrack — Android source preview

MoneyTrack records and analyses financial activity locally. It does not initiate payments, discover bank accounts from phone numbers, or provide verified live balances.

## Build without Android Studio

Use the included [GitHub APK workflow](GITHUB_APK.md) to build and download an installable APK entirely on GitHub.

## Run the demo

1. Install Android Studio with Android SDK 35 and JDK 17 or 21. Minimum device: Android 9 (API 28).
2. Open this directory as the project. Set your SDK path in Android Studio / local.properties.
3. Run `./gradlew :core:test :app:assembleDebug` (Windows: `gradlew.bat`). If the wrapper is not present, use Gradle 8.13 to run `gradle wrapper` first.
4. Install `app/build/outputs/apk/debug/app-debug.apk` with Android Studio or `adb install -r ...`.
5. Choose **Explore synthetic demo**. No credentials are needed. It uses a distinct encrypted demo database.

The APK is a debug preview, not a production-signed release. See TEST_RESULTS.md for what was actually verified.

## What is implemented

- Kotlin/Compose/Material 3 Android screens, system light/dark theme, ViewModel/Flow, Indian currency formatting.
- Separate synthetic demo, transaction search/status filtering, source/status details, category corrections, duplicate exclusion, explicit refund/own-transfer linking.
- Room/SQLCipher storage, random per-user database keys wrapped by Android Keystore, backup exclusion, device authentication, user-separated stores.
- Local monthly totals, categories, 3-month mean forecasts with insufficient-data checks, dated estimated balances, budgets, manually maintained goals, deterministic assistant.
- Gmail OAuth read-only authorization, full-import pagination, incremental Gmail history, durable checkpoints, idempotent evidence ingestion, retry handling, manual and 15-minute WorkManager checks.
- Private local notifications, deduplication, minimum amounts, quiet hours, budget thresholds. Initial import is silent.
- Better Auth phone-number OTP server with bearer sessions, Supabase PostgreSQL configuration, consent records, Twilio and generic HTTPS SMS adapters. No financial API endpoints.
- Optional Firebase Analytics configuration, off by default, with no financial event parameters.
- Fly.io container/configuration, Cloudflare Pages static landing/support/privacy files, GitHub Actions checks.

## Integration status — read before connecting real data

| Integration | Status |
|---|---|
| Local demo and local calculations | Implemented; see validation report |
| Room + SQLCipher + Keystore | Implemented; device tests require emulator/device |
| Gmail API/OAuth transport | Implemented; requires your Google Cloud Android client and consent configuration; not live-tested |
| Real bank email parsers | **Not enabled.** Only synthetic fixtures are supported; see SUPPORTED_FORMATS.md |
| Phone OTP | Implemented API/client; requires deployed API, migrated Supabase database and SMS credentials; not live-tested |
| Firebase | Optional, requires google-services.json and user opt-in |
| Authorized bank-data integration | Interface only; unavailable in the UI |
| Cloud hosting / DNS | Configuration and instructions supplied; not deployed |

**OAuth credentials alone do not enable live bank tracking.** Validate real, redacted bank email samples and register a parser before claiming support for a bank.

## Configuration

See [SETUP.md](SETUP.md) for OAuth, Supabase, OTP, Fly.io, Firebase, Cloudflare and CI instructions. No service secrets are included.

## Structure

- `core/`: platform-independent model, parsers, reconciliation, calculations, assistant, future provider interface, JUnit tests.
- `app/`: Compose UI, encrypted Room persistence, auth client, Gmail transport/sync, local notifications, instrumented tests.
- `server/`: Node/TypeScript Better Auth API and consent storage, SMS adapters and tests.
- `site/`: buildless Cloudflare Pages files.

## Important limits

This is a source preview. There is no enabled real-bank parser, no release-signing configuration, and no claim of production readiness. Gmail HTML-only email parsing is not implemented. Initial historical import is limited to 365 days; expired history triggers a bounded rescan, so older missed messages may remain missing. Incremental checks inspect new messages; full bodies are not persisted.

Quiet-hour notifications are suppressed, not queued for morning delivery. Notification delivery is at-most-once around an OS/process failure. Forecast warnings are shown in Accounts and checked for local notifications after successful sync. Transfers/refunds require explicit user confirmation. No curated bank support-number directory is shipped, so no bank numbers are generated.

Settings offers separate local deletion and cloud-profile-plus-local deletion. Account lock relies on device credentials; configure a screen lock. Signing out preserves that user's encrypted local records. A lost/reset Keystore key makes those records unrecoverable by design. Exports are unencrypted and are not removed by app deletion.

Read the privacy preview before deployment. Supply an operator identity, support contact, retention policy and verified bank coverage before public launch.
