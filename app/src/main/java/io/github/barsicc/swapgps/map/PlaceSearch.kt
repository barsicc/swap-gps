package io.github.barsicc.swapgps.map

import com.yandex.mapkit.geometry.BoundingBox
import com.yandex.mapkit.geometry.Geometry
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.search.Response
import com.yandex.mapkit.search.SearchFactory
import com.yandex.mapkit.search.SearchManagerType
import com.yandex.mapkit.search.SearchOptions
import com.yandex.mapkit.search.SearchType
import com.yandex.mapkit.search.Session
import com.yandex.mapkit.search.SuggestOptions
import com.yandex.mapkit.search.SuggestResponse
import com.yandex.mapkit.search.SuggestSession
import com.yandex.mapkit.search.SuggestType
import com.yandex.runtime.Error
import com.yandex.runtime.network.NetworkError
import io.github.barsicc.swapgps.engine.Diag
import io.github.barsicc.swapgps.engine.GeoPoint

/** Подсказка поиска. */
data class Suggestion(
    val title: String,
    val subtitle: String?,
    val distance: String?,
    /** Координаты, если Яндекс их сразу прислал. */
    val point: GeoPoint?,
    /** Текст, по которому искать точку, если координат нет. */
    val searchText: String,
    val uri: String?,
)

/**
 * Поиск Яндекса: подсказки по мере ввода (ближайшие места — первыми),
 * поиск координат выбранной подсказки и адрес точки под меткой.
 */
class PlaceSearch {

    private val manager = SearchFactory.getInstance().createSearchManager(SearchManagerType.ONLINE)
    private val suggestSession: SuggestSession = manager.createSuggestSession()

    // Сессии нужно держать, иначе запрос отменится
    private var resolveSession: Session? = null
    private var addressSession: Session? = null

    fun suggest(
        text: String,
        around: GeoPoint,
        bounds: BoundingBox,
        onResult: (List<Suggestion>) -> Unit,
        onError: (String) -> Unit,
    ) {
        val options = SuggestOptions()
            .setSuggestTypes(SuggestType.GEO.value or SuggestType.BIZ.value)
            .setUserPosition(Point(around.latitude, around.longitude))
            .setStrictBounds(false)
        suggestSession.suggest(text, bounds, options, object : SuggestSession.SuggestListener {
            override fun onResponse(response: SuggestResponse) {
                onResult(response.items.take(8).map { item ->
                    Suggestion(
                        title = item.title.text,
                        subtitle = item.subtitle?.text,
                        distance = item.distance?.text,
                        point = item.center?.let { GeoPoint(it.latitude, it.longitude) },
                        searchText = item.searchText ?: item.displayText ?: item.title.text,
                        uri = item.uri,
                    )
                })
            }

            override fun onError(error: Error) {
                onError(describe(error))
            }
        })
    }

    fun cancelSuggest() = suggestSession.reset()

    /** Найти координаты подсказки, если Яндекс не прислал их сразу. */
    fun resolve(suggestion: Suggestion, area: Geometry, onResult: (GeoPoint?) -> Unit) {
        suggestion.point?.let { onResult(it); return }
        val listener = object : Session.SearchListener {
            override fun onSearchResponse(response: Response) {
                onResult(firstPoint(response))
            }

            override fun onSearchError(error: Error) {
                Diag.log("Поиск: ${describe(error)}")
                onResult(null)
            }
        }
        val options = SearchOptions().setResultPageSize(1)
        resolveSession = if (suggestion.uri != null) {
            manager.searchByURI(suggestion.uri, options, listener)
        } else {
            manager.submit(suggestion.searchText, area, options, listener)
        }
    }

    /** Адрес точки (улица и дом), чтобы показать его под меткой. */
    fun address(point: GeoPoint, zoom: Int, onResult: (String?) -> Unit) {
        addressSession?.cancel()
        addressSession = manager.submit(
            Point(point.latitude, point.longitude),
            zoom,
            SearchOptions().setSearchTypes(SearchType.GEO.value).setResultPageSize(1),
            object : Session.SearchListener {
                override fun onSearchResponse(response: Response) {
                    onResult(response.collection.children.firstOrNull()?.obj?.name)
                }

                override fun onSearchError(error: Error) {
                    onResult(null)
                }
            },
        )
    }

    private fun firstPoint(response: Response): GeoPoint? =
        response.collection.children.firstOrNull()?.obj?.geometry?.firstOrNull()?.point
            ?.let { GeoPoint(it.latitude, it.longitude) }

    private fun describe(error: Error): String =
        if (error is NetworkError) "нет связи с сервером Яндекса" else "ошибка поиска (${error.javaClass.simpleName})"
}
