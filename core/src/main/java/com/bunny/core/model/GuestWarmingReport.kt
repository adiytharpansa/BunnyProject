package com.bunny.core.model

/**
 * Selera Tamu (Langkah 2): the warming probe's verdict — whether
 * the persistent guest visitor identity has started to be
 * answered by the two surfaces that only respond once YouTube
 * has registered viewing under that identity:
 *
 * - the guest home grid (FEwhat_to_watch), empty for a brand-new
 *   visitor by YouTube's own documented behaviour;
 * - the guest history browse (FEhistory), the recording oracle:
 *   unlike an HTTP 204 on a tracking ping, an answer here means
 *   the watches were actually written down server-side.
 */
data class GuestWarmingReport(
    /** Items the guest home browse returned (0 = still cold). */
    val homeItemCount: Int,
    /** Items the guest history browse returned; null = it refused. */
    val historyItemCount: Int?,
    /** HTTP status behind a history refusal, when there was one. */
    val historyHttpCode: Int?,
    val checkedAtEpochMs: Long,
)
