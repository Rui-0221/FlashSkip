package com.flashskip.data

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Non-blocking local logger:
 * - UI reads lock-free immutable snapshot tail (never blocked by disk I/O);
 * - Disk writes happen asynchronously on a dedicated HandlerThread;
 * - Dual-file rotation (`skip.log` + `skip.log.1`, 512KB each) avoids abrupt history wipe;
 * - Timestamps include full date (`yyyy-MM-dd HH:mm:ss.SSS`).
 */
object SkipLog {
    private const val TAG = "SkipAd"
    private const val MAX_LINES = 1500
    private const val MAX_FILE_BYTES = 512L * 1024L

    private val lock = Any()
    private val ring = ArrayDeque<String>(MAX_LINES)
    private val tailSnapshot = AtomicReference<List<String>>(emptyList())
    val version = AtomicInteger(0)

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var writerHandler: Handler? = null

    private val dateFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    fun init(context: Context) {
        if (logDir == null) {
            logDir = context.filesDir
        }
        if (writerHandler == null) {
            synchronized(lock) {
                if (writerHandler == null) {
                    val thread = HandlerThread("SkipLogWriter").apply { start() }
                    writerHandler = Handler(thread.looper)
                    loadRecentFromDisk()
                }
            }
        }
    }

    fun log(msg: String) {
        val ts = dateFmt.get()!!.format(Date())
        val line = "[$ts] $msg"
        Log.i(TAG, msg)

        synchronized(lock) {
            ring.addLast(line)
            if (ring.size > MAX_LINES) ring.removeFirst()
            tailSnapshot.set(ring.toList())
            version.incrementAndGet()
        }

        val dir = logDir ?: return
        writerHandler?.post {
            appendLineRotated(dir, line)
        }
    }

    fun tail(n: Int): String {
        val list = tailSnapshot.get()
        if (list.isEmpty()) return ""
        val from = (list.size - n).coerceAtLeast(0)
        return list.subList(from, list.size).joinToString("\n")
    }

    fun exportAll(): String {
        val dir = logDir
        if (dir != null) {
            val prev = File(dir, "skip.log.1")
            val curr = File(dir, "skip.log")
            val sb = StringBuilder()
            try {
                if (prev.exists()) sb.append(prev.readText())
                if (curr.exists()) sb.append(curr.readText())
                if (sb.isNotEmpty()) return sb.toString()
            } catch (_: Exception) {
            }
        }
        return tail(MAX_LINES)
    }

    fun clear() {
        synchronized(lock) {
            ring.clear()
            tailSnapshot.set(emptyList())
            version.incrementAndGet()
        }
        val dir = logDir ?: return
        writerHandler?.post {
            try {
                File(dir, "skip.log").delete()
                File(dir, "skip.log.1").delete()
            } catch (_: Exception) {
            }
        }
    }

    fun writeSnapshotFile(pkg: String, json: String): File? {
        val dir = logDir ?: return null
        return try {
            val snapDir = File(dir, "snapshots").apply { mkdirs() }
            val safePkg = pkg.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val file = File(snapDir, "snap_${safePkg}_${System.currentTimeMillis()}.json")
            file.writeText(json)
            // Retain only latest 20 snapshots
            snapDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() }
            file
        } catch (_: Exception) {
            null
        }
    }

    private fun appendLineRotated(dir: File, line: String) {
        try {
            val curr = File(dir, "skip.log")
            if (curr.exists() && curr.length() > MAX_FILE_BYTES) {
                val prev = File(dir, "skip.log.1")
                if (prev.exists()) prev.delete()
                curr.renameTo(prev)
            }
            curr.appendText(line + "\n")
        } catch (_: Exception) {
        }
    }

    private fun loadRecentFromDisk() {
        val dir = logDir ?: return
        try {
            val curr = File(dir, "skip.log")
            if (!curr.exists()) return
            val existing = curr.readLines().takeLast(MAX_LINES)
            ring.clear()
            ring.addAll(existing)
            tailSnapshot.set(ring.toList())
            version.incrementAndGet()
        } catch (_: Exception) {
        }
    }
}
