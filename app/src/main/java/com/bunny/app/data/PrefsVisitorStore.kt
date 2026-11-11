package com.bunny.app.data

import android.content.Context
import com.bunny.engine.lontar.VisitorStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The guest visitor identity's shelf: one SharedPreferences entry.
 * The value is an anonymous session token YouTube itself issued
 * to this install — not a credential, not an account — but it is
 * the install's long-lived guest card, so it is kept app-private
 * like everything else in Bunny's stores.
 */
@Singleton
class PrefsVisitorStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : VisitorStore {

    override fun load(): String? =
        prefs().getString(KEY_VISITOR, null)?.takeIf { it.isNotEmpty() }

    override fun save(visitorData: String) {
        prefs().edit().putString(KEY_VISITOR, visitorData).apply()
    }

    override fun clear() {
        prefs().edit().remove(KEY_VISITOR).apply()
    }

    private fun prefs() =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "bunny_visitor"
        private const val KEY_VISITOR = "visitor_data"
    }
}
