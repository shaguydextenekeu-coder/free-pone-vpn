#!/bin/bash
set -e

echo "============================================="
echo "  FREE-PONE ENTERPRISE VPN - CLOUD SETUP     "
echo "============================================="

# 1. Mise à jour pare-feu Ubuntu Oracle
echo "[1/6] Configuration du pare-feu local iptables..."
sudo iptables -I INPUT 1 -p udp --dport 51820 -j ACCEPT
sudo iptables -I INPUT 1 -p tcp --dport 3000 -j ACCEPT
sudo iptables -I INPUT 1 -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 1 -p tcp --dport 443 -j ACCEPT

# 2. Activation du routage IPv4
echo "[2/6] Activation du routage de paquets IPv4..."
echo "net.ipv4.ip_forward=1" | sudo tee /etc/sysctl.d/99-wireguard.conf
sudo sysctl -p /etc/sysctl.d/99-wireguard.conf

# 3. Installation des paquets système
echo "[3/6] Installation de WireGuard, iptables et dépendances..."
sudo apt-get update -y
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y \
    wireguard \
    wireguard-tools \
    iptables \
    iptables-persistent \
    curl \
    git \
    sqlite3 \
    build-essential

# 4. Installation de Node.js LTS
echo "[4/6] Installation de Node.js v20..."
if ! command -v node &> /dev/null; then
    curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
    sudo apt-get install -y nodejs
fi

echo "Node version: $(node -v)"
echo "NPM version: $(npm -v)"

# 5. Configuration de WireGuard
echo "[5/6] Configuration de WireGuard wg0..."
sudo mkdir -p /etc/wireguard
cd /etc/wireguard

if [ ! -f /etc/wireguard/server_private.key ]; then
    wg genkey | sudo tee /etc/wireguard/server_private.key | wg pubkey | sudo tee /etc/wireguard/server_public.key
    sudo chmod 600 /etc/wireguard/server_private.key
fi

SERVER_PRIVKEY=$(sudo cat /etc/wireguard/server_private.key)
SERVER_PUBKEY=$(sudo cat /etc/wireguard/server_public.key)
DEFAULT_IFACE=$(ip route show default | awk '{print $5}')

echo "Interface réseau par défaut détectée : $DEFAULT_IFACE"
echo "Clé publique WireGuard du serveur : $SERVER_PUBKEY"

cat <<EOF | sudo tee /etc/wireguard/wg0.conf
[Interface]
Address = 10.8.0.1/24
ListenPort = 51820
PrivateKey = $SERVER_PRIVKEY
PostUp = iptables -A FORWARD -i wg0 -j ACCEPT; iptables -A FORWARD -o wg0 -j ACCEPT; iptables -t nat -A POSTROUTING -o $DEFAULT_IFACE -j MASQUERADE
PostDown = iptables -D FORWARD -i wg0 -j ACCEPT; iptables -D FORWARD -o wg0 -j ACCEPT; iptables -t nat -D POSTROUTING -o $DEFAULT_IFACE -j MASQUERADE

EOF

sudo systemctl enable wg-quick@wg0
sudo systemctl restart wg-quick@wg0
sudo wg show

# Sauvegarde des règles iptables
sudo netfilter-persistent save || true

# 6. Préparation du répertoire Free-Pone
echo "[6/6] Préparation du dossier de déploiement /opt/free-pone..."
sudo mkdir -p /opt/free-pone
sudo chown -R ubuntu:ubuntu /opt/free-pone

echo "Setup de base terminé avec succès !"
