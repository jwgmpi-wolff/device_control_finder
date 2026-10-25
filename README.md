# Consent-based Remote Support

## Downloads

**Windows requestor installer:** [Download RemoteControllerDesktop-Setup.exe](https://github.com/jwgmpi-wolff/device_control_finder/releases/latest/download/RemoteControllerDesktop-Setup.exe)

**Android target app:** [Download RemoteAgent-target-debug.apk](https://github.com/jwgmpi-wolff/device_control_finder/releases/latest/download/RemoteAgent-target-debug.apk)

**Android requestor app:** [Download RemoteController-requestor-debug.apk](https://github.com/jwgmpi-wolff/device_control_finder/releases/latest/download/RemoteController-requestor-debug.apk)

The APKs are separate: install RemoteAgent on the target Android device and
RemoteController on the requesting Android device. The Windows setup installer is
for the requesting PC. See [all releases](https://github.com/jwgmpi-wolff/device_control_finder/releases)
for version history. Builds are development/debug builds and are not Play Store
signed.

This workspace contains starter projects for an Android agent, Android controller,
Windows WPF controller, and the RemoteHub backend.

## Safety model

Remote actions are requests, not commands that activate a sensor. A controller can
ask for a camera, microphone, or one-time location share. The agent must show the
requester and requested capability and the device owner must explicitly accept it.
The agent can decline or end an accepted session at any time. The backend rejects
WebRTC signaling until that particular session is accepted and only relays messages
between the registered participants. There is no background camera/microphone
activation or continuous location tracking in this starter.

## Projects

- `RemoteHub/` - Express REST API, Redis session/audit storage, JWT auth, FCM request
  delivery, and consent-gated WebSocket signaling.
- `RemoteAgent/` - Android Compose consent UI and agent request API client.
- `RemoteController/` - Android Compose device list, request actions, and REST client.
- `RemoteControllerDesktop/` - WPF controller sample with device list and request
  actions.

## Build downloads

The GitHub Actions workflow in `.github/workflows/build-downloads.yml` builds both
APKs and the Windows requestor installer. Push a `v*` tag to create a GitHub Release
with direct download links shown above. Running the workflow manually uploads builds
as workflow artifacts instead. The apps ask for your deployed RemoteHub HTTPS URL at
first use; builds are not tied to a sample server.

To enable FCM in the target APK, add the base64-encoded Firebase
`google-services.json` as the repository Actions secret
`REMOTEAGENT_GOOGLE_SERVICES_JSON_BASE64` before running the workflow. Without this
secret, the target APK still builds but FCM registration will not work.

## Deploy RemoteHub (required for login)

Authentication is against the **RemoteHub backend you deploy**. The downloadable
apps no longer point at a placeholder, but login cannot work until this service is
running at a publicly reachable HTTPS address. The included Docker Compose setup
runs RemoteHub, private Redis, and Caddy for automatic TLS.

1. Provision a Linux host with Docker Compose, a public DNS name (for example
   `remotehub.your-domain.com`), and inbound TCP ports 80 and 443 available.
2. Point the hostname's DNS A/AAAA record at the host. From the repository root,
   copy `.env.production.example` to `.env`.
3. Set `PUBLIC_HOSTNAME`, `ACME_EMAIL`, and a newly generated `JWT_SECRET` of at
   least 32 characters in `.env`. Keep `.env` private; it is git-ignored.
4. From the repository root, run `docker compose up -d --build`. Check
   `docker compose ps` and `https://<your-hostname>/ready`. Caddy obtains and
   renews the HTTPS certificate automatically.
5. Enter `https://<your-hostname>` in RemoteController and sign in with Google.
   On the target, enter the same server URL, complete first-run consent, then
   choose **Continue with Google to register this target** and use the same
   Google account. The target will appear in that account's controller device
   list with a unique Device ID. Existing pairing-token setup remains available
   as a manual alternative.
6. Enable **Accepting requests** on the target. This only permits requests to be
   shown; the device owner must still approve each individual request.

For FCM push delivery, create a Firebase project and Android app for package
`com.example.remoteagent`. Store its `google-services.json` as the repository
Actions secret `REMOTEAGENT_GOOGLE_SERVICES_JSON_BASE64` (base64-encoded). Put the
Firebase Admin service-account JSON in the server's `.env` as a single-line
`FIREBASE_SERVICE_ACCOUNT_JSON` value, then restart RemoteHub. Without those
Firebase credentials, FCM delivery is unavailable; configure them before relying
on remote request notifications.

### Configure Google sign-in

1. In Google Cloud Console, configure the OAuth consent screen and create a **Web
   application** OAuth client. Add its client ID as the repository Actions
   variable `GOOGLE_WEB_CLIENT_ID`.
2. Create **Android** OAuth clients for packages `com.example.remotecontroller`
   and `com.example.remoteagent`, using each app's signing certificate SHA-1.
   Get the local debug SHA-1 values with
   `gradle -p RemoteController :app:signingReport` and
   `gradle -p RemoteAgent :app:signingReport` for locally built APKs. The
   certificate configured in Google Cloud must match the APK you install.
   Rebuild the APKs after setting the Web client variable.
3. Add the Web client ID to the comma-separated `GOOGLE_CLIENT_IDS` value in the
   deployed `.env`, then rebuild/restart RemoteHub.
4. For the Windows requestor, create a separate **Desktop app** OAuth client.
   Add both the Web and Desktop client IDs to `GOOGLE_CLIENT_IDS`, and save the
   downloaded desktop client JSON at
   `%LOCALAPPDATA%\RemoteControllerDesktop\google-oauth-client.json`.

The backend verifies the Google ID token signature, audience, and verified email
before issuing its own RemoteHub JWT. Account password login remains available as
an alternative. Do not add OAuth client-secret JSON files or Firebase credentials
to Git.

The Android target and Android/Windows requestors can identify a target through
the same verified Google account; the target registers itself to that account and
then appears in its device list. This avoids treating an unverified phone number
as proof of device ownership. Phone-number discovery is not implemented; it
requires an SMS verification provider. The Android and Windows clients implement
real backend account creation/sign-in, device registration, request creation, and
device listing. The agent can submit
owner decisions and revoke sessions. Signaling transport is provided, but this
version does not capture camera/microphone, collect location, or render WebRTC media.
Those capabilities must not be enabled by a remote request alone; they require
per-session owner approval, OS permissions, foreground indicators, and an explicit
active share experience on the device.

**This repository does not contain a hosted backend URL or Firebase credentials.**
You must deploy RemoteHub and configure Firebase to activate online login and push
delivery. Do not enter a sample/example URL; use the HTTPS hostname from your
deployment. Camera/audio/location sharing and a WebRTC media viewer are not active
features in this version.

`npm test` runs backend authorization and consent-gate tests. These scaffolds are
not a substitute for a production security review, platform permission handling,
pairing UX, key rotation, or deployment hardening.

## Android projects

Open each project directory in Android Studio. Enter the deployed HTTPS server URL
in the app's server field. Add Firebase's local `google-services.json` to
RemoteAgent only when configuring FCM. Do not commit service-account keys or local
Firebase credentials.

## Windows project

Open `RemoteControllerDesktop/RemoteControllerDesktop.csproj` in Visual Studio.
Enter the deployed RemoteHub HTTPS URL to create an account or sign in. The Windows
app provides request actions only; sensor access remains the agent owner's decision.
