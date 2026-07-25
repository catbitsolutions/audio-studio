package com.example.audiostudio

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

private const val CATBIT_SITE = "https://catbit.com.ar"
private const val CATBIT_LOGO = "https://catbit.com.ar/images/logo-catbit.png"

/**
 * Pie de pagina: "Disenado por CatBit" + logo circular con glow.
 * Al tocarlo abre https://catbit.com.ar
 */
fun buildCatBitFooter(act: Activity): View {

    val dens = act.resources.displayMetrics.density
    fun dp(v: Int): Int = (v * dens).toInt()

    val row = LinearLayout(act)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER
    row.setPadding(dp(8), dp(26), dp(8), dp(14))

    // ---- Texto: "Disenado por CatBit" -------------------------------
    val txt = TextView(act)
    val sb = SpannableStringBuilder()
    sb.append("Diseñado por ")

    val iCat = sb.length
    sb.append("Cat")
    val iBit = sb.length
    sb.append("Bit")

    sb.setSpan(
        ForegroundColorSpan(Color.parseColor("#FFFFFFFF")),
        iCat, iBit, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    )
    sb.setSpan(
        ForegroundColorSpan(Color.parseColor("#FF39FF88")),
        iBit, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    )
    sb.setSpan(
        StyleSpan(Typeface.BOLD),
        iCat, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    )

    txt.text = sb
    txt.textSize = 14f
    txt.setTextColor(Color.parseColor("#CCDDDDEE"))
    row.addView(txt)

    // ---- Logo circular con resplandor -------------------------------
    val boxDp = 46
    val boxPx = dp(boxDp)

    val logo = ImageView(act)
    val lp = LinearLayout.LayoutParams(boxPx, boxPx)
    lp.leftMargin = dp(8)
    logo.layoutParams = lp
    val pad = dp(9)
    logo.setPadding(pad, pad, pad, pad)
    logo.background = glowCircle(act, boxPx)
    logo.scaleType = ImageView.ScaleType.FIT_CENTER
    logo.visibility = View.INVISIBLE
    row.addView(logo)

    // ---- Click: abrir el sitio --------------------------------------
    row.isClickable = true
    row.setOnClickListener {
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(CATBIT_SITE))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            act.startActivity(i)
        } catch (e: Throwable) {
        }
    }

    // ---- Animacion tipo :hover --------------------------------------
    row.setOnTouchListener { _, ev ->
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                logo.animate().scaleX(1.15f).scaleY(1.15f)
                    .rotation(-5f).setDuration(180L).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                logo.animate().scaleX(1f).scaleY(1f)
                    .rotation(0f).setDuration(180L).start()
            }
        }
        false
    }

    loadCatBitLogo(act, logo, boxPx)
    return row
}

/** Circulo blanco con glow radial (equivale al box-shadow del CSS). */
private fun glowCircle(act: Activity, size: Int): Drawable {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val r = size / 2f
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.shader = RadialGradient(
        r, r, r,
        intArrayOf(
            Color.argb(220, 255, 255, 255),
            Color.argb(220, 255, 255, 255),
            Color.argb(120, 255, 255, 255),
            Color.argb(0, 255, 255, 255)
        ),
        floatArrayOf(0f, 0.74f, 0.88f, 1f),
        Shader.TileMode.CLAMP
    )
    c.drawCircle(r, r, r, p)
    return BitmapDrawable(act.resources, bmp)
}

/** Descarga el logo (y lo cachea). Si falla, dibuja "CB". */
private fun loadCatBitLogo(act: Activity, view: ImageView, sizePx: Int) {
    val h = Handler(Looper.getMainLooper())
    val cache = File(act.cacheDir, "catbit_logo.png")

    thread(name = "catbit-logo") {
        var bmp: Bitmap? = null
        try {
            if (cache.exists() && cache.length() > 128L) {
                bmp = BitmapFactory.decodeFile(cache.absolutePath)
            }
            if (bmp == null) {
                val cn = URL(CATBIT_LOGO).openConnection() as HttpURLConnection
                cn.connectTimeout = 8000
                cn.readTimeout = 8000
                cn.instanceFollowRedirects = true
                cn.setRequestProperty("User-Agent", "AudioStudio")
                cn.connect()
                if (cn.responseCode == 200) {
                    cn.inputStream.use { inp ->
                        FileOutputStream(cache).use { out -> inp.copyTo(out) }
                    }
                    bmp = BitmapFactory.decodeFile(cache.absolutePath)
                }
                cn.disconnect()
            }
        } catch (e: Throwable) {
            bmp = null
        }

        val result = bmp ?: fallbackLogo(sizePx)
        h.post {
            view.setImageBitmap(result)
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(350L).start()
        }
    }
}

/** Logo de respaldo si no hay internet: las letras CB. */
private fun fallbackLogo(sizePx: Int): Bitmap {
    val s = if (sizePx > 32) sizePx else 96
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = Color.parseColor("#FF111318")
    p.textAlign = Paint.Align.CENTER
    p.textSize = s * 0.44f
    p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    val fm = p.fontMetrics
    val y = s / 2f - (fm.ascent + fm.descent) / 2f
    c.drawText("CB", s / 2f, y, p)
    return bmp
}
