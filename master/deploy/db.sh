#!/usr/bin/env bash
#
# Tao / nang cap database Robotuth trong PostgreSQL (dung chung instance voi bookchat).
#
#   sudo bash deploy/db.sh              # tao role + database + extension + bang
#   bash deploy/db.sh --chi-xem         # chi kiem tra hien trang, khong ghi gi
#
# Idempotent: moi buoc kiem truoc khi tao -> chay lai khong hong.
# KHONG cai PostgreSQL. Chua co psql, hoac postgres khong chay thi bao roi dung.
#
# ============================== VI SAO CAN FILE NAY ==============================
# server/db/init_knowledge_db.py va init_face_db.py tu tao database va tu chay
# `CREATE EXTENSION vector`. Nhung ca hai deu doi quyen CAO HON role ung dung:
#   - CREATE DATABASE        -> doi CREATEDB
#   - CREATE EXTENSION vector -> doi SUPERUSER (pgvector khong phai trusted extension)
# Chay bang role `robotuth` se bao "permission denied to create extension vector".
# Te hon: `init_schema()` duoc goi lai MOI luot chat, nen loi nay lap vo han.
# File nay lam phan superuser TRUOC (role / database / extension), roi moi de
# Python tao bang bang chinh role ung dung.
#
# Thu tu dung (hoac mot lenh: bash deploy/deploy.sh):
#   sudo bash deploy/db.sh          <- buoc nay
#   bash deploy/check_system.sh
#   bash deploy/start_system.sh
# ===============================================================================
set -uo pipefail

DAY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${ROBOTUTH_ROOT:-$(cd "$DAY/.." && pwd)}"
cd "$ROOT"
CHI_XEM=0
[ "${1:-}" = "--chi-xem" ] && CHI_XEM=1
FAIL=0

ok()    { printf '  [OK]    %s\n' "$1"; }
thieu() { printf '  [THIEU] %s\n' "$1"; FAIL=1; }
info()  { printf '  [.]     %s\n' "$1"; }
step()  { printf '\n=== %s ===\n' "$1"; }

# Doc 1 khoa tu .env theo cach AN TOAN. KHONG `source .env`: gia tri co dau cach
# (vd DEEPGRAM_KEYTERMS) se lam vo shell — dung loi bookchat gap 25/09/2026.
env_get() {
  [ -f "$ROOT/.env" ] || return 0
  grep -E "^[[:space:]]*$1=" "$ROOT/.env" | tail -1 | cut -d= -f2- \
    | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e 's/^"//' -e 's/"$//'
}

# Truy van bang role UNG DUNG (khong can superuser). Mat khau qua PGPASSWORD,
# khong nhet vao argv (tranh lo qua `ps`).
app_q() {  # $1=db  $2=sql
  PGPASSWORD="$PGW" psql -h "$PGH" -p "$PGP" -U "$PGU" -d "$1" -tAc "$2" 2>/dev/null
}

# Truy van bang SUPERUSER: uu tien PGSUPER_DSN, khong thi `sudo -u postgres`.
SUPER_DSN="${PGSUPER_DSN:-}"
run_super() {  # $1=db  rest = psql args
  local db="$1"; shift
  if [ -n "$SUPER_DSN" ]; then psql "$SUPER_DSN" -d "$db" "$@"; else sudo -u postgres psql -d "$db" "$@"; fi
}

CAC_BANG_KNOWLEDGE="departments users robots knowledge_bundles audit_log knowledge_documents knowledge_chunks"
CAC_BANG_FACE="face_embeddings attendance_records"

printf '=== DATABASE ROBOTUTH — %s ===\n' "$ROOT"

# ---------------------------------------------------------------- 1. cau hinh
step "1. Cau hinh"
if [ -f "$ROOT/.env" ]; then info ".env: $ROOT/.env"; else thieu "chua co .env — cp .env.example .env roi dien gia tri"; fi
PGH="$(env_get POSTGRES_HOST)"; PGH="${PGH:-127.0.0.1}"
PGP="$(env_get POSTGRES_PORT)"; PGP="${PGP:-5432}"
PGU="$(env_get POSTGRES_USER)"; PGU="${PGU:-robotuth}"
PGW="$(env_get POSTGRES_PASSWORD)"
DBK="$(env_get POSTGRES_DB_KNOWLEDGE)"; DBK="${DBK:-robotuth_knowledge}"
DBF="$(env_get POSTGRES_DB_FACE)"; DBF="${DBF:-robotuth_face}"
FACE="$(env_get FACE_ENABLED)"; FACE="${FACE:-false}"

info "host=$PGH port=$PGP user=$PGU"
info "db_knowledge=$DBK  db_face=$DBF  (FACE_ENABLED=$FACE)"
case "$PGU$DBK$DBF" in
  *[!A-Za-z0-9_]*) echo "  [LOI] ten user/database chi duoc chu, so, gach duoi"; exit 1 ;;
esac
[ -n "$PGW" ] || thieu "POSTGRES_PASSWORD rong trong .env"
case "$PGH" in
  127.0.0.1|localhost|"") ;;
  *) thieu "POSTGRES_HOST=$PGH tro toi may khac — file nay chay psql CUC BO; hay nap tay tren may chu" ;;
esac

# ---------------------------------------------------------------- 2. cong cu
step "2. Kiem cong cu (khong cai gi)"
command -v psql >/dev/null 2>&1 \
  && ok "psql $(psql --version 2>/dev/null | awk '{print $3}')" \
  || thieu "chua co psql — cai postgresql-client"

PY="$ROOT/.venv/bin/python"
if [ -x "$PY" ]; then
  ok "venv: $ROOT/.venv"
  "$PY" -c 'import psycopg' 2>/dev/null \
    && ok "psycopg co trong venv" \
    || thieu "thieu psycopg — .venv/bin/pip install -e ."
else
  thieu "chua co venv tai $PY — python3 -m venv .venv && .venv/bin/pip install -e ."
fi

if run_super postgres -tAc 'SELECT 1' >/dev/null 2>&1; then
  ok "ket noi duoc superuser ($([ -n "$SUPER_DSN" ] && echo PGSUPER_DSN || echo 'sudo -u postgres'))"
  HAVE_SUPER=1
else
  HAVE_SUPER=0
  info "khong ket noi duoc superuser — CHI KIEM TRA duoc, khong tao duoc"
  info "  can: chay bang sudo, hoac dat PGSUPER_DSN=postgresql://postgres:...@127.0.0.1:5432/postgres"
fi

# ------------------------------------------------------- 3. kiem lai (read-only)
kiem_lai() {
  step "Kiem lai"
  # Extension: nguon loi "permission denied" neu thieu.
  local nv
  nv="$(app_q "$DBK" "SELECT count(*) FROM pg_extension WHERE extname='vector'")"
  if [ "${nv:-0}" = "1" ]; then ok "$DBK: extension vector"; else thieu "$DBK: THIEU extension vector"; fi

  # Bang knowledge (7 bang).
  local want n
  want="$(printf "%s" "$CAC_BANG_KNOWLEDGE" | wc -w)"
  n="$(app_q "$DBK" "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename IN ($(printf "'%s'," $CAC_BANG_KNOWLEDGE | sed 's/,$//'))")"
  if [ "${n:-0}" -ge "$want" ]; then ok "$DBK: du ${n:-0}/$want bang"; else thieu "$DBK: chi ${n:-0}/$want bang (chay lai db.sh)"; fi

  # Chu so huu la co ng that: SQL khong co GRANT nao, bang thuoc `postgres` thi
  # role ung dung bao "permission denied for table knowledge_documents".
  local sohuu
  sohuu="$(app_q "$DBK" "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tableowner='$PGU' AND tablename IN ($(printf "'%s'," $CAC_BANG_KNOWLEDGE | sed 's/,$//'))")"
  if [ "${sohuu:-0}" -ge "$want" ]; then ok "$DBK: ${sohuu:-0}/${n:-0} bang thuoc '$PGU'"; else thieu "$DBK: chi ${sohuu:-0}/${n:-0} bang thuoc '$PGU' — role ung dung se khong doc/ghi duoc"; fi

  if [ "$FACE" = "true" ]; then
    local wantf nf
    wantf="$(printf "%s" "$CAC_BANG_FACE" | wc -w)"
    nf="$(app_q "$DBF" "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename IN ($(printf "'%s'," $CAC_BANG_FACE | sed 's/,$//'))")"
    if [ "${nf:-0}" -ge "$wantf" ]; then ok "$DBF: du ${nf:-0}/$wantf bang"; else thieu "$DBF: chi ${nf:-0}/$wantf bang (chay lai db.sh)"; fi
  fi
}

if [ "$CHI_XEM" = "1" ]; then
  step "3. Ke hoach"
  echo "  (--chi-xem: KHONG ghi gi. Bo co nay de chay that.)"
  echo "  Se lam: tao role '$PGU' -> database -> CREATE EXTENSION vector -> tao bang bang Python."
  kiem_lai
  echo
  if [ "$FAIL" = "0" ]; then echo "  Hien trang: OK."; else echo "  Hien trang: con THIEU (xem [THIEU] o tren)."; fi
  exit "$FAIL"
fi

step "3. Ke hoach"
echo "  1) tao role '$PGU' (LOGIN + CREATEDB) neu chua co"
if [ "$FACE" = "true" ]; then
  echo "  2) tao database '$DBK' va '$DBF' neu chua co"
else
  echo "  2) tao database '$DBK' neu chua co (bo qua '$DBF' vi FACE_ENABLED=false)"
fi
echo "  3) CREATE EXTENSION vector (them uuid-ossp cho face) bang superuser"
echo "  4) tao bang bang Python: server.db.init_knowledge_db$( [ "$FACE" = "true" ] && printf " + server.db.init_face_db" )"
echo "  5) kiem lai extension + so bang + chu so huu"

if [ "$FAIL" != "0" ]; then
  echo
  echo "  [DUNG] Con muc THIEU o tren (script KHONG tu cai)."
  exit 1
fi
if [ "$HAVE_SUPER" != "1" ]; then
  echo
  echo "  [DUNG] Can quyen superuser de tao role/database/extension."
  echo "         Chay lai bang:  sudo bash deploy/db.sh"
  echo "         (hoac dat PGSUPER_DSN=postgresql://postgres:...@127.0.0.1:5432/postgres)"
  exit 1
fi

# --------------------------------------------------------- 4. role + database
step "4. Role + database"
if [ "$(run_super postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='$PGU'")" = "1" ]; then
  ok "role '$PGU' da co"
  # Dam bao CREATEDB de cac script init_*.py tu tao database duoc khi can.
  run_super postgres -qc "ALTER ROLE \"$PGU\" WITH LOGIN CREATEDB" >/dev/null 2>&1 \
    && ok "role '$PGU': LOGIN + CREATEDB" || thieu "khong chinh duoc quyen role '$PGU'"
elif run_super postgres -qc "CREATE ROLE \"$PGU\" WITH LOGIN CREATEDB PASSWORD '$PGW'" >/dev/null 2>&1; then
  ok "da tao role '$PGU' (LOGIN + CREATEDB)"
else
  thieu "khong tao duoc role '$PGU'"
fi

tao_db() {  # $1=ten db
  if [ "$(run_super postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$1'")" = "1" ]; then
    ok "database '$1' da co"
  elif run_super postgres -qc "CREATE DATABASE \"$1\" OWNER \"$PGU\"" >/dev/null 2>&1; then
    ok "da tao database '$1'"
  else
    thieu "khong tao duoc database '$1'"
  fi
}
tao_db "$DBK"
[ "$FACE" = "true" ] && tao_db "$DBF"

# ------------------------------------------------------------- 5. extension
# Phai chay bang superuser: pgvector khong phai trusted extension.
step "5. Extension (superuser)"
LOG=/tmp/robotuth-db-ext.log
if run_super "$DBK" -q -v ON_ERROR_STOP=1 -c 'CREATE EXTENSION IF NOT EXISTS vector' >"$LOG" 2>&1; then
  ok "$DBK: vector"
else
  thieu "$DBK: vector — $(grep -E '^(psql:|ERROR)' "$LOG" 2>/dev/null | tail -1)"
fi
if [ "$FACE" = "true" ]; then
  if run_super "$DBF" -q -v ON_ERROR_STOP=1 \
      -c 'CREATE EXTENSION IF NOT EXISTS vector' \
      -c 'CREATE EXTENSION IF NOT EXISTS "uuid-ossp"' >"$LOG" 2>&1; then
    ok "$DBF: vector + uuid-ossp"
  else
    thieu "$DBF: extension — $(grep -E '^(psql:|ERROR)' "$LOG" 2>/dev/null | tail -1)"
  fi
fi

# ---------------------------------------------------------------- 6. bang
step "6. Tao bang (Python, role '$PGU')"
if [ "$FAIL" = "0" ]; then
  if "$PY" -m server.db.init_knowledge_db; then
    ok "server.db.init_knowledge_db"
  else
    thieu "server.db.init_knowledge_db that bai"
  fi
  if [ "$FACE" = "true" ]; then
    if "$PY" -m server.db.init_face_db; then
      ok "server.db.init_face_db"
    else
      thieu "server.db.init_face_db that bai"
    fi
  else
    info "FACE_ENABLED=false — bo qua server.db.init_face_db"
  fi
else
  info "bo qua (con muc THIEU o tren)"
fi

# ---------------------------------------------------------------- 7. ket qua
kiem_lai

echo
if [ "$FAIL" = "0" ]; then
  echo "  Xong. DB san sang — tiep theo:  bash deploy/deploy.sh"
else
  echo "  Con loi o tren — chua xong."
fi
exit "$FAIL"
