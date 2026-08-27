# Hướng dẫn sử dụng Bedside English

**Đối tượng sử dụng:** Bác sĩ tốt nghiệp y khoa quốc tế (IMG), bác sĩ nội trú, sinh viên y khoa và các chuyên gia y tế đang chuẩn bị cho tiếng Anh lâm sàng, OSCE, OET Speaking, Phỏng vấn nội trú Hoa Kỳ (US Residency Match), Trình bày bệnh án khi đi buồng (Ward Round) và Giao tiếp lâm sàng.

Bedside English là ứng dụng dành riêng cho Android được hỗ trợ bởi AI trò chuyện (Google Gemini, OpenAI Realtime, Anthropic Claude) và công nghệ giọng nói thời gian thực. Ứng dụng được thiết kế để rèn luyện kỹ năng giao tiếp lâm sàng, lập luận y khoa, phát âm và độ rõ ràng của lời nói theo nhiều chiều. Hướng dẫn này chi tiết tất cả các tính năng và cách sử dụng phiên bản ứng dụng mới nhất.

---

## 📌 Mục lục

1. [Cấu hình và thiết lập API Key](#1-cấu-hình-và-thiết-lập-api-key)
2. [Cài đặt ứng dụng và thiết lập quyền](#2-cài-đặt-ứng-dụng-và-thiết-lập-quyền)
3. [Onboarding lần đầu & Tùy chỉnh người học L1](#3-onboarding-lần-đầu--tùy-chỉnh-người-học-l1)
4. [Tổng quan giao diện (5 Tab điều hướng dưới & Trợ giúp)](#4-tổng-quan-giao-diện-5-tab-điều-hướng-dưới--trợ-giúp)
5. [Làm chủ Bảng điều khiển (Trang chủ)](#5-làm-chủ-bảng-điều-khiển-trang-chủ)
6. [Trung tâm luyện tập & Mở khóa tính năng lũy tiến](#6-trung-tâm-luyện-tập--mở-khóa-tính-năng-lũy-tiến)
7. [Cuộc gặp bệnh nhân & Trình theo dõi bao phủ bệnh sử trực tiếp](#7-cuộc-gặp-bệnh-nhân--trình-theo-dõi-bao-phủ-bệnh-sử-trực-tiếp)
8. [Chế độ Thi & Chẩn đoán (Chẩn đoán 10 phút & Ánh xạ CEFR)](#8-chế-độ-thi--chẩn-đoán-chẩn-đoán-10-phút--ánh-xạ-cefr)
9. [Phòng luyện phát âm & Độ rõ tiếng (Pron Lab)](#9-phòng-luyện-phát-âm--độ-rõ-tiếng-pron-lab)
10. [Tiếng Anh sinh tồn & Phòng luyện nghe](#10-tiếng-anh-sinh-tồn--phòng-luyện-nghe)
11. [Giảng lại bài học (Kỹ thuật Feynman)](#11-giảng-lại-bài-học-kỹ-thuật-feynman)
12. [Phỏng vấn mô phỏng nội trú](#12-phỏng-vấn-mô-phỏng-nội-trú)
13. [Góc tiếng Anh tự do & Tình huống tùy chỉnh](#13-góc-tiếng-anh-tự-do--tình-huống-tùy-chỉnh)
14. [Phân tích báo cáo phản hồi (7 phần chính)](#14-phân-tích-báo-cáo-phản-hồi-7-phần-chính)
15. [AI Tutor kiểu Socratic & Huấn luyện viên giọng nói 1:1 luôn sẵn sàng](#15-ai-tutor-kiểu-socratic--huấn-luyện-viên-giọng-nói-11-luôn-sẵn-sàng)
16. [Trình theo dõi lỗi lặp lãng quên & Bộ gen lỗi sai](#16-trình-theo-dõi-lỗi-lặp-lãng-quên--bộ-gen-lỗi-sai)
17. [Chuỗi trình bày bệnh án cho Bác sĩ điều trị](#17-chuỗi-trình-bày-bệnh-án-cho-bác-sĩ-điều-trị)
18. [Nhập lịch sử trò chuyện bên ngoài (Import Transcript)](#18-nhập-lịch-sử-trò-chuyện-bên-ngoài-import-transcript)
19. [Xuất bộ thẻ Anki & Báo cáo Word](#19-xuất-bộ-thẻ-anki--báo-cáo-word)
20. [Wiki trợ giúp trong ứng dụng](#20-wiki-trợ-giúp-trong-ứng-dụng)
21. [Cài đặt ngôn ngữ giao diện, Theo dõi chi phí API & Tùy chọn](#21-cài-đặt-ngôn-ngữ-giao-diện-theo-dõi-chi-phí-api--tùy-chọn)
22. [Câu hỏi thường gặp (FAQ) & Tiêu chí chấm điểm](#22-câu-hỏi-thường-gặp-faq--tiêu-chí-chấm-điểm)

---

## 1. Cấu hình và thiết lập API Key

Bedside English hỗ trợ linh hoạt các backend Google Gemini, OpenAI và Anthropic Claude.

### 💡 Thiết lập khuyến nghị (Chế độ đơn Key Google Gemini)
**Đăng ký một API key Google Gemini duy nhất cho phép bật tất cả tính năng ứng dụng — từ trò chuyện giọng nói thời gian thực đến phân tích phản hồi sau buổi tập — một cách nhanh chóng và tiết kiệm chi phí nhất.**

| Dịch vụ | Mục đích chính | Yêu cầu / Tùy chọn | Liên kết |
| :--- | :--- | :--- | :--- |
| **Google (Gemini)** | Trò chuyện giọng nói thời gian thực (Gemini Live) + Phân tích phản hồi sâu | **Bắt buộc (Đơn key bao phủ tất cả tính năng)** | [aistudio.google.com](https://aistudio.google.com) |
| **OpenAI** | Giọng nói thời gian thực (OpenAI Realtime) + Phản hồi + TTS cao cấp | Tùy chọn | [platform.openai.com](https://platform.openai.com) |
| **Anthropic (Claude)** | Phân tích phản hồi sau buổi tập (backend có thể chọn) | Tùy chọn (Mặc định là Gemini) | [console.anthropic.com](https://console.anthropic.com) |

### Nhập và an toàn API Key
API key được nhập trực tiếp trong ứng dụng:
* Cấu hình trong **Trình hướng dẫn Onboarding lần đầu** hoặc qua menu **Biểu tượng bánh răng (⚙️) → Tùy chọn → API Keys** ở thanh trên cùng.
* API key đã nhập được **lưu trữ an toàn** trong bộ nhớ mã hóa trên thiết bị (`EncryptedSharedPreferences`) và không bao giờ gửi đến máy chủ bên ngoài.
* Mỗi ô nhập key có biểu tượng con mắt ở bên phải để bật/tắt hiển thị key.

### 🎈 Chế độ Demo (Trải nghiệm miễn phí hoàn toàn)
Nếu bạn muốn trải nghiệm ứng dụng mà không cần đăng ký API key hoặc cấp quyền micro, hãy chọn chế độ **Demo** trên màn hình onboarding hoặc trong phần **Tùy chọn**.
Các cuộc trò chuyện mô phỏng theo kịch bản và dữ liệu phản hồi sẽ được tải, cho phép bạn khám phá đầy đủ giao diện, trình theo dõi lỗi và tính năng ôn tập **miễn phí** mà không tiêu tốn token. Sau khi hoàn thành phiên demo, thông báo sẽ cho phép bạn nhập API key hoặc tiếp tục với bài luyện demo tiếp theo bất cứ lúc nào.

---

## 2. Cài đặt ứng dụng và thiết lập quyền

Bedside English chạy trên điện thoại thông minh và máy tính bảng chạy Android 8.0 (API Level 26) trở lên.

### Cài đặt ứng dụng
* Khởi chạy tệp cài đặt được cung cấp (`.apk`) trên thiết bị Android của bạn và làm theo hướng dẫn trên màn hình để cài đặt.

### Quyền micro & Chế độ Gõ thay thế (Type-Instead)
* Khi khởi chạy chế độ luyện nói trực tiếp lần đầu tiên, hệ điều hành Android sẽ yêu cầu quyền truy cập micro. Nhấn **[Cho phép]** để nhận dạng giọng nói hoạt động bình thường.
* Nếu bạn ở trong môi trường khó nói chuyện hoặc nếu quyền bị từ chối, ứng dụng sẽ không bị lỗi. Ứng dụng tự động chuyển sang chế độ **Gõ thay thế (Type instead)**, cho phép bạn luyện tập trò chuyện bằng bàn phím.

### Quyền thông báo & Kiểm tra âm thanh trước phiên (Audio Preflight)
* Quyền thông báo được yêu cầu để bạn có thể nhận thông báo khi quá trình phân tích phản hồi chạy ngầm hoàn tất.
* Ngay trước khi bắt đầu phiên giọng nói trực tiếp đầu tiên, bảng kiểm tra **Audio Preflight** hiển thị để hướng dẫn sử dụng tai nghe và kiểm tra mức đầu vào micro, ngăn ngừa hiện tượng vọng âm (hú còi).

---

## 3. Onboarding lần đầu & Tùy chỉnh người học L1

Khi khởi chạy ứng dụng lần đầu tiên, trình hướng dẫn cài đặt 4 bước sẽ chạy để thiết lập môi trường học tập tùy chỉnh:

1. **Màn hình chào mừng**: Giới thiệu các tính năng cốt lõi và cung cấp nút **Thử chế độ Demo** để khám phá mà không cần API key.
2. **Chọn ngôn ngữ giao diện**: Chọn ngôn ngữ giao diện ưa thích của bạn (8 ngôn ngữ được hỗ trợ: Tiếng Anh, Tiếng Hàn, Tiếng Tây Ban Nha, Tiếng Trung, Tiếng Ả Rập, Tiếng Hindi, Tiếng Bồ Đào Nha, Tiếng Tagalog).
3. **Thiết lập API Keys**: Đăng ký Google Gemini hoặc các API key AI khác.
4. **Tiếng mẹ đẻ & Quyền riêng tư**: Chọn ngôn ngữ đầu tiên của bạn (ví dụ: **Tiếng Hàn**, Tiếng Trung, Tiếng Tây Ban Nha, Tiếng Ả Rập, Tiếng Hindi, Tiếng Tagalog, Tiếng Bồ Đào Nha). Điều này kích hoạt phân tích ngữ pháp và phát âm chính xác được thiết kế riêng cho các mẫu lỗi giao thoa ngôn ngữ mẹ đẻ của bạn.

### 🌐 Điểm nổi bật về tùy chỉnh tiếng mẹ đẻ L1 (Ví dụ: Người học L1 Tiếng Hàn)
* **Sửa lỗi Ngữ pháp & Cụm từ**:
  * Thiếu mạo từ (bỏ sót *a/an/the* trước danh từ)
  * Thiếu danh từ số nhiều *-s* (*two patient* → *two patients*)
  * Lỗi thì (dùng thì hiện tại khi thảo luận về tiền sử bệnh)
  * Dùng sai giới từ (*in hospital*, bỏ sót giới từ trong *explain to patient*)
  * Dịch thô trực tiếp theo nghĩa đen / Konglish (*skin scale*, dịch gượng gạo *side effect*)
* **Sửa lỗi Phát âm & Độ rõ ràng**:
  * Phân biệt cặp âm tối thiểu *r / l* (*liver* vs *river*)
  * Phân biệt *f / p* (*fever* vs *peter*)
  * Phát âm âm sát răng *th* (*think* vs *tink*)
  * Bỏ sót phụ âm cuối và chèn âm tiết không cần thiết (*cardiac* → *cardi-ack-eu*)
  * Đặt sai trọng âm từ y khoa (*angina*, *arrhythmia*)

### 💡 Tour tương tác lần đầu
Sau khi hoàn thành onboarding và vào Bảng điều khiển lần đầu tiên, một bài hướng dẫn tương tác sẽ tự động dẫn dắt bạn qua vị trí và chức năng của các nút chính (Bảng điều khiển, Trung tâm luyện tập, Pron Lab, SRS Reviews, Lịch sử, Trợ giúp, Huấn luyện viên giọng nói 1:1).

---

## 4. Tổng quan giao diện (5 Tab điều hướng dưới & Trợ giúp)

### Thanh điều hướng dưới (5 Tab)
Thanh điều hướng chính bao gồm 5 tab bên dưới:

```
┌───────────┬──────────────┬───────────────────────┬───────────────┬─────────────┐
│  🏠 Trang chủ│  ▶ Luyện tập │  🎙️ Phòng phát âm     │  ⚠️ Ôn tập SRS│  🕘 Lịch sử │
└───────────┴──────────────┴───────────────────────┴───────────────┴─────────────┘
```

1. **Trang chủ (Bảng điều khiển)**: Chuỗi ngày luyện tập (`🔥`), nhiệm vụ lâm sàng 5 phút, biểu đồ xu hướng hiệu suất, Bộ gen lỗi sai (Mistake Genome), lộ trình và **Huấn luyện viên giọng nói 1:1 luôn sẵn sàng**.
2. **Luyện tập (Trung tâm luyện tập)**: Trung tâm cho tất cả các chế độ nói thời gian thực: Cuộc gặp bệnh nhân, Thi & Chẩn đoán, Tiếng Anh sinh tồn, Giảng lại bài, Phỏng vấn, Góc tự do và Tình huống tùy chỉnh.
3. **Phòng phát âm (Pron Lab)**: Tab rèn luyện chuyên biệt cho độ rõ tiếng và sửa phát âm (lọc mẫu lỗi, quản lý trạng thái quan sát).
4. **Ôn tập SRS (Review điểm yếu)**: Các bài kiểm tra nói dựa trên thuật toán lặp lãng quên đối với các câu sửa lỗi đã chấp nhận.
5. **Lịch sử (Lịch sử phiên)**: Xem điểm số và phản hồi từ các phiên trước, theo dõi chi phí token, xuất sang Anki/Word và kích hoạt **Trình bày bệnh án cho Bác sĩ điều trị (Present Case)**.

### Thanh ứng dụng trên cùng
* **Biểu tượng Bedside English**: Tiêu đề chính.
* **Wiki trợ giúp (Biểu tượng `?`)**: Nhấn vào biểu tượng `?` để mở Hướng dẫn sử dụng này trong trình xem toàn màn hình với thanh điều hướng Mục lục và tìm kiếm từ khóa toàn văn.
* **Biểu tượng bánh răng (⚙️ Tùy chọn)**: API keys, backend giọng nói, tốc độ nói, chống vọng âm, ngôn ngữ và quản lý dữ liệu.

---

## 5. Làm chủ Bảng điều khiển (Trang chủ)

Bảng điều khiển chính hiển thị trực quan sự phát triển kỹ năng giao tiếp tiếng Anh lâm sàng của bạn trên nhiều chiều:

* **Chuỗi ngày luyện tập**: Hiển thị số ngày luyện tập liên tục với biểu tượng ngọn lửa (`🔥`) để xây dựng thói quen học tập hàng ngày.
* **Các thẻ chỉ số cốt lõi**:
  * **Phiên hoàn thành**: Tổng số phiên đã hoàn thành và phân tích đầy đủ.
  * **Lỗi đã theo dõi**: Các bản sửa lỗi đã xác nhận được đăng ký vào cơ sở dữ liệu điểm yếu của bạn.
  * **Đã thành thạo**: Các lỗi đã được giải quyết và vượt qua các bài kiểm tra ôn tập lặp lại.
  * **Cần ôn ngay**: Số thẻ ôn tập SRS được lên lịch kiểm tra nói hôm nay.
  * **Lỗi cứng đầu**: Các lỗi "dai dẳng" bị trượt 4+ lần liên tiếp cần sự chú ý tập trung.
* **Nhiệm vụ lâm sàng 5 phút hôm nay**: Tự động đề xuất khóa luyện tập 5 phút tối ưu nhắm vào các mục cần ôn tập, chẩn đoán bắt buộc hoặc miền kỹ năng yếu nhất của bạn.
* **Độ bao phủ từ vựng OET bình dân (Layman Vocabulary)**:
  * Theo dõi mức độ hiệu quả của bạn khi thay thế từ ngữ bình dân, thân thiện với bệnh nhân (ví dụ: *fainting*) cho các thuật ngữ y khoa phức tạp (ví dụ: *syncope*).
  * Các từ đã dùng xuất hiện trong **Đã mở khóa gần đây**, trong khi các biểu đạt chưa dùng được xếp hàng trong **Mục tiêu tiếp theo (Đã khóa)**.
* **Bảng bộ gen lỗi sai (Mistake Genome Panel)**: Phân tích các danh mục lỗi thường gặp nhất của bạn (Mạo từ, Số nhiều, Thì, Giới từ, Văn phong, Dịch thô) và hiển thị 5 vùng yếu nhất dưới dạng biểu đồ cột.
* **Biểu đồ xu hướng tăng trưởng & Giao thoa L1**:
  * Biểu đồ xu hướng điểm số qua 5 miền (Ngữ pháp, Độ chính xác, Lập luận, Tính chuyên nghiệp, Lưu loát) trong 20 phiên gần nhất.
  * Trực quan hóa các mẫu lỗi ngữ pháp lặp đi lặp lại.
* **Lộ trình cá nhân hóa**: Phân tích các chỉ số yếu và lịch sử lỗi để trình bày 4 thẻ kỹ năng trọng tâm được ưu tiên.
* **Nút Huấn luyện viên giọng nói 1:1 luôn sẵn sàng (`🎙️ RecordVoiceOver`)**:
  * Nằm ở góc dưới bên phải Bảng điều khiển. Nhấn để mở ngay cuộc trò chuyện nói 1:1 với gia sư AI dựa trên hồ sơ điểm yếu cá nhân của bạn mà không cần bắt đầu một kịch bản phiên đầy đủ.

---

## 6. Trung tâm luyện tập & Mở khóa tính năng lũy tiến

### Mở khóa tính năng lũy tiến
Để tránh người dùng mới cảm thấy quá tải, người dùng lần đầu bắt đầu với màn hình giới thiệu nhẹ nhàng hiển thị các chế độ cốt lõi (**Bảng điều khiển**, **Cuộc gặp bệnh nhân**, **Tiếng Anh sinh tồn**, **Lịch sử**).
* **Hoàn thành phiên luyện tập đầu tiên** sẽ tự động **mở khóa** các chế độ nâng cao (Thi, Giảng lại bài, Phỏng vấn, Góc tự do, Tùy chỉnh) kèm theo thông báo chúc mừng.
* Bạn cũng có thể nhấn để mở rộng và hiển thị tất cả các chế độ ngay lập tức từ màn hình Luyện tập.

### Các danh mục chế độ chính trong Trung tâm luyện tập
1. **Cuộc gặp bệnh nhân**: Hỏi bệnh sử, tái khám, chế độ Nền tảng cho người mới bắt đầu, Luyện kỹ năng nhỏ, Luyện lỗi sai của tôi.
2. **Thi & Chẩn đoán**: Chẩn đoán lâm sàng 10 phút, các bài thi mô phỏng OSCE, OET, Phỏng vấn nội trú và Đi buồng.
3. **Tiếng Anh sinh tồn & Phòng luyện nghe**: Tình huống bất ngờ tại bệnh viện, trò chuyện nhanh, 15 hồ sơ giọng bản ngữ, bài luyện nghe chi tiết.
4. **Giảng lại bài học**: Rèn luyện kỹ thuật Feynman dựa trên tóm tắt YouTube/văn bản.
5. **Phỏng vấn nội trú**: Phỏng vấn mô phỏng Hành vi, Lâm sàng và dành riêng cho IMG.
6. **Góc tiếng Anh tự do**: Tranh luận y khoa, thảo luận phụ đề tin tức, giải quyết xung đột nơi làm việc.
7. **Tình huống tùy chỉnh**: Tự soạn prompt AI và tiêu chí chấm điểm riêng.

---

## 7. Cuộc gặp bệnh nhân & Trình theo dõi bao phủ bệnh sử trực tiếp

Mô phỏng hỏi bệnh sử tại giường bệnh và tư vấn bệnh nhân — cốt lõi của giao tiếp lâm sàng.

### 7-1. Các chế độ phụ hoạt động
* **Chế độ Nền tảng (Foundations Mode)**: Loại bỏ gánh nặng lập luận lâm sàng cho người mới học, tập trung hoàn toàn vào **ngữ pháp, từ vựng lâm sàng, xây dựng mối quan hệ và sự lưu loát**.
* **Luyện kỹ năng nhỏ (Skill Drills)**: Các bài tập kỹ năng nhỏ mục tiêu (kỹ thuật thấu cảm NURSE, giải thích bằng ngôn ngữ bình dân, bàn giao ca đêm, **Phiên dịch y khoa nối tiếp Ngôn ngữ mẹ đẻ → Tiếng Anh**).
* **Luyện lỗi sai của tôi (Practice My Mistakes)**: Tổng hợp bài kiểm tra đối thoại bằng giọng nói tức thì từ các lỗi đang chờ xử lý trong cơ sở dữ liệu của bạn.
* **Nhiệm vụ hàng ngày (Daily Mission)**: Thử thách 5 phút hàng ngày thích ứng nhắm vào các lỗ hổng kỹ năng hiện tại của bạn.

### 7-2. Trình theo dõi bao phủ bệnh sử trực tiếp
Một bảng điều khiển thời gian thực có thể thu gọn giúp đánh dấu các mục bệnh sử khi bạn nói:
* Tự động theo dõi các mục dựa trên ngữ cảnh trò chuyện AI.
* Giám sát khởi phát/thời gian, tính chất chất đau, hướng lan, yếu tố tăng/giảm, triệu chứng kèm theo, ICE (Ý kiến, Lo lắng, Kỳ vọng), tiền sử, thuốc, dị ứng, rượu/thuốc lá, tiền sử gia đình, v.v.
* Các câu hỏi xác nhận phủ định như *"You don't smoke, do you?"* được nhận dạng và theo dõi chính xác.

### 7-3. Trợ giúp tiếp tục theo ngữ cảnh (`💡 Help me continue`)
Nếu bạn bị mắc kẹt hoặc hết câu hỏi giữa phiên, hãy nhấn **💡 Help me continue** ở cuối màn hình.
* Phân tích câu trả lời mới nhất của bệnh nhân để đề xuất mục tiêu câu hỏi logic tiếp theo cùng với **câu tiếng Anh mẫu sẵn sàng sử dụng**.
* Trình theo dõi giai đoạn phỏng vấn ở trên cùng hiển thị tiến trình bằng các ký hiệu trạng thái:
  * `✓`: Đã phát hiện đủ bằng chứng
  * `•`: Đã đề cập một phần
  * `?`: Đã đến giai đoạn sau mà chưa xác minh giai đoạn trước

---

## 8. Chế độ Thi & Chẩn đoán (Chẩn đoán 10 phút & Ánh xạ CEFR)

Đo lường năng lực giao tiếp trong điều kiện thi có tính giờ và nhập vai:

### Chẩn đoán Tiếng Anh Lâm sàng Nền tảng 10 phút
* Bắt đầu bằng phần giới thiệu của giám khảo, tiếp theo là 4 nhiệm vụ ngắn (giải thích chẩn đoán, xử lý câu hỏi tái khám, bàn giao SBAR 45 giây, trả lời câu hỏi phỏng vấn nội trú).
* Tự động ánh xạ hiệu suất của bạn sang **cấp độ CEFR** quốc tế:
  * **Điểm >= 8.5**: **C1** (Lưu loát, giao tiếp lâm sàng an toàn ở cấp độ bác sĩ điều trị)
  * **Điểm >= 7.2**: **B2+** (Đủ năng lực cho thực tập lâm sàng và hành nghề tại bệnh viện)
  * **Điểm >= 6.0**: **B1-B2** (Khả năng giao tiếp cơ bản; nên học theo cấu trúc)
  * **Điểm < 6.0**: **A2-B1** (Cần rèn luyện giao tiếp lâm sàng nền tảng)

### Các tình huống thi mô phỏng
* **OSCE**: Hỏi bệnh sử đau ngực của ông Hayes (đo lường ICE, phát hiện dấu hiệu nguy hiểm, sự thấu cảm).
* **OET Speaking**: Nhập vai tư vấn cho bệnh nhân tăng huyết áp.
* **Nội trú**: Phỏng vấn mô phỏng với Giám đốc chương trình Nội khoa Hoa Kỳ.
* **Đi buồng**: Trình bày bệnh án viêm phổi mắc phải tại cộng đồng 5 phút và xử lý câu hỏi hỏi đáp.

### Huy hiệu độ tin cậy của điểm số
Chỉ ra độ tin cậy chấm điểm của AI là **Cao (High)**, **Trung bình (Medium)**, hoặc **Thấp (Low)**:
* **Cao**: Số từ của người học >= 180 từ & tỷ lệ bằng chứng bảng kiểm >= 75%.
* **Trung bình**: Số từ của người học >= 80 từ & tỷ lệ bằng chứng bảng kiểm >= 50%.
* **Thấp**: Số từ < 80 từ (Cờ bản ghi ngắn) hoặc tỷ lệ bằng chứng < 50%.

---

## 9. Phòng luyện phát âm & Độ rõ tiếng (Pron Lab)

Nằm trong tab thứ 3 chuyên biệt bên dưới (`🎙️ Pron Lab`), đây là trung tâm rèn luyện chuyên biệt của bạn về độ rõ ràng của lời nói.

### 💡 Huấn luyện lấy độ rõ tiếng làm trung tâm
Mục tiêu không phải là bắt chước giọng bản xứ, mà là **"Các đồng nghiệp quốc tế và bệnh nhân có thể hiểu rõ lời nói của tôi mà không bị hiểu lầm không?"**
* Phân tích âm thanh cung cấp hướng dẫn chính xác chỉ trên các mục phát âm gây ra sự hiểu lầm cho người nghe.

### Danh mục mẫu lỗi & Thẻ lọc
Các lỗi phát âm được phát hiện qua các phiên của bạn được tổ chức theo danh mục vào các thẻ lọc:
* `r · l`: *liver / river*, *clinical / critical*
* `f · p`: *fever / peter*, *palpation / falcation*
* `th`: *think / tink*, *throat / troat*
* `final`: Bỏ sót phụ âm cuối (*chest / ches*)
* `cluster`: Xử lý cụm phụ âm & chèn âm tiết không cần thiết (*cardiac* → *cardi-ack-eu*)
* `stress`: Đặt sai trọng âm từ y khoa (*angina*, *arrhythmia*)
* `vowel`: Nhầm lẫn nguyên âm ngắn và dài (*ship / sheep*, *fit / feet*)

### Quy tắc thăng hạng trạng thái quan sát
Khi một thẻ phát âm đã chấp nhận được ghi lại lần đầu tiên, nó sẽ vào trạng thái **Quan sát (Observed)** thay vì trở thành thẻ bài tập hàng ngày ngay lập tức. Nó chỉ được thăng hạng thành thẻ lỗi ôn tập SRS hoạt động khi cùng một mẫu lỗi lặp lại trong một phiên riêng biệt, đảm bảo các sự cố nhận dạng giọng nói đơn lẻ không tạo ra quá nhiều bài tập về nhà.

---

## 10. Tiếng Anh sinh tồn & Phòng luyện nghe

Phòng luyện chuẩn bị cho các IMG các tương tác thực tế, ngoài lâm sàng tại bệnh viện bên ngoài phòng khám.

* **Chế độ Ngẫu nhiên / Bất ngờ**: Xử lý tự phát các tình huống bất ngờ (câu hỏi hành lang, cuộc gọi lại từ nhà thuốc, trò chuyện với y tá) với ngữ cảnh tình huống được ẩn cho đến khi AI nói.
* **Trò chuyện nhanh (Rapid-fire Small Talk)**: Phản hồi nhanh với các thay đổi chủ đề đột ngột.
* **15 Hồ sơ giọng bản ngữ**: Luyện tập thích ứng với các giọng bản ngữ và nhịp điệu nói quốc tế.
* **Điều khiển tốc độ âm thanh thời gian thực (0.5× đến 2.5×)**: Thanh trượt tốc độ phát giữ nguyên cao độ để điều chỉnh theo người nói bản ngữ nhanh.
* **Chế độ chỉ nghe (Ear-only)**: Ẩn phụ đề trò chuyện AI để bạn chỉ dựa vào việc nghe, với tùy chọn **[Hiện dòng cuối]** khi cần.
* **Khuyến khích biểu đạt sửa lỗi**: Sử dụng các chiến lược sửa lỗi như *"Sorry?", "Could you say that again?"* sẽ nhận được **điểm thưởng** thay vì bị trừ điểm.
* **Phòng luyện nghe (Listening Lab)**: Bài tập kiểm tra mức độ hiểu chi tiết chính xác (con số, liều lượng thuốc, tên bệnh nhân, thời gian, hướng đi, giá cả) với điểm số chính xác theo từng mục.

---

## 11. Giảng lại bài học (Kỹ thuật Feynman)

Sử dụng kỹ thuật Feynman — giải thích các khái niệm thành tiếng như thể đang dạy người khác — để củng cố kiến thức y khoa.

1. **Chuẩn bị tài liệu bài giảng**: Dán ghi chú tóm tắt hoặc nhập URL bài giảng y khoa trên YouTube và nhấn **`🎬 Fetch YT transcript`**.
2. **Cô đọng AI**: Đối với tài liệu dài, nhấn **`✨ Condense`** để nén văn bản thành dàn ý 500 từ có cấu trúc.
3. **Chọn hình mẫu khán giả (Audience Persona)**:
   * **Giáo sư thi vấn đáp**: Đặt các câu hỏi tiếp theo lâm sàng sắc bén, thách thức về "Tại sao" và "Nếu... thì sao".
   * **Bạn học bối rối**: Yêu cầu giải thích bằng ngôn ngữ bình dân không có từ ngữ chuyên ngành nặng nề.
   * **Gia sư thân thiện**: Đưa ra sự động viên hỗ trợ và hướng dẫn cách diễn đạt.
4. **Giảng bài**: Nhấn **Bắt đầu** và giải thích qua micro khi người nghe AI phản hồi bằng các câu hỏi làm rõ.

---

## 12. Phỏng vấn mô phỏng nội trú

Mô phỏng các cuộc phỏng vấn thực tế cho tuyển dụng bệnh viện nước ngoài và US Residency Match:

* **Hành vi (Behavioral)**: Mô tả kinh nghiệm theo phương pháp STAR (Tình huống, Nhiệm vụ, Hành động, Kết quả).
* **Lâm sàng (Clinical)**: Trình bày bệnh án bằng lời, đạo đức y học và logic quản lý cấp cứu.
* **Dành riêng cho IMG**: Tập trung vào các câu hỏi IMG phổ biến (bảo lãnh visa, giải thích năm trống CV, thế mạnh độc đáo của một IMG).
* Giám đốc chương trình AI sẽ dẫn dắt các câu hỏi tiếp theo lịch sự nhưng sâu sắc.

---

## 13. Góc tiếng Anh tự do & Tình huống tùy chỉnh

### Góc tiếng Anh tự do
* Thảo luận về các chủ đề y khoa hiện tại, tóm tắt các bài báo nghiên cứu, giải quyết xung đột nơi làm việc/y tá, hoặc luyện trò chuyện giờ giải lao.
* Lấy phụ đề tin tức y khoa YouTube để tranh luận tự do với AI.

### Tình huống tùy chỉnh
Tạo các tình huống luyện tập tùy chỉnh theo nhu cầu của bạn:
* **Tên tình huống**: Định danh tùy chỉnh.
* **Hình mẫu / Ngữ cảnh**: System prompt xác định vai trò và tình huống của AI.
* **Mẫu đánh giá**: Chọn tiêu chí đánh giá (ví dụ: quy trình SPIKES để thông tin xấu).
* **Tiêu chí đánh giá tùy chỉnh**: Đặt các điểm đánh giá chính cụ thể để AI kiểm tra.

---

## 14. Phân tích báo cáo phản hồi (7 phần chính)

Sau khi hoàn thành một phiên, báo cáo 7 phần cung cấp phản hồi đa chiều:

1. **Điểm số**: So sánh điểm số các miền của AI (0–10) sóng đôi với tự đánh giá của bạn. Khoảng cách **2.0+ điểm** sẽ kích hoạt thẻ **Gợi ý tự suy ngẫm (Reflection Prompt)** màu vàng để hướng dẫn tự suy ngẫm.
2. **Sự lưu loát**:
   * **WPM (Số từ mỗi phút)**: Đo tốc độ nói so với mục tiêu khuyến nghị (100–130 WPM).
   * **Từ đệm**: Đo mật độ từ đệm (`um`, `uh`, `like`), khuyến khích sử dụng khoảng nghỉ hiệu quả.
3. **Bảng kiểm**: Đánh giá các mục tiêu lâm sàng, trích dẫn các câu bản ghi chính xác làm **Bằng chứng**.
4. **So sánh ghi chú SOAP**: So sánh ghi chú SOAP tự động tạo từ phiên của bạn với ghi chú SOAP mẫu tham chiếu.
5. **Sửa lỗi**: Sửa lỗi dạng thẻ cho dịch thô, mạo từ/số nhiều, biểu đạt không tự nhiên và phát âm. Nhấn **[Chấp nhận]** sẽ đăng ký mục đó vào trình theo dõi lỗi SRS cá nhân của bạn.
6. **Luyện nói theo (Shadowing)**: Viết lại các câu yếu thành tiếng Anh lâm sàng cấp độ bác sĩ điều trị để rèn luyện âm thanh nghe-và-nói-lại.
7. **Tóm tắt & Thẻ chia sẻ**: Hiển thị phản hồi tổng thể và cung cấp trình tạo hình ảnh **Thẻ chia sẻ** để chia sẻ tóm tắt hiệu suất với bạn học.

---

## 15. AI Tutor kiểu Socratic & Huấn luyện viên giọng nói 1:1 luôn sẵn sàng

### 15-1. Socratic AI Tutor 1:1 Debrief (`[Debrief with AI Tutor]`)
Nhấn **[Debrief with AI Tutor]** ở cuối báo cáo phản hồi để mở phòng chat 1:1 với cố vấn AI kiểu Socratic.
* Đặt các câu hỏi gợi mở thay vì đưa ra câu trả lời trực tiếp, giúp bạn tự phát hiện và sửa lỗi.
* Lịch sử chat debrief được lưu trữ trong cơ sở dữ liệu để bạn có thể quay lại và tiếp tục bất cứ lúc nào.

### 15-2. Huấn luyện viên giọng nói 1:1 luôn sẵn sàng (Nút nổi Trang chủ)
Nhấn **Nút nổi Huấn luyện viên giọng nói (`🎙️ RecordVoiceOver`)** ở góc dưới bên phải Bảng điều khiển để mở ngay cuộc trò chuyện với huấn luyện viên nói mà không cần hoàn thành một tình huống đầy đủ trước.
* Huấn luyện viên AI dẫn dắt các cuộc trò chuyện giọng nói 1:1 tùy chỉnh dựa trên các điểm yếu SRS tích lũy của bạn.

---

## 16. Trình theo dõi lỗi lặp lãng quên & Bộ gen lỗi sai

Các bản sửa lỗi được chấp nhận qua **[Chấp nhận]** được quản lý tự động bởi các thuật toán lặp lãng quên trong cơ sở dữ liệu lỗi của bạn.

* **Phát hiện trùng lặp mờ**: Tự động ngăn chặn việc ghi lại trùng lặp các lỗi tương tự.
* **Lỗi cứng đầu / Leech**: Các mục bị trượt 4+ lần liên tiếp trong các bài kiểm tra ôn tập được gắn thẻ **Cứng đầu / Leech** để quản lý tập trung.
* **Khoảng thời gian lặp lãng quên khoa học**:
  * Lịch ôn tập: **1 ngày → 3 ngày → 7 ngày → 14 ngày → 30 ngày**. Vượt qua 3 lần ôn tập liên tiếp sẽ thăng hạng mục đó thành **Đã thành thạo**.
  * Xóa các bài kiểm tra nói trong **Luyện lỗi sai của tôi** sẽ giúp các mục tiến tới Thành thạo.
* **Bảng bộ gen lỗi sai (Mistake Genome Panel)**:
  * Hiển thị 5 danh mục lỗi yếu nhất của bạn (Mạo từ, Số nhiều, Thì, Giới từ, Văn phong, Dịch thô) dưới dạng biểu đồ cột Bảng điều khiển với chú thích công cụ bằng ngôn ngữ bình dân.

---

## 17. Chuỗi trình bày bệnh án cho Bác sĩ điều trị

Rèn luyện kỹ năng bàn giao bằng lời bằng cách trình bày bệnh án cho bác sĩ giám sát sau Cuộc gặp bệnh nhân:

1. Hoàn thành một phiên **Cuộc gặp bệnh nhân**.
2. Đến tab **Lịch sử**, chọn phiên đó và nhấn **`📋 Present Case`**.
3. Bác sĩ điều trị AI bắt đầu bằng: *"Doctor, please present the case you just saw."*
4. Thực hiện bài trình bày bệnh án bằng lời sử dụng định dạng SBAR hoặc SOAP, và trả lời các câu hỏi tiếp theo về chẩn đoán phân biệt và kế hoạch điều trị.

---

## 18. Nhập lịch sử trò chuyện bên ngoài (Import Transcript)

Nhập văn bản trò chuyện từ ChatGPT, Gemini hoặc ghi chú lâm sàng vào ứng dụng để nhận phản hồi đầy đủ:

1. **Tích hợp chia sẻ Android**: Bôi đen văn bản trò chuyện trong các ứng dụng bên ngoài và chọn **[Chia sẻ] → [Bedside English]** để tự động mở màn hình **Import Transcript**.
2. **Nhập trực tiếp / Dán**: Mở màn hình `Import Transcript` trực tiếp từ Tùy chọn hoặc menu chính và dán văn bản.
3. **Phản hồi tự động & SRS**: Tạo điểm số miền, ghi chú SOAP và các thẻ sửa lỗi có sẵn để chấp nhận SRS.

---

## 19. Xuất bộ thẻ Anki & Báo cáo Word

* **Xuất thẻ Anki (`.txt` phân tách bằng tab)**:
  * Chuyển đổi các bản sửa lỗi đã chấp nhận thành tệp nhập văn bản của Anki (Tệp → Nhập trong Anki / AnkiDroid); phân loại của mỗi lỗi được giữ lại dưới dạng thẻ (tag).
* **Xuất báo cáo Word (`.docx`)**:
  * Tạo các báo cáo y khoa có cấu trúc chứa điểm số, bằng chứng bảng kiểm, ghi chú SOAP và các câu sửa lỗi. Tùy chọn Tùy chọn cho phép tự động lưu.

---

## 20. Wiki trợ giúp trong ứng dụng

Nhấn vào **Biểu tượng trợ giúp `?`** trên thanh ứng dụng trên cùng để mở trình xem Wiki trợ giúp tích hợp ở chế độ toàn màn hình.

* **Tích hợp đa ngôn ngữ**: Tự động tải tệp hướng dẫn sử dụng khớp với cài đặt ngôn ngữ giao diện của ứng dụng.
* **Thanh bên Mục lục (TOC)**: Cho phép chuyển hướng nhảy nhanh qua tất cả các phần hướng dẫn.
* **Tìm kiếm toàn văn**: Nhập từ khóa vào thanh tìm kiếm để tô sáng các kết quả khớp và di chuyển bằng các nút trước/sau.
* **Superlink bên ngoài**: Nhấn vào các liên kết web trong hướng dẫn sẽ mở trình duyệt hệ thống mặc định của bạn.

---

## 21. Cài đặt ngôn ngữ giao diện, Theo dõi chi phí API & Tùy chọn

Truy cập **Biểu tượng bánh răng (⚙️)** ở thanh trên cùng để tùy chỉnh ứng dụng theo thiết bị và ngân sách của bạn:

* **Giọng nói & Phản hồi**:
  - Chọn mô hình AI giọng nói và phản hồi (Demo, Gemini, OpenAI, Claude).
  - **Chống vọng âm**: Tự động tắt tiếng micro trong khi AI nói để ngăn ngừa vòng lặp phản hồi âm thanh (thiết yếu khi không dùng tai nghe).
  - **Tốc độ nói AI**: Điều khiển tốc độ theo từng bước (Chậm, Bình thường, Nhanh, Thử thách).
* **Phân tích chi phí API**: Theo dõi minh bạch việc sử dụng token và chi phí đô la ước tính cho mỗi phiên với biểu đồ trực quan.
* **Âm thanh**: Chỉ báo mức micro thời gian thực và âm thanh kiểm tra loa.
* **API Keys**: Trình quản lý key lưu trữ mã hóa cục bộ.
* **Xuất & Học tập**: Tùy chọn tự động lưu Docx, bắt buộc nhập ghi chú SOAP, cài đặt Tiếng mẹ đẻ và Ngôn ngữ giao diện.
* **Dữ liệu**: Bật/tắt phân tích phát âm, cấu hình công cụ và chọn công cụ shadowing TTS.
* **Quyền riêng tư**: Bật/tắt phép đo từ xa sử dụng ẩn danh.

---

## 22. Câu hỏi thường gặp (FAQ) & Tiêu chí chấm điểm

### Câu hỏi thường gặp (FAQ)

**Q: Micro không thu âm thanh và AI không phản hồi.**
* Kiểm tra **Cài đặt Android → Ứng dụng → Bedside English → Quyền → Micro** và đặt thành [Cho phép]. Nếu quyền bị từ chối, ứng dụng sẽ chuyển sang chế độ **Gõ thay thế (Type instead)** để bạn có thể tiếp tục luyện tập qua bàn phím.

**Q: Các chế độ luyện tập nâng cao (Thi, Giảng lại bài, Phỏng vấn, Góc tự do, Tùy chỉnh) bị thiếu.**
* Hoàn thành phiên luyện tập đầu tiên của bạn và tất cả các chế độ nâng cao sẽ tự động mở khóa kèm theo thông báo chúc mừng. Bạn cũng có thể mở rộng và hiển thị tất cả các chế độ từ màn hình Luyện tập.

**Q: Kết quả phân tích phát âm không vào cơ sở dữ liệu ôn tập SRS của tôi ngay lập tức.**
* Để tránh lỗi nhận dạng giọng nói một lần gánh nặng cho người học, các mục phát âm được chấp nhận sẽ vào trạng thái **Quan sát (Observed)** trước. Chúng chỉ được thăng hạng thành các thẻ ôn tập SRS hoạt động khi cùng một mẫu lỗi lặp lại trong một phiên tương lai.

**Q: Tôi có thể nhập bản ghi trò chuyện bên ngoài (ví dụ: ChatGPT) để chấm điểm không?**
* Có. Sử dụng tính năng chia sẻ của Android để chia sẻ văn bản sang Bedside English hoặc dán văn bản vào màn hình `Import Transcript` để nhận phản hồi đầy đủ, ghi chú SOAP và thẻ sửa lỗi.

---

### 📝 Tiêu chí chấm điểm đánh giá chi tiết (Thang điểm 0–10)

| Điểm | Cấp độ đánh giá | Tiêu chí đánh giá |
| :--- | :--- | :--- |
| **9 ~ 10** | **Bác sĩ điều trị / Chuyên gia** | Ngữ pháp và biểu đạt hoàn hảo; từ vựng lâm sàng chính xác và lập luận y khoa có hệ thống; giọng điệu tự nhiên, chuyên nghiệp. |
| **7 ~ 8** | **Đủ năng lực / Đạt** | Lỗi ngữ pháp nhỏ nhưng giao tiếp hoàn toàn rõ ràng; xác định các yếu tố nguy cơ chính và thực hiện chẩn đoán phân biệt an toàn. |
| **5 ~ 6** | **Đang phát triển** | Lỗi ngữ pháp cấu trúc thường xuyên đòi hỏi nỗ lực của người nghe; lập luận lâm sàng và từ vựng không có hệ thống. |
| **1 ~ 4** | **Nghiêm trọng / Trượt** | Lỗi y khoa nghiêm trọng hoặc bỏ sót các yếu tố nguy cơ; lời nói giới hạn ở các từ đơn lẻ hoặc thường xuyên ngắt nghỉ dài ngăn cản đối thoại bình thường. |
