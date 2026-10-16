package cc.runtick.app.core

/** 服务器网关地址（与 H5 同源；原生客户端不携带 Origin 头即可通过同源校验）。 */
object AppConfiguration {
    const val SERVER_URL = "https://app.runtick.cc/"
}
