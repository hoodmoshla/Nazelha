package com.snapnet.download.http

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal data class TestHttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
) {
    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]
}

/** A tiny raw HTTP/1.1 server so JVM tests do not need Android-incompatible JDK server modules. */
internal class TestHttpServer : Closeable {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val handlers = ConcurrentHashMap<String, (TestHttpRequest, OutputStream) -> Unit>()
    private val acceptThread =
        Thread {
                while (!socket.isClosed) {
                    try {
                        val client = socket.accept()
                        executor.submit { handle(client) }
                    } catch (_: IOException) {
                        if (!socket.isClosed) continue
                    }
                }
            }
            .apply {
                name = "snapnet-test-http-accept"
                isDaemon = true
                start()
            }

    val port: Int
        get() = socket.localPort

    fun url(path: String): String = "http://127.0.0.1:$port$path"

    fun createContext(path: String, handler: (TestHttpRequest, OutputStream) -> Unit) {
        handlers[path] = handler
    }

    fun respond(
        output: OutputStream,
        status: Int,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = byteArrayOf(),
        contentLength: Long? = body.size.toLong(),
        chunked: Boolean = false,
        writeBody: ((OutputStream) -> Unit)? = null,
    ) {
        val responseHeaders = LinkedHashMap(headers)
        responseHeaders.putIfAbsent("Connection", "close")
        if (chunked) {
            responseHeaders["Transfer-Encoding"] = "chunked"
        } else if (contentLength != null) {
            responseHeaders.putIfAbsent("Content-Length", contentLength.toString())
        }
        val reason =
            when (status) {
                200 -> "OK"
                206 -> "Partial Content"
                302 -> "Found"
                301 -> "Moved Permanently"
                303 -> "See Other"
                307 -> "Temporary Redirect"
                308 -> "Permanent Redirect"
                405 -> "Method Not Allowed"
                416 -> "Range Not Satisfiable"
                501 -> "Not Implemented"
                else -> "Test Response"
            }
        val headerText = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            responseHeaders.forEach { (name, value) ->
                append(name).append(": ").append(value).append("\r\n")
            }
            append("\r\n")
        }
        output.write(headerText.toByteArray(StandardCharsets.ISO_8859_1))
        output.flush()
        if (writeBody != null) {
            writeBody(output)
        } else if (chunked) {
            if (body.isNotEmpty()) {
                output.write(body.size.toString(16).toByteArray(StandardCharsets.US_ASCII))
                output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.write(body)
                output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
            }
            output.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        } else if (body.isNotEmpty()) {
            output.write(body)
        }
        output.flush()
    }

    private fun handle(client: Socket) {
        client.use { accepted ->
            try {
                accepted.soTimeout = 10_000
                val input = BufferedInputStream(accepted.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ', limit = 3)
                if (parts.size < 2) return
                val requestHeaders = linkedMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        requestHeaders[line.substring(0, separator).trim().lowercase(Locale.ROOT)] =
                            line.substring(separator + 1).trim()
                    }
                }
                val request =
                    TestHttpRequest(parts[0], parts[1].substringBefore('?'), requestHeaders)
                val handler = handlers[request.path]
                if (handler == null) {
                    respond(accepted.getOutputStream(), 404, contentLength = 0L)
                } else {
                    handler(request, accepted.getOutputStream())
                }
            } catch (_: IOException) {
                // Client cancellation and deliberately truncated test responses close the socket.
            }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) return if (bytes.size() == 0) null else bytes.toString("US-ASCII")
            if (next == '\n'.code) return bytes.toString("US-ASCII").removeSuffix("\r")
            bytes.write(next)
        }
    }

    override fun close() {
        socket.close()
        executor.shutdownNow()
        acceptThread.join(1_000)
    }
}
