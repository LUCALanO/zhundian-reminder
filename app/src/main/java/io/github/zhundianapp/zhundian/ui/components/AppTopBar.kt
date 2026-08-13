package io.github.zhundianapp.zhundian.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

/**
 * 应用统一顶部标题栏。
 *
 * 背景色与底部导航栏一致（[MaterialTheme.colorScheme.surfaceContainer]），
 * 让标题区与内容区形成明确的分区；标题收紧行高后文字在标题栏内上下居中。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                // headlineSmall 默认行高(32sp)远大于字号(24sp)，文字会整体下坠；
                // 收紧到与字号一致，让文字本体在标题栏内真正垂直居中，不再靠偏移补偿
                style = MaterialTheme.typography.headlineSmall.copy(
                    lineHeight = MaterialTheme.typography.headlineSmall.fontSize
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    )
}
