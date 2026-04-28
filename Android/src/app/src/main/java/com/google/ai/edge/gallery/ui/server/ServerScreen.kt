package com.google.ai.edge.gallery.ui.server

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.server.LocalApiService
import com.google.ai.edge.gallery.server.ModelProvider
import com.google.ai.edge.gallery.server.PendingImageStore
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream

import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

import androidx.compose.ui.graphics.ImageBitmap

data class ChatMessage(val role: String, val content: String, val imageBitmap: ImageBitmap? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    navigateUp: () -> Unit,
    modelManagerViewModel: ModelManagerViewModel,
    viewModel: ServerViewModel = hiltViewModel()
) {
    val status by viewModel.serverStatus.collectAsState()
    val ip by viewModel.serverIp.collectAsState()
    val apiKey by viewModel.apiKey.collectAsState()
    val port by viewModel.port.collectAsState()
    val selectedModelName by viewModel.selectedModelName.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val accelerator by viewModel.accelerator.collectAsState()
    val topK by viewModel.topK.collectAsState()
    val topP by viewModel.topP.collectAsState()
    val temperature by viewModel.temperature.collectAsState()

    val uiState by modelManagerViewModel.uiState.collectAsState()
    val llmModels = modelManagerViewModel.allowlistModels.filter { model ->
        model.isLlm && uiState.modelDownloadStatus[model.name]?.status == com.google.ai.edge.gallery.data.ModelDownloadStatusType.SUCCEEDED
    }
    val clipboardManager = LocalClipboardManager.current

    var expanded by remember { mutableStateOf(false) }
    var showChat by remember { mutableStateOf(false) }
    var showConfig by remember { mutableStateOf(false) }

    // Automatically close chat if server stops
    LaunchedEffect(status) {
        if (showChat && status != LocalApiService.ServerStatus.RUNNING && status != LocalApiService.ServerStatus.STARTING) {
            showChat = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Local API Server") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (showChat) showChat = false else navigateUp()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (status == LocalApiService.ServerStatus.STOPPED || status == LocalApiService.ServerStatus.ERROR) {
                        IconButton(onClick = { showConfig = true }) {
                            Icon(Icons.Default.Tune, contentDescription = "Config")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (showChat && status == LocalApiService.ServerStatus.RUNNING) {
            TestChatPanel(port, apiKey, selectedModelName, Modifier.padding(padding).fillMaxSize())
        } else {
            Column(
                modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatusCard(status, ip, port, errorMessage)

                // Model Selection
                SettingsItem(Icons.Rounded.CloudQueue, "Model", "Select model for inference") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { if (status == LocalApiService.ServerStatus.STOPPED) expanded = !expanded },
                            modifier = Modifier.weight(1f)
                        ) {
                            OutlinedTextField(
                                value = llmModels.find { it.name == selectedModelName }?.name ?: "Select a model",
                                onValueChange = {}, readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                enabled = status == LocalApiService.ServerStatus.STOPPED
                            )
                            ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                                if (llmModels.isEmpty()) {
                                    DropdownMenuItem(text = { Text("No models downloaded") }, onClick = { expanded = false })
                                } else {
                                    llmModels.forEach { model ->
                                        DropdownMenuItem(text = { Text(model.name) }, onClick = { viewModel.selectModel(model.name); expanded = false })
                                    }
                                }
                            }
                        }
                        if (selectedModelName.isNotEmpty()) {
                            IconButton(
                                onClick = { clipboardManager.setText(AnnotatedString(selectedModelName)) },
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, "Copy Model ID")
                            }
                        }
                    }
                }

                // Current config summary
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Engine Config", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.weight(1f))
                            if (status == LocalApiService.ServerStatus.STOPPED || status == LocalApiService.ServerStatus.ERROR) {
                                TextButton(onClick = { showConfig = true }) { Text("Edit") }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("Accelerator: $accelerator  •  TopK: $topK  •  TopP: ${"%.2f".format(topP)}  •  Temp: ${"%.2f".format(temperature)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Control Buttons
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val selectedModel = llmModels.find { it.name == selectedModelName }
                            if (selectedModel != null) ModelProvider.registerModel(selectedModel)
                            viewModel.startServer()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = (status == LocalApiService.ServerStatus.STOPPED || status == LocalApiService.ServerStatus.ERROR) && selectedModelName.isNotEmpty()
                    ) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Start") }

                    OutlinedButton(
                        onClick = { viewModel.stopServer() }, modifier = Modifier.weight(1f),
                        enabled = status == LocalApiService.ServerStatus.RUNNING || status == LocalApiService.ServerStatus.STARTING
                    ) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop") }
                }

                if (status == LocalApiService.ServerStatus.RUNNING) {
                    Button(onClick = { showChat = true }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)), shape = RoundedCornerShape(16.dp)
                    ) { Icon(Icons.AutoMirrored.Rounded.Chat, null); Spacer(Modifier.width(8.dp)); Text("Test Chat", fontWeight = FontWeight.Bold) }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                // API Key
                SettingsItem(Icons.Rounded.VpnKey, "API Key", "Bearer token (optional)") {
                    OutlinedTextField(value = apiKey, onValueChange = { viewModel.saveApiKey(it) }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("API Key") }, trailingIcon = {
                            Row {
                                IconButton(onClick = { viewModel.generateApiKey() }) { Icon(Icons.Rounded.Refresh, "Generate") }
                                if (apiKey.isNotEmpty()) IconButton(onClick = { clipboardManager.setText(AnnotatedString(apiKey)) }) { Icon(Icons.Default.ContentCopy, "Copy") }
                            }
                        })
                }

                // Port
                SettingsItem(Icons.Rounded.SettingsEthernet, "Port", "Server listen port") {
                    OutlinedTextField(value = port, onValueChange = { if (it.all { c -> c.isDigit() }) viewModel.savePort(it) },
                        modifier = Modifier.fillMaxWidth(), label = { Text("Port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }

                if (status == LocalApiService.ServerStatus.RUNNING && ip != null) {
                    HorizontalDivider(Modifier.padding(vertical = 16.dp))
                    Text("Connection Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    
                    val baseUrl = "http://$ip:$port/v1"
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            DetailItem("Base URL", baseUrl, clipboardManager)
                            DetailItem("Model ID", selectedModelName, clipboardManager)
                        }
                    }
                }
            }
        }

        // Config Dialog
        if (showConfig) {
            ConfigDialog(
                currentAccelerator = accelerator, currentTopK = topK, currentTopP = topP, currentTemperature = temperature,
                onDismiss = { showConfig = false },
                onSave = { acc, tk, tp, temp ->
                    viewModel.saveAccelerator(acc); viewModel.saveTopK(tk); viewModel.saveTopP(tp); viewModel.saveTemperature(temp)
                    showConfig = false
                }
            )
        }
    }
}

@Composable
fun DetailItem(label: String, value: String, clipboard: androidx.compose.ui.platform.ClipboardManager) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { clipboard.setText(AnnotatedString(value)) }) {
                Icon(Icons.Default.ContentCopy, "Copy", modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun ConfigDialog(currentAccelerator: String, currentTopK: Int, currentTopP: Float, currentTemperature: Float,
    onDismiss: () -> Unit, onSave: (String, Int, Float, Float) -> Unit) {
    var acc by remember { mutableStateOf(currentAccelerator) }
    var tk by remember { mutableFloatStateOf(currentTopK.toFloat()) }
    var tp by remember { mutableFloatStateOf(currentTopP) }
    var temp by remember { mutableFloatStateOf(currentTemperature) }

    AlertDialog(onDismissRequest = onDismiss, title = { Text("Engine Configuration", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Accelerator
                Text("Accelerator", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Accelerator.GPU.label, Accelerator.CPU.label).forEach { label ->
                        FilterChip(selected = acc == label, onClick = { acc = label },
                            label = { Text(label) }, leadingIcon = if (acc == label) {{ Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }} else null)
                    }
                }
                Text("⚠️ GPU may crash in service mode", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))

                // TopK
                Text("TopK: ${tk.toInt()}", style = MaterialTheme.typography.labelLarge)
                Slider(value = tk, onValueChange = { tk = it }, valueRange = 5f..100f, steps = 94)

                // TopP
                Text("TopP: ${"%.2f".format(tp)}", style = MaterialTheme.typography.labelLarge)
                Slider(value = tp, onValueChange = { tp = it }, valueRange = 0f..1f)

                // Temperature
                Text("Temperature: ${"%.2f".format(temp)}", style = MaterialTheme.typography.labelLarge)
                Slider(value = temp, onValueChange = { temp = it }, valueRange = 0f..2f)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(acc, tk.toInt(), tp, temp) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ─── Test Chat Panel ───

@Composable
fun TestChatPanel(port: String, apiKey: String, modelName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var inputText by remember { mutableStateOf("") }
    var loadingStatus by remember { mutableStateOf<String?>(null) }
    var currentJob by remember { mutableStateOf<Job?>(null) }
    var attachedImage by remember { mutableStateOf<Bitmap?>(null) }
    val focusManager = LocalFocusManager.current
    val activeConnection = remember { mutableStateOf<HttpURLConnection?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { u ->
            scope.launch(Dispatchers.IO) {
                try {
                    // First pass: decode only dimensions (no pixels loaded into memory)
                    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }

                    // Calculate inSampleSize to get close to 512px (power of 2 downscale)
                    val maxPx = 512
                    var sampleSize = 1
                    val w = boundsOpts.outWidth; val h = boundsOpts.outHeight
                    if (w > maxPx || h > maxPx) {
                        val halfW = w / 2; val halfH = h / 2
                        while (halfW / sampleSize >= maxPx && halfH / sampleSize >= maxPx) {
                            sampleSize *= 2
                        }
                    }

                    // Second pass: decode with subsampling + RGB_565 (half the memory of ARGB_8888)
                    val decodeOpts = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    val bmp = context.contentResolver.openInputStream(u)?.use {
                        BitmapFactory.decodeStream(it, null, decodeOpts)
                    }

                    // Final scale to exactly 512px max if still larger
                    val finalBmp = if (bmp != null && (bmp.width > maxPx || bmp.height > maxPx)) {
                        val s = maxPx.toFloat() / maxOf(bmp.width, bmp.height)
                        val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
                        if (scaled !== bmp) bmp.recycle()
                        scaled
                    } else bmp

                    withContext(Dispatchers.Main) { attachedImage = finalBmp }
                } catch (_: Exception) {}
            }
        }
    }

    Column(modifier = modifier.imePadding()) {
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            if (messages.isEmpty() && loadingStatus == null) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.AutoMirrored.Rounded.Chat, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                        Spacer(Modifier.height(12.dp))
                        Text("Test your API", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Text("Send a message to test the server", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), textAlign = TextAlign.Center)
                    }
                }
            }
            items(messages) { ChatBubble(it) }
            if (loadingStatus != null) {
                item {
                    Row(Modifier.padding(start = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(loadingStatus!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                }
            }
        }

        // Attached image preview
        if (attachedImage != null) {
            Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(bitmap = attachedImage!!.asImageBitmap(), contentDescription = "Attached",
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(8.dp))
                    Text("Image attached", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { attachedImage = null }) { Icon(Icons.Rounded.Close, "Remove", Modifier.size(20.dp)) }
                }
            }
        }

        Surface(tonalElevation = 3.dp, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                // Image attach button
                IconButton(onClick = { imagePicker.launch("image/*") }, enabled = loadingStatus == null) {
                    Icon(Icons.Rounded.Image, "Attach image", tint = MaterialTheme.colorScheme.primary)
                }
                OutlinedTextField(value = inputText, onValueChange = { inputText = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message…") }, shape = RoundedCornerShape(24.dp), maxLines = 4, enabled = loadingStatus == null,
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default))
                Spacer(Modifier.width(4.dp))
                if (loadingStatus != null) {
                    // Stop button — disconnect the HTTP connection to break the stream
                    FilledIconButton(onClick = {
                        activeConnection.value?.let { try { it.disconnect() } catch (_: Exception) {} }
                        activeConnection.value = null
                        currentJob?.cancel()
                        loadingStatus = null
                    },
                        shape = CircleShape, modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Default.Stop, "Stop", tint = Color.White)
                    }
                } else {
                    // Send button
                    FilledIconButton(onClick = {
                        if (inputText.isNotBlank() || attachedImage != null) {
                            val msg = inputText.trim(); val img = attachedImage; inputText = ""; attachedImage = null
                            focusManager.clearFocus()
                            currentJob = scope.launch {
                                sendTestMessage(msg, img, port, apiKey, modelName, messages, activeConnection, { loadingStatus = it }, { focusManager.clearFocus() }) { 
                                    scope.launch { 
                                        // Scroll to the very end (messages + 1 for loading status)
                                        val target = if (loadingStatus != null) messages.size else messages.size - 1
                                        if (target >= 0) listState.animateScrollToItem(target) 
                                    } 
                                }
                            }
                        }
                    }, enabled = inputText.isNotBlank() || attachedImage != null, shape = CircleShape, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send")
                    }
                }
            }
        }
    }
}

private suspend fun sendTestMessage(userText: String, image: Bitmap?, port: String, apiKey: String, modelName: String,
    messages: MutableList<ChatMessage>, activeConnection: MutableState<HttpURLConnection?>, setLoading: (String?) -> Unit, clearFocus: () -> Unit, scrollToBottom: () -> Unit) {
    
    // 1. Add user message IMMEDIATELY to UI
    withContext(Dispatchers.Main) {
        messages.add(ChatMessage("user", userText.ifBlank { if (image != null) "(Image)" else "" }, imageBitmap = image?.asImageBitmap()))
        scrollToBottom()
    }
    
    // 2. Clear focus/keyboard IMMEDIATELY
    clearFocus()

    var imageBytes: ByteArray? = null
    
    // 3. Image-specific processing (with 2s delay)
    if (image != null) {
        // Wait 2 seconds for images as requested
        delay(2000)
        
        setLoading("Compressing image...")
        scrollToBottom()
        withContext(Dispatchers.Default) {
            val stream = ByteArrayOutputStream()
            image.compress(Bitmap.CompressFormat.JPEG, 80, stream)
            imageBytes = stream.toByteArray()
        }
    }

    // 4. API Generation (Immediate for text, after 2s for images)
    setLoading("Generating response...")
    scrollToBottom()
    // Capture history BEFORE adding the empty assistant placeholder
    val history = messages.toList().dropLast(1) // exclude the user msg we just added
    val assistantIndex = messages.size
    messages.add(ChatMessage("assistant", ""))
    // Throttle UI updates: buffer chunks and flush to main thread periodically
    var lastScrollTime = 0L
    try {
        withContext(Dispatchers.IO) {
            val buffer = StringBuilder()
            streamLocalApi(userText, imageBytes, port, apiKey, modelName, activeConnection, history) { chunk ->
                buffer.append(chunk)
                val now = System.currentTimeMillis()
                // Flush buffer to UI at most every 50ms to avoid per-token main-thread hops
                if (now - lastScrollTime > 50) {
                    lastScrollTime = now
                    val flushed = buffer.toString()
                    buffer.clear()
                    withContext(Dispatchers.Main) {
                        val current = messages[assistantIndex]
                        messages[assistantIndex] = current.copy(content = current.content + flushed)
                        scrollToBottom()
                    }
                }
            }
            // Flush any remaining buffer
            if (buffer.isNotEmpty()) {
                val remaining = buffer.toString()
                withContext(Dispatchers.Main) {
                    val current = messages[assistantIndex]
                    messages[assistantIndex] = current.copy(content = current.content + remaining)
                    scrollToBottom()
                }
            }
        }
        if (messages[assistantIndex].content.isBlank()) {
            messages[assistantIndex] = ChatMessage("assistant", "(No response)")
        }
    } catch (_: kotlinx.coroutines.CancellationException) {
        if (messages[assistantIndex].content.isBlank()) {
            messages[assistantIndex] = ChatMessage("assistant", "(Stopped)")
        }
    } catch (e: Exception) {
        if (messages[assistantIndex].content.isBlank()) {
            messages[assistantIndex] = ChatMessage("error", "Error: ${e.message}")
        }
    }
    activeConnection.value = null
    setLoading(null); scrollToBottom()
}

private suspend fun streamLocalApi(userText: String, imageBytes: ByteArray?, port: String, apiKey: String, modelName: String,
    activeConnection: MutableState<HttpURLConnection?>, chatHistory: List<ChatMessage>, onChunk: suspend (String) -> Unit) {

    // Use pre-compressed bytes
    if (imageBytes != null) {
        PendingImageStore.set(imageBytes)
    }

    val messagesArray = JSONArray()
    // Send prior conversation history (text only)
    for (msg in chatHistory) {
        if (msg.role == "error") continue
        messagesArray.put(JSONObject().apply {
            put("role", msg.role)
            put("content", msg.content)
        })
    }
    // Current user message
    val userMsg = JSONObject().apply {
        put("role", "user")
        put("content", userText.ifBlank { "Describe this image" })
    }
    messagesArray.put(userMsg)
    val body = JSONObject().apply {
        put("model", modelName)
        put("messages", messagesArray)
        put("stream", true)
        if (imageBytes != null) put("has_image", true)
    }

    val conn = (URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Accept", "text/event-stream")
        if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        doOutput = true; connectTimeout = 60000; readTimeout = 120000
    }
    activeConnection.value = conn
    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

    val code = conn.responseCode
    if (code !in 200..299) {
        val errText = BufferedReader(InputStreamReader(conn.errorStream)).use { it.readText() }
        throw Exception("HTTP $code: $errText")
    }

    BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line ?: continue
            if (!l.startsWith("data: ")) continue
            val data = l.removePrefix("data: ").trim()
            if (data == "[DONE]") break
            try {
                val chunk = JSONObject(data)
                val choices = chunk.optJSONArray("choices") ?: continue
                if (choices.length() == 0) continue
                val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue
                val content = delta.optString("content", "")
                if (content.isNotEmpty()) {
                    onChunk(content)
                }
            } catch (_: Exception) {}
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == "user"; val isError = message.role == "error"
    val bubbleColor = when { isUser -> MaterialTheme.colorScheme.primary; isError -> MaterialTheme.colorScheme.errorContainer; else -> MaterialTheme.colorScheme.surfaceVariant }
    val textColor = when { isUser -> MaterialTheme.colorScheme.onPrimary; isError -> MaterialTheme.colorScheme.onErrorContainer; else -> MaterialTheme.colorScheme.onSurfaceVariant }
    val shape = if (isUser) RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)

    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        if (!isUser && !isError) Text("AI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, bottom = 2.dp))
        Surface(color = bubbleColor, shape = shape, modifier = Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (message.imageBitmap != null) {
                    Image(bitmap = message.imageBitmap, contentDescription = "Sent image",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop)
                    if (message.content.isNotBlank()) Spacer(Modifier.height(6.dp))
                }
                if (message.content.isNotBlank()) {
                    Text(message.content, color = textColor, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

// ─── Reusable Components ───

@Composable
fun StatusCard(status: LocalApiService.ServerStatus, ip: String?, port: String, errorMessage: String? = null) {
    val statusColor = when (status) {
        LocalApiService.ServerStatus.RUNNING -> Color(0xFF4CAF50); LocalApiService.ServerStatus.STARTING -> Color(0xFFFFC107)
        LocalApiService.ServerStatus.ERROR -> MaterialTheme.colorScheme.error; LocalApiService.ServerStatus.STOPPED -> MaterialTheme.colorScheme.outline
    }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(80.dp).clip(CircleShape).background(statusColor.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                Icon(when (status) { LocalApiService.ServerStatus.RUNNING -> Icons.Rounded.CloudDone; LocalApiService.ServerStatus.ERROR -> Icons.Rounded.Error; else -> Icons.Rounded.CloudQueue },
                    null, Modifier.size(40.dp), tint = statusColor)
            }
            Spacer(Modifier.height(16.dp))
            Text(status.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = statusColor)
            
            if (status == LocalApiService.ServerStatus.RUNNING && ip != null) {
                val fullUrl = "http://$ip:$port/v1"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { clipboardManager.setText(AnnotatedString(fullUrl)) }
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(fullUrl, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (status == LocalApiService.ServerStatus.ERROR && !errorMessage.isNullOrBlank()) Text(errorMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
fun SettingsItem(icon: ImageVector, title: String, description: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column { Text(title, style = MaterialTheme.typography.titleSmall); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        content()
    }
}
