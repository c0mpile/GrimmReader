package com.c0mpile.grimmreader.api.grimmory

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Every DTO field must exist in the schema it mirrors, so spec drift is caught on version bumps. */
class ApiContractTest {
    private val schemas: JsonObject by lazy {
        val root = File(System.getProperty("grimm.root") ?: error("grimm.root not set"))
        val spec = Json.parseToJsonElement(File(root, "docs/openapi.json").readText()).jsonObject
        spec["components"]!!.jsonObject["schemas"]!!.jsonObject
    }

    private val mapping: List<Pair<KSerializer<*>, String>> =
        listOf(
            serializer<HealthcheckEnvelopeDto>() to "SuccessResponseHealthcheckResponse",
            serializer<HealthcheckDto>() to "HealthcheckResponse",
            serializer<PublicSettingsDto>() to "PublicAppSetting",
            serializer<LoginRequestDto>() to "UserLoginRequest",
            serializer<RefreshRequestDto>() to "RefreshTokenRequest",
            serializer<TokenDto>() to "AccessTokenDto",
            serializer<UserDto>() to "BookLoreUser",
            serializer<LibraryDto>() to "Library",
            serializer<BookPageDto>() to "AppPageResponseAppBookSummary",
            serializer<BookSummaryDto>() to "AppBookSummary",
            serializer<BookDetailDto>() to "AppBookDetail",
            serializer<BookFileDto>() to "AppBookFile",
            serializer<ProgressDto>() to "AppBookProgressResponse",
            serializer<EpubProgressDto>() to "EpubProgress",
            serializer<PageProgressDto>() to "CbxProgress",
            serializer<PageProgressDto>() to "PdfProgress",
            serializer<FileProgressDto>() to "BookFileProgress",
            serializer<UpdateProgressDto>() to "UpdateProgressRequest",
            serializer<BookmarkDto>() to "BookMark",
            serializer<CreateBookmarkDto>() to "CreateBookMarkRequest",
            serializer<ShelfDto>() to "AppShelfSummary",
            serializer<ShelfDto>() to "Shelf",
            serializer<MagicShelfDto>() to "AppMagicShelfSummary",
            serializer<ShelvesAssignmentDto>() to "ShelvesAssignmentRequest",
            serializer<ShelfCreateDto>() to "ShelfCreateRequest",
        )

    @Test fun dtoFieldsExistInTheSpec() {
        val problems =
            mapping.flatMap { (serializer, schema) ->
                val properties =
                    schemas[schema]
                        ?.jsonObject
                        ?.get("properties")
                        ?.jsonObject
                        ?.keys ?: return@flatMap listOf("missing schema $schema")
                val descriptor = serializer.descriptor
                (0 until descriptor.elementsCount).map(descriptor::getElementName).filter { it !in properties }.map { "$schema.$it" }
            }
        assertEquals(emptyList<String>(), problems)
    }
}
