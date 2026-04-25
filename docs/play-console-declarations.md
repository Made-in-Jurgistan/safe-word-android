# Safe Word — Google Play Console Declarations Guide

Pre-filled answers for the Play Console forms required before publishing.
All answers are based on a code-level audit of the app as of April 7, 2026
(versionCode 1, versionName 0.1.0).

---

## 1. Data Safety Form

Navigate to: **Play Console → App content → Data safety**

### 1.1 Data Collection and Sharing Overview

| Question | Answer |
|----------|--------|
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all of the user data collected by your app encrypted in transit? | **Yes** (HTTPS only, cleartext disabled) |
| Do you provide a way for users to request that their data is deleted? | **Yes** (Clear Data in Android Settings deletes everything) |

### 1.2 Data Types — What to Declare

| Data Category | Data Type | Collected | Shared | Purpose | Optional |
|---------------|-----------|-----------|--------|---------|----------|
| Audio | Voice or sound recordings | **Yes** | No | App functionality | No |
| App info and performance | Other app info (active package name) | **Yes** | No | App functionality | No |
| Personal info | — | No | — | — | — |
| Financial info | — | No | — | — | — |
| Health and fitness | — | No | — | — | — |
| Messages | — | No | — | — | — |
| Photos and videos | — | No | — | — | — |
| Files and docs | — | No | — | — | — |
| Calendar | — | No | — | — | — |
| Contacts | — | No | — | — | — |
| Location | — | No | — | — | — |
| Web browsing | — | No | — | — | — |
| Device or other IDs | — | No | — | — | — |

### 1.3 Per-Type Details

#### Voice or sound recordings

| Question | Answer |
|----------|--------|
| Is this data collected, shared, or both? | **Collected** |
| Is this data processed ephemerally? | **Yes** — audio is processed in RAM and immediately discarded |
| Is this data required for your app, or can users choose whether it's collected? | **Required** (core feature) |
| Why is this user data collected? | **App functionality** |

#### Other app info and performance (package name / field hints)

| Question | Answer |
|----------|--------|
| Is this data collected, shared, or both? | **Collected** |
| Is this data processed ephemerally? | **No** — stored in local database for vocabulary scoping |
| Is this data required for your app, or can users choose whether it's collected? | **Required** |
| Why is this user data collected? | **App functionality** (scope corrections to specific apps) |

---

## 2. Content Rating Questionnaire (IARC)

Navigate to: **Play Console → App content → Content rating**

### Category: **Utility / Productivity**

| Question | Answer |
|----------|--------|
| Does the app contain user-generated content? | **No** |
| Does the app allow users to communicate with each other? | **No** |
| Does the app share the user's location? | **No** |
| Does the app allow purchases? | **No** |
| Does the app contain ads? | **No** |
| Does this app contain violence? | **No** |
| Does this app contain sexual content? | **No** |
| Does this app contain profanity? | **No** |
| Does this app contain drug references? | **No** |
| Does this app promote gambling? | **No** |
| Does this app allow uncontrolled internet access? | **No** |
| Can users interact with strangers? | **No** |

**Expected rating:** PEGI 3 / Everyone

---

## 3. Accessibility Declaration

Navigate to: **Play Console → App content → App access** (if applicable)
and **Play Console → Store presence → Store listing → Accessibility**

### AccessibilityService Declaration

Google may prompt additional review because the app declares an
AccessibilityService. Be prepared to answer:

| Question | Answer |
|----------|--------|
| What does the accessibility service do? | Inserts transcribed text into focused text fields in any app |
| Why can't you use an InputMethodService instead? | The app is a voice dictation overlay, not a full keyboard. Users keep their preferred keyboard for typing and activate Safe Word via a floating button to dictate. An IME would replace the user's keyboard, which conflicts with the app's design |
| What data does the service access? | Focused text field content (to position cursor), field type, and foreground app package name. Password fields are excluded |
| Is data transmitted off-device? | No — all processing is on-device |
| Is `isAccessibilityTool` set? | Yes — `android:isAccessibilityTool="true"` in accessibility_service_config.xml |

### Restricted Settings (Sideloaded Apps)

On Android 13+ (API 33), sideloaded apps trigger Restricted Settings for
AccessibilityService enablement. The app handles this in onboarding by
guiding users through the "Allow restricted settings" flow. This only
affects sideloaded installs — Play Store installs are not restricted.

---

## 4. App Access (Special Permissions)

Navigate to: **Play Console → App content → App access**

If Google asks why the app needs special permissions:

| Permission | Justification |
|------------|---------------|
| RECORD_AUDIO | Core feature — voice dictation requires microphone input |
| SYSTEM_ALERT_WINDOW | The app draws a floating microphone button over other apps so users can dictate from any screen |
| AccessibilityService | Text insertion — the app inserts transcribed text into the active text field. It is not a keyboard/IME |

---

## 5. Target Audience and Content

Navigate to: **Play Console → App content → Target audience and content**

| Question | Answer |
|----------|--------|
| Target age group | **18 and over** (recommended to avoid COPPA review) |
| Does the app appeal to children? | **No** — utility/productivity app |
| Does the app contain ads? | **No** |

---

## 6. News App Declaration

| Question | Answer |
|----------|--------|
| Is your app a news app? | **No** |

---

## 7. Financial Features Declaration

| Question | Answer |
|----------|--------|
| Does your app provide financial services? | **No** |

---

## 8. Health App Declaration

| Question | Answer |
|----------|--------|
| Is this a health app? | **No** |

---

## 9. Government Apps Declaration

| Question | Answer |
|----------|--------|
| Is this a government app? | **No** |

---

## 10. Pre-Release Checklist

Before submitting for review:

- [ ] **Privacy policy URL** — host `docs/privacy-policy.md` at a public URL
      (e.g., GitHub Pages, your website) and paste the URL in:
      - Play Console → Store listing → Privacy policy
      - Play Console → App content → Data safety → Privacy policy
- [ ] **App signing** — enroll in Play App Signing (recommended: let Google
      manage the signing key)
- [ ] **Store listing assets** — screenshots (phone), feature graphic
      (1024×500), short description, full description
- [ ] **Release track** — start with Internal Testing → Closed Testing →
      Open Testing → Production
- [ ] **Verify `isAccessibilityTool`** — already set in xml/accessibility_service_config.xml
- [ ] **Verify permissions justification** — all permissions declared in
      manifest have runtime request flows in onboarding
- [ ] **Test on a Play Store install** — restricted settings bypass is
      automatic for Play installs (no 3-phase flow needed)
