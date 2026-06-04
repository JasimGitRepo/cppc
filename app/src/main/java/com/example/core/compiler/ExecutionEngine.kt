package com.example.core.compiler

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ExecutionEngine manages the connection lifetime with the sandboxed IsolatedExecutionService,
 * sending run commands, and exposing execution terminal logs via StateFlow.
 */
class ExecutionEngine(private val context: Context) {

    private val TAG = "ExecutionEngine"

    private var targetService: Messenger? = null
    private var isBound = false

    private val _executionLogs = MutableStateFlow<String>("")
    val executionLogs: StateFlow<String> = _executionLogs

    private val _isExecuting = MutableStateFlow<Boolean>(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting

    private val _exitCode = MutableStateFlow<Int?>(null)
    val exitCode: StateFlow<Int?> = _exitCode

    // Handler to process incoming feedback logs from the isolated sandbox process
    private val clientHandler = Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            IsolatedExecutionService.MSG_FEEDBACK_LOG -> {
                val text = msg.data.getString(IsolatedExecutionService.KEY_LOG_TEXT) ?: ""
                appendLog(text)
                true
            }
            IsolatedExecutionService.MSG_FEEDBACK_SUCCESS -> {
                val code = msg.data.getInt(IsolatedExecutionService.KEY_RETURN_CODE, 0)
                _exitCode.value = code
                _isExecuting.value = false
                appendLog("\n§G[PROCESS EXITED] Return status: $code ✔\n")
                // Unbind to allow process to be recycled for next run
                unbindService()
                true
            }
            IsolatedExecutionService.MSG_FEEDBACK_ERROR -> {
                val err = msg.data.getString(IsolatedExecutionService.KEY_ERROR_MESSAGE) ?: "Unknown failure"
                _isExecuting.value = false
                appendLog("\n§R[PROCESS CRASHED] Error message: $err ✘\n")
                // Unbind to allow process to be recycled for next run
                unbindService()
                true
            }
            else -> false
        }
    }

    private val clientMessenger = Messenger(clientHandler)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            targetService = Messenger(service)
            isBound = true
            Log.d(TAG, "Successfully bound to isolated runner service.")
            
            // Register this client to receive logs
            registerClientWithService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            targetService = null
            isBound = false
            _isExecuting.value = false
            Log.w(TAG, "Isolated service disconnected unexpectedly (segfault or limits triggered).")
            appendLog("\n§R[SANDBOX EXITED ABRUPTLY] Note: This indicates the user's C++ code may have crashed under SIGSEGV or out-of-memory parameters.\n")
        }
    }

    fun bindService() {
        if (!isBound) {
            val intent = Intent(context, IsolatedExecutionService::class.java)
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    fun unbindService() {
        if (isBound) {
            context.unbindService(connection)
            isBound = false
            targetService = null
        }
    }

    private fun registerClientWithService() {
        try {
            val msg = Message.obtain(null, IsolatedExecutionService.MSG_REGISTER_CLIENT).apply {
                replyTo = clientMessenger
            }
            targetService?.send(msg)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register connection client", e)
        }
    }

    /**
     * Triggers load and execution of the compiled C++ shared object.
     */
    fun startExecution(soPath: String, args: String? = null) {
        if (!isBound || targetService == null) {
            bindService()
            appendLog("Establishing workspace environment connection... Please retry in a moment.\n")
            return
        }

        _executionLogs.value = ""
        _isExecuting.value = true
        _exitCode.value = null

        try {
            val msg = Message.obtain(null, IsolatedExecutionService.MSG_EXECUTE_LIBRARY).apply {
                replyTo = clientMessenger
                data = Bundle().apply {
                    putString(IsolatedExecutionService.KEY_LIBRARY_PATH, soPath)
                    if (args != null) {
                        putString(IsolatedExecutionService.KEY_LIBRARY_ARGS, args)
                    }
                }
            }
            targetService?.send(msg)
            Log.d(TAG, "Sent EXECUTE directive to sandbox with target path: $soPath args: $args")
        } catch (e: Exception) {
            _isExecuting.value = false
            appendLog("Failed to message isolated processor: ${e.localizedMessage}\n")
        }
    }

    /**
     * Abort active execution.
     */
    fun stopExecution() {
        if (!isBound || targetService == null) return
        try {
            val msg = Message.obtain(null, IsolatedExecutionService.MSG_CANCEL_EXECUTION)
            targetService?.send(msg)
            _isExecuting.value = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel process", e)
        }
    }

    fun clearLogs() {
        val hasSuccess = _executionLogs.value.contains("[ENGINE_SUCCESS]")
        if (hasSuccess) {
            _executionLogs.value = "[ENGINE_SUCCESS]\n"
        } else {
            _executionLogs.value = ""
        }
    }

    fun appendLogPublic(text: String) {
        _executionLogs.value += text
    }

    fun replaceLastLogPublic(text: String) {
        val currentLogs = _executionLogs.value
        val lastNewlineIndex = currentLogs.lastIndexOf("\n", currentLogs.length - 2)
        if (lastNewlineIndex != -1) {
            _executionLogs.value = currentLogs.substring(0, lastNewlineIndex + 1) + text + (if (!text.endsWith("\n")) "\n" else "")
        } else {
            _executionLogs.value = text + (if (!text.endsWith("\n")) "\n" else "")
        }
    }

    private fun appendLog(text: String) {
        _executionLogs.value += text
    }
}
