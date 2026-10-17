package com.hdfcdual

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

enum class SubMode(val label: String) {
    TR_ONLY("Sadece Türkçe"),
    EN_ONLY("Sadece İngilizce"),
    DUAL("İkisi Birlikte (Dual)"),
    OFF("Altyazıları Kapat")
}

/**
 * Kullanıcı ayarları. Hepsi SharedPreferences'ta saklanır ve loadLinks sırasında okunur.
 *
 * ÖNEMLİ: Offset'ler iki dil için AYRI tutulur (trOffsetMs / enOffsetMs). Birleştirme
 * yapılmadan önce her dil kendi offset'iyle kaydırılır; böylece biri diğerini etkilemez.
 */
object SubtitleSettings {
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("hdfcdual_subtitles", Context.MODE_PRIVATE)
    }

    var mode: SubMode
        get() = SubMode.values().getOrElse(prefs?.getInt("mode", SubMode.DUAL.ordinal) ?: SubMode.DUAL.ordinal) { SubMode.DUAL }
        set(v) { prefs?.edit()?.putInt("mode", v.ordinal)?.apply() }

    /** Milisaniye. Pozitif = altyazı daha GEÇ görünür, negatif = daha ERKEN görünür. */
    var trOffsetMs: Long
        get() = prefs?.getLong("tr_offset", 0L) ?: 0L
        set(v) { prefs?.edit()?.putLong("tr_offset", v)?.apply() }

    var enOffsetMs: Long
        get() = prefs?.getLong("en_offset", 0L) ?: 0L
        set(v) { prefs?.edit()?.putLong("en_offset", v)?.apply() }

    /** Dual modda Türkçe üstte mi? */
    var trOnTop: Boolean
        get() = prefs?.getBoolean("tr_top", true) ?: true
        set(v) { prefs?.edit()?.putBoolean("tr_top", v)?.apply() }

    /** Dual modda alttaki satırı sarı renkle ayır. */
    var colorSecondLine: Boolean
        get() = prefs?.getBoolean("color_second", true) ?: true
        set(v) { prefs?.edit()?.putBoolean("color_second", v)?.apply() }

    // ------------------------------------------------------------------ arayüz

    fun showDialog(ctx: Context) {
        val items = arrayOf(
            "Altyazı modu: ${mode.label}",
            "Türkçe gecikme: ${fmtOffset(trOffsetMs)}",
            "İngilizce gecikme: ${fmtOffset(enOffsetMs)}",
            "Dual sıralama: ${if (trOnTop) "Türkçe üstte" else "İngilizce üstte"}",
            "Dual alt satır sarı: ${if (colorSecondLine) "Açık" else "Kapalı"}"
        )
        AlertDialog.Builder(ctx)
            .setTitle("HDFilmCehennemi Altyazı Ayarları")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickMode(ctx)
                    1 -> askOffset(ctx, "Türkçe gecikme", trOffsetMs) { trOffsetMs = it }
                    2 -> askOffset(ctx, "İngilizce gecikme", enOffsetMs) { enOffsetMs = it }
                    3 -> { trOnTop = !trOnTop; showDialog(ctx) }
                    4 -> { colorSecondLine = !colorSecondLine; showDialog(ctx) }
                }
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun fmtOffset(ms: Long) = if (ms > 0) "+$ms ms" else "$ms ms"

    private fun pickMode(ctx: Context) {
        val modes = SubMode.values()
        AlertDialog.Builder(ctx)
            .setTitle("Altyazı modu")
            .setSingleChoiceItems(modes.map { it.label }.toTypedArray(), mode.ordinal) { d, i ->
                mode = modes[i]
                d.dismiss()
                showDialog(ctx)
            }
            .setNegativeButton("İptal") { _, _ -> showDialog(ctx) }
            .show()
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun askOffset(ctx: Context, title: String, current: Long, save: (Long) -> Unit) {
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(current.toString())
            setSelection(text.length)
        }

        fun bump(delta: Long) {
            val now = input.text.toString().toLongOrNull() ?: 0L
            input.setText((now + delta).toString())
            input.setSelection(input.text.length)
        }

        val quick = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            listOf(-1000L, -250L, 250L, 1000L).forEach { d ->
                addView(Button(ctx).apply {
                    text = if (d > 0) "+$d" else "$d"
                    setOnClickListener { bump(d) }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

        val hint = TextView(ctx).apply {
            text = "Milisaniye. Pozitif = altyazı geç gelir, negatif = erken gelir.\nBu değer diğer dili etkilemez. Kaydettikten sonra bölümü yeniden aç."
            textSize = 12f
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), 0)
            addView(hint)
            addView(input)
            addView(quick)
        }

        AlertDialog.Builder(ctx)
            .setTitle("$title (ms)")
            .setView(root)
            .setPositiveButton("Kaydet") { _, _ ->
                val v = input.text.toString().toLongOrNull()
                if (v == null) {
                    Toast.makeText(ctx, "Geçersiz sayı", Toast.LENGTH_SHORT).show()
                } else {
                    save(v.coerceIn(-600_000L, 600_000L))
                    Toast.makeText(ctx, "Kaydedildi. Bölümü yeniden aç.", Toast.LENGTH_SHORT).show()
                }
                showDialog(ctx)
            }
            .setNeutralButton("Sıfırla") { _, _ -> save(0L); showDialog(ctx) }
            .setNegativeButton("İptal") { _, _ -> showDialog(ctx) }
            .show()
    }
}
