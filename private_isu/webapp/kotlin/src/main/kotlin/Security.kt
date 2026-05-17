package io.github.msksgm

import io.ktor.server.application.*
import io.ktor.server.sessions.*
import net.rubyeye.xmemcached.XMemcachedClientBuilder
import net.rubyeye.xmemcached.utils.AddrUtil

fun Application.configureSecurity() {
    val memcachedAddress = System.getenv("ISUCONP_MEMCACHED_ADDRESS") ?: "localhost"
    val memcachedClient = XMemcachedClientBuilder(AddrUtil.getAddresses(memcachedAddress)).build()
    val storage = MemcachedSessionStorage(memcachedClient)

    install(Sessions) {
        cookie<UserSession>("isuconp-kotlin.session", storage) {
            cookie.extensions["SameSite"] = "lax"
        }
    }
}
