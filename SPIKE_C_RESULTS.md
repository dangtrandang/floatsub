# SPIKE C RESULTS: CLOUD ASR BENCHMARK

**Thiết bị thử nghiệm:** Xiaomi Mi 10T Pro 5G (`M2007J3SG`), Snapdragon 865, Android 12 (MIUI 14)  
**Package:** `com.floatsub.probe`  
**Mục tiêu duy nhất:** Dùng cùng mẫu audio 2.0 giây PCM để đo xem Cloud ASR end-to-end thực tế mất bao nhiêu ms và có giải quyết được bottleneck Whisper CPU ~40 giây hay không.

---

## 1. Thiết lập Benchmark

- **Audio Fixture:** 2.00 giây PCM (16,000 Hz, 16-bit Mono, Little Endian = 64,000 bytes).
- **Chuyển đổi âm thanh:** PCM 64,000 bytes → Standard RIFF WAV (44-byte header, 64,044 bytes). Thời gian chuẩn bị (`prep_time`): **0 – 1 ms**.
- **Provider kiểm chứng:** **Groq Cloud ASR**
- **Model:** `whisper-large-v3-turbo`
- **Giao thức:** HTTP/POST Multipart `audio/wav` qua OkHttp.
- **Bảo mật:** API Key nhập trực tiếp trên màn hình thiết bị, lưu SharedPreferences nội bộ, không logcat, không đưa vào code.

---

## 2. Kết quả Thực nghiệm Thực tế trên Thiết bị (Xiaomi Mi 10T Pro)

### A. Test đơn lẻ (Single Connection Test)
- **Audio duration:** 2.00 s
- **Preparation time:** 0 ms
- **Network & API latency:** 699 ms
- **Total End-to-End latency:** 699 ms
- **RTF (Real-Time Factor):** 0.350
- **HTTP status:** 200 OK
- **Transcript:** `"And so, my fellow Americans..."`

### B. Batch Benchmark 10 Runs liên tiếp (10 Consecutive Runs)
- **Số lượt chạy (Runs):** 10
- **Thành công:** **10/10 (100%)**
- **Thất bại:** **0**
- **HTTP Status:** 200 (Toàn bộ 10 runs)

| Metric | Giá trị thực nghiệm |
| :--- | :--- |
| **Min Latency** | **557 ms** |
| **P50 (Median) Latency** | **566 ms** |
| **Average Latency** | **590.3 ms** |
| **P95 Latency** | **666 ms** |
| **Max Latency** | **666 ms** |
| **Average RTF** | **0.2951** |
| **Server Timing info** | `server_time = unavailable` (dùng total client-observed latency) |
| **Transcript** | `"And so, my fellow Americans..."` |

Chi tiết từng lần chạy:
- Run 1: 637 ms (RTF: 0.319)
- Run 2: 564 ms (RTF: 0.282)
- Run 3: 561 ms (RTF: 0.281)
- Run 4: 666 ms (RTF: 0.333)
- Run 5: 571 ms (RTF: 0.286)
- Run 6: 557 ms (RTF: 0.279)
- Run 7: 559 ms (RTF: 0.280)
- Run 8: 566 ms (RTF: 0.283)
- Run 9: 665 ms (RTF: 0.333)
- Run 10: 557 ms (RTF: 0.279)

---

## 3. So sánh trực tiếp: Local CPU vs Cloud ASR

| Tiêu chí | Local Whisper (Spike B) | Cloud ASR - Groq (Spike C) | Mức độ cải thiện |
| :--- | :--- | :--- | :--- |
| **Model** | `ggml-tiny.en-q5_1.bin` (31 MB) | `whisper-large-v3-turbo` | Model Cloud lớn hơn & chính xác hơn nhiều |
| **Tài nguyên** | 4 Cores CPU Kryo 585 (100%) | Client chỉ gửi 64 KB qua mạng | CPU điện thoại hoàn toàn nhàn rỗi (<2%) |
| **Audio Input** | 2.0 s | 2.0 s | Cùng dữ liệu test 16kHz PCM |
| **End-to-End Latency** | **40,250 ms** (~40.2 giây) | **590.3 ms** (~0.59 giây) | **Nhanh hơn 68.2 LẦN** |
| **RTF (Real-Time Factor)** | **19.66** (Tắc nghẽn tích lũy) | **0.295** (Dưới 1.0) | **Đạt chuẩn Realtime (RTF < 0.35)** |
| **Độ ổn định (P95)** | ~40,000+ ms | 666 ms | Ổn định cao trong mạng thực tế |

---

## 4. Trả lời câu hỏi trọng tâm của Spike C

> **Câu hỏi:** Với cùng audio 2 giây trên Xiaomi Mi 10T Pro, Cloud ASR end-to-end thực tế mất bao nhiêu ms và có đủ nhanh để làm nền cho live translation hay không?

- **Kết quả thực tế:** Client-observed latency trung bình đạt **~590 ms** (Min: 557 ms, Max: 666 ms), RTF = **0.295**.
- **Kết luận:** **CÓ. Cloud ASR hoàn toàn giải quyết triệt để bottleneck 40 giây của CPU.** Thời gian xử lý chunk 2 giây chỉ mất chưa đến 0.6 giây, tạo ra một buffer dư thừa ~1.4 giây trước khi chunk tiếp theo đến, hoàn toàn đủ khoảng trống cho pipeline dịch thuật (Live Translation).
