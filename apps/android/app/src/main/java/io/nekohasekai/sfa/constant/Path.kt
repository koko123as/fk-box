package io.nekohasekai.sfa.constant

object Path {
    /** The client store (crates/client-core), under filesDir. */
    /** The client store directory (profiles.json, settings.json; client.db of earlier builds is imported once). */
    const val CLIENT_STORE_DIR = "fkbox"

    // Read once by the client store's import; left in place for older releases.
    const val SETTINGS_DATABASE_PATH = "settings.db"
    const val PROFILES_DATABASE_PATH = "profiles.db"
    const val UI_PREFERENCES_NAME = "flclash_ui"
    const val WEBDAV_PREFERENCES_NAME = "bbox_webdav"
}
