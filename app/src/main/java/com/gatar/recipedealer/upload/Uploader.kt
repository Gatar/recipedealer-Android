package com.gatar.recipedealer.upload

import com.gatar.recipedealer.store.CaptureStatus
import com.gatar.recipedealer.store.CaptureStore
import kotlinx.coroutines.CancellationException
import java.io.File

/** Sends one stored capture with the given sender and records the outcome in the store. */
class CaptureUploadRunner(private val store: CaptureStore) {
    suspend fun upload(id: String, sender: CaptureSender): SendResult {
        val entry = store.get(id) ?: return SendResult.Invalid(listOf("unknown capture $id"))
        val capture = entry.capture ?: return SendResult.Invalid(listOf("capture $id has no data yet"))
        val dir = store.filesDir(id)
        val files = capture.attachments.associate { it.name to File(dir, it.name) }
        val result = try {
            sender.send(capture, files)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SendResult.Retry("${e.javaClass.simpleName}: ${e.message}")
        }
        store.updateState(id) { s ->
            val attempts = s.attempts + 1
            when (result) {
                is SendResult.Accepted -> s.copy(status = CaptureStatus.SENT, error = null, attempts = attempts,
                    destination = result.destination, needsAuth = false)
                is SendResult.Invalid -> s.copy(status = CaptureStatus.FAILED, attempts = attempts,
                    error = "Odrzucony przez serwer: " + result.details.joinToString("; "))
                is SendResult.Unauthorized -> s.copy(status = CaptureStatus.FAILED, attempts = attempts, needsAuth = true,
                    error = "Brak autoryzacji (${result.code}) – sprawdź token w ustawieniach")
                SendResult.TooLarge -> s.copy(status = CaptureStatus.FAILED, attempts = attempts,
                    error = "Za duży (413)")
                is SendResult.Retry -> s.copy(status = CaptureStatus.QUEUED, attempts = attempts,
                    error = "Ponowię: ${result.reason}")
            }
        }
        return result
    }
}
