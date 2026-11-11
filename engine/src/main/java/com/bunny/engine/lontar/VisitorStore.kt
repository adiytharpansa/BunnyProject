package com.bunny.engine.lontar

/**
 * Selera Tamu (Langkah 0): where the guest visitor identity lives
 * between processes. YouTube's own guest personalization keys on
 * ONE long-lived visitor cookie (VISITOR_INFO1_LIVE /
 * __Secure-YNID, six months, per Google's cookie documentation);
 * a client whose visitor is reborn every process is, to the
 * server, a brand-new user forever — its guest home browse is
 * empty for exactly the reason YouTube Help names ("a new user
 * before watching any videos"). The store is deliberately dumb:
 * the engine owns the policy (reuse, suspect-refresh), the app
 * owns the shelf.
 */
interface VisitorStore {
    fun load(): String?
    fun save(visitorData: String)
    fun clear()

    companion object {
        /** Remembers nothing: the pre-Selera-Tamu behaviour. */
        val NONE: VisitorStore = object : VisitorStore {
            override fun load(): String? = null
            override fun save(visitorData: String) = Unit
            override fun clear() = Unit
        }
    }
}
