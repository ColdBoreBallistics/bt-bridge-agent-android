// Copyright (C) 2026 Jason M. Schwefel. All Rights Reserved.
package com.coldboreballisticsllc.btbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coldboreballisticsllc.btbridge.MainViewModel
import com.coldboreballisticsllc.btbridge.ScanDevice
import com.coldboreballisticsllc.btbridge.ServerQuestion
import com.coldboreballisticsllc.btbridge.template.RenderedFrame

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll log to bottom
    LaunchedEffect(state.log.size) {
        if (state.log.isNotEmpty()) listState.animateScrollToItem(state.log.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Title
        Text(
            text  = "BLE Bridge",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        // Status row
        StatusRow(
            serverConnected = state.serverConnected,
            bleDevice       = state.bleDevice,
            onBleDc         = viewModel::disconnectBle,
        )

        HorizontalDivider(color = Color(0xFF2A2E30))

        // BLE scan panel — shown when not connected to a device
        if (state.bleDevice == null) {
            ScanPanel(
                isScanning  = state.isScanning,
                results     = state.scanResults,
                onScan      = viewModel::startLocalScan,
                onStop      = viewModel::stopLocalScan,
                onConnect   = viewModel::connectDevice,
            )
            HorizontalDivider(color = Color(0xFF2A2E30))
        }

        // Template display — shown when connected to a device
        if (state.bleDevice != null) {
            TemplateDisplayPanel(
                renderedFrame    = state.renderedFrame,
                gattAnalyserMode = state.gattAnalyserMode,
                activeView       = state.activeView,
                availableViews   = state.availableViews,
                templateWarnings = state.templateWarnings,
                onViewSelected   = { view ->
                    state.bleDevice?.let { addr -> viewModel.selectView(addr, view) }
                },
            )
            HorizontalDivider(color = Color(0xFF2A2E30))
        }

        if (state.pendingQuestions.isNotEmpty()) {
            QuestionStack(
                questions = state.pendingQuestions,
                onAnswer  = viewModel::answerQuestion,
                onDismiss = viewModel::dismissQuestion,
            )
            HorizontalDivider(color = Color(0xFF2A2E30))
        }

        // Server connection controls
        ServerPanel(
            host             = state.serverHost,
            port             = state.serverPort,
            connected        = state.serverConnected,
            history          = state.ipHistory,
            onHost           = viewModel::updateHost,
            onPort           = viewModel::updatePort,
            onSelectHistory  = viewModel::updateHostFromHistory,
            onConnect        = viewModel::connectServer,
            onDisconnect     = viewModel::disconnectServer,
        )

        HorizontalDivider(color = Color(0xFF2A2E30))

        // Log header
        Row(
            modifier              = Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text  = "Event Log",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            TextButton(
                onClick      = viewModel::clearLog,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(
                    text  = "Clear",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }
        }
        LazyColumn(
            state    = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF181C1E))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(state.log) { line ->
                val color = when {
                    line.contains("ERROR", ignoreCase = true) -> Color(0xFFC0554A)
                    line.contains("←BLE")                     -> Color(0xFF7EAFD4)
                    line.contains("→SRV")                     -> Color(0xFF5A9E6F)
                    line.contains("TCP connected")            -> Color(0xFFF0A500)
                    line.contains("TCP disconnected")         -> Color(0xFFB07A10)
                    else                                      -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                }
                Text(
                    text     = line,
                    style    = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize   = 11.sp,
                        lineHeight = 15.sp,
                    ),
                    color    = color,
                )
            }
        }
    }
}

@Composable
private fun StatusRow(
    serverConnected: Boolean,
    bleDevice:       String?,
    onBleDc:         () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        StatusChip(
            label   = "Server",
            active  = serverConnected,
            activeColor = MaterialTheme.colorScheme.primary,
        )
        StatusChip(
            label   = if (bleDevice != null) "BLE: ${bleDevice.takeLast(8)}" else "BLE: idle",
            active  = bleDevice != null,
            activeColor = MaterialTheme.colorScheme.secondary,
        )
        if (bleDevice != null) {
            TextButton(
                onClick        = onBleDc,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(
                    text  = "Disconnect BLE",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFC0554A),
                )
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, active: Boolean, activeColor: Color) {
    Surface(
        color  = if (active) activeColor.copy(alpha = 0.15f) else Color(0xFF202528),
        shape  = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier          = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = if (active) activeColor else Color(0xFF2A2E30),
                        shape = androidx.compose.foundation.shape.CircleShape,
                    )
            )
            Text(
                text  = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) activeColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun ServerPanel(
    host:            String,
    port:            String,
    connected:       Boolean,
    history:         List<String>,
    onHost:          (String) -> Unit,
    onPort:          (String) -> Unit,
    onSelectHistory: (String) -> Unit,
    onConnect:       () -> Unit,
    onDisconnect:    () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text  = "Desktop Server",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            IpHistoryField(
                value         = host,
                history       = history,
                enabled       = !connected,
                onValueChange = onHost,
                onSelect      = onSelectHistory,
                modifier      = Modifier.weight(1f),
            )
            OutlinedTextField(
                value         = port,
                onValueChange = onPort,
                label         = { Text("Port") },
                singleLine    = true,
                enabled       = !connected,
                modifier      = Modifier.width(90.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors        = serverFieldColors(),
            )
            Button(
                onClick = if (connected) onDisconnect else onConnect,
                colors  = ButtonDefaults.buttonColors(
                    containerColor = if (connected) Color(0xFF202528) else MaterialTheme.colorScheme.primary,
                    contentColor   = if (connected) MaterialTheme.colorScheme.onSurface else Color.Black,
                ),
            ) {
                Text(if (connected) "Disconnect" else "Connect")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IpHistoryField(
    value:         String,
    history:       List<String>,
    enabled:       Boolean,
    onValueChange: (String) -> Unit,
    onSelect:      (String) -> Unit,
    modifier:      Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded         = expanded && history.isNotEmpty() && enabled,
        onExpandedChange = { if (enabled) expanded = it },
        modifier         = modifier,
    ) {
        OutlinedTextField(
            value           = value,
            onValueChange   = onValueChange,
            label           = { Text("Server IP") },
            singleLine      = true,
            enabled         = enabled,
            trailingIcon    = {
                if (history.isNotEmpty() && enabled) {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            colors          = serverFieldColors(),
            modifier        = Modifier
                .menuAnchor(MenuAnchorType.PrimaryEditable)
                .fillMaxWidth(),
        )
        if (history.isNotEmpty()) {
            ExposedDropdownMenu(
                expanded         = expanded,
                onDismissRequest = { expanded = false },
            ) {
                history.forEach { ip ->
                    DropdownMenuItem(
                        text    = { Text(ip, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) },
                        onClick = { onSelect(ip); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuestionStack(
    questions: List<ServerQuestion>,
    onAnswer:  (String, Boolean) -> Unit,
    onDismiss: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text  = "Questions from Server",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        questions.forEach { q ->
            QuestionCard(question = q, onAnswer = onAnswer, onDismiss = onDismiss)
        }
    }
}

@Composable
private fun QuestionCard(
    question:  ServerQuestion,
    onAnswer:  (String, Boolean) -> Unit,
    onDismiss: (String) -> Unit,
) {
    Surface(
        color = Color(0xFF181C1E),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.Top,
            ) {
                Text(
                    text     = question.question,
                    style    = MaterialTheme.typography.bodyMedium,
                    color    = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick  = { onDismiss(question.reqId) },
                    modifier = Modifier.size(20.dp),
                ) {
                    Icon(
                        imageVector        = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint               = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier           = Modifier.size(16.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick  = { onAnswer(question.reqId, true) },
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2A5C3A),
                        contentColor   = Color.White,
                    ),
                    modifier = Modifier.weight(1f),
                ) { Text("Yes") }
                Button(
                    onClick  = { onAnswer(question.reqId, false) },
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF5C2A2A),
                        contentColor   = Color.White,
                    ),
                    modifier = Modifier.weight(1f),
                ) { Text("No") }
            }
        }
    }
}

@Composable
private fun ScanPanel(
    isScanning: Boolean,
    results:    List<ScanDevice>,
    onScan:     () -> Unit,
    onStop:     () -> Unit,
    onConnect:  (ScanDevice) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text  = "BLE Devices",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            Button(
                onClick = if (isScanning) onStop else onScan,
                colors  = ButtonDefaults.buttonColors(
                    containerColor = if (isScanning) Color(0xFF202528) else MaterialTheme.colorScheme.secondary,
                    contentColor   = if (isScanning) MaterialTheme.colorScheme.onSurface else Color.Black,
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier  = Modifier.size(14.dp),
                        color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (isScanning) "Stop" else "Scan")
            }
        }

        if (results.isEmpty() && !isScanning) {
            Text(
                text  = "Tap Scan to discover nearby BLE devices",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            )
        }

        results.forEach { device ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onConnect(device) },
                color  = Color(0xFF181C1E),
                shape  = MaterialTheme.shapes.small,
            ) {
                Row(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text  = device.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text  = device.address,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize   = 10.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        )
                    }
                    Text(
                        text  = "${device.rssi} dBm",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = when {
                            device.rssi >= -60 -> Color(0xFF5A9E6F)
                            device.rssi >= -75 -> Color(0xFFF0A500)
                            else               -> Color(0xFFC0554A)
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateDisplayPanel(
    renderedFrame:    RenderedFrame?,
    gattAnalyserMode: Boolean,
    activeView:       String,
    availableViews:   List<String>,
    templateWarnings: List<String>,
    onViewSelected:   (String) -> Unit,
    modifier:         Modifier = Modifier,
) {
    Column(
        modifier            = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text  = "Device Display",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )

        // Warning banners
        templateWarnings.forEach { warning ->
            Surface(
                color = Color(0xFF3A2E00),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text     = "⚠ $warning",
                    color    = Color(0xFFFFCC00),
                    style    = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        // View selector — only when more than one view is available
        if (availableViews.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                availableViews.forEach { view ->
                    FilterChip(
                        selected = view == activeView,
                        onClick  = { onViewSelected(view) },
                        label    = { Text(view, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }

        // Rendered fields
        if (renderedFrame != null) {
            renderedFrame.fields.filter { it.display }.forEach { field ->
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text  = field.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    Text(
                        text  = if (field.unit.isNotEmpty()) "${field.value} ${field.unit}" else field.value,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            renderedFrame.warnings.forEach { w ->
                Text(
                    text  = "⚠ ${w.fieldId}: ${w.message}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFCC00),
                )
            }
        } else if (gattAnalyserMode || templateWarnings.isEmpty()) {
            Text(
                text  = if (gattAnalyserMode) "GATT Analyser — no template" else "Waiting for BLE data…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun serverFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor      = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor    = Color(0xFF2A2E30),
    focusedLabelColor       = MaterialTheme.colorScheme.primary,
    cursorColor             = MaterialTheme.colorScheme.primary,
    focusedContainerColor   = Color(0xFF0F1112),
    unfocusedContainerColor = Color(0xFF0F1112),
    focusedTextColor        = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor      = MaterialTheme.colorScheme.onSurface,
    disabledTextColor       = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
    disabledBorderColor     = Color(0xFF2A2E30).copy(alpha = 0.5f),
    disabledLabelColor      = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
    disabledContainerColor  = Color(0xFF0F1112),
)
