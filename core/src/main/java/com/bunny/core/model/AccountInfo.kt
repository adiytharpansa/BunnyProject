package com.bunny.core.model

/**
 * The signed-in Google account as YouTube itself reports it (Fase 5).
 * Only ever populated from the account's own authenticated response —
 * never guessed locally.
 */
data class AccountInfo(
    val name: String,
    val avatarUrl: String? = null,
)
