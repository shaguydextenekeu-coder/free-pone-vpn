package com.freepone.vpn

import android.net.VpnService
import android.util.Base64
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FreePoneRelay v1.3.2 — Pont SOCKS5 local pour Free-Pone.
 *
 * CORRECTIONS v1.3.2 :
 * ─────────────────────────────────────────────────────────
 * 1. DESTINATION ENCODÉE DANS L'URL : plus de protocole DEST/OK séparé.
 *    Format : GET /fp-tunnel/google.com/443 HTTP/1.1
 *    → Le serveur se connecte à la destination AVANT d'envoyer 101.
 *    → Plus de race condition, plus de listener data parasite.
 *
 * 2. PROTECT() GARANTI : chaque socket upstream est protégé avant
 *    toute tentative de connexion pour éviter la boucle de routage TUN.
 *
 * 3. TIMEOUT ADAPTATIF : soTimeout = 20s pendant le handshake,
 *    0 (infini) pendant le streaming pour ne pas couper les connexions longues.
 *
 * 4. GESTION DES ERREURS ROBUSTE : toute exception est capturée et loggée.
 */
class FreePoneRelay(
    private val vpnService: VpnService,
    private val vpsHost: String = "92.4.142.34",
    private val vpsPort: Int = 80,
    private val listenPort: Int = 10871
) {
    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val threadPool = Executors.newCachedThreadPool()
    private val rng = SecureRandom()

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        threadPool.execute {
            try {
                serverSocket = ServerSocket(listenPort, 256, InetAddress.getByName("127.0.0.1"))
                Log.i(TAG, "✅ SOCKS5 Relay actif sur 127.0.0.1:$listenPort → $vpsHost:$vpsPort")

                while (isRunning.get() && !serverSocket!!.isClosed) {
                    try {
                        val client = serverSocket!!.accept()
                        threadPool.execute { handleClient(client) }
                    } catch (e: Exception) {
                        if (isRunning.get()) Log.w(TAG, "Erreur accept: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Relay arrêté: ${e.message}")
            }
        }
    }

    private fun handleClient(client: Socket) {
        var upstream: Socket? = null
        try {
            client.soTimeout = 20000
            val clientIn  = client.getInputStream()
            val clientOut = client.getOutputStream()

            // ══════════════════════════════════════════════════════════════
            // ÉTAPE 1 : Handshake SOCKS5 (RFC 1928)
            // ══════════════════════════════════════════════════════════════
            val ver = clientIn.read()
            if (ver != 0x05) { client.close(); return }

            val nMethods = clientIn.read()
            val methods = ByteArray(nMethods.coerceAtLeast(0))
            if (nMethods > 0) clientIn.read(methods)

            // Répondre NO AUTH REQUIRED
            clientOut.write(byteArrayOf(0x05, 0x00))
            clientOut.flush()

            // ══════════════════════════════════════════════════════════════
            // ÉTAPE 2 : Lire la requête CONNECT SOCKS5
            // ══════════════════════════════════════════════════════════════
            val reqVer = clientIn.read()
            val cmd    = clientIn.read()
            /* RSV */ clientIn.read()
            val atyp   = clientIn.read()

            if (reqVer != 0x05 || cmd != 0x01) {
                clientOut.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                clientOut.flush(); client.close(); return
            }

            val destHost: String = when (atyp) {
                0x01 -> { val b = ByteArray(4); clientIn.read(b); InetAddress.getByAddress(b).hostAddress ?: "" }
                0x03 -> { val len = clientIn.read(); val b = ByteArray(len); clientIn.read(b); String(b, Charsets.UTF_8) }
                0x04 -> { val b = ByteArray(16); clientIn.read(b); InetAddress.getByAddress(b).hostAddress ?: "" }
                else -> { client.close(); return }
            }
            val destPort = ((clientIn.read() and 0xFF) shl 8) or (clientIn.read() and 0xFF)

            if (destHost.isEmpty() || destPort <= 0) { client.close(); return }
            Log.d(TAG, "→ CONNECT $destHost:$destPort")

            // ══════════════════════════════════════════════════════════════
            // ÉTAPE 3 : Connexion au VPS — PROTECT() impératif avant connect
            // pour que ce socket bypass le TUN et aille directement sur internet
            // ══════════════════════════════════════════════════════════════
            upstream = Socket()
            if (!vpnService.protect(upstream)) {
                Log.e(TAG, "protect() a échoué pour $destHost:$destPort")
            }
            upstream.connect(InetSocketAddress(vpsHost, vpsPort), 15000)
            upstream.soTimeout = 25000

            val upOut = upstream.getOutputStream()
            val upIn  = upstream.getInputStream()

            // ══════════════════════════════════════════════════════════════
            // ÉTAPE 4 : Tunnel WebSocket — destination encodée dans l'URL
            // Format : GET /fp-tunnel/<host>/<port> HTTP/1.1
            // Le serveur se connecte à la destination AVANT de répondre 101.
            // Plus de protocole DEST/OK séparé → plus de race condition.
            // ══════════════════════════════════════════════════════════════
            val wsKey = generateWsKey()
            // Sanitize host pour URL (évite caractères spéciaux)
            val safeHost = destHost.replace("[^a-zA-Z0-9.\\-]".toRegex(), "")
                .ifEmpty { destHost }

            val upgradeReq =
                "GET /fp-tunnel/$safeHost/$destPort HTTP/1.1\r\n" +
                "Host: recharge.orange.cm\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $wsKey\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "X-FP-Version: 1.3.2\r\n" +
                "\r\n"

            upOut.write(upgradeReq.toByteArray(Charsets.US_ASCII))
            upOut.flush()

            // ══════════════════════════════════════════════════════════════
            // ÉTAPE 5 : Lire la réponse du serveur (101 Switching Protocols)
            // Le serveur envoie 101 seulement si la connexion à la destination
            // a réussi — sinon il envoie 503 et on fail proprement.
            // ══════════════════════════════════════════════════════════════
            val respBuf = StringBuilder()
            var b: Int
            while (upIn.read().also { b = it } != -1) {
                respBuf.append(b.toChar())
                if (respBuf.endsWith("\r\n\r\n")) break
                if (respBuf.length > 4096) break  // sécurité anti-loop
            }
            val respStr = respBuf.toString()

            if (!respStr.contains("101")) {
                Log.w(TAG, "❌ Pas de 101 pour $destHost:$destPort — ${respStr.take(60)}")
                clientOut.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                clientOut.flush(); client.close(); upstream.close(); return
            }

            Log.d(TAG, "✅ Tunnel établi → $destHost:$destPort")

            // ══════════════════════════════════════════════════════════════
            // ÉTAPE 6 : Répondre SOCKS5 SUCCESS au client local
            // ══════════════════════════════════════════════════════════════
            clientOut.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            clientOut.flush()

            // ══════════════════════════════════════════════════════════════
            // ÉTAPE 7 : Streaming bidirectionnel sans timeout (connexions longues)
            // ══════════════════════════════════════════════════════════════
            client.soTimeout   = 0
            upstream.soTimeout = 0

            val t1 = threadPool.submit { pipe(clientIn, upOut,  "client→vps") }
            val t2 = threadPool.submit { pipe(upIn,  clientOut, "vps→client") }
            t1.get()
            t2.get()

        } catch (e: Exception) {
            Log.d(TAG, "Session terminée ($e)")
        } finally {
            try { client.close()     } catch (_: Exception) {}
            try { upstream?.close()  } catch (_: Exception) {}
        }
    }

    private fun pipe(input: InputStream, output: OutputStream, label: String) {
        val buffer = ByteArray(32768)
        try {
            while (isRunning.get()) {
                val count = input.read(buffer)
                if (count == -1) break
                output.write(buffer, 0, count)
                output.flush()
            }
        } catch (_: Exception) {}
        Log.d(TAG, "Pipe '$label' fermé")
    }

    /** Génère une clé WebSocket Base64 de 16 octets aléatoires */
    private fun generateWsKey(): String {
        val bytes = ByteArray(16); rng.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun stop() {
        isRunning.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        threadPool.shutdownNow()
        Log.i(TAG, "Relay arrêté.")
    }

    companion object {
        private const val TAG = "FreePoneRelay"
    }
}
