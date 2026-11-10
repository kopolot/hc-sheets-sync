package io.github.kopolot.hcsync.net

import io.github.kopolot.hcsync.domain.DailySummary
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class SheetsClientTest {
    private val server = MockWebServer()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    private fun client() = SheetsClient(server.url("/exec").toString(), "sec", "test")
    private val days = listOf(DailySummary(LocalDate.parse("2026-11-10")))

    @Test
    fun `follows apps script style 302 and reads json from echo url`() = runTest {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/echo").toString()).build())
        server.enqueue(MockResponse.Builder().body("""{"ok":true,"days":1,"workouts":0}""").build())

        val resp = client().upload(days)

        assertEquals(1, resp.days)
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertTrue(post.body!!.utf8().contains("\"secret\":\"sec\""))
        assertEquals("GET", server.takeRequest().method)
    }

    @Test(expected = PermanentSyncException::class)
    fun `ok false is permanent`() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"ok":false,"error":"bad secret"}""").build())
        client().upload(days)
    }

    @Test(expected = PermanentSyncException::class)
    fun `html login page is permanent`() = runTest {
        server.enqueue(MockResponse.Builder().body("<html>Sign in</html>").build())
        client().upload(days)
    }

    @Test(expected = IOException::class)
    fun `5xx is retryable`() = runTest {
        server.enqueue(MockResponse.Builder().code(503).build())
        client().upload(days)
    }

    @Test(expected = PermanentSyncException::class)
    fun `missing config is permanent`() = runTest {
        SheetsClient("", "", "test").upload(days)
    }
}
