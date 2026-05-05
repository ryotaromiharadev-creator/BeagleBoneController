package com.example.linuxconnect.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.linuxconnect.model.ServerInfo
import com.example.linuxconnect.viewmodel.RcViewModel
import kotlin.math.roundToInt

enum class JoystickAxis { VERTICAL, HORIZONTAL }

// ─── Virtual Joystick ────────────────────────────────────────────────────────

@Composable
fun JoystickView(
    label: String,
    axis: JoystickAxis,
    onValueChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val baseDp  = 90.dp
    val thumbDp = 28.dp
    val density = LocalDensity.current
    val baseR   = with(density) { baseDp.toPx() }
    val thumbR  = with(density) { thumbDp.toPx() }

    var thumbOffset by remember { mutableStateOf(Offset.Zero) }

    Column(
        modifier            = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            modifier = Modifier
                .size(baseDp * 2)
                .pointerInput(axis, baseR) {
                    detectDragGestures(
                        onDragEnd    = { thumbOffset = Offset.Zero; onValueChanged(0f) },
                        onDragCancel = { thumbOffset = Offset.Zero; onValueChanged(0f) },
                        onDrag       = { change, _ ->
                            change.consume()
                            val center     = Offset(size.width / 2f, size.height / 2f)
                            val fromCenter = change.position - center
                            thumbOffset = when (axis) {
                                JoystickAxis.VERTICAL ->
                                    Offset(0f, fromCenter.y.coerceIn(-baseR, baseR))
                                JoystickAxis.HORIZONTAL ->
                                    Offset(fromCenter.x.coerceIn(-baseR, baseR), 0f)
                            }
                            onValueChanged(
                                when (axis) {
                                    JoystickAxis.VERTICAL   -> (-thumbOffset.y / baseR).coerceIn(-1f, 1f)
                                    JoystickAxis.HORIZONTAL -> ( thumbOffset.x / baseR).coerceIn(-1f, 1f)
                                }
                            )
                        },
                    )
                },
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)

            // Base circle
            drawCircle(color = Color(0xFF2C2C2E), radius = baseR, center = center)
            drawCircle(
                color  = Color(0xFF48484A),
                radius = baseR,
                center = center,
                style  = Stroke(width = 2f),
            )

            // Axis guide
            when (axis) {
                JoystickAxis.VERTICAL -> {
                    drawLine(Color(0xFF636366),
                        center.copy(y = center.y - baseR), center.copy(y = center.y + baseR), 2f)
                    drawLine(Color(0xFF636366),
                        center.copy(x = center.x - 12f), center.copy(x = center.x + 12f), 2f)
                }
                JoystickAxis.HORIZONTAL -> {
                    drawLine(Color(0xFF636366),
                        center.copy(x = center.x - baseR), center.copy(x = center.x + baseR), 2f)
                    drawLine(Color(0xFF636366),
                        center.copy(y = center.y - 12f), center.copy(y = center.y + 12f), 2f)
                }
            }

            // Thumb — glow
            drawCircle(
                color  = Color(0x330A84FF),
                radius = thumbR * 1.5f,
                center = center + thumbOffset,
            )
            // Thumb — body
            drawCircle(
                color  = Color(0xFF0A84FF),
                radius = thumbR,
                center = center + thumbOffset,
            )
            // Thumb — highlight
            drawCircle(
                color  = Color(0xFF5AC8FA),
                radius = thumbR * 0.45f,
                center = center + thumbOffset - Offset(thumbR * 0.15f, thumbR * 0.15f),
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(text = label, color = Color(0xFF8E8E93), fontSize = 12.sp)
    }
}

// ─── RC Control Screen ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RcControlScreen(
    server: ServerInfo,
    onBack: () -> Unit,
    vm: RcViewModel = viewModel(),
) {
    LaunchedEffect(server) { vm.connect(server) }
    DisposableEffect(Unit)  { onDispose { vm.disconnect() } }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1C1C1E),
                ),
                title = {
                    Column {
                        Text(
                            text       = server.name,
                            fontWeight = FontWeight.SemiBold,
                            color      = Color.White,
                            fontSize   = 16.sp,
                        )
                        Text(
                            text     = if (vm.isConnected)
                                "送信中 ${RcViewModel.SEND_HZ} Hz · UDP ${server.host}:${RcViewModel.UDP_PORT}"
                            else
                                vm.errorMessage ?: "未接続",
                            fontSize = 11.sp,
                            color    = if (vm.isConnected) Color(0xFF32D74B) else Color(0xFFFF453A),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { vm.disconnect(); onBack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint               = Color.White,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier              = Modifier
                .fillMaxSize()
                .background(Color(0xFF000000))
                .padding(innerPadding),
            verticalArrangement   = Arrangement.Center,
            horizontalAlignment   = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier              = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                // ── Left stick: throttle (vertical) ──────────────────────────
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text       = "THROTTLE",
                        color      = Color(0xFF8E8E93),
                        fontSize   = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text       = "${(vm.throttle * 127f).roundToInt()}",
                        color      = Color.White,
                        fontSize   = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(12.dp))
                    JoystickView(
                        label          = "↑ 前進  後退 ↓",
                        axis           = JoystickAxis.VERTICAL,
                        onValueChanged = { vm.throttle = it },
                    )
                }

                // ── Centre telemetry ──────────────────────────────────────────
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val tByte = (vm.throttle * 127f).roundToInt()
                    val sByte = (vm.steering * 127f).roundToInt()
                    Text("Byte[0]", color = Color(0xFF636366), fontSize = 10.sp)
                    Text("$tByte",  color = Color(0xFFFFCC00), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    Text("Byte[1]", color = Color(0xFF636366), fontSize = 10.sp)
                    Text("$sByte",  color = Color(0xFFFF9F0A), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }

                // ── Right stick: steering (horizontal) ───────────────────────
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text       = "STEERING",
                        color      = Color(0xFF8E8E93),
                        fontSize   = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text       = "${(vm.steering * 127f).roundToInt()}",
                        color      = Color.White,
                        fontSize   = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(12.dp))
                    JoystickView(
                        label          = "← 左  右 →",
                        axis           = JoystickAxis.HORIZONTAL,
                        onValueChanged = { vm.steering = it },
                    )
                }
            }
        }
    }
}
