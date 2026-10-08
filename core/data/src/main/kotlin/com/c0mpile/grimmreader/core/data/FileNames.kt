package com.c0mpile.grimmreader.core.data

private const val MAX_NAME = 120

/** [name] as a file or folder name that file systems accept (FAT/exFAT cards included), or [fallback] when nothing is left. */
internal fun safeName(
    name: String,
    fallback: String,
): String =
    name
        .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
        .take(MAX_NAME)
        // Trimmed again after the cut: names ending in a space or dot are rejected by FAT/exFAT cards.
        .trim()
        .trimEnd('.', ' ')
        .ifEmpty { fallback }
