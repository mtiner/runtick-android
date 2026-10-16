package cc.runtick.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.runtick.app.core.AppJson
import cc.runtick.app.core.User
import cc.runtick.app.network.APIClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---- 历史成绩响应 DTO（ui 层内解析，避免依赖 core 序列化注解） ----

@Serializable
data class HistoryAthlete(val id: Long, val loginId: String)

@Serializable
data class HistoryRecordDto(
    val raceId: String,
    val athleteId: Long,
    val recorderId: Long,
    val distance: Int,
    val elapsedMs: Double,
    val performedAt: Double,
    val savedAt: Double,
    val isPb: Boolean = false,
    val pbImprovementMs: Double? = null
)

@Serializable
data class HistoryData(
    val athletes: List<HistoryAthlete> = emptyList(),
    val athleteId: Long = 0,
    val distances: List<Int> = emptyList(),
    val records: List<HistoryRecordDto> = emptyList(),
    val hasMore: Boolean = false
)

/**
 * 历史成绩页：运动员 / 距离 / 排序筛选 + 分页。
 * room 非空时请求带 room 参数（房间内查看本房间成绩）。
 */
@Composable
fun HistoryView(api: APIClient, user: User, room: String?, onBack: () -> Unit) {
    var data by remember { mutableStateOf<HistoryData?>(null) }
    var athleteId by remember { mutableStateOf(0L) }
    var distance by remember { mutableStateOf(0) }
    var sort by remember { mutableStateOf("date-desc") }
    var offset by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var reloadKey by remember { mutableStateOf(0) }

    // 筛选条件变化时重置分页。
    LaunchedEffect(athleteId) { distance = 0; offset = 0 }
    LaunchedEffect(distance) { offset = 0 }
    LaunchedEffect(sort) { offset = 0 }

    suspend fun load() {
        loading = true; error = ""
        try {
            val rule = sort.split("-")
            var path = "/api/history?athleteId=${if (athleteId == 0L) user.id else athleteId}" +
                "&sort=${rule[0]}&direction=${rule.getOrElse(1) { "desc" }}" +
                "&offset=$offset&includeTrend=false"
            if (distance > 0) path += "&distance=$distance"
            if (room != null) path += "&room=$room"
            val result: HistoryData = api.request(path) { element ->
                AppJson.decodeFromJsonElement(HistoryData.serializer(), element)
            }
            data = result
        } catch (e: Exception) {
            error = e.message ?: "加载成绩未完成"
        } finally {
            loading = false
        }
    }

    // 筛选或刷新标记变化时重新加载。
    LaunchedEffect(athleteId, distance, sort, offset, reloadKey) { load() }

    Box(Modifier.fillMaxSize()) {
        RTPage {
            // 顶部返回行。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(34.dp)
                        .background(Color.White.copy(alpha = 0.75f), RoundedCornerShape(50))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = RT.ink, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.weight(1f))
            }
            RTHero(
                badge = "训练档案",
                title = "历史成绩",
                subtitle = "每一次出发，都在向更好的自己靠近。",
                modifier = Modifier.rtEntrance(40)
            )
            RTCard(title = "筛选") {
                val current = data
                if (current != null && current.athletes.size > 1) {
                    FilterChipRow(
                        options = current.athletes.map { athlete ->
                            FilterOption(id = athlete.id.toString(), label = if (athlete.id == user.id) "我" else athlete.loginId)
                        },
                        selected = athleteId.toString(),
                        onSelect = { athleteId = it.toLong() }
                    )
                }
                FilterChipRow(
                    options = listOf(FilterOption("0", "全部距离")) +
                        (data?.distances ?: emptyList()).map { FilterOption(it.toString(), "$it 米") },
                    selected = distance.toString(),
                    onSelect = { distance = it.toInt() }
                )
                FilterChipRow(
                    options = listOf(
                        FilterOption("date-desc", "最新"),
                        FilterOption("date-asc", "最早"),
                        FilterOption("result-asc", "最快"),
                        FilterOption("result-desc", "最慢")
                    ),
                    selected = sort,
                    onSelect = { sort = it }
                )
            }
            if (loading) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(color = RT.deep, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.padding(start = 8.dp))
                    Text("加载成绩中…", fontSize = 13.sp, color = RT.muted)
                }
            }
            if (error.isNotEmpty()) {
                RTButton(onClick = { reloadKey++ }) { RTButtonInkText("重新加载成绩") }
            }
            data?.let { current ->
                RTCard(title = "历史成绩") {
                    if (current.records.isEmpty()) {
                        Text("暂无成绩，完成计时后由终点填写距离并保存。", fontSize = 14.sp, color = RT.muted)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val dateFormat = remember { SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.getDefault()) }
                        current.records.forEach { record ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text("${record.distance} 米", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = RT.ink)
                                    Text(
                                        dateFormat.format(Date(record.performedAt.toLong())),
                                        fontSize = 12.sp, color = RT.muted
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                Text(
                                    String.format("%.3f 秒", record.elapsedMs / 1000.0),
                                    fontSize = 15.sp, color = RT.ink
                                )
                                if (record.isPb) {
                                    Spacer(Modifier.padding(start = 8.dp))
                                    RTChip("PB", lemon = true)
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RTButton(
                        onClick = { offset = (offset - 50).coerceAtLeast(0) },
                        enabled = offset > 0 && !loading
                    ) { RTButtonInkText("上一页") }
                    Spacer(Modifier.weight(1f))
                    RTButton(
                        onClick = { offset += 50 },
                        enabled = current.hasMore && !loading
                    ) { RTButtonInkText("下一页") }
                }
            }
            RTBrandFooter()
        }
    }
    RTErrorAlert(error, enabled = true)
}

internal data class FilterOption(val id: String, val label: String)

/** 筛选 chips 横向滚动行。 */
@Composable
private fun FilterChipRow(options: List<FilterOption>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            val isSelected = option.id == selected
            Text(
                option.label,
                modifier = Modifier
                    .background(
                        if (isSelected) RT.aqua.copy(alpha = 0.30f) else RT.background,
                        RoundedCornerShape(50)
                    )
                    .clickable { onSelect(option.id) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = RT.ink
            )
        }
    }
}
