# KIẾN TRÚC ON-DEVICE STREAMING LIVE CAPTION & TRANSLATION
*(Lấy cảm hứng từ LiveCaptionN - chartmann1590/LiveTranscribe-Android)*

---

## 1. Bối cảnh & Vấn đề của Kiến trúc Cũ (Batch / Cloud REST)

Trong các phiên bản thử nghiệm trước (Spike A $\rightarrow$ Spike D), hệ thống gặp phải các nút thắt kỹ thuật nghiêm trọng:

1. **Cơ chế gom Chunk (Batch Window 2.0s):**
   * Phải chờ đủ 2.0s âm thanh phát xong $\rightarrow$ đóng gói WAV $\rightarrow$ gửi mạng $\rightarrow$ ASR $\rightarrow$ LLM Dịch.
   * **Độ trễ cảm nhận:** Người dùng phải chờ từ **2.0s đến 3.2s** sau khi âm thanh bắt đầu mới thấy phụ đề xuất hiện. Khi lướt Reel/TikTok 15s, phụ đề nhảy giật cục cả đoạn dài và lệch nhịp video hoàn toàn.
2. **Khủng hoảng Cloud API Key & Rate Limits:**
   * **Google Translate Cloud:** Dính lỗi `HTTP 403 API_KEY_SERVICE_BLOCKED`.
   * **Groq Cloud:** Bị giới hạn `20 RPM` dẫn đến `HTTP 429 Rate Limit`, mô hình reasoning (`gpt-oss-20b`) nuốt hết token suy luận trả về text rỗng.
   * **Gemini Direct Audio:** Trả về `HTTP 503 Overloaded`, timeout >30s, hoàn toàn không khả thi (Not Viable).

---

## 2. Kiến trúc Mới: On-Device Streaming Real-Time (100% Offline)

Kiến trúc mới loại bỏ hoàn toàn cơ chế gom chunk và dẹp bỏ mọi API Key Cloud, chuyển dịch toàn bộ sang xử lý cục bộ trên thiết bị với độ trễ siêu thấp:

```text
┌─────────────────────────────────────────────────────────┐
│        MediaProjection / AudioPlaybackCapture           │
│        (16,000 Hz, 16-bit Mono PCM System Audio)        │
└────────────────────────────┬────────────────────────────┘
                             │  Stream liên tục mỗi ~100ms (1600 samples = 3200 bytes)
                             ▼
┌─────────────────────────────────────────────────────────┐
│         Vosk On-Device Streaming ASR Engine             │
│   • Duy trì 1 Recognizer sống duy nhất (Long-lived)     │
│   • Cấu hình tối ưu: setWords(false), maxAlt(0)         │
│   • Model: vosk-model-small-en-us-0.15 (39 MB offline)  │
│   • Nhả partialResult theo thời gian thực (~20-50ms)   │
└────────────────────────────┬────────────────────────────┘
                             │  Text tiếng Anh từng từ / cụm từ
                             ▼
┌─────────────────────────────────────────────────────────┐
│        Google ML Kit Translate (On-Device NPU/CPU)       │
│   • Model: EN ⇄ VI (~30 MB tải 1 lần, cache vĩnh viễn)  │
│   • Tốc độ dịch: 10 - 30 ms                             │
│   • KHÔNG cần API Key, KHÔNG tốn quota, 0đ chi phí      │
└────────────────────────────┬────────────────────────────┘
                             │  Text tiếng Việt tức thời
                             ▼
┌─────────────────────────────────────────────────────────┐
│            Floating Subtitle Overlay                    │
│   • SYSTEM_ALERT_WINDOW hiển thị đè lên TikTok / Reel   │
│   • Cập nhật chữ chạy theo miệng người nói (Real-time)  │
└─────────────────────────────────────────────────────────┘
```

---

## 3. Các thành phần chi tiết của Hệ thống Mới

### A. Audio Streaming Pipeline (~100ms Chunks)
* Thay vì bộ tích lũy 2.0s, vòng lặp `AudioRecord` đọc buffer kích thước **1,600 samples (100ms)**.
* Gói PCM 100ms được đẩy ngay vào Vosk: `recognizer.acceptWaveForm(buffer, samplesRead)`.
* Không tạo mới `Recognizer`, không cấp phát lại bộ nhớ, loại bỏ hoàn toàn GC overhead.

### B. Vosk Streaming Session (Real-Time ASR)
* **Khi người nói đang nói dở (`acceptWaveForm == false`):**
  * Lấy ngay `recognizer.partialResult`.
  * Trích xuất trường `"partial"`. Chữ tiếng Anh xuất hiện ngay khi âm tiết vừa kết thúc (~20ms).
* **Khi người nói ngắt nghỉ / kết thúc câu (`acceptWaveForm == true`):**
  * Lấy `recognizer.result`.
  * Chốt câu hoàn chỉnh (`finalResult`) và chuẩn bị cho câu tiếp theo.

### C. Google ML Kit On-Device Translation
* Sử dụng thư viện `com.google.mlkit:translate`.
* Cơ chế tự động quản lý model:
  * Khi khởi động lần đầu, kiểm tra model EN $\rightarrow$ VI trên máy.
  * Nếu chưa có, tự động tải ngầm gói ngôn ngữ (~30MB) qua `downloadModelIfNeeded()`.
  * Sau khi tải xong, mọi thao tác dịch diễn ra offline 100% trong RAM/NPU.
* Tốc độ dịch mỗi cụm từ chỉ mất **10 – 25 ms**.

### D. Trình hiển thị Floating Overlay (Dual-Line Display)
* Hỗ trợ 2 chế độ hiển thị:
  1. **Dual-line:** Dòng trên hiển thị tiếng Anh gốc (nhảy chữ theo thời gian thực), dòng dưới hiển thị tiếng Việt đã dịch.
  2. **Vi-only:** Chỉ hiển thị câu dịch tiếng Việt mượt mà.

---

## 4. So sánh Chỉ số Hiệu năng Mục tiêu

| Chỉ số | Kiến trúc Cũ (Groq Batch) | Kiến trúc Mới (Vosk Streaming + ML Kit) | Mức độ cải thiện |
| :--- | :---: | :---: | :---: |
| **Độ trễ toàn trình (E2E)** | **2,000 ms – 3,200 ms** | **100 ms – 200 ms** | **Nhanh hơn 15 – 20 LẦN** |
| **Trải nghiệm hiển thị** | Đứng hình chờ 2s rồi giật cục | Nhảy từng từ theo miệng người nói | Mượt mà như Google Live Caption |
| **Phụ thuộc Internet** | 100% (Mất mạng = Chết app) | **0% (Offline hoàn toàn)** | Hoạt động mọi lúc mọi nơi |
| **Rủi ro Rate-limit / Quota** | Rất cao (429, 403, 503) | **Bằng 0 (0 API Key, 0 Server)** | Bền vững tuyệt đối |
| **Tải CPU thiết bị** | < 1% CPU (nhưng nghẽn mạng) | ~15 - 25% CPU Snapdragon 865 | Hoàn toàn mát máy và tiết kiệm pin |
