# 🛡️ Free-Pone VPN — Système Intégral

Bienvenue sur le projet **Free-Pone VPN**, une architecture logicielle complète pour gérer et opérer votre propre service VPN avec système de jetons d'accès (vouchers).

---

## 📁 Structure du Projet

```text
free-pone-vpn/
├── backend/                  # 1. API Node.js & Base de données SQLite
│   ├── server.js             # Moteur de l'API & routes
│   ├── admin.html            # Dashboard Web d'administration
│   ├── free_pone.db          # Base de données SQLite intégrée
│   └── test_api.js           # Tests automatiques de bon fonctionnement
├── android-app/              # 2. Application mobile Android (Free-Pone)
│   ├── app/src/main/         # Interface graphique (Dark theme) & logique Kotlin
│   └── build.gradle          # Configuration de compilation Android
└── server-deploy/            # 3. Scripts de déploiement serveur
    └── install_wireguard.sh  # Script d'installation automatique WireGuard pour VPS
```

---

## 🚀 1. Utilisation Immédiate du Tableau de Bord (Admin)

Le serveur backend tourne actuellement sur votre machine.

1. Ouvrez votre navigateur web et rendez-vous sur :
   👉 **`http://localhost:3000/admin`**
2. Vous pouvez :
   * Générer des codes d'accès en 1 clic (ex: `FP-XXXX-YYYY-ZZZZ`).
   * Définir le volume de data (Illimité, 5 Go, 10 Go...) et la durée de validité (30 jours, etc.).
   * Voir la liste des codes émis et les appareils qui s'y connectent.

---

## 📱 2. Fonctionnement de l'Application Mobile

L'application Android située dans `android-app/` reproduit l'expérience utilisateur moderne :
* **Bouton Power central** avec retour visuel (Vert = Connecté, Violet = Prêt, Rouge = Expiré).
* **Validation du jeton** : L'utilisateur saisit son code, l'application le valide auprès du backend.
* **Affichage des débits** : Compteurs Réception / Envoi en temps réel.
* **Routage réseau** : Utilise le protocole WireGuard haute performance.

---

## 🌐 3. Mise en ligne sur votre VPS (Oracle Cloud)

Dès que votre machine virtuelle Ubuntu est allumée :
1. Exécutez le script automatique : `sudo bash install_wireguard.sh`
2. Il configure WireGuard et les règles de pare-feu automatiquement en 60 secondes.
