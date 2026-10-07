package com.c0mpile.grimmreader.api.grimmory

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Grimmory REST endpoints used by the app. Paths are relative to the server base URL (which may have a prefix). */
interface GrimmoryApi {
    @GET("api/v1/public-settings")
    suspend fun publicSettings(): PublicSettingsDto

    @POST("api/v1/auth/login")
    suspend fun login(
        @Body body: LoginRequestDto,
    ): TokenDto

    @POST("api/v1/auth/refresh")
    suspend fun refresh(
        @Body body: RefreshRequestDto,
    ): TokenDto

    @POST("api/v1/auth/logout")
    suspend fun logout(): Response<Unit>

    /** Full permission set (`/api/v1/app/users/me` only has five flags). */
    @GET("api/v1/users/me")
    suspend fun me(): UserDto

    /** `/api/v1/app/libraries` returns HTTP 500 on v3.5.0 (PLAN §15); this one works. */
    @GET("api/v1/libraries")
    suspend fun libraries(): List<LibraryDto>

    @GET("api/v1/app/books")
    suspend fun books(
        @Query("page") page: Int,
        @Query("size") size: Int,
        @Query("libraryId") libraryId: Long? = null,
        @Query("sort") sort: String? = null,
        @Query("dir") dir: String? = null,
    ): BookPageDto

    @GET("api/v1/app/books/{bookId}")
    suspend fun book(
        @Path("bookId") bookId: Long,
    ): BookDetailDto

    @GET("api/v1/app/books/{bookId}/progress")
    suspend fun progress(
        @Path("bookId") bookId: Long,
    ): ProgressDto

    @PUT("api/v1/app/books/{bookId}/progress")
    suspend fun updateProgress(
        @Path("bookId") bookId: Long,
        @Body body: UpdateProgressDto,
    ): Response<Unit>

    /** 1-based page numbers of a comic, extracted on the server (works for CBZ, CB7 and CBR alike). */
    @GET("api/v1/cbx/{bookId}/pages")
    suspend fun cbxPages(
        @Path("bookId") bookId: Long,
    ): List<Int>

    companion object {
        val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                coerceInputValues = true
            }

        /** [client] must be the guarded client (plus the auth interceptor); never a new one. */
        fun create(
            baseUrl: HttpUrl,
            client: OkHttpClient,
        ): GrimmoryApi =
            Retrofit
                .Builder()
                .baseUrl(baseUrl.newBuilder().addPathSegment("").build())
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(GrimmoryApi::class.java)
    }
}

/** URLs fetched outside Retrofit (downloads, covers), always through the same guarded client. */
object GrimmoryUrls {
    fun healthcheck(base: HttpUrl): HttpUrl = base.resolveSegments("api", "v1", "healthcheck")

    fun content(
        base: HttpUrl,
        bookId: Long,
    ): HttpUrl = base.resolveSegments("api", "v1", "books", bookId.toString(), "content")

    /** One comic page image ([page] is 1-based); the server labels every page image/jpeg, so callers sniff. */
    fun cbxPage(
        base: HttpUrl,
        bookId: Long,
        page: Int,
    ): HttpUrl = base.resolveSegments("api", "v1", "media", "book", bookId.toString(), "cbx", "pages", page.toString())

    /** [coverUpdatedOn] busts caches when the cover changes. */
    fun thumbnail(
        base: HttpUrl,
        bookId: Long,
        coverUpdatedOn: String?,
    ): HttpUrl =
        base
            .resolveSegments("api", "v1", "media", "book", bookId.toString(), "thumbnail")
            .newBuilder()
            .apply { if (coverUpdatedOn != null) addQueryParameter("v", coverUpdatedOn) }
            .build()

    private fun HttpUrl.resolveSegments(vararg segments: String): HttpUrl =
        newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()
}
