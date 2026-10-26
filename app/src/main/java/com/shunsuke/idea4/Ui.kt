@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.shunsuke.idea4

import android.media.AudioDeviceInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List as ListIcon
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------- 色（デザイン案から読み取った値） ----------------

private val Bg = Color(0xFF12181C)
private val NavBg = Color(0xFF13191C)
private val CardBg = Color(0xFF1A2227)
private val FieldBg = Color(0xFF151C21)
private val Line = Color(0xFF2B353C)
private val GridLine = Color(0xFF242E34)
private val Outline = Color(0xFFB4BEC6)
private val Fg = Color(0xFFF2F5F7)
private val Sub = Color(0xFF8E99A6)
private val Dim = Color(0xFF4A545E)
private val Accent = Color(0xFF9BEB80)
private val OnAccent = Color(0xFF0B2410)
private val Red = Color(0xFFF85858)
private val ChipBg = Color(0xFF1D2C26)
private val ChipLine = Color(0xFF2F4A3B)
private val Wave = Color(0xFF8793A1)

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    background = Bg,
    onBackground = Fg,
    surface = CardBg,
    onSurface = Fg,
    surfaceVariant = CardBg,
    onSurfaceVariant = Sub,
    secondaryContainer = Line,
    onSecondaryContainer = Fg,
    error = Red,
    onError = Color.White,
    outline = Line,
)

@Composable
fun IdeaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, content = content)
}

private fun fmt(s: Double): String {
    val m = (s / 60).toInt()
    val sec = (s % 60).toInt()
    return m.toString() + ":" + (if (sec < 10) "0" else "") + sec
}

// ---------------- 共通の部品 ----------------

@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    dot: Boolean = false,
    filled: Boolean = false,
    color: Color = Fg,
    borderColor: Color = Outline,
    bg: Color = Color.Transparent,
    height: Dp = 40.dp,
    fontSize: TextUnit = 14.sp,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(10.dp)
    val content = if (filled) OnAccent else color
    Box(
        modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(if (filled) Accent else bg)
            .then(if (filled) Modifier else Modifier.border(1.dp, borderColor, shape))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (dot) Box(Modifier.size(9.dp).clip(CircleShape).background(content))
            if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Text(text, color = content, fontSize = fontSize, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun ThinSlider(
    value: Float,
    onChange: (Float) -> Unit,
    min: Float,
    max: Float,
    modifier: Modifier = Modifier,
    fromCenter: Boolean = false,
) {
    val cur = rememberUpdatedState(onChange)
    Canvas(
        modifier
            .height(28.dp)
            .pointerInput(min, max) {
                val pad = 8.dp.toPx()
                detectTapGestures(onTap = { o ->
                    val f = ((o.x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
                    val fn = cur.value
                    fn(min + f * (max - min))
                })
            }
            .pointerInput(min, max) {
                val pad = 8.dp.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        val f = ((o.x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
                        val fn = cur.value
                        fn(min + f * (max - min))
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val f = ((change.position.x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
                        val fn = cur.value
                        fn(min + f * (max - min))
                    },
                )
            },
    ) {
        val pad = 8.dp.toPx()
        val w = size.width - 2 * pad
        val frac = ((value - min) / (max - min)).coerceIn(0f, 1f)
        val cy = size.height / 2f
        drawLine(
            Line, Offset(pad, cy), Offset(pad + w, cy),
            strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round,
        )
        val startX = if (fromCenter) pad + w / 2f else pad
        drawLine(
            Accent, Offset(startX, cy), Offset(pad + w * frac, cy),
            strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round,
        )
        if (fromCenter) {
            drawLine(
                Sub, Offset(pad + w / 2f, cy - 7.dp.toPx()), Offset(pad + w / 2f, cy + 7.dp.toPx()),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
        drawCircle(Accent, radius = 6.dp.toPx(), center = Offset(pad + w * frac, cy))
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    min: Float,
    max: Float,
    fromCenter: Boolean = false,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Sub, fontSize = 12.sp)
            Text(valueText, color = Fg, fontSize = 12.sp)
        }
        ThinSlider(value, onChange, min, max, Modifier.fillMaxWidth(), fromCenter)
    }
}

@Composable
private fun AppField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .clip(shape)
            .background(FieldBg)
            .border(1.dp, Line, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (leading != null) Icon(leading, contentDescription = null, tint = Sub, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = Sub, fontSize = 14.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = Fg, fontSize = 14.sp),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TagChip(text: String, selected: Boolean, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(8.dp)
    val base = Modifier
        .height(30.dp)
        .clip(shape)
        .background(if (selected) ChipBg else Color.Transparent)
        .border(1.dp, if (selected) ChipLine else Line, shape)
    val mod = if (onClick != null) base.clickable(onClick = onClick) else base
    Box(mod.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (selected) Accent else Sub, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(17.dp)
    Box(
        Modifier
            .height(34.dp)
            .clip(shape)
            .background(if (selected) Accent else Color.Transparent)
            .then(if (selected) Modifier else Modifier.border(1.dp, Line, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) OnAccent else Fg,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

private fun peaksOf(p: ShortArray?, n: Int): FloatArray {
    if (p == null || p.isEmpty()) return FloatArray(0)
    val out = FloatArray(n)
    val step = maxOf(1, p.size / n)
    for (b in 0 until n) {
        var m = 0
        val s = b * step
        val e = minOf(p.size, s + step)
        var i = s
        val skip = maxOf(1, (e - s) / 32)
        while (i < e) {
            val v = abs(p[i].toInt())
            if (v > m) m = v
            i += skip
        }
        out[b] = m / 32768f
    }
    return out
}

@Composable
private fun Waveform(pcm: ShortArray?, color: Color, modifier: Modifier) {
    val peaks = remember(pcm) { peaksOf(pcm, 90) }
    Canvas(modifier) {
        val h = size.height
        val w = size.width
        if (peaks.isEmpty()) {
            drawLine(
                color.copy(alpha = 0.5f), Offset(0f, h / 2f), Offset(w, h / 2f),
                strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx())),
            )
        } else {
            val step = w / peaks.size
            for (x in peaks.indices) {
                val px = x * step + step / 2f
                val p = maxOf(0.06f, peaks[x])
                drawLine(
                    color,
                    Offset(px, h / 2f - p * h / 2f),
                    Offset(px, h / 2f + p * h / 2f),
                    strokeWidth = maxOf(1.5f, step * 0.5f),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String?,
    yes: String,
    no: String,
    onYes: () -> Unit,
    onNo: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onNo,
        containerColor = CardBg,
        titleContentColor = Fg,
        textContentColor = Sub,
        title = { Text(title, fontSize = 17.sp) },
        text = if (body != null) ({ Text(body) }) else null,
        confirmButton = { TextButton(onClick = onYes) { Text(yes, color = Accent) } },
        dismissButton = { TextButton(onClick = onNo) { Text(no, color = Sub) } },
    )
}

// ---------------- 全体 ----------------

@Composable
fun IdeaApp(vm: AppVm, requestMic: (() -> Unit) -> Unit, signIn: () -> Unit) {
    val names = listOf("録音", "カレンダー", "一覧", "設定")
    val icons = listOf(Icons.Filled.Mic, Icons.Filled.CalendarMonth, Icons.Filled.ListIcon, Icons.Filled.Settings)
    Scaffold(
        containerColor = Bg,
        bottomBar = {
            Column {
                if (vm.message.isNotEmpty()) {
                    Surface(
                        color = CardBg,
                        modifier = Modifier.fillMaxWidth().clickable { vm.say("") },
                    ) {
                        Text(
                            vm.message,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            color = Accent,
                            fontSize = 13.sp,
                        )
                    }
                }
                if (vm.tab == 0) TransportBar(vm)
                NavigationBar(containerColor = NavBg) {
                    for (i in 0 until 4) {
                        NavigationBarItem(
                            selected = vm.tab == i,
                            onClick = { vm.tab = i },
                            icon = { Icon(icons[i], contentDescription = names[i]) },
                            label = { Text(names[i], fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Accent,
                                selectedTextColor = Accent,
                                unselectedIconColor = Sub,
                                unselectedTextColor = Sub,
                                indicatorColor = Color.Transparent,
                            ),
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(Bg)) {
            when (vm.tab) {
                0 -> RecordScreen(vm, requestMic)
                1 -> CalendarScreen(vm)
                2 -> ListScreen(vm)
                else -> SettingsScreen(vm, requestMic, signIn)
            }
        }
    }
}

// ---------------- 録音画面 ----------------

@Composable
fun RecordScreen(vm: AppVm, requestMic: (() -> Unit) -> Unit) {
    var confirmNew by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                vm.currentLabel(),
                color = Fg,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val st = vm.streak()
            if (st > 0) Text("連続 " + st + " 日", color = Sub, fontSize = 13.sp)
        }
        for (i in 0 until N_TRACKS) TrackCard(vm, i, requestMic)
        SaveSection(vm, onNew = {
            if (vm.tracks.any { it.pcm != null }) confirmNew = true else vm.newIdea()
        })
    }
    if (confirmNew) {
        ConfirmDialog(
            "新しいアイデアを始めますか？", "保存していない録音は消えます。", "始める", "やめる",
            onYes = {
                confirmNew = false
                vm.newIdea()
            },
            onNo = { confirmNew = false },
        )
    }
}

@Composable
private fun TransportBar(vm: AppVm) {
    Column(
        Modifier.fillMaxWidth().background(NavBg).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(
                "再生", { vm.play() }, Modifier.weight(1.3f),
                icon = Icons.Filled.PlayArrow, filled = true, height = 44.dp,
            )
            PillButton(
                "停止", { if (vm.recIdx >= 0) vm.stopRec() else vm.stopAll() }, Modifier.weight(1.1f),
                icon = Icons.Filled.Stop, height = 44.dp,
            )
            Box(
                Modifier.weight(0.9f).height(44.dp).clip(RoundedCornerShape(10.dp)).background(CardBg),
                contentAlignment = Alignment.Center,
            ) {
                Text(fmt(vm.timeSec), color = Fg, fontSize = 18.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("マスター", color = Sub, fontSize = 12.sp)
            ThinSlider(vm.masterVol, { vm.setMaster(it) }, 0f, 1f, Modifier.weight(1f))
            Text((vm.masterVol * 100).toInt().toString() + "%", color = Sub, fontSize = 12.sp, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
        }
        LevelMeter(vm.level, vm.recIdx >= 0)
    }
}

@Composable
private fun LevelMeter(level: Float, active: Boolean) {
    val shown = if (active) Math.sqrt(level.toDouble()).toFloat().coerceIn(0f, 1f) else 0f
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("入力", color = Sub, fontSize = 12.sp)
        Canvas(Modifier.weight(1f).height(10.dp)) {
            val h = size.height
            val w = size.width
            drawRoundRect(Line, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2f, h / 2f))
            if (shown > 0.01f) {
                drawRoundRect(
                    if (shown > 0.92f) Red else Accent,
                    size = androidx.compose.ui.geometry.Size(w * shown, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2f, h / 2f),
                )
            }
        }
        Text(if (active && shown > 0.92f) "大きすぎ" else if (active) "録音中" else "待機", color = if (active && shown > 0.92f) Red else Sub, fontSize = 12.sp)
    }
}

@Composable
private fun TrackCard(vm: AppVm, i: Int, requestMic: (() -> Unit) -> Unit) {
    val t = vm.tracks[i]
    val recording = vm.recIdx == i
    var open by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val panText = if (t.pan < -0.04f) "左 " + (-t.pan * 100).toInt() else if (t.pan > 0.04f) "右 " + (t.pan * 100).toInt() else "中央"
    val fxOn = t.comp > 0.01f || t.dMix > 0.01f
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CardBg)
            .then(if (recording) Modifier.border(1.dp, Red, shape) else Modifier)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("トラック " + (i + 1), color = Fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Waveform(t.pcm, if (recording) Red else Wave, Modifier.weight(1f).height(36.dp))
            PillButton(
                "録音", { if (recording) vm.stopRec() else requestMic { vm.startRec(i) } },
                Modifier.width(78.dp),
                dot = true,
                color = if (recording) Red else Fg,
                borderColor = if (recording) Red else Outline,
                bg = if (recording) Red.copy(alpha = 0.18f) else Color.Transparent,
                height = 36.dp, fontSize = 13.sp,
            )
            PillButton(
                if (t.mute) "ミュート中" else "ミュート", { t.mute = !t.mute },
                Modifier.width(88.dp),
                color = if (t.mute) Red else Fg,
                borderColor = if (t.mute) Red else Outline,
                bg = if (t.mute) Red.copy(alpha = 0.18f) else Color.Transparent,
                height = 36.dp, fontSize = 13.sp,
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("音量", color = Sub, fontSize = 12.sp, modifier = Modifier.width(28.dp))
            ThinSlider(t.vol, { t.vol = it }, 0f, 1f, Modifier.weight(1f))
            Box(Modifier.width(44.dp))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("パン", color = Sub, fontSize = 12.sp, modifier = Modifier.width(28.dp))
            ThinSlider(
                t.pan,
                { t.pan = if (abs(it) < 0.06f) 0f else (it * 20f).roundToInt() / 20f },
                -1f, 1f, Modifier.weight(1f), fromCenter = true,
            )
            Text(panText, color = Sub, fontSize = 12.sp, modifier = Modifier.width(44.dp), textAlign = TextAlign.End)
        }
        Row(
            Modifier.clickable { open = !open }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null, tint = Sub, modifier = Modifier.size(18.dp),
            )
            Text("エフェクト", color = Sub, fontSize = 12.sp)
            if (fxOn) Text("オン", color = Accent, fontSize = 12.sp)
        }
        if (open) {
          Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF26323A)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            LabeledSlider(
                "コンプレッサー",
                if (t.comp > 0.01f) (t.comp * 100).toInt().toString() + "%" else "オフ",
                t.comp, 0f, 1f,
            ) { t.comp = (it * 20f).roundToInt() / 20f }
            LabeledSlider(
                "ディレイの量",
                if (t.dMix > 0.01f) (t.dMix * 100).toInt().toString() + "%" else "オフ",
                t.dMix, 0f, 1f,
            ) { t.dMix = (it * 20f).roundToInt() / 20f }
            LabeledSlider("ディレイの間隔", t.dMs.toInt().toString() + " ms", t.dMs, 50f, 800f) {
                t.dMs = (it / 10f).roundToInt() * 10f
            }
            LabeledSlider("ズレ補正（＋で音を早める）", t.adjMs.toInt().toString() + " ms", t.adjMs, -500f, 500f, fromCenter = true) {
                t.adjMs = (it / 5f).roundToInt() * 5f
            }
            Text(
                "エフェクトは再生とミックスにかかります。録音した元の音は変わりません。",
                color = Sub, fontSize = 11.sp,
            )
            PillButton(
                "このトラックを消去", { confirmClear = true }, Modifier.fillMaxWidth(),
                icon = Icons.Outlined.Delete, color = Red, borderColor = Red,
                height = 36.dp, fontSize = 13.sp,
                enabled = t.pcm != null && !recording,
            )
          }
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            "トラック" + (i + 1) + "を消去しますか？", null, "消去", "やめる",
            onYes = {
                t.pcm = null
                t.adjMs = 0f
                t.shiftMs = 0f
                confirmClear = false
            },
            onNo = { confirmClear = false },
        )
    }
}

@Composable
private fun SaveSection(vm: AppVm, onNew: () -> Unit) {
    var addTag by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("一言メモ", color = Sub, fontSize = 13.sp)
            AppField(vm.memo, { vm.memo = it.take(80) }, "例：夜のコード進行", Modifier.weight(1f))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (t in vm.allTags()) {
                TagChip("#" + t, vm.selTags.contains(t)) {
                    if (vm.selTags.contains(t)) vm.selTags.remove(t) else vm.selTags.add(t)
                }
            }
            TagChip("＋ タグ", false) { addTag = true }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("保存", { vm.save() }, Modifier.weight(1f), filled = true, height = 44.dp)
            PillButton("新しいアイデア", onNew, Modifier.weight(1f), height = 44.dp)
        }
    }
    if (addTag) {
        AlertDialog(
            onDismissRequest = { addTag = false },
            containerColor = CardBg,
            titleContentColor = Fg,
            title = { Text("新しいタグ", fontSize = 17.sp) },
            text = { AppField(newTag, { newTag = it.take(12) }, "タグの名前（12文字まで）", Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    vm.addTag(newTag)
                    newTag = ""
                    addTag = false
                }) { Text("追加", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { addTag = false }) { Text("やめる", color = Sub) } },
        )
    }
}

// ---------------- アイデアのカード ----------------

@Composable
private fun IdeaCard(vm: AppVm, m: Idea) {
    var confirmDel by remember { mutableStateOf(false) }
    val synced = m.drive != null && m.drive.at >= m.updatedAt
    val playing = vm.previewId == m.id
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardBg).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(whenStr(m.ts), color = Sub, fontSize = 13.sp)
            Text(
                m.tracks.count { it.has }.toString() + "トラック" + (if (synced) "・ドライブ済" else ""),
                color = Sub, fontSize = 13.sp,
            )
        }
        Text(
            if (m.memo.isEmpty()) "（メモなし）" else m.memo,
            color = Fg, fontSize = 17.sp, fontWeight = FontWeight.Bold,
        )
        if (m.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in m.tags) TagChip("#" + t, true, null)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(
                if (playing) "停止" else "聴く", { vm.preview(m.id) }, Modifier.weight(1f),
                icon = if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                filled = true, height = 38.dp,
            )
            PillButton(
                "開く", { vm.openIdea(m.id) }, Modifier.weight(1f),
                icon = Icons.Outlined.Description, height = 38.dp,
            )
            PillButton(
                "削除", { confirmDel = true }, Modifier.weight(1f),
                icon = Icons.Outlined.Delete, color = Red, borderColor = Red, height = 38.dp,
            )
        }
    }
    if (confirmDel) {
        ConfirmDialog(
            "このアイデアを削除しますか？", null, "削除", "やめる",
            onYes = {
                confirmDel = false
                vm.deleteIdea(m.id)
            },
            onNo = { confirmDel = false },
        )
    }
}

// ---------------- カレンダー ----------------

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(CardBg).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = Fg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Sub, fontSize = 11.sp)
    }
}

@Composable
fun CalendarScreen(vm: AppVm) {
    var ym by remember { mutableStateOf(YearMonth.now()) }
    var sel by remember { mutableStateOf(LocalDate.now().toString()) }
    val days = vm.ideas.map { it.day }.toSet()
    val today = LocalDate.now().toString()
    val prefix = ym.toString() + "-"
    val monthDays = days.count { it.startsWith(prefix) }
    val shape = RoundedCornerShape(14.dp)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat(vm.streak().toString() + "日", "連続日数", Modifier.weight(1f))
            Stat(monthDays.toString() + "日", "今月の録音日数", Modifier.weight(1f))
            Stat(vm.ideas.size.toString() + "件", "アイデア総数", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { ym = ym.minusMonths(1) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "前の月", tint = Fg)
            }
            Text(ym.year.toString() + "年" + ym.monthValue + "月", color = Fg, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { ym = ym.plusMonths(1) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "次の月", tint = Fg)
            }
        }
        val lead = ym.atDay(1).dayOfWeek.value % 7
        val len = ym.lengthOfMonth()
        val rows = (lead + len + 6) / 7
        val prevLen = ym.minusMonths(1).lengthOfMonth()
        Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, GridLine, shape)) {
            Row(Modifier.fillMaxWidth()) {
                for (w in listOf("日", "月", "火", "水", "木", "金", "土")) {
                    Box(Modifier.weight(1f).height(34.dp), contentAlignment = Alignment.Center) {
                        Text(w, color = Sub, fontSize = 12.sp)
                    }
                }
            }
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth()) {
                    for (c in 0 until 7) {
                        val d = r * 7 + c - lead + 1
                        val inMonth = d >= 1 && d <= len
                        val shown = if (d < 1) prevLen + d else if (d > len) d - len else d
                        val ds = if (inMonth) ym.atDay(d).toString() else ""
                        val isSel = inMonth && ds == sel
                        val isToday = inMonth && ds == today
                        val has = inMonth && days.contains(ds)
                        Box(
                            Modifier
                                .weight(1f)
                                .height(54.dp)
                                .border(0.5.dp, GridLine)
                                .then(if (inMonth) Modifier.clickable { sel = ds } else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(if (isSel) Accent else Color.Transparent)
                                        .then(if (isToday && !isSel) Modifier.border(1.dp, Accent, CircleShape) else Modifier),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        shown.toString(),
                                        color = if (isSel) OnAccent else if (inMonth) Fg else Dim,
                                        fontSize = 15.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                                Box(
                                    Modifier
                                        .padding(top = 2.dp)
                                        .size(5.dp)
                                        .clip(CircleShape)
                                        .background(if (has) Accent else Color.Transparent),
                                )
                            }
                        }
                    }
                }
            }
        }
        val parts = sel.split("-")
        val title = if (parts.size == 3) parts[1].toInt().toString() + "月" + parts[2].toInt() + "日のアイデア" else "アイデア"
        Text(title, color = Fg, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        val list = vm.ideas.filter { it.day == sel }
        if (list.isEmpty()) {
            Text("この日の録音はありません。", color = Sub, fontSize = 13.sp)
        }
        for (m in list) IdeaCard(vm, m)
    }
}

// ---------------- 一覧 ----------------

@Composable
fun ListScreen(vm: AppVm) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    val used = vm.ideas.flatMap { it.tags }.distinct()
    val list = vm.ideas.filter { m ->
        (filter.isEmpty() || m.tags.contains(filter)) &&
            (query.isEmpty() || m.memo.contains(query, ignoreCase = true))
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("メモで検索", color = Fg, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        AppField(query, { query = it }, "キーワードを入力", Modifier.fillMaxWidth(), leading = Icons.Filled.Search)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPill("すべて", filter.isEmpty()) { filter = "" }
            for (t in used) FilterPill(t, filter == t) { filter = t }
        }
        if (list.isEmpty()) {
            Text(
                if (vm.ideas.isEmpty()) "まだ保存したアイデアがありません。録音画面で保存すると、ここに並びます。" else "条件に合うアイデアがありません。",
                color = Sub, fontSize = 13.sp,
            )
        }
        for (m in list) IdeaCard(vm, m)
    }
}

// ---------------- 設定 ----------------

private fun typeName(t: Int): String = when (t) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "本体のマイク"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "本体のスピーカー"
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "受話口"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有線ヘッドセット"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有線ヘッドホン"
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth（通話）"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth（音楽）"
    else -> "その他"
}

private fun devLabel(d: AudioDeviceInfo): String {
    val n = d.productName.toString()
    return typeName(d.type) + (if (n.isNotEmpty()) "（" + n + "）" else "")
}

@Composable
private fun DropdownBox(
    current: String,
    items: List<Pair<String, Int>>,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(FieldBg)
                .border(1.dp, Line, shape)
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(current, color = Fg, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Sub)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (item in items) {
                DropdownMenuItem(
                    text = { Text(item.first) },
                    onClick = {
                        onSelect(item.second)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardBg).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, color = Fg, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
fun SettingsScreen(vm: AppVm, requestMic: (() -> Unit) -> Unit, signIn: () -> Unit) {
    LaunchedEffect(Unit) { vm.refreshDevices() }
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Panel("入力と出力") {
            val inItems = listOf(Pair("自動（本体のマイク）", -1)) + vm.inputs.map { Pair(devLabel(it), it.id) }
            val outItems = listOf(Pair("自動（イヤホン優先）", -1)) + vm.outputs.map { Pair(devLabel(it), it.id) }
            val inCur = vm.inputs.firstOrNull { it.id == vm.inDevId }
            val outCur = vm.outputs.firstOrNull { it.id == vm.outDevId }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("録音に使うマイク", color = Fg, fontSize = 13.sp, modifier = Modifier.weight(0.9f))
                DropdownBox(
                    if (inCur != null) devLabel(inCur) else "自動（本体のマイク）",
                    inItems, { vm.chooseInput(it) }, Modifier.weight(1.3f),
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("再生に使う出力", color = Fg, fontSize = 13.sp, modifier = Modifier.weight(0.9f))
                DropdownBox(
                    if (outCur != null) devLabel(outCur) else "自動（イヤホン優先）",
                    outItems, { vm.chooseOutput(it) }, Modifier.weight(1.3f),
                )
            }
            Text("スマートフォンのマイクを使って録音します。", color = Sub, fontSize = 12.sp)
            PillButton(
                "接続機器を再読み込み", { vm.refreshDevices() }, Modifier.fillMaxWidth(),
                icon = Icons.Filled.Refresh, height = 44.dp,
            )
        }
        Panel("遅延の補正") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PillButton("遅延を測る", { requestMic { vm.measureLatency() } }, Modifier.width(140.dp), filled = true, height = 44.dp)
                Text(
                    if (vm.latencyMs > 0f) "測定済み：" + vm.latencyMs.toInt() + " ms" else "未測定",
                    color = Fg, fontSize = 14.sp,
                )
            }
            Text("静かな場所で、スマホのスピーカーの音量を上げて測ります。", color = Sub, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("イヤホン使用時の追加補正（ms）", color = Fg, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Row(
                    Modifier.clip(shape).background(FieldBg).border(1.dp, Line, shape),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(44.dp).clickable { vm.setExtra((vm.extraMs - 5f).coerceIn(-500f, 1000f)) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Remove, contentDescription = "減らす", tint = Fg) }
                    Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) {
                        Text(vm.extraMs.toInt().toString(), color = Fg, fontSize = 15.sp, textAlign = TextAlign.Center)
                    }
                    Box(
                        Modifier.size(44.dp).clickable { vm.setExtra((vm.extraMs + 5f).coerceIn(-500f, 1000f)) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Add, contentDescription = "増やす", tint = Fg) }
                }
            }
        }
        Panel("Google ドライブ") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Cloud, contentDescription = null, tint = Fg, modifier = Modifier.size(36.dp))
                PillButton(
                    if (vm.syncing) "同期中…" else "ドライブと同期", { signIn() }, Modifier.weight(1f),
                    height = 44.dp, enabled = !vm.syncing,
                )
            }
            if (vm.syncStatus.isNotEmpty()) Text(vm.syncStatus, color = Accent, fontSize = 13.sp)
            Text(
                "録音した各トラックの WAV ファイルとミックスを Google ドライブにバックアップします。",
                color = Sub, fontSize = 12.sp,
            )
        }
    }
}
