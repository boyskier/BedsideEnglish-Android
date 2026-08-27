<div align="center">

# Bedside English: Talk & Train

### Speak before the room is waiting.

Real-time AI speaking practice for clinical English and unpredictable everyday conversations.

[**Explore the app →**](https://bedsideenglish.github.io/android.html) · [Website](https://bedsideenglish.github.io/) · [Windows version](https://github.com/boyskier/BedsideEnglish-Desktop)

<br>

<a href="https://play.google.com/store/apps/details?id=com.boyskier.bedsideenglish">
  <img src="https://bedsideenglish.github.io/assets/store/google-play-badge-en.png" alt="Get it on Google Play" width="230">
</a>

<br>

[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://play.google.com/store/apps/details?id=com.boyskier.bedsideenglish)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)](app/src/main)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

![Bedside English app preview](https://bedsideenglish.github.io/assets/social/feature-graphic.png)

</div>

## What it is

Bedside English is an Android app for practising spoken English out loud with AI. It is designed for international medical graduates and medical students, but it also includes everyday survival English for the conversations that happen outside the hospital.

You bring your own Gemini API key. The app stores keys on-device and talks directly to the selected AI provider; there is no Bedside English account or subscription.

## Highlights

- Live AI patient encounters, clinical handovers, residency interviews, OSCE/OET-style practice, and case presentations
- Everyday survival scenarios, listening drills, pronunciation practice, and phrase rehearsal
- Immediate feedback with rubric scores, fluency metrics, corrections, and SOAP-style summaries
- Spaced-repetition review, mistake trends, daily missions, and session history
- Offline mock mode for exploring the app without an API key or microphone
- Local-first storage with multilingual UI and in-app guides

See the [interactive Android tour](https://bedsideenglish.github.io/android.html) for screenshots and a fuller walkthrough.

## Install

The easiest route is Google Play:

<a href="https://play.google.com/store/apps/details?id=com.boyskier.bedsideenglish">
  <img src="https://bedsideenglish.github.io/assets/store/google-play-badge-en.png" alt="Get it on Google Play" width="230">
</a>

Package: `com.boyskier.bedsideenglish`

## Build from source

### Requirements

- Android Studio with Android SDK 36, or an equivalent command-line SDK setup
- JDK 21
- Android 8.0 / API 26 or newer for a device or emulator

### Build a debug APK

```bash
git clone https://github.com/boyskier/BedsideEnglish-Android.git
cd BedsideEnglish-Android

# macOS / Linux
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`.

Open the project root in Android Studio and select **Run** to install it on a connected device or emulator. API keys are entered inside the app on first launch; no `.env` file is required.

## Source layout

```text
app/src/main/    Android application source and resources
data/            Cases, rubrics, phrase banks, translations, and in-app guides
gradle/          Version catalog and Gradle wrapper
```

This public tree intentionally contains only the application source, runtime content, build configuration, README, and license. Internal planning documents, QA work products, temporary scripts, and private service endpoints are not part of the public snapshot. Consequently, maintainer-hosted in-app reporting is disabled in source builds.

## Privacy and costs

- Voice and analysis requests go directly from the app to the provider you configure.
- API keys are stored encrypted on the Android device.
- Audio, transcripts, and practice history remain local unless you explicitly use a sharing/export feature or send content to your configured AI provider.
- Provider usage may incur charges. Gemini's available free tier can be enough to start, subject to Google's current limits.

Read the [privacy policy](https://bedsideenglish.github.io/privacy.html) before using the app.

## Disclaimer

Bedside English is a language-practice tool, not a medical device. Its scenarios and AI responses are educational material and are not a substitute for clinical guidance, local policy, supervision, or professional judgment.

## License

Released under the [MIT License](LICENSE).
