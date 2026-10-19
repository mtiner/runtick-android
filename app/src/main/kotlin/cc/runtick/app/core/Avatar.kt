package cc.runtick.app.core

import kotlinx.serialization.Serializable
import java.net.URLEncoder

@Serializable data class AvatarOption(val filename: String,val description: String? = null) {
    val title: String get() = description ?: filename.removeSuffix(".png")
}
@Serializable data class AvatarGallery(val avatars: List<AvatarOption>)

object AvatarImageURL {
    fun make(filename: String?): String? {
        if (filename == null || filename.length <= 4 || !filename.endsWith(".png") || filename.contains("..") || !filename.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in " ._-" }) return null
        if(!filename.contains(" ") && filename!="default.png" && filename==filename.lowercase()) return "https://api.runtick.cc/api/avatars/$filename"
        return "https://download.runtick.cc/avatar/" + URLEncoder.encode(filename,"UTF-8").replace("+","%20")
    }
}
