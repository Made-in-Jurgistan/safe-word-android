# Safe Word — Privacy Policy

**Effective date:** April 7, 2026
**App name:** Safe Word
**Package:** com.safeword.android
**Developer:** Jurge

---

## Overview

Safe Word is a voice dictation app that transcribes speech into text using an
on-device speech recognition engine. All audio processing happens locally on
your device. Safe Word does not have user accounts, does not use analytics or
crash-reporting services, and does not display advertisements.

---

## What Data We Collect and Why

### Microphone Audio

Safe Word records audio from your device microphone when you activate
dictation. Audio is processed in real time by the on-device speech recognition
model and is **never saved to disk, uploaded, or shared**. Once a chunk of
audio has been transcribed the raw samples are discarded from memory.

**Legal basis:** Necessary for the core dictation feature. Requires your
explicit runtime permission (`RECORD_AUDIO`).

### Transcribed Text and Corrections

When the speech engine produces a transcript, it is inserted into the text
field you are editing via Android's accessibility framework. Safe Word may
store individual words or phrases that you manually add to your personal
vocabulary, or that are automatically learned from corrections you make. These
entries are stored locally in an on-device database and are never transmitted
off the device.

### Accessibility Service Data

Safe Word uses an Android AccessibilityService solely to insert transcribed
text into text fields in other apps. In order to place text at the correct
cursor position the service reads:

- the **focused text field's current content and cursor position**
- the **text field type** (e.g., single-line, multi-line, password indicator)
- the **name of the foreground app** (package name)

Password fields are explicitly excluded — Safe Word never reads text from a
field marked as a password input.

Package names and field-hint text may be stored alongside personal vocabulary
entries so corrections can be scoped to specific apps or field types. This data
is stored locally and never leaves your device.

### Microphone Access Log

Safe Word records timestamps of when the microphone was accessed and the
duration of each recording session. This log exists for your own transparency
and is stored locally. It does not contain audio content.

### App Preferences

User-interface settings (dark mode, haptic feedback, overlay toggle, onboarding
progress) are stored locally using Android DataStore. These preferences contain
no personal information.

---

## What Data We Do NOT Collect

- Device identifiers (IMEI, Android ID, advertising ID)
- Location data
- Contacts, calendar, or SMS content
- Browsing history
- Biometric data
- Health or fitness data
- Financial or payment data
- User account credentials
- Analytics or telemetry events
- Crash reports or stack traces

---

## Network Connections

Safe Word makes network requests only to download the speech recognition model
files. Downloads are served from **download.moonshine.ai** over HTTPS with
certificate pinning enabled. The request is a plain HTTP `GET` — no device
identifiers, user IDs, cookies, or custom headers are sent.

The download happens once (at setup) and the model is cached locally. After the
model is downloaded the app can function entirely offline.

Your device's **IP address** is necessarily visible to the download server
during this request. We do not log or retain IP addresses on the server side.

---

## Third-Party Services and SDKs

Safe Word contains **no** third-party analytics, advertising, crash-reporting,
or attribution SDKs. Specifically:

- No Firebase (Analytics, Crashlytics, Cloud Messaging, Remote Config)
- No Google Analytics or Google Ads
- No Sentry, Bugsnag, or Datadog
- No Facebook, TikTok, or social media SDKs
- No mobile measurement partners (Adjust, AppsFlyer, Branch)

The following open-source libraries are used and do not collect data:

| Library | Purpose |
|---------|---------|
| Moonshine Voice SDK | On-device speech recognition |
| ONNX Runtime | On-device voice activity detection |
| OkHttp | Model file downloads |
| Jetpack Compose | User interface |
| Room | Local database |
| Hilt | Dependency injection |
| Timber | Local-only debug logging |

---

## Data Storage and Security

All user data is stored in the app's private internal storage, which is
sandboxed by Android and inaccessible to other apps. Key protections:

- **Backup disabled** (`android:allowBackup="false"`) — your data is not
  included in device backups.
- **Cleartext traffic disabled** — all network connections use TLS.
- **Certificate pinning** — model downloads verify the server's TLS
  certificate chain against pinned hashes.
- **SHA-256 integrity verification** — downloaded model files are verified
  against hardcoded checksums before use.

---

## Data Retention

- **Audio:** Never retained — discarded from memory immediately after
  transcription.
- **Personal vocabulary:** Retained until you delete individual entries or
  clear app data. Dormant entries (unused for ~30 days) are automatically
  deactivated but remain in the database.
- **Microphone access log:** Retained until you clear app data.
- **Preferences:** Retained until you clear app data or uninstall.

---

## Your Rights

You can exercise the following at any time:

- **Access** your personal vocabulary, microphone access log, and preferences
  through the app's settings screen.
- **Delete** individual vocabulary entries or all app data via Android's
  Settings → Apps → Safe Word → Clear Data.
- **Revoke** microphone, overlay, or accessibility permissions at any time
  through Android Settings.

Because all data is local, there is nothing to request from a remote server.

---

## Children's Privacy

Safe Word does not knowingly collect information from children under 13. The
app does not require or support user accounts and contains no age-gated
content.

---

## Changes to This Policy

If we update this privacy policy we will update the "Effective date" at the top
of this page. Continued use of the app after a policy update constitutes
acceptance of the revised terms.

---

## Contact

If you have questions about this privacy policy or Safe Word's data practices,
contact:

**Email:** [ADD YOUR CONTACT EMAIL HERE]

---

*This policy was last reviewed on April 7, 2026.*
