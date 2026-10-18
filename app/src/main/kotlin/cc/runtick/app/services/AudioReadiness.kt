package cc.runtick.app.services

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** 发令前的声音准备检查：媒体音量、输出路由。 */
object AudioReadiness {

    /** 媒体音量百分比（0–100）。 */
    fun volumePercent(context: Context): Int {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0
        return (manager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100.0 / max).toInt()
    }

    /** 当前输出设备名（多个用「、」连接）。 */
    fun outputRouteName(context: Context): String? {
        val devices = audioManager(context).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        if (devices.isEmpty()) return null
        return devices.joinToString("、") { deviceTypeName(it.type) }
    }

    /** 是否仅有本机扬声器在输出（耳机/蓝牙/外接音响都会导致 false）。 */
    fun isBuiltInSpeakerOnly(context: Context): Boolean {
        val devices = audioManager(context).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.isNotEmpty() && devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } && devices.none { it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
    }

    private fun audioManager(context: Context): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "扬声器"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> "耳机"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙音频"
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 音频"
        AudioDeviceInfo.TYPE_TELEPHONY -> "通话音频"
        else -> "外部设备"
    }
}
