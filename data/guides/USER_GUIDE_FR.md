# Guide de l'utilisateur — Bedside English

**Public cible :** Diplômés en médecine à l'international (IMG), internes, étudiants en médecine et professionnels de santé qui se préparent à l'anglais médical clinique, à l'OSCE, à l'OET Speaking, aux entretiens de résidence aux États-Unis (US Residency Match), aux présentations de cas en visite (Ward Round) et à la communication clinique.

Bedside English est une application Android alimentée par une IA conversationnelle (Google Gemini, OpenAI Realtime, Anthropic Claude) et par la technologie de voix en temps réel. Elle est conçue pour entraîner les compétences en communication clinique, le raisonnement médical, la prononciation et l'intelligibilité de la parole selon de multiples dimensions. Ce guide détaille toutes les fonctionnalités et l'utilisation de la dernière version de l'application.

---

## 📌 Table des matières

1. [Configuration de la clé API](#1-configuration-de-la-clé-api)
2. [Installation de l'application et configuration des autorisations](#2-installation-de-lapplication-et-configuration-des-autorisations)
3. [Onboarding à la première ouverture & personnalisation pour les locuteurs L1](#3-onboarding-à-la-première-ouverture--personnalisation-pour-les-locuteurs-l1)
4. [Vue d'ensemble de l'interface (5 onglets de navigation & aide)](#4-vue-densemble-de-linterface-5-onglets-de-navigation--aide)
5. [Maîtriser le tableau de bord (Home)](#5-maîtriser-le-tableau-de-bord-home)
6. [Practice Hub & déblocage progressif des fonctionnalités](#6-practice-hub--déblocage-progressif-des-fonctionnalités)
7. [Patient Encounters & suivi de la couverture d'anamnèse en direct](#7-patient-encounters--suivi-de-la-couverture-danamnèse-en-direct)
8. [Mode Examen & Diagnostic (Baseline 10 min & correspondance CECRL)](#8-mode-examen--diagnostic-baseline-10-min--correspondance-cecrl)
9. [Laboratoire de prononciation & d'intelligibilité (Pron Lab)](#9-laboratoire-de-prononciation--dintelligibilité-pron-lab)
10. [Anglais de survie & Laboratoire d'écoute (Survival English & Listening Lab)](#10-anglais-de-survie--laboratoire-découte-survival-english--listening-lab)
11. [Lecture Teach-back (Technique de Feynman)](#11-lecture-teach-back-technique-de-feynman)
12. [Entretiens de résidence simulés (Residency Mock Interviews)](#12-entretiens-de-résidence-simulés-residency-mock-interviews)
13. [Free English Lounge & scénarios personnalisés](#13-free-english-lounge--scénarios-personnalisés)
14. [Analyse détaillée du rapport de feedback (7 sections)](#14-analyse-détaillée-du-rapport-de-feedback-7-sections)
15. [Tuteur IA socratique & Coach vocal 1:1 permanent](#15-tuteur-ia-socratique--coach-vocal-11-permanent)
16. [Suivi des erreurs par répétition espacée & Mistake Genome](#16-suivi-des-erreurs-par-répétition-espacée--mistake-genome)
17. [Présentation de cas en chaîne (Attending Case Presentation Chaining)](#17-présentation-de-cas-en-chaîne-attending-case-presentation-chaining)
18. [Importation de conversations externes (Import Transcript)](#18-importation-de-conversations-externes-import-transcript)
19. [Export Anki & Word](#19-export-anki--word)
20. [Aide intégrée (In-App Help Wiki)](#20-aide-intégrée-in-app-help-wiki)
21. [Paramètres de langue, suivi des coûts API & Préférences](#21-paramètres-de-langue-suivi-des-coûts-api--préférences)
22. [Foire aux questions (FAQ) & grille d'évaluation](#22-foire-aux-questions-faq--grille-dévaluation)

---

## 1. Configuration de la clé API

Bedside English prend en charge de façon flexible Google Gemini, OpenAI et Anthropic Claude.

### 💡 Configuration recommandée (mode clé unique Google Gemini)

**Enregistrer une seule clé API Google Gemini suffit à activer toutes les fonctionnalités de l'application — conversations vocales en temps réel et analyses de feedback après session — de la manière la plus rapide et la plus économique.**

| Service                | Utilisation principale                                                            | Requis / Optionnel                                           | Lien                                                   |
| :--------------------- | :-------------------------------------------------------------------------------- | :----------------------------------------------------------- | :----------------------------------------------------- |
| **Google (Gemini)**    | Conversation vocale en temps réel (Gemini Live) + Analyse de feedback approfondie | **Requis (une seule clé couvre toutes les fonctionnalités)** | [aistudio.google.com](https://aistudio.google.com)     |
| **OpenAI**             | Voix en temps réel (OpenAI Realtime) + Feedback + TTS premium                     | Optionnel                                                    | [platform.openai.com](https://platform.openai.com)     |
| **Anthropic (Claude)** | Analyse du feedback de session (backend sélectionnable)                           | Optionnel (Gemini par défaut)                                | [console.anthropic.com](https://console.anthropic.com) |

### Saisie de la clé API et sécurité

Les clés API sont saisies directement dans l'application :

- Configurez-les lors de l'**assistant d'onboarding à la première ouverture** ou via le menu **Engrenage des paramètres (⚙️) → Préférences → Clés API** dans la barre supérieure.
- Les clés API saisies sont **stockées de façon sécurisée** dans un espace chiffré sur l'appareil (`EncryptedSharedPreferences`) et ne sont jamais envoyées à des serveurs externes.
- Chaque champ de clé dispose d'une icône en forme d'œil à droite pour afficher ou masquer la clé.

### 🎈 Mode Démo (Essai entièrement gratuit)

Si vous souhaitez explorer l'application sans enregistrer de clé API ni accorder d'autorisations microphone, sélectionnez le mode **Démo** sur l'écran d'onboarding ou dans les **Préférences**.
Des conversations simulées scriptées et des données de feedback se chargeront, vous permettant d'explorer l'interface complète, le suivi des erreurs et les fonctionnalités de révision **gratuitement**, sans consommer de tokens. Après avoir terminé une session de démo, une invite vous propose d'entrer une clé API ou de continuer avec la prochaine session de démo.

---

## 2. Installation de l'application et configuration des autorisations

Bedside English fonctionne sur les smartphones et tablettes Android 8.0 (API niveau 26) ou supérieur.

### Installation de l'application

- Lancez le fichier d'installation fourni (`.apk`) sur votre appareil Android et suivez les instructions à l'écran pour installer l'application.

### Autorisation microphone & mode Saisie au clavier

- Au premier lancement d'un mode de pratique vocale en direct, Android demande l'autorisation d'accès au microphone. Appuyez sur **[Autoriser]** pour que la reconnaissance vocale fonctionne correctement.
- Si vous êtes dans un environnement où parler est difficile ou si l'autorisation est refusée, l'application ne plantera pas. Elle bascule automatiquement en mode **Saisie au clavier (Type instead)**, vous permettant de pratiquer les conversations en tapant.

### Autorisation de notifications & vérification audio (Audio Preflight)

- L'autorisation de notifications est demandée afin de vous prévenir quand l'analyse de feedback en arrière-plan est terminée.
- Juste avant de démarrer votre première session vocale en direct, un contrôle **Audio Preflight** s'affiche pour guider l'utilisation du casque et tester les niveaux d'entrée du microphone, prévenant ainsi les boucles de retour audio (effet Larsen).

---

## 3. Onboarding à la première ouverture & personnalisation pour les locuteurs L1

Au premier lancement de l'application, un assistant de configuration en 4 étapes s'exécute pour créer un environnement d'apprentissage personnalisé :

1. **Écran d'accueil (Welcome)** : Présente les fonctionnalités principales et propose un bouton **Try Demo Mode** pour explorer sans clé API.
2. **Sélection de la langue d'affichage (UI Language Picker)** : Choisissez la langue de l'interface (8 langues supportées : anglais, coréen, espagnol, chinois, arabe, hindi, portugais, tagalog).
3. **Configuration des clés API** : Enregistrez votre clé API Google Gemini ou d'autres services IA.
4. **Langue maternelle & confidentialité** : Sélectionnez votre première langue (ex. : **coréen**, chinois, espagnol, arabe, hindi, tagalog, portugais). Cela active une analyse précise de la grammaire et de la prononciation adaptée aux schémas d'interférence spécifiques à votre langue maternelle.

### 🌐 Points forts de la personnalisation pour la langue maternelle L1 (ex. : apprenants coréens L1)

- **Corrections grammaticales et de formulation** :
  - Articles manquants (omission de _a/an/the_ devant les noms)
  - Oubli du pluriel _-s_ (_two patient_ → _two patients_)
  - Erreurs de temps verbaux (utilisation du présent pour évoquer des antécédents médicaux)
  - Mauvais emploi des prépositions (_in hospital_, omission de prépositions dans _explain to patient_)
  - Traductions directes littérales / Konglish (_skin scale_, traduction directe maladroite de _side effect_)
- **Corrections de prononciation & d'intelligibilité** :
  - Distinction de la paire minimale _r / l_ (_liver_ vs _river_)
  - Distinction _f / p_ (_fever_ vs _peter_)
  - Prononciation de la fricative dentale _th_ (_think_ vs _tink_)
  - Consonnes finales omises et insertion de voyelle inutile (_cardiac_ → _cardi-ack-eu_)
  - Accent tonique médical mal placé (_angina_, _arrhythmia_)

### 💡 Tutoriel interactif à la première ouverture

Après l'onboarding et au premier accès au tableau de bord, un tutoriel interactif vous guide automatiquement à travers l'emplacement et la fonction des boutons clés (Dashboard, Practice Hub, Pron Lab, SRS Reviews, History, Help, Coach vocal 1:1).

---

## 4. Vue d'ensemble de l'interface (5 onglets de navigation & aide)

### Barre de navigation inférieure (5 onglets)

La navigation principale se compose de 5 onglets inférieurs :

```
┌───────────┬──────────────┬───────────────────────┬───────────────┬─────────────┐
│  🏠 Home  │  ▶ Practice  │  🎙️ Pronunciation Lab │  ⚠️ SRS Reviews│  🕘 History │
└───────────┴──────────────┴───────────────────────┴───────────────┴─────────────┘
```

1. **Home (Tableau de bord)** : Série de pratique (`🔥`), mission clinique de 5 minutes, graphiques de tendance des performances, Mistake Genome, feuille de route et **Coach vocal 1:1 permanent**.
2. **Practice (Practice Hub)** : Plateforme centrale pour tous les modes de pratique orale en temps réel : Patient Encounters, Exam & Diagnostic, Survival English, Teach-back, Entretiens, Lounge et scénarios personnalisés.
3. **Pronunciation Lab (Pron Lab)** : Onglet dédié à l'entraînement à l'intelligibilité de la parole et à la correction de la prononciation (filtrage des schémas d'erreurs, gestion des statuts de révision).
4. **SRS Reviews (Révision des points faibles)** : Quiz de révision orale basés sur des algorithmes de répétition espacée pour les corrections acceptées.
5. **History (Historique des sessions)** : Consulter les scores et les feedbacks des sessions passées, suivre les coûts en tokens, exporter vers Anki/Word et lancer la **Présentation de cas au chef de service (Present Case)**.

### Barre d'application supérieure

- **Logo Bedside English** : Titre principal.
- **Aide Wiki (icône `?`)** : Appuyer sur l'icône `?` ouvre ce guide utilisateur dans une visionneuse plein écran avec navigation par table des matières et recherche en texte intégral.
- **Engrenage des paramètres (⚙️ Préférences)** : Clés API, backends vocaux, débit de parole, prévention de l'écho, langue et gestion des données.

---

## 5. Maîtriser le tableau de bord (Home)

Le tableau de bord principal visualise votre progression en communication clinique en anglais selon de multiples dimensions :

- **Série de pratique (Practice Streak)** : Affiche le nombre de jours de pratique consécutifs avec une icône de flamme (`🔥`) pour encourager l'étude quotidienne.
- **Indicateurs clés** :
  - **Sessions done** : Nombre total de sessions entièrement complétées et analysées.
  - **Errors tracked** : Corrections confirmées enregistrées dans votre base de données personnelle de points faibles.
  - **Mastered** : Erreurs résolues et graduées grâce à des quiz de révision répétés.
  - **Due now** : Nombre de cartes SRS planifiées pour une révision orale aujourd'hui.
  - **Stubborn** : Erreurs « persistantes » ratées 4 fois ou plus consécutivement nécessitant une attention particulière.
- **Mission clinique de 5 minutes (Today's 5-Minute Clinical Mission)** : Recommande automatiquement un parcours de pratique de 5 minutes ciblant les révisions dues, les diagnostics requis ou votre domaine le plus faible.
- **Couverture du vocabulaire OET Layman** :
  - Suit l'efficacité avec laquelle vous remplacez les termes médicaux complexes (ex. : _syncope_) par des termes accessibles aux patients (ex. : _fainting_).
  - Les termes utilisés apparaissent sous **Recently Unlocked**, tandis que les expressions non utilisées sont listées sous **Next Goals (Locked)**.
- **Panneau Mistake Genome** : Analyse vos catégories d'erreurs les plus fréquentes (Articles, Pluriels, Temps, Prépositions, Registre, Traductions directes) et affiche vos 5 points les plus faibles sous forme de graphique à barres.
- **Tendances de progression & graphique des interférences L1** :
  - Représente les tendances de scores dans 5 domaines (Grammaire, Précision, Raisonnement, Professionnalisme, Fluidité) sur vos 20 dernières sessions.
  - Visualise les schémas d'erreurs grammaticales récurrentes.
- **Feuille de route personnalisée** : Analyse vos métriques faibles et votre historique d'erreurs pour présenter 4 cartes de compétences prioritaires.
- **Bouton Coach vocal 1:1 permanent (`🎙️ RecordVoiceOver`)** :
  - Situé en bas à droite du tableau de bord. Appuyer dessus ouvre instantanément un dialogue de pratique orale 1:1 avec un tuteur IA basé sur votre profil de points faibles, sans démarrer un scénario complet.

---

## 6. Practice Hub & déblocage progressif des fonctionnalités

### Déblocage progressif des fonctionnalités

Pour éviter que les nouveaux utilisateurs ne se sentent dépassés, les premiers utilisateurs commencent avec un écran d'introduction calme affichant les modes principaux (**Tableau de bord**, **Patient Encounters**, **Survival English**, **History**).

- **Terminer votre première session de pratique** débloque automatiquement les modes avancés (Exam, Teach-back, Interview, Lounge, Custom) avec un message de félicitations.
- Vous pouvez également appuyer pour afficher immédiatement tous les modes depuis l'écran Practice.

### Catégories de modes principaux du Practice Hub

1. **Patient Encounters** : Anamnèse, suivi, mode Foundations débutant, Skill Drills, Practice My Mistakes.
2. **Exam & Diagnostic** : Diagnostic de base de 10 minutes, OSCE, OET, Résidence et examens simulés de Ward Round.
3. **Survival English & Listening Lab** : Situations inattendues à l'hôpital, small talk rapide, 15 profils d'accent natif, exercices d'écoute de détail.
4. **Lecture Teach-back** : Entraînement à la technique de Feynman basé sur des résumés YouTube/texte.
5. **Residency Interviews** : Entretiens simulés comportementaux, cliniques et spécifiques aux IMG.
6. **Free English Lounge** : Débats médicaux, discussions sur des sous-titres d'actualités, gestion des conflits en milieu professionnel.
7. **Custom Scenarios** : Créez des prompts IA et des grilles d'évaluation personnalisés.

---

## 7. Patient Encounters & suivi de la couverture d'anamnèse en direct

Simule la prise d'anamnèse au chevet du patient et le conseil patient — le cœur de la communication clinique.

### 7-1. Sous-modes opérationnels

- **Mode Foundations** : Élimine les charges de raisonnement clinique pour les débutants, en se concentrant strictement sur la **grammaire, le vocabulaire clinique, le rapport et la fluidité**.
- **Skill Drills** : Exercices de microcompétences ciblées (technique d'empathie NURSE, explications en langage simple, passation de consignes de nuit, **interprétation médicale séquentielle langue maternelle → anglais**).
- **Practice My Mistakes** : Génère instantanément un quiz de dialogue oral à partir des erreurs en attente dans votre base de données.
- **Daily Mission** : Un défi adaptatif de 5 minutes ciblant vos lacunes actuelles.

### 7-2. Suivi de la couverture d'anamnèse en direct

Un panneau rétractable en temps réel qui coche les éléments d'anamnèse au fur et à mesure que vous parlez :

- Suit automatiquement les éléments en fonction du contexte de la conversation IA.
- Surveille l'apparition/durée, le caractère de la douleur, l'irradiation, les facteurs aggravants/soulageants, les symptômes associés, l'ICE (Idées, Craintes, Attentes), les antécédents, les médicaments, les allergies, l'alcool/tabac, les antécédents familiaux, etc.
- Les questions de confirmation négatives comme _« You don't smoke, do you? »_ sont correctement reconnues et cochées.

### 7-3. Aide contextuelle à la continuation (`💡 Help me continue`)

Si vous êtes bloqué ou à court de questions en cours de session, appuyez sur **💡 Help me continue** en bas de l'écran.

- Analyse la dernière réponse du patient pour suggérer le prochain objectif logique de questionnement ainsi qu'une **phrase exemple en anglais prête à l'emploi**.
- Le traqueur de phase d'entretien en haut affiche la progression avec des symboles de statut :
  - `✓` : Preuve suffisante détectée
  - `•` : Mention partielle détectée
  - `?` : Phase suivante atteinte sans vérification de la phase précédente

---

## 8. Mode Examen & Diagnostic (Baseline 10 min & correspondance CECRL)

Mesure la compétence en communication dans des conditions d'examen chronométrées et immersives :

### Diagnostic clinique de base de 10 minutes (10-minute Baseline Diagnostic)

- Commence par une introduction d'examinateur, suivie de 4 courtes tâches (expliquer un diagnostic, gérer des questions de suivi, délivrer un compte-rendu SBAR de 45 secondes, répondre à une question d'entretien de résidence).
- Mappe automatiquement vos performances aux **niveaux CECRL** internationaux :
  - **Score >= 8,5** : **C1** (Communication clinique fluide et sûre au niveau d'un médecin titulaire)
  - **Score >= 7,2** : **B2+** (Compétent pour les stages cliniques et la pratique hospitalière)
  - **Score >= 6,0** : **B1-B2** (Capacité de communication de base ; étude structurée recommandée)
  - **Score < 6,0** : **A2-B1** (Formation en communication clinique fondamentale requise)

### Scénarios d'examens simulés

- **OSCE** : Prise d'anamnèse pour douleur thoracique (M. Hayes) — mesure de l'ICE, détection des signes d'alarme, empathie.
- **OET Speaking** : Jeu de rôle de conseil patient pour hypertension.
- **Residency** : Entretien simulé avec un directeur de programme de médecine interne américain.
- **Ward Round** : Présentation de cas de pneumonie communautaire de 5 minutes avec gestion des questions orales.

### Badge de fiabilité du score

Indique la confiance dans la notation IA : **Élevée**, **Moyenne** ou **Faible** :

- **Élevée** : Nombre de mots de l'apprenant >= 180 & taux d'evidence de la checklist >= 75 %.
- **Moyenne** : Nombre de mots >= 80 & taux d'evidence >= 50 %.
- **Faible** : Nombre de mots < 80 (indicateur Short Transcript) ou taux d'evidence < 50 %.

---

## 9. Laboratoire de prononciation & d'intelligibilité (Pron Lab)

Situé dans le 3e onglet dédié (`🎙️ Pron Lab`), c'est votre centre d'entraînement spécialisé pour l'intelligibilité de la parole.

### 💡 Coaching centré sur l'intelligibilité

L'objectif n'est pas d'imiter l'accent natif, mais de répondre à la question : **« Mes collègues et patients internationaux me comprennent-ils clairement sans malentendu ? »**

- L'analyse audio fournit un coaching ciblé uniquement sur les éléments de prononciation causant des malentendus.

### Catégories de schémas d'erreurs & filtres

Les erreurs de prononciation détectées dans vos sessions sont organisées par catégorie en filtres :

- `r · l` : _liver / river_, _clinical / critical_
- `f · p` : _fever / peter_, _palpation / falcation_
- `th` : _think / tink_, _throat / troat_
- `final` : Consonnes finales omises (_chest / ches_)
- `cluster` : Traitement des groupes consonantiques & insertion de voyelles inutiles (_cardiac_ → _cardi-ack-eu_)
- `stress` : Accent tonique médical mal placé (_angina_, _arrhythmia_)
- `vowel` : Confusion voyelle courte / longue (_ship / sheep_, _fit / feet_)

### Règle de promotion vers l'état Observé

Quand une carte de correction de prononciation acceptée est enregistrée pour la première fois, elle entre dans un état **Observé** plutôt que de devenir immédiatement une carte de révision quotidienne. Elle est promue en carte SRS active uniquement quand le même schéma d'erreur réapparaît dans une session séparée, garantissant que les erreurs de reconnaissance vocale ponctuelles ne génèrent pas un excès de devoirs.

---

## 10. Anglais de survie & Laboratoire d'écoute (Survival English & Listening Lab)

Prépare les IMG aux interactions hospitalières réelles hors salle de consultation.

- **Mode Aléatoire / Surprise** : Gestion spontanée de situations inattendues (questions impromptues dans le couloir, rappels de pharmacie, small talk avec les infirmières) avec le contexte du scénario caché jusqu'à ce que l'IA parle.
- **Small Talk rapide** : Réponses rapides à des changements de sujet soudains.
- **15 profils d'accent natif** : Entraînez-vous à vous adapter aux accents natifs internationaux et aux rythmes de parole.
- **Contrôle de vitesse audio en temps réel (0,5× à 2,5×)** : Curseur de vitesse de lecture avec préservation de la hauteur pour s'adapter aux locuteurs natifs rapides.
- **Mode écoute seule (Ear-only)** : Masque les sous-titres de la conversation IA pour que vous vous fiiez uniquement à l'écoute, avec **[Reveal last line]** disponible si nécessaire.
- **Encouragement des expressions de réparation** : Utiliser des stratégies de réparation comme _« Sorry? »_, _« Could you say that again? »_ accorde des **points bonus** plutôt que des pénalités.
- **Listening Lab** : Exercices testant la compréhension exacte des détails (chiffres, posologies, noms de patients, heures, directions, prix) avec un score de précision détaillé.

---

## 11. Lecture Teach-back (Technique de Feynman)

Utilise la technique de Feynman — expliquer des concepts à voix haute comme si on enseignait à quelqu'un — pour consolider les connaissances médicales.

1. **Préparer le matériel de cours** : Collez des notes de résumé ou entrez une URL de cours médical YouTube et appuyez sur **`🎬 Fetch YT transcript`**.
2. **Condensation par IA** : Pour les longs contenus, appuyez sur **`✨ Condense`** pour compresser le texte en un plan structuré de 500 mots.
3. **Choisir un personnage d'audience** :
   - **Oral Exam Professor** : Pose des questions cliniques « Pourquoi » et « Et si » incisives et exigeantes.
   - **Confused Classmate** : Demande des explications en langage simple sans jargon lourd.
   - **Friendly Tutor** : Offre un encouragement bienveillant et des suggestions de formulation.
4. **Enseigner** : Appuyez sur **Start** et expliquez via le microphone pendant que le patient IA répond avec des questions de clarification.

---

## 12. Entretiens de résidence simulés (Residency Mock Interviews)

Simule des entretiens réalistes pour l'emploi dans des hôpitaux étrangers et le Residency Match américain :

- **Comportemental** : Descriptions d'expériences selon la méthode STAR (Situation, Tâche, Action, Résultat).
- **Clinique** : Présentation orale de cas, éthique médicale et logique de gestion des urgences.
- **Spécifique IMG** : Se concentre sur les questions fréquentes des IMG (parrainage de visa, explications des lacunes dans le CV, atouts uniques en tant qu'IMG).
- Un directeur de programme IA mène des questions de suivi polies mais incisives.

---

## 13. Free English Lounge & scénarios personnalisés

### Free English Lounge

- Discutez de sujets médicaux d'actualité, résumez des articles de journaux, résolvez des conflits en milieu professionnel/infirmier, ou pratiquez le small talk de pause-café.
- Récupérez les sous-titres de l'actualité médicale YouTube pour débattre librement avec l'IA.

### Scénarios personnalisés (Custom Scenarios)

Créez des scénarios de pratique personnalisés adaptés à vos besoins :

- **Nom du scénario** : Identifiant personnalisé.
- **Persona / Contexte** : Prompt système définissant le rôle et la situation de l'IA.
- **Modèle d'évaluation** : Sélectionnez des grilles d'évaluation (ex. : protocole SPIKES pour l'annonce de mauvaises nouvelles).
- **Critères d'évaluation personnalisés** : Définissez des points d'évaluation clés spécifiques à vérifier par l'IA.

---

## 14. Analyse détaillée du rapport de feedback (7 sections)

Après avoir terminé une session, un rapport en 7 sections fournit un feedback multidimensionnel :

1. **Scores** : Compare les scores de domaine de l'IA (0–10) côte à côte avec votre auto-évaluation. Un écart de **2,0+ points** déclenche une carte jaune **Reflection Prompt** pour guider la réflexion personnelle.
2. **Fluidité (Fluency)** :
   - **WPM (Mots par minute)** : Mesure la vitesse de parole par rapport aux cibles recommandées (100–130 WPM).
   - **Mots de remplissage (Filler Words)** : Mesure la densité de mots de remplissage (`um`, `uh`, `like`), encourageant l'utilisation efficace des pauses.
3. **Checklist** : Évalue les objectifs cliniques, en citant les phrases exactes de la transcription comme **Evidence (preuves)**.
4. **Comparaison de note SOAP** : Compare une note SOAP générée automatiquement à partir de votre session avec une note SOAP de référence modèle.
5. **Corrections** : Corrections par carte pour les traductions directes, les articles/pluriels, les expressions non naturelles et la prononciation. Appuyer sur **[Accept]** enregistre l'élément dans votre suivi SRS personnel.
6. **Shadowing** : Réécrit les phrases faibles en anglais clinique de niveau médecin titulaire pour un entraînement audio d'écoute et de répétition.
7. **Résumé & cartes de partage** : Affiche le feedback global et fournit un générateur d'**image Share Card** pour partager les résumés de performance avec des camarades d'étude.

---

## 15. Tuteur IA socratique & Coach vocal 1:1 permanent

### 15-1. Débriefing 1:1 avec le tuteur IA socratique (`[Debrief with AI Tutor]`)

Appuyer sur **[Debrief with AI Tutor]** en bas du rapport de feedback ouvre une salle de discussion 1:1 avec un mentor IA socratique.

- Pose des questions guidantes plutôt que de donner directement les réponses, vous aidant à découvrir et corriger vos erreurs vous-même.
- L'historique du chat de débriefing est conservé dans la base de données pour que vous puissiez y revenir et continuer à tout moment.

### 15-2. Coach vocal 1:1 permanent (FAB sur l'accueil)

Appuyer sur le **Bouton flottant Coach vocal (`🎙️ RecordVoiceOver`)** en bas à droite du tableau de bord ouvre instantanément un dialogue de coach de parole sans compléter d'abord un scénario complet.

- Le coach IA mène des conversations vocales 1:1 personnalisées basées sur vos points faibles SRS accumulés.

---

## 16. Suivi des erreurs par répétition espacée & Mistake Genome

Les corrections acceptées via **[Accept]** sont automatiquement gérées par des algorithmes de répétition espacée dans votre base de données d'erreurs.

- **Détection de doublons approximatifs** : Empêche automatiquement l'enregistrement en double d'erreurs similaires.
- **Erreurs persistantes (Leech / Stubborn)** : Les éléments ratés 4 fois ou plus consécutivement dans les quiz de révision sont marqués **Stubborn / Leech** pour une gestion ciblée.
- **Intervalles scientifiques de répétition espacée** :
  - Calendrier de révision : **1 jour → 3 jours → 7 jours → 14 jours → 30 jours**. Passer 3 révisions consécutives graduate l'élément en **Maîtrisé (Mastered)**.
  - Réussir les quiz oraux dans **Practice My Mistakes** fait progresser les éléments vers la Maîtrise.
- **Panneau Mistake Genome** :
  - Affiche vos 5 catégories d'erreurs les plus faibles (Articles, Pluriels, Temps, Prépositions, Registre, Traductions directes) sous forme de graphique à barres sur le tableau de bord avec des infobulles en langage simple.

---

## 17. Présentation de cas en chaîne (Attending Case Presentation Chaining)

Entraîne les compétences orales de passation de cas en présentant des cas à un superviseur après un Patient Encounter :

1. Terminez une session **Patient Encounter**.
2. Allez dans l'onglet **History**, sélectionnez la session et appuyez sur **`📋 Present Case`**.
3. L'IA Attending ouvre avec : _« Docteur, veuillez présenter le cas que vous venez de voir. »_
4. Délivrez une présentation orale de cas en format SBAR ou SOAP, et répondez aux questions de suivi sur le diagnostic différentiel et les plans de traitement.

---

## 18. Importation de conversations externes (Import Transcript)

Importez du texte de conversation provenant de ChatGPT, Gemini ou de notes cliniques dans l'application pour recevoir un feedback complet :

1. **Intégration de partage Android** : Surlignez le texte de conversation dans des applications externes et sélectionnez **[Partager] → [Bedside English]** pour ouvrir automatiquement l'écran **Import Transcript**.
2. **Saisie directe / Coller** : Ouvrez directement l'écran `Import Transcript` depuis les Préférences ou le menu principal et collez le texte.
3. **Feedback automatisé & SRS** : Génère des scores de domaine, des notes SOAP et des cartes de correction disponibles pour l'acceptation SRS.

---

## 19. Export Anki & Word

- **Export de cartes Anki (`.txt` séparé par tabulations)** :
  - Convertit les corrections acceptées en fichier d'import texte pour Anki (Fichier → Importer dans Anki / AnkiDroid). La catégorie de chaque erreur est conservée sous forme de tag. Disponible à la fois sur l'écran de feedback après session et depuis **My Mistakes**, qui exporte toute votre liste de révision en une seule fois.
- **Export de rapport Word (`.docx`)** :
  - Génère des rapports médicaux structurés contenant les scores, les preuves de la checklist, les notes SOAP et les corrections de phrases. Une option dans les Préférences permet la sauvegarde automatique.

---

## 20. Aide intégrée (In-App Help Wiki)

Appuyer sur l'**icône d'aide `?`** dans la barre d'application supérieure ouvre la visionneuse d'aide intégrée en mode plein écran.

- **Intégration multilingue** : Charge automatiquement le fichier de guide utilisateur correspondant au paramètre de langue de l'interface de l'application.
- **Barre latérale de table des matières (TOC)** : Permet une navigation rapide entre toutes les sections du guide.
- **Recherche en texte intégral** : Entrez des mots-clés dans la barre de recherche pour mettre en évidence les correspondances et naviguer avec les boutons précédent/suivant.
- **Liens hypertexte externes** : Appuyer sur les liens web dans le guide ouvre votre navigateur système par défaut.

---

## 21. Paramètres de langue, suivi des coûts API & Préférences

Accédez à l'**engrenage des paramètres (⚙️)** dans la barre supérieure pour personnaliser l'application selon votre appareil et votre budget :

- **Voix & Feedback** :
  - Sélectionnez les modèles d'IA pour la voix et le feedback (Démo, Gemini, OpenAI, Claude).
  - **Prévention de l'écho** : Met automatiquement le microphone en sourdine pendant que l'IA parle pour prévenir les boucles de retour audio (essentiel sans casque).
  - **Débit de parole IA** : Contrôle par paliers de la vitesse (Lent, Normal, Rapide, Défi).
- **Analyse des coûts API** : Suit de façon transparente l'utilisation des tokens et le coût estimé en dollars par session avec des visualisations graphiques.
- **Audio** : Indicateur de niveau microphone en temps réel et tonalité de test du haut-parleur.
- **Clés API** : Gestionnaire de clés en stockage chiffré local.
- **Export & Apprentissage** : Options de sauvegarde automatique Docx, note SOAP obligatoire, paramètres de langue maternelle et de langue d'interface.
- **Données** : Activer/désactiver l'analyse de la prononciation, configurer les moteurs et sélectionner les moteurs TTS pour le shadowing.
- **Confidentialité** : Activer/désactiver la télémétrie d'utilisation anonyme.

---

## 22. Foire aux questions (FAQ) & grille d'évaluation

### Foire aux questions (FAQ)

**Q : Le microphone ne capte pas l'audio et l'IA ne répond pas.**

- Vérifiez **Paramètres Android → Applications → Bedside English → Autorisations → Microphone** et réglez sur [Autoriser]. Si l'autorisation est refusée, l'application bascule en mode **Saisie au clavier (Type instead)** pour que vous puissiez continuer à pratiquer via le clavier.

**Q : Les modes de pratique avancés (Exam, Teach-back, Interview, Lounge, Custom) sont absents.**

- Terminez votre première session de pratique et tous les modes avancés se déverrouilleront automatiquement avec un message de félicitations. Vous pouvez également afficher tous les modes depuis l'écran Practice.

**Q : Les résultats de l'analyse de prononciation n'entrent pas immédiatement dans ma base de données SRS.**

- Pour éviter que les erreurs de reconnaissance vocale ponctuelles ne surchargent les apprenants, les éléments de prononciation acceptés entrent d'abord dans un état **Observé**. Ils sont promus en cartes SRS actives uniquement quand le même schéma d'erreur réapparaît dans une session future.

**Q : Puis-je importer des transcriptions de conversations externes (ex. : ChatGPT) pour une notation ?**

- Oui. Utilisez la fonction de partage Android pour partager du texte vers Bedside English ou collez le texte dans l'écran `Import Transcript` pour recevoir un feedback complet, des notes SOAP et des cartes de correction.

---

### 📝 Grille de notation détaillée (plage de scores 0–10)

| Score      | Niveau d'évaluation            | Critères d'évaluation                                                                                                                                                   |
| :--------- | :----------------------------- | :---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **9 ~ 10** | **Médecin titulaire / Expert** | Grammaire et expression irréprochables ; vocabulaire clinique précis et raisonnement médical systématique ; ton naturel et professionnel.                               |
| **7 ~ 8**  | **Compétent / Réussite**       | Erreurs grammaticales mineures mais communication parfaitement claire ; identifie les facteurs de risque clés et effectue un diagnostic différentiel en toute sécurité. |
| **5 ~ 6**  | **En développement**           | Erreurs grammaticales structurelles fréquentes nécessitant un effort d'écoute ; raisonnement clinique et vocabulaire non systématiques.                                 |
| **1 ~ 4**  | **Critique / Échec**           | Erreurs médicales graves ou facteurs de risque manqués ; parole limitée à des mots isolés ou pauses longues fréquentes empêchant un dialogue normal.                    |
