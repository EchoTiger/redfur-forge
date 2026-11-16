package com.fissal.forge.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fissal.forge.data.local.FormatPresetEntity
import com.fissal.forge.domain.model.MediaKind
import com.fissal.forge.domain.model.PipelineSpec
import com.fissal.forge.ui.theme.AnvilGunmetal
import com.fissal.forge.ui.theme.CoralError
import com.fissal.forge.ui.theme.CrispWhite
import com.fissal.forge.ui.theme.EmberFlame
import com.fissal.forge.ui.theme.ForgeBevelOutline
import com.fissal.forge.ui.theme.ForgeGold
import com.fissal.forge.ui.theme.FurnaceCard
import com.fissal.forge.ui.theme.MoltenEmber
import com.fissal.forge.ui.theme.MutedSteel
import com.fissal.forge.ui.theme.SecundaSilver
import com.fissal.forge.ui.theme.SlateCard
import com.fissal.forge.ui.theme.SlateOutline
import java.text.BreakIterator

private val CURATED_PRESET_EMOJIS = listOf(
    "🔥", "⚡", "🎬", "💎", "📱", "🎯",
    "🪶", "📸", "🎵", "🚀", "🌙", "✨"
)

private fun friendlyMediaKindLabel(mediaKind: MediaKind): String = when (mediaKind) {
    MediaKind.VIDEO -> "video"
    MediaKind.AUDIO -> "audio"
    MediaKind.IMAGE -> "image"
    MediaKind.CAMERA_RAW -> "Camera RAW"
    MediaKind.REMOTE_URL -> "link"
    MediaKind.UNKNOWN -> "media"
}

/**
 * Extracts the first Unicode grapheme cluster using [BreakIterator], falling back to a full
 * UTF-16 codepoint so surrogate pairs are never split.
 */
private fun extractFirstGrapheme(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(trimmed)
    val start = iterator.first()
    val end = iterator.next()
    if (start != BreakIterator.DONE && end != BreakIterator.DONE && end > start) {
        return trimmed.substring(start, end)
    }
    val firstCodePoint = trimmed.codePointAt(0)
    val charCount = Character.charCount(firstCodePoint)
    return trimmed.substring(0, charCount.coerceAtMost(trimmed.length))
}

private fun isPlausibleEmojiGrapheme(grapheme: String): Boolean {
    if (grapheme.isEmpty()) return false
    var hasEmojiChar = false
    var i = 0
    while (i < grapheme.length) {
        val cp = grapheme.codePointAt(i)
        i += Character.charCount(cp)
        val type = Character.getType(cp)
        val isPictographicOrSymbol = cp > 0xFF && (
            type == Character.OTHER_SYMBOL.toInt() ||
                cp in 0x1F300..0x1FAFF ||
                cp in 0x2600..0x27BF
            )
        if (isPictographicOrSymbol) {
            hasEmojiChar = true
            break
        }
    }
    return hasEmojiChar
}

private fun resolveNextCustomEmoji(previous: String, updated: String): String {
    val trimmed = updated.trim()
    if (trimmed.isEmpty()) return ""
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(trimmed)
    val firstEnd = iterator.next()
    val candidate = if (firstEnd == BreakIterator.DONE || firstEnd >= trimmed.length) {
        extractFirstGrapheme(trimmed)
    } else if (previous.isNotEmpty() && trimmed.startsWith(previous)) {
        extractFirstGrapheme(trimmed.removePrefix(previous))
    } else {
        extractFirstGrapheme(trimmed)
    }
    return if (isPlausibleEmojiGrapheme(candidate)) candidate else previous
}

/**
 * Molten Forge dialog for naming, picking an emoji badge, and optionally setting a saved
 * [PipelineSpec] format preset as the auto-applied default for [mediaKind].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmojiPresetPickerDialog(
    mediaKind: MediaKind,
    spec: PipelineSpec,
    initialName: String = "",
    initialEmoji: String = "🔥",
    initialDefaultForKind: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (emoji: String, name: String, isDefaultForKind: Boolean) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val normalizedInitialEmoji = remember(initialEmoji) {
        extractFirstGrapheme(initialEmoji).ifBlank { "🔥" }
    }
    var selectedEmoji by remember(normalizedInitialEmoji) { mutableStateOf(normalizedInitialEmoji) }
    var customEmojiText by remember(normalizedInitialEmoji) {
        mutableStateOf(
            if (normalizedInitialEmoji in CURATED_PRESET_EMOJIS) "" else normalizedInitialEmoji
        )
    }
    var presetName by remember(initialName) { mutableStateOf(initialName) }
    var isDefaultForKind by remember(initialDefaultForKind) { mutableStateOf(initialDefaultForKind) }

    val activeEmoji = customEmojiText.ifBlank { selectedEmoji }.ifBlank { "🔥" }
    val trimmedName = presetName.trim()
    val kindLabel = remember(mediaKind) { friendlyMediaKindLabel(mediaKind) }
    val specSummary = remember(spec) { spec.sanitizeForPreset().summaryLabel() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            shape = RoundedCornerShape(22.dp),
            color = AnvilGunmetal,
            contentColor = CrispWhite,
            border = BorderStroke(1.dp, ForgeBevelOutline)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = FurnaceCard,
                            border = BorderStroke(1.25.dp, ForgeGold.copy(alpha = 0.72f))
                        ) {
                            Box(
                                modifier = Modifier.size(42.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = activeEmoji,
                                    fontSize = 21.sp
                                )
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Save Format Preset",
                                style = MaterialTheme.typography.titleMedium,
                                color = CrispWhite
                            )
                            Text(
                                text = "Reusable ${kindLabel} profile",
                                style = MaterialTheme.typography.labelSmall,
                                color = SecundaSilver
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close preset dialog",
                            tint = SecundaSilver
                        )
                    }
                }

                // Spec Summary Preview Badge
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = FurnaceCard,
                    border = BorderStroke(1.dp, ForgeBevelOutline)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LocalFireDepartment,
                            contentDescription = null,
                            tint = EmberFlame,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = specSummary,
                            style = MaterialTheme.typography.labelMedium,
                            color = ForgeGold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Curated Emoji Picker Grid + Custom Grapheme Input
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Pick Badge Emoji",
                        style = MaterialTheme.typography.labelMedium,
                        color = SecundaSilver
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CURATED_PRESET_EMOJIS.forEach { emoji ->
                            val isSelected = activeEmoji == emoji
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) MoltenEmber.copy(alpha = 0.22f) else FurnaceCard,
                                border = BorderStroke(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) ForgeGold else ForgeBevelOutline
                                ),
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        selectedEmoji = emoji
                                        customEmojiText = ""
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = emoji,
                                        fontSize = 20.sp
                                    )
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = customEmojiText,
                        onValueChange = { raw ->
                            val next = resolveNextCustomEmoji(customEmojiText, raw)
                            customEmojiText = next
                            selectedEmoji = if (next.isNotEmpty()) {
                                next
                            } else if (normalizedInitialEmoji in CURATED_PRESET_EMOJIS) {
                                normalizedInitialEmoji
                            } else {
                                CURATED_PRESET_EMOJIS.first()
                            }
                        },
                        label = { Text("Custom Emoji") },
                        placeholder = { Text("Type or paste any emoji") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = EmberFlame,
                            unfocusedBorderColor = ForgeBevelOutline,
                            focusedLabelColor = EmberFlame,
                            unfocusedLabelColor = SecundaSilver,
                            focusedTextColor = CrispWhite,
                            unfocusedTextColor = CrispWhite,
                            cursorColor = EmberFlame,
                            focusedContainerColor = FurnaceCard,
                            unfocusedContainerColor = FurnaceCard
                        )
                    )
                }

                // Preset Name Field
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text("Preset Name") },
                    placeholder = { Text("Discord 10MB AV1") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        }
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = EmberFlame,
                        unfocusedBorderColor = ForgeBevelOutline,
                        focusedLabelColor = EmberFlame,
                        unfocusedLabelColor = SecundaSilver,
                        focusedTextColor = CrispWhite,
                        unfocusedTextColor = CrispWhite,
                        cursorColor = EmberFlame,
                        focusedContainerColor = FurnaceCard,
                        unfocusedContainerColor = FurnaceCard
                    )
                )

                // Default Auto-Apply Switch
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = FurnaceCard,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isDefaultForKind) ForgeGold.copy(alpha = 0.55f) else ForgeBevelOutline
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "Auto-apply to future $kindLabel files",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CrispWhite
                            )
                            Text(
                                text = "Marks this preset with ★ as your default $kindLabel profile",
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedSteel
                            )
                        }
                        Switch(
                            checked = isDefaultForKind,
                            onCheckedChange = { isDefaultForKind = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CrispWhite,
                                checkedTrackColor = EmberFlame,
                                uncheckedThumbColor = SecundaSilver,
                                uncheckedTrackColor = SlateCard,
                                uncheckedBorderColor = SlateOutline
                            )
                        )
                    }
                }

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, SlateOutline),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        modifier = Modifier
                            .weight(0.9f)
                            .defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(
                            text = "Cancel",
                            style = MaterialTheme.typography.titleSmall,
                            color = SecundaSilver
                        )
                    }

                    ForgePrimaryButton(
                        onClick = { onConfirm(activeEmoji, trimmedName, isDefaultForKind) },
                        text = "Save Preset",
                        icon = Icons.Rounded.BookmarkAdd,
                        enabled = trimmedName.isNotEmpty(),
                        modifier = Modifier.weight(1.3f)
                    )
                }
            }
        }
    }
}

/**
 * Compact Molten-Forge strip displaying saved emoji format presets for [mediaKind], highlighting
 * default presets with a [ForgeGold] border + `★` star tag, and supporting long-press management.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EmojiPresetStrip(
    mediaKind: MediaKind,
    presets: List<FormatPresetEntity>,
    onApplyPreset: (FormatPresetEntity) -> Unit,
    onSaveCurrentAsPreset: () -> Unit,
    onToggleDefaultForKind: (FormatPresetEntity) -> Unit,
    onDeletePreset: (FormatPresetEntity) -> Unit,
    modifier: Modifier = Modifier,
    actionLabel: String = "Save Preset"
) {
    val scopedPresets = remember(presets, mediaKind) {
        presets.filter { it.mediaKind == mediaKind.name }
    }
    var managedPreset by remember { mutableStateOf<FormatPresetEntity?>(null) }
    val kindLabel = remember(mediaKind) { friendlyMediaKindLabel(mediaKind) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = AnvilGunmetal,
        border = BorderStroke(1.dp, ForgeBevelOutline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                scopedPresets.forEach { preset ->
                    val isDefault = preset.isDefaultForKind
                    val pillShape = RoundedCornerShape(999.dp)
                    Surface(
                        shape = pillShape,
                        color = if (isDefault) MoltenEmber.copy(alpha = 0.16f) else FurnaceCard,
                        border = BorderStroke(
                            width = if (isDefault) 1.35.dp else 1.dp,
                            color = if (isDefault) ForgeGold else ForgeBevelOutline
                        ),
                        modifier = Modifier
                            .clip(pillShape)
                            .combinedClickable(
                                onClick = { onApplyPreset(preset) },
                                onLongClick = { managedPreset = preset }
                            )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                text = "${preset.emoji} ${preset.name}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isDefault) FontWeight.Bold else FontWeight.Medium,
                                color = CrispWhite,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isDefault) {
                                Text(
                                    text = "★",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = ForgeGold
                                )
                            }
                        }
                    }
                }

                // + Save Preset Chip
                val saveChipShape = RoundedCornerShape(999.dp)
                Surface(
                    onClick = onSaveCurrentAsPreset,
                    shape = saveChipShape,
                    color = MoltenEmber.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, EmberFlame.copy(alpha = 0.65f))
                ) {
                    Text(
                        text = "+ $actionLabel",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = EmberFlame,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            }

            if (scopedPresets.isNotEmpty()) {
                Text(
                    text = "Tap to apply • Hold to manage default/delete",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedSteel
                )
            }
        }
    }

    val activeTarget = managedPreset
    if (activeTarget != null) {
        val defaultActionLabel = if (activeTarget.isDefaultForKind) {
            "Remove Default for $kindLabel"
        } else {
            "Set Default for $kindLabel"
        }
        AlertDialog(
            onDismissRequest = { managedPreset = null },
            containerColor = AnvilGunmetal,
            titleContentColor = CrispWhite,
            textContentColor = SecundaSilver,
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${activeTarget.emoji} ${activeTarget.name}",
                        style = MaterialTheme.typography.titleMedium,
                        color = CrispWhite,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (activeTarget.isDefaultForKind) {
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = "Default preset",
                            tint = ForgeGold,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Manage this saved $kindLabel preset:",
                        style = MaterialTheme.typography.bodySmall,
                        color = SecundaSilver
                    )
                    Spacer(Modifier.height(2.dp))

                    ForgePrimaryButton(
                        onClick = {
                            onApplyPreset(activeTarget)
                            managedPreset = null
                        },
                        text = "Apply",
                        icon = Icons.Rounded.Check,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedButton(
                        onClick = {
                            onToggleDefaultForKind(activeTarget)
                            managedPreset = null
                        },
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(
                            1.dp,
                            if (activeTarget.isDefaultForKind) SlateOutline else ForgeGold.copy(alpha = 0.65f)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = FurnaceCard,
                            contentColor = ForgeGold
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 46.dp)
                    ) {
                        Icon(
                            imageVector = if (activeTarget.isDefaultForKind) {
                                Icons.Rounded.StarBorder
                            } else {
                                Icons.Rounded.Star
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = defaultActionLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    HorizontalDivider(color = ForgeBevelOutline)

                    OutlinedButton(
                        onClick = {
                            onDeletePreset(activeTarget)
                            managedPreset = null
                        },
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, CoralError.copy(alpha = 0.55f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = FurnaceCard,
                            contentColor = CoralError
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 46.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(text = "Delete Preset")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { managedPreset = null }) {
                    Text("Cancel", color = SecundaSilver)
                }
            }
        )
    }
}
