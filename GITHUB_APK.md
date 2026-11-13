# Build an APK on GitHub — no Android Studio required

The complete source project must be in the repository. A workflow YAML file alone cannot build the app.

1. Put the contents of this MoneyTrack directory at the repository root, including `app/`, `core/`, `gradle/`, build files and `.github/`.
2. Ensure `.github/workflows/build-apk.yml` is committed on the default branch.
3. In GitHub, open **Actions → Build Android APK → Run workflow → Run workflow**.
4. Open the completed run. Under **Artifacts**, download **MoneyTrack-debug-APK**.
5. Unzip it and install `MoneyTrack-debug.apk` on an Android 9+ phone. Allow installation from that file source if Android requests it.
6. Choose **Explore synthetic demo**. No Google, SMS, Supabase or Firebase credentials are needed for the demo.

Pushes changing Android source on `main` or `master` also trigger APK builds. Pull requests and manually selected full validation use the separate CI workflow. All builds execute on GitHub-hosted runners; your computer does not need the Android SDK or Android Studio. GitHub account Actions availability and usage limits still apply.

Optional: set the repository variable `MONEYTRACK_API_URL` to your deployed HTTPS API URL under Settings → Secrets and variables → Actions → Variables. It is a public endpoint, not a secret. Never put SMS, database or authentication secrets in this variable or YAML. Live bank tracking still requires validated bank-email parsers and service configuration described in SETUP.md.

The downloaded artifact is a ZIP containing the APK and SHA-256 checksum. Debug signing keys may change between clean runners; if Android rejects an update with a signature mismatch, uninstall the old preview first (this deletes its local records). Use an explicitly managed stable signing key for a future release. This workflow does not publish to Google Play or deploy the backend.

## Current delivery status

The repository includes the complete source and `.github/workflows/build-apk.yml`. Open Actions → Build Android APK to build it. A successful run provides the downloadable APK artifact.

## Successful build

The APK has been built successfully on GitHub. See [the verified build and download links](GITHUB_BUILD_RESULTS.md).
