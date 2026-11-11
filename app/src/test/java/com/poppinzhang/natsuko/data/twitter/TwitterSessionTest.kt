package com.poppinzhang.natsuko.data.twitter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitterSessionTest {
    @Test
    fun importsOnlyAuthenticationAndCsrfCookiesAndRedactsDiagnostics() {
        val session = checkNotNull(TwitterSession.fromCookieHeader("guest_id=ignored; auth_token=private-auth; other=ignored; ct0=private-csrf"))
        assertEquals("auth_token=private-auth; ct0=private-csrf", session.cookieHeader)
        assertEquals("private-csrf", session.csrfToken)
        assertEquals("TwitterSession(redacted)", session.toString())
        assertFalse(session.toString().contains("private"))
    }

    @Test
    fun rejectsIncompleteAmbiguousAndUnsafeHeaders() {
        for (header in listOf(
            null, "", "auth_token=a", "ct0=b", "auth_token=; ct0=b", "auth_token=a; ct0=",
            "auth_token=a; auth_token=c; ct0=b", "auth_token=a; ct0=b; ct0=c",
            "auth_token=a\r\nX-Evil: true; ct0=b", "other=a\n; auth_token=a; ct0=b",
            "auth_token=a b; ct0=b", "auth_token=\"a\"; ct0=b", "auth_token=a\\b; ct0=b",
            "auth_token=あ; ct0=b", "auth_token=a\u0000; ct0=b", "auth_token=a,b; ct0=b",
            "auth_token=${"a".repeat(4097)}; ct0=b", "other=${"a".repeat(32768)}; auth_token=a; ct0=b",
        )) assertNull("Rejected header index ${header?.length}", TwitterSession.fromCookieHeader(header))
    }

    @Test
    fun allowsCookieOctetsWithoutDecodingOrReinterpretingTheValues() {
        val session = TwitterSession.fromCookieHeader("ct0=csrf%3Dtoken; auth_token=abc=def_-.~")
        assertNotNull(session)
        assertEquals("auth_token=abc=def_-.~; ct0=csrf%3Dtoken", session?.cookieHeader)
    }

    @Test
    fun onlyAllowsExactHttpsXLoginOrigins() {
        for (url in listOf(
            TwitterLoginUrl.START, "https://x.com/home", "https://www.x.com:443/i/flow/login?lang=en",
            "https://twitter.com/account/access", "https://www.twitter.com/login", "HTTPS://X.COM/home",
        )) assertTrue(url, TwitterLoginUrl.isAllowed(url))
        for (url in listOf(
            "http://x.com/login", "https://x.com.evil.test/login", "https://evil.test/?next=https://x.com",
            "https://x.com@evil.test/login", "https://evil.test@x.com/login", "https://x.com:444/login",
            "https://api.x.com/login", "file:///login", "content://x.com/login", "javascript:alert(1)",
            "intent://x.com/login", "https://x.com./login", "https://x.com\\@evil.test", "//x.com/login",
        )) assertFalse(url, TwitterLoginUrl.isAllowed(url))
        assertEquals("https://x.com", TwitterLoginUrl.origin("https://X.COM:443/i/flow/login"))
        assertNull(TwitterLoginUrl.origin("http://x.com/login"))
    }
}
