package com.example

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.example.api.ChatRequest
import com.example.api.ChatMessage
import com.example.api.RetrofitClient
import com.example.data.SettingsRepository
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

class FloatingService : LifecycleService(), SavedStateRegistryOwner {

    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private lateinit var params: WindowManager.LayoutParams

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    private lateinit var settingsRepo: SettingsRepository
    private var lastExtractedText: String? = null
    private var cachedTranslation: String? = null

    // Compose states
    private var showTranslation by mutableStateOf(false)
    private var currentTranslation by mutableStateOf("")
    private var isTranslating by mutableStateOf(false)

    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        settingsRepo = SettingsRepository(this)
        
        createNotificationChannel()
        startForeground(1, buildNotification())
        
        setupFloatingView()
    }

    private fun setupFloatingView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100

        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingService)
            setViewTreeSavedStateRegistryOwner(this@FloatingService)
            
            setContent {
                MaterialTheme {
                    FloatingUI(
                        showTranslation = showTranslation,
                        translation = currentTranslation,
                        isTranslating = isTranslating,
                        onTap = { handleTap() },
                        onDrag = { dx, dy ->
                            params.x += dx.toInt()
                            params.y += dy.toInt()
                            windowManager.updateViewLayout(composeView, params)
                        }
                    )
                }
            }
        }

        windowManager.addView(composeView, params)
    }

    private fun handleTap() {
        if (showTranslation) {
            // Hide translation
            showTranslation = false
        } else {
            // Trigger capture
            isTranslating = true
            lifecycleScope.launch {
                captureAndTranslate()
            }
        }
    }

    private suspend fun captureAndTranslate() {
        // Short delay to allow button state to update and maybe hide
        kotlinx.coroutines.delay(200)

        val bitmap = captureScreen() ?: return resetState("Failed to capture screen")
        
        // Bounding box cropping
        val boxStr = settingsRepo.boundingBox.first()
        val parts = boxStr.split(",").mapNotNull { it.toFloatOrNull() }
        val croppedBitmap = if (parts.size == 4) {
            val (px, py, pw, ph) = parts
            val bx = (px / 100f * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
            val by = (py / 100f * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
            val bw = (pw / 100f * bitmap.width).toInt().coerceIn(1, bitmap.width - bx)
            val bh = (ph / 100f * bitmap.height).toInt().coerceIn(1, bitmap.height - by)
            Bitmap.createBitmap(bitmap, bx, by, bw, bh)
        } else bitmap

        val image = InputImage.fromBitmap(croppedBitmap, 0)
        
        try {
            val result = com.google.android.gms.tasks.Tasks.await(textRecognizer.process(image))
            val text = result.text.trim()
            if (text.isEmpty()) {
                resetState("No text found")
                return
            }

            if (text == lastExtractedText && cachedTranslation != null) {
                showResult(cachedTranslation!!)
                return
            }

            lastExtractedText = text
            val translation = performTranslation(text)
            cachedTranslation = translation
            showResult(translation)

        } catch (e: Exception) {
            Log.e("FloatingService", "Error during translation", e)
            resetState("Error: ${e.message}")
        }
    }

    private fun resetState(msg: String) {
        isTranslating = false
        currentTranslation = msg
        showTranslation = true
    }

    private fun showResult(translation: String) {
        isTranslating = false
        currentTranslation = translation
        showTranslation = true
    }

    private suspend fun performTranslation(text: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val apiKey = BuildConfig.DEEPSEEK_API_KEY
                if (apiKey.isNullOrEmpty()) return@withContext "API Key missing in .env"

                val model = settingsRepo.selectedModel.first()
                val pronoun = settingsRepo.pronounTheme.first()

                val systemPrompt = """
                    Strictly output only the translated text in Thai. 
                    No markdown, no conversational filler, no explanations. 
                    Context/Pronouns: $pronoun.
                """.trimIndent()

                val request = ChatRequest(
                    model = model,
                    messages = listOf(
                        ChatMessage("system", systemPrompt),
                        ChatMessage("user", text)
                    )
                )

                val response = RetrofitClient.api.translateText("Bearer $apiKey", request)
                response.choices.firstOrNull()?.message?.content?.trim() ?: "No translation"
            } catch (e: Exception) {
                Log.e("FloatingService", "API Error", e)
                "API Error: ${e.message}"
            }
        }
    }

    @SuppressLint("WrongConstant")
    private suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.IO) {
        if (mediaProjection == null) return@withContext null

        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val density = displayMetrics.densityDpi

        if (imageReader == null) {
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
        }

        // Wait a tiny bit for the surface to get an image
        kotlinx.coroutines.delay(100)
        
        val image: Image? = imageReader?.acquireLatestImage()
        image?.let {
            val planes = it.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            it.close()
            
            // Crop to actual width if needed due to row padding
            if (rowPadding == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, width, height)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        val resultCode = intent?.getIntExtra("RESULT_CODE", 0) ?: 0
        val data: Intent? = intent?.getParcelableExtra("DATA")
        
        if (resultCode != 0 && data != null) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, data)
        }

        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        if (::composeView.isInitialized) {
            windowManager.removeView(composeView)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "floating_service",
                "Translation Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, "floating_service")
            .setContentTitle("Translation Active")
            .setContentText("Floating widget is running")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }
}

@Composable
fun FloatingUI(
    showTranslation: Boolean,
    translation: String,
    isTranslating: Boolean,
    onTap: () -> Unit,
    onDrag: (Float, Float) -> Unit
) {
    Box(
        modifier = Modifier
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
            .clip(if (showTranslation) RoundedCornerShape(8.dp) else CircleShape)
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable { onTap() }
            .padding(if (showTranslation) 16.dp else 12.dp)
    ) {
        if (showTranslation) {
            Text(
                text = translation,
                color = Color.White,
                fontSize = 16.sp
            )
        } else if (isTranslating) {
            Text(
                text = "...",
                color = Color.White,
                fontSize = 16.sp
            )
        } else {
            Icon(
                imageVector = Icons.Default.Translate,
                contentDescription = "Translate",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
