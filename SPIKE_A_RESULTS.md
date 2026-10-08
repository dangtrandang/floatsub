# KẾT QUẢ THỰC NGHIỆM SPIKE A: INTERNAL AUDIO PLAYBACK CAPTURE

*Ngày thực hiện: 08/10/2026*  
*Mục tiêu: Kiểm chứng thực địa khả năng bắt âm thanh nội bộ (AudioPlaybackCapture) trên thiết bị thật mà không dùng Microphone.*

---

## 1. Thông Tin Thiết Bị Kiểm Thử (Target Device)

* **Model máy:** Xiaomi Mi 10T Pro 5G (`M2007J3SG`)
* **Chipset (SoC):** Qualcomm Snapdragon 865 (`SM8250`)
  * CPU: 8 nhân (Kryo 585: 1x 2.84GHz Prime, 3x 2.42GHz Gold, 4x 1.80GHz Silver)
  * Tập lệnh: ARMv8.2-A (Hỗ trợ phần cứng ARM NEON FP16, dot product - cực kỳ thuận lợi cho Whisper)
* **Hệ điều hành:** Android 12 (API 31)
* **Giao diện tùy biến:** MIUI 14 (`V140`)

---

## 2. Kết Quả Bắt Âm Thanh Theo Ứng Dụng (App Verification Matrix)

| Nguồn phát | Package ID | Kết quả | Chi tiết luồng âm thanh cấp thấp (AudioFlinger) |
| :--- | :--- | :---: | :--- |
| **YouTube App** | `com.google.android.youtube` | **PASS** | Tóm trực tiếp ExoPlayer; submix stream 16,000 Hz liên tục; cờ `Sil: n` (không câm). |
| **Browser (Cốc Cốc / Chromium)** | `com.coccoc.trinhduyet` | **PASS** | Tóm trọn vẹn video YouTube chạy trên nền web HTML5; không đứt gãy luồng. |
| **TikTok** | `com.zhiliaoapp.musically` | **PASS** | TTPlayer của TikTok không chặn capture; nhận đều đặn ~16,000 samples/giây. |
| **Facebook (Watch / Reels)** | `com.facebook.katanb` | **PASS** | Video/Reels xuất âm thanh vào mixer chuẩn; capture nhận dữ liệu trơn tru. |

---

## 3. Bằng Chứng Kỹ Thuật Cấp Nhân Hệ Thống (Kernel / Audio HAL)

Dữ liệu trích xuất trực tiếp từ `dumpsys media.audio_flinger`:

```text
Track Capture:           Active = yes, Id = 2204, Port Id = 2305
Client Process:          PID = 5039 (com.floatsub.probe), UID = 10497
Session ID:              9233
Audio Format:            AUDIO_FORMAT_PCM_16_BIT (0x00000001)
Channel Config:          CHANNEL_IN_MONO (0x00000010)
Sample Rate:             16,000 Hz
Audio Source:            Source 8 (AUDIO_SOURCE_REMOTE_SUBMIX)
Input Device:            0x80000100 (AUDIO_DEVICE_IN_REMOTE_SUBMIX)
Silence Flag (Sil):      n  (NO -> Hoàn toàn có tín hiệu âm thanh thật)
Tốc độ trung bình:       15,970 – 16,080 samples/giây (~16.0 kHz)
Tổng dung lượng đo đạc:  > 6,830,000 frames PCM (~7.1 phút ghi liên tục không đứt)
```

---

## 4. Đánh Giá Độ Ổn Định Chạy Nền (MIUI 14 Background Behavior)

* **Foreground Service:** Đăng ký thành công loại `mediaProjection`.
* **Chuyển app liên tục:** Người dùng lần lượt chuyển qua YouTube $\to$ Trình duyệt $\to$ TikTok $\to$ Facebook trong hơn 7 phút.
* **Hành vi dọn RAM của MIUI:** Dịch vụ ghi âm và luồng đọc PCM vẫn duy trì liên tục ở tốc độ 16 kHz, **hoàn toàn không bị MIUI 14 kill hay gián đoạn**.

---

## 5. Kết Luận & Quyết Định Cột Mốc (Gate Decision)

1. **Khả năng bắt âm thanh nội bộ:** **CHÍNH THỨC XÁC NHẬN KHẢ THI 100% TRÊN MÁY THỰC TẾ.** 
   Nút thắt kỹ thuật lớn nhất của dự án đã được vượt qua. Toàn bộ các nền tảng video mục tiêu (YouTube, Web, TikTok, Facebook) đều cho phép trích xuất PCM sạch.
2. **Tuân thủ quy trình Fail-Fast:**
   * Spike A đã hoàn thành trọn vẹn nhiệm vụ duy nhất của nó.
   * Mã nguồn Spike A được giữ ở mức tối giản (chỉ gồm `MainActivity` + `AudioCaptureService`).
   * Chưa thêm bất kỳ code thừa nào của ASR, VAD, Translation hay Overlay.
3. **Sẵn sàng cho Spike B:** Bước tiếp theo sẽ là đưa stream PCM này vào benchmark Whisper trực tiếp trên chip Snapdragon 865 để đo RTF (Real-Time Factor) và RAM tiêu thụ.
