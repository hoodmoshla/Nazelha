package com.snapnet.util

import org.junit.Assert.assertEquals
import org.junit.Test

class CookieSecurityPolicyTest {
    @Test
    fun marksAllNetscapeCookiesSecureIncludingHttpOnlyRows() {
        val input =
            "# Netscape HTTP Cookie File\n" +
                ".example.com\tTRUE\t/\tFALSE\t1893456000\tsession\tvalue\n" +
                "#HttpOnly_.example.com\tTRUE\t/\tFALSE\t1893456000\thttpOnly\tvalue\n"

        val output = CookieSecurityPolicy.httpsOnlyNetscapeContent(input)

        assertEquals(
            "# Netscape HTTP Cookie File\n" +
                ".example.com\tTRUE\t/\tTRUE\t1893456000\tsession\tvalue\n" +
                "#HttpOnly_.example.com\tTRUE\t/\tTRUE\t1893456000\thttpOnly\tvalue\n",
            output,
        )
    }

    @Test
    fun preservesCommentsAndFilesWithoutTrailingNewline() {
        val input = "# header\n.example.com\tTRUE\t/\tFALSE\t0\ta\tb"
        assertEquals(
            "# header\n.example.com\tTRUE\t/\tTRUE\t0\ta\tb",
            CookieSecurityPolicy.httpsOnlyNetscapeContent(input),
        )
    }
}
