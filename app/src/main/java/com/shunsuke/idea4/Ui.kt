@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.shunsuke.idea4

import android.media.AudioDeviceInfo
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.roundToInt

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF4FD1B8),
    onPrimary = Color(0xFF06231E),
    background = Color(0xFF0F1719),
    onBackground = Color(0xFFE8E4D8),
    surface = Color(0xFF172326),
    onSurface = Color(0xFFE8E4D8),
    surfaceVariant = Color(0xFF172326),
    onSurfaceVariant = Color(0xFF8FA0A0),
    secondaryContainer = Color(0xFF27373B),
    onSecondaryContainer = Color(0xFFE8E4D8),
    error = Color(0xFFFF5A4D),
    onError = Color(0xFFFFFFFF),
    outline = Color(0xFF27373B),
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

@Composable
fun IdeaApp(vm: AppVm, requestMic: (() -> Unit) -> Unit, signIn: () -> Unit) {
    val icons = listOf("●", "▦", "≡", "⚙")
    val names = listOf("録音", "カレンダー", "一覧", "設定")
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column {
                if (vm.message.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().clickable { vm.say("") },
                    ) {
                        Text(
                            vm.message,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 13.sp,
                        )
                    }
                }
                NavigationBar {
                    for (i in 0 until 4) {
                        NavigationBarItem(
                            selected = vm.tab == i,
                            onClick = { vm.tab = i },
                            icon = { Text(icons[i]) },
                            label = { Text(names[i], fontSize = 11.sp) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                vm.currentLabel(),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            val st = vm.streak()
            if (st > 0) Text("連続 " + st + " 日", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.play() }) { Text("▶ 再生") }
            OutlinedButton(onClick = { if (vm.recIdx >= 0) vm.stopRec() else vm.stopAll() }) { Text("■ 停止") }
            Text(fmt(vm.timeSec), fontSize = 22.sp)
        }
        for (i in 0 until N_TRACKS) TrackCard(vm, i, requestMic)
        SaveCard(vm, onNew = {
            if (vm.tracks.any { it.pcm != null }) confirmNew = true else vm.newIdea()
        })
    }
    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            title = { Text("新しいアイデアを始めますか？") },
            text = { Text("保存していない録音は消えます。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmNew = false
                    vm.newIdea()
                }) { Text("始める") }
            },
            dismissButton = { TextButton(onClick = { confirmNew = false }) { Text("やめる") } },
        )
    }
}

@Composable
fun TrackCard(vm: AppVm, i: Int, requestMic: (() -> Unit) -> Unit) {
    val t = vm.tracks[i]
    val recording = vm.recIdx == i
    var confirmClear by remember { mutableStateOf(false) }
    var fxOpen by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        border = if (recording) BorderStroke(1.dp, MaterialTheme.colorScheme.error) else null,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text((i + 1).toString(), fontWeight = FontWeight.Bold)
                Waveform(t.pcm, Modifier.weight(1f).height(48.dp))
            }
            Text("音量", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = t.vol, onValueChange = { t.vol = it }, valueRange = 0f..1f)
            Text(
                "ズレ補正 " + t.adjMs.toInt() + " ms（＋で音を早める）",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = t.adjMs,
                onValueChange = { t.adjMs = (it / 5f).roundToInt() * 5f },
                valueRange = -500f..500f,
            )
            val panText = if (t.pan < -0.04f) "左 " + (-t.pan * 100).toInt() else if (t.pan > 0.04f) "右 " + (t.pan * 100).toInt() else "中央"
            Text("パン " + panText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = t.pan,
                onValueChange = { t.pan = if (abs(it) < 0.06f) 0f else (it * 20f).roundToInt() / 20f },
                valueRange = -1f..1f,
            )
            TextButton(onClick = { fxOpen = !fxOpen }) {
                val on = t.comp > 0.01f || t.dMix > 0.01f
                Text("エフェクト " + (if (fxOpen) "▲" else "▼") + (if (on) "（オン）" else ""), fontSize = 13.sp)
            }
            if (fxOpen) {
                Text(
                    "コンプレッサー " + (if (t.comp > 0.01f) (t.comp * 100).toInt().toString() + "%" else "オフ"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(value = t.comp, onValueChange = { t.comp = (it * 20f).roundToInt() / 20f }, valueRange = 0f..1f)
                Text(
                    "ディレイの量 " + (if (t.dMix > 0.01f) (t.dMix * 100).toInt().toString() + "%" else "オフ"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(value = t.dMix, onValueChange = { t.dMix = (it * 20f).roundToInt() / 20f }, valueRange = 0f..1f)
                Text(
                    "ディレイの間隔 " + t.dMs.toInt() + " ms",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(value = t.dMs, onValueChange = { t.dMs = (it / 10f).roundToInt() * 10f }, valueRange = 50f..800f)
                Text(
                    "エフェクトは再生とミックスにかかります。録音した元の音は変わりません。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { if (recording) vm.stopRec() else requestMic { vm.startRec(i) } },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = if (recording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.weight(1f),
                ) { Text(if (recording) "■ 停止" else "● 録音") }
                OutlinedButton(onClick = { t.mute = !t.mute }, modifier = Modifier.weight(1f)) {
                    Text(if (t.mute) "ミュート中" else "ミュート", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = { confirmClear = true },
                    enabled = t.pcm != null && !recording,
                    modifier = Modifier.weight(1f),
                ) { Text("消去") }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("トラック" + (i + 1) + "を消去しますか？") },
            confirmButton = {
                TextButton(onClick = {
                    t.pcm = null
                    t.adjMs = 0f
                    t.shiftMs = 0f
                    confirmClear = false
                }) { Text("消去") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("やめる") } },
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
fun Waveform(pcm: ShortArray?, modifier: Modifier) {
    val peaks = remember(pcm) { peaksOf(pcm, 200) }
    val line = MaterialTheme.colorScheme.outline
    val wave = Color(0xFF7FB8AD)
    Canvas(modifier.background(MaterialTheme.colorScheme.background)) {
        val h = size.height
        val w = size.width
        drawLine(line, Offset(0f, h / 2f), Offset(w, h / 2f))
        if (peaks.isNotEmpty()) {
            val step = w / peaks.size
            for (x in peaks.indices) {
                val px = x * step + step / 2f
                val p = peaks[x]
                drawLine(
                    wave,
                    Offset(px, h / 2f - p * h / 2f),
                    Offset(px, h / 2f + p * h / 2f),
                    strokeWidth = maxOf(1f, step * 0.7f),
                )
            }
        }
    }
}

@Composable
fun SaveCard(vm: AppVm, onNew: () -> Unit) {
    var newTag by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("保存", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = vm.memo,
                onValueChange = { vm.memo = it.take(80) },
                label = { Text("一言メモ") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in vm.allTags()) {
                    FilterChip(
                        selected = vm.selTags.contains(t),
                        onClick = { if (vm.selTags.contains(t)) vm.selTags.remove(t) else vm.selTags.add(t) },
                        label = { Text(t) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it.take(12) },
                    label = { Text("新しいタグ") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = {
                    vm.addTag(newTag)
                    newTag = ""
                }) { Text("追加") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.save() }, modifier = Modifier.weight(1f)) { Text("保存") }
                OutlinedButton(onClick = onNew, modifier = Modifier.weight(1f)) { Text("新しいアイデア") }
            }
        }
    }
}

// ---------------- アイデアのカード ----------------

@Composable
fun IdeaCard(vm: AppVm, m: Idea) {
    var confirmDel by remember { mutableStateOf(false) }
    val synced = m.drive != null && m.drive.at >= m.updatedAt
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(whenStr(m.ts))
                Text(
                    m.tracks.count { it.has }.toString() + "トラック" + (if (synced) "・ドライブ済" else ""),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(if (m.memo.isEmpty()) "（メモなし）" else m.memo)
            if (m.tags.isNotEmpty()) {
                Text(
                    m.tags.joinToString("  ") { "#" + it },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.preview(m.id) }, modifier = Modifier.weight(1f)) {
                    Text(if (vm.previewId == m.id) "■ 停止" else "▶ 聴く", fontSize = 13.sp)
                }
                OutlinedButton(onClick = { vm.openIdea(m.id) }, modifier = Modifier.weight(1f)) {
                    Text("開く", fontSize = 13.sp)
                }
                OutlinedButton(onClick = { confirmDel = true }, modifier = Modifier.weight(1f)) {
                    Text("削除", fontSize = 13.sp)
                }
            }
        }
    }
    if (confirmDel) {
        AlertDialog(
            onDismissRequest = { confirmDel = false },
            title = { Text("このアイデアを削除しますか？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDel = false
                    vm.deleteIdea(m.id)
                }) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { confirmDel = false }) { Text("やめる") } },
        )
    }
}

// ---------------- カレンダー ----------------

@Composable
fun Stat(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("連続日数", vm.streak().toString(), Modifier.weight(1f))
            Stat("今月の録音日数", monthDays.toString(), Modifier.weight(1f))
            Stat("アイデア総数", vm.ideas.size.toString(), Modifier.weight(1f))
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { ym = ym.minusMonths(1) }) { Text("◀") }
            Text(ym.year.toString() + "年" + ym.monthValue + "月", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { ym = ym.plusMonths(1) }) { Text("▶") }
        }
        Row(Modifier.fillMaxWidth()) {
            for (w in listOf("日", "月", "火", "水", "木", "金", "土")) {
                Text(
                    w,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val lead = ym.atDay(1).dayOfWeek.value % 7
        val len = ym.lengthOfMonth()
        val rows = (lead + len + 6) / 7
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val d = r * 7 + c - lead + 1
                    if (d < 1 || d > len) {
                        Box(Modifier.weight(1f).height(48.dp))
                    } else {
                        val ds = ym.atDay(d).toString()
                        val isSel = ds == sel
                        val has = days.contains(ds)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .padding(2.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) primary else Color.Transparent)
                                .border(
                                    if (ds == today) 1.dp else 0.dp,
                                    if (ds == today) MaterialTheme.colorScheme.onBackground else Color.Transparent,
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable { sel = ds },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(d.toString(), color = if (isSel) onPrimary else MaterialTheme.colorScheme.onBackground)
                                if (has) {
                                    Box(
                                        Modifier.size(6.dp).clip(CircleShape)
                                            .background(if (isSel) onPrimary else primary),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        val parts = sel.split("-")
        val title = if (parts.size == 3) parts[1].toInt().toString() + "月" + parts[2].toInt() + "日のアイデア" else "アイデア"
        Text(title, fontWeight = FontWeight.Bold)
        val list = vm.ideas.filter { it.day == sel }
        if (list.isEmpty()) {
            Text("この日の録音はありません。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("メモで検索") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = filter.isEmpty(), onClick = { filter = "" }, label = { Text("すべて") })
            for (t in used) {
                FilterChip(selected = filter == t, onClick = { filter = t }, label = { Text(t) })
            }
        }
        if (list.isEmpty()) {
            Text(
                if (vm.ideas.isEmpty()) "まだ保存したアイデアがありません。録音画面で保存すると、ここに並びます。" else "条件に合うアイデアがありません。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
fun DevicePicker(
    title: String,
    autoLabel: String,
    devices: List<AudioDeviceInfo>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val cur = devices.firstOrNull { it.id == selectedId }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (cur != null) devLabel(cur) else autoLabel)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text(autoLabel) }, onClick = {
                    onSelect(-1)
                    open = false
                })
                for (d in devices) {
                    DropdownMenuItem(text = { Text(devLabel(d)) }, onClick = {
                        onSelect(d.id)
                        open = false
                    })
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: AppVm, requestMic: (() -> Unit) -> Unit, signIn: () -> Unit) {
    LaunchedEffect(Unit) { vm.refreshDevices() }
    var extraText by remember { mutableStateOf(vm.extraMs.toInt().toString()) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("入力と出力", fontWeight = FontWeight.Bold)
        DevicePicker("録音に使うマイク", "自動（本体のマイク）", vm.inputs, vm.inDevId) { vm.chooseInput(it) }
        DevicePicker("再生に使う出力", "自動（つないだイヤホン、なければスピーカー）", vm.outputs, vm.outDevId) { vm.chooseOutput(it) }
        Text(
            "USBやBluetoothのイヤホンをつけても、録音は本体のマイクを使います。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { vm.refreshDevices() }) { Text("接続機器を再読み込み") }

        Text("遅延の補正", fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { requestMic { vm.measureLatency() } }) { Text("遅延を測る") }
            Text(if (vm.latencyMs > 0f) "測定済み：" + vm.latencyMs.toInt() + " ms" else "未測定")
        }
        Text(
            "スマホのスピーカーからビープ音を鳴らして測ります。静かな場所で、音量を上げて行ってください。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = extraText,
            onValueChange = {
                extraText = it.filter { c -> c.isDigit() || c == '-' }.take(5)
                val v = extraText.toIntOrNull()
                if (v != null) vm.setExtra(v.toFloat())
            },
            label = { Text("イヤホン使用時の追加補正（ms）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "測定値に足す値です。Bluetoothイヤホンなど、遅れが大きい機器を使うときに調整します。次に録音するトラックから反映されます。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Googleドライブ", fontWeight = FontWeight.Bold)
        Button(onClick = { signIn() }, enabled = !vm.syncing) { Text("ドライブと同期") }
        if (vm.syncStatus.isNotEmpty()) Text(vm.syncStatus, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        Text(
            "ドライブの「アイデア録音」フォルダに、トラックごとのWAV、ミックス、メモを保存します。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
