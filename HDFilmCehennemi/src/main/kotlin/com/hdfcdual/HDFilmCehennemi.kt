package com.hdfcdual

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * HDFilmCehennemi sağlayıcısı + bağımsız zamanlamalı çift altyazı.
 *
 * Sitenin HTML/JSON yapısı, çalışan referans uygulamadan (keyiflerolsun/Kekik-cloudstream ve
 * hexated) alınmıştır. Site bir değişiklik yaparsa bozulabilecek yerler "TODO-VERIFY" ile
 * işaretlidir.
 */
class HDFilmCehennemi : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi Dual"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // TODO-VERIFY: kategori yolları siteden siteye / zamanla değişebilir
    override val mainPage = mainPageOf(
        mainUrl to "Yeni Eklenen Filmler",
        "$mainUrl/yabancidiziizle-2" to "Yeni Eklenen Diziler",
        "$mainUrl/category/tavsiye-filmler-izle2" to "Tavsiye Filmler",
        "$mainUrl/imdb-7-puan-uzeri-filmler" to "IMDB 7+ Filmler",
        "$mainUrl/en-cok-yorumlananlar-1" to "En Çok Yorumlananlar",
        "$mainUrl/en-cok-begenilen-filmleri-izle" to "En Çok Beğenilenler",
        "$mainUrl/tur/aksiyon-filmleri-izleyin-3" to "Aksiyon Filmleri",
        "$mainUrl/tur/bilim-kurgu-filmlerini-izleyin-2" to "Bilim Kurgu Filmleri",
        "$mainUrl/tur/komedi-filmlerini-izleyin-1" to "Komedi Filmleri",
        "$mainUrl/tur/korku-filmlerini-izle-2/" to "Korku Filmleri",
        "$mainUrl/tur/romantik-filmleri-izle-1" to "Romantik Filmleri"
    )

    // ------------------------------------------------------------------ ana sayfa

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Site kategori sayfalarında sayfalama yolunu doğrulayamadığım için yalnızca ilk sayfa
        // listelenir (referans uygulamayla aynı davranış). Yanlış tahmin, aynı içeriğin
        // sonsuz tekrarına yol açardı.
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)

        val document = app.get(request.data).document
        // TODO-VERIFY: kart seçicisi
        val home = document.select("div.section-content a.poster").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home, false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("strong.poster-title")?.text() ?: return null
        val href = fixUrlNull(this.attr("href")) ?: return null
        val poster = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
    }

    // ------------------------------------------------------------------ arama

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        // TODO-VERIFY: arama uç noktası {"results":["<html>", ...]} döndürür
        val q = URLEncoder.encode(query, "UTF-8")
        val response = app.get(
            "$mainUrl/search?q=$q",
            headers = mapOf("X-Requested-With" to "fetch")
        ).parsedSafe<Results>() ?: return emptyList()

        return response.results.mapNotNull { html ->
            val doc = Jsoup.parse(html)
            val title = doc.selectFirst("h4.title")?.text() ?: return@mapNotNull null
            val href = fixUrlNull(doc.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val img = doc.selectFirst("img")
            val poster = fixUrlNull(img?.attr("src")?.ifBlank { null } ?: img?.attr("data-src"))
                ?.replace("/thumb/", "/list/")
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }
    }

    // ------------------------------------------------------------------ detay

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // TODO-VERIFY: tüm seçiciler
        val title = document.selectFirst("h1.section-title")?.text()?.substringBefore(" izle")?.trim()
            ?: return null
        val poster = fixUrlNull(
            document.select("aside.post-info-poster img.lazyload").lastOrNull()
                ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        )
        val tags = document.select("div.post-info-genres a").map { it.text() }
        val year = document.selectFirst("div.post-info-year-country a")?.text()?.trim()?.toIntOrNull()
        val isSeries = document.select("div.seasons").isNotEmpty()
        val description = document.selectFirst("article.post-info-content > p")?.text()?.trim()
        val ratingScore = Score.from10(
            document.selectFirst("div.post-info-imdb-rating span")?.text()?.substringBefore("(")?.trim()
        )
        val actors = document.select("div.post-info-cast a").mapNotNull {
            val n = it.selectFirst("strong")?.text() ?: return@mapNotNull null
            Actor(n, it.select("img").attr("data-src"))
        }
        val trailer = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")
            ?.substringAfter("trailer/", "")?.takeIf { it.isNotBlank() }
            ?.let { "https://www.youtube.com/embed/$it" }

        val recommendations = document.select("div.section-slider-container div.slider-slide").mapNotNull {
            val a = it.selectFirst("a") ?: return@mapNotNull null
            val recName = a.attr("title").ifBlank { return@mapNotNull null }
            val recHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val img = it.selectFirst("img")
            val recPoster = fixUrlNull(img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))
            newMovieSearchResponse(recName, recHref, TvType.Movie) { this.posterUrl = recPoster }
        }

        return if (isSeries) {
            val episodes = document.select("div.seasons-tab-content a").mapNotNull {
                val epName = it.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
                val epHref = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val epNo = Regex("""(\d+)\. ?Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                val epSeason = Regex("""(\d+)\. ?Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                newEpisode(epHref) {
                    this.name = epName
                    this.season = epSeason
                    this.episode = epNo
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = ratingScore
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = ratingScore
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    // ------------------------------------------------------------------ video

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Her kaynağın oynatıcı sayfasından gelen altyazı izlerini dil koduna göre toplarız
        // (ilk bulunan kazanır). Böylece 5 farklı kaynak 5 kez tekrar eden altyazı üretmez.
        val tracks = LinkedHashMap<String, String>()
        var found = false

        // TODO-VERIFY: "div.alternative-links" (data-lang) > "button.alternative-link" (data-video)
        for (block in document.select("div.alternative-links")) {
            val langCode = block.attr("data-lang").uppercase(Locale.ROOT)
            for (button in block.select("button.alternative-link")) {
                val videoId = button.attr("data-video")
                if (videoId.isBlank()) continue
                val source = (button.text().replace("(HDrip Xbet)", "").trim() + " $langCode").trim()

                try {
                    if (invokeSource(source, videoId, data, tracks, callback)) found = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Kaynak başarısız: $source", e)
                }
            }
        }

        try {
            emitSubtitles(tracks, isCasting, subtitleCallback)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Altyazı hazırlanamadı", e)
        }
        return found
    }

    private suspend fun invokeSource(
        source: String,
        videoId: String,
        referer: String,
        tracks: MutableMap<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1) Video kimliğinden oynatıcı iframe adresini al (JSON içinde kaçışlı HTML döner)
        val apiGet = app.get(
            "$mainUrl/video/$videoId/",
            headers = mapOf("Content-Type" to "application/json", "X-Requested-With" to "fetch"),
            referer = referer
        ).text

        var iframe = Regex("""data-src=\\"([^"]+)""").find(apiGet)?.groupValues?.get(1)?.replace("\\", "")
            ?: return false
        if (iframe.contains("?rapidrame_id=")) {
            iframe = "$mainUrl/playerr/" + iframe.substringAfter("?rapidrame_id=")
        }
        Log.d(TAG, "$source » $videoId » $iframe")

        // 2) Oynatıcı sayfasındaki paketlenmiş (eval) betiği çöz
        val script = app.get(iframe, referer = "$mainUrl/").document.select("script")
            .find { it.data().contains("sources:") }?.data() ?: return false

        val encoded = getAndUnpack(script).substringAfter("file_link=\"", "").substringBefore("\";")
        if (encoded.isBlank()) return false
        val videoUrl = base64Decode(encoded)

        callback.invoke(
            newExtractorLink(
                source = source,
                name = source,
                url = videoUrl,
                type = if (videoUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else INFER_TYPE
            ) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
            }
        )

        // 3) Altyazı izleri: tracks: [{"file":"...","label":"...","kind":"captions"}, ...]
        val trackJson = script.substringAfter("tracks: [", "").substringBefore("]")
        if (trackJson.isNotBlank()) {
            tryParseJson<List<SubSource>>("[$trackJson]")
                ?.filter { it.kind == null || it.kind == "captions" }
                ?.forEach { t ->
                    val file = t.file ?: return@forEach
                    val code = detectLang(t.label, file) ?: return@forEach
                    tracks.getOrPut(code) { fixUrl(file) }
                }
        }
        return true
    }

    // =====================================================================================
    //  ALTYAZI ENTEGRASYONU
    //
    //  Cloudstream'de eklentinin verdiği her altyazı bir SubtitleFile(lang, url) olur ve
    //  oynatıcının altyazı menüsüne eklenir (içeride SubtitleData'ya çevrilir: originalName=lang,
    //  url, origin=URL, mimeType=URL'nin uzantısından). lang metni kullanıcıya gösterilir ve
    //  "tercih edilen altyazı dili" filtresi bu metinde dil adını arar; bu yüzden etiketlerde
    //  "Turkish" ve "English" sözcüklerini kullanıyoruz.
    //
    //  SINIR: ExoPlayer aynı anda TEK bir altyazı izi gösterir. Gerçek çift altyazı için iki dosyayı
    //  indirip kendimiz birleştirir ve TEK bir SubtitleFile olarak veririz (DualSubtitleEngine).
    //
    //  BAĞIMSIZ ZAMANLAMA: iki dil birleştirmeden ÖNCE kendi offset'iyle ayrı ayrı kaydırılır;
    //  birleştirme cue eşleştirmesi yapmaz, yalnızca "o anda hangisi aktif" bilgisine bakar.
    // =====================================================================================

    private suspend fun emitSubtitles(
        tracks: Map<String, String>,
        isCasting: Boolean,
        cb: (SubtitleFile) -> Unit
    ) {
        val mode = SubtitleSettings.mode
        if (mode == SubMode.OFF) return

        val trUrl = tracks["tr"]
        val enUrl = tracks["en"]
        val trOff = SubtitleSettings.trOffsetMs
        val enOff = SubtitleSettings.enOffsetMs

        when (mode) {
            SubMode.TR_ONLY -> trUrl?.let { single("Turkish", it, trOff, isCasting)?.let(cb) }
            SubMode.EN_ONLY -> enUrl?.let { single("English", it, enOff, isCasting)?.let(cb) }
            SubMode.DUAL -> {
                // Chromecast gibi cihazlar telefonun 127.0.0.1 adresine ulaşamaz: ayrı ayrı ver.
                val merged = if (trUrl != null && enUrl != null && !isCasting) buildDual(trUrl, enUrl, trOff, enOff) else null
                if (merged != null) {
                    cb(merged)
                } else {
                    // Biri yoksa (veya birleştirme başarısızsa) eldekileri ayrı ayrı ver
                    trUrl?.let { single("Turkish", it, trOff, isCasting)?.let(cb) }
                    enUrl?.let { single("English", it, enOff, isCasting)?.let(cb) }
                }
            }
            SubMode.OFF -> Unit
        }
    }

    private suspend fun fetchCues(url: String): List<Cue> {
        val bytes = app.get(url, referer = "$mainUrl/").okhttpResponse.body.bytes()
        return DualSubtitleEngine.parse(DualSubtitleEngine.decodeSmart(bytes))
    }

    /** Tek dil. Offset yoksa (veya cast ediliyorsa) orijinal URL'yi olduğu gibi verir. */
    private suspend fun single(label: String, url: String, offsetMs: Long, isCasting: Boolean): SubtitleFile? {
        if (offsetMs == 0L || isCasting) return newSubtitleFile(label, url)
        val shifted = try {
            DualSubtitleEngine.shift(fetchCues(url), offsetMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        if (shifted.isEmpty()) return newSubtitleFile(label, url)

        val served = LocalSubServer.serve(
            "single_${stamp(url)}_$offsetMs.srt",
            DualSubtitleEngine.toSrt(shifted)
        ) ?: return newSubtitleFile(label, url)
        return newSubtitleFile("$label (${if (offsetMs > 0) "+" else ""}$offsetMs ms)", served)
    }

    private suspend fun buildDual(trUrl: String, enUrl: String, trOff: Long, enOff: Long): SubtitleFile? {
        val trCues = try { fetchCues(trUrl) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        val enCues = try { fetchCues(enUrl) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        // Biri okunamadıysa dual anlamsız; çağıran ayrı ayrı vermeye düşer
        if (trCues.isEmpty() || enCues.isEmpty()) return null

        // Her dil KENDİ offset'iyle, birbirinden bağımsız kaydırılır
        val tr = DualSubtitleEngine.shift(trCues, trOff)
        val en = DualSubtitleEngine.shift(enCues, enOff)

        val trOnTop = SubtitleSettings.trOnTop
        val color = if (SubtitleSettings.colorSecondLine) "#FFD54F" else null
        val merged = if (trOnTop) DualSubtitleEngine.merge(tr, en, color) else DualSubtitleEngine.merge(en, tr, color)
        if (merged.isEmpty()) return null

        val served = LocalSubServer.serve(
            "dual_${stamp(trUrl + "|" + enUrl)}_${trOff}_${enOff}_${if (trOnTop) "t" else "e"}_${if (color != null) "c" else "n"}.srt",
            DualSubtitleEngine.toSrt(merged)
        ) ?: return null

        val label = if (trOnTop) "Turkish + English (Dual)" else "English + Turkish (Dual)"
        return newSubtitleFile(label, served)
    }

    /** URL'den kısa, dosya adı olarak güvenli bir parmak izi. */
    private fun stamp(s: String): String = Integer.toHexString(s.hashCode())

    /** Etiket veya dosya adından dil kodu çıkarır. Yalnızca "tr" ve "en" ile ilgileniriz. */
    private fun detectLang(label: String?, url: String): String? {
        fun norm(x: String) = x.lowercase(Locale.ROOT)
            .replace("\u0307", "")
            .replace('ı', 'i').replace('ü', 'u').replace('ç', 'c')
            .replace('ş', 's').replace('ğ', 'g').replace('ö', 'o')

        val l = norm(label.orEmpty()).trim()
        val u = norm(url.substringAfterLast('/').substringBefore('?'))

        return when {
            l.startsWith("turk") || l == "tr" || l == "tur" || l.startsWith("turkish") -> "tr"
            l.startsWith("ingiliz") || l.startsWith("english") || l == "en" || l == "eng" -> "en"
            Regex("""(^|[._\-])(tr|tur|turkish|turkce)([._\-]|$)""").containsMatchIn(u) -> "tr"
            Regex("""(^|[._\-])(en|eng|english|ingilizce)([._\-]|$)""").containsMatchIn(u) -> "en"
            else -> null
        }
    }

    private data class SubSource(
        @param:JsonProperty("file") val file: String? = null,
        @param:JsonProperty("label") val label: String? = null,
        @param:JsonProperty("kind") val kind: String? = null
    )

    data class Results(
        @param:JsonProperty("results") val results: List<String> = arrayListOf()
    )

    private companion object {
        const val TAG = "HDFCDual"
    }
}
