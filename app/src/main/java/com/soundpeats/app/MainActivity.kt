package com.soundpeats.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.soundpeats.app.ui.theme.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var earbudsController: EarbudsController
    private lateinit var bluetoothAdapter: BluetoothAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        earbudsController = EarbudsController()
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        setContent {
            SoundpeatsControllerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppScreen(earbudsController, bluetoothAdapter)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        earbudsController.disconnect()
    }
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(controller: EarbudsController, bluetoothAdapter: BluetoothAdapter?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var hasPermissions by remember { mutableStateOf(false) }
    val isConnected by controller.isConnected.collectAsState()

    var initialCheckDone by remember { mutableStateOf(false) }
    var isAutoConnecting by remember { mutableStateOf(false) }

    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
    }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grantedMap ->
        hasPermissions = grantedMap.values.all { it }
    }

    LaunchedEffect(Unit) {
        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!allGranted) {
            launcher.launch(permissions)
        } else {
            hasPermissions = true
        }
    }

    LaunchedEffect(hasPermissions, bluetoothAdapter?.isEnabled, isConnected) {
        if (hasPermissions && bluetoothAdapter?.isEnabled == true && !isConnected) {
            while (!controller.isConnected.value) {
                isAutoConnecting = true
                val targetDevice = bluetoothAdapter.bondedDevices.find { it.name == "SOUNDPEATS Mini Pro HS" }
                if (targetDevice != null) {
                    val success = controller.connect(targetDevice)
                    if (!success) {
                        Log.d("MainActivity", "Failed to connect to Soundpeats Mini Pro HS")
                    }
                } else {
                    Log.d("MainActivity", "Soundpeats Mini Pro HS not found in paired devices")
                }
                isAutoConnecting = false
                initialCheckDone = true
                if (!controller.isConnected.value) {
                    kotlinx.coroutines.delay(3000) // wait before retrying
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Custom Top Bar
            PremiumTopBar(isConnected = isConnected, onDisconnect = {
                controller.disconnect()
            })
            
            // Content
            AnimatedContent(
                targetState = when {
                    !hasPermissions -> "permissions"
                    bluetoothAdapter == null || !bluetoothAdapter.isEnabled -> "bluetooth_off"
                    !isConnected -> "connecting"
                    else -> "control_panel"
                },
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)) + slideInVertically { it / 4 } togetherWith
                    fadeOut(animationSpec = tween(200))
                },
                label = "content"
            ) { screen ->
                when (screen) {
                    "permissions" -> StatusScreen(
                        icon = Icons.Filled.Lock,
                        title = "Permissions Required",
                        subtitle = "Bluetooth permissions are needed to connect to your earbuds.",
                        color = AmberWarning
                    )
                    "bluetooth_off" -> StatusScreen(
                        icon = Icons.Filled.BluetoothDisabled,
                        title = "Bluetooth is Off",
                        subtitle = "Please enable Bluetooth to discover and connect to your earbuds.",
                        color = RedError
                    )
                    "connecting" -> StatusScreen(
                        icon = Icons.Filled.BluetoothSearching,
                        title = "Connecting",
                        subtitle = "Searching for Soundpeats Mini Pro HS...",
                        color = CyanAccent
                    )
                    "control_panel" -> ControlPanel(controller)
                }
            }
        }
    }
}

// --- Premium Top Bar ---
@Composable
fun PremiumTopBar(isConnected: Boolean, onDisconnect: () -> Unit) {
    val statusDotAlpha = if (isConnected) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        infiniteTransition.animateFloat(
            initialValue = 0.4f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
            label = "pulseAlpha"
        ).value
    } else 1f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "Soundpeats Studio",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .alpha(statusDotAlpha)
                            .clip(CircleShape)
                            .background(if (isConnected) GreenActive else TextMuted)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (isConnected) "Connected" else "Not Connected",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isConnected) GreenActive else TextMuted
                    )
                }
            }
            if (isConnected) {
                IconButton(onClick = onDisconnect) {
                    Icon(
                        Icons.Filled.BluetoothDisabled,
                        contentDescription = "Disconnect",
                        tint = RedError
                    )
                }
            }
        }
    }
}

// --- Status Screen ---
@Composable
fun StatusScreen(icon: ImageVector, title: String, subtitle: String, color: Color) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(40.dp))
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            Spacer(modifier = Modifier.height(8.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, textAlign = TextAlign.Center)
        }
    }
}

// --- Device Selection ---
@SuppressLint("MissingPermission")
@Composable
fun DeviceSelectionList(bluetoothAdapter: BluetoothAdapter, onDeviceSelected: (BluetoothDevice) -> Unit) {
    val devices = remember(bluetoothAdapter) { bluetoothAdapter.bondedDevices.toList() }
    var connectingDevice by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text("Paired Devices", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text("${devices.size} device${if (devices.size != 1) "s" else ""} found", style = MaterialTheme.typography.bodySmall, color = TextMuted)
        Spacer(modifier = Modifier.height(20.dp))
        
        if (devices.isEmpty()) {
            StatusScreen(
                icon = Icons.Filled.BluetoothSearching,
                title = "No Paired Devices",
                subtitle = "Pair your Soundpeats earbuds in system Bluetooth settings first.",
                color = BlueInfo
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(devices) { device ->
                    val isConnecting = connectingDevice == device.address
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize()
                            .clickable(enabled = !isConnecting) {
                                connectingDevice = device.address
                                onDeviceSelected(device)
                            },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        border = if (isConnecting) androidx.compose.foundation.BorderStroke(1.dp, CyanAccent.copy(alpha = 0.5f)) else null
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(CyanAccent.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.Headphones, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(24.dp))
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(device.name ?: "Unknown Device", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(device.address, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            }
                            if (isConnecting) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = CyanAccent)
                            } else {
                                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- Control Panel ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlPanel(controller: EarbudsController) {
    val scope = rememberCoroutineScope()
    val observedAncMode by controller.ancMode.collectAsState()
    val observedGameMode by controller.isGameMode.collectAsState()
    val observedTouchEnabled by controller.isTouchEnabled.collectAsState()
    val observedEqPreset by controller.eqPreset.collectAsState()

    var selectedAncMode by remember { mutableIntStateOf(0) }
    var gameModeEnabled by remember { mutableStateOf(false) }
    var touchEnabled by remember { mutableStateOf(true) }

    LaunchedEffect(observedAncMode) { observedAncMode?.let { selectedAncMode = it } }
    LaunchedEffect(observedGameMode) { observedGameMode?.let { gameModeEnabled = it } }
    LaunchedEffect(observedTouchEnabled) { observedTouchEnabled?.let { touchEnabled = it } }

    // Correct PEQ band frequencies from the WuQi WQ7033 hardware
    val eqBandLabels = listOf("60Hz", "180Hz", "540Hz", "1.2kHz", "3kHz", "6.6kHz", "15kHz")
    val eqGains = remember { mutableStateListOf(0f, 0f, 0f, 0f, 0f, 0f, 0f) }

    var isCustomEqActive = observedEqPreset == 0x09

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ANC Section
        SectionCard(title = "Noise Control", icon = Icons.Filled.HearingDisabled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AncModeChip("Normal", Icons.Filled.VolumeOff, selectedAncMode == 0, Modifier.weight(1f)) {
                    selectedAncMode = 0; controller.setAncMode(0)
                }
                AncModeChip("ANC", Icons.Filled.NoiseAware, selectedAncMode == 1, Modifier.weight(1f)) {
                    selectedAncMode = 1; controller.setAncMode(1)
                }
                AncModeChip("Transparent", Icons.Filled.Hearing, selectedAncMode == 2, Modifier.weight(1f)) {
                    selectedAncMode = 2; controller.setAncMode(2)
                }
            }
        }

        // Game Mode
        SectionCard(title = "Game Mode", icon = Icons.Filled.SportsEsports) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Low Latency", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        if (gameModeEnabled) "Enabled — reduced audio delay" else "Disabled — normal latency",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
                Switch(
                    checked = gameModeEnabled,
                    onCheckedChange = { gameModeEnabled = it; controller.setGameMode(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = DarkSurface1,
                        checkedTrackColor = CyanAccent,
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = DarkSurface4
                    )
                )
            }
        }

        // Touch Sensors
        SectionCard(title = "Touch Controls", icon = Icons.Filled.TouchApp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Earbud Touch Panels", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        if (touchEnabled) "Enabled — tap to control" else "Disabled — panels locked",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
                Switch(
                    checked = touchEnabled,
                    onCheckedChange = { touchEnabled = it; controller.setTouchEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = DarkSurface1,
                        checkedTrackColor = CyanAccent,
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = DarkSurface4
                    )
                )
            }
        }

        // Equalizer — PEQ (Parametric EQ), sends individual band packets
        SectionCard(
            title = "Equalizer",
            icon = Icons.Filled.Equalizer,
            trailing = {
                TextButton(onClick = {
                    for (i in 0 until 7) eqGains[i] = 0f
                    scope.launch { controller.setCustomEq(eqGains.toList()) }
                }) {
                    Text("Reset", color = CyanAccent, style = MaterialTheme.typography.labelLarge)
                }
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                eqBandLabels.forEachIndexed { index, freq ->
                    EqBandRow(
                        frequency = freq,
                        gain = eqGains[index],
                        onGainChange = { eqGains[index] = it },
                        onChangeFinished = {
                            // Send the changed band and trigger commit via band 7
                            scope.launch {
                                controller.setEqBand(index, eqGains[index], eqGains[6])
                            }
                        }
                    )
                }
            }
        }

        // EQ Presets — send all 7 bands sequentially via PEQ
        SectionCard(title = "EQ Presets (Active: ${if (observedEqPreset == 0x09) "Custom" else "Preset ${observedEqPreset ?: ""}"})", icon = Icons.Filled.Tune) {
            val presets = listOf(
                "Flat" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f),
                "Bass Boost" to listOf(8f, 5f, 2f, 0f, 0f, 0f, 0f),
                "Treble" to listOf(0f, 0f, 0f, 2f, 4f, 7f, 8f),
                "V-Shape" to listOf(6f, 4f, 0f, -2f, 0f, 4f, 6f),
                "Vocal" to listOf(-2f, 0f, 3f, 6f, 4f, 1f, 0f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { (name, gains) ->
                    val isActive = eqGains.toList() == gains
                    FilterChip(
                        selected = isActive,
                        onClick = {
                            gains.forEachIndexed { i, v -> eqGains[i] = v }
                            scope.launch { controller.setCustomEq(gains) }
                        },
                        label = { Text(name, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyanAccent.copy(alpha = 0.15f),
                            selectedLabelColor = CyanAccent
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = MaterialTheme.colorScheme.outline,
                            selectedBorderColor = CyanAccent.copy(alpha = 0.4f)
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// --- Reusable Section Card ---
@Composable
fun SectionCard(
    title: String,
    icon: ImageVector,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                }
                trailing?.invoke()
            }
            Spacer(modifier = Modifier.height(14.dp))
            content()
        }
    }
}

// --- ANC Mode Chip ---
@Composable
fun AncModeChip(text: String, icon: ImageVector, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bgColor by animateColorAsState(
        if (isSelected) CyanAccent.copy(alpha = 0.15f) else Color.Transparent,
        animationSpec = tween(250), label = "ancBg"
    )
    val borderColor by animateColorAsState(
        if (isSelected) CyanAccent.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline,
        animationSpec = tween(250), label = "ancBorder"
    )
    val contentColor by animateColorAsState(
        if (isSelected) CyanAccent else TextSecondary,
        animationSpec = tween(250), label = "ancContent"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.material.ripple.rememberRipple(color = CyanAccent),
                onClick = onClick
            )
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.height(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, color = contentColor)
        }
    }
}

// --- EQ Band Row ---
@Composable
fun EqBandRow(frequency: String, gain: Float, onGainChange: (Float) -> Unit, onChangeFinished: () -> Unit) {
    // Round gain to nearest 0.5 dB for display
    val displayGain = (Math.round(gain * 2) / 2.0f)
    val gainText = if (displayGain == displayGain.toInt().toFloat()) {
        "${if (displayGain > 0) "+" else ""}${displayGain.toInt()}dB"
    } else {
        "${if (displayGain > 0) "+" else ""}${"%.1f".format(displayGain)}dB"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Text(
            frequency,
            modifier = Modifier.width(52.dp),
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )
        Slider(
            value = gain,
            onValueChange = { newValue ->
                // Snap to nearest integer for clean dB values
                onGainChange(Math.round(newValue).toFloat())
            },
            onValueChangeFinished = onChangeFinished,
            valueRange = EarbudsController.MIN_GAIN_DB.toFloat()..EarbudsController.MAX_GAIN_DB.toFloat(),
            steps = (EarbudsController.MAX_GAIN_DB - EarbudsController.MIN_GAIN_DB) - 1,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            colors = SliderDefaults.colors(
                thumbColor = CyanAccent,
                activeTrackColor = CyanAccent,
                inactiveTrackColor = MaterialTheme.colorScheme.outline,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            )
        )
        Text(
            gainText,
            modifier = Modifier.width(48.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (gain != 0f) CyanAccent else TextMuted,
            textAlign = TextAlign.End
        )
    }
}
