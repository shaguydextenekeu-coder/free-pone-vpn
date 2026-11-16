const https = require('https');
const crypto = require('crypto');
const fs = require('fs');

const CONFIG = {
  tenancy: 'ocid1.tenancy.oc1..aaaaaaaagoguwb4jmasvhnxdo3ueferwy373fgnv3fymb4p6ehx4sifsgl3q',
  user: 'ocid1.user.oc1..aaaaaaaacnfzq7i77tpdggy7fhtlybk7x7hff6qtx33h2nxlffnnpmjzcpla',
  fingerprint: 'a5:4d:4f:7a:6c:ae:5a:da:e5:87:3a:53:12:01:a0:82',
  region: 'af-johannesburg-1',
  privateKeyPath: 'C:\\Users\\DELL\\.oci\\oci_api_key.pem'
};

const privateKey = fs.readFileSync(CONFIG.privateKeyPath, 'utf8');
const keyId = `${CONFIG.tenancy}/${CONFIG.user}/${CONFIG.fingerprint}`;
const host = `iaas.${CONFIG.region}.oraclecloud.com`;
const targetPath = `/20160918/instances?compartmentId=${encodeURIComponent(CONFIG.tenancy)}`;

const date = new Date().toUTCString();
const headersToSign = ['(request-target)', 'date', 'host'];
const signingString = `(request-target): get ${targetPath}\ndate: ${date}\nhost: ${host}`;

const signer = crypto.createSign('RSA-SHA256');
signer.update(signingString);
const signature = signer.sign(privateKey, 'base64');

const authHeader = `Signature version="1",headers="${headersToSign.join(' ')}",keyId="${keyId}",algorithm="rsa-sha256",signature="${signature}"`;

const req = https.request({
  hostname: host,
  port: 443,
  path: targetPath,
  method: 'GET',
  headers: {
    Host: host,
    Date: date,
    Authorization: authHeader
  }
}, res => {
  let data = '';
  res.on('data', chunk => data += chunk);
  res.on('end', () => {
    console.log('HTTP Status:', res.statusCode);
    try {
      const parsed = JSON.parse(data);
      console.log('Response:', JSON.stringify(parsed, null, 2));
    } catch(e) {
      console.log('Raw data:', data);
    }
  });
});
req.on('error', err => console.error('Req error:', err.message));
req.end();
