package com.nuvio.app.features.watchprogress

object ResumePromptRepository {

    fun markPlayerEntered(videoId: String) {
        ResumePromptStorage.saveWasInPlayer(true)
        ResumePromptStorage.saveLastPlayerVideoId(videoId)
    }

    fun markPlayerExitedNormally() {
        ResumePromptStorage.saveWasInPlayer(false)
        ResumePromptStorage.saveLastPlayerVideoId(null)
    }

    fun consumeResumePrompt(): ContinueWatchingItem? {
        val wasInPlayer = ResumePromptStorage.loadWasInPlayer()
        if (!wasInPlayer) return null

        val videoId = ResumePromptStorage.loadLastPlayerVideoId()
        ResumePromptStorage.saveWasInPlayer(false)
        ResumePromptStorage.saveLastPlayerVideoId(null)

        if (videoId.isNullOrBlank()) return null

        WatchProgressRepository.ensureLoaded()
        val entry = WatchProgressRepository.progressForVideo(videoId) ?: return null
        if (!entry.isResumable) return null

        return entry.toContinueWatchingItem()
    }
}
