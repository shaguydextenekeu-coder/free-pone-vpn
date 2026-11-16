package com.freepone.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import hev.htproxy.TProxyService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Service VPN Android Natif pour Free-Pone.
 * Établit l'interface TUN, démarre le pont SOCKS5 local FreePoneRelay
 * et active le moteur C natif libhev-socks5-tunnel pour router 100% du trafic.
 */
class FreePoneVpnService : VpnService() {

    companion object {
        const val ACTION_START_VPN = "com.freepone.vpn.START"
        const val ACTION_STOP_VPN = "com.freepone.vpn.STOP"
        const val CHANNEL_ID = "freepone_tunnel_channel"
        const val NOTIFICATION_ID = 2001
        private const val TAG = "FreePoneVpnService"

        @Volatile
        var isVpnActive: Boolean = false
            private set

        @Volatile
        var vpnSessionVoucher: String? = null
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val isRunning = AtomicBoolean(false)
    private var relay: FreePoneRelay? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_VPN

        if (action == ACTION_STOP_VPN) {
            stopTunnel()
            return START_NOT_STICKY
        }

        vpnSessionVoucher = intent?.getStringExtra("VOUCHER_CODE")
        val serverName = intent?.getStringExtra("SERVER_NAME") ?: "Afrique du Sud"

        startForeground(NOTIFICATION_ID, buildNotification("Connexion sécurisée à $serverName..."))
        startTunnel()

        return START_STICKY
    }

    private fun startTunnel() {
        if (isRunning.get()) return
        isRunning.set(true)

        thread(name = "FreePone-TunWorker") {
            try {
                // 1. Démarrage du relais SOCKS5 local protégé
                relay = FreePoneRelay(this, "92.4.142.34", 80, 10871)
                relay?.start()

                // Attendre que le relay SOCKS5 soit prêt à accepter des connexions
                // 500ms pour être sûr même sur les appareils lents
                Thread.sleep(500)

                // 2. Génération du fichier de configuration YAML pour libhev-socks5-tunnel
                val configFile = generateTunConfig()
                Log.i(TAG, "Config TUN générée: ${configFile.absolutePath}")

                // 3. Construction de l'interface TUN Android avec routage global
                val builder = Builder()
                    .setSession("Free-Pone Cyber Tunnel")
                    .addAddress("10.8.0.2", 24)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .setMtu(1420)
                    .setBlocking(false)

                // Exclure notre propre app du tunnel VPN :
                // le relay peut ainsi atteindre directement le VPS sans boucle TUN
                try {
                    builder.addDisallowedApplication(packageName)
                    Log.i(TAG, "App exclue du TUN: $packageName")
                } catch (e: Exception) {
                    Log.w(TAG, "Impossible d'exclure l'app: ${e.message}")
                }

                vpnInterface = builder.establish()

                if (vpnInterface == null) {
                    Log.e(TAG, "Impossible d'établir l'interface TUN — permission manquante ?")
                    stopTunnel()
                    return@thread
                }

                isVpnActive = true
                updateNotification("Tunnel Free-Pone Actif · Protection en cours")

                // 4. Lancement du moteur JNI natif hev-socks5-tunnel
                val fd = vpnInterface!!.fd
                Log.i(TAG, "Démarrage moteur natif TProxy (fd=$fd, config=${configFile.absolutePath})")
                val success = TProxyService.TProxyStartService(configFile.absolutePath, fd)
                Log.i(TAG, "Moteur TProxy démarré — résultat: $success")

                if (!success) {
                    Log.e(TAG, "Échec démarrage moteur natif TProxy")
                    stopTunnel()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Erreur démarrage tunnel: ${e.message}", e)
                stopTunnel()
            }
        }
    }

    private fun generateTunConfig(): File {
        val file = File(filesDir, "tun.yaml")
        val yaml = """
            tunnel:
              mtu: 1420
              ipv4: 10.8.0.2
            socks5:
              address: 127.0.0.1
              port: 10871
              udp: tcp
            mapdns:
              address: 1.1.1.1
              port: 53
              network: 240.0.0.0
              netmask: 240.0.0.0
              cache-size: 10000
            misc:
              connect-timeout: 5000
              tcp-read-write-timeout: 60000
              tcp-buffer-size: 65536
              max-session-count: 512
              log-file: stderr
              log-level: error
        """.trimIndent()

        FileOutputStream(file).use { out ->
            out.write(yaml.toByteArray(Charsets.UTF_8))
        }
        return file
    }

    private fun stopTunnel() {
        if (!isRunning.getAndSet(false)) return

        isVpnActive = false
        vpnSessionVoucher = null

        try {
            TProxyService.TProxyStopService()
        } catch (e: Exception) {
            Log.e(TAG, "Erreur arrêt TProxy: ${e.message}")
        }

        try {
            relay?.stop()
            relay = null
        } catch (_: Exception) {}

        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (_: IOException) {}
        isVpnActive = false
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTunnel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Statut du Tunnel Free-Pone",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Indique l'état actif du tunnel VPN chiffré"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Free-Pone")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_vpn_key)
            .setColor(0xFF00F0FF.toInt())
            .setContentIntent(pendingIntent)
            .setShowWhen(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
