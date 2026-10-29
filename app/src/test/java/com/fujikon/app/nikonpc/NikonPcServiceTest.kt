package com.fujikon.app.nikonpc

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class NikonPcServiceTest {

    private lateinit var server: ServerSocket
    private lateinit var serverThread: Thread
    private lateinit var cacheDir: File
    private var metadataRequests = 0
    private var downloadRequests = 0
    private var lastMetadataBody = ""
    private var lastDownloadBody = ""

    @Before
    fun setUp() {
        cacheDir = File(System.getProperty("java.io.tmpdir"), "fujikon-nikonpc-${System.nanoTime()}")
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverThread = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket -> handle(socket) }
                } catch (_: Exception) {
                    if (!server.isClosed) continue
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    @After
    fun tearDown() {
        server.close()
        serverThread.join(2_000)
        cacheDir.deleteRecursively()
    }

    @Test
    fun fetchesCatalogueDownloadsPresetAndCachesIt() = runBlocking {
        val service = NikonPcService(
            baseUrl = "http://127.0.0.1:${server.localPort}/",
            cacheDir = cacheDir
        )

        val catalog = service.fetchCatalog()
        assertEquals(1, catalog.size)
        assertEquals("Agfa Optima", catalog.single().name)

        val first = service.download(catalog.single())
        val expected = javaClass.classLoader!!.getResourceAsStream("agfa_optima.ncp")!!.readBytes()
        assertTrue(first.contentEquals(expected))
        assertEquals(1, metadataRequests)
        assertEquals(1, downloadRequests)

        val decodedMetadata = URLDecoder.decode(lastMetadataBody, StandardCharsets.UTF_8)
        assertTrue(decodedMetadata.contains("name=Agfa/PICCON01.NCP"))

        val decodedDownload = URLDecoder.decode(lastDownloadBody, StandardCharsets.UTF_8)
        assertTrue(decodedDownload.contains("PicCtrlItem=Neutral"))
        assertTrue(decodedDownload.contains("RegistrationName=Agfa Optima"))
        assertTrue(decodedDownload.contains("points=0,0,38,36,75,85,176,194,221,222,255,255"))
        assertTrue(decodedDownload.contains("selFilterEffects=80"))

        val second = service.download(catalog.single())
        assertTrue(second.contentEquals(expected))
        assertEquals("A cached preset should not request metadata again", 1, metadataRequests)
        assertEquals("A cached preset should not request the NCP again", 1, downloadRequests)
    }

    private fun handle(socket: Socket) {
        val input = socket.getInputStream()
        val requestLine = readLine(input) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] =
                    line.substring(separator + 1).trim()
            }
        }

        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val target = parts.getOrNull(1).orEmpty()
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = readExactly(input, length)
        val query = runCatching { URI(target).rawQuery.orEmpty() }.getOrDefault("")

        when {
            method == "GET" && query.isBlank() -> {
                respond(socket.getOutputStream(), catalogHtml.toByteArray(StandardCharsets.UTF_8), "text/html")
            }
            query.contains("cmd=loadNpc") -> {
                metadataRequests++
                lastMetadataBody = body.toString(StandardCharsets.UTF_8)
                respond(socket.getOutputStream(), metadata.toByteArray(StandardCharsets.UTF_8), "text/plain")
            }
            query.contains("cmd=dowloadModefiedNpc") -> {
                downloadRequests++
                lastDownloadBody = body.toString(StandardCharsets.UTF_8)
                val ncp = javaClass.classLoader!!.getResourceAsStream("agfa_optima.ncp")!!.readBytes()
                respond(socket.getOutputStream(), ncp, "application/octet-stream")
            }
            else -> respond(socket.getOutputStream(), ByteArray(0), "text/plain", status = "404 Not Found")
        }
    }

    private fun respond(
        output: OutputStream,
        bytes: ByteArray,
        contentType: String,
        status: String = "200 OK"
    ) {
        val header = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        output.write(header)
        output.write(bytes)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val value = input.read()
            if (value < 0) return if (builder.isEmpty()) null else builder.toString()
            if (value == '\n'.code) return builder.toString()
            if (value != '\r'.code) builder.append(value.toChar())
        }
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == length) bytes else bytes.copyOf(offset)
    }

    private val catalogHtml = """
        <select id="selFileList">
          <option value="Agfa/PICCON01.NCP" title="Agfa Optima; Agfa/PICCON01.NCP">[01] Agfa Optima</option>
        </select>
    """.trimIndent()

    private val metadata = """
        {'filename':'PICCON01.NCP',
        'name':'Agfa Optima',
        'picCtrl':'Neutral',
        'reserved02':'0',
        'customCurveFlag':'1',
        'sharpening':'2',
        'contrast':null,
        'brightness':null,
        'splain':{'points':[['0',
        '0'],
        ['38',
        '36'],
        ['75',
        '85'],
        ['176',
        '194'],
        ['221',
        '222'],
        ['255',
        '255']],
        'gamma':'1.00',
        'minInput':'0',
        'maxInput':'255',
        'minOutput':'0',
        'maxOutput':'255'},
        'lut':{'lut':[0.000000,
        255.000000]},
        'lutSource':'0 255',
        'saturation':'0',
        'hue':'0'}
    """.trimIndent()
}
