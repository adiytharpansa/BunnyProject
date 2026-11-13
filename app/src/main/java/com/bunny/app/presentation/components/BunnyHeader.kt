package com.bunny.app.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bunny.app.R

private val HeaderHorizontalPadding = 12.dp

/**
 * Header bersama untuk Beranda, Live, dan Kanal: logo + "Bunny",
 * lonceng, cari, lalu tab topik. Profil sengaja tidak memakainya.
 *
 * [selectedTopic] = null berarti tab "Beranda" (tanpa filter). Tiap
 * halaman menyimpan filternya sendiri dan menyaring isinya sendiri.
 */
@Composable
fun BunnyHeader(
    topics: List<String>,
    selectedTopic: String?,
    onTopicSelect: (String?) -> Unit,
    onNotificationsClick: () -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
    notificationsUnread: Boolean = false,
) {
    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.background),
    ) {
        BunnyTopBar(
            onNotificationsClick = onNotificationsClick,
            onSearchClick = onSearchClick,
            notificationsUnread = notificationsUnread,
        )
        // Dulu: baris disembunyikan bila topik <= 1 — akibatnya chip
        // Beranda + "Siaran Langsung" ikut hilang selama umpan masih
        // memuat (topik alasan belum ada). Kini tampil bila ada
        // minimal satu topik; kosong tetap sembunyi.
        if (topics.isNotEmpty()) {
            BunnyTopicTabs(
                topics = topics,
                selected = selectedTopic,
                onSelect = onTopicSelect,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BunnyTopBar(
    onNotificationsClick: () -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
    notificationsUnread: Boolean = false,
) {
    TopAppBar(
        modifier = modifier,
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
        title = {
            // Wordmark: ikon aplikasi (kelinci) + "Bunny" tebal rapat.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.ic_bunny_logo),
                    contentDescription = null,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.home_title),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.8).sp,
                )
            }
        },
        actions = {
            IconButton(onClick = onNotificationsClick) {
                Box {
                    Icon(
                        Icons.Outlined.Notifications,
                        contentDescription = stringResource(R.string.notif_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Fase 7-C: the unread dot — shown only when the
                    // badge's local check found a newest upload the
                    // user has not seen; no dot is ever faked.
                    if (notificationsUnread) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(9.dp)
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
            IconButton(onClick = onSearchClick) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = stringResource(R.string.search_open),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(4.dp))
        },
    )
}

/**
 * Tab topik di bawah app bar (Beranda / Podcast / Berita ...). Dipindahkan
 * dari HomeScreen (`HomeTabs`) apa adanya.
 */
@Composable
fun BunnyTopicTabs(
    topics: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val all = listOf<String?>(null) + topics
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = HeaderHorizontalPadding, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(all, key = { it ?: "__all__" }) { topic ->
            val isSelected = topic == selected
            val label = topic?.removePrefix("Topik: ")
                ?: stringResource(R.string.nav_home)
            val container by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surface
                },
                animationSpec = tween(180),
                label = "tabContainer",
            )
            val content by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(180),
                label = "tabContent",
            )
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(50))
                    .background(container)
                    .selectable(
                        selected = isSelected,
                        role = Role.Tab,
                        onClick = { onSelect(topic) },
                    )
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = content,
                    maxLines = 1,
                )
            }
        }
    }
}
