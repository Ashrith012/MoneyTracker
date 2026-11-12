# Setup and deployment

## Android

Use JDK 17, Android SDK platform 35/build tools 35.0.0, and Gradle 8.13 (wrapper included). The pinned stack is Kotlin 2.1.20, AGP 8.9.2, Compose BOM 2025.04.01, Room 2.7.1, WorkManager 2.10.1 and the maintained `sqlcipher-android` artifact 4.10.0. These are stable pins, not a claim to be the newest releases.

```sh
./gradlew :core:test :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
# With an emulator or physical device connected:
./gradlew :app:connectedDebugAndroidTest
```

On Windows use `gradlew.bat`. A deep Windows workspace may need a shorter `java.io.tmpdir` and `jdk.net.unixdomain.tmpdir` for Java's local sockets; keep this temporary directory writable. For reproducible CI the included workflow uses Linux. If local automatic debug-key generation is blocked, generate a standard debug keystore and pass `-PdebugKeystore=/absolute/path/to/debug.keystore`; this override affects only debug signing. Production signing keys are deliberately not supplied.

## Google OAuth and Gmail

1. Create your own Google Cloud project and enable the Gmail API. Configure the OAuth consent branding, support contact and privacy URL.
2. Create an **Android** OAuth client with package `in.moneytrack.app` and your signing certificate SHA-1 (`./gradlew signingReport`). Configure debug and production certificates separately. No OAuth client secret belongs in the Android app.
3. Add test users while the OAuth application is in testing. Request only `https://www.googleapis.com/auth/gmail.readonly`. Complete Google's required verification for your eventual distribution/use case.
4. Deploy the OTP backend, build with its HTTPS URL, sign in, and use Settings → Connect Gmail. AuthorizationClient handles access tokens; the app does not send Gmail tokens to its backend.
5. Add validated parsers as described in SUPPORTED_FORMATS.md before expecting live transaction rows. The shipped live registry is intentionally empty.

Initial import uses the selected period and Gmail search with pagination. A profile history ID is captured before import; subsequent checks replay history from that point. The durable history checkpoint advances only after all pages succeed. An expired history ID resets the initial import. Source message IDs make replay safe. Switching the connected Google mailbox requires deleting that user's local imported data first, preventing silent mailbox mixing.

Disconnect collection before revoking the grant at [Google connections](https://myaccount.google.com/connections). A 401/403 prompts reconnection; network/rate-limit failures retry with WorkManager backoff. Android scheduling is approximate, not a payment-alert SLA.

## Supabase + Better Auth

This project uses **Supabase PostgreSQL**, not Supabase Auth. Better Auth owns phone OTP and sessions.

1. Create a PostgreSQL project and a server database connection with TLS validation. Use a dedicated backend role; never embed database credentials or a service role key in Android.
2. Copy `server/.env.example` to an untracked `.env`, replace placeholders, and load it into your shell environment. Node does not implicitly load this file; for local execution use Node's `--env-file=.env` option.
3. From `server/`, run `npm ci` and `npm run build`.
4. With the environment loaded, use the Better Auth CLI matching the installed release to migrate the exported config: `npx auth migrate --config ./src/auth.ts`. Review its SQL before applying it to a shared database.
5. Apply `server/migrations/001_consents.sql` after the Better Auth migration. This table has RLS enabled with no public client grants. Disable public Data API exposure/grants on Better Auth's authentication tables as well; only the backend role should access them.
6. Start with `node --env-file=.env dist/index.js`, behind HTTPS. `GET /health` is the health endpoint.
7. Build Android with `./gradlew :app:assembleDebug -PapiUrl=https://YOUR_APP.fly.dev`. With no URL, live OTP buttons are disabled and the explicitly separated demo remains available.

The backend rejects non-HTTPS base URLs and short secrets. Generate a unique random secret of at least 32 characters. Better Auth limits attempts and expiry; the database-backed auth rate limit survives server restarts. The outer Express limiter is per instance. Configure edge-level SMS abuse protection and monitor delivery failures before public deployment.

Endpoints used by Android:

- `POST /api/auth/phone-number/send-otp`
- `POST /api/auth/phone-number/verify`
- `POST /api/auth/sign-out`
- `POST /api/consents` (bearer session required; kind, granted, version only)
- `DELETE /api/profile` (bearer session required; cascades user authentication and consent metadata)

No endpoint accepts financial records. Optional display names are not required; no gender field exists.

## SMS delivery

For the built-in Twilio adapter, set `SMS_PROVIDER=twilio`, `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_MESSAGING_SERVICE_SID`, and `TWILIO_OTP_BODY`. The body must contain `{code}` and match your approved transactional template. Configure the provider for Indian destinations and applicable sender/template registration. Better Auth generates and checks the OTP; Twilio only delivers it. Test with your authorized number after provisioning.

For another provider set `SMS_PROVIDER=webhook` and configure `SMS_ENDPOINT`, `SMS_API_KEY`, and `SMS_TEMPLATE_ID`. The adapter POSTs `{"to":"+91…","templateId":"…","variables":{"code":"…"}}` with a bearer API key and requires a successful HTTP response. Adapt that payload in `server/src/sms.ts` to your provider's documented contract. There is no built-in pretend-success transport and no production hardcoded OTP.

## Firebase Analytics

Create an Android Firebase app matching the package, place its `google-services.json` in `app/`, and rebuild. The Google Services Gradle plugin applies only when the file exists. Analytics is disabled by default. Enable it only through the app's explicit settings toggle. Do not attach transaction IDs, amounts, merchant names, email addresses, phone numbers, account labels or financial search text to analytics events. No financial custom events are implemented.

## Fly.io

From `server/`, create your Fly application, replace the name in `fly.toml`, provision secrets using Fly's secret storage, then run `fly deploy`. The Docker image exposes port 8080 and runs as an unprivileged user. Set `BETTER_AUTH_URL` to the final HTTPS origin. Add a custom domain/certificate if wanted. The supplied workflow deploys only when manually dispatched and requires a `production` environment and `FLY_API_TOKEN` secret.

The health endpoint checks process availability, not database/SMS readiness. Verify a real sign-in, consent insert, sign-out and deletion against a staging database before production. No hosting account has been configured or charged by this delivery.

## Cloudflare Pages and DNS

Deploy `site/` as static HTML: no framework and no build command, with output directory `site` when the repository root is this project. Alternatively upload the directory directly through Cloudflare Pages. The pages have no forms, tracking scripts, external fonts or financial API connections.

Before publishing, fill in the operator's identity, support contact and retention policy on the privacy/support pages. Attach your domain through the Pages project's Custom domains flow and create the requested Cloudflare DNS records. Point an API subdomain to Fly using Fly's documented domain/certificate settings. Keep API secrets out of Pages and DNS records.

## CI and release

Copy this project as the repository root, retaining `.github/workflows`. CI builds the APK, runs core JUnit tests, runs lint and checks the server. A separate CI job runs instrumented tests on an API 35 emulator; local connected-device runs require no live credentials. The manual deploy workflow is separate from pull-request checks and cannot deploy without configured secrets.

Before a public release: validate real email parsers; complete device tests, OAuth/provider verification and release signing; replace preview website operator placeholders; test live auth/revocation/deletion and notifications on supported Android versions. Never relabel demo data as live.

## Official references consulted

- [Compose BOM](https://developer.android.com/develop/ui/compose/bom)
- [Android Gradle compatibility](https://developer.android.com/build/releases/about-agp)
- [Room](https://developer.android.com/jetpack/androidx/releases/room)
- [WorkManager](https://developer.android.com/jetpack/androidx/releases/work)
- [Google AuthorizationClient](https://developer.android.com/identity/authorization)
- [Gmail synchronization](https://developers.google.com/workspace/gmail/api/guides/sync)
- [Better Auth phone-number plugin](https://better-auth.com/docs/plugins/phone-number)
- [SQLCipher maintained Android library and 16KB support](https://www.zetetic.net/blog/2025/06/26/sqlcipher-for-android-16kb-page-size-support/)
- [Twilio Messages API](https://www.twilio.com/docs/messaging/api/message-resource)
- [Fly GitHub Actions deployment](https://docs.fly.io/launch/continuous-deployment-with-github-actions)
- [Cloudflare static HTML](https://developers.cloudflare.com/pages/framework-guides/deploy-anything/)
