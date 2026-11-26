package com.example.mopedtrackv2.data.remote

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    /**
     * Overpass only sends response headers once the query has finished executing server-side,
     * so heavy queries need a much longer read timeout than regular API calls. Keep this a bit
     * above the `[timeout:N]` value used in the Overpass QL queries.
     */
    private const val OVERPASS_READ_TIMEOUT_SECONDS = 100

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath.endsWith("/api/interpreter")) {
                chain.withReadTimeout(OVERPASS_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS).proceed(request)
            } else {
                chain.proceed(request)
            }
        }
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://nominatim.openstreetmap.org/") // Base URL doesn't matter much since we use full URLs in @GET/@POST
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    val osmApi: OsmApi = retrofit.create(OsmApi::class.java)
}
