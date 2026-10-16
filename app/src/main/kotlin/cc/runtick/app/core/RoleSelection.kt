package cc.runtick.app.core

/** Latest tap wins immediately; serialize writes and ignore stale broadcasts. */
class RoleSelection {
    var optimistic: String? = null
        private set
    var inFlight: String? = null
        private set
    val saving: Boolean get() = inFlight != null
    fun begin(target: String, serverRole: String?): Boolean {
        if (target == effective(serverRole)) return false
        optimistic = target
        if (inFlight != null) return false
        inFlight = target
        return true
    }
    fun succeeded(serverRole: String?) {
        if (optimistic != inFlight) { inFlight = optimistic; return }
        inFlight = null
        if (serverRole == optimistic) optimistic = null
    }
    fun fail() {
        if (optimistic != inFlight) { inFlight = optimistic; return }
        optimistic = null; inFlight = null
    }
    fun receivedServerState(serverRole: String?) {
        if (!saving && serverRole == optimistic) optimistic = null
    }
    fun effective(serverRole: String?): String? = optimistic ?: serverRole
}
