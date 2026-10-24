package com.nuostudy.feature.homework

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuostudy.core.data.local.LocalItem
import com.nuostudy.core.ui.text.friendlyDate
import com.nuostudy.core.ui.text.monthDay
import com.nuostudy.core.ui.text.subjectLabel
import com.nuostudy.core.ui.theme.AnswerPending
import com.nuostudy.core.ui.theme.AppTheme
import com.nuostudy.core.ui.theme.SubjectChinese
import com.nuostudy.core.ui.theme.SubjectEnglish
import com.nuostudy.core.ui.theme.SubjectMath
import com.nuostudy.core.ui.theme.WarningAmber

// -----------------------------------------------------------------------------
// 作业卡片的紧凑间距（2026-10-10 靖哥哥要求：「每科作业上下留白太多，紧凑一点」）
//
// ⚠️ 刻意**不改** `AppTheme.dimens.cardPadding` / `itemGap`：
//    那两个是全局 token，家长端和其它 feature 也在用，为了这一屏把它们一起压缩
//    会波及别的界面（家长端本来就该松一些，看的不是同一类信息）。
//    所以这几个数就近定义、只作用于本屏的作业卡片。
//
// 压缩的主力是**竖直方向**：水平内边距保持 dimens.cardPadding 不变 ——
// 左右是留白的美感来源，砍了会显得局促；上下才是「一屏能塞几张卡」的关键。
// -----------------------------------------------------------------------------
private val CardPaddingV = 12.dp   // 卡片上下内边距（token = 18dp）
private val CardGap = 10.dp        // 卡片之间的竖直间距（token = 16dp）
private val CardInnerGap = 6.dp    // 卡片内「头部行 / 每个作业项」之间的间距（token = 8dp）

/**
 * 今日作业 · 孩子端主屏（平板横屏）。
 *
 * 当前实现范围 = **看作业**：从本地 Room 订阅今天已发布的作业单，按科目分卡展示，
 * 每个作业项标出「作业性质」与「作答方式」。
 *
 * **展示规则：今天有就显示今天的；今天没有，退回显示最近一次上传的那一份。**
 * 退回时标题会变成「最近一次作业」并配一行琥珀色说明 —— 这一步不能省：
 * 孩子看到一份作业是不会有疑问的，他只会照着做，而做成昨天的作业是要挨说的。
 *
 * ⚠️ **作答台（三栏布局、自绘键盘、手写画布）还没接入**，是独立的一步。
 *    两条届时不能忘的约束先写在这里，免得接手时重新踩：
 *  1. 进入作答态必须锁定外层 Pager 的滑动（`userScrollEnabled = false`）。
 *     手写画布与外层横向 Pager 会抢手势，孩子写竖式时手一横就翻页、草稿全丢。
 *  2. 作答控件按 `answerMode` 分流（0 打卡 / 1 自绘键盘 / 2 手写 / 3 录音 / 4 拍照），
 *     **不要**统一给一个输入框 —— 那会让「默写」这类题型彻底失去意义。
 *
 * 数据只来自本地库，界面从不直接等网络：断网时这一页照样要能打开。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeworkScreen(
    modifier: Modifier = Modifier,
    vm: HomeworkViewModel = hiltViewModel(),
) {
    val dimens = AppTheme.dimens
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(dimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(dimens.itemGap),
    ) {
        // ---------------- 标题行 ----------------
        //
        // 用 FlowRow 而不是 Row：标题「今日作业」+ 日期 + 各科计数胶囊 一行放不下时
        // 会自动折到第二行（平板横屏 533dp 可用宽度，四五个元素就顶满了）。
        // 刷新按钮**不进 FlowRow** —— 它要恒定贴右，跟着折行会飘到中间。
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(dimens.itemGap),
                verticalArrangement = Arrangement.spacedBy(dimens.itemGap / 2),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    // ⚠️ 标题必须跟着**实际展示的那一天**走，不能恒为「今日作业」：
                    //    退回模式下写成「今日作业 · 10月8日」是自相矛盾的，孩子会以为
                    //    那是今天新发的，而家长会以为程序把日期搞错了。
                    text = if (state.isToday) stringResource(R.string.feature_homework_title)
                    else stringResource(R.string.feature_homework_title_recent),
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    text = friendlyDate(state.date),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 各科作业数量统计。统计口径 = **作业项数**，且统计的是
                // **当前实际展示那一天**的作业（`state.entries` 已经按 isToday 选好了：
                // 今天是今天的，退回时是最近一次的）。所以退回态下这几颗胶囊照常出现，
                // 数字跟着那份「最近一次作业」走 —— 与卡片内的「共 N 项」同源。
                // 之所以敢在退回态也显示：标题已经写明「最近一次作业 + 日期」，
                // 胶囊上只有「语文 3」这种中性计数，不会被误读成今天的量。
                SubjectCountPills(subjectCounts(state.entries))
            }

            Spacer(Modifier.width(dimens.itemGap))

            if (vm.refreshing) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(dimens.itemGap / 2))
                Text(
                    text = stringResource(R.string.feature_homework_syncing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(dimens.itemGap))
            }
            OutlinedButton(
                onClick = vm::refresh,
                enabled = !vm.refreshing,
                modifier = Modifier.heightIn(min = dimens.minTouchTarget),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.width(dimens.itemGap / 2))
                Text(stringResource(R.string.feature_homework_refresh))
            }
        }

        // 退回模式的说明。只在「有内容可看但不是今天」时出现 ——
        // 本地一条都没有时走下面的空态，那里有更贴切的说法。
        if (!state.isToday && state.entries.isNotEmpty()) {
            Text(
                text = stringResource(R.string.feature_homework_recent_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = WarningAmber,
            )
        }

        vm.message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---------------- 作业列表 ----------------
        if (state.entries.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(CardGap)) {
                items(state.entries, key = { it.assignment.id }) { entry ->
                    AssignmentCard(entry)
                }
                item {
                    Text(
                        text = stringResource(R.string.feature_homework_workbench_pending),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = dimens.itemGap),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    val dimens = AppTheme.dimens
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.feature_homework_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AssignmentCard(entry: HomeworkViewModel.AssignmentWithItems) {
    val dimens = AppTheme.dimens
    val a = entry.assignment
    val accent = subjectColor(a.subject)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // ⚠️ 左侧色条要撑满卡片高度，必须给 Row 定死 IntrinsicSize.Min 再 fillMaxHeight。
        //    直接写 fillMaxSize() 会和 width(10.dp) 打架；而只写 fillMaxHeight()
        //    在 LazyColumn 里拿到的高度约束是**无界**的，量不出结果。
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // 左侧色条。孩子靠颜色定位模块比读文字快 —— 语文朱红 / 数学靛蓝 / 英语橙。
            Box(
                modifier = Modifier
                    .width(10.dp)
                    .fillMaxHeight()
                    .background(accent),
            )
            Column(
                modifier = Modifier.padding(
                    start = dimens.cardPadding,
                    end = dimens.cardPadding,
                    // 上下收窄：cardPadding 的 18dp 对「一屏要看几科」来说太奢侈，
                    // 卡片本身没有可点区域，不需要为触控留那么大内边距。
                    top = CardPaddingV,
                    bottom = CardPaddingV,
                ),
                verticalArrangement = Arrangement.spacedBy(CardInnerGap),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = subjectLabel(a.subject),
                        style = MaterialTheme.typography.titleLarge,
                        color = accent,
                    )
                    Spacer(Modifier.width(dimens.itemGap))
                    // 科目名后面**只跟日期**，不显示作业单标题（2026-10-10 靖哥哥要求）。
                    // 识别出来的标题常是「10.9数学作业」「英语作业」这种 —— 左边科目名
                    // 已经说了是数学/英语，标题再把科目说一遍纯属重复，还占掉一行宽度。
                    // 日期才是孩子需要的（尤其退回态下有多天作业时，要能一眼看出是哪天的）。
                    // 用 monthDay() 不带星期：这一行还要塞徽标，星期在这里只是噪声。
                    Text(
                        text = monthDay(a.assignDate),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 「需要提交」徽标**紧跟日期**，不再用 weight(1f) 甩到最右 ——
                    // 甩到最右会和左边隔开一大段空白，看着像两个不相干的东西。
                    Spacer(Modifier.width(dimens.itemGap))
                    Badge(
                        text = if (a.needSubmit) stringResource(R.string.feature_homework_need_submit)
                        else stringResource(R.string.feature_homework_no_submit),
                        tint = if (a.needSubmit) accent else AnswerPending,
                    )
                    // 「共 N 项」也收进这一行、紧跟徽标（2026-10-10 靖哥哥要求）。
                    // 原来它单占一行，与科目/日期隔得远；现在「哪科 · 哪天 · 要不要交 ·
                    // 几件事」四件事在同一行看完，不用再往下扫。
                    Spacer(Modifier.width(dimens.itemGap))
                    Text(
                        text = stringResource(R.string.feature_homework_item_count, entry.items.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                entry.items.forEach { item -> ItemRow(item) }
            }
        }
    }
}

@Composable
private fun ItemRow(item: LocalItem) {
    Column {
        Text(text = "${item.seq}. ${item.title}", style = MaterialTheme.typography.bodyLarge)
        item.requirement?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = "要求：$it",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 这里刻意**不**再挂「客观题 / 主观题 / 键盘作答 / 手写作答」那两个小标签
        // （2026-10-09 去掉的）。理由：
        //   · 那是给家长核对识别结果用的术语，摆在孩子面前只有干扰 ——
        //     三年级的孩子不需要在开始做题前先读一个题型分类；
        //   · 「作答方式」本来就由 answerMode 决定渲染什么控件（见 Dto.assignmentItem），
        //     界面自己体现得出来，再写一行字纯属重复。
        // 家长端确认页同理，两处一起去掉的。
    }
}

@Composable
private fun Badge(text: String, tint: Color) {
    val dimens = AppTheme.dimens
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(dimens.radiusButton))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = dimens.itemGap / 2, vertical = 4.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

/** 学科 → 颜色。与服务端 `SUBJECT_MAP`（语文1 / 数学2 / 英语3 / 其他9）对应。 */
private fun subjectColor(subject: Int): Color = when (subject) {
    1 -> SubjectChinese
    2 -> SubjectMath
    3 -> SubjectEnglish
    else -> AnswerPending
}

/**
 * 按科目汇总作业项数，供标题行的统计胶囊使用。
 *
 * 口径 = **作业项（item）数**，不是作业单数：「今天数学有 2 件事、语文 1 件」
 * 对孩子安排做题顺序更有用（他关心的是要做几件事，不是老师发了几张单子）。
 *
 * 排序：语文 → 数学 → 英语 → 其他（1/2/3/9 升序），与卡片列表、顶栏色块顺序一致，
 * 避免「统计里数学在前、卡片里语文在前」这种自相矛盾。
 * 数量为 0 的科目不出现 —— 标题行空间宝贵，空科目占位只会稀释信息。
 */
private fun subjectCounts(entries: List<HomeworkViewModel.AssignmentWithItems>): List<Pair<Int, Int>> =
    entries
        .flatMap { it.items }
        .groupingBy { it.subject }
        .eachCount()
        .toList()
        .sortedBy { it.first }

/**
 * 各科作业数量胶囊，如「语文 3 · 数学 2 · 英语 1」。
 *
 * 用科目主题色浅底 + 同色字，和下方卡片左侧色条、科目名颜色一一对应 ——
 * 孩子靠颜色对号入座，不用逐个读字。
 */
@Composable
private fun SubjectCountPills(counts: List<Pair<Int, Int>>) {
    // 每颗胶囊自己就是一个 FlowRow 项，所以这里直接按顺序排即可。
    // （外层 FlowRow 已负责整体折行。）
    counts.forEach { (subject, n) ->
        val color = subjectColor(subject)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(AppTheme.dimens.radiusButton))
                .background(color.copy(alpha = 0.12f))
                .padding(horizontal = AppTheme.dimens.itemGap / 2, vertical = 4.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.feature_homework_subject_count,
                    subjectLabel(subject),
                    n,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
        }
    }
}
