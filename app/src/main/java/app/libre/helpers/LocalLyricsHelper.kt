package app.libre.helpers

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.charset.Charset

object LocalLyricsHelper {
    private const val TAG = "LocalLyricsHelper"

    /**
     * Resolves local lyrics for a given [videoId] or local file path.
     * Looks for companion .lrc files and embedded lyrics tags without making network requests.
     */
    fun findLocalLyrics(videoId: String): String? {
        val path = LocalAudioMatcher.getLocalPath(videoId) ?: return null
        val audioFile = File(path)
        if (!audioFile.exists() || !audioFile.isFile) return null

        // 1. Check companion .lrc file in the same directory
        findCompanionLrcFile(audioFile)?.let { lrcFile ->
            try {
                val text = readLrcFileSafely(lrcFile)
                if (text.isNotBlank()) {
                    Log.i(TAG, "Found companion .lrc file for $videoId at: ${lrcFile.absolutePath}")
                    return text
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error reading .lrc file ${lrcFile.absolutePath}", e)
            }
        }

        // 2. Check embedded lyrics inside the audio file
        extractEmbeddedLyrics(audioFile)?.let { embedded ->
            if (embedded.isNotBlank()) {
                Log.i(TAG, "Found embedded lyrics in audio file for $videoId")
                return embedded
            }
        }

        return null
    }

    /**
     * Searches for candidate .lrc files using multiple filename strategies:
     * - Exact match: `Song.lrc` next to `Song.mp3`
     * - Cleaned match: Stripping track numbers (01 - ) and [videoId] tags
     * - Subdirectories: `lyrics/Song.lrc` or `.lyrics/Song.lrc`
     */
    private fun findCompanionLrcFile(audioFile: File): File? {
        val parentDir = audioFile.parentFile ?: return null
        val baseName = audioFile.nameWithoutExtension

        // Exact match in same directory
        val direct = File(parentDir, "$baseName.lrc")
        if (direct.exists() && direct.isFile && direct.length() > 0) return direct

        // Subdirectory `lyrics/` or `.lyrics/`
        val inSubLyrics = File(parentDir, "lyrics/$baseName.lrc")
        if (inSubLyrics.exists() && inSubLyrics.isFile && inSubLyrics.length() > 0) return inSubLyrics

        val inDotLyrics = File(parentDir, ".lyrics/$baseName.lrc")
        if (inDotLyrics.exists() && inDotLyrics.isFile && inDotLyrics.length() > 0) return inDotLyrics

        // Cleaned filename: strip leading track number ("01 - ", "01. ") and trailing bracketed tags "[xxx]"
        val cleanedName = baseName
            .replace(Regex("""^\d+[\s._-]+"""), "")
            .replace(Regex("""\[[a-zA-Z0-9_-]+\]$"""), "")
            .trim()

        if (cleanedName.isNotEmpty() && cleanedName != baseName) {
            val cleanedFile = File(parentDir, "$cleanedName.lrc")
            if (cleanedFile.exists() && cleanedFile.isFile && cleanedFile.length() > 0) return cleanedFile

            val cleanedInSub = File(parentDir, "lyrics/$cleanedName.lrc")
            if (cleanedInSub.exists() && cleanedInSub.isFile && cleanedInSub.length() > 0) return cleanedInSub
        }

        return null
    }

    /**
     * Reads an .lrc file with robust encoding detection (UTF-8, UTF-16, ISO-8859-1 fallback).
     */
    private fun readLrcFileSafely(file: File): String {
        val bytes = file.readBytes()
        if (bytes.size >= 2) {
            // Check BOM
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16BE"))
            }
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16LE"))
            }
            if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
                return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            }
        }
        return try {
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            String(bytes, Charset.forName("ISO-8859-1"))
        }
    }

    /**
     * Scans ID3 header for USLT/SYLT frames or MP4 ©lyr atom for embedded lyrics.
     */
    private fun extractEmbeddedLyrics(file: File): String? {
        if (!file.exists() || file.length() < 128) return null
        return try {
            val scanLimit = minOf(file.length(), 256 * 1024).toInt()
            val buffer = ByteArray(scanLimit)
            FileInputStream(file).use { it.read(buffer) }

            // 1. ID3v2 USLT (Unsynchronized lyrics) frame
            val usltMarker = "USLT".toByteArray(Charsets.ISO_8859_1)
            val usltIdx = indexOfBytes(buffer, usltMarker)
            if (usltIdx != -1 && usltIdx + 10 < buffer.size) {
                // Read frame size (4 bytes in ID3v2 synchsafe/standard)
                val frameSize = ((buffer[usltIdx + 4].toInt() and 0xFF) shl 21) or
                        ((buffer[usltIdx + 5].toInt() and 0xFF) shl 14) or
                        ((buffer[usltIdx + 6].toInt() and 0xFF) shl 7) or
                        (buffer[usltIdx + 7].toInt() and 0xFF)
                val safeSize = frameSize.coerceIn(1, scanLimit - (usltIdx + 10))
                val rawPayload = String(buffer, usltIdx + 10, safeSize, Charset.forName("ISO-8859-1"))
                val clean = rawPayload.replace("\u0000", "").substringAfter("eng", "").substringAfter("xxx", rawPayload).trim()
                if (clean.length > 20) return clean
            }

            // 2. MP4 ©lyr atom
            val lyrMarker = "©lyr".toByteArray(Charsets.ISO_8859_1)
            val lyrIdx = indexOfBytes(buffer, lyrMarker)
            if (lyrIdx != -1 && lyrIdx + 8 < buffer.size) {
                val dataMarker = "data".toByteArray(Charsets.ISO_8859_1)
                val dataIdx = indexOfBytes(buffer, dataMarker, lyrIdx)
                if (dataIdx != -1 && dataIdx + 16 < buffer.size) {
                    val rawLyr = String(buffer, dataIdx + 16, minOf(4096, buffer.size - (dataIdx + 16)), Charsets.UTF_8)
                    val clean = rawLyr.takeWhile { it.code >= 32 || it == '\n' || it == '\r' || it == '\t' }.trim()
                    if (clean.length > 20) return clean
                }
            }

            null
        } catch (e: Exception) {
            null
        }
    }

    private fun indexOfBytes(source: ByteArray, target: ByteArray, startOffset: Int = 0): Int {
        if (target.isEmpty() || source.size < target.size + startOffset) return -1
        outer@ for (i in startOffset..(source.size - target.size)) {
            for (j in target.indices) {
                if (source[i + j] != target[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
