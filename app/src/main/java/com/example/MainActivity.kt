package com.example

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
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
    var boundingBox by remember { mutableStateOf("0,0,100,100") }
    var apiKeyInput by remember { mutableStateOf("") }
    
    var availableModels by remember { mutableStateOf(listOf("deepseek-chat")) }
    var modelsLoading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        selectedModel = settingsRepo.selectedModel.first()
        pronounTheme = settingsRepo.pronounTheme.first()
        boundingBox = settingsRepo.boundingBox.first()
        apiKeyInput = settingsRepo.apiKey.first()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Game Translator Settings", style = MaterialTheme.typography.headlineMedium)

        if (!isOverlayGranted) {
            Button(onClick = onRequestOverlay) {
                Text("Grant Overlay Permission")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStartService, enabled = isOverlayGranted) {
                Text("Start Floating Translator")
            }
            Button(onClick = onStopService) {
                Text("Stop Service")
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
            label = { Text("DeepSeek API Key (Optional if set in .env)") },
            placeholder = { Text("sk-...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // Models
        var modelsDropdownExpanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { modelsDropdownExpanded = true }) {
                Text("Model: $selectedModel")
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
            Text(if (modelsLoading) "Loading Models..." else "Update Models from API")
        }

        // Pronoun Theme Dropdown
        val themes = listOf("Neutral", "I/You", "Master/Servant", "Commander/Soldier", "Hero/Villain")
        var themesDropdownExpanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { themesDropdownExpanded = true }) {
                Text("Theme: $pronounTheme")
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

        // Bounding Box
        OutlinedTextField(
            value = boundingBox,
            onValueChange = { 
                boundingBox = it
                coroutineScope.launch { settingsRepo.updateBoundingBox(it) }
            },
            label = { Text("Bounding Box (X,Y,W,H %)") },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Default 0,0,100,100 means full screen capture area.\n" +
            "Set e.g. 10,70,80,20 for bottom 20% area.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
