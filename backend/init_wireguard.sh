#!/bin/bash
set -e

echo "=== INITIALISATION WIREGUARD ==="
mkdir -p /etc/wireguard
chmod 700 /etc/wireguard

if [ ! -f /etc/wireguard/server_private.key ]; then
    wg genkey | tee /etc/wireguard/server_private.key | wg pubkey > /etc/wireguard/server_public.key
    chmod 600 /etc/wireguard/server_private.key
fi

PRIV=$(cat /etc/wireguard/server_private.key)
PUB=$(cat /etc/wireguard/server_public.key)
IFACE=$(ip route show default | awk '{print $5}')

echo "Interface par defaut: $IFACE"
echo "Cle publique serveur: $PUB"

cat <<EOF > /etc/wireguard/wg0.conf
[Interface]
Address = 10.8.0.1/24
ListenPort = 51820
PrivateKey = $PRIV
PostUp = iptables -A FORWARD -i wg0 -j ACCEPT; iptables -A FORWARD -o wg0 -j ACCEPT; iptables -t nat -A POSTROUTING -o $IFACE -j MASQUERADE
PostDown = iptables -D FORWARD -i wg0 -j ACCEPT; iptables -D FORWARD -o wg0 -j ACCEPT; iptables -t nat -D POSTROUTING -o $IFACE -j MASQUERADE

EOF

systemctl enable wg-quick@wg0
systemctl restart wg-quick@wg0
echo "=== WIREGUARD STATUS ==="
wg show
echo "PUBLIC_KEY_START:$PUB:PUBLIC_KEY_END"
