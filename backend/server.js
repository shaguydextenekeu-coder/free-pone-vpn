// server.js - Version Intégrale pour Host Spoofing + Tunnel WebSocket + Structure de Relais

const WebSocket = require('ws');
const http = require('http');
const url = require('url');
const net = require('net'); // Pour la création de sockets TCP

// --- Configuration ---
const HTTP_PORT = 8080;        // Port pour écouter les requêtes entrantes (leurre HTTP et handshake WebSocket)
const TARGET_HOST = 'recharge.orange.cm'; // L'hôte "leurre" utilisé pour le Host Spoofing
const TARGET_PATH = '/geny-wireguard';   // Le chemin utilisé pour le leurre HTTP et le tunnel WebSocket

// --- Gestion des connexions actives ---
// Stocke les sockets WebSocket actifs et, potentiellement, les sockets TCP associés.
// La structure exacte dépendra de la façon dont vous gérez le relais.
// Exemple: Map<WebSocket, { targetSocket?: net.Socket, buffer?: Buffer[] }>
const activeConnections = new Map();

// --- Serveur HTTP pour le Host Spoofing et le Handshake WebSocket ---
const httpServer = http.createServer((req, res) => {
    const requestUrl = url.parse(req.url);
    const hostHeader = req.headers.host;

    console.log(`[HTTP] Requête reçue: ${req.method} ${requestUrl.pathname}`);
    console.log(`[HTTP] Headers:`, req.headers);

    // Vérification du "leurre" : méthode GET, chemin spécifique et hôte attendu
    if (req.method === 'GET' && requestUrl.pathname === TARGET_PATH && req.headers.host === TARGET_HOST) {
        console.log(`[HTTP] Leurre détecté : Host=${req.headers.host}, Path=${requestUrl.pathname}`);

        // Vérification si c'est une demande de mise à niveau vers WebSocket
        if (req.headers.upgrade && req.headers.upgrade.toLowerCase() === 'websocket' &&
            req.headers.connection && req.headers.connection.toLowerCase() === 'upgrade') {

            console.log('[HTTP] Handshake WebSocket demandé. Le serveur WebSocket va prendre le relais.');
            // Le module 'ws' est configuré pour écouter sur TARGET_PATH.
            // Il interceptera cette connexion et gérera le reste du handshake.
            // Nous répondons ici avec 101 pour confirmer la mise à niveau.
            res.writeHead(101, {
                'Upgrade': 'websocket',
                'Connection': 'Upgrade',
                // D'autres en-têtes peuvent être nécessaires selon le protocole exact,
                // mais pour un handshake standard, ceux-ci suffisent.
            });
            res.end(); // Fin de la réponse HTTP initiale. La connexion est maintenant une connexion WebSocket.
        } else {
            // La requête correspond au leurre mais n'est pas une demande WebSocket.
            console.log('[HTTP] Requête sur le chemin du leurre, mais pas une demande de mise à niveau WebSocket.');
            res.writeHead(400, { 'Content-Type': 'text/plain' });
            res.end('Bad Request: Expected WebSocket upgrade.');
        }
    } else {
        // La requête ne correspond pas au schéma du leurre.
        console.log('[HTTP] Requête non ciblée ignorée.');
        res.writeHead(404, { 'Content-Type': 'text/plain' });
        res.end('Not Found');
    }
});

// --- Serveur WebSocket pour le tunnel chiffré ---
// Il écoute sur le même serveur HTTP mais spécifiquement sur le chemin TARGET_PATH.
const wss = new WebSocket.Server({
    server: httpServer,
    path: TARGET_PATH
});

// --- Gestionnaire d'événements pour les nouvelles connexions WebSocket ---
wss.on('connection', (ws, req) => {
    const clientIp = req.socket.remoteAddress;
    console.log(`[WS] Nouveau tunnel WebSocket établi avec ${clientIp}`);

    // Initialisation pour cette connexion : pas de socket cible au début.
    // On pourrait stocker un buffer ici si le client envoie des données avant que la destination ne soit connue.
    const connectionInfo = {
        targetSocket: null,
        buffer: [], // Buffer pour les données reçues avant l'établissement de la connexion cible
        isTargetConnected: false // Indicateur pour savoir si la connexion cible est établie
    };
    activeConnections.set(ws, connectionInfo);

    // --- Gestionnaire d'événements pour les messages reçus sur le tunnel WebSocket ---
    ws.on('message', (message) => {
        console.log(`[WS] Données reçues via tunnel depuis ${clientIp}: ${message.length} octets`);

        // --- *** POINT CRUCIAL : LOGIQUE DE RELAIS DU TRAFIC *** ---
        // Ici, `message` contient les données de l'utilisateur (potentiellement chiffrées).
        // Le serveur doit déterminer la destination finale sur Internet et router ces données.
        //
        // **Comment le client indique-t-il la destination ?**
        // C'est la partie qui nécessite une définition de protocole entre votre client Android
        // et ce serveur Node.js. Voici des scénarios possibles :
        //
        // 1.  **Le client envoie une commande de connexion explicite :**
        //     Exemple: `{"command": "CONNECT", "host": "www.example.com", "port": 80}`
        //     Le serveur analyse cette commande, établit `targetSocket`, puis renvoie
        //     "OK" ou "ERROR" au client. Ensuite, les données suivantes sont relayées.
        //
        // 2.  **Le client utilise un protocole comme SOCKS5 via WebSocket :**
        //     Le serveur devrait implémenter un proxy SOCKS5 côté serveur pour comprendre
        //     les requêtes SOCKS5 envoyées par le client via WebSocket.
        //
        // 3.  **Le trafic est déjà chiffré (ex: WireGuard) et le serveur doit juste le relayer :**
        //     Dans ce cas, le serveur n'a pas besoin de connaître la destination finale
        //     car le protocole sous-jacent (WireGuard) gère le routage. Le serveur
        //     agirait comme un simple pont entre le WebSocket et une autre interface réseau
        //     ou un autre tunnel.
        //
        // **Ce code fournit une structure pour le scénario 1 (commande explicite),
        // car c'est le plus adaptable sans connaître le détail de votre implémentation.**
        //
        // **Vous DEVEZ adapter la logique ci-dessous en fonction de VOTRE protocole client.**

        if (!connectionInfo.isTargetConnected && !connectionInfo.targetSocket) {
            // Si la connexion cible n'est pas encore établie, essayer de l'établir.
            // Tentative d'analyse comme une commande de connexion JSON (Scénario 1)
            try {
                const messageStr = message.toString();
                const parsedMessage = JSON.parse(messageStr);

                if (parsedMessage.command === "CONNECT" && parsedMessage.host && parsedMessage.port) {
                    const targetHost = parsedMessage.host;
                    const targetPort = parsedMessage.port;

                    console.log(`[RELAY] Tentative de connexion à ${targetHost}:${targetPort} pour ${clientIp}`);

                    connectionInfo.targetSocket = net.createConnection({ host: targetHost, port: targetPort }, () => {
                        console.log(`[RELAY] Connexion établie avec ${targetHost}:${targetPort}`);
                        connectionInfo.isTargetConnected = true;

                        // Envoyer une confirmation au client
                        if (ws.readyState === WebSocket.OPEN) {
                            ws.send(JSON.stringify({ status: "CONNECTED", host: targetHost, port: targetPort }));
                        }

                        // Vider le buffer des données reçues avant la connexion
                        if (connectionInfo.buffer.length > 0) {
                            console.log(`[RELAY] Envoi des données bufferisées (${connectionInfo.buffer.length}) vers ${targetHost}:${targetPort}`);
                            connectionInfo.buffer.forEach(bufferedData => {
                                if (connectionInfo.targetSocket && connectionInfo.targetSocket.writable) {
                                    connectionInfo.targetSocket.write(bufferedData);
                                }
                            });
                            connectionInfo.buffer = []; // Vider le buffer une fois envoyé
                        }
                    });

                    // Gestionnaire d'événements pour les données reçues du serveur cible
                    connectionInfo.targetSocket.on('data', (dataFromTarget) => {
                        console.log(`[RELAY] Données reçues de ${targetHost}:${targetPort} (${dataFromTarget.length} octets)`);
                        // Renvoyer les données au client via le tunnel WebSocket
                        if (ws.readyState === WebSocket.OPEN) {
                            // Vous pourriez vouloir encapsuler ces données si le client attend un format spécifique
                            // ws.send(JSON.stringify({ type: "DATA", payload: dataFromTarget.toString('base64') })); // Exemple: base64
                            ws.send(dataFromTarget); // Envoi direct si le client attend le flux brut
                        }
                    });

                    // Gestionnaire d'erreurs pour le socket cible
                    connectionInfo.targetSocket.on('error', (err) => {
                        console.error(`[RELAY] Erreur socket cible ${targetHost}:${targetPort} pour ${clientIp}:`, err.message);
                        connectionInfo.isTargetConnected = false;
                        if (ws.readyState === WebSocket.OPEN) {
                            // Informer le client de l'erreur
                            ws.send(JSON.stringify({ error: `RELAY_TARGET_ERROR`, message: err.message }));
                        }
                        // Fermer le tunnel WebSocket si la connexion cible échoue
                        ws.close(1011, "Target connection error"); // Code d'erreur WebSocket
                    });

                    // Gestionnaire de fermeture pour le socket cible
                    connectionInfo.targetSocket.on('close', () => {
                        console.log(`[RELAY] Connexion fermée avec ${targetHost}:${targetPort}`);
                        connectionInfo.isTargetConnected = false;
                        connectionInfo.targetSocket = null; // Réinitialiser le socket cible
                        if (ws.readyState === WebSocket.OPEN) {
                            ws.send(JSON.stringify({ status: "TARGET_DISCONNECTED", host: targetHost, port: targetPort }));
                        }
                        // Fermer le tunnel WebSocket si la connexion cible se ferme
                        ws.close(1001, "Target disconnected"); // Code d'erreur WebSocket
                    });

                    // Si la commande CONNECT était suivie de données (rare mais possible), les bufferiser
                    // Note: Dans ce scénario, on suppose que la commande CONNECT est le premier message.
                    // Si des données suivent immédiatement, elles seront bufferisées ici.

                } else {
                    // Message reçu mais ce n'est pas une commande CONNECT valide
                    console.warn(`[WS] Message reçu invalide ou non reconnu depuis ${clientIp}: ${messageStr}`);
                    if (ws.readyState === WebSocket.OPEN) {
                        ws.send(JSON.stringify({ error: "INVALID_MESSAGE_FORMAT" }));
                    }
                    // Optionnel: fermer la connexion si le format est incorrect
                    // ws.close(1008, "Protocol error");
                }
            } catch (e) {
                // Le message n'est pas du JSON valide ou contient une erreur d'analyse
                console.error(`[WS] Erreur d'analyse du message JSON depuis ${clientIp}:`, e.message);
                if (ws.readyState === WebSocket.OPEN) {
                    ws.send(JSON.stringify({ error: "JSON_PARSE_ERROR" }));
                }
                // Si le client envoie des données brutes sans commande, les bufferiser
                // pour une utilisation potentielle si une connexion cible est établie plus tard.
                connectionInfo.buffer.push(message);
                console.log(`[WS] Données brutes ajoutées au buffer pour ${clientIp}.`);
            }
        } else if (connectionInfo.targetSocket && connectionInfo.targetSocket.writable) {
            // Si la connexion cible est établie, transférer les données reçues
            connectionInfo.targetSocket.write(message);
        } else {
            // Si la connexion cible n'est pas prête mais qu'on a déjà essayé de la connecter, bufferiser
            console.log(`[WS] Connexion cible non prête, bufferisation des données pour ${clientIp}.`);
            connectionInfo.buffer.push(message);
        }
    });

    // --- Gestionnaire d'événements pour la fermeture du tunnel WebSocket ---
    ws.on('close', (code, reason) => {
        const clientIp = ws.remoteAddress || req.socket.remoteAddress; // Récupérer l'IP si possible
        console.log(`[WS] Tunnel WebSocket fermé avec ${clientIp}. Code: ${code}, Raison: ${reason}`);

        // Fermer proprement la connexion cible si elle existe
        const connInfo = activeConnections.get(ws);
        if (connInfo && connInfo.targetSocket) {
            connInfo.targetSocket.end(); // Ferme la connexion TCP proprement
        }
        activeConnections.delete(ws); // Retirer de la liste des connexions actives
    });

    // --- Gestionnaire d'événements pour les erreurs sur le tunnel WebSocket ---
    ws.on('error', (error) => {
        const clientIp = ws.remoteAddress || req.socket.remoteAddress;
        console.error(`[WS] Erreur du tunnel WebSocket pour ${clientIp}:`, error.message);

        // Fermer proprement la connexion cible en cas d'erreur WebSocket
        const connInfo = activeConnections.get(ws);
        if (connInfo && connInfo.targetSocket) {
            connInfo.targetSocket.end();
        }
        // Le socket WebSocket sera fermé automatiquement par l'événement 'error' ou 'close'
        // On supprime juste l'entrée de la map
         activeConnections.delete(ws);
    });
});

// --- Démarrage du serveur HTTP (qui inclut le serveur WebSocket) ---
httpServer.listen(HTTP_PORT, () => {
    console.log(`[Server] Serveur démarré.`);
    console.log(`[Server] Écoute sur le port ${HTTP_PORT} pour les requêtes HTTP/WebSocket.`);
    console.log(`[Server] Tunnel WebSocket attendu sur le chemin : ${TARGET_PATH}`);
    console.log(`[Server] Host pour le leurre Host Spoofing : ${TARGET_HOST}`);
});

// --- Gestion globale des erreurs du serveur ---
httpServer.on('error', (err) => {
    if (err.code === 'EADDRINUSE') {
        console.error(`[Server] Erreur: Le port ${HTTP_PORT} est déjà utilisé. Veuillez vérifier si un autre processus utilise ce port.`);
    } else {
        console.error('[Server] Erreur critique du serveur HTTP:', err);
    }
    process.exit(1); // Arrêter le processus en cas d'erreur critique au démarrage
});

wss.on('error', (err) => {
    console.error('[Server] Erreur du serveur WebSocket:', err);
});

console.log(`[Server] Initialisation terminée. Le serveur est prêt.`);

// --- Instructions pour le Client Android ---
// Pour que le relais fonctionne, le client Android doit envoyer un message JSON
// au serveur WebSocket contenant la commande "CONNECT", l'hôte et le port de destination.
// Exemple de message JSON à envoyer par le client Android :
// `{"command": "CONNECT", "host": "www.google.com", "port": 80}`
//
// Les données suivantes envoyées par le client seront alors relayées vers cette destination.
// Les réponses du serveur distant seront renvoyées au client via le tunnel WebSocket.
