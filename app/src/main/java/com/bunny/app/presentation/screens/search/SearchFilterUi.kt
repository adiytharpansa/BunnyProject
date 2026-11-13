package com.bunny.app.presentation.screens.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bunny.app.R
import com.bunny.core.model.DurationRange
import com.bunny.core.model.SearchFilter
import com.bunny.core.model.SearchSort
import com.bunny.core.model.UploadDate

// ------------------------------------------------------------ baris chip

/** Baris chip cepat di bawah kolom cari + tombol Filter lengkap. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchFilterBar(
    filter: SearchFilter,
    onFilterChange: (SearchFilter) -> Unit,
    onOpenSheet: () -> Unit,
) {
    val count = filter.activeCount
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = count > 0,
            onClick = onOpenSheet,
            label = {
                val title = stringResource(R.string.filter_title)
                Text(if (count > 0) "$title · $count" else title)
            },
            leadingIcon = {
                Icon(
                    Icons.Filled.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            },
        )
        QuickChip(R.string.filter_all, filter.isDefault) {
            onFilterChange(SearchFilter.DEFAULT)
        }
        QuickChip(R.string.filter_today, filter.uploadDate == UploadDate.TODAY) {
            onFilterChange(
                filter.copy(
                    uploadDate = if (filter.uploadDate == UploadDate.TODAY) {
                        UploadDate.ANY
                    } else {
                        UploadDate.TODAY
                    },
                ),
            )
        }
        QuickChip(R.string.filter_week, filter.uploadDate == UploadDate.WEEK) {
            onFilterChange(
                filter.copy(
                    uploadDate = if (filter.uploadDate == UploadDate.WEEK) {
                        UploadDate.ANY
                    } else {
                        UploadDate.WEEK
                    },
                ),
            )
        }
        QuickChip(R.string.filter_live, filter.live) {
            onFilterChange(filter.copy(live = !filter.live))
        }
        QuickChip(R.string.filter_sort_date_short, filter.sort == SearchSort.DATE) {
            onFilterChange(
                filter.copy(
                    sort = if (filter.sort == SearchSort.DATE) {
                        SearchSort.RELEVANCE
                    } else {
                        SearchSort.DATE
                    },
                ),
            )
        }
        QuickChip(R.string.filter_sort_views_short, filter.sort == SearchSort.VIEWS) {
            onFilterChange(
                filter.copy(
                    sort = if (filter.sort == SearchSort.VIEWS) {
                        SearchSort.RELEVANCE
                    } else {
                        SearchSort.VIEWS
                    },
                ),
            )
        }
        QuickChip(R.string.filter_duration_short_chip, filter.duration == DurationRange.SHORT) {
            onFilterChange(
                filter.copy(
                    duration = if (filter.duration == DurationRange.SHORT) {
                        DurationRange.ANY
                    } else {
                        DurationRange.SHORT
                    },
                ),
            )
        }
        QuickChip(R.string.filter_duration_long_chip, filter.duration == DurationRange.LONG) {
            onFilterChange(
                filter.copy(
                    duration = if (filter.duration == DurationRange.LONG) {
                        DurationRange.ANY
                    } else {
                        DurationRange.LONG
                    },
                ),
            )
        }
        QuickChip(R.string.filter_hd, filter.hd) {
            onFilterChange(filter.copy(hd = !filter.hd))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickChip(labelRes: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(stringResource(labelRes)) },
    )
}

// ------------------------------------------------------------- sheet lengkap

/** Sheet filter lengkap; perubahan baru berlaku saat "Terapkan". */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    ExperimentalFoundationApi::class,
)
@Composable
internal fun SearchFilterSheet(
    initial: SearchFilter,
    onApply: (SearchFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.filter_title),
                style = MaterialTheme.typography.titleLarge,
            )

            SheetSection(R.string.filter_sort) {
                SheetChip(R.string.filter_sort_relevance, draft.sort == SearchSort.RELEVANCE) {
                    draft = draft.copy(sort = SearchSort.RELEVANCE)
                }
                SheetChip(R.string.filter_sort_rating, draft.sort == SearchSort.RATING) {
                    draft = draft.copy(sort = SearchSort.RATING)
                }
                SheetChip(R.string.filter_sort_date, draft.sort == SearchSort.DATE) {
                    draft = draft.copy(sort = SearchSort.DATE)
                }
                SheetChip(R.string.filter_sort_views, draft.sort == SearchSort.VIEWS) {
                    draft = draft.copy(sort = SearchSort.VIEWS)
                }
            }

            SheetSection(R.string.filter_upload_date) {
                SheetChip(R.string.filter_any, draft.uploadDate == UploadDate.ANY) {
                    draft = draft.copy(uploadDate = UploadDate.ANY)
                }
                SheetChip(R.string.filter_hour, draft.uploadDate == UploadDate.HOUR) {
                    draft = draft.copy(uploadDate = UploadDate.HOUR)
                }
                SheetChip(R.string.filter_today, draft.uploadDate == UploadDate.TODAY) {
                    draft = draft.copy(uploadDate = UploadDate.TODAY)
                }
                SheetChip(R.string.filter_week, draft.uploadDate == UploadDate.WEEK) {
                    draft = draft.copy(uploadDate = UploadDate.WEEK)
                }
                SheetChip(R.string.filter_month, draft.uploadDate == UploadDate.MONTH) {
                    draft = draft.copy(uploadDate = UploadDate.MONTH)
                }
                SheetChip(R.string.filter_year, draft.uploadDate == UploadDate.YEAR) {
                    draft = draft.copy(uploadDate = UploadDate.YEAR)
                }
            }

            SheetSection(R.string.filter_duration) {
                SheetChip(R.string.filter_any, draft.duration == DurationRange.ANY) {
                    draft = draft.copy(duration = DurationRange.ANY)
                }
                SheetChip(R.string.filter_duration_short, draft.duration == DurationRange.SHORT) {
                    draft = draft.copy(duration = DurationRange.SHORT)
                }
                SheetChip(R.string.filter_duration_medium, draft.duration == DurationRange.MEDIUM) {
                    draft = draft.copy(duration = DurationRange.MEDIUM)
                }
                SheetChip(R.string.filter_duration_long, draft.duration == DurationRange.LONG) {
                    draft = draft.copy(duration = DurationRange.LONG)
                }
            }

            SheetSection(R.string.filter_features) {
                SheetChip(R.string.filter_live, draft.live) {
                    draft = draft.copy(live = !draft.live)
                }
                SheetChip(R.string.filter_hd, draft.hd) {
                    draft = draft.copy(hd = !draft.hd)
                }
                SheetChip(R.string.filter_4k, draft.fourK) {
                    draft = draft.copy(fourK = !draft.fourK)
                }
                SheetChip(R.string.filter_subtitles, draft.subtitles) {
                    draft = draft.copy(subtitles = !draft.subtitles)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { draft = SearchFilter.DEFAULT }) {
                    Text(stringResource(R.string.filter_reset))
                }
                Button(onClick = { onApply(draft) }) {
                    Text(stringResource(R.string.filter_apply))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SheetSection(titleRes: Int, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { chips() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetChip(labelRes: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(stringResource(labelRes)) },
        leadingIcon = if (selected) {
            {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            }
        } else {
            null
        },
    )
}
