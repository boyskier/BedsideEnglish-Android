# Bedside English Benutzerhandbuch

**Zielgruppe:** Internationale Ärztinnen und Ärzte (IMGs - International Medical Graduates), Assistenzärzte, Medizinstudierende und medizinisches Fachpersonal, die sich auf klinisches Englisch, OSCE, OET Speaking, US-Residency-Interviews, Visiten-Fallpräsentationen (Ward Round) und klinische Kommunikation vorbereiten.

Bedside English ist eine exklusiv für Android erhältliche App, die auf konversationeller KI (Google Gemini, OpenAI Realtime, Anthropic Claude) und Echtzeit-Sprachtechnologie basiert. Sie wurde entwickelt, um klinische Kommunikationsfähigkeiten, medizinisches Denken (Clinical Reasoning), Aussprache und Sprachverständlichkeit (Intelligibility) über mehrere Dimensionen hinweg zu trainieren. Dieses Handbuch beschreibt alle Funktionen und die Nutzung der neuesten App-Version, die auf die Benutzer zugeschnitten ist.

---

## 📌 Inhaltsverzeichnis

1. [API-Schlüssel Einrichtung und Konfiguration](#1-api-schlüssel-einrichtung-und-konfiguration)
2. [App-Installation und Berechtigungseinstellungen](#2-app-installation-und-berechtigungseinstellungen)
3. [Erster Start: Onboarding & Anpassung an die L1-Muttersprache](#3-erster-start-onboarding--anpassung-an-die-l1-muttersprache)
4. [Überblick über das UI-Layout (Untere Navigationsleiste 5 Tabs & Hilfe)](#4-überblick-über-das-ui-layout-untere-navigationsleiste-5-tabs--hilfe)
5. [Das Dashboard (Home) meistern](#5-das-dashboard-home-meistern)
6. [Übungs-Hub (Practice Hub) & Stufenweise Funktionsfreischaltung](#6-übungs-hub-practice-hub--stufenweise-funktionsfreischaltung)
7. [Patientenkonsultationen (Patient Encounters) & Live-Anamnese-Tracker](#7-patientenkonsultationen-patient-encounters--live-anamnese-tracker)
8. [Prüfungs- & Diagnosemodus (10-Minuten-Baseline & CEFR-Zuordnung)](#8-prüfungs--diagnosemodus-10-minuten-baseline--cefr-zuordnung)
9. [Aussprache- & Verständlichkeitslabor (Pron Lab)](#9-aussprache--verständlichkeitslabor-pron-lab)
10. [Survival English & Hörverständnis-Labor (Listening Lab)](#10-survival-english--hörverständnis-labor-listening-lab)
11. [Vorlesung Teach-back (Feynman-Methode)](#11-vorlesung-teach-back-feynman-methode)
12. [Residency-Mock-Interviews (Probeinterviews)](#12-residency-mock-interviews-probeinterviews)
13. [Free English Lounge & Benutzerdefinierte Szenarien](#13-free-english-lounge--benutzerdefinierte-szenarien)
14. [Den Feedback-Bericht entschlüsseln (7 Hauptabschnitte)](#14-den-feedback-bericht-entschlüsseln-7-hauptabschnitte)
15. [Sokratischer KI-Tutor & Always-On 1:1 Sprachcoach](#15-sokratischer-ki-tutor--always-on-11-sprachcoach)
16. [Vergessenskurven-Fehler-Tracker (Spaced Repetition) & Mistake Genome](#16-vergessenskurven-fehler-tracker-spaced-repetition--mistake-genome)
17. [Fallpräsentation vor dem Oberarzt (Attending Case Presentation Chaining)](#17-fallpräsentation-vor-dem-oberarzt-attending-case-presentation-chaining)
18. [Externe Gesprächsverläufe importieren (Import Transcript)](#18-externe-gesprächsverläufe-importieren-import-transcript)
19. [Anki-Decks & Word-Dokument-Exporte](#19-anki-decks--word-dokument-exporte)
20. [In-App-Hilfe-Wiki](#20-in-app-hilfe-wiki)
21. [UI-Spracheinstellungen, API-Kostenverfolgung & Einstellungen (Preferences)](#21-ui-spracheinstellungen-api-kostenverfolgung--einstellungen-preferences)
22. [Häufig gestellte Fragen (FAQ) & Bewertungsrubrik (Scoring Rubric)](#22-häufig-gestellte-fragen-faq--bewertungsrubrik-scoring-rubric)

---

## 1. API-Schlüssel Einrichtung und Konfiguration

Bedside English unterstützt flexibel Google Gemini, OpenAI und Anthropic Claude Backends.

### 💡 Empfohlene Einrichtung (Google Gemini Einzel-Schlüssel Modus)

**Die Registrierung eines einzigen Google Gemini API-Schlüssels schaltet alle App-Funktionen frei – von Echtzeit-Sprachgesprächen bis hin zu Feedback-Analysen nach der Sitzung – auf die schnellste und kostengünstigste Weise.**

| Service                | Hauptzweck                                                         | Erforderlich / Optional                                      | Link                                                   |
| :--------------------- | :----------------------------------------------------------------- | :----------------------------------------------------------- | :----------------------------------------------------- |
| **Google (Gemini)**    | Echtzeit-Sprachkonversation (Gemini Live) + Tiefe Feedback-Analyse | **Erforderlich (Einzel-Schlüssel deckt alle Funktionen ab)** | [aistudio.google.com](https://aistudio.google.com)     |
| **OpenAI**             | Echtzeit-Sprache (OpenAI Realtime) + Feedback + Premium TTS        | Optional                                                     | [platform.openai.com](https://platform.openai.com)     |
| **Anthropic (Claude)** | Sitzungs-Feedback-Analyse (auswählbares Backend)                   | Optional (Gemini ist Standard)                               | [console.anthropic.com](https://console.anthropic.com) |

### API-Schlüssel Eingabe und Sicherheit

API-Schlüssel werden direkt in der App eingegeben:

- Konfigurieren Sie diese während des **First-Run Onboarding Wizards** oder über das Menü **Zahnrad-Symbol (⚙️) → Einstellungen (Preferences) → API-Schlüssel (API Keys)** in der oberen Leiste.
- Eingegebene API-Schlüssel werden **sicher gespeichert** in einem geräteinternen, verschlüsselten Speicher (`EncryptedSharedPreferences`) und niemals an externe Server gesendet.
- Jedes Schlüsselfeld verfügt über ein Augen-Symbol auf der rechten Seite, um die Sichtbarkeit des Schlüssels umzuschalten.

### 🎈 Demo-Modus (Vollständig kostenloser Test)

Wenn Sie die App erleben möchten, ohne einen API-Schlüssel zu registrieren oder Mikrofonberechtigungen zu erteilen, wählen Sie den **Demo**-Modus auf dem Onboarding-Bildschirm oder unter **Einstellungen (Preferences)**.
Skriptbasierte Mock-Gespräche (Probe-Gespräche) und Feedback-Daten werden geladen, sodass Sie die vollständige Benutzeroberfläche, den Fehler-Tracker und die Überprüfungsfunktionen **kostenlos** erkunden können, ohne Token zu verbrauchen. Nach Abschluss einer Demo-Sitzung ermöglicht Ihnen eine Eingabeaufforderung, einen API-Schlüssel einzugeben oder jederzeit mit der nächsten Demo-Patientenübung fortzufahren.

---

## 2. App-Installation und Berechtigungseinstellungen

Bedside English läuft auf Smartphones und Tablets mit Android 8.0 (API-Level 26) oder höher.

### App-Installation

- Starten Sie die bereitgestellte Installationsdatei (`.apk`) auf Ihrem Android-Gerät und folgen Sie den Anweisungen auf dem Bildschirm zur Installation.

### Mikrofonberechtigung & Textmodus (Type-Instead Mode)

- Wenn Sie zum ersten Mal einen Live-Sprachübungsmodus starten, fordert das Android-Betriebssystem die Mikrofon-Zugriffsberechtigung an. Tippen Sie auf **[Zulassen]**, damit die Spracherkennung ordnungsgemäß funktioniert.
- Wenn Sie sich in einer Umgebung befinden, in der das Sprechen schwierig ist, oder wenn die Berechtigung verweigert wird, stürzt die App nicht ab. Sie schaltet automatisch in den **Type instead** (Stattdessen tippen) Modus, in dem Sie Gespräche über die Tastatureingabe üben können.

### Benachrichtigungsberechtigung & Audio-Preflight (Vorflug-Check)

- Die Benachrichtigungsberechtigung wird angefordert, damit Sie Benachrichtigungen erhalten können, wenn die Hintergrund-Feedback-Analyse abgeschlossen ist.
- Unmittelbar vor Beginn Ihrer ersten Live-Sprachsitzung wird ein **Audio-Preflight**-Check-Modal angezeigt, das zur Nutzung von Kopfhörern anleitet und die Mikrofoneingangspegel testet, um Lautsprecher-Rückkopplungsschleifen (Heulen) zu verhindern.

---

## 3. Erster Start: Onboarding & Anpassung an die L1-Muttersprache

Beim ersten Start der App wird ein 4-stufiger Einrichtungsassistent ausgeführt, um eine maßgeschneiderte Lernumgebung zu erstellen:

1. **Willkommensbildschirm (Welcome Screen)**: Stellt die Kernfunktionen vor und bietet eine Schaltfläche **Demo-Modus ausprobieren (Try Demo Mode)**, um die App ohne API-Schlüssel zu erkunden.
2. **Auswahl der UI-Sprache (UI Language Picker)**: Wählen Sie Ihre bevorzugte Sprache für die Benutzeroberfläche (8 unterstützte Sprachen: Englisch, Koreanisch, Spanisch, Chinesisch, Arabisch, Hindi, Portugiesisch, Tagalog).
3. **API-Schlüssel Einrichtung (API Keys Setup)**: Registrieren Sie Ihre Google Gemini oder andere KI-API-Schlüssel.
4. **Muttersprache & Datenschutz (Native Language & Privacy)**: Wählen Sie Ihre Erstsprache (z. B. **Koreanisch**, Chinesisch, Spanisch, Arabisch, Hindi, Tagalog, Portugiesisch). Dies aktiviert eine Präzisions-Grammatik- und Ausspracheanalyse, die auf die spezifischen Interferenzmuster Ihrer Muttersprache zugeschnitten ist.

### 🌐 Highlights der L1-Muttersprachenanpassung (z. B. koreanische L1-Lernende)

- **Grammatik- & Phrasierungskorrekturen**:
  - Fehlende Artikel (Weglassen von _a/an/the_ vor Substantiven)
  - Fehlendes Plural-_-s_ (_two patient_ → _two patients_)
  - Zeitformfehler (Verwendung des Präsens bei der Besprechung der medizinischen Vorgeschichte)
  - Missbrauch von Präpositionen (_in hospital_, Weglassen von Präpositionen bei _explain to patient_)
  - Wörtliche direkte Übersetzungen / Konglish (_skin scale_, unbeholfene direkte Übersetzung von _side effect_)
- **Aussprache- & Verständlichkeitskorrekturen**:
  - _r / l_ Minimalpaar-Unterscheidung (_liver_ vs _river_)
  - _f / p_ Unterscheidung (_fever_ vs _peter_)
  - _th_ dentaler Frikativ (Reibelaut) Aussprache (_think_ vs _tink_)
  - Weggelassene Endkonsonanten und unnötige Vokaleinfügung (_cardiac_ → _cardi-ack-eu_)
  - Fehlplatzierung der medizinischen Wortbetonung (_angina_, _arrhythmia_)

### 💡 Interaktive Tour beim ersten Start (First-Run Tour)

Nach Abschluss des Onboardings und beim ersten Betreten des Dashboards führt Sie ein interaktives Tutorial automatisch durch die Positionen und Funktionen der wichtigsten Schaltflächen (Dashboard, Practice Hub, Pron Lab, SRS Reviews, History, Help, 1:1 Voice Coach).

---

## 4. Überblick über das UI-Layout (Untere Navigationsleiste 5 Tabs & Hilfe)

### Untere Navigationsleiste (5 Tabs)

Die Hauptnavigation besteht aus 5 unteren Tabs:

```
┌───────────┬──────────────┬───────────────────────┬───────────────┬─────────────┐
│  🏠 Home  │  ▶ Practice  │  🎙️ Pronunciation Lab │  ⚠️ SRS Reviews│  🕘 History │
└───────────┴──────────────┴───────────────────────┴───────────────┴─────────────┘
```

1. **Home (Dashboard)**: Übungs-Serie (`🔥`), 5-minütige klinische Mission, Leistungs-Trenddiagramme, Mistake Genome, Roadmap und **Always-on 1:1 Voice Coach** (Immer aktiver 1:1 Sprachcoach).
2. **Practice (Übungs-Hub)**: Zentraler Hub für alle Echtzeit-Sprechmodi: Patient Encounters (Patientenkonsultationen), Exam & Diagnostic (Prüfungs- & Diagnosemodus), Survival English, Teach-back, Interviews, Lounge und Custom Scenarios (Benutzerdefinierte Szenarien).
3. **Pronunciation Lab (Pron Lab - Aussprache-Labor)**: Dedizierter Trainings-Tab für Sprachverständlichkeit und Aussprachekorrektur (Filtern von Fehlermustern, Verwaltung des Beobachtungsstatus).
4. **SRS Reviews (Schwächenüberprüfung)**: Mündliche Überprüfungs-Quiz basierend auf Spaced-Repetition-Algorithmen (verteilte Wiederholung) für akzeptierte Korrektursätze.
5. **History (Sitzungsverlauf)**: Zeigen Sie Ergebnisse und Feedback aus vergangenen Sitzungen an, verfolgen Sie Token-Kosten, exportieren Sie zu Anki/Word und lösen Sie **Attending Case Presentation (Present Case - Fallpräsentation vor dem Oberarzt)** aus.

### Obere App-Leiste (Top App Bar)

- **Bedside English Logo**: Haupttitel.
- **Hilfe-Wiki (`?` Icon)**: Das Tippen auf das `?`-Symbol öffnet dieses Benutzerhandbuch in einem Vollbild-Viewer mit Inhaltsverzeichnis-Navigation und Volltext-Schlüsselwortsuche.
- **Einstellungen-Zahnrad (⚙️ Preferences)**: API-Schlüssel, Sprach-Backends, Sprechgeschwindigkeit, Echoprävention, Sprache und Datenverwaltung.

---

## 5. Das Dashboard (Home) meistern

Das Haupt-Dashboard visualisiert Ihr Wachstum in klinischen englischen Kommunikationsfähigkeiten über mehrere Dimensionen hinweg:

- **Practice Streak (Übungs-Serie)**: Zeigt aufeinanderfolgende aktive Übungstage mit einem Flammen-Symbol (`🔥`) an, um tägliche Lerngewohnheiten aufzubauen.
- **Kern-Metrik-Karten (Core Metric Cards)**:
  - **Sessions done (Abgeschlossene Sitzungen)**: Gesamtzahl der vollständig abgeschlossenen und analysierten Sitzungen.
  - **Errors tracked (Verfolgte Fehler)**: Bestätigte Korrekturen, die in Ihrer persönlichen Schwächen-Datenbank registriert sind.
  - **Mastered (Gemeistert)**: Durch wiederholte Überprüfungs-Quiz gelöste und abgestufte Fehler.
  - **Due now (Jetzt fällig)**: Anzahl der SRS-Überprüfungskarten (Spaced Repetition), die heute für eine mündliche Überprüfung geplant sind.
  - **Stubborn (Hartnäckig)**: "Leech"-Fehler (blutsaugende Fehler), die 4+ Mal in Folge aufgetreten sind und konzentrierte Aufmerksamkeit erfordern.
- **Today's 5-Minute Clinical Mission (Die heutige 5-minütige klinische Mission)**: Empfiehlt automatisch einen optimalen 5-minütigen Übungskurs, der auf fällige Überprüfungselemente, erforderliche Diagnostik oder Ihre schwächste Fähigkeitsdomäne abzielt.
- **OET Layman Vocabulary Coverage (OET-Laienwortschatz-Abdeckung)**:
  - Verfolgt, wie effektiv Sie komplexe medizinische Fachausdrücke (Jargon, z. B. _syncope_) durch einfache, patientenfreundliche Begriffe (z. B. _fainting_) ersetzen.
  - Verwendete Begriffe erscheinen unter **Recently Unlocked (Kürzlich freigeschaltet)**, während ungenutzte Ausdrücke unter **Next Goals (Locked) (Nächste Ziele (Gesperrt))** eingereiht werden.
- **Mistake Genome Panel (Fehler-Genom-Panel)**: Analysiert Ihre häufigsten Fehlerkategorien (Artikel, Plurale, Zeitformen, Präpositionen, Register, direkte Übersetzungen) und zeigt Ihre Top-5-Schwachstellen als Balkendiagramm an.
- **Growth Trends & L1 Interference Chart (Wachstumstrends & L1-Interferenz-Diagramm)**:
  - Stellt die Punkte-Trends über 5 Domänen (Grammatik, Genauigkeit, Logisches Denken, Professionalität, Sprachfluss) im Verlauf Ihrer letzten 20 Sitzungen grafisch dar.
  - Visualisiert wiederkehrende grammatikalische Fehlermuster.
- **Personalized Roadmap (Personalisierte Roadmap)**: Analysiert schwache Metriken und den Fehlerverlauf, um 4 priorisierte Fokus-Fähigkeitskarten zu präsentieren.
- **Always-On 1:1 Voice Coach Button (`🎙️ RecordVoiceOver`) (Immer aktiver 1:1-Sprachcoach-Button)**:
  - Befindet sich unten rechts im Dashboard. Tippen Sie darauf, um sofort einen 1:1-Sprachdialog mit einem KI-Tutor basierend auf Ihrem persönlichen Schwächenprofil zu öffnen, ohne ein vollständiges Sitzungsszenario zu starten.

---

## 6. Übungs-Hub (Practice Hub) & Stufenweise Funktionsfreischaltung

### Stufenweise Funktionsfreischaltung (Progressive Feature Unlocking)

Um zu verhindern, dass sich Erstbenutzer überfordert fühlen, beginnen diese mit einem übersichtlichen Intro-Bildschirm, der die Kernmodi anzeigt (**Dashboard**, **Patient Encounters**, **Survival English**, **History**).

- **Das Abschließen Ihrer ersten Übungssitzung schaltet automatisch** erweiterte Modi (Exam, Teach-back, Interview, Lounge, Custom) mit einer Feier-Nachricht frei.
- Sie können auch tippen, um den Bereich zu erweitern und alle Modi auf dem Übungsbildschirm (Practice Screen) sofort anzuzeigen.

### Hauptmoduskategorien im Übungs-Hub (Practice Hub Main Mode Categories)

1. **Patient Encounters (Patientenkonsultationen)**: Anamneseerhebung, Follow-up, Foundations-Anfängermodus, Skill Drills (Fähigkeitstrainings), Practice My Mistakes (Meine Fehler üben).
2. **Exam & Diagnostic (Prüfungs- & Diagnosemodus)**: 10-Minuten-Baseline-Diagnostik, OSCE-, OET-, Residency- und Ward Round (Visite)-Probeexams.
3. **Survival English & Listening Lab (Hörverständnis-Labor)**: Unerwartete Situationen im Krankenhaus, schneller Smalltalk (Rapid-fire small talk), 15 muttersprachliche Akzentprofile, Listening Lab-Detailübungen.
4. **Lecture Teach-back (Vorlesungs-Teach-back)**: Feynman-Technik-Training basierend auf YouTube-/Textzusammenfassungen.
5. **Residency Interviews (Residency-Mock-Interviews)**: Verhaltensbasierte (Behavioral), klinische (Clinical) und IMG-spezifische Probeinterviews.
6. **Free English Lounge**: Medizinische Debatten, Diskussionen über Nachrichtenuntertitel, Konfliktlösung am Arbeitsplatz.
7. **Custom Scenarios (Benutzerdefinierte Szenarien)**: Erstellen Sie benutzerdefinierte KI-Prompts und Bewertungsrubriken.

---

## 7. Patientenkonsultationen (Patient Encounters) & Live-Anamnese-Tracker

Simuliert die Anamneseerhebung am Krankenbett und die Patientenberatung – den Kern der klinischen Kommunikation.

### 7-1. Operationelle Sub-Modi

- **Foundations Mode (Grundlagen-Modus)**: Entfernt die Belastung durch medizinisches Denken für frühe Lernende und konzentriert sich strikt auf **Grammatik, klinischen Wortschatz, Beziehungsaufbau (Rapport) und Sprachfluss**.
- **Skill Drills (Fähigkeitstrainings)**: Gezielte Mikrokompetenz-Übungen (NURSE-Empathietechnik, Erklärungen in einfacher Sprache, Nachtschichtübergabe, **Sequentielles medizinisches Dolmetschen aus der Muttersprache → Englisch**).
- **Practice My Mistakes (Meine Fehler üben)**: Erstellt sofort ein mündliches Dialog-Quiz aus ausstehenden Fehlern in Ihrer Datenbank.
- **Daily Mission (Tägliche Mission)**: Eine adaptive 5-minütige tägliche Herausforderung, die auf Ihre aktuellen Kompetenzlücken abzielt.

### 7-2. Live-Anamnese-Tracker (Live History Coverage Tracker)

Ein einklappbares Echtzeit-Panel, das Elemente der Anamneseerhebung abhakt, während Sie sprechen:

- Verfolgt Elemente automatisch basierend auf dem Kontext des KI-Gesprächs.
- Überwacht Beginn/Dauer (onset/duration), Schmerzcharakter, Ausstrahlung (radiation), erschwerende/lindernde Faktoren (aggravating/relieving factors), Begleitsymptome, ICE (Ideen, Sorgen, Erwartungen - Ideas, Concerns, Expectations), Vorgeschichte, Medikamente, Allergien, Alkohol/Rauchen, Familienanamnese usw.
- Negative Bestätigungsfragen wie _"You don't smoke, do you?"_ (Sie rauchen nicht, oder?) werden korrekt erkannt und verfolgt.

### 7-3. Kontextbezogene Fortsetzungshilfe (`💡 Help me continue`)

Wenn Sie während der Sitzung nicht weiterwissen oder Ihnen die Fragen ausgehen, tippen Sie unten auf dem Bildschirm auf **💡 Help me continue** (Hilf mir fortzufahren).

- Analysiert die letzte Antwort des Patienten, um das nächste logische Fragenziel zusammen mit einem **gebrauchsfertigen englischen Beispielsatz** vorzuschlagen.
- Der obere Interview-Phasen-Tracker zeigt den Fortschritt anhand von Statussymbolen an:
  - `✓`: Ausreichende Beweise erkannt
  - `•`: Teilweise Erwähnung erkannt
  - `?`: Spätere Phase ohne vorherige Phasenüberprüfung erreicht

---

## 8. Prüfungs- & Diagnosemodus (10-Minuten-Baseline & CEFR-Zuordnung)

Misst die Kommunikationsfähigkeit unter zeitgesteuerten, immersiven Prüfungsbedingungen:

### 10-Minuten Baseline Clinical English Diagnostic

- Beginnt mit einer Vorstellung durch den Prüfer, gefolgt von 4 kurzen Aufgaben (Erklären einer Diagnose, Umgang mit Follow-up-Fragen, Halten einer 45-sekündigen SBAR-Übergabe, Beantworten einer Residency-Interview-Frage).
- Ordnet Ihre Leistung automatisch den internationalen **CEFR-Stufen** zu:
  - **Punktzahl >= 8.5**: **C1** (Fließende, sichere klinische Kommunikation auf Oberarztniveau)
  - **Punktzahl >= 7.2**: **B2+** (Kompetent für klinische Famulaturen und Krankenhauspraxis)
  - **Punktzahl >= 6.0**: **B1-B2** (Grundlegende Kommunikationsfähigkeit; strukturiertes Lernen empfohlen)
  - **Punktzahl < 6.0**: **A2-B1** (Grundlegendes klinisches Kommunikationstraining erforderlich)

### Mock Exam Scenarios (Probeexamens-Szenarien)

- **OSCE**: Anamneseerhebung bei Herrn Hayes wegen Brustschmerzen (Messung von ICE, Erkennung von Warnsignalen (Red Flags), Empathie).
- **OET Speaking**: Rollenspiel zur Beratung von Bluthochdruckpatienten.
- **Residency**: US Internal Medicine Program Director Mock-Interview (Probeinterview mit dem Programmdirektor für Innere Medizin in den USA).
- **Ward Round (Visite)**: 5-minütige Fallpräsentation einer ambulant erworbenen Lungenentzündung (community-acquired pneumonia) und Umgang mit mündlichen Fragen.

### Score Reliability Badge (Punkte-Zuverlässigkeits-Abzeichen)

Gibt die Zuverlässigkeit der KI-Bewertung als **High (Hoch)**, **Medium (Mittel)** oder **Low (Niedrig)** an:

- **High**: Wortzahl des Lernenden >= 180 Wörter & Checklisten-Beweisrate >= 75%.
- **Medium**: Wortzahl des Lernenden >= 80 Wörter & Checklisten-Beweisrate >= 50%.
- **Low**: Wortzahl < 80 Wörter (Short Transcript Flag - Kurzes Transkript) oder Beweisrate < 50%.

---

## 9. Aussprache- & Verständlichkeitslabor (Pron Lab)

Befindet sich im 3. dedizierten unteren Tab (`🎙️ Pron Lab`); dies ist Ihr spezialisiertes Trainingszentrum für Sprachverständlichkeit.

### 💡 Verständlichkeitszentriertes Coaching (Intelligibility-Centered Coaching)

Das Ziel ist keine Imitation eines muttersprachlichen Akzents, sondern: **"Können internationale Kollegen und Patienten meine Rede ohne Missverständnisse klar verstehen?"**

- Die Audioanalyse bietet ein punktgenaues Coaching nur für Ausspracheelemente, die beim Zuhörer zu Missverständnissen führen.

### Fehlerkategorien & Filter-Chips (Error Pattern Categories & Filter Chips)

In Ihren Sitzungen erkannte Aussprachefehler werden nach Kategorien in Filter-Chips organisiert:

- `r · l`: _liver / river_, _clinical / critical_
- `f · p`: _fever / peter_, _palpation / falcation_
- `th`: _think / tink_, _throat / troat_
- `final`: Weggelassene Endkonsonanten (_chest / ches_)
- `cluster`: Verarbeitung von Konsonantenclustern & unnötige Vokaleinfügung (_cardiac_ → _cardi-ack-eu_)
- `stress`: Fehlplatzierung der medizinischen Wortbetonung (_angina_, _arrhythmia_)
- `vowel`: Verwechslung von kurzen und langen Vokalen (_ship / sheep_, _fit / feet_)

### Regel für die Hochstufung des beobachteten Status (Observed State Promotion Rule)

Wenn eine akzeptierte Aussprachekarte zum ersten Mal protokolliert wird, geht sie in einen **Beobachtet (Observed)**-Status über, anstatt sofort zu einer täglichen Hausaufgabenkarte zu werden. Sie wird nur dann zu einer aktiven SRS-Überprüfungskarte hochgestuft, wenn dasselbe Fehlermuster in einer separaten Sitzung erneut auftritt. Dadurch wird sichergestellt, dass einmalige Spracherkennungsfehler (Glitches) keine übermäßigen Hausaufgaben verursachen.

---

## 10. Survival English & Hörverständnis-Labor (Listening Lab)

Bereitet IMGs auf reale, nicht-klinische Interaktionen im Krankenhaus außerhalb des Untersuchungsraums vor.

- **Random / Surprise Mode (Zufalls- / Überraschungsmodus)**: Spontaner Umgang mit unerwarteten Situationen (Fragen im Flur, Rückrufe aus der Apotheke, Smalltalk mit Pflegekräften), wobei der Szenariokontext verborgen bleibt, bis die KI spricht.
- **Rapid-fire Small Talk (Schneller Smalltalk)**: Schnelle Reaktionen auf plötzliche Themenwechsel.
- **15 Native Accent Profiles (15 muttersprachliche Akzentprofile)**: Üben Sie die Anpassung an internationale muttersprachliche Akzente und Sprachrhythmen.
- **Real-time Audio Speed Control (Echtzeit-Audio-Geschwindigkeitsregelung 0,5× bis 2,5×)**: Tonhöhenerhaltender Schieberegler für die Wiedergabegeschwindigkeit zur Anpassung an schnelle Muttersprachler.
- **Ear-only Listening Mode (Nur-Ohr-Hör-Modus)**: Verbirgt KI-Gesprächsuntertitel, sodass Sie sich rein auf das Zuhören verlassen können, wobei **[Reveal last line] (Letzte Zeile anzeigen)** bei Bedarf verfügbar ist.
- **Repair Expression Encouragement (Ermutigung zu Reparaturausdrücken)**: Die Verwendung von Reparaturstrategien wie _"Sorry?", "Could you say that again?"_ (Entschuldigung?, Könnten Sie das noch einmal sagen?) gewährt **Bonuspunkte** anstelle von Punktabzügen.
- **Listening Lab (Hörverständnis-Labor)**: Übungen, die das exakte Detailverständnis (Zahlen, Medikamentendosierungen, Patientennamen, Zeiten, Wegbeschreibungen, Preise) mit detaillierter Genauigkeitsbewertung testen.

---

## 11. Vorlesung Teach-back (Feynman-Methode)

Nutzt die Feynman-Methode – Konzepte laut zu erklären, als würde man sie jemand anderem beibringen –, um medizinisches Wissen zu festigen.

1. **Vorlesungsmaterial vorbereiten (Prepare Lecture Material)**: Fügen Sie Zusammenfassungsnotizen ein oder geben Sie eine YouTube-URL einer medizinischen Vorlesung ein und tippen Sie auf **`🎬 Fetch YT transcript`** (YT-Transkript abrufen).
2. **KI-Kompression (AI Condensation)**: Tippen Sie bei langen Materialien auf **`✨ Condense`** (Komprimieren), um den Text in eine strukturierte 500-Wörter-Gliederung zu komprimieren.
3. **Zuhörer-Persona auswählen (Select Audience Persona)**:
   - **Oral Exam Professor (Mündlicher Prüfungsprofessor)**: Stellt scharfe, herausfordernde klinische "Warum"- und "Was-wäre-wenn"-Folgefragen.
   - **Confused Classmate (Verwirrter Kommilitone)**: Bittet um Erklärungen in einfacher Sprache ohne schweren Jargon.
   - **Friendly Tutor (Freundlicher Tutor)**: Bietet unterstützende Ermutigung und Anleitung zur Phrasierung.
4. **Lehren (Teach)**: Tippen Sie auf **Start** und erklären Sie über das Mikrofon, während der KI-Zuhörer mit Verständnisfragen reagiert.

---

## 12. Residency-Mock-Interviews (Probeinterviews)

Simuliert realistische Interviews für die Beschäftigung in Krankenhäusern in den USA und das US Residency Match:

- **Behavioral (Verhaltensbasiert)**: Erfahrungsbeschreibungen nach der STAR-Methode (Situation, Task, Action, Result - Situation, Aufgabe, Aktion, Ergebnis).
- **Clinical (Klinisch)**: Mündliche Fallpräsentation, medizinische Ethik und Logik des Notfallmanagements.
- **IMG-Specific (IMG-spezifisch)**: Konzentriert sich auf häufige IMG-Fragen (Visumsponsoring, Erklärungen für Lücken im Lebenslauf (Gap Year), einzigartige Stärken als IMG).
- Ein KI-Programmdirektor leitet höfliche, aber bohrende Folgefragen.

---

## 13. Free English Lounge & Benutzerdefinierte Szenarien

### Free English Lounge

- Diskutieren Sie aktuelle medizinische Themen, fassen Sie Zeitschriftenartikel (Journal Articles) zusammen, lösen Sie Konflikte am Arbeitsplatz/mit Pflegekräften oder üben Sie Smalltalk in der Kaffeepause.
- Rufen Sie YouTube-Untertitel zu medizinischen Nachrichten ab, um frei mit der KI zu debattieren.

### Benutzerdefinierte Szenarien (Custom Scenarios)

Erstellen Sie benutzerdefinierte Übungsszenarien, die auf Ihre Bedürfnisse zugeschnitten sind:

- **Scenario Name (Szenarioname)**: Benutzerdefinierte Kennung.
- **Persona / Context (Persona / Kontext)**: System-Prompt, der die Rolle und Situation der KI definiert.
- **Eval Template (Bewertungsvorlage)**: Wählen Sie Bewertungsrubriken (z. B. SPIKES-Protokoll zur Übermittlung schlechter Nachrichten).
- **Custom Eval Criteria (Benutzerdefinierte Bewertungskriterien)**: Legen Sie spezifische Schlüsselbewertungspunkte fest, die die KI überprüfen soll.

---

## 14. Den Feedback-Bericht entschlüsseln (7 Hauptabschnitte)

Nach Abschluss einer Sitzung bietet ein 7-teiliger Bericht multidimensionales Feedback:

1. **Scores (Punkte)**: Vergleicht die KI-Domänenpunkte (0–10) direkt mit Ihrer Selbsteinschätzung. Eine Lücke von **2.0+ Punkten** löst eine gelbe **Reflection Prompt**-Karte (Reflexionsaufforderung) aus, um die Selbstreflexion zu leiten.
2. **Fluency (Sprachfluss)**:
   - **WPM (Words Per Minute - Wörter pro Minute)**: Misst die Sprechgeschwindigkeit im Vergleich zu den empfohlenen Zielen (100–130 WPM).
   - **Filler Words (Füllwörter)**: Misst die Dichte der Füllwörter (`um`, `uh`, `like`), um eine effektive Nutzung von Pausen zu fördern.
3. **Checklist (Checkliste)**: Bewertet klinische Ziele und zitiert genaue Transkriptsätze als **Evidence (Beweis)**.
4. **SOAP Note Comparison (SOAP-Notiz-Vergleich)**: Vergleicht eine automatisch aus Ihrer Sitzung generierte SOAP-Notiz mit einer Modellreferenz-SOAP-Notiz.
5. **Corrections (Korrekturen)**: Kartenbasierte Korrekturen für direkte Übersetzungen, Artikel/Plurale, unnatürliche Ausdrücke und Aussprache. Das Tippen auf **[Accept] (Akzeptieren)** registriert das Element in Ihrem persönlichen SRS-Fehler-Tracker.
6. **Shadowing**: Schreibt schwache Sätze in klinisches Englisch auf Oberarztniveau für das Hören-und-Wiederholen-Audiotraining um.
7. **Summary & Share Cards (Zusammenfassung & Teilen-Karten)**: Zeigt allgemeines Feedback an und bietet einen **Share Card**-Bildgenerator (Teilen-Karten-Generator), um Leistungszusammenfassungen mit Studienkollegen zu teilen.

---

## 15. Sokratischer KI-Tutor & Always-On 1:1 Sprachcoach

### 15-1. Sokratischer KI-Tutor 1:1 Nachbesprechung (`[Debrief with AI Tutor]`)

Das Tippen auf **[Debrief with AI Tutor]** unten im Feedback-Bericht öffnet einen 1:1-Chatroom mit einem sokratischen KI-Mentor.

- Stellt Leitfragen, anstatt Antworten direkt herauszugeben, und hilft Ihnen so, Fehler selbst zu entdecken und zu korrigieren.
- Der Nachbesprechungs-Chatverlauf bleibt in der Datenbank erhalten, sodass Sie jederzeit zurückkehren und fortfahren können.

### 15-2. Always-On 1:1 Speaking Voice Coach (Home FAB) (Immer aktiver 1:1-Sprachcoach)

Das Tippen auf den **Voice Coach Floating Button (`🎙️ RecordVoiceOver`)** (Schwebende Sprachcoach-Schaltfläche) unten rechts im Dashboard öffnet sofort einen Sprachcoach-Dialog, ohne dass zuerst ein vollständiges Szenario abgeschlossen werden muss.

- Der KI-Coach führt maßgeschneiderte 1:1-Sprachgespräche basierend auf Ihren angesammelten SRS-Schwachstellen.

---

## 16. Vergessenskurven-Fehler-Tracker (Spaced Repetition) & Mistake Genome

Über **[Accept]** akzeptierte Korrekturen werden automatisch durch Spaced-Repetition-Algorithmen in Ihrer Fehlerdatenbank verwaltet.

- **Fuzzy Duplicate Detection (Fuzzy-Duplikaterkennung)**: Verhindert automatisch das doppelte Protokollieren ähnlicher Fehler.
- **Leech / Stubborn Errors (Hartnäckige Fehler)**: Elemente, die in Überprüfungs-Quiz 4+ Mal hintereinander falsch beantwortet wurden, werden als **Stubborn / Leech** für ein gezieltes Management markiert.
- **Scientific Spaced Repetition Intervals (Wissenschaftliche Intervalle der verteilten Wiederholung)**:
  - Überprüfungsplan: **1 Tag → 3 Tage → 7 Tage → 14 Tage → 30 Tage**. Das Bestehen von 3 aufeinanderfolgenden Überprüfungen stuft das Element auf **Mastered (Gemeistert)** ab.
  - Das Lösen mündlicher Quiz in **Practice My Mistakes (Meine Fehler üben)** bringt die Elemente in Richtung Mastery (Meisterschaft) voran.
- **Mistake Genome Panel (Fehler-Genom-Panel)**:
  - Zeigt Ihre Top 5 der schwachen Fehlerkategorien (Artikel, Plurale, Zeitformen, Präpositionen, Register, direkte Übersetzungen) als Dashboard-Balkendiagramm mit Tooltips in einfacher Sprache an.

---

## 17. Fallpräsentation vor dem Oberarzt (Attending Case Presentation Chaining)

Trainieren Sie mündliche Übergabefähigkeiten (Handoff Skills), indem Sie einem Vorgesetzten (Supervisor) im Anschluss an eine Patientenkonsultation Fälle präsentieren:

1. Schließen Sie eine **Patient Encounter (Patientenkonsultation)**-Sitzung ab.
2. Gehen Sie zum Tab **History (Verlauf)**, wählen Sie die Sitzung aus und tippen Sie auf **`📋 Present Case` (Fall präsentieren)**.
3. Der KI-Oberarzt (Attending) eröffnet mit: _"Doctor, please present the case you just saw."_ (Doktor, bitte präsentieren Sie den Fall, den Sie gerade gesehen haben.)
4. Liefern Sie eine mündliche Fallpräsentation im SBAR- oder SOAP-Format und beantworten Sie Folgefragen zur Differentialdiagnose und zu Behandlungsplänen.

---

## 18. Externe Gesprächsverläufe importieren (Import Transcript)

Importieren Sie Gesprächsteexte aus ChatGPT, Gemini oder klinischen Notizen in die App, um vollständiges Feedback zu erhalten:

1. **Android Share Integration (Android-Teilen-Integration)**: Markieren Sie Gesprächsteexte in externen Apps und wählen Sie **[Share] (Teilen) → [Bedside English]**, um automatisch den Bildschirm **Import Transcript (Transkript importieren)** zu öffnen.
2. **Direct Entry / Paste (Direkteingabe / Einfügen)**: Öffnen Sie den Bildschirm `Import Transcript` direkt aus den Einstellungen (Preferences) oder dem Hauptmenü und fügen Sie Text ein.
3. **Automated Feedback & SRS (Automatisiertes Feedback & SRS)**: Generiert Domänenpunkte, SOAP-Notizen und Korrekturkarten, die für die SRS-Akzeptanz verfügbar sind.

---

## 19. Anki-Decks & Word-Dokument-Exporte

- **Anki Card Export (tab-separated `.txt`) (Anki-Karten-Export (tabulatorgetrennt))**:
  - Konvertiert akzeptierte Korrekturen in eine Anki-Klartext-Importdatei (Datei → Importieren in Anki / AnkiDroid). Die Kategorie jedes Fehlers wird als Tag (Schlagwort) übernommen. Verfügbar sowohl auf dem Feedback-Bildschirm nach der Sitzung als auch unter **My Mistakes (Meine Fehler)**, wodurch Ihre gesamte Überprüfungsliste auf einmal exportiert wird.
- **Word Report (`.docx`) Export (Word-Berichts-Export)**:
  - Generiert strukturierte medizinische Berichte, die Ergebnisse, Checklisten-Beweise, SOAP-Notizen und Satzkorrekturen enthalten. Die Einstellungsoption aktiviert die automatische Speicherung.

---

## 20. In-App-Hilfe-Wiki

Das Tippen auf das **`?` Hilfe-Symbol** in der oberen App-Leiste öffnet den integrierten Hilfe-Wiki-Viewer im Vollbildmodus.

- **Multilingual Integration (Mehrsprachige Integration)**: Lädt automatisch die Benutzerhandbuchdatei, die der UI-Spracheinstellung der App entspricht.
- **Table of Contents (TOC) Sidebar (Inhaltsverzeichnis-Seitenleiste)**: Ermöglicht eine schnelle Sprungnavigation durch alle Abschnitte des Handbuchs.
- **Full-Text Search (Volltextsuche)**: Geben Sie Schlüsselwörter in die Suchleiste ein, um Übereinstimmungen hervorzuheben und mit den Zurück/Weiter-Tasten zu navigieren.
- **External Hyperlinks (Externe Hyperlinks)**: Das Tippen auf Weblinks im Handbuch öffnet Ihren Standard-Systembrowser.

---

## 21. UI-Spracheinstellungen, API-Kostenverfolgung & Einstellungen (Preferences)

Greifen Sie auf das **Zahnrad-Symbol (⚙️)** in der oberen Leiste zu, um die App an Ihr Gerät und Ihr Budget anzupassen:

- **Voice & Feedback (Stimme & Feedback)**:
  - Wählen Sie KI-Modelle für Stimme und Feedback aus (Demo, Gemini, OpenAI, Claude).
  - **Echo Prevention (Echoprävention)**: Schaltet das Mikrofon automatisch stumm, während die KI spricht, um Audio-Feedback-Schleifen zu verhindern (unerlässlich, wenn keine Kopfhörer verwendet werden).
  - **AI Speaking Pace (KI-Sprechgeschwindigkeit)**: Stufenweise Geschwindigkeitsregelung (Slow - Langsam, Normal, Fast - Schnell, Challenge - Herausforderung).
- **API Cost Analytics (API-Kostenanalyse)**: Verfolgt transparent die Token-Nutzung und die geschätzten Dollarkosten pro Sitzung mit Diagrammvisualisierungen.
- **Audio**: Echtzeit-Mikrofonpegelanzeige und Lautsprecher-Testton.
- **API Keys (API-Schlüssel)**: Lokaler, verschlüsselter Speicherschlüssel-Manager.
- **Export & Learning (Export & Lernen)**: Docx-Auto-Save-Optionen, obligatorische Eingabe von SOAP-Notizen, Einstellung der Muttersprache (Native Language) und der UI-Sprache.
- **Data (Daten)**: Schalten Sie die Ausspracheanalyse um, konfigurieren Sie Engines (Engines) und wählen Sie TTS-Shadowing-Engines aus.
- **Privacy (Datenschutz)**: Schalten Sie anonyme Nutzungs-Telemetrie um.

---

## 22. Häufig gestellte Fragen (FAQ) & Bewertungsrubrik (Scoring Rubric)

### Häufig gestellte Fragen (FAQ)

**F: Das Mikrofon nimmt keinen Ton auf und die KI antwortet nicht.**

- Überprüfen Sie Android **Einstellungen → Apps → Bedside English → Berechtigungen → Mikrofon** und setzen Sie es auf [Zulassen]. Wenn die Berechtigung verweigert wird, wechselt die App in den Modus **Type instead (Stattdessen tippen)**, sodass Sie über die Tastatur weiter üben können.

**F: Erweiterte Übungsmodi (Exam, Teach-back, Interview, Lounge, Custom) fehlen.**

- Schließen Sie Ihre erste Übungssitzung ab, und alle erweiterten Modi werden automatisch mit einer Feier-Nachricht freigeschaltet. Sie können den Bereich auch erweitern und alle Modi auf dem Übungsbildschirm (Practice Screen) anzeigen.

**F: Die Ergebnisse der Ausspracheanalyse werden nicht sofort in meine SRS-Überprüfungsdatenbank aufgenommen.**

- Um zu verhindern, dass einmalige Spracherkennungsfehler die Lernenden belasten, gehen akzeptierte Ausspracheelemente zunächst in einen **Beobachtet (Observed)**-Status über. Sie werden nur dann zu aktiven SRS-Überprüfungskarten hochgestuft, wenn dasselbe Fehlermuster in einer zukünftigen Sitzung erneut auftritt.

**F: Kann ich externe Gesprächstranskripte (z. B. ChatGPT) zur Bewertung importieren?**

- Ja. Verwenden Sie die Android-Teilen-Funktion, um Text für Bedside English freizugeben, oder fügen Sie Text in den Bildschirm `Import Transcript` ein, um vollständiges Feedback, SOAP-Notizen und Korrekturkarten zu erhalten.

---

### 📝 Detaillierte Bewertungsrubrik (Scoring Rubric) (Punktebereich 0–10)

| Punktzahl  | Bewertungsebene (Rating Level)                 | Bewertungskriterien (Assessment Criteria)                                                                                                                                   |
| :--------- | :--------------------------------------------- | :-------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **9 ~ 10** | **Attending / Expert (Oberarzt / Experte)**    | Fehlerfreie Grammatik und Ausdrucksweise; präziser klinischer Wortschatz und systematisches medizinisches Denken; natürlicher, professioneller Ton.                         |
| **7 ~ 8**  | **Competent / Pass (Kompetent / Bestanden)**   | Kleinere grammatikalische Fehler, aber völlig klare Kommunikation; identifiziert wichtige Risikofaktoren und führt Differentialdiagnosen sicher durch.                      |
| **5 ~ 6**  | **Developing (Entwickelnd)**                   | Häufige strukturelle Grammatikfehler, die die Anstrengung des Zuhörers erfordern; unsystematisches medizinisches Denken und Vokabular.                                      |
| **1 ~ 4**  | **Critical / Fail (Kritisch / Durchgefallen)** | Schwere medizinische Fehler oder übersehene Risikofaktoren; die Sprache ist auf einzelne Wörter oder häufige lange Pausen beschränkt, die einen normalen Dialog verhindern. |
