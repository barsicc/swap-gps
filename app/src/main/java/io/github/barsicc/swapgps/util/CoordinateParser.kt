package io.github.barsicc.swapgps.util

import io.github.barsicc.swapgps.engine.GeoPoint
import java.net.URLDecoder

/**
 * Распознаёт координаты в том виде, в каком их обычно копируют:
 *  - «55.7558, 37.6173», «55.7558 37.6173», «55,7558 37,6173», «55.7558; 37.6173»
 *  - geo:-ссылка: «geo:55.7558,37.6173?z=16»
 *  - ссылки Google Карт: «…/@55.7558,37.6173,15z», «…?q=55.7558,37.6173»
 *  - ссылки Яндекс Карт: «…?ll=37.6173,55.7558» и «pt=37.6173,55.7558» (там сначала долгота!)
 */
object CoordinateParser {

    private const val NUM = """-?\d{1,3}(?:[.,]\d+)?"""

    fun parse(input: String): GeoPoint? {
        val text = runCatching { URLDecoder.decode(input.trim(), "UTF-8") }.getOrDefault(input.trim())
        if (text.isEmpty()) return null

        // Яндекс: ll= / pt= — порядок «долгота,широта»
        Regex("""[?&](?:pt|ll)=(-?\d+\.\d+),(-?\d+\.\d+)""").find(text)?.let {
            return valid(it.groupValues[2], it.groupValues[1])
        }
        // Google: /@lat,lon
        Regex("""@(-?\d+\.\d+),(-?\d+\.\d+)""").find(text)?.let {
            return valid(it.groupValues[1], it.groupValues[2])
        }
        // geo:lat,lon и q=lat,lon
        Regex("""(?:geo:|[?&]q=)(-?\d+\.\d+),\s*(-?\d+\.\d+)""").find(text)?.let {
            return valid(it.groupValues[1], it.groupValues[2])
        }

        // Просто два числа. Если внутри чисел запятые, разделитель — пробел или точка с запятой
        val plain = text.removePrefix("(").removeSuffix(")")
        Regex("""^\s*($NUM)\s*[,;]\s*($NUM)\s*$""").find(plain)?.let {
            // «55.75, 37.61» — запятая как разделитель
            if (!it.groupValues[1].contains(',') && !it.groupValues[2].contains(','))
                return valid(it.groupValues[1], it.groupValues[2])
        }
        Regex("""^\s*($NUM)\s*[;\s]\s*($NUM)\s*$""").find(plain)?.let {
            return valid(it.groupValues[1], it.groupValues[2])
        }
        return null
    }

    private fun valid(latText: String, lonText: String): GeoPoint? {
        val lat = latText.replace(',', '.').toDoubleOrNull() ?: return null
        val lon = lonText.replace(',', '.').toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return GeoPoint(lat, lon)
    }
}
