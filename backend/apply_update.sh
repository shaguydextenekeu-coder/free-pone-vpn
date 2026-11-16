#!/bin/bash
set -e
cp /home/ubuntu/Free-Pone.apk /opt/free-pone/Free-Pone.apk
chmod 644 /opt/free-pone/Free-Pone.apk
sqlite3 /opt/free-pone/free_pone.db "UPDATE settings SET value = '1.2.2' WHERE key = 'app_latest_version';"
sqlite3 /opt/free-pone/free_pone.db "UPDATE settings SET value = '5' WHERE key = 'app_latest_version_code';"
sqlite3 /opt/free-pone/free_pone.db "UPDATE settings SET value = 'Compatibilite reseaux mobiles et connexion instantanee' WHERE key = 'app_changelog';"
systemctl restart freepone.service
sleep 1
systemctl status freepone.service --no-pager
ls -lh /opt/free-pone/Free-Pone.apk
