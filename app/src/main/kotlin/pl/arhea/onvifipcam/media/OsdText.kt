package pl.arhea.onvifipcam.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** On-screen display: camera name and local date/time, rendered to a bitmap once per second. */
class OsdText(private val name: String) {
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TEXT_SIZE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val stroke = Paint(fill).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = TEXT_SIZE / 8f
    }

    fun currentText(): String {
        val time = fmt.format(Date())
        return if (name.isBlank()) time else "$time  $name"
    }

    fun render(text: String): Bitmap {
        val pad = (TEXT_SIZE / 6f).toInt()
        val w = fill.measureText(text).toInt() + pad * 2
        val fm = fill.fontMetricsInt
        val h = fm.descent - fm.ascent + pad * 2
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val y = (pad - fm.ascent).toFloat()
        c.drawText(text, pad.toFloat(), y, stroke)
        c.drawText(text, pad.toFloat(), y, fill)
        return bmp
    }

    companion object {
        private const val TEXT_SIZE = 48f
    }
}
