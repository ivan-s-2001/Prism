package com.prism.music.ui.theme

import android.content.Context
import android.util.Base64
import android.util.LruCache
import android.util.Xml
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import org.xmlpull.v1.XmlPullParser

/** Offline Tabler catalog from the same pinned build archive as PrismIcons (MIT; design/TABLER-LICENSE.txt). */
internal object VibePresetIcons {
    private data class Catalog(val data: Map<String, String>, val names: List<String>, val aliases: Map<String, String>)
    @Volatile private var loaded: Catalog? = null
    private val cache = LruCache<String, ImageVector>(256)
    private val valid = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

    private fun catalog(context: Context): Catalog {
        loaded?.let { return it }
        return synchronized(this) {
            loaded ?: runCatching {
                val data = context.assets.open("vibe-icons/catalog.tsv").bufferedReader().useLines { lines ->
                    lines.mapNotNull { line ->
                        val split = line.indexOf('\t')
                        if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
                    }.toMap()
                }
                val aliases = context.assets.open("vibe-icons/aliases.tsv").bufferedReader().useLines { lines ->
                    lines.mapNotNull { line ->
                        val split = line.indexOf('\t')
                        if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
                    }.toMap()
                }
                Catalog(data, data.keys.filter { it.startsWith("outline/") }.map { it.removePrefix("outline/") }.sorted(), aliases)
            }.getOrElse { Catalog(emptyMap(), listOf("bookmark"), emptyMap()) }.also { loaded = it }
        }
    }

    fun names(context: Context): List<String> = catalog(context).names
    fun vector(context: Context, id: String): ImageVector {
        if (id == "bookmark" || !valid.matches(id)) return PrismIcons.Bookmark
        val key = "$id:${IconStyle.pack}:${IconStyle.fill}:${IconStyle.weight}:${IconStyle.rounded}"
        cache.get(key)?.let { return it }
        val catalog = catalog(context)
        val value = runCatching {
            val alias = catalog.aliases[id]
            if (alias != null) PrismIcons.preview(alias, IconStyle.fill, IconStyle.pack)
            else {
                val filled = IconStyle.fill == IconFill.FILLED && catalog.data.containsKey("filled/$id")
                val encoded = catalog.data[if (filled) "filled/$id" else "outline/$id"] ?: return PrismIcons.Bookmark
                decode(id, Base64.decode(encoded, Base64.DEFAULT), filled)
            }
        }.getOrElse { PrismIcons.Bookmark }
        cache.put(key, value)
        return value
    }

    private fun decode(id: String, bytes: ByteArray, filled: Boolean): ImageVector {
        val parser = Xml.newPullParser()
        parser.setInput(bytes.inputStream(), "UTF-8")
        val ink = SolidColor(Color.Black)
        val round = IconStyle.rounded && IconStyle.pack != IconPack.FACET
        val builder = ImageVector.Builder("Tabler-$id", 24.dp, 24.dp, 24f, 24f)
        var event = parser.eventType
        var count = 0
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                fun attr(key: String): String = parser.getAttributeValue(null, key).orEmpty()
                fun n(key: String): Float = attr(key).toFloatOrNull() ?: 0f
                val path = when (parser.name) {
                    "path" -> attr("d")
                    "line" -> "M ${n("x1")} ${n("y1")} L ${n("x2")} ${n("y2")}"
                    "circle" -> {
                        val x = n("cx"); val y = n("cy"); val r = n("r")
                        "M ${x+r} $y A $r $r 0 1 1 ${x-r} $y A $r $r 0 1 1 ${x+r} $y Z"
                    }
                    "rect" -> {
                        val x = n("x"); val y = n("y"); val w = n("width"); val h = n("height")
                        val r = n("rx").coerceIn(0f, minOf(w, h).coerceAtLeast(0f) / 2f)
                        if (r == 0f) "M $x $y h $w v $h h ${-w} Z"
                        else "M ${x+r} $y H ${x+w-r} A $r $r 0 0 1 ${x+w} ${y+r} V ${y+h-r} A $r $r 0 0 1 ${x+w-r} ${y+h} H ${x+r} A $r $r 0 0 1 $x ${y+h-r} V ${y+r} A $r $r 0 0 1 ${x+r} $y Z"
                    }
                    "polyline", "polygon" -> {
                        val points = Regex("[-+]?(?:[0-9]+[.]?[0-9]*|[.][0-9]+)").findAll(attr("points")).map { it.value }.toList()
                        if (points.size < 4 || points.size % 2 != 0) "" else
                            "M " + points.chunked(2).joinToString(" L ") { it.joinToString(" ") } +
                                if (parser.name == "polygon") " Z" else ""
                    }
                    else -> ""
                }
                if (path.isNotBlank() && !(attr("stroke") == "none" && attr("fill") == "none")) {
                    val isFill = attr("fill") != "none" && (filled || attr("fill") == "currentColor")
                    val isStroke = attr("stroke") != "none" && (!filled || attr("stroke").isNotBlank())
                    val duo = !isFill && IconStyle.fill == IconFill.DUOTONE && path.trim().endsWith("z", true)
                    if (isFill || isStroke || duo) {
                        builder.addPath(addPathNodes(path),
                            fill = if (isFill || duo) ink else null, fillAlpha = if (duo) 0.24f else 1f,
                            stroke = if (isStroke) ink else null,
                            strokeLineWidth = (attr("stroke-width").toFloatOrNull() ?: 2f) * IconStyle.weight.scale,
                            strokeLineCap = if (round) StrokeCap.Round else StrokeCap.Butt,
                            strokeLineJoin = if (round) StrokeJoin.Round else StrokeJoin.Miter)
                        count++
                    }
                }
            }
            event = parser.next()
        }
        require(count > 0)
        return builder.build()
    }
}
