package com.example.core.compiler

import android.os.Build
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import java.io.File

/**
 * IsolatedExecutionService executes compiled client code in a sandboxed, isolated OS process.
 * If user code crashes, dereferences NULL, or leaks memory, the OS garbage-collects this process 
 * without bringing down the main parent IDE.
 */
class IsolatedExecutionService : Service() {

    private val TAG = "IsolatedService"

    companion object {
        // Command messages
        const val MSG_REGISTER_CLIENT = 1
        const val MSG_EXECUTE_LIBRARY = 2
        const val MSG_CANCEL_EXECUTION = 3

        // Feedback messages
        const val MSG_FEEDBACK_LOG = 100
        const val MSG_FEEDBACK_SUCCESS = 101
        const val MSG_FEEDBACK_ERROR = 102

        // Bundle argument keys
        const val KEY_LIBRARY_PATH = "lib_path"
        const val KEY_LIBRARY_ARGS = "lib_args"
        const val KEY_LOG_TEXT = "log_text"
        const val KEY_RETURN_CODE = "return_code"
        const val KEY_ERROR_MESSAGE = "error_msg"
    }

    private var clientMessenger: Messenger? = null
    private var isExecuting = false

    // Messenger that handles incoming workspace directives from the main app
    private val incomingHandler = Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            MSG_REGISTER_CLIENT -> {
                clientMessenger = msg.replyTo
                sendProgressLog("Sandbox execution environment connected successfully.")
                true
            }
            MSG_EXECUTE_LIBRARY -> {
                val libPath = msg.data.getString(KEY_LIBRARY_PATH) ?: ""
                val libArgs = msg.data.getString(KEY_LIBRARY_ARGS)
                executeIsolatedLibrary(libPath, libArgs)
                true
            }
            MSG_CANCEL_EXECUTION -> {
                sendProgressLog("\n[Sandbox execution canceled by user.]")
                isExecuting = false
                true
            }
            else -> false
        }
    }

    private val serviceMessenger = Messenger(incomingHandler)

    override fun onBind(intent: Intent?): IBinder? {
        Log.i(TAG, "Binding isolated workspace executor.")
        return serviceMessenger.binder
    }

    private fun executeIsolatedLibrary(path: String, args: String? = null) {
        if (isExecuting) {
            sendErrorFeedback("Execution already active in this sandbox process.")
            return
        }

        isExecuting = true
        Thread {
            try {
                sendProgressLog("\n[SANDBOX ENGINE] Initializing JVM-Native boundaries...\n")
                if (!args.isNullOrBlank()) {
                    sendProgressLog("[SANDBOX ENGINE] Running with parameters: $args\n")
                }
                var libFile = File(path)
                if (!libFile.exists()) {
                    val fileName = libFile.name
                    val fallbackWorkspaceOut = File(filesDir, "workspace/output/$fileName")
                    val fallbackSandbox = File(cacheDir, "compilation_sandbox/$fileName")
                    val fallbackStorageOut = File("/storage/emulated/0/CPPC/output/$fileName")
                    
                    if (fallbackWorkspaceOut.exists()) {
                        libFile = fallbackWorkspaceOut
                    } else if (fallbackSandbox.exists()) {
                        libFile = fallbackSandbox
                    } else if (fallbackStorageOut.exists()) {
                        libFile = fallbackStorageOut
                    }
                }

                if (!libFile.exists()) {
                    sendErrorFeedback("Library file missing under specified workspace target.")
                    isExecuting = false
                    return@Thread
                }

                sendProgressLog("[SANDBOX ENGINE] Resolving linkage references: ${libFile.name}\n")

                val elfError = getElfBinaryValidationError(libFile)
                if (elfError != null) {
                    sendErrorFeedback(elfError)
                } else {
                    // Modern Android: Copy dynamic library to internal partition before calling System.load()
                    // Loading directly from external space or storage can fail permission/W^X checks on API 29+.
                    val executionDir = File(cacheDir, "execution_sandbox")
                    if (!executionDir.exists()) {
                        executionDir.mkdirs()
                    }

                    // Copy and preload dynamic standard C++ library (libc++_shared.so) to satisfy dependencies
                    val stlSource = File(filesDir, "toolchain/sysroot/usr/lib/libc++.so")
                    if (stlSource.exists()) {
                        val sandboxStl = File(executionDir, "libc++_shared.so")
                        try {
                            if (!sandboxStl.exists() || sandboxStl.length() != stlSource.length()) {
                                stlSource.copyTo(sandboxStl, overwrite = true)
                                sandboxStl.setReadable(true, false)
                                sandboxStl.setExecutable(true, false)
                            }
                            Log.i(TAG, "Preloading standard C++ runtime environment from: ${sandboxStl.absolutePath}")
                            System.load(sandboxStl.absolutePath)
                        } catch (stlEx: Throwable) {
                            Log.w(TAG, "Preload warning for standard C++ runtime environment", stlEx)
                        }
                    }

                    // Clean up any old staging libraries to free storage space
                    try {
                        executionDir.listFiles()?.forEach { file ->
                            if (file.name.endsWith(".so") && file.name != "libc++_shared.so") {
                                file.delete()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Staging cleanup warning", e)
                    }

                    val uniqueName = "lib_${System.currentTimeMillis()}_" + libFile.name
                    val secureLibFile = File(executionDir, uniqueName)
                    try {
                        libFile.copyTo(secureLibFile, overwrite = true)
                        secureLibFile.setExecutable(true, false)
                        secureLibFile.setReadable(true, false)
                    } catch (copyEx: Exception) {
                        Log.w(TAG, "Non-fatal library sandbox staging warning", copyEx)
                    }

                    val targetToLoad = if (secureLibFile.exists()) secureLibFile else libFile
                    realNativeExecution(targetToLoad, args)
                }

            } catch (e: Throwable) {
                sendErrorFeedback("Sandbox exception during linkage:\n❌ Error: ${e.localizedMessage ?: e.message}\n• Class: ${e.javaClass.canonicalName}")
                Log.e(TAG, "Linkage crash inside isolated sandbox", e)
            } finally {
                isExecuting = false
            }
        }.start()
    }

    private fun getElfBinaryValidationError(file: File): String? {
        if (!file.exists()) {
            return "Binary linkage failure: File does not exist at expected path: ${file.absolutePath}"
        }
        if (!file.isFile) {
            return "Binary linkage failure: Target path is a directory or not a standard file: ${file.absolutePath}"
        }
        val length = file.length()
        if (length == 0L) {
            return "Binary linkage failure: File is completely empty (0 bytes). Check if compiler build target succeeded without zeroing outputs."
        }
        if (length < 100) {
            return "Binary linkage failure: File is too small ($length bytes) to be a valid ELF binary object. Expected at least 100 bytes for basic ELF headers."
        }
        return try {
            val bytes = ByteArray(4)
            java.io.FileInputStream(file).use { input ->
                val read = input.read(bytes)
                if (read != 4) {
                    "Binary linkage failure: Could not read 4-byte magic signature. Only read $read bytes."
                } else if (bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.toByte() || bytes[2] != 'L'.toByte() || bytes[3] != 'F'.toByte()) {
                    val hexMagic = bytes.joinToString(" ") { String.format("%02X", it) }
                    "Binary linkage failure: Invalid file descriptor magic number signature: [$hexMagic]. Expected standard ELF format magic bytes: [7F 45 4C 46]."
                } else {
                    null // Valid ELF
                }
            }
        } catch (e: Exception) {
            "Binary linkage failure: Exception occurred while parsing ELF binary validation markers: ${e.message}"
        }
    }

    private fun isValidElfBinary(file: File): Boolean {
        return getElfBinaryValidationError(file) == null
    }

    private fun realNativeExecution(libFile: File, args: String? = null) {
        val stdoutFile = File(cacheDir, "isolated_stdout_${System.currentTimeMillis()}.log")
        try {
            if (stdoutFile.exists()) stdoutFile.delete()
            stdoutFile.createNewFile()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize stdout log file", e)
        }

        // Start background tailing thread to capture standard printf logs
        var isThreadActive = true
        val logReaderThread = Thread {
            try {
                var lastPointer: Long = 0
                while (isThreadActive) {
                    if (stdoutFile.exists() && stdoutFile.length() > lastPointer) {
                        stdoutFile.inputStream().use { stream ->
                            stream.skip(lastPointer)
                            val available = stream.available()
                            if (available > 0) {
                                val buffer = ByteArray(available)
                                val read = stream.read(buffer)
                                if (read > 0) {
                                    val text = String(buffer, 0, read)
                                    sendProgressLog(text)
                                    lastPointer += read
                                }
                            }
                        }
                    }
                    Thread.sleep(30)
                }
                // Final residual read flush
                if (stdoutFile.exists() && stdoutFile.length() > lastPointer) {
                    stdoutFile.inputStream().use { stream ->
                        stream.skip(lastPointer)
                        val available = stream.available()
                        if (available > 0) {
                            val buffer = ByteArray(available)
                            val read = stream.read(buffer)
                            if (read > 0) {
                                val text = String(buffer, 0, read)
                                sendProgressLog(text)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Log reader polling crash", e)
            }
        }
        logReaderThread.start()

        try {
            // Load file inside isolated process container safely
            sendProgressLog("[SANDBOX ENGINE] Loading shared dynamic library via System.load()...\n")
            
            // Primary loading attempt
            try {
                System.load(libFile.absolutePath)
            } catch (loadErr: UnsatisfiedLinkError) {
                Log.w(TAG, "Primary secure load path failed, trying fallback...", loadErr)
                throw loadErr
            }

            sendProgressLog("[SANDBOX ENGINE] File loaded successfully. Invoking entrypoint triggerJniEntrypoint()...\n")

            // JNI Bridge entry point execution with redirected paths and arguments
            val returnCode = triggerJniEntrypoint(stdoutFile.absolutePath, args ?: "")
            
            // Sleep slightly to let stdout thread catch up
            Thread.sleep(100)
            isThreadActive = false
            logReaderThread.join(500)

            sendProgressLog("\n[SANDBOX ENGINE] Exit context returned code: $returnCode.\n")
            sendSuccessFeedback(returnCode)
        } catch (unsatisfied: UnsatisfiedLinkError) {
            isThreadActive = false
            try { logReaderThread.join(200) } catch (ignored: Exception) {}
            
            val errorMsg = unsatisfied.localizedMessage ?: unsatisfied.message ?: ""
            val diagnosis = StringBuilder()
            diagnosis.append("Unsatisfied Linkage Error occurred:\n")
            diagnosis.append("❌ Error: $errorMsg\n\n")
            diagnosis.append("--- DIAGNOSTIC DATA ---\n")
            diagnosis.append("• Library Path: ${libFile.absolutePath}\n")
            diagnosis.append("• Binary Size: ${libFile.length()} bytes\n")
            if (libFile.exists()) {
                diagnosis.append("• File Permissions: Read=${libFile.canRead()}, Write=${libFile.canWrite()}, Execute=${libFile.canExecute()}\n")
                // Parse ELF class/endianness headers
                try {
                    java.io.FileInputStream(libFile).use { input ->
                        val header = ByteArray(16)
                        val readBytes = input.read(header)
                        if (readBytes >= 6 && header[0] == 0x7F.toByte() && header[1] == 'E'.toByte() && header[2] == 'L'.toByte() && header[3] == 'F'.toByte()) {
                            val elfClass = if (header[4].toInt() == 1) "32-bit" else if (header[4].toInt() == 2) "64-bit" else "Unknown class (${header[4]})"
                            val endianness = if (header[5].toInt() == 1) "Little Endian" else if (header[5].toInt() == 2) "Big Endian" else "Unknown endianness (${header[5]})"
                            diagnosis.append("• ELF Class: $elfClass\n")
                            diagnosis.append("• ELF Endianness: $endianness\n")
                        } else {
                            diagnosis.append("• ELF Status: Invalid header signature.\n")
                        }
                    }
                } catch (e: Exception) {
                    diagnosis.append("• ELF Parser Alert: ${e.message}\n")
                }
            } else {
                diagnosis.append("• Status: File missing at run-time!\n")
            }
            diagnosis.append("• System ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}\n")
            diagnosis.append("• Hardware: ${Build.MANUFACTURER} / ${Build.MODEL}\n")
            diagnosis.append("• Android: API ${Build.VERSION.SDK_INT} (OS ${Build.VERSION.RELEASE})\n")
            diagnosis.append("-----------------------\n")
            diagnosis.append("💡 Recovery Blueprint:\n")
            if (errorMsg.contains("32-bit instead of 64-bit") || errorMsg.contains("64-bit instead of 32-bit")) {
                diagnosis.append("👉 ABI architecture mismatch: Please verify that you compiled the C++ program utilizing the correct compiler architecture flag match for your host CPU.\n")
            } else if (errorMsg.contains("not found") || errorMsg.contains("cannot find")) {
                diagnosis.append("👉 Missing symbols or dependencies: Verify all necessary libraries are referenced correctly.\n")
            } else {
                diagnosis.append("👉 Dynamic linkage method signature mismatch: Ensure JNI function prototypes use exact signature: jint Java_com_example_core_compiler_IsolatedExecutionService_triggerJniEntrypoint(JNIEnv*, jobject, jstring, jstring)\n")
            }
            
            val completeReport = diagnosis.toString()
            sendErrorFeedback(completeReport)
            Log.e(TAG, "Unsatisfied linkage diagnostic output:\n$completeReport", unsatisfied)
        } catch (e: Exception) {
            isThreadActive = false
            try { logReaderThread.join(200) } catch (ignored: Exception) {}
            
            val errorMsg = e.localizedMessage ?: e.message ?: ""
            val diagnosis = StringBuilder()
            diagnosis.append("Runtime Sandbox Execution Exception:\n")
            diagnosis.append("❌ Error: $errorMsg\n\n")
            diagnosis.append("--- EXCEPTION DATA ---\n")
            diagnosis.append("• Class Type: ${e.javaClass.canonicalName}\n")
            diagnosis.append("• Sandbox Target: ${libFile.absolutePath}\n")
            diagnosis.append("• Environment: ABI=${Build.SUPPORTED_ABIS.getOrNull(0)}, API=${Build.VERSION.SDK_INT}\n")
            diagnosis.append("----------------------\n")
            if (e is NullPointerException) {
                diagnosis.append("💡 Null Pointer Reference: Review object instances/references inside compiling boundaries.\n")
            }
            
            val completeReport = diagnosis.toString()
            sendErrorFeedback(completeReport)
            Log.e(TAG, "Runtime execution exception diagnostic output:\n$completeReport", e)
        } finally {
            isThreadActive = false
            try {
                if (stdoutFile.exists()) {
                    stdoutFile.delete()
                }
            } catch (ignored: Exception) {}
        }
    }

    /**
     * Placeholder Native Method JNI Bridge
     * In a live context containing actual clang native output libs, JNI binds to the loaded dynamic symbol.
     */
    private external fun triggerJniEntrypoint(stdoutPath: String, args: String?): Int

    private fun sendProgressLog(text: String) {
        try {
            clientMessenger?.send(Message.obtain(null, MSG_FEEDBACK_LOG).apply {
                data = Bundle().apply {
                    putString(KEY_LOG_TEXT, text)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed communication feedback", e)
        }
    }

    private fun sendSuccessFeedback(exitCode: Int) {
        try {
            clientMessenger?.send(Message.obtain(null, MSG_FEEDBACK_SUCCESS).apply {
                data = Bundle().apply {
                    putInt(KEY_RETURN_CODE, exitCode)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Feedback sync failure", e)
        }
    }

    private fun sendErrorFeedback(message: String) {
        try {
            clientMessenger?.send(Message.obtain(null, MSG_FEEDBACK_ERROR).apply {
                data = Bundle().apply {
                    putString(KEY_ERROR_MESSAGE, message)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Feedback sync failure", e)
        }
    }
}
