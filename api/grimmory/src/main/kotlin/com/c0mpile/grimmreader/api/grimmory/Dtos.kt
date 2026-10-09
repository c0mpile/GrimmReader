package com.c0mpile.grimmreader.api.grimmory

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

// Hand-written subsets of the Grimmory v3.5.0 schemas (docs/openapi.json). Field names must exist in the
// referenced schema; ApiContractTest checks that. Unknown fields are ignored.

/** `SuccessResponseHealthcheckResponse` */
@Serializable
data class HealthcheckEnvelopeDto(
    val status: Int? = null,
    val message: String? = null,
    val data: HealthcheckDto? = null,
)

/** `HealthcheckResponse` */
@Serializable
data class HealthcheckDto(
    val status: String? = null,
    val version: String? = null,
)

/** `PublicAppSetting` */
@Serializable
data class PublicSettingsDto(
    val oidcEnabled: Boolean = false,
    val remoteAuthEnabled: Boolean = false,
    val oidcForceOnlyMode: Boolean = false,
)

/** `UserLoginRequest` */
@Serializable
data class LoginRequestDto(
    val username: String,
    val password: String,
)

/** `RefreshTokenRequest` */
@Serializable
data class RefreshRequestDto(
    val refreshToken: String,
)

/** `AccessTokenDto`; [expires] is the access token lifetime in seconds. */
@Serializable
data class TokenDto(
    val accessToken: String,
    val refreshToken: String,
    val expires: Long? = null,
    val isDefaultPassword: Boolean = false,
)

/** `BookLoreUser` (subset). [permissions] is kept as raw JSON so new flags never break parsing. */
@Serializable
data class UserDto(
    val id: Long,
    val username: String? = null,
    val permissions: JsonObject? = null,
    val defaultPassword: Boolean = false,
) {
    fun permissionFlags(): Set<String> =
        permissions.orEmpty().filterValues { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() == true }.keys
}

/** `Library` (subset; server `paths` are deliberately not modelled). */
@Serializable
data class LibraryDto(
    val id: Long,
    val name: String,
    val icon: String? = null,
    val allowedFormats: List<String> = emptyList(),
)

/** `AppPageResponseAppBookSummary` */
@Serializable
data class BookPageDto(
    val content: List<BookSummaryDto> = emptyList(),
    val page: Int = 0,
    val size: Int = 0,
    val totalElements: Long = 0,
    val totalPages: Int = 0,
    val hasNext: Boolean = false,
)

/** `AppBookSummary` (subset) */
@Serializable
data class BookSummaryDto(
    val id: Long,
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val readStatus: String? = null,
    val seriesName: String? = null,
    val seriesNumber: Float? = null,
    val libraryId: Long? = null,
    val addedOn: String? = null,
    val lastReadTime: String? = null,
    val readProgress: Float? = null,
    val primaryFileId: Long? = null,
    val primaryFileType: String? = null,
    val primaryFileName: String? = null,
    val fileSizeKb: Long? = null,
    val coverUpdatedOn: String? = null,
    val pageCount: Int? = null,
)

/** `AppBookDetail` (subset) */
@Serializable
data class BookDetailDto(
    val id: Long,
    val title: String? = null,
    val subtitle: String? = null,
    val authors: List<String> = emptyList(),
    val description: String? = null,
    val readStatus: String? = null,
    val seriesName: String? = null,
    val seriesNumber: Float? = null,
    val libraryId: Long? = null,
    val publisher: String? = null,
    val publishedDate: String? = null,
    val pageCount: Int? = null,
    val language: String? = null,
    val readProgress: Float? = null,
    val primaryFileType: String? = null,
    val files: List<BookFileDto> = emptyList(),
    val coverUpdatedOn: String? = null,
    val epubProgress: EpubProgressDto? = null,
    val cbxProgress: PageProgressDto? = null,
    val pdfProgress: PageProgressDto? = null,
)

/** `AppBookFile` (subset) */
@Serializable
data class BookFileDto(
    val id: Long,
    val bookId: Long? = null,
    val fileName: String? = null,
    val bookType: String? = null,
    val fileSizeKb: Long? = null,
    val extension: String? = null,
    val primary: Boolean = false,
)

/** `AppBookProgressResponse` (no `fileProgress`: the server never returns it, Spike b). */
@Serializable
data class ProgressDto(
    val readProgress: Float? = null,
    val readStatus: String? = null,
    val lastReadTime: String? = null,
    val epubProgress: EpubProgressDto? = null,
    val pdfProgress: PageProgressDto? = null,
    val cbxProgress: PageProgressDto? = null,
)

/** `EpubProgress` */
@Serializable
data class EpubProgressDto(
    val cfi: String? = null,
    val href: String? = null,
    val percentage: Float? = null,
    val contentSourceProgressPercent: Float? = null,
)

/** `CbxProgress` / `PdfProgress`: 1-based page. */
@Serializable
data class PageProgressDto(
    val page: Int? = null,
    val percentage: Float? = null,
)

/** `BookFileProgress` */
@Serializable
data class FileProgressDto(
    val bookFileId: Long,
    val positionData: String,
    val positionHref: String? = null,
    val progressPercent: Float,
    val contentSourceProgressPercent: Float? = null,
)

/** `UpdateProgressRequest`: the web sends the per-format object and `fileProgress` together. */
@Serializable
data class UpdateProgressDto(
    val fileProgress: FileProgressDto? = null,
    val epubProgress: EpubProgressDto? = null,
    val pdfProgress: PageProgressDto? = null,
    val cbxProgress: PageProgressDto? = null,
)

/**
 * `BookMark`. Ebooks store a CFI in [cfi]; the comic reader stores the 1-based page as text in [cfi]; the PDF
 * reader uses [pageNumber]. Audiobook bookmarks ([positionMs]) are ignored by the app.
 */
@Serializable
data class BookmarkDto(
    val id: Long,
    val bookId: Long? = null,
    val cfi: String? = null,
    val pageNumber: Int? = null,
    val positionMs: Long? = null,
    val title: String? = null,
    val createdAt: String? = null,
)

/** `CreateBookMarkRequest`. [pdfBookmark] makes the server reject a second bookmark on the same page (409). */
@Serializable
data class CreateBookmarkDto(
    val bookId: Long,
    val cfi: String? = null,
    val pageNumber: Int? = null,
    val title: String? = null,
    val pdfBookmark: Boolean? = null,
)

/** `AppShelfSummary` (also the subset of `Shelf` returned on create). The server creates "Favorites" (icon "heart") per user. */
@Serializable
data class ShelfDto(
    val id: Long,
    val name: String,
    val icon: String? = null,
    val bookCount: Int? = null,
    val publicShelf: Boolean? = null,
)

/** `AppMagicShelfSummary`: a rule-based shelf; membership comes from the server. */
@Serializable
data class MagicShelfDto(
    val id: Long,
    val name: String,
    val icon: String? = null,
    val publicShelf: Boolean? = null,
)

/** `ShelvesAssignmentRequest`: only the user's own shelves are accepted. Both lists are always sent. */
@Serializable
data class ShelvesAssignmentDto(
    val bookIds: List<Long>,
    val shelvesToAssign: List<Long>,
    val shelvesToUnassign: List<Long>,
)

/** `ShelfCreateRequest` */
@Serializable
data class ShelfCreateDto(
    val name: String,
    val icon: String? = null,
    val publicShelf: Boolean = false,
)

/** `AppNotebookBookSummary`: a book with highlights, notes or bookmarks. */
@Serializable
data class NotebookBookDto(
    val bookId: Long,
    val bookTitle: String? = null,
    val noteCount: Int = 0,
    val authors: List<String> = emptyList(),
    val coverUpdatedOn: String? = null,
)

/** `AppNotebookEntry`: [type] is HIGHLIGHT, NOTE or BOOKMARK. */
@Serializable
data class NotebookEntryDto(
    val id: Long,
    val type: String? = null,
    val bookId: Long? = null,
    val text: String? = null,
    val note: String? = null,
    val color: String? = null,
    val style: String? = null,
    val chapterTitle: String? = null,
    val createdAt: String? = null,
)

/** `AppPageResponseAppNotebookBookSummary` */
@Serializable
data class NotebookBookPageDto(
    val content: List<NotebookBookDto> = emptyList(),
    val hasNext: Boolean = false,
)

/** `AppPageResponseAppNotebookEntry` */
@Serializable
data class NotebookEntryPageDto(
    val content: List<NotebookEntryDto> = emptyList(),
    val hasNext: Boolean = false,
)
