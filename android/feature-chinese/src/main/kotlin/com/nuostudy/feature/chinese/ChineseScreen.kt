package com.nuostudy.feature.chinese

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuostudy.core.ui.theme.AppTheme

/**
 * 语文 · 内容层暂缓（三年级内容未建，当前只显示占位页）。
 *
 * 内容重心：朗读/背诵、字词默写、阅读理解。
 * 作文（习作）是半结构化题型 —— 只能落成打卡 + 拍照上传，不要指望自动判分。
 *
 * 权限：朗读题需要录音（RECORD_AUDIO），**进入朗读题时才申请**，
 * 不要一进 App 就弹窗 —— 孩子端只在这一个场景需要麦克风。
 */
@Composable
fun ChineseScreen(modifier: Modifier = Modifier) {
    val dimens = AppTheme.dimens
    Column(modifier = modifier.fillMaxSize().padding(dimens.screenPadding)) {
        Text("语文", style = MaterialTheme.typography.displaySmall)
        Text(
            text = "朗读 · 默写 · 阅读 · 习作（习作仅打卡拍照）",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
