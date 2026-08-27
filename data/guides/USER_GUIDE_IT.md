# Guida Utente di Bedside English

**Pubblico di Riferimento:** Laureati in Medicina all'Estero (IMG), medici residenti, studenti di medicina e professionisti sanitari che si preparano per l'inglese clinico, OSCE, OET Speaking, colloqui di Residency negli Stati Uniti, presentazioni durante i giri di corsia e comunicazione clinica.

Bedside English è un'applicazione esclusiva per Android supportata da IA conversazionale (Google Gemini, OpenAI Realtime, Anthropic Claude) e tecnologia vocale in tempo reale. È progettata per allenare le competenze di comunicazione clinica, il ragionamento medico, la pronuncia e l'intelligibilità dell'eloquio su più dimensioni. Questa guida descrive dettagliatamente tutte le funzionalità e l'utilizzo dell'applicazione nella sua versione più recente.

---

## 📌 Indice dei Contenuti

1. [Configurazione e Impostazione della Chiave API](#1-configurazione-e-impostazione-della-chiave-api)
2. [Installazione dell'App e Configurazione dei Permessi](#2-installazione-dellapp-e-configurazione-dei-permessi)
3. [Primo Avvio e Personalizzazione della Lingua Madre (L1)](#3-primo-avvio-e-personalizzazione-della-lingua-madre-l1)
4. [Panoramica dell'Interfaccia Utente (5 Schede Inferiori e Guida)](#4-panoramica-dellinterfaccia-utente-5-schede-inferiori-e-guida)
5. [Padroneggiare la Dashboard (Home)](#5-padroneggiare-la-dashboard-home)
6. [Practice Hub e Sblocco Progressivo delle Funzionalità](#6-practice-hub-e-sblocco-progressivo-delle-funzionalità)
7. [Incontri con i Pazienti e Monitoraggio della Copertura Clinica](#7-incontri-con-i-pazienti-e-monitoraggio-della-copertura-clinica)
8. [Modalità Esame e Diagnostica (Valutazione di 10 minuti e CEFR)](#8-modalità-esame-e-diagnostica-valutazione-di-10-minuti-e-cefr)
9. [Laboratorio di Pronuncia e Intelligibilità (Pron Lab)](#9-laboratorio-di-pronuncia-e-intelligibilità-pron-lab)
10. [Inglese di Sopravvivenza e Laboratorio di Ascolto](#10-inglese-di-sopravvivenza-e-laboratorio-di-ascolto)
11. [Spiegazione del Concetto Clinico (Tecnica di Feynman)](#11-spiegazione-del-concetto-clinico-tecnica-di-feynman)
12. [Simulazione Colloqui di Residency (Mock Interviews)](#12-simulazione-colloqui-di-residency-mock-interviews)
13. [Lounge di Inglese Libero e Scenari Personalizzati](#13-lounge-di-inglese-libero-e-scenari-personalizzati)
14. [Analisi del Rapporto di Feedback (7 Sezioni Principali)](#14-analisi-del-rapporto-di-feedback-7-sezioni-principali)
15. [Tutor IA Socratico e Voice Coach 1:1](#15-tutor-ia-socratico-e-voice-coach-11)
16. [Tracciamento Errori a Ripetizione Dilazionata e Genoma degli Errori](#16-tracciamento-errori-a-ripetizione-dilazionata-e-genoma-degli-errori)
17. [Presentazione del Caso al Medico Strutturato](#17-presentazione-del-caso-al-medico-strutturato)
18. [Importazione di Cronologie di Conversazione Esterne (Import Transcript)](#18-importazione-di-cronologie-di-conversazione-esterne-import-transcript)
19. [Esportazione di Mazzi Anki e Documenti Word](#19-esportazione-di-mazzi-anki-e-documenti-word)
20. [Wiki di Guida Integrata nell'App](#20-wiki-di-guida-integrata-nellapp)
21. [Impostazioni della Lingua, Tracciamento Costi API e Preferenze](#21-impostazioni-della-lingua-tracciamento-costi-api-e-preferenze)
22. [Domande Frequenti (FAQ) e Criteri di Valutazione](#22-domande-frequenti-faq-e-criteri-di-valutazione)

---

## 1. Configurazione e Impostazione della Chiave API

Bedside English supporta in modo flessibile i provider Google Gemini, OpenAI e Anthropic Claude.

### 💡 Configurazione Consigliata (Modalità Chiave Singola Google Gemini)

**La registrazione di una singola chiave API Google Gemini abilita ogni funzionalità dell'app — dalle conversazioni vocali in tempo reale all'analisi del feedback post-sessione — nel modo più rapido ed economico.**

| Servizio               | Scopo Principale                                                            | Richiesto / Opzionale                       | Link                                                   |
| :--------------------- | :-------------------------------------------------------------------------- | :------------------------------------------ | :----------------------------------------------------- |
| **Google (Gemini)**    | Conversazione vocale dal vivo (Gemini Live) + Analisi feedback approfondita | **Richiesto (Una sola chiave copre tutto)** | [aistudio.google.com](https://aistudio.google.com)     |
| **OpenAI**             | Voce in tempo reale (OpenAI Realtime) + Feedback + TTS Premium              | Opzionale                                   | [platform.openai.com](https://platform.openai.com)     |
| **Anthropic (Claude)** | Analisi feedback post-sessione (backend selezionabile)                      | Opzionale (Gemini è predefinito)            | [console.anthropic.com](https://console.anthropic.com) |

### Inserimento della Chiave API e Sicurezza

Le chiavi API vengono inserite direttamente nell'app:

- Configura durante la **Procedura Guidata di Primo Avvio (Onboarding Wizard)** o tramite l'**Icona Impostazioni (⚙️) → Preferenze → Chiavi API** nella barra superiore.
- Le chiavi inserite vengono **salvate in modo sicuro** nella memoria crittografata del dispositivo (`EncryptedSharedPreferences`) e non vengono mai inviate a server esterni.
- Ogni campo della chiave include un'icona a forma di occhio sulla destra per attivare/disattivare la visibilità della chiave.

### 🎈 Modalità Demo (Prova Completamente Gratuita)

Se desideri provare l'app senza registrare una chiave API o concedere permessi al microfono, seleziona la modalità **Demo** nella schermata di benvenuto o in **Preferenze**.
Verranno caricati scenari simulati e dati di feedback di esempio, consentendoti di esplorare l'interfaccia utente completa, il tracciamento errori e le funzioni di ripasso **gratuitamente** senza consumare token. Dopo aver completato una sessione demo, un prompt ti permetterà di inserire una chiave API o continuare con la pratica del paziente demo successivo in qualsiasi momento.

---

## 2. Installazione dell'App e Configurazione dei Permessi

Bedside English è compatibile con smartphone e tablet con Android 8.0 (API Level 26) o superiore.

### Installazione dell'App

- Apri il file di installazione fornito (`.apk`) sul tuo dispositivo Android e segui le istruzioni sullo schermo per installare.

### Permessi Microfono e Modalità Digita Invece

- Al primo avvio di una modalità vocale dal vivo, il sistema operativo Android chiederà l'accesso al microfono. Tocca **[Consenti]** per far funzionare correttamente il riconoscimento vocale.
- Se ti trovi in un ambiente in cui parlare è difficile o se il permesso viene negato, l'app non si bloccherà. Passerà automaticamente alla modalità **Digita invece (Type instead)**, consentendoti di esercitarti nelle conversazioni tramite l'inserimento da tastiera.

### Permessi Notifiche e Controllo Audio Preliminare (Audio Preflight)

- Viene richiesta l'autorizzazione alle notifiche in modo da poter ricevere avvisi al completamento dell'analisi del feedback in background.
- Immediatamente prima di avviare la tua prima sessione vocale dal vivo, viene visualizzata una finestra di **Controllo Audio Preliminare (Audio Preflight)** per guidare l'uso delle cuffie e testare i livelli di ingresso del microfono, prevenendo loop di feedback dell'altoparlante (effetto larsen/fischio).

---

## 3. Primo Avvio e Personalizzazione della Lingua Madre (L1)

Al primo avvio dell'app, viene eseguita una procedura guidata in 4 passaggi per creare un ambiente di apprendimento personalizzato:

1. **Schermata di Benvenuto**: Presenta le funzioni principali e offre un pulsante **Prova Modalità Demo** per esplorare senza chiavi API.
2. **Selezione Lingua Interfaccia**: Scegli la lingua preferita per l'interfaccia (8 lingue supportate: Inglese, Coreano, Spagnolo, Cinese, Arabo, Hindi, Portoghese, Tagalog).
3. **Impostazione Chiavi API**: Registra le tue chiavi API Google Gemini o di altri provider di IA.
4. **Lingua Madre e Privacy**: Seleziona la tua prima lingua (es. **Coreano**, Cinese, Spagnolo, Arabo, Hindi, Tagalog, Portoghese). Questo attiva un'analisi grammaticale e fonetica di precisione calibrata sui modelli di interferenza specifici della tua lingua madre.

### 🌐 Punti Salienti della Personalizzazione per Lingua Madre L1 (es. Apprendenti L1 Coreani)

- **Correzioni Grammaticali e Sintattiche**:
  - Articoli mancanti (omissione di _a/an/the_ prima dei nomi)
  - Plurale _-s_ mancante (_two patient_ → _two patients_)
  - Errori di tempo verbale (uso del tempo presente durante la discussione dell'anamnesi medica passata)
  - Uso improprio delle preposizioni (_in hospital_, omissione delle preposizioni in _explain to patient_)
  - Traduzioni dirette letterali / Konglish (_skin scale_, traduzione diretta imbarazzante di _side effect_)
- **Correzioni della Pronuncia e Intelligibilità**:
  - Distinzione della coppia minima _r / l_ (_liver_ vs _river_)
  - Distinzione _f / p_ (_fever_ vs _peter_)
  - Pronuncia della fricativa dentale _th_ (_think_ vs _tink_)
  - Consonanti finali omesse e inserimento vocale non necessario (_cardiac_ → _cardi-ack-eu_)
  - Errata accentazione delle parole mediche (_angina_, _arrhythmia_)

### 💡 Tour Interattivo al Primo Avvio

Dopo aver completato l'onboarding ed essere entrati nella Dashboard per la prima volta, un tutorial interattivo ti guida automaticamente attraverso le posizioni e le funzioni dei pulsanti chiave (Dashboard, Practice Hub, Pron Lab, SRS Reviews, History, Help, 1:1 Voice Coach).

---

## 4. Panoramica dell'Interfaccia Utente (5 Schede Inferiori e Guida)

### Barra di Navigazione Inferiore (5 Schede)

La navigazione principale è costituita da 5 schede inferiori:

```
┌───────────┬──────────────┬───────────────────────┬───────────────┬─────────────┐
│  🏠 Home  │  ▶ Practice  │  🎙️ Pronunciation Lab │  ⚠️ SRS Reviews│  🕘 History │
└───────────┴──────────────┴───────────────────────┴───────────────┴─────────────┘
```

1. **Home (Dashboard)**: Serie di pratica (`🔥`), missione clinica di 5 minuti, grafici delle tendenze delle prestazioni, Mistake Genome, roadmap e **Tutor Vocale 1:1 sempre attivo**.
2. **Practice (Practice Hub)**: Hub centrale per tutte le modalità di conversazione in tempo reale: Incontri con i Pazienti, Esame e Diagnostica, Inglese di Sopravvivenza, Teach-back, Colloqui, Lounge e Scenari Personalizzati.
3. **Pronunciation Lab (Pron Lab)**: Scheda di formazione dedicata per l'intelligibilità dell'eloquio e la correzione della pronuncia (filtraggio dei pattern di errore, gestione dello stato di osservazione).
4. **SRS Reviews (Revisione delle Debolezze)**: Quiz di revisione vocale basati su algoritmi di ripetizione dilazionata per frasi di correzione accettate.
5. **History (Cronologia Sessioni)**: Visualizza punteggi e feedback delle sessioni passate, traccia i costi dei token, esporta in Anki/Word e attiva la **Presentazione del Caso al Medico Strutturato (Present Case)**.

### Barra Superiore dell'App

- **Logo Bedside English**: Titolo principale.
- **Wiki di Guida (Icona `?`)**: Toccando l'icona `?` si apre questa Guida Utente in un visualizzatore a schermo intero con navigazione nel Sommario (TOC) e ricerca per parole chiave a testo intero.
- **Ingranaggio Impostazioni (⚙️ Preferenze)**: Chiavi API, backend vocali, ritmo del parlato, prevenzione eco, lingua e gestione dati.

---

## 5. Padroneggiare la Dashboard (Home)

La Dashboard principale presenta visivamente la tua crescita nelle capacità di comunicazione in inglese clinico su più dimensioni:

- **Serie di Pratica (Practice Streak)**: Visualizza i giorni di pratica attivi consecutivi con un'icona a forma di fiamma (`🔥`) per sviluppare abitudini di studio quotidiane.
- **Schede delle Metriche Principali**:
  - **Sessions done**: Numero totale di sessioni completamente terminate e analizzate.
  - **Errors tracked**: Correzioni confermate registrate nel tuo database personale delle debolezze.
  - **Mastered**: Errori risolti e superati attraverso quiz di revisione ripetuti.
  - **Due now**: Numero di schede di revisione SRS programmate per la revisione vocale odierna.
  - **Stubborn**: Errori "sanguisuga (Leech)" sfuggiti per oltre 4 volte consecutive che richiedono attenzione focalizzata.
- **Missione Clinica di 5 Minuti di Oggi**: Raccomanda automaticamente un corso di pratica ottimale di 5 minuti mirato agli elementi di revisione in scadenza, alla diagnostica richiesta o al tuo dominio di abilità più debole.
- **Copertura Lessicale OET (Layman Vocabulary)**:
  - Traccia l'efficacia con cui sostituisci termini medici complessi (es. _syncope_) con termini semplici e adatti ai pazienti (es. _fainting_).
  - I termini utilizzati vengono visualizzati sotto **Sbloccati di Recente (Recently Unlocked)**, mentre le espressioni inutilizzate vengono messe in coda sotto **Prossimi Obiettivi (Bloccati)**.
- **Pannello Mistake Genome**: Analizza le categorie di errore più frequenti (Articoli, Plurali, Tempi, Preposizioni, Registro, Traduzioni Dirette) e visualizza le tue prime 5 aree deboli come un grafico a barre con tooltip in linguaggio semplice.
- **Tendenze di Crescita e Grafico di Interferenza L1**:
  - Traccia le tendenze dei punteggi attraverso 5 domini (Grammatica, Accuratezza, Ragionamento, Professionalità, Scioltezza) nel corso delle ultime 20 sessioni.
  - Visualizza schemi di errori grammaticali ricorrenti.
- **Roadmap Personalizzata**: Analizza le metriche deboli e la cronologia degli errori per presentare 4 schede di abilità prioritarie di concentrazione.
- **Pulsante Coach Vocale 1:1 Sempre Attivo (`🎙️ RecordVoiceOver`)**:
  - Posizionato in basso a destra nella Dashboard. Tocca per aprire istantaneamente un dialogo vocale 1:1 con un tutor IA basato sul tuo profilo di debolezza personale senza avviare uno scenario di sessione completo.

---

## 6. Practice Hub e Sblocco Progressivo delle Funzionalità

### Sblocco Progressivo delle Funzionalità

Per evitare che i nuovi utenti si sentano sopraffatti, gli utenti che accedono per la prima volta iniziano con una schermata introduttiva tranquilla che mostra le modalità principali (**Dashboard**, **Incontri con i Pazienti**, **Inglese di Sopravvivenza**, **Cronologia**).

- **Il completamento della tua prima sessione di pratica** sblocca automaticamente le modalità avanzate (Esame, Teach-back, Colloquio, Lounge, Custom) con un messaggio di celebrazione.
- Puoi anche toccare per espandere e rivelare tutte le modalità immediatamente dalla schermata di Pratica.

### Categorie Principali del Practice Hub

1. **Incontri con i Pazienti (Patient Encounters)**: Presa dell'anamnesi, follow-up, modalità base Foundations, Skill Drills (Esercizi di Abilità), Practice My Mistakes (Pratica i Miei Errori).
2. **Esame e Diagnostica (Exam & Diagnostic)**: Diagnostica Base di 10 minuti, simulazioni OSCE, OET, Residency, ed esami simulati di Ward Round.
3. **Inglese di Sopravvivenza e Laboratorio di Ascolto**: Situazioni impreviste in ospedale, chiacchiere (small talk) a fuoco rapido, 15 profili di accenti nativi, esercizi di dettaglio per il Listening Lab.
4. **Lecture Teach-back (Spiegazione del Concetto Clinico)**: Allenamento sulla tecnica di Feynman basato su riassunti YouTube/testo.
5. **Colloqui di Residency (Residency Interviews)**: Simulazioni di colloqui comportamentali, clinici e specifici per IMG.
6. **Free English Lounge**: Dibattiti medici, discussioni sui sottotitoli dei telegiornali, risoluzione dei conflitti sul posto di lavoro.
7. **Scenari Personalizzati (Custom Scenarios)**: Crea prompt IA e rubriche di valutazione personalizzati.

---

## 7. Incontri con i Pazienti e Monitoraggio della Copertura Clinica

Simula la raccolta dell'anamnesi a bordo letto e la consulenza del paziente: il fulcro della comunicazione clinica.

### 7-1. Sotto-Modalità Operative

- **Modalità Foundations (Fondamenta)**: Rimuove l'onere del ragionamento clinico per i primi apprendenti, concentrandosi strettamente su **grammatica, vocabolario clinico, costruzione del rapporto ed espressione fluente**.
- **Skill Drills (Esercizi di Abilità)**: Esercizi di micro-competenza mirati (tecnica di empatia NURSE, spiegazioni in linguaggio semplice, passaggio del turno di notte, **Interpretazione medica sequenziale Lingua Madre → Inglese**).
- **Practice My Mistakes (Pratica i Miei Errori)**: Sintetizza un quiz di dialogo vocale istantaneo dagli errori in sospeso nel tuo database.
- **Daily Mission (Missione Quotidiana)**: Una sfida quotidiana adattiva di 5 minuti che prende di mira le tue attuali lacune di abilità.

### 7-2. Monitoraggio della Copertura Clinica dal Vivo (Live History Coverage Tracker)

Un pannello in tempo reale comprimibile che spunta gli elementi di raccolta dell'anamnesi mentre parli:

- Traccia automaticamente gli elementi in base al contesto della conversazione IA.
- Monitora insorgenza/durata, carattere del dolore, irradiazione, fattori aggravanti/allevianti, sintomi associati, ICE (Idee, Preoccupazioni, Aspettative), storia passata, farmaci, allergie, alcol/fumo, anamnesi familiare, ecc.
- Le domande di conferma negativa come _"Lei non fuma, vero?"_ sono correttamente riconosciute e tracciate.

### 7-3. Aiuto per Continuare Sensibile al Contesto (`💡 Help me continue`)

Se rimani bloccato o finisci le domande a metà sessione, tocca **💡 Help me continue** nella parte inferiore dello schermo.

- Analizza l'ultima risposta del paziente per suggerire il successivo obiettivo di domanda logico insieme a **una frase di esempio in inglese pronta all'uso**.
- Il Tracciatore della Fase del Colloquio in alto visualizza i progressi utilizzando simboli di stato:
  - `✓`: Rilevata prova sufficiente
  - `•`: Rilevata menzione parziale
  - `?`: Fase successiva raggiunta senza verifica della fase precedente

---

## 8. Modalità Esame e Diagnostica (Valutazione di 10 minuti e CEFR)

Misura la competenza comunicativa in condizioni di esame a tempo e immersive:

### Diagnostica Base di Inglese Clinico di 10 Minuti

- Inizia con l'introduzione di un esaminatore, seguita da 4 compiti brevi (spiegare una diagnosi, gestire le domande di follow-up, consegnare un passaggio di consegne SBAR di 45 secondi, rispondere a una domanda di colloquio di residenza).
- Mappa automaticamente le tue prestazioni ai **gradi CEFR** internazionali:
  - **Punteggio >= 8.5**: **C1** (Comunicazione clinica fluida e sicura a livello di medico curante/strutturato)
  - **Punteggio >= 7.2**: **B2+** (Competente per tirocinio clinico e pratica ospedaliera)
  - **Punteggio >= 6.0**: **B1-B2** (Capacità di comunicazione di base; raccomandato studio strutturato)
  - **Punteggio < 6.0**: **A2-B1** (Necessaria formazione di base in comunicazione clinica)

### Scenari di Esame Simulato (Mock Exam)

- **OSCE**: Raccolta anamnesi del dolore toracico di Mr. Hayes (misurazione ICE, rilevamento di red flag, empatia).
- **OET Speaking**: Gioco di ruolo (roleplay) di consulenza per paziente iperteso.
- **Residency**: Simulazione di colloquio con il Direttore del Programma di Medicina Interna degli Stati Uniti.
- **Ward Round (Giro di Corsia)**: Presentazione del caso di polmonite acquisita in comunità (5 minuti) e gestione delle domande orali.

### Distintivo di Affidabilità del Punteggio (Score Reliability Badge)

Indica la fiducia della valutazione dell'IA come **High (Alta)**, **Medium (Media)**, o **Low (Bassa)**:

- **High**: Conteggio delle parole del discente >= 180 parole & tasso di prova della checklist >= 75%.
- **Medium**: Conteggio delle parole del discente >= 80 parole & tasso di prova della checklist >= 50%.
- **Low**: Conteggio delle parole < 80 parole (flag di Trascrizione Breve) o tasso di prova < 50%.

---

## 9. Laboratorio di Pronuncia e Intelligibilità (Pron Lab)

Situato nella 3ª scheda inferiore dedicata (`🎙️ Pron Lab`), questo è il tuo centro di allenamento specializzato per l'intelligibilità dell'eloquio.

### 💡 Coaching Incentrato sull'Intelligibilità

L'obiettivo non è l'imitazione dell'accento nativo, ma **"Possono i colleghi internazionali e i pazienti capire chiaramente il mio discorso senza fraintendimenti?"**

- L'analisi audio fornisce un coaching mirato solo sugli elementi di pronuncia che causano incomprensione nell'ascoltatore.

### Categorie dei Pattern di Errore & Chip di Filtro

Gli errori di pronuncia rilevati durante le tue sessioni sono organizzati per categoria in chip di filtro:

- `r · l`: _liver / river_, _clinical / critical_
- `f · p`: _fever / peter_, _palpation / falcation_
- `th`: _think / tink_, _throat / troat_
- `final`: Consonanti finali omesse (_chest / ches_)
- `cluster`: Elaborazione del cluster consonantico & inserimento vocale non necessario (_cardiac_ → _cardi-ack-eu_)
- `stress`: Errata accentazione delle parole mediche (_angina_, _arrhythmia_)
- `vowel`: Confusione vocale corta vs lunga (_ship / sheep_, _fit / feet_)

### Regola di Promozione dello Stato Osservato

Quando una scheda di pronuncia accettata viene registrata per la prima volta, entra in uno stato **Osservato (Observed)** piuttosto che diventare immediatamente una scheda di compiti quotidiani. Viene promossa a scheda di errore di revisione SRS attiva solo quando lo stesso schema di errore si ripete in una sessione separata, assicurando che i glitch di riconoscimento vocale di una singola occorrenza non creino compiti eccessivi.

---

## 10. Inglese di Sopravvivenza e Laboratorio di Ascolto

Prepara gli IMG per interazioni ospedaliere reali, non cliniche, al di fuori della sala d'esame.

- **Modalità Random / Sorpresa**: Gestione spontanea di situazioni inaspettate (domande rapide in corridoio, richiami in farmacia, chiacchierate con gli infermieri) con il contesto dello scenario nascosto finché l'IA non parla.
- **Rapid-fire Small Talk**: Risposte rapide a improvvisi cambiamenti di argomento.
- **15 Profili di Accenti Nativi**: Esercitati ad adattarti agli accenti nativi internazionali e ai ritmi del parlato.
- **Controllo della Velocità Audio in Tempo Reale (da 0.5× a 2.5×)**: Cursore di velocità di riproduzione che preserva l'altezza tonale per adattarsi a madrelingua veloci.
- **Modalità di Ascolto Solo Orecchio (Ear-only)**: Nasconde i sottotitoli della conversazione IA così da affidarsi puramente all'ascolto, con **[Rivela l'ultima riga]** disponibile quando necessario.
- **Incoraggiamento all'Espressione di Riparazione**: L'uso di strategie di riparazione come _"Scusi?", "Potrebbe ripeterlo?"_ garantisce **punti bonus** piuttosto che detrazioni di punti.
- **Listening Lab (Laboratorio di Ascolto)**: Esercizi che testano la comprensione esatta dei dettagli (numeri, dosaggi di farmaci, nomi dei pazienti, orari, indicazioni, prezzi) con punteggio di accuratezza analitico.

---

## 11. Spiegazione del Concetto Clinico (Tecnica di Feynman)

Utilizza la tecnica di Feynman — spiegare concetti ad alta voce come se si stesse insegnando a qualcun altro — per solidificare la conoscenza medica.

1. **Prepara il Materiale Didattico**: Incolla note riassuntive o inserisci l'URL di una lezione medica di YouTube e tocca **`🎬 Fetch YT transcript`**.
2. **Condensazione IA**: Per i materiali lunghi, tocca **`✨ Condense`** per comprimere il testo in una bozza strutturata di 500 parole.
3. **Seleziona la Persona del Pubblico**:
   - **Professore d'Esame Orale (Oral Exam Professor)**: Pone domande di follow-up cliniche stimolanti e acute sui "Perché" e "Cosa succede se".
   - **Compagno di Classe Confuso (Confused Classmate)**: Richiede spiegazioni in linguaggio semplice senza pesanti gerghi medici.
   - **Tutor Amichevole (Friendly Tutor)**: Fornisce incoraggiamento di supporto e guida per la fraseologia.
4. **Insegna**: Tocca **Inizia** e spiega tramite il microfono mentre l'ascoltatore IA risponde con domande di chiarimento.

---

## 12. Simulazione Colloqui di Residency (Mock Interviews)

Simula colloqui realistici per l'impiego ospedaliero all'estero e il Residency Match negli Stati Uniti:

- **Comportamentale**: Descrizioni dell'esperienza con il metodo STAR (Situazione, Compito, Azione, Risultato).
- **Clinico**: Presentazione del caso orale, etica medica e logica di gestione delle emergenze.
- **Specifico per IMG**: Si concentra sulle domande comuni per IMG (sponsorizzazione del visto, spiegazioni dell'anno di pausa nel CV, punti di forza unici come IMG).
- Un Direttore del Programma IA conduce domande di follow-up educate ma indagatorie.

---

## 13. Lounge di Inglese Libero e Scenari Personalizzati

### Free English Lounge (Lounge di Inglese Libero)

- Discuti di argomenti medici attuali, riassumi articoli di riviste, risolvi conflitti sul posto di lavoro/infermieri o pratica chiacchiere da pausa caffè.
- Recupera i sottotitoli delle notizie mediche di YouTube per dibattere liberamente con l'IA.

### Scenari Personalizzati

Crea scenari di pratica personalizzati su misura per le tue esigenze:

- **Nome Scenario**: Identificatore personalizzato.
- **Persona / Contesto**: Prompt di sistema che definisce il ruolo dell'IA e la situazione.
- **Modello di Valutazione (Eval Template)**: Seleziona le rubriche di valutazione (es. protocollo SPIKES per dare brutte notizie).
- **Criteri di Valutazione Personalizzati**: Imposta punti di valutazione chiave specifici che l'IA dovrà controllare.

---

## 14. Analisi del Rapporto di Feedback (7 Sezioni Principali)

Dopo aver completato una sessione, un rapporto a 7 sezioni fornisce un feedback multidimensionale:

1. **Scores (Punteggi)**: Confronta i punteggi del dominio dell'IA (0-10) fianco a fianco con la tua autovalutazione. Un divario di **2.0+ punti** innesca una scheda gialla **Prompt di Riflessione** per guidare l'autoriflessione.
2. **Fluency (Scioltezza)**:
   - **WPM (Parole al Minuto)**: Misura la velocità di parola rispetto agli obiettivi raccomandati (100–130 WPM).
   - **Intercalari (Filler Words)**: Misura la densità delle parole riempitive (`um`, `uh`, `like`), incoraggiando l'uso efficace delle pause.
3. **Checklist**: Valuta gli obiettivi clinici, citando le frasi esatte della trascrizione come **Prove (Evidence)**.
4. **Confronto SOAP Note**: Confronta una nota SOAP generata automaticamente dalla tua sessione rispetto a una nota SOAP di riferimento modello.
5. **Corrections (Correzioni)**: Correzioni basate su schede per traduzioni dirette, articoli/plurali, espressioni innaturali e pronuncia. Toccando **[Accept]** si registra l'elemento nel tuo tracker di errori SRS personale.
6. **Shadowing**: Riscrive le frasi deboli in un inglese clinico di livello superiore (attending-level) per l'allenamento audio ascolta-e-ripeti.
7. **Summary & Share Cards (Riepilogo & Schede di Condivisione)**: Visualizza il feedback generale e fornisce un generatore di immagini **Share Card** per condividere i riepiloghi delle prestazioni con i compagni di studio.

---

## 15. Tutor IA Socratico e Voice Coach 1:1 Sempre Attivo

### 15-1. Debriefing 1:1 del Tutor IA Socratico (`[Debrief with AI Tutor]`)

Toccando **[Debrief with AI Tutor]** in fondo al rapporto di feedback si apre una chatroom 1:1 con un mentore IA Socratico.

- Pone domande guida piuttosto che distribuire direttamente le risposte, aiutandoti a scoprire e correggere i tuoi errori da solo.
- La cronologia della chat di debriefing viene conservata nel database in modo da potervi tornare e continuare in qualsiasi momento.

### 15-2. Voice Coach Parlante 1:1 Sempre Attivo (FAB Home)

Toccando il **Pulsante Fluttuante Voice Coach (`🎙️ RecordVoiceOver`)** in basso a destra della Dashboard si apre un dialogo di coaching vocale istantaneo senza dover prima completare uno scenario completo.

- Il coach IA conduce conversazioni vocali 1:1 personalizzate basate sui tuoi punti deboli accumulati in SRS.

---

## 16. Tracciamento Errori a Ripetizione Dilazionata e Genoma degli Errori

Le correzioni accettate tramite **[Accept]** vengono gestite automaticamente da algoritmi di ripetizione dilazionata nel tuo database degli errori.

- **Rilevamento Duplicati Fuzzy**: Previene automaticamente la registrazione duplicata di errori simili.
- **Sanguisuga / Errori Ostinati (Leech / Stubborn Errors)**: Gli elementi mancati per 4+ volte consecutive nei quiz di revisione vengono etichettati come **Stubborn / Leech** per una gestione focalizzata.
- **Intervalli Scientifici di Ripetizione Dilazionata**:
  - Programmazione delle revisioni: **1 giorno → 3 giorni → 7 giorni → 14 giorni → 30 giorni**. Superare 3 revisioni consecutive promuove l'elemento a **Mastered**.
  - Completare i quiz vocali in **Pratica i Miei Errori (Practice My Mistakes)** fa avanzare gli elementi verso la Padronanza.
- **Pannello Mistake Genome**:
  - Visualizza le tue prime 5 categorie di errori deboli (Articoli, Plurali, Tempi, Preposizioni, Registro, Traduzioni Dirette) come un grafico a barre sulla Dashboard con tooltip in linguaggio semplice.

---

## 17. Presentazione del Caso al Medico Strutturato (Attending Case Presentation Chaining)

Allena le capacità di passaggio di consegne orale presentando i casi a un supervisore dopo un Incontro con il Paziente:

1. Completa una sessione di **Incontro con il Paziente**.
2. Vai alla scheda **History (Cronologia)**, seleziona la sessione e tocca **`📋 Present Case`**.
3. Il Medico Strutturato IA (Attending) apre con: _"Dottore, per favore presenti il caso che ha appena visto."_
4. Consegna una presentazione del caso orale utilizzando il formato SBAR o SOAP e rispondi alle domande di follow-up sulla diagnosi differenziale e sui piani di trattamento.

---

## 18. Importazione di Cronologie di Conversazione Esterne (Import Transcript)

Importa testi di conversazione da ChatGPT, Gemini, o note cliniche nell'app per ricevere un feedback completo:

1. **Integrazione di Condivisione Android**: Evidenzia il testo della conversazione nelle app esterne e seleziona **[Condividi] → [Bedside English]** per aprire automaticamente la schermata **Import Transcript**.
2. **Inserimento Diretto / Incolla**: Apri la schermata `Import Transcript` direttamente da Preferenze o dal menu principale e incolla il testo.
3. **Feedback Automatico & SRS**: Genera punteggi di dominio, note SOAP, e schede di correzione disponibili per l'accettazione SRS.

---

## 19. Esportazione di Mazzi Anki e Documenti Word

- **Esportazione Carta Anki (`.txt` separato da tabulazioni)**:
  - Converte le correzioni accettate in un file di importazione testuale per Anki (File → Importa in Anki / AnkiDroid). La categoria di ogni errore viene portata con sé come un tag. Disponibile sia sulla schermata del feedback post-sessione sia da **My Mistakes**, che esporta l'intera lista di revisione in una volta sola.
- **Esportazione Rapporto Word (`.docx`)**:
  - Genera rapporti medici strutturati contenenti punteggi, prove della checklist, note SOAP, e correzioni di frasi. L'opzione Preferenze abilita il salvataggio automatico.

---

## 20. Wiki di Guida Integrata nell'App

Toccando l'icona `?` (Guida) nella barra superiore dell'app si apre il visualizzatore Wiki integrato in modalità schermo intero.

- **Integrazione Multilingue**: Carica automaticamente il file della guida utente corrispondente all'impostazione della lingua UI dell'app.
- **Barra Laterale Sommario (TOC)**: Consente una navigazione rapida (jump) attraverso tutte le sezioni della guida.
- **Ricerca a Testo Intero (Full-Text Search)**: Inserisci le parole chiave nella barra di ricerca per evidenziare le corrispondenze e navigare con i pulsanti precedente/successivo.
- **Collegamenti Ipertestuali Esterni**: Toccando i link web nella guida si apre il browser di sistema predefinito.

---

## 21. Impostazioni della Lingua, Tracciamento Costi API e Preferenze

Accedi all'**Ingranaggio delle Impostazioni (⚙️)** nella barra superiore per adattare l'app al tuo dispositivo e al tuo budget:

- **Voice & Feedback (Voce & Feedback)**:
  - Seleziona modelli vocali e di feedback IA (Demo, Gemini, OpenAI, Claude).
  - **Prevenzione dell'Eco (Echo Prevention)**: Disattiva automaticamente il microfono mentre l'IA parla per prevenire loop di feedback audio (essenziale quando non si usano le cuffie).
  - **Ritmo del Parlato IA (AI Speaking Pace)**: Controllo della velocità graduale (Lento, Normale, Veloce, Sfida).
- **Analisi Costi API (API Cost Analytics)**: Traccia in modo trasparente l'utilizzo dei token e il costo in dollari stimato per sessione con visualizzazioni a grafico.
- **Audio**: Indicatore del livello del microfono in tempo reale e tono di prova degli altoparlanti.
- **API Keys (Chiavi API)**: Gestore di chiavi con archiviazione crittografata locale.
- **Export & Learning (Esportazione & Apprendimento)**: Opzioni di salvataggio automatico Docx, inserimento obbligatorio SOAP note, Lingua Madre, e impostazioni Lingua UI.
- **Data (Dati)**: Attiva/disattiva analisi della pronuncia, configura motori, e seleziona motori di shadowing TTS.
- **Privacy**: Attiva/disattiva la telemetria di utilizzo anonima.

---

## 22. Domande Frequenti (FAQ) e Criteri di Valutazione

### Domande Frequenti (FAQ)

**D: Il microfono non rileva l'audio e l'IA non risponde.**

- Controlla in **Impostazioni → App → Bedside English → Permessi → Microfono** sul tuo Android e impostalo su [Consenti]. Se il permesso viene negato, l'app passa alla modalità **Digita invece (Type instead)** per permetterti di continuare a praticare con la tastiera.

**D: Le modalità di pratica avanzate (Esame, Teach-back, Colloquio, Lounge, Custom) sono scomparse.**

- Completa la tua prima sessione di pratica e tutte le modalità avanzate si sbloccheranno automaticamente con un messaggio di celebrazione. Puoi anche espandere e rivelare tutte le modalità dalla schermata di Pratica.

**D: I risultati dell'analisi della pronuncia non vanno immediatamente nel mio database di revisione SRS.**

- Per evitare che errori di riconoscimento vocale di una singola occorrenza appesantiscano gli apprendenti, gli elementi di pronuncia accettati entrano prima in uno stato **Osservato**. Vengono promossi a schede di revisione SRS attive solo quando lo stesso schema di errore si ripresenta in una sessione futura.

**D: Posso importare trascrizioni di conversazioni esterne (es. ChatGPT) per la valutazione?**

- Sì. Usa la funzione di condivisione di Android per condividere il testo a Bedside English o incolla il testo nella schermata `Import Transcript` per ricevere feedback completo, note SOAP e schede di correzione.

---

### 📝 Criteri Dettagliati di Valutazione (Scala 0–10)

| Punteggio  | Livello                   | Criteri di Valutazione                                                                                                                                     |
| :--------- | :------------------------ | :--------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **9 ~ 10** | **Strutturato / Esperto** | Grammatica ed espressione impeccabili; terminologia medica precisa e ragionamento clinico sistematico; tono professionale e naturale.                      |
| **7 ~ 8**  | **Competente / Idoneo**   | Lievi errori grammaticali ma comunicazione del tutto chiara; individua i fattori di rischio principali ed effettua la diagnosi differenziale in sicurezza. |
| **5 ~ 6**  | **In Sviluppo**           | Frequenti errori grammaticali strutturali che richiedono sforzo da parte dell'ascoltatore; ragionamento clinico e lessico poco sistematici.                |
| **1 ~ 4**  | **Critico / Non Idoneo**  | Gravi errori medici o fattori di rischio ignorati; parlato limitato a singole parole o frequenti lunghe pause che impediscono il colloquio.                |
