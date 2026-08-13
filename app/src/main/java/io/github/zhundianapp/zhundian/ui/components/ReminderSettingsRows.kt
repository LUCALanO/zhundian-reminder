package io.github.zhundianapp.zhundian.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zhundianapp.zhundian.R
import io.github.zhundianapp.zhundian.notification.SoundPlayer
import kotlin.math.roundToInt

/**
 * 提醒设置通用行组件。供间隔提醒编辑页与系统日历日程设置页共用，
 * 保证两处的铃声选择 / 音量 / 时长 / 开关交互完全一致。
 */

/** 开关行：标题 + 可选副标题 + Switch。 */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 提醒铃声选择行：显示当前铃声名（未选为系统默认），提供「系统铃声」选择器、
 * 「本地音频」文件选择器（选中即持久化 SAF 访问权限），以及「恢复默认」。
 * 系统铃声选择器仅能选系统音源；本地音频走 OpenDocument，重启后依然可播放。
 */
@Composable
fun RingtonePickerRow(
    currentUri: String?,
    onPick: (String?) -> Unit
) {
    val context = LocalContext.current
    val ringtoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val picked = result.data!!
                .getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            onPick(picked?.toString())
        }
    }
    val localAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 持久化读取权限，否则进程被杀/重启后 URI 失效、到点静默无声
        val granted = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.isSuccess
        if (granted) {
            // 替换时释放旧本地文件的授权，防止顶到系统持久化授权数量上限
            currentUri?.let { old ->
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            onPick(uri.toString())
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.local_audio_pick_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    val currentName = remember(currentUri) {
        currentUri?.let { soundDisplayName(context, it) }
            ?: context.getString(R.string.ringtone_default)
    }
    // 仅系统音源 URI 才能传给系统铃声选择器高亮当前项；SAF 本地 URI 认不出来
    val isSystemUri = remember(currentUri) {
        currentUri?.let {
            val uri = Uri.parse(it)
            uri.scheme == "content" && uri.authority in setOf("settings", "media")
        } ?: false
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.ringtone),
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = currentName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = {
                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(
                        RingtoneManager.EXTRA_RINGTONE_TYPE,
                        RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_NOTIFICATION
                    )
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, context.getString(R.string.choose_ringtone))
                    if (currentUri != null && isSystemUri) {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(currentUri))
                    }
                }
                ringtoneLauncher.launch(intent)
            }) {
                Text(stringResource(R.string.choose_ringtone))
            }
            TextButton(onClick = { localAudioLauncher.launch(arrayOf("audio/*")) }) {
                Text(stringResource(R.string.ringtone_local))
            }
            if (currentUri != null) {
                TextButton(onClick = { onPick(null) }) {
                    Text(stringResource(R.string.ringtone_reset))
                }
            }
        }
    }
}

/**
 * 当前铃声的显示名：
 * - 系统铃声（RingtoneManager 可取标题）→ 标题；
 * - 本地音频（SAF document URI，RingtoneManager 取不到）→ 查询 DISPLAY_NAME；
 * - 均失败 → null（调用方回退「系统默认」文案）。
 */
private fun soundDisplayName(context: Context, uriString: String): String? {
    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
    runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }
        .getOrNull()
        ?.takeIf { !it.isNullOrBlank() }
        ?.let { return it }
    return runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
}

/** 响铃音量行：Slider（0~1），右侧显示百分比。 */
@Composable
fun SoundVolumeRow(
    volume: Float,
    onVolumeChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.sound_volume),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(R.string.sound_volume_percent, (volume * 100).roundToInt()),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = volume,
            onValueChange = onVolumeChange,
            valueRange = 0f..1f
        )
    }
}

/** 响铃时长行：可选档位 FilterChip。 */
@Composable
fun SoundDurationRow(
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.sound_duration),
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoundPlayer.DURATION_OPTIONS_SECONDS.forEach { seconds ->
                FilterChip(
                    selected = selected == seconds,
                    onClick = { onSelect(seconds) },
                    label = { Text(stringResource(R.string.sound_duration_seconds, seconds)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        }
    }
}

/** 顶部弹窗权限缺失提示行。 */
@Composable
fun OverlayPermissionHint(onOpenOverlay: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.overlay_permission_hint),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onOpenOverlay) {
            Text(stringResource(R.string.grant_overlay))
        }
    }
}
