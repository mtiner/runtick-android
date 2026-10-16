package cc.runtick.app.core

/** 客户端错误：status 为 HTTP 状态码（网络层失败时为 0），code 为服务器错误码。 */
class ClientError(val status: Int, val code: String?, message: String) : RuntimeException(message)
