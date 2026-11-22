package io.nekohasekai.sfa.database.preference

interface OnPreferenceDataStoreChangeListener {
    fun onPreferenceDataStoreChanged(store: StorePreferenceDataStore, key: String)
}
