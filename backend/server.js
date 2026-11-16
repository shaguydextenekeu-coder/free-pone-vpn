/**
 * Free-Pone VPN - Enterprise Backend, Node Management & Admin Control Center
 * Built with Node.js native modules (Zero external dependencies)
 */

const http = require('http');
const net = require('net');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { exec } = require('child_process');
const { DatabaseSync } = require('node:sqlite');

function syncWireGuardPeer(publicKey, assignedIp) {
  if (!publicKey || !assignedIp) return;
  const clientIp = assignedIp.split('/')[0];
  exec(`wg set wg0 peer "${publicKey}" allowed-ips "${clientIp}/32" persistent-keepalive 25`, (err) => {
    if (!err) console.log(`[WireGuard] Peer synchronisé: ${publicKey} (${clientIp}/32)`);
  });
}

const PORT = process.env.PORT || 3000;
const DB_FILE = path.join(__dirname, 'free_pone.db');

// --- 1. BASE DE DONNÉES SQLITE INTÉGRÉE ---
const db = new DatabaseSync(DB_FILE);

// Création des tables professionnelles
db.exec(`
  CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY,
    value TEXT
  );

  CREATE TABLE IF NOT EXISTS vouchers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code TEXT UNIQUE NOT NULL,
    plan_name TEXT NOT NULL,
    max_bytes INTEGER NOT NULL,
    used_bytes INTEGER DEFAULT 0,
    duration_days INTEGER NOT NULL,
    created_at TEXT DEFAULT (datetime('now')),
    activated_at TEXT,
    expires_at TEXT,
    is_active INTEGER DEFAULT 1,
    device_public_key TEXT,
    assigned_ip TEXT
  );

  CREATE TABLE IF NOT EXISTS servers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    country_code TEXT NOT NULL,
    flag TEXT NOT NULL,
    endpoint TEXT NOT NULL,
    public_key TEXT NOT NULL,
    ping_ms INTEGER DEFAULT 32,
    load_percent INTEGER DEFAULT 18,
    is_active INTEGER DEFAULT 1,
    is_default INTEGER DEFAULT 0
  );

  CREATE TABLE IF NOT EXISTS plans (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    duration_days INTEGER NOT NULL,
    max_gb REAL NOT NULL,
    price TEXT NOT NULL,
    badge TEXT,
    description TEXT
  );

  CREATE TABLE IF NOT EXISTS announcements (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    message TEXT NOT NULL,
    type TEXT DEFAULT 'info',
    is_active INTEGER DEFAULT 1,
    created_at TEXT DEFAULT (datetime('now'))
  );
`);

// Initialisation des réglages par défaut
const initSetting = (key, defaultVal) => {
  const row = db.prepare('SELECT value FROM settings WHERE key = ?').get(key);
  if (!row) {
    db.prepare('INSERT INTO settings (key, value) VALUES (?, ?)').run(key, defaultVal);
  }
};

initSetting('server_endpoint', 'vpn.free-pone.com:51820');
initSetting('server_public_key', 'FP_SERVER_PUBKEY_TEST_ABC123456789=');
initSetting('dns_server', '1.1.1.1, 1.0.0.1');
initSetting('vpn_network', '10.8.0.');
initSetting('app_latest_version', '1.0.0');
initSetting('app_latest_version_code', '1');
initSetting('app_changelog', 'Version 3D Cyberpunk avec Motion Design et navigation multi-onglets');
initSetting('app_mandatory_update', 'false');

function getSetting(key) {
  const row = db.prepare('SELECT value FROM settings WHERE key = ?').get(key);
  return row ? row.value : null;
}

function setSetting(key, val) {
  db.prepare('INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = ?').run(key, val, val);
}

// Initialisation des serveurs par défaut si la table est vide
const serverCount = db.prepare('SELECT COUNT(*) as count FROM servers').get().count;
if (serverCount === 0) {
  const insertServer = db.prepare(`
    INSERT INTO servers (name, country_code, flag, endpoint, public_key, ping_ms, load_percent, is_active, is_default)
    VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)
  `);
  insertServer.run('Afrique du Sud (Johannesburg)', 'ZA', '🇿🇦', 'vpn-za.free-pone.com:51820', 'FP_ZA_PUBKEY_9876543210=', 32, 18, 1);
  insertServer.run('France (Paris)', 'FR', '🇫🇷', 'vpn-fr.free-pone.com:51820', 'FP_FR_PUBKEY_1234567890=', 78, 42, 0);
  insertServer.run('Allemagne (Francfort)', 'DE', '🇩🇪', 'vpn-de.free-pone.com:51820', 'FP_DE_PUBKEY_4567890123=', 84, 27, 0);
  insertServer.run('États-Unis (New York)', 'US', '🇺🇸', 'vpn-us.free-pone.com:51820', 'FP_US_PUBKEY_7890123456=', 125, 55, 0);
}

// Initialisation des forfaits par défaut si la table est vide
const planCount = db.prepare('SELECT COUNT(*) as count FROM plans').get().count;
if (planCount === 0) {
  const insertPlan = db.prepare(`
    INSERT INTO plans (id, name, duration_days, max_gb, price, badge, description)
    VALUES (?, ?, ?, ?, ?, ?, ?)
  `);
  insertPlan.run('plan_24h', 'Pass 24H Flash', 1, 0, '5 000 GNF', 'Populaire', 'Accès ultra-rapide illimité pendant 24 heures');
  insertPlan.run('plan_7d', 'Pass Semaine Ultra', 7, 0, '25 000 GNF', 'Recommandé', 'Trafic illimité 7 jours sur tous les serveurs');
  insertPlan.run('plan_30d', 'Pass VIP Mensuel', 30, 0, '80 000 GNF', 'VIP', 'Bande passante prioritaire maximale + Tous les nœuds mondiaux');
}

// Initialisation de l'annonce d'accueil
const announceCount = db.prepare('SELECT COUNT(*) as count FROM announcements').get().count;
if (announceCount === 0) {
  db.prepare('INSERT INTO announcements (message, type, is_active) VALUES (?, ?, ?)')
    .run('🚀 Bienvenue sur le réseau Free-Pone ! Tous nos serveurs mondiaux sont actifs et sécurisés.', 'info', 1);
}

function generateVoucherCode() {
  const part = () => crypto.randomBytes(2).toString('hex').toUpperCase();
  return `FP-${part()}-${part()}-${part()}`;
}

function allocateNextIp() {
  const countRow = db.prepare('SELECT COUNT(*) as count FROM vouchers WHERE assigned_ip IS NOT NULL').get();
  const nextNum = (countRow ? countRow.count : 0) + 2;
  const base = getSetting('vpn_network') || '10.8.0.';
  return `${base}${nextNum}/32`;
}

// --- 2. SERVEUR HTTP REST API & BACKOFFICE ---
const server = http.createServer((req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, DELETE, OPTIONS, HEAD');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  const url = new URL(req.url, `http://${req.headers.host}`);
  const pathname = url.pathname;

  let body = '';
  req.on('data', chunk => { body += chunk; });
  req.on('end', () => {
    let json = {};
    if (body) {
      try { json = JSON.parse(body); } catch (_) {}
    }

    try {
      handleRoute(req, res, pathname, url, json);
    } catch (err) {
      console.error('Erreur serveur:', err);
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'Erreur interne du serveur', detail: err.message }));
    }
  });
});

function handleRoute(req, res, pathname, url, json) {
  const send = (status, data) => {
    res.writeHead(status, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(data, null, 2));
  };

  // --- HEALTHCHECK & PRE-FLIGHT PING ---
  if (req.method === 'GET' && (pathname === '/' || pathname === '/api/v1/ping')) {
    return send(200, {
      service: 'Free-Pone Enterprise VPN Platform',
      status: 'online',
      version: getSetting('app_latest_version'),
      time: new Date().toISOString()
    });
  }

  // --- INTERFACE WEB D'ADMINISTRATION (BACKOFFICE) ---
  if (req.method === 'GET' && (pathname === '/admin' || pathname === '/admin.html')) {
    const adminHtmlPath = path.join(__dirname, 'admin.html');
    fs.readFile(adminHtmlPath, 'utf8', (err, html) => {
      if (err) {
        res.writeHead(500, { 'Content-Type': 'text/plain' });
        res.end('Erreur de chargement du dashboard admin');
        return;
      }
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(html);
    });
    return;
  }

  // --- TÉLÉCHARGEMENT DIRECT DE L'APK ---
  if ((req.method === 'GET' || req.method === 'HEAD') && (pathname === '/download/Free-Pone.apk' || pathname === '/download/latest.apk')) {
    const localApk = path.join(__dirname, 'Free-Pone.apk');
    const desktopApk = path.join('C:\\Users\\DELL\\Desktop', 'Free-Pone.apk');
    const projectApk = path.join(__dirname, '..', 'Free-Pone.apk');
    const apkFile = fs.existsSync(localApk) ? localApk : (fs.existsSync(desktopApk) ? desktopApk : projectApk);

    if (!fs.existsSync(apkFile)) {
      res.writeHead(404, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'Fichier APK introuvable' }));
      return;
    }

    const stat = fs.statSync(apkFile);
    res.writeHead(200, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Length': stat.size,
      'Content-Disposition': 'attachment; filename="Free-Pone.apk"'
    });
    if (req.method === 'HEAD') { res.end(); return; }
    fs.createReadStream(apkFile).pipe(res);
    return;
  }

  // =========================================================================
  // ENDPOINTS PUBLICS (POUR L'APPLICATION MOBILE)
  // =========================================================================

  // GET /api/v1/servers - Récupère la liste dynamique des serveurs VPN
  if (req.method === 'GET' && pathname === '/api/v1/servers') {
    const servers = db.prepare('SELECT id, name, country_code, flag, endpoint, ping_ms, load_percent, is_default, is_active FROM servers WHERE is_active = 1 ORDER BY is_default DESC, ping_ms ASC').all();
    const formatted = servers.map(s => {
      let city = 'Relais';
      if (s.name.includes('(') && s.name.includes(')')) {
        city = s.name.substring(s.name.indexOf('(') + 1, s.name.indexOf(')'));
      }
      return {
        id: s.id,
        name: s.name,
        country_code: s.country_code,
        flag: s.flag,
        flag_emoji: s.flag,
        city: city,
        endpoint: s.endpoint,
        ping_ms: s.ping_ms,
        load_percent: s.load_percent,
        is_online: s.is_active === 1,
        is_default: s.is_default === 1
      };
    });
    return send(200, { success: true, servers: formatted, data: formatted });
  }

  // GET /api/v1/plans - Récupère la liste des forfaits disponibles
  if (req.method === 'GET' && pathname === '/api/v1/plans') {
    const plans = db.prepare('SELECT * FROM plans ORDER BY duration_days ASC').all();
    const formatted = plans.map(p => {
      const numMatch = (p.price || '').replace(/\s/g, '').match(/\d+/);
      const numPrice = numMatch ? parseInt(numMatch[0], 10) : 0;
      const curr = (p.price || '').replace(/[0-9\s]/g, '') || 'GNF';
      return {
        id: p.id,
        name: p.name,
        duration_days: p.duration_days,
        max_gb: p.max_gb,
        quota_gb: p.max_gb,
        price: numPrice,
        price_str: p.price,
        currency: curr,
        badge: p.badge || '',
        description: p.description || ''
      };
    });
    return send(200, { success: true, plans: formatted, data: formatted });
  }

  // GET /api/v1/announcement - Récupère le message d'alerte ou broadcast
  if (req.method === 'GET' && pathname === '/api/v1/announcement') {
    const ann = db.prepare('SELECT message, type, is_active FROM announcements WHERE is_active = 1 ORDER BY id DESC LIMIT 1').get();
    const data = ann ? { message: ann.message, type: ann.type, is_active: true } : { message: '', type: 'info', is_active: false };
    return send(200, { success: true, announcement: ann || null, data });
  }

  // GET /api/v1/app/check-update - Vérification OTA
  if (req.method === 'GET' && pathname === '/api/v1/app/check-update') {
    const clientVersionCode = parseInt(url.searchParams.get('version_code') || '1', 10);
    const serverVersionCode = parseInt(getSetting('app_latest_version_code') || '1', 10);
    const hasUpdate = serverVersionCode > clientVersionCode;
    const updateInfo = {
      has_update: hasUpdate,
      latest_version: getSetting('app_latest_version') || '1.0.0',
      latest_version_code: serverVersionCode,
      release_notes: getSetting('app_changelog') || 'Mise à jour de performance et sécurité',
      changelog: getSetting('app_changelog') || 'Mise à jour de performance et sécurité',
      mandatory: getSetting('app_mandatory_update') === 'true',
      apk_url: '/download/Free-Pone.apk',
      download_url: '/download/Free-Pone.apk'
    };
    return send(200, {
      success: true,
      ...updateInfo,
      data: updateInfo
    });
  }

  // POST /api/v1/activate - Activation d'un code de forfait
  if (req.method === 'POST' && pathname === '/api/v1/activate') {
    const code = (json.code || '').trim().toUpperCase();
    const devicePublicKey = (json.device_public_key || '').trim();
    const deviceId = (json.device_id || json.device_public_key || '').trim();

    if (!code) return send(400, { error: 'Code de forfait manquant' });

    const voucher = db.prepare('SELECT * FROM vouchers WHERE code = ?').get(code);
    if (!voucher) return send(404, { error: 'Code de forfait inexistant ou erroné.' });
    if (voucher.is_active === 0) return send(403, { error: 'Ce forfait a été révoqué par l\'administrateur.' });

    if (voucher.expires_at) {
      const now = new Date();
      if (now > new Date(voucher.expires_at)) {
        return send(403, { error: 'Ce forfait est expiré depuis le ' + voucher.expires_at });
      }
    }

    // Gestion du nombre d'appareils autorisés (1, 2, 3...)
    const maxDevices = voucher.max_devices || 1;
    let registeredDevices = [];
    try { registeredDevices = JSON.parse(voucher.registered_devices || '[]'); } catch (_) {}

    if (deviceId) {
      if (!registeredDevices.includes(deviceId)) {
        if (registeredDevices.length >= maxDevices) {
          return send(403, { error: `Limite atteinte : ce forfait est déjà utilisé sur le maximum de ${maxDevices} appareil(s) autorisé(s).` });
        }
        registeredDevices.push(deviceId);
        db.prepare('UPDATE vouchers SET registered_devices = ? WHERE id = ?').run(JSON.stringify(registeredDevices), voucher.id);
      }
    }

    let assignedIp = voucher.assigned_ip;
    let expiresAt = voucher.expires_at;

    if (!voucher.activated_at) {
      assignedIp = allocateNextIp();
      const expirationDate = new Date();
      expirationDate.setDate(expirationDate.getDate() + voucher.duration_days);
      expiresAt = expirationDate.toISOString();

      db.prepare(`
        UPDATE vouchers
        SET activated_at = datetime('now'),
            expires_at = ?,
            device_public_key = ?,
            assigned_ip = ?
        WHERE id = ?
      `).run(expiresAt, devicePublicKey, assignedIp, voucher.id);
    } else if (devicePublicKey && devicePublicKey !== voucher.device_public_key) {
      db.prepare('UPDATE vouchers SET device_public_key = ? WHERE id = ?').run(devicePublicKey, voucher.id);
    }

    if (devicePublicKey && assignedIp) {
      syncWireGuardPeer(devicePublicKey, assignedIp);
    }

    return send(200, {
      success: true,
      message: 'Forfait activé avec succès !',
      plan: {
        code: voucher.code,
        name: voucher.plan_name,
        plan_name: voucher.plan_name,
        is_unlimited: voucher.max_bytes === -1,
        max_bytes: voucher.max_bytes,
        used_bytes: voucher.used_bytes,
        max_devices: maxDevices,
        devices_count: registeredDevices.length,
        expires_at: expiresAt
      },
      data: {
        code: voucher.code,
        plan_name: voucher.plan_name,
        max_bytes: voucher.max_bytes,
        used_bytes: voucher.used_bytes,
        max_devices: maxDevices,
        devices_count: registeredDevices.length,
        expires_at: expiresAt
      },
      vpn_config: {
        server_endpoint: getSetting('server_endpoint'),
        server_public_key: getSetting('server_public_key'),
        client_assigned_ip: assignedIp,
        dns: getSetting('dns_server'),
        keepalive: 25
      }
    });
  }

  // POST /api/v1/heartbeat - Synchronisation temps réel des données consommées
  if (req.method === 'POST' && pathname === '/api/v1/heartbeat') {
    const code = (json.code || '').trim().toUpperCase();
    const bytesDelta = parseInt(json.used_bytes_delta || 0, 10);

    if (!code) return send(400, { error: 'Code requis pour le heartbeat' });

    const voucher = db.prepare('SELECT * FROM vouchers WHERE code = ?').get(code);
    if (!voucher) {
      return send(200, { success: false, is_active: false, reason: 'NOT_FOUND', message: 'Forfait inexistant' });
    }

    if (voucher.is_active === 0) {
      return send(200, { success: true, is_active: false, reason: 'REVOKED', message: 'Ce forfait a été révoqué par l\'administrateur.' });
    }

    const now = new Date();
    if (voucher.expires_at && now > new Date(voucher.expires_at)) {
      return send(200, { success: true, is_active: false, reason: 'EXPIRED', message: 'Votre forfait a expiré.' });
    }

    const safeDelta = bytesDelta > 0 ? bytesDelta : 0;
    const newUsedBytes = voucher.used_bytes + safeDelta;

    if (voucher.max_bytes > 0 && newUsedBytes >= voucher.max_bytes) {
      db.prepare('UPDATE vouchers SET used_bytes = ?, last_seen = datetime(\'now\') WHERE id = ?').run(newUsedBytes, voucher.id);
      return send(200, { success: true, is_active: false, reason: 'QUOTA_EXCEEDED', message: 'Quota de données épuisé.' });
    }

    db.prepare('UPDATE vouchers SET used_bytes = ?, last_seen = datetime(\'now\') WHERE id = ?').run(newUsedBytes, voucher.id);

    const ann = db.prepare('SELECT message FROM announcements WHERE is_active = 1 ORDER BY id DESC LIMIT 1').get();

    return send(200, {
      success: true,
      is_active: true,
      plan_name: voucher.plan_name,
      used_bytes: newUsedBytes,
      max_bytes: voucher.max_bytes,
      remaining_bytes: voucher.max_bytes > 0 ? Math.max(0, voucher.max_bytes - newUsedBytes) : -1,
      expires_at: voucher.expires_at,
      announcement: ann ? ann.message : null
    });
  }

  // GET /api/v1/status - Statut en direct d'un forfait
  if (req.method === 'GET' && pathname === '/api/v1/status') {
    const code = (url.searchParams.get('code') || '').trim().toUpperCase();
    if (!code) return send(400, { error: 'Paramètre code requis' });

    const voucher = db.prepare('SELECT * FROM vouchers WHERE code = ?').get(code);
    if (!voucher) return send(404, { error: 'Forfait non trouvé' });

    const now = new Date();
    const isExpired = voucher.expires_at ? now > new Date(voucher.expires_at) : false;
    const isQuotaExceeded = voucher.max_bytes > 0 && voucher.used_bytes >= voucher.max_bytes;
    let regDevices = [];
    try { regDevices = JSON.parse(voucher.registered_devices || '[]'); } catch (_) {}

    return send(200, {
      success: true,
      code: voucher.code,
      name: voucher.plan_name,
      active: voucher.is_active === 1 && !isExpired && !isQuotaExceeded,
      is_expired: isExpired,
      is_quota_exceeded: isQuotaExceeded,
      expires_at: voucher.expires_at,
      used_bytes: voucher.used_bytes,
      max_bytes: voucher.max_bytes,
      max_devices: voucher.max_devices || 1,
      devices_count: regDevices.length,
      used_mb: (voucher.used_bytes / (1024 * 1024)).toFixed(2),
      assigned_ip: voucher.assigned_ip
    });
  }

  // POST /api/v1/deactivate - Dconnecte / libre un appareil d'un forfait
  if (req.method === 'POST' && pathname === '/api/v1/deactivate') {
    const code = (json.code || '').trim().toUpperCase();
    const deviceId = (json.device_id || '').trim();

    if (!code) return send(400, { error: 'Code requis pour la dconnexion' });

    const voucher = db.prepare('SELECT * FROM vouchers WHERE code = ?').get(code);
    if (!voucher) return send(404, { error: 'Forfait introuvable' });

    let regDevices = [];
    try { regDevices = JSON.parse(voucher.registered_devices || '[]'); } catch (_) {}

    if (deviceId) {
      regDevices = regDevices.filter(d => d !== deviceId);
      db.prepare('UPDATE vouchers SET registered_devices = ? WHERE id = ?').run(JSON.stringify(regDevices), voucher.id);
    }

    return send(200, {
      success: true,
      message: 'Forfait dconnect avec succs de cet appareil',
      devices_count: regDevices.length,
      max_devices: voucher.max_devices || 1
    });
  }

  // =========================================================================
  // ENDPOINTS D'ADMINISTRATION COMPLÈTE (BACKOFFICE)
  // =========================================================================

  // GET /api/admin/overview - Métriques globales
  if (req.method === 'GET' && pathname === '/api/admin/overview') {
    const totalVouchers = db.prepare('SELECT COUNT(*) as c FROM vouchers').get().c;
    const activeVouchers = db.prepare('SELECT COUNT(*) as c FROM vouchers WHERE activated_at IS NOT NULL AND is_active = 1').get().c;
    const unusedVouchers = db.prepare('SELECT COUNT(*) as c FROM vouchers WHERE activated_at IS NULL AND is_active = 1').get().c;
    const onlineUsersNow = db.prepare("SELECT COUNT(*) as c FROM vouchers WHERE last_seen IS NOT NULL AND last_seen >= datetime('now', '-30 seconds')").get().c;
    const totalBytes = db.prepare('SELECT SUM(used_bytes) as s FROM vouchers').get().s || 0;
    const serversCount = db.prepare('SELECT COUNT(*) as c FROM servers WHERE is_active = 1').get().c;

    let provisionStatus = { status: 'STANDBY', message: 'Robot en attente' };
    const pFile = path.join(__dirname, 'provision_status.json');
    if (fs.existsSync(pFile)) {
      try { provisionStatus = JSON.parse(fs.readFileSync(pFile, 'utf8')); } catch (_) {}
    }

    return send(200, {
      totalVouchers,
      activeVouchers,
      unusedVouchers,
      onlineUsersNow,
      totalBytes,
      totalMb: (totalBytes / (1024 * 1024)).toFixed(2),
      totalGb: (totalBytes / (1024 * 1024 * 1024)).toFixed(3),
      serversCount,
      provisionStatus,
      appVersion: getSetting('app_latest_version')
    });
  }

  // --- GESTION DES FORFAITS (VOUCHERS) ---
  if (req.method === 'GET' && pathname === '/api/admin/vouchers') {
    const list = db.prepare('SELECT * FROM vouchers ORDER BY id DESC').all();
    const nowMs = Date.now();
    const enriched = list.map(v => {
      let isOnline = false;
      if (v.last_seen) {
        const d = new Date(v.last_seen.endsWith('Z') ? v.last_seen : (v.last_seen.replace(' ', 'T') + 'Z'));
        isOnline = (nowMs - d.getTime()) < 35000;
      }
      const isExpired = v.expires_at ? nowMs > new Date(v.expires_at).getTime() : false;
      const pct = v.max_bytes > 0 ? Math.min(100, Math.round((v.used_bytes / v.max_bytes) * 100)) : 0;
      let regDevices = [];
      try { regDevices = JSON.parse(v.registered_devices || '[]'); } catch (_) {}

      return {
        ...v,
        max_devices: v.max_devices || 1,
        devices_count: regDevices.length,
        devices_label: `${regDevices.length}/${v.max_devices || 1}`,
        is_online: isOnline,
        is_expired: isExpired,
        percent_used: pct,
        used_mb: (v.used_bytes / (1024 * 1024)).toFixed(2),
        used_gb: (v.used_bytes / (1024 * 1024 * 1024)).toFixed(3),
        max_gb: v.max_bytes > 0 ? (v.max_bytes / (1024 * 1024 * 1024)).toFixed(2) : 'Illimité'
      };
    });
    return send(200, { success: true, count: enriched.length, vouchers: enriched });
  }

  if (req.method === 'POST' && pathname === '/api/admin/vouchers') {
    const count = parseInt(json.count || 1, 10);
    const planName = json.plan_name || 'Pass Standard';
    const maxGb = parseFloat(json.max_gb || 0);
    const maxBytes = maxGb > 0 ? Math.floor(maxGb * 1024 * 1024 * 1024) : -1;
    const durationDays = parseInt(json.duration_days || 30, 10);
    const maxDevices = parseInt(json.max_devices || 1, 10);

    const insertStmt = db.prepare(`
      INSERT INTO vouchers (code, plan_name, max_bytes, duration_days, max_devices, registered_devices)
      VALUES (?, ?, ?, ?, ?, '[]')
    `);

    const created = [];
    for (let i = 0; i < count; i++) {
      const code = generateVoucherCode();
      insertStmt.run(code, planName, maxBytes, durationDays, maxDevices);
      created.push(code);
    }

    return send(201, {
      success: true,
      message: `${count} jeton(s) créé(s) avec succès ! (${maxDevices} appareil(s) autorisé(s))`,
      vouchers: created
    });
  }

  // DELETE /api/admin/vouchers - Révocation ou suppression d'un jeton
  if (req.method === 'DELETE' && pathname === '/api/admin/vouchers') {
    const id = parseInt(url.searchParams.get('id'), 10);
    if (!id) return send(400, { error: 'ID requis' });
    db.prepare('DELETE FROM vouchers WHERE id = ?').run(id);
    return send(200, { success: true, message: 'Jeton supprimé définitivement' });
  }

  // POST /api/admin/vouchers/toggle - Activer/Désactiver un jeton
  if (req.method === 'POST' && pathname === '/api/admin/vouchers/toggle') {
    const id = parseInt(json.id, 10);
    const voucher = db.prepare('SELECT is_active FROM vouchers WHERE id = ?').get(id);
    if (!voucher) return send(404, { error: 'Jeton introuvable' });
    const newState = voucher.is_active === 1 ? 0 : 1;
    db.prepare('UPDATE vouchers SET is_active = ? WHERE id = ?').run(newState, id);
    return send(200, { success: true, is_active: newState });
  }

  // GET /api/admin/export-vouchers - Téléchargement CSV
  if (req.method === 'GET' && pathname === '/api/admin/export-vouchers') {
    const list = db.prepare('SELECT code, plan_name, duration_days, created_at, activated_at, expires_at, is_active FROM vouchers ORDER BY id DESC').all();
    let csv = 'Code,Offre,Duree_Jours,Date_Creation,Date_Activation,Date_Expiration,Statut\n';
    list.forEach(v => {
      const statut = v.is_active === 0 ? 'Revoque' : (v.activated_at ? 'Active' : 'Non_Active');
      csv += `"${v.code}","${v.plan_name}",${v.duration_days},"${v.created_at || ''}","${v.activated_at || ''}","${v.expires_at || ''}","${statut}"\n`;
    });

    res.writeHead(200, {
      'Content-Type': 'text/csv; charset=utf-8',
      'Content-Disposition': 'attachment; filename="FreePone_Vouchers.csv"'
    });
    res.end(csv);
    return;
  }

  // --- GESTION DES SERVEURS VPN ---
  if (req.method === 'GET' && pathname === '/api/admin/servers') {
    const servers = db.prepare('SELECT * FROM servers ORDER BY id ASC').all();
    return send(200, { success: true, servers });
  }

  if (req.method === 'POST' && pathname === '/api/admin/servers') {
    const { name, country_code, flag, endpoint, public_key, ping_ms, load_percent } = json;
    if (!name || !endpoint) return send(400, { error: 'Nom et Endpoint requis' });

    db.prepare(`
      INSERT INTO servers (name, country_code, flag, endpoint, public_key, ping_ms, load_percent, is_active, is_default)
      VALUES (?, ?, ?, ?, ?, ?, ?, 1, 0)
    `).run(name, country_code || 'UN', flag || '🌐', endpoint, public_key || '', parseInt(ping_ms || 40, 10), parseInt(load_percent || 20, 10));

    return send(201, { success: true, message: 'Serveur VPN ajouté avec succès !' });
  }

  if (req.method === 'DELETE' && pathname === '/api/admin/servers') {
    const id = parseInt(url.searchParams.get('id'), 10);
    if (!id) return send(400, { error: 'ID requis' });
    db.prepare('DELETE FROM servers WHERE id = ?').run(id);
    return send(200, { success: true, message: 'Serveur supprimé' });
  }

  // --- GESTION DU CATALOGUE DE FORFAITS ---
  if (req.method === 'GET' && pathname === '/api/admin/plans') {
    const plans = db.prepare('SELECT * FROM plans ORDER BY duration_days ASC').all();
    return send(200, { success: true, plans });
  }

  if (req.method === 'POST' && pathname === '/api/admin/plans') {
    const { id, name, duration_days, max_gb, price, badge, description } = json;
    const planId = (id || `plan_${Date.now()}`).trim();
    db.prepare(`
      INSERT INTO plans (id, name, duration_days, max_gb, price, badge, description)
      VALUES (?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(id) DO UPDATE SET
        name = excluded.name,
        duration_days = excluded.duration_days,
        max_gb = excluded.max_gb,
        price = excluded.price,
        badge = excluded.badge,
        description = excluded.description
    `).run(planId, name, parseInt(duration_days || 30, 10), parseFloat(max_gb || 0), price, badge || '', description || '');

    return send(200, { success: true, message: 'Forfait enregistré dans le catalogue !' });
  }

  if (req.method === 'DELETE' && pathname === '/api/admin/plans') {
    const id = url.searchParams.get('id');
    if (!id) return send(400, { error: 'ID requis' });
    db.prepare('DELETE FROM plans WHERE id = ?').run(id);
    return send(200, { success: true, message: 'Forfait retiré du catalogue' });
  }

  // --- ANNONCE BROADCAST ---
  if (req.method === 'GET' && pathname === '/api/admin/announcement') {
    const ann = db.prepare('SELECT * FROM announcements ORDER BY id DESC LIMIT 1').get();
    return send(200, { success: true, announcement: ann });
  }

  if (req.method === 'POST' && pathname === '/api/admin/announcement') {
    const { message, type, is_active } = json;
    db.prepare('INSERT INTO announcements (message, type, is_active) VALUES (?, ?, ?)')
      .run(message || '', type || 'info', is_active ? 1 : 0);
    return send(200, { success: true, message: 'Annonce mise à jour !' });
  }

  // --- VERSION OTA ---
  if (req.method === 'GET' && pathname === '/api/admin/app-version') {
    return send(200, {
      latest_version: getSetting('app_latest_version') || '1.0.0',
      latest_version_code: parseInt(getSetting('app_latest_version_code') || '1', 10),
      changelog: getSetting('app_changelog') || '',
      mandatory: getSetting('app_mandatory_update') === 'true'
    });
  }

  if (req.method === 'POST' && pathname === '/api/admin/app-version') {
    setSetting('app_latest_version', json.version || '1.0.0');
    setSetting('app_latest_version_code', (json.version_code || 1).toString());
    setSetting('app_changelog', json.changelog || '');
    setSetting('app_mandatory_update', json.mandatory ? 'true' : 'false');
    return send(200, { success: true, message: 'Paramètres OTA publiés !' });
  }

  // --- STATUT DU ROBOT ORACLE ---
  if (req.method === 'GET' && pathname === '/api/admin/provision-status') {
    const pFile = path.join(__dirname, 'provision_status.json');
    if (fs.existsSync(pFile)) {
      try {
        return send(200, JSON.parse(fs.readFileSync(pFile, 'utf8')));
      } catch (_) {}
    }
    return send(200, { status: 'STANDBY', message: 'En attente...' });
  }

  // 404
  send(404, { error: 'Route introuvable' });
}

// ─────────────────────────────────────────────────────────────────────────────
// TUNNEL FREE-PONE v1.3.2 — Architecture simplifiée et robuste
//
// Protocole :
//   Client → GET /fp-tunnel/<host>/<port> HTTP/1.1
//             Host: recharge.orange.cm
//             Upgrade: websocket
//   Serveur → connexion TCP vers <host>:<port>
//           → si succès : HTTP/1.1 101 Switching Protocols
//           → si échec  : HTTP/1.1 503 Service Unavailable
//   Ensuite → pipe bidirectionnel transparent client ↔ destination
//
// Corrections v1.3.2 :
//   ✅ La destination est dans l'URL (plus de protocole DEST/OK séparé)
//   ✅ Le serveur se connecte à la destination AVANT d'envoyer 101
//   ✅ clientSocket.resume() explicite pour débloquer le flux de données
//   ✅ Listeners correctement nettoyés, pas de double-consommation
// ─────────────────────────────────────────────────────────────────────────────
server.on('upgrade', (req, clientSocket, head) => {
  try {
    const urlObj = new URL(req.url, 'http://localhost');
    const pathname = urlObj.pathname;

    // ── Endpoint Free-Pone : /fp-tunnel/<host>/<port> ───────────────────────
    if (pathname.startsWith('/fp-tunnel/')) {
      const segments = pathname.split('/');
      // segments = ['', 'fp-tunnel', 'host', 'port']
      const destHost = segments[2] || '';
      const destPort = parseInt(segments[3] || '0', 10);

      if (!destHost || !destPort || destPort < 1 || destPort > 65535) {
        clientSocket.destroy();
        return;
      }

      // Connexion TCP vers la destination RÉELLE en premier
      const targetSocket = net.connect(destPort, destHost);
      let tunnelOpen = false;

      targetSocket.once('connect', () => {
        tunnelOpen = true;

        // Envoyer 101 APRÈS connexion réussie à la destination
        try {
          clientSocket.write(
            'HTTP/1.1 101 Switching Protocols\r\n' +
            'Upgrade: websocket\r\n' +
            'Connection: Upgrade\r\n' +
            'X-FP-Server: FreePone/1.3.2\r\n' +
            '\r\n'
          );
        } catch (e) {
          targetSocket.destroy();
          return;
        }

        // Relayer les données buffées dans 'head' si présentes
        if (head && head.length > 0) {
          targetSocket.write(head);
        }

        // Débloquer le socket client et démarrer le pipe bidirectionnel
        clientSocket.resume();
        targetSocket.pipe(clientSocket);
        clientSocket.pipe(targetSocket);
      });

      targetSocket.once('error', (err) => {
        console.error(`[fp-tunnel] Connexion échouée vers ${destHost}:${destPort} — ${err.message}`);
        if (!tunnelOpen) {
          try {
            clientSocket.write('HTTP/1.1 503 Service Unavailable\r\n\r\n');
          } catch (_) {}
        }
        try { clientSocket.destroy(); } catch (_) {}
      });

      targetSocket.once('close', () => { try { clientSocket.destroy(); } catch (_) {} });
      clientSocket.once('error',  () => { try { targetSocket.destroy(); } catch (_) {} });
      clientSocket.once('close',  () => { try { targetSocket.destroy(); } catch (_) {} });

      return;
    }

    // ── Endpoint générique WebSocket (rétrocompatibilité) ────────────────────
    const target = urlObj.searchParams.get('target') || req.headers['x-target'] || '';
    if (!target) { clientSocket.destroy(); return; }

    const tParts = target.split(':');
    const tHost  = tParts[0];
    const tPort  = parseInt(tParts[1] || '80', 10);

    const tSocket = net.connect(tPort, tHost, () => {
      clientSocket.write(
        'HTTP/1.1 101 Switching Protocols\r\n' +
        'Upgrade: websocket\r\n' +
        'Connection: Upgrade\r\n' +
        '\r\n'
      );
      if (head && head.length > 0) tSocket.write(head);
      clientSocket.resume();
      tSocket.pipe(clientSocket);
      clientSocket.pipe(tSocket);
    });

    tSocket.once('error', () => { try { clientSocket.destroy(); } catch (_) {} });
    clientSocket.once('error', () => { try { tSocket.destroy(); } catch (_) {} });

  } catch (err) {
    console.error('[upgrade] Erreur inattendue:', err.message);
    try { clientSocket.destroy(); } catch (_) {}
  }
});

server.on('connect', (req, clientSocket, head) => {
  const target = req.url || req.headers['x-target'] || '';
  const parts = target.split(':');
  const host = parts[0];
  const port = parseInt(parts[1] || 443, 10);

  if (!host) {
    clientSocket.destroy();
    return;
  }

  const targetSocket = net.connect(port, host, () => {
    clientSocket.write('HTTP/1.1 200 Connection Established\r\n\r\n');
    if (head && head.length > 0) targetSocket.write(head);
    targetSocket.pipe(clientSocket);
    clientSocket.pipe(targetSocket);
  });

  targetSocket.on('error', () => clientSocket.destroy());
  clientSocket.on('error', () => targetSocket.destroy());
});

server.listen(PORT, () => {
  console.log(`=======================================================`);
  console.log(`[Free-Pone Enterprise] Serveur démarré sur le port ${PORT}`);
  console.log(`Base de données : ${DB_FILE}`);
  console.log(`Tableau de bord : http://localhost:${PORT}/admin`);
  console.log(`=======================================================`);
});
