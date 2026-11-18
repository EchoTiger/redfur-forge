package com.fissal.forge.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fissal.forge.auth.ConnectedServiceSummary
import com.fissal.forge.auth.ConnectionHealthStatus
import com.fissal.forge.auth.ServiceCatalog
import com.fissal.forge.auth.ServiceDescriptor
import com.fissal.forge.ui.theme.AmberActive
import com.fissal.forge.ui.theme.ArcRelayCyan
import com.fissal.forge.ui.theme.CoralError
import com.fissal.forge.ui.theme.CrispWhite
import com.fissal.forge.ui.theme.EmeraldSuccess
import com.fissal.forge.ui.theme.ForgeReadoutStyle
import com.fissal.forge.ui.theme.MutedSteel
import com.fissal.forge.ui.theme.RelayAbyss
import com.fissal.forge.ui.theme.RelayBevelOutline
import com.fissal.forge.ui.theme.RelayCard
import com.fissal.forge.ui.theme.SecundaSilver
import com.fissal.forge.ui.theme.SlateCard
import com.fissal.forge.ui.theme.SlateOutline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class ServiceDropdownOption(
    val service: ServiceDescriptor,
    val title: String,
    val hostHint: String,
    val isConnected: Boolean
)

/**
 * Relay-domain Settings card managing encrypted site connections, real-time platform/domain
 * search, WebView session login, `cookies.txt` import, health verification, and removal.
 */
@Composable
fun ConnectionsSettingsCard(
    connections: List<ConnectedServiceSummary>,
    checkingServiceId: String?,
    onConnectServiceWebView: (ServiceDescriptor) -> Unit,
    onImportCookiesTxt: (ServiceDescriptor) -> Unit,
    onCheckConnection: (String) -> Unit,
    onReauthenticate: (ServiceDescriptor) -> Unit,
    onRemoveConnection: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var searchQuery by remember { mutableStateOf("") }
    var isDropdownExpanded by remember { mutableStateOf(false) }

    val connectedIds = remember(connections) {
        connections.map { it.serviceId.lowercase(Locale.US) }.toSet()
    }

    val dropdownOptions = remember(searchQuery, connectedIds) {
        buildDropdownOptions(
            query = searchQuery,
            connectedIds = connectedIds
        )
    }

    WorkshopDomainCard(
        domain = EngineDomain.RELAY,
        isActive = checkingServiceId != null,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(14.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Card Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Connected Accounts & Cookies",
                        style = MaterialTheme.typography.titleMedium,
                        color = CrispWhite
                    )
                    Text(
                        text = "Sign in or import cookies.txt to unlock private, age-gated, or members-only links.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSteel,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = ArcRelayCyan,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "Encrypted locally in Android Keystore",
                            style = MaterialTheme.typography.labelSmall,
                            color = SecundaSilver
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                DomainRolePill(
                    domain = EngineDomain.RELAY,
                    label = "Local Vault"
                )
            }

            // Real-time Searchable Dropdown Input (in-layout anchored below, no focus-stealing Popup)
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { updated ->
                    searchQuery = updated
                    isDropdownExpanded = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            isDropdownExpanded = true
                        }
                    },
                singleLine = true,
                placeholder = {
                    Text(
                        text = "Search platform or enter domain (YouTube, Patreon, Facebook...)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedSteel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = ArcRelayCyan,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    searchQuery = ""
                                    isDropdownExpanded = true
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Clear,
                                    contentDescription = "Clear platform search",
                                    tint = MutedSteel,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                val nextExpanded = !isDropdownExpanded
                                isDropdownExpanded = nextExpanded
                                if (!nextExpanded) {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (isDropdownExpanded) {
                                    Icons.Rounded.ExpandLess
                                } else {
                                    Icons.Rounded.ExpandMore
                                },
                                contentDescription = if (isDropdownExpanded) {
                                    "Hide platform list"
                                } else {
                                    "Show platform list"
                                },
                                tint = SecundaSilver
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ArcRelayCyan,
                    unfocusedBorderColor = RelayBevelOutline,
                    focusedTextColor = CrispWhite,
                    unfocusedTextColor = CrispWhite,
                    cursorColor = ArcRelayCyan,
                    focusedContainerColor = RelayAbyss,
                    unfocusedContainerColor = RelayAbyss
                ),
                shape = RoundedCornerShape(12.dp)
            )

            // Anchored In-Layout Dropdown Card
            AnimatedVisibility(
                visible = isDropdownExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = RelayAbyss,
                    border = BorderStroke(1.dp, ArcRelayCyan.copy(alpha = 0.42f))
                ) {
                    if (dropdownOptions.isEmpty()) {
                        Text(
                            text = "No built-in platform matched. Enter a full domain (like members.example.com) to connect any custom site.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedSteel,
                            modifier = Modifier.padding(14.dp)
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 290.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            dropdownOptions.forEachIndexed { index, option ->
                                ServiceDropdownRow(
                                    option = option,
                                    onSelectWebView = {
                                        isDropdownExpanded = false
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                        onConnectServiceWebView(option.service)
                                    },
                                    onSelectImportCookies = {
                                        isDropdownExpanded = false
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                        onImportCookiesTxt(option.service)
                                    }
                                )
                                if (index < dropdownOptions.lastIndex) {
                                    HorizontalDivider(
                                        color = RelayBevelOutline.copy(alpha = 0.45f),
                                        thickness = 1.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Connected Services List
            if (connections.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = SlateCard,
                    border = BorderStroke(1.dp, SlateOutline)
                ) {
                    Text(
                        text = "No sites connected yet. Search above to connect YouTube, Facebook, Patreon, or any custom domain for private/member downloads.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedSteel,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    connections.forEach { summary ->
                        ConnectedServiceItemCard(
                            summary = summary,
                            isChecking = checkingServiceId == summary.serviceId,
                            onCheckConnection = { onCheckConnection(summary.serviceId) },
                            onReauthenticate = { onReauthenticate(summary.service) },
                            onRemoveConnection = { onRemoveConnection(summary.serviceId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServiceDropdownRow(
    option: ServiceDropdownOption,
    onSelectWebView: () -> Unit,
    onSelectImportCookies: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelectWebView)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ServiceBrandIcon(
            service = option.service,
            size = 32.dp
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = option.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = CrispWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (option.isConnected) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = EmeraldSuccess.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, EmeraldSuccess.copy(alpha = 0.50f))
                    ) {
                        Text(
                            text = "Connected",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = EmeraldSuccess,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (option.hostHint.isNotBlank()) {
                Text(
                    text = option.hostHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = SecundaSilver,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        IconButton(
            onClick = onSelectImportCookies,
            modifier = Modifier.size(34.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.FolderOpen,
                contentDescription = "Import cookies.txt for ${option.service.displayName}",
                tint = ArcRelayCyan,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectedServiceItemCard(
    summary: ConnectedServiceSummary,
    isChecking: Boolean,
    onCheckConnection: () -> Unit,
    onReauthenticate: () -> Unit,
    onRemoveConnection: () -> Unit
) {
    val isHealthy = summary.status == ConnectionHealthStatus.CONNECTED
    val statusColor = if (isHealthy) EmeraldSuccess else AmberActive
    val borderColor = if (isHealthy) RelayBevelOutline else AmberActive.copy(alpha = 0.55f)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = RelayCard,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ServiceBrandIcon(
                    service = summary.service,
                    size = 36.dp
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = summary.service.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = CrispWhite,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    val cookieLabel = if (summary.cookieCount == 1) {
                        "1 cookie"
                    } else {
                        "${summary.cookieCount} cookies"
                    }
                    val metaLabel = buildString {
                        if (!summary.accountBadge.isNullOrBlank()) {
                            append(summary.accountBadge)
                            append(" • ")
                        }
                        append(cookieLabel)
                    }
                    Text(
                        text = metaLabel,
                        style = ForgeReadoutStyle,
                        color = SecundaSilver,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(999.dp),
                color = statusColor.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.50f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (isHealthy) {
                            Icons.Rounded.CheckCircle
                        } else {
                            Icons.Rounded.WarningAmber
                        },
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = if (isHealthy) "Connected" else "Expired / Re-auth Needed",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        maxLines = 1
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = summary.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isHealthy) SecundaSilver else CoralError
                )

                val savedLabel = "Saved ${formatAuthTimestamp(summary.savedAtMs)}"
                val checkedLabel = summary.lastCheckedAtMs?.let {
                    " • Checked ${formatAuthTimestamp(it)}"
                }.orEmpty()
                Text(
                    text = savedLabel + checkedLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedSteel
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCheckConnection,
                    enabled = !isChecking,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, RelayBevelOutline),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 36.dp)
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = ArcRelayCyan
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            tint = ArcRelayCyan,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (isChecking) "Verifying..." else "Verify",
                        style = MaterialTheme.typography.labelMedium,
                        color = CrispWhite,
                        maxLines = 1
                    )
                }

                OutlinedButton(
                    onClick = onReauthenticate,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, RelayBevelOutline),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = SecundaSilver,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "Sign In Again",
                        style = MaterialTheme.typography.labelMedium,
                        color = CrispWhite,
                        maxLines = 1
                    )
                }

                OutlinedButton(
                    onClick = onRemoveConnection,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, CoralError.copy(alpha = 0.45f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = null,
                        tint = CoralError,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "Disconnect",
                        style = MaterialTheme.typography.labelMedium,
                        color = CoralError,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

internal fun buildDropdownOptions(
    query: String,
    connectedIds: Set<String>
): List<ServiceDropdownOption> {
    val trimmed = query.trim()
    val filtered = ServiceCatalog.filterServices(trimmed)

    val customCandidate = if (trimmed.contains('.') && ServiceCatalog.matchUrl(trimmed) == null) {
        ServiceCatalog.forCustomDomain(trimmed)
    } else {
        null
    }

    val result = mutableListOf<ServiceDropdownOption>()

    if (customCandidate != null &&
        filtered.none { it.id.equals(customCandidate.id, ignoreCase = true) }
    ) {
        result += ServiceDropdownOption(
            service = customCandidate,
            title = "Connect custom domain: ${customCandidate.displayName}",
            hostHint = "Custom site login & cookies",
            isConnected = customCandidate.id.lowercase(Locale.US) in connectedIds
        )
    }

    filtered.forEach { service ->
        val hint = if (service.isCustomDomainTemplate) {
            "Enter any website URL or import cookies.txt"
        } else {
            service.hostMatchers.firstOrNull().orEmpty()
        }
        result += ServiceDropdownOption(
            service = service,
            title = service.displayName,
            hostHint = hint,
            isConnected = service.id.lowercase(Locale.US) in connectedIds
        )
    }

    return result
}

private fun formatAuthTimestamp(epochMs: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(epochMs))
