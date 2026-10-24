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
as workflow artifacts instead.

To enable FCM in the target APK, add the base64-encoded Firebase
`google-services.json` as the repository Actions secret
`REMOTEAGENT_GOOGLE_SERVICES_JSON_BASE64` before running the workflow. Without this
secret, the target APK still builds but FCM registration will not work.

## Backend setup

Requires Node.js 20+, Redis, and a Firebase Admin service account for push delivery.

1. Copy `RemoteHub/.env.example` to `.env` and set secrets and service URLs.
2. From `RemoteHub`, run `npm install`, then `npm run dev`.
3. Use HTTPS/WSS behind a TLS-terminating reverse proxy in deployment. Never expose
   Redis or the service-account key publicly.
4. Create a controller account with `POST /auth/register`, then register an agent
   with `POST /devices`. The returned device token belongs on the agent only.
5. The agent receives a push request and presents it for explicit owner approval.
   Signaling becomes available only after acceptance.

The controller account must register its agent using its bearer token. The response
contains a one-time `agentToken`; transfer it to the target device through a trusted
pairing channel, enter it in RemoteAgent, and do not retain it in controller logs.
The target then uploads its FCM token using its agent token. In the agent app, the
owner must complete first-run consent and switch on "Accepting requests"; this still
does not pre-approve any session.

The current Android and Windows clients implement login, device listing, request
creation, and basic signaling transport. The backend and agent implement request
approval/revocation. This starter intentionally does not capture camera/microphone,
collect location, or implement a WebRTC media renderer; those resource-specific
features require separate OS permission prompts, foreground indicators, and explicit
per-session owner approval before any capture begins.

`npm test` runs backend authorization and consent-gate tests. These scaffolds are
not a substitute for a production security review, platform permission handling,
pairing UX, key rotation, or deployment hardening.

## Android projects

Open each project directory in Android Studio. Set `BuildConfig.API_BASE_URL` and
`BuildConfig.WS_BASE_URL` to your TLS endpoints. Add Firebase's local
`google-services.json` to RemoteAgent. Android apps deliberately do not
start camera, microphone, or location collection from a push callback; they only
surface a request for foreground user approval. Media capture and location sharing
should be added behind the same explicit, visible, revocable approval flow.
For Firebase push, add the app's `google-services.json` locally. Do not commit the
service-account key or local project credentials.

## Windows project

Open `RemoteControllerDesktop/RemoteControllerDesktop.csproj` in Visual Studio.
Set the API URL in app settings or inject it at startup. The sample uses HTTPS and
provides request actions only; sensor access remains the agent owner's decision.
