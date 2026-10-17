package com.hdfcdual

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Üretilen (birleştirilmiş / kaydırılmış) altyazıyı oynatıcıya ulaştıran minik HTTP sunucusu.
 *
 * NEDEN GEREKLİ?
 * Cloudstream, eklentinin verdiği her SubtitleFile'ı OkHttp ile indirir (SubtitleOrigin.URL).
 * OkHttp "file://" ve "data:" şemalarını DESTEKLEMEZ. Bu yüzden sentetik altyazıyı yalnızca
 * 127.0.0.1 üzerinde dinleyen bu sunucudan "http://127.0.0.1:PORT/dual_xxx.srt" olarak sunuyoruz.
 * Cloudstream uygulaması usesCleartextTraffic="true" olduğu için düz HTTP'ye izin verilir.
 *
 * Güvenlik: yalnızca loopback arayüzüne bağlanır; dışarıdan erişilemez. Yalnızca GET/HEAD,
 * yalnızca [A-Za-z0-9_.-] karakterli dosya adları ve son 24 dosya bellekte tutulur.
 *
 * Dosya adı ".srt" ile BİTMELİDİR: oynatıcı MIME türünü URL'nin uzantısından çıkarır.
 */
object LocalSubServer {
    private const val MAX_FILES = 24
    private val NAME_OK = Regex("""[A-Za-z0-9_.\-]+""")

    private val files = object : LinkedHashMap<String, ByteArray>(32, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
            size > MAX_FILES
    }

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "hdfc-sub-server").apply { isDaemon = true }
    }

    @Volatile
    private var server: ServerSocket? = null

    /** İçeriği yayına alır ve oynatıcıya verilecek URL'yi döndürür. Başlatılamazsa null. */
    @Synchronized
    fun serve(fileName: String, content: String): String? {
        require(NAME_OK.matches(fileName)) { "Geçersiz dosya adı: $fileName" }
        val s = ensureStarted() ?: return null
        synchronized(files) { files[fileName] = content.toByteArray(Charsets.UTF_8) }
        return "http://127.0.0.1:${s.localPort}/$fileName"
    }

    private fun ensureStarted(): ServerSocket? {
        server?.let { if (!it.isClosed) return it }
        return try {
            val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            server = s
            pool.execute { acceptLoop(s) }
            s
        } catch (e: Exception) {
            null
        }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val client = try { s.accept() } catch (e: Exception) { return }
            pool.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        client.use { sock ->
            try {
                sock.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
                val requestLine = reader.readLine() ?: return
                // Başlıkların sonuna kadar oku
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }

                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0).orEmpty()
                val name = parts.getOrNull(1).orEmpty().substringBefore('?').removePrefix("/")

                val out = sock.getOutputStream()
                val body = if (method == "GET" || method == "HEAD") {
                    if (NAME_OK.matches(name)) synchronized(files) { files[name] } else null
                } else null

                if (body == null) {
                    val status = if (method == "GET" || method == "HEAD") "404 Not Found" else "405 Method Not Allowed"
                    out.write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                } else {
                    val header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/x-subrip; charset=utf-8\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Cache-Control: no-store\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Connection: close\r\n\r\n"
                    out.write(header.toByteArray(Charsets.ISO_8859_1))
                    if (method == "GET") out.write(body)
                }
                out.flush()
            } catch (_: Exception) {
                // Oynatıcı bağlantıyı erken kapatabilir; sessizce yoksay
            }
        }
    }
}
