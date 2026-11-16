const https = require('https');
const crypto = require('crypto');
const fs = require('fs');

const CONFIG = {
  tenancy: 'ocid1.tenancy.oc1..aaaaaaaagoguwb4jmasvhnxdo3ueferwy373fgnv3fymb4p6ehx4sifsgl3q',
  user: 'ocid1.user.oc1..aaaaaaaacnfzq7i77tpdggy7fhtlybk7x7hff6qtx33h2nxlffnnpmjzcpla',
  fingerprint: 'a5:4d:4f:7a:6c:ae:5a:da:e5:87:3a:53:12:01:a0:82',
  region: 'af-johannesburg-1',
  privateKeyPath: 'C:\\Users\\DELL\\.oci\\oci_api_key.pem',
  secListId: 'ocid1.securitylist.oc1.af-johannesburg-1.aaaaaaaas5umvgbe5q3nte6le63nyvpjgkna3hchc2xr2d4piuswfywx7oza'
};

const privateKey = fs.readFileSync(CONFIG.privateKeyPath, 'utf8');
const keyId = `${CONFIG.tenancy}/${CONFIG.user}/${CONFIG.fingerprint}`;
const host = `iaas.${CONFIG.region}.oraclecloud.com`;
const targetPath = `/20160918/securityLists/${encodeURIComponent(CONFIG.secListId)}`;

const date = new Date().toUTCString();
const headersToSign = ['(request-target)', 'date', 'host'];
const signingString = `(request-target): get ${targetPath}\ndate: ${date}\nhost: ${host}`;

const signer = crypto.createSign('RSA-SHA256');
signer.update(signingString);
const signature = signer.sign(privateKey, 'base64');

const authHeader = `Signature version="1",headers="${headersToSign.join(' ')}",keyId="${keyId}",algorithm="rsa-sha256",signature="${signature}"`;

https.request({
  hostname: host,
  port: 443,
  path: targetPath,
  method: 'GET',
  headers: { Host: host, Date: date, Authorization: authHeader }
}, res => {
  let data = '';
  res.on('data', c => data += c);
  res.on('end', () => {
    try {
      const list = JSON.parse(data);
      console.log('Ingress rules:');
      list.ingressSecurityRules.forEach((r, idx) => {
        console.log(`[${idx}] proto=${r.protocol} src=${r.source} tcp=${JSON.stringify(r.tcpOptions)} udp=${JSON.stringify(r.udpOptions)} desc=${r.description}`);
      });
    } catch(e) {
      console.error(e, data);
    }
  });
}).end();
