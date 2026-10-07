package com.c0mpile.grimmreader

/** Debug builds only: optional server prefill from the build environment (never hard-coded). */
object DevDefaults {
    const val SERVER_URL: String = BuildConfig.DEV_SERVER_URL
}
