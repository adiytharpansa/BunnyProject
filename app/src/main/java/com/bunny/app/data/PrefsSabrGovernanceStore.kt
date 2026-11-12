package com.bunny.app.data

import android.content.Context
import com.bunny.engine.lontar.SabrGovernanceStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The SABR governance log's shelf (Fase 8 Langkah 7, mechanism 7):
 * one SharedPreferences entry holding the governance's own
 * serialized outcome log (its format is the engine component's,
 * this class only keeps the paper). The log holds timestamps,
 * verdicts and coarse network classes — no video ids, no user
 * data — but it is this install's road history, so it stays
 * app-private like Bunny's other stores. A lost or unreadable
 * shelf means a clean governance window: the safe direction for
 * a self-disabling gate.
 */
@Singleton
class PrefsSabrGovernanceStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : SabrGovernanceStore {

    override fun load(): String? =
        prefs().getString(KEY_OUTCOMES, null)?.takeIf { it.isNotEmpty() }

    override fun save(serialized: String) {
        prefs().edit().putString(KEY_OUTCOMES, serialized).apply()
    }

    private fun prefs() =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "bunny_sabr_governance"
        private const val KEY_OUTCOMES = "outcomes"
    }
}
