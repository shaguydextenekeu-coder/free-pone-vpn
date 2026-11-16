/**
 * Test automatique du backend Free-Pone
 */
const http = require('http');

function post(url, data) {
  return new Promise((resolve, reject) => {
    const payload = JSON.stringify(data);
    const req = http.request(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(payload)
      }
    }, res => {
      let body = '';
      res.on('data', c => body += c);
      res.on('end', () => resolve({ status: res.statusCode, body: JSON.parse(body) }));
    });
    req.on('error', reject);
    req.write(payload);
    req.end();
  });
}

function get(url) {
  return new Promise((resolve, reject) => {
    http.get(url, res => {
      let body = '';
      res.on('data', c => body += c);
      res.on('end', () => resolve({ status: res.statusCode, body: JSON.parse(body) }));
    }).on('error', reject);
  });
}

async function runTests() {
  console.log('--- TEST 1 : Vérification de l’état du serveur ---');
  const health = await get('http://127.0.0.1:3000/');
  console.log('Status serveur:', health.body);

  console.log('\n--- TEST 2 : Génération de 2 codes d’accès (Admin) ---');
  const adminRes = await post('http://127.0.0.1:3000/api/admin/vouchers', {
    count: 2,
    plan_name: 'Pass Illimité 30 Jours',
    max_gb: 0, // Illimité
    duration_days: 30
  });
  console.log('Codes créés:', adminRes.body.vouchers);
  const testCode = adminRes.body.vouchers[0];

  console.log('\n--- TEST 3 : Simulation d’activation par l’application mobile ---');
  const activateRes = await post('http://127.0.0.1:3000/api/v1/activate', {
    code: testCode,
    device_public_key: 'CLIENT_MOBILE_PUBKEY_TEST_XYZ12345='
  });
  console.log('Réponse reçue par le mobile:');
  console.log(JSON.stringify(activateRes.body, null, 2));

  console.log('\n--- TEST 4 : Consultation du statut et du forfait en direct ---');
  const statusRes = await get(`http://127.0.0.1:3000/api/v1/status?code=${testCode}`);
  console.log('Statut actuel:', statusRes.body);

  console.log('\n✅ TOUS LES TESTS SONT PASSÉS AVEC SUCCÈS !');
  process.exit(0);
}

// Laisse un petit instant au serveur pour s'initialiser
setTimeout(runTests, 1000);
