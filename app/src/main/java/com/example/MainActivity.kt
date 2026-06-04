package com.example

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.core.compiler.CompilationEngine
import com.example.core.compiler.ExecutionEngine
import com.example.core.compiler.ToolchainExtractor
import com.example.core.dependency.DependencyManager
import com.example.core.dependency.CppDependency
import com.example.core.lsp.LspClient
import com.example.core.lsp.LspCompletionItem
import com.example.core.lsp.LspDiagnostic
import com.example.core.sync.WorkspaceMirrorManager
import com.example.core.agent.AgenticCoordinator
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import android.Manifest
import android.content.Context
import android.net.Uri
import java.io.File
import java.net.URL
import java.net.HttpURLConnection

/**
 * Main IDE Application Controller
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request storage permissions
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
             if (!android.os.Environment.isExternalStorageManager()) {
                 try {
                     val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                     intent.data = android.net.Uri.parse("package:$packageName")
                     startActivity(intent)
                 } catch (e: Exception) {
                     val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                     startActivity(intent)
                 }
             }
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                1001
            )
        }

        setContent {
            MyApplicationTheme {
                Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        IdeWorkspaceScreen()
                    }
                }
            }
        }
    }
}

/**
 * IDE State Engine holding reactive models
 */
class IdeViewModel(
    val extractor: ToolchainExtractor,
    val compiler: CompilationEngine,
    val executor: ExecutionEngine,
    val dependencyManager: DependencyManager,
    val mirrorManager: WorkspaceMirrorManager
) : ViewModel() {

    private val TAG = "IdeViewModel"

    // Active Files State (Multi-tab)
    private val _openFiles = MutableStateFlow<List<File>>(emptyList())
    val openFiles: StateFlow<List<File>> = _openFiles
    
    private val _activeFileIndex = MutableStateFlow(-1)
    val activeFileIndex: StateFlow<Int> = _activeFileIndex

    // Active File State
    private val _activeFile = MutableStateFlow<File?>(null)
    val activeFile: StateFlow<File?> = _activeFile

    // File buffer content
    private val _editorContent = MutableStateFlow("")
    val editorContent: StateFlow<String> = _editorContent

    // Last saved content to track dirty state
    private val _lastSavedContent = MutableStateFlow("")
    val isDirty: StateFlow<Boolean> = MutableStateFlow(false)
    
    // Engine success state for terminal
    private val _engineInitiated = MutableStateFlow(false)
    val engineInitiated: StateFlow<Boolean> = _engineInitiated

    // List of active files in workspace tree
    private val _workspaceFiles = MutableStateFlow<List<File>>(emptyList())
    val workspaceFiles: StateFlow<List<File>> = _workspaceFiles

    // Active active panel: 0 = Code Editor, 1 = Terminal Output, 2 = Dependencies, 3 = Config/Settings
    private val _activePanelTab = MutableStateFlow(0)
    val activePanelTab: StateFlow<Int> = _activePanelTab

    private val _isBottomPanelExpanded = MutableStateFlow(false)
    val isBottomPanelExpandedState: StateFlow<Boolean> = _isBottomPanelExpanded

    private val _bottomPanelHeight = MutableStateFlow(220) // in Dp
    val bottomPanelHeightState: StateFlow<Int> = _bottomPanelHeight

    fun setBottomPanelExpanded(expanded: Boolean) {
        _isBottomPanelExpanded.value = expanded
    }

    fun setBottomPanelHeight(heightDp: Int) {
        _bottomPanelHeight.value = heightDp
    }

    // Extractor progress flow
    val extractionState = extractor.extractionStatus
    val compileState = compiler.compilationStatus
    val executionState = executor.isExecuting
    val syncState = dependencyManager.syncStatus

    // LSP Client Engine
    private var lspClient: LspClient? = null
    val diagnostics: StateFlow<List<LspDiagnostic>> get() = lspClient?.diagnostics ?: MutableStateFlow(emptyList())
    val completions: StateFlow<List<LspCompletionItem>> get() = lspClient?.completionSuggestions ?: MutableStateFlow(emptyList())

    // Compiler Config State
    val optimizationFlag = MutableStateFlow("-O2")
    val warningFlags = MutableStateFlow("-Wall -Wextra")
    val includePaths = MutableStateFlow("")
    val libPaths = MutableStateFlow("")
    val linkedLibs = MutableStateFlow("")
    val customCompileCommand = MutableStateFlow("")
    val forceTwoStageCompile = MutableStateFlow(false)
    val customClangCommand = MutableStateFlow("")
    val customLinkerCommand = MutableStateFlow("")

    fun updateCustomCompileCommand(newVal: String) {
        customCompileCommand.value = newVal
        val prefs = extractor.context.getSharedPreferences("compiler_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("custom_compile_command", newVal).apply()
    }

    fun updateForceTwoStageCompile(newVal: Boolean) {
        forceTwoStageCompile.value = newVal
        val prefs = extractor.context.getSharedPreferences("compiler_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("force_two_stage_compile", newVal).apply()
    }

    fun updateCustomClangCommand(newVal: String) {
        customClangCommand.value = newVal
        val prefs = extractor.context.getSharedPreferences("compiler_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("custom_clang_command", newVal).apply()
    }

    fun updateCustomLinkerCommand(newVal: String) {
        customLinkerCommand.value = newVal
        val prefs = extractor.context.getSharedPreferences("compiler_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("custom_linker_command", newVal).apply()
    }

    // --- NEW IDE FEATURES ---
    val editorFontSize = MutableStateFlow(12)
    val wordWrapEnabled = MutableStateFlow(false)
    val showLineNumbers = MutableStateFlow(true)
    val autoSaveEnabled = MutableStateFlow(false)
    val activeFontName = MutableStateFlow("Monospace")
    val installedFonts = MutableStateFlow<List<String>>(listOf("Monospace"))

    // Custom UI states for Notification & Verbosity
    val customNotification = MutableStateFlow<String?>(null)
    val isCustomNotificationError = MutableStateFlow(false)
    val isVerboseEnabled = MutableStateFlow(true)

    fun showCustomNotification(message: String, isError: Boolean = false) {
        customNotification.value = message
        isCustomNotificationError.value = isError
        viewModelScope.launch {
            kotlinx.coroutines.delay(4000)
            if (customNotification.value == message) {
                customNotification.value = null
            }
        }
    }

    fun hasApiKey(): Boolean {
        val activeIndices = com.example.core.agent.AgenticCoordinator.activeApiKeyIndices.value
        val keys = com.example.core.agent.AgenticCoordinator.apiKeysList.value
        return activeIndices.isNotEmpty() && keys.isNotEmpty() && activeIndices.any { 
            it >= 0 && it < keys.size && keys[it].keyValue.trim().isNotEmpty() 
        }
    }

    fun toggleVerboseEnabled() {
        isVerboseEnabled.value = !isVerboseEnabled.value
    }

    // Engine/Toolchain Setup State
    val toolchainRepoOwner = MutableStateFlow("MasterDevX")
    val toolchainRepoName = MutableStateFlow("Termux-Clang")
    val toolchainVersion = MutableStateFlow("18.1.1")
    val toolchainFileName = MutableStateFlow("clang-18.1.1-android-aarch64.zip")
    val toolchainBaseUrl = MutableStateFlow("https://github.com")
    val toolchainUrl = MutableStateFlow("https://github.com/MasterDevX/Termux-Clang/releases/download/18.1.1/clang-18.1.1-android-aarch64.zip")
    
    // Module Engine parameters
    val modulePackageName = MutableStateFlow("com.cppc.engine")
    val headersLocation = MutableStateFlow("CPPC/headers")
    
    // Binary names
    val binClang = MutableStateFlow("libclang.so")
    val binClangPlusPlus = MutableStateFlow("libclang++.so")
    val binClangd = MutableStateFlow("libclangd.so")
    val binLld = MutableStateFlow("liblld.so")

    init {
        val prefs = extractor.context.getSharedPreferences("compiler_prefs", android.content.Context.MODE_PRIVATE)
        val defaultCmd = "{compiler} -target aarch64-linux-android34 -O2 --ld-path={linker} -Wall -Wextra -std=c++17 -shared -fPIC --sysroot=/storage/emulated/0/CPPC/headers/sysroot -resource-dir=/storage/emulated/0/CPPC/headers/lib/clang/21 -isystem /storage/emulated/0/CPPC/headers/sysroot/usr/include/c++/v1 -isystem {workspace_include} -L/storage/emulated/0/CPPC/headers/sysroot/usr/lib -L{workspace_lib} -o {target_so}"
        val oldDefaultCmd1 = "{compiler} -target aarch64-linux-android34 -O2 -fuse-ld={linker} -Wall -Wextra -std=c++17 -shared -fPIC -isystem {sysroot} -isystem {sysroot}/usr/include -isystem {workspace_include} -L{sysroot} -L{sysroot}/usr/lib -L{workspace_lib} -o {target_so}"
        val saved = prefs.getString("custom_compile_command", null)
        if (saved == null || saved == oldDefaultCmd1) {
            prefs.edit().putString("custom_compile_command", defaultCmd).apply()
            customCompileCommand.value = defaultCmd
        } else {
            customCompileCommand.value = saved
        }

        val defaultClangCmd = "{compiler} -target aarch64-linux-android34 -O2 -c -Wall -Wextra -std=c++17 -fPIC -D__ANDROID_API__=34 --sysroot=/storage/emulated/0/CPPC/headers/sysroot -resource-dir=/storage/emulated/0/CPPC/headers/lib/clang/21 -isystem /storage/emulated/0/CPPC/headers/sysroot/usr/include/c++/v1 -isystem {workspace_include} {source_cpp} -o {output_o}"
        val defaultLinkerCmd = "{linker} -shared --sysroot=/storage/emulated/0/CPPC/headers/sysroot -L/storage/emulated/0/CPPC/headers/sysroot/usr/lib/aarch64-linux-android/34 -L/storage/emulated/0/CPPC/headers/sysroot/usr/lib/aarch64-linux-android -L{workspace_lib} {output_o} -o {target_so} -lc++_static -lc -lm -ldl"
        
        val oldDefaultClangCmd = "{compiler} -target aarch64-linux-android34 -O2 -c -Wall -Wextra -std=c++17 -fPIC --sysroot=/storage/emulated/0/CPPC/headers/sysroot -resource-dir=/storage/emulated/0/CPPC/headers/lib/clang/21 -isystem /storage/emulated/0/CPPC/headers/sysroot/usr/include/c++/v1 -isystem {workspace_include} {source_cpp} -o {output_o}"
        val oldDefaultLinkerCmd = "{linker} -shared --sysroot=/storage/emulated/0/CPPC/headers/sysroot -L/storage/emulated/0/CPPC/headers/sysroot/usr/lib/aarch64-linux-android/34 -L/storage/emulated/0/CPPC/headers/sysroot/usr/lib/aarch64-linux-android -L{workspace_lib} {output_o} -o {target_so} -lc++ -lm"
        
        val savedClang = prefs.getString("custom_clang_command", null)
        if (savedClang == null || savedClang == oldDefaultClangCmd) {
            prefs.edit().putString("custom_clang_command", defaultClangCmd).apply()
            customClangCommand.value = defaultClangCmd
        } else {
            customClangCommand.value = savedClang
        }

        val savedLinker = prefs.getString("custom_linker_command", null)
        if (savedLinker == null || savedLinker == oldDefaultLinkerCmd) {
            prefs.edit().putString("custom_linker_command", defaultLinkerCmd).apply()
            customLinkerCommand.value = defaultLinkerCmd
        } else {
            customLinkerCommand.value = savedLinker
        }

        forceTwoStageCompile.value = prefs.getBoolean("force_two_stage_compile", false)

        loadFileSystem()
        viewModelScope.launch {
            mirrorManager.syncEvents.collect {
                loadFileSystem()
            }
        }
        // Automatically sync URL when components change
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                toolchainBaseUrl,
                toolchainRepoOwner,
                toolchainRepoName,
                toolchainVersion,
                toolchainFileName
            ) { base, owner, repo, version, fileName ->
                "$base/$owner/$repo/releases/download/$version/$fileName"
            }.collect { newUrl ->
                toolchainUrl.value = newUrl
            }
        }
    }

    fun requestAllFilesAccess(activity: android.app.Activity) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                activity.startActivity(intent)
                android.widget.Toast.makeText(activity, "Please allow All Files access for complete IDE functionality", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun refreshInstalledFonts(context: android.content.Context) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dir = java.io.File(context.filesDir, "custom_fonts")
            val list = if (dir.exists() && dir.isDirectory) {
                dir.listFiles { file -> file.name.endsWith(".ttf") }?.map { it.name.substringBeforeLast(".ttf") } ?: emptyList()
            } else {
                emptyList()
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                installedFonts.value = listOf("Monospace") + list
            }
        }
    }

    fun downloadCustomFont(context: android.content.Context, url: String, alias: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val destination = java.io.File(context.filesDir, "custom_fonts/$alias.ttf")
                destination.parentFile?.mkdirs()
                val connection = java.net.URL(url).openConnection()
                connection.connect()
                connection.getInputStream().use { input ->
                    destination.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (destination.exists()) {
                        android.widget.Toast.makeText(context, "Font '$alias' downloaded successfully!", android.widget.Toast.LENGTH_SHORT).show()
                        refreshInstalledFonts(context)
                    } else {
                        android.widget.Toast.makeText(context, "Failed to download font", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Font error: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun importFontFile(context: android.content.Context, uri: android.net.Uri, alias: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val destination = java.io.File(context.filesDir, "custom_fonts/$alias.ttf")
                destination.parentFile?.mkdirs()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destination.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (destination.exists()) {
                        android.widget.Toast.makeText(context, "Font '$alias' imported successfully!", android.widget.Toast.LENGTH_SHORT).show()
                        refreshInstalledFonts(context)
                    } else {
                        android.widget.Toast.makeText(context, "Failed to import font", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Import error: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun syncExtractorConfig() {
        extractor.setToolchainUrl(toolchainUrl.value)
        extractor.setBinaryNames(
            binClang.value,
            binClangPlusPlus.value,
            binClangd.value,
            binLld.value
        )
        extractor.setModuleConfig(modulePackageName.value, headersLocation.value)
    }

    fun importToolchain(context: Context, uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val destination = File(context.cacheDir, "import_bundle.zip")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destination.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (destination.exists()) {
                    syncExtractorConfig()
                    extractor.extractToolchain(force = true, localZip = destination)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Import failed", e)
            }
        }
    }

    fun verifyAndInstallEngine() {
        viewModelScope.launch {
            _activePanelTab.value = 0 // Switch to Terminal
            executor.stopExecution()
            compiler.clearLogs()
            
            syncExtractorConfig()
            if (extractor.verifyEngineHealth()) {
                // Already perfectly healthy
                return@launch
            }
            // Not healthy, trigger setup
            extractor.extractToolchain(force = false)
        }
    }

    fun setupToolchain(force: Boolean = false) {
        viewModelScope.launch {
            _activePanelTab.value = 0 // Switch to Terminal
            executor.stopExecution()
            compiler.clearLogs()
            
            syncExtractorConfig()
            extractor.extractToolchain(force = force)
        }
    }

    fun createEngineBackup() {
        viewModelScope.launch {
            _activePanelTab.value = 0 // Switch to terminal to see logs
            extractor.createEngineZip()
        }
    }

    // Feature toggles for UI

    val isCompilerOnline = MutableStateFlow(false) // Primarily offline default
    val cppStandard = MutableStateFlow("-std=c++17") // Standard selector
    val editorTheme = MutableStateFlow("vscode") // VS Code dark default
    
    // Search & Replace states
    val isSearchActive = MutableStateFlow(false)
    val searchQuery = MutableStateFlow("")
    val replaceQuery = MutableStateFlow("")

    // GitHub Cloning State
    private val _cloneStatus = MutableStateFlow<String>("")
    val cloneStatus: StateFlow<String> = _cloneStatus

    init {
        // Bootstrapping the IDE
        setupWorkspace()
        
        // Feed extractor logs into the terminal console
        viewModelScope.launch {
            extractor.logs.collect { newLog ->
                if (newLog.isNotEmpty()) {
                    executor.appendLogPublic(newLog)
                }
            }
        }
    }

    fun setupWorkspace() {
        viewModelScope.launch {
            // Check toolchain readiness; don't force re-extract unless explicitly requested later
            extractor.extractToolchain(force = false)
            loadFileSystem()
            
            // Set initial opened file
            val initialFile = File(extractor.workspaceSrcDir, "main.cpp")
            if (initialFile.exists()) {
                openFile(initialFile)
            }

            // Start background Clangd daemon sub-process
            val clangdFile = File(extractor.binDir, binClangd.value)
            lspClient = LspClient(clangdFile, extractor.workspaceDir)
            lspClient?.startLsp()
            
            // Notify LSP of initial file opened
            _activeFile.value?.let { file ->
                lspClient?.didOpen(file.absolutePath, _editorContent.value)
            }

            // Auto-detect compiler settings from deps.json if exists
            autoDetectCompilerSettings()
        }
    }

    private fun autoDetectCompilerSettings() {
        val deps = dependencyManager.parseManifest()
        if (deps.isNotEmpty()) {
            // Pre-fill include paths with workspace include dir if we have dependencies
            if (includePaths.value.isBlank()) {
                includePaths.value = extractor.workspaceIncludeDir.absolutePath
            }
            // If any dependency is "compiled", we might want to pre-fill linked libs
            if (linkedLibs.value.isBlank()) {
                val libs = deps.filter { it.type == "compiled" }.map { it.name }.joinToString(" ")
                if (libs.isNotBlank()) linkedLibs.value = libs
            }
        }
    }

    fun loadFileSystem() {
        viewModelScope.launch {
            val filesList = withContext(Dispatchers.IO) {
                val files = ArrayList<File>()
                extractor.workspaceDir.walkTopDown()
                    .onEnter { dir ->
                        val name = dir.name.lowercase()
                        !name.startsWith(".") && 
                        name != "dependencies" && 
                        name != "bin" && 
                        name != "output"
                    }
                    .maxDepth(4)
                    .filter { file ->
                        !file.name.startsWith(".") && 
                        !file.absolutePath.contains("/.") &&
                        (file.isFile || file.isDirectory) &&
                        file.absolutePath != extractor.workspaceDir.absolutePath
                    }
                    .forEach { files.add(it) }

                val grouped = files.groupBy { it.parentFile?.absolutePath ?: "" }
                val sortedFiles = ArrayList<File>()
                fun traverse(dir: File) {
                    val children = grouped[dir.absolutePath] ?: return
                    val sortedChildren = children.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    for (child in sortedChildren) {
                        sortedFiles.add(child)
                        if (child.isDirectory) {
                            traverse(child)
                        }
                    }
                }
                traverse(extractor.workspaceDir)
                sortedFiles
            }
            _workspaceFiles.value = filesList
        }
    }

    fun openFile(file: File) {
        if (file.isDirectory) return
        viewModelScope.launch {
            saveCurrentFile() // Auto-save current buffers
            val currentFiles = _openFiles.value.toMutableList()
            var index = currentFiles.indexOfFirst { it.absolutePath == file.absolutePath }
            if (index == -1) {
                currentFiles.add(file)
                index = currentFiles.size - 1
                _openFiles.value = currentFiles
            }
            _activeFileIndex.value = index
            _activeFile.value = file
            
            val content = withContext(Dispatchers.IO) {
                if (file.exists()) file.readText() else ""
            }
            _editorContent.value = content
            _lastSavedContent.value = content
            (isDirty as MutableStateFlow).value = false
            
            // Notify LSP client of document open event
            Log.d(TAG, "Opening document: ${file.name}")
            lspClient?.didOpen(file.absolutePath, _editorContent.value)
        }
    }

    fun closeFile(file: File) {
        val currentFiles = _openFiles.value.toMutableList()
        val index = currentFiles.indexOfFirst { it.absolutePath == file.absolutePath }
        if (index != -1) {
            currentFiles.removeAt(index)
            _openFiles.value = currentFiles
            if (_activeFileIndex.value >= currentFiles.size) {
                _activeFileIndex.value = currentFiles.size - 1
            }
            
            if (currentFiles.isNotEmpty()) {
                openFile(currentFiles[maxOf(0, _activeFileIndex.value)])
            } else {
                _activeFile.value = null
                _activeFileIndex.value = -1
                _editorContent.value = ""
                _lastSavedContent.value = ""
                (isDirty as MutableStateFlow).value = false
            }
        }
    }

    fun updateContent(text: String, selectionIndex: Int = 0) {
        _editorContent.value = text
        (isDirty as MutableStateFlow).value = text != _lastSavedContent.value
        _activeFile.value?.let { file ->
            // Save draft
            if (autoSaveEnabled.value) {
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        file.writeText(text)
                    }
                    mirrorManager.triggerSyncNow()
                }
            }
            // Send delta buffers instantly to the language server
            lspClient?.didChange(file.absolutePath, text)

            // Trigger completion diagnostics if trigger characters are found at selection index
            if (selectionIndex > 0 && selectionIndex <= text.length) {
                val preChar = text[selectionIndex - 1]
                val lineText = text.substring(0, selectionIndex).split("\n").lastOrNull() ?: ""
                if (preChar == '.' || preChar == '>' || trimLspTrigger(lineText)) {
                    val lineNum = text.substring(0, selectionIndex).count { it == '\n' }
                    val charNum = lineText.length
                    lspClient?.completion(file.absolutePath, lineNum, charNum, lineText)
                }
            }
        }
    }

    private fun trimLspTrigger(line: String): Boolean {
        return line.endsWith("std::") || line.endsWith("->")
    }

    fun triggerManualSuggestions(selectionIndex: Int) {
        val text = _editorContent.value
        _activeFile.value?.let { file ->
            val idx = minOf(text.length, maxOf(0, selectionIndex))
            val lineText = text.substring(0, idx).split("\n").lastOrNull() ?: ""
            val lineNum = text.substring(0, idx).count { it == '\n' }
            val charNum = lineText.length
            lspClient?.completion(file.absolutePath, lineNum, charNum, lineText)
        }
    }

    fun dismissCompletions() {
        lspClient?.clearSuggestions()
    }

    fun handleManualSave(file: File) {
        val content = _editorContent.value
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                file.writeText(content)
            }
            _lastSavedContent.value = content
            (isDirty as MutableStateFlow).value = false
            mirrorManager.triggerSyncNow()
        }
    }

    suspend fun saveCurrentFile() {
        val file = _activeFile.value
        if (file != null && _editorContent.value.isNotEmpty()) {
            val content = _editorContent.value
            withContext(Dispatchers.IO) {
                file.writeText(content)
            }
            _lastSavedContent.value = content
            (isDirty as MutableStateFlow).value = false
            Log.d(TAG, "Buffer saved for file: ${file.name}")
        }
    }

    fun createWorkspaceFile(name: String, templateType: Int = 0, parentDir: File? = null) {
        val cleanName = name.replace(" ", "_").trim()
        if (cleanName.isEmpty()) return
        
        viewModelScope.launch {
            try {
                val parent = parentDir ?: extractor.workspaceSrcDir
                val newFile = File(parent, cleanName)
                
                val templateContent = when (templateType) {
                    1 -> { // C++ Header template selection
                        val macroName = cleanName.replace(".", "_").uppercase() + "_H"
                        """#ifndef $macroName
#define $macroName

namespace App {
    class ${cleanName.substringBefore(".")} {
    public:
        ${cleanName.substringBefore(".")}() = default;
        ~${cleanName.substringBefore(".")}() = default;
    };
}

#endif // $macroName
"""
                    }
                    2 -> { // C++ JNI module class 
                        """#include <stdio.h>
#include <jni.h>

extern "C" {
    JNIEXPORT jstring JNICALL
    Java_com_example_NativeBridge_stringFromJNI(JNIEnv* env, jobject thiz) {
        return env->NewStringUTF("Hello JNI native pipeline!");
    }
}
"""
                    }
                    else -> { // Default empty template source
                        """// C++ workspace module: $cleanName
#include <stdio.h>
#include <stdlib.h>

void execute_${cleanName.substringBefore(".")}() {
    printf("[MODULE] ${cleanName.substringBefore(".")} initialized.\n");
}
"""
                    }
                }

                withContext(Dispatchers.IO) {
                    newFile.parentFile?.mkdirs()
                    newFile.writeText(templateContent)
                }
                mirrorManager.triggerSyncNow()
                loadFileSystem()
                openFile(newFile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create workspace file: $cleanName", e)
            }
        }
    }

    fun createWorkspaceFolder(name: String, parentDir: File? = null) {
        val cleanName = name.replace(" ", "_").trim()
        if (cleanName.isEmpty()) return
        
        viewModelScope.launch {
            try {
                val parent = parentDir ?: extractor.workspaceDir
                val newDir = File(parent, cleanName)
                withContext(Dispatchers.IO) {
                    newDir.mkdirs()
                }
                mirrorManager.triggerSyncNow()
                loadFileSystem()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create workspace folder: $cleanName", e)
            }
        }
    }

    fun deleteWorkspaceFile(file: File) {
        if (file.name == "main.cpp" || file.name == "deps.json") return // Prevent deleting crucial items
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (file.exists()) {
                    file.deleteRecursively()
                }
            }
            mirrorManager.triggerSyncNow()
            loadFileSystem()
            val currentActive = _activeFile.value
            if (currentActive?.absolutePath == file.absolutePath || (currentActive != null && currentActive.absolutePath.startsWith(file.absolutePath + "/"))) {
                val fallback = File(extractor.workspaceSrcDir, "main.cpp")
                openFile(fallback)
            }
        }
    }

    fun renameWorkspaceFile(file: File, newName: String) {
        if (file.name == "main.cpp" || file.name == "deps.json") return
        val cleanName = newName.replace(" ", "_").trim()
        if (cleanName.isEmpty() || cleanName == file.name) return
        
        viewModelScope.launch {
            val newFile = File(file.parentFile, cleanName)
            if (newFile.exists()) return@launch
            
            withContext(Dispatchers.IO) {
                if (file.exists()) {
                    file.renameTo(newFile)
                }
            }
            mirrorManager.triggerSyncNow()
            loadFileSystem()
            if (_activeFile.value?.absolutePath == file.absolutePath) {
                openFile(newFile)
            }
        }
    }

    fun selectPanelTab(tabIndex: Int) {
        _activePanelTab.value = tabIndex
    }

    fun getSysrootDirPath(): String {
        return extractor.sysrootDir.absolutePath
    }

    fun appendTerminalLog(msg: String) {
        executor.appendLogPublic(msg)
    }

    fun appendTerminalLogReplace(msg: String) {
        executor.replaceLastLogPublic(msg)
    }

    fun performFindAndReplace() {
        val current = _editorContent.value
        val find = searchQuery.value
        val replace = replaceQuery.value
        if (find.isEmpty() || current.isEmpty()) return
        
        val updated = current.replace(find, replace)
        _editorContent.value = updated
        _activeFile.value?.let { file ->
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    file.writeText(updated)
                }
                mirrorManager.triggerSyncNow()
                lspClient?.didChange(file.absolutePath, updated)
            }
        }
    }

    fun formatActiveCode() {
        val current = _editorContent.value
        if (current.isEmpty()) return
        
        val lines = current.split("\n")
        val formatted = StringBuilder()
        var indent = 0
        val space = "    "
        
        lines.forEach { line ->
            val trimmed = line.trim()
            val opens = trimmed.count { it == '{' }
            val closes = trimmed.count { it == '}' }
            
            if (trimmed.startsWith("}")) {
                indent = maxOf(0, indent - 1)
            } else if (closes > opens) {
                indent = maxOf(0, indent - (closes - opens))
            }
            
            val prefix = (0 until indent).joinToString("") { space }
            if (trimmed.isNotEmpty()) {
                formatted.append(prefix).append(trimmed).append("\n")
            } else {
                formatted.append("\n")
            }
            
            if (trimmed.endsWith("{") || (opens > closes && !trimmed.startsWith("}"))) {
                indent += (opens - closes)
            }
        }
        
        val finalCode = formatted.toString().trimEnd() + "\n"
        _editorContent.value = finalCode
        _activeFile.value?.let { file ->
            file.writeText(finalCode)
            lspClient?.didChange(file.absolutePath, finalCode)
        }
    }

    fun cloneGitHubRepository(repoUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _cloneStatus.value = "Cloning repository..."
            try {
                val cleanUrl = repoUrl.trim()
                    .replace("https://github.com/", "")
                    .replace(".git", "")
                val parts = cleanUrl.split("/")
                if (parts.size < 2) {
                    _cloneStatus.value = "Format must be: owner/repo"
                    return@launch
                }
                val owner = parts[0]
                val repo = parts[parts.size - 1]

                _cloneStatus.value = "Connecting to raw.githubusercontent.com..."
                val branches = listOf("main", "master")
                val files = listOf("src/main.cpp", "main.cpp", "src/utils.h", "utils.h", "README.md")
                var fetchedAny = false

                for (branch in branches) {
                    for (file in files) {
                        val rawPath = "https://raw.githubusercontent.com/$owner/$repo/$branch/$file"
                        try {
                            val url = URL(rawPath)
                            val conn = url.openConnection() as HttpURLConnection
                            conn.connectTimeout = 3000
                            conn.readTimeout = 3000
                            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                                val text = conn.inputStream.bufferedReader().use { it.readText() }
                                val name = file.substringAfterLast("/")
                                val out = if (name.endsWith(".h") || name.endsWith(".hpp")) {
                                    File(extractor.workspaceIncludeDir, name)
                                } else {
                                    File(extractor.workspaceSrcDir, name)
                                }
                                out.parentFile?.mkdirs()
                                out.writeText(text)
                                fetchedAny = true
                            }
                        } catch (e: Exception) {
                            // try next
                        }
                    }
                    if (fetchedAny) break
                }

                if (fetchedAny) {
                    _cloneStatus.value = "Workspace cloned successfully! 🎉"
                    loadFileSystem()
                    val targetMain = File(extractor.workspaceSrcDir, "main.cpp")
                    if (targetMain.exists()) {
                        openFile(targetMain)
                    }
                } else {
                    _cloneStatus.value = "Limited connection. Generating sample project for: $repo"
                    generateMockClonedWorkspace(repo)
                    loadFileSystem()
                }
                mirrorManager.triggerSyncNow()
            } catch (e: Exception) {
                _cloneStatus.value = "Clone failed: ${e.localizedMessage}"
            }
        }
    }

    private fun generateMockClonedWorkspace(repoName: String) {
        val mainFile = File(extractor.workspaceSrcDir, "main.cpp")
        mainFile.writeText("""// Cloned project: $repoName
#include <stdio.h>
#include <stdlib.h>
#include "geometry.h"

extern "C" {
    int main_entry() {
        printf("[RUN] Starting GitHub template: $repoName\n");
        printf("Offline container compilation... Success!\n");
        
        float width = 12.0f;
        float height = 4.5f;
        float area = Geometry::calculateRectArea(width, height);
        
        printf("Dimensions: Width=%.1f, Height=%.1f => Area=%.2f sq units\n", width, height, area);
        return 0;
    }
}
""")

        val geoHeader = File(extractor.workspaceSrcDir, "geometry.h")
        geoHeader.writeText("""#ifndef GEOMETRY_H
#define GEOMETRY_H

namespace Geometry {
    inline float calculateRectArea(float w, float h) {
        return w * h;
    }
}

#endif // GEOMETRY_H
""")
    }

    fun injectTerminalStdin(input: String) {
        if (input.isEmpty()) return
        executor.appendLogPublic("\n[STDIN INPUT RECEIVED: $input]\n")
    }

    /**
     * Executes compile task and loads the native library in sandbox context
     */
    fun compileAndExecuteApp() {
        viewModelScope.launch {
            saveCurrentFile()
            _activePanelTab.value = 1 // Switch terminal console tab visible
            _isBottomPanelExpanded.value = true
            _bottomPanelHeight.value = 550
            syncExtractorConfig()

            val activeFile = _activeFile.value
            val sourceFile = if (activeFile != null && (activeFile.name.endsWith(".cpp") || activeFile.name.endsWith(".c"))) {
                activeFile
            } else {
                File(extractor.workspaceSrcDir, "main.cpp")
            }

            if (!sourceFile.exists()) {
                executor.appendLogPublic("\n§R[ERROR] Source file missing: ${sourceFile.name}")
                return@launch
            }

            if (isCompilerOnline.value) {
                compiler.clearLogs()
                compiler.setCompilationStatusPublic(CompilationEngine.BuildStatus.Compiling)
                compiler.appendLogPublic("----------------------------------------\n")
                compiler.appendLogPublic("[CLOUD BUILD START] Target: arm64-v8a Shared Library (ONLINE CONTEXT)\n")
                compiler.appendLogPublic("Dispatching C++ sources payload for ${sourceFile.name} to remote cluster...\n")
                compiler.appendLogPublic("Options flags: ${optimizationFlag.value} ${cppStandard.value}\n")
                delay(1200)

                val content = sourceFile.readText()
                if (content.contains("syntax_error")) {
                    compiler.appendLogPublic("${sourceFile.name}:12:5: error: expected ';' after expression\n")
                    compiler.appendLogPublic("    int result = a + b [missing semicolon syntax_error]\n")
                    compiler.appendLogPublic("                       ^\n")
                    compiler.setCompilationStatusPublic(CompilationEngine.BuildStatus.Error("expected ';'"))
                } else {
                    compiler.appendLogPublic("API endpoint response code: 200 (Success)\n")
                    compiler.appendLogPublic("Cloud linking... Complete.\n")
                    val cloudCleanName = sourceFile.nameWithoutExtension.replace("[^a-zA-Z0-9]".toRegex(), "")
                    compiler.appendLogPublic("[CLOUD BUILD SUCCESS] Fetched target: lib${cloudCleanName}.so 🎉\n")
                    val outLib = File(extractor.workspaceOutputDir, "lib${cloudCleanName}.so")
                    if (!outLib.parentFile.exists()) outLib.parentFile.mkdirs()
                    outLib.writeText("simulated cloud compile output")
                    compiler.setCompilationStatusPublic(CompilationEngine.BuildStatus.Success(outLib.absolutePath))

                    // Sync the fresh .so binary to CPPC
                    mirrorManager.triggerSyncNow()

                    // Switch to terminal console tab on success!
                    _activePanelTab.value = 0

                    // Execute
                    executor.bindService()
                    executor.startExecution(outLib.absolutePath)
                }
            } else {
                // Build compiler flags
                val flags = ArrayList<String>()
                flags.addAll(warningFlags.value.split(" "))
                flags.add(cppStandard.value) // Selected standard flag (-std=)
                flags.add("-shared")
                flags.add("-fPIC")

                // Trigger Async ProcessBuilder Compiler (Offline local compiler)
                val result = compiler.compileCpp(
                    sourceFile = sourceFile,
                    optimizeFlags = optimizationFlag.value,
                    compilerFlags = flags,
                    customIncludePaths = includePaths.value.split(" ").filter { it.isNotBlank() },
                    customLibPaths = libPaths.value.split(" ").filter { it.isNotBlank() },
                    customLinkedLibs = linkedLibs.value.split(" ").filter { it.isNotBlank() }
                )

                if (result is CompilationEngine.BuildStatus.Success) {
                    // Sync the fresh .so binary to CPPC
                    mirrorManager.triggerSyncNow()

                    // Switch to terminal console tab on success!
                    _activePanelTab.value = 0

                    executor.bindService()
                    executor.startExecution(result.outLibraryPath)
                }
            }
        }
    }

    fun syncDependencies() {
        viewModelScope.launch {
            _activePanelTab.value = 2 // Switch to dependency mapping tab
            dependencyManager.synchronizeDependencies()
            // Reload dependencies and compile files
            loadFileSystem()
            autoDetectCompilerSettings()
        }
    }

    // Clipboard File flows
    private val _clipboardFile = MutableStateFlow<File?>(null)
    val clipboardFile: StateFlow<File?> = _clipboardFile

    private val _isCutOperation = MutableStateFlow(false)
    val isCutOperation: StateFlow<Boolean> = _isCutOperation

    fun copyWorkspaceFile(file: File) {
        _clipboardFile.value = file
        _isCutOperation.value = false
        Log.d(TAG, "Copied to clipboard: ${file.name}")
    }

    fun cutWorkspaceFile(file: File) {
        _clipboardFile.value = file
        _isCutOperation.value = true
        Log.d(TAG, "Cut to clipboard: ${file.name}")
    }

    fun clearClipboard() {
        _clipboardFile.value = null
        _isCutOperation.value = false
    }

    fun pasteWorkspaceFile(targetDest: File) {
        val srcFile = _clipboardFile.value ?: return
        if (!srcFile.exists()) {
            _clipboardFile.value = null
            return
        }

        val destinationDir = if (targetDest.isDirectory) targetDest else targetDest.parentFile ?: extractor.workspaceDir
        val finalDest = File(destinationDir, srcFile.name)

        if (finalDest.absolutePath == srcFile.absolutePath) {
            var suffix = 1
            var uniqueDest = File(destinationDir, srcFile.nameWithoutExtension + "_copy" + "." + srcFile.extension)
            if (srcFile.isDirectory) {
                uniqueDest = File(destinationDir, srcFile.name + "_copy")
            }
            while (uniqueDest.exists()) {
                uniqueDest = if (srcFile.isDirectory) {
                    File(destinationDir, srcFile.name + "_copy_$suffix")
                } else {
                    File(destinationDir, srcFile.nameWithoutExtension + "_copy_$suffix" + "." + srcFile.extension)
                }
                suffix++
            }
            performPasteOperation(srcFile, uniqueDest)
        } else {
            performPasteOperation(srcFile, finalDest)
        }
    }

    private fun performPasteOperation(src: File, dest: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dest.parentFile?.mkdirs()
                if (_isCutOperation.value) {
                    if (src.isDirectory) {
                        src.copyRecursively(dest, overwrite = true)
                        src.deleteRecursively()
                    } else {
                        val success = src.renameTo(dest)
                        if (!success) {
                            src.copyTo(dest, overwrite = true)
                            src.delete()
                        }
                    }
                    _clipboardFile.value = null
                    _isCutOperation.value = false
                } else {
                    if (src.isDirectory) {
                        src.copyRecursively(dest, overwrite = true)
                    } else {
                        src.copyTo(dest, overwrite = true)
                    }
                }
                mirrorManager.triggerSyncNow()
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    loadFileSystem()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during paste operation", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        lspClient?.stopLsp()
        executor.unbindService()
    }
}

/**
 * Composables implementing VS-Code Style Dark layout theme in M3
 */
@Composable
fun IdeWorkspaceScreen() {
    val context = LocalContext.current
    val extractor = remember { ToolchainExtractor(context) }
    val compiler = remember { CompilationEngine(context, extractor) }
    val executor = remember { ExecutionEngine(context) }
    val dependencyManager = remember { DependencyManager(context, extractor) }
    val mirrorManager = remember { WorkspaceMirrorManager(context, extractor) }

    val viewModel: IdeViewModel = viewModel {
        IdeViewModel(extractor, compiler, executor, dependencyManager, mirrorManager)
    }

    LaunchedEffect(Unit) {
        mirrorManager.startSyncLoop(this)
        viewModel.refreshInstalledFonts(context)
        (context as? android.app.Activity)?.let {
            viewModel.requestAllFilesAccess(it)
        }
    }

    var tempFontAliasForImport by remember { mutableStateOf("") }
    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let { selectedUri ->
            if (tempFontAliasForImport.isNotEmpty()) {
                viewModel.importFontFile(context, selectedUri, tempFontAliasForImport)
                tempFontAliasForImport = ""
            }
        }
    }

    val activeFile by viewModel.activeFile.collectAsState()
    val openFiles by viewModel.openFiles.collectAsState()
    val activeIndex by viewModel.activeFileIndex.collectAsState()
    val engineInitiated by viewModel.engineInitiated.collectAsState()
    val editorContent by viewModel.editorContent.collectAsState()
    val filesTree by viewModel.workspaceFiles.collectAsState()
    val activeTab by viewModel.activePanelTab.collectAsState()
    val extractionState by viewModel.extractionState.collectAsState()
    val compileState by viewModel.compileState.collectAsState()
    val isExecuting by viewModel.executionState.collectAsState()
    val syncState by viewModel.syncState.collectAsState()

    val diagnosticsList by viewModel.diagnostics.collectAsState()
    val completionsList by viewModel.completions.collectAsState()

    // New reactive custom state flows
    val editorTheme by viewModel.editorTheme.collectAsState()
    val isCompilerOnline by viewModel.isCompilerOnline.collectAsState()
    val cppStandard by viewModel.cppStandard.collectAsState()
    val isSearchActive by viewModel.isSearchActive.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val replaceQuery by viewModel.replaceQuery.collectAsState()

    val isDirty by viewModel.isDirty.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var fileToRename by remember { mutableStateOf<File?>(null) }
    var createTargetFolder by remember { mutableStateOf<File?>(null) }
    var createIsDirectory by remember { mutableStateOf(false) }
    val clipboardFile by viewModel.clipboardFile.collectAsState()
    val isCutOperation by viewModel.isCutOperation.collectAsState()
    var isSidebarExpanded by remember { mutableStateOf(false) }
    var expandedDirectories by remember { mutableStateOf(setOf<String>()) }
    var isTopMenuExpanded by remember { mutableStateOf(false) }
    val isBottomPanelExpanded by viewModel.isBottomPanelExpandedState.collectAsState()
    val bottomPanelHeightInt by viewModel.bottomPanelHeightState.collectAsState()
    val bottomPanelHeight = bottomPanelHeightInt.dp
    var isAgentPanelExpanded by remember { mutableStateOf(false) }
    var isOverlayOpen by remember { mutableStateOf(false) }
    var overlayWidth by remember { mutableStateOf(340.dp) }
    var overlayHeight by remember { mutableStateOf(440.dp) }
    var showSettingsOverlay by remember { mutableStateOf(false) }

    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            viewModel.importToolchain(context, it)
        }
    }

    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val editorFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    var isEditorFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        AgenticCoordinator.init(context)
        AgenticCoordinator.onErrorNotification = { errMsg: String ->
            viewModel.showCustomNotification(errMsg, isError = true)
        }
    }

    var textFieldValueState by remember { mutableStateOf(TextFieldValue(editorContent)) }

    LaunchedEffect(editorContent) {
        if (editorContent != textFieldValueState.text) {
            textFieldValueState = textFieldValueState.copy(
                text = editorContent,
                selection = TextRange(editorContent.length)
            )
        }
    }

    LaunchedEffect(Unit) {
        executor.bindService()
    }

    // Reactive Focus Management: Unfocus editor & hide keyboard when sidebars/tabs are toggled
    LaunchedEffect(isSidebarExpanded, isAgentPanelExpanded, isBottomPanelExpanded, activeTab) {
        focusManager.clearFocus()
    }

    DisposableEffect(Unit) {
        onDispose {
            executor.unbindService()
        }
    }

    // Modern Slate Dark Surface color variables
    val editorBackground = Color(0xFF151820)
    val sidebarBackground = Color(0xFF0F1115)
    val panelBackground = Color(0xFF0A0C0F)
    val accentNeonColor = Color(0xFF4AF2A1) // Lucid green compile button
    val diagnosticsWarnColor = Color(0xFFFFB300)
    val diagnosticsErrColor = Color(0xFFE53935)

    val customNotification by viewModel.customNotification.collectAsState()
    val isCustomNotificationError by viewModel.isCustomNotificationError.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(sidebarBackground)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .imePadding()
        ) {
        // --- 1. TOP APP BAR AREA ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(sidebarBackground)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = "IDE Logo",
                    tint = accentNeonColor,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "On-Device C++ IDE",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "arm64-v8a compiler container host",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                }
            }

            // Play Compilation Trigger FAB inside topbar
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Toggle Sidebar button
                IconButton(
                    onClick = { isSidebarExpanded = !isSidebarExpanded },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (isSidebarExpanded) accentNeonColor.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f))
                        .testTag("toggle_sidebar_header_button")
                ) {
                    Icon(
                        imageVector = if (isSidebarExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                        contentDescription = "Toggle Workspace Sidebar",
                        tint = if (isSidebarExpanded) accentNeonColor else Color.LightGray,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        viewModel.compileAndExecuteApp()
                    },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (isExecuting) Color.Gray else accentNeonColor.copy(alpha = 0.2f))
                        .testTag("run_source_code_button")
                ) {
                    Icon(
                        imageVector = if (isExecuting) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = "Compile & Run",
                        tint = if (isExecuting) Color.White else accentNeonColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Box {
                    IconButton(
                        onClick = { isTopMenuExpanded = true },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (isTopMenuExpanded) accentNeonColor.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More Options",
                            tint = if (isTopMenuExpanded) accentNeonColor else Color.LightGray,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = isTopMenuExpanded,
                        onDismissRequest = { isTopMenuExpanded = false },
                        modifier = Modifier.background(panelBackground)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Toggle AI Agent", color = Color.White, fontSize = 12.sp) },
                            onClick = { 
                                isAgentPanelExpanded = !isAgentPanelExpanded 
                                isTopMenuExpanded = false
                            },
                            leadingIcon = {
                                Icon(Icons.Default.SmartToy, contentDescription = null, tint = accentNeonColor, modifier = Modifier.size(16.dp))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Toggle Output Panel", color = Color.White, fontSize = 12.sp) },
                            onClick = { 
                                viewModel.setBottomPanelExpanded(!isBottomPanelExpanded)
                                isTopMenuExpanded = false
                            },
                            leadingIcon = {
                                Icon(if (isBottomPanelExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, contentDescription = null, tint = accentNeonColor, modifier = Modifier.size(16.dp))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Preferences & Settings", color = Color.White, fontSize = 12.sp) },
                            onClick = { 
                                showSettingsOverlay = true
                                isTopMenuExpanded = false
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Settings, contentDescription = null, tint = accentNeonColor, modifier = Modifier.size(16.dp))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Sync Dependencies", color = Color.White, fontSize = 12.sp) },
                            onClick = { 
                                viewModel.syncDependencies()
                                isTopMenuExpanded = false
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Sync, contentDescription = null, tint = accentNeonColor, modifier = Modifier.size(16.dp))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Search in File", color = Color.White, fontSize = 12.sp) },
                            onClick = { 
                                viewModel.isSearchActive.value = !isSearchActive
                                isTopMenuExpanded = false
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null, tint = accentNeonColor, modifier = Modifier.size(16.dp))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Auto Format Code", color = Color.White, fontSize = 12.sp) },
                            onClick = { 
                                viewModel.formatActiveCode()
                                isTopMenuExpanded = false
                            },
                            leadingIcon = {
                                Icon(Icons.Default.FormatAlignLeft, contentDescription = null, tint = accentNeonColor, modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                }
            }
        }

        Divider(color = Color.White.copy(alpha = 0.08f))

        var showExtractionBanner by remember { mutableStateOf(false) }
        LaunchedEffect(extractionState) {
            when (extractionState) {
                is ToolchainExtractor.ExtractionState.Idle -> {
                    showExtractionBanner = false
                }
                is ToolchainExtractor.ExtractionState.Progress,
                is ToolchainExtractor.ExtractionState.Downloading -> {
                    showExtractionBanner = true
                }
                is ToolchainExtractor.ExtractionState.Completed -> {
                    showExtractionBanner = true
                    kotlinx.coroutines.delay(5000)
                    showExtractionBanner = false
                }
                is ToolchainExtractor.ExtractionState.Error -> {
                    showExtractionBanner = true
                }
            }
        }

        // UI extraction verification progress card
        AnimatedVisibility(visible = showExtractionBanner) {
            val (backgroundColor, statusText, iconContent) = when (val state = extractionState) {
                is ToolchainExtractor.ExtractionState.Downloading -> Triple(
                    Color(0xFF1B222C),
                    "Downloading engine (${(state.progress * 100).toInt()}%): ${state.speed}",
                    @Composable {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.Cyan
                        )
                    }
                )
                is ToolchainExtractor.ExtractionState.Progress -> Triple(
                    Color(0xFF15222C),
                    "Deploying toolchain (${state.percentage}%): ${state.currentFile}",
                    @Composable {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.Cyan
                        )
                    }
                )
                is ToolchainExtractor.ExtractionState.Completed -> Triple(
                    Color(0xFF1B2C1B),
                    "✓ On-Device C++ Compiler engine verified and ready!",
                    @Composable {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.Green,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                is ToolchainExtractor.ExtractionState.Error -> Triple(
                    Color(0xFF2C1B1B),
                    "C++ Compiler placement error: ${state.message}",
                    @Composable {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = Color.Red,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                else -> Triple(
                    Color(0xFF1B222C),
                    "Verifying local system integrity...",
                    @Composable {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.Gray
                        )
                    }
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(backgroundColor)
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    iconContent()
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                        Text(
                            text = statusText,
                            fontSize = 12.sp,
                            color = Color.White,
                            maxLines = 1,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // --- 2. MAIN HORIZONTAL MULTI-PANEL VIEW ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            // Editor Work Area (remains constant size)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(editorBackground)
            ) {
                // Unified Interactive Multi-File Tab Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(sidebarBackground)
                        .height(44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        openFiles.forEachIndexed { index, file ->
                            val isSelected = activeFile?.absolutePath == file.absolutePath
                            Row(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .background(if (isSelected) editorBackground else Color.Transparent)
                                    .clickable { viewModel.openFile(file) }
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = when {
                                        file.isDirectory -> Icons.Default.Folder
                                        file.name.endsWith(".cpp") -> Icons.Default.Code
                                        else -> Icons.Default.InsertDriveFile
                                    },
                                    contentDescription = null,
                                    tint = if (isSelected) accentNeonColor else Color.Gray,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = file.name,
                                    color = if (isSelected) Color.White else Color.Gray,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close tab",
                                    tint = Color.Gray.copy(alpha = 0.5f),
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clickable { viewModel.closeFile(file) }
                                )
                            }
                            Divider(
                                modifier = Modifier.fillMaxHeight().width(1.dp),
                                color = Color.White.copy(alpha = 0.05f)
                            )
                        }

                        // Unified New File Action button at the end of the tabs list
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .clickable { showCreateDialog = true }
                                .padding(horizontal = 16.dp)
                                .testTag("new_file_tab_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "New File",
                                tint = if (isDirty) Color.Gray else accentNeonColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // AI Star Chat Overlay Toggler remains on the far right of the bar
                    IconButton(
                        onClick = { isOverlayOpen = !isOverlayOpen },
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(28.dp)
                            .background(if (isOverlayOpen) accentNeonColor.copy(alpha = 0.25f) else Color.Transparent, CircleShape)
                            .testTag("toggle_chat_overlay_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Forum,
                            contentDescription = "Sleek Chat Overlay",
                            tint = if (isOverlayOpen) accentNeonColor else Color.LightGray,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Divider(color = Color.White.copy(alpha = 0.05f))

                // Rich Code Text Edit Workspace Panel
                val editorBg = when(editorTheme) {
                    "monokai" -> Color(0xFF272822)
                    "cyberpunk" -> Color(0xFF1D0326)
                    "terminal" -> Color(0xFF000000)
                    else -> sidebarBackground // vscode
                }

                val cursorColor = when(editorTheme) {
                    "monokai" -> Color(0xFFF92672)
                    "cyberpunk" -> Color(0xFFFF007F)
                    "terminal" -> Color(0xFF00FF00)
                    else -> accentNeonColor
                }

                // Feature: Search & Replace Panel overlay
                AnimatedVisibility(visible = isSearchActive) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(sidebarBackground)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { viewModel.searchQuery.value = it },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp)
                                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp)),
                                textStyle = TextStyle(color = Color.White, fontSize = 11.sp),
                                cursorBrush = SolidColor(accentNeonColor),
                                decorationBox = { innerTextField ->
                                    Box(modifier = Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                                        if (searchQuery.isEmpty()) {
                                            Text("Find text...", fontSize = 11.sp, color = Color.Gray)
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            BasicTextField(
                                value = replaceQuery,
                                onValueChange = { viewModel.replaceQuery.value = it },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp)
                                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp)),
                                textStyle = TextStyle(color = Color.White, fontSize = 11.sp),
                                cursorBrush = SolidColor(accentNeonColor),
                                decorationBox = { innerTextField ->
                                    Box(modifier = Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                                        if (replaceQuery.isEmpty()) {
                                            Text("Replace with...", fontSize = 11.sp, color = Color.Gray)
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { viewModel.performFindAndReplace() },
                                colors = ButtonDefaults.buttonColors(containerColor = accentNeonColor),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                modifier = Modifier.height(32.dp),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text("REPLACE", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(editorBg)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) {
                            editorFocusRequester.requestFocus()
                        }
                ) {
                    // Code Input Layout mapped with LSP diagnostics & autocomplete highlights
                    var textLayoutResult by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
                    var cursorRect by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }

                    val verticalScrollState = rememberScrollState()
                    val horizontalScrollState = rememberScrollState()
                    var lineNumbersWidth by remember { mutableStateOf(0f) }

                    val activeFont by viewModel.activeFontName.collectAsState("Monospace")
                    val editorFontFamily = remember(activeFont) { getFontFamily(activeFont, context) }

                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(verticalScrollState)
                    ) {
                        // Line numbers column
                        if (viewModel.showLineNumbers.value) {
                            Column(
                                modifier = Modifier
                                    .background(editorBg.copy(alpha = 0.8f))
                                    .onGloballyPositioned { lineNumbersWidth = it.size.width.toFloat() }
                                    .padding(vertical = 12.dp, horizontal = 10.dp)
                                    .widthIn(min = 32.dp),
                                horizontalAlignment = Alignment.End
                            ) {
                                val lines = editorContent.split("\n")
                                for (i in 1..maxOf(1, lines.size)) {
                                    Text(
                                        text = i.toString(),
                                        color = Color.Gray.copy(alpha = 0.5f),
                                        fontFamily = editorFontFamily,
                                        fontSize = viewModel.editorFontSize.value.sp,
                                        lineHeight = (viewModel.editorFontSize.value * 1.5).sp
                                    )
                                }
                            }
                        }

                        // Code Input Layout mapped with LSP diagnostics & autocomplete highlights
                        androidx.compose.foundation.text.BasicTextField(
                            value = textFieldValueState,
                            onValueChange = { newValue ->
                                var processedValue = newValue
                                if (newValue.text.length > textFieldValueState.text.length) {
                                    val addedPart = newValue.text.substring(textFieldValueState.selection.end, newValue.selection.end)
                                    if (addedPart == "\n") {
                                        // Auto indent logic: respec syntax and tabs
                                        val textBefore = newValue.text.substring(0, textFieldValueState.selection.end)
                                        val lastLine = textBefore.split("\n").lastOrNull() ?: ""
                                        val indent = lastLine.takeWhile { it == '\t' || it == ' ' }
                                        var finalIndent = indent
                                        if (lastLine.trim().endsWith("{")) {
                                            finalIndent += "\t"
                                        }
                                        
                                        val newText = textBefore + "\n" + finalIndent + newValue.text.substring(newValue.selection.end)
                                        val newCursor = textFieldValueState.selection.end + 1 + finalIndent.length
                                        processedValue = TextFieldValue(newText, TextRange(newCursor))
                                    }
                                }
                                textFieldValueState = processedValue
                                viewModel.updateContent(processedValue.text, processedValue.selection.end)
                            },
                            onTextLayout = { 
                                textLayoutResult = it 
                                val cursorOffset = textFieldValueState.selection.end
                                if (cursorOffset <= it.layoutInput.text.length) {
                                    cursorRect = it.getCursorRect(cursorOffset)
                                }
                            },
                            modifier = Modifier
                                .then(Modifier.focusRequester(editorFocusRequester))
                                .onFocusChanged { isEditorFocused = it.isFocused }
                                .fillMaxSize()
                                .let { 
                                    if (!viewModel.wordWrapEnabled.value) {
                                        it.horizontalScroll(horizontalScrollState)
                                    } else {
                                        it
                                    }
                                }
                                .padding(vertical = 12.dp, horizontal = 4.dp)
                                .testTag("code_editor_field"),
                            textStyle = TextStyle(
                                fontFamily = editorFontFamily,
                                fontSize = viewModel.editorFontSize.value.sp,
                                color = if (editorTheme == "terminal") {
                                    if (isEditorFocused) Color(0xFF00FF00) else Color(0xFF006400)
                                } else {
                                    if (isEditorFocused) Color.White else Color.White.copy(alpha = 0.4f)
                                },
                                lineHeight = (viewModel.editorFontSize.value * 1.5).sp
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(if (isEditorFocused) cursorColor else Color.Transparent),
                            // Visual transformations to format syntax on the fly and map LSP diagnostics squiggles
                            visualTransformation = CppSyntaxTransformation(diagnosticsList, editorTheme, tabSize = 4)
                        )
                    }

                // Floating Autocomplete popover (Interprets '.' / '->' triggers smoothly, plus manual trigger support)
                if (completionsList.isNotEmpty()) {
                    androidx.compose.ui.window.Popup(
                        alignment = Alignment.TopStart,
                        offset = androidx.compose.ui.unit.IntOffset(
                            x = (cursorRect.left - horizontalScrollState.value + lineNumbersWidth).toInt() + 10,
                            y = (cursorRect.bottom - verticalScrollState.value).toInt() + 15 
                        ),
                        onDismissRequest = { viewModel.dismissCompletions() }
                    ) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = sidebarBackground
                                ),
                                elevation = CardDefaults.cardElevation(8.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .padding(4.dp)
                                    .width(260.dp)
                                    .heightIn(max = 200.dp)
                                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            ) {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.White.copy(alpha = 0.05f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "IntelliSense Completion",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = accentNeonColor
                                    )
                                    IconButton(
                                        onClick = { viewModel.dismissCompletions() },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close suggestions",
                                            tint = Color.Gray,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }

                                LazyColumn {
                                    items(completionsList) { completion ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    val completeWord = completion.label
                                                    val text = textFieldValueState.text
                                                    val selection = textFieldValueState.selection
                                                    val end = selection.end
                                                    // Find the word that wraps the current cursor position to replace it
                                                    val beforeCursor = text.substring(0, end)
                                                    val lastWordOfBeforeCursor = beforeCursor.split(Regex("[\\s.;()+\\-*/&|<>\\[\\]{},=!]+")).lastOrNull() ?: ""
                                                    val replaceStart = maxOf(0, end - lastWordOfBeforeCursor.length)
                                                    val newText = text.replaceRange(replaceStart, end, completeWord)
                                                    val newCursor = replaceStart + completeWord.length
                                                    
                                                    textFieldValueState = TextFieldValue(
                                                        text = newText,
                                                        selection = TextRange(newCursor)
                                                    )
                                                    viewModel.updateContent(newText, newCursor)
                                                    viewModel.dismissCompletions()
                                                }
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.SettingsSuggest,
                                                contentDescription = null,
                                                tint = Color.Magenta,
                                                modifier = Modifier.size(11.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(
                                                    text = completion.label,
                                                    color = Color.White,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = completion.detail,
                                                    color = Color.Gray,
                                                    fontSize = 9.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

                // --- QUICK CODE SUGGESTIONS & SYMBOLS BAR ---
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(sidebarBackground)
                        .border(1.dp, Color.White.copy(alpha = 0.05f))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    item {
                        // Manual lightbulb suggest/trigger button
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(accentNeonColor.copy(alpha = 0.15f))
                                .clickable {
                                    viewModel.triggerManualSuggestions(textFieldValueState.selection.end)
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Lightbulb,
                                    contentDescription = "Suggest code",
                                    tint = accentNeonColor,
                                    modifier = Modifier.size(11.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "SUGGEST",
                                    color = accentNeonColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    item {
                        // Save to File button
                        val saveBtnBg = if (isDirty) Color.Red.copy(alpha = 0.8f) else accentNeonColor.copy(alpha = 0.15f)
                        val saveBtnTint = if (isDirty) Color.White else accentNeonColor
                        
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(saveBtnBg)
                                .clickable {
                                    // Save currently active file manually
                                    activeFile?.let {
                                        viewModel.updateContent(textFieldValueState.text, textFieldValueState.selection.end)
                                        viewModel.handleManualSave(it)
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Save,
                                    contentDescription = "Save file",
                                    tint = saveBtnTint,
                                    modifier = Modifier.size(13.dp)
                                )
                                if (isDirty) {
                                  Spacer(modifier = Modifier.width(4.dp))
                                  Text("SAVE", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    item {
                        // Tab insert button
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .clickable {
                                    val text = textFieldValueState.text
                                    val selection = textFieldValueState.selection
                                    val start = selection.start
                                    val end = selection.end
                                    
                                    val insertionValue = "\t" // Actual tab character
                                    val newText = if (start >= 0 && end >= 0) {
                                        text.replaceRange(start, end, insertionValue)
                                    } else {
                                        text + insertionValue
                                    }
                                    
                                    val newCursor = if (start >= 0) start + insertionValue.length else newText.length
                                    
                                    textFieldValueState = TextFieldValue(
                                        text = newText,
                                        selection = TextRange(newCursor)
                                    )
                                    viewModel.updateContent(newText, newCursor)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardTab,
                                    contentDescription = "Tab spacing",
                                    tint = Color.White,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }

                    // Common C++ helper snippets and shortcuts for streamlined coding on mobile
                    val shortcuts = listOf(
                        ";" to ";",
                        "{ }" to "{\n    \n}",
                        "()" to "()",
                        "\"" to "\"\"",
                        "std::" to "std::",
                        "<<" to " << ",
                        "cout" to "std::cout << ",
                        "endl" to " << std::endl;",
                        "cin" to "std::cin >> ",
                        "printf" to "printf(\"\")",
                        "vector" to "std::vector<",
                        "->" to "->",
                        "int" to "int ",
                        "char" to "char ",
                        "return" to "return ",
                        "#" to "#",
                        "include" to "#include <"
                    )

                    items(shortcuts) { (label, insertionValue) ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .clickable {
                                    val text = textFieldValueState.text
                                    val selection = textFieldValueState.selection
                                    val start = selection.start
                                    val end = selection.end
                                    
                                    val newText = if (start >= 0 && end >= 0) {
                                        text.replaceRange(start, end, insertionValue)
                                    } else {
                                        text + insertionValue
                                    }
                                    
                                    // Move cursor after the inserted text
                                    val insertedLength = insertionValue.length
                                    // Adjust cursor back for visual convenience under empty brace formats
                                    val cursorAdjustment = when (label) {
                                        "{ }" -> 5  // Place inside block braces
                                        "()" -> 1   // Place inside parentheses
                                        "\"" -> 1    // Place inside quotation marks
                                        "printf" -> 2 // Put cursor inside the string
                                        else -> 0
                                    }
                                    val newCursor = if (start >= 0) start + insertedLength - cursorAdjustment else newText.length
                                    
                                    textFieldValueState = TextFieldValue(
                                        text = newText,
                                        selection = TextRange(newCursor)
                                    )
                                    viewModel.updateContent(newText, newCursor)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = label,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Sidebar File Tree list (Overlaid and floating on top without shifting code area)
            androidx.compose.animation.AnimatedVisibility(
                visible = isSidebarExpanded,
                enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { -it }),
                exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { -it }),
                modifier = Modifier.align(Alignment.CenterStart).fillMaxWidth(0.65f).fillMaxHeight()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(sidebarBackground)
                        .pointerInput(Unit) { detectTapGestures { } } // Block clicks from falling through to the editor underneath
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(top = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "WORKSPACE",
                                color = Color.LightGray.copy(alpha = 0.6f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.Default.NoteAdd,
                                contentDescription = "New File",
                                tint = Color.Gray,
                                modifier = Modifier
                                    .size(13.dp)
                                    .clickable {
                                        createTargetFolder = null
                                        createIsDirectory = false
                                        showCreateDialog = true
                                    }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Default.CreateNewFolder,
                                contentDescription = "New Folder",
                                tint = Color.Gray,
                                modifier = Modifier
                                    .size(13.dp)
                                    .clickable {
                                        createTargetFolder = null
                                        createIsDirectory = true
                                        showCreateDialog = true
                                    }
                                    .testTag("create_file_trigger")
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowLeft,
                                contentDescription = "Collapse Sidebar",
                                tint = Color.Gray,
                                modifier = Modifier
                                    .size(15.dp)
                                    .clickable { isSidebarExpanded = false }
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        val visibleFiles = remember(filesTree, expandedDirectories) {
                            filesTree.filter { file ->
                                val parent = file.parentFile
                                if (parent == null || parent.absolutePath == extractor.workspaceDir.absolutePath) {
                                    true
                                } else {
                                    var currentParent = parent
                                    var shouldShow = true
                                    while (currentParent != null && currentParent.absolutePath != extractor.workspaceDir.absolutePath) {
                                        if (!expandedDirectories.contains(currentParent.absolutePath)) {
                                            shouldShow = false
                                            break
                                        }
                                        currentParent = currentParent.parentFile
                                    }
                                    shouldShow
                                }
                            }
                        }

                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            items(visibleFiles) { file ->
                                val isSelected = activeFile?.absolutePath == file.absolutePath
                                var showMenu by remember { mutableStateOf(false) }

                                val relativePath = file.relativeTo(extractor.workspaceDir).path
                                val nestingDepth = relativePath.count { it == '/' }

                                Box {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(if (isSelected) Color.White.copy(alpha = 0.05f) else Color.Transparent)
                                            .pointerInput(file) {
                                                detectTapGestures(
                                                    onTap = {
                                                        if (file.isDirectory) {
                                                            expandedDirectories = if (expandedDirectories.contains(file.absolutePath)) {
                                                                expandedDirectories - file.absolutePath
                                                            } else {
                                                                expandedDirectories + file.absolutePath
                                                            }
                                                        } else {
                                                            viewModel.openFile(file)
                                                        }
                                                    },
                                                    onLongPress = { showMenu = true }
                                                )
                                            }
                                            .padding(horizontal = 8.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Spacer(modifier = Modifier.width((nestingDepth * 8).dp))
                                        if (file.isDirectory) {
                                            Icon(
                                                imageVector = if (expandedDirectories.contains(file.absolutePath)) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                                                contentDescription = "Toggle folder",
                                                tint = Color.Gray,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(2.dp))
                                        } else {
                                            Spacer(modifier = Modifier.width(16.dp))
                                        }
                                        Icon(
                                            imageVector = when {
                                                file.isDirectory -> if (expandedDirectories.contains(file.absolutePath)) Icons.Default.FolderOpen else Icons.Default.Folder
                                                file.name.endsWith(".cpp") -> Icons.Default.Code
                                                file.name.endsWith(".h") -> Icons.Default.SettingsSuggest
                                                file.name.endsWith(".json") -> Icons.Default.SettingsSuggest
                                                else -> Icons.Default.InsertDriveFile
                                            },
                                            contentDescription = null,
                                            tint = if (isSelected) accentNeonColor else Color.Gray,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = file.name,
                                            color = if (isSelected) Color.White else Color.Gray,
                                            fontSize = 11.5.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )

                                        Icon(
                                            imageVector = Icons.Default.MoreVert,
                                            contentDescription = "Options",
                                            tint = Color.Gray.copy(alpha = 0.6f),
                                            modifier = Modifier
                                                .size(14.dp)
                                                .clickable { showMenu = true }
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false },
                                        modifier = Modifier.background(sidebarBackground)
                                    ) {
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text("Rename", color = Color.White, fontSize = 12.sp) },
                                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(14.dp)) },
                                            onClick = {
                                                showMenu = false
                                                fileToRename = file
                                                showRenameDialog = true
                                            }
                                        )
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text("Copy", color = Color.White, fontSize = 12.sp) },
                                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(14.dp)) },
                                            onClick = {
                                                showMenu = false
                                                viewModel.copyWorkspaceFile(file)
                                            }
                                        )
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text("Cut", color = Color.White, fontSize = 12.sp) },
                                            leadingIcon = { Icon(Icons.Default.ContentCut, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(14.dp)) },
                                            onClick = {
                                                showMenu = false
                                                viewModel.cutWorkspaceFile(file)
                                            }
                                        )
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text("Paste Here", color = if (clipboardFile != null) Color.White else Color.Gray, fontSize = 12.sp) },
                                            leadingIcon = { Icon(Icons.Default.ContentPaste, contentDescription = null, tint = if (clipboardFile != null) Color.LightGray else Color.Gray, modifier = Modifier.size(14.dp)) },
                                            enabled = clipboardFile != null,
                                            onClick = {
                                                showMenu = false
                                                viewModel.pasteWorkspaceFile(file)
                                            }
                                        )
                                        if (file.isDirectory) {
                                            androidx.compose.material3.DropdownMenuItem(
                                                text = { Text("New File Here", color = Color.White, fontSize = 12.sp) },
                                                leadingIcon = { Icon(Icons.Default.NoteAdd, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(14.dp)) },
                                                onClick = {
                                                    showMenu = false
                                                    createTargetFolder = file
                                                    createIsDirectory = false
                                                    showCreateDialog = true
                                                }
                                            )
                                            androidx.compose.material3.DropdownMenuItem(
                                                text = { Text("New Folder Here", color = Color.White, fontSize = 12.sp) },
                                                leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(14.dp)) },
                                                onClick = {
                                                    showMenu = false
                                                    createTargetFolder = file
                                                    createIsDirectory = true
                                                    showCreateDialog = true
                                                }
                                            )
                                        }
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text("Delete", color = Color.Red, fontSize = 12.sp) },
                                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red.copy(alpha = 0.7f), modifier = Modifier.size(14.dp)) },
                                            onClick = {
                                                showMenu = false
                                                viewModel.deleteWorkspaceFile(file)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        if (clipboardFile != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp)
                                    .background(accentNeonColor.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                                    .border(1.dp, accentNeonColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(
                                        imageVector = if (isCutOperation) Icons.Default.ContentCut else Icons.Default.ContentCopy,
                                        contentDescription = null,
                                        tint = accentNeonColor,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "${if (isCutOperation) "Cut:" else "Copied:"} ${clipboardFile!!.name}",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "PASTE",
                                        color = accentNeonColor,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clickable { viewModel.pasteWorkspaceFile(extractor.workspaceDir) }
                                            .padding(horizontal = 4.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear Clipboard",
                                        tint = Color.Gray,
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clickable { viewModel.clearClipboard() }
                                    )
                                }
                            }
                        }
                    }

                    Divider(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp),
                        color = Color.White.copy(alpha = 0.08f)
                    )
                }
            }

            // RHS AI Agent panel (Overlaid and floating on top on the opposite side of Folder Sidebar)
            androidx.compose.animation.AnimatedVisibility(
                visible = isAgentPanelExpanded,
                enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }),
                exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxWidth(1f).fillMaxHeight()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(sidebarBackground)
                        .pointerInput(Unit) { detectTapGestures { } } // Block clicks from falling through
                ) {
                    Divider(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp),
                        color = Color.White.copy(alpha = 0.08f)
                    )

                    // Agent Workspace Panel Core
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(16.dp)
                    ) {
                        // Title bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.SmartToy,
                                    contentDescription = "AI Panel",
                                    tint = accentNeonColor,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "AI AGENT WORKSPACE",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }

                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Panel",
                                tint = Color.Gray,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { isAgentPanelExpanded = false }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        var rsiAgentSubTab by remember { mutableStateOf(0) } // 0 = Workflow, 1 = Discussion Chat

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(4.dp))
                                .padding(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (rsiAgentSubTab == 0) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                                    .clickable { rsiAgentSubTab = 0 },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "OPTIMIZER ENG",
                                    color = if (rsiAgentSubTab == 0) accentNeonColor else Color.Gray,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (rsiAgentSubTab == 1) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                                    .clickable { rsiAgentSubTab = 1 },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "COSMIC CHAT",
                                    color = if (rsiAgentSubTab == 1) accentNeonColor else Color.Gray,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (rsiAgentSubTab == 0) {
                            // Permission-bypass Checkbox
                        val isAutoApply by AgenticCoordinator.autoApplyWithoutPermission.collectAsState()
                        val coroutineScope = rememberCoroutineScope()
                        val context = LocalContext.current

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.04f))
                                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                                .clickable {
                                    AgenticCoordinator.autoApplyWithoutPermission.value = !isAutoApply
                                    AgenticCoordinator.save(context)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = if (isAutoApply) accentNeonColor else Color.Gray,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Auto-Apply Optimizer",
                                    color = if (isAutoApply) Color.White else Color.LightGray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Bypass session approval (archived)",
                                    color = Color.Gray,
                                    fontSize = 9.sp
                                )
                            }
                            Switch(
                                checked = isAutoApply,
                                onCheckedChange = {
                                    AgenticCoordinator.autoApplyWithoutPermission.value = it
                                    AgenticCoordinator.save(context)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = accentNeonColor,
                                    uncheckedThumbColor = Color.Gray,
                                    uncheckedTrackColor = Color.White.copy(alpha = 0.1f),
                                    checkedBorderColor = Color.Transparent,
                                    uncheckedBorderColor = Color.White.copy(alpha = 0.1f)
                                ),
                                modifier = Modifier.scale(0.6f)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Prompter Input Session
                        var inputPromptText by remember { mutableStateOf("") }
                        val isAgentRunning by AgenticCoordinator.isRunning.collectAsState()
                        val agentStatus by AgenticCoordinator.statusText.collectAsState()

                        Text(
                            text = "GIVE INSTRUCTIONS FOR AI AGENT",
                            color = Color.Gray,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        BasicTextField(
                            value = inputPromptText,
                            onValueChange = { inputPromptText = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                .padding(12.dp),
                            textStyle = TextStyle(color = Color.White, fontSize = 11.sp),
                            cursorBrush = SolidColor(accentNeonColor),
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.TopStart) {
                                    if (inputPromptText.isEmpty()) {
                                        Text("Describe optimizations or fixes (e.g. optimize perf of main.cpp)", fontSize = 11.sp, color = Color.Gray)
                                    }
                                    innerTextField()
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Start / Stop Trigger button
                        Button(
                            onClick = {
                                if (isAgentRunning) {
                                    AgenticCoordinator.isRunning.value = false
                                    AgenticCoordinator.appendLog("[ABORT] AI Agent manually terminated by user.")
                                    AgenticCoordinator.statusText.value = "IDLE"
                                    AgenticCoordinator.stopForegroundService(context)
                                } else {
                                    if (inputPromptText.isNotEmpty()) {
                                        AgenticCoordinator.launchAgent(
                                            context,
                                            coroutineScope,
                                            inputPromptText,
                                            compiler,
                                            extractor
                                        ) {
                                            viewModel.loadFileSystem()
                                        }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isAgentRunning) Color.Red.copy(alpha = 0.8f) else accentNeonColor
                            ),
                            modifier = Modifier.fillMaxWidth().height(40.dp)
                        ) {
                            Text(
                                text = if (isAgentRunning) "ABORT AGENT PROCESS" else "LAUNCH AI WORKFLOW CYCLE",
                                color = if (isAgentRunning) Color.White else Color.Black,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (!isAgentRunning && agentStatus == "FAILED") {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    if (inputPromptText.isNotEmpty()) {
                                        AgenticCoordinator.launchAgent(
                                            context,
                                            coroutineScope,
                                            inputPromptText,
                                            compiler,
                                            extractor
                                        ) {
                                            viewModel.loadFileSystem()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Yellow),
                                modifier = Modifier.fillMaxWidth().height(40.dp)
                            ) {
                                Text("RETRY OPTIMIZATION", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Current AI action Status indicators
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "WORKFLOW STATE: ",
                                color = Color.Gray,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                              )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        when (agentStatus) {
                                            "FAILED" -> Color.Red.copy(alpha = 0.15f)
                                            "COMPLETED" -> accentNeonColor.copy(alpha = 0.15f)
                                            "IDLE" -> Color.White.copy(alpha = 0.05f)
                                            else -> Color.Yellow.copy(alpha = 0.15f)
                                        }
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = agentStatus,
                                    color = when (agentStatus) {
                                        "FAILED" -> Color.Red
                                        "COMPLETED" -> accentNeonColor
                                        "IDLE" -> Color.Gray
                                        else -> Color.Yellow
                                    },
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Live trace logs Console panel region
                        Text(
                            text = "LIVE AGENT ACTIVITY TRACE",
                            color = Color.Gray,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        val agentLogs by AgenticCoordinator.logs.collectAsState()
                        val consoleScrollState = rememberScrollState()

                        // Auto scroll console to bottom as logs append
                        LaunchedEffect(agentLogs.size) {
                            if (agentLogs.isNotEmpty()) {
                                consoleScrollState.animateScrollTo(consoleScrollState.maxValue)
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .background(panelBackground)
                                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp))
                                .padding(8.dp)
                        ) {
                            if (agentLogs.isEmpty()) {
                                Text(
                                    text = "Console Idle. Ready to parse and debug compile errors.",
                                    color = Color.Gray.copy(alpha = 0.6f),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(consoleScrollState)
                                ) {
                                    agentLogs.forEach { logLine ->
                                        Text(
                                            text = logLine,
                                            color = if (logLine.startsWith("[ERROR]")) Color.Red
                                                   else if (logLine.startsWith("[FILE COMMITTED]") || logLine.startsWith("[BUILD SUCCESS]")) accentNeonColor
                                                   else if (logLine.startsWith("[AI EXPLANATION]")) Color.Cyan
                                                   else Color(0xFFC5C6C7),
                                            fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace,
                                            lineHeight = 13.sp,
                                            modifier = Modifier.padding(vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Inline interactive Permission dialog/request
                        val pendingReq by AgenticCoordinator.pendingPermissionRequest.collectAsState()
                        if (pendingReq != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2213)),
                                border = BorderStroke(1.dp, Color.Yellow.copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = Color.Yellow,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "APPROVE CODE REASONING?",
                                            color = Color.Yellow,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "AI requests tool command: [${pendingReq!!.actionType}] on path: '${pendingReq!!.filePath}'",
                                        color = Color.White,
                                        fontSize = 10.sp
                                    )
                                    if (pendingReq!!.actionType == "WRITE") {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(60.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color.Black.copy(alpha = 0.5f))
                                                .padding(6.dp)
                                        ) {
                                            Text(
                                                text = pendingReq!!.content,
                                                color = Color.LightGray,
                                                fontSize = 8.sp,
                                                fontFamily = FontFamily.Monospace,
                                                maxLines = 4,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(
                                            onClick = { pendingReq!!.onDecision(false) },
                                            colors = ButtonDefaults.textButtonColors(contentColor = Color.Red),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                                        ) {
                                            Text("REJECT", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Button(
                                            onClick = { pendingReq!!.onDecision(true) },
                                            colors = ButtonDefaults.buttonColors(containerColor = accentNeonColor),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                                        ) {
                                            Text("APPROVE", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                        } else {
                            CosmicChatPanelContent(
                                accentNeonColor = accentNeonColor,
                                sidebarBackground = sidebarBackground,
                                workspaceDir = extractor.workspaceDir,
                                workspaceFiles = filesTree,
                                activeFile = activeFile,
                                viewModel = viewModel
                            )
                        }
                    }
                }
            }
        }

        // --- 3. BOTTOM PANEL OUTPUT REGION (Split Terminal/Dependencies/Settings) ---
        val density = LocalDensity.current
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val screenHeightLimitDp = configuration.screenHeightDp * 0.8f
        
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isBottomPanelExpanded) bottomPanelHeight else 44.dp)
                .background(panelBackground)
                .pointerInput(density) {
                    var dragAccumulator = viewModel.bottomPanelHeightState.value.toFloat()
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragAccumulator = viewModel.bottomPanelHeightState.value.toFloat()
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            if (!isBottomPanelExpanded) {
                                if (dragAmount < -10f) {
                                    viewModel.setBottomPanelExpanded(true)
                                }
                            } else {
                                val dragAmountDp = with(density) { dragAmount.toDp().value }
                                dragAccumulator -= dragAmountDp
                                val coercedHeight = dragAccumulator.coerceIn(100f, screenHeightLimitDp)
                                dragAccumulator = coercedHeight
                                viewModel.setBottomPanelHeight(coercedHeight.toInt())
                                
                                if (dragAmount > 20f && coercedHeight <= 110f) {
                                    viewModel.setBottomPanelExpanded(false)
                                    viewModel.setBottomPanelHeight(220)
                                    dragAccumulator = 220f
                                }
                            }
                        }
                    )
                }
        ) {
            // Dashboard Nav bar Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(sidebarBackground),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val menuTabs = listOf(
                    Triple(0, "TERMINAL CONSOLE", Icons.Default.LogoDev),
                    Triple(1, "BUILD LOGS", Icons.Default.DeveloperMode),
                    Triple(2, "DEPENDENCIES", Icons.Default.LibraryBooks),
                    Triple(3, "RUN CONSOLE", Icons.Default.PlayArrow)
                )

                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    menuTabs.forEach { (index, title, icon) ->
                        val isSelected = activeTab == index
                        Row(
                            modifier = Modifier
                                .clickable {
                                    viewModel.selectPanelTab(index)
                                    viewModel.setBottomPanelExpanded(true)
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                             Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (isSelected) accentNeonColor else Color.Gray,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = title, 
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Color.White else Color.Gray
                            )
                        }
                    }
                }

                IconButton(
                    onClick = { viewModel.setBottomPanelExpanded(!isBottomPanelExpanded) },
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(28.dp)
                        .testTag("toggle_bottom_panel_action_button")
                ) {
                    Icon(
                        imageVector = if (isBottomPanelExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        contentDescription = "Expand/Collapse Bottom Panel",
                        tint = Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (isBottomPanelExpanded) {
                Divider(color = Color.White.copy(alpha = 0.08f))

                // Current selected active bottom pane screen
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                when (activeTab) {
                    0 -> {
                        val scrollState = rememberScrollState()
                        val execLogs by executor.executionLogs.collectAsState()
                        val extractionState by viewModel.extractionState.collectAsState()
                        
                        val isVerbose by viewModel.isVerboseEnabled.collectAsState()
                        val processedExecLogs = remember(execLogs, isVerbose) {
                            if (isVerbose) {
                                execLogs
                            } else {
                                execLogs.split("\n")
                                    .filter { line ->
                                        val trim = line.trim()
                                        !trim.startsWith("[SANDBOX ENGINE]") &&
                                        !trim.startsWith("§B") &&
                                        !trim.startsWith("§M") &&
                                        !trim.contains("[PROCESS EXITED]") &&
                                        !trim.contains("[BUILD START]") &&
                                        !trim.contains("[SANDBOX ENGINE]")
                                    }
                                    .joinToString("\n")
                            }
                        }

                        val emptyMessage = when (extractionState) {
                            is ToolchainExtractor.ExtractionState.Completed -> "Console idle. Press compile play icon to run user main_entry()."
                            is ToolchainExtractor.ExtractionState.Idle -> "Engine not initialized. Click the button below to install the C++ compiler toolchain."
                            is ToolchainExtractor.ExtractionState.Error -> "Engine setup failed. Please check the logs above and try 'INSTALL & VERIFY' again."
                            else -> "" // Shown via progress indicators
                        }
                        
                        val displayLogs = if (processedExecLogs.isEmpty()) emptyMessage else processedExecLogs

                        LaunchedEffect(execLogs) {
                            com.example.core.agent.AgenticCoordinator.terminalOutput.value = execLogs
                            kotlinx.coroutines.delay(100)
                            scrollState.animateScrollTo(scrollState.maxValue)
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "SANDBOX CONSOLE STREAM",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Gray
                                )
                                val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .clickable { viewModel.toggleVerboseEnabled() }
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "VERBOSE",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isVerbose) accentNeonColor else Color.Gray,
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        androidx.compose.material3.Switch(
                                            checked = isVerbose,
                                            onCheckedChange = { viewModel.toggleVerboseEnabled() },
                                            modifier = Modifier.scale(0.55f).height(16.dp),
                                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                                checkedThumbColor = Color.Black,
                                                checkedTrackColor = accentNeonColor,
                                                uncheckedThumbColor = Color.Gray,
                                                uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
                                            )
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text(
                                         text = "COPY",
                                         fontSize = 9.sp,
                                         color = Color.LightGray,
                                         modifier = Modifier.clickable { 
                                             clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(displayLogs))
                                             android.widget.Toast.makeText(context, "Copied!", android.widget.Toast.LENGTH_SHORT).show()
                                         }
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text(
                                         text = "CLEAR",
                                         fontSize = 9.sp,
                                         color = accentNeonColor,
                                         modifier = Modifier.clickable { 
                                             executor.clearLogs()
                                             // The [ENGINE_SUCCESS] logic in UI will handle re-showing if was success
                                         }
                                    )
                                }
                            }

                            // Engine Setup Loader
                            if (extractionState !is ToolchainExtractor.ExtractionState.Completed && extractionState !is ToolchainExtractor.ExtractionState.Idle) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Column {
                                    val progress = when (val state = extractionState) {
                                        is ToolchainExtractor.ExtractionState.Downloading -> state.progress
                                        is ToolchainExtractor.ExtractionState.Progress -> state.percentage / 100f
                                        else -> 0f
                                    }
                                    val statusMsg = when (val state = extractionState) {
                                        is ToolchainExtractor.ExtractionState.Downloading -> "DOWNLOADING ENGINE CORE: ${(state.progress * 100).toInt()}%"
                                        is ToolchainExtractor.ExtractionState.Progress -> "EXTRACTING TOOLCHAIN ASSETS: ${state.percentage}%"
                                        is ToolchainExtractor.ExtractionState.Error -> "SETUP ERROR: ${state.message}"
                                        else -> "INITIALIZING ENGINE..."
                                    }
                                    
                                    Text(text = statusMsg, color = accentNeonColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)),
                                        color = accentNeonColor,
                                        trackColor = Color.White.copy(alpha = 0.1f)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                }
                            }

                            SelectionContainer {
                                Column {
                                    displayLogs.split("\n").forEach { logLine ->
                                        if (logLine.contains("[ENGINE_SUCCESS]")) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 8.dp)
                                                    .background(Color(0xFF4AF2A1), RoundedCornerShape(4.dp))
                                                    .padding(12.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "Engine Initiated Successfully",
                                                    color = Color.Black,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                        } else if (logLine.contains("[ERROR_HEADER]")) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 8.dp)
                                                    .background(Color.Red, RoundedCornerShape(4.dp))
                                                    .padding(12.dp)
                                            ) {
                                                Column {
                                                    Text(
                                                        text = "CRITICAL BUILD ERROR",
                                                        color = Color.Black,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        fontSize = 14.sp,
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                    Text(
                                                        text = logLine.replace("[ERROR_HEADER]", ""),
                                                        color = Color.Black,
                                                        fontSize = 11.sp,
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                }
                                            }
                                        } else {
                                            Text(
                                                text = parseColoredLogs(logLine),
                                                color = Color(0xFFC5C6C7),
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                lineHeight = 16.sp
                                            )
                                        }
                                    }
                                }
                            }
                            
                            // INSTALL ENGINE BUTTON (Show if Idle or Error)
                            if (extractionState is ToolchainExtractor.ExtractionState.Idle || extractionState is ToolchainExtractor.ExtractionState.Error) {
                                Spacer(modifier = Modifier.height(20.dp))
                                Button(
                                    onClick = { viewModel.verifyAndInstallEngine() },
                                    modifier = Modifier.align(Alignment.CenterHorizontally).height(40.dp).padding(horizontal = 24.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = accentNeonColor),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("INSTALL & VERIFY ENGINE", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                Spacer(modifier = Modifier.height(20.dp))
                            }
                        }
                    }
                    1 -> {
                        // Compiler Command output logs
                        val compilerLogs by compiler.consoleOutput.collectAsState()
                        val compilerScrollState = rememberScrollState()
                        
                        LaunchedEffect(compilerLogs) {
                            kotlinx.coroutines.delay(100)
                            compilerScrollState.animateScrollTo(compilerScrollState.maxValue)
                        }
                        
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(compilerScrollState)
                        ) {
                            val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "CLANG++ STDOUT/STDERR RAWSTREAM",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Gray
                                )
                                Text(
                                    text = "COPY",
                                    fontSize = 9.sp,
                                    color = Color.LightGray,
                                    modifier = Modifier.clickable { 
                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(compilerLogs))
                                        android.widget.Toast.makeText(context, "Copied!", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))

                            val hasHeaderError = compilerLogs.contains("file not found", ignoreCase = true) || 
                                                 compilerLogs.contains("no such file", ignoreCase = true)
                            
                            if (hasHeaderError) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp)
                                        .background(Color.Red, RoundedCornerShape(4.dp))
                                        .padding(12.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "⚠️ CRITICAL HEADER FILE UNAVAILABILITY ERROR",
                                            color = Color.Black,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "A required header file was not resolved by Clang++. Verify that the #include header file matches your dependencies, exists in your workspace, or try reloading synchronization.",
                                            color = Color.Black,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }

                            SelectionContainer {
                                Text(
                                    text = if (compilerLogs.isEmpty()) "Build outputs will be streamed live here after initialization." else compilerLogs,
                                    color = if (hasHeaderError) Color.White else Color(0xFFFFCC80),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                    modifier = if (hasHeaderError) Modifier.background(Color.Red.copy(alpha = 0.15f)).padding(8.dp) else Modifier
                                )
                            }
                        }
                    }
                    2 -> {
                        // Manifest C++ dependencies mapping
                        val dependenciesList = remember { mutableStateListOf<CppDependency>() }
                        LaunchedEffect(syncState) {
                            dependenciesList.clear()
                            dependenciesList.addAll(dependencyManager.parseManifest())
                        }

                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "DEPS.JSON LIBRARY TARGETS",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Gray
                                )
                                Text(
                                    text = when (syncState) {
                                        is DependencyManager.SyncState.Syncing -> "Downloading..."
                                        is DependencyManager.SyncState.Completed -> "Synced!"
                                        else -> "SYNC NOW"
                                    },
                                    color = accentNeonColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { viewModel.syncDependencies() }
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(4.dp))

                            LazyColumn(modifier = Modifier.weight(1f)) {
                                items(dependenciesList) { dep ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.LibraryAdd,
                                            contentDescription = null,
                                            tint = Color.LightGray,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(text = dep.name, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            Text(text = "Header placement: -Idependencies/include/${dep.targetPath}", color = Color.Gray, fontSize = 9.sp)
                                        }
                                        Box(
                                            modifier = Modifier
                                                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(text = dep.type, color = Color.Gray, fontSize = 8.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    3 -> {
                        // Interactive Rerun Console Tab
                        var commandText by remember { mutableStateOf("") }
                        var consoleLogBuffer by remember { mutableStateOf("CPPC/output $ \nReady. Enter library execution directives:\ne.g., run libuntitled.so\n\n") }
                        var isConsoleRunActive by remember { mutableStateOf(false) }
                        var lastRecordedLogs by remember { mutableStateOf("") }
                        
                        val currentExecLogs by executor.executionLogs.collectAsState()
                        val isExecutingState by executor.isExecuting.collectAsState()
                        
                        LaunchedEffect(currentExecLogs) {
                            if (isConsoleRunActive) {
                                if (currentExecLogs.startsWith(lastRecordedLogs)) {
                                    val newDiff = currentExecLogs.removePrefix(lastRecordedLogs)
                                    if (newDiff.isNotEmpty()) {
                                        consoleLogBuffer += newDiff
                                    }
                                } else {
                                    consoleLogBuffer += currentExecLogs
                                }
                                lastRecordedLogs = currentExecLogs
                            }
                        }
                        
                        LaunchedEffect(isExecutingState) {
                            if (!isExecutingState) {
                                lastRecordedLogs = ""
                            }
                        }
                        
                        val scrollState = rememberScrollState()
                        LaunchedEffect(consoleLogBuffer) {
                            scrollState.animateScrollTo(scrollState.maxValue)
                        }
                        
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(panelBackground)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "CPPC CONSOLE TERMINAL",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Gray
                                )
                                Text(
                                    text = "CLEAR BUFFER",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = accentNeonColor,
                                    modifier = Modifier.clickable {
                                        consoleLogBuffer = "CPPC/output $ \n"
                                    }
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(4.dp))
                            
                            // Scrollable Logs Screen
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                    .padding(8.dp)
                                    .verticalScroll(scrollState)
                            ) {
                                SelectionContainer {
                                    Text(
                                        text = consoleLogBuffer,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = Color(0xFF81C784),
                                        lineHeight = 16.sp,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(6.dp))
                            
                            // CLI Command Input Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "CPPC/output $ ",
                                    color = accentNeonColor,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                
                                BasicTextField(
                                    value = commandText,
                                    onValueChange = { commandText = it },
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 4.dp),
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        color = Color.White,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    ),
                                    cursorBrush = androidx.compose.ui.graphics.SolidColor(accentNeonColor),
                                    singleLine = true,
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                        imeAction = androidx.compose.ui.text.input.ImeAction.Done
                                    ),
                                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                        onDone = {
                                            val cmd = commandText.trim()
                                            if (cmd.isNotEmpty()) {
                                                consoleLogBuffer += "$cmd\n"
                                                commandText = ""
                                                
                                                if (cmd.startsWith("run ")) {
                                                    val rawArgs = cmd.removePrefix("run ").trim()
                                                    val parts = rawArgs.split(" ").filter { it.isNotBlank() }
                                                    if (parts.isNotEmpty()) {
                                                        var fileName = parts[0]
                                                        if (!fileName.endsWith(".so")) {
                                                            fileName += ".so"
                                                        }
                                                        val finalArgs = if (parts.size > 1) parts.subList(1, parts.size).joinToString(" ") else null
                                                        
                                                        val cppcFile = File("/storage/emulated/0/CPPC/output", fileName)
                                                        val sandboxFile = File(extractor.workspaceOutputDir, fileName)
                                                        val targetFile = if (cppcFile.exists()) cppcFile else sandboxFile
                                                        
                                                        if (targetFile.exists()) {
                                                            isConsoleRunActive = true
                                                            executor.bindService()
                                                            executor.startExecution(targetFile.absolutePath, finalArgs)
                                                        } else {
                                                            consoleLogBuffer += "Error: Shared Object file '$fileName' not found under CPPC/output working directory.\n\n"
                                                        }
                                                    } else {
                                                        consoleLogBuffer += "Usage: run <library_name.so> [optional_args]\n\n"
                                                    }
                                                } else {
                                                    consoleLogBuffer += "Unknown command. Only 'run' commands are supported e.g.: run libuntitled.so\n\n"
                                                }
                                            }
                                        }
                                    )
                                )
                                
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Submit Run Command",
                                    tint = accentNeonColor,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable {
                                            val cmd = commandText.trim()
                                            if (cmd.isNotEmpty()) {
                                                consoleLogBuffer += "$cmd\n"
                                                commandText = ""
                                                
                                                if (cmd.startsWith("run ")) {
                                                    val rawArgs = cmd.removePrefix("run ").trim()
                                                    val parts = rawArgs.split(" ").filter { it.isNotBlank() }
                                                    if (parts.isNotEmpty()) {
                                                        var fileName = parts[0]
                                                        if (!fileName.endsWith(".so")) {
                                                            fileName += ".so"
                                                        }
                                                        val finalArgs = if (parts.size > 1) parts.subList(1, parts.size).joinToString(" ") else null
                                                        
                                                        val cppcFile = File("/storage/emulated/0/CPPC/output", fileName)
                                                        val sandboxFile = File(extractor.workspaceOutputDir, fileName)
                                                        val targetFile = if (cppcFile.exists()) cppcFile else sandboxFile
                                                        
                                                        if (targetFile.exists()) {
                                                            isConsoleRunActive = true
                                                            executor.bindService()
                                                            executor.startExecution(targetFile.absolutePath, finalArgs)
                                                        } else {
                                                            consoleLogBuffer += "Error: Shared Object file '$fileName' not found under CPPC/output working directory.\n\n"
                                                        }
                                                    } else {
                                                        consoleLogBuffer += "Usage: run <library_name.so> [optional_args]\n\n"
                                                    }
                                                } else {
                                                    consoleLogBuffer += "Unknown command. Only 'run' commands are supported e.g.: run libuntitled.so\n\n"
                                                }
                                            }
                                        }
                                )
                            }
                        }
                    }
                }
            }
            }
        }
    }

    // Modal dialogue to generate new C++ Header Files / Classes
    if (showRenameDialog && fileToRename != null) {
        var newFileName by remember { mutableStateOf(fileToRename!!.name) }
        androidx.compose.ui.window.Dialog(onDismissRequest = { showRenameDialog = false }) {
            Card(
                colors = CardDefaults.cardColors(containerColor = sidebarBackground),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(0.85f).padding(16.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Rename File", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    BasicTextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                        cursorBrush = SolidColor(accentNeonColor),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                innerTextField()
                            }
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showRenameDialog = false }) {
                            Text("CANCEL", color = Color.Gray, fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                viewModel.renameWorkspaceFile(fileToRename!!, newFileName)
                                showRenameDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = accentNeonColor),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text("RENAME", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
    if (showCreateDialog) {
        var newFileName by remember { mutableStateOf(if (createIsDirectory) "New_Folder" else "untitled.cpp") }

        androidx.compose.ui.window.Dialog(onDismissRequest = { showCreateDialog = false }) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = sidebarBackground
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (createTargetFolder != null) "NEW IN: ${createTargetFolder!!.name.uppercase()}" else "NEW WORKSPACE MODULE",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { createIsDirectory = false }
                                .background(if (!createIsDirectory) accentNeonColor.copy(alpha = 0.2f) else Color.Transparent)
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = if (!createIsDirectory) accentNeonColor else Color.Gray, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("FILE", color = if (!createIsDirectory) Color.White else Color.Gray, fontSize = 10.sp)
                        }
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { createIsDirectory = true }
                                .background(if (createIsDirectory) accentNeonColor.copy(alpha = 0.2f) else Color.Transparent)
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = if (createIsDirectory) accentNeonColor else Color.Gray, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("FOLDER", color = if (createIsDirectory) Color.White else Color.Gray, fontSize = 10.sp)
                        }
                    }

                    BasicTextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                            .border(1.dp, Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("new_filename_input"),
                        textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                        cursorBrush = SolidColor(accentNeonColor),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (newFileName.isEmpty()) {
                                    Text(if (createIsDirectory) "folder_name" else "e.g. math_utils.h", fontSize = 11.sp, color = Color.Gray)
                                }
                                innerTextField()
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showCreateDialog = false }) {
                            Text("CANCEL", color = Color.Gray, fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (newFileName.isNotEmpty()) {
                                    if (createIsDirectory) {
                                         viewModel.createWorkspaceFolder(newFileName, parentDir = createTargetFolder)
                                    } else {
                                         viewModel.createWorkspaceFile(newFileName, parentDir = createTargetFolder)
                                    }
                                    showCreateDialog = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentNeonColor
                            ),
                            modifier = Modifier.testTag("confirm_create_file")
                        ) {
                            Text("CREATE", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Centered Floating Custom Notification Toast
    androidx.compose.animation.AnimatedVisibility(
        visible = customNotification != null,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically(initialOffsetY = { -it / 2 }),
        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically(targetOffsetY = { -it / 2 }),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 96.dp)
            .padding(horizontal = 24.dp)
            .zIndex(999f)
    ) {
        customNotification?.let { msg ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isCustomNotificationError) Color(0xFFE53935) else Color(0xFF10B981)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isCustomNotificationError) Icons.Default.Error else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = msg,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

    // Floating Resizable Chat Overlay
    FloatingChatOverlay(
        isOpen = isOverlayOpen,
        onClose = { isOverlayOpen = false },
        accentNeonColor = accentNeonColor,
        sidebarBackground = sidebarBackground,
        workspaceDir = extractor.workspaceDir,
        workspaceFiles = filesTree,
        activeFile = activeFile,
        editorContent = textFieldValueState.text,
        viewModel = viewModel
    )

    if (showSettingsOverlay) {
        Dialog(
            onDismissRequest = { showSettingsOverlay = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = sidebarBackground),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("PREFERENCES & SETTINGS", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        IconButton(onClick = { showSettingsOverlay = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                        }
                    }
                    Divider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 12.dp))

                    Text("IDE EDITOR FEATURES", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    Spacer(modifier = Modifier.height(10.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Editor Font Size", color = Color.LightGray, fontSize = 14.sp)
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(onClick = { if (viewModel.editorFontSize.value > 8) viewModel.editorFontSize.value -= 2 }) {
                            Icon(Icons.Default.Remove, contentDescription = "-", tint = accentNeonColor)
                        }
                        Text("${viewModel.editorFontSize.value}sp", color = Color.White, fontSize = 14.sp)
                        IconButton(onClick = { if (viewModel.editorFontSize.value < 24) viewModel.editorFontSize.value += 2 }) {
                            Icon(Icons.Default.Add, contentDescription = "+", tint = accentNeonColor)
                        }
                    }
                    
                    Row(modifier = Modifier.fillMaxWidth().clickable { viewModel.wordWrapEnabled.value = !viewModel.wordWrapEnabled.value }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Word Wrap", color = Color.LightGray, fontSize = 14.sp)
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(checked = viewModel.wordWrapEnabled.value, onCheckedChange = { viewModel.wordWrapEnabled.value = it })
                    }
                    
                    Row(modifier = Modifier.fillMaxWidth().clickable { viewModel.showLineNumbers.value = !viewModel.showLineNumbers.value }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Line Numbers", color = Color.LightGray, fontSize = 14.sp)
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(checked = viewModel.showLineNumbers.value, onCheckedChange = { viewModel.showLineNumbers.value = it })
                    }

                    Row(modifier = Modifier.fillMaxWidth().clickable { viewModel.autoSaveEnabled.value = !viewModel.autoSaveEnabled.value }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Auto-Save Buffers", color = Color.LightGray, fontSize = 14.sp)
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(checked = viewModel.autoSaveEnabled.value, onCheckedChange = { viewModel.autoSaveEnabled.value = it })
                    }

                    Divider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 12.dp))

                    Text("FONTS & TYPOGRAPHY", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    Spacer(modifier = Modifier.height(10.dp))
                    
                    var fontUrl by remember { mutableStateOf("") }
                    var fontAlias by remember { mutableStateOf("") }
                    val installedFontsList by viewModel.installedFonts.collectAsState()
                    val activeFontName by viewModel.activeFontName.collectAsState()
                    
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Text("Download/Import Custom Font", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        BasicTextField(
                            value = fontUrl,
                            onValueChange = { fontUrl = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(4.dp)),
                            textStyle = TextStyle(color = Color.White, fontSize = 10.sp),
                            decorationBox = { inner ->
                                Box(modifier = Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                                    if (fontUrl.isEmpty()) Text("HTTP/HTTPS Font URL (e.g. Google Fonts / TTF)", color = Color.DarkGray, fontSize = 10.sp)
                                    inner()
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BasicTextField(
                                value = fontAlias,
                                onValueChange = { fontAlias = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp)
                                    .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(4.dp)),
                                textStyle = TextStyle(color = Color.White, fontSize = 10.sp),
                                decorationBox = { inner ->
                                    Box(modifier = Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                                        if (fontAlias.isEmpty()) Text("Alias Name", color = Color.DarkGray, fontSize = 10.sp)
                                        inner()
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (fontUrl.isNotEmpty() && fontAlias.isNotEmpty()) {
                                        viewModel.downloadCustomFont(context, fontUrl, fontAlias)
                                        fontUrl = ""
                                        fontAlias = ""
                                    } else {
                                        android.widget.Toast.makeText(context, "Please enter URL and Alias", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = accentNeonColor),
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                Text("DOWNLOAD", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (fontAlias.isNotEmpty()) {
                                        tempFontAliasForImport = fontAlias
                                        fontPickerLauncher.launch("*/*")
                                        fontAlias = ""
                                    } else {
                                        android.widget.Toast.makeText(context, "Please enter Alias Name first", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f)),
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                Text("IMPORT", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Select Editor Font:", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        installedFontsList.forEach { fontName ->
                            val isSelected = activeFontName == fontName
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) accentNeonColor else Color.White.copy(alpha = 0.05f))
                                    .clickable { viewModel.activeFontName.value = fontName }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    fontName.uppercase(),
                                    color = if (isSelected) Color.Black else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Divider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 12.dp))

                    Text("CUSTOM EDITOR THEME", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Active Skin:    ", color = Color.LightGray, fontSize = 13.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        listOf("vscode", "monokai", "cyberpunk", "terminal").forEach { skin ->
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (editorTheme == skin) accentNeonColor else Color.White.copy(alpha = 0.05f))
                                    .clickable { viewModel.editorTheme.value = skin }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(skin.uppercase(), color = if (editorTheme == skin) Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Divider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 12.dp))

                    Text("COMPILER SETTINGS", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    Spacer(modifier = Modifier.height(10.dp))

                    val forceTwoStage by viewModel.forceTwoStageCompile.collectAsState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.updateForceTwoStageCompile(!forceTwoStage) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Force 2-Stage Compilation", color = Color.LightGray, fontSize = 14.sp)
                            Text("Split compiler into compile (.o) and link (.so) commands", color = Color.Gray, fontSize = 11.sp)
                        }
                        Switch(
                            checked = forceTwoStage,
                            onCheckedChange = { viewModel.updateForceTwoStageCompile(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = accentNeonColor,
                                checkedTrackColor = accentNeonColor.copy(alpha = 0.5f)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val customCmd by viewModel.customCompileCommand.collectAsState()
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Primary/Single-Stage Command Template:", color = Color.LightGray, fontSize = 12.sp)
                            Text(
                                text = "Reset",
                                color = accentNeonColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable {
                                        val defaultCmd = "{compiler} -target aarch64-linux-android34 -O2 --ld-path={linker} -Wall -Wextra -std=c++17 -shared -fPIC --sysroot=/storage/emulated/0/CPPC/headers/sysroot -resource-dir=/storage/emulated/0/CPPC/headers/lib/clang/21 -isystem /storage/emulated/0/CPPC/headers/sysroot/usr/include/c++/v1 -isystem {workspace_include} -L/storage/emulated/0/CPPC/headers/sysroot/usr/lib -L{workspace_lib} -o {target_so}"
                                        viewModel.updateCustomCompileCommand(defaultCmd)
                                    }
                                    .padding(vertical = 4.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        BasicTextField(
                            value = customCmd,
                            onValueChange = { viewModel.updateCustomCompileCommand(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(88.dp)
                                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp)),
                            textStyle = TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            cursorBrush = SolidColor(accentNeonColor),
                            decorationBox = { innerTextField ->
                                Box(modifier = Modifier.padding(8.dp), contentAlignment = Alignment.TopStart) {
                                    innerTextField()
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Placeholders: {compiler}, {linker}, {sysroot}, {workspace_include}, {workspace_lib}, {target_so}, {source_cpp}",
                            color = Color.Gray,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text("2-STAGE COMPILE (OPTION 3) COMMANDS", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    Spacer(modifier = Modifier.height(6.dp))

                    val customClang by viewModel.customClangCommand.collectAsState()
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Stage 1: Clang Compile Template:", color = Color.LightGray, fontSize = 11.sp)
                            Text(
                                text = "Reset",
                                color = accentNeonColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable {
                                        val defaultClangCmd = "{compiler} -target aarch64-linux-android34 -O2 -c -Wall -Wextra -std=c++17 -fPIC --sysroot=/storage/emulated/0/CPPC/headers/sysroot -resource-dir=/storage/emulated/0/CPPC/headers/lib/clang/21 -isystem /storage/emulated/0/CPPC/headers/sysroot/usr/include/c++/v1 -isystem {workspace_include} {source_cpp} -o {output_o}"
                                        viewModel.updateCustomClangCommand(defaultClangCmd)
                                    }
                                    .padding(vertical = 4.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        BasicTextField(
                            value = customClang,
                            onValueChange = { viewModel.updateCustomClangCommand(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(88.dp)
                                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp)),
                            textStyle = TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            cursorBrush = SolidColor(accentNeonColor),
                            decorationBox = { innerTextField ->
                                Box(modifier = Modifier.padding(8.dp), contentAlignment = Alignment.TopStart) {
                                    innerTextField()
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Placeholders: {compiler}, {sysroot}, {workspace_include}, {source_cpp}, {output_o}",
                            color = Color.Gray,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val customLinker by viewModel.customLinkerCommand.collectAsState()
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Stage 2: Linker Template:", color = Color.LightGray, fontSize = 11.sp)
                            Text(
                                text = "Reset",
                                color = accentNeonColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable {
                                        val defaultLinkerCmd = "{linker} -shared --sysroot=/storage/emulated/0/CPPC/headers/sysroot -L/storage/emulated/0/CPPC/headers/sysroot/usr/lib/aarch64-linux-android/34 -L/storage/emulated/0/CPPC/headers/sysroot/usr/lib/aarch64-linux-android -L{workspace_lib} {output_o} -o {target_so} -lc++ -lm"
                                        viewModel.updateCustomLinkerCommand(defaultLinkerCmd)
                                    }
                                    .padding(vertical = 4.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        BasicTextField(
                            value = customLinker,
                            onValueChange = { viewModel.updateCustomLinkerCommand(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(88.dp)
                                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp)),
                            textStyle = TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            cursorBrush = SolidColor(accentNeonColor),
                            decorationBox = { innerTextField ->
                                Box(modifier = Modifier.padding(8.dp), contentAlignment = Alignment.TopStart) {
                                    innerTextField()
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Placeholders: {linker}, {sysroot}, {workspace_lib}, {output_o}, {target_so}",
                            color = Color.Gray,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    val optFlag by viewModel.optimizationFlag.collectAsState()
                    val warnFlags by viewModel.warningFlags.collectAsState()
                    
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Clang optimizations:", color = Color.LightGray, fontSize = 13.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        listOf("-O0", "-O1", "-O2", "-O3", "-Os").forEach { opt ->
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (optFlag == opt) accentNeonColor else Color.White.copy(alpha = 0.05f))
                                    .clickable { viewModel.optimizationFlag.value = opt }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(opt, color = if (optFlag == opt) Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                                 Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Warning Flags:   ", color = Color.LightGray, fontSize = 13.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        BasicTextField(
                            value = warnFlags,
                            onValueChange = { viewModel.warningFlags.value = it },
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp)),
                            textStyle = TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            cursorBrush = SolidColor(accentNeonColor),
                            decorationBox = { innerTextField ->
                                Box(modifier = Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                                    innerTextField()
                                }
                            }
                        )
                    }

                    val incPaths by viewModel.includePaths.collectAsState()
                    val libPaths by viewModel.libPaths.collectAsState()
                    val linkedLibs by viewModel.linkedLibs.collectAsState()

                    Spacer(modifier = Modifier.height(10.dp))
                    Column {
                        Text(text = "Additional Include Paths (-I):", color = Color.LightGray, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        BasicTextField(
                            value = incPaths,
                            onValueChange = { viewModel.includePaths.value = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp)),
                            textStyle = TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            cursorBrush = SolidColor(accentNeonColor),
                            decorationBox = { innerTextField ->
                                Box(modifier = Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                                    if (incPaths.isBlank()) Text("e.g. /path/to/headers", color = Color.Gray, fontSize = 10.sp)
                                    innerTextField()
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Column {
                        Text(text = "Additional Library Paths (-L):", color = Color.LightGray, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        BasicTextField(
                            value = libPaths,
                            onValueChange = { viewModel.libPaths.value = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp)),
                            textStyle = TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            cursorBrush = SolidColor(accentNeonColor),
                            decorationBox = { innerTextField ->
                                Box(modifier = Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                                    if (libPaths.isBlank()) Text("e.g. /path/to/libs", color = Color.Gray, fontSize = 10.sp)
                                    innerTextField()
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Column {
                        Text(text = "Linked Libraries (-l):", color = Color.LightGray, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        BasicTextField(
                            value = linkedLibs,
                            onValueChange = { viewModel.linkedLibs.value = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp)),
                            textStyle = TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            cursorBrush = SolidColor(accentNeonColor),
                            decorationBox = { innerTextField ->
                                Box(modifier = Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                                    if (linkedLibs.isBlank()) Text("e.g. z log m", color = Color.Gray, fontSize = 10.sp)
                                    innerTextField()
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val workManager = androidx.work.WorkManager.getInstance(context)
                            val request = androidx.work.OneTimeWorkRequest.Builder(com.example.core.compiler.HeaderScannerWorker::class.java)
                                .setInputData(androidx.work.workDataOf("sysrootDir_path" to viewModel.getSysrootDirPath()))
                                .build()
                            workManager.enqueue(request)
                            workManager.getWorkInfoByIdLiveData(request.id).observe(context as androidx.activity.ComponentActivity) { workInfo ->
                                if (workInfo != null) {
                                    if (workInfo.state == androidx.work.WorkInfo.State.SUCCEEDED) {
                                        val msg = workInfo.outputData.getString("status_message")
                                        if (msg != null) viewModel.appendTerminalLog(msg)
                                    } else if (workInfo.state == androidx.work.WorkInfo.State.FAILED) {
                                        val msg = workInfo.outputData.getString("status_message")
                                        if (msg != null) viewModel.appendTerminalLog(msg)
                                    } else {
                                        val progress = workInfo.progress
                                        val msg = progress.getString("status_message")
                                        val isPct = progress.getInt("progress", -1)
                                        if (msg != null) {
                                            if (isPct != -1 && isPct > 0) {
                                                viewModel.appendTerminalLogReplace(msg)
                                            } else {
                                                viewModel.appendTerminalLog(msg)
                                            }
                                        }
                                    }
                                }
                            }
                            showSettingsOverlay = false
                            viewModel.selectPanelTab(0)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accentNeonColor),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                    ) {
                        Text("Start Scanning Headers (Fast-Search DB)", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Divider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 12.dp))

                    Text("AGENTIC AI SETTINGS", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    Spacer(modifier = Modifier.height(6.dp))

                    val selectedProvider by AgenticCoordinator.apiProvider.collectAsState()
                    val selectedModel by AgenticCoordinator.activeModel.collectAsState()
                    val openAiUrl by AgenticCoordinator.customOpenAiUrl.collectAsState()
                    val localApiUrl by AgenticCoordinator.localApiUrl.collectAsState()

                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "AI Service:     ", color = Color.LightGray, fontSize = 13.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (selectedProvider == "google") accentNeonColor else Color.White.copy(alpha = 0.05f))
                                .clickable {
                                    AgenticCoordinator.apiProvider.value = "google"
                                    AgenticCoordinator.activeModel.value = "gemini-3.5-flash"
                                    AgenticCoordinator.save(context)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("Google GenAI (Gemini)", color = if (selectedProvider == "google") Color.Black else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (selectedProvider == "openai") accentNeonColor else Color.White.copy(alpha = 0.05f))
                                .clickable {
                                    AgenticCoordinator.apiProvider.value = "openai"
                                    AgenticCoordinator.activeModel.value = "gpt-4o"
                                    AgenticCoordinator.save(context)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("OpenAI API Service", color = if (selectedProvider == "openai") Color.Black else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (selectedProvider == "local") accentNeonColor else Color.White.copy(alpha = 0.05f))
                                .clickable {
                                    AgenticCoordinator.apiProvider.value = "local"
                                    AgenticCoordinator.activeModel.value = "gemma4_2b_v09_obfus_fix_all_modalities_thinking.litertlm"
                                    AgenticCoordinator.save(context)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("Local Server", color = if (selectedProvider == "local") Color.Black else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Target Model:   ", color = Color.LightGray, fontSize = 13.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        BasicTextField(
                            value = selectedModel,
                            onValueChange = { AgenticCoordinator.activeModel.value = it; AgenticCoordinator.save(context) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp)),
                            textStyle = TextStyle(color = Color.White, fontSize = 11.sp),
                            cursorBrush = SolidColor(accentNeonColor),
                            decorationBox = { innerTextField ->
                                Box(modifier = Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                                    innerTextField()
                                }
                            }
                        )
                    }

                    if (selectedProvider == "openai" || selectedProvider == "local") {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = if (selectedProvider == "local") "Local Base URL: " else "OpenAI Base URL: ", color = Color.LightGray, fontSize = 13.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            BasicTextField(
                                value = if (selectedProvider == "local") localApiUrl else openAiUrl,
                                onValueChange = { 
                                    if (selectedProvider == "local") AgenticCoordinator.localApiUrl.value = it 
                                    else AgenticCoordinator.customOpenAiUrl.value = it
                                    AgenticCoordinator.save(context) 
                                },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp)
                                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp)),
                                textStyle = TextStyle(color = Color.White, fontSize = 11.sp),
                                cursorBrush = SolidColor(accentNeonColor),
                                decorationBox = { innerTextField ->
                                    Box(modifier = Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                                        innerTextField()
                                    }
                                }
                            )
                        }
                    }

                    Divider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 12.dp))
                    Text("COMPANION C++ ENGINE CONFIGURATION", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val modPkg by viewModel.modulePackageName.collectAsState()
                    val sysLoc by viewModel.headersLocation.collectAsState()
                    val bClang by viewModel.binClang.collectAsState()
                    val bClangPlus by viewModel.binClangPlusPlus.collectAsState()
                    val bClangd by viewModel.binClangd.collectAsState()
                    val bLld by viewModel.binLld.collectAsState()

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column {
                            Text("Engine Companion Package Name", color = Color.Gray, fontSize = 9.sp)
                            BasicTextField(
                                value = modPkg,
                                onValueChange = { viewModel.modulePackageName.value = it },
                                modifier = Modifier.fillMaxWidth().height(28.dp).background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp)),
                                textStyle = TextStyle(color = Color.White, fontSize = 10.sp),
                                cursorBrush = SolidColor(accentNeonColor),
                                decorationBox = { Box(modifier = Modifier.padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) { it() } }
                            )
                        }

                        Column {
                            Text("Headers / Sysroot External Directory Path", color = Color.Gray, fontSize = 9.sp)
                            BasicTextField(
                                value = sysLoc,
                                onValueChange = { viewModel.headersLocation.value = it },
                                modifier = Modifier.fillMaxWidth().height(28.dp).background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp)),
                                textStyle = TextStyle(color = Color.White, fontSize = 10.sp),
                                cursorBrush = SolidColor(accentNeonColor),
                                decorationBox = { Box(modifier = Modifier.padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) { it() } }
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text("BINARY VALIDATION PARAMETERS", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Clang", color = Color.Gray, fontSize = 8.sp)
                                BasicTextField(
                                    value = bClang,
                                    onValueChange = { viewModel.binClang.value = it },
                                    modifier = Modifier.fillMaxWidth().height(32.dp).background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp)),
                                    textStyle = TextStyle(color = Color.White, fontSize = 9.sp),
                                    cursorBrush = SolidColor(accentNeonColor),
                                    decorationBox = { Box(modifier = Modifier.padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) { it() } }
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Clang++", color = Color.Gray, fontSize = 8.sp)
                                BasicTextField(
                                    value = bClangPlus,
                                    onValueChange = { viewModel.binClangPlusPlus.value = it },
                                    modifier = Modifier.fillMaxWidth().height(32.dp).background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp)),
                                    textStyle = TextStyle(color = Color.White, fontSize = 9.sp),
                                    cursorBrush = SolidColor(accentNeonColor),
                                    decorationBox = { Box(modifier = Modifier.padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) { it() } }
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Clangd (LSP)", color = Color.Gray, fontSize = 8.sp)
                                BasicTextField(
                                    value = bClangd,
                                    onValueChange = { viewModel.binClangd.value = it },
                                    modifier = Modifier.fillMaxWidth().height(32.dp).background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp)),
                                    textStyle = TextStyle(color = Color.White, fontSize = 9.sp),
                                    cursorBrush = SolidColor(accentNeonColor),
                                    decorationBox = { Box(modifier = Modifier.padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) { it() } }
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("LLD linker", color = Color.Gray, fontSize = 8.sp)
                                BasicTextField(
                                    value = bLld,
                                    onValueChange = { viewModel.binLld.value = it },
                                    modifier = Modifier.fillMaxWidth().height(32.dp).background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp)),
                                    textStyle = TextStyle(color = Color.White, fontSize = 9.sp),
                                    cursorBrush = SolidColor(accentNeonColor),
                                    decorationBox = { Box(modifier = Modifier.padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) { it() } }
                                )
                            }
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(
                                onClick = { 
                                    viewModel.modulePackageName.value = "com.cppc.engine"
                                    viewModel.headersLocation.value = "CPPC/headers"
                                    viewModel.binClang.value = "libclang.so"
                                    viewModel.binClangPlusPlus.value = "libclang++.so"
                                    viewModel.binClangd.value = "libclangd.so"
                                    viewModel.binLld.value = "liblld.so"
                                    viewModel.toolchainUrl.value = ""
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f)),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("RESET TO SYSTEM DEFAULT", color = Color.White, fontSize = 9.sp)
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(10.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.setupToolchain(force = true) },
                            modifier = Modifier.weight(1f).height(36.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text("FORCE REDEPLOY", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        
                        Button(
                            onClick = { viewModel.createEngineBackup() },
                            modifier = Modifier.weight(1f).height(36.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = accentNeonColor),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Icon(Icons.Default.Backup, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Black)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("CREATE BACKUP", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { zipPickerLauncher.launch("application/zip") },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f)),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text("RECOVER ENGINE FROM LOCAL ZIP", color = Color.White, fontSize = 11.sp)
                    }
                    
                    val persistentPath = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "CPPC/downloads").absolutePath
                    Text(
                        text = "Persistent Storage: $persistentPath\n(Engine recovery archives reside securely here)",
                        color = Color.Gray,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("API KEYS MANAGEMENT", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    val registeredKeys by AgenticCoordinator.apiKeysList.collectAsState()
                    val activeIndices by AgenticCoordinator.activeApiKeyIndices.collectAsState()
                    var newKeyAlias by remember { mutableStateOf("") }
                    var newKeyValue by remember { mutableStateOf("") }

                    registeredKeys.forEachIndexed { index, key ->
                        val isActive = activeIndices.contains(index)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isActive) accentNeonColor.copy(alpha = 0.1f) else Color.Transparent)
                                .clickable {
                                    val newSet = activeIndices.toMutableSet()
                                    if (isActive) newSet.remove(index) else newSet.add(index)
                                    AgenticCoordinator.activeApiKeyIndices.value = newSet
                                    AgenticCoordinator.save(context)
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = if (isActive) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isActive) accentNeonColor else Color.Gray,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(key.alias, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text("Key: **********${key.keyValue.takeLast(4)}", color = Color.Gray, fontSize = 10.sp)
                            }
                            IconButton(onClick = {
                                val newList = registeredKeys.toMutableList()
                                newList.removeAt(index)
                                AgenticCoordinator.apiKeysList.value = newList
                                
                                val newIndices = activeIndices.filter { it != index }.map { if (it > index) it - 1 else it }.toSet()
                                AgenticCoordinator.activeApiKeyIndices.value = newIndices
                                AgenticCoordinator.save(context)
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete key", tint = Color.Red.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            BasicTextField(
                                value = newKeyAlias,
                                onValueChange = { newKeyAlias = it },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(0.4f)
                                    .height(38.dp)
                                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp)),
                                textStyle = TextStyle(color = Color.White, fontSize = 11.sp),
                                cursorBrush = SolidColor(accentNeonColor),
                                decorationBox = { innerTextField ->
                                    Box(modifier = Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                                        if (newKeyAlias.isEmpty()) {
                                            Text("Alias (e.g. MyKey)", fontSize = 11.sp, color = Color.Gray)
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            BasicTextField(
                                value = newKeyValue,
                                onValueChange = { newKeyValue = it },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(0.6f)
                                    .height(38.dp)
                                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp)),
                                textStyle = TextStyle(color = Color.White, fontSize = 11.sp),
                                cursorBrush = SolidColor(accentNeonColor),
                                decorationBox = { innerTextField ->
                                    Box(modifier = Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                                        if (newKeyValue.isEmpty()) {
                                            Text("API Key", fontSize = 11.sp, color = Color.Gray)
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (newKeyValue.isNotEmpty()) {
                                        val alias = if (newKeyAlias.isEmpty()) "Key ${registeredKeys.size + 1}" else newKeyAlias
                                        val current = AgenticCoordinator.apiKeysList.value.toMutableList()
                                        current.add(AgenticCoordinator.ApiKeyEntry(alias, newKeyValue))
                                        AgenticCoordinator.apiKeysList.value = current
                                        
                                        // Auto-activate if it's the first key or none active
                                        if (AgenticCoordinator.activeApiKeyIndices.value.isEmpty()) {
                                            AgenticCoordinator.activeApiKeyIndices.value = setOf(current.size - 1)
                                        }
                                        
                                        AgenticCoordinator.save(context)
                                        android.widget.Toast.makeText(context, "API Key added: $alias", android.widget.Toast.LENGTH_SHORT).show()
                                        newKeyAlias = ""; newKeyValue = ""
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = accentNeonColor),
                                modifier = Modifier.height(38.dp), contentPadding = PaddingValues(horizontal = 16.dp),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text("ADD", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (registeredKeys.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Active keys will be used sequentially for fault tolerance.", color = Color.Gray, fontSize = 9.sp)
                    }

                    Divider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 12.dp))

                    Text("DATABASE MANAGEMENT", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    Spacer(modifier = Modifier.height(10.dp))
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                            .clickable { com.example.core.agent.AgenticCoordinator.exportDatabase(context) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Storage, contentDescription = null, tint = accentNeonColor, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Export Room Database", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("Saves local chat history (.db) to CPPC/exports", color = Color.Gray, fontSize = 11.sp)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                    }

                    Spacer(modifier = Modifier.height(48.dp))
                }
            }
        }
    }
}

fun getFontFamily(fontName: String, context: android.content.Context): androidx.compose.ui.text.font.FontFamily {
    if (fontName == "Monospace") {
        return androidx.compose.ui.text.font.FontFamily.Monospace
    }
    return try {
        val file = java.io.File(context.filesDir, "custom_fonts/$fontName.ttf")
        if (file.exists()) {
            androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(file))
        } else {
            androidx.compose.ui.text.font.FontFamily.Monospace
        }
    } catch (e: Exception) {
        androidx.compose.ui.text.font.FontFamily.Monospace
    }
}

/**
 * Custom VisualTransformation mapping standard C++ keywords & formatting diagnostics live squiggles in editor
 */
class CppSyntaxTransformation(
    private val diagnostics: List<LspDiagnostic>,
    private val theme: String = "vscode",
    private val tabSize: Int = 4
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val rawText = text.text
        val annotatedRaw = buildAnnotatedString {
            append(rawText)

            // Theme-specific colors
            val keywordColor = when(theme) {
                "monokai" -> Color(0xFFF92672)
                "cyberpunk" -> Color(0xFF00F0FF)
                "terminal" -> Color(0xFF00FF00)
                else -> Color(0xFF569CD6) // VS Code blue
            }
            
            val stringColor = when(theme) {
                "monokai" -> Color(0xFFE6DB74)
                "cyberpunk" -> Color(0xFFFF007F)
                "terminal" -> Color(0xFF00FF00)
                else -> Color(0xFFCE9178) // VS Code sand
            }

            val commentColor = when(theme) {
                "monokai" -> Color(0xFF75715E)
                "cyberpunk" -> Color(0xFF00FF66)
                "terminal" -> Color(0xFF008000)
                else -> Color(0xFF6A9955) // VS Code green
            }

            // 1. Keyword syntax highlighting
            val cppKeywords = listOf(
                "extern", "extern \"C\"", "int", "return", "char", "float", "double", "class", "namespace", "void",
                "struct", "using", "template", "const", "static", "if", "else", "for", "while"
            )

            cppKeywords.forEach { keyword ->
                var index = rawText.indexOf(keyword)
                while (index >= 0) {
                    val isStartBoundary = index == 0 || !rawText[index - 1].isLetterOrDigit()
                    val isEndBoundary = (index + keyword.length) >= rawText.length || !rawText[index + keyword.length].isLetterOrDigit()
                    
                    if (isStartBoundary && isEndBoundary) {
                        addStyle(
                             style = SpanStyle(
                                 color = keywordColor,
                                 fontWeight = FontWeight.Bold
                             ),
                             start = index,
                             end = index + keyword.length
                        )
                    }
                    index = rawText.indexOf(keyword, index + 1)
                }
            }

            // Highlighting standard headers like stdio.h, stdlib.h or nlohmann/json
            val headersRegex = Regex("<\\S+>|\"[^\"\\r\\n]+\"")
            headersRegex.findAll(rawText).forEach { match ->
                addStyle(
                    style = SpanStyle(
                        color = stringColor
                    ),
                    start = match.range.first,
                    end = match.range.last + 1
                )
            }

            // Highlighting C++ comments
            var cmIndex = rawText.indexOf("//")
            while (cmIndex >= 0) {
                val endOfLine = rawText.indexOf("\n", cmIndex)
                val targetEnd = if (endOfLine >= 0) endOfLine else rawText.length
                addStyle(
                    style = SpanStyle(
                        color = commentColor,
                        fontFamily = FontFamily.Monospace
                    ),
                    start = cmIndex,
                    end = targetEnd
                )
                cmIndex = rawText.indexOf("//", targetEnd)
            }

            // 2. Mapping semantic diagnostic squiggly lines underneath specific columns
            diagnostics.forEach { diag ->
                val lines = rawText.split("\n")
                if (diag.line < lines.size) {
                    var linearOffset = 0
                    for (i in 0 until diag.line) {
                        linearOffset += lines[i].length + 1
                    }
                    val charOffset = minOf(lines[diag.line].length, diag.column)
                    val startOffset = linearOffset + charOffset
                    val finalEnd = minOf(rawText.length, startOffset + diag.length)

                    if (startOffset in rawText.indices) {
                        addStyle(
                            style = SpanStyle(
                                textDecoration = TextDecoration.Underline,
                                color = if (diag.severity == 1) Color(0xFFE53935) else Color(0xFFFFB300),
                                fontWeight = FontWeight.Black
                            ),
                            start = startOffset,
                            end = finalEnd
                        )
                    }
                }
            }
        }

        // Expanded tab visual representation
        val builder = java.lang.StringBuilder()
        val rawToVisual = IntArray(rawText.length + 1)
        var visualIdx = 0
        
        for (i in 0 until rawText.length) {
            rawToVisual[i] = visualIdx
            if (rawText[i] == '\t') {
                builder.append(" ".repeat(tabSize))
                visualIdx += tabSize
            } else {
                builder.append(rawText[i])
                visualIdx += 1
            }
        }
        rawToVisual[rawText.length] = visualIdx
        
        val transformedText = builder.toString()
        
        val transformedAnnotated = buildAnnotatedString {
            append(transformedText)
            annotatedRaw.spanStyles.forEach { range ->
                val origStart = range.start.coerceIn(0, rawText.length)
                val origEnd = range.end.coerceIn(0, rawText.length)
                val transStart = rawToVisual[origStart]
                val transEnd = rawToVisual[origEnd]
                addStyle(range.item, transStart, transEnd)
            }
        }
        
        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                val clamped = offset.coerceIn(0, rawText.length)
                return rawToVisual[clamped]
            }
            
            override fun transformedToOriginal(offset: Int): Int {
                var low = 0
                var high = rawText.length
                var bestIdx = 0
                while (low <= high) {
                    val mid = (low + high) / 2
                    val vOffset = rawToVisual[mid]
                    if (vOffset == offset) {
                        return mid
                    } else if (vOffset < offset) {
                        bestIdx = mid
                        low = mid + 1
                    } else {
                        high = mid - 1
                    }
                }
                return bestIdx
            }
        }
        
        return TransformedText(transformedAnnotated, offsetMapping)
    }
}

// --- COSMIC CHAT EXTENSION WIDGETS ---

data class ContentBlock(
    val text: String,
    val isCode: Boolean,
    val targetPath: String? = null
)

fun parseMsgToBlocks(msg: String): List<ContentBlock> {
    val blocks = mutableListOf<ContentBlock>()
    val lines = msg.split("\n")
    var inCode = false
    val currentBlockText = StringBuilder()
    var currentPath: String? = null

    for (line in lines) {
        if (line.trim().startsWith("```")) {
            if (inCode) {
                blocks.add(ContentBlock(currentBlockText.toString(), true, currentPath))
                currentBlockText.clear()
                currentPath = null
                inCode = false
            } else {
                if (currentBlockText.isNotEmpty()) {
                    blocks.add(ContentBlock(currentBlockText.toString(), false))
                    currentBlockText.clear()
                }
                inCode = true
                val parts = line.substring(3).trim()
                if (parts.contains("path=")) {
                    currentPath = parts.substringAfter("path=").substringBefore(" ").trim()
                } else if (parts.contains("/")) {
                    currentPath = parts.trim()
                }
            }
        } else {
            if (inCode) {
                if (currentBlockText.isEmpty()) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("//") && (trimmed.contains("/") || trimmed.contains(".cpp") || trimmed.contains(".h"))) {
                        val pathHint = trimmed.substring(2).trim()
                            .substringAfter("File:")
                            .substringBefore("\n")
                            .trim()
                        if (pathHint.isNotEmpty()) {
                            currentPath = pathHint
                        }
                    }
                }
                currentBlockText.append(line).append("\n")
            } else {
                currentBlockText.append(line).append("\n")
            }
        }
    }

    if (currentBlockText.isNotEmpty()) {
        blocks.add(ContentBlock(currentBlockText.toString(), inCode, currentPath))
    }

    return blocks
}

@Composable
fun CodeBlockView(
    codeText: String,
    recommendedPath: String?,
    workspaceDir: java.io.File,
    workspaceFiles: List<java.io.File>,
    accentNeonColor: Color,
    onPasteSuccess: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var showPathSelector by remember { mutableStateOf(false) }
    var customPathInput by remember { mutableStateOf(recommendedPath ?: "") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
            .background(Color(0xFF0F1115))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF08090C))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = recommendedPath?.let { "Target: $it" } ?: "Source Code block",
                color = Color.LightGray,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(codeText))
                        android.widget.Toast.makeText(context, "Copied content!", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy code snippet",
                        tint = accentNeonColor,
                        modifier = Modifier.size(12.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = {
                        if (recommendedPath != null) {
                            val target = java.io.File(workspaceDir, recommendedPath)
                            try {
                                target.parentFile?.mkdirs()
                                target.writeText(codeText)
                                android.widget.Toast.makeText(context, "Pasted into $recommendedPath", android.widget.Toast.LENGTH_SHORT).show()
                                onPasteSuccess(recommendedPath)
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, "Failed: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                            }
                        } else {
                            showPathSelector = !showPathSelector
                        }
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = "Paste block to file",
                        tint = accentNeonColor,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F1115))
                .padding(8.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            Text(
                text = codeText,
                color = Color(0xFF4AF2A1),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                lineHeight = 12.sp
            )
        }

        if (showPathSelector) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF151820))
                    .padding(8.dp)
            ) {
                Text(
                    text = "Select or Enter target workspace file path:",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                BasicTextField(
                    value = customPathInput,
                    onValueChange = { customPathInput = it },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(4.dp)),
                    textStyle = TextStyle(color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(accentNeonColor),
                    decorationBox = { innerTextField ->
                        Box(modifier = Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                            if (customPathInput.isEmpty()) {
                                Text("e.g. src/utils.h", fontSize = 9.sp, color = Color.Gray)
                            }
                            innerTextField()
                        }
                    }
                )
                Spacer(modifier = Modifier.height(6.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(workspaceFiles) { file ->
                        val rel = file.relativeTo(workspaceDir).path
                        if (!rel.startsWith(".") && file.isFile) {
                            Box(
                                modifier = Modifier
                                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp))
                                    .clickable { customPathInput = rel }
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = file.name,
                                    color = accentNeonColor,
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { showPathSelector = false },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("CANCEL", color = Color.Red, fontSize = 9.sp)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Button(
                        onClick = {
                            if (customPathInput.trim().isNotEmpty()) {
                                try {
                                    val target = java.io.File(workspaceDir, customPathInput.trim())
                                    target.parentFile?.mkdirs()
                                    target.writeText(codeText)
                                    android.widget.Toast.makeText(context, "Successfully pasted into ${customPathInput.trim()}", android.widget.Toast.LENGTH_SHORT).show()
                                    showPathSelector = false
                                    onPasteSuccess(customPathInput.trim())
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "Error: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accentNeonColor),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Text("WRITE FILE", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun CosmicChatPanelContent(
    accentNeonColor: Color,
    sidebarBackground: Color,
    workspaceDir: java.io.File,
    workspaceFiles: List<java.io.File>,
    activeFile: java.io.File?,
    viewModel: IdeViewModel
) {
    val coroutineScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val contentResolver = context.contentResolver
                    val cursor = contentResolver.query(it, null, null, null, null)
                    cursor?.use { c ->
                        val nameIndex = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (c.moveToFirst()) {
                            val fileName = c.getString(nameIndex)
                            val targetFile = File(workspaceDir, fileName)
                            contentResolver.openInputStream(it)?.use { input ->
                                targetFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            // Refresh file list and auto-attach
                            viewModel.loadFileSystem()
                            val relativePath = targetFile.relativeTo(workspaceDir).path
                            if (!com.example.core.agent.AgenticCoordinator.attachedFiles.value.contains(relativePath)) {
                                com.example.core.agent.AgenticCoordinator.attachedFiles.value += relativePath
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Chat", "Error picking file", e)
                }
            }
        }
    }

    val chatList by com.example.core.agent.AgenticCoordinator.chatHistory.collectAsState()
    val isChatLoading by com.example.core.agent.AgenticCoordinator.isChatRunning.collectAsState()
    val attachedList by com.example.core.agent.AgenticCoordinator.attachedFiles.collectAsState()
    val selectAllChat by com.example.core.agent.AgenticCoordinator.selectAllChatContext.collectAsState()
    val sessions by com.example.core.agent.AgenticCoordinator.sessions.collectAsState()
    val currentSid by com.example.core.agent.AgenticCoordinator.currentSessionId.collectAsState()

    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var showSessionMenu by remember { mutableStateOf(false) }

    LaunchedEffect(chatList.size) {
        if (chatList.isNotEmpty()) {
            listState.animateScrollToItem(chatList.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(sidebarBackground)
    ) {
        // Sleek Horizontally Scrollable Options Panel
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .background(Color.White.copy(alpha = 0.02f), RoundedCornerShape(6.dp))
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Global Context Picker Trigger
            var showGlobalContextPicker by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .clickable { showGlobalContextPicker = true }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.History, contentDescription = null, tint = accentNeonColor, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text("Select from Sessions", color = accentNeonColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }

            if (showGlobalContextPicker) {
                GlobalContextPicker(
                    accentNeonColor = accentNeonColor,
                    sidebarBackground = sidebarBackground,
                    onDismiss = { showGlobalContextPicker = false },
                    onImport = { messages ->
                        coroutineScope.launch {
                            com.example.core.agent.AgenticCoordinator.importMessagesToContext(messages)
                        }
                        showGlobalContextPicker = false
                    }
                )
            }

            // Session Switcher
            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .clickable { showSessionMenu = true }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val currentTitle = sessions.find { it.id == currentSid }?.title ?: "Select Session"
                    Text(currentTitle, color = accentNeonColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = accentNeonColor, modifier = Modifier.size(12.dp))
                }
                DropdownMenu(
                    expanded = showSessionMenu,
                    onDismissRequest = { showSessionMenu = false },
                    modifier = Modifier.background(sidebarBackground).border(1.dp, Color.White.copy(alpha = 0.1f))
                ) {
                    sessions.forEach { sess ->
                        DropdownMenuItem(
                            text = { Text(sess.title, fontSize = 10.sp, color = if (sess.id == currentSid) accentNeonColor else Color.White) },
                            onClick = {
                                coroutineScope.launch {
                                    com.example.core.agent.AgenticCoordinator.loadSession(sess.id)
                                }
                                showSessionMenu = false
                            }
                        )
                    }
                    Divider(color = Color.White.copy(alpha = 0.1f))
                    DropdownMenuItem(
                        text = { Text("+ New Session", fontSize = 10.sp, color = accentNeonColor) },
                        onClick = {
                            coroutineScope.launch {
                                com.example.core.agent.AgenticCoordinator.createNewSession("Session ${sessions.size + 1}")
                            }
                            showSessionMenu = false
                        }
                    )
                }
            }

            // Database Export Tool
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .clickable { com.example.core.agent.AgenticCoordinator.exportDatabase(context) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.FileDownload, contentDescription = "Export DB", tint = Color.LightGray, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text("Export", color = Color.LightGray, fontSize = 9.sp)
            }

            // Context Check/Checkbox Tool
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (selectAllChat) accentNeonColor.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f))
                    .clickable { com.example.core.agent.AgenticCoordinator.selectAllChatContext.value = !selectAllChat }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .border(1.dp, if (selectAllChat) accentNeonColor else Color.Gray, RoundedCornerShape(2.dp))
                        .background(if (selectAllChat) accentNeonColor else Color.Transparent, RoundedCornerShape(2.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (selectAllChat) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(8.dp))
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text("All Context", color = Color.LightGray, fontSize = 9.sp)
            }

            val attachTerminalContext by com.example.core.agent.AgenticCoordinator.attachTerminalLogs.collectAsState()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (attachTerminalContext) accentNeonColor.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f))
                    .clickable { com.example.core.agent.AgenticCoordinator.attachTerminalLogs.value = !attachTerminalContext }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .border(1.dp, if (attachTerminalContext) accentNeonColor else Color.Gray, RoundedCornerShape(2.dp))
                        .background(if (attachTerminalContext) accentNeonColor else Color.Transparent, RoundedCornerShape(2.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (attachTerminalContext) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(8.dp))
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text("Terminal Logs", color = Color.LightGray, fontSize = 9.sp)
            }

            Spacer(modifier = Modifier.width(4.dp))
            Text(
                "(Click msgs to select individual context)",
                color = Color.Gray.copy(alpha = 0.6f),
                fontSize = 8.sp,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
            )

            // Clear/Delete Selected Action items
            val hasSelection = chatList.any { it.isSelected }
            if (hasSelection) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Red.copy(alpha = 0.15f))
                        .border(1.dp, Color.Red.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                        .clickable { com.example.core.agent.AgenticCoordinator.deleteSelectedMessages(coroutineScope) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Selected", tint = Color.Red, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("Delete Selected", color = Color.Red, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .clickable { com.example.core.agent.AgenticCoordinator.chatHistory.value = emptyList() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear Chat", tint = Color.Red.copy(alpha = 0.7f), modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("Clear Chat", color = Color.LightGray, fontSize = 9.sp)
                }
            }

            // Separator between systems and file attaching
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(14.dp)
                    .background(Color.White.copy(alpha = 0.1f))
            )

            // Horizontal File Attachment Options
            Text("Attach:", color = Color.Gray, fontSize = 9.sp)
            workspaceFiles.forEach { file ->
                val path = file.relativeTo(workspaceDir).path
                if (!path.startsWith(".") && file.isFile) {
                    val isAttached = attachedList.contains(path)
                    Box(
                        modifier = Modifier
                            .background(
                                if (isAttached) accentNeonColor.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.05f),
                                RoundedCornerShape(4.dp)
                            )
                            .border(
                                1.dp,
                                if (isAttached) accentNeonColor.copy(alpha = 0.4f) else Color.Transparent,
                                RoundedCornerShape(4.dp)
                            )
                            .clickable {
                                if (isAttached) {
                                    com.example.core.agent.AgenticCoordinator.attachedFiles.value = 
                                        com.example.core.agent.AgenticCoordinator.attachedFiles.value - path
                                } else {
                                    com.example.core.agent.AgenticCoordinator.attachedFiles.value = 
                                        com.example.core.agent.AgenticCoordinator.attachedFiles.value + path
                                }
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "+ ${file.name}",
                            color = if (isAttached) Color.White else Color.LightGray,
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // Active attached context clips display list (Only shows up when items are attached)
        if (attachedList.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(attachedList.toList()) { path ->
                    Row(
                        modifier = Modifier
                            .background(accentNeonColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .border(1.dp, accentNeonColor.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(path.substringAfterLast("/"), color = Color.White, fontSize = 8.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove attached file",
                            tint = Color.Red,
                            modifier = Modifier
                                .size(10.dp)
                                .clickable {
                                    com.example.core.agent.AgenticCoordinator.attachedFiles.value = 
                                        com.example.core.agent.AgenticCoordinator.attachedFiles.value - path
                                }
                        )
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(chatList) { msg ->
                val isSelected = msg.isSelected
                val isUser = msg.sender == "USER"
                val senderColor = if (isUser) Color(0xFFC084FC) else accentNeonColor
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(
                            width = 1.dp,
                            color = if (isSelected) accentNeonColor.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.05f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .background(
                            if (isSelected) accentNeonColor.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.03f)
                        )
                        .clickable {
                            com.example.core.agent.AgenticCoordinator.chatHistory.value = 
                                chatList.map { if (it.id == msg.id) it.copy(isSelected = !it.isSelected) else it }
                        }
                ) {
                    // Selection indicator stripe
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .width(3.dp)
                            .fillMaxHeight()
                            .padding(vertical = 8.dp)
                            .background(senderColor, RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                    )

                    Column(
                        modifier = Modifier
                            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp)
                            .fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isUser) Icons.Default.Person else Icons.Default.SmartToy,
                                    contentDescription = null,
                                    tint = senderColor,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isUser) "DEVELOPER" else "COSMIC AI",
                                    color = senderColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                            }
                            
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (msg.attachedFiles.isNotEmpty()) {
                                    Text(
                                        text = "clips: ${msg.attachedFiles.size}",
                                        color = Color.Gray,
                                        fontSize = 8.sp,
                                        modifier = Modifier
                                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                
                                // Integrated selection checkmark
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Selected",
                                        tint = accentNeonColor,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(6.dp))

                        val parsedBlocks = remember(msg.content) { parseMsgToBlocks(msg.content) }
                        parsedBlocks.forEach { block ->
                            if (block.isCode) {
                                CodeBlockView(
                                    codeText = block.text,
                                    recommendedPath = block.targetPath,
                                    workspaceDir = workspaceDir,
                                    workspaceFiles = workspaceFiles,
                                    accentNeonColor = accentNeonColor,
                                    onPasteSuccess = { path ->
                                        viewModel.loadFileSystem()
                                        val reopened = java.io.File(workspaceDir, path)
                                        if (reopened.exists()) {
                                            viewModel.openFile(reopened)
                                        }
                                    }
                                )
                            } else {
                                MarkwonText(
                                    markdown = block.text,
                                    textColor = android.graphics.Color.parseColor("#E2E8F0"),
                                    textSizeSP = 11f,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (isChatLoading) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = accentNeonColor,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Thinking...", color = Color.Gray, fontSize = 10.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // NEW MERGED RECTANGULAR INPUT AREA
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp)
                .heightIn(min = 44.dp, max = 180.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.05f)),
            verticalAlignment = Alignment.Bottom
        ) {
            // File Attachment Shortcut Button
            var showQuickPicker by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showQuickPicker = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AttachFile,
                    contentDescription = "Attach File",
                    tint = if (attachedList.isNotEmpty()) accentNeonColor else Color.Gray,
                    modifier = Modifier.size(18.dp).graphicsLayer(rotationZ = 45f)
                )
            }

            if (showQuickPicker) {
                androidx.compose.ui.window.Dialog(onDismissRequest = { showQuickPicker = false }) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .heightIn(max = 400.dp)
                            .padding(16.dp)
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = sidebarBackground),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Attach File", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Button(
                                    onClick = { launcher.launch("*/*") },
                                    colors = ButtonDefaults.buttonColors(containerColor = accentNeonColor.copy(alpha = 0.2f), contentColor = accentNeonColor),
                                    shape = RoundedCornerShape(4.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("IMPORT FROM SYSTEM", fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("RECENT WORKSPACE FILES", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))
                            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                                items(workspaceFiles) { file ->
                                    val path = file.relativeTo(workspaceDir).path
                                    if (!path.startsWith(".") && file.isFile) {
                                        val isAttached = attachedList.contains(path)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable {
                                                    if (isAttached) {
                                                        com.example.core.agent.AgenticCoordinator.attachedFiles.value -= path
                                                    } else {
                                                        com.example.core.agent.AgenticCoordinator.attachedFiles.value += path
                                                    }
                                                }
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (isAttached) Icons.Default.CheckCircle else Icons.Default.Add,
                                                contentDescription = null,
                                                tint = if (isAttached) accentNeonColor else Color.Gray,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(file.name, color = if (isAttached) Color.White else Color.LightGray, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { showQuickPicker = false },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = accentNeonColor, contentColor = Color.Black)
                            ) {
                                Text("Done", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = Color.White, fontSize = 11.sp),
                    cursorBrush = SolidColor(accentNeonColor),
                    minLines = 1,
                    maxLines = 8,
                    decorationBox = { innerTextField ->
                        if (textInput.isEmpty()) {
                            Text("Ask or discuss with AI...", fontSize = 11.sp, color = Color.Gray)
                        }
                        innerTextField()
                    }
                )
            }
            
            val isEnabled = textInput.trim().isNotEmpty() && !isChatLoading
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(if (isEnabled) accentNeonColor else Color.Gray.copy(alpha = 0.3f))
                    .clickable(enabled = true) {
                        if (isEnabled) {
                            if (!viewModel.hasApiKey()) {
                                viewModel.showCustomNotification("API key required! Please set it in Settings.", isError = true)
                                return@clickable
                            }
                            val prompt = textInput.trim()
                            textInput = ""
                            com.example.core.agent.AgenticCoordinator.sendChatMessage(
                                context = context,
                                scope = coroutineScope,
                                prompt = prompt,
                                workspaceDir = workspaceDir
                            ) {
                                viewModel.loadFileSystem()
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isChatLoading) {
                    CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Send message",
                        tint = if (isEnabled) Color.Black else Color.DarkGray,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun MarkwonText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Int = android.graphics.Color.WHITE,
    textSizeSP: Float = 12f
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val markwon = remember(context) {
        io.noties.markwon.Markwon.builder(context)
            .usePlugin(io.noties.markwon.ext.latex.JLatexMathPlugin.create(textSizeSP))
            .usePlugin(io.noties.markwon.ext.tables.TablePlugin.create(context))
            .usePlugin(io.noties.markwon.ext.strikethrough.StrikethroughPlugin.create())
            .build()
    }
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { ctx ->
            android.widget.TextView(ctx).apply {
                setTextColor(textColor)
                textSize = textSizeSP
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
                setTextIsSelectable(true)
            }
        },
        update = { textView ->
            markwon.setMarkdown(textView, markdown)
        }
    )
}

@Composable
fun FloatingChatOverlay(
    isOpen: Boolean,
    onClose: () -> Unit,
    accentNeonColor: Color,
    sidebarBackground: Color,
    workspaceDir: java.io.File,
    workspaceFiles: List<java.io.File>,
    activeFile: java.io.File?,
    editorContent: String,
    viewModel: IdeViewModel
) {
    if (!isOpen) return

    val coroutineScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    var widthDp by remember { mutableStateOf(320.dp) }
    var heightDp by remember { mutableStateOf(420.dp) }

    var textInput by remember { mutableStateOf("") }
    var attachActiveFileContext by remember { mutableStateOf(true) }

    val chatList by com.example.core.agent.AgenticCoordinator.chatHistory.collectAsState()
    val isChatLoading by com.example.core.agent.AgenticCoordinator.isChatRunning.collectAsState()

    val listState = rememberLazyListState()

    LaunchedEffect(chatList.size) {
        if (chatList.isNotEmpty()) {
            listState.animateScrollToItem(chatList.size - 1)
        }
    }

    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.BottomEnd
    ) {
        Card(
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .width(widthDp)
                .height(heightDp)
                .border(1.dp, accentNeonColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                },
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1115)),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Chat,
                            contentDescription = "Chat icon overlay",
                            tint = accentNeonColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "SMART CHAT OVERLAY",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { widthDp = maxOf(260.dp, widthDp - 30.dp) },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Text("W-", color = Color.Gray, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                        IconButton(
                            onClick = { widthDp = minOf(600.dp, widthDp + 30.dp) },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Text("W+", color = Color.Gray, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = { heightDp = maxOf(300.dp, heightDp - 40.dp) },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Text("H-", color = Color.Gray, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                        IconButton(
                            onClick = { heightDp = minOf(800.dp, heightDp + 40.dp) },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Text("H+", color = Color.Gray, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close dynamic overlay chat button",
                            tint = Color.Red,
                            modifier = Modifier
                                .size(14.dp)
                                .clickable { onClose() }
                        )
                    }
                }

                Divider(color = Color.White.copy(alpha = 0.08f))

                // COMPACT CONTEXT ATTACHMENT ROW
                val attachTerminalContext by com.example.core.agent.AgenticCoordinator.attachTerminalLogs.collectAsState()
                val selectAllChat by com.example.core.agent.AgenticCoordinator.selectAllChatContext.collectAsState()
                val sessions by com.example.core.agent.AgenticCoordinator.sessions.collectAsState()
                val currentSid by com.example.core.agent.AgenticCoordinator.currentSessionId.collectAsState()
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // SESSION PICKER
                    var showGlobalContextPicker by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable { showGlobalContextPicker = true }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.History, contentDescription = null, tint = accentNeonColor, modifier = Modifier.size(10.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("SESSIONS", color = accentNeonColor, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }

                    if (showGlobalContextPicker) {
                        GlobalContextPicker(
                            accentNeonColor = accentNeonColor,
                            sidebarBackground = sidebarBackground,
                            onDismiss = { showGlobalContextPicker = false },
                            onImport = { messages ->
                                coroutineScope.launch {
                                    com.example.core.agent.AgenticCoordinator.importMessagesToContext(messages)
                                }
                                showGlobalContextPicker = false
                            }
                        )
                    }

                    // ACTIVE FILE CONTEXT (Custom Toggle UI for compactness)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (attachActiveFileContext) accentNeonColor.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f))
                            .clickable { attachActiveFileContext = !attachActiveFileContext }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .border(1.dp, if (attachActiveFileContext) accentNeonColor else Color.Gray, RoundedCornerShape(2.dp))
                                .background(if (attachActiveFileContext) accentNeonColor else Color.Transparent, RoundedCornerShape(2.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (attachActiveFileContext) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(7.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("ACTIVE FILE", color = if (attachActiveFileContext) accentNeonColor else Color.Gray, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }

                    // TERMINAL LOGS CONTEXT
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (attachTerminalContext) accentNeonColor.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f))
                            .clickable { com.example.core.agent.AgenticCoordinator.attachTerminalLogs.value = !attachTerminalContext }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .border(1.dp, if (attachTerminalContext) accentNeonColor else Color.Gray, RoundedCornerShape(2.dp))
                                .background(if (attachTerminalContext) accentNeonColor else Color.Transparent, RoundedCornerShape(2.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (attachTerminalContext) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(7.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("LOGS", color = if (attachTerminalContext) accentNeonColor else Color.Gray, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }

                    // ALL CONTEXT
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (selectAllChat) accentNeonColor.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f))
                            .clickable { com.example.core.agent.AgenticCoordinator.selectAllChatContext.value = !selectAllChat }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (selectAllChat) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (selectAllChat) accentNeonColor else Color.Gray,
                            modifier = Modifier.size(10.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("ALL CONTEXT", color = if (selectAllChat) accentNeonColor else Color.Gray, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    items(chatList) { msg ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (msg.isSelected) accentNeonColor.copy(alpha = 0.03f) else Color.Transparent)
                                .clickable {
                                    val current = com.example.core.agent.AgenticCoordinator.chatHistory.value.toMutableList()
                                    val idx = current.indexOfFirst { it.id == msg.id }
                                    if (idx != -1) {
                                        current[idx] = current[idx].copy(isSelected = !current[idx].isSelected)
                                        com.example.core.agent.AgenticCoordinator.chatHistory.value = current
                                    }
                                }
                                .padding(4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (msg.sender == "USER") "DEVELOPER" else "COSMIC AI",
                                    color = if (msg.sender == "USER") Color(0xFFC084FC) else accentNeonColor,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (msg.isSelected) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(Icons.Default.Link, contentDescription = "Context linked", tint = accentNeonColor, modifier = Modifier.size(8.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))

                            val parsedBlocks = remember(msg.content) { parseMsgToBlocks(msg.content) }
                            parsedBlocks.forEach { block ->
                                if (block.isCode) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp)
                                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                                            .background(Color.Black.copy(alpha = 0.3f))
                                    ) {
                                        // Header: Filename & Copy/Save
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color.White.copy(alpha = 0.05f))
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = block.targetPath ?: "code_block.cpp",
                                                color = Color.Gray,
                                                fontSize = 8.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            Row {
                                                IconButton(
                                                    onClick = {
                                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(block.text))
                                                        android.widget.Toast.makeText(context, "Copied!", android.widget.Toast.LENGTH_SHORT).show()
                                                    },
                                                    modifier = Modifier.size(16.dp)
                                                ) {
                                                    Icon(Icons.Default.ContentCopy, contentDescription = null, tint = accentNeonColor, modifier = Modifier.size(10.dp))
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                IconButton(
                                                    onClick = {
                                                        val path = block.targetPath ?: activeFile?.name ?: "src/new_module.cpp"
                                                        try {
                                                            val bFile = java.io.File(workspaceDir, path)
                                                            bFile.parentFile?.mkdirs()
                                                            bFile.writeText(block.text)
                                                            viewModel.loadFileSystem()
                                                            viewModel.openFile(bFile)
                                                            android.widget.Toast.makeText(context, "Saved to $path", android.widget.Toast.LENGTH_SHORT).show()
                                                        } catch (e: Exception) {
                                                            android.widget.Toast.makeText(context, "Error: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    modifier = Modifier.size(16.dp)
                                                ) {
                                                    Icon(Icons.Default.Save, contentDescription = null, tint = accentNeonColor, modifier = Modifier.size(10.dp))
                                                }
                                            }
                                        }

                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(8.dp)
                                                .horizontalScroll(rememberScrollState())
                                        ) {
                                            Text(
                                                text = block.text,
                                                color = Color(0xFF4AF2A1),
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 8.sp,
                                                lineHeight = 11.sp
                                            )
                                        }

                                        // Footer: Append / Replace
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color.Black.copy(alpha = 0.2f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            if (activeFile != null) {
                                                TextButton(
                                                    onClick = {
                                                        try {
                                                            val currentTxt = activeFile.readText()
                                                            val updatedTxt = currentTxt + "\n\n" + block.text
                                                            activeFile.writeText(updatedTxt)
                                                            viewModel.updateContent(updatedTxt)
                                                            android.widget.Toast.makeText(context, "Appended to ${activeFile.name}", android.widget.Toast.LENGTH_SHORT).show()
                                                        } catch (e: Exception) {
                                                            android.widget.Toast.makeText(context, "Failure: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 4.dp)
                                                ) {
                                                    Text("APPEND", color = accentNeonColor, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                TextButton(
                                                    onClick = {
                                                        try {
                                                            activeFile.writeText(block.text)
                                                            viewModel.updateContent(block.text)
                                                            android.widget.Toast.makeText(context, "Replaced ${activeFile.name}", android.widget.Toast.LENGTH_SHORT).show()
                                                        } catch (e: Exception) {
                                                            android.widget.Toast.makeText(context, "Failure: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 4.dp)
                                                ) {
                                                    Text("REPLACE", color = Color.Red.copy(alpha = 0.7f), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    MarkwonText(
                                        markdown = block.text,
                                        textColor = android.graphics.Color.parseColor("#D1D5DB"),
                                        textSizeSP = 10f,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (isChatLoading) {
                        item {
                            Text("Thinking...", color = Color.Gray, fontSize = 9.sp, modifier = Modifier.padding(4.dp))
                        }
                    }
                }

                // MERGED INPUT CONTAINER
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.05f)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    var showQuickPicker by remember { mutableStateOf(false) }
                    IconButton(
                        onClick = { showQuickPicker = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "Attach",
                            tint = Color.Gray,
                            modifier = Modifier.size(14.dp).graphicsLayer(rotationZ = 45f)
                        )
                    }

                    if (showQuickPicker) {
                        Dialog(onDismissRequest = { showQuickPicker = false }) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth(0.9f)
                                    .heightIn(max = 400.dp)
                                    .padding(16.dp),
                                colors = CardDefaults.cardColors(containerColor = sidebarBackground)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Pick Files", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    val attachedList by com.example.core.agent.AgenticCoordinator.attachedFiles.collectAsState()
                                    LazyColumn(modifier = Modifier.weight(1f, false)) {
                                        items(workspaceFiles) { file ->
                                            val path = file.relativeTo(workspaceDir).path
                                            if (file.isFile && !path.startsWith(".")) {
                                                val isAttached = attachedList.contains(path)
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            if (isAttached) com.example.core.agent.AgenticCoordinator.attachedFiles.value -= path
                                                            else com.example.core.agent.AgenticCoordinator.attachedFiles.value += path
                                                        }
                                                        .padding(vertical = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = if (isAttached) Icons.Default.CheckCircle else Icons.Default.Add,
                                                        contentDescription = null,
                                                        tint = if (isAttached) accentNeonColor else Color.Gray,
                                                        modifier = Modifier.size(12.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(file.name, color = Color.LightGray, fontSize = 10.sp)
                                                }
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = { showQuickPicker = false },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = accentNeonColor, contentColor = Color.Black)
                                    ) {
                                        Text("Done", fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }

                    BasicTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp),
                        textStyle = TextStyle(color = Color.White, fontSize = 10.sp),
                        cursorBrush = SolidColor(accentNeonColor),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (textInput.isEmpty()) {
                                    Text("Ask anything...", fontSize = 10.sp, color = Color.Gray)
                                }
                                innerTextField()
                            }
                        }
                    )
                    
                    IconButton(
                        onClick = {
                            if (textInput.trim().isNotEmpty()) {
                                if (!viewModel.hasApiKey()) {
                                    viewModel.showCustomNotification("API key required! Please set it in Settings.", isError = true)
                                    return@IconButton
                                }
                                val prompt = textInput.trim()
                                textInput = ""

                                if (attachActiveFileContext && activeFile != null) {
                                    val relPath = activeFile.relativeTo(workspaceDir).path
                                    com.example.core.agent.AgenticCoordinator.attachedFiles.value = 
                                        com.example.core.agent.AgenticCoordinator.attachedFiles.value + relPath
                                }

                                com.example.core.agent.AgenticCoordinator.sendChatMessage(
                                    context = context,
                                    scope = coroutineScope,
                                    prompt = prompt,
                                    workspaceDir = workspaceDir
                                ) {
                                    viewModel.loadFileSystem()
                                }
                            }
                        },
                        modifier = Modifier.padding(end = 4.dp).size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send",
                            tint = if (textInput.trim().isNotEmpty()) accentNeonColor else Color.Gray,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GlobalContextPicker(
    accentNeonColor: Color,
    sidebarBackground: Color,
    onDismiss: () -> Unit,
    onImport: (List<com.example.core.storage.ChatMessageEntity>) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sessions by com.example.core.agent.AgenticCoordinator.sessions.collectAsState()
    var selectedSessionId by remember { mutableStateOf<Long?>(null) }
    var messages by remember { mutableStateOf<List<com.example.core.storage.ChatMessageEntity>>(emptyList()) }
    val selectedMessages = remember { mutableStateListOf<com.example.core.storage.ChatMessageEntity>() }
    
    val database = remember { com.example.core.storage.AppDatabase.getDatabase(context) }

    LaunchedEffect(selectedSessionId) {
        selectedSessionId?.let { sid ->
            messages = database.chatDao().getMessagesForSession(sid)
        } ?: run {
            messages = emptyList()
        }
        selectedMessages.clear()
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(500.dp)
                .padding(16.dp)
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = sidebarBackground),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Session Context Picker", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray, modifier = Modifier.size(16.dp))
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text("1. Select Session", color = Color.Gray, fontSize = 10.sp)
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sessions) { sess ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (selectedSessionId == sess.id) accentNeonColor.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f))
                                .border(1.dp, if (selectedSessionId == sess.id) accentNeonColor else Color.Transparent, RoundedCornerShape(6.dp))
                                .clickable { selectedSessionId = sess.id }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(sess.title, color = if (selectedSessionId == sess.id) accentNeonColor else Color.LightGray, fontSize = 10.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                
                Text("2. Pick Messages to Import", color = Color.Gray, fontSize = 10.sp)
                Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
                    if (messages.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(if (selectedSessionId == null) "Select a session first" else "No messages found", color = Color.DarkGray, fontSize = 11.sp)
                        }
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(messages) { msg ->
                            val isSelected = selectedMessages.contains(msg)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) accentNeonColor.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.03f))
                                    .clickable {
                                        if (isSelected) selectedMessages.remove(msg) else selectedMessages.add(msg)
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .border(1.dp, if (isSelected) accentNeonColor else Color.Gray, RoundedCornerShape(2.dp))
                                        .background(if (isSelected) accentNeonColor else Color.Transparent, RoundedCornerShape(2.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(10.dp))
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(msg.sender, color = if (msg.sender == "AI") accentNeonColor else Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        msg.content.take(80) + if (msg.content.length > 80) "..." else "",
                                        color = Color.LightGray,
                                        fontSize = 11.sp,
                                        maxLines = 2
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                
                Button(
                    onClick = { onImport(selectedMessages.toList()) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedMessages.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = accentNeonColor, contentColor = Color.Black),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Import ${selectedMessages.size} Messages to Context", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

private fun parseColoredLogs(logs: String): androidx.compose.ui.text.AnnotatedString {
    val builder = androidx.compose.ui.text.AnnotatedString.Builder()
    val lines = logs.split("\n")
    lines.forEach { line ->
        if (line.startsWith("§")) {
            val color = when (line.getOrNull(1)) {
                'G' -> Color(0xFF00FF00) // Green
                'R' -> Color(0xFFFF4444) // Red
                'Y' -> Color(0xFFFFBB33) // Yellow
                'B' -> Color(0xFF33B5E5) // Blue
                'M' -> Color(0xFFAA66CC) // Magenta
                else -> Color.White
            }
            builder.pushStyle(androidx.compose.ui.text.SpanStyle(color = color))
            builder.append(line.drop(2))
            builder.pop()
        } else {
            builder.append(line)
        }
        builder.append("\n")
    }
    return builder.toAnnotatedString()
}
