package com.c0mpile.grimmreader.core.network

import com.c0mpile.grimmreader.core.network.ServerUrlParser.Result
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerUrlParserTest {
    private fun ok(
        input: String,
        allowHttp: Boolean = false,
    ) = (ServerUrlParser.parse(input, allowHttp) as Result.Ok).baseUrl

    @Test fun normalisesCommonInputs() {
        assertEquals("https://grimmory.example.com", ok("grimmory.example.com"))
        assertEquals("https://grimmory.example.com", ok("  https://grimmory.example.com/  "))
        assertEquals("https://grimmory.example.com:8443", ok("grimmory.example.com:8443"))
        assertEquals("https://grimmory.example.com/grimmory", ok("https://grimmory.example.com/grimmory/"))
        assertEquals("https://grimmory.example.com/grimmory", ok("https://grimmory.example.com/grimmory/api/v1/books?x=1#y"))
        assertEquals("https://grimmory.example.com", ok("https://grimmory.example.com/api"))
        assertEquals("https://192.0.2.10:6060", ok("192.0.2.10:6060"))
        assertEquals("https://[2001:db8::1]:6060", ok("[2001:db8::1]:6060"))
        assertEquals("https://grimmory.example.com", ok("HTTPS://Grimmory.Example.com:443"))
    }

    @Test fun cleartextNeedsExplicitOptIn() {
        assertEquals(Result.CleartextNotAllowed, ServerUrlParser.parse("http://192.0.2.10:6060", allowCleartext = false))
        assertEquals("http://192.0.2.10:6060", ok("http://192.0.2.10:6060", allowHttp = true))
        assertEquals("http://192.0.2.10", ok("http://192.0.2.10:80", allowHttp = true))
    }

    @Test fun rejectsGarbage() {
        assertEquals(Result.Empty, ServerUrlParser.parse("   ", false))
        assertEquals(Result.Invalid, ServerUrlParser.parse("ftp://grimmory.example.com", false))
        assertEquals(Result.Invalid, ServerUrlParser.parse("https://", false))
        assertEquals(Result.Invalid, ServerUrlParser.parse("not a host name", false))
    }
}
