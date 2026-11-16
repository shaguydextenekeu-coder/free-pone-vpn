const https = require('https');
const crypto = require('crypto');
const fs = require('fs');

const tenancy = 'ocid1.tenancy.oc1..aaaaaaaagoguwb4jmasvhnxdo3ueferwy373fgnv3fymb4p6ehx4sifsgl3q';
const user = 'ocid1.user.oc1..aaaaaaaacnfzq7i77tpdggy7fhtlybk7x7hff6qtx33h2nxlffnnpmjzcpla';
const fingerprint = 'a5:4d:4f:7a:6c:ae:5a:da:e5:87:3a:53:12:01:a0:82';
const region = 'af-johannesburg-1';
const privateKeyPath = 'C:\\Users\\DELL\\.oci\\oci_api_key.pem';
const privateKey = fs.readFileSync(privateKeyPath, 'utf8');

const keyId = `${tenancy}/${user}/${fingerprint}`;
const host = `iaas.${region}.oraclecloud.com`;

function signHeaders(method, targetPath) {
  const date = new Date().toUTCString();
  const headersToSign = ['(request-target)', 'date', 'host'];
  const signingString = `(request-target): ${method.toLowerCase()} ${targetPath}\ndate: ${date}\nhost: ${host}`;

  const signer = crypto.createSign('RSA-SHA256');
  signer.update(signingString);
  const signature = signer.sign(privateKey, 'base64');

  const authHeader = `Signature version="1",headers="${headersToSign.join(' ')}",keyId="${keyId}",algorithm="rsa-sha256",signature="${signature}"`;

  return { host, date, Authorization: authHeader };
}

const targetPath = `/20160918/images?compartmentId=${encodeURIComponent(tenancy)}&operatingSystem=Canonical%20Ubuntu&limit=50`;
const headers = signHeaders('GET', targetPath);

const options = {
  hostname: host,
  port: 443,
  path: targetPath,
  method: 'GET',
  headers: {
    'Date': headers.date,
    'Authorization': headers.Authorization,
    'Content-Type': 'application/json'
  }
};

const req = https.request(options, (res) => {
  let data = '';
  res.on('data', chunk => { data += chunk; });
  res.on('end', () => {
    try {
      const images = JSON.parse(data);
      const filtered = images.map(img => ({
        id: img.id,
        displayName: img.displayName,
        osVersion: img.operatingSystemVersion,
        arch: img.lifecycleState
      }));
      console.log('Images found:', JSON.stringify(filtered, null, 2));
    } catch (e) {
      console.log('Error parsing:', data);
    }
  });
});

req.end();
