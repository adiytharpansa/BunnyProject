package com.bunny.core.model

/** One page of results. [nextPage] is an opaque backend token; null = no more pages. */
data class Page<T>(
    val items: List<T>,
    val nextPage: String?,
)
