#!/bin/bash
set -e

echo "=== CONFIGURATION ET DEMARRAGE DU SERVICE CLOUD ==="

# 1. Mise à jour de la base de données
sqlite3 /opt/free-pone/free_pone.db "
UPDATE settings SET value = '92.4.142.34:51820' WHERE key = 'server_endpoint';
UPDATE settings SET value = 'HKMM9Y6M6Y2QT4KnXsySJYX0f/BD9zaRvfhgUrhdg20=' WHERE key = 'server_public_key';
UPDATE servers SET endpoint = '92.4.142.34:51820', public_key = 'HKMM9Y6M6Y2QT4KnXsySJYX0f/BD9zaRvfhgUrhdg20=' WHERE name LIKE '%Johannesburg%';
"

# 2. Création du service systemd
cat <<EOF > /etc/systemd/system/freepone.service
[Unit]
Description=Free-Pone Enterprise VPN Cloud Backend
After=network.target wg-quick@wg0.service

[Service]
Type=simple
User=root
WorkingDirectory=/opt/free-pone
ExecStart=/usr/bin/node /opt/free-pone/server.js
Restart=always
RestartSec=3
Environment=NODE_ENV=production
Environment=PORT=3000

[Install]
WantedBy=multi-user.target
EOF

# 3. Activation et démarrage
systemctl daemon-reload
systemctl enable freepone.service
systemctl restart freepone.service
sleep 2

echo "=== STATUT DU SERVICE CLOUD ==="
systemctl status freepone.service --no-pager
