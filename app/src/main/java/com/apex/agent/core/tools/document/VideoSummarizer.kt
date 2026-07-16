package com.apex.core.tools.document

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import javax.imageio.ImageIO

class VideoSummarizer {

    suspend fun extractKeyFrames(videoPath: String, frameCount: Int = 10): List<ByteArray> = withContext(Dispatchers.IO) {
        val frames = mutableListOf<ByteArray>()
        try {
            val outputDir = File.createTempFile("video_frames_", "")
            outputDir.delete()
            outputDir.mkdirs()

            val framePattern = File(outputDir, "frame_%03d.png")
            val process = Runtime.getRuntime().exec(
                arrayOf(
                    "ffmpeg",
                    "-i", videoPath,
                    "-vf", "fps=${frameCount}",
                    "-q:v", "2",
                    framePattern.absolutePath
                )
            )
            process.waitFor()

            outputDir.listFiles()?.sortedBy { it.name }?.forEach { file ->
                if (file.extension == "png") {
                    frames.add(file.readBytes())
                    file.delete()
                }
            }
            outputDir.delete()
        } catch (e: Exception) {
        }
        frames
    }

    suspend fun extractAudio(videoPath: String): ByteArray = withContext(Dispatchers.IO) {
        val outputFile = File.createTempFile("audio_", ".wav")
        outputFile.delete()
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf(
                    "ffmpeg",
                    "-i", videoPath,
                    "-vn",
                    "-acodec", "pcm_s16le",
                    "-ar", "16000",
                    "-ac", "1",
                    outputFile.absolutePath
                )
            )
            process.waitFor()
            outputFile.readBytes()
        } catch (e: Exception) {
            ByteArray(0)
        } finally {
            outputFile.delete()
        }
    }

    suspend fun transcribeAudio(audioData: ByteArray): String = withContext(Dispatchers.IO) {
        try {
            val tempFile = File.createTempFile("audio_transcribe_", ".wav")
            tempFile.writeBytes(audioData)
            tempFile.deleteOnExit()

            val result = executeWhisperTranscription(tempFile.absolutePath)
            tempFile.delete()
            result
        } catch (e: Exception) {
            ""
        }
    }

    suspend fun generateSummary(
        videoPath: String,
        includeFrames: Boolean = true,
        includeTranscript: Boolean = true
    ): VideoSummaryResult = withContext(Dispatchers.IO) {
        try {
            val duration = getVideoDuration(videoPath)
            val keyFrames = if (includeFrames) extractKeyFrames(videoPath) else emptyList()
            val audioData = extractAudio(videoPath)
            val transcript = if (includeTranscript) transcribeAudio(audioData) else ""

            val summary = if (transcript.isNotEmpty()) {
                generateTextSummary(transcript, duration)
            } else ""

            val timestamps = generateTimestamps(keyFrames, duration)

            VideoSummaryResult(
                videoPath = videoPath,
                duration = duration,
                keyFrames = keyFrames,
                transcript = transcript,
                summary = summary,
                timestamps = timestamps,
                success = true
            )
        } catch (e: Exception) {
            VideoSummaryResult(
                videoPath = videoPath,
                duration = 0,
                success = false,
                errorMessage = e.message
            )
        }
    }

    private fun getVideoDuration(videoPath: String): Long {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", videoPath)
            )
            val reader = process.inputStream.bufferedReader()
            val durationStr = reader.readLine() ?: "0"
            reader.close()
            (durationStr.toDoubleOrNull()?.toLong() ?: 0L) * 1000
        } catch (e: Exception) {
            0L
        }
    }

    private fun executeWhisperTranscription(audioPath: String): String {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("whisper", "-f", "json", audioPath)
            )
            val reader = process.inputStream.bufferedReader()
            val output = reader.readText()
            reader.close()
            parseWhisperOutput(output)
        } catch (e: Exception) {
            ""
        }
    }

    private fun parseWhisperOutput(jsonOutput: String): String {
        val textBuilder = StringBuilder()
        try {
            val textPattern = "\"text\"\\s*:\\s*\"([^\"]+)\"".toRegex()
            textPattern.findAll(jsonOutput).forEach { match ->
                textBuilder.append(match.groupValues[1]).append(" ")
            }
        } catch (e: Exception) {
        }
        return textBuilder.toString().trim()
    }

    private fun generateTextSummary(transcript: String, durationMs: Long): String {
        if (transcript.isEmpty()) return ""

        val durationSec = durationMs / 1000
        val minutes = durationSec / 60
        val seconds = durationSec % 60

        val sentences = transcript.split(Regex("[.!?。！？]")).filter { it.trim().isNotEmpty() }
        val summary = if (sentences.size > 3) {
            sentences.take(3).joinToString(". ") + "."
        } else {
            transcript
        }

        return "视频时长: ${minutes}${seconds}秒。主要内? ${summary}"
    }

    private fun generateTimestamps(frames: List<ByteArray>, durationMs: Long): List<VideoTimestamp> {
        if (frames.isEmpty()) return emptyList()

        val timestamps = mutableListOf<VideoTimestamp>()
        val interval = durationMs / frames.size

        frames.forEachIndexed { index, frame ->
            val time = index * interval
            val description = "关键?${index + 1}"
            timestamps.add(
                VideoTimestamp(
                    time = time,
                    description = description,
                    frame = frame
                )
            )
        }
        return timestamps
    }
}

data class VideoSummaryResult(
    val videoPath: String,
    val duration: Long,
    val keyFrames: List<ByteArray> = emptyList(),
    val transcript: String = "",
    val summary: String = "",
    val timestamps: List<VideoTimestamp> = emptyList(),
    val success: Boolean = true,
    val errorMessage: String? = null
)

data class VideoTimestamp(
    val time: Long,
    val description: String,
    val frame: ByteArray? = null
)