package com.example.core.compiler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.core.storage.AppDatabase
import com.example.core.storage.HeaderFileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class HeaderScannerWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sysrootDirPath = inputData.getString("sysrootDir_path") ?: return@withContext Result.failure()
        val sysrootDir = File(sysrootDirPath)

        if (!sysrootDir.exists() || !sysrootDir.isDirectory) {
            setProgress(workDataOf("status_message" to "§R[ERROR] Headers/Sysroot missing at: ${sysrootDir.absolutePath}\n"))
            return@withContext Result.failure()
        }

        val allFiles = sysrootDir.walkTopDown().filter { it.isFile }.toList()
        val headerCount = allFiles.size

        if (headerCount > 0) {
            setProgress(workDataOf("status_message" to "[INFO] Starting fast-index rebuild for $headerCount files...\n"))
            try {
                val db = AppDatabase.getDatabase(applicationContext)
                val dao = db.headerFileDao()
                dao.deleteAll()

                var lastReportedPct = -1
                
                // Show mapping progress
                val entities = allFiles.mapIndexed { index, file ->
                    val pct = ((index + 1) * 90) / Math.max(1, headerCount)
                    if (pct != lastReportedPct && pct % 10 == 0) {
                        setProgress(
                            workDataOf(
                                "progress" to pct,
                                "status_message" to "§Y[INDEXING] Building fast-search database: $pct%...\n"
                            )
                        )
                        lastReportedPct = pct
                    }
                    
                    HeaderFileEntity(
                        filename = file.name,
                        filepath = file.absolutePath
                    )
                }

                setProgress(workDataOf("status_message" to "§Y[INDEXING] Saving to database...\n"))
                
                // Insert all at once; Room handles the transaction efficiently.
                // We chunk it by 1000 just in case there are size limits.
                val chunks = entities.chunked(1000)
                chunks.forEachIndexed { i, chunk ->
                    dao.insertAll(chunk)
                }
                
                val finalMsg = "§G[INDEXING] Fast-search database indexing complete. Indexed ${entities.size} files.\n"
                setProgress(workDataOf("status_message" to finalMsg))
                return@withContext Result.success(workDataOf("status_message" to finalMsg))
            } catch (e: Throwable) {
                val errMsg = "§R[INDEXING] Failed to index headers: ${e.stackTraceToString()}\n"
                setProgress(workDataOf("status_message" to errMsg))
                return@withContext Result.failure(workDataOf("status_message" to errMsg))
            }
        }
        Result.success()
    }
}
