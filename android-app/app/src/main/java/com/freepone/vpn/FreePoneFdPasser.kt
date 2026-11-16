package com.freepone.vpn

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FreePoneFdPasser — Passe le TUN fd au binaire natif via LocalSocket (SCM_RIGHTS).
 *
 * Protocole (identique à Geny) :
 * ─────────────────────────────────────────────────────────────────
 *  1. Java crée un LocalServerSocket lié au chemin socketPath
 *  2. Java démarre le binaire natif via ProcessBuilder
 *  3. Le binaire se connecte au socket dès son démarrage
 *  4. Java envoie le TUN fd via setFileDescriptorsForSend() (SCM_RIGHTS)
 *  5. Java écrit un octet 0x01 pour signaler l'envoi du fd
 *  6. Boucle : le binaire envoie ses sockets à protéger (bypass TUN loop)
 *              Java appelle VpnService.protect() sur chaque fd reçu
 * ─────────────────────────────────────────────────────────────────
 */
class FreePoneFdPasser(
    private val vpnService: VpnService,
    val socketPath: String
) {
    private var serverSocket: LocalServerSocket? = null
    private var bindSocket: LocalSocket? = null
    private val active = AtomicBoolean(true)
    private val executor = Executors.newSingleThreadExecutor()

    companion object {
        private const val TAG = "FreePoneFdPasser"
        private const val ACCEPT_TIMEOUT_SEC = 8L

        /**
         * Extrait le numéro entier du fd depuis un objet FileDescriptor.
         * VpnService.protect(int) exige un fd entier.
         */
        fun fdToInt(fd: FileDescriptor): Int {
            return try {
                val field = FileDescriptor::class.java.getDeclaredField("fd")
                field.isAccessible = true
                field.getInt(fd)
            } catch (e: Exception) {
                Log.w(TAG, "fdToInt: ${e.message}")
                -1
            }
        }
    }

    /**
     * Crée et lie le LocalSocket Unix. À appeler AVANT de lancer le binaire.
     */
    @Throws(Exception::class)
    fun setup() {
        // Nettoyer l'ancien socket si présent
        File(socketPath).delete()

        val lSocket = LocalSocket()
        val addr = LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM)
        lSocket.bind(addr)

        // 0777 : le binaire (lancé dans le même uid) doit pouvoir se connecter
        try { Os.chmod(socketPath, 0x1FF) } catch (_: Exception) {}

        bindSocket    = lSocket
        serverSocket  = LocalServerSocket(lSocket.fileDescriptor)
        Log.i(TAG, "LocalServerSocket lié: $socketPath")
    }

    /**
     * Démarre le thread passeur de fd.
     * Accepte la connexion du binaire, envoie le TUN fd, puis protège les sockets reçus.
     *
     * @param tunFd  FileDescriptor de l'interface TUN Android
     */
    fun startAsync(tunFd: FileDescriptor) {
        executor.execute {
            try {
                val srv = serverSocket ?: return@execute

                // ── Attendre la connexion du binaire avec timeout ──────────
                Log.i(TAG, "En attente de la connexion du binaire ($ACCEPT_TIMEOUT_SEC s)...")
                val future = Executors.newSingleThreadExecutor().submit<LocalSocket> { srv.accept() }

                val conn = try {
                    future.get(ACCEPT_TIMEOUT_SEC, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    future.cancel(true)
                    Log.w(TAG, "Timeout/erreur accept: ${e.message}")
                    return@execute
                }

                Log.i(TAG, "✅ Binaire connecté — envoi du TUN fd")

                // ── Envoyer le TUN fd via SCM_RIGHTS ──────────────────────
                conn.setFileDescriptorsForSend(arrayOf(tunFd))
                conn.outputStream.write(0x01)   // signal "fd envoyé"
                conn.outputStream.flush()
                conn.setFileDescriptorsForSend(null)  // réinitialiser

                Log.i(TAG, "✅ TUN fd transmis au binaire natif")

                // ── Boucle de protection des sockets du binaire ────────────
                // Quand le binaire ouvre un socket vers Internet, il l'envoie ici
                // pour qu'on l'exclue du TUN (évite la boucle de routage)
                val buf = ByteArray(1)
                while (active.get()) {
                    val n = try { conn.inputStream.read(buf) } catch (_: Exception) { -1 }
                    if (n == -1) break

                    val fds = conn.ancillaryFileDescriptors
                    if (fds != null) {
                        for (fd in fds) {
                            val fdInt = fdToInt(fd)
                            if (fdInt > 0) {
                                val protected = vpnService.protect(fdInt)
                                Log.d(TAG, "Socket protégé fd=$fdInt résultat=$protected")
                            }
                        }
                    }
                }

                Log.i(TAG, "FdPasser: connexion fermée")
                try { conn.close() } catch (_: Exception) {}

            } catch (e: Exception) {
                if (active.get()) Log.e(TAG, "FdPasser erreur: ${e.message}")
            }
        }
    }

    fun stop() {
        active.set(false)
        executor.shutdownNow()
        try { serverSocket?.close()  } catch (_: Exception) {}
        try { bindSocket?.close()    } catch (_: Exception) {}
        try { File(socketPath).delete() } catch (_: Exception) {}
        Log.i(TAG, "FdPasser arrêté")
    }
}
