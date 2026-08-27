# Bedside English Guia do Usuário

**Público-alvo:** Graduados Médicos Internacionais (IMGs), médicos residentes, estudantes de medicina e profissionais de saúde que se preparam para inglês clínico, OSCE, OET Speaking, entrevistas de residência médica nos EUA, apresentações de pases de plantão/rondas (Ward Rounds) e comunicação clínica.

O Bedside English é um aplicativo exclusivo para Android alimentado por IA conversacional (Google Gemini, OpenAI Realtime, Anthropic Claude) e tecnologia de voz em tempo real. Foi projetado para treinar habilidades de comunicação clínica, raciocínio médico, pronúncia e inteligibilidade da fala em múltiplas dimensões. Este guia detalha todos os recursos e o uso da versão mais recente do aplicativo projetada para os usuários.

---

## 📌 Sumário

1. [Configuração de Chaves API e Configuração](#1-configuração-de-chaves-api-e-configuração)
2. [Instalação do App e Configuração de Permissões](#2-instalação-do-app-e-configuração-de-permissões)
3. [Integração Inicial e Personalização para Alunos L1](#3-integração-inicial-e-personalização-para-alunos-l1)
4. [Visão Geral do Layout da Interface (5 Abas de Navegação Inferior e Ajuda)](#4-visão-geral-do-layout-da-interface-5-abas-de-navegação-inferior-e-ajuda)
5. [Dominando o Painel Principal (Início)](#5-dominando-o-painel-principal-início)
6. [Central de Prática e Desbloqueio Progressivo de Recursos](#6-central-de-prática-e-desbloqueio-progressivo-de-recursos)
7. [Consultas de Pacientes e Rastreador de Cobertura de Histórico ao Vivo](#7-consultas-de-pacientes-e-rastreador-de-cobertura-de-histórico-ao-vivo)
8. [Modo de Exame e Diagnóstico (Linha de Base de 10 minutos e Mapeamento CEFR)](#8-modo-de-exame-e-diagnóstico-linha-de-base-de-10-minutos-e-mapeamento-cefr)
9. [Laboratório de Pronúncia e Inteligibilidade (Pron Lab)](#9-laboratório-de-pronúncia-e-inteligibilidade-pron-lab)
10. [Inglês de Sobrevivência e Laboratório de Audição](#10-inglês-de-sobrevivência-e-laboratório-de-audição)
11. [Ensino Inverso de Palestras (Técnica Feynman)](#11-ensino-inverso-de-palestras-técnica-feynman)
12. [Simulações de Entrevista de Residência](#12-simulações-de-entrevista-de-residência)
13. [Lounge de Inglês Livre e Cenários Personalizados](#13-lounge-de-inglês-livre-e-cenários-personalizados)
14. [Desconstruindo o Relatório de Feedback (7 Seções Principais)](#14-desconstruindo-o-relatório-de-feedback-7-seções-principais)
15. [Tutor de IA Socrático e Coach de Voz 1:1 Sempre Ativo](#15-tutor-de-ia-socrático-e-coach-de-voz-11-sempre-ativo)
16. [Rastreador de Erros por Repetição Espaçada e Genoma de Erros](#16-rastreador-de-erros-por-repetição-espaçada-e-genoma-de-erros)
17. [Apresentação de Casos Atendidos em Cadeia](#17-apresentação-de-casos-atendidos-em-cadeia)
18. [Importação de Histórico de Conversas Externas (Import Transcript)](#18-importação-de-histórico-de-conversas-externas-import-transcript)
19. [Decks Anki e Exportações de Documentos Word](#19-decks-anki-e-exportações-de-documentos-word)
20. [Wiki de Ajuda no Aplicativo](#20-wiki-de-ajuda-no-aplicativo)
21. [Configurações de Idioma da Interface, Rastreamento de Custos de API e Preferências](#21-configurações-de-idioma-da-interface-rastreamento-de-custos-de-api-e-preferências)
22. [Perguntas Frequentes (FAQ) e Rubrica de Avaliação](#22-perguntas-frequentes-faq-e-rubrica-de-avaliação)

---

## 1. Configuração de Chaves API e Configuração

O Bedside English suporta de forma flexível os backends Google Gemini, OpenAI e Anthropic Claude.

### 💡 Configuração Recomendada (Modo de Chave Única Google Gemini)

**Registrar uma única chave API do Google Gemini ativa todos os recursos do aplicativo—desde conversas de voz em tempo real até análise profunda de feedback pós-sessão—da maneira mais rápida e econômica.**

| Serviço                | Propósito Principal                                                        | Obrigatório / Opcional                                  | Link                                                   |
| :--------------------- | :------------------------------------------------------------------------- | :------------------------------------------------------ | :----------------------------------------------------- |
| **Google (Gemini)**    | Conversa de voz em tempo real (Gemini Live) + Análise profunda de feedback | **Obrigatório (A chave única cobre todos os recursos)** | [aistudio.google.com](https://aistudio.google.com)     |
| **OpenAI**             | Voz em tempo real (OpenAI Realtime) + Feedback + TTS Premium               | Opcional                                                | [platform.openai.com](https://platform.openai.com)     |
| **Anthropic (Claude)** | Análise de feedback da sessão (backend selecionável)                       | Opcional (Gemini é o padrão)                            | [console.anthropic.com](https://console.anthropic.com) |

### Entrada de Chave API e Segurança

As chaves API são inseridas diretamente no aplicativo:

- Configure durante o **Assistente de Integração de Primeira Execução** ou através do menu **Engrenagem de Configurações (⚙️) → Preferences → API Keys** na barra superior.
- As chaves inseridas são **armazenadas com segurança** em um armazenamento criptografado no dispositivo (`EncryptedSharedPreferences`) e nunca são enviadas a servidores externos.
- Cada campo de chave inclui um ícone de olho à direita para alternar a visibilidade da chave.

### 🎈 Modo Demo (Teste Completamente Gratuito)

Se você deseja experimentar o aplicativo sem registrar uma chave API ou conceder permissões de microfone, selecione o modo **Demo** na tela de integração ou em **Preferences**.
Conversas simuladas com roteiro e dados de feedback serão carregados, permitindo que você explore toda a interface do usuário, rastreador de erros e recursos de revisão **gratuitamente** sem consumir nenhum token. Após concluir uma sessão de demonstração, um aviso permite que você insira uma chave API ou continue com a próxima prática de paciente de demonstração a qualquer momento.

---

## 2. Instalação do App e Configuração de Permissões

O Bedside English é executado em smartphones e tablets com Android 8.0 (API Nível 26) ou superior.

### Instalação do App

- Inicie o arquivo de instalação fornecido (`.apk`) no seu dispositivo Android e siga as instruções na tela para instalar.

### Permissão de Microfone e Modo Digite-em-vez-disso

- Ao iniciar um modo de prática de voz ao vivo pela primeira vez, o sistema operacional Android solicita permissão de acesso ao microfone. Toque em **[Permitir]** para que o reconhecimento de voz funcione corretamente.
- Se você estiver em um ambiente onde falar é difícil ou se a permissão for negada, o aplicativo não travará. Ele muda automaticamente para o modo **Type instead**, permitindo que você pratique conversas usando a entrada do teclado.

### Permissão de Notificações e Verificação Pré-voo de Áudio

- A permissão de notificação é solicitada para que você possa receber notificações quando a análise de feedback em segundo plano for concluída.
- Imediatamente antes de iniciar sua primeira sessão de voz ao vivo, um modal de verificação **Audio Preflight** é exibido para orientar o uso de fones de ouvido e testar os níveis de entrada do microfone, evitando loops de feedback do alto-falante (microfonia).

---

## 3. Integração Inicial e Personalização para Alunos L1

Ao iniciar o aplicativo pela primeira vez, um assistente de configuração de 4 etapas é executado para criar um ambiente de aprendizado personalizado:

1. **Tela de Boas-vindas**: Apresenta os principais recursos e fornece um botão **Try Demo Mode** para explorar sem chaves API.
2. **Seletor de Idioma da Interface**: Selecione o idioma de interface de sua preferência (8 idiomas suportados: Inglês, Coreano, Espanhol, Chinês, Árabe, Hindi, Português, Tagalog).
3. **Configuração de Chaves API**: Registre suas chaves API do Google Gemini ou de outra IA.
4. **Idioma Nativo e Privacidade**: Selecione seu primeiro idioma (por exemplo, **Korean**, Chinês, Espanhol, Árabe, Hindi, Tagalog, Português). Isso ativa análises precisas de gramática e pronúncia adaptadas aos padrões específicos de interferência do seu idioma nativo.

### 🌐 Destaques da Personalização do Idioma Nativo L1 (por exemplo, Alunos Coreanos L1)

- **Correções de Gramática e Fraseado**:
  - Artigos ausentes (omitindo _a/an/the_ antes de substantivos)
  - Falta do plural _-s_ (_two patient_ → _two patients_)
  - Erros de tempo verbal (usar o tempo presente ao discutir o histórico médico passado)
  - Mau uso de preposições (_in hospital_, omitindo preposições em _explain to patient_)
  - Traduções diretas literais / Konglish (_skin scale_, tradução direta estranha de _side effect_)
- **Correções de Pronúncia e Inteligibilidade**:
  - Distinção de pares mínimos de _r / l_ (_liver_ vs _river_)
  - Distinção de _f / p_ (_fever_ vs _peter_)
  - Pronúncia da fricativa dental _th_ (_think_ vs _tink_)
  - Consoantes finais omitidas e inserção desnecessária de vogais (_cardiac_ → _cardi-ack-eu_)
  - Má colocação do estresse da palavra médica (_angina_, _arrhythmia_)

### 💡 Tour Interativo de Primeira Execução

Após concluir a integração e entrar no Dashboard pela primeira vez, um tutorial interativo orienta você automaticamente pelos locais e funções dos principais botões (Dashboard, Practice Hub, Pron Lab, SRS Reviews, History, Help, 1:1 Voice Coach).

---

## 4. Visão Geral do Layout da Interface (5 Abas de Navegação Inferior e Ajuda)

### Barra de Navegação Inferior (5 Abas)

A navegação principal consiste em 5 guias inferiores:

```
┌───────────┬──────────────┬───────────────────────┬───────────────┬─────────────┐
│  🏠 Home  │  ▶ Practice  │  🎙️ Pronunciation Lab │  ⚠️ SRS Reviews│  🕘 History │
└───────────┴──────────────┴───────────────────────┴───────────────┴─────────────┘
```

1. **Home (Dashboard)**: Sequência de prática (`🔥`), missão clínica de 5 minutos, gráficos de tendência de desempenho, Genoma de Erros, roteiro e **Coach de Voz 1:1 Sempre Ativo**.
2. **Practice (Practice Hub)**: Hub central para todos os modos de conversação em tempo real: Consultas com Pacientes, Exame e Diagnóstico, Inglês de Sobrevivência, Ensino Inverso, Entrevistas, Lounge e Cenários Personalizados.
3. **Pronunciation Lab (Pron Lab)**: Aba de treinamento dedicada à inteligibilidade da fala e correção de pronúncia (filtragem de padrão de erro, gerenciamento de status de observação).
4. **SRS Reviews (Weakness Review)**: Quizzes vocais de revisão baseados em algoritmos de repetição espaçada para frases de correção aceitas.
5. **History (Session History)**: Visualize pontuações e feedback de sessões anteriores, acompanhe os custos de tokens, exporte para Anki/Word e ative a **Apresentação de Casos Atendidos (Present Case)**.

### Barra de Aplicativos Superior

- **Logotipo Bedside English**: Título principal.
- **Wiki de Ajuda (Ícone `?`)**: Tocar no ícone `?` abre este Guia do Usuário em um visualizador de tela cheia com navegação no Sumário e pesquisa de palavras-chave em texto completo.
- **Engrenagem de Configurações (⚙️ Preferences)**: Chaves API, backends de voz, ritmo de fala, prevenção de eco, idioma e gerenciamento de dados.

---

## 5. Dominando o Painel Principal (Início)

O Dashboard principal apresenta visualmente o seu crescimento em habilidades de comunicação clínica em inglês em várias dimensões:

- **Sequência de Prática**: Exibe dias consecutivos de prática ativa com um ícone de chama (`🔥`) para construir hábitos de estudo diários.
- **Cartões de Métricas Principais**:
  - **Sessions done**: Número total de sessões totalmente concluídas e analisadas.
  - **Errors tracked**: Correções confirmadas registradas em seu banco de dados pessoal de fraquezas.
  - **Mastered**: Erros resolvidos e graduados através de questionários de revisão repetidos.
  - **Due now**: Número de cartões de revisão SRS agendados para revisão vocal hoje.
  - **Stubborn**: Erros "sanguessugas" (_Leech_) que escaparam 4+ vezes consecutivas exigindo atenção focada.
- **Missão Clínica de 5 Minutos de Hoje**: Recomenda automaticamente um curso de prática ideal de 5 minutos, visando itens de revisão devidos, diagnósticos necessários ou seu domínio de habilidade mais fraco.
- **Cobertura de Vocabulário Leigo OET**:
  - Acompanha a eficácia com que você substitui o jargão médico complexo (por exemplo, _syncope_) por termos simples e compreensíveis para o paciente (por exemplo, _fainting_).
  - Os termos usados aparecem em **Recently Unlocked**, enquanto as expressões não utilizadas ficam na fila em **Next Goals (Locked)**.
- **Painel Genoma de Erros**: Analisa suas categorias de erros mais frequentes (Artigos, Plurais, Tempos, Preposições, Registro, Traduções Diretas) e exibe suas 5 principais áreas fracas como um gráfico de barras.
- **Tendências de Crescimento e Gráfico de Interferência L1**:
  - Representa graficamente as tendências de pontuação em 5 domínios (Gramática, Precisão, Raciocínio, Profissionalismo, Fluência) nas suas últimas 20 sessões.
  - Visualiza padrões recorrentes de erros gramaticais.
- **Roteiro Personalizado**: Analisa métricas fracas e histórico de erros para apresentar 4 cartões de foco de habilidades priorizadas.
- **Botão Coach de Voz 1:1 Sempre Ativo (`🎙️ RecordVoiceOver`)**:
  - Localizado no canto inferior direito do Dashboard. Toque para abrir instantaneamente um diálogo de fala vocal 1:1 com um tutor de IA baseado no seu perfil de fraqueza pessoal sem iniciar um cenário de sessão completa.

---

## 6. Central de Prática e Desbloqueio Progressivo de Recursos

### Desbloqueio Progressivo de Recursos

Para evitar que novos usuários se sintam sobrecarregados, usuários iniciantes começam com uma tela de introdução calma exibindo os modos principais (**Dashboard**, **Patient Encounters**, **Survival English**, **History**).

- **Concluir sua primeira sessão de prática** automaticamente **desbloqueia** modos avançados (Exam, Teach-back, Interview, Lounge, Custom) com uma mensagem de comemoração.
- Você também pode tocar para expandir e revelar todos os modos imediatamente na tela Practice.

### Categorias do Modo Principal do Hub de Prática

1. **Patient Encounters**: Obtenção de histórico, acompanhamento, modo iniciante Foundations, Skill Drills, Practice My Mistakes.
2. **Exam & Diagnostic**: Diagnóstico de linha de base de 10 minutos, exames simulados da OSCE, OET, Residency e Ward Round.
3. **Survival English & Listening Lab**: Situações inesperadas no hospital, bate-papo rápido, 15 perfis de sotaque nativo, exercícios de detalhes no Listening Lab.
4. **Lecture Teach-back**: Treinamento da técnica de Feynman baseado em resumos do YouTube/texto.
5. **Residency Interviews**: Entrevistas simuladas Comportamentais, Clínicas e específicas para IMG.
6. **Free English Lounge**: Debates médicos, discussões de legendas de notícias, resolução de conflitos no local de trabalho.
7. **Custom Scenarios**: Crie prompts personalizados de IA e rubricas de pontuação.

---

## 7. Consultas de Pacientes e Rastreador de Cobertura de Histórico ao Vivo

Simula a obtenção do histórico à beira do leito e o aconselhamento do paciente — o núcleo da comunicação clínica.

### 7-1. Submodos Operacionais

- **Modo Foundations**: Remove os fardos do raciocínio clínico para alunos em estágio inicial, concentrando-se estritamente na **gramática, vocabulário clínico, construção de rapport e fluência**.
- **Skill Drills**: Exercícios de microcompetência direcionados (técnica de empatia NURSE, explicações em linguagem simples, passagem de turno noturno, **Interpretação médica sequencial de Idioma Nativo → Inglês**).
- **Practice My Mistakes**: Sintetiza um teste de diálogo vocal instantâneo a partir de erros pendentes em seu banco de dados.
- **Daily Mission**: Um desafio diário adaptativo de 5 minutos que visa suas lacunas de habilidade atuais.

### 7-2. Rastreador de Cobertura de Histórico ao Vivo

Um painel recolhível em tempo real que marca itens de obtenção de histórico conforme você fala:

- Rastreia itens automaticamente com base no contexto da conversa da IA.
- Monitora início/duração, caráter da dor, irradiação, fatores agravantes/de alívio, sintomas associados, ICE (Ideias, Preocupações, Expectativas), histórico passado, medicamentos, alergias, álcool/tabagismo, histórico familiar, etc.
- Perguntas de confirmação negativa como _"You don't smoke, do you?"_ são corretamente reconhecidas e rastreadas.

### 7-3. Ajuda de Continuação Consciente do Contexto (`💡 Help me continue`)

Se você ficar preso ou ficar sem perguntas no meio da sessão, toque em **💡 Help me continue** na parte inferior da tela.

- Analisa a resposta mais recente do paciente para sugerir o próximo objetivo lógico da pergunta, juntamente com uma **frase de exemplo em inglês pronta para uso**.
- O Rastreador da Fase da Entrevista superior exibe o progresso usando símbolos de status:
  - `✓`: Evidência suficiente detectada
  - `•`: Menção parcial detectada
  - `?`: Fase posterior atingida sem verificação da fase anterior

---

## 8. Modo de Exame e Diagnóstico (Linha de Base de 10 minutos e Mapeamento CEFR)

Mede a proficiência em comunicação sob condições de exame imersivas e cronometradas:

### Diagnóstico de Inglês Clínico de Linha de Base de 10 Minutos

- Começa com a introdução de um examinador, seguida de 4 tarefas curtas (explicar um diagnóstico, lidar com perguntas de acompanhamento, fornecer uma transferência SBAR de 45 segundos, responder a uma pergunta de entrevista de residência).
- Mapeia automaticamente o seu desempenho para as **séries CEFR** internacionais:
  - **Pontuação >= 8.5**: **C1** (Comunicação clínica segura e fluente ao nível do médico assistente)
  - **Pontuação >= 7.2**: **B2+** (Competente para estágio clínico e prática hospitalar)
  - **Pontuação >= 6.0**: **B1-B2** (Capacidade de comunicação básica; recomenda-se estudo estruturado)
  - **Pontuação < 6.0**: **A2-B1** (É necessário treinamento básico em comunicação clínica)

### Cenários de Exame Simulado

- **OSCE**: Obtenção do histórico de dor no peito de Mr. Hayes (medindo ICE, detecção de bandeira vermelha, empatia).
- **OET Speaking**: Interpretação de aconselhamento a paciente com hipertensão.
- **Residency**: Entrevista simulada com Diretor de Programa de Medicina Interna dos EUA.
- **Ward Round**: Apresentação de caso de pneumonia adquirida na comunidade de 5 minutos e tratamento de questões orais.

### Distintivo de Confiabilidade da Pontuação

Indica a confiança da IA na classificação como **High**, **Medium** ou **Low**:

- **High**: Contagem de palavras do aluno >= 180 palavras e taxa de evidência da lista de verificação >= 75%.
- **Medium**: Contagem de palavras do aluno >= 80 palavras e taxa de evidência da lista de verificação >= 50%.
- **Low**: Contagem de palavras < 80 palavras (Sinalizador Short Transcript) ou taxa de evidência < 50%.

---

## 9. Laboratório de Pronúncia e Inteligibilidade (Pron Lab)

Localizado na terceira aba inferior dedicada (`🎙️ Pron Lab`), este é o seu centro de treinamento especializado em inteligibilidade da fala.

### 💡 Treinamento Centrado na Inteligibilidade

O objetivo não é a imitação do sotaque nativo, mas **"Colegas e pacientes internacionais conseguem entender minha fala claramente sem mal-entendidos?"**

- A análise de áudio fornece treinamento pontual apenas em itens de pronúncia que causam mal-entendidos no ouvinte.

### Categorias de Padrão de Erro e Chips de Filtro

Erros de pronúncia detectados ao longo de suas sessões são organizados por categoria em chips de filtro:

- `r · l`: _liver / river_, _clinical / critical_
- `f · p`: _fever / peter_, _palpation / falcation_
- `th`: _think / tink_, _throat / troat_
- `final`: Consoantes finais omitidas (_chest / ches_)
- `cluster`: Processamento de grupo consonantal e inserção de vogal desnecessária (_cardiac_ → _cardi-ack-eu_)
- `stress`: Erro de acentuação médica da palavra (_angina_, _arrhythmia_)
- `vowel`: Confusão entre vogal curta e longa (_ship / sheep_, _fit / feet_)

### Regra de Promoção de Estado Observado

Quando um cartão de pronúncia aceito é registrado pela primeira vez, ele entra em um estado **Observed** em vez de se tornar imediatamente um cartão de dever de casa diário. Ele é promovido a um cartão de erro de revisão SRS ativo apenas quando o mesmo padrão de erro se repete em uma sessão separada, garantindo que falhas de reconhecimento de fala de ocorrência única não criem deveres de casa excessivos.

---

## 10. Inglês de Sobrevivência e Laboratório de Audição

Prepara o IMG para interações hospitalares do mundo real e não clínicas fora da sala de exame.

- **Modo Random / Surprise**: Tratamento espontâneo de situações inesperadas (perguntas de calçada no corredor, retornos de chamada da farmácia, conversa fiada de enfermeira) com o contexto do cenário oculto até que a IA fale.
- **Rapid-fire Small Talk**: Respostas rápidas a mudanças repentinas de assunto.
- **15 Perfis de Sotaque Nativo**: Pratique a adaptação a sotaques e ritmos de fala nativos internacionais.
- **Controle de Velocidade de Áudio em Tempo Real (0.5× a 2.5×)**: Controle deslizante de velocidade de reprodução com preservação de tom para se ajustar a falantes nativos rápidos.
- **Modo Ear-only Listening**: Oculta as legendas de conversação da IA para que você dependa exclusivamente da audição, com **[Reveal last line]** disponível quando necessário.
- **Incentivo de Expressão de Reparo**: Usar estratégias de reparo como _"Sorry?", "Could you say that again?"_ concede **pontos bônus** em vez de deduções de pontos.
- **Listening Lab**: Exercícios que testam a compreensão exata de detalhes (números, dosagens de medicamentos, nomes de pacientes, horários, direções, preços) com pontuação de precisão discriminada.

---

## 11. Ensino Inverso de Palestras (Técnica Feynman)

Usa a técnica de Feynman — explicando conceitos em voz alta como se estivesse ensinando outra pessoa — para solidificar o conhecimento médico.

1. **Preparar o Material da Palestra**: Cole notas de resumo ou insira um URL de palestra médica no YouTube e toque em **`🎬 Fetch YT transcript`**.
2. **Condensação da IA**: Para materiais longos, toque em **`✨ Condense`** para comprimir o texto em um esboço estruturado de 500 palavras.
3. **Selecione a Persona do Público**:
   - **Professor de Exame Oral**: Faz perguntas de acompanhamento clínico afiadas e desafiadoras "Por que" e "E se".
   - **Colega de Classe Confuso**: Solicita explicações em linguagem simples sem jargão pesado.
   - **Tutor Amigável**: Fornece incentivo de apoio e orientação de fraseado.
4. **Ensine**: Toque em **Start** e explique via microfone enquanto o ouvinte IA responde com perguntas esclarecedoras.

---

## 12. Simulações de Entrevista de Residência

Simula entrevistas realistas para empregos em hospitais no exterior e Correspondência de Residência dos EUA:

- **Comportamental**: Descrições de experiência do método STAR (Situação, Tarefa, Ação, Resultado).
- **Clínico**: Apresentação de casos orais, ética médica e lógica de gerenciamento de emergência.
- **Específico para IMG**: Foca nas perguntas comuns do IMG (patrocínio de visto, explicações sobre o ano sabático no CV, pontos fortes únicos como IMG).
- Um Diretor de Programa IA conduz perguntas de acompanhamento educadas, mas investigativas.

---

## 13. Lounge de Inglês Livre e Cenários Personalizados

### Lounge de Inglês Livre

- Discuta tópicos médicos atuais, resuma artigos de periódicos, resolva conflitos de enfermagem/local de trabalho ou pratique conversa fiada no intervalo para o café.
- Busque legendas de notícias médicas no YouTube para debater livremente com a IA.

### Cenários Personalizados

Crie cenários de prática personalizados, adaptados às suas necessidades:

- **Scenario Name**: Identificador personalizado.
- **Persona / Context**: Prompt do sistema definindo o papel e a situação da IA.
- **Eval Template**: Selecione rubricas de avaliação (por exemplo, protocolo SPIKES para dar más notícias).
- **Custom Eval Criteria**: Defina pontos-chave de avaliação específicos para a IA verificar.

---

## 14. Desconstruindo o Relatório de Feedback (7 Seções Principais)

Após concluir uma sessão, um relatório de 7 seções fornece feedback multidimensional:

1. **Scores**: Compara as pontuações do domínio IA (0–10) lado a lado com sua autoavaliação. Uma lacuna de **2.0+ pontos** aciona um cartão de **Reflection Prompt** amarelo para guiar a autorreflexão.
2. **Fluency**:
   - **WPM (Words Per Minute)**: Mede a velocidade de fala em relação às metas recomendadas (100–130 WPM).
   - **Filler Words**: Mede a densidade das palavras de preenchimento (`um`, `uh`, `like`), incentivando o uso efetivo de pausas.
3. **Checklist**: Avalia objetivos clínicos, citando frases exatas da transcrição como **Evidence**.
4. **SOAP Note Comparison**: Compara uma nota SOAP gerada automaticamente a partir da sua sessão com uma nota SOAP de referência de modelo.
5. **Corrections**: Correções baseadas em cartão para traduções diretas, artigos/plurais, expressões não naturais e pronúncia. Tocar em **[Accept]** registra o item em seu rastreador de erros SRS pessoal.
6. **Shadowing**: Reescreve frases fracas em inglês clínico em nível de médico assistente para treinamento em áudio de ouvir e repetir.
7. **Summary & Share Cards**: Exibe o feedback geral e fornece um gerador de imagem **Share Card** para compartilhar resumos de desempenho com colegas de estudo.

---

## 15. Tutor de IA Socrático e Coach de Voz 1:1 Sempre Ativo

### 15-1. Reunião de Tutor de IA Socrático 1:1 (`[Debrief with AI Tutor]`)

Tocar em **[Debrief with AI Tutor]** na parte inferior do relatório de feedback abre uma sala de bate-papo 1:1 com um mentor IA Socrático.

- Faz perguntas orientadoras em vez de distribuir respostas diretamente, ajudando você a descobrir e corrigir erros sozinho.
- O histórico de bate-papo da reunião é preservado no banco de dados para que você possa voltar e continuar a qualquer momento.

### 15-2. Coach de Voz 1:1 Sempre Ativo (Home FAB)

Tocar no **Voice Coach Floating Button (`🎙️ RecordVoiceOver`)** no canto inferior direito do Dashboard abre um diálogo de treinador de fala instantâneo sem concluir um cenário completo primeiro.

- O coach IA lidera conversas de voz 1:1 personalizadas com base em seus pontos fracos SRS acumulados.

---

## 16. Rastreador de Erros por Repetição Espaçada e Genoma de Erros

Correções aceitas através de **[Accept]** são gerenciadas automaticamente por algoritmos de repetição espaçada em seu banco de dados de erros.

- **Fuzzy Duplicate Detection**: Evita automaticamente o registro duplicado de erros semelhantes.
- **Leech / Stubborn Errors**: Itens perdidos 4+ vezes consecutivas em testes de revisão são marcados como **Stubborn / Leech** para gerenciamento focado.
- **Intervalos de Repetição Espaçada Científica**:
  - Cronograma de revisão: **1 dia → 3 dias → 7 dias → 14 dias → 30 dias**. Passar em 3 revisões consecutivas gradua o item para **Mastered**.
  - A aprovação em questionários vocais em **Practice My Mistakes** avança itens em direção à Maestria.
- **Painel Genoma de Erros**:
  - Exibe as 5 principais categorias de erro mais fracas (Artigos, Plurais, Tempo, Preposições, Registro, Traduções Diretas) como um gráfico de barras no Dashboard com dicas de linguagem simples.

---

## 17. Apresentação de Casos Atendidos em Cadeia

Treine habilidades de passagem de plantão oral apresentando casos a um supervisor após um Encontro com Paciente:

1. Complete uma sessão de **Patient Encounter**.
2. Vá para a guia **History**, selecione a sessão e toque em **`📋 Present Case`**.
3. O Médico Assistente de IA inicia com: _"Doutor, por favor apresente o caso que você acabou de ver."_
4. Entregue uma apresentação de caso oral usando o formato SBAR ou SOAP e responda a perguntas de acompanhamento sobre diagnóstico diferencial e planos de tratamento.

---

## 18. Importação de Histórico de Conversas Externas (Import Transcript)

Importe o texto da conversa do ChatGPT, Gemini ou anotações clínicas para o aplicativo para receber feedback completo:

1. **Integração de Compartilhamento do Android**: Destaque o texto da conversa em aplicativos externos e selecione **[Compartilhar] → [Bedside English]** para abrir automaticamente a tela **Import Transcript**.
2. **Entrada Direta / Colar**: Abra a tela `Import Transcript` diretamente das Preferências ou do menu principal e cole o texto.
3. **Feedback e SRS Automatizados**: Gera pontuações de domínio, notas SOAP e cartões de correção disponíveis para aceitação de SRS.

---

## 19. Decks Anki e Exportações de Documentos Word

- **Exportação de Cartão Anki (`.txt` separado por tabulações)**:
  - Converte correções aceitas em um arquivo de importação de texto sem formatação do Anki (File → Import no Anki / AnkiDroid). A categoria de cada erro é transferida como uma tag. Disponível tanto na tela de feedback pós-sessão quanto em **My Mistakes**, que exporta toda a sua lista de revisão de uma só vez.
- **Exportação de Relatório do Word (`.docx`)**:
  - Gera relatórios médicos estruturados contendo pontuações, evidências de lista de verificação, notas SOAP e correções de frases. A opção Preferences ativa o salvamento automático.

---

## 20. Wiki de Ajuda no Aplicativo

Tocar no **ícone Ajuda `?`** na barra superior do aplicativo abre o visualizador Wiki de Ajuda integrado em modo de tela cheia.

- **Integração Multilíngue**: Carrega automaticamente o arquivo de guia do usuário que corresponde à configuração do idioma da interface do usuário do aplicativo.
- **Barra Lateral do Sumário (TOC)**: Permite navegação rápida em todas as seções do guia.
- **Pesquisa de Texto Completo**: Insira palavras-chave na barra de pesquisa para destacar correspondências e navegue com os botões anterior/próximo.
- **Hiperlinks Externos**: Tocar em links da web no guia abre o navegador padrão do sistema.

---

## 21. Configurações de Idioma da Interface, Rastreamento de Custos de API e Preferências

Acesse a **Engrenagem de Configurações (⚙️)** na barra superior para adaptar o aplicativo ao seu dispositivo e orçamento:

- **Voz e Feedback**:
  - Selecione voz e modelos IA de feedback (Demo, Gemini, OpenAI, Claude).
  - **Prevenção de Eco**: Silencia automaticamente o microfone enquanto a IA fala para evitar loops de feedback de áudio (essencial ao não usar fones de ouvido).
  - **Ritmo de Fala da IA**: Controle de velocidade gradual (Lento, Normal, Rápido, Desafio).
- **Análise de Custo de API**: Acompanha de forma transparente o uso de tokens e o custo estimado em dólares por sessão com visualizações de gráficos.
- **Áudio**: Indicador de nível de microfone em tempo real e tom de teste do alto-falante.
- **Chaves API**: Gerenciador de chaves de armazenamento criptografado local.
- **Exportação e Aprendizado**: Opções de salvamento automático do Docx, entrada obrigatória de nota SOAP, Idioma Nativo e configurações de Idioma da Interface.
- **Dados**: Alterne a análise de pronúncia, configure motores e selecione motores de shadowing TTS.
- **Privacidade**: Alterne a telemetria de uso anônimo.

---

## 22. Perguntas Frequentes (FAQ) e Rubrica de Avaliação

### Perguntas Frequentes (FAQ)

**P: O microfone não está captando o áudio e a IA não responde.**

- Verifique **Configurações → Aplicativos → Bedside English → Permissões → Microfone** do Android e defina-o para [Permitir]. Se a permissão for negada, o aplicativo mudará para o modo **Type instead**, para que você possa continuar praticando via teclado.

**P: Os modos de prática avançada (Exam, Teach-back, Interview, Lounge, Custom) estão ausentes.**

- Complete sua primeira sessão prática e todos os modos avançados serão desbloqueados automaticamente com uma mensagem de celebração. Você também pode expandir e revelar todos os modos na tela Practice.

**P: Os resultados da análise de pronúncia não vão para o meu banco de dados de revisão SRS imediatamente.**

- Para evitar que erros de reconhecimento de fala de ocorrência única sobrecarreguem os alunos, os itens de pronúncia aceitos entram em um estado **Observed** primeiro. Eles são promovidos a cartões de revisão SRS ativos apenas quando o mesmo padrão de erro ocorre em uma sessão futura.

**P: Posso importar transcrições de conversas externas (por exemplo, ChatGPT) para classificação?**

- Sim. Use o recurso de compartilhamento do Android para compartilhar texto no Bedside English ou colar texto na tela `Import Transcript` para receber feedback completo, notas SOAP e cartões de correção.

---

### 📝 Rubrica de Avaliação Detalhada (Faixa de Pontuação 0-10)

| Pontuação  | Nível de Avaliação                   | Critérios de Avaliação                                                                                                                             |
| :--------- | :----------------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------- |
| **9 ~ 10** | **Médico Assistente / Especialista** | Gramática e expressão impecáveis; vocabulário clínico preciso e raciocínio médico sistemático; tom natural e profissional.                         |
| **7 ~ 8**  | **Competente / Aprovado**            | Erros gramaticais menores, mas comunicação totalmente clara; identifica fatores de risco chave e realiza diagnóstico diferencial com segurança.    |
| **5 ~ 6**  | **Em Desenvolvimento**               | Erros gramaticais estruturais frequentes que exigem esforço do ouvinte; raciocínio clínico e vocabulário assistemáticos.                           |
| **1 ~ 4**  | **Crítico / Reprovado**              | Erros médicos graves ou fatores de risco não observados; fala limitada a palavras únicas ou pausas longas e frequentes impedindo o diálogo normal. |
