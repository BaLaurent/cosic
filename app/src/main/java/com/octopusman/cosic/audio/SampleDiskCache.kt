package com.octopusman.cosic.audio

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/** Where the audio files of the network are kept: up to this many bytes. */
internal const val DISK_CACHE_CAP_BYTES = 1L shl 30

/** When the cache is over its cap, the least recently used files go until it is down to this part of it. */
private const val EVICT_TO = 0.8

private const val TEMP_MARK = ".tmp"

/**
 * The files downloaded from the network, one per key in [dir], so that a sample is fetched once and not once per run. A file is
 * written under a temporary name and renamed, so a reader sees a whole file or none (an interrupted download leaves nothing under
 * the key). Every [get] marks the file as used by touching its modification time; when the files outgrow [capBytes], the ones used
 * least recently are deleted until 80 % of the cap is left. Thread safe.
 */
internal class SampleDiskCache(
    private val dir: File,
    private val capBytes: Long = DISK_CACHE_CAP_BYTES,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val counter = AtomicLong()
    private val instance = java.util.UUID.randomUUID().toString().take(8)

    // The bytes in the directory; unknown (-1) until the first write has looked at what an earlier run left.
    private var total = -1L

    /** The cached file of [key], marked as just used; null when there is none. */
    fun get(key: String): File? {
        val file = File(dir, key)
        if (!file.isFile) return null
        file.setLastModified(now())
        return file
    }

    /**
     * Makes the file of [key] what [write] writes, and returns it. If [write] throws, nothing changes and the exception is rethrown.
     * A file that is there already is replaced.
     */
    fun put(key: String, write: (OutputStream) -> Unit): File {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("cannot create $dir")
        val temp = File(dir, "$key$TEMP_MARK$instance-${counter.incrementAndGet()}")
        try {
            temp.outputStream().use(write)
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
        val size = temp.length()
        val target = File(dir, key)
        synchronized(lock) {
            scanIfNeeded()
            val replaced = if (target.isFile) target.length() else 0L
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) {
                    temp.delete()
                    throw IOException("cannot store $key in $dir")
                }
            }
            target.setLastModified(now())
            total += size - replaced
            evictIfNeeded(keep = key)
        }
        return target
    }

    /** Deletes the file of [key], for one that turned out to be damaged. */
    fun remove(key: String) {
        synchronized(lock) {
            val file = File(dir, key)
            if (!file.isFile) return
            scanIfNeeded()
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    /** The bytes the files take up. */
    fun sizeBytes(): Long = synchronized(lock) {
        scanIfNeeded()
        total
    }

    private fun scanIfNeeded() {
        if (total >= 0) return
        var sum = 0L
        for (file in dir.listFiles().orEmpty()) {
            when {
                // A download of this run is still going on; one that an earlier run did not finish never will.
                TEMP_MARK in file.name -> if ("$TEMP_MARK$instance-" !in file.name) file.delete()
                else -> sum += file.length()
            }
        }
        total = sum
    }

    private fun evictIfNeeded(keep: String) {
        if (total <= capBytes) return
        val goal = (capBytes * EVICT_TO).toLong()
        val oldestFirst = dir.listFiles().orEmpty().filter { it.isFile && TEMP_MARK !in it.name && it.name != keep }.sortedBy { it.lastModified() }
        for (file in oldestFirst) {
            if (total <= goal) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }
}
