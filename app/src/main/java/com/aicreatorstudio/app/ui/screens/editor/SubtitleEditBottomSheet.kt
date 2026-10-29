package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.model.PersianFont
import com.aicreatorstudio.app.model.TextOverlay
import com.aicreatorstudio.app.ui.screens.editor.toFontFamily
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.iOSShapes

/**
 * A sliding bottom-sheet panel for editing a selected subtitle.
 *
 * Appears when the user taps a subtitle on the timeline. Contains:
 *   - Text field with the current subtitle text (RTL-aware for Persian)
 *   - Save / Delete / Cancel buttons
 *
 * The sheet is modal so the user must deal with it before interacting
 * with other panels. Timestamps and word timings are preserved as-is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleEditBottomSheet(
    selectedTextOverlay: TextOverlay?,
    onUpdateTextOverlay: (textId: String, newText: String) -> Unit,
    onDeleteTextOverlay: (textId: String) -> Unit,
    onDismiss: () -> Unit
) {
    if (selectedTextOverlay == null) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var editTextBuffer by remember { mutableStateOf(selectedTextOverlay.text) }

    LaunchedEffect(selectedTextOverlay.id) {
        editTextBuffer = selectedTextOverlay.text
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = androidx.compose.ui.graphics.Color(0xFF1A1A24),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Handle bar
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(HairlineSubtle)
                    .align(Alignment.CenterHorizontally)
            )

            Text(
                text = "ویرایش زیرنویس",
                color = LabelPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = editTextBuffer,
                onValueChange = { editTextBuffer = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("متن زیرنویس") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = LabelPrimary,
                    fontFamily = selectedTextOverlay.fontFamily.let { name ->
                        runCatching { PersianFont.valueOf(name) }
                            .getOrNull()
                            ?.toFontFamily()
                    }
                ),
                minLines = 2,
                maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = iOSBlueBright,
                    unfocusedBorderColor = HairlineSubtle,
                    cursorColor = iOSBlueBright
                )
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Save
                androidx.compose.material3.Button(
                    onClick = {
                        onUpdateTextOverlay(selectedTextOverlay.id, editTextBuffer)
                        onDismiss()
                    },
                    enabled = editTextBuffer.isNotEmpty() && editTextBuffer != selectedTextOverlay.text,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = iOSBlueBright
                    ),
                    shape = iOSShapes.capsule,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Save,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("ذخیره")
                }

                // Delete
                OutlinedButton(
                    onClick = {
                        onDeleteTextOverlay(selectedTextOverlay.id)
                        onDismiss()
                    },
                    shape = iOSShapes.capsule,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.Red.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("حذف", color = androidx.compose.ui.graphics.Color.Red.copy(alpha = 0.8f))
                }

                // Cancel
                TextButton(
                    onClick = {
                        editTextBuffer = selectedTextOverlay.text
                        onDismiss()
                    },
                    shape = iOSShapes.capsule,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("لغو", color = LabelSecondary)
                }
            }
        }
    }
}
