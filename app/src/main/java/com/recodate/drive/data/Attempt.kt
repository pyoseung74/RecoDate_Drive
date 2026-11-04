package com.recodate.drive.data

import kotlinx.coroutines.CancellationException

/** A cancelled request must never become fallback data or a user-visible error. */
internal inline fun <T> cancellableAttempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
