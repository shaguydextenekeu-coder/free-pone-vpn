#!/bin/bash
# ==============================================================================
# Free-Pone VPN - Script de déploiement automatique pour Oracle Cloud / Ubuntu
# ==============================================================================

set -e

echo "=========================================================="
echo "  Installation et Configuration de Free-Pone VPN Server   "
echo "=========================================================="

if [ "$EUID" -ne 0 ]; then
  echo "[-] Ce script doit être exécuté en tant que root (sudo)."
  exit 1
fi

# 1. Mise à jour et installation des paquets requis
echo "[+] Mise à jour du système et installation de WireGuard..."
apt-get update -y
apt-get install -y wireguard iptables qrencode curl

# 2. Activation du routage IP (IP Forwarding)
echo "[+] Activation du routage IPv4..."
sysctl -w net.ipv4.ip_forward=1
sed -i 's/#net.ipv4.ip_forward=1/net.ipv4.ip_forward=1/' /etc/sysctl.conf

# 3. Détection de l'interface réseau publique
DEFAULT_INTERFACE=$(ip route get 8.8.8.8 | awk '{print $5; exit}')
PUBLIC_IP=$(curl -s -4 https://api.ipify.org || echo "VOTRE_IP_SERVEUR")

echo "[+] Interface réseau détectée : $DEFAULT_INTERFACE"
echo "[+] IP Publique : $PUBLIC_IP"

# 4. Génération des clés du serveur WireGuard
mkdir -p /etc/wireguard
cd /etc/wireguard
umask 077

if [ ! -f server_private.key ]; then
    wg genkey | tee server_private.key | wg pubkey > server_public.key
fi

SERVER_PRIVKEY=$(cat server_private.key)
SERVER_PUBKEY=$(cat server_public.key)

# 5. Création du fichier de configuration WireGuard (wg0.conf)
cat > /etc/wireguard/wg0.conf <<EOF
[Interface]
Address = 10.8.0.1/24
ListenPort = 51820
PrivateKey = $SERVER_PRIVKEY
PostUp = iptables -A FORWARD -i wg0 -j ACCEPT; iptables -t nat -A POSTROUTING -o $DEFAULT_INTERFACE -j MASQUERADE
PostDown = iptables -D FORWARD -i wg0 -j ACCEPT; iptables -t nat -D POSTROUTING -o $DEFAULT_INTERFACE -j MASQUERADE
EOF

# 6. Démarrage et activation du service WireGuard
systemctl enable wg-quick@wg0
systemctl restart wg-quick@wg0

echo "=========================================================="
echo "  ✅ SERVEUR WIREGUARD OPÉRATIONNEL SUR LE PORT 51820 UDP "
echo "=========================================================="
echo ""
echo "INFORMATIONS DU SERVEUR À RENSEIGNER DANS L'API :"
echo "----------------------------------------------------------"
echo "Endpoint Serveur : $PUBLIC_IP:51820"
echo "Clé Publique     : $SERVER_PUBKEY"
echo "----------------------------------------------------------"
echo ""
echo "Votre serveur est prêt à relayer le trafic de l'application !"
