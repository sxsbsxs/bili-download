package com.example.bilidl

import java.nio.charset.StandardCharsets

/**
 * BVD-compatible filename templates for media and sidecar resources.
 * Supported tokens are deliberately small and stable for existing settings.
 */
internal object BiliDownloadFileNamePolicy {
    const val VIDEO_NAME_TOKEN = "{VideoName}"
    const val PART_NAME_TOKEN = "{PartName}"

    const val KEY_VIDEO_TEMPLATE = "file_name_template_video"
    const val KEY_AUDIO_TEMPLATE = "file_name_template_audio"
    const val KEY_MIXED_TEMPLATE = "file_name_template_mixed"

    const val DEFAULT_VIDEO_TEMPLATE = "Video-$VIDEO_NAME_TOKEN-$PART_NAME_TOKEN"
    const val DEFAULT_AUDIO_TEMPLATE = "Audio-$VIDEO_NAME_TOKEN-$PART_NAME_TOKEN"
    const val DEFAULT_MIXED_TEMPLATE = "$VIDEO_NAME_TOKEN-$PART_NAME_TOKEN"

    private const val MAX_BASE_NAME_BYTES = 200
    private val illegalChars = Regex("""[\\/:*?"<>|\u0000-\u001F]""")
    private val whitespace = Regex("""\s+""")
    private val duplicateUnderscores = Regex("""_+""")
    private val underscoreBeforeDash = Regex("""_-""")
    private val underscoreAfterDash = Regex("""-_""")

    fun render(
        template: String?,
        defaultTemplate: String,
        videoName: String,
        partName: String
    ): String {
        val selected = template.orEmpty().trim().ifBlank { defaultTemplate }
        val rendered = selected
            .replace(VIDEO_NAME_TOKEN, videoName)
            .replace(PART_NAME_TOKEN, partName)
        return sanitizeBaseName(rendered).ifBlank {
            sanitizeBaseName(videoName).ifBlank { "BiliDl media" }
        }
    }

    fun sanitizeBaseName(value: String, maxBytes: Int = MAX_BASE_NAME_BYTES): String {
        val normalized = value
            .replace(illegalChars, "_")
            .replace(duplicateUnderscores, "_")
            .replace(underscoreBeforeDash, "-")
            .replace(underscoreAfterDash, "-")
            .replace(whitespace, " ")
            .trim { it <= ' ' || it == '.' || it == '-' || it == '_' }
        return truncateUtf8(normalized, maxBytes).trim { it <= ' ' || it == '.' || it == '-' || it == '_' }
    }

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        val safeLimit = maxBytes.coerceAtLeast(1)
        val result = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val chars = String(Character.toChars(codePoint))
            val length = chars.toByteArray(StandardCharsets.UTF_8).size
            if (bytes + length > safeLimit) break
            result.append(chars)
            bytes += length
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }
}
