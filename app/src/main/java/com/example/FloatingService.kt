package com.example

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Translate
import com.example.api.GoogleTranslateHelper
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.api.*
import com.example.data.SettingsRepository
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.ByteBuffer
import java.util.Locale

class FloatingService : LifecycleService(), SavedStateRegistryOwner, ViewModelStoreOwner {

    private val mViewModelStore = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = mViewModelStore

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleComposeView: ComposeView
    private lateinit var bubbleParams: WindowManager.LayoutParams

    private lateinit var textOverlayComposeView: ComposeView
    private lateinit var textOverlayParams: WindowManager.LayoutParams

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private lateinit var settingsRepo: SettingsRepository
    private var lastExtractedText: String? = null
    private var cachedTranslation: String? = null
    private var autoHideJob: Job? = null
    private var translationJob: Job? = null

    // Compose states
    private var currentTranslation by mutableStateOf("")
    private var isTranslating by mutableStateOf(false)
    private var isSelectionMode by mutableStateOf(false)
    private var currentBoundingBox by mutableStateOf("0,0,100,100")
    private var overlayOpacity by mutableStateOf(85)
    private var activeModelName by mutableStateOf("deepseek-v4-flash")
    private var activeEngine by mutableStateOf("deepseek") // "deepseek" or "google"

    private var bubbleX = 30
    private var bubbleY = 300
    private var textOverlayX = 40
    private var textOverlayY = 600

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private fun toggleTranslationEngine() {
        val nextEngine = if (activeEngine == "google") "deepseek" else "google"
        activeEngine = nextEngine
        cachedTranslation = null // Clear cache so re-translating with the new engine fetches freshly
        lifecycleScope.launch {
            settingsRepo.updateTranslationEngine(nextEngine)
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        settingsRepo = SettingsRepository(this)

        lifecycleScope.launch {
            settingsRepo.boundingBox.collect { currentBoundingBox = it }
        }
        lifecycleScope.launch {
            settingsRepo.overlayOpacity.collect { overlayOpacity = it }
        }
        lifecycleScope.launch {
            settingsRepo.selectedModel.collect { activeModelName = it }
        }
        lifecycleScope.launch {
            settingsRepo.translationEngine.collect { activeEngine = it }
        }

        createNotificationChannel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    1,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(1, buildNotification())
            }
        } catch (e: Exception) {
            Log.e("FloatingService", "Error calling startForeground", e)
            startForeground(1, buildNotification())
        }

        setupFloatingViews()
    }

    private fun setupFloatingViews() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = resources.displayMetrics
        val screenW = displayMetrics.widthPixels
        val screenH = displayMetrics.heightPixels
        bubbleX = 30
        bubbleY = (screenH * 0.35f).toInt()
        textOverlayX = (screenW * 0.06f).toInt()
        textOverlayY = (screenH * 0.65f).toInt()

        // 1. Bubble Params
        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleX
            y = bubbleY
        }

        // 2. Text Overlay Params (starts hidden)
        textOverlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = textOverlayX
            y = textOverlayY
        }

        // Initialize Bubble ComposeView
        bubbleComposeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingService)
            setViewTreeSavedStateRegistryOwner(this@FloatingService)
            setViewTreeViewModelStoreOwner(this@FloatingService)

            setContent {
                MaterialTheme {
                    if (isSelectionMode) {
                        SelectionOverlay(
                            initialBox = currentBoundingBox,
                            onSave = { newBox ->
                                lifecycleScope.launch { settingsRepo.updateBoundingBox(newBox) }
                                enableSelectionMode(false)
                            },
                            onCancel = { enableSelectionMode(false) }
                        )
                    } else {
                        BubbleUI(
                            isTranslating = isTranslating,
                            activeEngine = activeEngine,
                            onTap = { handleBubbleTap() },
                            onToggleEngine = { toggleTranslationEngine() },
                            onLongPress = { enableSelectionMode(true) },
                            onDrag = { dx, dy ->
                                bubbleX += dx.toInt()
                                bubbleY += dy.toInt()
                                bubbleParams.x = bubbleX
                                bubbleParams.y = bubbleY
                                try {
                                    windowManager.updateViewLayout(bubbleComposeView, bubbleParams)
                                } catch (e: Exception) {
                                    Log.e("FloatingService", "Error updating bubble layout", e)
                                }
                            }
                        )
                    }
                }
            }
        }

        // Initialize Movable Text Overlay ComposeView
        textOverlayComposeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingService)
            setViewTreeSavedStateRegistryOwner(this@FloatingService)
            setViewTreeViewModelStoreOwner(this@FloatingService)
            visibility = View.GONE

            setContent {
                MaterialTheme {
                    MovableTranslationOverlay(
                        translation = currentTranslation,
                        isTranslating = isTranslating,
                        opacityPercent = overlayOpacity,
                        engine = activeEngine,
                        modelName = activeModelName,
                        onToggleEngine = {
                            toggleTranslationEngine()
                            // Also optionally re-trigger translation with the newly selected engine
                            handleBubbleTap()
                        },
                        onClose = { hideTranslationView() },
                        onDrag = { dx, dy ->
                            textOverlayX += dx.toInt()
                            textOverlayY += dy.toInt()
                            textOverlayParams.x = textOverlayX
                            textOverlayParams.y = textOverlayY
                            try {
                                windowManager.updateViewLayout(textOverlayComposeView, textOverlayParams)
                            } catch (e: Exception) {
                                Log.e("FloatingService", "Error moving text overlay", e)
                            }
                        },
                        onDragEnd = {
                            lifecycleScope.launch {
                                settingsRepo.updateTextPos(textOverlayX, textOverlayY)
                            }
                        }
                    )
                }
            }
        }

        try {
            windowManager.addView(bubbleComposeView, bubbleParams)
        } catch (e: Exception) {
            Log.e("FloatingService", "Error adding bubbleComposeView", e)
        }

        try {
            windowManager.addView(textOverlayComposeView, textOverlayParams)
        } catch (e: Exception) {
            Log.e("FloatingService", "Error adding textOverlayComposeView", e)
        }

        // Load saved text overlay position
        lifecycleScope.launch {
            val savedX = settingsRepo.textPosX.first()
            val savedY = settingsRepo.textPosY.first()
            if (savedX >= 0) textOverlayX = savedX.coerceIn(0, (screenW - 100).coerceAtLeast(0))
            if (savedY >= 0) textOverlayY = savedY.coerceIn(0, (screenH - 100).coerceAtLeast(0))
            textOverlayParams.x = textOverlayX
            textOverlayParams.y = textOverlayY
            if (::textOverlayComposeView.isInitialized && textOverlayComposeView.isAttachedToWindow) {
                try {
                    windowManager.updateViewLayout(textOverlayComposeView, textOverlayParams)
                } catch (e: Exception) {
                    Log.e("FloatingService", "Error updating initial overlay position", e)
                }
            }
        }
    }

    private fun enableSelectionMode(enabled: Boolean) {
        isSelectionMode = enabled
        if (enabled) {
            hideTranslationView()
            bubbleParams.width = WindowManager.LayoutParams.MATCH_PARENT
            bubbleParams.height = WindowManager.LayoutParams.MATCH_PARENT
            bubbleParams.x = 0
            bubbleParams.y = 0
        } else {
            bubbleParams.width = WindowManager.LayoutParams.WRAP_CONTENT
            bubbleParams.height = WindowManager.LayoutParams.WRAP_CONTENT
            bubbleParams.x = bubbleX
            bubbleParams.y = bubbleY
        }
        try {
            windowManager.updateViewLayout(bubbleComposeView, bubbleParams)
        } catch (e: Exception) {
            Log.e("FloatingService", "Error updating selection mode layout", e)
        }
    }

    private fun showTranslationView() {
        if (!::textOverlayComposeView.isInitialized) return
        textOverlayComposeView.visibility = View.VISIBLE
        textOverlayParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        try {
            windowManager.updateViewLayout(textOverlayComposeView, textOverlayParams)
        } catch (e: Exception) {
            Log.e("FloatingService", "Error showing text overlay", e)
        }
    }

    private fun hideTranslationView() {
        if (!::textOverlayComposeView.isInitialized) return
        autoHideJob?.cancel()
        translationJob?.cancel()
        isTranslating = false
        textOverlayComposeView.visibility = View.GONE
        textOverlayParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try {
            windowManager.updateViewLayout(textOverlayComposeView, textOverlayParams)
        } catch (e: Exception) {
            Log.e("FloatingService", "Error hiding text overlay", e)
        }
    }

    private fun handleBubbleTap() {
        autoHideJob?.cancel()
        translationJob?.cancel()

        translationJob = lifecycleScope.launch {
            try {
                captureAndStreamTranslate()
            } catch (e: Throwable) {
                Log.e("FloatingService", "Error in captureAndStreamTranslate launch", e)
                withContext(Dispatchers.Main) {
                    resetState("เกิดข้อผิดพลาด: ${e.localizedMessage ?: "ไม่ทราบสาเหตุ"}")
                }
            }
        }
    }

    private suspend fun captureAndStreamTranslate() {
        withContext(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                isTranslating = true
                currentTranslation = "กำลังจับภาพหน้าจอ..."
                showTranslationView()
            }

            delay(150)

            val bitmap = captureScreen()
            if (bitmap == null) {
                withContext(Dispatchers.Main) {
                    resetState("ไม่สามารถจับภาพหน้าจอได้ (กรุณาเปิดบริการใหม่)")
                }
                return@withContext
            }

            withContext(Dispatchers.Main) {
                currentTranslation = "กำลังอ่านข้อความ..."
            }

            // Bounding box cropping
            val boxStr = settingsRepo.boundingBox.first()
            val parts = boxStr.split(",").mapNotNull { it.toFloatOrNull() }
            val croppedBitmap = if (parts.size == 4) {
                val (px, py, pw, ph) = parts
                val bx = (px / 100f * bitmap.width).toInt().coerceIn(0, (bitmap.width - 1).coerceAtLeast(0))
                val by = (py / 100f * bitmap.height).toInt().coerceIn(0, (bitmap.height - 1).coerceAtLeast(0))
                val bw = (pw / 100f * bitmap.width).toInt().coerceIn(1, (bitmap.width - bx).coerceAtLeast(1))
                val bh = (ph / 100f * bitmap.height).toInt().coerceIn(1, (bitmap.height - by).coerceAtLeast(1))
                try {
                    Bitmap.createBitmap(bitmap, bx, by, bw, bh)
                } catch (e: Exception) {
                    bitmap
                }
            } else bitmap

            val image = InputImage.fromBitmap(croppedBitmap, 0)
            val result = com.google.android.gms.tasks.Tasks.await(textRecognizer.process(image))
            val text = result.text.trim()
            if (text.isEmpty()) {
                withContext(Dispatchers.Main) {
                    resetState("ไม่พบข้อความในพื้นที่ที่กำหนด")
                }
                return@withContext
            }

            // Cache check
            if (text == lastExtractedText && cachedTranslation != null) {
                withContext(Dispatchers.Main) {
                    currentTranslation = cachedTranslation!!
                    isTranslating = false
                    scheduleAutoHide()
                }
                return@withContext
            }

            lastExtractedText = text

            val currentEngine = settingsRepo.translationEngine.first()
            activeEngine = currentEngine

            // 1. If Google Translate is selected
            if (currentEngine == "google") {
                withContext(Dispatchers.Main) {
                    currentTranslation = "กำลังแปลด้วย Google..."
                }
                try {
                    val rawTranslation = GoogleTranslateHelper.translateToThai(text)
                    val customPronouns = settingsRepo.customPronouns.first().trim()
                    val enablePronouns = settingsRepo.googlePronounsEnabled.first()
                    val finalTranslation = if (enablePronouns && customPronouns.isNotEmpty()) {
                        GoogleTranslateHelper.applyCustomPronouns(rawTranslation, customPronouns)
                    } else {
                        rawTranslation
                    }
                    withContext(Dispatchers.Main) {
                        currentTranslation = finalTranslation
                        cachedTranslation = finalTranslation
                        isTranslating = false
                        scheduleAutoHide()
                    }
                } catch (e: Exception) {
                    Log.e("FloatingService", "Google Translate error", e)
                    withContext(Dispatchers.Main) {
                        resetState("Google แปลภาษาผิดพลาด: ${e.message}")
                    }
                }
                return@withContext
            }

            // 2. If DeepSeek AI is selected
            val userKey = settingsRepo.apiKey.first()
            val apiKey = userKey.trim().ifEmpty { BuildConfig.DEEPSEEK_API_KEY }
            if (apiKey.isNullOrEmpty()) {
                withContext(Dispatchers.Main) {
                    resetState("กรุณาใส่ API Key ในหน้าตั้งค่าแอป")
                }
                return@withContext
            }

            val model = settingsRepo.selectedModel.first()
            val customPronouns = settingsRepo.customPronouns.first().trim()
            val customPromptTemplate = settingsRepo.customPrompt.first().trim()

            val prompt = if (customPromptTemplate.isNotEmpty()) {
                if (customPromptTemplate.contains("{pronouns}")) {
                    customPromptTemplate.replace("{pronouns}", customPronouns.ifEmpty { "ธรรมชาติ/เป็นกันเอง" })
                } else if (customPronouns.isNotEmpty()) {
                    "$customPromptTemplate Pronouns: $customPronouns."
                } else {
                    customPromptTemplate
                }
            } else {
                if (customPronouns.isNotEmpty()) {
                    "Translate to Thai game dialogue. Compact and natural. Pronouns: $customPronouns. Output ONLY the Thai translation, nothing else."
                } else {
                    "Translate to Thai game dialogue. Compact and natural. Output ONLY the Thai translation, nothing else."
                }
            }

            val request = ChatRequest(
                model = model,
                messages = listOf(
                    ChatMessage("system", prompt),
                    ChatMessage("user", text)
                ),
                stream = true
            )
            Log.i("FloatingService", "Sending translation request with model: '$model'")

            withContext(Dispatchers.Main) {
                currentTranslation = "กำลังแปลด้วย AI..."
            }

            val response = try {
                RetrofitClient.api.streamTranslateText("Bearer $apiKey", request)
            } catch (e: Exception) {
                Log.e("FloatingService", "Network error calling stream API", e)
                withContext(Dispatchers.Main) {
                    resetState("ข้อผิดพลาดเครือข่าย: ${e.message}")
                }
                return@withContext
            }

            if (!response.isSuccessful) {
                val errorBody = response.errorBody()?.string() ?: "HTTP ${response.code()}"
                Log.e("FloatingService", "API error: ${response.code()} $errorBody")
                withContext(Dispatchers.Main) {
                    resetState("ข้อผิดพลาด API (${response.code()}): $errorBody")
                }
                return@withContext
            }

            val responseBody = response.body()
            if (responseBody == null) {
                withContext(Dispatchers.Main) {
                    resetState("ไม่มีข้อมูลตอบกลับจากเซิร์ฟเวอร์")
                }
                return@withContext
            }

            val source = responseBody.source()
            val streamAdapter = RetrofitClient.moshi.adapter(StreamChunk::class.java)
            val sb = StringBuilder()
            var receivedTokens = false

            try {
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.startsWith("data: ")) {
                        val data = line.substring(6).trim()
                        if (data == "[DONE]") {
                            break
                        }
                        try {
                            val chunk = streamAdapter.fromJson(data)
                            val deltaContent = chunk?.choices?.firstOrNull()?.delta?.content
                            if (!deltaContent.isNullOrEmpty()) {
                                sb.append(deltaContent)
                                val currentText = sb.toString()
                                withContext(Dispatchers.Main) {
                                    currentTranslation = currentText
                                    isTranslating = false
                                }
                                receivedTokens = true
                            }
                        } catch (e: Exception) {
                            // ignore malformed SSE json or comment lines
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("FloatingService", "Error reading stream", e)
            }

            val finalText = sb.toString().trim()
            if (finalText.isNotEmpty()) {
                cachedTranslation = finalText
                withContext(Dispatchers.Main) {
                    currentTranslation = finalText
                    isTranslating = false
                    scheduleAutoHide()
                }
            } else if (!receivedTokens) {
                withContext(Dispatchers.Main) {
                    resetState("ไม่มีข้อความแปล")
                }
            }
        }
    }

    private fun resetState(msg: String) {
        isTranslating = false
        currentTranslation = msg
        showTranslationView()
        scheduleAutoHide()
    }

    private fun scheduleAutoHide() {
        autoHideJob?.cancel()
        autoHideJob = lifecycleScope.launch {
            try {
                val seconds = settingsRepo.autoHideSeconds.first()
                if (seconds > 0) {
                    delay(seconds * 1000L)
                    withContext(Dispatchers.Main) {
                        hideTranslationView()
                    }
                }
            } catch (e: Exception) {
                Log.e("FloatingService", "Error in autoHideJob", e)
            }
        }
    }

    @SuppressLint("WrongConstant")
    private suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.IO) {
        val proj = mediaProjection ?: return@withContext null

        try {
            val displayMetrics = resources.displayMetrics
            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels
            val density = displayMetrics.densityDpi

            if (imageReader == null) {
                imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            }
            if (virtualDisplay == null) {
                virtualDisplay = proj.createVirtualDisplay(
                    "ScreenCapture",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface, null, null
                )
            }

            var image: Image? = null
            for (i in 0 until 6) {
                image = imageReader?.acquireLatestImage()
                if (image != null) break
                delay(80)
            }

            if (image == null) {
                Log.w("FloatingService", "acquireLatestImage returned null after retries")
                return@withContext null
            }

            image.use { img ->
                val planes = img.planes
                val buffer: ByteBuffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)

                if (rowPadding == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, width, height)
            }
        } catch (e: Exception) {
            Log.e("FloatingService", "Error in captureScreen", e)
            null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val resultCode = intent?.getIntExtra("RESULT_CODE", 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("DATA")
        }

        if (resultCode != 0 && data != null) {
            try {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection?.stop()
                mediaProjection = mpm.getMediaProjection(resultCode, data)
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        super.onStop()
                        try {
                            virtualDisplay?.release()
                            virtualDisplay = null
                            imageReader?.close()
                            imageReader = null
                            mediaProjection = null
                        } catch (e: Exception) {
                            Log.e("FloatingService", "Error in onStop callback", e)
                        }
                    }
                }, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {
                Log.e("FloatingService", "Error registering MediaProjection", e)
            }
        }

        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        autoHideJob?.cancel()
        translationJob?.cancel()
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.e("FloatingService", "Error during projection cleanup", e)
        }
        try {
            if (::bubbleComposeView.isInitialized && bubbleComposeView.isAttachedToWindow) {
                windowManager.removeView(bubbleComposeView)
            }
        } catch (e: Exception) {
            Log.e("FloatingService", "Error removing bubbleComposeView", e)
        }
        try {
            if (::textOverlayComposeView.isInitialized && textOverlayComposeView.isAttachedToWindow) {
                windowManager.removeView(textOverlayComposeView)
            }
        } catch (e: Exception) {
            Log.e("FloatingService", "Error removing textOverlayComposeView", e)
        }
        mViewModelStore.clear()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "floating_service",
                "ระบบแปลภาษา",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, "floating_service")
            .setContentTitle("ตัวแปลภาษากำลังทำงาน")
            .setContentText("บับเบิลแปลภาษากำลังทำงานอยู่บนหน้าจอ")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }
}

@Composable
fun BubbleUI(
    isTranslating: Boolean,
    activeEngine: String = "deepseek",
    onTap: () -> Unit,
    onToggleEngine: () -> Unit = {},
    onLongPress: () -> Unit,
    onDrag: (Float, Float) -> Unit
) {
    val isGoogle = activeEngine == "google"
    val accentColor = if (isGoogle) Color(0xFF10B981) else Color(0xFF60A5FA)
    val badgeBg = if (isGoogle) Color(0xFF065F46) else Color(0xFF1E3A8A)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.wrapContentSize()
    ) {
        // Main circular bubble
        Box(
            modifier = Modifier
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onLongPress = { onLongPress() }
                    )
                }
                .size(54.dp)
                .clip(CircleShape)
                .background(Color(0xE6111827))
                .border(2.5.dp, accentColor.copy(alpha = 0.85f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (isTranslating) {
                CircularProgressIndicator(
                    color = accentColor,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Icon(
                    imageVector = if (isGoogle) Icons.Default.Translate else Icons.Default.AutoAwesome,
                    contentDescription = if (isGoogle) "Google Translate (แตะเพื่อแปล)" else "DeepSeek AI (แตะเพื่อแปล)",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(3.dp))

        // Quick Toggle Engine Pill (Tap to switch between AI and Google instantly!)
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = badgeBg,
            border = BorderStroke(1.dp, accentColor),
            shadowElevation = 3.dp,
            modifier = Modifier.clickable { onToggleEngine() }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = if (isGoogle) "🌐 G-ฟรี" else "🤖 AI",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun MovableTranslationOverlay(
    translation: String,
    isTranslating: Boolean,
    opacityPercent: Int = 85,
    engine: String = "deepseek",
    modelName: String = "deepseek-v4-flash",
    onToggleEngine: () -> Unit = {},
    onClose: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit
) {
    val alpha = (opacityPercent / 100f).coerceIn(0.15f, 1f)
    val isGoogle = engine == "google"
    val badgeBorder = if (isGoogle) Color(0xFF34D399) else Color(0xFF60A5FA)
    val badgeBg = if (isGoogle) Color(0x3310B981) else Color(0x333B82F6)
    val badgeText = if (isGoogle) Color(0xFFA7F3D0) else Color(0xFF93C5FD)

    Card(
        modifier = Modifier
            .widthIn(min = 220.dp, max = 340.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { onDragEnd() }
                ) { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF18181B).copy(alpha = alpha)
        ),
        border = BorderStroke(1.dp, Color.White.copy(alpha = (alpha * 0.35f).coerceAtLeast(0.12f))),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Header Row: Drag handle + Status + Clickable Mode Badge + Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = "ลากเพื่อย้าย",
                        tint = Color(0x99FFFFFF),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isTranslating) "แปล..." else "คำแปล",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isTranslating) badgeText else Color(0xCCFFFFFF),
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    // Clickable Engine Badge (Tap to quickly switch and re-translate!)
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = badgeBg,
                        border = BorderStroke(0.5.dp, badgeBorder),
                        modifier = Modifier.clickable { onToggleEngine() }
                    ) {
                        Text(
                            text = if (isGoogle) "🌐 Google (สลับ AI)" else "🤖 $modelName (สลับ G)",
                            fontSize = 10.sp,
                            color = badgeText,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "ปิด",
                        tint = Color(0xCCFFFFFF),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Body text with vertical scroll for long messages
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = translation,
                    color = Color.White,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Normal
                )
            }
        }
    }
}

@Composable
fun SelectionOverlay(
    initialBox: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
    ) {
        val density = LocalDensity.current
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()

        val parts = initialBox.split(",").mapNotNull { it.toFloatOrNull() }
        val initX = if (parts.size == 4) parts[0] / 100f * screenW else 0f
        val initY = if (parts.size == 4) parts[1] / 100f * screenH else 0f
        val initW = if (parts.size == 4) parts[2] / 100f * screenW else screenW
        val initH = if (parts.size == 4) parts[3] / 100f * screenH else screenH

        var offsetX by remember { mutableStateOf(initX) }
        var offsetY by remember { mutableStateOf(initY) }
        var width by remember { mutableStateOf(initW) }
        var height by remember { mutableStateOf(initH) }

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.toInt(), offsetY.toInt()) }
                .size(
                    with(density) { width.toDp() },
                    with(density) { height.toDp() }
                )
                .border(2.dp, Color.Green)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount.x).coerceIn(0f, screenW - width)
                        offsetY = (offsetY + dragAmount.y).coerceIn(0f, screenH - height)
                    }
                }
        ) {
            // Top Left Handle
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset((-12).dp, (-12).dp)
                    .size(24.dp)
                    .background(Color.White, CircleShape)
                    .border(2.dp, Color.Green, CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val newOffsetX = (offsetX + dragAmount.x).coerceIn(0f, offsetX + width - 100f)
                            val newOffsetY = (offsetY + dragAmount.y).coerceIn(0f, offsetY + height - 100f)
                            width += (offsetX - newOffsetX)
                            height += (offsetY - newOffsetY)
                            offsetX = newOffsetX
                            offsetY = newOffsetY
                        }
                    }
            )
            // Bottom Right Handle
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(12.dp, 12.dp)
                    .size(24.dp)
                    .background(Color.White, CircleShape)
                    .border(2.dp, Color.Green, CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            width = (width + dragAmount.x).coerceIn(100f, screenW - offsetX)
                            height = (height + dragAmount.y).coerceIn(100f, screenH - offsetY)
                        }
                    }
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
        ) {
            Button(onClick = onCancel) {
                Text("ยกเลิก")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = {
                val px = (offsetX / screenW * 100).coerceIn(0f, 100f)
                val py = (offsetY / screenH * 100).coerceIn(0f, 100f)
                val pw = (width / screenW * 100).coerceIn(1f, 100f - px)
                val ph = (height / screenH * 100).coerceIn(1f, 100f - py)
                val newBoxStr = String.format(Locale.US, "%.1f,%.1f,%.1f,%.1f", px, py, pw, ph)
                onSave(newBoxStr)
            }) {
                Text("บันทึกกรอบข้อความ")
            }
        }
    }
}
