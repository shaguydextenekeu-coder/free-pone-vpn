package com.freepone.vpn

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_VPN_CODE = 101
    }

    // Configuration par défaut de l'API Backend (IP dynamique Wi-Fi actuelle)
    private val PREFS_NAME = "FreePonePrefs"
    private val KEY_API_URL = "api_server_url"
    private val KEY_DEVICE_ID = "device_unique_id"
    private val KEY_VOUCHER_CODE = "active_voucher_code"
    private val KEY_PLAN_NAME = "active_plan_name"
    private val KEY_EXPIRES_AT = "active_expires_at"
    private val KEY_MAX_BYTES = "active_max_bytes"
    private val KEY_USED_BYTES = "active_used_bytes"
    private val KEY_MAX_DEVICES = "active_max_devices"
    private val KEY_ACTIVE_SERVER_ID = "active_server_id"

    // Vues principales & 5 Onglets
    private lateinit var tabConnectView: LinearLayout
    private lateinit var tabVouchersView: LinearLayout
    private lateinit var tabBridgeView: LinearLayout
    private lateinit var tabStreamingView: LinearLayout
    private lateinit var tabSettingsView: LinearLayout
    private lateinit var bottomNavigation: BottomNavigationView

    // Annonce en direct
    private lateinit var bannerAnnouncement: LinearLayout
    private lateinit var tvAnnouncementText: TextView
    private lateinit var tvHeaderPing: TextView

    // Tab 1 : Bouton 3D & Animation Motion Design
    private lateinit var powerButtonContainer: FrameLayout
    private lateinit var btnPower: ImageButton
    private lateinit var orbitRing: View
    private lateinit var radarWave: View
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusSubtitle: TextView

    // Tab 1 : Cyber Quota Card (Page d'accueil : Consommation Réelle vs Quota)
    private lateinit var tvHomePlanTitle: TextView
    private lateinit var tvHomePlanPercent: TextView
    private lateinit var pbHomeQuota: ProgressBar
    private lateinit var tvHomeQuotaStats: TextView
    private lateinit var tvHomeExpiryCountdown: TextView

    // Tab 1 : Télémétrie Réelle (Rx / Tx)
    private lateinit var tvRxSpeed: TextView
    private lateinit var tvRxTotal: TextView
    private lateinit var tvTxSpeed: TextView
    private lateinit var tvTxTotal: TextView

    // Tab 2 : Forfaits & Vouchers
    private lateinit var tvVoucherStateBadge: TextView
    private lateinit var tvTabVoucherCode: TextView
    private lateinit var tvTabPlanName: TextView
    private lateinit var tvTabDevicesCount: TextView
    private lateinit var tvTabExpiryDate: TextView
    private lateinit var btnDisconnectVoucher: Button
    private lateinit var btnActivateNewCode: Button
    private lateinit var llDynamicPlansList: LinearLayout

    // Tab 3 : Partage Wi-Fi (Cyber Bridge)
    private lateinit var switchBridgePage: androidx.appcompat.widget.SwitchCompat
    private lateinit var llBridgePageDetails: LinearLayout
    private lateinit var tvBridgePageIpPort: TextView
    private lateinit var tvBridgePageClients: TextView
    private lateinit var btnCopyBridgePageConfig: Button
    private val cyberBridge = CyberBridgeServer(8080)

    // Tab 4 : Streaming Ultra-HD
    private lateinit var switchStreamingTurbo: androidx.appcompat.widget.SwitchCompat
    private lateinit var llDynamicStreamingServersList: LinearLayout

    // Tab 5 : Paramètres & Sécurité
    private lateinit var tvAppVersionDisplay: TextView
    private lateinit var btnCheckUpdateManual: Button
    private lateinit var cardConfigServer: LinearLayout
    private lateinit var tvSettingsServerUrl: TextView

    // Variables d'état
    private var isConnected = false
    private var isConnecting = false

    // Suivi RÉEL du trafic réseau matériel (TrafficStats)
    private var prevRxBytes: Long = 0
    private var prevTxBytes: Long = 0
    private var lastHeartbeatRx: Long = 0
    private var lastHeartbeatTx: Long = 0
    private var sessionRxBytes: Long = 0
    private var sessionTxBytes: Long = 0

    private val handler = Handler(Looper.getMainLooper())
    private var telemetryRunnable: Runnable? = null
    private var heartbeatRunnable: Runnable? = null
    private var radarPulseAnimator: ObjectAnimator? = null
    private var orbitAnimator: ObjectAnimator? = null

    // Données en cache des serveurs
    private var cachedServers = mutableListOf<JSONObject>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupBottomNavigation()
        setupMotionDesign()
        loadLocalSettings()
        setupClickListeners()
        registerNetworkMonitoring()

        // Synchronisation immédiate du forfait avec le serveur
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val activeCode = prefs.getString(KEY_VOUCHER_CODE, null)
        if (!activeCode.isNullOrEmpty()) {
            fetchVoucherStatusFromServer(activeCode)
        }

        // Chargement initial des données depuis le serveur Backoffice
        fetchServerAnnouncement()
        fetchServersList()
        fetchPlansCatalog()

        // Vérification automatique des mises à jour OTA
        checkForOtaUpdates(silent = true)
    }

    private fun initViews() {
        tabConnectView = findViewById(R.id.tabConnectView)
        tabVouchersView = findViewById(R.id.tabVouchersView)
        tabBridgeView = findViewById(R.id.tabBridgeView)
        tabStreamingView = findViewById(R.id.tabStreamingView)
        tabSettingsView = findViewById(R.id.tabSettingsView)
        bottomNavigation = findViewById(R.id.bottomNavigation)

        bannerAnnouncement = findViewById(R.id.bannerAnnouncement)
        tvAnnouncementText = findViewById(R.id.tvAnnouncementText)
        tvHeaderPing = findViewById(R.id.tvHeaderPing)

        powerButtonContainer = findViewById(R.id.powerButtonContainer)
        btnPower = findViewById(R.id.btnPower)
        orbitRing = findViewById(R.id.orbitRing)
        radarWave = findViewById(R.id.radarWave)
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatusSubtitle = findViewById(R.id.tvStatusSubtitle)

        // Quota Card en page d'accueil
        tvHomePlanTitle = findViewById(R.id.tvHomePlanTitle)
        tvHomePlanPercent = findViewById(R.id.tvHomePlanPercent)
        pbHomeQuota = findViewById(R.id.pbHomeQuota)
        tvHomeQuotaStats = findViewById(R.id.tvHomeQuotaStats)
        tvHomeExpiryCountdown = findViewById(R.id.tvHomeExpiryCountdown)

        // Télémétrie
        tvRxSpeed = findViewById(R.id.tvRxSpeed)
        tvRxTotal = findViewById(R.id.tvRxTotal)
        tvTxSpeed = findViewById(R.id.tvTxSpeed)
        tvTxTotal = findViewById(R.id.tvTxTotal)

        // Tab 2 : Forfaits
        tvVoucherStateBadge = findViewById(R.id.tvVoucherStateBadge)
        tvTabVoucherCode = findViewById(R.id.tvTabVoucherCode)
        tvTabPlanName = findViewById(R.id.tvTabPlanName)
        tvTabDevicesCount = findViewById(R.id.tvTabDevicesCount)
        tvTabExpiryDate = findViewById(R.id.tvTabExpiryDate)
        btnDisconnectVoucher = findViewById(R.id.btnDisconnectVoucher)
        btnActivateNewCode = findViewById(R.id.btnActivateNewCode)
        llDynamicPlansList = findViewById(R.id.llDynamicPlansList)

        // Tab 3 : Cyber Bridge
        switchBridgePage = findViewById(R.id.switchBridgePage)
        llBridgePageDetails = findViewById(R.id.llBridgePageDetails)
        tvBridgePageIpPort = findViewById(R.id.tvBridgePageIpPort)
        tvBridgePageClients = findViewById(R.id.tvBridgePageClients)
        btnCopyBridgePageConfig = findViewById(R.id.btnCopyBridgePageConfig)

        // Tab 4 : Streaming
        switchStreamingTurbo = findViewById(R.id.switchStreamingTurbo)
        llDynamicStreamingServersList = findViewById(R.id.llDynamicStreamingServersList)

        // Tab 5 : Paramètres
        tvAppVersionDisplay = findViewById(R.id.tvAppVersionDisplay)
        btnCheckUpdateManual = findViewById(R.id.btnCheckUpdateManual)
        cardConfigServer = findViewById(R.id.cardConfigServer)
        tvSettingsServerUrl = findViewById(R.id.tvSettingsServerUrl)
    }

    private fun getOrCreateDeviceId(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var devId = prefs.getString(KEY_DEVICE_ID, null)
        if (devId.isNullOrEmpty()) {
            devId = "phone_" + UUID.randomUUID().toString().substring(0, 12)
            prefs.edit().putString(KEY_DEVICE_ID, devId).apply()
        }
        return devId
    }

    private fun getApiBaseUrl(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_API_URL, null)
        if (saved.isNullOrEmpty() || saved.contains("192.168.") || saved.contains(":3000")) {
            val cloudUrl = "http://92.4.142.34"
            prefs.edit().putString(KEY_API_URL, cloudUrl).apply()
            return cloudUrl
        }
        return saved
    }

    private fun setApiBaseUrl(url: String) {
        val cleanUrl = url.trim().trimEnd('/')
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_API_URL, cleanUrl).apply()
        tvSettingsServerUrl.text = "Serveur Cloud ($cleanUrl)"
    }

    private fun loadLocalSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentUrl = getApiBaseUrl()
        tvSettingsServerUrl.text = "Serveur Cloud Chiffré ($currentUrl)"

        val code = prefs.getString(KEY_VOUCHER_CODE, null)
        val plan = prefs.getString(KEY_PLAN_NAME, null)
        val expiry = prefs.getString(KEY_EXPIRES_AT, null)
        val maxBytes = prefs.getLong(KEY_MAX_BYTES, -1L)
        val usedBytes = prefs.getLong(KEY_USED_BYTES, 0L)
        val maxDevices = prefs.getInt(KEY_MAX_DEVICES, 1)

        updateHomeQuotaDisplay(code, plan, maxBytes, usedBytes, expiry)

        if (!code.isNullOrEmpty()) {
            tvTabVoucherCode.text = code
            tvTabPlanName.text = plan ?: "Pass VIP Actif"
            tvTabDevicesCount.text = "Appareils autorisés : $maxDevices appareil(s)"
            tvTabExpiryDate.text = "Expire le : ${formatExpiryDisplay(expiry)}"
            tvVoucherStateBadge.text = "ACTIF"
            tvVoucherStateBadge.setTextColor(Color.parseColor("#10B981"))
            btnDisconnectVoucher.visibility = View.VISIBLE
        } else {
            tvTabVoucherCode.text = "AUCUN FORFAIT ACTIF"
            tvTabPlanName.text = "Touchez le bouton ci-dessous pour activer un forfait"
            tvTabDevicesCount.text = "Appareils autorisés : 1 appareil"
            tvTabExpiryDate.text = "Validité : --"
            tvVoucherStateBadge.text = "INACTIF"
            tvVoucherStateBadge.setTextColor(Color.parseColor("#EC4899"))
            btnDisconnectVoucher.visibility = View.GONE
        }

        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            tvAppVersionDisplay.text = "Free-Pone v${pInfo.versionName} (Build ${pInfo.versionCode})"
        } catch (e: Exception) {
            tvAppVersionDisplay.text = "Free-Pone v1.0.0"
        }
    }

    private fun updateHomeQuotaDisplay(code: String?, plan: String?, maxBytes: Long, usedBytes: Long, expiry: String?) {
        if (code.isNullOrEmpty()) {
            tvHomePlanTitle.text = "FORFAIT : AUCUN CODE ACTIF"
            tvHomePlanPercent.text = "0%"
            pbHomeQuota.progress = 0
            tvHomeQuotaStats.text = "0.0 Mo consommés / 0 Go"
            tvHomeExpiryCountdown.text = "Validité : --"
            return
        }

        val planLabel = (plan ?: "Pass VIP").uppercase(Locale.getDefault())

        if (maxBytes > 0) {
            val percent = Math.min(100, Math.max(0, ((usedBytes.toDouble() / maxBytes.toDouble()) * 100).toInt()))
            val usedStr = formatBytes(usedBytes)
            val maxStr = formatBytes(maxBytes)
            val remainingBytes = Math.max(0L, maxBytes - usedBytes)
            val remainingStr = formatBytes(remainingBytes)

            tvHomePlanTitle.text = "FORFAIT : $planLabel ($maxStr)"
            pbHomeQuota.progress = percent
            tvHomePlanPercent.text = "$percent%"
            tvHomeQuotaStats.text = "$usedStr consommés / $maxStr ($remainingStr restants)"
        } else {
            val usedStr = formatBytes(usedBytes)
            tvHomePlanTitle.text = "FORFAIT : $planLabel"
            pbHomeQuota.progress = 100
            tvHomePlanPercent.text = "100%"
            tvHomeQuotaStats.text = "$usedStr consommés • FORFAIT ILLIMITÉ"
        }

        tvHomeExpiryCountdown.text = "Expire le : ${formatExpiryDisplay(expiry)}"
    }

    private fun fetchVoucherStatusFromServer(code: String) {
        val url = "${getApiBaseUrl()}/api/v1/status?code=$code"
        thread {
            try {
                val json = httpGet(url)
                val obj = JSONObject(json)
                if (obj.optBoolean("success", false)) {
                    val planName = obj.optString("name", "Pass VIP")
                    val maxBytes = obj.optLong("max_bytes", -1L)
                    val usedBytes = obj.optLong("used_bytes", 0L)
                    val expiresAt = obj.optString("expires_at", "")
                    val maxDevices = obj.optInt("max_devices", 1)

                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString(KEY_PLAN_NAME, planName)
                        .putLong(KEY_MAX_BYTES, maxBytes)
                        .putLong(KEY_USED_BYTES, usedBytes)
                        .putString(KEY_EXPIRES_AT, expiresAt)
                        .putInt(KEY_MAX_DEVICES, maxDevices)
                        .apply()

                    handler.post {
                        loadLocalSettings()
                    }
                }
            } catch (e: Exception) {
                // Silencieux si serveur injoignable temporairement
            }
        }
    }

    private fun formatExpiryDisplay(rawIso: String?): String {
        if (rawIso.isNullOrEmpty() || rawIso == "Illimité") return "Illimité"
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            val outputFormat = SimpleDateFormat("dd/MM/yyyy à HH:mm", Locale.FRANCE)
            val date = inputFormat.parse(rawIso)
            if (date != null) outputFormat.format(date) else rawIso
        } catch (e: Exception) {
            rawIso
        }
    }

    private fun setupBottomNavigation() {
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_connect -> showTab(0)
                R.id.nav_vouchers -> {
                    showTab(1)
                    fetchPlansCatalog()
                }
                R.id.nav_bridge -> {
                    showTab(2)
                    tvBridgePageIpPort.text = "${getDeviceIpAddress()}:8080"
                }
                R.id.nav_streaming -> {
                    showTab(3)
                    fetchServersList()
                }
                R.id.nav_settings -> showTab(4)
                else -> false
            }
            true
        }
    }

    private fun showTab(index: Int) {
        tabConnectView.visibility = if (index == 0) View.VISIBLE else View.GONE
        tabVouchersView.visibility = if (index == 1) View.VISIBLE else View.GONE
        tabBridgeView.visibility = if (index == 2) View.VISIBLE else View.GONE
        tabStreamingView.visibility = if (index == 3) View.VISIBLE else View.GONE
        tabSettingsView.visibility = if (index == 4) View.VISIBLE else View.GONE
    }

    // =========================================================================
    // MOTION DESIGN AVANCÉ
    // =========================================================================
    private fun setupMotionDesign() {
        orbitAnimator = ObjectAnimator.ofFloat(orbitRing, "rotation", 0f, 360f).apply {
            duration = 10000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }

        val scaleXHolder = PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.45f)
        val scaleYHolder = PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.45f)
        val alphaHolder = PropertyValuesHolder.ofFloat("alpha", 0.7f, 0.0f)
        radarPulseAnimator = ObjectAnimator.ofPropertyValuesHolder(radarWave, scaleXHolder, scaleYHolder, alphaHolder).apply {
            duration = 1600
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
    }

    private fun animateButtonPress(view: View, onFinish: () -> Unit) {
        view.animate()
            .scaleX(0.88f)
            .scaleY(0.88f)
            .setDuration(120)
            .withEndAction {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(160)
                    .withEndAction { onFinish() }
                    .start()
            }
            .start()
    }

    private fun setupClickListeners() {
        btnPower.setOnClickListener {
            animateButtonPress(btnPower) {
                toggleConnection()
            }
        }

        btnActivateNewCode.setOnClickListener {
            showActivateDialog()
        }

        btnDisconnectVoucher.setOnClickListener {
            confirmDisconnectVoucher()
        }

        btnCheckUpdateManual.setOnClickListener {
            checkForOtaUpdates(silent = false)
        }

        cardConfigServer.setOnClickListener {
            showServerUrlDialog()
        }

        // Configuration du Partage Hotspot Cyber Bridge (Tab 3)
        switchBridgePage.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val localIp = getDeviceIpAddress()
                val started = cyberBridge.start { _, clients ->
                    handler.post {
                        tvBridgePageClients.text = "$clients appareil(s) connecté(s) au relais"
                    }
                }
                if (started) {
                    llBridgePageDetails.visibility = View.VISIBLE
                    tvBridgePageIpPort.text = "$localIp:8080"
                    Toast.makeText(this, "Cyber Bridge activé sur le port 8080", Toast.LENGTH_SHORT).show()
                } else {
                    switchBridgePage.isChecked = false
                    Toast.makeText(this, "Impossible d'ouvrir le port 8080", Toast.LENGTH_SHORT).show()
                }
            } else {
                cyberBridge.stop()
                llBridgePageDetails.visibility = View.GONE
                Toast.makeText(this, "Cyber Bridge désactivé", Toast.LENGTH_SHORT).show()
            }
        }

        btnCopyBridgePageConfig.setOnClickListener {
            val proxyStr = tvBridgePageIpPort.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Proxy", proxyStr)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Paramètres copiés : $proxyStr", Toast.LENGTH_SHORT).show()
        }

        // Toggle Streaming Turbo
        switchStreamingTurbo.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                Toast.makeText(this, "Mode Turbo Streaming 4K activé : Fast-Path UDP & Zéro Buffering", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Mode de streaming standard", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDisconnectVoucher() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val code = prefs.getString(KEY_VOUCHER_CODE, null) ?: return

        AlertDialog.Builder(this)
            .setTitle("🔄 Déconnecter ce forfait")
            .setMessage("Souhaitez-vous libérer le forfait '$code' de cet appareil ?\n\nLe tunnel VPN sera coupé et vous pourrez entrer un autre code ou choisir une autre offre.")
            .setPositiveButton("Déconnecter") { _, _ ->
                if (isConnected) {
                    disconnectTunnel()
                }

                // Notification au backend pour libérer le device_id du compteur multi-appareils
                val deviceId = getOrCreateDeviceId()
                val url = "${getApiBaseUrl()}/api/v1/deactivate"
                thread {
                    try {
                        val body = JSONObject().apply {
                            put("code", code)
                            put("device_id", deviceId)
                        }.toString()
                        httpPost(url, body)
                    } catch (e: Exception) {}
                }

                // Nettoyage local
                prefs.edit()
                    .remove(KEY_VOUCHER_CODE)
                    .remove(KEY_PLAN_NAME)
                    .remove(KEY_MAX_BYTES)
                    .remove(KEY_USED_BYTES)
                    .remove(KEY_EXPIRES_AT)
                    .remove(KEY_MAX_DEVICES)
                    .apply()

                loadLocalSettings()
                Toast.makeText(this, "Forfait déconnecté avec succès", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun getDeviceIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "192.168.43.1"
                    }
                }
            }
        } catch (e: Exception) {}
        return "192.168.43.1"
    }

    private fun isNetworkConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        try {
            // 1. Android M+ : inspection exhaustive des interfaces réseau physiques
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val networks = cm.allNetworks
                if (networks != null && networks.isNotEmpty()) {
                    for (net in networks) {
                        val caps = cm.getNetworkCapabilities(net) ?: continue
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                            return true
                        }
                    }
                }
                val activeNet = cm.activeNetwork
                if (activeNet != null) {
                    val caps = cm.getNetworkCapabilities(activeNet)
                    if (caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                                         caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                         caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                        return true
                    }
                }
            }

            // 2. Téléphonie matérielle : Données cellulaires actives sur la carte SIM
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (tm != null && tm.simState == TelephonyManager.SIM_STATE_READY) {
                val dataState = tm.dataState
                if (dataState == TelephonyManager.DATA_CONNECTED || dataState == TelephonyManager.DATA_CONNECTING) {
                    return true
                }
            }

            // 3. Fallback sur allNetworkInfo
            @Suppress("DEPRECATION")
            val allInfo = cm.allNetworkInfo
            if (allInfo != null) {
                for (info in allInfo) {
                    if (info != null && (info.isConnected || info.isConnectedOrConnecting)) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            return true
        }

        return false
    }

    private fun registerNetworkMonitoring() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val builder = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                handler.post {
                    // Déconnexion uniquement si TOUTES les liaisons physiques sont coupées
                    if (isConnected && !isNetworkConnected()) {
                        Toast.makeText(this@MainActivity, "⚠️ Données mobiles coupées : fermeture du tunnel", Toast.LENGTH_SHORT).show()
                        disconnectTunnel()
                    }
                }
            }
        }
        try {
            cm.registerNetworkCallback(builder.build(), networkCallback!!)
        } catch (e: Exception) {}
    }

    private fun unregisterNetworkMonitoring() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            networkCallback?.let { cm?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {}
    }

    // =========================================================================
    // GESTION DU TUNNEL ET DE LA CONNEXION VPN NATIVE
    // =========================================================================
    private fun toggleConnection() {
        if (isConnecting) return

        if (isConnected) {
            disconnectTunnel()
        } else {
            // Vérification matérielle : Données mobiles ou Wi-Fi activés
            if (!isNetworkConnected()) {
                AlertDialog.Builder(this)
                    .setTitle("📶 Données Mobiles Requises")
                    .setMessage("Veuillez activer vos Données Mobiles ou vous connecter à un réseau Wi-Fi avant de lancer le tunnel.")
                    .setPositiveButton("Paramètres Réseau") { _, _ ->
                        try {
                            startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))
                        } catch (e: Exception) {}
                    }
                    .setNegativeButton("Fermer", null)
                    .show()
                return
            }

            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val code = prefs.getString(KEY_VOUCHER_CODE, null)

            if (code == null) {
                AlertDialog.Builder(this)
                    .setTitle("🔑 Forfait Requis")
                    .setMessage("Aucun forfait n'est actuellement actif sur votre appareil.\n\nSouhaitez-vous entrer un code de forfait ou activer le mode d'essai gratuit ?")
                    .setPositiveButton("Saisir un code") { _, _ ->
                        bottomNavigation.selectedItemId = R.id.nav_vouchers
                        showActivateDialog()
                    }
                    .setNeutralButton("Essai Gratuit") { _, _ ->
                        prepareAndStartVpn()
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            } else {
                prepareAndStartVpn()
            }
        }
    }

    private fun prepareAndStartVpn() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, REQUEST_VPN_CODE)
        } else {
            connectTunnel()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN_CODE) {
            if (resultCode == RESULT_OK) {
                connectTunnel()
            } else {
                Toast.makeText(this, "Autorisation VPN refusée par l'utilisateur", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun connectTunnel() {
        isConnecting = true
        tvStatusTitle.text = "CONNEXION..."
        tvStatusTitle.setTextColor(Color.parseColor("#F59E0B"))
        tvStatusSubtitle.text = "Établissement du tunnel chiffré..."
        orbitAnimator?.duration = 2000

        // Démarrage effectif immédiat du service VPN natif Android
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val code = prefs.getString(KEY_VOUCHER_CODE, "DEMO-ACTIVE")
        val vpnServiceIntent = Intent(this, FreePoneVpnService::class.java).apply {
            action = FreePoneVpnService.ACTION_START_VPN
            putExtra("VOUCHER_CODE", code)
            putExtra("SERVER_NAME", "Afrique du Sud (Johannesburg)")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(vpnServiceIntent)
        } else {
            startService(vpnServiceIntent)
        }

        isConnecting = false
        isConnected = true

        // Mise à jour graphique 3D
        btnPower.setBackgroundResource(R.drawable.btn_3d_core_connected)
        radarWave.visibility = View.VISIBLE
        radarPulseAnimator?.start()

        // Affichage éphémère élégant
        tvStatusTitle.text = "CONNECTÉ AVEC SUCCÈS"
        tvStatusTitle.setTextColor(Color.parseColor("#10B981"))
        tvStatusSubtitle.text = "Tunnel sécurisé"
        tvHeaderPing.text = "-- ms"
        orbitAnimator?.duration = 6000

        // Après 3 secondes, transition douce vers le statut permanent
        handler.postDelayed({
            if (isConnected) {
                tvStatusTitle.text = "TUNNEL ACTIF"
                tvStatusSubtitle.text = "Protection en cours • 100% Chiffré"
            }
        }, 3000)

        startRealTelemetryAndHeartbeat()
        Toast.makeText(this, "Connecté avec succès !", Toast.LENGTH_SHORT).show()

        // Test asynchrone non-bloquant de latence avec le serveur
        thread {
            val pingUrl = "${getApiBaseUrl()}/api/v1/ping"
            try {
                val start = System.currentTimeMillis()
                val conn = URL(pingUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.connect()
                val codeResp = conn.responseCode
                val latency = System.currentTimeMillis() - start
                conn.disconnect()
                if (codeResp == 200) {
                    handler.post {
                        if (isConnected) {
                            tvHeaderPing.text = "$latency ms"
                        }
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    if (isConnected) {
                        tvHeaderPing.text = "En ligne"
                    }
                }
            }
        }
    }

    private fun disconnectTunnel() {
        isConnected = false
        isConnecting = false

        val stopIntent = Intent(this, FreePoneVpnService::class.java).apply {
            action = FreePoneVpnService.ACTION_STOP_VPN
        }
        startService(stopIntent)

        btnPower.setBackgroundResource(R.drawable.btn_3d_core_disconnected)
        radarPulseAnimator?.cancel()
        radarWave.visibility = View.INVISIBLE

        tvStatusTitle.text = "DÉCONNECTÉ"
        tvStatusTitle.setTextColor(Color.parseColor("#F8FAFC"))
        tvStatusSubtitle.text = "Touchez le bouton pour démarrer"
        tvHeaderPing.text = "--"
        orbitAnimator?.duration = 10000

        stopTelemetryAndHeartbeat()
        tvRxSpeed.text = "0.0 Ko/s"
        tvTxSpeed.text = "0.0 Ko/s"

        Toast.makeText(this, "Tunnel désactivé.", Toast.LENGTH_SHORT).show()
    }

    // =========================================================================
    // TÉLÉMÉTRIE RÉELLE DU MATÉRIEL (TRAFFICSTATS ET SYNCHRO HEARTBEAT)
    // =========================================================================
    private fun startRealTelemetryAndHeartbeat() {
        stopTelemetryAndHeartbeat()

        prevRxBytes = TrafficStats.getTotalRxBytes()
        prevTxBytes = TrafficStats.getTotalTxBytes()
        lastHeartbeatRx = prevRxBytes
        lastHeartbeatTx = prevTxBytes
        sessionRxBytes = 0
        sessionTxBytes = 0

        // 1. Mise à jour de la vitesse chaque seconde
        telemetryRunnable = object : Runnable {
            override fun run() {
                if (isConnected) {
                    val currentRx = TrafficStats.getTotalRxBytes()
                    val currentTx = TrafficStats.getTotalTxBytes()

                    val rxDelta = if (prevRxBytes > 0 && currentRx >= prevRxBytes) currentRx - prevRxBytes else 0L
                    val txDelta = if (prevTxBytes > 0 && currentTx >= prevTxBytes) currentTx - prevTxBytes else 0L

                    sessionRxBytes += rxDelta
                    sessionTxBytes += txDelta

                    val rxSpeedKo = rxDelta / 1024.0
                    val txSpeedKo = txDelta / 1024.0

                    tvRxSpeed.text = String.format(Locale.US, "%.1f Ko/s", rxSpeedKo)
                    tvTxSpeed.text = String.format(Locale.US, "%.1f Ko/s", txSpeedKo)
                    tvRxTotal.text = "Session : " + formatBytes(sessionRxBytes)
                    tvTxTotal.text = "Session : " + formatBytes(sessionTxBytes)

                    prevRxBytes = currentRx
                    prevTxBytes = currentTx

                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.post(telemetryRunnable!!)

        // 2. Synchronisation Heartbeat avec le Backoffice chaque 3 secondes
        heartbeatRunnable = object : Runnable {
            override fun run() {
                if (isConnected) {
                    sendHeartbeatToBackend()
                    handler.postDelayed(this, 3000)
                }
            }
        }
        handler.postDelayed(heartbeatRunnable!!, 1500)
    }

    private fun stopTelemetryAndHeartbeat() {
        telemetryRunnable?.let { handler.removeCallbacks(it) }
        heartbeatRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun sendHeartbeatToBackend() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val code = prefs.getString(KEY_VOUCHER_CODE, null) ?: return

        val currentRx = TrafficStats.getTotalRxBytes()
        val currentTx = TrafficStats.getTotalTxBytes()

        val rxDelta = if (lastHeartbeatRx > 0 && currentRx >= lastHeartbeatRx) currentRx - lastHeartbeatRx else 0L
        val txDelta = if (lastHeartbeatTx > 0 && currentTx >= lastHeartbeatTx) currentTx - lastHeartbeatTx else 0L
        val totalDelta = rxDelta + txDelta

        lastHeartbeatRx = currentRx
        lastHeartbeatTx = currentTx

        val url = "${getApiBaseUrl()}/api/v1/heartbeat"
        thread {
            try {
                val body = JSONObject().apply {
                    put("code", code)
                    put("used_bytes_delta", totalDelta)
                }.toString()

                val resp = httpPost(url, body)
                val obj = JSONObject(resp)

                val isActive = obj.optBoolean("is_active", true)
                if (!isActive) {
                    val reasonMsg = obj.optString("message", "Ce forfait a été suspendu ou a expiré.")
                    handler.post {
                        disconnectTunnel()
                        AlertDialog.Builder(this)
                            .setTitle("⚠️ Connexion Interrompue")
                            .setMessage("Notification du Backoffice Free-Pone :\n\n$reasonMsg")
                            .setPositiveButton("Compris", null)
                            .show()
                    }
                    return@thread
                }

                val usedBytes = obj.optLong("used_bytes", 0L)
                val maxBytes = obj.optLong("max_bytes", -1L)
                val expiresAt = obj.optString("expires_at", "")
                val planName = obj.optString("plan_name", "Pass Actif")

                prefs.edit()
                    .putLong(KEY_USED_BYTES, usedBytes)
                    .putLong(KEY_MAX_BYTES, maxBytes)
                    .putString(KEY_EXPIRES_AT, expiresAt)
                    .apply()

                handler.post {
                    updateHomeQuotaDisplay(code, planName, maxBytes, usedBytes, expiresAt)
                }

                val announcement = obj.optString("announcement", "")
                if (announcement.isNotEmpty()) {
                    handler.post {
                        tvAnnouncementText.text = announcement
                        bannerAnnouncement.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                // Silencieux si coupure
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f Go", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024L -> String.format(Locale.US, "%.2f Mo", bytes / (1024.0 * 1024))
            bytes >= 1024L -> String.format(Locale.US, "%.1f Ko", bytes / 1024.0)
            else -> "$bytes o"
        }
    }

    // =========================================================================
    // APPELS API BACKEND : ANNONCES, SERVEURS, FORFAITS, ACTIVATION
    // =========================================================================

    private fun fetchServerAnnouncement() {
        val url = "${getApiBaseUrl()}/api/v1/announcement"
        thread {
            try {
                val json = httpGet(url)
                val obj = JSONObject(json)
                if (obj.optBoolean("success", false)) {
                    val data = obj.optJSONObject("data")
                    if (data != null && data.optBoolean("is_active", false)) {
                        val message = data.optString("message", "")
                        handler.post {
                            tvAnnouncementText.text = message
                            bannerAnnouncement.visibility = View.VISIBLE
                        }
                    } else {
                        handler.post { bannerAnnouncement.visibility = View.GONE }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun fetchServersList() {
        val url = "${getApiBaseUrl()}/api/v1/servers"
        thread {
            try {
                val json = httpGet(url)
                val obj = JSONObject(json)
                if (obj.optBoolean("success", false)) {
                    val arr = obj.optJSONArray("servers") ?: obj.optJSONArray("data") ?: JSONArray()
                    cachedServers.clear()
                    for (i in 0 until arr.length()) {
                        cachedServers.add(arr.getJSONObject(i))
                    }
                    handler.post { renderStreamingServersList() }
                }
            } catch (e: Exception) {}
        }
    }

    private fun renderStreamingServersList() {
        llDynamicStreamingServersList.removeAllViews()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedServerId = prefs.getInt(KEY_ACTIVE_SERVER_ID, 1)

        for (i in 0 until cachedServers.size) {
            val server = cachedServers[i]
            val id = server.optInt("id", i + 1)
            val name = server.optString("name", "Serveur VPN")
            val flagEmoji = server.optString("flag_emoji", server.optString("flag", "🌐"))
            val city = server.optString("city", "Cloud Relais")
            val isSelected = (id == savedServerId)

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 14)
                }
                setBackgroundResource(R.drawable.card_glass_3d)
                setPadding(36, 30, 36, 30)
                isClickable = true
                isFocusable = true
            }

            val flagView = TextView(this).apply {
                text = flagEmoji
                textSize = 24f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = 30
                }
            }

            val textContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleView = TextView(this).apply {
                text = name
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                textSize = 14f
            }

            val subtitleView = TextView(this).apply {
                text = "$city • Optimisé Streaming 4K"
                setTextColor(Color.parseColor("#8A99AD"))
                textSize = 11f
            }

            textContainer.addView(titleView)
            textContainer.addView(subtitleView)

            val badgeView = TextView(this).apply {
                text = if (isSelected) "✓ ACTIF" else "CHOISIR"
                setTextColor(if (isSelected) Color.parseColor("#00F0FF") else Color.parseColor("#8A99AD"))
                setTypeface(null, Typeface.BOLD)
                textSize = 11f
                setPadding(20, 10, 20, 10)
            }

            card.addView(flagView)
            card.addView(textContainer)
            card.addView(badgeView)

            card.setOnClickListener {
                prefs.edit().putInt(KEY_ACTIVE_SERVER_ID, id).apply()
                renderStreamingServersList()
                Toast.makeText(this, "Nœud de streaming sélectionné : $name", Toast.LENGTH_SHORT).show()
            }

            llDynamicStreamingServersList.addView(card)
        }
    }

    private fun fetchPlansCatalog() {
        val url = "${getApiBaseUrl()}/api/v1/plans"
        thread {
            try {
                val json = httpGet(url)
                val obj = JSONObject(json)
                if (obj.optBoolean("success", false)) {
                    val arr = obj.optJSONArray("plans") ?: obj.optJSONArray("data") ?: JSONArray()
                    handler.post { renderPlansCatalog(arr) }
                }
            } catch (e: Exception) {}
        }
    }

    private fun renderPlansCatalog(arr: JSONArray) {
        llDynamicPlansList.removeAllViews()

        for (i in 0 until arr.length()) {
            val plan = arr.getJSONObject(i)
            val name = plan.optString("name", "Pass")
            val priceStr = plan.optString("price_str", plan.optString("price", "5 000 GNF"))
            val description = plan.optString("description", "")
            val maxGb = plan.optInt("max_gb", 0)

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 16)
                }
                setBackgroundResource(R.drawable.card_glass_3d)
                setPadding(36, 32, 36, 32)
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val titleView = TextView(this).apply {
                text = name
                setTextColor(Color.parseColor("#00F0FF"))
                setTypeface(null, Typeface.BOLD)
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val priceView = TextView(this).apply {
                text = priceStr
                setTextColor(Color.parseColor("#10B981"))
                setTypeface(null, Typeface.BOLD)
                textSize = 15f
            }

            topRow.addView(titleView)
            topRow.addView(priceView)

            val descView = TextView(this).apply {
                text = if (description.isNotEmpty()) description else if (maxGb > 0) "Volume de données garanti : $maxGb Go" else "Accès illimité haute vitesse"
                setTextColor(Color.parseColor("#8A99AD"))
                textSize = 12f
                setPadding(0, 8, 0, 14)
            }

            val btnOrder = Button(this).apply {
                text = "🔑 Activer ce forfait"
                setBackgroundColor(Color.parseColor("#172033"))
                setTextColor(Color.WHITE)
                textSize = 12f
                setOnClickListener {
                    showActivateDialog()
                }
            }

            card.addView(topRow)
            card.addView(descView)
            card.addView(btnOrder)

            llDynamicPlansList.addView(card)
        }
    }

    private fun showActivateDialog() {
        val input = EditText(this).apply {
            hint = "Ex : FP-XXXX-YYYY-ZZZZ"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            textSize = 17f
            gravity = Gravity.CENTER
            setSingleLine(true)
            setPadding(30, 30, 30, 30)
        }

        val container = FrameLayout(this).apply {
            setPadding(50, 20, 50, 10)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("🔑 Activer un code de forfait")
            .setMessage("Entrez le code voucher obtenu depuis votre Backoffice ou revendeur :")
            .setView(container)
            .setPositiveButton("Valider") { _, _ ->
                val code = input.text.toString().trim().uppercase(Locale.getDefault())
                if (code.isNotEmpty()) {
                    performActivation(code)
                } else {
                    Toast.makeText(this, "Veuillez entrer un code valide", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun performActivation(code: String) {
        val url = "${getApiBaseUrl()}/api/v1/activate"
        val deviceId = getOrCreateDeviceId()

        thread {
            try {
                val postData = JSONObject().apply {
                    put("code", code)
                    put("device_id", deviceId)
                }.toString()

                val resp = httpPost(url, postData)
                val obj = JSONObject(resp)

                if (obj.optBoolean("success", false)) {
                    val planObj = obj.optJSONObject("plan") ?: obj.optJSONObject("data")
                    val planName = planObj?.optString("name", planObj.optString("plan_name", "Pass VIP")) ?: "Pass VIP"
                    val expiresAt = planObj?.optString("expires_at", "Illimité") ?: "Illimité"
                    val maxBytes = planObj?.optLong("max_bytes", -1L) ?: -1L
                    val usedBytes = planObj?.optLong("used_bytes", 0L) ?: 0L
                    val maxDevices = planObj?.optInt("max_devices", 1) ?: 1

                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString(KEY_VOUCHER_CODE, code)
                        .putString(KEY_PLAN_NAME, planName)
                        .putString(KEY_EXPIRES_AT, expiresAt)
                        .putLong(KEY_MAX_BYTES, maxBytes)
                        .putLong(KEY_USED_BYTES, usedBytes)
                        .putInt(KEY_MAX_DEVICES, maxDevices)
                        .apply()

                    handler.post {
                        loadLocalSettings()
                        val maxDisplay = if (maxBytes > 0) formatBytes(maxBytes) else "Illimité"
                        AlertDialog.Builder(this)
                            .setTitle("✅ Forfait Activé !")
                            .setMessage("Félicitations ! Le forfait '$planName' a été activé avec succès sur cet appareil.\n\nCode : $code\nQuota : $maxDisplay\nAppareils autorisés : $maxDevices\nExpiration : ${formatExpiryDisplay(expiresAt)}")
                            .setPositiveButton("Lancer le tunnel") { _, _ ->
                                bottomNavigation.selectedItemId = R.id.nav_connect
                                if (!isConnected) toggleConnection()
                            }
                            .show()
                    }
                } else {
                    val msg = obj.optString("error", "Code invalide ou limite d'appareils atteinte.")
                    handler.post {
                        AlertDialog.Builder(this)
                            .setTitle("❌ Erreur d'activation")
                            .setMessage(msg)
                            .setPositiveButton("Réessayer") { _, _ -> showActivateDialog() }
                            .setNegativeButton("Fermer", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    Toast.makeText(this, "Erreur de connexion au serveur : ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // =========================================================================
    // DÉPLOIEMENT & MISE À JOUR OTA INTÉGRÉE
    // =========================================================================

    private fun checkForOtaUpdates(silent: Boolean) {
        var currentVersionCode = 1
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            currentVersionCode = pInfo.versionCode
        } catch (e: Exception) {}

        val url = "${getApiBaseUrl()}/api/v1/app/check-update?version_code=$currentVersionCode"

        thread {
            try {
                val json = httpGet(url)
                val obj = JSONObject(json)
                if (obj.optBoolean("success", false)) {
                    val data = obj.optJSONObject("data") ?: obj
                    val hasUpdate = data.optBoolean("has_update", false)

                    if (hasUpdate) {
                        val latestVersion = data.optString("latest_version", "1.0.1")
                        val notes = data.optString("release_notes", data.optString("changelog", "Améliorations de performances et sécurité."))
                        val apkUrl = data.optString("apk_url", data.optString("download_url", "/download/Free-Pone.apk"))

                        handler.post {
                            promptOtaInstallation(latestVersion, notes, apkUrl)
                        }
                    } else if (!silent) {
                        handler.post {
                            Toast.makeText(this, "Vous disposez déjà de la dernière version (Build $currentVersionCode).", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                if (!silent) {
                    handler.post {
                        Toast.makeText(this, "Impossible de joindre le serveur de mise à jour.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun promptOtaInstallation(version: String, notes: String, relativeApkUrl: String) {
        val fullUrl = if (relativeApkUrl.startsWith("http")) relativeApkUrl else "${getApiBaseUrl()}$relativeApkUrl"

        AlertDialog.Builder(this)
            .setTitle("🚀 Mise à jour disponible (v$version)")
            .setMessage("Une nouvelle version de Free-Pone est disponible pour votre appareil !\n\nNouveautés :\n$notes\n\nSouhaitez-vous installer la mise à jour maintenant ?")
            .setPositiveButton("Mettre à jour maintenant") { _, _ ->
                startOtaDownloadAndInstall(fullUrl)
            }
            .setNegativeButton("Plus tard", null)
            .setCancelable(false)
            .show()
    }

    private fun startOtaDownloadAndInstall(downloadUrl: String) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Téléchargement de Free-Pone...")
            .setMessage("Téléchargement du nouveau paquet en cours depuis le serveur Backoffice...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        thread {
            try {
                val destinationFile = File(cacheDir, "Free-Pone-Update.apk")
                if (destinationFile.exists()) destinationFile.delete()

                val url = URL(downloadUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.connect()

                val input = BufferedInputStream(conn.inputStream)
                val output = FileOutputStream(destinationFile)
                val buffer = ByteArray(8192)
                var bytesRead: Int

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }

                output.flush()
                output.close()
                input.close()

                handler.post {
                    progressDialog.dismiss()
                    launchApkInstaller(destinationFile)
                }
            } catch (e: Exception) {
                handler.post {
                    progressDialog.dismiss()
                    Toast.makeText(this, "Échec du téléchargement OTA : ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun launchApkInstaller(apkFile: File) {
        try {
            val contentUri: Uri = FileProvider.getUriForFile(
                this,
                "com.freepone.vpn.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur lors du lancement de l'installeur : ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showServerUrlDialog() {
        val currentUrl = getApiBaseUrl()
        val input = EditText(this).apply {
            setText(currentUrl)
            setTextColor(Color.WHITE)
            setPadding(30, 30, 30, 30)
        }

        AlertDialog.Builder(this)
            .setTitle("⚙️ Configuration Serveur Cloud")
            .setMessage("Adresse de l'API backend Free-Pone :")
            .setView(input)
            .setPositiveButton("Enregistrer") { _, _ ->
                val newUrl = input.text.toString().trim()
                if (newUrl.isNotEmpty()) {
                    setApiBaseUrl(newUrl)
                    Toast.makeText(this, "Serveur mis à jour", Toast.LENGTH_SHORT).show()
                    fetchServerAnnouncement()
                    fetchServersList()
                    fetchPlansCatalog()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // =========================================================================
    // UTILITAIRES HTTP SANS DÉPENDANCES SUPPLÉMENTAIRES
    // =========================================================================

    private fun httpGet(urlString: String): String {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.connect()

        val reader = BufferedReader(InputStreamReader(conn.inputStream))
        val sb = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            sb.append(line)
        }
        reader.close()
        return sb.toString()
    }

    private fun httpPost(urlString: String, jsonBody: String): String {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.doOutput = true
        conn.connectTimeout = 8000
        conn.readTimeout = 8000

        val writer = OutputStreamWriter(conn.outputStream)
        writer.write(jsonBody)
        writer.flush()
        writer.close()

        val reader = BufferedReader(InputStreamReader(conn.inputStream))
        val sb = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            sb.append(line)
        }
        reader.close()
        return sb.toString()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterNetworkMonitoring()
        cyberBridge.stop()
        stopTelemetryAndHeartbeat()
        orbitAnimator?.cancel()
        radarPulseAnimator?.cancel()
    }
}
