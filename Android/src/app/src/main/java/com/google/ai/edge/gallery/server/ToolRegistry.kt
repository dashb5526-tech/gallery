package com.google.ai.edge.gallery.server

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.serialization.json.*

interface Tool {
    val name: String
    val description: String
    val parameters: JsonElement
    fun execute(context: Context, arguments: Map<String, JsonElement>): String
}

class ToolRegistry(private val context: Context) {
    private val tools = mutableMapOf<String, Tool>()

    init {
        registerTool(OpenBrowserTool())
        registerTool(WebSearchTool())
    }

    private fun registerTool(tool: Tool) {
        tools[tool.name] = tool
    }

    fun getToolDefinitions(): List<ToolDefinition> {
        return tools.values.map { ToolDefinition(it.name, it.description, it.parameters) }
    }

    fun executeTool(name: String, arguments: String): String {
        val tool = tools[name] ?: return "Error: Tool '$name' not found."
        return try {
            val argsMap = Json.parseToJsonElement(arguments).jsonObject
            tool.execute(context, argsMap)
        } catch (e: Exception) {
            "Error executing tool '$name': ${e.message}"
        }
    }
}

data class ToolDefinition(val name: String, val description: String, val parameters: JsonElement)

class OpenBrowserTool : Tool {
    override val name = "open_browser"
    override val description = "Opens a specific URL in the Android web browser."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("url") {
                put("type", "string")
                put("description", "The full URL to open (e.g., https://google.com)")
            }
        }
        putJsonArray("required") { add("url") }
    }

    override fun execute(context: Context, arguments: Map<String, JsonElement>): String {
        val url = arguments["url"]?.jsonPrimitive?.content ?: return "Error: Missing URL"
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Successfully opened browser to $url"
        } catch (e: Exception) {
            "Failed to open browser: ${e.message}"
        }
    }
}

class WebSearchTool : Tool {
    override val name = "web_search"
    override val description = "Searches the web for information using a search engine."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") {
                put("type", "string")
                put("description", "The search query.")
            }
        }
        putJsonArray("required") { add("query") }
    }

    override fun execute(context: Context, arguments: Map<String, JsonElement>): String {
        val query = arguments["query"]?.jsonPrimitive?.content ?: return "Error: Missing query"
        // In a real app, you'd use a Search API here. 
        // For now, we'll return a simulated response or a link.
        Log.d("WebSearchTool", "Searching for: $query")
        return "Search results for '$query': [Simulated Search Result] Information about '$query' found on the web. (In a production app, this would return real-time web content)."
    }
}
