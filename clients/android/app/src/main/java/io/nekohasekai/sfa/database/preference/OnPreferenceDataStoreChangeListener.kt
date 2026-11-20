package io.nekohasekai.sfa.database.preference

interface OnPreferenceDataStoreChangeListener {
    fun onPreferenceDataStoreChanged(store: RoomPreferenceDataStore, key: String)
}
