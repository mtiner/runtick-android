package cc.runtick.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import cc.runtick.app.core.*
import cc.runtick.app.services.AppModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

// Separate cookie-free client: never send API session cookies to the image host.
private object AvatarImages {
    private val client = OkHttpClient.Builder().callTimeout(8,TimeUnit.SECONDS).build()
    private val cache = object: LruCache<String,Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String,value: Bitmap) = value.byteCount
    }
    suspend fun load(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object: Callback {
                override fun onFailure(call: Call,e: IOException) { if(continuation.isActive) continuation.resume(null) }
                override fun onResponse(call: Call,response: Response) {
                    val bitmap = runCatching {
                        response.use {
                            if(!it.isSuccessful) return@use null
                            val source = it.body?.source() ?: return@use null
                            source.request(2L * 1024 * 1024 + 1)
                            if(source.buffer.size > 2L * 1024 * 1024) return@use null
                            val bytes=source.readByteArray()
                            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                            if(bounds.outWidth !in 1..10000 || bounds.outHeight !in 1..10000) return@use null
                            var scale=1
                            while(bounds.outWidth/scale > 256 || bounds.outHeight/scale > 256) scale *= 2
                            BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=scale })
                        }
                    }.getOrNull()
                    if(bitmap != null) cache.put(url,bitmap)
                    if(continuation.isActive) continuation.resume(bitmap)
                }
            })
        }
    }
}

@Composable
fun UserAvatar(filename: String?,name: String,size: Dp = 40.dp) {
    val url=AvatarImageURL.make(filename)
    val bitmap by produceState<Bitmap?>(null,url) { value=null; if(url != null) value=AvatarImages.load(url) }
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(RT.aqua.copy(alpha=0.22f)).clearAndSetSemantics {},contentAlignment=Alignment.Center) {
        val image=bitmap
        if(image != null) Image(image.asImageBitmap(),contentDescription=null,contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize().padding(size * 0.06f))
        else Text(name.take(1).uppercase(),fontSize=(size.value * 0.4f).sp,fontWeight=FontWeight.Bold,color=RT.deep)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarPicker(model: AppModel,user: User,onDismiss: () -> Unit) {
    val scope=rememberCoroutineScope()
    var avatars by remember(user.id) { mutableStateOf<List<AvatarOption>>(emptyList()) }
    var selected by remember(user.id) { mutableStateOf(user.avatar) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(user.id,reload) {
        loading=true; error=""
        try {
            val gallery=model.api!!.request("/api/avatars") { AppJson.decodeFromJsonElement(AvatarGallery.serializer(),it) }
            avatars=gallery.avatars.filter { AvatarImageURL.make(it.filename) != null }
            if(avatars.isEmpty()) error="头像图库暂时不可用，请稍后重试。"
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { error=e.message ?: "加载头像图库失败" }
        finally { loading=false }
    }
    ModalBottomSheet(onDismissRequest={ if(!saving) onDismiss() },sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true,confirmValueChange={ !saving })) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("选择头像",Modifier.weight(1f),fontSize=20.sp,fontWeight=FontWeight.Bold,color=RT.ink)
                TextButton(onClick=onDismiss,enabled=!saving) { Text("取消") }
            }
            if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
            if(avatars.isEmpty() && !loading) TextButton(onClick={reload++}) { Text("重新加载") }
            LazyVerticalGrid(columns=GridCells.Adaptive(140.dp),modifier=Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                items(avatars,key={it.filename}) { option ->
                    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(if(selected==option.filename) RT.aqua.copy(alpha=0.18f) else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(enabled=!saving) { selected=option.filename }.semantics { contentDescription=option.title; this.selected=selected==option.filename }.padding(10.dp),
                        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        UserAvatar(option.filename,"头像",80.dp)
                        Text(option.title,fontSize=12.sp,textAlign=TextAlign.Center,maxLines=3,overflow=TextOverflow.Ellipsis,color=RT.ink)
                        Text(if(selected==option.filename) "已选择" else "选择",fontSize=12.sp,color=RT.deep)
                    }
                }
            }
            RTButton(onClick={
                val filename=selected ?: return@RTButton
                saving=true; error=""
                scope.launch {
                    try { model.updateAvatar(filename); onDismiss() }
                    catch(e: CancellationException) { throw e }
                    catch(e: Exception) { error=e.message ?: "头像保存失败，请重试" }
                    finally { saving=false }
                }
            },enabled=!saving && !loading && selected!=user.avatar && avatars.any { it.filename==selected },modifier=Modifier.fillMaxWidth()) {
                RTButtonInkText(if(saving) "保存中…" else "保存头像")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
