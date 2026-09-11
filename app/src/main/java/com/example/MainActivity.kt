package com.example

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.api.RetrofitClient
import com.example.data.SettingsRepository
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var settingsRepo: SettingsRepository
    private var isOverlayGranted by mutableStateOf(false)

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, FloatingService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error starting FloatingService", e)
                Toast.makeText(this, "ไม่สามารถเริ่มบริการได้: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepo = SettingsRepository(this)
        checkOverlayPermission()

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        settingsRepo = settingsRepo,
                        isOverlayGranted = isOverlayGranted,
                        onRequestOverlay = { requestOverlayPermission() },
                        onStartService = { startCaptureService() },
                        onStopService = {
                            stopService(Intent(this@MainActivity, FloatingService::class.java))
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkOverlayPermission()
    }

    private fun checkOverlayPermission() {
        isOverlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun startCaptureService() {
        if (!isOverlayGranted) {
            requestOverlayPermission()
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    settingsRepo: SettingsRepository,
    isOverlayGranted: Boolean,
    onRequestOverlay: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    
    var selectedModel by remember { mutableStateOf("deepseek-flash") }
    var customPronounsInput by remember { mutableStateOf("ฉัน / เธอ") }
    var overlayOpacity by remember { mutableStateOf(85) }
    var apiKeyInput by remember { mutableStateOf("") }
    var autoHideSeconds by remember { mutableStateOf(5) }
    
    val defaultModels = remember {
        listOf(
            "deepseek-flash",
            "deepseek-v4-flash",
            "deepseek-chat",
            "deepseek-v4-pro",
            "deepseek-reasoner"
        )
    }
    var availableModels by remember { mutableStateOf(defaultModels) }
    var modelsLoading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        selectedModel = settingsRepo.selectedModel.first()
        customPronounsInput = settingsRepo.customPronouns.first()
        overlayOpacity = settingsRepo.overlayOpacity.first()
        apiKeyInput = settingsRepo.apiKey.first()
        autoHideSeconds = settingsRepo.autoHideSeconds.first()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("การตั้งค่าตัวแปลเกม", style = MaterialTheme.typography.headlineMedium)

        if (!isOverlayGranted) {
            Button(onClick = onRequestOverlay) {
                Text("อนุญาตให้แสดงทับแอปอื่น (Overlay)")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStartService, enabled = isOverlayGranted) {
                Text("เปิดใช้งานบับเบิลแปลภาษา")
            }
            Button(onClick = onStopService) {
                Text("ปิดการใช้งาน")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // API Key Field
        OutlinedTextField(
            value = apiKeyInput,
            onValueChange = {
                apiKeyInput = it
                coroutineScope.launch { settingsRepo.updateApiKey(it) }
            },
            label = { Text("DeepSeek API Key (ไม่บังคับถ้าใส่ใน .env แล้ว)") },
            placeholder = { Text("sk-...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // Models
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("โมเดล DeepSeek", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "แนะนำ: deepseek-flash (V4 Flash) ประหยัดโทเค็นมากที่สุดและตอบสนองเร็วพิเศษ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = selectedModel,
                onValueChange = {
                    selectedModel = it
                    coroutineScope.launch { settingsRepo.updateModel(it) }
                },
                label = { Text("ชื่อโมเดลที่ใช้งาน") },
                placeholder = { Text("เช่น deepseek-flash, deepseek-chat") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Quick Chips for popular models
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                availableModels.forEach { model ->
                    val isFlash = model.contains("flash", ignoreCase = true)
                    SuggestionChip(
                        onClick = {
                            selectedModel = model
                            coroutineScope.launch { settingsRepo.updateModel(model) }
                        },
                        label = {
                            Text(
                                if (isFlash) "⚡ $model (ประหยัด)" else model,
                                fontWeight = if (selectedModel == model) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        border = if (selectedModel == model) {
                            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        } else null
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(onClick = {
                    coroutineScope.launch {
                        modelsLoading = true
                        try {
                            val keyToUse = apiKeyInput.trim().ifEmpty { BuildConfig.DEEPSEEK_API_KEY }
                            val response = RetrofitClient.api.getModels("Bearer $keyToUse")
                            val apiModels = response.data.map { it.id }
                            availableModels = (defaultModels + apiModels).distinct()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            modelsLoading = false
                        }
                    }
                }, enabled = !modelsLoading) {
                    Text(if (modelsLoading) "กำลังโหลด..." else "🔄 ซิงค์รายการโมเดลจาก API")
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Custom Pronouns / Tone input (Freeform text field)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("สรรพนาม / สไตล์ภาษาในการแปล", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = customPronounsInput,
                onValueChange = {
                    customPronounsInput = it
                    coroutineScope.launch { settingsRepo.updateCustomPronouns(it) }
                },
                label = { Text("ระบุสรรพนามตามต้องการ") },
                placeholder = { Text("เช่น ฉัน/เธอ, กู/มึง, ข้า/เอ็ง, นายท่าน/ข้า") },
                supportingText = { Text("พิมพ์คู่สรรพนาม หรือสไตล์การพูดที่ต้องการให้แปลได้อิสระ") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Quick suggestion chips
            val quickSuggestions = listOf("ฉัน / เธอ", "กู / มึง", "ข้า / เอ็ง", "ผม / คุณ", "นายท่าน / ข้า", "เป็นกันเอง/คำหยาบได้")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickSuggestions.forEach { suggestion ->
                    SuggestionChip(
                        onClick = {
                            customPronounsInput = suggestion
                            coroutineScope.launch { settingsRepo.updateCustomPronouns(suggestion) }
                        },
                        label = { Text(suggestion) }
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Overlay Opacity / Transparency Slider
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "ความทึบแสงกล่องแปล:",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "$overlayOpacity%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                "ปรับให้โปร่งแสงเพื่อให้อ่านข้อความแปลได้โดยไม่บังตัวเกมด้านหลัง",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Slider(
                value = overlayOpacity.toFloat(),
                onValueChange = {
                    overlayOpacity = it.toInt()
                    coroutineScope.launch { settingsRepo.updateOverlayOpacity(it.toInt()) }
                },
                valueRange = 20f..100f,
                steps = 15,
                modifier = Modifier.fillMaxWidth()
            )

            // Live Preview Container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0F172A)),
                contentAlignment = Alignment.Center
            ) {
                Text("🎮 (จำลองภาพฉากหลังในเกม)", color = Color(0x55FFFFFF), fontSize = 12.sp)
                Card(
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF18181B).copy(alpha = (overlayOpacity / 100f).coerceIn(0.15f, 1f))
                    ),
                    border = BorderStroke(
                        1.dp,
                        Color.White.copy(alpha = ((overlayOpacity / 100f) * 0.35f).coerceAtLeast(0.12f))
                    )
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "ตัวอย่างกล่องแปลโปร่งใส ($overlayOpacity%)",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Auto-hide Duration Settings
        val autoHideOptions = listOf(
            0 to "ไม่ซ่อนอัตโนมัติ (แตะเพื่อปิดเอง)",
            3 to "3 วินาที",
            5 to "5 วินาที (ค่าเริ่มต้น)",
            7 to "7 วินาที",
            10 to "10 วินาที",
            15 to "15 วินาที"
        )
        var autoHideDropdownExpanded by remember { mutableStateOf(false) }
        val currentLabel = autoHideOptions.firstOrNull { it.first == autoHideSeconds }?.second ?: "$autoHideSeconds วินาที"

        Text("เวลาแสดงผลก่อนข้อความหายไป:", style = MaterialTheme.typography.titleSmall)
        Box {
            OutlinedButton(onClick = { autoHideDropdownExpanded = true }) {
                Text(currentLabel)
            }
            DropdownMenu(
                expanded = autoHideDropdownExpanded,
                onDismissRequest = { autoHideDropdownExpanded = false }
            ) {
                autoHideOptions.forEach { (seconds, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            autoHideSeconds = seconds
                            coroutineScope.launch { settingsRepo.updateAutoHideSeconds(seconds) }
                            autoHideDropdownExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
