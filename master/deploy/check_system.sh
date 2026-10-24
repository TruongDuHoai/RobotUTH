#!/usr/bin/env bash
#
# robotuthv3 — kiem tra moi thu can de van hanh he thong.
#
#   bash deploy/check_system.sh
#
# CHI KIEM, KHONG TU CAI. Thieu gi thi bao [THIEU] va noi ro can gi.
# Exit 0 = du dieu kien chay; exit 1 = con thieu muc bat buoc.
#
# Vi sao khong dung Docker: he thong chay truc tiep tren host bang PM2
# (xem deploy/ecosystem.config.cjs), Postgres dung chung instance voi bookchat.
#
set -uo pipefail

DAY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${ROBOTUTH_ROOT:-$(cd "$DAY/.." && pwd)}"
cd "$ROOT"

OK=0
MISS=0
ok()      { printf '  [OK]    %s\n' "$1"; OK=$((OK+1)); }
thieu()   { printf '  [THIEU] %s\n' "$1"; MISS=$((MISS+1)); }
canh_bao() { printf '  [CANH BAO] %s\n' "$1"; }
info()    { printf '  [--]    %s\n' "$1"; }
head_()   { printf '\n== %s ==\n' "$1"; }

# Doc 1 khoa tu .env theo cach AN TOAN. KHONG dung `source .env`: gia tri co dau
# cach (vd DEEPGRAM_KEYTERMS) se lam vo shell — dung loi bookchat gap 25/09/2026.
env_get() {
  [ -f .env ] || return 0
  grep -E "^[[:space:]]*$1=" .env | tail -1 | cut -d= -f2- \
    | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e 's/^"//' -e 's/"$//'
}

tcp_open()  { timeout 3 bash -c "echo > /dev/tcp/$1/$2" 2>/dev/null; }
port_free() { ! timeout 2 bash -c "echo > /dev/tcp/127.0.0.1/$1" 2>/dev/null; }

echo "robotuthv3 — kiem tra he thong"
echo "Thu muc: $ROOT"

# --- 1. Python --------------------------------------------------------------
head_ "Python"
SYS_PY="$(command -v python3 || true)"
if [ -z "$SYS_PY" ]; then
  thieu "python3 khong co trong PATH"
elif "$SYS_PY" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3,10) else 1)'; then
  ok "python3 $("$SYS_PY" -c 'import sys;print("%d.%d.%d"%sys.version_info[:3])')"
else
  thieu "python3 qua cu (can >= 3.10)"
fi

if [ -x "$ROOT/.venv/bin/python" ]; then
  ok "venv: $ROOT/.venv"
  if "$ROOT/.venv/bin/python" -c 'import fastapi, uvicorn, httpx, psycopg, dotenv, numpy, PIL' 2>/dev/null; then
    ok "deps trong venv (fastapi/uvicorn/httpx/psycopg/dotenv/numpy/PIL)"
  else
    thieu "thieu deps trong venv — chay: .venv/bin/pip install -e \".[face]\""
  fi
else
  thieu "chua co venv tai $ROOT/.venv — tao bang: python3 -m venv .venv && .venv/bin/pip install -e \".[face]\""
fi

# --- 1b. Venv dich vu GPU (ASR/TTS + OCR) -----------------------------------
# PM2 chay 3 app nay bang venv RIENG (xem deploy/ecosystem.config.cjs). Thieu venv
# thi app restart-loop (exp_backoff nen khong chet han, nhung khong phuc vu duoc).
head_ "Venv dich vu GPU"
ASR_PY="$ROOT/.venv-asr/bin/python"
if [ -x "$ASR_PY" ]; then
  ok "venv ASR+TTS: $ROOT/.venv-asr"
  if "$ASR_PY" -c 'import fastapi, uvicorn, soundfile, numpy' 2>/dev/null; then
    ok ".venv-asr: deps co ban"
  else
    canh_bao ".venv-asr thieu fastapi/uvicorn/soundfile/numpy — .venv-asr/bin/pip install -e \".[speech]\""
  fi
  # torch (ASR), nemo_toolkit (ASR), vieneu (TTS) cai RIENG theo CUDA nen chi canh bao.
  "$ASR_PY" -c 'import torch' 2>/dev/null && ok ".venv-asr: torch" \
    || canh_bao ".venv-asr thieu torch — .venv-asr/bin/pip install torch torchaudio --index-url https://download.pytorch.org/whl/cu128 (Blackwell sm_120 can CUDA >= 12.8)"
  "$ASR_PY" -c 'import nemo' 2>/dev/null && ok ".venv-asr: nemo_toolkit (ASR)" \
    || canh_bao ".venv-asr thieu nemo_toolkit — pip install \"nemo_toolkit[asr]>=1.21.0\""
else
  thieu "chua co .venv-asr — python3 -m venv .venv-asr && .venv-asr/bin/pip install -e \".[speech]\" (+ torch, nemo_toolkit, llama-cpp-python)"
fi

OCR_PY="$ROOT/.venv-ocr/bin/python"
if [ -x "$OCR_PY" ]; then
  ok "venv OCR: $ROOT/.venv-ocr"
  if "$OCR_PY" -c 'import fastapi, uvicorn, paddleocr' 2>/dev/null; then
    ok ".venv-ocr: paddleocr"
  else
    canh_bao ".venv-ocr thieu paddleocr/paddlepaddle — .venv-ocr/bin/pip install -e \".[ocr]\" (+ paddlepaddle-gpu)"
  fi
else
  # OCR la tuy chon (chi can cho PDF scan) nen khong chan deploy.
  canh_bao "chua co .venv-ocr — robotuth-ocr se restart-loop. Tao venv, hoac bo app do neu khong dung PDF scan"
fi

# --- 2. Tien ich he thong ---------------------------------------------------
head_ "Tien ich"
if command -v pm2 >/dev/null 2>&1; then ok "pm2 $(pm2 -v 2>/dev/null)"; else
  thieu "pm2 chua cai — npm i -g pm2"
fi
if command -v nginx >/dev/null 2>&1; then ok "nginx $(nginx -v 2>&1 | sed 's/.*nginx\///')"; else
  thieu "nginx chua cai — apt install nginx (chi can binary, khong bat service)"
fi
if command -v psql >/dev/null 2>&1; then ok "psql"; else
  thieu "psql chua cai — apt install postgresql-client (de kiem tra DB)"
fi

# --- 2b. Dung luong dia -----------------------------------------------------
# Cai venv GPU (torch + nemo + paddle) can ~10 GB. Loi hay gap nhat la
# "No space left on device": gcc ghi /tmp, CMake ghi /var/tmp, pip ghi cache.
# Canh bao SOM thay vi de chet giua luc cai. (Deploy thuong KHONG cai gi.)
head_ "Dung luong dia"
free_mb() { df -Pm "$1" 2>/dev/null | awk 'NR==2 {print $4}'; }
TMPD="${TMPDIR:-/tmp}"
for d in "$ROOT" "$TMPD" /var/tmp; do
  [ -d "$d" ] || continue
  mb="$(free_mb "$d")"
  case "$mb" in
    ''|*[!0-9]*) info "$d: khong doc duoc dung luong" ;;
    *) if [ "$mb" -lt 1024 ]; then
         canh_bao "$d: chi con ${mb} MB — cai venv GPU se that bai (can ~10 GB cho .venv-asr)"
       elif [ "$mb" -lt 5120 ]; then
         canh_bao "$d: con ${mb} MB — du de deploy, nhung cai torch/nemo (~10 GB) co the thieu"
       else
         ok "$d: con $((mb / 1024)) GB"
       fi ;;
  esac
  ipct="$(df -Pi "$d" 2>/dev/null | awk 'NR==2 {gsub("%","",$5); print $5}')"
  case "$ipct" in
    ''|*[!0-9]*) : ;;
    *) [ "$ipct" -ge 90 ] && canh_bao "$d: inode da dung ${ipct}% (df -i) — don file rac trong /tmp" ;;
  esac
done
[ -n "${TMPDIR:-}" ] || info "TMPDIR chua dat (build tu source ghi /tmp) — neu /tmp nho: export TMPDIR=<o con trong> (xem README)"

# --- 3. Cau hinh .env -------------------------------------------------------
head_ "Cau hinh .env"
if [ -f .env ]; then
  ok ".env ton tai"
  TOKENS="$(env_get ROBOT_API_TOKENS)"
  if [ -n "$TOKENS" ]; then
    ok "ROBOT_API_TOKENS da dat"
  else
    thieu "ROBOT_API_TOKENS RONG — auth se FAIL-OPEN (moi API khong can token). Dat token truoc khi chay."
  fi
else
  thieu "chua co .env — copy: cp .env.example .env  roi dien gia tri"
fi

FACE="$(env_get FACE_ENABLED)"; FACE="${FACE:-false}"
REDIS="$(env_get REDIS_ENABLED)"; REDIS="${REDIS:-true}"
if [ "$FACE" = "false" ] && [ "$REDIS" = "true" ]; then
  canh_bao "FACE_ENABLED=false nhung REDIS_ENABLED=true — Redis chi dung cho face, co the dat REDIS_ENABLED=false"
else
  ok "co nhat quan FACE_ENABLED=$FACE / REDIS_ENABLED=$REDIS"
fi

# --- 4. PostgreSQL (dung chung voi bookchat) --------------------------------
head_ "PostgreSQL"
PGH="$(env_get POSTGRES_HOST)"; PGH="${PGH:-127.0.0.1}"
PGP="$(env_get POSTGRES_PORT)"; PGP="${PGP:-5432}"
PGU="$(env_get POSTGRES_USER)"; PGU="${PGU:-robotuth}"
PGW="$(env_get POSTGRES_PASSWORD)"
DBK="$(env_get POSTGRES_DB_KNOWLEDGE)"; DBK="${DBK:-robotuth_knowledge}"
DBF="$(env_get POSTGRES_DB_FACE)"; DBF="${DBF:-robotuth_face}"

if command -v psql >/dev/null 2>&1; then
  DSN="postgresql://${PGU}:${PGW}@${PGH}:${PGP}/postgres"
  if psql "$DSN" -tAc 'SELECT 1' >/dev/null 2>&1; then
    ok "ket noi duoc ${PGU}@${PGH}:${PGP}"
    db_check() {  # $1=ten db  $2=required|optional
      local n
      n="$(psql "$DSN" -tAc "SELECT 1 FROM pg_database WHERE datname='$1'" 2>/dev/null)"
      if [ "$n" = "1" ]; then ok "database '$1' ton tai"
      elif [ "$2" = optional ]; then info "database '$1' chua co (khong bat buoc khi FACE_ENABLED=false)"
      else thieu "database '$1' chua co - chay: bash deploy/db.sh"; fi
    }
    db_check "$DBK" required
    if [ "$FACE" = "true" ]; then db_check "$DBF" required; else db_check "$DBF" optional; fi
  else
    thieu "khong ket noi duoc Postgres ${PGU}@${PGH}:${PGP} — kiem tra service + user/mat khau trong .env"
  fi
else
  info "bo qua (chua co psql — xem muc Tien ich)"
fi

# --- 5. Host AI services (cung may) ----------------------------------------
# LUU Y: ASR/TTS/OCR do PM2 khoi dong (deploy/start_system.sh) — o lan chay DAU
# TIEN chung CHUA len. Vi vay KHONG danh [THIEU]: neu danh thi deploy.sh se dung
# o buoc check truoc khi kip start. Chi 9router la dich vu NGOAI, phai co san.
head_ "Host AI services"
tcp_open 127.0.0.1 20128 && ok "9router  :20128 (LLM)" \
  || thieu "9router :20128 khong tra loi (LLM upstream — dich vu NGOAI, phai chay truoc)"
tcp_open 127.0.0.1 8002 && ok "ASR      :8002" \
  || info "ASR :8002 chua chay — PM2 se start (deploy/gpu/asr_gpu_server.py)"
tcp_open 127.0.0.1 8004 && ok "TTS      :8004" \
  || info "TTS :8004 chua chay — PM2 se start (deploy/gpu/tts_gpu_server.py)"
if tcp_open 127.0.0.1 8003; then ok "OCR      :8003 (tuy chon)"; else info "OCR :8003 chua chay — chi can cho PDF scan"; fi

# --- 6. Cong gateway --------------------------------------------------------
head_ "Cong"
if port_free 10010; then
  ok "cong 10010 trong"
else
  info "cong 10010 dang duoc dung (co the la gateway dang chay — on neu ban chi muon restart)"
fi

# --- Ket qua -----------------------------------------------------------------
echo
if [ "$MISS" -eq 0 ]; then
  echo "KET QUA: OK ($OK muc dat). Chay: bash deploy/deploy.sh"
  exit 0
else
  echo "KET QUA: THIEU $MISS muc bat buoc (xem [THIEU] o tren)."
  exit 1
fi
