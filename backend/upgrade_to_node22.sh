#!/bin/bash
set -e
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt-get install -y nodejs
echo "New Node Version: $(node -v)"
node -e "const { DatabaseSync } = require('node:sqlite'); console.log('NODE:SQLITE AVAILABLE: OK');"
