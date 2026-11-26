package com.example.mopedtrackv2.data.remote

import com.squareup.moshi.JsonClass
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Field
import retrofit2.http.Url

val OVERPASS_ENDPOINTS = listOf(
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
)

/** Maps an Overpass `.../api/interpreter` endpoint to its lightweight `.../api/status` endpoint. */
fun overpassStatusUrl(interpreterUrl: String): String =
    interpreterUrl.removeSuffix("/interpreter") + "/status"

class OverpassException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface OsmApi {
    @Headers("User-Agent: MopedTrackApp/1.0 (mopedtrack.app@gmail.com)")
    @GET("https://nominatim.openstreetmap.org/search")
    suspend fun searchNominatim(
        @Query("q") query: String,
        @Query("format") format: String = "jsonv2",
        @Query("polygon_geojson") polygonGeojson: Int = 0
    ): List<NominatimResult>

    @Headers("User-Agent: MopedTrackApp/1.0 (mopedtrack.app@gmail.com)")
    @FormUrlEncoded
    @POST("")
    suspend fun queryOverpass(
        @Url url: String = OVERPASS_ENDPOINTS.first(),
        @Field("data") query: String
    ): OverpassResponse

    /** Cheap GET used to check whether an Overpass mirror is alive before sending a heavy query. */
    @Headers("User-Agent: MopedTrackApp/1.0 (mopedtrack.app@gmail.com)")
    @GET
    suspend fun getOverpassStatus(@Url url: String): Response<ResponseBody>
}

@JsonClass(generateAdapter = true)
data class NominatimResult(
    val place_id: Long,
    val osm_type: String, // "relation", "way", "node"
    val osm_id: Long,
    val display_name: String,
    val lat: String,
    val lon: String,
    val boundingbox: List<String>
)

@JsonClass(generateAdapter = true)
data class OverpassResponse(
    val elements: List<OverpassElement>,
    /** Overpass reports server-side failures (e.g. "runtime error: Query timed out") here with HTTP 200. */
    val remark: String? = null
)

@JsonClass(generateAdapter = true)
data class OverpassElement(
    val type: String,
    val id: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val tags: Map<String, String>? = null,
    val nodes: List<Long>? = null
)
