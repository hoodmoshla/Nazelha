package com.snapnet.download.http

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicInternetDnsTest {
    @Test
    fun rejectsPrivateLoopbackLinkLocalAndDocumentationIpv4Ranges() {
        listOf(
                "0.1.2.3",
                "10.1.2.3",
                "100.64.0.1",
                "127.0.0.1",
                "169.254.1.1",
                "172.16.0.1",
                "192.0.2.1",
                "192.88.99.1",
                "192.168.1.1",
                "198.18.0.1",
                "198.51.100.1",
                "203.0.113.1",
                "224.0.0.1",
                "240.0.0.1",
            )
            .forEach { address -> assertFalse("$address must be blocked", isPublic(address)) }
    }

    @Test
    fun acceptsPublicIpv4Addresses() {
        assertTrue(isPublic("8.8.8.8"))
        assertTrue(isPublic("1.1.1.1"))
    }

    @Test
    fun rejectsPrivateAndSpecialIpv6Ranges() {
        listOf(
                "::",
                "::1",
                "fc00::1",
                "fe80::1",
                "ff02::1",
                "2001:db8::1",
                "2001::1",
                "2002:7f00:1::1",
                "64:ff9b::7f00:1",
                "100::1",
                "::ffff:127.0.0.1",
            )
            .forEach { address -> assertFalse("$address must be blocked", isPublic(address)) }
    }

    @Test
    fun acceptsPublicIpv6Address() {
        assertTrue(isPublic("2001:4860:4860::8888"))
    }

    private fun isPublic(value: String): Boolean =
        PublicInternetDns.isPublicAddress(InetAddress.getByName(value))
}
