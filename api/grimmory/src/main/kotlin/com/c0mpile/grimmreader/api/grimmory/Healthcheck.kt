package com.c0mpile.grimmreader.api.grimmory

/** What answered at `/api/v1/healthcheck`. */
sealed interface HealthcheckResult {
    data class Grimmory(
        val version: String?,
    ) : HealthcheckResult

    /** HTML instead of JSON: usually a forward-auth login page in front of the server. */
    data object LoginPage : HealthcheckResult

    data object NotGrimmory : HealthcheckResult

    data class HttpError(
        val code: Int,
    ) : HealthcheckResult
}

object HealthcheckClassifier {
    private const val HTTP_OK_MIN = 200
    private const val HTTP_OK_MAX = 299

    fun classify(
        code: Int,
        contentType: String?,
        body: String,
    ): HealthcheckResult {
        val trimmed = body.trimStart()
        val html = contentType?.contains("html", ignoreCase = true) == true || trimmed.startsWith("<")
        return when {
            html -> HealthcheckResult.LoginPage
            code !in HTTP_OK_MIN..HTTP_OK_MAX -> HealthcheckResult.HttpError(code)
            else -> parse(trimmed)
        }
    }

    private fun parse(body: String): HealthcheckResult {
        val envelope = runCatching { GrimmoryApi.json.decodeFromString<HealthcheckEnvelopeDto>(body) }.getOrNull()
        val looksRight = envelope != null && (envelope.message == "Pong" || envelope.data?.status == "UP")
        return if (looksRight) HealthcheckResult.Grimmory(envelope.data?.version) else HealthcheckResult.NotGrimmory
    }
}

/** Supported server range: 3.5.x is tested; newer is allowed with a warning. */
object ServerVersion {
    const val MIN = "3.5.0"
    const val TESTED_MAJOR_MINOR = "3.5"
    private const val VERSION_PARTS = 3

    sealed interface Support {
        data object Supported : Support

        data object Untested : Support

        data object TooOld : Support

        data object Unknown : Support
    }

    fun support(version: String?): Support {
        val parts = parse(version) ?: return Support.Unknown
        val min = parse(MIN)!!
        val tested = parse(TESTED_MAJOR_MINOR)!!
        return when {
            compare(parts, min) < 0 -> Support.TooOld
            parts[0] == tested[0] && parts[1] == tested[1] -> Support.Supported
            else -> Support.Untested
        }
    }

    private fun parse(version: String?): List<Int>? {
        val numbers =
            version
                ?.trim()
                ?.removePrefix("v")
                ?.split('.', '-')
                ?.take(VERSION_PARTS)
                ?.map { it.toIntOrNull() ?: return null } ?: return null
        return (numbers + List(VERSION_PARTS) { 0 }).take(VERSION_PARTS)
    }

    private fun compare(
        a: List<Int>,
        b: List<Int>,
    ): Int = a.zip(b).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: 0
}
