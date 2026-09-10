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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    
    var selectedModel by remember { mutableStateOf("deepseek-chat") }
    var pronounTheme by remember { mutableStateOf("Neutral") }
    var apiKeyInput by remember { mutableStateOf("") }
    var autoHideSeconds by remember { mutableStateOf(5) }
    
    var availableModels by remember { mutableStateOf(listOf("deepseek-chat")) }
    var modelsLoading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        selectedModel = settingsRepo.selectedModel.first()
        pronounTheme = settingsRepo.pronounTheme.first()
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
        var modelsDropdownExpanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { modelsDropdownExpanded = true }) {
                Text("โมเดล: $selectedModel")
            }
            DropdownMenu(
                expanded = modelsDropdownExpanded,
                onDismissRequest = { modelsDropdownExpanded = false }
            ) {
                availableModels.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model) },
                        onClick = {
                            selectedModel = model
                            coroutineScope.launch { settingsRepo.updateModel(model) }
                            modelsDropdownExpanded = false
                        }
                    )
                }
            }
        }
        
        Button(onClick = {
            coroutineScope.launch {
                modelsLoading = true
                try {
                    val keyToUse = apiKeyInput.trim().ifEmpty { BuildConfig.DEEPSEEK_API_KEY }
                    val response = RetrofitClient.api.getModels("Bearer $keyToUse")
                    availableModels = response.data.map { it.id }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    modelsLoading = false
                }
            }
        }, enabled = !modelsLoading) {
            Text(if (modelsLoading) "กำลังโหลดโมเดล..." else "อัปเดตโมเดลจาก API")
        }

        // Pronoun Theme Dropdown
        val themes = listOf("Neutral", "I/You", "Master/Servant", "Commander/Soldier", "Hero/Villain")
        var themesDropdownExpanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { themesDropdownExpanded = true }) {
                Text("ธีม/สรรพนาม: $pronounTheme")
            }
            DropdownMenu(
                expanded = themesDropdownExpanded,
                onDismissRequest = { themesDropdownExpanded = false }
            ) {
                themes.forEach { theme ->
                    DropdownMenuItem(
                        text = { Text(theme) },
                        onClick = {
                            pronounTheme = theme
                            coroutineScope.launch { settingsRepo.updatePronounTheme(theme) }
                            themesDropdownExpanded = false
                        }
                    )
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
