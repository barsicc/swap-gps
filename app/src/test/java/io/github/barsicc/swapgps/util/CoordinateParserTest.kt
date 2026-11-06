package io.github.barsicc.swapgps.util

import io.github.barsicc.swapgps.engine.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoordinateParserTest {

    private val moscow = GeoPoint(55.7558, 37.6173)

    private fun check(input: String, expected: GeoPoint? = moscow) {
        val result = CoordinateParser.parse(input)
        if (expected == null) {
            assertNull(input, result)
        } else {
            requireNotNull(result) { "Не распознано: $input" }
            assertEquals(input, expected.latitude, result.latitude, 1e-9)
            assertEquals(input, expected.longitude, result.longitude, 1e-9)
        }
    }

    @Test fun commaSeparated() = check("55.7558, 37.6173")
    @Test fun commaNoSpace() = check("55.7558,37.6173")
    @Test fun spaceSeparated() = check("55.7558 37.6173")
    @Test fun semicolon() = check("55.7558; 37.6173")
    @Test fun decimalCommas() = check("55,7558 37,6173")
    @Test fun decimalCommasSemicolon() = check("55,7558; 37,6173")
    @Test fun parentheses() = check("(55.7558, 37.6173)")
    @Test fun negative() = check("-33.8688, 151.2093", GeoPoint(-33.8688, 151.2093))
    @Test fun geoUri() = check("geo:55.7558,37.6173?z=16")
    @Test fun googleAt() = check("https://www.google.com/maps/@55.7558,37.6173,15z")
    @Test fun googleQuery() = check("https://maps.google.com/?q=55.7558,37.6173")
    @Test fun yandexLl() = check("https://yandex.ru/maps/?ll=37.6173%2C55.7558&z=16")
    @Test fun yandexPt() = check("https://yandex.ru/maps/?pt=37.6173,55.7558&z=16")

    @Test fun outOfRange() = check("95.0, 37.0", null)
    @Test fun garbage() = check("улица Молодёжная", null)
    @Test fun empty() = check("   ", null)
}
