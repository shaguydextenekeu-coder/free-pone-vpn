const https = require('https');
const crypto = require('crypto');
const fs = require('fs');

const CONFIG = {
  tenancy: 'ocid1.tenancy.oc1..aaaaaaaagoguwb4jmasvhnxdo3ueferwy373fgnv3fymb4p6ehx4sifsgl3q',
  user: 'ocid1.user.oc1..aaaaaaaacnfzq7i77tpdggy7fhtlybk7x7hff6qtx33h2nxlffnnpmjzcpla',
  fingerprint: 'a5:4d:4f:7a:6c:ae:5a:da:e5:87:3a:53:12:01:a0:82',
  region: 'af-johannesburg-1',
  privateKeyPath: 'C:\\Users\\DELL\\.oci\\oci_api_key.pem',
  instanceId: 'ocid1.instance.oc1.af-johannesburg-1.anvg4ljrt7lz5racka4wxcpx2ytqz6p7u6adhrrgvpvzgpadhskfxpwhrnta'
};

const privateKey = fs.readFileSync(CONFIG.privateKeyPath, 'utf8');
const keyId = `${CONFIG.tenancy}/${CONFIG.user}/${CONFIG.fingerprint}`;
const host = `iaas.${CONFIG.region}.oraclecloud.com`;

function signRequest(method, targetPath, bodyString = '') {
  const date = new Date().toUTCString();
  const headersToSign = ['(request-target)', 'date', 'host'];
  let signingString = `(request-target): ${method.toLowerCase()} ${targetPath}\ndate: ${date}\nhost: ${host}`;

  const headers = {
    Host: host,
    Date: date
  };

  const signer = crypto.createSign('RSA-SHA256');
  signer.update(signingString);
  const signature = signer.sign(privateKey, 'base64');

  headers['Authorization'] = `Signature version="1",headers="${headersToSign.join(' ')}",keyId="${keyId}",algorithm="rsa-sha256",signature="${signature}"`;

  return headers;
}

function sendOciRequest(method, targetPath) {
  return new Promise((resolve, reject) => {
    const headers = signRequest(method, targetPath, '');

    const options = {
      hostname: host,
      port: 443,
      path: targetPath,
      method: method,
      headers: headers
    };

    const req = https.request(options, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        let json = null;
        try { json = JSON.parse(data); } catch (_) {}
        resolve({ statusCode: res.statusCode, body: json || data });
      });
    });

    req.on('error', reject);
    req.end();
  });
}

async function checkStatus() {
  const resInst = await sendOciRequest('GET', `/20160918/instances/${encodeURIComponent(CONFIG.instanceId)}`);
  console.log('Instance State:', resInst.body ? resInst.body.lifecycleState : resInst.statusCode);

  // VNIC attachments
  const resVnicAtt = await sendOciRequest('GET', `/20160918/vnicAttachments?compartmentId=${encodeURIComponent(CONFIG.tenancy)}&instanceId=${encodeURIComponent(CONFIG.instanceId)}`);
  if (Array.isArray(resVnicAtt.body) && resVnicAtt.body.length > 0) {
    const vnicId = resVnicAtt.body[0].vnicId;
    console.log('VnicId:', vnicId);
    if (vnicId) {
      const resVnic = await sendOciRequest('GET', `/20160918/vnics/${encodeURIComponent(vnicId)}`);
      console.log('Public IP:', resVnic.body ? resVnic.body.publicIp : 'none');
      console.log('Private IP:', resVnic.body ? resVnic.body.privateIp : 'none');
    }
  } else {
    console.log('No VNIC attached yet');
  }
}

checkStatus().catch(console.error);
