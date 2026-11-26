package io.github.kawincodes.stiksound.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException

/** MyInstants URL rules, ported from StikSound `url-policy.ts`. */
object UrlPolicy {
    private val IPV4 = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")

    fun isPrivateOrReservedIp(ip: String): Boolean {
        val n = ip.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (n.isEmpty()) return true
        if (n.contains(':')) {
            if (n == "::1" || n == "::") return true
            if (n.startsWith("fe80:") || n.startsWith("fc") || n.startsWith("fd")) return true
            if (n.startsWith("::ffff:")) return isPrivateOrReservedIp(n.removePrefix("::ffff:"))
            return false
        }
        if (!IPV4.matches(n)) return true
        val parts = n.split('.').map { it.toInt() }
        if (parts.any { it !in 0..255 }) return true
        val (a, b) = parts
        return a == 0 || a == 10 || a == 127 ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 100 && b in 64..127) ||
            a >= 224
    }

    /** Returns the parsed URI, or throws [ErrorCode.URL_REJECTED]. */
    fun validate(rawUrl: String): URI {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) fail(ErrorCode.URL_REJECTED, "Empty URL.")
        val uri = try {
            URI(trimmed)
        } catch (e: URISyntaxException) {
            fail(ErrorCode.URL_REJECTED, "Malformed URL.")
        }
        if (!uri.isAbsolute || uri.isOpaque) fail(ErrorCode.URL_REJECTED, "Malformed URL.")
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            fail(ErrorCode.URL_REJECTED, "Only https URLs are allowed (got ${uri.scheme}:).")
        }
        if (uri.rawUserInfo != null) fail(ErrorCode.URL_REJECTED, "URL userinfo is forbidden.")
        if (uri.port != -1 && uri.port != 443) fail(ErrorCode.URL_REJECTED, "Non-443 port rejected: ${uri.port}")
        val host = uri.host?.lowercase() ?: fail(ErrorCode.URL_REJECTED, "Malformed URL.")
        if (host == "localhost" || IPV4.matches(host) || host.contains(':') || host.startsWith("[")) {
            fail(ErrorCode.URL_REJECTED, "Raw IP or localhost rejected: $host")
        }
        if (host !in Limits.ALLOWED_MYINSTANTS_HOSTS) fail(ErrorCode.URL_REJECTED, "Host not on MyInstants allowlist: $host")
        return uri
    }

    fun isAllowed(rawUrl: String): Boolean = runCatching { validate(rawUrl) }.isSuccess
}

data class ExtractedPage(val title: String?, val mediaUrl: String)

/** Pulls the sound file URL and title out of a MyInstants page. Ported from `myinstants-extractor.ts`. */
object MyInstantsExtractor {
    private const val PATH = """(/media/sounds/[A-Za-z0-9._~!$&'()*+,;=:@%/-]+\.(?:mp3|wav|ogg|m4a))"""
    private val PRIMARY_BUTTON = Regex(
        """id=["']instant-page-button(?:-element)?["'][^>]*?(?:data-url|onclick)=["'][^"']*?$PATH""",
        RegexOption.IGNORE_CASE,
    )
    private val DOWNLOAD_LINK = Regex("""href=["']$PATH["'][^>]*?download""", RegexOption.IGNORE_CASE)
    private val MEDIA_PATH = Regex(
        """(?:https://(?:www\.)?myinstants\.com)?$PATH(?:\?[^"'\s<>)]*)?""",
        RegexOption.IGNORE_CASE,
    )
    private val OG_TITLE = Regex("""<meta\s+[^>]*property=["']og:title["'][^>]*content=["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
    private val OG_TITLE_REV = Regex("""<meta\s+[^>]*content=["']([^"']+)["'][^>]*property=["']og:title["'][^>]*>""", RegexOption.IGNORE_CASE)
    private val TITLE_TAG = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
    private val TITLE_SUFFIX = Regex("""\s*[-|]\s*(?:Myinstants|Bot[oó]n de sonido|Instant Sound Button|Sound Button).*$""", RegexOption.IGNORE_CASE)

    private fun decodeEntities(s: String): String = s
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    private fun cleanTitle(raw: String?): String? {
        if (raw == null) return null
        val cleaned = decodeEntities(raw).replace(TITLE_SUFFIX, "").replace(Regex("""\s+"""), " ").trim()
        return cleaned.ifEmpty { null }?.take(120)
    }

    fun extract(html: String): ExtractedPage {
        val match = PRIMARY_BUTTON.find(html) ?: DOWNLOAD_LINK.find(html) ?: MEDIA_PATH.find(html)
        val path = match?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }
            ?: fail(ErrorCode.REMOTE_UNAVAILABLE, "No MyInstants media URL found in page HTML.")
        val mediaUrl = UrlPolicy.validate("https://www.myinstants.com$path").toString()
        val rawTitle = (OG_TITLE.find(html) ?: OG_TITLE_REV.find(html))?.groupValues?.get(1)
            ?: TITLE_TAG.find(html)?.groupValues?.get(1)
        return ExtractedPage(cleanTitle(rawTitle), mediaUrl)
    }
}

/** One HTTP GET without following redirects. */
data class HttpResponse(val status: Int, val location: String?, val contentLength: Long?, val body: ByteArray?)

fun interface HttpFetcher {
    /** Performs a GET of [url]; must not follow redirects and must stop reading after [maxBytes] + 1 bytes. */
    fun get(url: String, maxBytes: Int, timeoutMs: Int): HttpResponse
}

data class ResolvedSound(val pageUrl: String?, val mediaUrl: String, val title: String?, val bytes: ByteArray)

/** Resolves a MyInstants page or file link to audio bytes. Ported from `myinstants-provider.ts`. */
class MyInstantsProvider(private val fetcher: HttpFetcher = UrlConnectionFetcher()) {
    private val directMedia = Regex("""^/media/sounds/[^/]+\.(mp3|wav|ogg|m4a)$""", RegexOption.IGNORE_CASE)
    private val instantPage = Regex("""^/(?:[a-z]{2}(?:-[a-z]{2})?/)?instant/[^/]+/?$""", RegexOption.IGNORE_CASE)

    fun canResolve(rawUrl: String): Boolean = try {
        val path = UrlPolicy.validate(rawUrl).rawPath ?: ""
        directMedia.matches(path) || instantPage.matches(path)
    } catch (e: StikSoundException) {
        false
    }

    fun resolve(rawUrl: String): ResolvedSound {
        val uri = UrlPolicy.validate(rawUrl)
        val path = uri.rawPath ?: ""
        if (directMedia.matches(path)) {
            val bytes = fetchLimited(uri.toString(), Limits.MAX_AUDIO_BYTES)
            return ResolvedSound(null, uri.toString(), null, bytes)
        }
        if (!instantPage.matches(path)) {
            fail(ErrorCode.URL_REJECTED, "URL is not a MyInstants instant page or direct media file.")
        }
        val normalizedPath = if (path.endsWith("/")) path else "$path/"
        val pageUrl = "https://${uri.host.lowercase()}$normalizedPath"
        val html = String(fetchLimited(pageUrl, Limits.MAX_HTML_BYTES), Charsets.UTF_8)
        val extracted = MyInstantsExtractor.extract(html)
        val audio = fetchLimited(extracted.mediaUrl, Limits.MAX_AUDIO_BYTES)
        return ResolvedSound(pageUrl, extracted.mediaUrl, extracted.title, audio)
    }

    private fun fetchLimited(target: String, maxBytes: Int): ByteArray {
        var current = UrlPolicy.validate(target).toString()
        for (redirects in 0..Limits.MAX_REDIRECTS) {
            val response = try {
                fetcher.get(current, maxBytes, Limits.FETCH_TIMEOUT_MS)
            } catch (e: StikSoundException) {
                throw e
            } catch (e: IOException) {
                throw StikSoundException(ErrorCode.REMOTE_UNAVAILABLE, "Network error or timeout while contacting MyInstants.", e)
            }
            when {
                response.status in 300..399 -> {
                    val location = response.location
                    if (location.isNullOrBlank() || redirects == Limits.MAX_REDIRECTS) {
                        fail(ErrorCode.URL_REJECTED, "Too many redirects or missing Location header.")
                    }
                    val next = try {
                        URI(current).resolve(location.trim()).toString()
                    } catch (e: IllegalArgumentException) {
                        fail(ErrorCode.URL_REJECTED, "Malformed redirect.")
                    }
                    current = UrlPolicy.validate(next).toString()
                }
                response.status == 413 -> fail(ErrorCode.FILE_TOO_LARGE, "Remote response exceeds the size limit.")
                response.status == 403 -> fail(ErrorCode.URL_REJECTED, "URL rejected by MyInstants security policy.")
                response.status !in 200..299 -> fail(ErrorCode.REMOTE_UNAVAILABLE, "HTTP ${response.status} from MyInstants.")
                else -> {
                    val declared = response.contentLength
                    if (declared != null && declared > maxBytes) fail(ErrorCode.FILE_TOO_LARGE, "Remote response exceeds $maxBytes bytes.")
                    val body = response.body ?: ByteArray(0)
                    if (body.size > maxBytes) fail(ErrorCode.FILE_TOO_LARGE, "Remote body exceeds $maxBytes bytes.")
                    return body
                }
            }
        }
        fail(ErrorCode.URL_REJECTED, "Redirect limit exceeded.")
    }
}

/**
 * [HttpFetcher] on top of [HttpURLConnection]. Before connecting it resolves the
 * host and refuses private, loopback and reserved addresses (as the desktop app does).
 */
class UrlConnectionFetcher : HttpFetcher {
    override fun get(url: String, maxBytes: Int, timeoutMs: Int): HttpResponse {
        val host = URI(url).host ?: fail(ErrorCode.URL_REJECTED, "Malformed URL.")
        val addresses = InetAddress.getAllByName(host)
        if (addresses.isEmpty() || addresses.any { UrlPolicy.isPrivateOrReservedIp(it.hostAddress ?: "") }) {
            fail(ErrorCode.URL_REJECTED, "Host resolves to a private or reserved address.")
        }
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "*/*")
            val status = connection.responseCode
            val location = connection.getHeaderField("Location")
            val length = connection.contentLengthLong.takeIf { it >= 0 }
            if (status !in 200..299) return HttpResponse(status, location, length, null)
            if (length != null && length > maxBytes) return HttpResponse(status, location, length, null)
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > maxBytes) break
                }
            }
            return HttpResponse(status, location, length, out.toByteArray())
        } finally {
            connection.disconnect()
        }
    }
}
