package cc.runtick.app

import cc.runtick.app.core.*
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class AvatarTest {
    @Test fun rawSpacesAreEncodedOnce() {
        for(filename in listOf("Nike Dragonfly.png","adidas Adizero Prime SP 2.png","New Balance FuelCell SuperComp MD-X.png","PUMA evoSPEED Sprint Nitro 2.png")) {
            val url=AvatarImageURL.make(filename)!!
            assertTrue(url.contains("%20")); assertFalse(url.contains("%2520")); assertFalse(url.contains('+'))
            assertEquals(filename,URLDecoder.decode(url.substringAfterLast('/'),"UTF-8"))
        }
        assertEquals("https://download.runtick.cc/avatar/Nike%20Dragonfly.png",AvatarImageURL.make("Nike Dragonfly.png"))
    }
    @Test fun unsafeFilenamesRejected() {
        for(value in listOf(null,"",".png","../Nike Dragonfly.png","Nike%20Dragonfly.png","avatar/Nike Dragonfly.png","https://evil.example/a.png")) assertNull(AvatarImageURL.make(value))
    }
    @Test fun legacyAndCurrentUsersAndMembersDecode() {
        assertNull(AppJson.decodeFromString(User.serializer(),"""{"id":1,"loginId":"runner"}""").avatar)
        val user=AppJson.decodeFromString(User.serializer(),"""{"id":1,"loginId":"runner","avatar":"Nike Dragonfly.png"}""")
        assertEquals("Nike Dragonfly.png",user.avatar)
        assertEquals(user,AppJson.decodeFromString(User.serializer(),AppJson.encodeToString(User.serializer(),user)))
        val member=AppJson.decodeFromString(MemberState.serializer(),"""{"loginId":"runner","deviceRole":"start","online":true,"avatar":"Nike Dragonfly.png"}""")
        assertEquals(user.avatar,member.avatar)
    }
}
