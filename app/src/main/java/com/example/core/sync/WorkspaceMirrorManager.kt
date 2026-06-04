package com.example.core.sync

import android.content.Context
import android.util.Log
import com.example.core.compiler.ToolchainExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class WorkspaceMirrorManager(
    private val context: Context,
    private val extractor: ToolchainExtractor
) {
    private val TAG = "WorkspaceMirrorManager"
    
    val sandboxDir: File = extractor.workspaceDir
    val cppcDir: File = File("/storage/emulated/0/CPPC")

    // State trackers to prevent infinite loop synchronization bounce
    private val previousSandboxState = mutableMapOf<String, FileRecord>()
    private val previousCppcState = mutableMapOf<String, FileRecord>()
    
    private val syncLock = Any()
    
    private val _syncEvents = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(replay = 0)
    val syncEvents = _syncEvents.asSharedFlow()
    
    data class FileRecord(
        val relativePath: String,
        val lastModified: Long,
        val length: Long,
        val sha256: String
    )

    init {
        // Ensure directories exist
        try {
            if (!sandboxDir.exists()) sandboxDir.mkdirs()
            if (!cppcDir.exists()) cppcDir.mkdirs()
        } catch (e: Exception) {
            Log.e(TAG, "Initialization directory creation failed", e)
        }
    }

    /**
     * Launch the continuous background synchronization loop in IO context
     */
    fun startSyncLoop(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            // Give 2 seconds on app startup to allow first-time extraction/setup
            delay(2000)
            
            // Prime the initial states
            synchronized(syncLock) {
                previousSandboxState.putAll(getDirectoryState(sandboxDir))
                previousCppcState.putAll(getDirectoryState(cppcDir))
                _syncEvents.tryEmit(Unit)
            }
            
            Log.d(TAG, "Workspace bidirectional sync loop started.")
            while (true) {
                try {
                    performReconciliation()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in sync cycle loop", e)
                }
                delay(1200) // 1.2 second check frequency
            }
        }
    }

    /**
     * Public gateway to force direct file synchronization sync pass
     */
    fun triggerSyncNow() {
        Thread {
            try {
                performReconciliation()
            } catch (e: Exception) {
                Log.e(TAG, "Failed manual sync call", e)
            }
        }.start()
    }

    private fun performReconciliation() {
        synchronized(syncLock) {
            if (!cppcDir.exists()) {
                try {
                    if (!cppcDir.mkdirs() && !cppcDir.exists()) {
                        return
                    }
                } catch (e: Exception) {
                    // If CPPC directory is not writable (no permissions), compile/work solely in sandbox and exit
                    return
                }
            }

            val currentSandbox = getDirectoryState(sandboxDir)
        val currentCppc = getDirectoryState(cppcDir)

        // Part 1: Determine changes on CPPC side relative to last sync checkpoint
        val cppcAdded = currentCppc.keys - previousCppcState.keys
        val cppcDeleted = previousCppcState.keys - currentCppc.keys
        val cppcModified = currentCppc.filter { (path, rec) ->
            val prev = previousCppcState[path]
            prev != null && (prev.lastModified != rec.lastModified || prev.length != rec.length || prev.sha256 != rec.sha256)
        }.keys

        // Part 2: Determine changes in Sandbox relative to last sync checkpoint
        val sbAdded = currentSandbox.keys - previousSandboxState.keys
        val sbDeleted = previousSandboxState.keys - currentSandbox.keys
        val sbModified = currentSandbox.filter { (path, rec) ->
            val prev = previousSandboxState[path]
            prev != null && (prev.lastModified != rec.lastModified || prev.length != rec.length || prev.sha256 != rec.sha256)
        }.keys

        // ----------------- SECTION A: PROPAGATE CPPC CHANGES TO SANDBOX -----------------
        val resolvedCppcMoves = mutableMapOf<String, String>() // deletedPath -> addedPath
        val remainingCppcAdded = cppcAdded.toMutableSet()
        val remainingCppcDeleted = cppcDeleted.toMutableSet()

        // Detect CPPC moves/renames via content MD5/SHA match of added vs deleted paths
        for (addedPath in cppcAdded) {
            val addedRec = currentCppc[addedPath] ?: continue
            val matchingDeletedPath = remainingCppcDeleted.firstOrNull { delPath ->
                val delRec = previousCppcState[delPath]
                delRec != null && delRec.sha256 == addedRec.sha256 && delRec.length == addedRec.length
            }
            if (matchingDeletedPath != null) {
                resolvedCppcMoves[matchingDeletedPath] = addedPath
                remainingCppcAdded.remove(addedPath)
                remainingCppcDeleted.remove(matchingDeletedPath)
            }
        }

        // Apply detected CPPC moves to Sandbox
        for ((delPath, addPath) in resolvedCppcMoves) {
            val srcFile = File(sandboxDir, delPath)
            val destFile = File(sandboxDir, addPath)
            if (srcFile.exists()) {
                destFile.parentFile?.mkdirs()
                val moveOk = srcFile.renameTo(destFile)
                if (moveOk) {
                    Log.i(TAG, "BiSync: CPPC Rename detected. Replicated Sandbox move: $delPath -> $addPath")
                } else {
                    // Fallback to copy/delete if renameTo failed (e.g. cross volume)
                    try {
                        srcFile.copyTo(destFile, overwrite = true)
                        srcFile.delete()
                    } catch (e: Exception) {
                        Log.e(TAG, "Fallback move failed", e)
                    }
                }
            }
        }

        // Apply simple CPPC deletions in Sandbox
        for (delPath in remainingCppcDeleted) {
            val file = File(sandboxDir, delPath)
            if (file.exists()) {
                file.delete()
                Log.i(TAG, "BiSync: CPPC Delete detected. Deleted in Sandbox: $delPath")
            }
        }

        // Apply simple CPPC additions/modifications to Sandbox
        for (path in (remainingCppcAdded + cppcModified)) {
            val srcFile = File(cppcDir, path)
            val destFile = File(sandboxDir, path)
            if (srcFile.exists()) {
                destFile.parentFile?.mkdirs()
                try {
                    srcFile.copyTo(destFile, overwrite = true)
                    Log.i(TAG, "BiSync: CPPC Change sync-write to Sandbox: $path")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed syncing CPPC file $path to sandbox", e)
                }
            }
        }

        // ----------------- SECTION B: PROPAGATE SANDBOX CHANGES TO CPPC -----------------
        // Get the post-reconciled Sandbox state before applying Sandbox -> CPPC sync
        val postCppcSandbox = getDirectoryState(sandboxDir)

        val freshSbAdded = postCppcSandbox.keys - previousSandboxState.keys
        val freshSbDeleted = previousSandboxState.keys - postCppcSandbox.keys

        val resolvedSbMoves = mutableMapOf<String, String>() // deletedPath -> addedPath
        val remainingSbAdded = freshSbAdded.toMutableSet()
        val remainingSbDeleted = freshSbDeleted.toMutableSet()

        // Detect Sandbox moves/renames via content match of added vs deleted paths
        for (addedPath in freshSbAdded) {
            val addedRec = postCppcSandbox[addedPath] ?: continue
            val matchingDeletedPath = remainingSbDeleted.firstOrNull { delPath ->
                val delRec = previousSandboxState[delPath]
                delRec != null && delRec.sha256 == addedRec.sha256 && delRec.length == addedRec.length
            }
            if (matchingDeletedPath != null) {
                resolvedSbMoves[matchingDeletedPath] = addedPath
                remainingSbAdded.remove(addedPath)
                remainingSbDeleted.remove(matchingDeletedPath)
            }
        }

        // Apply Sandbox moves to CPPC
        for ((delPath, addPath) in resolvedSbMoves) {
            val srcFile = File(cppcDir, delPath)
            val destFile = File(cppcDir, addPath)
            if (srcFile.exists()) {
                val parent = destFile.parentFile
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    continue // Skip 
                }
                
                val moveOk = srcFile.renameTo(destFile)
                if (moveOk) {
                    Log.i(TAG, "BiSync: Sandbox Rename detected. Replicated CPPC move: $delPath -> $addPath")
                } else {
                    try {
                        srcFile.copyTo(destFile, overwrite = true)
                        srcFile.delete()
                    } catch (e: Exception) {
                        if (e.message?.contains("ENOENT") == true || e.message?.contains("No such file or directory") == true) {
                            Log.w(TAG, "Fallback move failed (Permission denied/ENOENT)")
                        } else {
                            Log.e(TAG, "Fallback move failed", e)
                        }
                    }
                }
            }
        }

        // Apply Sandbox simple deletions to CPPC
        for (delPath in remainingSbDeleted) {
            val file = File(cppcDir, delPath)
            if (file.exists()) {
                file.delete()
                Log.i(TAG, "BiSync: Sandbox Delete detected. Deleted in CPPC: $delPath")
            }
        }

        // Apply Sandbox simple additions/modifications to CPPC
        val freshSbModified = postCppcSandbox.filter { (path, rec) ->
            val prev = previousSandboxState[path]
            prev != null && (prev.lastModified != rec.lastModified || prev.length != rec.length || prev.sha256 != rec.sha256)
        }.keys

        for (path in (remainingSbAdded + freshSbModified)) {
            val srcFile = File(sandboxDir, path)
            val destFile = File(cppcDir, path)
            if (srcFile.exists()) {
                val parent = destFile.parentFile
                if (parent != null) {
                    if (!parent.exists() && !parent.mkdirs()) {
                        continue // Skip writing if parent directory doesn't exist and can't be created (no permissions)
                    }
                }
                
                try {
                    srcFile.copyTo(destFile, overwrite = true)
                    Log.i(TAG, "BiSync: Sandbox Change sync-write to CPPC: $path")
                } catch (e: Exception) {
                    if (e.message?.contains("ENOENT") == true || e.message?.contains("No such file or directory") == true) {
                        Log.w(TAG, "Skipped syncing Sandbox file $path to CPPC (Permission denied/ENOENT)")
                    } else {
                        Log.e(TAG, "Failed syncing Sandbox file $path to CPPC", e)
                    }
                }
            }
        }

        // Update checkpoints to true current post-sync states for next tick
        previousSandboxState.clear()
        previousSandboxState.putAll(getDirectoryState(sandboxDir))
        previousCppcState.clear()
        previousCppcState.putAll(getDirectoryState(cppcDir))
        }
        _syncEvents.tryEmit(Unit)
    }

    /**
     * Map relative path to detailed metadata record
     */
    private fun getDirectoryState(baseDir: File): Map<String, FileRecord> {
        val records = mutableMapOf<String, FileRecord>()
        try {
            if (!baseDir.exists()) return records
            
            baseDir.walkTopDown()
                .onEnter { dir ->
                    val name = dir.name.lowercase()
                    !name.startsWith(".") && 
                    !name.startsWith("header") && 
                    !name.startsWith("bin")
                }
                .forEach { file ->
                    if (file.isFile) {
                        val relPath = file.relativeTo(baseDir).path
                        val normPath = relPath.replace('\\', '/')
                        // Skip hidden/dot internal configuration, header files, and binary toolchains to avoid massive overhead
                        if (!normPath.startsWith(".") && 
                            !normPath.contains("/.") && 
                            !normPath.contains("header") && 
                            !normPath.contains("bin")) {
                            val length = file.length()
                            val lastModified = file.lastModified()
                            val sha = getFileSha256(file)
                            records[relPath] = FileRecord(relPath, lastModified, length, sha)
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error listing file state for ${baseDir.name}", e)
        }
        return records
    }

    /**
     * Helper to compute rapid unique hashes
     */
    private fun getFileSha256(file: File): String {
        if (!file.exists()) return ""
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            FileInputStream(file).use { input ->
                var read: Int
                while (input.read(buffer).also { read = it } > 0) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            // Soft fallback to length/name representation
            "${file.name}_${file.length()}"
        }
    }
}
