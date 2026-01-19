package com.mediadash.android.domain.usecase

import com.mediadash.android.data.repository.MediaRepository
import com.mediadash.android.domain.model.PlaybackCommand
import javax.inject.Inject

/**
 * Use case for processing playback commands received from the Go client.
 * Delegates to MediaRepository for actual command execution.
 */
class ProcessPlaybackCommandUseCase @Inject constructor(
    private val mediaRepository: MediaRepository
) {
    /**
     * Processes a playback command.
     * @param command The command to process
     */
    suspend operator fun invoke(command: PlaybackCommand) {
        if (!command.isValid()) {
            return
        }
        mediaRepository.processCommand(command)
    }
}
