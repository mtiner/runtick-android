package cc.runtick.app.core

/**
 * 乐观角色选择：本地高亮与服务端权威状态分离。
 * 请求在途时保留乐观值，服务器确认或下一次房间状态到达后再收敛。
 */
class RoleSelection {
    var optimistic: String? = null
        private set
    var saving: Boolean = false
        private set

    /** 请求发出前：立即展示目标角色。 */
    fun choose(target: String) {
        optimistic = target
        saving = true
    }

    /** HTTP 成功后：服务端已确认则清掉乐观值。 */
    fun confirmServer(serverRole: String?) {
        saving = false
        if (serverRole == optimistic) optimistic = null
    }

    /** 请求失败：回滚乐观值。 */
    fun fail() {
        optimistic = null
        saving = false
    }

    /** 收到房间状态：请求在途时保留本地高亮，其余情况以服务端为准。 */
    fun receivedServerState() {
        if (!saving) optimistic = null
    }

    /** 展示值：乐观值优先。 */
    fun effective(serverRole: String?): String? = optimistic ?: serverRole
}
