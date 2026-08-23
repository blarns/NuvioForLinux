package com.nuvio.app.features.player.skip

/**
 * The kinds of segment auto-skip can act on, and the mapping from the many names the skip
 * providers use for them.
 *
 * [storedValue] is what goes in the settings store, so these strings are part of the on-disk
 * format and must not be renamed.
 *
 * Ported from NuvioDesktop@6cd7e14.
 */
enum class AutoSkipSegmentType(val storedValue: String) {
    INTRO("intro"),
    RECAP("recap"),
    OUTRO("outro");

    companion object {
        fun fromStoredValue(value: String): AutoSkipSegmentType? =
            entries.firstOrNull { it.storedValue == value }

        /**
         * The providers disagree about names for the same thing — AniSkip says `op`/`ed`,
         * Anime-Skip says `mixed-op`, IntroDb says `intro`/`outro` — so every spelling seen in
         * the wild is folded onto one of the three types here. An unknown type returns null and
         * is simply never auto-skipped.
         */
        fun fromSkipIntervalType(type: String): AutoSkipSegmentType? = when (type.trim().lowercase()) {
            "op", "opening", "mixed-op", "intro" -> INTRO
            "recap" -> RECAP
            "ed", "ending", "mixed-ed", "outro", "credits" -> OUTRO
            else -> null
        }
    }
}

/**
 * Identity for "this exact segment", so a segment is auto-skipped at most once per playback.
 * Provider is part of the key because two providers can report overlapping-but-different
 * intervals for the same episode.
 */
internal fun SkipInterval.autoSkipKey(): String =
    "$provider:$type:$startTime:$endTime"
