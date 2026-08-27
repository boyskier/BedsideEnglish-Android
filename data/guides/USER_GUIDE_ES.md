# Bedside English Guía del Usuario

**Público Objetivo:** Graduados Médicos Internacionales (IMGs), médicos residentes, estudiantes de medicina y profesionales de la salud que se preparan para inglés clínico, OSCE, OET Speaking, entrevistas de residencia en EE. UU., presentaciones de pases de guardia (Ward Rounds) y comunicación clínica.

Bedside English es una aplicación exclusiva para Android impulsada por IA conversacional (Google Gemini, OpenAI Realtime, Anthropic Claude) y tecnología de voz en tiempo real. Está diseñada para entrenar las habilidades de comunicación clínica, el razonamiento médico, la pronunciación y la inteligibilidad del habla en múltiples dimensiones. Esta guía detalla todas las características y el uso de la versión más reciente de la aplicación adaptada para los usuarios.

---

## 📌 Tabla de Contenidos

1. [Configuración de Claves API y Ajustes](#1-configuración-de-claves-api-y-ajustes)
2. [Instalación de la App y Permisos](#2-instalación-de-la-app-y-permisos)
3. [Configuración Inicial y Personalización L1](#3-configuración-inicial-y-personalización-l1)
4. [Resumen de la Interfaz (5 Pestañas y Ayuda)](#4-resumen-de-la-interfaz-5-pestañas-y-ayuda)
5. [Dominando el Panel Principal (Inicio)](#5-dominando-el-panel-principal-inicio)
6. [Centro de Práctica y Desbloqueo Progresivo](#6-centro-de-práctica-y-desbloqueo-progresivo)
7. [Encuentros con Pacientes y Rastreador de Anamnesis](#7-encuentros-con-pacientes-y-rastreador-de-anamnesis)
8. [Modo Examen y Diagnóstico (Línea Base de 10 min y CEFR)](#8-modo-examen-y-diagnóstico-línea-base-de-10-min-y-cefr)
9. [Laboratorio de Pronunciación e Inteligibilidad (Pron Lab)](#9-laboratorio-de-pronunciación-e-inteligibilidad-pron-lab)
10. [Inglés de Supervivencia y Laboratorio de Escucha](#10-inglés-de-supervivencia-y-laboratorio-de-escucha)
11. [Enseñanza Inversa de Conferencias (Técnica Feynman)](#11-enseñanza-inversa-de-conferencias-técnica-feynman)
12. [Simulacros de Entrevistas de Residencia](#12-simulacros-de-entrevistas-de-residencia)
13. [Lounge de Inglés Libre y Escenarios Personalizados](#13-lounge-de-inglés-libre-y-escenarios-personalizados)
14. [Análisis Detallado del Informe de Retroalimentación (7 Secciones)](#14-análisis-detallado-del-informe-de-retroalimentación-7-secciones)
15. [Tutor IA Socrático y Coach de Voz 1:1 Siempre Disponible](#15-tutor-ia-socrático-y-coach-de-voz-11-siempre-disponible)
16. [Rastreador de Errores por Repetición Espaciada y Genoma de Errores](#16-rastreador-de-errores-por-repetición-espaciada-y-genoma-de-errores)
17. [Presentación de Casos al Médico Adscrito (Attending Case Presentation Chaining)](#17-presentación-de-casos-al-médico-adscrito-attending-case-presentation-chaining)
18. [Importación de Transcripciones Externas (Import Transcript)](#18-importación-de-transcripciones-externas-import-transcript)
19. [Exportación a Anki y Documentos Word](#19-exportación-a-anki-y-documentos-word)
20. [Wiki de Ayuda Integrada en la App](#20-wiki-de-ayuda-integrada-en-la-app)
21. [Configuración de Idioma, Costes API y Preferencias](#21-configuración-de-idioma-costes-api-y-preferencias)
22. [Preguntas Frecuentes (FAQ) y Rúbrica de Evaluación](#22-preguntas-frecuentes-faq-y-rúbrica-de-evaluación)

---

## 1. Configuración de Claves API y Ajustes

Bedside English es compatible de forma flexible con los motores de Google Gemini, OpenAI y Anthropic Claude.

### 💡 Configuración Recomendada (Modo de Clave Única de Google Gemini)

**Registrar una sola clave API de Google Gemini habilita todas las funciones de la aplicación —desde conversaciones de voz en tiempo real hasta el análisis profundo de la retroalimentación posterior a la sesión— de la manera más rápida y económica.**

| Servicio               | Propósito Principal                                                                       | Requerido / Opcional                                | Enlace                                                 |
| :--------------------- | :---------------------------------------------------------------------------------------- | :-------------------------------------------------- | :----------------------------------------------------- |
| **Google (Gemini)**    | Conversación de voz en tiempo real (Gemini Live) + Análisis profundo de retroalimentación | **Requerido (Una clave cubre todas las funciones)** | [aistudio.google.com](https://aistudio.google.com)     |
| **OpenAI**             | Voz en tiempo real (OpenAI Realtime) + Retroalimentación + TTS Premium                    | Opcional                                            | [platform.openai.com](https://platform.openai.com)     |
| **Anthropic (Claude)** | Análisis de retroalimentación de la sesión (motor seleccionable)                          | Opcional (Gemini es el predeterminado)              | [console.anthropic.com](https://console.anthropic.com) |

### Introducción y Seguridad de Claves API

Las claves API se introducen directamente en la aplicación:

- Configúrelas durante el **Asistente de Configuración Inicial** o a través del menú **Ajustes (⚙️) → Preferences → API Keys** en la barra superior.
- Las claves introducidas se **almacenan de forma segura** en la memoria encriptada del dispositivo (`EncryptedSharedPreferences`) y nunca se envían a servidores externos.
- Cada campo de clave incluye el icono de un ojo a la derecha para mostrar u ocultar la clave.

### 🎈 Modo Demo (Prueba Totalmente Gratuita)

Si desea probar la aplicación sin registrar una clave API ni otorgar permisos de micrófono, seleccione el modo **Demo** en la pantalla inicial o en **Preferences**.
Se cargarán conversaciones simuladas y datos de retroalimentación, lo que le permitirá explorar la interfaz completa, el rastreador de errores y las funciones de repaso de forma **gratuita** sin consumir tokens. Al completar una sesión de prueba, un aviso le permitirá registrar una clave API o continuar con el siguiente paciente de práctica en cualquier momento.

---

## 2. Instalación de la App y Permisos

Bedside English funciona en teléfonos inteligentes y tabletas con Android 8.0 (Nivel de API 26) o superior.

### Instalación de la App

- Ejecute el archivo de instalación proporcionado (`.apk`) en su dispositivo Android y siga las instrucciones en pantalla para instalarlo.

### Permiso de Micrófono y Modo "Type instead" (Escribir en su lugar)

- Al iniciar un modo de práctica de voz en vivo por primera vez, el sistema operativo Android solicitará permiso de acceso al micrófono. Toque **[Permitir]** para que el reconocimiento de voz funcione correctamente.
- Si se encuentra en un entorno donde no puede hablar o si deniega el permiso, la aplicación no fallará. Cambiará automáticamente al modo **Type instead** (Escribir en su lugar), permitiéndole practicar las conversaciones mediante el teclado.

### Permiso de Notificaciones y Comprobación de Audio (Audio Preflight)

- Se solicita permiso de notificaciones para que pueda recibir avisos cuando finalice el análisis de retroalimentación en segundo plano.
- Justo antes de iniciar su primera sesión de voz en vivo, se mostrará la ventana de comprobación **Audio Preflight** para guiarle en el uso de los auriculares y probar los niveles de entrada del micrófono, previniendo así bucles de retroalimentación acústica (acople).

---

## 3. Configuración Inicial y Personalización L1

Al iniciar la aplicación por primera vez, se ejecutará un asistente de 4 pasos para configurar un entorno de aprendizaje personalizado:

1. **Pantalla de Bienvenida (Welcome)**: Presenta las funciones principales y ofrece el botón **Try Demo Mode** (Probar Modo Demo) para explorar la aplicación sin claves API.
2. **Selector de Idioma de la Interfaz (UI Language Picker)**: Seleccione el idioma de interfaz de su preferencia (soporta 8 idiomas: inglés, coreano, español, chino, árabe, hindi, portugués y tagalo).
3. **Configuración de Claves API (API Keys Setup)**: Registre sus claves API de Google Gemini u otras IA.
4. **Idioma Materno y Privacidad (Native Language & Privacy)**: Seleccione su lengua materna (por ejemplo, **Korean (Coreano)**, chino, español, árabe, hindi, tagalo, portugués). Esto activa correcciones precisas de gramática y pronunciación adaptadas a los patrones de interferencia específicos de su lengua materna.

### 🌐 Aspectos Destacados de la Personalización de Idioma Nativo L1 (p. ej., Estudiantes de L1 Coreano)

- **Correcciones de Gramática y Fraseo**:
  - Omisión de artículos (omitir _a/an/the_ antes de los sustantivos)
  - Omisión de la _-s_ en el plural (_two patient_ → _two patients_)
  - Errores en el tiempo verbal (uso del presente al hablar de antecedentes médicos pasados)
  - Uso incorrecto de las preposiciones (_in hospital_, omisión de preposición en _explain to patient_)
  - Traducciones literales / Konglish (_skin scale_, traducción literal incómoda de _side effect_)
- **Correcciones de Pronunciación e Inteligibilidad**:
  - Distinción de pares mínimos _r / l_ (_liver_ vs _river_)
  - Distinción _f / p_ (_fever_ vs _peter_)
  - Pronunciación de la fricativa dental _th_ (_think_ vs _tink_)
  - Omisión de consonantes finales e inserción de vocales innecesarias (_cardiac_ → _cardi-ack-eu_)
  - Acentuación incorrecta de palabras médicas (_angina_, _arrhythmia_)

### 💡 Recorrido Interactivo Inicial

Tras completar el proceso de configuración y acceder al Panel Principal por primera vez, un tutorial interactivo le mostrará automáticamente la ubicación y la función de los botones principales (Dashboard, Practice Hub, Pron Lab, SRS Reviews, History, Help, 1:1 Voice Coach).

---

## 4. Resumen de la Interfaz (5 Pestañas y Ayuda)

### Barra de Navegación Inferior (5 Pestañas)

La navegación principal consta de 5 pestañas inferiores:

```
┌───────────┬──────────────┬───────────────────────┬───────────────┬─────────────┐
│  🏠 Home  │  ▶ Practice  │  🎙️ Pronunciation Lab │  ⚠️ SRS Reviews│  🕘 History │
└───────────┴──────────────┴───────────────────────┴───────────────┴─────────────┘
```

1. **Home (Panel Principal)**: Racha de práctica (`🔥`), misión clínica de 5 minutos, gráficos de tendencias de rendimiento, Mistake Genome (Genoma de Errores), hoja de ruta (roadmap) y **Always-on 1:1 Voice Coach** (Coach de voz 1:1 siempre activo).
2. **Practice (Centro de Práctica)**: Centro principal para todos los modos de conversación en tiempo real: Patient Encounters, Exam & Diagnostic, Survival English, Teach-back, Interviews, Lounge, y Custom Scenarios.
3. **Pronunciation Lab (Lab de Pronunciación)**: Pestaña de entrenamiento dedicada a la inteligibilidad del habla y a la corrección de la pronunciación (filtrado de patrones de error, gestión del estado de observación).
4. **SRS Reviews (Repaso de Debilidades)**: Cuestionarios vocales de repaso basados en algoritmos de repetición espaciada para las oraciones de corrección aceptadas.
5. **History (Historial de Sesiones)**: Permite ver las puntuaciones y la retroalimentación de sesiones pasadas, llevar el control del gasto de tokens, exportar a Anki/Word y activar **Attending Case Presentation (Present Case)**.

### Barra Superior (Top App Bar)

- **Logotipo de Bedside English**: Título de la aplicación.
- **Wiki de Ayuda (Icono `?`)**: Al tocar el icono `?`, se abre esta Guía del Usuario en un visor a pantalla completa con índice de contenidos y búsqueda de texto completo por palabras clave.
- **Engranaje de Configuración (⚙️ Preferences)**: Claves API, motores de voz, velocidad del habla, prevención de eco, idioma y gestión de datos.

---

## 5. Dominando el Panel Principal (Inicio)

El Panel Principal (Dashboard) muestra visualmente su progreso en las habilidades de comunicación clínica en inglés a través de múltiples dimensiones:

- **Racha de Práctica (Practice Streak)**: Muestra los días de práctica activa consecutivos con un icono de llama (`🔥`) para fomentar el hábito de estudio diario.
- **Tarjetas de Métricas Principales (Core Metric Cards)**:
  - **Sessions done**: Número total de sesiones completadas y analizadas.
  - **Errors tracked**: Correcciones confirmadas que se han registrado en su base de datos personal de debilidades.
  - **Mastered**: Errores superados tras realizar cuestionarios de repaso repetidos.
  - **Due now**: Número de tarjetas SRS programadas para el repaso vocal de hoy.
  - **Stubborn**: Errores persistentes ("Leech") fallados 4 o más veces consecutivas que requieren atención especial.
- **Misión Clínica de 5 minutos de Hoy (Today's 5-Minute Clinical Mission)**: Recomienda automáticamente una sesión de práctica óptima de 5 minutos enfocada en las tareas pendientes de repaso, los diagnósticos requeridos o su área de menor rendimiento.
- **Cobertura de Vocabulario Coloquial OET (OET Layman Vocabulary Coverage)**:
  - Evalúa la eficacia con la que sustituye la jerga médica compleja (p. ej., _syncope_) por términos sencillos y comprensibles para el paciente (p. ej., _fainting_).
  - Los términos utilizados aparecen bajo **Recently Unlocked** (Desbloqueados recientemente), mientras que las expresiones no utilizadas quedan pendientes en **Next Goals (Locked)** (Próximos objetivos - Bloqueados).
- **Panel del Genoma de Errores (Mistake Genome Panel)**: Analiza sus categorías de error más frecuentes (Artículos, Plurales, Tiempos verbales, Preposiciones, Registro, Traducciones Directas) y muestra un gráfico de barras con sus 5 puntos débiles principales.
- **Tendencias de Crecimiento y Gráfico de Interferencia L1 (Growth Trends & L1 Interference Chart)**:
  - Muestra la evolución de sus puntuaciones en 5 dominios (Gramática, Precisión, Razonamiento, Profesionalismo, Fluidez) durante las últimas 20 sesiones.
  - Muestra de forma visual los patrones de errores gramaticales recurrentes.
- **Hoja de Ruta Personalizada (Personalized Roadmap)**: Analiza las métricas más bajas y el historial de errores para proponer 4 tarjetas de aprendizaje priorizadas.
- **Botón del Coach de Voz 1:1 Siempre Activo (`🎙️ RecordVoiceOver`)**:
  - Situado en la esquina inferior derecha del Panel Principal. Púlselo para iniciar al instante un diálogo vocal 1:1 con un tutor de IA adaptado a sus puntos débiles sin tener que iniciar un escenario completo.

---

## 6. Centro de Práctica y Desbloqueo Progresivo

### Desbloqueo Progresivo de Funciones

Para evitar abrumar a los nuevos usuarios, la aplicación comienza con una pantalla introductoria simplificada que muestra los modos principales (**Dashboard**, **Patient Encounters**, **Survival English**, **History**).

- **Al completar su primera sesión de práctica** se **desbloquearán** automáticamente los modos avanzados (Exam, Teach-back, Interview, Lounge, Custom) con un mensaje de felicitación.
- También puede tocar para desplegar y revelar todos los modos de inmediato desde la pantalla de Práctica (Practice).

### Categorías Principales del Centro de Práctica (Practice Hub)

1. **Encuentros con Pacientes (Patient Encounters)**: Toma de historia clínica, consultas de seguimiento, modo de Fundamentos (Foundations) para principiantes, Ejercicios de Habilidad (Skill Drills) y Práctica de Mis Errores (Practice My Mistakes).
2. **Examen y Diagnóstico (Exam & Diagnostic)**: Diagnóstico Base de 10 minutos (10-minute Baseline), simulacros OSCE, OET, Residency (Residencia) y pases de guardia (Ward Round).
3. **Inglés de Supervivencia y Laboratorio de Escucha (Survival English & Listening Lab)**: Situaciones imprevistas en el hospital, conversaciones informales rápidas, 15 perfiles de acentos nativos y ejercicios detallados en el Laboratorio de Escucha.
4. **Enseñanza Inversa de Conferencias (Lecture Teach-back)**: Entrenamiento basado en la técnica Feynman a partir de resúmenes de texto o de YouTube.
5. **Entrevistas de Residencia (Residency Interviews)**: Simulacros de entrevistas de Comportamiento (Behavioral), Clínicas (Clinical) y específicas para IMGs.
6. **Lounge de Inglés Libre (Free English Lounge)**: Debates sobre temas médicos, discusiones a partir de subtítulos de noticias y resolución de conflictos laborales.
7. **Escenarios Personalizados (Custom Scenarios)**: Permite crear prompts de IA y rúbricas de evaluación a medida.

---

## 7. Encuentros con Pacientes y Rastreador de Anamnesis

Simula la toma de historia clínica y la comunicación con el paciente, el pilar fundamental de la comunicación clínica.

### 7-1. Submodos Operativos

- **Modo Fundamentos (Foundations Mode)**: Elimina la carga del razonamiento clínico para los principiantes y se centra exclusivamente en **la gramática, el vocabulario clínico, la empatía (rapport) y la fluidez**.
- **Ejercicios de Habilidades (Skill Drills)**: Ejercicios de microcompetencias específicas (técnica de empatía NURSE, explicaciones en lenguaje sencillo, entrega del turno de noche e **Interpretación médica secuencial de Idioma Nativo → Inglés**).
- **Práctica de Mis Errores (Practice My Mistakes)**: Crea instantáneamente un cuestionario de diálogo vocal a partir de los errores pendientes en su base de datos.
- **Misión Diaria (Daily Mission)**: Un reto diario adaptativo de 5 minutos enfocado en sus debilidades actuales.

### 7-2. Rastreador de Anamnesis en Tiempo Real (Live History Coverage Tracker)

Un panel desplegable en tiempo real que va marcando los elementos de la historia clínica a medida que usted habla:

- Registra automáticamente los datos basándose en el contexto de la conversación con la IA.
- Controla el inicio/duración, las características del dolor, la irradiación, los factores agravantes/atenuantes, los síntomas asociados, ICE (Ideas, Inquietudes, Expectativas), los antecedentes médicos, la medicación, las alergias, el consumo de alcohol/tabaco, los antecedentes familiares, etc.
- Las preguntas de confirmación negativa como _"You don't smoke, do you?"_ ("Usted no fuma, ¿verdad?") se reconocen y registran correctamente.

### 7-3. Ayuda de Continuación Contextual (`💡 Help me continue`)

Si se queda atascado o no sabe qué más preguntar a mitad de la sesión, toque **💡 Help me continue** (Ayúdame a continuar) en la parte inferior de la pantalla.

- Analizará la última respuesta del paciente para sugerirle el objetivo de la siguiente pregunta lógica junto con una **oración de ejemplo en inglés lista para usar**.
- El Rastreador de Fases de la Entrevista, situado en la parte superior, muestra su progreso mediante símbolos:
  - `✓`: Evidencia suficiente detectada
  - `•`: Mención parcial detectada
  - `?`: Se ha pasado a una fase posterior sin verificar la fase anterior

---

## 8. Modo Examen y Diagnóstico (Línea Base de 10 minutos y CEFR)

Mide la competencia comunicativa en condiciones de examen inmersivas y con límite de tiempo:

### Diagnóstico de Inglés Clínico Base de 10 minutos (10-minute Baseline Clinical English Diagnostic)

- Comienza con la presentación del examinador, seguida de 4 tareas breves (explicar un diagnóstico, responder a preguntas de seguimiento, realizar un traspaso SBAR de 45 segundos y responder a una pregunta de entrevista de residencia).
- Asigna automáticamente su rendimiento a los niveles internacionales del **CEFR**:
  - **Puntuación >= 8.5**: **C1** (Comunicación clínica fluida y segura a nivel de médico especialista o adscrito)
  - **Puntuación >= 7.2**: **B2+** (Competente para prácticas clínicas y hospitalarias)
  - **Puntuación >= 6.0**: **B1-B2** (Capacidad de comunicación básica; se recomienda estudio estructurado)
  - **Puntuación < 6.0**: **A2-B1** (Se requiere formación fundamental en comunicación clínica)

### Escenarios de Simulacros de Examen

- **OSCE**: Toma de historia clínica de dolor torácico (Sr. Hayes) (evaluación de ICE, detección de banderas rojas, empatía).
- **OET Speaking**: Juego de roles sobre asesoramiento a un paciente con hipertensión.
- **Residencia**: Simulacro de entrevista con el Director de un Programa de Medicina Interna de EE. UU.
- **Pase de Guardia (Ward Round)**: Presentación oral de 5 minutos sobre un caso de neumonía adquirida en la comunidad y manejo de preguntas.

### Insignia de Confiabilidad de la Puntuación (Score Reliability Badge)

Indica el grado de confianza de la calificación de la IA como **High** (Alta), **Medium** (Media) o **Low** (Baja):

- **High**: Recuento de palabras del estudiante >= 180 palabras y tasa de evidencia de la lista de verificación >= 75%.
- **Medium**: Recuento de palabras del estudiante >= 80 palabras y tasa de evidencia de la lista de verificación >= 50%.
- **Low**: Recuento de palabras < 80 palabras (indicador de Transcripción Corta) o tasa de evidencia < 50%.

---

## 9. Laboratorio de Pronunciación e Inteligibilidad (Pron Lab)

Situado en la tercera pestaña inferior (`🎙️ Pron Lab`), este es su centro de entrenamiento especializado para la inteligibilidad del habla.

### 💡 Coaching Centrado en la Inteligibilidad

El objetivo no es imitar un acento nativo, sino asegurar: **"¿Pueden mis colegas y pacientes internacionales entender claramente lo que digo sin malentendidos?"**

- El análisis de audio proporciona correcciones específicas solo en los elementos de pronunciación que causan confusión al oyente.

### Categorías de Patrones de Error y Chips de Filtro

Los errores de pronunciación detectados en todas sus sesiones se organizan por categorías mediante filtros:

- `r · l`: _liver / river_, _clinical / critical_
- `f · p`: _fever / peter_, _palpation / falcation_
- `th`: _think / tink_, _throat / troat_
- `final`: Omisión de consonantes finales (_chest / ches_)
- `cluster`: Pronunciación de grupos consonánticos e inserción de vocales innecesarias (_cardiac_ → _cardi-ack-eu_)
- `stress`: Acentuación incorrecta de términos médicos (_angina_, _arrhythmia_)
- `vowel`: Confusión entre vocales cortas y largas (_ship / sheep_, _fit / feet_)

### Regla de Promoción del Estado Observado (Observed)

Cuando se registra por primera vez una tarjeta de pronunciación aceptada, pasa a un estado **Observed** (Observado) en lugar de convertirse de inmediato en una tarea de repaso diaria. Solo se convertirá en una tarjeta activa de repaso SRS si el mismo patrón de error se repite en una sesión distinta, lo que evita que los fallos puntuales del reconocimiento de voz generen una carga de trabajo excesiva.

---

## 10. Inglés de Supervivencia y Laboratorio de Escucha

Prepara a los IMGs para las interacciones no clínicas del mundo real que ocurren en el hospital fuera de la sala de consultas.

- **Modo Aleatorio / Sorpresa (Random / Surprise Mode)**: Respuesta espontánea ante situaciones imprevistas (preguntas de pasillo, llamadas de la farmacia, charlas informales con enfermeras) en las que el contexto del escenario se mantiene oculto hasta que la IA habla.
- **Charla Rápida (Rapid-fire Small Talk)**: Respuestas ágiles ante cambios bruscos de tema.
- **15 Perfiles de Acentos Nativos**: Práctica para adaptarse a diversos acentos y ritmos de habla de hablantes nativos internacionales.
- **Control de Velocidad de Audio en Tiempo Real (0.5× a 2.5×)**: Control deslizante de velocidad de reproducción que mantiene el tono de voz para adaptarse a hablantes nativos rápidos.
- **Modo Solo Escucha (Ear-only Listening Mode)**: Oculta los subtítulos de la conversación de la IA para que dependa exclusivamente de su oído, con la opción **[Reveal last line]** (Revelar última línea) disponible cuando lo necesite.
- **Recompensa por el Uso de Expresiones de Reparación**: El uso de estrategias de reparación como _"Sorry?", "Could you say that again?"_ (¿Disculpe?, ¿Podría repetirlo?) otorga **puntos extra** en lugar de penalizar.
- **Laboratorio de Escucha (Listening Lab)**: Ejercicios que evalúan la comprensión de detalles exactos (números, dosis de medicamentos, nombres de pacientes, horas, direcciones, precios) con una puntuación de precisión detallada.

---

## 11. Enseñanza Inversa de Conferencias (Técnica Feynman)

Utiliza la técnica Feynman (explicar conceptos en voz alta como si se los estuviera enseñando a otra persona) para afianzar los conocimientos médicos.

1. **Preparar el Material de la Clase**: Pegue sus notas de resumen o introduzca la URL de una conferencia médica de YouTube y toque **`🎬 Fetch YT transcript`**.
2. **Resumen de la IA (Condense)**: Para textos largos, toque **`✨ Condense`** y la IA comprimirá el texto en un esquema estructurado de 500 palabras.
3. **Seleccionar el Perfil de la Audiencia**:
   - **Oral Exam Professor** (Profesor de Examen Oral): Plantea preguntas clínicas incisivas y desafiantes del tipo "Por qué" y "Qué pasaría si".
   - **Confused Classmate** (Compañero Confundido): Pide explicaciones en lenguaje sencillo y sin jerga técnica.
   - **Friendly Tutor** (Tutor Amistoso): Le ofrece apoyo y le orienta a la hora de expresarse.
4. **Teach (Enseñar)**: Toque **Start** y comience a explicar a través del micrófono mientras el oyente de la IA interactúa haciéndole preguntas para aclarar dudas.

---

## 12. Simulacros de Entrevistas de Residencia

Simula entrevistas realistas para conseguir empleo en hospitales extranjeros y para el US Residency Match:

- **Comportamiento (Behavioral)**: Descripción de experiencias utilizando el método STAR (Situación, Tarea, Acción, Resultado).
- **Clínica (Clinical)**: Presentación oral de casos, ética médica y razonamiento en el manejo de emergencias.
- **Específico para IMGs**: Se centra en las preguntas habituales para los IMGs (patrocinio de visados, explicación de años en blanco en el currículum, puntos fuertes exclusivos como IMG).
- Un Director de Programa de IA formula preguntas de seguimiento educadas pero incisivas.

---

## 13. Lounge de Inglés Libre y Escenarios Personalizados

### Lounge de Inglés Libre (Free English Lounge)

- Debata sobre temas médicos de actualidad, resuma artículos científicos, resuelva conflictos laborales o con el personal de enfermería, o practique charlas informales durante la pausa del café.
- Importe subtítulos de noticias médicas de YouTube para debatir libremente con la IA.

### Escenarios Personalizados (Custom Scenarios)

Cree escenarios de práctica a medida según sus necesidades:

- **Scenario Name (Nombre del Escenario)**: Identificador personalizado.
- **Persona / Context (Persona / Contexto)**: Prompt del sistema que define el rol de la IA y la situación.
- **Eval Template (Plantilla de Evaluación)**: Seleccione las rúbricas de evaluación (p. ej., protocolo SPIKES para dar malas noticias).
- **Custom Eval Criteria (Criterios de Evaluación Personalizados)**: Establezca puntos clave específicos para que la IA los evalúe.

---

## 14. Análisis Detallado del Informe de Retroalimentación (7 Secciones)

Una vez finalizada la sesión, un informe de 7 secciones le ofrecerá un análisis multidimensional:

1. **Scores (Puntuaciones)**: Compara las puntuaciones otorgadas por la IA (de 0 a 10) con su propia autoevaluación. Una diferencia de **2.0 puntos o más** activará una tarjeta amarilla **Reflection Prompt** (Aviso de Reflexión) para fomentar la autoevaluación.
2. **Fluency (Fluidez)**:
   - **WPM (Words Per Minute)**: Compara su velocidad de habla con los objetivos recomendados (100–130 WPM).
   - **Filler Words (Muletillas)**: Mide la frecuencia de uso de muletillas (`um`, `uh`, `like`), y fomenta el uso eficaz de las pausas.
3. **Checklist (Lista de Verificación)**: Evalúa el cumplimiento de los objetivos clínicos citando frases exactas de la transcripción como **Evidence** (Evidencia).
4. **Comparación de Nota SOAP (SOAP Note Comparison)**: Compara la nota SOAP generada automáticamente a partir de su sesión con una nota SOAP modelo de referencia.
5. **Corrections (Correcciones)**: Tarjetas de corrección para traducciones literales, uso de artículos/plurales, expresiones poco naturales y pronunciación. Al pulsar **[Accept]** el elemento se registrará en su sistema personal de repaso de errores (SRS).
6. **Shadowing (Sombreado)**: Reescribe las frases más débiles en un inglés clínico propio de un médico especialista para que pueda escucharlas y repetirlas como entrenamiento de audio.
7. **Summary & Share Cards (Resumen y Tarjetas para Compartir)**: Muestra una valoración general y ofrece un generador de imágenes **Share Card** (Tarjeta para Compartir) para que pueda compartir el resumen de su rendimiento con sus compañeros de estudio.

---

## 15. Tutor IA Socrático y Coach de Voz 1:1 Siempre Disponible

### 15-1. Análisis 1:1 con el Tutor IA Socrático (`[Debrief with AI Tutor]`)

Al tocar **[Debrief with AI Tutor]** en la parte inferior del informe de retroalimentación se abrirá una sala de chat 1:1 con un mentor socrático de IA.

- En lugar de darle las respuestas directamente, le hará preguntas orientativas para ayudarle a descubrir y corregir los errores por sí mismo.
- El historial de la conversación se guardará en la base de datos para que pueda retomarla en cualquier momento.

### 15-2. Coach de Voz 1:1 Siempre Disponible (Botón Flotante de Inicio)

Si toca el **Botón Flotante del Coach de Voz (`🎙️ RecordVoiceOver`)** situado en la parte inferior derecha del Panel Principal (Dashboard), se abrirá al instante un diálogo con el tutor de voz sin necesidad de completar antes un escenario.

- El coach de IA dirige conversaciones de voz personalizadas 1:1 basadas en los puntos débiles que haya acumulado en el SRS.

---

## 16. Rastreador de Errores por Repetición Espaciada y Genoma de Errores

Las correcciones que acepte mediante el botón **[Accept]** se gestionarán automáticamente mediante algoritmos de repetición espaciada en su base de datos de errores.

- **Detección Difusa de Duplicados (Fuzzy Duplicate Detection)**: Evita automáticamente que se registren por duplicado errores similares.
- **Errores Persistentes (Leech / Stubborn)**: Los elementos que falle o se salte 4 o más veces consecutivas en las pruebas de repaso se etiquetarán como **Stubborn / Leech** para que pueda prestarles especial atención.
- **Intervalos Científicos de Repetición Espaciada**:
  - Calendario de repasos: **1 día → 3 días → 7 días → 14 días → 30 días**. Si supera 3 repasos consecutivos, el elemento pasará a la categoría **Mastered** (Dominado).
  - Al completar los cuestionarios vocales en **Practice My Mistakes**, los elementos avanzarán hacia el nivel Dominado (Mastery).
- **Panel Mistake Genome (Genoma de Errores)**:
  - Muestra sus 5 categorías de errores más frecuentes (Artículos, Plurales, Tiempos verbales, Preposiciones, Registro, Traducciones literales) en forma de gráfico de barras en el Panel Principal (Dashboard) con explicaciones emergentes (tooltips) sencillas.

---

## 17. Presentación de Casos al Médico Adscrito (Attending Case Presentation Chaining)

Practique sus habilidades de presentación oral exponiendo casos a un supervisor después de un Encuentro con el Paciente (Patient Encounter):

1. Complete una sesión de **Patient Encounter**.
2. Vaya a la pestaña **History**, seleccione la sesión y toque **`📋 Present Case`**.
3. El Médico Adscrito (Attending) de la IA iniciará la conversación: _"Doctor, please present the case you just saw."_ ("Doctor, por favor, presente el caso que acaba de ver").
4. Realice una presentación oral del caso utilizando el formato SBAR o SOAP, y responda a las preguntas de seguimiento sobre el diagnóstico diferencial y los planes de tratamiento.

---

## 18. Importación de Transcripciones Externas (Import Transcript)

Importe a la aplicación textos de conversaciones de ChatGPT, Gemini o notas clínicas para recibir un análisis completo:

1. **Integración con la Función Compartir de Android (Android Share Integration)**: Seleccione el texto de la conversación en una aplicación externa y pulse **[Compartir] → [Bedside English]** para abrir automáticamente la pantalla **Import Transcript**.
2. **Entrada Directa / Pegar (Direct Entry / Paste)**: Abra la pantalla `Import Transcript` directamente desde Preferences o desde el menú principal y pegue el texto.
3. **Análisis Automático y SRS**: Genera puntuaciones por dominios, notas SOAP y tarjetas de corrección que podrá añadir a su sistema SRS.

---

## 19. Exportación a Anki y Documentos Word

- **Exportación a Tarjetas de Anki (`.txt` separado por tabulaciones)**:
  - Convierte las correcciones aceptadas en un archivo de texto plano importable en Anki (Archivo → Importar en Anki / AnkiDroid). La categoría de cada error se conserva como etiqueta. Está disponible tanto en la pantalla de retroalimentación tras la sesión como en **My Mistakes**, lo que le permite exportar toda su lista de repaso de una sola vez.
- **Exportación a Informes de Word (`.docx`)**:
  - Genera informes médicos estructurados que contienen las puntuaciones, la evidencia de la lista de verificación, las notas SOAP y las correcciones de frases. Puede activar el guardado automático desde Preferences.

---

## 20. Wiki de Ayuda Integrada en la App

Al tocar el icono de **Ayuda `?`** en la barra superior de la aplicación, se abrirá el visor a pantalla completa de la Wiki de Ayuda integrada.

- **Integración Multilingüe**: Carga automáticamente el archivo de la guía del usuario que corresponda con el idioma de interfaz (UI) de la aplicación.
- **Barra Lateral del Índice de Contenidos (TOC)**: Permite saltar rápidamente a cualquier sección de la guía.
- **Búsqueda de Texto Completo (Full-Text Search)**: Introduzca palabras clave en la barra de búsqueda para resaltar las coincidencias y utilice los botones anterior/siguiente para navegar por ellas.
- **Hipervínculos Externos**: Al tocar los enlaces web de la guía, se abrirán en el navegador predeterminado del sistema.

---

## 21. Configuración de Idioma, Costes API y Preferencias

Acceda al **Engranaje de Configuración (⚙️)** en la barra superior de la aplicación para adaptar la app a su dispositivo y a su presupuesto:

- **Voz y Retroalimentación (Voice & Feedback)**:
  - Seleccione los modelos de IA de voz y retroalimentación (Demo, Gemini, OpenAI, Claude).
  - **Prevención de Eco (Echo Prevention)**: Silencia automáticamente el micrófono mientras la IA habla para evitar bucles de retroalimentación de audio (imprescindible cuando no se usan auriculares).
  - **Velocidad del Habla de la IA (AI Speaking Pace)**: Control gradual de la velocidad (Slow, Normal, Fast, Challenge).
- **Análisis de Costes de la API (API Cost Analytics)**: Permite hacer un seguimiento transparente del uso de tokens y del coste estimado en dólares por sesión mediante gráficos visuales.
- **Audio**: Indicador del nivel del micrófono en tiempo real y tono de prueba del altavoz.
- **Claves API (API Keys)**: Gestor local de claves en memoria encriptada y segura.
- **Exportación y Aprendizaje (Export & Learning)**: Opciones de guardado automático (auto-save) en formato Docx, requisito obligatorio de nota SOAP, configuración del Idioma Materno y del Idioma de la Interfaz (UI Language).
- **Datos (Data)**: Permite activar o desactivar el análisis de pronunciación, configurar los motores y seleccionar los motores de sombreado (shadowing) TTS.
- **Privacidad (Privacy)**: Permite activar o desactivar la telemetría de uso anónima.

---

## 22. Preguntas Frecuentes (FAQ) y Rúbrica de Evaluación

### Preguntas Frecuentes (FAQ)

**P: El micrófono no detecta el audio y la IA no responde.**

- Vaya a **Ajustes (Settings) de Android → Aplicaciones → Bedside English → Permisos (Permissions) → Micrófono (Microphone)** y configúrelo en [Permitir]. Si se deniega el permiso, la aplicación cambiará al modo **Type instead** (Escribir en su lugar) para que pueda seguir practicando con el teclado.

**P: No aparecen los modos de práctica avanzados (Exam, Teach-back, Interview, Lounge, Custom).**

- Al completar su primera sesión de práctica, todos los modos avanzados se desbloquearán automáticamente y aparecerá un mensaje de felicitación. También puede desplegar y revelar todos los modos desde la pantalla de Práctica (Practice).

**P: Los resultados de los análisis de pronunciación no se añaden inmediatamente a mi base de datos de repaso SRS.**

- Para evitar que los fallos puntuales del reconocimiento de voz sobrecarguen a los estudiantes, los elementos de pronunciación aceptados pasan primero a un estado **Observed** (Observado). Solo se convertirán en tarjetas activas de repaso SRS si el mismo patrón de error se repite en una sesión posterior.

**P: ¿Puedo importar transcripciones de conversaciones externas (por ejemplo, de ChatGPT) para que me las evalúen?**

- Sí. Utilice la función de compartir (Share) de Android para enviar el texto a Bedside English o pegue el texto en la pantalla `Import Transcript` para recibir un análisis completo, notas SOAP y tarjetas de corrección.

---

### 📝 Rúbrica Detallada de Evaluación (Escala de Puntuación de 0 a 10)

| Puntuación | Nivel de Clasificación                       | Criterios de Evaluación                                                                                                                                                       |
| :--------- | :------------------------------------------- | :---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **9 ~ 10** | **Attending / Expert** (Adscrito / Experto)  | Gramática y expresión impecables; vocabulario clínico preciso y razonamiento médico sistemático; tono natural y profesional.                                                  |
| **7 ~ 8**  | **Competent / Pass** (Competente / Aprobado) | Errores gramaticales menores, pero con una comunicación totalmente clara; identifica los principales factores de riesgo y realiza el diagnóstico diferencial de forma segura. |
| **5 ~ 6**  | **Developing** (En Desarrollo)               | Errores estructurales y gramaticales frecuentes que exigen un esfuerzo por parte del oyente; razonamiento clínico y vocabulario asistemáticos.                                |
| **1 ~ 4**  | **Critical / Fail** (Crítico / Suspenso)     | Errores médicos graves o factores de riesgo omitidos; el habla se limita a palabras sueltas o presenta pausas largas y frecuentes que impiden una conversación normal.        |
