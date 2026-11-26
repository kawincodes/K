package io.github.kawincodes.stiksound.core

/** Error categories, matching the codes StikSound uses on web and desktop. */
enum class ErrorCode {
    UNSUPPORTED_IMAGE,
    UNSUPPORTED_AUDIO,
    FILE_TOO_LARGE,
    TRIM_OUT_OF_RANGE,
    URL_REJECTED,
    REMOTE_UNAVAILABLE,
    ENCODE_FAILED,
    UNSUPPORTED_CODEC,
}

class StikSoundException(
    val code: ErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal fun fail(code: ErrorCode, message: String): Nothing = throw StikSoundException(code, message)
