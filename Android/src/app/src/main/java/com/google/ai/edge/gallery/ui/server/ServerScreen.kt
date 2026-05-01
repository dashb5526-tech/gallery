package com.google.ai.edge.gallery.ui.server

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.content.ClipboardManager
import android.content.ClipData
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.server.LocalApiService
import com.google.ai.edge.gallery.server.PendingImageStore
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class ChatMessage(
    val role: String,
    val content: String,
    val imageBitmap: androidx.compose.ui.graphics.ImageBitmap? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    navigateUp: () -> Unit,
    viewModel: ServerViewModel = hiltViewModel(),
    modelManagerViewModel: ModelManagerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val status by viewModel.serverStatus.collectAsState()
    val ip by viewModel.serverIp.collectAsState()
    val portStr by viewModel.port.collectAsState()
    val apiKey by viewModel.apiKey.collectAsState()
    
    val selectedModelName by viewModel.selectedModelName.collectAsState()
    
    val temperature by viewModel.temperature.collectAsState()
    val topP by viewModel.topP.collectAsState()
    val topK by viewModel.topK.collectAsState()
    val maxTokens by viewModel.maxTokens.collectAsState()
    val accelerator by viewModel.accelerator.collectAsState()

    val uiState by modelManagerViewModel.uiState.collectAsState()
    val downloadedModels = modelManagerViewModel.getAllModels().filter { model ->
        (model.isLlm || model.llmSupportImage || model.runtimeType == com.google.ai.edge.gallery.data.RuntimeType.LITERT_LM) && 
        uiState.modelDownloadStatus[model.name]?.status == com.google.ai.edge.gallery.data.ModelDownloadStatusType.SUCCEEDED
    }

    var showConfigDialog by remember { mutableStateOf(false) }
    var showLiveTest by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    
    // Test Chat State
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }
    var attachedImageUri by remember { mutableStateOf<Uri?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingStatus by remember { mutableStateOf<String?>(null) }
    var testErrorMessage by remember { mutableStateOf<String?>(null) }
    val activeConnection = remember { mutableStateOf<HttpURLConnection?>(null) }
    val focusManager = LocalFocusManager.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        attachedImageUri = uri
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    fun scrollToBottom() {
        scope.launch {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    fun onStopTest() {
        activeConnection.value?.let { conn ->
            scope.launch(Dispatchers.IO) {
                try { conn.disconnect() } catch (e: Exception) {}
            }
        }
        activeConnection.value = null
        isLoading = false
        loadingStatus = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F111A))
    ) {
        // --- Header (Manual Row instead of TopAppBar to avoid crashes) ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = navigateUp) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
            }
            Text(
                text = "Local API Server",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
            IconButton(onClick = { showLiveTest = !showLiveTest }) {
                Icon(if (showLiveTest) Icons.Default.Settings else Icons.Default.PlayArrow, "Toggle", tint = Color.White)
            }
        }

        if (!showLiveTest) {
            // --- Dashboard View ---
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
            ) {
                // --- Status Card ---
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .padding(vertical = 12.dp),
                    color = Color(0xFF1C1E26),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (status == LocalApiService.ServerStatus.RUNNING) {
                                Surface(
                                    modifier = Modifier.size(100.dp),
                                    shape = CircleShape,
                                    color = Color(0xFF4DB6AC).copy(alpha = glowAlpha * 0.15f)
                                ) {}
                                Surface(
                                    modifier = Modifier.size(80.dp),
                                    shape = CircleShape,
                                    color = Color(0xFF4DB6AC).copy(alpha = glowAlpha * 0.3f)
                                ) {}
                            }
                            Icon(
                                imageVector = Icons.Default.CloudQueue,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = if (status == LocalApiService.ServerStatus.RUNNING) Color(0xFF4DB6AC) else Color.Gray.copy(alpha = 0.5f)
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = if (status == LocalApiService.ServerStatus.RUNNING) "RUNNING" else "STOPPED",
                            style = TextStyle(
                                fontSize = 32.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (status == LocalApiService.ServerStatus.RUNNING) Color(0xFF4DB6AC) else Color.Gray,
                                letterSpacing = 2.sp
                            )
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // --- Model Selection ---
                Text("Model", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("Select model for inference", color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                
                var expanded by remember { mutableStateOf(false) }
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = if (selectedModelName.isEmpty()) "Select a Model" else selectedModelName,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = status != LocalApiService.ServerStatus.RUNNING,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF4DB6AC),
                            unfocusedBorderColor = Color(0xFF30363D),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            disabledTextColor = Color.White,
                            disabledBorderColor = Color(0xFF30363D)
                        ),
                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, tint = Color.White) }
                    )
                    if (status != LocalApiService.ServerStatus.RUNNING) {
                        Box(modifier = Modifier.matchParentSize().clickable { expanded = true })
                    }
                }
                
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(Color(0xFF21262D)).fillMaxWidth(0.8f)
                ) {
                    downloadedModels.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.name, color = Color.White) },
                            onClick = {
                                viewModel.selectModel(model.name)
                                expanded = false
                            }
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
                
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF1C1E26),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFF30363D).copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {                        // Accelerator Selection
                        Text("Accelerator", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Using a hardcoded list of common labels to avoid enum iteration issues
                            val accelerators = listOf("CPU", "GPU", "NPU", "TPU")
                            accelerators.forEach { label ->
                                val isSelected = accelerator == label
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(40.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) Color(0xFF4DB6AC) else Color(0xFF1E2130))
                                        .border(if (isSelected) 1.dp else 1.dp, if (isSelected) Color.Transparent else Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                        .clickable(enabled = status != LocalApiService.ServerStatus.RUNNING) { 
                                            viewModel.saveAccelerator(label) 
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(label, color = if (isSelected) Color.Black else Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                        
                        Spacer(Modifier.height(16.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Engine Configuration", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { viewModel.resetToDefaults() }, enabled = status != LocalApiService.ServerStatus.RUNNING) {
                                Text("Reset Defaults", color = Color(0xFF4DB6AC), style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        ParameterRow("Temp", temperature, 0.0f..2.0f) { viewModel.saveTemperature(it) }
                        ParameterRow("TopP", topP, 0.0f..1.0f) { viewModel.saveTopP(it) }
                        ParameterRowInt("TopK", topK, 1..100) { viewModel.saveTopK(it) }
                        ParameterRowInt("MaxTokens", maxTokens, 128..10000) { viewModel.saveMaxTokens(it) }
                    }
                }

                Spacer(Modifier.height(32.dp))

                // --- Action Buttons ---
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { viewModel.startServer() },
                        enabled = status != LocalApiService.ServerStatus.RUNNING,
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFAEC6FF),
                            contentColor = Color(0xFF1A1D2E),
                            disabledContainerColor = Color(0xFF1C1E26)
                        ),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { viewModel.stopServer() },
                        enabled = status == LocalApiService.ServerStatus.RUNNING,
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1C1E26),
                            contentColor = Color.Gray,
                            disabledContainerColor = Color(0xFF1C1E26).copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        Icon(Icons.Default.Stop, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Stop", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(24.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF30363D)))
                Spacer(Modifier.height(24.dp))

                // --- API Settings Section ---
                Text("API Settings", color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Port Field
                    Column(modifier = Modifier.weight(0.3f)) {
                        Text("Port", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = portStr,
                            onValueChange = { if (it.length <= 5 && it.all { c -> c.isDigit() }) viewModel.savePort(it) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = status != LocalApiService.ServerStatus.RUNNING,
                            shape = RoundedCornerShape(12.dp),
                            textStyle = TextStyle(fontSize = 14.sp, color = Color.White),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF4DB6AC),
                                unfocusedBorderColor = Color(0xFF30363D),
                                disabledBorderColor = Color(0xFF21262D)
                            )
                        )
                    }
                    
                    // API Key Field
                    Column(modifier = Modifier.weight(0.7f)) {
                        Text("API Key", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { viewModel.saveApiKey(it) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = status != LocalApiService.ServerStatus.RUNNING,
                            shape = RoundedCornerShape(12.dp),
                            textStyle = TextStyle(fontSize = 14.sp, color = Color.White),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF4DB6AC),
                                unfocusedBorderColor = Color(0xFF30363D),
                                disabledBorderColor = Color(0xFF21262D)
                            ),
                            trailingIcon = {
                                Row {
                                    IconButton(
                                        onClick = { viewModel.generateApiKey() },
                                        enabled = status != LocalApiService.ServerStatus.RUNNING
                                    ) {
                                        Icon(Icons.Default.Refresh, null, tint = if (status == LocalApiService.ServerStatus.RUNNING) Color.DarkGray else Color.Gray, modifier = Modifier.size(20.dp))
                                    }
                                    IconButton(onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("API Key", apiKey))
                                        Toast.makeText(context, "API Key copied", Toast.LENGTH_SHORT).show()
                                    }) {
                                        Icon(Icons.Default.ContentCopy, null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        )
                    }
                }

                if (status == LocalApiService.ServerStatus.RUNNING) {
                    Text(
                        "Stop the server to change Port or API Key",
                        color = Color(0xFFE57373),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                    )
                }

                if (status == LocalApiService.ServerStatus.RUNNING && ip != null) {
                    Spacer(Modifier.height(24.dp))
                    Text("API Credentials", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Use these endpoints in your client applications", color = Color.Gray, fontSize = 12.sp)
                    Spacer(Modifier.height(16.dp))
                    
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF1C1E26))
                            .border(1.dp, Color(0xFF30363D).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        ApiInfoRow("Model ID", if (selectedModelName.isEmpty()) "none" else selectedModelName, context)
                        ApiInfoRow("Base API URL", "http://$ip:$portStr/v1", context)
                        ApiInfoRow("Public URL", "http://$ip:$portStr", context)
                    }
                }
                
                Spacer(Modifier.height(120.dp))
            }
        } else {
            // --- Live Test Chat View ---
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    if (messages.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.ChatBubbleOutline, null, tint = Color.Gray, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("Test your Local API Server", color = Color.Gray)
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(messages) { msg -> ChatBubble(msg) }
                            if (isLoading) {
                                item { ThinkingBubble(loadingStatus ?: "Processing...") }
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1A1D2E))
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(12.dp)
                ) {
                    Column {
                        attachedImageUri?.let { uri ->
                            Box(modifier = Modifier.padding(bottom = 8.dp)) {
                                val bitmap = remember(uri) {
                                    try {
                                        val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
                                        android.graphics.ImageDecoder.decodeBitmap(source)
                                    } catch (e: Exception) { null }
                                }
                                if (bitmap != null) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.size(80.dp).clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                                IconButton(
                                    onClick = { attachedImageUri = null },
                                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp).background(Color.Black.copy(0.7f), CircleShape)
                                ) { Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(12.dp)) }
                            }
                        }

                        Row(verticalAlignment = Alignment.Bottom) {
                            IconButton(
                                onClick = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                modifier = Modifier.background(Color(0xFF30363D), CircleShape).size(44.dp)
                            ) { Icon(Icons.Default.Add, null, tint = Color.White) }
                            
                            Spacer(Modifier.width(8.dp))

                            TextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                placeholder = { Text("Ask something...", color = Color.Gray) },
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(24.dp)),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color(0xFF30363D),
                                    unfocusedContainerColor = Color(0xFF30363D),
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                            
                            Spacer(Modifier.width(8.dp))

                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(if (isLoading) Color(0xFFE57373) else Color(0xFF4DB6AC), CircleShape)
                                    .clickable {
                                        if (isLoading) onStopTest() else {
                                            if (inputText.isNotBlank() || attachedImageUri != null) {
                                                val msgText = inputText; val uri = attachedImageUri
                                                inputText = ""; attachedImageUri = null
                                                isLoading = true
                                                scope.launch {
                                                    sendTestMessage(
                                                        msgText, uri, portStr, apiKey, selectedModelName,
                                                        messages, activeConnection, { focusManager.clearFocus() },
                                                        { scrollToBottom() }, { testErrorMessage = it },
                                                        { loadingStatus = it }, { isLoading = false }, scope
                                                    )
                                                }
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isLoading) {
                                    Box(modifier = Modifier.size(14.dp).background(Color.White, RoundedCornerShape(2.dp)))
                                } else {
                                    Icon(Icons.AutoMirrored.Filled.Send, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ApiInfoRow(label: String, value: String, context: Context) {
    Column {
        Text(label, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
        Spacer(Modifier.height(4.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF1C1E26),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF30363D))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = value,
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText(label, value)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun ChatBubble(msg: ChatMessage) {
    val isUser = msg.role == "user"
    val bubbleColor = if (isUser) Color(0xFF21262D) else Color(0xFF161B22)
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val shape = if (isUser) RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            Surface(
                color = bubbleColor,
                shape = shape,
                border = if (!isUser) BorderStroke(1.dp, Color(0xFF30363D)) else null,
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    if (msg.imageBitmap != null) {
                        Image(
                            bitmap = msg.imageBitmap,
                            contentDescription = null,
                            modifier = Modifier.sizeIn(maxWidth = 240.dp).clip(RoundedCornerShape(12.dp)).padding(bottom = 8.dp),
                            contentScale = ContentScale.FillWidth
                        )
                    }
                    Text(text = msg.content, color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp))
                }
            }
        }
    }
}

@Composable
fun ThinkingBubble(status: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.background(Color(0xFF161B22), RoundedCornerShape(12.dp)).border(1.dp, Color(0xFF30363D), RoundedCornerShape(12.dp)).padding(10.dp)) {
            Text(status, color = Color.Gray, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun ParameterRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit) {
    val clampedValue = value.coerceIn(range.start, range.endInclusive)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
            val displayValue = ((clampedValue * 100).toInt() / 100.0).toString()
            Text(displayValue, color = Color(0xFF4DB6AC), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = clampedValue,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF4DB6AC),
                activeTrackColor = Color(0xFF4DB6AC),
                inactiveTrackColor = Color(0xFF30363D)
            )
        )
    }
}

@Composable
fun ParameterRowInt(label: String, value: Int, range: IntRange, onValueChange: (Int) -> Unit) {
    val clampedValue = value.coerceIn(range.first, range.last)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
            Text(clampedValue.toString(), color = Color(0xFF4DB6AC), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = clampedValue.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF4DB6AC),
                activeTrackColor = Color(0xFF4DB6AC),
                inactiveTrackColor = Color(0xFF30363D)
            )
        )
    }
}

private suspend fun sendTestMessage(
    userText: String, imageUri: Uri?, portStr: String, apiKey: String, modelName: String,
    messages: SnapshotStateList<ChatMessage>, activeConnection: MutableState<HttpURLConnection?>,
    clearFocus: () -> Unit, scrollToBottom: () -> Unit, setErrorMessage: (String?) -> Unit,
    setLoadingStatus: (String?) -> Unit, onFinish: () -> Unit, scope: CoroutineScope
) {
    try {
        setErrorMessage(null)
        setLoadingStatus("Preparing...")
        
        var bitmap: Bitmap? = null
        var imageBytes: ByteArray? = null
        if (imageUri != null) {
            val context = com.google.ai.edge.gallery.MainActivity.instance
            val source = android.graphics.ImageDecoder.createSource(context.contentResolver, imageUri)
            bitmap = android.graphics.ImageDecoder.decodeBitmap(source)
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            imageBytes = stream.toByteArray()
        }

        withContext(Dispatchers.Main) {
            messages.add(ChatMessage("user", userText, bitmap?.asImageBitmap()))
            scrollToBottom()
            clearFocus()
            messages.add(ChatMessage("assistant", ""))
            scrollToBottom()
        }

        val assistantIndex = messages.size - 1
        val history = messages.toList()

        withContext(Dispatchers.IO) {
            setLoadingStatus("Thinking...")
            streamLocalApi(userText, imageBytes, portStr, apiKey, modelName, activeConnection, history) { chunk ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    setLoadingStatus(null)
                    if (assistantIndex < messages.size) {
                        val current = messages[assistantIndex]
                        messages[assistantIndex] = current.copy(content = current.content + chunk)
                        scrollToBottom()
                    }
                }
            }
        }
    } catch (t: Throwable) {
        if (t !is CancellationException) {
            Log.e("ServerScreen", "Test Error", t)
            setErrorMessage("Error: ${t.message}")
        }
    } finally {
        onFinish()
        activeConnection.value = null
    }
}

private suspend fun streamLocalApi(userText: String, imageBytes: ByteArray?, port: String, apiKey: String, modelName: String,
    activeConnection: MutableState<HttpURLConnection?>, chatHistory: List<ChatMessage>, onChunk: (String) -> Unit) {

    if (imageBytes != null) PendingImageStore.set(imageBytes)

    val messagesArray = JSONArray()
    chatHistory.forEach { msg ->
        if (msg.content.isNotBlank()) {
            messagesArray.put(JSONObject().apply { put("role", msg.role); put("content", msg.content) })
        }
    }
    
    val body = JSONObject().apply {
        put("model", modelName); put("messages", messagesArray); put("stream", true)
        if (imageBytes != null) put("has_image", true)
    }

    val conn = URL("http://localhost:$port/v1/chat/completions").openConnection() as HttpURLConnection
    activeConnection.value = conn
    conn.requestMethod = "POST"
    conn.setRequestProperty("Content-Type", "application/json")
    if (apiKey.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
    conn.doOutput = true
    conn.outputStream.use { it.write(body.toString().toByteArray()) }

    if (conn.responseCode == 200) {
        conn.inputStream.bufferedReader().forEachLine { line ->
            if (line.startsWith("data: ")) {
                val data = line.substring(6).trim()
                if (data == "[DONE]") return@forEachLine
                try {
                    val content = JSONObject(data).getJSONArray("choices").getJSONObject(0).getJSONObject("delta").optString("content", "")
                    if (content.isNotEmpty()) onChunk(content)
                } catch (_: Exception) {}
            }
        }
    } else {
        val err = conn.errorStream?.bufferedReader()?.readText() ?: "API Error"
        throw Exception("Status ${conn.responseCode}: $err")
    }
}
