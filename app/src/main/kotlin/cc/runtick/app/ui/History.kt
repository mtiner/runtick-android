package cc.runtick.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import cc.runtick.app.core.*
import cc.runtick.app.network.APIClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

@Serializable data class HistoryAthlete(val id:Long,val loginId:String,val avatar:String?=null)
@Serializable data class HistoryRecordDto(val raceId:String,val athleteId:Long,val recorderId:Long,val distance:Int,
    val elapsedMs:Double,val performedAt:Double,val savedAt:Double,val isPb:Boolean=false,val pbImprovementMs:Double?=null,
    val reactionMs:Double?=null,val reactionStatus:String?=null)
@Serializable data class HistoryData(val athletes:List<HistoryAthlete> = emptyList(),val athleteId:Long=0,
    val distances:List<Int> = emptyList(),val records:List<HistoryRecordDto> = emptyList(),val hasMore:Boolean=false,val nextCursor:String?=null)
internal data class FilterOption(val id:String,val label:String)

@Composable
fun HistoryView(api:APIClient,user:User,room:String?,onBack:()->Unit) {
    androidx.activity.compose.BackHandler(onBack=onBack)
    var showSolo by remember {mutableStateOf(false)}
    if(showSolo) {SoloHistoryView(api,onBack={showSolo=false});return}
    val scope=rememberCoroutineScope();val list=rememberLazyListState()
    var data by remember {mutableStateOf<HistoryData?>(null)}
    var athleteId by remember {mutableStateOf(user.id)}
    var distance by remember {mutableStateOf(0)}
    var distanceIndex by remember {mutableFloatStateOf(0f)}
    var sort by remember {mutableStateOf("date-desc")}
    var loading by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf("")}
    var generation by remember {mutableIntStateOf(0)}
    val filter="$athleteId:$distance:$sort:${user.avatar}"
    suspend fun load(reset:Boolean) {
        if(!reset && (loading || data?.hasMore!=true)) return
        if(reset) {generation++;data=data?.copy(records=emptyList(),hasMore=false,nextCursor=null)}
        val token=generation;val requested="$athleteId:$distance:$sort:${user.avatar}"
        val previous=data
        loading=true;error=""
        try {
            val rule=sort.split('-')
            var path="/api/history?athleteId=$athleteId&limit=5&sort=${rule[0]}&direction=${rule[1]}&includeTrend=false"
            if(!reset) previous?.nextCursor?.let {path+="&cursor=${android.net.Uri.encode(it)}"}
            if(distance>0) path+="&distance=$distance"
            if(room!=null) path+="&room=$room"
            val result=api.request(path) {AppJson.decodeFromJsonElement(HistoryData.serializer(),it)}
            currentCoroutineContext().ensureActive()
            if(token!=generation || requested!="$athleteId:$distance:$sort:${user.avatar}") return
            data=result.copy(records=((previous?.records ?: emptyList())+result.records).distinctBy {it.raceId})
            if(distance!=0 && distance !in result.distances) {distance=0;distanceIndex=0f}
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {if(token==generation) error=e.message ?: "加载成绩失败"}
        finally {if(token==generation) loading=false}
    }
    LaunchedEffect(filter) {load(true)}
    LaunchedEffect(athleteId) {distance=0;distanceIndex=0f}
    LaunchedEffect(list,data?.nextCursor,filter) {
        snapshotFlow {list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let {it>=list.layoutInfo.totalItemsCount-3} ?: false}
            .distinctUntilChanged().collect {nearEnd->if(nearEnd && !loading && data?.hasMore==true) load(false)}
    }
    val options=listOf(0)+(data?.distances ?: emptyList())
    val dateFormat=remember {SimpleDateFormat("yyyy年M月d日 HH:mm",Locale.getDefault())}
    LazyColumn(state=list,modifier=Modifier.fillMaxSize().rtBackdrop(),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {TextButton(onClick=onBack) {Text("返回")}}
        item {RTHero(badge="训练档案",title="历史成绩",subtitle="每一次出发，都在向更好的自己靠近。")}
        item {RTButton(onClick={showSolo=true}) {RTButtonInkText("单人起跑记录")}}
        item {RTCard("筛选") {
            if((data?.athletes?.size ?: 0)>1) FilterChipRow(data!!.athletes.map {FilterOption(it.id.toString(),if(it.id==user.id) "我" else it.loginId)},athleteId.toString()) {athleteId=it.toLong()}
            val selected=options[distanceIndex.roundToInt().coerceIn(0,options.lastIndex)]
            Text(if(selected==0) "距离：全部距离" else "距离：$selected 米",color=RT.ink)
            Slider(value=distanceIndex.coerceIn(0f,max(1,options.lastIndex).toFloat()),onValueChange={distanceIndex=it.roundToInt().toFloat()},
                onValueChangeFinished={distance=options[distanceIndex.roundToInt().coerceIn(0,options.lastIndex)]},
                valueRange=0f..max(1,options.lastIndex).toFloat(),steps=max(0,options.size-2),enabled=options.size>1)
            FilterChipRow(listOf(FilterOption("date-desc","最新"),FilterOption("date-asc","最早"),FilterOption("result-asc","最快"),FilterOption("result-desc","最慢")),sort) {sort=it}
        }}
        items(data?.records ?: emptyList(),key={it.raceId}) {record->
            var shift by remember(record.raceId) {mutableFloatStateOf(0f)}
            val width=with(androidx.compose.ui.platform.LocalDensity.current) {76.dp.toPx()}
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))) {
                if(record.athleteId==user.id && shift>0) TextButton(onClick={scope.launch {
                    try {api.request("/api/history/${record.raceId}","DELETE") {};load(true)} catch(e:Exception) {if(e is CancellationException) throw e;error=e.message ?: "删除失败"}
                }},modifier=Modifier.align(Alignment.CenterStart)) {Text("删除",color=MaterialTheme.colorScheme.error)}
                RTCard("${record.distance} 米",modifier=Modifier.offset {IntOffset(shift.roundToInt(),0)}.pointerInput(record.raceId,user.id) {
                    if(record.athleteId==user.id) detectHorizontalDragGestures(onDragEnd={shift=if(shift>width/2) width else 0f}) {change,amount->change.consume();shift=(shift+amount).coerceIn(0f,width)}
                }) {
                    Text("%.3f 秒".format(record.elapsedMs/1000),fontSize=22.sp,fontWeight=FontWeight.Bold,color=RT.ink)
                    Text(dateFormat.format(Date(record.performedAt.toLong())),fontSize=12.sp,color=RT.muted)
                    Text(reactionText(record.reactionMs,record.reactionStatus),fontSize=12.sp,color=RT.muted)
                    if(record.isPb) RTChip("PB",lemon=true)
                }
            }
        }
        if(loading) item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center) {CircularProgressIndicator(Modifier.size(24.dp),color=RT.deep)}}
        if(error.isNotEmpty()) item {Text(error,color=MaterialTheme.colorScheme.error);RTButton(onClick={scope.launch {load(data==null)}}) {RTButtonInkText("重新加载成绩")}}
        if(!loading && data?.records?.isEmpty()==true) item {Text("暂无成绩，完成计时后由终点填写距离并保存。",color=RT.muted)}
        if(data?.records?.isNotEmpty()==true && data?.hasMore==false) item {Text("已加载全部成绩",color=RT.muted,fontSize=12.sp)}
        item {RTBrandFooter()}
    }
}
@Composable private fun FilterChipRow(options:List<FilterOption>,selected:String,onSelect:(String)->Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        options.forEach {option->Text(option.label,Modifier.background(if(option.id==selected) RT.aqua.copy(alpha=.3f) else RT.background,RoundedCornerShape(50)).clickable {onSelect(option.id)}.padding(horizontal=14.dp,vertical=8.dp),fontSize=13.sp,color=RT.ink)}
    }
}
