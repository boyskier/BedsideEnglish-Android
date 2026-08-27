# Panduan Pengguna Bedside English

**Target Pengguna:** Lulusan Kedokteran Luar Negeri (IMG), dokter residen, mahasiswa kedokteran, dan profesional kesehatan yang bersiap untuk Bahasa Inggris klinis, OSCE, OET Speaking, Wawancara Residensi AS, presentasi ronde bangsal (Ward Round), serta Komunikasi Klinis.

Bedside English adalah aplikasi khusus Android yang didukung oleh AI percakapan (Google Gemini, OpenAI Realtime, Anthropic Claude) dan teknologi suara langsung (real-time). Aplikasi ini dirancang untuk melatih keterampilan komunikasi klinis, penalaran medis, pengucapan, dan kejelasan berbicara (intelligibility) dalam berbagai dimensi. Panduan ini menjelaskan secara rinci seluruh fitur dan penggunaan versi aplikasi terbaru yang disesuaikan untuk pengguna.

---

## 📌 Daftar Isi

1. [Pengaturan dan Konfigurasi Kunci API](#1-api-key-setup-and-configuration)
2. [Pemasangan Aplikasi dan Pengaturan Izin](#2-app-installation-and-permissions-setup)
3. [Panduan Awal Jalur Pertama & Penyesuaian Pembelajar L1](#3-first-run-onboarding--l1-learner-customization)
4. [Ikhtisar Tampilan UI (5 Tab Navigasi Bawah & Bantuan)](#4-ui-layout-overview-bottom-navigation-5-tabs--help)
5. [Menguasai Dasbor (Beranda)](#5-mastering-the-dashboard-home)
6. [Pusat Latihan & Pembukaan Fitur Bertahap](#6-practice-hub--progressive-feature-unlocking)
7. [Simulasi Pasien & Pelacak Anamnesis Langsung](#7-patient-encounters--live-history-coverage-tracker)
8. [Mode Ujian & Diagnostik (Diagnostik Awal 10 Menit & Pemetaan CEFR)](#8-exam--diagnostic-mode-10-minute-baseline--cefr-mapping)
9. [Laboratorium Pengucapan & Kejelasan Berbicara (Pron Lab)](#9-pronunciation--intelligibility-lab-pron-lab)
10. [Bahasa Inggris Kelangsungan Hidup & Lab Mendengar (Survival English)](#10-survival-english--listening-lab)
11. [Penjelasan Ulang Kuliah — Metode Feynman (Teach-back)](#11-lecture-teach-back-feynman-technique)
12. [Simulasi Wawancara Residensi](#12-residency-mock-interviews)
13. [Lounge Bahasa Inggris Bebas & Skenario Kustom](#13-free-english-lounge--custom-scenarios)
14. [Bedah Laporan Umpan Balik (7 Bagian Utama)](#14-deconstructing-the-feedback-report-7-main-sections)
15. [Tutor AI Sokrates & Pelatih Suara 1:1 Selalu Aktif](#15-socratic-ai-tutor--always-on-11-voice-coach)
16. [Pelacak Kesalahan Pengulangan Berjarak & Mistake Genome](#16-spaced-repetition-error-tracker--mistake-genome)
17. [Presentasi Kasus Dokter Penanggung Jawab Berantai](#17-attending-case-presentation-chaining)
18. [Impor Riwayat Percakapan Eksternal (Import Transcript)](#18-importing-external-conversation-history-import-transcript)
19. [Ekspor Kartu Anki & Dokumen Word](#19-anki-decks--word-document-exports)
20. [Wiki Bantuan dalam Aplikasi](#20-in-app-help-wiki)
21. [Pengaturan Bahasa UI, Pelacakan Biaya API & Preferensi](#21-ui-language-settings-api-cost-tracking--preferences)
22. [Pertanyaan yang Sering Diajukan (FAQ) & Rubrik Penilaian](#22-frequently-asked-questions-faq--scoring-rubric)

---

## 1. API Key Setup and Configuration

Bedside English mendukung backend Google Gemini, OpenAI, dan Anthropic Claude secara fleksibel.

### 💡 Pengaturan yang Direkomendasikan (Mode Kunci Tunggal Google Gemini)

**Daftarkan satu kunci API Google Gemini untuk mengaktifkan seluruh fitur aplikasi—mulai dari percakapan suara langsung hingga analisis umpan balik setelah sesi—dengan cara tercepat dan paling hemat biaya.**

| Layanan                | Tujuan Utama                                                            | Wajib / Opsional                              | Tautan                                                 |
| :--------------------- | :---------------------------------------------------------------------- | :-------------------------------------------- | :----------------------------------------------------- |
| **Google (Gemini)**    | Percakapan suara langsung (Gemini Live) + Analisis mendalam umpan balik | **Wajib (Satu kunci mencakup seluruh fitur)** | [aistudio.google.com](https://aistudio.google.com)     |
| **OpenAI**             | Suara langsung (OpenAI Realtime) + Umpan balik + TTS Kualitas Tinggi    | Opsional                                      | [platform.openai.com](https://platform.openai.com)     |
| **Anthropic (Claude)** | Analisis umpan balik sesi (backend yang dapat dipilih)                  | Opsional (Gemini adalah bawaan)               | [console.anthropic.com](https://console.anthropic.com) |

### Penginputan Kunci API dan Keamanan

Kunci API dimasukkan langsung di dalam aplikasi:

- Konfigurasikan selama **Wisaya Pengaturan Pertama Kali** atau melalui menu **Ikon Pengaturan (⚙️) → Preferensi → Kunci API** di bilah atas.
- Kunci API yang dimasukkan **disimpan secara aman** di dalam penyimpanan terenkripsi perangkat (`EncryptedSharedPreferences`) dan tidak pernah dikirim ke server luar.
- Setiap kolom kunci dilengkapi ikon mata di sebelah kanan untuk menampilkan atau menyembunyikan kunci.

### 🎈 Mode Demo (Uji Coba Gratis Sepenuhnya)

Jika Anda ingin mencoba aplikasi tanpa mendaftarkan kunci API atau memberikan izin mikrofon, pilih mode **Demo** pada layar awal atau di bawah **Preferensi**.
Percakapan simulasi dan data umpan balik terstruktur akan dimuat, memungkinkan Anda menjelajahi seluruh UI, pelacak kesalahan, dan fitur ulasan secara **gratis** tanpa mengonsumsi token. Setelah menyelesaikan sesi demo, aplikasi akan menawarkan opsi untuk memasukkan kunci API atau melanjutkan ke latihan pasien demo berikutnya kapan saja.

---

## 2. App Installation and Permissions Setup

Bedside English berjalan pada ponsel pintar dan tablet dengan Android 8.0 (API Level 26) atau lebih tinggi.

### Pemasangan Aplikasi

- Jalankan berkas pemasangan yang disediakan (`.apk`) pada perangkat Android Anda dan ikuti petunjuk di layar untuk memasang.

### Izin Mikrofon & Mode Ketik Jawaban

- Saat pertama kali membuka mode latihan suara langsung, OS Android akan meminta izin akses mikrofon. Ketuk **[Izinkan]** agar pengenalan suara berjalan dengan baik.
- Jika Anda berada di lingkungan yang tidak memungkinkan untuk berbicara atau jika izin ditolak, aplikasi tidak akan berhenti bekerja. Aplikasi akan beralih secara otomatis ke mode **Ketik saja (Type instead)**, memungkinkan Anda berlatih percakapan menggunakan papan ketik.

### Izin Notifikasi & Pemeriksaan Pra-Penerbangan Audio

- Izin notifikasi diminta agar Anda dapat menerima pemberitahuan ketika analisis umpan balik latar belakang selesai.
- Sesaat sebelum memulai sesi suara langsung pertama Anda, jendela **Pemeriksaan Pra-Penerbangan Audio** akan ditampilkan untuk memandu penggunaan fon telinga (headphone) dan menguji tingkat input mikrofon guna mencegah timbal balik audio (howling).

---

## 3. First-Run Onboarding & L1 Learner Customization

Saat pertama kali membuka aplikasi, wisaya pengaturan 4 langkah akan berjalan untuk membangun lingkungan pembelajaran yang disesuaikan:

1. **Layar Selamat Datang**: Memperkenalkan fitur utama dan menyediakan tombol **Coba Mode Demo** untuk menjelajah tanpa kunci API.
2. **Pemilih Bahasa UI**: Pilih bahasa antarmuka pilihan Anda (8 bahasa yang didukung: Bahasa Inggris, Bahasa Korea, Bahasa Spanyol, Bahasa Mandarin, Bahasa Arab, Bahasa Hindi, Bahasa Portugis, Bahasa Tagalog, dan Bahasa Indonesia).
3. **Pengaturan Kunci API**: Daftarkan kunci API Google Gemini Anda atau kunci AI penyedia lainnya.
4. **Bahasa Ibu & Privasi**: Pilih bahasa pertama Anda (misalnya **Bahasa Indonesia**, Bahasa Korea, Bahasa Mandarin, Bahasa Spanyol, Bahasa Arab, Bahasa Hindi, Bahasa Tagalog, Bahasa Portugis). Ini mengaktifkan analisis presisi tata bahasa dan pengucapan yang disesuaikan dengan pola interferensi bahasa ibu Anda.

### 🌐 Sorotan Penyesuaian Bahasa Ibu L1 (misalnya Pembelajar L1 Bahasa Korea)

- **Koreksi Tata Bahasa & Ungkapan**:
  - Penggunaan kata sandang (_article_: mengabaikan _a/an/the_ sebelum kata benda)
  - Bentuk jamak kata benda (_two patient_ → _two patients_)
  - Ketersesuaian tata bahasa waktu (_tense_: menggunakan bentuk waktu sekarang saat membahas riwayat medis masa lalu)
  - Penggunaan kata depan (_preposition_: keliru menggunakan _in hospital_, atau melewatkan kata depan pada _explain to patient_)
  - Terjemahan harfiah / Konglish (_skin scale_, terjemahan langsung yang kurang alami untuk _side effect_)
- **Koreksi Pengucapan & Kejelasan Berbicara**:
  - Pembedaan pasangan minimal _r / l_ (_liver_ vs _river_)
  - Pembedaan bunyi _f / p_ (_fever_ vs _peter_)
  - Pengucapan geseran gigi _th_ (_think_ vs _tink_)
  - Pengabaian konsonan akhir dan penambahan bunyi vokal yang tidak perlu (_cardiac_ → _cardi-ack-eu_)
  - Penekanan suku kata kata medis (_angina_, _arrhythmia_)

### 💡 Tur Pertama Kali Jalankan (First-Run Tour)

Setelah menyelesaikan pengaturan awal (onboarding) dan masuk ke Dasbor untuk pertama kalinya, tur interaktif akan secara otomatis memandu Anda ke lokasi dan fungsi tombol-tombol utama (Dasbor, Pusat Latihan, Lab Pengucapan, Ulasan SRS, Riwayat, Bantuan, Pelatih Suara 1:1).

---

## 4. UI Layout Overview (Bottom Navigation 5 Tabs & Help)

### Bilah Navigasi Bawah (5 Tab)

Navigasi utama terdiri dari 5 tab bawah:

```
┌───────────┬──────────────┬───────────────────────┬───────────────┬─────────────┐
│  🏠 Home  │  ▶ Practice  │  🎙️ Pronunciation Lab │  ⚠️ SRS Reviews│  🕘 History │
└───────────┴──────────────┴───────────────────────┴───────────────┴─────────────┘
```

1. **Beranda (Dashboard)**: Rentetan latihan (`🔥`), misi klinis 5 menit, grafik tren performa, Mistake Genome, alur pembelajaran, dan **Pelatih Suara 1:1 Selalu Aktif**.
2. **Latihan (Practice Hub)**: Pusat utama untuk seluruh mode berbicara langsung: Simulasi Pasien, Ujian & Diagnostik, Bahasa Inggris Kelangsungan Hidup, Penjelasan Ulang (Teach-back), Wawancara, Lounge Bebas, dan Skenario Kustom.
3. **Lab Pengucapan (Pron Lab)**: Tab latihan khusus untuk kejelasan berbicara dan koreksi pengucapan (penyaringan pola kesalahan, manajemen status observasi).
4. **Ulasan SRS (Weakness Review)**: Kuis ulasan lisan berbasis algoritma pengulangan berjarak (spaced repetition) untuk kalimat koreksi yang diterima.
5. **Riwayat (Session History)**: Lihat skor dan umpan balik dari sesi terdahulu, lacak biaya token, ekspor ke Anki/Word, dan picu **Presentasi Kasus Dokter Penanggung Jawab (Present Case)**.

### Bilah Aplikasi Atas

- **Logo Bedside English**: Judul utama.
- **Wiki Bantuan (Ikon `?`)**: Ketuk ikon `?` untuk membuka Panduan Pengguna ini dalam tampilan layar penuh dengan navigasi Daftar Isi dan pencarian kata kunci.
- **Ikon Pengaturan (⚙️ Preferensi)**: Kunci API, backend suara, kecepatan berbicara, pencegahan gema, bahasa, dan manajemen data.

---

## 5. Mastering the Dashboard (Home)

Dasbor utama menampilkan perkembangan keterampilan komunikasi bahasa Inggris klinis Anda secara visual dalam berbagai dimensi:

- **Rentetan Latihan (Practice Streak)**: Menampilkan hari latihan aktif berturut-turut dengan ikon api (`🔥`) untuk membangun kebiasaan belajar harian.
- **Kartu Metrik Utama**:
  - **Sesi selesai**: Jumlah total sesi yang telah diselesaikan dan dianalisis sepenuhnya.
  - **Kesalahan dilacak**: Koreksi yang dikonfirmasi dan terdaftar dalam basis data kelemahan pribadi Anda.
  - **Dikuasai**: Kesalahan yang telah teratasi dan lulus melalui kuis ulasan berulang.
  - **Jatuh tempo sekarang**: Jumlah kartu ulasan SRS yang dijadwalkan untuk kuis lisan hari ini.
  - **Kesalahan gigih (Stubborn)**: Kesalahan "leech" yang gagal 4+ kali berturut-turut dan membutuhkan perhatian khusus.
- **Misi Klinis 5 Menit Hari Ini**: Secara otomatis merekomendasikan alur latihan 5 menit optimal yang menargetkan item ulasan jatuh tempo, diagnostik yang diperlukan, atau area kelemahan Anda.
- **Cakupan Kosakata Awam OET**:
  - Melacak seberapa efektif Anda mengganti istilah medis rumit (misalnya _syncope_) dengan istilah awam yang ramah pasien (misalnya _fainting_).
  - Istilah yang digunakan muncul di bawah **Baru Saja Terbuka**, sedangkan ekspresi yang belum digunakan mengantre di bawah **Target Berikutnya (Terkunci)**.
- **Panel Mistake Genome**: Menganalisis kategori kesalahan paling sering (Articles, Plurals, Tenses, Prepositions, Register, Direct Translations) dan menampilkan 5 area kelemahan teratas sebagai grafik batang.
- **Grafik Tren Pertumbuhan & Interferensi L1**:
  - Menggambarkan tren skor di 5 domain (Grammar, Accuracy, Reasoning, Professionalism, Fluency) selama 20 sesi terakhir Anda.
  - Memvisualisasikan pola kesalahan tata bahasa berulang.
- **Peta Jalan Dipersonalisasi**: Menganalisis metrik lemah dan riwayat kesalahan untuk menyajikan 4 kartu fokus keterampilan berprioritas.
- **Tombol Pelatih Suara 1:1 Selalu Aktif (`🎙️ RecordVoiceOver`)**:
  - Terletak di kanan bawah Dasbor. Ketuk untuk membuka dialog percakapan lisan 1:1 secara instan dengan tutor AI berdasarkan profil kelemahan Anda tanpa perlu memulai skenario sesi penuh.

---

## 6. Practice Hub & Progressive Feature Unlocking

### Pembukaan Fitur Bertahap

Untuk mencegah pengguna baru merasa kewalahan, pengguna pertama kali akan melihat layar pengantar yang tenang berisi mode inti (**Dasbor**, **Simulasi Pasien**, **Bahasa Inggris Kelangsungan Hidup**, **Riwayat**).

- **Menyelesaikan sesi latihan pertama Anda** secara otomatis akan **membuka** mode lanjutan (Exam, Teach-back, Interview, Lounge, Custom) disertai pesan perayaan.
- Anda juga dapat mengetuk untuk memperluas dan menampilkan seluruh mode secara langsung dari layar Latihan.

### Kategori Mode Utama Pusat Latihan

1. **Simulasi Pasien (Patient Encounters)**: Anamnesis, pemantauan lanjutan, mode dasar Foundations, Dril Keterampilan, Latihan Kesalahan Saya.
2. **Ujian & Diagnostik (Exam & Diagnostic)**: Diagnostik Awal 10 Menit, ujian simulasi OSCE, OET, Wawancara Residensi, dan Ronde Bangsal.
3. **Bahasa Inggris Kelangsungan Hidup & Lab Mendengar (Survival English & Listening Lab)**: Situasi tidak terduga di rumah sakit, percakapan ringan cepat, 15 aksen internasional, drill detail Lab Mendengar.
4. **Penjelasan Ulang Kuliah (Teach-back)**: Pelatihan metode Feynman berdasarkan ringkasan YouTube/teks.
5. **Simulasi Wawancara Residensi**: Wawancara Perilaku (Behavioral), Klinis, dan khusus IMG.
6. **Lounge Bahasa Inggris Bebas**: Debat medis, diskusi cuplikan berita, penyelesaian konflik tempat kerja.
7. **Skenario Kustom**: Buat instruksi prompt AI dan rubrik penilaian kustom Anda sendiri.

---

## 7. Patient Encounters & Live History Coverage Tracker

Mensimulasikan anamnesis dan konseling pasien di samping tempat tidur—inti dari komunikasi klinis.

### 7-1. Sub-Mode Operasional

- **Mode Dasar (Foundations Mode)**: Menghilangkan beban penalaran klinis untuk pembelajar tingkat awal, berfokus sepenuhnya pada **tata bahasa, kosakata klinis, hubungan baik (rapport), dan kelancaran**.
- **Dril Keterampilan (Skill Drills)**: Latihan kompetensi mikro terarah (teknik empati NURSE, penjelasan bahasa awam, serah terima tugas malam, **Penerjemahan Medis Berurutan Bahasa Ibu → Bahasa Inggris**).
- **Latihan Kesalahan Saya (Practice My Mistakes)**: Menyusun kuis dialog lisan instan dari kesalahan aktif di basis data Anda.
- **Misi Harian (Daily Mission)**: Tantangan harian 5 menit adaptif yang menargetkan celah keterampilan Anda saat ini.

### 7-2. Pelacak Anamnesis Langsung (Live History Coverage Tracker)

Panel real-time yang dapat dilipat yang menandai item anamnesis saat Anda berbicara:

- Secara otomatis melacak item berdasarkan konteks percakapan AI.
- Memantau onset/durasi, karakter nyeri, penjelaran (radiation), faktor yang memperberat/meredakan, gejala penyerta, ICE (Ideas, Concerns, Expectations), riwayat penyakit dahulu, obat-obatan, alergi, alkohol/merokok, riwayat keluarga, dll.
- Pertanyaan konfirmasi negatif seperti _"You don't smoke, do you?"_ diakui dan dilacak dengan benar.

### 7-3. Bantuan Kelanjutan Berbasis Konteks (`💡 Help me continue`)

Jika Anda bingung atau kehabisan pertanyaan di tengah sesi, ketuk **💡 Help me continue** di bagian bawah layar.

- Menganalisis respons terbaru pasien untuk menyarankan pertanyaan logis berikutnya beserta **contoh kalimat Bahasa Inggris siap pakai**.
- Pelacak Fase Wawancara di bagian atas menampilkan kemajuan menggunakan simbol status:
  - `✓`: Bukti memadai terdeteksi
  - `•`: Pengungkapan parsial terdeteksi
  - `?`: Fase lanjut tercapai tanpa verifikasi fase sebelumnya

---

## 8. Exam & Diagnostic Mode (10-minute Baseline & CEFR Mapping)

Mengukur kemahiran komunikasi di bawah kondisi ujian berbatas waktu yang imersif:

### Diagnostik Bahasa Inggris Klinis Awal 10 Menit

- Dimulai dengan pengantar penguji, diikuti oleh 4 tugas singkat (menjelaskan diagnosis, menangani pertanyaan lanjutan, memberikan serah terima SBAR 45 detik, menjawab pertanyaan wawancara residensi).
- Secara otomatis memetakan performa Anda ke **tingkat CEFR** internasional:
  - **Skor >= 8.5**: **C1** (Lancar, komunikasi klinis aman pada tingkat dokter spesialis)
  - **Skor >= 7.2**: **B2+** (Kompeten untuk kepaniteraan klinis dan praktik rumah sakit)
  - **Skor >= 6.0**: **B1-B2** (Kemampuan komunikasi dasar; disarankan studi terstruktur)
  - **Skor < 6.0**: **A2-B1** (Memerlukan pelatihan komunikasi klinis mendasar)

### Skenario Ujian Simulasi

- **OSCE**: Anamnesis nyeri dada Tn. Hayes (mengukur ICE, tanda bahaya/red flags, empati).
- **OET Speaking**: Simulasi konseling pasien hipertensi.
- **Residensi**: Simulasi wawancara Direktur Program Penyakit Dalam AS.
- **Ronde Bangsal (Ward Round)**: Presentasi kasus pneumonia komunitas 5 menit dan penanganan pertanyaan lisan.

### Lencana Keandalan Skor

Menunjukkan tingkat kepercayaan penilaian AI sebagai **Tinggi (High)**, **Sedang (Medium)**, atau **Rendah (Low)**:

- **Tinggi**: Jumlah kata pembelajar >= 180 kata & tingkat bukti daftar periksa >= 75%.
- **Sedang**: Jumlah kata pembelajar >= 80 kata & tingkat bukti daftar periksa >= 50%.
- **Rendah**: Jumlah kata < 80 kata (bendera Short Transcript) atau tingkat bukti < 50%.

---

## 9. Pronunciation & Intelligibility Lab (Pron Lab)

Terletak di tab bawah ke-3 (`🎙️ Pron Lab`), ini adalah pusat latihan khusus Anda untuk kejelasan berbicara.

### 💡 Pelatihan Berfokus Kejelasan Berbicara

Tujuannya bukanlah meniru aksen penutur asli, melainkan **"Apakah rekan sejawat internasional dan pasien dapat memahami ucapan saya dengan jelas tanpa kesalahpahaman?"**

- Analisis audio memberikan bimbingan tepat hanya pada poin pengucapan yang menyebabkan kesalahpahaman pendengar.

### Kategori Pola Kesalahan & Chip Filter

Kesalahan pengucapan yang terdeteksi di seluruh sesi Anda dikelompokkan berdasarkan kategori ke dalam chip filter:

- `r · l`: _liver / river_, _clinical / critical_
- `f · p`: _fever / peter_, _palpation / falcation_
- `th`: _think / tink_, _throat / troat_
- `final`: Konsonan akhir yang terlewat (_chest / ches_)
- `cluster`: Pemrosesan kluster konsonan & penambahan vokal yang tidak perlu (_cardiac_ → _cardi-ack-eu_)
- `stress`: Penekanan suku kata kata medis yang keliru (_angina_, _arrhythmia_)
- `vowel`: Kebingungan vokal pendek vs. panjang (_ship / sheep_, _fit / feet_)

### Aturan Promosi Status Observasi

Saat kartu pengucapan yang diterima pertama kali dicatat, kartu tersebut masuk ke status **Observasi (Observed)** bukannya langsung menjadi tugas harian. Kartu baru dipromosikan menjadi kartu ulasan SRS aktif ketika pola kesalahan yang sama berulang di sesi terpisah, memastikan kesalahan pengenalan suara satu kali tidak menjadi beban tugas harian.

---

## 10. Survival English & Listening Lab

Mersiapkan Lulusan Kedokteran Luar Negeri (IMG) untuk interaksi rumah sakit dunia nyata non-klinis di luar ruang periksa.

- **Mode Acak / Kejutan**: Penanganan spontan situasi tidak terduga (pertanyaan di koridor, panggilan balik apotek, percakapan ringan dengan perawat) dengan konteks skenario tersembunyi hingga AI berbicara.
- **Percakapan Cepat (Rapid-fire Small Talk)**: Respons cepat terhadap perubahan topik mendadak.
- **15 Profil Aksen Penutur Asli**: Berlatih menyesuaikan diri dengan aksen dan ritme bicara internasional.
- **Kontrol Kecepatan Audio Real-time (0.5× hingga 2.5×)**: Penggeser kecepatan pemutaran yang mempertahankan nada untuk menyesuaikan dengan penutur asli yang cepat.
- **Mode Mendengar Saja (Ear-only)**: Menyembunyikan subteks percakapan AI agar Anda bergantung sepenuhnya pada pendengaran, dengan opsi **[Tampilkan baris terakhir]** yang dapat diakses jika diperlukan.
- **Pendorongan Ekspresi Perbaikan**: Menggunakan strategi perbaikan seperti _"Sorry?", "Could you say that again?"_ memberikan **poin bonus** daripada pengurangan poin.
- **Lab Mendengar (Listening Lab)**: Latihan menguji pemahaman detail tepat (angka, dosis obat, nama pasien, waktu, petunjuk arah, harga) dengan penilaian akurasi terperinci.

---

## 11. Lecture Teach-back (Feynman Technique)

Menggunakan metode Feynman—menjelaskan konsep secara lisan seolah-olah mengajar orang lain—untuk memperkuat pengetahuan medis.

1. **Siapkan Materi Kuliah**: Tempel catatan ringkasan atau masukkan URL kuliah medis YouTube lalu ketuk **`🎬 Fetch YT transcript`**.
2. **Penyederhanaan AI**: Untuk materi yang panjang, ketuk **`✨ Condense`** untuk memadatkan teks menjadi garis besar 500 kata terstruktur.
3. **Pilih Persona Pendengar**:
   - **Profesor Ujian Lisan**: Mengajukan pertanyaan lanjutan klinis "Mengapa" dan "Bagaimana jika" yang menantang.
   - **Rekan Sekelas Bingung**: Meminta penjelasan bahasa awam tanpa istilah medis berat.
   - **Tutor Ramah**: Memberikan dorongan suportif dan panduan ungkapan.
4. **Mengajar**: Ketuk **Mulai** dan jelaskan melalui mikrofon saat pendengar AI merespons dengan pertanyaan klarifikasi.

---

## 12. Residency Mock Interviews

Mensimulasikan wawancara realistis untuk pekerjaan rumah sakit luar negeri dan US Residency Match:

- **Wawancara Perilaku (Behavioral)**: Pengalaman metode STAR (Situation, Task, Action, Result).
- **Wawancara Klinis**: Presentasi kasus lisan, etika medis, dan penalaran penanganan darurat.
- **Khusus IMG**: Berfokus pada pertanyaan umum IMG (sponsor visa, penjelasan jeda tahun pada CV, kekuatan unik sebagai IMG).
- Direktur Program AI memimpin pertanyaan lanjutan yang sopan namun mendalam.

---

## 13. Free English Lounge & Custom Scenarios

### Lounge Bahasa Inggris Bebas

- Diskusikan topik medis terkini, rangkum artikel jurnal, selesaikan konflik tempat kerja/perawat, atau latih percakapan ringan saat waktu istirahat.
- Ambil subteks berita medis YouTube untuk diperdebatkan secara bebas dengan AI.

### Skenario Kustom

Buat skenario latihan kustom yang disesuaikan dengan kebutuhan Anda:

- **Nama Skenario**: Pengidentifikasi kustom.
- **Persona / Konteks**: Prompt sistem yang menentukan peran dan situasi AI.
- **Templat Evaluasi**: Pilih rubrik evaluasi (misalnya protokol SPIKES untuk menyampaikan kabar buruk).
- **Kriteria Evaluasi Kustom**: Tetapkan poin penilaian kunci khusus untuk diperiksa oleh AI.

---

## 14. Deconstructing the Feedback Report (7 Main Sections)

Setelah menyelesaikan sesi, laporan 7 bagian memberikan umpan balik multidimensi:

1. **Skor**: Membandingkan skor domain AI (0–10) berdampingan dengan evaluasi mandiri Anda. Perbedaan **2.0+ poin** memicu kartu kuning **Petunjuk Refleksi** untuk memandu refleksi diri.
2. **Kelancaran (Fluency)**:
   - **WPM (Words Per Minute)**: Mengukur kecepatan bicara terhadap target yang direkomendasikan (100–130 WPM).
   - **Kata Pengisi (Filler Words)**: Mengukur kepadatan kata pengisi (_um_, _uh_, _like_), mendorong penggunaan jeda yang efektif.
3. **Daftar Periksa (Checklist)**: Mengevaluasi tujuan klinis, mengutip kalimat transkrip yang tepat sebagai **Bukti (Evidence)**.
4. **Perbandingan Catatan SOAP**: Membandingkan catatan SOAP yang dihasilkan secara otomatis dari sesi Anda dengan catatan SOAP referensi model.
5. **Koreksi (Corrections)**: Koreksi berbasis kartu untuk terjemahan langsung, kata sandang/jamak, ekspresi tidak alami, dan pengucapan. Mengetuk **[Terima]** mendaftarkan item ke dalam pelacak kesalahan SRS pribadi Anda.
6. **Shadowing**: Menulis ulang kalimat yang lemah menjadi bahasa Inggris klinis tingkat spesialis untuk latihan mendengar dan mengulang.
7. **Ringkasan & Kartu Berbagi**: Menampilkan umpan balik keseluruhan dan menyediakan pembuat gambar **Kartu Berbagi** untuk membagikan ringkasan performa dengan rekan belajar.

---

## 15. Socratic AI Tutor & Always-On 1:1 Voice Coach

### 15-1. Evaluasi Ulang 1:1 Tutor AI Sokrates (`[Debrief with AI Tutor]`)

Mengetuk **[Debrief with AI Tutor]** di bagian bawah laporan umpan balik akan membuka ruang obrolan 1:1 dengan mentor AI Sokrates.

- Mengajukan pertanyaan pemandu bukannya memberikan jawaban secara langsung, membantu Anda menemukan dan memperbaiki kesalahan sendiri.
- Riwayat obrolan evaluasi tersimpan di basis data sehingga Anda dapat kembali dan melanjutkan kapan saja.

### 15-2. Pelatih Suara Berbicara 1:1 Selalu Aktif (Home FAB)

Mengetuk **Tombol Melayang Pelatih Suara (`🎙️ RecordVoiceOver`)** di kanan bawah Dasbor membuka dialog pelatih berbicara instan tanpa harus menyelesaikan skenario penuh terlebih dahulu.

- Pelatih AI memimpin percakapan suara 1:1 yang disesuaikan berdasarkan poin kelemahan SRS Anda yang terkumpul.

---

## 16. Spaced Repetition Error Tracker & Mistake Genome

Koreksi yang diterima melalui **[Terima]** secara otomatis dikelola oleh algoritma pengulangan berjarak di basis data kesalahan Anda.

- **Deteksi Duplikat Samar (Fuzzy Duplicate)**: Secara otomatis mencegah pencatatan ganda untuk kesalahan yang serupa.
- **Kesalahan Gigih (Leech / Stubborn Errors)**: Item yang gagal 4+ kali berturut-turut dalam kuis ulasan ditandai sebagai **Gigih (Stubborn / Leech)** untuk penanganan khusus.
- **Interval Pengulangan Berjarak Ilmiah**:
  - Jadwal ulasan: **1 hari → 3 hari → 7 hari → 14 hari → 30 hari**. Lulus 3 ulasan berturut-turut akan mempromosikan item menjadi **Dikuasai (Mastered)**.
  - Menyelesaikan kuis lisan di **Latihan Kesalahan Saya** mengarahkan item menuju Penguasaan.
- **Panel Mistake Genome**:
  - Menampilkan 5 kategori kesalahan kelemahan teratas Anda (Articles, Plurals, Tense, Prepositions, Register, Direct Translations) sebagai grafik batang Dasbor dengan panduan bahasa sederhana.

---

## 17. Attending Case Presentation Chaining

Latih keterampilan serah terima lisan dengan mempresentasikan kasus kepada dokter penanggung jawab setelah Simulasi Pasien:

1. Selesaikan sesi **Simulasi Pasien**.
2. Buka tab **Riwayat**, pilih sesi tersebut, lalu ketuk **`📋 Present Case`**.
3. Dokter Spesialis AI membuka percakapan: _"Doctor, please present the case you just saw."_
4. Sampaikan presentasi kasus lisan menggunakan format SBAR atau SOAP, dan jawab pertanyaan lanjutan tentang diagnosis banding serta rencana perawatan.

---

## 18. Importing External Conversation History (Import Transcript)

Impor teks percakapan dari ChatGPT, Gemini, atau catatan klinis ke dalam aplikasi untuk menerima umpan balik penuh:

1. **Integrasi Berbagi Android**: Sorot teks percakapan di aplikasi luar dan pilih **[Bagikan] → [Bedside English]** untuk membuka layar **Impor Transkrip** secara otomatis.
2. **Penginputan Langsung / Tempel**: Buka layar `Import Transcript` secara langsung dari Preferensi atau menu utama lalu tempelkan teks.
3. **Umpan Balik Otomatis & SRS**: Menghasilkan skor domain, catatan SOAP, dan kartu koreksi yang siap untuk diterima ke SRS.

---

## 19. Anki Decks & Word Document Exports

- **Ekspor Kartu Anki (`.txt` dipisahkan tab)**:
  - Mengonversi koreksi yang diterima menjadi berkas impor teks Anki (File → Import di Anki / AnkiDroid), dengan kategori tiap kesalahan ikut sebagai tag. Tersedia baik di layar umpan balik pasca-sesi maupun dari **Kesalahan Saya (My Mistakes)**, yang mengekspor seluruh daftar ulasan Anda sekaligus.
- **Ekspor Laporan Word (`.docx`)**:
  - Menghasilkan laporan medis terstruktur yang berisi skor, bukti daftar periksa, catatan SOAP, dan koreksi kalimat. Opsi Preferensi memungkinkan penyimpanan otomatis.

---

## 20. In-App Help Wiki

Mengetuk **Ikon Bantuan `?`** di bilah aplikasi atas akan membuka tampilan Wiki Bantuan terbawa dalam mode layar penuh.

- **Integrasi Multibahasa**: Secara otomatis memuat berkas panduan pengguna yang sesuai dengan pengaturan bahasa UI aplikasi.
- **Bilah Sisi Daftar Isi (TOC)**: Memungkinkan navigasi lompat cepat ke seluruh bagian panduan.
- **Pencarian Teks Penuh**: Masukkan kata kunci di bilah pencarian untuk menyorot pencocokan dan bernavigasi dengan tombol sebelumnya/berikutnya.
- **Pautan Luar**: Mengetuk tautan web di dalam panduan akan membuka peramban sistem bawaan Anda.

---

## 21. UI Language Settings, API Cost Tracking & Preferences

Akses **Ikon Pengaturan (⚙️)** di bilah atas untuk menyesuaikan aplikasi dengan perangkat dan anggaran Anda:

- **Suara & Umpan Balik**:
  - Pilih model AI suara dan umpan balik (Demo, Gemini, OpenAI, Claude).
  - **Pencegahan Gema**: Secara otomatis membisukan mikrofon saat AI berbicara untuk mencegah timbal balik audio (sangat penting saat tidak menggunakan fon telinga).
  - **Kecepatan Bicara AI**: Kontrol kecepatan bertahap (Lambat, Normal, Cepat, Tantangan).
- **Analitik Biaya API**: Melacak penggunaan token secara transparan dan perkiraan biaya dolar per sesi dengan visualisasi grafik.
- **Audio**: Indikator tingkat mikrofon real-time dan nada uji pengeras suara.
- **Kunci API**: Manajer penyimpanan kunci lokal terenkripsi.
- **Ekspor & Pembelajaran**: Opsi simpan otomatis Docx, penginputan wajib catatan SOAP, Bahasa Ibu, dan pengaturan Bahasa UI.
- **Data**: Beralih analisis pengucapan, konfigurasikan mesin, dan pilih mesin TTS shadowing.
- **Privasi**: Beralih telemetri penggunaan anonim.

---

## 22. Frequently Asked Questions (FAQ) & Scoring Rubric

### Pertanyaan yang Sering Diajukan (FAQ)

**T: Mikrofon tidak menangkap suara dan AI tidak merespons.**

- Periksa Android **Pengaturan → Aplikasi → Bedside English → Izin → Mikrofon** dan atur ke [Izinkan]. Jika izin ditolak, aplikasi beralih ke mode **Ketik saja (Type instead)** agar Anda dapat terus berlatih melalui papan ketik.

**T: Mode latihan lanjutan (Exam, Teach-back, Interview, Lounge, Custom) tidak muncul.**

- Selesaikan sesi latihan pertama Anda dan seluruh mode lanjutan akan terbuka secara otomatis disertai pesan perayaan. Anda juga dapat memperluas dan menampilkan seluruh mode dari layar Latihan.

**T: Hasil analisis pengucapan tidak langsung masuk ke basis data ulasan SRS saya.**

- Untuk mencegah kesalahan pengenalan suara satu kali membebani pembelajar, item pengucapan yang diterima masuk ke status **Observasi (Observed)** terlebih dahulu. Kartu tersebut baru dipromosikan menjadi kartu ulasan SRS aktif ketika pola kesalahan yang sama berulang di sesi mendatang.

**T: Bisakah saya mengimpor transkrip percakapan eksternal (misalnya ChatGPT) untuk dinilai?**

- Ya. Gunakan fitur berbagi Android untuk membagikan teks ke Bedside English atau tempelkan teks ke dalam layar `Import Transcript` untuk menerima umpan balik lengkap, catatan SOAP, dan kartu koreksi.

---

### 📝 Rubrik Penilaian Evaluasi Terperinci (Rentang Skor 0–10)

| Skor       | Tingkat Kualifikasi          | Kriteria Penilaian                                                                                                                                        |
| :--------- | :--------------------------- | :-------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **9 ~ 10** | **Dokter Spesialis / Pakar** | Tata bahasa dan ekspresi tanpa cela; kosakata klinis presisi dan penalaran medis sistematis; nada suara alami dan profesional.                            |
| **7 ~ 8**  | **Kompeten / Lulus**         | Kesalahan tata bahasa kecil namun komunikasi sangat jelas; mengidentifikasi faktor risiko utama dan melakukan diagnosis banding secara aman.              |
| **5 ~ 6**  | **Berkembang (Developing)**  | Kesalahan tata bahasa struktural sering terjadi yang membutuhkan usaha dari pendengar; penalaran klinis dan kosakata kurang sistematis.                   |
| **1 ~ 4**  | **Kritis / Gagal**           | Kesalahan medis berat atau melewatkan faktor risiko utama; ucapan terbatas pada kata tunggal atau jeda panjang yang sering yang menghambat dialog normal. |
