package com.example.core.compiler

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * CompilationEngine manages native on-device clang++ compilation processes on a background thread pool,
 * parsing build parameters, and feeding real-time stderr/stdout into a reactive console feed.
 */
class CompilationEngine(private val context: Context, private val extractor: ToolchainExtractor) {

    private val TAG = "CompilationEngine"

    private val _consoleOutput = MutableStateFlow<String>("")
    val consoleOutput: StateFlow<String> = _consoleOutput

    private val _compilationStatus = MutableStateFlow<BuildStatus>(BuildStatus.Idle)
    val compilationStatus: StateFlow<BuildStatus> = _compilationStatus

    sealed class BuildStatus {
        object Idle : BuildStatus()
        object Compiling : BuildStatus()
        data class Success(val outLibraryPath: String) : BuildStatus()
        data class Error(val logs: String) : BuildStatus()
    }

    /**
     * Clear active terminal outputs
     */
    fun clearLogs() {
        _consoleOutput.value = ""
    }

    /**
     * Public helper to append custom logs for simulations or status reporting
     */
    fun appendLogPublic(msg: String) {
        _consoleOutput.value += msg
    }

    /**
     * Public helper to override status
     */
    fun setCompilationStatusPublic(status: BuildStatus) {
        _compilationStatus.value = status
    }

    /**
     * Executes the clang++ compiler process.
     * Takes source path and compilation parameters to generate libusercode.so
     */
    suspend fun compileCpp(
        sourceFile: File,
        optimizeFlags: String = "-O2",
        compilerFlags: List<String> = listOf("-Wall", "-Wextra", "-shared", "-fPIC"),
        customIncludePaths: List<String> = emptyList(),
        customLibPaths: List<String> = emptyList(),
        customLinkedLibs: List<String> = emptyList(),
        selfHealDepth: Int = 0
    ): BuildStatus = withContext(Dispatchers.IO) {
        _compilationStatus.value = BuildStatus.Compiling
        clearLogs()
        appendLog("§B----------------------------------------\n")
        appendLog("§M[BUILD START] Target: arm64-v8a Shared Library\n")
        appendLog("§WCompiler: ${extractor.binDir.absolutePath}/${extractor.binClangPlusPlus}\n")

        val sandboxDir = File(context.cacheDir, "compilation_sandbox")
        if (!sandboxDir.exists()) sandboxDir.mkdirs()

        // Copy source file to sandbox
        val sandboxSource = File(sandboxDir, sourceFile.name)
        sourceFile.copyTo(sandboxSource, overwrite = true)

        val cleanName = sourceFile.nameWithoutExtension.replace("[^a-zA-Z0-9]".toRegex(), "")
        val targetLibrary = File(sandboxDir, "lib${cleanName}.so")
        if (targetLibrary.exists()) {
            targetLibrary.delete()
        }

        // Append dynamic JNI entrypoint mapping to the end of the sandbox source file
        try {
            val jniBridge = """

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef __cplusplus
// Declare custom C++ entry point with standard C++ language linkage (weak reference)
__attribute__((weak)) void execute_${cleanName}();

extern "C" {
#endif

// Declare standard and custom entry points as weak symbols with C linkage
__attribute__((weak)) int main_entry();
__attribute__((weak)) int main(int argc, char** argv);

// Bulletproof runner selecting whichever main signature is compiled
int run_cpp_program(int argc, char** argv) {
    if (&execute_${cleanName} != nullptr) {
        execute_${cleanName}();
        return 0;
    } else if (&main_entry != nullptr) {
        return main_entry();
    } else if (&main != nullptr) {
        return main(argc, argv);
    }
    return 0;
}

// JNI Entrypoint invoked by modern JVM when calling triggerJniEntrypoint
__attribute__((visibility("default"))) jint Java_com_example_core_compiler_IsolatedExecutionService_triggerJniEntrypoint(JNIEnv* env, jobject /*thiz*/, jstring stdoutPath, jstring jArgs) {
    // Process stdout/stderr redirection to log for terminal feedback loop
    if (stdoutPath != nullptr) {
        const char* pathChars = env->GetStringUTFChars(stdoutPath, nullptr);
        if (pathChars != nullptr) {
            freopen(pathChars, "w", stdout);
            freopen(pathChars, "w", stderr);
            env->ReleaseStringUTFChars(stdoutPath, pathChars);
        }
        // Force unbuffered stream writing so console receives text instantly
        setvbuf(stdout, nullptr, _IONBF, 0);
        setvbuf(stderr, nullptr, _IONBF, 0);
    }

    // Parse space-separated arguments into clean argv array
    int argc = 1;
    char* argv_storage[32] = { (char*)"program" };
    char** argv = argv_storage;
    if (jArgs != nullptr) {
        const char* argsChars = env->GetStringUTFChars(jArgs, nullptr);
        if (argsChars != nullptr && strlen(argsChars) > 0) {
            char* argsCopy = strdup(argsChars);
            char* token = strtok(argsCopy, " ");
            while (token != nullptr && argc < 31) {
                argv_storage[argc++] = token;
                token = strtok(nullptr, " ");
            }
            env->ReleaseStringUTFChars(jArgs, argsChars);
        }
    }

    int ret = run_cpp_program(argc, argv);

    // Flush to ensure nothing lingers in cache/descriptors
    fflush(stdout);
    fflush(stderr);

    return ret;
}

// Modern dynamic registration bypasses namespace/strict classloader limits on Android
static int registerNativeMethods(JNIEnv* env) {
    JNINativeMethod methods[] = {
        {(char*)"triggerJniEntrypoint", (char*)"(Ljava/lang/String;Ljava/lang/String;)I", (void*)Java_com_example_core_compiler_IsolatedExecutionService_triggerJniEntrypoint}
    };
    jclass clazz = env->FindClass("com/example/core/compiler/IsolatedExecutionService");
    if (clazz == nullptr) return -1;
    if (env->RegisterNatives(clazz, methods, 1) < 0) return -1;
    return 0;
}

// JNI_OnLoad entry point automatically executed upon dynamic library loading phase
__attribute__((visibility("default"))) jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK) {
        registerNativeMethods(env);
    }
    return JNI_VERSION_1_6;
}

#ifdef __cplusplus
}
#endif
"""
            sandboxSource.appendText(jniBridge)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append dynamic JNI bridge mapping to sandbox source file", e)
        }

        // Load custom compilation command template from preferences
        val prefs = context.getSharedPreferences("compiler_prefs", android.content.Context.MODE_PRIVATE)
        val defaultTemplate = "{compiler} -target aarch64-linux-android34 -O2 --ld-path={linker} -Wall -Wextra -std=c++17 -shared -fPIC --sysroot=/storage/emulated/0/CPPC/headers/sysroot -resource-dir=/storage/emulated/0/CPPC/headers/lib/clang/21 -isystem /storage/emulated/0/CPPC/headers/sysroot/usr/include/c++/v1 -isystem {workspace_include} -L/storage/emulated/0/CPPC/headers/sysroot/usr/lib -L{workspace_lib} -o {target_so}"
        val template = prefs.getString("custom_compile_command", defaultTemplate) ?: defaultTemplate

        val clangBinary = File(extractor.binDir, extractor.binClangPlusPlus)
        val linkerPath = File(extractor.binDir, extractor.binLld)

        // Option 2: Create a symlink named "ld.lld" pointing to the actual generic lld binary
        // This is necessary because lld expects argv[0] to be "ld.lld" to decide on the elf flavor.
        val symlinkLld = File(context.filesDir, "ld.lld")
        try {
            if (symlinkLld.exists() || java.nio.file.Files.isSymbolicLink(symlinkLld.toPath())) {
                symlinkLld.delete()
            }
            android.system.Os.symlink(linkerPath.absolutePath, symlinkLld.absolutePath)
        } catch (e: Exception) {
            try {
                linkerPath.inputStream().use { input ->
                    symlinkLld.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                symlinkLld.setExecutable(true)
            } catch (ex: Exception) {
                // fallback to original if everything fails
            }
        }
        val actualLinkerPath = if (symlinkLld.exists()) symlinkLld.absolutePath else linkerPath.absolutePath

        val forceTwoStage = prefs.getBoolean("force_two_stage_compile", false)
        if (forceTwoStage) {
            val success = runTwoStageCompilation(
                prefs, sandboxDir, sandboxSource, targetLibrary,
                clangBinary, actualLinkerPath, customIncludePaths,
                customLibPaths, customLinkedLibs
            )
            if (success) {
                val cleanOutputsName = sourceFile.nameWithoutExtension.replace("[^a-zA-Z0-9]".toRegex(), "")
                val finalOutput = File(extractor.workspaceOutputDir, "lib${cleanOutputsName}.so")
                finalOutput.parentFile?.mkdirs()
                targetLibrary.copyTo(finalOutput, overwrite = true)
                
                appendLog("§G[BUILD SUCCESS] Generated file: ${targetLibrary.name} 🎉\n")
                _compilationStatus.value = BuildStatus.Success(targetLibrary.absolutePath)
                return@withContext BuildStatus.Success(targetLibrary.absolutePath)
            } else {
                appendLog("[ERROR_HEADER] 2-stage compilation failed.\n")
                _compilationStatus.value = BuildStatus.Error("2-stage compilation failed.")
                return@withContext BuildStatus.Error("2-stage compilation failed.")
            }
        }

        // Resolve placeholders in the template:
        var resolvedCommand = template
            .replace("{compiler}", clangBinary.absolutePath)
            .replace("{linker}", actualLinkerPath)
            .replace("{sysroot}", extractor.sysrootDir.absolutePath)
            .replace("{workspace_include}", extractor.workspaceIncludeDir.absolutePath)
            .replace("{workspace_lib}", extractor.workspaceLibDir.absolutePath)
            .replace("{target_so}", targetLibrary.absolutePath)

        val finalArgs = ArrayList<String>()
        
        // Split resolved command into shell arguments, preserving quoted arguments
        val regex = Regex("[^\\s\"']+|\"([^\"]*)\"|'([^']*)'")
        val matches = regex.findAll(resolvedCommand)
        for (m in matches) {
            val part = m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[0] } }
            if (part.isNotBlank()) {
                finalArgs.add(part)
            }
        }

        // Add programmatic custom include paths as -isystem to prevent library header resolution warnings
        customIncludePaths.filter { it.isNotBlank() }.forEach { path ->
            finalArgs.add("-isystem")
            finalArgs.add(path)
        }

        // Add custom library paths
        customLibPaths.filter { it.isNotBlank() }.forEach { path ->
            finalArgs.add("-L")
            finalArgs.add(path)
        }

        // Add the source file; check if placeholder was used or append at the end
        if (template.contains("{source_cpp}")) {
            for (i in finalArgs.indices) {
                if (finalArgs[i].contains("{source_cpp}")) {
                    finalArgs[i] = finalArgs[i].replace("{source_cpp}", sandboxSource.absolutePath)
                }
            }
        } else {
            finalArgs.add(sandboxSource.absolutePath)
        }

        // Standard link libraries if not already requested in the template
        val standardLibs = listOf("-lc++", "-lm")
        standardLibs.forEach { lib ->
            if (!resolvedCommand.contains(lib)) {
                finalArgs.add(lib)
            }
        }

        customLinkedLibs.filter { it.isNotBlank() }.forEach { lib ->
            val formattedLib = if (lib.startsWith("-l")) lib else "-l$lib"
            if (!resolvedCommand.contains(formattedLib)) {
                finalArgs.add(formattedLib)
            }
        }

        appendLog("Full compilation command executed:\n${finalArgs.joinToString(" ")}\n\n")

        try {
            val processBuilder = ProcessBuilder(finalArgs)
            // Redirect error stream so we can bind stderr and stdout consistently
            processBuilder.redirectErrorStream(true)
            processBuilder.directory(sandboxDir)

            val process = processBuilder.start()

            // Stream logs asynchronously
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            val fullLogs = StringBuilder()

            while (reader.readLine().also { line = it } != null) {
                appendLog("$line\n")
                fullLogs.append(line).append("\n")
            }

            val exitCode = process.waitFor()
            appendLog("\n§M[COMPILER PROCESS RETURNED CODE: $exitCode]\n")

            if (exitCode == 0) {
                // Compile finished successfully.
                if (!targetLibrary.exists()) {
                    throw java.io.FileNotFoundException("Compiler processes returned 0 code, but no binary built at: ${targetLibrary.absolutePath}")
                }
                
                // Copy to CPPC workspace for persistence/mirroring
                val cleanOutputsName = sourceFile.nameWithoutExtension.replace("[^a-zA-Z0-9]".toRegex(), "")
                val finalOutput = java.io.File(extractor.workspaceOutputDir, "lib${cleanOutputsName}.so")
                finalOutput.parentFile?.mkdirs()
                targetLibrary.copyTo(finalOutput, overwrite = true)
                
                appendLog("§G[BUILD SUCCESS] Generated file: ${targetLibrary.name} 🎉\n")
                _compilationStatus.value = BuildStatus.Success(targetLibrary.absolutePath)
                return@withContext BuildStatus.Success(targetLibrary.absolutePath)
            } else {
                appendLog("§Y[FALLBACK] Single-stage compilation failed. Trying 2-stage compilation fallback...\n")
                val success = runTwoStageCompilation(
                    prefs, sandboxDir, sandboxSource, targetLibrary,
                    clangBinary, actualLinkerPath, customIncludePaths,
                    customLibPaths, customLinkedLibs
                )
                if (success) {
                    val cleanOutputsName = sourceFile.nameWithoutExtension.replace("[^a-zA-Z0-9]".toRegex(), "")
                    val finalOutput = File(extractor.workspaceOutputDir, "lib${cleanOutputsName}.so")
                    finalOutput.parentFile?.mkdirs()
                    targetLibrary.copyTo(finalOutput, overwrite = true)
                    
                    appendLog("§G[BUILD SUCCESS] Generated file: ${targetLibrary.name} 🎉\n")
                    _compilationStatus.value = BuildStatus.Success(targetLibrary.absolutePath)
                    return@withContext BuildStatus.Success(targetLibrary.absolutePath)
                }

                appendLog("[ERROR_HEADER] Build failed with exit code $exitCode. Check the logs above for syntax errors or missing headers.\n")
                
                val errorStr = fullLogs.toString()
                val errorRegex = Regex("^(.*?):(\\d+):(\\d+): fatal error: '(.*?)' file not found", RegexOption.MULTILINE)
                val match = errorRegex.find(errorStr)

                if (match != null && selfHealDepth < 10) {
                    val errorSourceFile = match.groupValues[1]
                    val errorLine = match.groupValues[2].toInt()
                    val missingFile = match.groupValues[4]
                    
                    val missingFileName = missingFile.substringAfterLast("/")
                    
                    appendLog("\n§Y[SELF-HEAL] Detected missing header: '$missingFile' at line $errorLine.\n")
                    appendLog("§Y[SELF-HEAL] Querying fast-search database for '$missingFileName'...\n")
                    
                    var bestCandidates = mutableListOf<File>()
                    try {
                        val db = com.example.core.storage.AppDatabase.getDatabase(context)
                        val entities = db.headerFileDao().findByName(missingFileName)
                        
                        var maxScore = -1
                        for (entity in entities) {
                            val candidate = File(entity.filepath)
                            if (candidate.exists() && candidate.absolutePath != errorSourceFile) {
                                if (missingFile == missingFileName || candidate.absolutePath.endsWith("/$missingFile")) {
                                    bestCandidates.clear()
                                    bestCandidates.add(candidate)
                                    break
                                } else {
                                    val candParts = candidate.absolutePath.split("/")
                                    val missParts = missingFile.split("/")
                                    var score = 0
                                    var cIdx = candParts.size - 1
                                    var mIdx = missParts.size - 1
                                    while (cIdx >= 0 && mIdx >= 0 && candParts[cIdx] == missParts[mIdx]) {
                                        score++
                                        cIdx--
                                        mIdx--
                                    }
                                    if (score > maxScore) {
                                        maxScore = score
                                        bestCandidates.clear()
                                        bestCandidates.add(candidate)
                                    } else if (score == maxScore && score > 0) {
                                        if (!bestCandidates.contains(candidate)) {
                                            bestCandidates.add(candidate)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        appendLog("§R[SELF-HEAL] Database error: ${e.message}\n")
                    }
                    
                    if (bestCandidates.size == 1) {
                        val foundFile = bestCandidates[0]
                        appendLog("§G[SELF-HEAL] SUCCESS! Found '$missingFile' at: ${foundFile.absolutePath}\n")
                        appendLog("§G[SELF-HEAL] Patching broken path with actual absolute path...\n")
                        
                        try {
                            var sourceToPatch = File(errorSourceFile)
                            if (!sourceToPatch.isAbsolute) {
                                sourceToPatch = File(sandboxDir, errorSourceFile)
                            }
                            if (sourceToPatch.absolutePath == sandboxSource.absolutePath) {
                                sourceToPatch = sourceFile
                            }
                            
                            if (sourceToPatch.exists()) {
                                val lines = sourceToPatch.readLines().toMutableList()
                                if (errorLine > 0 && errorLine <= lines.size) {
                                    val oldLine = lines[errorLine - 1]
                                    lines[errorLine - 1] = "#include \"${foundFile.absolutePath}\""
                                    sourceToPatch.writeText(lines.joinToString("\n") + "\n")
                                    
                                    appendLog("§G[SELF-HEAL] Patched line $errorLine.\n")
                                    appendLog("§G[SELF-HEAL] Restarting compilation process (Depth: ${selfHealDepth + 1})...\n\n")
                                    
                                    return@withContext compileCpp(
                                        sourceFile = sourceFile,
                                        optimizeFlags = optimizeFlags,
                                        compilerFlags = compilerFlags,
                                        customIncludePaths = customIncludePaths,
                                        customLibPaths = customLibPaths,
                                        customLinkedLibs = customLinkedLibs,
                                        selfHealDepth = selfHealDepth + 1
                                    )
                                } else {
                                    appendLog("§R[SELF-HEAL] Failed to patch: Line number out of bounds.\n")
                                }
                            } else {
                                appendLog("§R[SELF-HEAL] Failed to patch: Source file $errorSourceFile does not exist.\n")
                            }
                        } catch (e: Exception) {
                            appendLog("§R[SELF-HEAL] Exception during patch: ${e.message}\n")
                        }
                    } else if (bestCandidates.size > 1) {
                        appendLog("§M[SELF-HEAL] Ambiguity detected. Multiple candidates found for '$missingFile':\n")
                        bestCandidates.forEachIndexed { idx, cand ->
                            appendLog("   [${idx + 1}] ${cand.absolutePath}\n")
                        }
                        appendLog("§M[SELF-HEAL] Please manually adjust the #include statement to use the correct absolute path to resolve this ambiguity.\n")
                    } else {
                        appendLog("§R[SELF-HEAL] File '$missingFile' could not be found in headers directory. Unable to self-fix.\n")
                    }
                }
                
                _compilationStatus.value = BuildStatus.Error(errorStr)
                return@withContext BuildStatus.Error(errorStr)
            }
        } catch (e: Exception) {
            val logErr = "Process spawn exception: ${e.localizedMessage}\n"
            Log.e(TAG, "Process builder runtime exception", e)
            
            appendLog(logErr)
            _compilationStatus.value = BuildStatus.Error(logErr)
            BuildStatus.Error(logErr)
        }
    }

    private fun replaceLastLog(msg: String) {
        val currentLogs = _consoleOutput.value
        val lastNewlineIndex = currentLogs.lastIndexOf("\n", currentLogs.length - 2)
        if (lastNewlineIndex != -1) {
            _consoleOutput.value = currentLogs.substring(0, lastNewlineIndex + 1) + msg + "\n"
        } else {
            _consoleOutput.value = msg + "\n"
        }
    }

    private fun appendLog(msg: String) {
        _consoleOutput.value += msg
    }

    private fun runTwoStageCompilation(
        prefs: android.content.SharedPreferences,
        sandboxDir: File,
        sandboxSource: File,
        targetLibrary: File,
        clangBinary: File,
        actualLinkerPath: String,
        customIncludePaths: List<String>,
        customLibPaths: List<String>,
        customLinkedLibs: List<String>
    ): Boolean {
        appendLog("§M[2-STAGE COMPILATION START]\n")
        
        val cleanName = sandboxSource.nameWithoutExtension.replace("[^a-zA-Z0-9]".toRegex(), "")
        val tempObjFile = File(sandboxDir, "${cleanName}.o")
        if (tempObjFile.exists()) tempObjFile.delete()
        
        // --- STAGE 1: COMPILE ---
        val defaultClangCmd = "{compiler} -target aarch64-linux-android34 -O2 -c -Wall -Wextra -std=c++17 -fPIC -D__ANDROID_API__=34 --sysroot=/storage/emulated/0/CPPC/headers/sysroot -resource-dir=/storage/emulated/0/CPPC/headers/lib/clang/21 -isystem /storage/emulated/0/CPPC/headers/sysroot/usr/include/c++/v1 -isystem {workspace_include} {source_cpp} -o {output_o}"
        val clangTemplate = prefs.getString("custom_clang_command", defaultClangCmd) ?: defaultClangCmd
        
        var resolvedClang = clangTemplate
            .replace("{compiler}", clangBinary.absolutePath)
            .replace("{sysroot}", extractor.sysrootDir.absolutePath)
            .replace("{workspace_include}", extractor.workspaceIncludeDir.absolutePath)
            .replace("{source_cpp}", sandboxSource.absolutePath)
            .replace("{output_o}", tempObjFile.absolutePath)
            
        val clangArgs = ArrayList<String>()
        val regex = Regex("[^\\s\"']+|\"([^\"]*)\"|'([^']*)'")
        var matches = regex.findAll(resolvedClang)
        for (m in matches) {
            val part = m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[0] } }
            if (part.isNotBlank()) {
                clangArgs.add(part)
            }
        }
        
        // Add customIncludePaths
        customIncludePaths.filter { it.isNotBlank() }.forEach { path ->
            clangArgs.add("-isystem")
            clangArgs.add(path)
        }
        
        appendLog("Stage 1 - Compiling to Object File:\n${clangArgs.joinToString(" ")}\n\n")
        val p1 = ProcessBuilder(clangArgs).redirectErrorStream(true).directory(sandboxDir).start()
        val r1 = BufferedReader(InputStreamReader(p1.inputStream))
        var line: String?
        while (r1.readLine().also { line = it } != null) {
            appendLog("  [Clang] $line\n")
        }
        val exit1 = p1.waitFor()
        appendLog("Stage 1 complete. Exit code: $exit1\n")
        if (exit1 != 0 || !tempObjFile.exists()) {
            appendLog("§R[STAGE 1 FAILED] Compilation to object file failed.\n")
            return false
        }
        
        // --- STAGE 2: LINK ---
        val defaultLinkerCmd = "{linker} -shared --sysroot=/storage/emulated/0/CPPC/headers/sysroot -L/storage/emulated/0/CPPC/headers/sysroot/usr/lib/aarch64-linux-android/34 -L/storage/emulated/0/CPPC/headers/sysroot/usr/lib/aarch64-linux-android -L{workspace_lib} {output_o} -o {target_so} -lc++_static -lc -lm -ldl"
        val linkerTemplate = prefs.getString("custom_linker_command", defaultLinkerCmd) ?: defaultLinkerCmd
        
        var resolvedLinker = linkerTemplate
            .replace("{linker}", actualLinkerPath)
            .replace("{sysroot}", extractor.sysrootDir.absolutePath)
            .replace("{workspace_lib}", extractor.workspaceLibDir.absolutePath)
            .replace("{output_o}", tempObjFile.absolutePath)
            .replace("{target_so}", targetLibrary.absolutePath)
            
        val linkerArgs = ArrayList<String>()
        matches = regex.findAll(resolvedLinker)
        for (m in matches) {
            val part = m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[0] } }
            if (part.isNotBlank()) {
                linkerArgs.add(part)
            }
        }
        
        // Add customLibPaths
        customLibPaths.filter { it.isNotBlank() }.forEach { path ->
            linkerArgs.add("-L")
            linkerArgs.add(path)
        }
        
        // Add standard libs and customLinkedLibs if not already present
        val standardLibs = listOf("-lc++_static", "-lm")
        standardLibs.forEach { lib ->
            val alreadyPresent = if (lib == "-lc++_static") {
                resolvedLinker.contains("-lc++") || resolvedLinker.contains("-lc++_static")
            } else {
                resolvedLinker.contains(lib)
            }
            if (!alreadyPresent) {
                linkerArgs.add(lib)
            }
        }
        
        customLinkedLibs.filter { it.isNotBlank() }.forEach { lib ->
            val formattedLib = if (lib.startsWith("-l")) lib else "-l$lib"
            if (!resolvedLinker.contains(formattedLib)) {
                linkerArgs.add(formattedLib)
            }
        }
        
        appendLog("Stage 2 - Linking Object File to Shared Library:\n${linkerArgs.joinToString(" ")}\n\n")
        val p2 = ProcessBuilder(linkerArgs).redirectErrorStream(true).directory(sandboxDir).start()
        val r2 = BufferedReader(InputStreamReader(p2.inputStream))
        while (r2.readLine().also { line = it } != null) {
            appendLog("  [Linker] $line\n")
        }
        val exit2 = p2.waitFor()
        appendLog("Stage 2 complete. Exit code: $exit2\n")
        if (exit2 != 0 || !targetLibrary.exists()) {
            appendLog("§R[STAGE 2 FAILED] Linking failed.\n")
            return false
        }
        
        appendLog("§G[2-STAGE COMPILATION SUCCESSFUL]\n")
        return true
    }
}
