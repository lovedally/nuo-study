package com.nuostudy.student

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nuostudy.core.ui.theme.AppTheme
import com.nuostudy.core.ui.theme.SubjectHomework
import com.nuostudy.core.ui.theme.SubjectLearning
import com.nuostudy.feature.assistant.AssistantOverlay
import com.nuostudy.feature.homework.HomeworkScreen
import com.nuostudy.feature.learning.LearningScreen
import com.nuostudy.feature.settings.ServerSettingsScreen
import com.nuostudy.student.mirror.MirrorSettings
import com.nuostudy.student.settings.AdminPasswordDialog
import kotlinx.coroutines.launch

/**
 * 孩子端顶层导航：**「作业 | 学习」两级结构，滑动切换**。
 *
 * 三条规则，每条都是踩过才知道的：
 *
 * 1. **滑动不能是唯一入口**。顶部必须有可见的指示器且**可点击**。
 *    只靠手势，功能就不可发现（孩子不会去猜），也不满足无障碍要求。
 *
 * 2. **作答中必须锁定滑动**（`userScrollEnabled = false`）。
 *    手写画布与外层 Pager 会抢横向手势 —— 孩子写竖式时手一横就翻页、草稿全丢。
 *    锁定开关由子页面通过状态回传，见 [StudentRoot] 的 `swipeEnabled`。
 *
 * 3. **不做嵌套横向 Pager**。学习内部若也横向滑动，内外两层会抢手势，
 *    要写复杂的嵌套滚动仲裁 —— 而切换成本本来就极低，不值得。
 *    学习内部用顶部 Tab 点击切换。
 */
@Composable
fun StudentRoot(modifier: Modifier = Modifier) {
    val titles = listOf("作业", "学习")
    val accent = listOf(SubjectHomework, SubjectLearning)   // 视觉锚点，孩子靠颜色找模块

    val pager = rememberPagerState(pageCount = { titles.size })
    val scope = rememberCoroutineScope()

    // 规则 2：接入作答页之后，这里要换成 remember { mutableStateOf(...) }，
    // 由作答态把它置 false 来锁住外层滑动。骨架阶段先恒为可滑动。
    val swipeEnabled = true

    // 家长侧开关：正文区显示设置页还是 pager。
    // 用 rememberSaveable 而不是 remember：进程被系统回收后再回来，
    // 家长不该发现自己「刚打开的设置页自己关了」。
    //
    // ⚠️ 原来的 AdminGate 家长口令被移除（2026-10-25）：口令常量只有原作者
    //    知道，我们拿不到也无法重设，齿轮点了永远进不去。这台是家庭专用机，
    //    直接放行设置页；将来要恢复口令，把 AdminPasswordDialog 的门闩加回来即可。
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    /* 顶栏是否收起。rememberSaveable：转屏、进程被回收再回来都保持家长/孩子选择的形态。 */
    var tabBarVisible by rememberSaveable { mutableStateOf(true) }

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
        topBar = {
            val dimens = AppTheme.dimens
            // ⚠️ 这里的 insets 必须自己加，别删。
            //
            // Material3 的 Scaffold 只在**没有 topBar** 时才把顶部 inset 算进内容 padding；
            // 一旦提供了 topBar，它就认定「topBar 会自己处理顶部安全区」。
            // 系统的 TopAppBar 确实会（它内部默认 windowInsets = TopAppBarDefaults.windowInsets），
            // 但我们这个是自定义的 Row —— 没人加，顶部 inset 就整个丢了。
            //
            // 实机（8 寸 1280x800 / density 240）实测：状态栏 inset = 36px，
            // 页签芯片落在 [30,12][628,96] —— 芯片被压掉 24px、文字顶部被压 5px。
            // 叠加 enableEdgeToEdge()，这就是「状态栏骑在页签上」的直接原因。
            //
            // 用 safeDrawing 而不是 statusBars：横屏设备若有摄像头挖孔，
            // 挖孔在**侧边**，只加 statusBars 是盖不住的，而 Scaffold 给内容的
            // contentWindowInsets 默认就含横向 —— 两边口径要一致，否则页签与内容会错位。
            //
            // ⚠️ 补丁（2026-10-10，改为全屏之后）：学生端现在**常驻全屏**
            //    （MainActivity.hideSystemBars 藏掉状态栏 + 导航栏），safeDrawing 的
            //    顶部 inset 因此恒为 0 —— 上面说的那 36px 撑开量没有了，页签会一路
            //    贴到屏幕顶边，而左右仍是 screenPadding(20dp)，四周观感就不对称了。
            //    下面补的那段就是拿回这 36px 的等效留白。
            //    （safeDrawing 本身不能删：横屏挖孔在侧边、以及将来系统栏临时唤出时还要靠它。）
            if (tabBarVisible) {
                SectionTabs(
                    titles = titles,
                    accents = accent,
                    selected = pager.currentPage,
                    onSelect = { scope.launch { pager.animateScrollToPage(it) } },
                    onOpenSettings = { settingsOpen = true },
                    // 收起：把整条顶栏让给内容（孩子做题时最需要纵向空间）
                    onCollapse = { tabBarVisible = false },
                    // 开着设置页时把页签整行禁掉：此时 Pager 不在组合里，
                    // 见 SectionTabs 的 [enabled] 注释。
                    enabled = !settingsOpen,
                    modifier = Modifier
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                            )
                        )
                        // 全屏下顶部 inset = 0，补一段让顶部留白与左右 screenPadding 对齐：
                        // 20（目标）− 3（SectionTabs 行内自带的内边距）= 17dp。
                        .padding(top = dimens.screenPadding - 3.dp),
                )
            } else {
                CollapsedTabsHandle(
                    onExpand = { tabBarVisible = true },
                    modifier = Modifier
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                            )
                        )
                        .padding(top = 6.dp),
                )
            }
        },
        ) { inner ->
            if (settingsOpen) {
                // 设置页直接盖在 pager 上面，不走导航图 ——
                // 它是家长的一次性操作，为它引入 nav host 与返回栈不划算，
                // 而且孩子端刻意没有导航栈（返回键行为越简单越好）。
                //
                // 实现来自 :feature-settings（家长端共用同一份）。这里 **不传 stackFields**，
                // 即保持「两个输入框并排」—— 平板横屏横向空间富裕，省下的纵向留给结果区。
                //
                // extraContent 挂的是「实时画面」开关（调试功能，见 mirror 包）。
                // 做成插槽而不是塞进共用设置页：家长端完全不需要它，混进去只会多出
                // 一堆家长看不懂的选项。
                ServerSettingsScreen(
                    onClose = { settingsOpen = false },
                    extraContent = { MirrorSettings() },
                    modifier = Modifier.padding(inner),
                )
            } else {
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.padding(inner).fillMaxSize(),
                    userScrollEnabled = swipeEnabled,
                ) { page ->
                    when (page) {
                        0 -> HomeworkScreen()
                        else -> LearningScreen()
                    }
                }
        }
        }

        // 悬浮小智助手（右下角小姐姐 + 语音对话）：写在最后 = 组合树最高层。
        // 会话生命周期它自己管（面板开 = 连，面板关 = 断），StudentRoot 不感知。
        AssistantOverlay()
    }
}

/**
 * 顶部板块切换。刻意做成两个大圆角块而不是细长的 TabRow ——
 * 孩子端的点击目标必须大，且颜色本身就是导航线索。
 *
 * [modifier] 由调用方用来注入系统窗口 insets（见 [StudentRoot] 的 topBar），
 * 它排在 `fillMaxWidth()` **之前**：先让出状态栏/挖孔的区域，再在剩余宽度里铺满。
 *
 * [onOpenSettings] 是家长入口。它刻意做成**低对比的小图标**而不是第三个色块：
 * 这一行是孩子的导航区，「作业 / 学习」两个色块才是线索；设置不该跟它们抢注意力，
 * 孩子最好根本注意不到它。真有孩子点进来也无所谓 —— 后面还有口令挡着。
 *
 * [enabled] 为 false 时整行变淡且不可点。这是**开着设置页**的时候：
 *   ⚠️ 不是为了好看。设置页打开时 HorizontalPager 根本不在组合里，
 *      此时若还能点到页签，`pager.animateScrollToPage()` 就是在一个
 *      **没有附着 Pager 的 PagerState** 上调滚动 —— 一条完全没测过的路径。
 *      把它禁掉，比赌它不抛异常划算。
 */
@Composable
private fun SectionTabs(
    titles: List<String>,
    accents: List<Color>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val dimens = AppTheme.dimens
    /* 顶栏高度（2026-10-23 调窄）。
     *
     * 原来用 `dimens.minTouchTarget`（孩子端 56dp）+ 上下各 8dp 内边距 ≈ 73dp，
     * 在 1280x800 / density 240 的平板上（可用高度只有 533dp）占到 13.7% ——
     * 靖哥哥反馈「太高了」。实测截图里页签芯片 y≈[30,115]，确实压着下面的内容。
     *
     * 这里**刻意不跟随 minTouchTarget**：那一项是为「必须点得准的小按钮」定的
     * （项目里触控 56dp 的理由是孩子误触代价大），而这两枚页签各占半屏宽，
     * 横向命中率极高，纵向只要 46dp 就够 —— 高度上省下的 20dp 全给内容。
     * 齿轮按钮一起缩，否则它会凭 56dp 把整行又撑回去。
     */
    /*
     * 46dp → **48dp**（2026-10-23）：48 是 Material 的最小触摸目标，
     * 46 已经低于它了。多出来的 2dp × 2 行 = 4dp，换来「点得中」，值。
     */
    val tabBarHeight = 48.dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.35f)
            .padding(horizontal = dimens.screenPadding, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(dimens.itemGap),
        // 让齿轮与页签色块中线对齐
        verticalAlignment = Alignment.CenterVertically,
    ) {
        titles.forEachIndexed { index, title ->
            val isSelected = index == selected
            val accent = accents[index]
            Surface(
                onClick = { onSelect(index) },
                enabled = enabled,
                shape = RoundedCornerShape(dimens.radiusButton),
                color = if (isSelected) accent.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = tabBarHeight)
                    .clip(RoundedCornerShape(dimens.radiusButton)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        /*
         * ⚠️ 这两枚按钮的尺寸是「点不点得中」的关键
         *    （2026-10-23 靖哥哥反馈「右上角折叠的按钮按起来不怎么灵敏」）。
         *
         * 原来写的是 `Modifier.size(tabBarHeight)` = 46dp：
         *   · 46dp **低于 Material 的 48dp 最小触摸目标**；
         *   · 更要紧的是 `Modifier.size()` 会把 IconButton 内部那句
         *     `minimumInteractiveComponentSize()` 顶掉 —— 它本来会给按钮补一个
         *     至少 48dp 的触摸区，被显式 size 固定住之后就补不了了。
         *   结果就是「箭头看得见，手指稍偏一点就点不中」。
         *
         * 现在给 56×48：横向多 10dp（手指横向容错更大），纵向补到 48dp 的合规值。
         * 另外加一层很淡的底色 —— 纯图标在一个奶油色顶栏上，孩子根本判断不出
         * 可点范围有多大，自然就会「点不准」。
         */
        IconButton(
            onClick = onOpenSettings,
            enabled = enabled,
            modifier = Modifier
                .width(56.dp)
                .height(tabBarHeight)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .05f)),
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                // contentDescription 写全「需口令」，读屏用户（家长）才知道点进去要什么
                contentDescription = "服务端设置，需要家长口令",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f),
                modifier = Modifier.size(22.dp),
            )
        }

        // 收起顶栏：做题时多出来的近 60dp 全给内容。
        // 箭头朝上 = 往屏幕顶上收，和收起后那条「把手」上的朝下箭头对应。
        IconButton(
            onClick = onCollapse,
            modifier = Modifier
                .width(56.dp)
                .height(tabBarHeight)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .05f)),
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowUp,
                contentDescription = "收起顶栏",
                // 折叠按钮的箭头比齿轮再深一点：它是「常用动作」，齿轮是家长偶尔用
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .75f),
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/**
 * 顶栏收起后留的那条「把手」。
 *
 * ⚠️ 不能彻底隐藏：两个页签是切换「作业 / 学习」的**唯一入口**
 *    （见本文件头的规则 1 —— 只靠手势的话功能不可发现）。
 *    彻底藏掉等于把「回作业」这个动作变成谜题。
 * 所以留一条全宽横条，带一行浅色小字，点一下就展开。
 * 全宽 = 横向命中率 100%，高度对误触几乎无影响。
 *
 * 高度 18dp → **22dp**（2026-10-23，同一次「按钮不灵敏」的修复）：
 * 18dp 在平板上手指很容易滑过去，而收起顶栏本来就是为省空间，
 * 22dp 仍然只占一行的三分之一。再加一层极淡底色，让「这里能点」看得出来。
 */
@Composable
private fun CollapsedTabsHandle(onExpand: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(22.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f))
            .clickable(onClick = onExpand),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.size(16.dp),
            )
            Text(
                "显示顶栏",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            )
        }
    }
}
