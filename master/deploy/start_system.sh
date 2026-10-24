#!/usr/bin/env bash
#
# robotuthv3 — khoi dong cum PM2 (nginx gateway + FastAPI + ASR/OCR/TTS).
#
#   bash deploy/start_system.sh
#
# CHI lam 2 viec: `pm2 startOrReload deploy/ecosystem.config.cjs` + `pm2 save`,
# roi cho /health. KHONG tao database, KHONG kiem moi truong.
# Muon chay ca chuoi day du (env -> db -> check -> start): bash deploy/deploy.sh
#
# Idempotent: dung `pm2 startOrReload` nen chay lai duoc.
#
# Lan dau tien tren may moi: chay `sudo bash deploy/db.sh` truoc, vi viec tao
# role/database/extension doi quyen superuser (xem deploy/db.sh).
#
set -euo pipefail

DAY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${ROBOTUTH_ROOT:-$(cd "$DAY/.." && pwd)}"
cd "$ROOT"

echo "== Khoi dong PM2 =="
mkdir -p "$ROOT/data/nginx"
pm2 startOrReload "$ROOT/deploy/ecosystem.config.cjs"
pm2 save

echo
echo "== Cho /health (toi da 60s) =="
URL="http://127.0.0.1:10010/health"
for i in $(seq 1 30); do
  if curl -fsS -m 3 "$URL" >/dev/null 2>&1; then
    echo "  [OK] $URL"
    break
  fi
  if [ "$i" -eq 30 ]; then
    echo "  [LOI] $URL khong tra loi sau 60s"
    pm2 list || true
    echo "  Xem log: pm2 logs robotuth-server --lines 100"
    exit 1
  fi
  sleep 2
done

echo
echo "== Xong =="
echo "  Gateway : http://<LAN-IP>:10010   (API docs: /docs)"
echo "  Tu chay khi boot: pm2 startup   (roi chay lenh ma no in ra)"
pm2 list
