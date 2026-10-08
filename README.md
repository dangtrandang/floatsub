# 🎬 FloatSub – Android Live Subtitles & Floating Translation
> **Ứng dụng tạo phụ đề và dịch thuật âm thanh hệ thống thời gian thực với khung nổi thông minh trên Android (Tối ưu cho MIUI / HyperOS & AOSP).**

---

## 📌 1. Giới thiệu tổng quan (Overview)

**FloatSub** là giải pháp phụ đề trực tiếp (Live Captions) đa ngôn ngữ chạy trên nền tảng Android. Ứng dụng thu trực tiếp luồng âm thanh nội bộ phát ra từ thiết bị (**Internal Audio Playback Capture** qua API `MediaProjection` & `AudioPlaybackCaptureConfiguration`), chuyển hóa thành văn bản (Speech-to-Text) và dịch tự động (Translation) sang tiếng Việt (hoặc ngôn ngữ tùy chọn), sau đó hiển thị dạng phụ đề nổi (**Floating Overlay Window**) ngay trên màn hình ứng dụng đang phát (YouTube, TikTok, Netflix, Twitter/X, Podcast, Game,...).

### ✨ Điểm nổi bật:
* 🔊 **Thu âm thanh nội bộ 100%:** Bắt trọn luồng âm thanh kỹ thuật số sạch của hệ thống, không bị lẫn tạp âm từ môi trường xung quanh như micro thông thường.
* ⚡ **Chế độ On-Device Offline cực nhẹ:** Tích hợp engine ASR Vosk (Kaldi-based) chạy hoàn toàn offline trên điện thoại, độ trễ thấp (~150ms–300ms chunking), không tốn chi phí token.
* ☁️ **Hỗ trợ Cloud Streaming:** Tích hợp pipeline Gemini Live / QuickTranslate xử lý ngữ cảnh đa ngôn ngữ chính xác cao khi có kết nối mạng.
* 🪟 **Giao diện phụ đề nổi 2 hàng (Dual-line HUD):** Tối ưu font chữ, nền mờ gradient sang trọng (Glassmorphism), thuật toán ngắt dòng thông minh, không bị nhảy giật chữ hoa/thường vô cớ.
* 🔘 **Bật/Tắt 1 chạm qua Quick Settings Tile & Notification:** Điều khiển bật tắt phụ đề tức thì từ trung tâm điều khiển (Control Center) hoặc thanh thông báo mà không cần chuyển màn hình vào App.

---

## 📐 2. Kiến trúc kỹ thuật (Architecture)

```
       [ Ứng dụng phát âm thanh: YouTube / TikTok / Game / Phim ]
                                  │
                                  ▼ (Internal Audio Stream)
                     [ MediaProjection Service ]
                                  │
                                  ▼ (AudioPlaybackCapture API)
                     [ AudioCaptureService.kt ]
                                  │ (PCM 16kHz 16-bit Mono)
                    ┌─────────────┴─────────────┐
                    ▼                           ▼
       [ Local: Vosk ASR Engine ]     [ Cloud: Gemini / Quick API ]
                    │                           │
                    ▼                           ▼
        (Text Stabilizer & Punctuation Breaker)
                    │
                    ▼
          [ Google / MT5 Translate ]
                    │
                    ▼ (Vietnamese Subtitle)
         [ FloatingSubtitleManager.kt ]
                    │
                    ▼ (WindowManager Overlay)
        [ Cửa sổ phụ đề nổi 2 hàng / Bong bóng thu nhỏ ]
```

---

## 🚀 3. Tính năng trải nghiệm người dùng (UX/UI Highlights)

### 1. Khung phụ đề nổi 2 hàng (Floating Subtitles Box)
* **Thuật toán ổn định văn bản (Text Stabilization):** Tự động chia dòng hợp lý (2 hàng), tránh tình trạng chữ nhảy liên tục gây mỏi mắt.
* **Tự do di chuyển (Draggable):** Kéo thả tự do đến mọi vị trí trên màn hình; tự động nhớ tọa độ người dùng mong muốn.
* **Bong bóng thu gọn (Bubble Mode):** Khi không xem phụ đề, có thể thu nhỏ thành biểu tượng nổi tinh gọn. **Nhấn giữ lâu (Long Press)** vào bong bóng sẽ mở ngay giao diện ứng dụng.

### 2. Phím tắt 1 chạm Quick Settings Tile
* Tích hợp trực tiếp vào ngăn kéo cài đặt nhanh (Quick Settings Drawer) của Android / MIUI Control Center (`FloatSubTileService`).
* **1 chạm BẬT:** Gọi thẳng hộp thoại xác nhận thu âm hệ thống trong suốt (`QuickToggleActivity`), tự khởi chạy phụ đề mà không che khuất màn hình video.
* **1 chạm TẮT:** Dừng ngay lập tức service và đóng overlay mà không làm gián đoạn trải nghiệm của người dùng.

### 3. Thông báo tương tác (Smart Interactive Notification)
* **Khi đang chờ (Idle):** Thông báo ghim sẵn nút *"▶ Chạm vào đây để BẬT phụ đề ngay"*.
* **Khi đang chạy (Active):** Thông báo hiển thị trạng thái phát cùng nút *"⏹ Chạm vào đây để TẮT phụ đề ngay"*.

---

## 📂 4. Cấu trúc thư mục & Vai trò các tệp tin (Project Structure)

```
floatsub/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml          # Khai báo permissions, Foreground Service, Quick Tile & Activity
│   │   ├── java/com/floatsub/probe/
│   │   │   ├── MainActivity.kt          # Màn hình cấu hình chính (Chọn chế độ, ngôn ngữ, nút Start/Stop)
│   │   │   ├── AudioCaptureService.kt   # Foreground Service quản lý thu âm nội bộ & vòng đời pipeline
│   │   │   ├── FloatingSubtitleManager.kt # Quản lý WindowManager overlay (hiển thị sub 2 hàng & bong bóng nổi)
│   │   │   ├── local/
│   │   │   │   ├── VoskRecognizerHelper.kt  # Wrapper khởi tạo và nạp stream PCM vào Vosk ASR Model
│   │   │   │   └── VoskBenchmarkReceiver.kt # Broadcast receiver phục vụ đo đạc hiệu năng offline
│   │   │   ├── quicktile/
│   │   │   │   ├── FloatSubTileService.kt   # Service xử lý Quick Settings Tile trên thanh điều khiển
│   │   │   │   ├── QuickToggleActivity.kt   # Activity trong suốt hỗ trợ bật/tắt 1 chạm qua MediaProjection
│   │   │   │   └── QuickNotificationManager.kt # Quản lý thông báo tương tác ghim trạng thái Bật/Tắt
│   │   │   ├── quicktranslate/
│   │   │   │   ├── QuickTranslatePipeline.kt # Pipeline nhận dạng & dịch thuật tốc độ cao
│   │   │   │   └── QuickTranslateBenchmarkReceiver.kt
│   │   │   └── cloud/
│   │   │       ├── GeminiLiveAudioClient.kt # Client kết nối WebSocket/Streaming tới Gemini API
│   │   │       └── GeminiBenchmarkReceiver.kt
│   │   └── res/
│   │       ├── drawable/
│   │       │   ├── ic_quick_subtitles.xml   # Icon vector dùng cho Quick Settings Tile
│   │       │   ├── shape_floating_subtitles.xml # Nền bo góc gradient kính cho khung phụ đề
│   │       │   ├── shape_floating_bubble.xml    # Nền cho bong bóng thu gọn
│   │       │   └── shape_dot_green.xml      # Đèn tín hiệu trạng thái LIVE
│   │       ├── layout/
│   │       │   ├── activity_main.xml        # Giao diện màn hình chính
│   │       │   ├── layout_floating_subtitles.xml # Layout khung phụ đề nổi 2 hàng
│   │       │   └── layout_floating_bubble.xml    # Layout bong bóng nổi thu nhỏ
│   │       ├── mipmap-*/
│   │       │   ├── ic_launcher.png          # App icon chuẩn raster đa độ phân giải (mdpi -> xxxhdpi)
│   │       │   └── ic_launcher_round.png    # App icon tròn chuẩn đa độ phân giải
│   │       └── values/
│   │           ├── colors.xml, strings.xml
│   │           └── themes.xml               # Bao gồm Theme.FloatSubProbe và Theme.Transparent
│   └── build.gradle.kts                 # Dependencies (AndroidX, Vosk Android SDK, OkHttp, Coroutines)
├── whisper_src/                         # Thư viện core C++ Whisper.cpp (phục vụ Spike/JNI Engine)
├── scripts/
│   └── generate_icons.py               # Script Python (Pillow) tạo bộ icon thích ứng sắc nét
├── ARCHITECTURE_ONDEVICE_STREAMING.md  # Bản thiết kế chi tiết kiến trúc On-Device Streaming
├── SPIKE_A_RESULTS.md                   # Kết quả thử nghiệm hiệu năng Whisper.cpp trên chip Snapdragon
├── SPIKE_C_RESULTS.md                   # Kết quả đo đạc Vosk ASR & độ trễ phân đoạn
├── build.gradle.kts, settings.gradle.kts
└── README.md                            # Tài liệu hướng dẫn dự án
```

---

## 🛠️ 5. Yêu cầu hệ thống & Cài đặt (Requirements & Setup)

### Yêu cầu thiết bị:
* **Hệ điều hành:** Android 10 (API Level 29) trở lên (bắt buộc để sử dụng `AudioPlaybackCaptureConfiguration`).
* **Thiết bị thử nghiệm chuẩn:** Xiaomi Mi 10T Pro (Snapdragon 865, Android 12 MIUI).
* **Quyền hạn cần thiết (Runtime Permissions):**
  1. `SYSTEM_ALERT_WINDOW`: Cho phép hiển thị trên các ứng dụng khác (Vẽ overlay).
  2. `RECORD_AUDIO`: Ghi âm hệ thống.
  3. `FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_MEDIA_PROJECTION`: Duy trì dịch vụ phụ đề nền.

### Hướng dẫn Build từ mã nguồn:
```bash
# 1. Clone repository
git clone https://github.com/dangtrandang/floatsub.git
cd floatsub

# 2. Build APK bản Debug
./gradlew assembleDebug

# 3. Cài đặt trực tiếp lên điện thoại qua ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📄 6. License
Dự án được phát triển dưới sự bảo hộ mã nguồn mở phục vụ mục đích nghiên cứu và sử dụng cá nhân.
Mọi đóng góp (Pull Request, Issue) đều được hoan nghênh!
