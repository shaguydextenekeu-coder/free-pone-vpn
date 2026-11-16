const https = require('https');
const crypto = require('crypto');
const fs = require('fs');

const CONFIG = {
  tenancy: 'ocid1.tenancy.oc1..aaaaaaaagoguwb4jmasvhnxdo3ueferwy373fgnv3fymb4p6ehx4sifsgl3q',
  user: 'ocid1.user.oc1..aaaaaaaacnfzq7i77tpdggy7fhtlybk7x7hff6qtx33h2nxlffnnpmjzcpla',
  fingerprint: 'a5:4d:4f:7a:6c:ae:5a:da:e5:87:3a:53:12:01:a0:82',
  region: 'af-johannesburg-1',
  privateKeyPath: 'C:\\Users\\DELL\\.oci\\oci_api_key.pem',
  availabilityDomain: 'CPYY:AF-JOHANNESBURG-1-AD-1',
  subnetId: 'ocid1.subnet.oc1.af-johannesburg-1.aaaaaaaac7qku6f4zt5y4tizxrhz4ermjsvvdzavq7jx5f7oiwme3iynpvdq',
  armImageId: 'ocid1.image.oc1.af-johannesburg-1.aaaaaaaalnoih4v44jdmk3do326tqmbr73a5r4vo5sfrinvluziu2nt5larq',
  x86ImageId: 'ocid1.image.oc1.af-johannesburg-1.aaaaaaaavsnarf4lysxrndigsbq7eobjhmbm4vmftxwpxnq4af2alrilu6xq',
  sshPublicKey: 'ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDmELqeTifmyG77a2nteB3P83JHN534KQIMNN33KGzRV0GilEhCIOr4RoP2NDnlNfzBOxSOELIRwyWGns+/NaPzM8diRJIDT0f/r2gM293zSDqylmjmw14/k+7ibHs25a3k2NP/s+slsPail4s4i9zYiKU8u8ceqM2pO5ldy3l42oxVNxjaoE714PSf5M4xQsaj/Kj6wYirMvtl3r7yVEDvca/l3Ngg7d7MyPeJ01YitPYC2HdOTaxo6HNPhifsWcQNrGdEphJ51YsITjjYpeIwlQjxDi1BiGhZGRWLWo/lv0jQo0cj1UTz1zLQBnd503apXo2AYsUOIJSc4eIKK02Z ssh-key-2026-11-09'
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

  if (bodyString) {
    const sha256 = crypto.createHash('sha256').update(bodyString).digest('base64');
    headers['Content-Type'] = 'application/json';
    headers['Content-Length'] = Buffer.byteLength(bodyString);
    headers['x-content-sha256'] = sha256;

    headersToSign.push('x-content-sha256', 'content-type', 'content-length');
    signingString += `\nx-content-sha256: ${sha256}\ncontent-type: application/json\ncontent-length: ${headers['Content-Length']}`;
  }

  const signer = crypto.createSign('RSA-SHA256');
  signer.update(signingString);
  const signature = signer.sign(privateKey, 'base64');

  headers['Authorization'] = `Signature version="1",headers="${headersToSign.join(' ')}",keyId="${keyId}",algorithm="rsa-sha256",signature="${signature}"`;

  return headers;
}

function sendOciRequest(method, targetPath, bodyObj = null) {
  return new Promise((resolve, reject) => {
    const bodyString = bodyObj ? JSON.stringify(bodyObj) : '';
    const headers = signRequest(method, targetPath, bodyString);

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
        resolve({ statusCode: res.statusCode, headers: res.headers, body: json || data });
      });
    });

    req.on('error', reject);
    if (bodyString) req.write(bodyString);
    req.end();
  });
}

async function testLaunch() {
  console.log('Testing launch on Oracle Cloud (Johannesburg)...');
  
  // Test 1: Ampere ARM A1
  const armPayload = {
    compartmentId: CONFIG.tenancy,
    availabilityDomain: CONFIG.availabilityDomain,
    displayName: 'free-pone-wireguard-arm',
    shape: 'VM.Standard.A1.Flex',
    shapeConfig: {
      ocpus: 1,
      memoryInGBs: 6
    },
    sourceDetails: {
      sourceType: 'image',
      imageId: CONFIG.armImageId
    },
    createVnicDetails: {
      subnetId: CONFIG.subnetId,
      assignPublicIp: true,
      displayName: 'vnic-free-pone-arm'
    },
    metadata: {
      ssh_authorized_keys: CONFIG.sshPublicKey
    }
  };

  const resArm = await sendOciRequest('POST', '/20160918/instances', armPayload);
  console.log('ARM result:', resArm.statusCode, resArm.body ? resArm.body.message || resArm.body.code : resArm.body);

  // Test 2: AMD Micro
  const amdPayload = {
    compartmentId: CONFIG.tenancy,
    availabilityDomain: CONFIG.availabilityDomain,
    displayName: 'free-pone-wireguard-amd',
    shape: 'VM.Standard.E2.1.Micro',
    sourceDetails: {
      sourceType: 'image',
      imageId: CONFIG.x86ImageId
    },
    createVnicDetails: {
      subnetId: CONFIG.subnetId,
      assignPublicIp: true,
      displayName: 'vnic-free-pone-amd'
    },
    metadata: {
      ssh_authorized_keys: CONFIG.sshPublicKey
    }
  };

  const resAmd = await sendOciRequest('POST', '/20160918/instances', amdPayload);
  console.log('AMD result:', resAmd.statusCode, resAmd.body ? resAmd.body.message || resAmd.body.code : resAmd.body);
}

testLaunch().catch(console.error);
