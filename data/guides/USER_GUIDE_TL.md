# Bedside English Gabay sa Gumagamit

**Target na Gumagamit:** International Medical Graduates (IMGs), resident physicians, mga estudyante ng medisina, at mga propesyonal sa kalusugan na naghahanda para sa clinical English, OSCE, OET Speaking, US Residency Interviews, Ward Round presentations, at Clinical Communication.

Ang Bedside English ay isang eksklusibong Android app na pinapatakbo ng conversational AI (Google Gemini, OpenAI Realtime, Anthropic Claude) at real-time voice technology. Dinisenyo ito upang sanayin ang mga kasanayan sa komunikasyong klinikal, medikal na pangangatwiran, pagbigkas, at linaw ng pagsasalita (intelligibility) sa maramihang dimensyon. Inilalahad ng gabay na ito ang lahat ng tampok at paggamit ng pinakabagong bersyon ng app na inangkop para sa mga gumagamit.

---

## 📌 Talaan ng Nilalaman

1. [Pagsasaayos ng API Key at Configuration](#1-pagsasaayos-ng-api-key-at-configuration)
2. [Pag-install ng App at Permissions Setup](#2-pag-install-ng-app-at-permissions-setup)
3. [First-Run Onboarding at L1 Learner Customization](#3-first-run-onboarding-at-l1-learner-customization)
4. [Pangkalahatang-tanaw sa UI Layout (Bottom Navigation 5 Tabs at Help)](#4-pangkalahatang-tanaw-sa-ui-layout-bottom-navigation-5-tabs-at-help)
5. [Pag-master sa Dashboard (Home)](#5-pag-master-sa-dashboard-home)
6. [Practice Hub at Unti-unting Pag-unlock ng Tampok](#6-practice-hub-at-unti-unting-pag-unlock-ng-tampok)
7. [Patient Encounters at Live History Coverage Tracker](#7-patient-encounters-at-live-history-coverage-tracker)
8. [Exam at Diagnostic Mode (10-minute Baseline at CEFR Mapping)](#8-exam-at-diagnostic-mode-10-minute-baseline-at-cefr-mapping)
9. [Pronunciation at Intelligibility Lab (Pron Lab)](#9-pronunciation-at-intelligibility-lab-pron-lab)
10. [Survival English at Listening Lab](#10-survival-english-at-listening-lab)
11. [Lecture Teach-back (Feynman Technique)](#11-lecture-teach-back-feynman-technique)
12. [Residency Mock Interviews](#12-residency-mock-interviews)
13. [Free English Lounge at Custom Scenarios](#13-free-english-lounge-at-custom-scenarios)
14. [Pagsusuri sa Feedback Report (7 Pangunahing Bahagi)](#14-pagsusuri-sa-feedback-report-7-pangunahing-bahagi)
15. [Socratic AI Tutor at Always-On 1:1 Voice Coach](#15-socratic-ai-tutor-at-always-on-11-voice-coach)
16. [Spaced Repetition Error Tracker at Mistake Genome](#16-spaced-repetition-error-tracker-at-mistake-genome)
17. [Attending Case Presentation Chaining](#17-attending-case-presentation-chaining)
18. [Pag-import ng External Conversation History (Import Transcript)](#18-pag-import-ng-external-conversation-history-import-transcript)
19. [Anki Decks at Word Document Exports](#19-anki-decks-at-word-document-exports)
20. [In-App Help Wiki](#20-in-app-help-wiki)
21. [UI Language Settings, API Cost Tracking at Preferences](#21-ui-language-settings-api-cost-tracking-at-preferences)
22. [Mga Madalas Itanong (FAQ) at Scoring Rubric](#22-mga-madalas-itanong-faq-at-scoring-rubric)

---

## 1. Pagsasaayos ng API Key at Configuration

Flexible na sinusuportahan ng Bedside English ang mga backend ng Google Gemini, OpenAI, at Anthropic Claude.

### 💡 Inirerekomendang Setup (Google Gemini Single Key Mode)

**Ang pagrehistro ng iisang Google Gemini API key ay nagpapagana sa bawat tampok ng app—mula sa real-time voice conversations hanggang sa post-session feedback analysis—sa pinakamabilis at pinakamatipid na paraan.**

| Serbisyo               | Pangunahing Layunin                                                 | Required / Optional                                         | Link                                                   |
| :--------------------- | :------------------------------------------------------------------ | :---------------------------------------------------------- | :----------------------------------------------------- |
| **Google (Gemini)**    | Real-time voice conversation (Gemini Live) + Deep feedback analysis | **Required (Sinasaklaw ng iisang key ang lahat ng tampok)** | [aistudio.google.com](https://aistudio.google.com)     |
| **OpenAI**             | Real-time voice (OpenAI Realtime) + Feedback + Premium TTS          | Optional                                                    | [platform.openai.com](https://platform.openai.com)     |
| **Anthropic (Claude)** | Session feedback analysis (maaaring piliing backend)                | Optional (Gemini ang default)                               | [console.anthropic.com](https://console.anthropic.com) |

### API Key Entry at Security

Ang mga API key ay inilalagay nang direkta sa loob ng app:

- I-configure ito sa panahon ng **First-Run Onboarding Wizard** o sa pamamagitan ng **Settings gear (⚙️) → Preferences → API Keys** menu sa top bar.
- Ang mga nailagay na API key ay **ligtas na nakaimbak** sa on-device encrypted storage (`EncryptedSharedPreferences`) at hindi kailanman ipinapadala sa mga panlabas na server.
- Ang bawat key field ay may kasamang eye icon sa kanan upang i-toggle ang visibility ng key.

### 🎈 Demo Mode (Completely Free Trial)

Kung gusto mong maranasan ang app nang hindi nagrerehistro ng API key o nagbibigay ng microphone permissions, piliin ang **Demo** mode sa onboarding screen o sa ilalim ng **Preferences**.
Maglo-load ang mga naka-script na mock conversation at feedback data, na magbibigay-daan sa iyong galugarin ang buong UI, error tracker, at mga tampok sa pagsusuri nang **libre** nang walang nakokonsumong anumang token. Pagkatapos makumpleto ang isang demo session, isang prompt ang magbibigay-daan sa iyo na maglagay ng API key o magpatuloy sa susunod na demo patient practice anumang oras.

---

## 2. Pag-install ng App at Permissions Setup

Gumagana ang Bedside English sa mga smartphone at tablet na nagpapatakbo ng Android 8.0 (API Level 26) o mas mataas.

### App Installation

- Ilunsad ang ibinigay na installation file (`.apk`) sa iyong Android device at sundin ang mga tagubilin sa screen upang i-install.

### Microphone Permission at Type-Instead Mode

- Kapag naglulunsad ng isang live voice practice mode sa unang pagkakataon, ang Android OS ay hihingi ng pahintulot para sa microphone access. I-tap ang **[Allow]** upang gumana nang maayos ang voice recognition.
- Kung ikaw ay nasa isang kapaligiran kung saan mahirap magsalita o kung ang pahintulot ay tinanggihan, hindi magca-crash ang app. Awtomatiko itong lilipat sa **Type instead** mode, na magbibigay-daan sa iyong magsanay ng mga pag-uusap gamit ang keyboard input.

### Notification Permission at Audio Preflight

- Hinihingi ang notification permission upang makatanggap ka ng mga abiso kapag nakumpleto na ang background feedback analysis.
- Bago mismo simulan ang iyong unang live voice session, isang **Audio Preflight** check modal ang ipapakita upang gumabay sa paggamit ng headphone at subukan ang mga antas ng microphone input, na pumipigil sa speaker feedback loops (howling).

---

## 3. First-Run Onboarding at L1 Learner Customization

Kapag inilunsad ang app sa unang pagkakataon, tatakbo ang isang 4-step setup wizard upang bumuo ng isang naka-customize na learning environment:

1. **Welcome Screen**: Ipinapakilala ang mga pangunahing tampok at nagbibigay ng **Try Demo Mode** button upang galugarin nang walang mga API key.
2. **UI Language Picker**: Piliin ang iyong gustong interface language (8 suportadong wika: English, Korean, Spanish, Chinese, Arabic, Hindi, Portuguese, Tagalog).
3. **API Keys Setup**: Irehistro ang iyong Google Gemini o iba pang mga AI API key.
4. **Native Language at Privacy**: Piliin ang iyong unang wika (hal., **Korean**, Chinese, Spanish, Arabic, Hindi, Tagalog, Portuguese). Ina-activate nito ang precision grammar at pronunciation analysis na iniangkop sa mga partikular na interference pattern ng iyong katutubong wika.

### 🌐 Mga Highlight sa L1 Native Language Customization (hal., Korean L1 Learners)

- **Mga Pagwawasto sa Gramatika at Phrasing**:
  - Mga nawawalang article (pag-omit ng _a/an/the_ bago ang mga pangngalan)
  - Nawawalang plural _-s_ (_two patient_ → _two patients_)
  - Mga error sa tense (paggamit ng present tense kapag tinatalakay ang past medical history)
  - Maling paggamit ng preposition (_in hospital_, pag-omit ng mga preposition sa _explain to patient_)
  - Mga literal na direktang pagsasalin / Konglish (_skin scale_, awkward na direktang pagsasalin ng _side effect_)
- **Mga Pagwawasto sa Pagbigkas at Intelligibility**:
  - Pagtatangi sa _r / l_ minimal pair (_liver_ vs _river_)
  - Pagtatangi sa _f / p_ (_fever_ vs _peter_)
  - Pagbigkas ng _th_ dental fricative (_think_ vs _tink_)
  - Nawawalang mga pinal na katinig at hindi kinakailangang pagdagdag ng patinig (_cardiac_ → _cardi-ack-eu_)
  - Maling paglalagay ng diin sa mga medikal na salita (_angina_, _arrhythmia_)

### 💡 Interactive First-Run Tour

Pagkatapos ng onboarding, i-ha-highlight ng isang interactive na step-by-step tutorial ang mga lokasyon at function ng mga pangunahing button (Dashboard, Practice Hub, Pron Lab, SRS Reviews, History, Help, 1:1 Voice Coach) upang matulungan kang mag-navigate nang mabilis.

---

## 4. Pangkalahatang-tanaw sa UI Layout (Bottom Navigation 5 Tabs at Help)

### Bottom Navigation Bar (5 Tabs)

Ang pangunahing navigation ay binubuo ng 5 bottom tabs:

```
┌───────────┬──────────────┬───────────────────────┬───────────────┬─────────────┐
│  🏠 Home  │  ▶ Practice  │  🎙️ Pronunciation Lab │  ⚠️ SRS Reviews│  🕘 History │
└───────────┴──────────────┴───────────────────────┴───────────────┴─────────────┘
```

1. **Home (Dashboard)**: Practice streak (`🔥`), 5-minute clinical mission, performance trend charts, Mistake Genome, roadmap, at **Always-on 1:1 Voice Coach**.
2. **Practice (Practice Hub)**: Ang sentrong hub para sa lahat ng real-time speaking modes: Patient Encounters, Exam & Diagnostic, Survival English, Teach-back, Interviews, Lounge, at Custom Scenarios.
3. **Pronunciation Lab (Pron Lab)**: Dedikadong tab ng pagsasanay para sa kalinawan ng pagsasalita at pagwawasto sa pagbigkas (error pattern filtering, observation status management).
4. **SRS Reviews (Weakness Review)**: Vocal review quizzes batay sa mga spaced repetition algorithm para sa mga tinanggap na correction sentence.
5. **History (Session History)**: Tingnan ang mga score at feedback mula sa mga nakaraang session, subaybayan ang token costs, i-export sa Anki/Word, at i-trigger ang **Attending Case Presentation (Present Case)**.

### Top App Bar

- **Bedside English Logo**: Pangunahing titulo.
- **Help Wiki (`?` Icon)**: Ang pag-tap sa `?` icon ay nagbubukas nitong Gabay sa Gumagamit (User Guide) sa isang full-screen viewer na may Table of Contents navigation at full-text keyword search.
- **Settings Gear (⚙️ Preferences)**: API keys, voice backends, speaking pace, echo prevention, wika, at pamamahala ng data.

---

## 5. Pag-master sa Dashboard (Home)

Ang pangunahing Dashboard ay biswal na nagpapakita ng paglago ng iyong mga kasanayan sa clinical English communication sa iba't ibang dimensyon:

- **Practice Streak**: Ipinapakita ang magkakasunod na aktibong araw ng pagsasanay gamit ang isang flame icon (`🔥`) upang bumuo ng pang-araw-araw na gawi sa pag-aaral.
- **Core Metric Cards**:
  - **Sessions done**: Kabuuang bilang ng ganap na nakumpleto at nasuring mga session.
  - **Errors tracked**: Nakumpirmang mga pagwawasto na nakarehistro sa iyong personal na weakness database.
  - **Mastered**: Mga pagkakamaling nalutas at nagtapos sa pamamagitan ng paulit-ulit na mga review quiz.
  - **Due now**: Bilang ng mga SRS review card na nakaiskedyul para sa vocal review ngayong araw.
  - **Stubborn**: Ang mga "Leech" error na nakalusot nang 4+ magkakasunod na beses na nangangailangan ng masinsinang atensyon.
- **Today's 5-Minute Clinical Mission**: Awtomatikong nagrerekomenda ng optimal na 5-minutong practice course na nagta-target ng due review items, kinakailangang diagnostics, o ang pinakamahinang skill domain mo.
- **OET Layman Vocabulary Coverage**:
  - Sinusubaybayan kung gaano ka kaepektibo sa pagpapalit ng mga simple at madaling maintindihang termino (hal., _fainting_) sa halip na kumplikadong medical jargon (hal., _syncope_).
  - Ang mga nagamit na termino ay makikita sa ilalim ng **Recently Unlocked**, habang ang mga hindi nagamit na ekspresyon ay nakapila sa ilalim ng **Next Goals (Locked)**.
- **Mistake Genome Panel**: Inaanalisa ang iyong pinakamadalas na kategorya ng pagkakamali (Articles, Plurals, Tenses, Prepositions, Register, Direct Translations) at ipinapakita ang iyong nangungunang 5 mahihinang aspeto bilang isang bar chart.
- **Growth Trends at L1 Interference Chart**:
  - Naggagrap ng mga trend sa score sa 5 domain (Grammar, Accuracy, Reasoning, Professionalism, Fluency) mula sa iyong huling 20 session.
  - Bino-visualize ang mga paulit-ulit na pattern ng pagkakamali sa gramatika.
- **Personalized Roadmap**: Inaanalisa ang mga mahihinang sukatan (weak metrics) at kasaysayan ng pagkakamali upang maglahad ng 4 na naka-prioritize na focus skill card.
- **Always-On 1:1 Voice Coach Button (`🎙️ RecordVoiceOver`)**:
  - Matatagpuan sa kanang ibaba ng Dashboard. I-tap upang agad na magbukas ng 1:1 vocal speaking dialogue kasama ang isang AI tutor batay sa iyong personal na weakness profile nang hindi kinakailangang magsimula ng isang buong senaryo.

---

## 6. Practice Hub at Unti-unting Pag-unlock ng Tampok

### Unti-unting Pag-unlock ng Tampok

Upang maiwasang ma-overwhelm ang mga bagong user, ang mga first-time user ay nagsisimula sa isang kalmadong intro screen na nagpapakita ng mga pangunahing mode (**Dashboard**, **Patient Encounters**, **Survival English**, **History**).

- **Ang pagkumpleto sa iyong unang practice session** ay awtomatikong mag-a-**unlock** sa mga advanced mode (Exam, Teach-back, Interview, Lounge, Custom) nang may celebration message.
- Maaari mo ring i-tap upang palawakin at agad na i-reveal ang lahat ng mga mode mula sa Practice screen.

### Mga Pangunahing Kategorya ng Mode sa Practice Hub

1. **Patient Encounters**: History taking, follow-up, Foundations beginner mode, Skill Drills, Practice My Mistakes.
2. **Exam at Diagnostic**: 10-minute Baseline Diagnostic, OSCE, OET, Residency, at Ward Round mock exams.
3. **Survival English at Listening Lab**: Hindi inaasahang mga sitwasyon sa ospital, mabilisang small talk, 15 native accent profiles, Listening Lab detail drills.
4. **Lecture Teach-back**: Feynman technique training na batay sa YouTube/text summaries.
5. **Residency Interviews**: Behavioral, Clinical, at IMG-specific na mga mock interview.
6. **Free English Lounge**: Medical debates, pagtatalakay ng mga news subtitle, workplace conflict resolution.
7. **Custom Scenarios**: Gumawa ng custom AI prompts at scoring rubrics.

---

## 7. Patient Encounters at Live History Coverage Tracker

Ini-simulate ang bedside history taking at patient counseling—ang pinaka-ubod ng clinical communication.

### 7-1. Mga Operational Sub-Mode

- **Foundations Mode**: Inaaalis ang bigat ng clinical reasoning para sa mga baguhan, at mahigpit na nakatuon sa **gramatika, clinical vocabulary, pagbuo ng rapport, at fluency**.
- **Skill Drills**: Mga partikular na pagsasanay sa micro-competency (NURSE empathy technique, plain-language explanations, night shift handover, **Native Language → English sequential medical interpreting**).
- **Practice My Mistakes**: Agad na bumubuo ng vocal dialogue quiz mula sa mga nakabinbing pagkakamali sa iyong database.
- **Daily Mission**: Isang nakaaangkop na 5-minutong pang-araw-araw na hamon na naka-target sa iyong kasalukuyang kahinaan sa kakayahan.

### 7-2. Live History Coverage Tracker

Isang collapsible real-time panel na nagmamarka sa mga item sa history-taking habang ikaw ay nagsasalita:

- Awtomatikong sinusubaybayan ang mga item batay sa konteksto ng AI conversation.
- Binabantayan ang onset/duration, pain character, radiation, aggravating/relieving factors, associated symptoms, ICE (Ideas, Concerns, Expectations), past history, medications, allergies, alcohol/smoking, family history, atbp.
- Tumpak na kinikilala at sinusubaybayan ang mga negative confirmation question tulad ng _"You don't smoke, do you?"_

### 7-3. Context-Aware Continuation Help (`💡 Help me continue`)

Kung ikaw ay ma-blanko o maubusan ng mga katanungan sa kalagitnaan ng session, i-tap ang **💡 Help me continue** sa ibaba ng screen.

- Susuriin nito ang pinakahuling tugon ng pasyente upang magmungkahi ng susunod na lohikal na layunin ng tanong kasama ang isang **ready-to-use English sample sentence**.
- Ang Interview Phase Tracker sa itaas ay nagpapakita ng pag-usad gamit ang mga status symbol:
  - `✓`: May nakitang sapat na ebidensya
  - `•`: May nakitang bahagyang pagbanggit
  - `?`: Naabot ang huling bahagi nang walang pag-verify sa naunang bahagi

---

## 8. Exam at Diagnostic Mode (10-minute Baseline at CEFR Mapping)

Sinusukat ang kahusayan sa komunikasyon sa ilalim ng tinakdang oras at nakaka-engganyong mga kundisyon ng pagsusulit:

### 10-minute Baseline Clinical English Diagnostic

- Nagsisimula sa pagpapakilala ng examiner, na sinusundan ng 4 na maiikling gawain (pagpapaliwanag ng diagnosis, pagsagot sa mga follow-up na katanungan, paghahatid ng 45-segundong SBAR handover, pagsagot sa residency interview question).
- Awtomatikong imamapa ang iyong performance sa pandaigdigang **CEFR grades**:
  - **Score >= 8.5**: **C1** (Mabilis, at ligtas na clinical communication sa antas ng attending physician)
  - **Score >= 7.2**: **B2+** (May kakayahan para sa clinical clerkship at hospital practice)
  - **Score >= 6.0**: **B1-B2** (May pangunahing kakayahan sa komunikasyon; inirerekomenda ang structured na pag-aaral)
  - **Score < 6.0**: **A2-B1** (Kinakailangan ng pundasyonal na clinical communication training)

### Mock Exam Scenarios

- **OSCE**: Mr. Hayes chest pain history taking (sinusukat ang ICE, pagtukoy sa red flags, at empathy).
- **OET Speaking**: Hypertension patient counseling roleplay.
- **Residency**: US Internal Medicine Program Director mock interview.
- **Ward Round**: 5-minutong community-acquired pneumonia case presentation at pagsagot sa verbal na katanungan.

### Score Reliability Badge

Ipinapahiwatig ang AI grading confidence bilang **High**, **Medium**, o **Low**:

- **High**: Learner word count >= 180 salita at checklist evidence rate >= 75%.
- **Medium**: Learner word count >= 80 salita at checklist evidence rate >= 50%.
- **Low**: Word count < 80 salita (Short Transcript flag) o evidence rate < 50%.

---

## 9. Pronunciation at Intelligibility Lab (Pron Lab)

Ang **Pronunciation Lab (Pron Lab)** (ikatlong tab sa ibaba) ay naiiba mula sa iba pang feedback modules dahil sinasadya nitong balewalain ang gramatika (tulad ng sirang syntax) at nag-e-evaluate nang eksklusibo batay sa kung paano malinaw at naiintindihan ang sinasabi ng iyong bibig.

### 💡 Intelligibility-Centered Coaching

Hindi ipinipilit ng Bedside English sa mga IMG na magkaroon ng "American-sounding" o "British-sounding" accent. Ang layunin ay **Intelligibility (kalinawan)**:

- Hindi ka mamarkahan nang masama sa pagpapanatili ng iyong katutubong accent kung nauunawaan ang iyong mensahe.
- Gayunpaman, binabalaan ka ng AI kung sakaling magpalit ang iyong tunog na umaabot na sa minimal pair breakdown o medikal na pagkakamali (hal., pagkakaiba sa tunog ng _hypo_ vs _hyper_, o pangkalahatang hindi maintindihan ng voice-to-text algorithm dahil sa masyadong maikling tunog ng patinig).

### Error Pattern Categories at Filter Chips

Ang panel na ito ay naglilista ng bawat pagkakamali sa pagbigkas na na-tag mula sa lahat ng iyong nakaraang sessions, na inihanay mula sa pinakamadalas magawa. Pinagpangkat-pangkat ang mga pagkakamali sa 4 na pattern:

- **Consonants**: Mga isyu sa pagbigkas ng katinig (hal., `L/R`, `F/P`, `V/B`).
- **Vowels**: Masyadong maikli o sobrang haba na mga patinig, hindi pagbigkas ng mga diptonggo.
- **Stress**: Maling posisyon ng diin (hal., pag-diin sa unang pantig sa salitang `arrhythmia` imbes na sa gitna).
- **Dropped Endings**: Kumpletong hindi pagbigkas ng mga huling titik dahil sa mabilis na pananalita (hal., `cardiac` na naririnig na `cardia-`).
  _(Ang mga filter chips sa itaas ng screen ay nagbibigay-daan sa iyo upang i-sort ang mga ito.)_

### Observed State Promotion Rule

- Ang Voice Recognition software ay nagkakaroon din ng mga pagkakamali (glitch/hallucination). Upang maiwasang mabigatan ka sa pag-memorize ng flashcards sa mga isang-beses lang nangyaring pagkakamali (false positives), kapag ang isang bagong pronunciation mistake ay nangyari at pinindot mo ang **[Accept]**, mapupunta lang muna ito sa isang pasibong estado na tinatawag na **"Observed"**.
- Upang ma-promote ang error na maging pormal na item para sa araw-araw na review sa (SRS), kailangang marinig ka ng AI na gumawa ng parehong _pattern_ ng pagkakamali sa mga susunod mong paggamit.

---

## 10. Survival English at Listening Lab

Inihahanda ang mga IMG para sa mga tunay, hindi klinikal na interaksyon sa ospital sa labas ng examination room.

- **Random / Surprise Mode**: Biglaang paghawak sa hindi inaasahang mga sitwasyon (hallway curbside questions, pharmacy callbacks, nurse small talk) kung saan nakatago ang konteksto ng senaryo hanggang sa magsalita ang AI.
- **Rapid-fire Small Talk**: Maiikling tugon sa biglaang pagbabago ng paksa.
- **15 Native Accent Profiles**: Magsanay sa pag-angkop sa mga pandaigdigang native accent at bilis ng pananalita.
- **Real-time Audio Speed Control (0.5× hanggang 2.5×)**: Pitch-preserving playback speed slider upang bumagay sa mabibilis na native speakers.
- **Ear-only Listening Mode**: Itinatago ang mga subtitle ng AI conversation upang umasa ka lamang sa pakikinig, na may kasamang **[Reveal last line]** na maaari mong gamitin kapag kailangan.
- **Repair Expression Encouragement**: Ang paggamit ng mga repair strategy tulad ng _"Sorry?", "Could you say that again?"_ ay nagbibigay ng **bonus points** sa halip na point deductions.
- **Listening Lab**: Mga pagsasanay na sumusubok sa eksaktong pag-unawa sa detalye (mga numero, drug dosages, pangalan ng pasyente, oras, direksyon, presyo) nang may detalyadong accuracy scoring.

---

## 11. Lecture Teach-back (Feynman Technique)

Kabisaduhin ang mga konsepto ng medikal sa pamamagitan ng pagpapaliwanag nito sa isang AI na gumaganap sa iba't ibang papel. Makakatulong ito sa pagpapalakas ng lohika at pagkatatas nang sabay.

1. **Pumili ng Paksa**: Piliin ang USMLE/PLAB na mga pathology mula sa library, o mag-import ng iyong sariling materyal gamit ang **`🎬 Fetch YT transcript`**.
2. **AI Condensation**: Para sa mahahabang materyales, i-tap ang **`✨ Condense`** para i-compress ang text tungo sa isang nakabalangkas na 500-salitang outline.
3. **Pumili ng Audience Persona**:
   - **Oral Exam Professor**: Nagtatanong ng mahihirap, mapanghamong mga klinikal na _"Why"_ at _"What-if"_ na follow-up na katanungan.
   - **Confused Classmate**: Humihingi ng simpleng paliwanag na walang masyadong mabibigat na jargon.
   - **Friendly Tutor**: Nagbibigay ng suporta at paggabay sa pagbuo ng pangungusap.
4. **Teach**: I-tap ang **Start** at magpaliwanag sa pamamagitan ng mikropono habang ang AI na nakikinig ay sumasagot nang may mga paglilinaw.

---

## 12. Residency Mock Interviews

Sinusubok ang mga makatotohanang panayam para sa pagtatrabaho sa ospital sa ibang bansa at sa US Residency Match:

- **Behavioral**: Paglalarawan ng karanasan gamit ang STAR method (Situation, Task, Action, Result).
- **Clinical**: Paglalahad ng kaso nang pasalita, medikal na etika, at lohika sa pangangasiwa ng emergency.
- **IMG-Specific**: Nakatuon sa mga karaniwang tanong para sa IMG (pag-sponsor sa visa, pagpapaliwanag ng gap year sa CV, natatanging kalakasan bilang isang IMG).
- Isang AI Program Director ang namumuno at nagtatanong ng magagalang ngunit malalalim na mga follow-up na katanungan.

---

## 13. Free English Lounge at Custom Scenarios

### Free English Lounge

- Pag-usapan ang mga kasalukuyang paksang medikal, lagumin ang mga artikulo sa journal, lutasin ang mga hindi pagkakasundo sa trabaho/nars, o magsanay sa pakikipag-usap tuwing coffee break (small talk).
- Kumuha ng mga subtitle mula sa YouTube medical news upang malayang makipagdebate sa AI.

### Custom Scenarios

Gumawa ng mga custom practice scenario na iniangkop sa iyong mga pangangailangan:

- **Scenario Name**: Custom na pagkakakilanlan.
- **Persona / Context**: System prompt na tumutukoy sa papel at sitwasyon ng AI.
- **Eval Template**: Pumili ng mga rubric sa pagsusuri (hal., SPIKES protocol para sa paghahatid ng masamang balita).
- **Custom Eval Criteria**: Magtakda ng mga partikular na pangunahing punto sa pagtatasa para i-check ng AI.

---

## 14. Pagsusuri sa Feedback Report (7 Pangunahing Bahagi)

Pagkatapos makumpleto ang isang session, nagbibigay ang isang 7-bahaging ulat ng multi-dimensional na feedback:

1. **Scores**: Ipinaghahambing ang mga marka ng AI domain (0–10) nang magkatabi sa iyong sariling pagtatasa (self-assessment). Ang puwang na **2.0+ puntos** ay magti-trigger ng dilaw na **Reflection Prompt** card na gagabay sa iyong self-reflection.
2. **Fluency**:
   - **WPM (Words Per Minute)**: Sinusukat ang bilis ng pagsasalita laban sa mga inirerekomendang target (100–130 WPM).
   - **Filler Words**: Sinusukat ang density ng mga filler word (`um`, `uh`, `like`), na naghihikayat sa epektibong paggamit ng pag-pause o paghinto.
3. **Checklist**: Sinusuri ang mga klinikal na layunin, na sumisipi sa mga eksaktong pangungusap sa transcript bilang **Katibayan (Evidence)**.
4. **SOAP Note Comparison**: Inihahambing ang isang awtomatikong nabuong SOAP note mula sa iyong session laban sa isang model reference SOAP note.
5. **Corrections**: Mga pagwawasto na nakabatay sa card para sa mga direktang pagsasalin, mga artikulo/pangmaramihan (articles/plurals), mga hindi natural na ekspresyon, at pagbigkas. Ang pag-tap sa **[Accept]** ay nirerehistro ang item sa iyong personal na SRS error tracker.
6. **Shadowing**: Muling isinusulat ang mga mahihinang pangungusap tungo sa attending-level clinical English para sa listen-and-repeat audio training.
7. **Summary at Share Cards**: Ipinapakita ang pangkalahatang feedback at nagbibigay ng **Share Card** image generator upang ibahagi ang buod ng iyong performance sa mga kaibigang kasabay mong nag-aaral.

---

## 15. Socratic AI Tutor at Always-On 1:1 Voice Coach

### 15-1. Socratic AI Tutor 1:1 Debrief (`[Debrief with AI Tutor]`)

Ang pag-tap sa **[Debrief with AI Tutor]** sa pinakababa ng feedback report ay nagbubukas ng 1:1 chatroom kasama ang isang Socratic AI mentor.

- Nagtatanong ito ng mga gabay na katanungan (guiding questions) kaysa direktang ibigay ang mga sagot, na tumutulong sa iyong matuklasan at iwasto ang mga pagkakamali mo mismo.
- Ang kasaysayan ng debrief chat ay napapanatili sa database kaya maaari kang bumalik at magpatuloy anumang oras.

### 15-2. Always-On 1:1 Speaking Voice Coach (Home FAB)

Ang pag-tap sa **Voice Coach Floating Button (`🎙️ RecordVoiceOver`)** sa kanang ibaba ng Dashboard ay nagbubukas ng isang instant na pakikipag-usap sa speaking coach nang hindi na kinakailangang tapusin muna ang isang buong scenario.

- Pinamumunuan ng AI coach ang mga naka-customize na 1:1 voice conversation batay sa iyong mga naipong SRS weak points.

---

## 16. Spaced Repetition Error Tracker at Mistake Genome

Ang mga pagwawastong tinanggap sa pamamagitan ng **[Accept]** ay awtomatikong pinapamahalaan ng mga spaced repetition algorithm sa iyong error database.

- **Fuzzy Duplicate Detection**: Awtomatikong pinipigilan ang duplicate logging ng mga magkakatulad na pagkakamali.
- **Leech / Stubborn Errors**: Ang mga item na nakaligtaan nang 4+ magkakasunod na beses sa mga review quiz ay nata-tag bilang **Stubborn / Leech** para sa mas nakatutok na pamamahala.
- **Scientific Spaced Repetition Intervals**:
  - Iskedyul ng pag-aaral: **1 araw → 3 araw → 7 araw → 14 araw → 30 araw**. Ang pagpasa sa 3 magkakasunod na review ay nagpo-promote sa item sa pagiging **Mastered**.
  - Ang pagpasa sa mga vocal quiz sa **Practice My Mistakes** ay nag-uusad sa mga item patungo sa Mastery.
- **Mistake Genome Panel**:
  - Ipinapakita ang iyong nangungunang 5 kategorya ng mga mahihinang error (Articles, Plurals, Tense, Prepositions, Register, Direct Translations) bilang isang bar chart sa Dashboard na may malilinaw na tooltips.

---

## 17. Attending Case Presentation Chaining

Sanayin ang mga kasanayan sa oral handoff sa pamamagitan ng paglalahad ng mga kaso sa isang supervisor pagkatapos ng isang Patient Encounter:

1. Kumpletuhin ang isang **Patient Encounter** session.
2. Pumunta sa **History** tab, piliin ang session, at i-tap ang **`📋 Present Case`**.
3. Magbubukas ang AI Attending na may: _"Doctor, please present the case you just saw."_
4. Maghatid ng oral case presentation gamit ang format na SBAR o SOAP, at sagutin ang mga follow-up na katanungan tungkol sa differential diagnosis at mga plano sa paggamot.

---

## 18. Pag-import ng External Conversation History (Import Transcript)

Mag-import ng text ng pag-uusap mula sa ChatGPT, Gemini, o mga clinical note sa app upang makatanggap ng buong feedback:

1. **Android Share Integration**: I-highlight ang text ng pag-uusap sa mga external na app at piliin ang **[Share] → [Bedside English]** upang awtomatikong buksan ang **Import Transcript** screen.
2. **Direct Entry / Paste**: Buksan nang direkta ang `Import Transcript` screen mula sa Preferences o sa main menu at i-paste ang text.
3. **Automated Feedback at SRS**: Bumubuo ng mga domain score, mga SOAP note, at mga correction card na magagamit para tanggapin sa SRS.

---

## 19. Anki Decks at Word Document Exports

- **Anki Card Export (tab-separated `.txt`)**:
  - Kino-convert ang mga tinanggap na pagwawasto sa isang Anki plain-text import file (File → Import sa Anki / AnkiDroid). Bawat kategorya ng pagkakamali ay dinadala bilang isang tag. Available ito pareho sa post-session feedback screen at mula sa **My Mistakes**, na nag-e-export nang sabay-sabay ng buong review list mo.
- **Word Report (`.docx`) Export**:
  - Bumubuo ng mga nakabalangkas na medical report na naglalaman ng mga score, checklist evidence, mga SOAP note, at mga pagwawasto ng pangungusap. Ang opsyon sa Preferences ay nagbibigay-daan sa auto-saving.

---

## 20. In-App Help Wiki

Ang pag-tap sa **`?` Help icon** sa itaas na app bar ay magbubukas sa built-in Help Wiki viewer sa full-screen mode.

- **Multilingual Integration**: Awtomatikong nilo-load ang user guide file na tumutugma sa UI language setting ng app.
- **Table of Contents (TOC) Sidebar**: Nagbibigay-daan sa mabilisang jump navigation sa lahat ng mga seksyon ng gabay.
- **Full-Text Search**: Maglagay ng mga keyword sa search bar upang i-highlight ang mga tugma at mag-navigate gamit ang mga previous/next button.
- **External Hyperlinks**: Ang pag-tap sa mga web link sa gabay ay nagbubukas ng iyong default system browser.

---

## 21. UI Language Settings, API Cost Tracking at Preferences

I-access ang **Settings gear (⚙️)** sa itaas na bar upang maiangkop ang app sa iyong device at budget:

- **Voice at Feedback**:
  - Piliin ang voice at feedback AI models (Demo, Gemini, OpenAI, Claude).
  - **Echo Prevention**: Awtomatikong mino-mute ang mikropono habang nagsasalita ang AI upang maiwasan ang audio feedback loops (mahalaga kapag hindi gumagamit ng headphones).
  - **AI Speaking Pace**: Stepwise speed control (Slow, Normal, Fast, Challenge).
- **API Cost Analytics**: Malinaw na sinusubaybayan ang paggamit ng token at ang tinatayang halaga sa dolyar kada session gamit ang mga chart visualization.
- **Audio**: Real-time microphone level indicator at speaker test tone.
- **API Keys**: Local encrypted storage key manager.
- **Export at Learning**: Mga opsyon sa Docx auto-save, mandatory SOAP note entry, Native Language, at mga setting sa UI Language.
- **Data**: I-toggle ang pronunciation analysis, i-configure ang mga engine, at piliin ang mga TTS shadowing engine.
- **Privacy**: I-toggle ang anonymous usage telemetry.

---

## 22. Mga Madalas Itanong (FAQ) at Scoring Rubric

### Mga Madalas Itanong (FAQ)

**Q: Ang mikropono ay hindi kumukuha ng audio at ang AI ay hindi sumasagot.**

- Tingnan ang Android **Settings → Apps → Bedside English → Permissions → Microphone** at i-set ito sa [Allow]. Kung tinanggihan ang pahintulot, ang app ay lilipat sa **Type instead** mode upang makapagpatuloy ka sa pagsasanay gamit ang keyboard.

**Q: Ang mga advanced na practice mode (Exam, Teach-back, Interview, Lounge, Custom) ay nawawala.**

- Kumpletuhin ang iyong unang practice session at ang lahat ng advanced na mode ay awtomatikong maa-unlock na may isang celebration message. Maaari mo ring palawakin at i-reveal ang lahat ng mga mode mula sa Practice screen.

**Q: Ang mga resulta ng pronunciation analysis ay hindi agad napupunta sa aking SRS review database.**

- Upang maiwasang mabigatan ang mga mag-aaral mula sa mga one-off speech recognition error, ang mga tinanggap na pronunciation item ay pumapasok muna sa **Observed** state. Naipo-promote lamang ang mga ito sa mga aktibong SRS review card kapag ang parehong error pattern ay maulit sa iyong mga susunod na session.

**Q: Maaari ba akong mag-import ng mga external conversation transcript (hal., ChatGPT) para mamarkahan?**

- Oo. Gamitin ang share feature ng Android upang ibahagi ang text sa Bedside English o i-paste ang text sa `Import Transcript` screen upang makatanggap ng buong feedback, mga SOAP note, at correction cards.

---

### 📝 Detalyadong Pamantayan sa Pagmamarka (0–10 Score Range)

| Score      | Rating Level           | Assessment Criteria                                                                                                                                                                      |
| :--------- | :--------------------- | :--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **9 ~ 10** | **Attending / Expert** | Walang kamaliang gramatika at ekspresyon; tumpak na klinikal na bokabularyo at sistematikong medikal na pangangatwiran; natural, at propesyonal na tono.                                 |
| **7 ~ 8**  | **Competent / Pass**   | Mga maliliit na grammatical error ngunit ganap na malinaw na komunikasyon; natutukoy ang mga pangunahing risk factor at nakapagsasagawa ng differential diagnosis nang ligtas.           |
| **5 ~ 6**  | **Developing**         | Madalas na mga error sa istruktura ng gramatika na nangangailangan ng pagsisikap ng nakikinig; hindi sistematikong klinikal na pangangatwiran at bokabularyo.                            |
| **1 ~ 4**  | **Critical / Fail**    | Malalang mga pagkakamaling medikal o nakaligtaang mga risk factor; ang pananalita ay limitado sa mga iisang salita o malimit at mahahabang pag-pause na pumipigil sa normal na diyalogo. |
