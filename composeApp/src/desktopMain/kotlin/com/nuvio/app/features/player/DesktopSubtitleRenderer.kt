package com.nuvio.app.features.player

/**
 * Desktop subtitle renderer supporting ASS/SSA and WebVTT/SRT formats.
 * 
 * On desktop, subtitles are rendered via VLCJ which uses libass (bundled with VLC).
 * VLCJ handles the rendering automatically when subtitles are loaded.
 * 
 * This module provides additional parsing and styling support for cases where
 * fine-grained control is needed beyond VLCJ's built-in capabilities.
 */

import androidx.compose.ui.graphics.Color
import java.io.File
import java.net.URL

object DesktopSubtitleRenderer {
    /**
     * Parses ASS/SSA subtitle format.
     * Returns style information and cue list.
     */
    fun parseAssSubtitles(content: String): AssSubtitleData {
        val lines = content.split("\n")
        val styles = mutableMapOf<String, AssStyle>()
        val events = mutableListOf<AssEvent>()

        var currentSection = ""
        for (line in lines) {
            when {
                line.startsWith("[V4+ Styles]") || line.startsWith("[V4 Styles]") -> {
                    currentSection = "styles"
                }

                line.startsWith("[Events]") -> {
                    currentSection = "events"
                }

                currentSection == "styles" && line.startsWith("Style:") -> {
                    val style = parseAssStyle(line)
                    if (style != null) {
                        styles[style.name] = style
                    }
                }

                currentSection == "events" && (line.startsWith("Dialogue:") || line.startsWith("Comment:")) -> {
                    val event = parseAssEvent(line)
                    if (event != null) {
                        events.add(event)
                    }
                }
            }
        }

        return AssSubtitleData(
            styles = styles,
            events = events.sortedBy { it.startMs }
        )
    }

    /**
     * Parses WebVTT subtitle format.
     */
    fun parseWebVttSubtitles(content: String): List<SubtitleSyncCue> {
        val cues = mutableListOf<SubtitleSyncCue>()
        val lines = content.split("\n").map { it.trim() }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            // Look for timing line (HH:MM:SS.mmm --> HH:MM:SS.mmm)
            if (line.contains("-->")) {
                val parts = line.split("-->")
                if (parts.size == 2) {
                    val startMs = parseWebVttTime(parts[0].trim())
                    val text = buildString {
                        i++
                        while (i < lines.size && lines[i].isNotEmpty() && !lines[i].contains("-->")) {
                            if (isNotEmpty()) append("\n")
                            append(lines[i])
                            i++
                        }
                    }

                    if (text.isNotEmpty()) {
                        cues.add(SubtitleSyncCue(startMs, text))
                    }
                    continue
                }
            }
            i++
        }

        return cues
    }

    /**
     * Parses SRT subtitle format.
     */
    fun parseSrtSubtitles(content: String): List<SubtitleSyncCue> {
        val cues = mutableListOf<SubtitleSyncCue>()
        val blocks = content.split("\n\n")

        for (block in blocks) {
            val lines = block.split("\n").filter { it.isNotEmpty() }
            if (lines.size >= 2) {
                // First line is subtitle number (ignored)
                // Second line is timing
                val timingLine = lines[1]
                val parts = timingLine.split("-->")

                if (parts.size == 2) {
                    val startMs = parseSrtTime(parts[0].trim())
                    val text = lines.drop(2).joinToString("\n")

                    if (text.isNotEmpty()) {
                        cues.add(SubtitleSyncCue(startMs, text))
                    }
                }
            }
        }

        return cues
    }

    private fun parseAssStyle(line: String): AssStyle? {
        return try {
            val parts = line.substring("Style:".length).split(",").map { it.trim() }
            if (parts.size >= 9) {
                AssStyle(
                    name = parts[0],
                    fontName = parts[1],
                    fontSize = parts[2].toIntOrNull() ?: 20,
                    primaryColour = parseAssColor(parts[3]),
                    secondaryColour = parseAssColor(parts[4]),
                    outlineColour = parseAssColor(parts[5]),
                    backColour = parseAssColor(parts[6]),
                    bold = parts[7].toIntOrNull() == 1,
                    italic = parts[8].toIntOrNull() == 1,
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseAssEvent(line: String): AssEvent? {
        return try {
            val prefix = if (line.startsWith("Dialogue:")) "Dialogue:" else "Comment:"
            val parts = line.substring(prefix.length).split(",", limit = 10)

            if (parts.size >= 10) {
                val startMs = parseAssTime(parts[1].trim())
                val endMs = parseAssTime(parts[2].trim())
                val text = parts[9].trim()
                    .replace(Regex("""\{[^}]*\}"""), "") // Remove formatting tags

                AssEvent(
                    startMs = startMs,
                    endMs = endMs,
                    text = text,
                    style = parts[3].trim()
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseAssColor(colorStr: String): Color {
        return try {
            // ASS colors are in BGR format (hex)
            val hex = colorStr.replace("&H", "").padStart(8, '0')
            val bgr = hex.toLong(16)
            val r = ((bgr shr 0) and 0xFF).toInt()
            val g = ((bgr shr 8) and 0xFF).toInt()
            val b = ((bgr shr 16) and 0xFF).toInt()
            Color(r, g, b)
        } catch (e: Exception) {
            Color.White
        }
    }

    private fun parseAssTime(timeStr: String): Long {
        // ASS time format: H:MM:SS.CC (centiseconds)
        return try {
            val parts = timeStr.split(":")
            if (parts.size == 3) {
                val hours = parts[0].toLong()
                val minutes = parts[1].toLong()
                val secondsAndCentis = parts[2].split(".")
                val seconds = secondsAndCentis[0].toLong()
                val centis = if (secondsAndCentis.size > 1) {
                    secondsAndCentis[1].padEnd(2, '0').toLong()
                } else {
                    0
                }

                hours * 3600000 + minutes * 60000 + seconds * 1000 + centis * 10
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun parseWebVttTime(timeStr: String): Long {
        // WebVTT time format: HH:MM:SS.mmm or MM:SS.mmm
        return try {
            val parts = timeStr.split(":")
            when (parts.size) {
                3 -> {
                    val hours = parts[0].toLong()
                    val minutes = parts[1].toLong()
                    val secondsAndMs = parts[2].split(".")
                    val seconds = secondsAndMs[0].toLong()
                    val millis = if (secondsAndMs.size > 1) {
                        secondsAndMs[1].padEnd(3, '0').toLong()
                    } else {
                        0
                    }
                    hours * 3600000 + minutes * 60000 + seconds * 1000 + millis
                }

                2 -> {
                    val minutes = parts[0].toLong()
                    val secondsAndMs = parts[1].split(".")
                    val seconds = secondsAndMs[0].toLong()
                    val millis = if (secondsAndMs.size > 1) {
                        secondsAndMs[1].padEnd(3, '0').toLong()
                    } else {
                        0
                    }
                    minutes * 60000 + seconds * 1000 + millis
                }

                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun parseSrtTime(timeStr: String): Long {
        // SRT time format: HH:MM:SS,mmm
        return try {
            val parts = timeStr.split(":")
            if (parts.size == 3) {
                val hours = parts[0].toLong()
                val minutes = parts[1].toLong()
                val secondsAndMs = parts[2].replace(",", ".").split(".")
                val seconds = secondsAndMs[0].toLong()
                val millis = if (secondsAndMs.size > 1) {
                    secondsAndMs[1].padEnd(3, '0').toLong()
                } else {
                    0
                }
                hours * 3600000 + minutes * 60000 + seconds * 1000 + millis
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }
}

data class AssSubtitleData(
    val styles: Map<String, AssStyle>,
    val events: List<AssEvent>,
)

data class AssStyle(
    val name: String,
    val fontName: String,
    val fontSize: Int,
    val primaryColour: Color,
    val secondaryColour: Color,
    val outlineColour: Color,
    val backColour: Color,
    val bold: Boolean,
    val italic: Boolean,
)

data class AssEvent(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val style: String,
)
