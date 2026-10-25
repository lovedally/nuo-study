package com.nuostudy.feature.english

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.nuostudy.core.network.ChatTurn
import com.nuostudy.core.network.CurriculumUnitDto
import com.nuostudy.core.network.SoeResult
import com.nuostudy.core.network.SoeWord
import kotlinx.coroutines.delay

private val green = Color(0xFF2D725E)
private val lime = Color(0xFFDDECA4)
private val cream = Color(0xFFF7F4EA)
private val coral = Color(0xFFFF8E68)
private val ink = Color(0xFF24352E)
private val red = Color(0xFFC0392B)

private enum class Page(val title: String, val mark: String) {
    Today("今日学习", "⌂"),
    Course("同步课程", "▤"),
    Words("单词卡片", "Aa"),
    Quiz("句型挑战", "✓"),
    Speaking("AI口语", "●"),
    Mistakes("错题本", "✎"),
    Report("学习报告", "◫"),
}

/**
 * 「听标准示范」在单元没有句型时兜底的那一句。
 *
 * 正常情况下句子的来源是 `unit.sentences`，与跟读评测的参考文本是同一批 ——
 * 两处必须一致，否则孩子听到的示范和他被评测的那句话对不上，分数会莫名偏低。
 */
private const val FALLBACK_SENTENCE = "You should tell the truth and admit your mistake."

/**
 * 对话空状态里推荐的三个问题。
 *
 * 挑的都是「三年级能听懂、一句话就能答」的，而且**不带中文** ——
 * 混中文会让千问也用中文回，孩子听到的就不是英文输入了。
 */
private val CHAT_SUGGESTIONS = listOf(
    "Tell me about your school day.",
    "What's your favourite animal?",
    "Can you teach me a new word?",
)

@Composable
fun EnglishScreen(
    modifier: Modifier = Modifier,
    onSelectSubject: (Int) -> Unit = {},
    vm: EnglishViewModel = hiltViewModel(),
) {
    var page by rememberSaveable { mutableStateOf(Page.Today) }
    var unitNo by rememberSaveable { mutableIntStateOf(1) }

    val units = vm.units
    // 单元是纯派生值：用 remember 包住，避免每次重组都线性查一遍。
    val unit = remember(units, unitNo) {
        units.firstOrNull { it.unit == unitNo } ?: units.firstOrNull()
    }

    // 离开英语页（切到别的学科）时把攒着的学情发出去。
    // 此刻 ViewModel 还没销毁、协程还能跑；等 onCleared 就来不及了。
    DisposableEffect(Unit) {
        onDispose { vm.flushProgress() }
    }

    // 提示是一次性的：显示 4 秒后自己消失。不做 Snackbar 是因为这里没有
    // Scaffold（整页是自绘的侧边栏布局），为了一个提示去套 Scaffold 不划算。
    LaunchedEffect(vm.notice) {
        if (vm.notice.isNotBlank()) {
            delay(4000)
            vm.consumeNotice()
        }
    }

    Row(modifier.fillMaxSize().background(cream)) {
        Navigation(page, onSelectSubject) { page = it }
        CompositionLocalProvider(LocalContentColor provides ink) {
            /*
             * ⚠️ 这里**只写 weight(1f)**，不要再加 fillMaxSize()。
             *    weight 已经给了确定约束，再 fillMaxSize 会让这一层多做一次
             *    尺寸对齐 —— 在 RK3566 这类设备上是每帧都要付的。
             */
            Box(Modifier.weight(1f)) {
                when {
                    unit != null -> when (page) {
                        Page.Today -> TodayPage(unit, onOpen = { page = it })
                        // 传相对路径进来，由 ViewModel 拼上家长在设置页填的服务端地址。
                        // 这里**不能**再出现写死的 host —— 融合前那行
                        // `const val SERVER = "http://192.168.1.10:4173"` 正是要删的东西。
                        Page.Course -> CoursePage(
                            unit = unit,
                            units = units,
                            onPickUnit = { unitNo = it },
                            play = vm::playRelative,
                            speak = vm::speak,
                        )
                        Page.Words -> WordsPage(
                            unit = unit,
                            units = units,
                            onPickUnit = { unitNo = it },
                            onSeen = { en, known ->
                                vm.markWordSeen(unit.unit, en)
                                if (known) vm.markWordKnown(unit.unit)
                            },
                        )
                        Page.Quiz -> QuizPage(
                            unit = unit,
                            onRecord = vm::recordMistake,
                            onAnswer = { ok -> vm.recordAnswer(unit.unit, ok) },
                        )
                        // 这一页要读的状态很多（录音中/上传中/评测结果/对话记录），
                        // 一个个回调传下去会变成十几个参数，直接给 vm 更清楚。
                        Page.Speaking -> SpeakingPage(unit = unit, vm = vm)
                        Page.Mistakes -> MistakesPage(vm.mistakes)
                        Page.Report -> ReportPage(vm)
                    }
                    else -> CurriculumPlaceholder(
                        error = vm.curriculumError,
                        loading = vm.curriculumLoading,
                        onRetry = { vm.loadCurriculum(force = true) },
                    )
                }

                if (vm.notice.isNotBlank()) {
                    Surface(
                        color = coral,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
                    ) {
                        Text(vm.notice, color = Color.White, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
                    }
                }
            }
        }
    }
}

@Composable private fun Navigation(selected: Page, selectSubject: (Int) -> Unit, choose: (Page) -> Unit) {
    Column(
        // padding 16 → 14：侧栏可用高度实测只有 ~511dp（顶栏占掉一部分），
        // 这 2dp×2 是「7 项全部首屏可见」的最后一点余量。
        Modifier.width(196.dp).fillMaxSize().background(green).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(6.dp, 0.dp, 6.dp, 6.dp)) {
            Box(Modifier.size(36.dp).background(lime, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                Text("E", color = green, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            }
            Column(Modifier.padding(start = 10.dp)) {
                Text("英语学习", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("三年级上册", color = Color.White.copy(alpha = .66f), style = MaterialTheme.typography.bodySmall)
            }
        }

        /* ⚠️ 菜单必须**可滚动**，且要 `weight(1f)` 而不是 `Spacer(weight(1f))` 挤在下面。
         *
         * 实测（8 寸 1280x800 / density 240 → 可用高度只有 533dp）：菜单项
         * 各 52dp + 间隔 8dp，加上 96dp 的标题和 110dp 的「切换学科」，
         * 六项时就已经 566dp > 533dp —— 最后一项「学习报告」被挤到屏幕外。
         * uiautomator dump 里它的 bounds 直接是 [0,0][0,0]（等于没布局），
         * 界面上不是"被裁一半"而是**整个消失**，孩子根本点不到。
         *
         * 现在的结构：标题固定 → 菜单占满剩余高度并可滚动 → 切换学科钉在底部。
         * 屏幕再矮也只是「菜单要滑一下」，不会丢功能。
         *
         * 2026-10-23 接课程数据后菜单变成 7 项（多了「句型挑战」），于是又超了一次。
         * 这次不是靠继续缩小，而是把每项从 52dp 收到 48dp、间距 8→6：
         * 7×48 + 6×6 = 372dp，加上压缩后的标题（50）与底部（38）总共约 518dp < 533dp，
         * 常见平板上 7 项**全部首屏可见**，不需要滑。
         * ⚠️ 48dp 是触摸目标的下限，不要再往下收 —— 手小的孩子会点不准。
         */
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Page.entries.forEach { page ->
                val active = page == selected
                Surface(
                    onClick = { choose(page) },
                    shape = RoundedCornerShape(15.dp),
                    color = if (active) Color.White else Color.Transparent,
                ) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(34.dp), contentAlignment = Alignment.CenterStart) {
                            Text(page.mark, color = if (active) green else Color.White.copy(.7f), fontWeight = FontWeight.Bold)
                        }
                        Text(
                            page.title,
                            color = if (active) green else Color.White.copy(.82f),
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = .16f), modifier = Modifier.padding(top = 6.dp))
        // 这行「切换学科」的小标题删掉了（2026-10-23）：下面两个按钮写着「语文 / 数学」，
        // 标题是纯冗余，而侧栏在 8 寸平板上每一 dp 都要抢。省下的 16dp 正好让
        // 第 7 项「学习报告」完整露出，不用滑。
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Surface(onClick = { selectSubject(0) }, shape = RoundedCornerShape(12.dp), color = Color.White.copy(.1f), modifier = Modifier.weight(1f)) {
                Box(Modifier.height(38.dp), contentAlignment = Alignment.Center) { Text("语文", color = Color.White) }
            }
            Surface(onClick = { selectSubject(1) }, shape = RoundedCornerShape(12.dp), color = Color.White.copy(.1f), modifier = Modifier.weight(1f)) {
                Box(Modifier.height(38.dp), contentAlignment = Alignment.Center) { Text("数学", color = Color.White) }
            }
        }
    }
}

// ---------------------------------------------------------------- 今日

@Composable private fun TodayPage(unit: CurriculumUnitDto, onOpen: (Page) -> Unit) {
    // 大写转换每次重组都会新建字符串，包一层
    val unitLabel = remember(unit.unit, unit.title) { "UNIT ${unit.unit} · ${unit.title.uppercase()}" }
    val coreWords = remember(unit) { unit.words.count { it.core } }
    val quizCount = remember(unit) { unit.quiz.size }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(Color(0xFFE7F0C0)), shape = RoundedCornerShape(28.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(unitLabel, color = green, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            unit.titleZh.ifBlank { "今天进步一点点" },
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(vertical = 5.dp),
                            maxLines = 1,
                        )
                        Text(
                            unit.bigQuestion.ifBlank { "预计 15 分钟 · 单词热身 → 故事跟读 → 口语挑战" },
                            color = ink.copy(.68f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button(
                        onClick = { onOpen(Page.Words) },
                        colors = ButtonDefaults.buttonColors(containerColor = green, contentColor = Color.White),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.height(58.dp),
                    ) { Text("开始学习  →", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp)) }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text("今日任务", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Unit ${unit.unit}", color = green, fontWeight = FontWeight.Bold)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Task("Aa", "单词热身", "$coreWords 个核心词", coral, Modifier.weight(1f)) { onOpen(Page.Words) }
                Task("▶", "故事跟读", unit.passage?.title?.ifBlank { "听原音并跟读" } ?: "听原音并跟读", Color(0xFFDCECF2), Modifier.weight(1f)) { onOpen(Page.Course) }
                Task("✓", "句型挑战", "$quizCount 道题", Color(0xFFFFF1BC), Modifier.weight(1f)) { onOpen(Page.Quiz) }
                Task("●", "AI 口语", "开口说英语", lime, Modifier.weight(1f)) { onOpen(Page.Speaking) }
            }
        }
    }
}

@Composable private fun Task(mark: String, title: String, desc: String, tint: Color, modifier: Modifier, action: () -> Unit) {
    Card(modifier.clickable(onClick = action), colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Box(Modifier.size(42.dp).background(tint, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                Text(mark, fontWeight = FontWeight.Bold)
            }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            Text(desc, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------- 同步课程

@Composable private fun CoursePage(
    unit: CurriculumUnitDto,
    units: List<CurriculumUnitDto>,
    onPickUnit: (Int) -> Unit,
    play: (String) -> Unit,
    speak: (String) -> Unit,
) {
    var lesson by rememberSaveable(unit.unit) { mutableIntStateOf(1) }
    var expanded by rememberSaveable(unit.unit) { mutableStateOf(false) }

    val passage = unit.passage
    // 课文先露 8 行，其余收在「展开」里 —— 有的单元 33 行，全铺开孩子找不到重点。
    val shownLines = remember(passage, expanded) {
        val lines = passage?.lines.orEmpty()
        if (expanded) lines else lines.take(8)
    }

    LazyColumn(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column {
                Text("同步课程", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${unit.module} · Unit ${unit.unit}",
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item { UnitPills(units, unit.unit, onPickUnit) }

        // ---- 课文 ----
        if (passage != null && shownLines.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(22.dp)) {
                        Text("STORY TIME · UNIT ${unit.unit}", color = green, fontWeight = FontWeight.Bold)
                        Text(
                            passage.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                        Text("点任意一句听发音", color = ink.copy(alpha = .45f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            // 逐句拆成独立 item：一是滚动时只组合可见行，二是点读的目标明确到句。
            itemsIndexed(shownLines) { _, line ->
                Surface(
                    onClick = { speak(line) },
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🔊", modifier = Modifier.padding(end = 12.dp))
                        Text(line, color = ink, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (passage.lines.size > 8) {
                item {
                    OutlinedButton(
                        onClick = { expanded = !expanded },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = green),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (expanded) "收起课文" else "展开全部 ${passage.lines.size} 句") }
                }
            }
        }

        // ---- 配套录音 ----
        item {
            Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(22.dp)) {
                /* ⚠️ 这个卡片必须是**竖排**，别再退回「左边课时按钮 + 右边播放按钮」的横排。
                 *
                 * 实测（1280x800 / density 240 → 853x533dp）：横排版本里 5 个课时按钮
                 * 和播放按钮抢同一行的宽度，结果
                 *   · 第 5 个「检测」被压到 72px 宽（其他 148px），文字排不进去 → 竖着挤出框外；
                 *   · 播放按钮 bounds [1043,516][1211,588] 与它 [981,567][1053,667] 直接重叠。
                 * 这是 Row 里子项都有最小固有宽度、没有谁肯让步时的必然结果。
                 *
                 * 现在的结构：标题 → 课时按钮（每行 3 个，自动折行）→ 播放按钮独占一行。
                 * 窄屏上只是多占一行的纵向空间，不会再有控件互相压。
                 */
                Column(Modifier.fillMaxWidth().padding(22.dp)) {
                    Text("UNIT ${unit.unit} · 配套原音", color = green, fontWeight = FontWeight.Bold)
                    Text(
                        "选择课时，播放课课练录音",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    // chunked(3)：每行 3 个。用 weight(1f) 让每个按钮等宽，
                    // 「检测」和「课时 1」一样宽，不会再被挤成竖排。
                    listOf(1, 2, 3, 4, 5).chunked(3).forEach { group ->
                        Row(
                            Modifier.fillMaxWidth().padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            group.forEach { p ->
                                OutlinedButton(
                                    onClick = { lesson = p },
                                    modifier = Modifier.weight(1f),
                                    colors = if (p == lesson)
                                        ButtonDefaults.outlinedButtonColors(
                                            containerColor = lime, contentColor = green,
                                        )
                                    else ButtonDefaults.outlinedButtonColors(contentColor = ink),
                                ) { Text(if (p == 5) "检测" else "课时$p", maxLines = 1) }
                            }
                            // 最后一行只有 2 个 → 补等宽空位，避免按钮被拉宽
                            repeat(3 - group.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }

                    val track = (unit.unit - 1) * 5 + lesson
                    Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = { play("audio/track-${track.toString().padStart(2, '0')}.mp3") },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = green, contentColor = Color.White,
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("▶ 播放") }
                        // 让家长一眼知道现在放的是第几段（对应下拉里的 01~40）
                        Text(
                            "第 ${track.toString().padStart(2, '0')} 段",
                            color = ink.copy(alpha = .55f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 单词卡片

@Composable private fun WordsPage(
    unit: CurriculumUnitDto,
    units: List<CurriculumUnitDto>,
    onPickUnit: (Int) -> Unit,
    /** 参数二 = 孩子是否点了「认识」。 */
    onSeen: (String, Boolean) -> Unit,
) {
    var index by rememberSaveable(unit.unit) { mutableIntStateOf(0) }
    var flip by rememberSaveable(unit.unit) { mutableStateOf(false) }

    val words = unit.words
    if (words.isEmpty()) {
        EmptyPage("单词卡片", "Unit ${unit.unit} 的单词还没录入。")
        return
    }
    // index 可能因为换单元而越界（rememberSaveable 的 key 是 unit.unit，正常会重置，
    // 但服务端课程改过之后同一个 unit 的 word 数可能变少）
    val safeIndex = index.coerceIn(0, words.lastIndex)
    val word = words[safeIndex]

    // 显示到哪一个词就记一个 —— 学习报告里的「翻过的词」必须是真数出来的
    LaunchedEffect(word.en) { onSeen(word.en, false) }

    Column(Modifier.fillMaxSize().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.fillMaxWidth()) {
            Text("单词卡片", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Unit ${unit.unit} · ${unit.title} · ${safeIndex + 1}/${words.size}",
                color = Color.Gray,
                modifier = Modifier.padding(top = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(12.dp))
        UnitPills(units, unit.unit, onPickUnit)
        Spacer(Modifier.height(14.dp))

        Card(
            Modifier.fillMaxWidth(.78f).weight(1f).clickable { flip = !flip },
            colors = CardDefaults.cardColors(if (flip) lime else Color.White),
            shape = RoundedCornerShape(26.dp),
        ) {
            Column(
                Modifier.fillMaxSize().padding(30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (flip) {
                    Text(word.zh, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, maxLines = 2)
                    if (word.example.isNotBlank()) {
                        Text(
                            word.example,
                            style = MaterialTheme.typography.titleMedium,
                            color = ink.copy(alpha = .7f),
                            modifier = Modifier.padding(top = 14.dp),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (word.tip.isNotBlank()) {
                        Text(
                            "💡 ${word.tip}",
                            color = green,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 10.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(word.en, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, maxLines = 2)
                    if (word.phonetic.isNotBlank()) {
                        Text(
                            word.phonetic,
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                    if (!word.core) {
                        Text("拓展词 · 认得即可", color = coral, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
                Text("点击卡片翻面", color = green, modifier = Modifier.padding(top = 18.dp))
            }
        }

        /*
         * 「再练一次 / 认识 ✓」——网页早就有一对（finishWord(known)），
         * 平板之前只有「上一个/下一个」，等于孩子对单词掌握到哪一步根本没被记录，
         * 家长那边的「掌握单词」永远是 0。这也是这次学情同步最容易漏的一环：
         * 只报"翻过"是不够的，家长想知道的是"记住了几个"。
         */
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    onSeen(word.en, false)
                    index = (safeIndex + 1) % words.size
                    flip = false
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ink),
                modifier = Modifier.weight(1f),
            ) { Text("再练一次") }
            Button(
                onClick = {
                    onSeen(word.en, true)
                    index = (safeIndex + 1) % words.size
                    flip = false
                },
                colors = ButtonDefaults.buttonColors(containerColor = coral, contentColor = Color.White),
                modifier = Modifier.weight(1f),
            ) { Text("认识 ✓") }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { index = (safeIndex - 1 + words.size) % words.size; flip = false },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ink),
                modifier = Modifier.weight(1f),
            ) { Text("← 上一个") }
            Button(
                onClick = { index = (safeIndex + 1) % words.size; flip = false },
                // 绿底必须配白字：暗色主题下 contentColor 默认会变成深色，实测"下一个 →"看不见
                colors = ButtonDefaults.buttonColors(containerColor = green, contentColor = Color.White),
                modifier = Modifier.weight(1f),
            ) { Text("下一个 →") }
        }
    }
}

// ---------------------------------------------------------------- 句型挑战

@Composable private fun QuizPage(
    unit: CurriculumUnitDto,
    onRecord: (Mistake) -> Unit,
    /** 每答一题都报一次（对错都报）——正确率需要总题数。 */
    onAnswer: (Boolean) -> Unit,
) {
    val quiz = unit.quiz
    if (quiz.isEmpty()) {
        EmptyPage("句型挑战", "Unit ${unit.unit} 的练习题还没录入。")
        return
    }

    var qi by rememberSaveable(unit.unit) { mutableIntStateOf(0) }
    var picked by rememberSaveable(unit.unit) { mutableIntStateOf(-1) }
    var correct by rememberSaveable(unit.unit) { mutableIntStateOf(0) }
    val safeQi = qi.coerceIn(0, quiz.lastIndex)
    val q = quiz[safeQi]
    val answered = picked in q.options.indices
    val isRight = answered && picked == q.answer

    LazyColumn(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Title("句型挑战", "Unit ${unit.unit} · ${unit.title} · 第 ${safeQi + 1}/${quiz.size} 题") }
        item {
            Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    Text(q.q, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    q.options.forEachIndexed { i, option ->
                        val bg = when {
                            !answered -> Color(0xFFF3F5F0)
                            i == q.answer -> Color(0xFFD9EFD3)   // 正确项：答完总是绿
                            i == picked -> Color(0xFFFADBD8)     // 选错的项：红
                            else -> Color(0xFFF3F5F0)
                        }
                        Surface(
                            onClick = {
                                if (!answered) {
                                    picked = i
                                    val right = i == q.answer
                                    onAnswer(right)
                                    if (right) {
                                        correct += 1
                                    } else {
                                        onRecord(
                                            Mistake(
                                                unit = unit.unit,
                                                question = q.q,
                                                chosen = option,
                                                correct = q.options.getOrNull(q.answer) ?: "",
                                                explain = q.explain,
                                            )
                                        )
                                    }
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = bg,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    listOf("A", "B", "C", "D").getOrElse(i) { "?" },
                                    fontWeight = FontWeight.Bold,
                                    color = green,
                                    modifier = Modifier.width(26.dp),
                                )
                                Text(option, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }

                    if (answered) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            if (isRight) "✓ 答对了" else "✗ 正确答案：${q.options.getOrNull(q.answer) ?: "?"}",
                            color = if (isRight) green else red,
                            fontWeight = FontWeight.Bold,
                        )
                        if (q.explain.isNotBlank()) {
                            Text(q.explain, color = ink.copy(alpha = .72f), modifier = Modifier.padding(top = 6.dp))
                        }
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = {
                                if (safeQi + 1 < quiz.size) {
                                    qi = safeQi + 1
                                    picked = -1
                                } else {
                                    // 最后一题：回到第 1 题，但保留得分（这一轮的成果）
                                    qi = 0
                                    picked = -1
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = green, contentColor = Color.White),
                        ) { Text(if (safeQi + 1 < quiz.size) "下一题 →" else "再来一轮") }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("本轮答对", color = Color.Gray, modifier = Modifier.weight(1f))
                Text("$correct 题", fontWeight = FontWeight.Bold, color = green)
            }
        }
    }
}

// ---------------------------------------------------------------- AI 口语

@Composable private fun SpeakingPage(unit: CurriculumUnitDto, vm: EnglishViewModel) {
    val context = LocalContext.current

    // 服务端限制 ref_text 是 1~30 个英文单词，超了直接 422。
    // 所以在**选句子**这一步就筛掉，而不是让孩子念完才被告知「不支持」。
    val sentences = remember(unit) {
        unit.sentences.map { it.en.trim() }
            .filter { it.isNotBlank() && it.split(Regex("\\s+")).size <= 30 }
            .ifEmpty { listOf(FALLBACK_SENTENCE) }
    }
    var si by rememberSaveable(unit.unit) { mutableIntStateOf(0) }
    val sentence = sentences[si.coerceIn(0, sentences.lastIndex)]

    // 权限不主动弹：孩子刚打开 App 就跳一个「允许录音吗」会被吓到。
    // 只有真的按住按钮时才问（见下面的 holdStart）。
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) {
            vm.showNotice("麦克风已授权，再按住按钮就能读了")
        } else {
            vm.showNotice("没有麦克风权限就没法跟读，请在系统设置里允许录音")
        }
    }

    /** 两个按钮共用：没权限就先去申请，有权限才开始录。 */
    val holdStart: (String) -> Boolean = { purpose ->
        if (!hasPermission) {
            askPermission.launch(Manifest.permission.RECORD_AUDIO)
            false
        } else {
            val ok = vm.startRecording(purpose)
            if (!ok) vm.showNotice("麦克风打不开，可能被别的应用占着，稍后再试")
            ok
        }
    }

    /*
     * ⚠️ 为什么是两栏（2026-10-23 靖哥哥反馈「一行行翻才能点到对话按钮」）
     *
     * 原来是单列：跟读卡(~280dp) + 评分卡(~280dp) + 对话卡(~200dp) + 标题(60dp)
     * ≈ 820dp，而 8 寸平板横屏的可用高度只有 ~530dp —— 对话按钮必然在第二屏。
     *
     * 平板本来就是横屏、内容区有 760dp 宽，竖着堆是浪费。改成左右两栏后
     * **两个「按住说话」都落在首屏**，而且右栏的按钮是钉在底部的，对话再多也不用翻。
     *
     * 700dp 是分界：再窄下去左栏只剩 400dp，一句课文要折 5 行，不如退回单列。
     */
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp
        if (wide) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(Modifier.weight(1.35f).fillMaxHeight()) {
                    SpeakHeader(vm)
                    Spacer(Modifier.height(10.dp))
                    ReadingCard(
                        vm = vm,
                        sentence = sentence,
                        canNext = sentences.size > 1,
                        onSpeak = { vm.speak(sentence) },
                        onNext = { si = (si + 1) % sentences.size },
                        onHoldStart = { holdStart("eval") },
                        onHoldEnd = { vm.stopAndEvaluate(unit.unit, sentence) },
                    )
                    Spacer(Modifier.height(10.dp))
                    val result = vm.evalResult
                    if (result != null) {
                        EvalCard(result, Modifier.weight(1f))
                    } else {
                        // 还没评过 → 左栏下半放「本单元句型」，点一句就是换跟读目标。
                        // 不放的话跟读卡只占上半屏，下面一大片 cream 色，
                        // 看起来像「这页还没加载完」。
                        SentenceList(
                            sentences = sentences,
                            current = si.coerceIn(0, sentences.lastIndex),
                            onPick = { si = it },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                ChatPanel(
                    vm = vm,
                    onHoldStart = { holdStart("chat") },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        } else {
            // 窄屏：单列，但对话卡仍撑满剩余高度（按钮在它的底部）
            Column(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SpeakHeader(vm)
                ReadingCard(
                    vm = vm,
                    sentence = sentence,
                    canNext = sentences.size > 1,
                    onSpeak = { vm.speak(sentence) },
                    onNext = { si = (si + 1) % sentences.size },
                    onHoldStart = { holdStart("eval") },
                    onHoldEnd = { vm.stopAndEvaluate(unit.unit, sentence) },
                )
                vm.evalResult?.let { EvalCard(it, Modifier.fillMaxWidth()) }
                ChatPanel(
                    vm = vm,
                    onHoldStart = { holdStart("chat") },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )            }
        }
    }
}

/** 标题 + 云服务状态压在**一行**里：竖排要 60dp，横排只要 26dp。 */
@Composable private fun SpeakHeader(vm: EnglishViewModel) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
        Text(
            "AI 口语教室",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(
            "· ${vm.cloud}",
            color = Color.Gray,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.padding(start = 6.dp, bottom = 3.dp),
        )
    }
}

@Composable private fun ReadingCard(
    vm: EnglishViewModel,
    sentence: String,
    canNext: Boolean,
    onSpeak: () -> Unit,
    onNext: () -> Unit,
    onHoldStart: () -> Boolean,
    onHoldEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier, colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "跟读练习",
                    color = green,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                // 「换一句」从按钮降级成文字链接：一行 40dp 的按钮换 0dp
                if (canNext) {
                    Text(
                        "换一句 →",
                        color = green,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable { onNext() }.padding(6.dp),
                    )
                }
            }
            Text(
                sentence,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 10.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 示范音走服务端讯飞合成（真实发音，不是设备 TTS）：
                // 孩子要模仿的是教材那套英式发音，设备自带的合成音会把他带偏。
                OutlinedButton(
                    onClick = onSpeak,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ink),
                ) { Text("🔊 听示范") }
                if (vm.healthLoaded && !vm.services.tencentSoe) {
                    Text(
                        "服务端没配讯飞评测，评分不可用",
                        color = red,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            HoldToTalk(
                label = if (vm.evalResult == null) "按住跟读" else "再读一次",
                enabled = !vm.busy && (!vm.healthLoaded || vm.services.tencentSoe),
                recording = vm.recording && vm.recordPurpose == "eval",
                busy = vm.busy,
                recordMs = vm.recordMs,
                onStart = onHoldStart,
                onEnd = onHoldEnd,
                onCancel = { vm.cancelRecording() },
            )
        }
    }
}

/**
 * 评分卡。
 *
 * 分数 / 三维度放头部（永远可见），**逐词批改放可滚动区** ——
 * 24 个单词的句子逐词 chips 能占 6 行，让它把按钮挤出屏幕就本末倒置了。
 */
/**
 * 本单元句型列表，点一句就把它设成跟读目标。
 *
 * 比「换一句 →」有用：那个是盲翻（不知道下一句是什么，也不知道一共几句），
 * 这个是看得见全貌、想练哪句点哪句。
 */
@Composable private fun SentenceList(
    sentences: List<String>,
    current: Int,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier, colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "本单元句型",
                    color = green,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "共 ${sentences.size} 句 · 点一句来跟读",
                    color = Color.Gray,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            LazyColumn(
                Modifier.weight(1f).padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                itemsIndexed(sentences) { i, s ->
                    val active = i == current
                    Surface(
                        onClick = { onPick(i) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (active) lime else Color.Transparent,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
                            Text(
                                "${i + 1}",
                                color = if (active) green else Color.Gray,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.width(20.dp),
                            )
                            Text(
                                s,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (active) ink else ink.copy(alpha = .72f),
                                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun EvalCard(r: SoeResult, modifier: Modifier = Modifier) {
    val comment = when {
        r.suggestedScore >= 90 -> "太棒了，语音语调很地道！"
        r.suggestedScore >= 75 -> "不错，再顺一点就更好"
        r.suggestedScore >= 60 -> "能听懂，跟着示范再念两遍"
        else -> "一个词一个词慢慢读，别急"
    }
    Card(modifier, colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "跟读得分",
                    color = green,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "%.0f".format(r.suggestedScore),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    " 分",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Text(comment, color = ink.copy(alpha = .72f), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // ⚠️ PronAccuracy 是 0~100，而 Fluency / Completion 是 **0~1 的比例**。
                //    不乘 100 的话孩子会看到「流利 1 分」。
                MiniMetric("准确", r.pronAccuracy, Modifier.weight(1f))
                MiniMetric("流利", r.pronFluency * 100.0, Modifier.weight(1f))
                MiniMetric("完整", r.pronCompletion * 100.0, Modifier.weight(1f))
            }
            if (r.words.isNotEmpty()) {
                Text(
                    "逐词批改",
                    color = Color.Gray,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 6.dp),
                ) {
                    // 一行 3 个：左栏只有 420dp，4 个会让 "should" 这种词折行
                    r.words.chunked(3).forEach { group ->
                        Row(
                            Modifier.padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            group.forEach { w -> WordChip(w) }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun MiniMetric(label: String, value: Double, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(10.dp), color = Color(0xFFF3F5F0)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(label, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            Text(
                "%.0f".format(value.coerceIn(0.0, 100.0)),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable private fun WordChip(w: SoeWord) {
    val bg = when {
        w.correct -> Color(0xFFD9EFD3)
        w.wrong -> Color(0xFFFADBD8)
        else -> Color(0xFFEFEFEF)       // 漏读 / 多读
    }
    val fg = when {
        w.correct -> green
        w.wrong -> red
        else -> ink.copy(alpha = .5f)
    }
    Surface(shape = RoundedCornerShape(10.dp), color = bg) {
        Column(
            Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(w.word, color = fg, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1)
            // 漏读的词 PronAccuracy 是 -1，不是 0 —— 直接格式化会显示「-1」
            Text(
                if (w.pronAccuracy >= 0) "%.0f".format(w.pronAccuracy) else "漏读",
                color = fg.copy(alpha = .8f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * 右栏：对话记录 + 「按住说话」。
 *
 * 按钮**不进滚动区**，永远钉在卡片底部 —— 这是这次改版的核心：
 * 对话越长，越不该让「说话」这个唯一的输入方式被挤到屏幕外。
 */
@Composable private fun ChatPanel(
    vm: EnglishViewModel,
    onHoldStart: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // 新消息进来就滚到底：孩子说完一句要能立刻看到自己说了什么
    LaunchedEffect(vm.turns.size) {
        if (vm.turns.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(vm.turns.lastIndex) }
        }
    }

    Card(modifier, colors = CardDefaults.cardColors(lime), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "自由对话",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (vm.turns.isNotEmpty()) {
                    Text(
                        "清空",
                        color = green,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable { vm.clearChat() }.padding(6.dp),
                    )
                }
            }

            if (vm.turns.isEmpty()) {
                Text(
                    if (!vm.healthLoaded || vm.services.qwenChat) {
                        "按住下面的按钮，用英语随便说一句，小智会用英语回你。"
                    } else {
                        "服务端还没配千问密钥（QWEN_API_KEY），对话不可用。"
                    },
                    color = ink.copy(alpha = .62f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                // 空状态不要只留白：给几句可以直接点的，孩子不用先想说什么。
                // 这几句同时也能验证「对话链路通不通」，不需要麦克风。
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "不想开口？点一句试试：",
                        color = green,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    CHAT_SUGGESTIONS.forEach { s ->
                        Surface(
                            onClick = { vm.sendMessage(s) },
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White.copy(alpha = .72f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                s,
                                style = MaterialTheme.typography.bodySmall,
                                color = ink,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    itemsIndexed(vm.turns) { _, turn -> ChatBubble(turn) }
                }
            }

            if (vm.dialogStatus.isNotBlank()) {
                Text(
                    vm.dialogStatus,
                    color = green,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            HoldToTalk(
                label = "按住说话",
                enabled = !vm.busy && (!vm.healthLoaded ||
                    (vm.services.qwenChat && vm.services.transcription)),
                recording = vm.recording && vm.recordPurpose == "chat",
                busy = vm.busy,
                recordMs = vm.recordMs,
                onStart = onHoldStart,
                onEnd = { vm.stopAndChat() },
                onCancel = { vm.cancelRecording() },
            )
        }
    }
}

/**
 * 「按住说话」按钮。
 *
 * 用底层手势（`awaitEachGesture` + `awaitFirstDown`）实现：
 * 需要知道**松手**这件事，而且松手可能发生在按钮外面（手指滑出去了）——
 * 那种情况算取消，不会把半句话送去评测。
 *
 * ⚠️ 2026-10-23 实测：`detectTapGestures(onPress = …)` 在这个页面上
 *    **一次都没进过 onPress**（adb 的 tap / swipe / motionevent 三种注入
 *    都试过，按钮毫无反应，也没有任何日志）。同一个 LazyColumn 里的
 *    OutlinedButton 却点得动 —— 所以不是注入的问题，是 tap 手势状态机
 *    在这个「无 clickable 语义节点、纯 Box + pointerInput」的组合下没跑起来。
 *    这里直接走「等第一根手指按下 → 一直等到它抬起」的最简路径，
 *    不依赖 tap 的超时、双击判定这些我们根本用不到的东西。
 */
@Composable private fun HoldToTalk(
    label: String,
    enabled: Boolean,
    recording: Boolean,
    busy: Boolean,
    recordMs: Int,
    onStart: () -> Boolean,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
) {
    val shape = RoundedCornerShape(15.dp)
    val background = when {
        recording -> red
        enabled -> green
        else -> green.copy(alpha = .35f)
    }
    val gesture = if (enabled) {
        Modifier.pointerInput(enabled) {
            awaitEachGesture {
                // requireUnconsumed = false：LazyColumn 的滚动检测可能先摸了一下 down，
                // 但「按住说话」跟滚动不冲突（纵向拖动时我们希望它继续录）。
                val down = awaitFirstDown(requireUnconsumed = false)
                // onStart 返回 false = 没权限 / 麦克风起不来，这一次按下不成立
                if (!onStart()) return@awaitEachGesture

                var released = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        released = change.changedToUp()
                        break
                    }
                }
                if (released) onEnd() else onCancel()
            }
        }
    } else {
        Modifier
    }

    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clip(shape)
            .background(background)
            .then(gesture),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            when {
                busy -> "正在处理…"
                recording -> "● 录音中 %.1f 秒 · 松手结束".format(recordMs / 1000f)
                else -> label
            },
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable private fun ChatBubble(turn: ChatTurn) {
    val mine = turn.role == "user"
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(13.dp),
            color = if (mine) Color.White else green,
        ) {
            Text(
                turn.content,
                color = if (mine) ink else Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            )
        }
    }
}


// ---------------------------------------------------------------- 错题本 / 报告

@Composable private fun MistakesPage(mistakes: List<Mistake>) {
    if (mistakes.isEmpty()) {
        EmptyPage("错题本", "完成「句型挑战」后，答错的题会自动收到这里。")
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Title("错题本", "共 ${mistakes.size} 道，按作答顺序排列") }
        items(mistakes) { m ->
            Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("Unit ${m.unit}", color = green, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(m.question, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                    Text("你选：${m.chosen}", color = red, modifier = Modifier.padding(top = 8.dp))
                    Text("正确：${m.correct}", color = green, fontWeight = FontWeight.Medium)
                    if (m.explain.isNotBlank()) {
                        Text(m.explain, color = ink.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable private fun ReportPage(vm: EnglishViewModel) {
    // 进页面先 flush 再读：刚在单词卡/跟读里做的事还攒在本地队列里（最多 2.5 秒），
    // 不先发出去的话家长刚打开报告会看到少一截的数据。
    LaunchedEffect(Unit) {
        vm.flushProgress()
        vm.loadProgress()
    }

    val p = vm.progress
    LazyColumn(
        Modifier.fillMaxSize().padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Title(
                "学习报告",
                when {
                    p == null -> "最近 7 天"
                    else -> "${p.student?.name ?: "孩子"} · ${p.range?.from ?: ""} 至 ${p.range?.to ?: ""}"
                },
            )
        }

        if (p == null) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    if (vm.progressError.isNotBlank()) {
                        Text(vm.progressError, color = red, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { vm.loadProgress() },
                            colors = ButtonDefaults.buttonColors(containerColor = green, contentColor = Color.White),
                        ) { Text("重试") }
                    } else {
                        Text("正在读取…", color = Color.Gray)
                    }
                }
            }
            return@LazyColumn
        }

        // ---- 汇总 ----
        val total = p.totals
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("学习天数", "${total.days} 天", Modifier.weight(1f))
                StatCard("翻过单词", "${total.wordsSeen} 个", Modifier.weight(1f))
                StatCard("做过题目", "${total.quizTotal} 道", Modifier.weight(1f))
                StatCard("答题正确率", "%.0f%%".format(total.quizAccuracy * 100), Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("跟读次数", "${total.speakCount} 次", Modifier.weight(1f))
                StatCard("跟读均分", "%.0f 分".format(total.speakAvg), Modifier.weight(1f))
                StatCard("掌握单词", "${total.wordsKnown} 个", Modifier.weight(1f))
            }
        }

        // ---- 按单元 ----
        if (p.units.isNotEmpty()) {
            item {
                Text(
                    "按单元",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            items(p.units) { u ->
                Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Unit ${u.unit}",
                            color = green,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(70.dp),
                        )
                        Text(
                            "词 ${u.wordsSeen} · 掌握 ${u.wordsKnown} · 题 ${u.quizCorrect}/${u.quizTotal}" +
                                " · 跟读 ${u.speakCount} 次" +
                                (if (u.speakCount > 0) "（均 %.0f）".format(u.speakAvg) else ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ink.copy(alpha = .78f),
                        )
                    }
                }
            }
        }

        // ---- 每天 ----
        if (p.daily.isNotEmpty()) {
            item {
                Text(
                    "每天",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            val peak = maxOf(1, p.daily.maxOf { it.wordsSeen + it.quizTotal + it.speakCount })
            items(p.daily) { d ->
                val amount = d.wordsSeen + d.quizTotal + d.speakCount
                Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            d.day.takeLast(5),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            modifier = Modifier.width(46.dp),
                        )
                        // 条形图：宽度按当天「词+题+跟读」总量占峰值的比例
                        Box(
                            Modifier.weight(1f).height(10.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth((amount.toFloat() / peak).coerceIn(0.02f, 1f))
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(green.copy(alpha = .55f)),
                            )
                        }
                        Text(
                            "  $amount",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(40.dp),
                        )
                    }
                }
            }
        }

        if (total.wordsSeen == 0 && total.quizTotal == 0 && total.speakCount == 0) {
            item {
                Text(
                    "最近 7 天还没有学习记录。孩子用过网页或平板之后，这里会自动出现。",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        item {
            Text(
                "这份数据存在服务端，网页、平板、家长手机看的都是同一份。",
                color = Color.Gray,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(22.dp)) {
            Text(label, color = Color.Gray)
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
    }
}

// ---------------------------------------------------------------- 公共件

/** 8 个单元的选择器。用 Surface 而不是 OutlinedButton：后者每次组合要算一整套 token。 */
@Composable private fun UnitPills(units: List<CurriculumUnitDto>, current: Int, onPick: (Int) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        units.forEach { u ->
            val active = u.unit == current
            Surface(
                onClick = { onPick(u.unit) },
                shape = RoundedCornerShape(12.dp),
                color = if (active) green else Color.White,
                contentColor = if (active) Color.White else ink.copy(alpha = .7f),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("U${u.unit}", fontWeight = FontWeight.Bold, maxLines = 1)
                    if (!u.ready) {
                        Text("未录入", style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 5.dp), maxLines = 1)
                    }
                }
            }
        }
        // 末尾补一个 Spacer：不补的话滑到最右时最后一个 pill 贴着裁切边界，看着像被切掉
        Spacer(Modifier.width(8.dp))
    }
}

@Composable private fun CurriculumPlaceholder(error: String, loading: Boolean, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when {
                    error.isNotBlank() -> error
                    loading -> "正在加载课程…"
                    else -> "课程还没加载"
                },
                color = if (error.isNotBlank()) red else ink.copy(alpha = .6f),
            )
            if (error.isNotBlank() && !loading) {
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = green, contentColor = Color.White),
                ) { Text("重试") }
            }
        }
    }
}

@Composable private fun EmptyPage(title: String, text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(text, color = Color.Gray, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable private fun Title(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Color.Gray, modifier = Modifier.padding(top = 4.dp), maxLines = 2,
            overflow = TextOverflow.Ellipsis)
    }
}
