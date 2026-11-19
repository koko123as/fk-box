package io.nekohasekai.sfa.constant

object Path {
    const val SETTINGS_DATABASE_PATH = "settings.db"
    const val PROFILES_DATABASE_PATH = "profiles.db"

    /** The Go-owned profile store (libbox/core_profile_store.go), beside profiles.db. */
    const val PROFILES_STORE_PATH = "profiles-store.json"
}
