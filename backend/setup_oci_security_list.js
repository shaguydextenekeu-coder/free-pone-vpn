const https = require('https');
const crypto = require('crypto');
const fs = require('fs');

const CONFIG = {
  tenancy: 'ocid1.tenancy.oc1..aaaaaaaagoguwb4jmasvhnxdo3ueferwy373fgnv3fymb4p6ehx4sifsgl3q',
  user: 'ocid1.user.oc1..aaaaaaaacnfzq7i77tpdggy7fhtlybk7x7hff6qtx33h2nxlffnnpmjzcpla',
  fingerprint: 'a5:4d:4f:7a:6c:ae:5a:da:e5:87:3a:53:12:01:a0:82',
  region: 'af-johannesburg-1',
  privateKeyPath: 'C:\\Users\\DELL\\.oci\\oci_api_key.pem',
  subnetId: 'ocid1.subnet.oc1.af-johannesburg-1.aaaaaaaac7qku6f4zt5y4tizxrhz4ermjsvvdzavq7jx5f7oiwme3iynpvdq'
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
        resolve({ statusCode: res.statusCode, body: json || data });
      });
    });

    req.on('error', reject);
    if (bodyString) req.write(bodyString);
    req.end();
  });
}

async function configureFirewall() {
  console.log('1. Fetching subnet details...');
  const resSubnet = await sendOciRequest('GET', `/20160918/subnets/${encodeURIComponent(CONFIG.subnetId)}`);
  if (!resSubnet.body || !resSubnet.body.securityListIds) {
    console.error('Failed to get subnet security lists:', resSubnet);
    return;
  }

  const secListId = resSubnet.body.securityListIds[0];
  console.log('Security List ID:', secListId);

  console.log('2. Fetching existing Security List rules...');
  const resSecList = await sendOciRequest('GET', `/20160918/securityLists/${encodeURIComponent(secListId)}`);
  const secList = resSecList.body;

  let ingressRules = secList.ingressSecurityRules || [];
  console.log(`Current ingress rules count: ${ingressRules.length}`);

  // Ports to ensure are open:
  // - 51820 UDP (WireGuard VPN)
  // - 3000 TCP (Free-Pone Backend API & Admin Dashboard)
  // - 80 TCP (HTTP)
  // - 443 TCP (HTTPS)

  const hasWireguard = ingressRules.some(r => r.protocol === '17' && r.udpOptions && r.udpOptions.destinationPortRange && r.udpOptions.destinationPortRange.min <= 51820 && r.udpOptions.destinationPortRange.max >= 51820);
  const hasPort3000 = ingressRules.some(r => r.protocol === '6' && r.tcpOptions && r.tcpOptions.destinationPortRange && r.tcpOptions.destinationPortRange.min <= 3000 && r.tcpOptions.destinationPortRange.max >= 3000);
  const hasHttp = ingressRules.some(r => r.protocol === '6' && r.tcpOptions && r.tcpOptions.destinationPortRange && r.tcpOptions.destinationPortRange.min <= 80 && r.tcpOptions.destinationPortRange.max >= 80);
  const hasHttps = ingressRules.some(r => r.protocol === '6' && r.tcpOptions && r.tcpOptions.destinationPortRange && r.tcpOptions.destinationPortRange.min <= 443 && r.tcpOptions.destinationPortRange.max >= 443);

  let updated = false;

  if (!hasWireguard) {
    console.log('+ Adding rule for WireGuard (51820 UDP)...');
    ingressRules.push({
      protocol: '17', // UDP
      source: '0.0.0.0/0',
      isStateless: false,
      description: 'Free-Pone WireGuard VPN Tunnel',
      udpOptions: {
        destinationPortRange: { min: 51820, max: 51820 }
      }
    });
    updated = true;
  }

  if (!hasPort3000) {
    console.log('+ Adding rule for Backend API (3000 TCP)...');
    ingressRules.push({
      protocol: '6', // TCP
      source: '0.0.0.0/0',
      isStateless: false,
      description: 'Free-Pone Backend API & Admin Web Dashboard',
      tcpOptions: {
        destinationPortRange: { min: 3000, max: 3000 }
      }
    });
    updated = true;
  }

  if (!hasHttp) {
    ingressRules.push({
      protocol: '6',
      source: '0.0.0.0/0',
      isStateless: false,
      description: 'HTTP Web Traffic',
      tcpOptions: { destinationPortRange: { min: 80, max: 80 } }
    });
    updated = true;
  }

  if (!hasHttps) {
    ingressRules.push({
      protocol: '6',
      source: '0.0.0.0/0',
      isStateless: false,
      description: 'HTTPS Encrypted Traffic',
      tcpOptions: { destinationPortRange: { min: 443, max: 443 } }
    });
    updated = true;
  }

  const hasUdp53 = ingressRules.some(r => r.protocol === '17' && r.udpOptions && r.udpOptions.destinationPortRange && r.udpOptions.destinationPortRange.min <= 53 && r.udpOptions.destinationPortRange.max >= 53);
  const hasUdp80 = ingressRules.some(r => r.protocol === '17' && r.udpOptions && r.udpOptions.destinationPortRange && r.udpOptions.destinationPortRange.min <= 80 && r.udpOptions.destinationPortRange.max >= 80);
  const hasUdp443 = ingressRules.some(r => r.protocol === '17' && r.udpOptions && r.udpOptions.destinationPortRange && r.udpOptions.destinationPortRange.min <= 443 && r.udpOptions.destinationPortRange.max >= 443);
  const hasPort8080 = ingressRules.some(r => r.protocol === '6' && r.tcpOptions && r.tcpOptions.destinationPortRange && r.tcpOptions.destinationPortRange.min <= 8080 && r.tcpOptions.destinationPortRange.max >= 8080);

  if (!hasUdp53) {
    ingressRules.push({
      protocol: '17',
      source: '0.0.0.0/0',
      isStateless: false,
      description: 'DNS UDP Bypass Port 53',
      udpOptions: { destinationPortRange: { min: 53, max: 53 } }
    });
    updated = true;
  }
  if (!hasUdp80) {
    ingressRules.push({
      protocol: '17',
      source: '0.0.0.0/0',
      isStateless: false,
      description: 'WireGuard UDP 80',
      udpOptions: { destinationPortRange: { min: 80, max: 80 } }
    });
    updated = true;
  }
  if (!hasUdp443) {
    ingressRules.push({
      protocol: '17',
      source: '0.0.0.0/0',
      isStateless: false,
      description: 'WireGuard UDP 443',
      udpOptions: { destinationPortRange: { min: 443, max: 443 } }
    });
    updated = true;
  }
  if (!hasPort8080) {
    ingressRules.push({
      protocol: '6',
      source: '0.0.0.0/0',
      isStateless: false,
      description: 'Proxy & API 8080',
      tcpOptions: { destinationPortRange: { min: 8080, max: 8080 } }
    });
    updated = true;
  }

  if (updated) {
    console.log('3. Updating Security List in Oracle Cloud...');
    const updatePayload = {
      ingressSecurityRules: ingressRules
    };
    const resUpdate = await sendOciRequest('PUT', `/20160918/securityLists/${encodeURIComponent(secListId)}`, updatePayload);
    console.log('Update result status:', resUpdate.statusCode);
    if (resUpdate.statusCode === 200) {
      console.log('SUCCESS: Oracle Cloud firewall opened for WireGuard (51820 UDP) and API (3000 TCP)!');
    } else {
      console.error('Error updating Security List:', resUpdate.body);
    }
  } else {
    console.log('All required ports are already open in Oracle Cloud Security List.');
  }
}

configureFirewall().catch(console.error);
