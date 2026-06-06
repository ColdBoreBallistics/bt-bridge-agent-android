// Copyright (C) 2026 Jason M. Schwefel. All Rights Reserved.
package com.coldboreballisticsllc.blebridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coldboreballisticsllc.blebridge.MainViewModel
import com.coldboreballisticsllc.blebridge.WeatherFlowReading

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
        )

        HorizontalDivider(color = Color(0xFF2A2E30))

        // WeatherFlow live data (visible only when frames are arriving)
        state.weatherFlow?.let { wf ->
            WeatherFlowPanel(reading = wf)
            HorizontalDivider(color = Color(0xFF2A2E30))
        }

        // Server connection controls
        ServerPanel(
            host      = state.serverHost,
            port      = state.serverPort,
            connected = state.serverConnected,
            onHost    = viewModel::updateHost,
            onPort    = viewModel::updatePort,
            onConnect = viewModel::connectServer,
            onDisconnect = viewModel::disconnectServer,
        )

        HorizontalDivider(color = Color(0xFF2A2E30))

        // Log
        Text(
            text  = "Event Log",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
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
private fun StatusRow(serverConnected: Boolean, bleDevice: String?) {
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
    host:        String,
    port:        String,
    connected:   Boolean,
    onHost:      (String) -> Unit,
    onPort:      (String) -> Unit,
    onConnect:   () -> Unit,
    onDisconnect: () -> Unit,
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
            OutlinedTextField(
                value         = host,
                onValueChange = onHost,
                label         = { Text("Server IP") },
                singleLine    = true,
                enabled       = !connected,
                modifier      = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors        = serverFieldColors(),
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

@Composable
private fun WeatherFlowPanel(reading: WeatherFlowReading) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text  = "WeatherFlow Tactical",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f),
        )
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WfTile(label = "Wind",  value = "%.2f".format(reading.windSpeedMph), unit = "mph", modifier = Modifier.weight(1f))
            WfTile(label = "Dir",   value = "${reading.windDirDeg}",              unit = "°",   modifier = Modifier.weight(1f))
            WfTile(label = "Temp",  value = "%.1f".format(reading.tempC),         unit = "°C",  modifier = Modifier.weight(1f))
            WfTile(label = "Hum",   value = "${reading.humidityPct}",             unit = "%",   modifier = Modifier.weight(1f))
            WfTile(label = "Pres",  value = "%.1f".format(reading.pressureHpa),   unit = "hPa", modifier = Modifier.weight(1.4f))
        }
    }
}

@Composable
private fun WfTile(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color    = Color(0xFF181C1E),
        shape    = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier              = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalAlignment   = Alignment.CenterHorizontally,
            verticalArrangement   = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text  = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
            Text(
                text  = value,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 15.sp,
                ),
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                text  = unit,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
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
