package hev.htproxy

/**
 * JNI Binding pour libhev-socks5-tunnel.so
 * Assure la conversion transparente des paquets IP du TUN Android vers le proxy SOCKS5 local.
 */
object TProxyService {
    init {
        try {
            System.loadLibrary("hev-socks5-tunnel")
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    @JvmStatic
    external fun TProxyStartService(configPath: String, fd: Int): Boolean

    @JvmStatic
    external fun TProxyStopService(): Boolean

    @JvmStatic
    external fun TProxyIsRunning(): Boolean

    @JvmStatic
    external fun TProxyGetStats(): LongArray?
}
