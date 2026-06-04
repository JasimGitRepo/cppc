package com.example.core.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.core.compiler.CompilationEngine
import com.example.core.compiler.ToolchainExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object AgenticCoordinator {
    private const val TAG = "AgenticCoordinator"
    private const val ARCHIVE_DIR_NAME = ".hidden_archive"

    // AI Core Live States
    val logs = MutableStateFlow<List<String>>(emptyList())
    val isRunning = MutableStateFlow(false)
    val statusText = MutableStateFlow("IDLE") // IDLE, THINKING, AWAITING_PERMISSION, COMPILING, COMPLETED, FAILED
    
    // Safety Switch
    val autoApplyWithoutPermission = MutableStateFlow(false)
    
    // Persistent Preferences
    val apiProvider = MutableStateFlow("google") // google vs openai vs local
    val activeModel = MutableStateFlow("gemini-3.5-flash") // Default task model
    val customOpenAiUrl = MutableStateFlow("https://api.openai.com/v1/")
    val localApiUrl = MutableStateFlow("http://10.132.142.237:8080/v1/")
    
    // Multiple API Keys storage
    val apiKeysList = MutableStateFlow<List<ApiKeyEntry>>(emptyList())
    val activeApiKeyIndices = MutableStateFlow<Set<Int>>(emptySet())
    
    // Room DB
    private var database: com.example.core.storage.AppDatabase? = null
    val currentSessionId = MutableStateFlow<Long?>(null)
    val sessions = MutableStateFlow<List<com.example.core.storage.ChatSession>>(emptyList())
    val pendingPermissionRequest = MutableStateFlow<PermissionRequest?>(null)
    var onErrorNotification: ((String) -> Unit)? = null

    // Cosmic Chat Extended state variables
    data class ChatMessage(
        val id: String,
        val sender: String, // "USER" or "AI"
        val content: String,
        val isSelected: Boolean = false,
        val attachedFiles: List<String> = emptyList(),
        val timestamp: Long = System.currentTimeMillis()
    )

    val chatHistory = MutableStateFlow<List<ChatMessage>>(emptyList())
    val isChatRunning = MutableStateFlow(false)
    val attachedFiles = MutableStateFlow<Set<String>>(emptySet())
    val selectAllChatContext = MutableStateFlow(false)
    val attachTerminalLogs = MutableStateFlow(false)
    val terminalOutput = MutableStateFlow("")

    // Current loop counter to avoid infinite spins
    private var debugIterationCount = 0

    data class ApiKeyEntry(val alias: String, val keyValue: String)

    data class PermissionRequest(
        val actionType: String, // WRITE, DELETE
        val filePath: String,
        val content: String,
        val onDecision: (Boolean) -> Unit
    )

    fun init(context: Context) {
        database = com.example.core.storage.AppDatabase.getDatabase(context)
        val prefs = context.getSharedPreferences("agentic_prefs", Context.MODE_PRIVATE)
        apiProvider.value = prefs.getString("provider", "google") ?: "google"
        activeModel.value = prefs.getString("model", "gemini-3.5-flash") ?: "gemini-3.5-flash"
        customOpenAiUrl.value = prefs.getString("custom_url", "https://api.openai.com/v1/") ?: "https://api.openai.com/v1/"
        localApiUrl.value = prefs.getString("local_api_url", "http://10.132.142.237:8080/v1/") ?: "http://10.132.142.237:8080/v1/"
        autoApplyWithoutPermission.value = prefs.getBoolean("auto_apply", false)
        
        val keysJson = prefs.getString("api_keys", "[]") ?: "[]"
        try {
            val arr = JSONArray(keysJson)
            val list = mutableListOf<ApiKeyEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(ApiKeyEntry(obj.getString("alias"), obj.getString("keyValue")))
            }
            apiKeysList.value = list
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing saved api keys", e)
        }
        
        val indicesJson = prefs.getString("active_key_indices", "[]") ?: "[]"
        try {
            val arr = JSONArray(indicesJson)
            val set = mutableSetOf<Int>()
            for (i in 0 until arr.length()) {
                set.add(arr.getInt(i))
            }
            activeApiKeyIndices.value = set
        } catch (e: Exception) {}

        // Load initial session
        CoroutineScope(Dispatchers.IO).launch {
            loadSessions()
        }
    }

    suspend fun loadSessions() {
        val list = database?.chatDao()?.getAllSessions() ?: emptyList()
        sessions.value = list
        if (currentSessionId.value == null && list.isNotEmpty()) {
            loadSession(list.first().id)
        } else if (list.isEmpty()) {
            createNewSession("Default Session")
        }
    }

    suspend fun createNewSession(title: String) {
        val id = database?.chatDao()?.insertSession(com.example.core.storage.ChatSession(title = title))
        id?.let {
            currentSessionId.value = it
            loadSessions()
            chatHistory.value = emptyList()
        }
    }

    suspend fun loadSession(id: Long) {
        currentSessionId.value = id
        val messages = database?.chatDao()?.getMessagesForSession(id) ?: emptyList()
        chatHistory.value = messages.map {
            ChatMessage(
                id = it.id.toString(),
                sender = it.sender,
                content = it.content,
                timestamp = it.timestamp
            )
        }
    }

    fun save(context: Context) {
        val prefs = context.getSharedPreferences("agentic_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("provider", apiProvider.value)
            putString("model", activeModel.value)
            putString("custom_url", customOpenAiUrl.value)
            putString("local_api_url", localApiUrl.value)
            putBoolean("auto_apply", autoApplyWithoutPermission.value)
            
            val arr = JSONArray()
            apiKeysList.value.forEach {
                val obj = JSONObject()
                obj.put("alias", it.alias)
                obj.put("keyValue", it.keyValue)
                arr.put(obj)
            }
            putString("api_keys", arr.toString())
            
            val indicesArr = JSONArray()
            activeApiKeyIndices.value.forEach { indicesArr.put(it) }
            putString("active_key_indices", indicesArr.toString())
            apply()
        }
    }

    fun appendLog(msg: String) {
        val current = logs.value.toMutableList()
        current.add(msg)
        logs.value = current
    }

    fun clearLogs() {
        logs.value = emptyList()
    }

    private fun startForegroundService(context: Context, text: String) {
        val intent = Intent(context, AgenticForegroundService::class.java).apply {
            putExtra("STATUS", text)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch service", e)
        }
    }

    private fun updateForegroundService(context: Context, text: String) {
        val intent = Intent(context, AgenticForegroundService::class.java).apply {
            putExtra("STATUS", text)
        }
        context.startService(intent)
    }

    fun stopForegroundService(context: Context) {
        context.stopService(Intent(context, AgenticForegroundService::class.java))
    }

    /**
     * Start the advanced agent loop in a separate coroutine
     */
    fun launchAgent(
        context: Context,
        scope: CoroutineScope,
        userPrompt: String,
        compiler: CompilationEngine,
        extractor: ToolchainExtractor,
        onFinished: () -> Unit
    ) {
        if (isRunning.value) return
        isRunning.value = true
        debugIterationCount = 0
        clearLogs()
        statusText.value = "THINKING"
        appendLog("[SYSTEM] Initiating agentic optimizer workflow task...")
        
        startForegroundService(context, "AI Agent: Initializing...")

        val activeIndices = activeApiKeyIndices.value
        val keys = apiKeysList.value
        if (activeIndices.isEmpty()) {
            appendLog("[ERROR] No active API Key selected! Please register/activate an API key in Settings first.")
            statusText.value = "FAILED"
            isRunning.value = false
            stopForegroundService(context)
            onFinished()
            return
        }

        scope.launch(Dispatchers.IO) {
            var latestCompilerOutput = ""
            var isCompilingSuccess = false
            var isAgentTargetFulfilled = false

            while (isRunning.value && debugIterationCount < 5 && !isAgentTargetFulfilled) {
                debugIterationCount++
                appendLog("[AGENT TICK] Think iteration #$debugIterationCount/5")
                statusText.value = "THINKING"
                updateForegroundService(context, "AI Task: Iteration #$debugIterationCount...")

                // Gather workspace files content (excluding .hidden_archive or dotfiles)
                val filesContent = gatherWorkspaceFiles(extractor.workspaceDir)
                
                // Formulate system and context prompts
                val systemInstruction = "You are an on-device expert coding assistant. Return raw JSON instruction indicating workspace file edits or compiling requirements."
                val userPayload = buildPayloadPrompt(userPrompt, filesContent, latestCompilerOutput, isCompilingSuccess)

                appendLog("[API CALL] Dispatching inquiry context payload to AI models...")
                
                var rawResponse = ""
                var lastError = ""
                
                // Retry logic with multiple keys
                for (idx in activeIndices) {
                    if (idx < 0 || idx >= keys.size) continue
                    val apiKey = keys[idx].keyValue
                    try {
                        rawResponse = if (apiProvider.value == "google") {
                            callGeminiApi(activeModel.value, apiKey, userPayload)
                        } else if (apiProvider.value == "local") {
                            callOpenAiApi(activeModel.value, apiKey, localApiUrl.value, systemInstruction, userPayload)
                        } else {
                            callOpenAiApi(activeModel.value, apiKey, customOpenAiUrl.value, systemInstruction, userPayload)
                        }
                        if (rawResponse.isNotEmpty()) break
                    } catch (e: Exception) {
                        lastError = e.localizedMessage ?: "Unknown error"
                        appendLog("[API RETRY] Key '${keys[idx].alias}' failed: $lastError. Trying next active key...")
                    }
                }

                if (rawResponse.trim().isEmpty()) {
                    appendLog("[API ERROR] All active API keys failed or returned empty. Last error: $lastError")
                    break
                }

                // Analyze response JSON instructions
                val parsedJson = parseAgentInstruction(rawResponse)
                if (parsedJson == null) {
                    appendLog("[PARSING ERROR] Failed to parse response into instructions JSON.")
                    appendLog("Raw output captured: $rawResponse")
                    break
                }

                val explanation = parsedJson.optString("explanation", "Implementing code updates...")
                appendLog("[AI EXPLANATION]: $explanation")

                val filesToWrite = parsedJson.optJSONArray("files_to_write")
                val filesToDelete = parsedJson.optJSONArray("files_to_delete")
                val triggerCompile = parsedJson.optBoolean("trigger_compile", false)
                val isFinished = parsedJson.optBoolean("finished", false)

                // 1. Process deletions
                if (filesToDelete != null && filesToDelete.length() > 0) {
                    for (i in 0 until filesToDelete.length()) {
                        val relPath = filesToDelete.getString(i)
                        val targetFile = File(extractor.workspaceDir, relPath)
                        if (targetFile.exists()) {
                            val deletionApproved = withContext(Dispatchers.Main) {
                                awaitPermission("DELETE", relPath, "")
                            }
                            if (deletionApproved) {
                                if (autoApplyWithoutPermission.value) {
                                    archiveFileForChanges(context, extractor.workspaceDir, relPath)
                                }
                                targetFile.delete()
                                appendLog("[FILE DELETED] Unlinked workspace file: $relPath")
                            } else {
                                appendLog("[PERMISSION DENIED] Skipping deletion of $relPath.")
                            }
                        }
                    }
                }

                // 2. Process file modifications
                if (filesToWrite != null && filesToWrite.length() > 0) {
                    for (i in 0 until filesToWrite.length()) {
                        val item = filesToWrite.getJSONObject(i)
                        val relPath = item.getString("path")
                        val content = item.getString("content")
                        
                        val writeApproved = withContext(Dispatchers.Main) {
                            awaitPermission("WRITE", relPath, content)
                        }

                        if (writeApproved) {
                            val targetFile = File(extractor.workspaceDir, relPath)
                            targetFile.parentFile?.mkdirs()
                            
                            // Safe silent backup before over-writing
                            if (autoApplyWithoutPermission.value && targetFile.exists()) {
                                archiveFileForChanges(context, extractor.workspaceDir, relPath)
                            }
                            
                            targetFile.writeText(content)
                            appendLog("[FILE COMMITTED] Successfully updated file $relPath.")
                        } else {
                            appendLog("[PERMISSION DENIED] Aborted update of $relPath.")
                        }
                    }
                }

                // 3. Trigger compilation if requested or file writes happened
                if (triggerCompile) {
                    appendLog("[COMPILER] Invoking on-device C++ build...")
                    statusText.value = "COMPILING"
                    updateForegroundService(context, "AI Task: Compiling code...")
                    
                    val mainCpp = File(extractor.workspaceDir, "main.cpp")
                    if (mainCpp.exists()) {
                        val buildResult = compiler.compileCpp(mainCpp)
                        when (buildResult) {
                            is CompilationEngine.BuildStatus.Success -> {
                                isCompilingSuccess = true
                                latestCompilerOutput = "Success"
                                val cleanName = mainCpp.nameWithoutExtension.replace("[^a-zA-Z0-9]".toRegex(), "")
                                appendLog("[BUILD SUCCESS] Local compiling finished on-device! lib${cleanName}.so built successfully.")
                            }
                            is CompilationEngine.BuildStatus.Error -> {
                                isCompilingSuccess = false
                                latestCompilerOutput = buildResult.logs
                                appendLog("[BUILD FAILED] compilation logs feedback:\n$latestCompilerOutput")
                            }
                            else -> {}
                        }
                    } else {
                        isCompilingSuccess = false
                        latestCompilerOutput = "Error: main.cpp was not found in the workspace."
                        appendLog("[COMPILER ERROR] missing main.cpp entry-point.")
                    }
                }

                if (isFinished) {
                    isAgentTargetFulfilled = true
                }
            }

            if (isAgentTargetFulfilled) {
                statusText.value = "COMPLETED"
                appendLog("[SYSTEM] Agent task successfully completed under constraint boundaries.")
                updateForegroundService(context, "AI Task: Completed successfully!")
            } else {
                statusText.value = "FAILED"
                appendLog("[SYSTEM] Agent iteration capped or stopped without success target.")
                updateForegroundService(context, "AI Task: Process ended.")
            }

            isRunning.value = false
            withContext(Dispatchers.Main) {
                onFinished()
            }
        }
    }

    private suspend fun awaitPermission(actionType: String, path: String, content: String): Boolean {
        if (autoApplyWithoutPermission.value) return true
        
        statusText.value = "AWAITING_PERMISSION"
        return withContext(Dispatchers.Default) {
            var answer: Boolean? = null
            pendingPermissionRequest.value = PermissionRequest(actionType, path, content) { decision ->
                answer = decision
            }
            while (answer == null) {
                kotlinx.coroutines.delay(200)
            }
            pendingPermissionRequest.value = null
            answer!!
        }
    }

    private fun archiveFileForChanges(context: Context, workspaceDir: File, relativePath: String) {
        try {
            val originalFile = File(workspaceDir, relativePath)
            if (!originalFile.exists()) return

            val archiveBase = File(workspaceDir, ARCHIVE_DIR_NAME)
            if (!archiveBase.exists()) {
                archiveBase.mkdirs()
            }

            val timestamp = System.currentTimeMillis()
            val backupName = "${originalFile.name.substringBeforeLast(".")}_${timestamp}.${originalFile.extension}"
            val backupFile = File(archiveBase, backupName)

            originalFile.copyTo(backupFile, overwrite = true)
            appendLog("[System Archive] Backed up '$relativePath' -> '$ARCHIVE_DIR_NAME/$backupName' to hidden files directory.")
        } catch (e: Exception) {
            Log.e(TAG, "Error archiving file", e)
            appendLog("[System Archive Warning] Failed to backup file '$relativePath': ${e.localizedMessage}")
        }
    }

    private fun gatherWorkspaceFiles(workspaceDir: File): String {
        val s = StringBuilder()
        if (!workspaceDir.exists()) return ""
        
        workspaceDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                val relPath = file.relativeTo(workspaceDir).path
                // Skip hidden files (.hidden_archive) from files listing sent to AI
                if (!relPath.startsWith(".") && !relPath.contains("/.")) {
                    s.append("FILERelativePath: ").append(relPath).append("\n")
                    s.append("```\n").append(file.readText()).append("\n```\n\n")
                }
            }
        }
        return s.toString()
    }

    private fun buildPayloadPrompt(
        userPrompt: String,
        workspaceFiles: String,
        compilerLogs: String,
        success: Boolean
    ): String {
        return """
            You are an expert compiler engineering agent resolving task prompt: "$userPrompt"
            
            Current workspace structures available:
            $workspaceFiles
            
            ${if (compilerLogs.isNotEmpty()) "Previous Compile Success: $success \nCompiler logs feedback:\n$compilerLogs" else ""}
            
            Analyze the files and specify modifications. You must return a strict JSON payload in your response. No prefix, no markdown blocks code annotations. Just raw valid JSON string.
            
            Expected JSON structure:
            {
              "explanation": "Summarize what you are optimizing",
              "files_to_write": [
                 { "path": "relative/path/to/file.cpp", "content": "Full source content" }
              ],
              "files_to_delete": [],
              "trigger_compile": true,
              "finished": false
            }
        """.trimIndent()
    }

    private fun parseAgentInstruction(raw: String): JSONObject? {
        val clean = cleanJson(raw)
        return try {
            JSONObject(clean)
        } catch (e: Exception) {
            Log.e(TAG, "JSON parsing aborted: ${e.localizedMessage}")
            null
        }
    }

    private fun cleanJson(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```json")) {
            s = s.substring(7)
        } else if (s.startsWith("```")) {
            s = s.substring(3)
        }
        if (s.endsWith("```")) {
            s = s.substring(0, s.length - 3)
        }
        return s.trim()
    }

    private fun callGeminiApi(model: String, apiKey: String, prompt: String): String {
        val endpointUrl = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val requestBody = JSONObject()
        val contentsArray = JSONArray()
        val contentObject = JSONObject()
        val partsArray = JSONArray()
        val partObject = JSONObject()

        partObject.put("text", prompt)
        partsArray.put(partObject)
        contentObject.put("parts", partsArray)
        contentsArray.put(contentObject)
        requestBody.put("contents", contentsArray)

        return executePostRequest(endpointUrl, requestBody.toString())
    }

    private fun callOpenAiApi(model: String, apiKey: String, baseUrl: String, sys: String, user: String): String {
        var apiBase = baseUrl.trim()
        if (!apiBase.endsWith("/")) apiBase += "/"
        val endpointUrl = if (apiBase.contains("/chat/completions")) apiBase else "${apiBase}chat/completions"

        val requestBody = JSONObject()
        requestBody.put("model", model)
        val messages = JSONArray()

        val systemMsg = JSONObject()
        systemMsg.put("role", "system")
        systemMsg.put("content", sys)
        messages.put(systemMsg)

        val userMsg = JSONObject()
        userMsg.put("role", "user")
        userMsg.put("content", user)
        messages.put(userMsg)

        requestBody.put("messages", messages)

        return executePostRequest(endpointUrl, requestBody.toString(), apiKey)
    }

    private fun executePostRequest(address: String, payload: String, bearerKey: String? = null): String {
        val url = URL(address)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 60000
        conn.readTimeout = 60000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        if (bearerKey != null) {
            conn.setRequestProperty("Authorization", "Bearer $bearerKey")
        }

        OutputStreamWriter(conn.outputStream).use { writer ->
            writer.write(payload)
            writer.flush()
        }

        val code = conn.responseCode
        if (code in 200..299) {
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val sb = java.lang.StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            
            // Extract text field if Gemini
            val raw = sb.toString()
            if (address.contains("generativelanguage")) {
                try {
                    val root = JSONObject(raw)
                    val candidates = root.getJSONArray("candidates")
                    val candidate = candidates.getJSONObject(0)
                    val content = candidate.getJSONObject("content")
                    val parts = content.getJSONArray("parts")
                    return parts.getJSONObject(0).getString("text")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed extracting text from Gemini response structure: ${e.localizedMessage}")
                    return raw
                }
            } else {
                // Extract description if OpenAI
                try {
                    val root = JSONObject(raw)
                    val choices = root.getJSONArray("choices")
                    val choice = choices.getJSONObject(0)
                    val msg = choice.getJSONObject("message")
                    return msg.getString("content")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed extracting text from OpenAI response structure: ${e.localizedMessage}")
                    return raw
                }
            }
        } else {
            val errorStream = conn.errorStream
            val errorMsg = if (errorStream != null) {
                BufferedReader(InputStreamReader(errorStream)).readText()
            } else {
                "HTTP Response Code: $code"
            }
            throw Exception("HTTP Error: $code. Info: $errorMsg")
        }
    }

    fun sendChatMessage(
        context: Context,
        scope: CoroutineScope,
        prompt: String,
        workspaceDir: File,
        onFinished: () -> Unit
    ) {
        if (isChatRunning.value) return
        isChatRunning.value = true

        val activeIndices = activeApiKeyIndices.value
        val keys = apiKeysList.value
        if (activeIndices.isEmpty()) {
            isChatRunning.value = false
            onFinished()
            return
        }

        val attachedFileList = attachedFiles.value.toList()
        
        val userMsg = ChatMessage(
            id = java.util.UUID.randomUUID().toString(),
            sender = "USER",
            content = prompt,
            attachedFiles = attachedFileList
        )
        chatHistory.value = chatHistory.value + userMsg
        attachedFiles.value = emptySet()

        scope.launch(Dispatchers.IO) {
            // Save user msg to Room
            currentSessionId.value?.let { sid ->
                database?.chatDao()?.insertMessage(com.example.core.storage.ChatMessageEntity(
                    sessionId = sid,
                    sender = "USER",
                    content = prompt
                ))
            }

            try {
                val payloadBuilder = java.lang.StringBuilder()
                payloadBuilder.append("Reply precisely and concisely without fluffy preambles. Structure: Intro -> Bullets (if needed) -> Code (if needed) -> Outro.\n\n")
                
                if (attachedFileList.isNotEmpty()) {
                    payloadBuilder.append("--- WORKSPACE CONTEXT ---\n")
                    attachedFileList.forEach { relativePath ->
                        val file = File(workspaceDir, relativePath)
                        if (file.exists() && file.isFile) {
                            val ext = file.extension.lowercase()
                            val isText = listOf("cpp", "h", "c", "hpp", "txt", "md", "json", "xml", "kt", "java", "sh", "py", "js", "html", "css", "gradle", "kts").contains(ext)
                            
                            // User requested specifically to "rename" .py etc to .txt for the model if needed. 
                            // We do this logically in the prompt payload.
                            val displayPath = if (ext == "py" || (isText && ext != "txt")) {
                                relativePath.substringBeforeLast(".") + ".txt"
                            } else {
                                relativePath
                            }

                            payloadBuilder.append("File Path: $displayPath")
                            if (!isText && !listOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "zip").contains(ext)) {
                                payloadBuilder.append(" (Sent as text fallback)\n")
                            } else {
                                payloadBuilder.append("\n")
                            }

                            if (isText || (!listOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "zip").contains(ext))) {
                                try {
                                    payloadBuilder.append("```\n")
                                    payloadBuilder.append(file.readText())
                                    payloadBuilder.append("\n```\n\n")
                                } catch (e: Exception) {
                                    payloadBuilder.append("[ERROR READING FILE AS TEXT: ${e.localizedMessage}]\n\n")
                                }
                            } else if (listOf("png", "jpg", "jpeg", "gif", "webp", "bmp").contains(ext)) {
                                payloadBuilder.append("[BINARY_IMAGE_FILE: Metadata only. Filename: ${file.name}, Size: ${file.length()} bytes]\n\n")
                            } else if (ext == "zip") {
                                payloadBuilder.append("[BINARY_ZIP_ARCHIVE: Filename: ${file.name}, Size: ${file.length()} bytes]\n\n")
                            } else {
                                payloadBuilder.append("[BINARY_FILE: Filename: ${file.name}, Type: $ext, Size: ${file.length()} bytes]\n\n")
                            }
                        }
                    }
                }

                val hist = chatHistory.value
                val selectedMsgs = hist.filter { it.isSelected || selectAllChatContext.value }
                if (selectedMsgs.isNotEmpty()) {
                    payloadBuilder.append("--- ATTACHED CHAT LOGS/HISTORY CONTEXT ---\n")
                    selectedMsgs.forEach { msg ->
                        payloadBuilder.append("${msg.sender}: ${msg.content}\n\n")
                    }
                    payloadBuilder.append("------------------------------------------\n\n")
                }

                if (attachTerminalLogs.value && terminalOutput.value.isNotEmpty()) {
                    payloadBuilder.append("--- TERMINAL & BUILD LOGS CONTEXT ---\n")
                    payloadBuilder.append(terminalOutput.value)
                    payloadBuilder.append("\n-------------------------------------\n\n")
                }

                payloadBuilder.append("User instruction: $prompt\n\n")
                payloadBuilder.append("Response requirement: Answer direct and naturally. If suggesting a C++ source code solution, write the code inside standard markdown codeblocks (using markdown triple backticks) with a comment tagging its recommended target path, e.g. \"// TARGET_FILE: src/main.cpp\" on the first or second line inside the codeblock, so that the IDE can detect it and let the user paste/append it directly to the workspace.\n")

                val finalPayload = payloadBuilder.toString()
                
                var rawResponse = ""
                var lastError = ""

                for (idx in activeIndices) {
                    if (idx < 0 || idx >= keys.size) continue
                    val apiKey = keys[idx].keyValue
                    try {
                        rawResponse = if (apiProvider.value == "google") {
                            callGeminiApi(activeModel.value, apiKey, finalPayload)
                        } else if (apiProvider.value == "local") {
                            callOpenAiApi(activeModel.value, apiKey, localApiUrl.value, "You are a helpful programming companion.", finalPayload)
                        } else {
                            callOpenAiApi(activeModel.value, apiKey, customOpenAiUrl.value, "You are a helpful programming companion.", finalPayload)
                        }
                        if (rawResponse.isNotEmpty()) break
                    } catch (e: Exception) {
                        lastError = e.localizedMessage ?: "Unknown error"
                        Log.w(TAG, "Chat Key retry with index $idx failed: $lastError")
                    }
                }

                if (rawResponse.isEmpty()) {
                    rawResponse = "[ERROR] All active API keys failed. Last error: $lastError"
                }

                withContext(Dispatchers.Main) {
                    // Save AI response to Room and reload session to get proper IDs
                    scope.launch(Dispatchers.IO) {
                        currentSessionId.value?.let { sid ->
                            database?.chatDao()?.insertMessage(com.example.core.storage.ChatMessageEntity(
                                sessionId = sid,
                                sender = "AI",
                                content = rawResponse
                            ))
                            loadSession(sid)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "AI response failure", e)
                // Error not added to chat history per user requirement
            } finally {
                isChatRunning.value = false
                withContext(Dispatchers.Main) {
                    onFinished()
                }
            }
        }
    }

    fun deleteSelectedMessages(scope: CoroutineScope) {
        val selectedIds = chatHistory.value.filter { it.isSelected }.map { it.id }
        if (selectedIds.isEmpty()) return
        
        chatHistory.value = chatHistory.value.filter { !it.isSelected }
        scope.launch(Dispatchers.IO) {
            selectedIds.forEach { idStr ->
                try {
                    val id = idStr.toLong()
                    database?.chatDao()?.deleteMessage(id)
                } catch (e: Exception) {}
            }
        }
    }

    suspend fun importMessagesToContext(messages: List<com.example.core.storage.ChatMessageEntity>) {
        val mapped = messages.map {
            ChatMessage(
                id = "imported_${it.id}",
                sender = it.sender,
                content = "[FROM OTHER SESSION] ${it.content}",
                timestamp = it.timestamp,
                isSelected = true // Mark as selected context immediately
            )
        }
        chatHistory.value = chatHistory.value + mapped
    }

    fun exportDatabase(context: Context) {
        scope.launch(Dispatchers.IO) {
            try {
                val dbFile = context.getDatabasePath("cppc_database")
                if (dbFile.exists()) {
                    val exportDir = File("/storage/emulated/0/CPPC/exports")
                    if (!exportDir.exists()) exportDir.mkdirs()
                    val target = File(exportDir, "chat_history_export_${System.currentTimeMillis()}.db")
                    dbFile.copyTo(target, overwrite = true)
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "DB Exported to ${target.absolutePath}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Export error: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main)
}
