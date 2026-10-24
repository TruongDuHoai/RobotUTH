/**
 * PM2 ecosystem — robotuthv3 production (no Docker).
 *
 *   pm2 start deploy/ecosystem.config.cjs
 *   pm2 save                 # luu de tu chay lai khi boot
 *   pm2 logs robotuth-server
 *
 * Nam tien trinh:
 *   robotuth-gateway  nginx    — cua LAN duy nhat tren :10010 (deploy/nginx.conf)
 *   robotuth-server   FastAPI  — `python -m server.main`, tu doc .env (:8000)
 *   robotuth-asr      Parakeet — deploy/gpu/asr_gpu_server.py (:8002)
 *   robotuth-ocr      PP-OCRv6 — deploy/gpu/ocr_gpu_server.py (:8003)
 *   robotuth-tts      VieNeU   — deploy/gpu/tts_gpu_server.py (:8004)
 *
 * ASR/OCR/TTS truoc day la systemd unit; nay chay duoi PM2 de `pm2 restart`
 * quan ly ca cum. Chung van dung venv rieng (.venv-asr cho ASR+TTS,
 * .venv-ocr cho OCR) va bind :8002/:8003/:8004.
 *
 * Postgres KHONG nam o day: no la instance dung chung voi bookchat, chay bang
 * systemd. De DB ngoai PM2 nghia la `pm2 reload` khong bao gio dung toi no.
 *
 * ROOT suy ra tu vi tri file nay (deploy/ -> len 1 cap), nen file chay dung o
 * BAT KY dau repo duoc checkout — khong hardcode duong dan may nao.
 */
const path = require("path");

const ROOT = process.env.ROBOTUTH_ROOT || path.resolve(__dirname, "..");
const NGINX_BIN = process.env.ROBOTUTH_NGINX || "/usr/sbin/nginx";
const NGINX_PREFIX = path.join(ROOT, "data", "nginx");

const PY_SERVER = path.join(ROOT, ".venv", "bin", "python");
const PY_ASR = path.join(ROOT, ".venv-asr", "bin", "python");
const PY_OCR = path.join(ROOT, ".venv-ocr", "bin", "python");

// Chong khoa: mac dinh cua PM2 (max_restarts=16) se dua mot tien trinh fail
// lien tuc vao trang thai `errored` va NGUNG restart (do o bookchat 25/09/2026).
// exp_backoff + min_uptime giu no o `waiting restart` thay vi chet han.
const CHONG_KHOA = {
  exec_mode: "fork",
  autorestart: true,
  exp_backoff_restart_delay: 100,
  min_uptime: "2m",
  max_restarts: 1000,
};

module.exports = {
  apps: [
    {
      ...CHONG_KHOA,
      name: "robotuth-gateway",
      script: NGINX_BIN,
      interpreter: "none", // nginx la binary, khong phai script
      // -p: prefix cho pid/log/temp (deploy/nginx.conf dung duong dan tuong doi).
      args: `-p ${NGINX_PREFIX} -c ${path.join(ROOT, "deploy", "nginx.conf")}`,
    },
    {
      ...CHONG_KHOA,
      name: "robotuth-server",
      script: PY_SERVER,
      args: "-m server.main",
      cwd: ROOT,
      max_memory_restart: "1G",
    },
    {
      ...CHONG_KHOA,
      name: "robotuth-asr",
      script: PY_ASR,
      // Parakeet CTC 0.6b-vi chay CPU (GPU de danh cho OCR/TTS). Doi sang
      // Nemotron thi thay `deploy/gpu/asr_gpu_server.py` bang lenh `nemo-speech serve`
      // tuong ung — xem noibo/PROJECT.md.
      args: "deploy/gpu/asr_gpu_server.py --host 0.0.0.0 --port 8002",
      cwd: ROOT,
      env: { ASR_USE_GPU: "false", CUDA_VISIBLE_DEVICES: "" },
      // FP32 torch model: steady RSS ~2.8 GiB, nen 4G de khong bi kill khi GC.
      max_memory_restart: "4G",
    },
    {
      ...CHONG_KHOA,
      name: "robotuth-ocr",
      script: PY_OCR,
      args: "deploy/gpu/ocr_gpu_server.py --host 0.0.0.0 --port 8003 --device gpu:0",
      cwd: ROOT,
      env: {
        CUDA_VISIBLE_DEVICES: "0",
        OCR_DEVICE: "gpu:0",
        OCR_DET_MODEL: "PP-OCRv6_medium_det",
        OCR_REC_MODEL: "PP-OCRv6_medium_rec",
      },
    },
    {
      ...CHONG_KHOA,
      name: "robotuth-tts",
      script: PY_ASR, // VieNeU cai chung .venv-asr
      // CPU Turbo de chua GPU cho Parakeet ASR.
      args: "deploy/gpu/tts_gpu_server.py --host 0.0.0.0 --port 8004 --device cpu --turbo",
      cwd: ROOT,
      max_memory_restart: "2G",
    },
  ],
};
