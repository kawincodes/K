package io.github.kawincodes.stiksound.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MyInstantsTest {
    private fun fixture(name: String) =
        requireNotNull(javaClass.getResourceAsStream("/myinstants/$name")).readBytes().toString(Charsets.UTF_8)

    private fun expectCode(code: ErrorCode, block: () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: StikSoundException) {
            assertEquals(code, e.code)
        }
    }

    @Test
    fun acceptsOnlyHttpsMyInstantsHosts() {
        UrlPolicy.validate("https://www.myinstants.com/en/instant/bruh/")
        UrlPolicy.validate("https://MYINSTANTS.com/media/sounds/a.mp3")
        for (bad in listOf(
            "http://www.myinstants.com/en/instant/a/",
            "https://evil.com/en/instant/a/",
            "https://www.myinstants.com.evil.com/x",
            "https://user:pw@www.myinstants.com/x",
            "https://www.myinstants.com:8443/x",
            "https://127.0.0.1/x",
            "https://localhost/x",
            "https://[::1]/x",
            "",
            "not a url",
        )) {
            expectCode(ErrorCode.URL_REJECTED) { UrlPolicy.validate(bad) }
        }
    }

    @Test
    fun flagsPrivateAddresses() {
        for (ip in listOf("10.1.2.3", "127.0.0.1", "169.254.1.1", "172.16.0.1", "172.31.9.9", "192.168.0.1", "100.64.0.1", "224.0.0.1", "0.0.0.0", "::1", "fe80::1", "fd00::1", "::ffff:127.0.0.1", "999.1.1.1", "")) {
            assertTrue(ip, UrlPolicy.isPrivateOrReservedIp(ip))
        }
        for (ip in listOf("8.8.8.8", "172.32.0.1", "104.21.3.4", "2606:4700::6810:1")) {
            assertFalse(ip, UrlPolicy.isPrivateOrReservedIp(ip))
        }
    }

    @Test
    fun extractsMediaAndTitleFromFixture() {
        val page = MyInstantsExtractor.extract(fixture("instant-page.html"))
        assertEquals("https://www.myinstants.com/media/sounds/airhorn-fixture.mp3", page.mediaUrl)
        assertEquals("Airhorn Classic", page.title)
    }

    @Test
    fun prefersInstantButtonOverOtherLinks() {
        val html = """
            <meta content="Bruh &amp; Co | Instant Sound Button" property="og:title">
            <a href="/media/sounds/other.mp3">x</a>
            <button id="instant-page-button" data-url="/media/sounds/main.mp3"></button>
        """.trimIndent()
        val page = MyInstantsExtractor.extract(html)
        assertEquals("https://www.myinstants.com/media/sounds/main.mp3", page.mediaUrl)
        assertEquals("Bruh & Co", page.title)
    }

    @Test
    fun missingMediaIsRemoteUnavailable() {
        expectCode(ErrorCode.REMOTE_UNAVAILABLE) { MyInstantsExtractor.extract(fixture("instant-page-missing.html")) }
    }

    private class FakeFetcher(val routes: Map<String, HttpResponse>) : HttpFetcher {
        val calls = mutableListOf<String>()
        override fun get(url: String, maxBytes: Int, timeoutMs: Int): HttpResponse {
            calls += url
            return routes[url] ?: HttpResponse(404, null, null, null)
        }
    }

    private fun ok(body: ByteArray) = HttpResponse(200, null, body.size.toLong(), body)

    @Test
    fun resolvesInstantPageToAudio() {
        val audio = byteArrayOf(1, 2, 3)
        val fetcher = FakeFetcher(
            mapOf(
                "https://www.myinstants.com/en/instant/airhorn/" to ok(fixture("instant-page.html").toByteArray()),
                "https://www.myinstants.com/media/sounds/airhorn-fixture.mp3" to ok(audio),
            ),
        )
        val provider = MyInstantsProvider(fetcher)
        assertTrue(provider.canResolve("https://www.myinstants.com/en/instant/airhorn?utm=1"))
        val sound = provider.resolve("https://www.myinstants.com/en/instant/airhorn?utm=1")
        assertEquals("https://www.myinstants.com/en/instant/airhorn/", sound.pageUrl)
        assertEquals("Airhorn Classic", sound.title)
        assertArrayEquals(audio, sound.bytes)
    }

    @Test
    fun resolvesDirectMediaLink() {
        val fetcher = FakeFetcher(mapOf("https://www.myinstants.com/media/sounds/a.mp3" to ok(byteArrayOf(9))))
        val sound = MyInstantsProvider(fetcher).resolve("https://www.myinstants.com/media/sounds/a.mp3")
        assertNull(sound.pageUrl)
        assertArrayEquals(byteArrayOf(9), sound.bytes)
    }

    @Test
    fun followsAllowedRedirectsOnly() {
        val fetcher = FakeFetcher(
            mapOf(
                "https://myinstants.com/media/sounds/a.mp3" to HttpResponse(301, "https://www.myinstants.com/media/sounds/a.mp3", null, null),
                "https://www.myinstants.com/media/sounds/a.mp3" to ok(byteArrayOf(5)),
                "https://www.myinstants.com/media/sounds/evil.mp3" to HttpResponse(302, "https://evil.example/x.mp3", null, null),
            ),
        )
        val provider = MyInstantsProvider(fetcher)
        assertArrayEquals(byteArrayOf(5), provider.resolve("https://myinstants.com/media/sounds/a.mp3").bytes)
        expectCode(ErrorCode.URL_REJECTED) { provider.resolve("https://www.myinstants.com/media/sounds/evil.mp3") }
    }

    @Test
    fun stopsAfterRedirectLimit() {
        val a = "https://www.myinstants.com/media/sounds/a.mp3"
        val b = "https://www.myinstants.com/media/sounds/b.mp3"
        val fetcher = FakeFetcher(mapOf(a to HttpResponse(302, b, null, null), b to HttpResponse(302, a, null, null)))
        expectCode(ErrorCode.URL_REJECTED) { MyInstantsProvider(fetcher).resolve(a) }
        assertEquals(Limits.MAX_REDIRECTS + 1, fetcher.calls.size)
    }

    @Test
    fun enforcesSizeLimitAndHttpErrors() {
        val big = "https://www.myinstants.com/media/sounds/big.mp3"
        val gone = "https://www.myinstants.com/media/sounds/gone.mp3"
        val fetcher = FakeFetcher(
            mapOf(
                big to HttpResponse(200, null, Limits.MAX_AUDIO_BYTES + 1L, null),
                gone to HttpResponse(500, null, null, null),
            ),
        )
        val provider = MyInstantsProvider(fetcher)
        expectCode(ErrorCode.FILE_TOO_LARGE) { provider.resolve(big) }
        expectCode(ErrorCode.REMOTE_UNAVAILABLE) { provider.resolve(gone) }
    }

    @Test
    fun rejectsNonSoundPaths() {
        val provider = MyInstantsProvider { _, _, _ -> error("must not fetch") }
        assertFalse(provider.canResolve("https://www.myinstants.com/en/search/?name=x"))
        expectCode(ErrorCode.URL_REJECTED) { provider.resolve("https://www.myinstants.com/en/search/?name=x") }
    }
}
