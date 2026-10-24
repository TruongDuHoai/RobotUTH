#!/usr/bin/env bash
#
# robotuthv3 — TRIEN KHAI (mot lenh).
#
#   bash deploy/deploy.sh
#
# Chuoi: .env -> db.sh (tao DB) -> check_system.sh (kiem moi truong) -> start_system.sh (pm2 + /health)
#
# ====================== SCRIPT NAY KHONG CAI GI ======================
# Python, Node, PM2, nginx, PostgreSQL deu phai co san; script chi doc cau hinh,
# kiem qua, roi start. Thieu thi bao [THIEU] va DUNG — khong tu tai/cai.
# Cac buoc cai dat co tai lieu rieng — xem README.md muc Cai dat.
# ====================================================================
#
# Lan dau tren may moi: chay `sudo bash deploy/db.sh` truoc (hoac de buoc 2/4 tu
# bao roi chay lai bang sudo), vi viec tao role/database/extension doi quyen
# superuser — xem deploy/db.sh.
#
# Muon chi khoi dong lai cum da co DB + moi truong: bash deploy/start_system.sh
#
set -uo pipefail

DAY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${ROBOTUTH_ROOT:-$(cd "$DAY/.." && pwd)}"
cd "$ROOT"

printf '=== TRIEN KHAI ROBOTUTH — %s ===\n' "$ROOT"

echo
echo "== 1/4 Kiem tra .env =="
if [ ! -f .env ]; then
  echo "  [THIEU] chua co .env — lam truoc: cp .env.example .env  roi dien gia tri"
  echo "          Bat buoc: ROBOT_API_TOKENS (khac rong), POSTGRES_PASSWORD, OPENAI_API_KEY."
  exit 1
fi
echo "  [OK] .env"

echo
echo "== 2/4 Database (deploy/db.sh) =="
if ! bash "$DAY/db.sh"; then
  echo
  echo "  Dung: database chua san sang (xem [THIEU] o tren)."
  echo "  Lan dau tren may moi can quyen superuser:  sudo bash deploy/db.sh"
  exit 1
fi

echo
echo "== 3/4 Kiem tra he thong (deploy/check_system.sh) =="
if ! bash "$DAY/check_system.sh"; then
  echo
  echo "  Dung: con thieu muc bat buoc (xem [THIEU] o tren)."
  exit 1
fi

echo
echo "== 4/4 Khoi dong (deploy/start_system.sh) =="
if ! bash "$DAY/start_system.sh"; then
  echo
  echo "  Dung: khoi dong that bai (xem [LOI] o tren)."
  exit 1
fi

echo
echo "== TRIEN KHAI XONG =="
echo "  Gateway : http://<LAN-IP>:10010   (API docs: /docs)"
echo "  Theo doi: pm2 list | pm2 logs robotuth-server"
echo "  Tu chay khi boot (lam MOT lan):  pm2 startup   # roi chay dong sudo no in ra"
