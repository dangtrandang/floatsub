package com.floatsub.probe

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.floatsub.probe.cloud.ApiKeyManager
import com.floatsub.probe.cloud.CloudAsrProvider
import com.floatsub.probe.cloud.GeminiProvider
import com.floatsub.probe.cloud.GoogleTranslator
import com.floatsub.probe.cloud.GroqProvider
import com.floatsub.probe.cloud.GroqTranslator
import com.floatsub.probe.cloud.LiveSubtitleEngine
import com.floatsub.probe.cloud.LiveSubtitleState
import com.floatsub.probe.cloud.OpenAiProvider
import com.floatsub.probe.cloud.TranslationProvider
import com.floatsub.probe.databinding.ActivityMainBinding
import com.floatsub.probe.model.Language
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var apiKeyManager: ApiKeyManager

    private var isCapturingCurrently = false
    private var isDiagnosticsExpanded = false

    // HTTP Client shared across providers
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Independent ASR Providers
    private val groqAsr = GroqProvider(httpClient)
    private val openAiAsr = OpenAiProvider(httpClient)
    private val geminiAsr = GeminiProvider(httpClient)

    // Independent Translation Providers
    private val googleTrans = GoogleTranslator(httpClient)
    private val groqTrans = GroqTranslator(httpClient)

    private var selectedAsrProvider = "Groq"
    private var selectedTransProvider = "Google"

    private var selectedEngineMode = AudioCaptureService.MODE_ON_DEVICE_STREAMING

    private var selectedInputLanguage: Language = Language.AUTO
    private var selectedOutputLanguage: Language = Language.SUPPORTED_OUTPUT_LANGUAGES[0] // Default: Vietnamese

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (recordAudioGranted) {
            CaptureMonitor.log("Permission", "Quyền RECORD_AUDIO đã được cấp.")
            checkAndStartPipeline()
        } else {
            CaptureMonitor.log("Permission", "Người dùng từ chối quyền RECORD_AUDIO")
            Toast.makeText(this, "Cần cấp quyền ghi âm để thu nhận âm thanh!", Toast.LENGTH_LONG).show()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            CaptureMonitor.log("Permission", "Quyền SYSTEM_ALERT_WINDOW đã được cấp.")
            checkAndStartPipeline()
        } else {
            CaptureMonitor.log("Permission", "Chưa cấp quyền SYSTEM_ALERT_WINDOW.")
            Toast.makeText(this, "Cần cấp quyền hiển thị trên ứng dụng khác để hiện phụ đề nổi!", Toast.LENGTH_LONG).show()
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            CaptureMonitor.log("Consent", "MediaProjection consent THÀNH CÔNG (RESULT_OK)")

            // 1. Khởi động AudioCaptureService (gồm floating overlay)
            val chunkDuration = getSelectedChunkDuration()
            val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_START
                putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AudioCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(AudioCaptureService.EXTRA_CHUNK_DURATION_SEC, chunkDuration)
                putExtra(AudioCaptureService.EXTRA_ENGINE_MODE, selectedEngineMode)
            }
            ContextCompat.startForegroundService(this, serviceIntent)

            // 2. Kích hoạt LiveSubtitleEngine nếu chạy Cloud Batch
            if (selectedEngineMode == AudioCaptureService.MODE_CLOUD_BATCH) {
                launchSubtitleEngine()
            }
        } else {
            CaptureMonitor.log("Consent", "Người dùng từ chối cấp quyền MediaProjection")
            Toast.makeText(this, "Cần cấp quyền bắt màn hình/âm thanh để chạy phụ đề!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apiKeyManager = ApiKeyManager(this)

        setupEngineModeSection()
        setupQuickAccessSection()
        setupPrimaryAction()
        setupTranslationSection()
        setupAsrSection()
        setupDiagnosticsSection()

        observeTelemetry()
        observeLiveSubtitleState()
        observeLogs()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Khởi tạo lựa chọn mặc định: Groq ASR + Groq LLM Translation (Task 7 Baseline)
        selectTransProvider("Groq")
        selectAsrProvider("Groq")
    }

    // =========================================================================
    // 0. CẤU HÌNH MÔ HÌNH XỬ LÝ (Engine Mode)
    // =========================================================================
    private fun setupEngineModeSection() {
        binding.btnModeOnDevice.setOnClickListener { selectEngineMode(AudioCaptureService.MODE_ON_DEVICE_STREAMING) }
        binding.btnModeCloud.setOnClickListener { selectEngineMode(AudioCaptureService.MODE_CLOUD_BATCH) }
        selectEngineMode(AudioCaptureService.MODE_ON_DEVICE_STREAMING)
    }

    // =========================================================================
    // 0.5 TRUY CẬP NHANH (QUICK NOTIFICATION & QUICK TILE)
    // =========================================================================
    private fun setupQuickAccessSection() {
        // 1. Khởi tạo và đồng bộ switch thông báo mở nhanh thường trực
        com.floatsub.probe.quicktile.QuickNotificationManager.init(this)
        binding.switchQuickNotification.isChecked = com.floatsub.probe.quicktile.QuickNotificationManager.isEnabled(this)
        binding.switchQuickNotification.setOnCheckedChangeListener { _, isChecked ->
            com.floatsub.probe.quicktile.QuickNotificationManager.setEnabled(this, isChecked)
            val msg = if (isChecked) "Đã bật thông báo mở nhanh trên thanh thông báo" else "Đã tắt thông báo mở nhanh"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // 2. Hướng dẫn & yêu cầu thêm Quick Settings Tile
        binding.btnQuickTileInfo.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    val statusBarManager = getSystemService(android.app.StatusBarManager::class.java)
                    val componentName = android.content.ComponentName(this, com.floatsub.probe.quicktile.FloatSubTileService::class.java)
                    statusBarManager?.requestAddTileService(
                        componentName,
                        "FloatSub",
                        android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_quick_subtitles),
                        mainExecutor
                    ) { result ->
                        CaptureMonitor.log("QuickTile", "Kết quả requestAddTileService: $result")
                    }
                } catch (e: Exception) {
                    CaptureMonitor.log("QuickTile", "Lỗi requestAddTileService: ${e.message}")
                }
            }

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Thêm FloatSub vào Quick Settings")
                .setMessage(
                    "Phím tắt Quick Tile cho phép mở app ngay khi vuốt bảng điều khiển xuống:\n\n" +
                    "1. Vuốt thanh thông báo xuống 2 lần để mở rộng bảng Quick Settings.\n" +
                    "2. Nhấn biểu tượng Sửa (Cây bút chì ✎ hoặc ô dấu + ở góc).\n" +
                    "3. Tìm ô 'FloatSub' ở danh sách phím tắt phía dưới và kéo thả lên trên.\n\n" +
                    "Sau khi thêm, bạn có thể chạm vào ô FloatSub ở bất kỳ đâu để bật app tức thì!"
                )
                .setPositiveButton("Đã hiểu", null)
                .show()
        }
    }

    private fun selectEngineMode(mode: String) {
        selectedEngineMode = mode
        val activeBg = Color.parseColor("#0284C7")
        val inactiveBg = Color.TRANSPARENT
        val inactiveTextColor = Color.parseColor("#9CA3AF")

        fun updateBtn(btn: com.google.android.material.button.MaterialButton, isActive: Boolean) {
            if (isActive) {
                btn.setBackgroundColor(activeBg)
                btn.setTextColor(Color.WHITE)
                btn.strokeWidth = 0
            } else {
                btn.setBackgroundColor(inactiveBg)
                btn.setTextColor(inactiveTextColor)
                btn.strokeWidth = 2
                btn.strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#4B5563"))
            }
        }

        val isOnDevice = mode == AudioCaptureService.MODE_ON_DEVICE_STREAMING
        updateBtn(binding.btnModeOnDevice, isOnDevice)
        updateBtn(binding.btnModeCloud, !isOnDevice)

        if (isOnDevice) {
            binding.tvEngineModeDesc.text = "★ Vosk Streaming + Google ML Kit: 100% Offline, độ trễ ~100ms, 0 API Key, nhảy từng từ theo thời gian thực."
            binding.tvEngineModeDesc.setTextColor(Color.parseColor("#34D399"))
            binding.tvLiveStatus.text = "● Sẵn sàng (On-Device): Bấm bắt đầu để chạy phụ đề Vosk + ML Kit"
        } else {
            binding.tvEngineModeDesc.text = "⚠ Cloud Batch Mode: Gom chunk 1.5s-2.0s gửi Cloud qua Groq/Google REST API. Cần API Key."
            binding.tvEngineModeDesc.setTextColor(Color.parseColor("#F59E0B"))
            binding.tvLiveStatus.text = "● Sẵn sàng (Cloud): Bấm bắt đầu để chạy phụ đề qua Cloud API"
        }
        CaptureMonitor.log("Config", "Đã chọn Pipeline Engine Mode: $mode")
    }

    // =========================================================================
    // 1. THE ONE PRIMARY RUNTIME ACTION
    // =========================================================================
    private fun setupPrimaryAction() {
        binding.btnToggleLiveSubtitle.setOnClickListener {
            val isRunning = if (selectedEngineMode == AudioCaptureService.MODE_ON_DEVICE_STREAMING) {
                com.floatsub.probe.local.OnDeviceStreamingEngine.isRunning || isCapturingCurrently
            } else {
                LiveSubtitleEngine.isLiveActive || isCapturingCurrently
            }

            if (isRunning) {
                stopLivePipeline()
            } else {
                checkAndStartPipeline()
            }
        }
    }

    private fun checkAndStartPipeline() {
        // Step 1: Kiểm tra quyền Hiển thị trên ứng dụng khác (Overlay)
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Vui lòng cấp quyền 'Hiển thị trên các ứng dụng khác' để hiện phụ đề nổi!", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }

        // Step 2: Kiểm tra quyền RECORD_AUDIO & POST_NOTIFICATIONS
        val perms = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (perms.isNotEmpty()) {
            permissionLauncher.launch(perms.toTypedArray())
            return
        }

        // Step 3: Kiểm tra API Keys (chỉ cần khi dùng Cloud mode)
        if (selectedEngineMode == AudioCaptureService.MODE_CLOUD_BATCH) {
            val asrKey = apiKeyManager.getApiKey(selectedAsrProvider)
            if (asrKey.isBlank()) {
                Toast.makeText(this, "Vui lòng nhập và LƯU API Key cho $selectedAsrProvider ASR!", Toast.LENGTH_LONG).show()
                return
            }

            val transKey = if (selectedTransProvider.equals("groq", ignoreCase = true) &&
                selectedAsrProvider.equals("groq", ignoreCase = true)) {
                asrKey
            } else {
                apiKeyManager.getApiKey(selectedTransProvider)
            }

            if (transKey.isBlank()) {
                Toast.makeText(this, "Vui lòng nhập và LƯU API Key cho $selectedTransProvider Translation!", Toast.LENGTH_LONG).show()
                return
            }
        }

        // Step 4: Yêu cầu MediaProjection để bắt âm thanh phát ra (Playback Capture)
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        CaptureMonitor.log("Consent", "Mở hộp thoại hệ thống MediaProjection...")
        val consentIntent = mpManager.createScreenCaptureIntent()
        projectionLauncher.launch(consentIntent)
    }

    private fun launchSubtitleEngine() {
        val asrInstance = getSelectedAsrInstance()
        val transInstance = getSelectedTransInstance()
        val asrKey = apiKeyManager.getApiKey(selectedAsrProvider)
        val transKey = if (selectedTransProvider.equals("groq", ignoreCase = true) &&
            selectedAsrProvider.equals("groq", ignoreCase = true)) {
            asrKey
        } else {
            apiKeyManager.getApiKey(selectedTransProvider)
        }
        val model = getSelectedModelId()

        LiveSubtitleEngine.startLive(
            asr = asrInstance,
            trans = transInstance,
            asrKey = asrKey,
            transKey = transKey,
            model = model,
            sourceLang = selectedInputLanguage.code,
            targetLang = selectedOutputLanguage.code
        )
    }

    private fun stopLivePipeline() {
        if (selectedEngineMode == AudioCaptureService.MODE_CLOUD_BATCH) {
            LiveSubtitleEngine.stopLive()
        } else {
            com.floatsub.probe.local.OnDeviceStreamingEngine.stop()
        }

        val stopIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_STOP
        }
        startService(stopIntent)
        try {
            stopService(Intent(this, AudioCaptureService::class.java))
        } catch (_: Exception) {}

        isCapturingCurrently = false
        binding.btnToggleLiveSubtitle.text = "BẮT ĐẦU PHỤ ĐỀ TRỰC TIẾP"
        binding.btnToggleLiveSubtitle.setBackgroundColor(Color.parseColor("#8B5CF6"))
        binding.tvLiveStatus.text = "● Sẵn sàng: Bấm bắt đầu để hiện phụ đề nổi trên màn hình"
        binding.tvLiveStatus.setTextColor(Color.parseColor("#34D399"))
    }

    // =========================================================================
    // 2. CẤU HÌNH DỊCH & NGÔN NGỮ (Translation Settings)
    // =========================================================================
    private fun setupTranslationSection() {
        // Setup Spinners
        val inputAdapter = ArrayAdapter(
            this,
            R.layout.item_spinner_language,
            Language.SUPPORTED_INPUT_LANGUAGES
        )
        inputAdapter.setDropDownViewResource(R.layout.item_spinner_language)
        binding.spinnerInputLang.adapter = inputAdapter
        binding.spinnerInputLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedInputLanguage = Language.SUPPORTED_INPUT_LANGUAGES[position]
                CaptureMonitor.log("Config", "Đã chọn Input Language: ${selectedInputLanguage.displayName}")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val outputAdapter = ArrayAdapter(
            this,
            R.layout.item_spinner_language,
            Language.SUPPORTED_OUTPUT_LANGUAGES
        )
        outputAdapter.setDropDownViewResource(R.layout.item_spinner_language)
        binding.spinnerOutputLang.adapter = outputAdapter
        binding.spinnerOutputLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedOutputLanguage = Language.SUPPORTED_OUTPUT_LANGUAGES[position]
                CaptureMonitor.log("Config", "Đã chọn Output Language: ${selectedOutputLanguage.displayName}")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Provider Selector Buttons
        binding.btnTransGoogle.setOnClickListener { selectTransProvider("Google") }
        binding.btnTransGroq.setOnClickListener { selectTransProvider("Groq") }

        // Google Save & Test Buttons
        binding.btnSaveGoogleKey.setOnClickListener {
            val key = binding.etGoogleApiKey.text?.toString() ?: ""
            apiKeyManager.saveApiKey("google", key)
            binding.tvGoogleKeyStatus.setTextColor(Color.parseColor("#34D399"))
            binding.tvGoogleKeyStatus.text = if (key.isNotBlank()) "✓ Đã lưu Google API key" else "✕ Key trống!"
            Toast.makeText(this, "Đã lưu Google API key", Toast.LENGTH_SHORT).show()
        }

        binding.btnTestGoogleKey.setOnClickListener {
            val key = binding.etGoogleApiKey.text?.toString()?.trim() ?: ""
            if (key.isBlank()) {
                binding.tvGoogleKeyStatus.setTextColor(Color.parseColor("#EF4444"))
                binding.tvGoogleKeyStatus.text = "✕ Vui lòng nhập Google API Key trước khi test!"
                return@setOnClickListener
            }

            binding.tvGoogleKeyStatus.setTextColor(Color.parseColor("#93C5FD"))
            binding.tvGoogleKeyStatus.text = "Đang gửi test 'Hello, world!' đến Google Translate..."

            lifecycleScope.launch {
                val res = googleTrans.translate("Hello, world!", "en", "vi", key)
                if (res.isSuccess) {
                    binding.tvGoogleKeyStatus.setTextColor(Color.parseColor("#34D399"))
                    binding.tvGoogleKeyStatus.text = "✓ Kết nối OK: \"Hello, world!\" → \"${res.translatedText}\" (${res.latencyMs}ms)"
                } else {
                    binding.tvGoogleKeyStatus.setTextColor(Color.parseColor("#EF4444"))
                    binding.tvGoogleKeyStatus.text = "✕ Lỗi [HTTP ${res.httpStatus}]: ${res.errorMessage}"
                }
            }
        }

        // Groq Translation Save & Test Buttons
        binding.btnSaveGroqTransKey.setOnClickListener {
            val key = binding.etGroqTransApiKey.text?.toString() ?: ""
            apiKeyManager.saveApiKey("groq", key)
            binding.tvGroqTransStatus.setTextColor(Color.parseColor("#34D399"))
            binding.tvGroqTransStatus.text = if (key.isNotBlank()) "✓ Đã lưu Groq API key" else "✕ Key trống!"
            Toast.makeText(this, "Đã lưu Groq API key", Toast.LENGTH_SHORT).show()
        }

        binding.btnTestGroqTransKey.setOnClickListener {
            val key = if (selectedAsrProvider.equals("groq", ignoreCase = true)) {
                apiKeyManager.getApiKey("groq")
            } else {
                binding.etGroqTransApiKey.text?.toString()?.trim() ?: ""
            }

            if (key.isBlank()) {
                binding.tvGroqTransStatus.setTextColor(Color.parseColor("#EF4444"))
                binding.tvGroqTransStatus.text = "✕ Vui lòng nhập Groq API Key trước khi test!"
                return@setOnClickListener
            }

            binding.tvGroqTransStatus.setTextColor(Color.parseColor("#93C5FD"))
            binding.tvGroqTransStatus.text = "Đang gửi test 'Hello, world!' đến Groq LLM..."

            lifecycleScope.launch {
                val res = groqTrans.translate("Hello, world!", "en", "vi", key)
                if (res.isSuccess) {
                    binding.tvGroqTransStatus.setTextColor(Color.parseColor("#34D399"))
                    binding.tvGroqTransStatus.text = "✓ Dịch Groq OK: \"Hello, world!\" → \"${res.translatedText}\" (${res.latencyMs}ms)"
                } else {
                    binding.tvGroqTransStatus.setTextColor(Color.parseColor("#EF4444"))
                    binding.tvGroqTransStatus.text = "✕ Lỗi [HTTP ${res.httpStatus}]: ${res.errorMessage}"
                }
            }
        }
    }

    private fun selectTransProvider(provider: String) {
        selectedTransProvider = provider
        val activeBg = Color.parseColor("#8B5CF6")
        val inactiveBg = Color.TRANSPARENT
        val inactiveTextColor = Color.parseColor("#9CA3AF")

        fun updateBtn(btn: com.google.android.material.button.MaterialButton, isActive: Boolean) {
            if (isActive) {
                btn.setBackgroundColor(activeBg)
                btn.setTextColor(Color.WHITE)
                btn.strokeWidth = 0
            } else {
                btn.setBackgroundColor(inactiveBg)
                btn.setTextColor(inactiveTextColor)
                btn.strokeWidth = 2
                btn.strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#4B5563"))
            }
        }

        updateBtn(binding.btnTransGoogle, provider.equals("google", ignoreCase = true))
        updateBtn(binding.btnTransGroq, provider.equals("groq", ignoreCase = true))

        if (provider.equals("google", ignoreCase = true)) {
            binding.layoutGoogleSection.visibility = View.VISIBLE
            binding.layoutGroqTransSection.visibility = View.GONE
            binding.etGoogleApiKey.setText(apiKeyManager.getApiKey("google"))
        } else {
            binding.layoutGoogleSection.visibility = View.GONE
            binding.layoutGroqTransSection.visibility = View.VISIBLE
            updateGroqKeyReuseState()
        }

        CaptureMonitor.log("Config", "Đã chọn Translation Provider: $provider")
    }

    private fun updateGroqKeyReuseState() {
        val isAsrAlsoGroq = selectedAsrProvider.equals("groq", ignoreCase = true)
        if (isAsrAlsoGroq) {
            binding.tvGroqReusedNote.visibility = View.VISIBLE
            binding.layoutGroqTransApiKey.visibility = View.GONE
            binding.btnSaveGroqTransKey.visibility = View.GONE
        } else {
            binding.tvGroqReusedNote.visibility = View.GONE
            binding.layoutGroqTransApiKey.visibility = View.VISIBLE
            binding.btnSaveGroqTransKey.visibility = View.VISIBLE
            binding.etGroqTransApiKey.setText(apiKeyManager.getApiKey("groq"))
        }
    }

    // =========================================================================
    // 3. CẤU HÌNH ASR (ASR Settings)
    // =========================================================================
    private fun setupAsrSection() {
        binding.btnAsrGroq.setOnClickListener { selectAsrProvider("Groq") }
        binding.btnAsrOpenAi.setOnClickListener { selectAsrProvider("OpenAI") }
        binding.btnAsrGemini.setOnClickListener { selectAsrProvider("Gemini") }

        binding.btnRefreshAsrModels.setOnClickListener {
            refreshGroqModels()
        }

        binding.btnSaveAsrKey.setOnClickListener {
            val key = binding.etAsrApiKey.text?.toString() ?: ""
            apiKeyManager.saveApiKey(selectedAsrProvider, key)
            binding.tvAsrKeyStatus.setTextColor(Color.parseColor("#34D399"))
            binding.tvAsrKeyStatus.text = if (key.isNotBlank()) "✓ Đã lưu $selectedAsrProvider ASR key" else "✕ Key trống!"
            Toast.makeText(this, "Đã lưu $selectedAsrProvider ASR key", Toast.LENGTH_SHORT).show()
            updateGroqKeyReuseState()
        }

        binding.btnTestAsrKey.setOnClickListener {
            val key = binding.etAsrApiKey.text?.toString()?.trim() ?: ""
            if (key.isBlank()) {
                binding.tvAsrKeyStatus.setTextColor(Color.parseColor("#EF4444"))
                binding.tvAsrKeyStatus.text = "✕ Vui lòng nhập $selectedAsrProvider API Key trước khi test!"
                return@setOnClickListener
            }

            binding.tvAsrKeyStatus.setTextColor(Color.parseColor("#93C5FD"))
            binding.tvAsrKeyStatus.text = "Đang kiểm tra kết nối $selectedAsrProvider ASR..."

            lifecycleScope.launch {
                val asr = getSelectedAsrInstance()
                val (pcm, _) = com.floatsub.probe.cloud.FixtureManager.loadFixturePcm(this@MainActivity)
                val conv = com.floatsub.probe.cloud.AudioWavConverter.pcmToWav(pcm, 16000)
                val model = getSelectedModelId()

                val res = asr.transcribe(conv.wavBytes, 2.0, key, model, conv.prepTimeMs)
                if (res.isSuccess) {
                    binding.tvAsrKeyStatus.setTextColor(Color.parseColor("#34D399"))
                    binding.tvAsrKeyStatus.text = "✓ Kết nối $selectedAsrProvider OK! [HTTP ${res.httpStatus}] (${res.networkLatencyMs}ms) \"${res.transcript}\""
                } else {
                    binding.tvAsrKeyStatus.setTextColor(Color.parseColor("#EF4444"))
                    binding.tvAsrKeyStatus.text = "✕ Lỗi [HTTP ${res.httpStatus}]: ${res.errorMessage}"
                }
            }
        }

        // Setup Chunk Duration Spinner (Task 3)
        val chunkOptions = listOf(
            ChunkDurationOption(1.0, "1.0s (Nhanh nhất - Perceived ~1.8s)"),
            ChunkDurationOption(1.5, "1.5s (Cân bằng - Khuyên dùng)"),
            ChunkDurationOption(2.0, "2.0s (Chuẩn cũ 2.0s)")
        )
        val chunkAdapter = ArrayAdapter(this, R.layout.item_spinner_language, chunkOptions)
        chunkAdapter.setDropDownViewResource(R.layout.item_spinner_language)
        binding.spinnerChunkDuration.adapter = chunkAdapter
        binding.spinnerChunkDuration.setSelection(1) // Default 1.5s
    }

    private fun getSelectedChunkDuration(): Double {
        val selected = binding.spinnerChunkDuration.selectedItem as? ChunkDurationOption
        return selected?.durationSec ?: 1.5
    }

    private fun updateAsrModelSpinner(provider: String, customModels: List<AsrModelOption>? = null) {
        val models = customModels ?: when (provider.lowercase()) {
            "groq" -> listOf(
                AsrModelOption("whisper-large-v3-turbo", "whisper-large-v3-turbo (Khuyên dùng)"),
                AsrModelOption("whisper-large-v3", "whisper-large-v3 (Chính xác cao)")
            )
            "openai" -> listOf(
                AsrModelOption("whisper-1", "whisper-1 (Mặc định)")
            )
            "gemini" -> listOf(
                AsrModelOption("gemini-1.5-flash", "gemini-1.5-flash (Khuyên dùng)"),
                AsrModelOption("gemini-2.0-flash", "gemini-2.0-flash (Tốc độ cao)")
            )
            else -> listOf(AsrModelOption("whisper-large-v3-turbo", "whisper-large-v3-turbo (Khuyên dùng)"))
        }

        val adapter = ArrayAdapter(this, R.layout.item_spinner_language, models)
        adapter.setDropDownViewResource(R.layout.item_spinner_language)
        binding.spinnerAsrModel.adapter = adapter

        // Tự động chọn model đầu tiên (recommended/default)
        binding.spinnerAsrModel.setSelection(0)

        // Hiển thị nút refresh nếu provider hỗ trợ API list models (Groq)
        binding.btnRefreshAsrModels.visibility = if (provider.equals("groq", ignoreCase = true)) View.VISIBLE else View.GONE
    }

    private fun refreshGroqModels() {
        val key = binding.etAsrApiKey.text?.toString()?.trim()?.ifBlank { apiKeyManager.getApiKey("groq") } ?: ""
        if (key.isBlank()) {
            Toast.makeText(this, "Vui lòng nhập API Key để lấy danh sách mô hình từ Groq!", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnRefreshAsrModels.text = "Đang tải..."
        binding.btnRefreshAsrModels.isEnabled = false

        lifecycleScope.launch {
            try {
                val request = okhttp3.Request.Builder()
                    .url("https://api.groq.com/openai/v1/models")
                    .addHeader("Authorization", "Bearer $key")
                    .get()
                    .build()

                val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    httpClient.newCall(request).execute()
                }

                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = org.json.JSONObject(body)
                    val dataArr = json.optJSONArray("data")
                    val whisperList = mutableListOf<AsrModelOption>()

                    if (dataArr != null) {
                        for (i in 0 until dataArr.length()) {
                            val id = dataArr.getJSONObject(i).optString("id")
                            if (id.contains("whisper", ignoreCase = true)) {
                                val label = if (id == "whisper-large-v3-turbo") "$id (Khuyên dùng)" else id
                                whisperList.add(AsrModelOption(id, label))
                            }
                        }
                    }

                    if (whisperList.isNotEmpty()) {
                        whisperList.sortByDescending { it.id.contains("turbo") }
                        updateAsrModelSpinner("groq", whisperList)
                        Toast.makeText(this@MainActivity, "✓ Đã tìm thấy ${whisperList.size} mô hình Whisper từ Groq!", Toast.LENGTH_SHORT).show()
                        CaptureMonitor.log("ASR", "Lấy thành công danh sách model Groq: ${whisperList.joinToString { it.id }}")
                    } else {
                        Toast.makeText(this@MainActivity, "Không tìm thấy mô hình Whisper nào trong phản hồi Groq", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@MainActivity, "Lỗi HTTP ${response.code} khi tải danh sách models", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Lỗi kết nối khi tải models: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.btnRefreshAsrModels.text = "↻ Làm mới"
                binding.btnRefreshAsrModels.isEnabled = true
            }
        }
    }

    private fun getSelectedModelId(): String {
        val selected = binding.spinnerAsrModel.selectedItem as? AsrModelOption
        return selected?.id ?: getSelectedAsrInstance().defaultModel
    }

    private fun selectAsrProvider(provider: String) {
        selectedAsrProvider = provider
        val activeBg = Color.parseColor("#3B82F6")
        val inactiveBg = Color.TRANSPARENT
        val inactiveTextColor = Color.parseColor("#9CA3AF")

        fun updateBtn(btn: com.google.android.material.button.MaterialButton, isActive: Boolean) {
            if (isActive) {
                btn.setBackgroundColor(activeBg)
                btn.setTextColor(Color.WHITE)
                btn.strokeWidth = 0
            } else {
                btn.setBackgroundColor(inactiveBg)
                btn.setTextColor(inactiveTextColor)
                btn.strokeWidth = 2
                btn.strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#4B5563"))
            }
        }

        updateBtn(binding.btnAsrGroq, provider.equals("groq", ignoreCase = true))
        updateBtn(binding.btnAsrOpenAi, provider.equals("openai", ignoreCase = true))
        updateBtn(binding.btnAsrGemini, provider.equals("gemini", ignoreCase = true))

        updateAsrModelSpinner(provider)
        binding.tvAsrKeyLabel.text = "${provider.uppercase()} ASR API KEY:"
        binding.etAsrApiKey.setText(apiKeyManager.getApiKey(provider))
        binding.tvAsrKeyStatus.text = ""

        updateGroqKeyReuseState()
        CaptureMonitor.log("Config", "Đã chọn ASR Provider: $provider")
    }

    private fun getSelectedAsrInstance(): CloudAsrProvider {
        return when (selectedAsrProvider.lowercase()) {
            "groq" -> groqAsr
            "openai" -> openAiAsr
            "gemini" -> geminiAsr
            else -> groqAsr
        }
    }

    private fun getSelectedTransInstance(): TranslationProvider {
        return when (selectedTransProvider.lowercase()) {
            "groq" -> groqTrans
            "google" -> googleTrans
            else -> googleTrans
        }
    }

    // =========================================================================
    // 4. CHẨN ĐOÁN & LOG (Collapsible Section)
    // =========================================================================
    private fun setupDiagnosticsSection() {
        binding.btnToggleDiagnostics.setOnClickListener {
            isDiagnosticsExpanded = !isDiagnosticsExpanded
            binding.layoutDiagnosticsContent.visibility = if (isDiagnosticsExpanded) View.VISIBLE else View.GONE
            binding.btnToggleDiagnostics.text = if (isDiagnosticsExpanded) {
                "▼ Đóng thông số Audio Capture & Log chẩn đoán"
            } else {
                "▶ Chẩn đoán Audio Capture & Event Log (Nâng cao)"
            }
        }

        binding.btnClearLog.setOnClickListener {
            binding.tvLog.text = ""
        }
    }

    // =========================================================================
    // 5. OBSERVERS
    // =========================================================================
    private fun observeLiveSubtitleState() {
        lifecycleScope.launch {
            LiveSubtitleEngine.state.collectLatest { state ->
                when (state) {
                    is LiveSubtitleState.Idle -> {
                        if (!isCapturingCurrently) {
                            binding.btnToggleLiveSubtitle.text = "BẮT ĐẦU PHỤ ĐỀ TRỰC TIẾP"
                            binding.btnToggleLiveSubtitle.setBackgroundColor(Color.parseColor("#8B5CF6"))
                            binding.tvLiveStatus.text = "● Sẵn sàng: Bấm bắt đầu để hiện phụ đề nổi trên màn hình"
                            binding.tvLiveStatus.setTextColor(Color.parseColor("#34D399"))
                        }
                    }
                    is LiveSubtitleState.Listening -> {
                        binding.btnToggleLiveSubtitle.text = "DỪNG PHỤ ĐỀ TRỰC TIẾP"
                        binding.btnToggleLiveSubtitle.setBackgroundColor(Color.parseColor("#EF4444"))
                        binding.tvLiveStatus.text = "● Đang chạy: Phụ đề nổi đang hiển thị! Mở YouTube/video để xem"
                        binding.tvLiveStatus.setTextColor(Color.parseColor("#10B981"))
                    }
                    is LiveSubtitleState.Processing -> {
                        binding.tvLiveStatus.text = "● Đang xử lý chunk #${state.chunkIndex} qua $selectedAsrProvider + $selectedTransProvider..."
                    }
                    is LiveSubtitleState.NewSubtitle -> {
                        val res = state.result
                        val displaySourceLang = res.detectedLanguage ?: selectedInputLanguage.code
                        binding.tvOriginalLangLabel.text = "LỜI THOẠI GỐC [$displaySourceLang - $selectedAsrProvider]:"
                        binding.tvOriginalTranscript.text = "\"${res.transcript}\""

                        binding.tvTranslatedLangLabel.text = "PHỤ ĐỀ DỊCH [${selectedOutputLanguage.code} - $selectedTransProvider]:"
                        binding.tvTranslatedSubtitle.text = "\"${res.translatedText}\""

                        binding.tvAsrLatency.text = "${res.asrLatencyMs} ms"
                        binding.tvTransLatency.text = "${res.transLatencyMs} ms"
                        binding.tvE2eLatency.text = "${res.perceivedLatencyMs} ms (Perceived)"
                        binding.tvHeadroom.text = "${res.captureWaitMs} ms (Window)"
                    }
                    is LiveSubtitleState.Error -> {
                        binding.tvLiveStatus.text = "● Lỗi: ${state.message}"
                        binding.tvLiveStatus.setTextColor(Color.parseColor("#EF4444"))
                        Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        lifecycleScope.launch {
            com.floatsub.probe.local.OnDeviceStreamingEngine.state.collectLatest { state ->
                when (state) {
                    is com.floatsub.probe.local.OnDeviceStreamingState.Idle -> {
                        if (selectedEngineMode == AudioCaptureService.MODE_ON_DEVICE_STREAMING && !isCapturingCurrently) {
                            binding.btnToggleLiveSubtitle.text = "BẮT ĐẦU PHỤ ĐỀ TRỰC TIẾP"
                            binding.btnToggleLiveSubtitle.setBackgroundColor(Color.parseColor("#8B5CF6"))
                            binding.tvLiveStatus.text = "● Sẵn sàng (On-Device): Bấm bắt đầu để chạy phụ đề Vosk + ML Kit"
                            binding.tvLiveStatus.setTextColor(Color.parseColor("#34D399"))
                        }
                    }
                    is com.floatsub.probe.local.OnDeviceStreamingState.Initializing -> {
                        binding.tvLiveStatus.text = "● Đang khởi tạo Vosk & ML Kit..."
                        binding.tvLiveStatus.setTextColor(Color.parseColor("#93C5FD"))
                    }
                    is com.floatsub.probe.local.OnDeviceStreamingState.Listening -> {
                        binding.btnToggleLiveSubtitle.text = "DỪNG PHỤ ĐỀ TRỰC TIẾP"
                        binding.btnToggleLiveSubtitle.setBackgroundColor(Color.parseColor("#EF4444"))
                        binding.tvLiveStatus.text = "● Đang chạy (On-Device Streaming): Mở video/YouTube để xem phụ đề!"
                        binding.tvLiveStatus.setTextColor(Color.parseColor("#10B981"))
                    }
                    is com.floatsub.probe.local.OnDeviceStreamingState.Streaming -> {
                        val sub = state.subtitle
                        val flag = if (sub.isFinal) "[FINAL]" else "[STREAM]"
                        binding.tvOriginalLangLabel.text = "LỜI THOẠI GỐC [EN - Vosk $flag]:"
                        binding.tvOriginalTranscript.text = "\"${sub.englishText}\""

                        binding.tvTranslatedLangLabel.text = "PHỤ ĐỀ DỊCH [VI - ML Kit On-Device]:"
                        binding.tvTranslatedSubtitle.text = "\"${sub.vietnameseText}\""

                        binding.tvAsrLatency.text = "~30 ms"
                        binding.tvTransLatency.text = "${sub.transLatencyMs} ms"
                        binding.tvE2eLatency.text = "${sub.transLatencyMs + 50} ms"
                        binding.tvHeadroom.text = "0 ms (Real-time)"
                    }
                    is com.floatsub.probe.local.OnDeviceStreamingState.Error -> {
                        binding.tvLiveStatus.text = "● Lỗi On-Device: ${state.message}"
                        binding.tvLiveStatus.setTextColor(Color.parseColor("#EF4444"))
                        Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun observeTelemetry() {
        lifecycleScope.launch {
            CaptureMonitor.telemetry.collectLatest { telemetry ->
                isCapturingCurrently = telemetry.isCapturing

                if (telemetry.isCapturing) {
                    if (telemetry.hasSignal) {
                        binding.tvSignalStatus.text = "PCM Signal: CÓ TÍN HIỆU (LIVE)"
                        binding.tvSignalStatus.setTextColor(Color.parseColor("#10B981"))
                    } else {
                        binding.tvSignalStatus.text = "PCM Signal: CÂM / CHỜ TÍN HIỆU"
                        binding.tvSignalStatus.setTextColor(Color.parseColor("#F59E0B"))
                    }

                    binding.tvRms.text = String.format(
                        Locale.US,
                        "RMS: %.1f dBFS | Peak: %.1f dBFS",
                        telemetry.rmsDb,
                        telemetry.peakDb
                    )

                    val progressPercent = ((telemetry.rmsDb + 70f) / 70f * 100f).toInt().coerceIn(0, 100)
                    binding.progressRms.progress = progressPercent

                    val kbRead = telemetry.totalBytes / 1024.0
                    binding.tvStreamStats.text = String.format(
                        Locale.US,
                        "Frames: %,d | Data: %.1f KB | Time: %.1fs",
                        telemetry.totalFrames,
                        kbRead,
                        telemetry.durationSeconds
                    )
                } else {
                    isCapturingCurrently = false
                    binding.tvSignalStatus.text = "PCM Signal: IDLE"
                    binding.tvSignalStatus.setTextColor(Color.parseColor("#9CA3AF"))
                    binding.progressRms.progress = 0
                    binding.tvRms.text = "RMS: -∞ dBFS | Peak: -∞ dBFS"
                    binding.btnToggleLiveSubtitle.text = "BẮT ĐẦU PHỤ ĐỀ TRỰC TIẾP"
                    binding.btnToggleLiveSubtitle.setBackgroundColor(Color.parseColor("#8B5CF6"))
                    binding.tvLiveStatus.text = if (selectedEngineMode == AudioCaptureService.MODE_ON_DEVICE_STREAMING) {
                        "● Sẵn sàng (On-Device): Bấm bắt đầu để chạy phụ đề Vosk + ML Kit"
                    } else {
                        "● Sẵn sàng (Cloud): Bấm bắt đầu để chạy phụ đề qua Cloud API"
                    }
                    binding.tvLiveStatus.setTextColor(Color.parseColor("#34D399"))
                }
            }
        }
    }

    private fun observeLogs() {
        lifecycleScope.launch {
            CaptureMonitor.logEvents.collectLatest { logLine ->
                val currentText = binding.tvLog.text.toString()
                binding.tvLog.text = if (currentText.isEmpty()) logLine else "$currentText\n$logLine"

                binding.scrollLog.post {
                    binding.scrollLog.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }
}

data class AsrModelOption(val id: String, val displayName: String) {
    override fun toString(): String = displayName
}

data class ChunkDurationOption(val durationSec: Double, val displayName: String) {
    override fun toString(): String = displayName
}

