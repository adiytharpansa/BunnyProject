package com.bunny.core.model

/**
 * Satu Ketuk, Langkah 1 — the token-evidence record. After a one-tap
 * authorization, the fresh OAuth access token is tried against
 * YouTube's account door (`account/accounts_list`) under several
 * InnerTube client contexts, because research showed token acceptance
 * is bound to the (token × context) pairing and the pairing for an
 * Android-client token was unproven. One verdict per context; the
 * first accepted context becomes the home of the personal data.
 * The token itself is never part of this record.
 */
data class BearerContextVerdict(
    /** InnerTube client context name, e.g. "TVHTML5", "WEB", "ANDROID". */
    val contextName: String,
    /** HTTP status of the probe call, null when the network itself failed. */
    val httpStatus: Int?,
    /** True only when the door answered 200 AND named a real account. */
    val accepted: Boolean,
    val accountName: String? = null,
    val accountHandle: String? = null,
    val accountEmail: String? = null,
    val avatarUrl: String? = null,
)
