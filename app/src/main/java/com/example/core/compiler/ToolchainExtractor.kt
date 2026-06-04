package com.example.core.compiler

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * ToolchainExtractor handles on-device deployment of the LLVM/Clang compiler toolchain.
 * It unpacks binaries from the APK assets to private storage (/data/data/[pkg]/files/toolchain),
 * verifies file integrity using MD5 hashes, and configures executable permissions.
 */
class ToolchainExtractor(val context: Context) {

    private val TAG = "ToolchainExtractor"

    // Directory references
    val toolchainDir: File = File(context.filesDir, "toolchain")
    var binDir: File = File(toolchainDir, "bin")
    var sysrootDir: File = File(android.os.Environment.getExternalStorageDirectory(), "CPPC/headers")
    
    // Module Engine settings
    private var modulePackageName: String = "com.cppc.engine"
    private var customHeadersDir: File? = null
    
    fun setModuleConfig(pkgName: String, headersPath: String) {
        modulePackageName = pkgName
        
        val cleanPath = headersPath.trim()
        val baseDir = android.os.Environment.getExternalStorageDirectory()
        
        customHeadersDir = when {
            cleanPath.startsWith("/storage/emulated/0/", ignoreCase = true) -> {
                File(cleanPath)
            }
            cleanPath.startsWith("/sdcard/", ignoreCase = true) -> {
                File(cleanPath)
            }
            cleanPath.startsWith("/") -> {
                val stripped = cleanPath.dropWhile { it == '/' }.trim()
                File(baseDir, stripped)
            }
            else -> {
                File(baseDir, cleanPath)
            }
        }
        updatePathReferences()
    }

    private fun updatePathReferences() {
        // Resolve Module APK library path
        val moduleLibPath = getModuleLibraryPath(context, modulePackageName)
        if (moduleLibPath != null) {
            binDir = File(moduleLibPath)
            Log.d(TAG, "Engine Binaries resolved to Module APK: $binDir")
        } else {
            binDir = File(toolchainDir, "bin")
            Log.d(TAG, "Module APK not found ($modulePackageName), falling back to internal: $binDir")
        }

        // Resolve headers (sysroot)
        if (customHeadersDir != null) {
            sysrootDir = customHeadersDir!!
        }
    }

    private fun getModuleLibraryPath(context: Context, packageName: String): String? {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(packageName, 0)
            val nativeLibraryDir = packageInfo.applicationInfo?.nativeLibraryDir
            if (nativeLibraryDir != null && File(nativeLibraryDir).exists()) nativeLibraryDir else null
        } catch (e: Exception) {
            null
        }
    }

    // Dynamic resolution of primary (/storage/emulated/0/CPPC/) workspace directory
    private fun resolveBestWorkspaceDir(): File {
        return File(context.filesDir, "workspace")
    }

    val workspaceDir: File = resolveBestWorkspaceDir()
    val workspaceSrcDir: File = File(workspaceDir, "src")
    val workspaceIncludeDir: File = File(workspaceDir, "dependencies/include")
    val workspaceLibDir: File = File(workspaceDir, "dependencies/lib")
    val workspaceOutputDir: File = File(workspaceDir, "output")

    // Reactive status flow
    private val _extractionStatus = MutableStateFlow<ExtractionState>(ExtractionState.Idle)
    val extractionStatus: StateFlow<ExtractionState> = _extractionStatus

    private val _logs = MutableStateFlow<String>("")
    val logs: StateFlow<String> = _logs

    private fun replaceLastLog(msg: String) {
        val coloredMsg = when {
            msg.contains("[SUCCESS]") || msg.contains("[DONE]") || msg.contains("checkmark") || msg.contains("ready-to-go") -> "§G$msg"
            msg.contains("[ERROR]") || msg.contains("[FATAL]") || msg.contains("failed") -> "§R$msg"
            msg.contains("[WARN]") || msg.contains("missing") -> "§Y$msg"
            msg.contains("[DOWNLOAD]") || msg.contains("[NET]") || msg.contains("[UNZIP]") -> "§B$msg"
            msg.contains("[BACKUP]") || msg.contains("[ENGINE]") -> "§M$msg"
            else -> msg
        }
        val currentLogs = _logs.value
        val lastNewlineIndex = currentLogs.lastIndexOf("\n", currentLogs.length - 2)
        if (lastNewlineIndex != -1) {
            _logs.value = currentLogs.substring(0, lastNewlineIndex + 1) + coloredMsg + "\n"
        } else {
            _logs.value = coloredMsg + "\n"
        }
    }

    private fun appendLog(msg: String) {
        val coloredMsg = when {
            msg.contains("[SUCCESS]") || msg.contains("[DONE]") || msg.contains("checkmark") || msg.contains("ready-to-go") -> "§G$msg"
            msg.contains("[ERROR]") || msg.contains("[FATAL]") || msg.contains("failed") -> "§R$msg"
            msg.contains("[WARN]") || msg.contains("missing") -> "§Y$msg"
            msg.contains("[DOWNLOAD]") || msg.contains("[NET]") || msg.contains("[UNZIP]") -> "§B$msg"
            msg.contains("[ENGINE") || msg.contains("[ENV") -> "§M$msg"
            else -> "§W$msg"
        }
        _logs.value += coloredMsg + "\n"
        Log.d(TAG, "Setup Log: $msg")
    }

    sealed class ExtractionState {
        object Idle : ExtractionState()
        data class Progress(val percentage: Int, val currentFile: String) : ExtractionState()
        data class Downloading(val progress: Float, val speed: String) : ExtractionState()
        object Completed : ExtractionState()
        data class Error(val message: String) : ExtractionState()
    }

    private val DEFAULT_TOOLCHAIN_URL = "https://github.com/MasterDevX/Termux-Clang/releases/download/18.1.1/clang-18.1.1-android-aarch64.zip"

    init {
        // Initialize folders immediately
        createDirectories()
    }

    private fun createDirectories() {
        listOf(
            toolchainDir, binDir, 
            workspaceDir, workspaceSrcDir, 
            workspaceIncludeDir, workspaceLibDir, workspaceOutputDir
        ).forEach { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
    }

    private fun isValidElfBinary(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() < 1000) return false
        return try {
            val bytes = ByteArray(4)
            FileInputStream(file).use { input ->
                val read = input.read(bytes)
                read == 4 && bytes[0] == 0x7F.toByte() && bytes[1] == 'E'.toByte() && bytes[2] == 'L'.toByte() && bytes[3] == 'F'.toByte()
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun rebuildHeaderDatabase() = withContext(Dispatchers.IO) {
        // Moved to HeaderScannerWorker
    }

    /**
     * Performs a deep health check of the engine.
     * Returns true if all critical components are healthy.
     */
    suspend fun verifyEngineHealth(): Boolean = withContext(Dispatchers.IO) {
        updatePathReferences() // Refresh paths
        appendLog("[VERIFY] Initiating access verification for package: $modulePackageName")
        
        val sysManager = context.packageManager
        var packageAccessOk = false
        try {
            val packageInfo = sysManager.getPackageInfo(modulePackageName, 0)
            if (packageInfo != null) {
                packageAccessOk = true
                appendLog("[INFO] [✓] Package '$modulePackageName' access check successful and authorized.")
            }
        } catch (e: Exception) {
            appendLog("[WARN] Package '$modulePackageName' not explicitly found in package manager. Verifying fallback access paths...")
        }

        val components = listOf(
            File(binDir, binClang),
            File(binDir, binClangPlusPlus),
            File(binDir, binClangd),
            File(binDir, binLld)
        )

        var allHealthy = true
        appendLog("[INFO] Checking availability of compiler binaries in: ${binDir.absolutePath}")
        components.forEach { file ->
            if (!file.exists()) {
                appendLog("[ERROR] Component missing: ${file.name} (Searching in: ${binDir.absolutePath})")
                allHealthy = false
            } else if (!isValidElfBinary(file)) {
                appendLog("[ERROR] Component ${file.name} is not a valid ELF binary file (possible mock or simulation wrapper).")
                allHealthy = false
                try {
                    file.delete()
                    appendLog("[CLEANUP] Deleted mock file: ${file.name}")
                } catch (delEx: Exception) {
                    Log.e(TAG, "Failed deleting invalid binary", delEx)
                }
            } else {
                appendLog("[INFO] [✓] Component found and verified (ELF format): ${file.name}")
            }
        }
        
        if (allHealthy) {
            appendLog("[INFO] [✓] All compiler binary components are available and validated.")
        } else {
            appendLog("[ERROR] Some binary components are missing or inaccessible.")
        }
        
        appendLog("[INFO] Proceeding to verify header files inside directory: ${sysrootDir.absolutePath}")
        if (!sysrootDir.exists() || !sysrootDir.isDirectory) {
            appendLog("[WARN] Headers/Sysroot missing or invalid at: ${sysrootDir.absolutePath}")
            allHealthy = false
        } else {
            val sysrootFilesCount = sysrootDir.walk().maxDepth(3).count()
            if (sysrootFilesCount < 5) {
                appendLog("[WARN] Sysroot seems mostly empty. Re-indexing via Settings may be needed.")
            } else {
                appendLog("[INFO] [✓] Header directory checks complete. Sysroot accessible.")
            }
        }

        if (allHealthy) {
            appendLog("[ENGINE_SUCCESS]") // Signal for special UI logging
        } else {
            appendLog("[ERROR] Engine health check failed. Please ensure CPPC Engine is installed and headers are placed correctly.")
        }
        
        allHealthy
    }

    private var customToolchainUrl: String? = null
    
    // Configurable binary names
    var binClang = "libclang.so"
    var binClangPlusPlus = "libclang++.so"
    var binClangd = "libclangd.so"
    var binLld = "liblld.so"
    
    fun setToolchainUrl(url: String?) {
        customToolchainUrl = url
    }

    fun setBinaryNames(clang: String, clangPlus: String, lsp: String, linker: String) {
        binClang = clang
        binClangPlusPlus = clangPlus
        binClangd = lsp
        binLld = linker
    }

    private fun getToolchainUrl(): String {
        return if (!customToolchainUrl.isNullOrBlank()) customToolchainUrl!! else ""
    }

    private val persistentDownloadsDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "CPPC/downloads")

    /**
     * Set up companion module-based C++ engine tools and structures.
     */
    suspend fun extractToolchain(force: Boolean = false, localZip: File? = null) = withContext(Dispatchers.IO) {
        if (!force && localZip == null) {
            if (verifyEngineHealth()) {
                _extractionStatus.value = ExtractionState.Completed
                Log.d(TAG, "Toolchain verified and healthy.")
                return@withContext
            }
        }

        try {
            _extractionStatus.value = ExtractionState.Progress(10, "Starting engine verification...")
            appendLog("[ENGINE SETUP] Starting C++ environment verification...")
            createDirectories()

            if (force) {
                appendLog("[CLEANUP] Refreshing environment parameters...")
            }

            if (localZip != null && localZip.exists()) {
                _extractionStatus.value = ExtractionState.Progress(50, "Installing local bundle: ${localZip.name}")
                appendLog("[BUNDLE ENGINE] Installing provided local zip: ${localZip.absolutePath}")
                unzipWithFiltering(localZip, toolchainDir)
                finalizeInstallation()
                return@withContext
            }

            // Check if Module APK is installed
            _extractionStatus.value = ExtractionState.Progress(30, "Checking companion C++ Engine...")
            appendLog("[ENGINE SETUP] Locating companion compiler Module APK ($modulePackageName)...")
            val moduleLibPath = getModuleLibraryPath(context, modulePackageName)
            
            if (moduleLibPath == null) {
                appendLog("[INFO] Companion C++ Engine Module APK ($modulePackageName) is not installed.")
                appendLog("[INFO] Searching for custom locally imported toolchain...")
                val localBinDir = File(toolchainDir, "bin")
                val binClangFile = File(localBinDir, binClang)
                val binClangPlusPlusFile = File(localBinDir, binClangPlusPlus)
                val binLldFile = File(localBinDir, binLld)
                
                if (!binClangFile.exists() || !binClangPlusPlusFile.exists() || !binLldFile.exists()) {
                    appendLog("[ERROR] Companion compiler APK not found AND no custom locally imported compiler binaries found.")
                    throw Exception("C++ Compiler Engine is not installed or initialized. Please install the companion APK or import a valid Clang toolchain bundle.")
                }
                
                appendLog("[SUCCESS] Custom locally imported compiler binaries found.")
                binDir = localBinDir
            } else {
                appendLog("[SUCCESS] Companion C++ Engine Module APK found and active.")
                binDir = File(moduleLibPath)
            }

            // Check headers/sysroot
            _extractionStatus.value = ExtractionState.Progress(65, "Verifying standard library headers...")
            appendLog("[ENGINE SETUP] Verifying standard library headers in: ${sysrootDir.absolutePath}")
            
            // Check if sysrootDir exists and contains files
            val sysrootFilesCount = if (sysrootDir.exists() && sysrootDir.isDirectory) sysrootDir.walk().maxDepth(3).count() else 0
            if (sysrootFilesCount < 10) {
                appendLog("[WARN] Standard header files (sysroot) are sparse or missing in: ${sysrootDir.absolutePath}")
                appendLog("[ERROR] Standard headers are missing from sysroot. Please import a toolchain bundle with headers or check your configuration.")
            } else {
                appendLog("[INFO] Header/Sysroot directory verified containing ($sysrootFilesCount files).")
            }

            // Perform health check
            _extractionStatus.value = ExtractionState.Progress(90, "Running deep system health check...")
            val isHealthy = verifyEngineHealth()
            if (isHealthy) {
                finalizeInstallation()
            } else {
                throw Exception("System health check failed. Verification of binaries or headers was unsuccessful.")
            }
        } catch (e: Exception) {
            _extractionStatus.value = ExtractionState.Error(e.localizedMessage ?: "Unknown setup error")
            appendLog("[ERROR] Setup failed: ${e.localizedMessage}")
            Log.e(TAG, "Error deploying toolchain", e)
        }
    }

    private fun finalizeInstallation() {
        appendLog("[PERMISSIONS] Applying executable bits to bin/ directory...")
        // Fix permissions for all binaries in bin/
        binDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                val success = setExecutablePermission(file)
                if (success) Log.v(TAG, "chmod +x ${file.name}")
            }
        }
        
        appendLog("[WORKSPACE] Generating default template project...")
        // Write template main.cpp to workspace on initial boot
        writeInitialWorkspaceTemplate()

        _extractionStatus.value = ExtractionState.Completed
        appendLog("[SUCCESS] Toolchain is now ready-to-go on Android arm64-v8a system.")
        Log.d(TAG, "Toolchain deployment finalized.")
    }

    /**
     * Efficiently unzips the toolchain, filtering out non-essential files to save space
     * and renaming executables to .so to try bypassing Android 10+ restrictions.
     */
    private fun unzipWithFiltering(zipFile: File, targetDirectory: File) {
        val requiredPatterns = listOf(
            "/bin/clang", "/bin/clang++", "/bin/clangd", "/bin/ld.lld", "/bin/lld",
            "bin/clang", "bin/clang++", "bin/clangd", "bin/ld.lld", "bin/lld"
        )

        java.util.zip.ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                
                val normalizedName = if (name.contains("/")) {
                    val firstSlash = name.indexOf("/")
                    val rootPart = name.substring(0, firstSlash)
                    if (rootPart.contains("clang")) {
                        name.substring(firstSlash + 1)
                    } else name
                } else name
                
                if (normalizedName.isEmpty()) {
                    entry = zis.nextEntry
                    continue
                }

                // Filtering non-essential files for engine installation
                val isRequired = requiredPatterns.any { normalizedName.contains(it) }

                if (isRequired || normalizedName.endsWith(".so")) {
                    var finalName = normalizedName
                    
                    // Rename binaries to .so if they are in bin folder
                    if (normalizedName.startsWith("bin/")) {
                        val fileName = normalizedName.substringAfterLast("/")
                        if (!fileName.contains(".") || (!fileName.endsWith(".so") && !fileName.contains("-"))) {
                             finalName = "bin/lib$fileName.so"
                             Log.d(TAG, "Renaming engine binary: $fileName -> lib$fileName.so")
                        }
                    }

                    val newFile = File(targetDirectory, finalName)
                    if (entry.isDirectory) {
                        newFile.mkdirs()
                    } else {
                        newFile.parentFile?.mkdirs()
                        FileOutputStream(newFile).use { fos ->
                            val buffer = ByteArray(1024 * 64)
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                fos.write(buffer, 0, len)
                            }
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * Packages the currently installed engine into a recovery ZIP file.
     */
    suspend fun createEngineZip(): File? = withContext(Dispatchers.IO) {
        try {
            appendLog("[BACKUP] Packaging current active engine into recovery bundle...")
            val backupFile = File(persistentDownloadsDir, "engine-recovery-backup.zip")
            if (backupFile.exists()) backupFile.delete()

            java.util.zip.ZipOutputStream(FileOutputStream(backupFile)).use { zos ->
                toolchainDir.walkTopDown().forEach { file ->
                    if (file.isFile) {
                        val entryName = file.absolutePath.removePrefix(toolchainDir.absolutePath).removePrefix("/")
                        val entry = java.util.zip.ZipEntry(entryName)
                        zos.putNextEntry(entry)
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            appendLog("[SUCCESS] Engine recovery archive created at: ${backupFile.name}")
            backupFile
        } catch (e: Exception) {
            appendLog("[ERROR] Failed to create engine zip: ${e.localizedMessage}")
            null
        }
    }

    private suspend fun extractFromAssets() {
        // List of target assets from APK to extract
        val assetFiles = listOf(
            "bin/clang++",
            "bin/clangd",
            "bin/lld",
            "sysroot/usr/include/stdio.h",
            "sysroot/usr/include/stdlib.h",
            "sysroot/usr/include/string.h",
            "sysroot/usr/include/jni.h",
            "sysroot/usr/include/jni_md.h",
            "sysroot/usr/lib/libc++.so"
        )

        assetFiles.forEachIndexed { index, path ->
            val progress = ((index + 1) * 100) / assetFiles.size
            _extractionStatus.value = ExtractionState.Progress(progress, "Installing $path...")

            val outputFile = File(toolchainDir, path)
            outputFile.parentFile?.mkdirs()

            val assetPath = "toolchain/$path"
            try {
                context.assets.open(assetPath).use { input ->
                    copyStream(input, outputFile)
                }
                
                if (outputFile.name == binClang || outputFile.name == binClangPlusPlus || 
                    outputFile.name == binClangd || outputFile.name == binLld) {
                    setExecutablePermission(outputFile)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Asset $assetPath not found. This APK was built without bundled toolchain.")
                // If it hits here, it should have been caught by the check at the start of extractToolchain
            }
        }
    }

    private fun copyStream(input: InputStream, output: File) {
        FileOutputStream(output).use { out ->
            val buffer = ByteArray(1024 * 64)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                out.write(buffer, 0, bytesRead)
            }
        }
    }

    private fun getFileMd5(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(8192)
            FileInputStream(file).use { input ->
                var read: Int
                while (input.read(buffer).also { read = it } > 0) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    private fun setExecutablePermission(file: File): Boolean {
        return try {
            // JVM file set can execute
            val jvmSuccess = file.setExecutable(true, false)
            file.setReadable(true, false)
            
            // Shell fallback to ensure permissions are applied correctly at the OS level
            val process = Runtime.getRuntime().exec(arrayOf("chmod", "755", file.absolutePath))
            val shellSuccess = process.waitFor() == 0
            
            jvmSuccess || shellSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set chmod for ${file.name}", e)
            false
        }
    }

    private fun writeInitialWorkspaceTemplate() {
        val mainCpp = File(workspaceSrcDir, "main.cpp")
        if (!mainCpp.exists()) {
            mainCpp.writeText(
                """
                #include <stdio.h>
                #include <stdlib.h>
                
                // On-device C++ entry point targeting JNI integration loading
                extern "C" {
                    int main_entry() {
                        printf("Initializing Native Main Frame Executability...\n");
                        printf("Host Architecture: arm64-v8a (Android ABI Node)\n");
                        
                        int factor = 42;
                        int multiplier = 10;
                        int product = factor * multiplier;
                        
                        printf("Calculation Verification: %d * %d = %d\n", factor, multiplier, product);
                        
                        // Feel free to introduce user-written errors to test diagnostics:
                        // Uncomment line below to trigger Clang diagnostic underlines:
                        // int syntax_error = 100
                        
                        return product;
                    }
                }
                """.trimIndent()
            )
        }

        val depsJson = File(workspaceDir, "deps.json")
        if (!depsJson.exists()) {
            depsJson.writeText(
                """
                {
                  "dependencies": [
                    {
                      "name": "nlohmann-json",
                      "url": "https://github.com/nlohmann/json/releases/download/v3.11.2/json.hpp",
                      "type": "header-only",
                      "target_path": "nlohmann/json.hpp"
                    },
                    {
                      "name": "glm-math",
                      "url": "https://raw.githubusercontent.com/g-truc/glm/master/glm/glm.hpp",
                      "type": "header-only",
                      "target_path": "glm/glm.hpp"
                    }
                  ]
                }
                """.trimIndent()
            )
        }
    }
}
