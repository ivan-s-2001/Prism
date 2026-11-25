package com.prism.music.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Original 24-unit line silhouettes, matching design/icons/yandex-stations.svg.
 * No network image loads or third-party artwork are needed.
 */
internal object StationIcons {
    private fun vector(name: String, d: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()

    private val models by lazy {
        mapOf(
        "Яндекс Станция 1" to vector("station1", "M7 3 H17 Q19 3 19 5 V21 H5 V5 Q5 3 7 3 Z M5 6 H19 M10 4.5 H14 M7 19 H17"),
        "Яндекс Станция 2" to vector("station2", "M7 3 H17 Q20 3 20 6 V20 Q20 21 18 21 H6 Q4 21 4 20 V6 Q4 3 7 3 Z M4 7 H20 M9 5 H15 M8 19 H16"),
        "Яндекс Станция 3" to vector("station3", "M8 2.5 H16 Q20 2.5 20 6.5 V18 Q20 21.5 16 21.5 H8 Q4 21.5 4 18 V6.5 Q4 2.5 8 2.5 Z M4 8 H20 M8 19 H16 M9 5 H15"),
        "Яндекс Станция Миди" to vector("midi", "M7 5 H17 Q20 5 20 8 V19 Q20 21 18 21 H6 Q4 21 4 19 V8 Q4 5 7 5 Z M4 9 H20 M9 7 H15 M8 13 H10 V15 H8 Z M14 13 H16 V15 H14 Z"),
        "Яндекс Станция Лайт" to vector("lite1", "M3 10 C3 7 7 5 12 5 C17 5 21 7 21 10 V16 C21 19 17 20 12 20 C7 20 3 19 3 16 Z M3 10 C3 13 7 14 12 14 C17 14 21 13 21 10 M10 9 H14"),
        "Яндекс Станция Лайт 2" to vector("lite2", "M4 8 Q4 5 8 5 H16 Q20 5 20 8 V17 Q20 20 17 20 H7 Q4 20 4 17 Z M4 10 H20 M9 7.5 H15 M10 13 H14 M10 16 H14"),
        "Яндекс Станция Мини 1" to vector("mini1", "M3 10 C3 7 7 6 12 6 C17 6 21 7 21 10 V15 C21 18 17 19 12 19 C7 19 3 18 3 15 Z M3 10 C3 12.5 7 14 12 14 C17 14 21 12.5 21 10 M11 9 H13"),
        "Яндекс Станция Мини 2" to vector("mini2", "M3 9 C3 6.5 7 5 12 5 C17 5 21 6.5 21 9 V16 C21 18.5 17 20 12 20 C7 20 3 18.5 3 16 Z M3 9 C3 11 7 12 12 12 C17 12 21 11 21 9 M8 15 H10 M14 15 H16 M12 14 V16"),
        "Яндекс Станция Мини 3" to vector("mini3", "M3 9 Q3 5 8 5 H16 Q21 5 21 9 V16 Q21 20 16 20 H8 Q3 20 3 16 Z M3 10 H21 M9 7 H15 M7 14 H10 M14 14 H17 M7 17 H10 M14 17 H17"),
        "Яндекс Станция Мини 3 Про" to vector("mini3pro", "M5 5 Q5 3 8 3 H16 Q19 3 19 5 V19 Q19 21 16 21 H8 Q5 21 5 19 Z M5 8 H19 M9 5.5 H15 M8 12 H11 M13 12 H16 M8 15 H11 M13 15 H16 M9 19 H15"),
        "Яндекс Станция Макс" to vector("max", "M6 3 H18 Q20 3 20 5 V21 H4 V5 Q4 3 6 3 Z M4 7 H20 M9 5 H15 M7 10 H17 V15 H7 Z M9 12.5 H11 M13 12.5 H15 M7 18 H17"),
        "Яндекс Станция Стрит" to vector("street", "M8 4 H16 Q19 4 19 8 V17 Q19 20 16 20 H8 Q5 20 5 17 V8 Q5 4 8 4 Z M8 4 V2 H16 V4 M5 9 H19 M5 16 H19 M10 6 H14"),
        "Яндекс Станция" to vector("generic", "M6 4 H18 Q20 4 20 6 V19 Q20 21 18 21 H6 Q4 21 4 19 V6 Q4 4 6 4 Z M4 8 H20 M10 6 H14 M12 12 A3 3 0 1 0 12 18 A3 3 0 1 0 12 12 Z"),
        )
    }

    fun forModel(model: String): ImageVector = models[model] ?: models.getValue("Яндекс Станция")
}
