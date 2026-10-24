# Robotuth v3

> Tài liệu đầy đủ (kiến trúc, số đo, hợp đồng API, nhật ký quyết định) nằm ở
> `noibo/PROJECT.md`. README này là **bản rút gọn cho người triển khai**.

Backend FastAPI cho robot hỏi đáp thư viện/tri thức bằng tiếng Việt (chat, ASR, TTS,
điều khiển robot qua WebSocket) và ứng dụng Android (`android/`) gọi backend đó.

## API chính

| API | Việc |
|---|---|
| `POST /api/chat/query` | Nhận câu hỏi → trả câu trả lời từ tài liệu đã nạp |
| `POST /asr/ctc/chunk` | Nhận WAV → trả văn bản (Parakeet; thiết bị CHƯA XÁC MINH, xem `noibo/PROJECT.md` W-4) |
| `WS /ws/asr/{session}` | Stream PCM16 → văn bản theo từng đoạn (tùy chọn) |
| `POST /api/tts/synthesize` | Nhận văn bản → WAV |
| `POST /api/knowledge/documents` | Nạp `.pdf` / `.docx` / `.txt` vào tri thức |
| `POST /api/knowledge/qna` | Nạp một cặp hỏi–đáp |
| `GET /health` | Trạng thái các dịch vụ (JSON) |
| `GET /docs` | Swagger UI (mở qua gateway) |

## Kiến trúc triển khai

```
Robot / điện thoại / admin ──HTTP + WS──► <LAN-IP>:10010  (nginx gateway, PM2)
                                                │
                                                └──► 127.0.0.1:8000  (FastAPI, PM2)
                                                          ├─► ASR   :8002 (PM2)
                                                          ├─► OCR   :8003 (PM2, tùy chọn)
                                                          ├─► TTS   :8004 (PM2)
                                                          └─► 9router :20128 (LLM, ngoài repo)
```

- **Không Docker.** nginx, FastAPI **và ASR/OCR/TTS** đều chạy dưới **PM2**
  (`deploy/ecosystem.config.cjs`, 5 tiến trình). ASR/OCR/TTS dùng venv riêng
  (`.venv-asr` cho ASR+TTS, `.venv-ocr` cho OCR).
- **Một cửa duy nhất `10010`** (nginx). `/docs` mở được; `/static`, `/api/knowledge`,
  `/api/settings` chỉ nhận từ LAN `172.16.1.0/24`.
- **Postgres dùng chung instance với bookchat** (cùng máy, `127.0.0.1:5432`), chạy như
  service hệ thống — **không** do PM2 quản lý. Cơ sở dữ liệu của dự án: `robotuth_knowledge`,
  `robotuth_face`.
- **Redis không cần** khi `FACE_ENABLED=false` (Redis chỉ phục vụ module khuôn mặt).

## Yêu cầu

| Thành phần | Ghi chú |
|---|---|
| Python ≥ 3.10 | có sẵn `python3` |
| Node.js + PM2 | `npm i -g pm2` |
| nginx | `apt install nginx` (chỉ cần binary; không cần bật service) |
| PostgreSQL ≥ 14 + pgvector | dùng chung với bookchat; cần `psql` để kiểm tra |
| Dịch vụ AI | ASR `:8002`, OCR `:8003`, TTS `:8004` do PM2 chạy từ repo (cần venv `.venv-asr` / `.venv-ocr`); 9router `:20128` là dịch vụ ngoài |

`deploy/check_system.sh` kiểm tra **tất cả** những thứ trên và báo `[OK]` / `[THIEU]`. Nó **không tự cài** —
thiếu gì thì bạn cài cái đó.

## Script trong `deploy/`

| Script | Việc | Ghi chú |
|---|---|---|
| `deploy/deploy.sh` | **Triển khai một lệnh**: `.env` → `db.sh` → `check_system.sh` → `start_system.sh` | bình thường chỉ cần cái này |
| `deploy/db.sh` | Tạo role + database + `CREATE EXTENSION vector` + bảng | `--chi-xem` để chỉ kiểm; lần đầu cần `sudo` |
| `deploy/check_system.sh` | Kiểm môi trường (Python, PM2, nginx, psql, `.env`, DB, cổng) | chỉ đọc, không thay đổi gì |
| `deploy/start_system.sh` | `pm2 startOrReload` + `pm2 save` + chờ `/health` | chỉ khởi động cụm, không tạo DB |

Cả bốn đều **idempotent** và **không tự cài** gì — thiếu thì báo `[THIEU]` rồi dừng.
Đặt trong `deploy/` (cùng chỗ `ecosystem.config.cjs` và `nginx.conf`) cho giống bookchat.

## Cài đặt

### Bước 1 — Cấu hình `.env`

```bash
cp .env.example .env
# Sửa .env: ROBOT_API_TOKENS (BẮT BUỘC khác rỗng), POSTGRES_PASSWORD, OPENAI_API_KEY.
# Không commit .env — file này chứa khóa thật.
```

> ⚠ `ROBOT_API_TOKENS` rỗng ⇒ auth **FAIL-OPEN**: mọi API gọi được mà không cần token.
> Sinh token: `openssl rand -base64 48`.

### Bước 2 — Tạo database

```bash
sudo bash deploy/db.sh        # lần đầu trên máy mới: tạo role + database + extension + bảng
bash deploy/db.sh --chi-xem   # các lần sau: chỉ kiểm tra hiện trạng, không ghi gì
```

`db.sh` làm phần cần quyền **superuser** (tạo role, `CREATE DATABASE`,
`CREATE EXTENSION vector`) rồi để Python tạo bảng bằng chính role ứng dụng.
Idempotent — chạy lại không hỏng. Không tự cài PostgreSQL; thiếu `psql` hoặc
postgres không chạy thì báo `[THIEU]` rồi dừng.

> ⚠ Vì sao cần bước này: `init_knowledge_db.py` / `init_face_db.py` tự chạy
> `CREATE EXTENSION vector`, mà pgvector **không phải trusted extension** — role
> ứng dụng sẽ bị `permission denied to create extension vector`, lặp lại mỗi lượt chat.
> `db.sh` tách đúng phần superuser ra trước.

### Bước 3 — Kiểm tra hệ thống

```bash
bash deploy/check_system.sh
```

Chỉ đọc, không thay đổi gì. Sửa hết mục `[THIEU]` rồi chạy lại.

### Bước 4 — Chạy toàn bộ

```bash
bash deploy/deploy.sh
```

`deploy/deploy.sh` lần lượt: `.env` → `db.sh` (tạo database nếu thiếu) → `check_system.sh` →
`start_system.sh` (`pm2 startOrReload deploy/ecosystem.config.cjs` → `pm2 save` → chờ `/health`).
Idempotent — chạy lại được.

Chỉ muốn khởi động lại cụm khi DB và môi trường đã sẵn sàng: `bash deploy/start_system.sh`.

Xong thì mở:

```
http://<LAN-IP>:10010/docs     # Swagger
http://<LAN-IP>:10010/health   # trạng thái
```

### Cài đặt thủ công (nếu không dùng `deploy/deploy.sh`)

```bash
python3 -m venv .venv
.venv/bin/pip install -e ".[face]"   # core + nhận diện khuôn mặt (CPU)

# Tạo role + 2 database + extension (chạy một lần, cần superuser):
sudo bash deploy/db.sh

mkdir -p data/nginx
pm2 start deploy/ecosystem.config.cjs
pm2 save
pm2 startup      # rồi chạy lệnh mà nó in ra, để tự chạy khi boot
```

## Cập nhật bản mới

```bash
git pull --ff-only
bash deploy/deploy.sh         # db.sh + check_system.sh + start_system.sh (pm2 + chờ /health)
```

> ⚠ **Không dùng `pm2 restart all`** — nó khởi động lại cả nginx. Restart đúng cách:
> `pm2 restart robotuth-server`. Postgres nằm ngoài PM2 nên không bị ảnh hưởng.

## Ứng dụng Android

```bash
cd android
# android/local.properties (không commit) phải có:
#   sdk.dir=<đường dẫn Android SDK>
#   ROBOT_API_TOKEN=<đúng giá trị trong ROBOT_API_TOKENS của server>
./gradlew assembleDebug
```

App mặc định trỏ `http://172.16.1.15:10010/` (và WS `ws://172.16.1.15:10010/ws`).
Máy đã cài bản cũ (địa chỉ Tailscale/funnel) sẽ tự chuyển sang LAN một lần khi mở app.

Hai cờ tính năng mặc định tắt, bật khi build:
`-PROBOT_STREAMING_ASR=true -PROBOT_WAKE_WORD=true`.

> ⚠ Token trên server và trong APK phải khớp. Lệch nhau thì mọi lệnh robot trả
> `401 {"detail":"Robot token required"}` và không có thông báo nào chỉ ra bên nào sai.
> Đổi token thì phải build lại APK.

## Vận hành

| Việc | Lệnh / nơi xem |
|---|---|
| Trạng thái | `curl -s http://127.0.0.1:10010/health` |
| Danh sách tiến trình | `pm2 list` |
| Log server | `pm2 logs robotuth-server` |
| Log gateway | `pm2 logs robotuth-gateway` |
| Khởi động lại server | `pm2 restart robotuth-server` |
| Tắt / bật nhận diện khuôn mặt | đặt `FACE_ENABLED` trong `.env`, rồi `pm2 restart robotuth-server` |
| Log ASR / OCR / TTS | `pm2 logs robotuth-asr` · `pm2 logs robotuth-ocr` · `pm2 logs robotuth-tts` |
| Web quản trị | `http://<LAN-IP>:10010/static/index.html` (chỉ từ LAN) |

Cổng: gateway `10010` (công khai trong LAN) → server `127.0.0.1:8000` (nội bộ).

## Kiểm thử & lint

```bash
python3 -m venv .venv
.venv/bin/pip install -e ".[dev]"
.venv/bin/ruff check .        # lint — cấu hình trong pyproject.toml
.venv/bin/pytest -q           # test — testpaths = tests/
```

CI: `.gitlab-ci.yml` chạy đúng hai lệnh trên (job `ruff` + `pytest`). Cần GitLab
có **runner** (Docker executor); chưa có runner thì chạy tay. Không cần DB và
không cần GPU — app import `insightface`/`torch`/`nemo` kiểu lazy và test tự
monkeypatch kết nối DB.

## Dịch vụ GPU & công cụ thí nghiệm

`deploy/gpu/` chứa **11 file cần khi chạy thật** (PM2 trỏ thẳng vào):

| File | Vì sao |
|---|---|
| `asr_gpu_server.py`, `ocr_gpu_server.py`, `tts_gpu_server.py` | 3 tiến trình PM2 (`:8002` / `:8003` / `:8004`) |
| `asr_postprocess.py`, `asr_dictionary.json` | import/nạp lúc chạy |
| `asr_pipeline/` (`spelling.py`, `audio_preprocess.py`, `pipeline.py`, `logger.py`, `__init__.py`, `data/vi_wordlist.txt`) | lớp QA sửa chính tả ASR (`asr_gpu_server.py:352`) |

Phụ thuộc của các venv dịch vụ nằm trong `pyproject.toml` (extras — không còn `requirements*.txt`):

| Extra | Venv | Gồm | Cài riêng (cần index-url đặc biệt) |
|---|---|---|---|
| `.[face]` | `.venv` (server) | insightface, onnxruntime (CPU) | máy GPU: `pip install onnxruntime-gpu` thay onnxruntime |
| `.[speech]` | `.venv-asr` | soundfile, vieneu, peft, transformers, accelerate, huggingface-hub | torch, `nemo_toolkit[asr]`, llama-cpp-python |
| `.[ocr]` | `.venv-ocr` | pypdfium2, paddleocr | paddlepaddle-gpu |
| `.[dev]` | `.venv` (dev/CI) | pytest, ruff | — |

```bash
# .venv-asr — ASR (Parakeet) + TTS (VieNeu)
# Cần ~10 GB trống. Nếu /tmp nhỏ, trỏ TMPDIR + pip cache sang ổ còn trống TRƯỚC khi cài:
#   export TMPDIR=/srv/tmp PIP_CACHE_DIR=/srv/pip-cache
python3 -m venv .venv-asr
.venv-asr/bin/pip install -e ".[speech]"
.venv-asr/bin/pip install torch torchaudio --index-url https://download.pytorch.org/whl/cu128   # Blackwell (sm_120) cần CUDA >= 12.8
.venv-asr/bin/pip install "nemo_toolkit[asr]>=1.21.0"
# TTS turbo dùng backbone GGUF qua llama-cpp-python — ưu tiên wheel dựng sẵn (build từ source rất nặng):
.venv-asr/bin/pip install "llama-cpp-python>=0.3.16" --extra-index-url https://abetlen.github.io/llama-cpp-python/whl/cpu/

# .venv-ocr — OCR cho PDF scan
python3 -m venv .venv-ocr
.venv-ocr/bin/pip install -e ".[ocr]"
.venv-ocr/bin/pip install paddlepaddle-gpu==3.0.0 -i https://www.paddlepaddle.org.cn/packages/stable/cu126/
```

Công cụ thí nghiệm (eval/bench/probe, test operator, proxy dev, script setup cũ — 22 file)
**không có trong repo**. Trên máy dev chúng nằm ở `noibo/thi-nghiem/` (`/noibo/` đã gitignore,
nên không cần liệt kê từng file trong `.gitignore`).

Chạy tay (đã sửa để tự tìm repo root — chạy được từ bất kỳ cwd, không cần `PYTHONPATH`):

```bash
python noibo/thi-nghiem/selftest.py                 # self-test pipeline (không cần model)
python noibo/thi-nghiem/evaluate.py --dry-run       # đánh giá ASR, không cần model
python noibo/thi-nghiem/virtual_robot.py --selftest # robot giả, kiểm giao thức WS
```

## Giới hạn đã biết

- **CI cần runner**: `.gitlab-ci.yml` chỉ thực sự chạy khi GitLab có runner.
- **Auth fail-open**: `ROBOT_API_TOKENS` rỗng ⇒ mọi API gated mở (xem cảnh báo ở Bước 1).
- `/api/knowledge/*` **ghi** và `/api/settings` **ghi** nay yêu cầu token ở tầng ứng dụng
  (`server/middleware/robot_api_auth.py`) *kèm* giới hạn LAN ở gateway. Các endpoint admin
  khác (`/api/tts/*` cấu hình, `/api/conversation/*`) vẫn chỉ dựa vào gateway.
- ASR/OCR/TTS bind `0.0.0.0` (chưa siết về `127.0.0.1`) — chỉ nên mở trong LAN.
- Chưa có type-checking (mypy).
- **Đĩa của server nhỏ**: cài `.venv-asr` (torch + nemo_toolkit) cần ~10 GB. Lỗi
  `No space left on device` khi cài thường do `/tmp`/`/var/tmp` đầy (gcc/CMake ghi tạm) —
  `export TMPDIR=<ổ còn trống>` + `pip cache purge` trước khi cài. `deploy/check_system.sh`
  mục "Dung luong dia" cảnh báo sớm. Deploy thường (`deploy/deploy.sh`) không cài gì.
- Chi tiết, số đo và lịch sử: `noibo/PROJECT.md`.
