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
- A Korean-language CPX track for the Korean medical licensing exam (below)

## 한국 의사국시 CPX 연습 (무료 · 오픈소스)

처음 실행할 때 "한국 의사국시 CPX (한국어)"를 고르면 국시 실기 진료문항을 한국어로 연습할 수 있어요.

- **국시 48개 임상표현 전부**와 보충 스테이션, 1,100개가 넘는 증례. 증례마다 표준화 환자 대본(활력징후·대답·진찰 소견)이 있어서 환자가 지어내지 않고 일관되게 대답합니다.
- **2026년 국시 형식 문제지**(주호소 없는 문제지, 활력징후, 과제 문구), 12분 타이머와 "종료 2분 전" 알림.
- **채점**: 병력청취·신체진찰·환자교육 체크리스트, 국시 PPI 6영역 4단계, 진단·검사·치료 계획. 항목마다 근거 인용과 모범 멘트가 있고, 채점이 틀리면 직접 고칠 수 있어요.
- **친구와 역할극**: 한 명은 학생의사, 한 명은 앱이 주는 환자 대본으로 연기하고, 휴대폰이 녹음한 대화를 AI가 채점합니다.
- **실전 모의고사**(9개 스테이션, 결과는 마지막에), **병력청취만 연습**하는 모드, 공부하기(증상별 체크리스트·자주 놓치는 항목), 기본진료술기 9종 셀프 체크리스트.
- Google AI Studio의 무료 Gemini API 키로 쓸 수 있고, 기록은 기기에만 저장됩니다. 체크리스트는 공개 자료를 바탕으로 새로 쓴 연습용 기준이며 국시원의 공식 채점표가 아닙니다.

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

### Optional reporting backend

The reporting clients, dialogs, and upload queue are included in the public source. The maintainer's private endpoint is not. A source build therefore uses an empty endpoint and hides reporting controls by default.

To use the reporting feature with your own compatible HTTPS backend, add this untracked setting to `local.properties` before building:

```properties
AI_REPORT_ENDPOINT=https://your-backend.example/report
```

You can also provide `AI_REPORT_ENDPOINT` as an environment variable or Gradle property. Never commit a real endpoint or credential.

## Source layout

```text
app/src/main/    Android application source and resources
data/            Cases, rubrics, phrase banks, translations, and in-app guides
gradle/          Version catalog and Gradle wrapper
```

This public tree intentionally contains only the application source, runtime content, build configuration, README, and license. Internal planning documents, QA work products, temporary scripts, and maintainer service configuration are not part of the public snapshot.

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
