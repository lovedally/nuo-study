package com.nuostudy.feature.math

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuostudy.core.ui.theme.AppTheme

/**
 * 数学 · 内容层暂缓（三年级内容未建，当前只显示占位页）。
 *
 * 三科里最适合自动判分的一科（纯算式可精确求值），
 * 但 M2 已整体暂缓，所以当前作答只落库、不判对错。
 *
 * ⚠️ 竖式计算必须手写（answerMode = 2），且必须允许多行 ——
 *    给一个单行输入框，孩子没法写竖式。
 */
@Composable
fun MathScreen(modifier: Modifier = Modifier) {
    val dimens = AppTheme.dimens
    Column(modifier = modifier.fillMaxSize().padding(dimens.screenPadding)) {
        Text("数学", style = MaterialTheme.typography.displaySmall)
        Text(
            "口算 · 竖式 · 应用题 · 几何（手写为主）",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
