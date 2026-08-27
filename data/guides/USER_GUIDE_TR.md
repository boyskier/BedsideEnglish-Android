# Bedside English Kullanım Kılavuzu

**Hedef Kitle:** Uluslararası Tıp Mezunları (IMG'ler), asistan hekimler, tıp öğrencileri ve klinik İngilizce, OSCE, OET Speaking, ABD Uzmanlık (US Residency) Mülakatları, Vizit (Ward Round) sunumları ve Klinik İletişim konularına hazırlanan sağlık profesyonelleri.

Bedside English, diyaloga dayalı yapay zeka (Google Gemini, OpenAI Realtime, Anthropic Claude) ve gerçek zamanlı ses teknolojisiyle desteklenen Android'e özel bir uygulamadır. Klinik iletişim becerilerini, tıbbi akıl yürütmeyi, telaffuzu ve konuşma anlaşılırlığını (intelligibility) çok boyutlu olarak eğitmek için tasarlanmıştır. Bu kılavuz, uygulamanın en son sürümündeki tüm özellikleri ve kullanımı kullanıcılara uyarlanmış bir şekilde eksiksiz olarak ayrıntılandırmaktadır.

---

## 📌 İçindekiler

1. [API Anahtarı Kurulumu ve Yapılandırma](#1-api-anahtarı-kurulumu-ve-yapılandırma)
2. [Uygulama Kurulumu ve İzin Ayarları](#2-uygulama-kurulumu-ve-izin-ayarları)
3. [İlk Çalıştırma Onboarding & L1 Öğrenici Özelleştirmesi](#3-ilk-çalıştırma-onboarding--l1-öğrenici-özelleştirmesi)
4. [Kullanıcı Arayüzü Düzenine Genel Bakış (Alt Gezinme 5 Sekme & Yardım)](#4-kullanıcı-arayüzü-düzenine-genel-bakış-alt-gezinme-5-sekme--yardım)
5. [Ana Panoda (Home) Uzmanlaşma](#5-ana-panoda-home-uzmanlaşma)
6. [Pratik Merkezi (Practice Hub) & Aşamalı Özellik Kilitlerini Açma](#6-pratik-merkezi-practice-hub--aşamalı-özellik-kilitlerini-açma)
7. [Hasta Karşılaşmaları (Patient Encounters) & Canlı Öykü Kapsamı İzleyici](#7-hasta-karşılaşmaları-patient-encounters--canlı-öykü-kapsamı-izleyici)
8. [Sınav & Teşhis Modu (10 Dakikalık Başlangıç Çizgisi & CEFR Eşlemesi)](#8-sınav--teşhis-modu-10-dakikalık-başlangıç-çizgisi--cefr-eşlemesi)
9. [Telaffuz & Anlaşılırlık Laboratuvarı (Pron Lab)](#9-telaffuz--anlaşılırlık-laboratuvarı-pron-lab)
10. [Hayatta Kalma İngilizcesi & Dinleme Laboratuvarı](#10-hayatta-kalma-ingilizcesi--dinleme-laboratuvarı)
11. [Ders Geri Anlatımı (Feynman Tekniği)](#11-ders-geri-anlatımı-feynman-tekniği)
12. [Uzmanlık (Residency) Deneme Mülakatları](#12-uzmanlık-residency-deneme-mülakatları)
13. [Serbest İngilizce Salonu & Özel Senaryolar](#13-serbest-ingilizce-salonu--özel-senaryolar)
14. [Geri Bildirim Raporunun Çözümlenmesi (7 Ana Bölüm)](#14-geri-bildirim-raporunun-çözümlenmesi-7-ana-bölüm)
15. [Sokratik AI Eğitmeni & Her Zaman Açık 1:1 Ses Koçu](#15-sokratik-ai-eğitmeni--her-zaman-açık-11-ses-koçu)
16. [Aralıklı Tekrar Hata İzleyici & Hata Genomu](#16-aralıklı-tekrar-hata-izleyici--hata-genomu)
17. [Kıdemli Hekime Vaka Sunumu Zinciri (Attending Case Presentation Chaining)](#17-kıdemli-hekime-vaka-sunumu-zinciri-attending-case-presentation-chaining)
18. [Harici Konuşma Geçmişini İçe Aktarma (Import Transcript)](#18-harici-konuşma-geçmişini-içe-aktarma-import-transcript)
19. [Anki Desteleri & Word Dokümanı Dışa Aktarımları](#19-anki-desteleri--word-dokümanı-dışa-aktarımları)
20. [Uygulama İçi Yardım Vikisi](#20-uygulama-içi-yardım-vikisi)
21. [Kullanıcı Arayüzü Dil Ayarları, API Maliyet Takibi & Tercihler](#21-kullanıcı-arayüzü-dil-ayarları-api-maliyet-takibi--tercihler)
22. [Sıkça Sorulan Sorular (SSS) & Puanlama Rubriği](#22-sıkça-sorulan-sorular-sss--puanlama-rubriği)

---

## 1. API Anahtarı Kurulumu ve Yapılandırma

Bedside English; Google Gemini, OpenAI ve Anthropic Claude arka uçlarını esnek bir şekilde destekler.

### 💡 Önerilen Kurulum (Google Gemini Tek Anahtar Modu)

**Tek bir Google Gemini API anahtarı kaydetmek, gerçek zamanlı sesli diyaloglardan oturum sonrası geri bildirim analizine kadar uygulamanın tüm özelliklerini en hızlı ve en uygun maliyetli şekilde etkinleştirir.**

| Hizmet                 | Birincil Amaç                                                            | Gerekli / İsteğe Bağlı                           | Bağlantı                                               |
| :--------------------- | :----------------------------------------------------------------------- | :----------------------------------------------- | :----------------------------------------------------- |
| **Google (Gemini)**    | Gerçek zamanlı sesli konuşma (Gemini Live) + Derin geri bildirim analizi | **Gerekli (Tek anahtar tüm özellikleri kapsar)** | [aistudio.google.com](https://aistudio.google.com)     |
| **OpenAI**             | Gerçek zamanlı ses (OpenAI Realtime) + Geri bildirim + Premium TTS       | İsteğe Bağlı                                     | [platform.openai.com](https://platform.openai.com)     |
| **Anthropic (Claude)** | Oturum geri bildirim analizi (seçilebilir arka uç)                       | İsteğe Bağlı (Gemini varsayılandır)              | [console.anthropic.com](https://console.anthropic.com) |

### API Anahtarı Girişi ve Güvenliği

API anahtarları doğrudan uygulamanın içerisine girilir:

- **İlk Çalıştırma Onboarding Sihirbazı** sırasında veya üst çubuktaki **Ayarlar simgesi (⚙️) → Tercihler → API Keys** menüsünden yapılandırın.
- Girilen API anahtarları cihaz üzerindeki şifreli depolamada (`EncryptedSharedPreferences`) **güvenli bir şekilde saklanır** ve asla harici sunuculara gönderilmez.
- Her anahtar alanı, anahtar görünürlüğünü değiştirmek için sağda bir göz simgesi içerir.

### 🎈 Demo Modu (Tamamen Ücretsiz Deneme)

Uygulamayı bir API anahtarı kaydetmeden veya mikrofon izinleri vermeden deneyimlemek istiyorsanız, onboarding ekranında veya **Tercihler** altında **Demo** modunu seçin.
Senaryolu deneme diyalogları ve geri bildirim verileri yüklenecek, böylece jeton (token) harcamadan **ücretsiz** olarak tüm kullanıcı arayüzünü, hata izleyiciyi ve inceleme özelliklerini keşfetmenize olanak tanıyacaktır. Bir demo oturumunu tamamladıktan sonra, bir API anahtarı girmenizi veya istediğiniz zaman bir sonraki demo hasta pratiği ile devam etmenizi sağlayan bir istem belirir.

---

## 2. Uygulama Kurulumu ve İzin Ayarları

Bedside English, Android 8.0 (API Seviyesi 26) veya daha yüksek bir sürümü çalıştıran akıllı telefon ve tabletlerde çalışır.

### Uygulama Kurulumu

- Sağlanan kurulum dosyasını (`.apk`) Android cihazınızda başlatın ve kurmak için ekrandaki talimatları izleyin.

### Mikrofon İzni ve Yazarak Yanıtla Modu

- Canlı sesli pratik modunu ilk kez başlatırken, Android OS mikrofon erişim izni ister. Ses tanımanın düzgün çalışması için **[İzin Ver]** seçeneğine dokunun.
- Konuşmanın zor olduğu bir ortamdaysanız veya izin reddedilirse uygulama çökmez. Otomatik olarak **Type instead (Bunun yerine yaz)** moduna geçer ve klavye girişini kullanarak konuşma pratiği yapmanıza olanak tanır.

### Bildirim İzni ve Ses Ön Kontrolü (Audio Preflight)

- Arka plan geri bildirim analizi tamamlandığında bildirim alabilmeniz için bildirim izni istenir.
- İlk canlı sesli oturumunuzu başlatmadan hemen önce, kulaklık kullanımına rehberlik etmek ve mikrofon giriş seviyelerini test etmek için bir **Audio Preflight (Ses Ön Kontrolü)** bilgi penceresi görüntülenir, bu da hoparlör geri besleme döngülerini (howling - uğultu) önler.

---

## 3. İlk Çalıştırma Onboarding & L1 Öğrenici Özelleştirmesi

Uygulamayı ilk kez başlatırken, özelleştirilmiş bir öğrenme ortamı oluşturmak için 4 adımlı bir kurulum sihirbazı çalışır:

1. **Hoş Geldiniz Ekranı**: Temel özellikleri tanıtır ve API anahtarları olmadan keşfetmek için bir **Try Demo Mode (Demo Modunu Dene)** düğmesi sağlar.
2. **Kullanıcı Arayüzü Dil Seçici**: Tercih ettiğiniz arayüz dilini seçin (Desteklenen 8 dil: İngilizce, Korece, İspanyolca, Çince, Arapça, Hintçe, Portekizce, Tagalogca).
3. **API Keys Kurulumu**: Google Gemini veya diğer AI API anahtarlarınızı kaydedin.
4. **Ana Dil & Gizlilik**: Birinci dilinizi seçin (ör. **Korece**, Çince, İspanyolca, Arapça, Hintçe, Tagalogca, Portekizce). Bu, ana dilinizin spesifik müdahale (interference) kalıplarına göre uyarlanmış hassas dilbilgisi ve telaffuz analizini etkinleştirir.

### 🌐 L1 Ana Dil Özelleştirme Önemli Noktaları (ör. Koreli L1 Öğreniciler)

- **Gramer & İfade Düzeltmeleri**:
  - Eksik tanımlıklar (isimlerden önce _a/an/the_ atlanması)
  - Eksik çoğul _-s_ eki (_two patient_ → _two patients_)
  - Zaman hataları (geçmiş tıbbi öyküyü tartışırken şimdiki zaman kullanımı)
  - Edatların (preposition) yanlış kullanımı (_in hospital_, _explain to patient_ içindeki edatların atlanması)
  - Birebir doğrudan çeviriler / Konglish (_skin scale_, _side effect_ kelimelerinin garip doğrudan çevirisi)
- **Telaffuz & Anlaşılırlık Düzeltmeleri**:
  - _r / l_ minimal çift ayrımı (_liver_ vs _river_)
  - _f / p_ ayrımı (_fever_ vs _peter_)
  - _th_ dişıl (dental) sürtünmeli ünsüz telaffuzu (_think_ vs _tink_)
  - Atlanan son ünsüzler ve gereksiz ünlü harf eklemeleri (_cardiac_ → _cardi-ack-eu_)
  - Tıbbi kelime vurgusunun yanlış yerleştirilmesi (_angina_, _arrhythmia_)

### 💡 Etkileşimli İlk Çalıştırma Turu

Onboarding'i tamamlayıp Ana Panoya ilk kez girdikten sonra, etkileşimli bir eğitim sizi ana düğmelerin (Ana Pano, Pratik Merkezi, Telaffuz Lab., SRS İncelemeleri, Geçmiş, Yardım, 1:1 Ses Koçu) konumları ve işlevleri boyunca otomatik olarak yönlendirir.

---

## 4. Kullanıcı Arayüzü Düzenine Genel Bakış (Alt Gezinme 5 Sekme & Yardım)

### Alt Gezinme Çubuğu (5 Sekme)

Ana gezinme 5 alt sekmeden oluşur:

```
┌───────────┬──────────────┬───────────────────────┬───────────────┬─────────────┐
│  🏠 Home  │  ▶ Practice  │  🎙️ Pronunciation Lab │  ⚠️ SRS Reviews│  🕘 History │
└───────────┴──────────────┴───────────────────────┴───────────────┴─────────────┘
```

1. **Home (Ana Pano)**: Pratik serisi (`🔥`), 5 dakikalık klinik görev, performans eğilimi grafikleri, Hata Genomu, yol haritası ve **Her zaman açık 1:1 Ses Koçu**.
2. **Practice (Pratik Merkezi)**: Tüm gerçek zamanlı konuşma modları için merkezi bağlantı noktası: Hasta Karşılaşmaları, Sınav & Teşhis, Hayatta Kalma İngilizcesi, Geri Anlatım (Teach-back), Mülakatlar, Salon ve Özel Senaryolar.
3. **Pronunciation Lab (Telaffuz Lab.)**: Konuşma anlaşılırlığı ve telaffuz düzeltmesi için özel eğitim sekmesi (hata kalıbı filtreleme, gözlem durumu yönetimi).
4. **SRS Reviews (Zayıf Nokta İncelemesi)**: Kabul edilen düzeltme cümleleri için aralıklı tekrar algoritmalarına dayalı sesli inceleme testleri.
5. **History (Oturum Geçmişi)**: Geçmiş oturumlardaki puanları ve geri bildirimleri görüntüleyin, jeton maliyetlerini takip edin, Anki/Word'e aktarın ve **Kıdemli Hekime Vaka Sunumunu (Present Case)** tetikleyin.

### Üst Uygulama Çubuğu

- **Bedside English Logosu**: Ana başlık.
- **Yardım Vikisi (`?` Simgesi)**: `?` simgesine dokunmak, İçindekiler gezintisi ve tam metin kelime araması içeren bu Kullanım Kılavuzunu tam ekran görüntüleyicide açar.
- **Ayarlar Çarkı (⚙️ Tercihler)**: API anahtarları, ses arka uçları, konuşma hızı, yankı önleme, dil ve veri yönetimi.

---

## 5. Ana Panoda (Home) Uzmanlaşma

Ana Pano, klinik İngilizce iletişim becerilerinizdeki gelişimi çok boyutlu olarak görsel bir şekilde sunar:

- **Pratik Serisi (Streak)**: Günlük çalışma alışkanlıkları oluşturmak için bir alev simgesiyle (`🔥`) üst üste aktif pratik günlerini görüntüler.
- **Temel Metrik Kartları**:
  - **Sessions done**: Tamamen tamamlanan ve analiz edilen toplam oturum sayısı.
  - **Errors tracked**: Kişisel zayıflık veritabanınıza kaydedilen onaylanmış düzeltmeler.
  - **Mastered**: Tekrarlanan inceleme testleriyle çözülen ve ustalaşılan hatalar.
  - **Due now**: Bugün sesli inceleme için planlanan SRS inceleme kartlarının sayısı.
  - **Stubborn**: Odaklanmış yönetim gerektiren üst üste 4+ kez kaçırılan "Sülük (Leech)" hatalar.
- **Bugünün 5 Dakikalık Klinik Görevi (Today's 5-Minute Clinical Mission)**: Vadesi gelen inceleme öğelerini, gerekli teşhisleri veya en zayıf beceri alanınızı hedefleyen optimum 5 dakikalık pratik kursunu otomatik olarak önerir.
- **OET Halk Dili (Layman) Kelime Kapsamı**:
  - Karmaşık tıbbi terimler (ör. _syncope_) yerine hasta dostu sade terimleri (ör. _fainting_) ne kadar etkili kullandığınızı takip eder.
  - Kullanılan terimler **Son Zamanlarda Açılanlar (Recently Unlocked)** altında görünürken, kullanılmayan ifadeler **Sonraki Hedefler (Kilitli) (Next Goals)** altında sıraya girer.
- **Hata Genomu Paneli**: En sık yaptığınız hata kategorilerini (Tanımlıklar, Çoğullar, Zamanlar, Edatlar, Dil Seviyesi (Register), Doğrudan Çeviriler) analiz eder ve en zayıf 5 alanınızı sade dilli ipuçlarıyla bir çubuk grafik olarak görüntüler.
- **Büyüme Eğilimleri & L1 Müdahale Grafiği**:
  - Son 20 oturumunuzdaki 5 alandaki (Gramer, Doğruluk, Akıl Yürütme, Profesyonellik, Akıcılık) puan eğilimlerini grafiksel olarak gösterir.
  - Tekrarlayan dilbilgisi hatası kalıplarını görselleştirir.
- **Kişiselleştirilmiş Yol Haritası**: Zayıf metrikleri ve hata geçmişini analiz ederek önceliklendirilmiş 4 odak beceri kartı sunar.
- **Her Zaman Açık 1:1 Ses Koçu Düğmesi (`🎙️ RecordVoiceOver`)**:
  - Ana Panonun sağ altında yer alır. Tam bir senaryo başlatmadan, biriken SRS zayıf noktalarınıza dayalı bir yapay zeka eğitmeni ile anında 1:1 sesli konuşma başlatmak için dokunun.

---

## 6. Pratik Merkezi & Aşamalı Özellik Kilitlerini Açma

### Aşamalı Özellik Kilitlerini Açma

Yeni kullanıcıların bunalmış hissetmesini önlemek için, ilk kez kullananlar temel modları (**Dashboard**, **Patient Encounters**, **Survival English**, **History**) görüntüleyen sakin bir giriş ekranıyla başlar.

- **İlk pratik oturumunuzu tamamlamak**, gelişmiş modların (Exam, Teach-back, Interview, Lounge, Custom) kilitlerini bir kutlama mesajıyla **otomatik olarak açar**.
- Tüm modları hemen genişletip ortaya çıkarmak için Pratik ekranından da dokunabilirsiniz.

### Pratik Merkezi Ana Mod Kategorileri

1. **Patient Encounters (Hasta Karşılaşmaları)**: Öykü alma, takip, Foundations (Temeller) başlangıç modu, Skill Drills (Beceri Egzersizleri), Practice My Mistakes (Hatalarımı Pratik Et).
2. **Exam & Diagnostic (Sınav & Teşhis)**: 10 dakikalık Baseline Diagnostic (Başlangıç Çizgisi Teşhisi), OSCE, OET, Residency (Uzmanlık) ve Ward Round (Vizit) deneme sınavları.
3. **Survival English & Listening Lab (Hayatta Kalma İngilizcesi & Dinleme Lab.)**: Hastane içi beklenmedik durumlar, hızlı küçük sohbetler, 15 anadili aksan profili, Dinleme Laboratuvarı detay egzersizleri.
4. **Lecture Teach-back (Ders Geri Anlatımı)**: YouTube/metin özetlerine dayalı Feynman tekniği eğitimi.
5. **Residency Interviews (Uzmanlık Mülakatları)**: Behavioral (Davranışsal), Clinical (Klinik) ve IMG'ye özel deneme mülakatları.
6. **Free English Lounge (Serbest İngilizce Salonu)**: Tıbbi tartışmalar, haber altyazı tartışmaları, iş yeri çatışma çözümü.
7. **Custom Scenarios (Özel Senaryolar)**: Özel yapay zeka istemleri ve puanlama rubrikleri oluşturun.

---

## 7. Hasta Karşılaşmaları & Canlı Öykü Kapsamı İzleyici

Klinik iletişimin özü olan başucu öyküsü alma (history taking) ve hasta danışmanlığını simüle eder.

### 7-1. Operasyonel Alt Modlar

- **Foundations (Temeller) Modu**: Erken öğrenenler için klinik akıl yürütme yüklerini ortadan kaldırır, kesinlikle **gramer, klinik kelime bilgisi, ilişki kurma (rapport building) ve akıcılığa** odaklanır.
- **Skill Drills (Beceri Egzersizleri)**: Hedeflenen mikro yeterlilik egzersizleri (NURSE empati tekniği, sade dille açıklamalar, gece vardiyası devri, **Ana Dil → İngilizce sıralı tıbbi çeviri (interpreting)**).
- **Practice My Mistakes (Hatalarımı Pratik Et)**: Veritabanınızdaki bekleyen hatalardan anında sesli bir diyalog testi sentezler.
- **Daily Mission (Günlük Görev)**: Mevcut beceri açıklarınızı hedefleyen uyarlanabilir 5 dakikalık günlük bir zorluk.

### 7-2. Canlı Öykü Kapsamı İzleyici

Siz konuştukça öykü alma maddelerini işaretleyen daraltılabilir gerçek zamanlı bir panel:

- Yapay zeka konuşma bağlamına göre öğeleri otomatik olarak takip eder.
- Başlangıç/süre, ağrı karakteri, yayılımı, ağırlaştıran/hafifleten faktörler, ilişkili semptomlar, ICE (Fikirler, Endişeler, Beklentiler), geçmiş öykü, ilaçlar, alerjiler, alkol/sigara, aile öyküsü vb. izler.
- _"You don't smoke, do you? (Sigara kullanmıyorsunuz, değil mi?)"_ gibi olumsuz doğrulama soruları doğru bir şekilde tanınır ve izlenir.

### 7-3. Bağlama Duyarlı Devam Etme Yardımı (`💡 Help me continue`)

Oturumun ortasında takılırsanız veya sorunuz kalmazsa, ekranın altındaki **💡 Help me continue** düğmesine dokunun.

- **Kullanıma hazır bir İngilizce örnek cümle** ile birlikte bir sonraki mantıklı soru hedefini önermek için hastanın son yanıtını analiz eder.
- Üstteki Röportaj Aşaması İzleyicisi, durum simgelerini kullanarak ilerlemeyi görüntüler:
  - `✓`: Yeterli kanıt tespit edildi
  - `•`: Kısmi bahsetme tespit edildi
  - `?`: Önceki aşama doğrulanmadan sonraki aşamaya ulaşıldı

---

## 8. Sınav & Teşhis Modu (10 Dakikalık Başlangıç Çizgisi & CEFR Eşlemesi)

Zaman kısıtlamalı, sürükleyici sınav koşulları altında iletişim yeterliliğini ölçer:

### 10 Dakikalık Başlangıç Çizgisi Klinik İngilizce Teşhisi

- Bir sınav görevlisi tanıtımıyla başlar, ardından 4 kısa görev gelir (bir teşhisi açıklama, takip sorularını yanıtlama, 45 saniyelik bir SBAR devri (handover) sunma, bir uzmanlık (residency) mülakatı sorusunu yanıtlama).
- Performansınızı uluslararası **CEFR derecelerine** otomatik olarak eşleştirir:
  - **Puan >= 8.5**: **C1** (Kıdemli hekim düzeyinde akıcı, güvenli klinik iletişim)
  - **Puan >= 7.2**: **B2+** (Klinik staj ve hastane uygulaması için yetkin)
  - **Puan >= 6.0**: **B1-B2** (Temel iletişim yeteneği; yapılandırılmış çalışma önerilir)
  - **Puan < 6.0**: **A2-B1** (Temel klinik iletişim eğitimi gereklidir)

### Deneme Sınavı Senaryoları

- **OSCE**: Mr. Hayes göğüs ağrısı öyküsü alma (ICE ölçümü, kırmızı bayrak tespiti, empati).
- **OET Speaking**: Hipertansiyon hastası danışmanlık rol yapma oyunu (roleplay).
- **Residency (Uzmanlık)**: ABD Dahiliye Program Direktörü deneme mülakatı.
- **Ward Round (Vizit)**: 5 dakikalık toplum kökenli pnömoni vaka sunumu ve sözlü soru yanıtlama.

### Puan Güvenilirlik Rozeti (Score Reliability)

Yapay zeka puanlama güvenini **High (Yüksek)**, **Medium (Orta)** veya **Low (Düşük)** olarak belirtir:

- **High**: Öğrenici kelime sayısı >= 180 kelime & kontrol listesi kanıt oranı >= %75.
- **Medium**: Öğrenici kelime sayısı >= 80 kelime & kontrol listesi kanıt oranı >= %50.
- **Low**: Kelime sayısı < 80 kelime (Kısa Transkript işareti) veya kanıt oranı < %50.

---

## 9. Telaffuz & Anlaşılırlık Laboratuvarı (Pron Lab)

3. özel alt sekmede (`🎙️ Pron Lab`) yer alan bu laboratuvar, konuşma anlaşılırlığı (intelligibility) için özel eğitim merkezinizdir.

### 💡 Anlaşılırlık Odaklı Koçluk

Amaç anadili aksanını taklit etmek değil, **"Uluslararası meslektaşlarım ve hastalarım konuşmamı yanlış anlama olmadan net bir şekilde anlayabiliyor mu?"** sorusuna yanıt vermektir.

- Ses analizi, yalnızca dinleyicinin yanlış anlamasına neden olan telaffuz öğeleri üzerinde nokta atışı koçluk sağlar.

### Hata Kalıbı Kategorileri & Filtre Çipleri

Oturumlarınız boyunca tespit edilen telaffuz hataları kategoriye göre filtre çiplerinde düzenlenir:

- `r · l`: _liver / river_, _clinical / critical_
- `f · p`: _fever / peter_, _palpation / falcation_
- `th`: _think / tink_, _throat / troat_
- `final`: Atlanan son ünsüz harfler (_chest / ches_)
- `cluster`: Ünsüz kümesi (consonant cluster) işleme & gereksiz ünlü harf ekleme (_cardiac_ → _cardi-ack-eu_)
- `stress`: Tıbbi kelime vurgusunun yanlış yerleştirilmesi (_angina_, _arrhythmia_)
- `vowel`: Kısa ve uzun ünlü harf karışıklığı (_ship / sheep_, _fit / feet_)

### Gözlemlenen Durum (Observed State) Yükseltme Kuralı

Kabul edilen bir telaffuz kartı ilk kaydedildiğinde, hemen günlük bir ödev kartı olmak yerine bir **Observed (Gözlemlenen)** durumuna girer. Yalnızca aynı hata kalıbı ayrı bir oturumda tekrarlandığında aktif bir SRS inceleme hata kartına yükseltilir, böylece tek seferlik ses tanıma hatalarının (glitches) öğrencilere aşırı yük oluşturması önlenir.

---

## 10. Hayatta Kalma İngilizcesi & Dinleme Laboratuvarı

IMG'leri muayene odası dışındaki gerçek dünyadaki klinik dışı hastane etkileşimlerine hazırlar.

- **Random / Surprise (Rastgele / Sürpriz) Modu**: Yapay zeka konuşana kadar senaryo bağlamı gizlenerek beklenmedik durumların kendiliğinden yönetilmesi (koridor (curbside) soruları, eczane geri aramaları, hemşire küçük sohbetleri).
- **Rapid-fire Small Talk (Hızlı Küçük Sohbet)**: Ani konu değişikliklerine hızlı yanıtlar.
- **15 Anadili Aksan Profili**: Uluslararası anadili aksanlarına ve konuşma ritimlerine uyum sağlama pratiği.
- **Gerçek Zamanlı Ses Hızı Kontrolü (0.5× - 2.5×)**: Hızlı konuşan anadili konuşucularına uyum sağlamak için tonu koruyan oynatma hızı kaydırıcısı.
- **Yalnızca Kulak Dinleme Modu (Ear-only Listening Mode)**: Yapay zeka konuşma altyazılarını gizler, böylece tamamen dinlemeye güvenirsiniz; gerektiğinde **[Reveal last line] (Son satırı göster)** seçeneği mevcuttur.
- **Onarım İfadesi Teşviki (Repair Expression Encouragement)**: _"Sorry?", "Could you say that again?"_ gibi onarım stratejilerini kullanmak puan düşürmek yerine **bonus puan** kazandırır.
- **Dinleme Laboratuvarı (Listening Lab)**: Maddeleştirilmiş doğruluk puanlamasıyla kesin ayrıntı kavrayışını (sayılar, ilaç dozajları, hasta isimleri, zamanlar, yönler, fiyatlar) test eden egzersizler.

---

## 11. Ders Geri Anlatımı (Feynman Tekniği)

Tıbbi bilgileri sağlamlaştırmak için Feynman tekniğini—kavramları başkasına öğretiyormuş gibi yüksek sesle açıklamayı—kullanır.

1. **Ders Materyalini Hazırlayın**: Özet notları yapıştırın veya bir YouTube tıbbi ders URL'si girin ve **`🎬 Fetch YT transcript`** düğmesine dokunun.
2. **Yapay Zeka Sıkıştırması (AI Condensation)**: Uzun materyaller için metni yapılandırılmış 500 kelimelik bir taslağa (outline) sıkıştırmak üzere **`✨ Condense`** düğmesine dokunun.
3. **İzleyici Personasını Seçin**:
   - **Sözlü Sınav Profesörü**: Keskin, zorlu klinik "Neden" ve "Ya şöyle olursa (What-if)" takip soruları sorar.
   - **Kafası Karışık Sınıf Arkadaşı**: Ağır terimler (jargon) olmadan sade dille açıklamalar ister.
   - **Dost Canlısı Eğitmen**: Destekleyici teşvik ve ifade rehberliği sağlar.
4. **Öğretin**: **Start (Başlat)**'a dokunun ve yapay zeka dinleyicisi açıklayıcı sorularla yanıt verirken mikrofon aracılığıyla açıklayın.

---

## 12. Uzmanlık (Residency) Deneme Mülakatları

Yurt dışı hastane istihdamı ve ABD Uzmanlık Eşleşmesi (Residency Match) için gerçekçi mülakatları simüle eder:

- **Behavioral (Davranışsal)**: STAR yöntemi (Durum, Görev, Eylem, Sonuç) deneyim açıklamaları.
- **Clinical (Klinik)**: Sözlü vaka sunumu, tıbbi etik ve acil durum yönetim mantığı.
- **IMG'ye Özel**: Yaygın IMG sorularına odaklanır (vize sponsorluğu, CV boşluk yılı (gap year) açıklamaları, bir IMG olarak benzersiz güçlü yönler).
- Bir yapay zeka Program Direktörü kibar ama sorgulayıcı takip sorularına liderlik eder.

---

## 13. Serbest İngilizce Salonu & Özel Senaryolar

### Serbest İngilizce Salonu (Free English Lounge)

- Güncel tıbbi konuları tartışın, dergi (journal) makalelerini özetleyin, iş yeri/hemşire çatışmalarını çözün veya kahve molası küçük sohbet pratiği yapın.
- Yapay zeka ile özgürce tartışmak için YouTube tıbbi haber altyazılarını çekin.

### Özel Senaryolar (Custom Scenarios)

İhtiyaçlarınıza göre uyarlanmış özel pratik senaryoları oluşturun:

- **Scenario Name (Senaryo Adı)**: Özel tanımlayıcı.
- **Persona / Context (Bağlam)**: Yapay zekanın rolünü ve durumunu tanımlayan sistem istemi.
- **Eval Template (Değerlendirme Şablonu)**: Değerlendirme rubriklerini seçin (ör. kötü haber verme için SPIKES protokolü).
- **Custom Eval Criteria (Özel Değerlendirme Kriterleri)**: Yapay zekanın kontrol etmesi için özel temel değerlendirme noktaları belirleyin.

---

## 14. Geri Bildirim Raporunun Çözümlenmesi (7 Ana Bölüm)

Bir oturumu tamamladıktan sonra, 7 bölümlü bir rapor çok boyutlu geri bildirim sağlar:

1. **Puanlar (Scores)**: Yapay zeka alan puanlarını (0-10) öz değerlendirmenizle yan yana karşılaştırır. **2.0+ puanlık** bir fark, öz yansımaya rehberlik etmek için sarı bir **Reflection Prompt (Yansıma İstemi)** kartını tetikler.
2. **Akıcılık (Fluency)**:
   - **WPM (Dakika Başına Kelime)**: Konuşma hızını önerilen hedeflere (100–130 WPM) göre ölçer.
   - **Dolgu Kelimeler (Filler Words)**: Dolgu kelime yoğunluğunu (`um`, `uh`, `like`) ölçer, duraklamaların etkili kullanımını teşvik eder.
3. **Kontrol Listesi (Checklist)**: Klinik hedefleri değerlendirir, kesin döküm cümlelerini **Evidence (Kanıt)** olarak alıntılar.
4. **SOAP Notu Karşılaştırması**: Oturumunuzdan otomatik olarak oluşturulan bir SOAP notunu bir model referans SOAP notuyla karşılaştırır.
5. **Düzeltmeler (Corrections)**: Doğrudan çeviriler, tanımlıklar/çoğullar, doğal olmayan ifadeler ve telaffuz için kart tabanlı düzeltmeler. **[Accept] (Kabul Et)** seçeneğine dokunmak, öğeyi kişisel SRS hata izleyicinize kaydeder.
6. **Gölgeleme (Shadowing)**: Dinle ve tekrar et ses eğitimi için zayıf cümleleri kıdemli hekim (attending) düzeyinde klinik İngilizceye yeniden yazar.
7. **Özet & Paylaşım Kartları**: Genel geri bildirimi görüntüler ve performans özetlerini çalışma arkadaşlarıyla (study peers) paylaşmak için bir **Share Card (Paylaşım Kartı)** görsel oluşturucusu sağlar.

---

## 15. Sokratik AI Eğitmeni & Her Zaman Açık 1:1 Ses Koçu

### 15-1. Sokratik AI Eğitmeni 1:1 Değerlendirme (`[Debrief with AI Tutor]`)

Geri bildirim raporunun altındaki **[Debrief with AI Tutor]** düğmesine dokunmak, Sokratik bir yapay zeka mentoru ile 1:1 sohbet odası açar.

- Doğrudan cevaplar vermek yerine yönlendirici sorular sorarak hataları kendinizin keşfetmesine ve düzeltmesine yardımcı olur.
- Değerlendirme sohbet geçmişi veritabanında korunur, böylece istediğiniz zaman geri dönüp devam edebilirsiniz.

### 15-2. Her Zaman Açık 1:1 Konuşma Ses Koçu (Ana Pano FAB)

Ana Panonun sağ alt tarafındaki **Voice Coach Floating Button (Ses Koçu Yüzen Düğmesine) (`🎙️ RecordVoiceOver`)** dokunmak, önce tam bir senaryoyu tamamlamadan anında bir konuşma koçu diyalogu açar.

- Yapay zeka koçu, birikmiş SRS zayıf noktalarınıza dayalı özelleştirilmiş 1:1 sesli konuşmaları yönetir.

---

## 16. Aralıklı Tekrar Hata İzleyici & Hata Genomu

**[Accept] (Kabul Et)** aracılığıyla kabul edilen düzeltmeler, hata veritabanınızdaki aralıklı tekrar (spaced repetition) algoritmaları tarafından otomatik olarak yönetilir.

- **Bulanık Çift Tespiti (Fuzzy Duplicate Detection)**: Benzer hataların yinelenen kaydını otomatik olarak önler.
- **Sülük (Leech) / İnatçı Hatalar (Stubborn Errors)**: İnceleme sınavlarında üst üste 4+ kez kaçırılan öğeler, odaklanmış yönetim için **Stubborn / Leech** olarak etiketlenir.
- **Bilimsel Aralıklı Tekrar Aralıkları**:
  - İnceleme programı: **1 gün → 3 gün → 7 gün → 14 gün → 30 gün**. Üst üste 3 incelemeyi geçmek öğeyi **Mastered (Ustalaşıldı)** durumuna geçirir.
  - **Practice My Mistakes (Hatalarımı Pratik Et)** modundaki sesli testleri temizlemek, öğeleri Ustalaşmaya (Mastery) doğru ilerletir.
- **Hata Genomu Paneli**:
  - En zayıf 5 hata kategorinizi (Tanımlıklar, Çoğullar, Zaman, Edatlar, Dil Seviyesi (Register), Doğrudan Çeviriler) sade dilli ipuçlarıyla bir Ana Pano çubuk grafiği olarak görüntüler.

---

## 17. Kıdemli Hekime Vaka Sunumu Zinciri (Attending Case Presentation Chaining)

Bir Hasta Karşılaşmasının ardından bir süpervizöre vaka sunarak sözlü devir (handoff) becerilerini eğitin:

1. Bir **Patient Encounter (Hasta Karşılaşması)** oturumunu tamamlayın.
2. **History (Geçmiş)** sekmesine gidin, oturumu seçin ve **`📋 Present Case`** düğmesine dokunun.
3. Yapay Zeka Kıdemli Hekimi (Attending) şu sözlerle açılır: _"Doctor, please present the case you just saw. (Doktor, lütfen az önce gördüğünüz vakayı sunun.)"_
4. SBAR veya SOAP formatını kullanarak sözlü bir vaka sunumu yapın ve ayırıcı tanı ve tedavi planlarıyla ilgili takip sorularını yanıtlayın.

---

## 18. Harici Konuşma Geçmişini İçe Aktarma (Import Transcript)

Tam geri bildirim almak için ChatGPT, Gemini veya klinik notlardan konuşma metnini uygulamaya içe aktarın:

1. **Android Paylaşım Entegrasyonu**: Harici uygulamalardaki konuşma metnini vurgulayın ve **Import Transcript** ekranını otomatik olarak açmak için **[Share] → [Bedside English]** seçeneğini belirleyin.
2. **Doğrudan Giriş / Yapıştırma**: Doğrudan Tercihler'den veya ana menüden `Import Transcript` ekranını açın ve metni yapıştırın.
3. **Otomatik Geri Bildirim & SRS**: SRS kabulü için mevcut olan alan puanları, SOAP notları ve düzeltme kartları oluşturur.

---

## 19. Anki Desteleri & Word Dokümanı Dışa Aktarımları

- **Anki Kartı (sekmeyle ayrılmış `.txt`) Dışa Aktarma**:
  - Kabul edilen düzeltmeleri bir Anki düz metin içe aktarma dosyasına dönüştürür (Anki / AnkiDroid'de Dosya → İçe Aktar). Her hatanın kategorisi bir etiket olarak taşınır. Hem oturum sonrası geri bildirim ekranında hem de tüm inceleme listenizi tek seferde dışa aktaran **My Mistakes (Hatalarım)** ekranında mevcuttur.
- **Word Raporu (`.docx`) Dışa Aktarma**:
  - Puanları, kontrol listesi kanıtlarını, SOAP notlarını ve cümle düzeltmelerini içeren yapılandırılmış tıbbi raporlar oluşturur. Tercihler (Preferences) seçeneği otomatik kaydetmeyi etkinleştirir.

---

## 20. Uygulama İçi Yardım Vikisi

Üst uygulama çubuğundaki **`?` Yardım simgesine** dokunmak, yerleşik Yardım Vikisi görüntüleyicisini tam ekran modunda açar.

- **Çok Dilli Entegrasyon**: Uygulamanın kullanıcı arayüzü dil ayarıyla eşleşen kullanım kılavuzu dosyasını otomatik olarak yükler.
- **İçindekiler (TOC) Kenar Çubuğu**: Tüm kılavuz bölümleri arasında hızlı atlama gezintisi sağlar.
- **Tam Metin Arama**: Eşleşmeleri vurgulamak ve önceki/sonraki düğmeleriyle gezinmek için arama çubuğuna anahtar kelimeler girin.
- **Harici Hiper Bağlantılar**: Kılavuzdaki web bağlantılarına dokunmak varsayılan sistem tarayıcınızı açar.

---

## 21. Kullanıcı Arayüzü Dil Ayarları, API Maliyet Takibi & Tercihler

Uygulamayı cihazınıza ve bütçenize göre uyarlamak için üst çubuktaki **Ayarlar çarkına (⚙️)** erişin:

- **Ses & Geri Bildirim**:
  - Ses ve geri bildirim yapay zeka modellerini seçin (Demo, Gemini, OpenAI, Claude).
  - **Echo Prevention (Yankı Önleme)**: Ses geri besleme döngülerini önlemek için yapay zeka konuşurken mikrofonu otomatik olarak sessize alır (kulaklık kullanılmadığında gereklidir).
  - **AI Speaking Pace (Yapay Zeka Konuşma Hızı)**: Kademeli hız kontrolü (Yavaş, Normal, Hızlı, Meydan Okuma).
- **API Maliyet Analitiği (API Cost Analytics)**: Grafik görselleştirmeleriyle jeton (token) kullanımını ve oturum başına tahmini dolar maliyetini şeffaf bir şekilde izler.
- **Ses**: Gerçek zamanlı mikrofon seviye göstergesi ve hoparlör test tonu.
- **API Anahtarları**: Yerel şifreli depolama anahtar yöneticisi.
- **Dışa Aktarma & Öğrenme**: Docx otomatik kaydetme seçenekleri, zorunlu SOAP notu girişi, Ana Dil ve Kullanıcı Arayüzü Dili ayarları.
- **Veri**: Telaffuz analizini açın/kapatın, motorları yapılandırın ve TTS gölgeleme motorlarını seçin.
- **Gizlilik**: Anonim kullanım telemetrisini açın/kapatın.

---

## 22. Sıkça Sorulan Sorular (SSS) & Puanlama Rubriği

### Sıkça Sorulan Sorular (SSS)

**S: Mikrofon sesi almıyor ve yapay zeka yanıt vermiyor.**

- Android **Ayarlar → Uygulamalar → Bedside English → İzinler → Mikrofon** seçeneğini kontrol edin ve [İzin Ver] olarak ayarlayın. İzin reddedilirse, uygulama **Type instead (Bunun yerine yaz)** moduna geçer, böylece klavye üzerinden pratik yapmaya devam edebilirsiniz.

**S: Gelişmiş pratik modları (Sınav, Geri Anlatım, Mülakat, Salon, Özel) eksik.**

- İlk pratik oturumunuzu tamamlayın; tüm gelişmiş modların kilidi bir kutlama mesajıyla otomatik olarak açılacaktır. Modları Pratik ekranından genişletip ortaya da çıkarabilirsiniz.

**S: Telaffuz analizi sonuçları hemen SRS inceleme veritabanıma gitmiyor.**

- Tek seferlik ses tanıma hatalarının öğrencilere yük olmasını önlemek için kabul edilen telaffuz öğeleri önce **Observed (Gözlemlenen)** durumuna girer. Yalnızca aynı hata kalıbı gelecekteki bir oturumda tekrarlandığında aktif SRS inceleme kartlarına yükseltilirler.

**S: Derecelendirme için harici konuşma transkriptlerini (ör. ChatGPT) içe aktarabilir miyim?**

- Evet. Metni Bedside English ile paylaşmak için Android'in paylaşım özelliğini kullanın veya tam geri bildirim, SOAP notları ve düzeltme kartları almak için metni `Import Transcript` ekranına yapıştırın.

---

### 📝 Detaylı Değerlendirme Puanlama Rubriği (0–10 Puan Aralığı)

| Puan       | Derecelendirme Seviyesi               | Değerlendirme Kriterleri                                                                                                                     |
| :--------- | :------------------------------------ | :------------------------------------------------------------------------------------------------------------------------------------------- |
| **9 ~ 10** | **Kıdemli Hekim (Attending) / Uzman** | Kusursuz gramer ve ifade; kesin klinik kelime bilgisi ve sistematik tıbbi akıl yürütme; doğal, profesyonel ton.                              |
| **7 ~ 8**  | **Yetkin / Geçer**                    | Küçük gramer hataları var ancak iletişim tamamen net; temel risk faktörlerini tanımlar ve ayırıcı tanıyı güvenle gerçekleştirir.             |
| **5 ~ 6**  | **Gelişmekte**                        | Dinleyicinin çaba göstermesini gerektiren sık yapısal gramer hataları; sistematik olmayan klinik akıl yürütme ve kelime bilgisi.             |
| **1 ~ 4**  | **Kritik / Başarısız**                | Ciddi tıbbi hatalar veya kaçırılan risk faktörleri; konuşma tek kelimelerle veya normal diyaloğu engelleyen sık uzun duraklamalarla sınırlı. |
