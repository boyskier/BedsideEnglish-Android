# Bedside English User Guide

**Target Audience:** International Medical Graduates (IMGs), resident physicians, medical students, and healthcare professionals preparing for clinical English, OSCE, OET Speaking, US Residency Interviews, Ward Round presentations, and Clinical Communication.

Bedside English is an Android-exclusive app powered by conversational AI (Google Gemini, OpenAI Realtime, Anthropic Claude) and real-time voice technology. It is designed to train clinical communication skills, medical reasoning, pronunciation, and speech intelligibility across multiple dimensions. This guide details all features and usage of the latest app version tailored for users.

---

## 📌 Table of Contents

1. [API Key Setup and Configuration](#1-api-key-setup-and-configuration)
2. [App Installation and Permissions Setup](#2-app-installation-and-permissions-setup)
3. [First-Run Onboarding & L1 Learner Customization](#3-first-run-onboarding--l1-learner-customization)
4. [UI Layout Overview (Bottom Navigation 5 Tabs & Help)](#4-ui-layout-overview-bottom-navigation-5-tabs--help)
5. [Mastering the Dashboard (Home)](#5-mastering-the-dashboard-home)
6. [Practice Hub & Progressive Feature Unlocking](#6-practice-hub--progressive-feature-unlocking)
7. [Patient Encounters & Live History Coverage Tracker](#7-patient-encounters--live-history-coverage-tracker)
8. [Exam & Diagnostic Mode (10-minute Baseline & CEFR Mapping)](#8-exam--diagnostic-mode-10-minute-baseline--cefr-mapping)
9. [Pronunciation & Intelligibility Lab (Pron Lab)](#9-pronunciation--intelligibility-lab-pron-lab)
10. [Survival English & Listening Lab](#10-survival-english--listening-lab)
11. [Lecture Teach-back (Feynman Technique)](#11-lecture-teach-back-feynman-technique)
12. [Residency Mock Interviews](#12-residency-mock-interviews)
13. [Free English Lounge & Custom Scenarios](#13-free-english-lounge--custom-scenarios)
14. [Deconstructing the Feedback Report (7 Main Sections)](#14-deconstructing-the-feedback-report-7-main-sections)
15. [Socratic AI Tutor & Always-On 1:1 Voice Coach](#15-socratic-ai-tutor--always-on-11-voice-coach)
16. [Spaced Repetition Error Tracker & Mistake Genome](#16-spaced-repetition-error-tracker--mistake-genome)
17. [Attending Case Presentation Chaining](#17-attending-case-presentation-chaining)
18. [Importing External Conversation History (Import Transcript)](#18-importing-external-conversation-history-import-transcript)
19. [Anki Decks & Word Document Exports](#19-anki-decks--word-document-exports)
20. [In-App Help Wiki](#20-in-app-help-wiki)
21. [UI Language Settings, API Cost Tracking & Preferences](#21-ui-language-settings-api-cost-tracking--preferences)
22. [Frequently Asked Questions (FAQ) & Scoring Rubric](#22-frequently-asked-questions-faq--scoring-rubric)

---

## 1. API Key Setup and Configuration

Bedside English flexibly supports Google Gemini, OpenAI, and Anthropic Claude backends.

### 💡 Recommended Setup (Google Gemini Single Key Mode)
**Registering a single Google Gemini API key enables every app feature—from real-time voice conversations to post-session feedback analysis—in the fastest and most cost-effective way.**

| Service | Primary Purpose | Required / Optional | Link |
| :--- | :--- | :--- | :--- |
| **Google (Gemini)** | Real-time voice conversation (Gemini Live) + Deep feedback analysis | **Required (Single key covers all features)** | [aistudio.google.com](https://aistudio.google.com) |
| **OpenAI** | Real-time voice (OpenAI Realtime) + Feedback + Premium TTS | Optional | [platform.openai.com](https://platform.openai.com) |
| **Anthropic (Claude)** | Session feedback analysis (selectable backend) | Optional (Gemini is default) | [console.anthropic.com](https://console.anthropic.com) |

### API Key Entry and Security
API keys are entered directly within the app:
* Configure during the **First-Run Onboarding Wizard** or via the **Settings gear (⚙️) → Preferences → API Keys** menu in the top bar.
* Entered API keys are **stored securely** in on-device encrypted storage (`EncryptedSharedPreferences`) and are never sent to external servers.
* Each key field includes an eye icon on the right to toggle key visibility.

### 🎈 Demo Mode (Completely Free Trial)
If you want to experience the app without registering an API key or granting microphone permissions, select **Demo** mode on the onboarding screen or under **Preferences**.
Scripted mock conversations and feedback data will load, allowing you to explore the full UI, error tracker, and review features for **free** without consuming any tokens. After completing a demo session, a prompt lets you enter an API key or continue with the next demo patient practice anytime.

---

## 2. App Installation and Permissions Setup

Bedside English runs on smartphones and tablets running Android 8.0 (API Level 26) or higher.

### App Installation
* Launch the provided installation file (`.apk`) on your Android device and follow the on-screen instructions to install.

### Microphone Permission & Type-Instead Mode
* When launching a live voice practice mode for the first time, Android OS requests microphone access permission. Tap **[Allow]** for voice recognition to function properly.
* If you are in an environment where speaking is difficult or if permission is denied, the app will not crash. It automatically switches to **Type instead** mode, allowing you to practice conversations using keyboard input.

### Notification Permission & Audio Preflight
* Notification permission is requested so you can receive notifications when background feedback analysis completes.
* Immediately before starting your first live voice session, an **Audio Preflight** check modal is displayed to guide headphone usage and test microphone input levels, preventing speaker feedback loops (howling).

---

## 3. First-Run Onboarding & L1 Learner Customization

When launching the app for the first time, a 4-step setup wizard runs to build a customized learning environment:

1. **Welcome Screen**: Introduces core features and provides a **Try Demo Mode** button to explore without API keys.
2. **UI Language Picker**: Select your preferred interface language (8 supported languages: English, Korean, Spanish, Chinese, Arabic, Hindi, Portuguese, Tagalog).
3. **API Keys Setup**: Register your Google Gemini or other AI API keys.
4. **Native Language & Privacy**: Select your first language (e.g., **Korean**, Chinese, Spanish, Arabic, Hindi, Tagalog, Portuguese). This activates precision grammar and pronunciation analysis tailored to your native language's specific interference patterns.

### 🌐 L1 Native Language Customization Highlights (e.g., Korean L1 Learners)
* **Grammar & Phrasing Corrections**:
  * Missing articles (omitting *a/an/the* before nouns)
  * Missing plural *-s* (*two patient* → *two patients*)
  * Tense errors (using present tense when discussing past medical history)
  * Preposition misuse (*in hospital*, omitting prepositions in *explain to patient*)
  * Literal direct translations / Konglish (*skin scale*, awkward direct translation of *side effect*)
* **Pronunciation & Intelligibility Corrections**:
  * *r / l* minimal pair distinction (*liver* vs *river*)
  * *f / p* distinction (*fever* vs *peter*)
  * *th* dental fricative pronunciation (*think* vs *tink*)
  * Omitted final consonants and unnecessary vowel insertion (*cardiac* → *cardi-ack-eu*)
  * Medical word stress misplacement (*angina*, *arrhythmia*)

### 💡 Interactive First-Run Tour
After completing onboarding and entering the Dashboard for the first time, an interactive tutorial automatically guides you through the locations and functions of key buttons (Dashboard, Practice Hub, Pron Lab, SRS Reviews, History, Help, 1:1 Voice Coach).

---

## 4. UI Layout Overview (Bottom Navigation 5 Tabs & Help)

### Bottom Navigation Bar (5 Tabs)
The main navigation consists of 5 bottom tabs:

```
┌───────────┬──────────────┬───────────────────────┬───────────────┬─────────────┐
│  🏠 Home  │  ▶ Practice  │  🎙️ Pronunciation Lab │  ⚠️ SRS Reviews│  🕘 History │
└───────────┴──────────────┴───────────────────────┴───────────────┴─────────────┘
```

1. **Home (Dashboard)**: Practice streak (`🔥`), 5-minute clinical mission, performance trend charts, Mistake Genome, roadmap, and **Always-on 1:1 Voice Coach**.
2. **Practice (Practice Hub)**: Central hub for all real-time speaking modes: Patient Encounters, Exam & Diagnostic, Survival English, Teach-back, Interviews, Lounge, and Custom Scenarios.
3. **Pronunciation Lab (Pron Lab)**: Dedicated training tab for speech intelligibility and pronunciation correction (error pattern filtering, observation status management).
4. **SRS Reviews (Weakness Review)**: Vocal review quizzes based on spaced repetition algorithms for accepted correction sentences.
5. **History (Session History)**: View scores and feedback from past sessions, track token costs, export to Anki/Word, and trigger **Attending Case Presentation (Present Case)**.

### Top App Bar
* **Bedside English Logo**: Main title.
* **Help Wiki (`?` Icon)**: Tapping the `?` icon opens this User Guide in a full-screen viewer with Table of Contents navigation and full-text keyword search.
* **Settings Gear (⚙️ Preferences)**: API keys, voice backends, speaking pace, echo prevention, language, and data management.

---

## 5. Mastering the Dashboard (Home)

The main Dashboard visually presents your growth in clinical English communication skills across multiple dimensions:

* **Practice Streak**: Displays consecutive active practice days with a flame icon (`🔥`) to build daily study habits.
* **Core Metric Cards**:
  * **Sessions done**: Total number of fully completed and analyzed sessions.
  * **Errors tracked**: Confirmed corrections registered in your personal weakness database.
  * **Mastered**: Mistakes resolved and graduated through repeated review quizzes.
  * **Due now**: Number of SRS review cards scheduled for vocal review today.
  * **Stubborn**: "Leech" errors slipped 4+ consecutive times requiring focused attention.
* **Today's 5-Minute Clinical Mission**: Automatically recommends an optimal 5-minute practice course targeting due review items, required diagnostics, or your weakest skill domain.
* **OET Layman Vocabulary Coverage**:
  * Tracks how effectively you substitute plain, patient-friendly terms (e.g., *fainting*) for complex medical jargon (e.g., *syncope*).
  * Used terms appear under **Recently Unlocked**, while unused expressions are queued under **Next Goals (Locked)**.
* **Mistake Genome Panel**: Analyzes your most frequent error categories (Articles, Plurals, Tenses, Prepositions, Register, Direct Translations) and displays your top 5 weak areas as a bar chart.
* **Growth Trends & L1 Interference Chart**:
  * Graphs score trends across 5 domains (Grammar, Accuracy, Reasoning, Professionalism, Fluency) over your last 20 sessions.
  * Visualizes recurring grammatical mistake patterns.
* **Personalized Roadmap**: Analyzes weak metrics and error history to present 4 prioritized focus skill cards.
* **Always-On 1:1 Voice Coach Button (`🎙️ RecordVoiceOver`)**:
  * Located at the bottom right of the Dashboard. Tap to instantly open a 1:1 vocal speaking dialogue with an AI tutor based on your personal weakness profile without starting a full session scenario.

---

## 6. Practice Hub & Progressive Feature Unlocking

### Progressive Feature Unlocking
To prevent new users from feeling overwhelmed, first-time users start with a calm intro screen displaying core modes (**Dashboard**, **Patient Encounters**, **Survival English**, **History**).
* **Completing your first practice session** automatically **unlocks** advanced modes (Exam, Teach-back, Interview, Lounge, Custom) with a celebration message.
* You can also tap to expand and reveal all modes immediately from the Practice screen.

### Practice Hub Main Mode Categories
1. **Patient Encounters**: History taking, follow-up, Foundations beginner mode, Skill Drills, Practice My Mistakes.
2. **Exam & Diagnostic**: 10-minute Baseline Diagnostic, OSCE, OET, Residency, and Ward Round mock exams.
3. **Survival English & Listening Lab**: Hospital unexpected situations, rapid small talk, 15 native accent profiles, Listening Lab detail drills.
4. **Lecture Teach-back**: Feynman technique training based on YouTube/text summaries.
5. **Residency Interviews**: Behavioral, Clinical, and IMG-specific mock interviews.
6. **Free English Lounge**: Medical debates, news subtitle discussions, workplace conflict resolution.
7. **Custom Scenarios**: Author custom AI prompts and scoring rubrics.

---

## 7. Patient Encounters & Live History Coverage Tracker

Simulates bedside history taking and patient counseling—the core of clinical communication.

### 7-1. Operational Sub-Modes
* **Foundations Mode**: Removes clinical reasoning burdens for early learners, focusing strictly on **grammar, clinical vocabulary, rapport building, and fluency**.
* **Skill Drills**: Targeted micro-competency exercises (NURSE empathy technique, plain-language explanations, night shift handover, **Native Language → English sequential medical interpreting**).
* **Practice My Mistakes**: Synthesizes an instant vocal dialogue quiz from pending errors in your database.
* **Daily Mission**: An adaptive 5-minute daily challenge that targets your current skill gaps.

### 7-2. Live History Coverage Tracker
A collapsible real-time panel that checks off history-taking items as you speak:
* Automatically tracks items based on AI conversation context.
* Monitors onset/duration, pain character, radiation, aggravating/relieving factors, associated symptoms, ICE (Ideas, Concerns, Expectations), past history, medications, allergies, alcohol/smoking, family history, etc.
* Negative confirmation questions like *"You don't smoke, do you?"* are correctly recognized and tracked.

### 7-3. Context-Aware Continuation Help (`💡 Help me continue`)
If you get stuck or run out of questions mid-session, tap **💡 Help me continue** at the bottom of the screen.
* Analyzes the patient's latest response to suggest the next logical question objective along with a **ready-to-use English sample sentence**.
* The top Interview Phase Tracker displays progress using status symbols:
  * `✓`: Sufficient evidence detected
  * `•`: Partial mention detected
  * `?`: Later phase reached without prior phase verification

---

## 8. Exam & Diagnostic Mode (10-minute Baseline & CEFR Mapping)

Measures communication proficiency under timed, immersive exam conditions:

### 10-minute Baseline Clinical English Diagnostic
* Begins with an examiner introduction, followed by 4 short tasks (explaining a diagnosis, handling follow-up questions, delivering a 45-second SBAR handover, answering a residency interview question).
* Automatically maps your performance to international **CEFR grades**:
  * **Score >= 8.5**: **C1** (Fluent, safe clinical communication at attending physician level)
  * **Score >= 7.2**: **B2+** (Competent for clinical clerkship and hospital practice)
  * **Score >= 6.0**: **B1-B2** (Basic communication capability; structured study recommended)
  * **Score < 6.0**: **A2-B1** (Foundational clinical communication training required)

### Mock Exam Scenarios
* **OSCE**: Mr. Hayes chest pain history taking (measuring ICE, red flag detection, empathy).
* **OET Speaking**: Hypertension patient counseling roleplay.
* **Residency**: US Internal Medicine Program Director mock interview.
* **Ward Round**: 5-minute community-acquired pneumonia case presentation and oral question handling.

### Score Reliability Badge
Indicates AI grading confidence as **High**, **Medium**, or **Low**:
* **High**: Learner word count >= 180 words & checklist evidence rate >= 75%.
* **Medium**: Learner word count >= 80 words & checklist evidence rate >= 50%.
* **Low**: Word count < 80 words (Short Transcript flag) or evidence rate < 50%.

---

## 9. Pronunciation & Intelligibility Lab (Pron Lab)

Located in the 3rd dedicated bottom tab (`🎙️ Pron Lab`), this is your specialized training center for speech intelligibility.

### 💡 Intelligibility-Centered Coaching
The goal is not native accent imitation, but **"Can international colleagues and patients understand my speech clearly without misunderstanding?"**
* Audio analysis provides pinpoint coaching only on pronunciation items that cause listener misunderstanding.

### Error Pattern Categories & Filter Chips
Pronunciation errors detected across your sessions are organized by category into filter chips:
* `r · l`: *liver / river*, *clinical / critical*
* `f · p`: *fever / peter*, *palpation / falcation*
* `th`: *think / tink*, *throat / troat*
* `final`: Omitted final consonants (*chest / ches*)
* `cluster`: Consonant cluster processing & unnecessary vowel insertion (*cardiac* → *cardi-ack-eu*)
* `stress`: Medical word stress misplacement (*angina*, *arrhythmia*)
* `vowel`: Short vs. long vowel confusion (*ship / sheep*, *fit / feet*)

### Observed State Promotion Rule
When an accepted pronunciation card is first logged, it enters an **Observed** state rather than immediately becoming a daily homework card. It is promoted to an active SRS review error card only when the same error pattern recurs in a separate session, ensuring single-occurrence speech-recognition glitches do not create excessive homework.

---

## 10. Survival English & Listening Lab

Prepares IMGs for real-world, non-clinical hospital interactions outside the exam room.

* **Random / Surprise Mode**: Spontaneous handling of unexpected situations (hallway curbside questions, pharmacy callbacks, nurse small talk) with scenario context hidden until the AI speaks.
* **Rapid-fire Small Talk**: Quick responses to sudden topic changes.
* **15 Native Accent Profiles**: Practice adapting to international native accents and speech rhythms.
* **Real-time Audio Speed Control (0.5× to 2.5×)**: Pitch-preserving playback speed slider to adjust to fast native speakers.
* **Ear-only Listening Mode**: Hides AI conversation subtitles so you rely purely on listening, with **[Reveal last line]** available when needed.
* **Repair Expression Encouragement**: Using repair strategies like *"Sorry?", "Could you say that again?"* grants **bonus points** rather than point deductions.
* **Listening Lab**: Exercises testing exact detail comprehension (numbers, drug dosages, patient names, times, directions, prices) with itemized accuracy scoring.

---

## 11. Lecture Teach-back (Feynman Technique)

Uses the Feynman technique—explaining concepts aloud as if teaching someone else—to solidify medical knowledge.

1. **Prepare Lecture Material**: Paste summary notes or enter a YouTube medical lecture URL and tap **`🎬 Fetch YT transcript`**.
2. **AI Condensation**: For long materials, tap **`✨ Condense`** to compress text into a structured 500-word outline.
3. **Select Audience Persona**:
   * **Oral Exam Professor**: Asks sharp, challenging clinical "Why" and "What-if" follow-up questions.
   * **Confused Classmate**: Requests plain-language explanations without heavy jargon.
   * **Friendly Tutor**: Provides supportive encouragement and phrasing guidance.
4. **Teach**: Tap **Start** and explain via microphone as the AI listener responds with clarifying questions.

---

## 12. Residency Mock Interviews

Simulates realistic interviews for overseas hospital employment and US Residency Match:

* **Behavioral**: STAR method (Situation, Task, Action, Result) experience descriptions.
* **Clinical**: Oral case presentation, medical ethics, and emergency management logic.
* **IMG-Specific**: Focuses on common IMG questions (visa sponsorship, CV gap year explanations, unique strengths as an IMG).
* An AI Program Director leads polite yet probing follow-up questions.

---

## 13. Free English Lounge & Custom Scenarios

### Free English Lounge
* Discuss current medical topics, summarize journal articles, resolve workplace/nurse conflicts, or practice coffee-break small talk.
* Fetch YouTube medical news subtitles to debate freely with the AI.

### Custom Scenarios
Create custom practice scenarios tailored to your needs:
* **Scenario Name**: Custom identifier.
* **Persona / Context**: System prompt defining the AI's role and situation.
* **Eval Template**: Select evaluation rubrics (e.g., SPIKES protocol for delivering bad news).
* **Custom Eval Criteria**: Set specific key assessment points for the AI to check.

---

## 14. Deconstructing the Feedback Report (7 Main Sections)

After completing a session, a 7-section report provides multi-dimensional feedback:

1. **Scores**: Compares AI domain scores (0–10) side-by-side with your self-assessment. A gap of **2.0+ points** triggers a yellow **Reflection Prompt** card to guide self-reflection.
2. **Fluency**:
   * **WPM (Words Per Minute)**: Measures speaking speed against recommended targets (100–130 WPM).
   * **Filler Words**: Measures filler word density (`um`, `uh`, `like`), encouraging effective use of pauses.
3. **Checklist**: Evaluates clinical objectives, quoting exact transcript sentences as **Evidence**.
4. **SOAP Note Comparison**: Compares an automatically generated SOAP note from your session against a model reference SOAP note.
5. **Corrections**: Card-based corrections for direct translations, articles/plurals, unnatural expressions, and pronunciation. Tapping **[Accept]** registers the item into your personal SRS error tracker.
6. **Shadowing**: Rewrites weak sentences into attending-level clinical English for listen-and-repeat audio training.
7. **Summary & Share Cards**: Displays overall feedback and provides a **Share Card** image generator to share performance summaries with study peers.

---

## 15. Socratic AI Tutor & Always-On 1:1 Voice Coach

### 15-1. Socratic AI Tutor 1:1 Debrief (`[Debrief with AI Tutor]`)
Tapping **[Debrief with AI Tutor]** at the bottom of the feedback report opens a 1:1 chatroom with a Socratic AI mentor.
* Asks guiding questions rather than handing out answers directly, helping you discover and correct mistakes yourself.
* Debrief chat history is preserved in the database so you can return and continue anytime.

### 15-2. Always-On 1:1 Speaking Voice Coach (Home FAB)
Tapping the **Voice Coach Floating Button (`🎙️ RecordVoiceOver`)** on the bottom right of the Dashboard opens an instant speaking coach dialogue without completing a full scenario first.
* The AI coach leads customized 1:1 voice conversations based on your accumulated SRS weak points.

---

## 16. Spaced Repetition Error Tracker & Mistake Genome

Corrections accepted via **[Accept]** are automatically managed by spaced repetition algorithms in your error database.

* **Fuzzy Duplicate Detection**: Automatically prevents duplicate logging of similar mistakes.
* **Leech / Stubborn Errors**: Items missed 4+ consecutive times in review quizzes are tagged as **Stubborn / Leech** for focused management.
* **Scientific Spaced Repetition Intervals**:
  * Review schedule: **1 day → 3 days → 7 days → 14 days → 30 days**. Passing 3 consecutive reviews graduates the item to **Mastered**.
  * Clearing vocal quizzes in **Practice My Mistakes** advances items toward Mastery.
* **Mistake Genome Panel**:
  * Displays your top 5 weak error categories (Articles, Plurals, Tense, Prepositions, Register, Direct Translations) as a Dashboard bar chart with plain-language tooltips.

---

## 17. Attending Case Presentation Chaining

Train oral handoff skills by presenting cases to a supervisor following a Patient Encounter:

1. Complete a **Patient Encounter** session.
2. Go to the **History** tab, select the session, and tap **`📋 Present Case`**.
3. The AI Attending opens with: *"Doctor, please present the case you just saw."*
4. Deliver an oral case presentation using SBAR or SOAP format, and answer follow-up questions on differential diagnosis and treatment plans.

---

## 18. Importing External Conversation History (Import Transcript)

Import conversation text from ChatGPT, Gemini, or clinical notes into the app to receive full feedback:

1. **Android Share Integration**: Highlight conversation text in external apps and select **[Share] → [Bedside English]** to automatically open the **Import Transcript** screen.
2. **Direct Entry / Paste**: Open the `Import Transcript` screen directly from Preferences or the main menu and paste text.
3. **Automated Feedback & SRS**: Generates domain scores, SOAP notes, and correction cards available for SRS acceptance.

---

## 19. Anki Decks & Word Document Exports

* **Anki Card Export (tab-separated `.txt`)**:
  * Converts accepted corrections into an Anki plain-text import file (File → Import in Anki / AnkiDroid). Each mistake's category is carried across as a tag. Available both on the post-session feedback screen and from **My Mistakes**, which exports your whole review list at once.
* **Word Report (`.docx`) Export**:
  * Generates structured medical reports containing scores, checklist evidence, SOAP notes, and sentence corrections. Preferences option enables auto-saving.

---

## 20. In-App Help Wiki

Tapping the **`?` Help icon** in the top app bar opens the built-in Help Wiki viewer in full-screen mode.

* **Multilingual Integration**: Automatically loads the user guide file matching the app's UI language setting.
* **Table of Contents (TOC) Sidebar**: Enables rapid jump navigation across all guide sections.
* **Full-Text Search**: Enter keywords in the search bar to highlight matches and navigate with previous/next buttons.
* **External Hyperlinks**: Tapping web links in the guide opens your default system browser.

---

## 21. UI Language Settings, API Cost Tracking & Preferences

Access the **Settings gear (⚙️)** in the top bar to tailor the app to your device and budget:

* **Voice & Feedback**:
  - Select voice and feedback AI models (Demo, Gemini, OpenAI, Claude).
  - **Echo Prevention**: Automatically mutes the microphone while the AI speaks to prevent audio feedback loops (essential when not using headphones).
  - **AI Speaking Pace**: Stepwise speed control (Slow, Normal, Fast, Challenge).
* **API Cost Analytics**: Transparently tracks token usage and estimated dollar cost per session with chart visualizations.
* **Audio**: Real-time microphone level indicator and speaker test tone.
* **API Keys**: Local encrypted storage key manager.
* **Export & Learning**: Docx auto-save options, mandatory SOAP note entry, Native Language, and UI Language settings.
* **Data**: Toggle pronunciation analysis, configure engines, and select TTS shadowing engines.
* **Privacy**: Toggle anonymous usage telemetry.

---

## 22. Frequently Asked Questions (FAQ) & Scoring Rubric

### Frequently Asked Questions (FAQ)

**Q: The microphone is not picking up audio and the AI does not respond.**
* Check Android **Settings → Apps → Bedside English → Permissions → Microphone** and set it to [Allow]. If permission is denied, the app switches to **Type instead** mode so you can continue practicing via keyboard.

**Q: Advanced practice modes (Exam, Teach-back, Interview, Lounge, Custom) are missing.**
* Complete your first practice session and all advanced modes will automatically unlock with a celebration message. You can also expand and reveal all modes from the Practice screen.

**Q: Pronunciation analysis results do not go into my SRS review database immediately.**
* To prevent single-occurrence speech-recognition errors from burdening learners, accepted pronunciation items enter an **Observed** state first. They are promoted to active SRS review cards only when the same error pattern recurs in a future session.

**Q: Can I import external conversation transcripts (e.g., ChatGPT) for grading?**
* Yes. Use Android's share feature to share text to Bedside English or paste text into the `Import Transcript` screen to receive full feedback, SOAP notes, and correction cards.

---

### 📝 Detailed Assessment Scoring Rubric (0–10 Score Range)

| Score | Rating Level | Assessment Criteria |
| :--- | :--- | :--- |
| **9 ~ 10** | **Attending / Expert** | Flawless grammar and expression; precise clinical vocabulary and systematic medical reasoning; natural, professional tone. |
| **7 ~ 8** | **Competent / Pass** | Minor grammatical errors but fully clear communication; identifies key risk factors and performs differential diagnosis safely. |
| **5 ~ 6** | **Developing** | Frequent structural grammatical errors requiring listener effort; unsystematic clinical reasoning and vocabulary. |
| **1 ~ 4** | **Critical / Fail** | Severe medical errors or missed risk factors; speech limited to single words or frequent long pauses preventing normal dialogue. |
