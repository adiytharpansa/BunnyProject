package com.bunny.engine.lontar

/**
 * Fase 8 Langkah 7, mechanism 7: where the SABR governance's
 * outcome log lives between processes — the shelf behind
 * [com.bunny.engine.lontar.sabr.SabrGovernance]. Deliberately as
 * dumb as the [VisitorStore] seam: the governance owns the policy
 * AND the serialization (one text blob, its own format), the app
 * owns the shelf (SharedPreferences). A store that remembers
 * nothing simply means governance starts every process with a
 * clean window — the safe direction for a self-disabling gate.
 */
interface SabrGovernanceStore {
    fun load(): String?
    fun save(serialized: String)

    companion object {
        /** Remembers nothing across processes. */
        val NONE: SabrGovernanceStore = object : SabrGovernanceStore {
            override fun load(): String? = null
            override fun save(serialized: String) = Unit
        }
    }
}
