package com.enderthor.kSafe.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.enderthor.kSafe.R
import com.enderthor.kSafe.activity.MainViewModel
import androidx.core.net.toUri

@Composable
fun TabLayout(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Re-check permission every time the screen is resumed (user may have just granted it)
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val tabs = listOf(
        stringResource(R.string.tab_provider),
        stringResource(R.string.tab_settings),
        stringResource(R.string.tab_actions),
        stringResource(R.string.tab_health),
        stringResource(R.string.tab_fueling),
    )
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Overlay permission warning banner ─────────────────────────────────
        if (!hasOverlayPermission) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFF3CD)   // amber / warning yellow
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.overlay_permission_title),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF856404)
                    )
                    Text(
                        text = stringResource(R.string.overlay_permission_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF664D03)
                    )
                    Button(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    "package:${context.packageName}".toUri()
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF856404)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.overlay_permission_button),
                            fontSize = 11.sp,
                            color = Color.White
                        )
                    }
                }
            }
        }

        ScrollableTabRowWithIndicators(
            selectedTabIndex = selectedTab,
            tabs = tabs,
            onTabSelected = { selectedTab = it },
        )

        when (selectedTab) {
            0 -> ProviderScreen(vm)
            1 -> SettingsScreen(vm)
            2 -> ActionsScreen(vm)
            3 -> HealthScreen(vm)
            4 -> FuelingScreen(vm)
        }
    }
}

/**
 * Horizontal scrolling tab strip. The Karoo screen is too narrow to fit five tabs at the
 * default `TabRow` font size — names get clipped. `ScrollableTabRow` lays the tabs at
 * their natural width and allows horizontal swiping to reach the others. Edge-fade gradients
 * plus subtle arrow icons make it visually obvious that more tabs exist beyond the visible
 * area, since users new to the app may not realise the strip is scrollable.
 *
 * Pattern adapted from karoo-dual `ScrollableTabRowWithIndicators`.
 */
@Composable
private fun ScrollableTabRowWithIndicators(
    selectedTabIndex: Int,
    tabs: List<String>,
    onTabSelected: (Int) -> Unit,
) {
    Box {
        ScrollableTabRow(
            selectedTabIndex = selectedTabIndex,
            modifier = Modifier.fillMaxWidth(),
            edgePadding = 8.dp,
            indicator = { tabPositions ->
                if (tabPositions.isNotEmpty()) {
                    TabRowDefaults.PrimaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                        width = tabPositions[selectedTabIndex].width,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            divider = {
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                )
            },
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { onTabSelected(index) },
                    text = {
                        Text(
                            text = title,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            color = if (selectedTabIndex == index)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface,
                        )
                    },
                )
            }
        }

        // Left edge fade — hints that there is content to the left when scrolled.
        Box(
            modifier = Modifier
                .height(48.dp)
                .width(24.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface,
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        // Right edge fade — hints that there is content beyond the visible area.
        Box(
            modifier = Modifier
                .height(48.dp)
                .width(24.dp)
                .align(Alignment.TopEnd)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.surface,
                        ),
                    ),
                ),
        )

        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 4.dp)
                .width(16.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )

        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp)
                .width(16.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}
