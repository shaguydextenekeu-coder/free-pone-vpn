package com.freepone.vpn

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cyber Bridge : Serveur proxy local haute performance pour partager
 * le tunnel VPN Free-Pone avec d'autres appareils connectés en Point d'accès Wi-Fi (Hotspot).
 */
class CyberBridgeServer(private val port: Int = 8080) {

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val threadPool = Executors.newCachedThreadPool()
    val activeClientsCount = AtomicInteger(0)

    fun start(onStatusChange: ((Boolean, Int) -> Unit)? = null): Boolean {
        if (isRunning.get()) return true

        return try {
            serverSocket = ServerSocket(port)
            isRunning.set(true)
            onStatusChange?.invoke(true, 0)

            threadPool.execute {
                while (isRunning.get()) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        activeClientsCount.incrementAndGet()
                        onStatusChange?.invoke(true, activeClientsCount.get())

                        threadPool.execute {
                            try {
                                handleClient(clientSocket)
                            } catch (e: Exception) {
                                // Déconnexion client
                            } finally {
                                try { clientSocket.close() } catch (e: Exception) {}
                                activeClientsCount.decrementAndGet().coerceAtLeast(0)
                                onStatusChange?.invoke(true, activeClientsCount.get())
                            }
                        }
                    } catch (e: Exception) {
                        if (!isRunning.get()) break
                    }
                }
            }
            true
        } catch (e: Exception) {
            isRunning.set(false)
            false
        }
    }

    private fun handleClient(clientSocket: Socket) {
        val clientIn = clientSocket.getInputStream()
        val clientOut = clientSocket.getOutputStream()

        val line = readLine(clientIn) ?: return
        val parts = line.split(" ")
        if (parts.size < 2) return

        val method = parts[0].uppercase()
        val target = parts[1]

        if (method == "CONNECT") {
            // Tunnel SSL / HTTPS
            val hostPort = target.split(":")
            val host = hostPort[0]
            val port = if (hostPort.size > 1) hostPort[1].toIntOrNull() ?: 443 else 443

            // Vider les en-têtes HTTP de la requête CONNECT
            while (true) {
                val header = readLine(clientIn) ?: break
                if (header.isEmpty()) break
            }

            val remoteSocket = Socket()
            remoteSocket.connect(InetSocketAddress(host, port), 8000)

            // Réponse 200 Connection Established
            clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            clientOut.flush()

            val remoteIn = remoteSocket.getInputStream()
            val remoteOut = remoteSocket.getOutputStream()

            // Relais bidirectionnel
            val t1 = Thread { pipeStream(clientIn, remoteOut) }
            val t2 = Thread { pipeStream(remoteIn, clientOut) }
            t1.start()
            t2.start()
            t1.join()
            t2.join()

            try { remoteSocket.close() } catch (e: Exception) {}
        } else {
            // Requêtes HTTP directes (GET, POST, HEAD...)
            try {
                var host = ""
                var port = 80
                var relativePath = target

                if (target.startsWith("http://", ignoreCase = true)) {
                    val uri = java.net.URI(target)
                    host = uri.host ?: ""
                    port = if (uri.port != -1) uri.port else 80
                    relativePath = if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath + (if (uri.rawQuery != null) "?${uri.rawQuery}" else "")
                } else {
                    val hp = target.split(":")
                    host = hp[0]
                    port = if (hp.size > 1) hp[1].toIntOrNull() ?: 80 else 80
                }

                if (host.isNotEmpty()) {
                    val remoteSocket = Socket()
                    remoteSocket.connect(InetSocketAddress(host, port), 8000)
                    val remoteIn = remoteSocket.getInputStream()
                    val remoteOut = remoteSocket.getOutputStream()

                    val httpVer = if (parts.size > 2) parts[2] else "HTTP/1.1"
                    remoteOut.write("$method $relativePath $httpVer\r\n".toByteArray())
                    remoteOut.flush()

                    val t1 = Thread { pipeStream(clientIn, remoteOut) }
                    val t2 = Thread { pipeStream(remoteIn, clientOut) }
                    t1.start()
                    t2.start()
                    t1.join()
                    t2.join()

                    try { remoteSocket.close() } catch (e: Exception) {}
                }
            } catch (e: Exception) {}
        }
    }

    private fun pipeStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        try {
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (e: Exception) {}
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var c: Int
        while (input.read().also { c = it } != -1) {
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return if (sb.isEmpty() && c == -1) null else sb.toString()
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverSocket = null
        activeClientsCount.set(0)
    }

    fun isRunning(): Boolean = isRunning.get()
}
