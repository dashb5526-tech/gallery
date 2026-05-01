# Local AI API Server (OpenAI Compatible) 🚀

The Google AI Edge Gallery includes a powerful, local-first API server that allows external applications to leverage on-device models via an OpenAI-compatible interface. This enables seamless integration with tools like Continue.dev, AutoGPT, or custom scripts, all while keeping your data entirely on-device.

## ✨ Key Features

### 1. Universal Model Fallback
The server is designed for high availability. If a client requests a specific model name that isn't currently loaded, the server automatically defaults to the **active model** selected in the app UI.
- **Priority**: Requested Model → Server-Active Model → First available active model.
- **Benefit**: No need to change your IDE settings every time you switch from Gemma 2b to Gemma 7b in the Gallery app.

### 2. Document-Aware Inference (PDF Support)
Process documents locally without uploading them to any cloud:
- **Automatic Text Extraction**: When a base64-encoded PDF is sent in the `attachments` array, the server extracts the text using `PDFBox-Android`.
- **Context Injection**: Extracted content is prefixed to your prompt, enabling "Chat with PDF" functionality for any external tool.
- **Optimized for Mobile**: Includes a 2MB safety limit per document to ensure device stability.

### 3. Hardened Security
- **48-Character Secure Keys**: Generates high-entropy, cryptographically secure API keys (e.g., `sk-aB1...`).
- **Standard Bearer Auth**: Uses the standard `Authorization: Bearer <key>` header format.
- **Local Network Safety**: Configured to ignore unauthorized requests while remaining accessible to your local network.

### 4. Robust Error Reporting
The server uses **Ktor StatusPages** to provide clear, actionable feedback for integration issues:
- **400 Bad Request**: Returned for malformed JSON or missing required fields, with a detailed JSON body explaining the error.
- **500 Internal Error**: Returned for unexpected server or native engine failures.

---

## 🛠️ Developer Integration

### Endpoint Configuration
- **Base URL**: `http://<device-ip>:8080/v1`
- **Chat Completion**: `POST /v1/chat/completions`
- **Model List**: `GET /v1/models`

### cURL Example
```bash
curl http://192.168.1.50:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer sk-your-48-char-key" \
  -d '{
    "model": "gemma-2b",
    "messages": [{"role": "user", "content": "Explain quantum physics to a cat."}],
    "stream": true
  }'
```

### Python Example (OpenAI SDK)
```python
from openai import OpenAI

client = OpenAI(
    base_url="http://192.168.1.50:8080/v1",
    api_key="sk-your-48-char-key"
)

response = client.chat.completions.create(
    model="any", # Falls back to the active model automatically!
    messages=[{"role": "user", "content": "Hello!"}],
    stream=True
)

for chunk in response:
    print(chunk.choices[0].delta.content or "", end="")
```

---

## 🚀 Getting Started

1. **Enable the Server**: Go to the **Server** tab in the Gallery app.
2. **Setup**: Choose your model and accelerator (CPU/GPU), then click **Start Server**.
3. **Generate Key**: Use the **Generate New Key** button to create a secure key.
4. **Test**: Use the built-in **Test Chat** panel in the app to verify the server is responding correctly before connecting external tools.

---

## ⚠️ Troubleshooting

- **HTTP 400 Errors**: Ensure your JSON matches the OpenAI spec. The Gallery server is now more lenient, but required fields like `messages` must be present.
- **Connection Refused**: Check your device's IP address and ensure your client is on the same Wi-Fi network.
- **Memory Issues**: If the server stops unexpectedly during large document processing, try reducing the PDF size below 2MB.
