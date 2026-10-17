package com.hdfcdual

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** Tek bir altyazı satırı. Zamanlar milisaniyedir. */
data class Cue(val start: Long, val end: Long, val text: String)

/**
 * Altyazı motoru. Android'e bağımlılığı YOKTUR; saf JVM olduğu için bilgisayarda test edilebilir.
 *
 * Bağımsız zamanlamanın temeli: her dilin cue listesi önce KENDİ offset'iyle kaydırılır
 * ([shift]), ardından [merge] iki listeyi zaman ekseninde birleştirir. Birleştirme, cue'ların
 * birbirine karşılık gelmesini ASLA varsaymaz; yalnızca "şu an hangi cue'lar aktif" sorusuna bakar.
 */
object DualSubtitleEngine {

    // 00:01:02,345 | 01:02.345 | 1:02:03.4 gibi biçimler
    private val TS = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})""")
    private val ARROW = Regex("""(\S+)\s*-->\s*(\S+)""")
    private val BLOCK_SPLIT = Regex("""\n{2,}""")
    private val ASS_TAG = Regex("""\{\\an?\d{1,2}\}""")

    // VTT'ye özgü etiketler: <c.xxx>, <v Ad>, <lang>, <ruby>, <00:01.000> zaman etiketleri
    private val VTT_TAGS = Regex("""</?(?:c|v|lang|ruby|rt)(?:[.\s][^>]*)?>|<\d{1,2}:\d{2}(?:[:.]\d{2,3})+>""")

    private const val MIN_SLICE_MS = 50L

    // ------------------------------------------------------------------ okuma

    /**
     * Ham bayt dizisini çözer. Önce katı UTF-8 dener; geçersiz bayt görürse Türkçe eski
     * dosyalar için windows-1254'e düşer (ı, ş, ğ gibi harflerin bozulmaması için).
     */
    fun decodeSmart(bytes: ByteArray): String {
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            utf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            val fallback = runCatching { Charset.forName("windows-1254") }.getOrDefault(Charsets.ISO_8859_1)
            String(bytes, fallback)
        }
    }

    private fun parseTs(s: String): Long? {
        val m = TS.matchEntire(s.trim()) ?: return null
        val (h, mi, se, msRaw) = m.destructured
        // "4" -> 400, "45" -> 450, "456" -> 456 (kesirli saniye)
        val ms = msRaw.padEnd(3, '0').toLong()
        return ((h.ifEmpty { "0" }.toLong() * 3600) + mi.toLong() * 60 + se.toLong()) * 1000 + ms
    }

    private fun cleanText(raw: String): String =
        raw.replace(ASS_TAG, "")
            .replace(VTT_TAGS, "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    /** SRT ve VTT'yi aynı çözücüyle okur. WEBVTT başlığı, NOTE ve STYLE blokları atlanır. */
    fun parse(raw: String): List<Cue> {
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val cues = ArrayList<Cue>()
        for (block in text.split(BLOCK_SPLIT)) {
            val lines = block.lines()
            val idx = lines.indexOfFirst { it.contains("-->") }
            if (idx < 0) continue
            val m = ARROW.find(lines[idx]) ?: continue
            val s = parseTs(m.groupValues[1]) ?: continue
            val e = parseTs(m.groupValues[2]) ?: continue
            val body = cleanText(lines.drop(idx + 1).joinToString("\n"))
            if (body.isNotEmpty() && e > s) cues.add(Cue(s, e, body))
        }
        return cues.sortedBy { it.start }
    }

    // ------------------------------------------------------------------ zamanlama

    /**
     * Bir dilin tüm cue'larını offsetMs kadar kaydırır (+ = geç göster, - = erken göster).
     * Negatif sonuca düşen kısım 0'da kırpılır; tamamen negatife düşen cue silinir.
     */
    fun shift(cues: List<Cue>, offsetMs: Long): List<Cue> {
        if (offsetMs == 0L) return cues
        return cues.mapNotNull {
            val s = it.start + offsetMs
            val e = it.end + offsetMs
            if (e <= 0L) null else Cue(s.coerceAtLeast(0L), e, it.text)
        }
    }

    // ------------------------------------------------------------------ birleştirme

    /**
     * Olay tabanlı birleştirme (iki dil tamamen bağımsız):
     *  1) İki listenin tüm başlangıç ve bitiş anları "kırılma noktası" olur.
     *  2) Ardışık iki nokta arasındaki her aralıkta, o an AKTİF olan üst ve alt cue'lar bulunur.
     *  3) Aralığa "üst\nalt" yazılır; yalnızca biri aktifse sadece o görünür.
     *  4) Metni aynı olan bitişik aralıklar tek cue'da birleştirilir (titremeyi önler).
     *  5) 50 ms'den kısa kırıntılar ve boşluklar, önceki cue'nun süresi uzatılarak yutulur
     *     (göz kırpma olmaz).
     *
     * Karmaşıklık O((n+m) log(n+m)). Eşzamanlı aktif cue sayısı küçük olduğundan sweep yeterlidir.
     */
    fun merge(top: List<Cue>, bottom: List<Cue>, bottomColor: String? = null): List<Cue> {
        val t = top.sortedBy { it.start }
        val b = bottom.sortedBy { it.start }

        val points = java.util.TreeSet<Long>()
        for (c in t) { points.add(c.start); points.add(c.end) }
        for (c in b) { points.add(c.start); points.add(c.end) }
        val p = points.toLongArray()

        val out = ArrayList<Cue>()
        val activeT = ArrayList<Cue>()
        val activeB = ArrayList<Cue>()
        var ti = 0
        var bi = 0

        for (i in 0 until p.size - 1) {
            val a = p[i]
            val z = p[i + 1]

            while (ti < t.size && t[ti].start <= a) activeT.add(t[ti++])
            while (bi < b.size && b[bi].start <= a) activeB.add(b[bi++])
            activeT.removeAll { it.end <= a }
            activeB.removeAll { it.end <= a }
            if (activeT.isEmpty() && activeB.isEmpty()) continue

            val last = out.lastOrNull()
            if (z - a < MIN_SLICE_MS && last != null && last.end == a) {
                out[out.size - 1] = last.copy(end = z)   // kırıntıyı yut
                continue
            }

            val parts = ArrayList<String>(2)
            if (activeT.isNotEmpty()) parts.add(activeT.joinToString("\n") { it.text })
            if (activeB.isNotEmpty()) {
                val tx = activeB.joinToString("\n") { it.text }
                parts.add(if (bottomColor == null) tx else "<font color=\"$bottomColor\">$tx</font>")
            }
            val txt = parts.joinToString("\n")

            // Önceki cue ile arada 50 ms'den kısa boşluk varsa kapat (göz kırpmayı önler)
            if (last != null && last.end < a && a - last.end < MIN_SLICE_MS) {
                out[out.size - 1] = last.copy(end = a)
            }
            val last2 = out.lastOrNull()

            if (last2 != null && last2.end == a && last2.text == txt) {
                out[out.size - 1] = last2.copy(end = z)
            } else {
                out.add(Cue(a, z, txt))
            }
        }
        return out
    }

    // ------------------------------------------------------------------ yazma

    /** Oynatıcıya verilecek biçim: SRT (en geniş uyumluluk). */
    fun toSrt(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { i, c ->
            append(i + 1).append('\n')
            append(fmt(c.start)).append(" --> ").append(fmt(c.end)).append('\n')
            append(c.text).append("\n\n")
        }
    }

    private fun fmt(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1000 % 60
        val x = ms % 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", h, m, s, x)
    }
}
