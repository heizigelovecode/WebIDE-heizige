package com.web.webide.ui.editor.aicoding

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import androidx.core.content.edit

data class ChatMessage(
    val role: String, // "user", "assistant", "system"
    val content: String,
    val reasoningContent: String? = null,
    val isError: Boolean = false
)

class AICodingViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("ai_coding_settings", Context.MODE_PRIVATE)

    var apiKey by mutableStateOf(prefs.getString("api_key", "") ?: "")
    var baseUrl by mutableStateOf(prefs.getString("base_url", "https://api.openai.com/v1") ?: "https://api.openai.com/v1")
    var model by mutableStateOf(prefs.getString("model", "gpt-3.5-turbo") ?: "gpt-3.5-turbo")
    
    // Provider Logic
    enum class ApiProvider(val displayName: String, val defaultBaseUrl: String, val defaultModel: String) {
        OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4o"),
        DEEPSEEK("DeepSeek", "https://api.deepseek.com", "deepseek-chat"),
        ANTHROPIC("Anthropic", "https://api.anthropic.com/v1", "claude-3-opus-20240229"),
        GOOGLE("Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.0-flash"),
        ZHIPU("Zhipu AI (GLM)", "https://open.bigmodel.cn/api/paas/v4", "glm-4.5"),
        MOONSHOT("Moonshot (Kimi)", "https://api.moonshot.cn/v1", "moonshot-v1-128k"),
        ALIYUN("Aliyun (Qwen)", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-max"),
        BAIDU("Baidu (Ernie)", "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop", "ernie-bot-4"),
        DOUBAO("Doubao", "https://ark.cn-beijing.volces.com/api/v3", "Doubao-pro-4k"),
        MISTRAL("Mistral", "https://codestral.mistral.ai/v1", "codestral-latest"),
        SILICONFLOW("SiliconFlow", "https://api.siliconflow.cn/v1", "yi-1.5-34b"),
        OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "google/gemini-pro"),
        LMSTUDIO("LM Studio (Local)", "http://localhost:1234/v1", "meta-llama-3.1-8b-instruct"),
        CUSTOM("Custom / Other", "", "");

        companion object {
            fun fromUrl(url: String): ApiProvider {
                return entries.find { it.defaultBaseUrl.isNotEmpty() && url.startsWith(it.defaultBaseUrl) } ?: CUSTOM
            }
        }
    }

    var selectedProvider by mutableStateOf(ApiProvider.fromUrl(baseUrl))

    val availableModels = mutableStateListOf<String>()
    var isFetchingModels by mutableStateOf(false)

    val messages = mutableStateListOf<ChatMessage>()
    var isLoading by mutableStateOf(false)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    init {
        // Add initial system message or greeting if empty
        if (messages.isEmpty()) {
            messages.add(ChatMessage("assistant", "Hello! I am your AI coding assistant. Please configure your API Key in settings to start."))
        }
    }

    fun updateSettings(newApiKey: String, newBaseUrl: String, newModel: String) {
        apiKey = newApiKey.trim()
        baseUrl = newBaseUrl.trim().removeSuffix("/")
        model = newModel.trim()
        
        prefs.edit {
            putString("api_key", apiKey)
                .putString("base_url", baseUrl)
                .putString("model", model)
        }
            
        messages.add(ChatMessage("system", "Settings updated."))
    }

    fun fetchModels() {
        if (apiKey.isBlank()) {
             messages.add(ChatMessage("assistant", "Please set your API Key first before fetching models.", isError = true))
             return
        }
        
        isFetchingModels = true
        viewModelScope.launch(Dispatchers.IO) {
            var currentBaseUrl = baseUrl.trim().removeSuffix("/")
            
            // Clean up common URL copy-paste errors
            if (currentBaseUrl.endsWith("/chat/completions")) {
                currentBaseUrl = currentBaseUrl.substringBefore("/chat/completions").removeSuffix("/")
                // Also remove /v1 if it was part of the removed path and we want to test clean first
                // But usually we want to preserve v1 if it was there. 
                // Let's just strip /chat/completions.
            }

            // Define candidate endpoints
            val candidates = mutableListOf<String>()
            // 1. Exact base url + /models
            candidates.add("$currentBaseUrl/models")
            
            // 2. If base url doesn't end in /v1, try adding it
            if (!currentBaseUrl.endsWith("/v1")) {
                candidates.add("$currentBaseUrl/v1/models")
            }

            var success = false
            var usedEndpoint = ""

            for (endpoint in candidates) {
                try {
                    withContext(Dispatchers.Main) {
                        messages.add(ChatMessage("system", "Trying to fetch models from: $endpoint"))
                    }

                    val request = Request.Builder()
                        .url(endpoint)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .get()
                        .build()
                    
                    val response = client.newCall(request).execute()
                    if (response.code == 404) {
                        response.close()
                        continue // Try next candidate
                    }
                    
                    if (!response.isSuccessful) {
                        val errorMsg = response.body?.string() ?: "Unknown error"
                        response.close()
                        throw IOException("Failed to fetch models: ${response.code} - $errorMsg")
                    }
                    
                    val responseBody = response.body!!.string()
                    val json = JSONObject(responseBody)
                    val data = json.optJSONArray("data")
                    
                    val fetchedModels = mutableListOf<String>()
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            val modelObj = data.getJSONObject(i)
                            fetchedModels.add(modelObj.getString("id"))
                        }
                    }
                    
                    withContext(Dispatchers.Main) {
                        if (fetchedModels.isNotEmpty()) {
                            availableModels.clear()
                            availableModels.addAll(fetchedModels)
                            messages.add(ChatMessage("system", "Successfully fetched ${fetchedModels.size} models."))
                            
                            // Update Base URL if we found a better one (e.g. added /v1)
                            if (endpoint.contains("/v1/models") && !baseUrl.endsWith("/v1")) {
                                val newBaseUrl = "$currentBaseUrl/v1"
                                baseUrl = newBaseUrl
                                prefs.edit { putString("base_url", newBaseUrl) }
                                messages.add(ChatMessage("system", "Auto-corrected Base URL to: $newBaseUrl"))
                            }
                        } else {
                             messages.add(ChatMessage("system", "No models found in response."))
                        }
                        isFetchingModels = false
                    }
                    success = true
                    usedEndpoint = endpoint
                    break // Stop after success
                    
                } catch (e: Exception) {
                    // Only log if this is the last attempt or not a 404 (which we handled above)
                    if (endpoint == candidates.last()) {
                        e.printStackTrace()
                        withContext(Dispatchers.Main) {
                             messages.add(ChatMessage("assistant", "Error fetching models: ${e.message}", isError = true))
                             isFetchingModels = false
                        }
                    }
                }
            }
            
            if (!success && !isFetchingModels) {
                 // Already handled in catch block for the last item, but if loop finished without success (e.g. all 404s)
                 withContext(Dispatchers.Main) {
                     if (availableModels.isEmpty()) {
                        messages.add(ChatMessage("assistant", "Could not fetch models. Verified endpoints returned 404.", isError = true))
                        isFetchingModels = false
                     }
                 }
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        if (apiKey.isBlank()) {
            messages.add(ChatMessage("assistant", "Please set your API Key first.", isError = true))
            return
        }

        messages.add(ChatMessage("user", text))
        isLoading = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (responseContent, reasoningContent) = callChatCompletionApi()
                withContext(Dispatchers.Main) {
                    messages.add(ChatMessage("assistant", responseContent, reasoningContent))
                    isLoading = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    messages.add(ChatMessage("assistant", "Error: ${e.message}", isError = true))
                    isLoading = false
                }
            }
        }
    }
    
    fun clearChat() {
        messages.clear()
        messages.add(ChatMessage("assistant", "Chat history cleared."))
    }

    private fun callChatCompletionApi(): Pair<String, String?> {
        val jsonBody = JSONObject()
        jsonBody.put("model", model)
        
        val messagesArray = JSONArray()
        // Add system prompt
        val systemMessage = JSONObject()
        systemMessage.put("role", "system")
        systemMessage.put("content", "You are a helpful expert coding assistant inside an Android IDE. Be concise and provide code snippets when asked.")
        messagesArray.put(systemMessage)

        // Add history (limit to last 10 messages to save tokens/context)
        val historyToInclude = messages.takeLast(10).filter { !it.isError && it.role != "system" }
        for (msg in historyToInclude) {
            val msgObj = JSONObject()
            msgObj.put("role", msg.role)
            msgObj.put("content", msg.content)
            messagesArray.put(msgObj)
        }
        
        jsonBody.put("messages", messagesArray)

        val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        
        // Handle custom base URLs properly with smart retry
        var currentBaseUrl = baseUrl.trim().removeSuffix("/")
        if (currentBaseUrl.endsWith("/chat/completions")) {
            currentBaseUrl = currentBaseUrl.substringBefore("/chat/completions").removeSuffix("/")
        }

        val candidates = mutableListOf<String>()
        candidates.add("$currentBaseUrl/chat/completions")
        if (!currentBaseUrl.endsWith("/v1")) {
            candidates.add("$currentBaseUrl/v1/chat/completions")
        }

        var lastError: Exception? = null
        
        for (endpoint in candidates) {
            try {
                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                
                if (response.code == 404) {
                    response.close()
                    continue // Try next candidate
                }

                response.use { 
                    if (!it.isSuccessful) {
                        val errorBody = it.body?.string()
                        throw IOException("API Call failed: ${it.code} - $errorBody")
                    }

                    val responseBody = it.body!!.string()
                    val jsonResponse = JSONObject(responseBody)
                    
                    val choices = jsonResponse.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val firstChoice = choices.getJSONObject(0)
                        val message = firstChoice.optJSONObject("message")
                        
                        // If we successfully used the fallback URL, update the base URL setting
                        if (endpoint.contains("/v1/chat/completions") && !baseUrl.endsWith("/v1")) {
                            val newBaseUrl = "$currentBaseUrl/v1"
                            // We can't easily update State from here since this is background thread without Main context for Compose State
                            // But we can update SharedPreferences
                            prefs.edit { putString("base_url", newBaseUrl) }
                            // We will update the State variable on Main thread later or next time
                        }
                        
                        val content = message?.optString("content") ?: ""
                        // Try to get reasoning content (common in DeepSeek R1 etc.)
                        val reasoning = message?.optString("reasoning_content")
                        
                        return Pair(content, if (reasoning.isNullOrBlank()) null else reasoning)
                    }
                    return Pair("No response content.", null)
                }
            } catch (e: Exception) {
                lastError = e
                if (endpoint == candidates.last()) {
                    throw e
                }
            }
        }
        
        throw lastError ?: IOException("Failed to connect to API")
    }
}
