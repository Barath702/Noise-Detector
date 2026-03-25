package com.example.noisedetector.ui.cyber

import android.annotation.SuppressLint
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.example.noisedetector.NoiseDetectorApp
import com.example.noisedetector.R
import com.example.noisedetector.service.ControllerService
import com.example.noisedetector.service.ListenerService
import com.example.noisedetector.util.AppPrefs
import com.example.noisedetector.util.IpUtils
import com.example.noisedetector.util.PairingCodes
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.math.sin

private enum class DeviceRole { Listener, Controller }

@Composable
fun CyberFootstepRoot(mainActivity: com.example.noisedetector.MainActivity) {
    val context = LocalContext.current
    val app = context.applicationContext as NoiseDetectorApp
    val prefs = remember { AppPrefs(context) }

    var role by remember { mutableStateOf(DeviceRole.Listener) }
    var listenerCodeInput by remember {
        mutableStateOf(prefs.listenerPairCode.takeIf { it.length == 4 }.orEmpty())
    }
    var controllerCodeInput by remember { mutableStateOf(prefs.lastPairCode) }
    var ipOptional by remember { mutableStateOf(prefs.controllerHost) }
    var margin by remember { mutableFloatStateOf(prefs.thresholdMarginDb) }
    var alertsOn by remember { mutableStateOf(prefs.alertsEnabled) }

    val listenerDb by app.listenerDb.collectAsStateWithLifecycle()
    val listenerClients by app.listenerClients.collectAsStateWithLifecycle()
    val listenerRunning by app.listenerRunning.collectAsStateWithLifecycle()

    val controllerDb by app.controllerDb.collectAsStateWithLifecycle()
    val controllerConnected by app.controllerConnected.collectAsStateWithLifecycle()
    val controllerRunning by app.controllerRunning.collectAsStateWithLifecycle()
    val noiseFloor by app.noiseFloor.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    val neededPermissions = remember {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    fun ensurePermissions(): Boolean {
        val missing = neededPermissions.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                it
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return true
        permissionLauncher.launch(missing.toTypedArray())
        return false
    }

    val liveDb = if (role == DeviceRole.Listener) listenerDb else controllerDb
    val effectiveFloor = if (noiseFloor.isNaN()) 28f else noiseFloor
    val trigger = effectiveFloor + margin
    val alarmActive = role == DeviceRole.Controller &&
        alertsOn &&
        controllerRunning &&
        controllerConnected &&
        controllerDb >= trigger

    Box(
        Modifier
            .fillMaxSize()
            .background(CyberColors.ScreenGradient)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            CyberHeader(title = stringResource(R.string.app_name))
            Spacer(Modifier.height(16.dp))
            RoleSelector(
                role = role,
                onRole = { role = it }
            )
            Spacer(Modifier.height(20.dp))

            CyberDecibelBlock(
                db = liveDb,
                label = stringResource(R.string.live_db),
                alarmActive = alarmActive
            )
            Spacer(Modifier.height(20.dp))
            CyberSpectrumBars(levelDb = liveDb)
            Spacer(Modifier.height(24.dp))

            if (role == DeviceRole.Listener) {
                ListenerCyberSection(
                    listenerRunning = listenerRunning,
                    listenerClients = listenerClients,
                    pairCode = listenerCodeInput,
                    onPairCodeChange = { listenerCodeInput = it.filter { ch -> ch.isDigit() }.take(4) },
                    onRandomCode = {
                        listenerCodeInput = (0..9999).random().toString().padStart(4, '0')
                    },
                    onStart = {
                        if (!ensurePermissions()) return@ListenerCyberSection
                        val code = PairingCodes.normalize(listenerCodeInput) ?: return@ListenerCyberSection
                        prefs.listenerPairCode = code
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, ListenerService::class.java).putExtra(
                                ListenerService.EXTRA_PAIR_CODE,
                                code
                            )
                        )
                    },
                    onStop = {
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, ListenerService::class.java).apply {
                                action = ListenerService.ACTION_STOP
                            }
                        )
                    }
                )
            } else {
                ControllerCyberSection(
                    pairCode = controllerCodeInput,
                    onPairCodeChange = { controllerCodeInput = it.filter { ch -> ch.isDigit() }.take(4) },
                    ipOptional = ipOptional,
                    onIpOptionalChange = { ipOptional = it },
                    margin = margin,
                    onMarginChange = { v ->
                        margin = v
                        prefs.thresholdMarginDb = v
                    },
                    alertsOn = alertsOn,
                    onAlertsChange = { v ->
                        alertsOn = v
                        prefs.alertsEnabled = v
                    },
                    controllerRunning = controllerRunning,
                    controllerConnected = controllerConnected,
                    controllerDb = controllerDb,
                    noiseFloor = noiseFloor,
                    trigger = trigger,
                    onConnect = {
                        if (!ensurePermissions()) return@ControllerCyberSection
                        val ip = ipOptional.trim()
                        val intent = Intent(context, ControllerService::class.java)
                        if (ip.isNotEmpty()) {
                            prefs.controllerHost = ip
                            intent.putExtra(ControllerService.EXTRA_HOST, ip)
                        } else {
                            val code = PairingCodes.normalize(controllerCodeInput)
                                ?: return@ControllerCyberSection
                            prefs.lastPairCode = code
                            intent.putExtra(ControllerService.EXTRA_PAIR_CODE, code)
                        }
                        ContextCompat.startForegroundService(context, intent)
                    },
                    onDisconnect = {
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, ControllerService::class.java).apply {
                                action = ControllerService.ACTION_STOP
                            }
                        )
                    },
                    onCalibrate = {
                        if (!controllerRunning) return@ControllerCyberSection
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, ControllerService::class.java).apply {
                                action = ControllerService.ACTION_CALIBRATE
                            }
                        )
                    },
                    onBattery = { openBatteryCyber(mainActivity) }
                )
            }

            Spacer(Modifier.height(28.dp))
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CyberLabel(stringResource(R.string.grant_permissions))
                    CyberMuted(stringResource(R.string.permission_mic))
                    CyberMuted(stringResource(R.string.permission_notifications))
                    NeonOutlineButton(
                        text = stringResource(R.string.grant_permissions),
                        onClick = { ensurePermissions() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }

        ThresholdAlertOverlay(
            visible = alarmActive,
            db = controllerDb,
            trigger = trigger
        )
    }
}

@Composable
private fun CyberHeader(title: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title.uppercase(),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 13.sp,
                letterSpacing = 3.sp,
                color = CyberColors.NeonCyan,
                shadow = Shadow(
                    color = CyberColors.NeonCyan.copy(alpha = 0.85f),
                    blurRadius = 18f,
                    offset = Offset.Zero
                )
            )
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(CyberColors.NeonCyan.copy(alpha = 0.3f + it * 0.2f))
                )
            }
        }
    }
}

@Composable
private fun RoleSelector(role: DeviceRole, onRole: (DeviceRole) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        NeonPill(
            text = stringResource(R.string.role_listener),
            selected = role == DeviceRole.Listener,
            onClick = { onRole(DeviceRole.Listener) },
            modifier = Modifier.weight(1f)
        )
        NeonPill(
            text = stringResource(R.string.role_controller),
            selected = role == DeviceRole.Controller,
            onClick = { onRole(DeviceRole.Controller) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun NeonPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glow by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(280),
        label = "pill"
    )
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .clip(shape)
            .then(
                if (selected) {
                    Modifier.border(
                        width = 1.dp,
                        brush = Brush.linearGradient(
                            listOf(CyberColors.NeonCyan, CyberColors.NeonMagenta)
                        ),
                        shape = shape
                    )
                } else Modifier.border(1.dp, CyberColors.GlassBorder, shape)
            )
            .background(
                if (selected) CyberColors.GlassLight.copy(alpha = 0.12f + glow * 0.08f)
                else CyberColors.GlassLight.copy(alpha = 0.06f)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text.uppercase(),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                color = if (selected) CyberColors.NeonCyan else CyberColors.TextMuted,
                shadow = if (selected) Shadow(
                    CyberColors.NeonCyan.copy(0.7f),
                    blurRadius = 12f * glow,
                    offset = Offset.Zero
                ) else Shadow(Color.Transparent, blurRadius = 0f, offset = Offset.Zero)
            )
        )
    }
}

@Composable
private fun CyberDecibelBlock(db: Float, label: String, alarmActive: Boolean) {
    val animatedDb by animateFloatAsState(
        targetValue = db,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "db"
    )
    val pulse = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    var prevDb by remember { mutableFloatStateOf(db) }
    LaunchedEffect(db) {
        if (db > prevDb + 0.6f) {
            scope.launch {
                pulse.snapTo(1.06f)
                pulse.animateTo(1f, spring(dampingRatio = Spring.DampingRatioLowBouncy))
            }
        }
        prevDb = db
    }
    val alarmPulse = rememberInfiniteTransition(label = "alarm")
    val alarmPhase by alarmPulse.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "al"
    )
    val ringScale = if (alarmActive) alarmPhase else 1f

    Box(
        Modifier
            .fillMaxWidth()
            .scale(pulse.value * ringScale)
            .drawBehind {
                if (alarmActive) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                CyberColors.NeonRed.copy(alpha = 0.45f),
                                Color.Transparent
                            ),
                            radius = size.minDimension * 0.85f
                        ),
                        radius = size.minDimension * 0.55f,
                        center = Offset(size.width / 2f, size.height / 2f)
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        GlassPanel(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(vertical = 28.dp, horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = label.uppercase(),
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        letterSpacing = 2.sp,
                        color = CyberColors.TextMuted
                    )
                )
                Spacer(Modifier.height(8.dp))
                val levelColor = levelColorForDb(animatedDb)
                Text(
                    text = "%.1f".format(animatedDb),
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 64.sp,
                        lineHeight = 68.sp,
                        color = levelColor,
                        shadow = Shadow(
                            color = levelColor.copy(alpha = 0.95f),
                            blurRadius = if (alarmActive) 28f else 20f,
                            offset = Offset.Zero
                        )
                    )
                )
                Text(
                    text = "dB",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        letterSpacing = 4.sp,
                        color = CyberColors.TextMuted
                    )
                )
            }
        }
    }
}

private fun levelColorForDb(db: Float): Color {
    val t = (db / 120f).coerceIn(0f, 1f)
    return when {
        t < 0.45f -> lerp(CyberColors.NeonGreen, CyberColors.NeonYellow, t / 0.45f)
        t < 0.75f -> lerp(
            CyberColors.NeonYellow,
            CyberColors.NeonRed,
            (t - 0.45f) / 0.3f
        )
        else -> CyberColors.NeonRed
    }
}

@Composable
private fun CyberSpectrumBars(levelDb: Float, modifier: Modifier = Modifier) {
    val smoothDb by animateFloatAsState(levelDb, tween(180), label = "barsDb")
    val infinite = rememberInfiniteTransition(label = "bars")
    val phase by infinite.animateFloat(
        0f,
        (Math.PI * 2).toFloat(),
        infiniteRepeatable(tween(4000, easing = LinearEasing)),
        label = "ph"
    )
    val barCount = 36
    Canvas(
        modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(18.dp))
    ) {
        val w = size.width / barCount
        val maxH = size.height * 0.92f
        val base = smoothDb / 120f
        for (i in 0 until barCount) {
            val wave = (sin(phase + i * 0.35f).toFloat() * 0.5f + 0.5f)
            val h = (base * (0.45f + 0.55f * wave)).coerceIn(0.04f, 1f) * maxH
            val t = (i / barCount.toFloat())
            val c = lerp(
                CyberColors.NeonGreen,
                CyberColors.NeonYellow,
                (t * 1.4f).coerceIn(0f, 1f)
            ).let { lc ->
                lerp(lc, CyberColors.NeonRed, ((t - 0.55f) / 0.45f).coerceIn(0f, 1f))
            }
            val left = i * w + w * 0.18f
            drawRoundRect(
                color = c.copy(alpha = 0.85f),
                topLeft = Offset(left, size.height - h),
                size = Size(w * 0.64f, h),
                cornerRadius = CornerRadius(4f, 4f)
            )
        }
    }
}

@Composable
private fun GlassPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .clip(shape)
            .border(1.dp, CyberColors.GlassBorder, shape)
            .background(CyberColors.GlassLight.copy(alpha = 0.14f))
            .drawBehind {
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            CyberColors.NeonCyan.copy(alpha = 0.08f),
                            Color.Transparent,
                            CyberColors.NeonMagenta.copy(alpha = 0.06f)
                        ),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height)
                    ),
                    cornerRadius = CornerRadius(22.dp.toPx(), 22.dp.toPx())
                )
            }
    ) {
        content()
    }
}

@Composable
private fun NeonFillButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    danger: Boolean = false
) {
    val shape = RoundedCornerShape(18.dp)
    val brush = if (danger) {
        Brush.horizontalGradient(listOf(CyberColors.NeonRed.copy(0.85f), CyberColors.NeonMagenta.copy(0.7f)))
    } else {
        Brush.horizontalGradient(listOf(CyberColors.NeonCyan.copy(0.75f), CyberColors.NeonMagenta.copy(0.55f)))
    }
    Box(
        modifier
            .clip(shape)
            .then(
                if (enabled) {
                    Modifier.drawBehind {
                        drawRoundRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    (if (danger) CyberColors.NeonRed else CyberColors.NeonCyan).copy(0.35f),
                                    Color.Transparent
                                ),
                                radius = size.maxDimension * 0.6f
                            ),
                            cornerRadius = CornerRadius(18.dp.toPx(), 18.dp.toPx())
                        )
                    }
                } else Modifier
            )
            .background(
                brush = if (enabled) brush else SolidColor(Color(0xFF151922)),
                shape = shape
            )
            .clickable(enabled = enabled, indication = null, interactionSource = remember { MutableInteractionSource() }) {
                onClick()
            }
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text.uppercase(),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 1.sp,
                color = if (enabled) CyberColors.VoidBlack else CyberColors.TextDim,
                textAlign = TextAlign.Center
            )
        )
    }
}

@Composable
private fun NeonOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .clip(shape)
            .border(
                1.dp,
                Brush.horizontalGradient(
                    listOf(
                        CyberColors.NeonCyan.copy(if (enabled) 1f else 0.35f),
                        CyberColors.NeonMagenta.copy(if (enabled) 1f else 0.25f)
                    )
                ),
                shape
            )
            .background(CyberColors.GlassLight.copy(if (enabled) 0.08f else 0.04f))
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { if (enabled) onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text.uppercase(),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = if (enabled) CyberColors.NeonCyan else CyberColors.TextDim,
                shadow = if (enabled) {
                    Shadow(
                        color = CyberColors.NeonCyan.copy(0.5f),
                        offset = Offset.Zero,
                        blurRadius = 10f
                    )
                } else {
                    Shadow(color = Color.Transparent, offset = Offset.Zero, blurRadius = 0f)
                }
            )
        )
    }
}

@Composable
private fun NeonSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int
) {
    val trackH = 8.dp
    val thumbR = 14.dp
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
    ) {
        val wPx = constraints.maxWidth.toFloat()
        val stepPx = (valueRange.endInclusive - valueRange.start) / (steps + 1)
        fun valueFromFraction(f: Float): Float {
            val raw = valueRange.start + f * (valueRange.endInclusive - valueRange.start)
            val n = kotlin.math.round((raw - valueRange.start) / stepPx).toInt()
                .coerceIn(0, steps + 1)
            return (valueRange.start + n * stepPx).coerceIn(valueRange.start, valueRange.endInclusive)
        }
        val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
        val thumbX = fraction * wPx

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(valueRange, steps, wPx) {
                    detectTapGestures { pos ->
                        val f = (pos.x / wPx).coerceIn(0f, 1f)
                        onValueChange(valueFromFraction(f))
                    }
                    detectHorizontalDragGestures { change, _ ->
                        val f = (change.position.x / wPx).coerceIn(0f, 1f)
                        onValueChange(valueFromFraction(f))
                    }
                }
        ) {
            val h = trackH.toPx()
            val cy = size.height / 2f
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    listOf(
                        CyberColors.PanelNavy,
                        CyberColors.DeepNavy
                    )
                ),
                topLeft = Offset(0f, cy - h / 2f),
                size = Size(size.width, h),
                cornerRadius = CornerRadius(h / 2f, h / 2f)
            )
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    listOf(CyberColors.NeonGreen, CyberColors.NeonYellow, CyberColors.NeonRed)
                ),
                topLeft = Offset(0f, cy - h / 2f),
                size = Size(size.width * fraction, h),
                cornerRadius = CornerRadius(h / 2f, h / 2f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(CyberColors.NeonCyan, CyberColors.NeonCyan.copy(0.3f)),
                    center = Offset(thumbX, cy),
                    radius = thumbR.toPx() * 1.8f
                ),
                radius = thumbR.toPx(),
                center = Offset(thumbX, cy)
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.9f),
                radius = thumbR.toPx() * 0.35f,
                center = Offset(thumbX, cy)
            )
        }
    }
}

@Composable
private fun CyberTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier) {
        Text(
            text = label.uppercase(),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                color = CyberColors.TextMuted
            )
        )
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = CyberColors.TextPrimary,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            ),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .border(1.dp, CyberColors.GlassBorder, shape)
                .background(CyberColors.VoidBlack.copy(alpha = 0.45f))
                .padding(horizontal = 16.dp, vertical = 14.dp)
        )
    }
}

@Composable
private fun CyberLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
            color = CyberColors.NeonCyan,
            shadow = Shadow(
                color = CyberColors.NeonCyan.copy(0.4f),
                offset = Offset.Zero,
                blurRadius = 8f
            )
        )
    )
}

@Composable
private fun CyberMuted(text: String) {
    Text(
        text = text,
        style = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 13.sp,
            color = CyberColors.TextMuted,
            lineHeight = 18.sp
        )
    )
}

@Composable
private fun CyberSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label.uppercase(),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                letterSpacing = 0.5.sp,
                color = CyberColors.TextPrimary
            )
        )
        val trackW = 52.dp
        val trackH = 28.dp
        val thumb = 22.dp
        val offset by animateFloatAsState(
            if (checked) 1f else 0f,
            tween(200),
            label = "sw"
        )
        Box(
            Modifier
                .width(trackW)
                .height(trackH)
                .clip(RoundedCornerShape(50))
                .background(
                    if (checked) CyberColors.NeonCyan.copy(0.25f)
                    else CyberColors.PanelNavy.copy(0.9f)
                )
                .border(
                    1.dp,
                    if (checked) CyberColors.NeonCyan.copy(0.8f) else CyberColors.GlassBorder,
                    RoundedCornerShape(50)
                )
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                    onCheckedChange(!checked)
                }
        ) {
            val pad = 3.dp
            val travel = trackW - thumb - pad * 2
            Box(
                Modifier
                    .offset(x = pad + travel * offset)
                    .size(thumb)
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.radialGradient(
                            listOf(
                                if (checked) CyberColors.NeonCyan else CyberColors.TextDim,
                                if (checked) CyberColors.NeonCyan.copy(0.6f) else CyberColors.TextDim.copy(0.5f)
                            )
                        )
                    )
            )
        }
    }
}

@Composable
private fun ListenerCyberSection(
    listenerRunning: Boolean,
    listenerClients: Int,
    pairCode: String,
    onPairCodeChange: (String) -> Unit,
    onRandomCode: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current
    val lanIp = remember { IpUtils.getLanIpv4(context).orEmpty() }
    val port = NoiseDetectorApp.WS_PORT
    val codeOk = PairingCodes.normalize(pairCode) != null

    GlassPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CyberMuted(stringResource(R.string.pair_code_listener_hint))
            CyberTextField(
                value = pairCode,
                onValueChange = onPairCodeChange,
                label = stringResource(R.string.pair_code_label),
                keyboardType = KeyboardType.Number
            )
            NeonOutlineButton(
                text = stringResource(R.string.random_code),
                onClick = onRandomCode,
                modifier = Modifier.fillMaxWidth()
            )
            CyberMuted(stringResource(R.string.listener_hint))
            CyberMuted(stringResource(R.string.clients_connected, listenerClients))
            if (lanIp.isEmpty()) {
                Text(
                    stringResource(R.string.no_wifi_ip),
                    color = CyberColors.NeonRed,
                    style = TextStyle(fontSize = 13.sp)
                )
            } else {
                CyberLabel(stringResource(R.string.stream_address))
                Text(
                    "ws://$lanIp:$port/",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        color = CyberColors.NeonCyan.copy(0.9f),
                        fontSize = 13.sp,
                        shadow = Shadow(
                            color = CyberColors.NeonCyan.copy(0.4f),
                            offset = Offset.Zero,
                            blurRadius = 6f
                        )
                    )
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NeonFillButton(
                    stringResource(R.string.start_listening),
                    onStart,
                    enabled = !listenerRunning && codeOk,
                    modifier = Modifier.weight(1f)
                )
                NeonFillButton(
                    stringResource(R.string.stop_listening),
                    onStop,
                    enabled = listenerRunning,
                    modifier = Modifier.weight(1f),
                    danger = true
                )
            }
        }
    }
}

@Composable
private fun ControllerCyberSection(
    pairCode: String,
    onPairCodeChange: (String) -> Unit,
    ipOptional: String,
    onIpOptionalChange: (String) -> Unit,
    margin: Float,
    onMarginChange: (Float) -> Unit,
    alertsOn: Boolean,
    onAlertsChange: (Boolean) -> Unit,
    controllerRunning: Boolean,
    controllerConnected: Boolean,
    controllerDb: Float,
    noiseFloor: Float,
    trigger: Float,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onCalibrate: () -> Unit,
    onBattery: () -> Unit
) {
    val canConnectByCode = PairingCodes.normalize(pairCode) != null
    val canConnect = !controllerRunning && (ipOptional.isNotBlank() || canConnectByCode)
    val status = when {
        controllerConnected -> stringResource(R.string.status_connected)
        controllerRunning && ipOptional.isBlank() -> stringResource(R.string.status_searching)
        controllerRunning -> stringResource(R.string.status_connecting)
        else -> stringResource(R.string.status_idle)
    }

    GlassPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CyberMuted(stringResource(R.string.controller_hint))
            CyberTextField(
                pairCode,
                onPairCodeChange,
                stringResource(R.string.pair_code_label),
                KeyboardType.Number
            )
            CyberTextField(
                ipOptional,
                onIpOptionalChange,
                stringResource(R.string.advanced_ip),
                KeyboardType.Uri
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NeonFillButton(
                    stringResource(R.string.connect),
                    onConnect,
                    enabled = canConnect,
                    modifier = Modifier.weight(1f)
                )
                NeonFillButton(
                    stringResource(R.string.disconnect),
                    onDisconnect,
                    enabled = controllerRunning,
                    modifier = Modifier.weight(1f),
                    danger = true
                )
            }
            Text(
                status.uppercase(),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    letterSpacing = 2.sp,
                    color = if (controllerConnected) CyberColors.NeonGreen else CyberColors.TextMuted
                )
            )
            CyberLabel(stringResource(R.string.noise_floor))
            CyberMuted(
                if (noiseFloor.isNaN()) stringResource(R.string.floor_uncalibrated)
                else stringResource(R.string.floor_value, noiseFloor)
            )
            CyberMuted(stringResource(R.string.effective_trigger, trigger))
            CyberLabel(stringResource(R.string.threshold_margin))
            Text(
                stringResource(R.string.threshold_value, margin),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    color = CyberColors.NeonYellow,
                    shadow = Shadow(
                        color = CyberColors.NeonYellow.copy(0.5f),
                        offset = Offset.Zero,
                        blurRadius = 10f
                    )
                )
            )
            NeonSlider(
                value = margin,
                onValueChange = onMarginChange,
                valueRange = AppPrefs.MARGIN_MIN_DB..AppPrefs.MARGIN_MAX_DB,
                steps = (AppPrefs.MARGIN_MAX_DB - AppPrefs.MARGIN_MIN_DB - 1).toInt()
            )
            CyberSwitchRow(stringResource(R.string.alerts_enabled), alertsOn, onAlertsChange)
            NeonOutlineButton(
                text = stringResource(R.string.calibrate),
                onClick = onCalibrate,
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (controllerRunning && controllerConnected) 1f else 0.4f),
                enabled = controllerRunning && controllerConnected
            )
            NeonOutlineButton(
                text = stringResource(R.string.battery_unrestricted),
                onClick = onBattery,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ThresholdAlertOverlay(visible: Boolean, db: Float, trigger: Float) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)) + scaleIn(initialScale = 0.9f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
        exit = fadeOut(tween(180)) + scaleOut()
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 100.dp),
            contentAlignment = Alignment.Center
        ) {
            val shape = RoundedCornerShape(24.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .border(2.dp, Brush.linearGradient(listOf(CyberColors.NeonRed, CyberColors.NeonYellow)), shape)
                    .background(CyberColors.VoidBlack.copy(0.92f))
                    .drawBehind {
                        drawRoundRect(
                            brush = Brush.radialGradient(
                                colors = listOf(CyberColors.NeonRed.copy(0.35f), Color.Transparent),
                                radius = size.maxDimension * 0.55f
                            ),
                            cornerRadius = CornerRadius(24.dp.toPx(), 24.dp.toPx())
                        )
                    }
                    .padding(24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.alert_notif_title).uppercase(),
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 16.sp,
                            letterSpacing = 2.sp,
                            color = CyberColors.NeonRed,
                            shadow = Shadow(
                                color = CyberColors.NeonRed.copy(0.9f),
                                offset = Offset.Zero,
                                blurRadius = 16f
                            ),
                            textAlign = TextAlign.Center
                        )
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.alert_notif_text, db, trigger),
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            color = CyberColors.TextPrimary,
                            textAlign = TextAlign.Center
                        )
                    )
                }
            }
        }
    }
}

@SuppressLint("BatteryLife", "UseKtx")
private fun openBatteryCyber(activity: com.example.noisedetector.MainActivity) {
    try {
        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = "package:${activity.packageName}".toUri()
        }
        activity.startActivity(i)
    } catch (_: Exception) {
        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
        }
        activity.startActivity(i)
    }
}
