"""
Tiền xử lý audio cho pipeline ASR tiếng Việt.

Mục tiêu đảm bảo chất lượng (QA):
  - Chuẩn hóa tần số lấy mẫu về 16 kHz (Parakeet CTC yêu cầu đầu vào 16k mono).
  - Downmix về mono.
  - Lọc nhiễu: high-pass one-pole (loại bỏ DC/ò oằn tần số thấp) + noise gate
    dựa trên RMS. Nếu có thư viện `noisereduce` thì dùng spectral subtraction.
  - Cắt bỏ khoảng lặng đầu/cuối (trim) để mô hình CTC không sinh token rác.

Thiết kế:
  - Chỉ import thư viện nặng (numpy / soundfile / librosa / noisereduce) khi thực sự
    cần, nên module vẫn import được trong môi trường chỉ có stdlib (phục vụ test
    đơn vị và fallback đọc WAV bằng module `wave`).
"""
from __future__ import annotations

import logging
import math
import wave
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional, Tuple

logger = logging.getLogger("asr_pipeline.audio")

TARGET_SR = 16000


@dataclass
class AudioMeta:
    orig_sr: Optional[int] = None
    channels: int = 1
    resampled: bool = False
    mono: bool = False
    denoised: bool = False
    trimmed: bool = False
    duration_seconds: float = 0.0
    rms_before: float = 0.0
    rms_after: float = 0.0
    method: str = "wave-stdlib"
    note: str = ""


# --------------------------------------------------------------------------- #
# Đọc WAV (hỗ trợ PCM 8/16/24/32-bit và float 32-bit) bằng stdlib `wave`       #
# Trả về list[float] trong khoảng [-1.0, 1.0] và sample rate.                  #
# --------------------------------------------------------------------------- #
def _read_wav_stdlib(path: Path) -> Tuple[List[float], int, int]:
    with wave.open(str(path), "rb") as wf:
        sr = wf.getframerate()
        channels = wf.getnchannels()
        width = wf.getsampwidth()
        n_frames = wf.getnframes()
        raw = wf.readframes(n_frames)
    if width == 1:  # 8-bit unsigned
        fmt = "<" + "B" * n_frames * channels
        samples = [s - 128 for s in struct_unpack(fmt, raw)]
        scale = 128.0
    elif width == 2:  # 16-bit signed
        fmt = "<" + "h" * n_frames * channels
        samples = list(struct_unpack(fmt, raw))
        scale = 32768.0
    elif width == 3:  # 24-bit signed -> đọc thủ công
        samples = []
        scale = 8388608.0
        for i in range(0, len(raw) - 2, 3):
            val = raw[i] | (raw[i + 1] << 8) | (raw[i + 2] << 16)
            if val & 0x800000:
                val -= 0x1000000
            samples.append(val)
    elif width == 4:  # 32-bit
        if _is_float_wav(path):
            fmt = "<" + "f" * n_frames * channels
            samples = list(struct_unpack(fmt, raw))
            scale = 1.0
        else:
            fmt = "<" + "i" * n_frames * channels
            samples = list(struct_unpack(fmt, raw))
            scale = 2147483648.0
    else:
        raise ValueError(f"Unsupported sample width {width} byte")

    samples = [s / scale for s in samples]
    return samples, sr, channels


def _is_float_wav(path: Path) -> bool:
    # WAVEFORMATEXTENSIBLE / float được đánh dấu qua `wave` không chuẩn, đoán theo
    # tên file hoặc dùng thư viện nặng nếu có. Ở đây mặc định 32-bit PCM.
    return path.suffix.lower() in (".f32",)


def _read_wav_soundfile(path: Path):
    try:
        import soundfile as sf  # type: ignore
    except Exception:
        return None
    data, sr = sf.read(str(path), dtype="float32", always_2d=True)
    channels = data.shape[1]
    samples = data[:, 0].tolist() if channels == 1 else data.flatten().tolist()
    return samples, sr, channels


def read_audio(path: Path) -> Tuple[List[float], int, int]:
    """Đọc audio thành list[float]. Thử soundfile trước, fallback sang wave."""
    path = Path(path)
    res = _read_wav_soundfile(path)
    if res is not None:
        return res
    return _read_wav_stdlib(path)


# --------------------------------------------------------------------------- #
# Các bước xử lý (pure python, test được)                                      #
# --------------------------------------------------------------------------- #
def to_mono(samples: List[float], channels: int) -> List[float]:
    if channels == 1:
        return samples
    out = []
    for i in range(0, len(samples) - channels + 1, channels):
        out.append(sum(samples[i : i + channels]) / channels)
    return out


def resample_linear(samples: List[float], orig_sr: int, target_sr: int) -> List[float]:
    if orig_sr == target_sr or not samples:
        return samples
    scale = target_sr / orig_sr
    n = max(1, int(round(len(samples) * scale)))
    out: List[float] = []
    for i in range(n):
        pos = i / scale
        i0 = int(math.floor(pos))
        frac = pos - i0
        i1 = min(i0 + 1, len(samples) - 1)
        out.append(samples[i0] * (1 - frac) + samples[i1] * frac)
    return out


def highpass(samples: List[float], sr: int, cutoff: float = 80.0) -> List[float]:
    """One-pole DC blocker (high-pass) loại bỏ méo DC và tiếng ù tần số thấp."""
    if cutoff <= 0:
        return samples
    rc = 1.0 / (2.0 * math.pi * cutoff)
    dt = 1.0 / sr
    a = rc / (rc + dt)
    prev_in = 0.0
    prev_out = 0.0
    out: List[float] = []
    for v in samples:
        cur = a * (prev_out + v - prev_in)
        out.append(cur)
        prev_in = v
        prev_out = cur
    return out


def _frame_rms(samples: List[float], frame_len: int) -> List[float]:
    out: List[float] = []
    for i in range(0, len(samples), frame_len):
        chunk = samples[i : i + frame_len]
        if not chunk:
            continue
        ms = math.sqrt(sum(v * v for v in chunk) / len(chunk))
        out.append(ms)
    return out


def trim_silence(
    samples: List[float],
    sr: int,
    frame_ms: float = 20.0,
    threshold: float = 0.02,
) -> Tuple[List[float], bool]:
    """Cắt bỏ khoảng lặng đầu/cuối dựa trên RMS frame so với max RMS."""
    if not samples:
        return samples, False
    frame_len = max(1, int(sr * frame_ms / 1000.0))
    rms = _frame_rms(samples, frame_len)
    if not rms:
        return samples, False
    peak = max(rms)
    if peak <= 0:
        return samples, False
    lim = threshold * peak
    start = 0
    end = len(rms) - 1
    while start < end and rms[start] < lim:
        start += 1
    while end > start and rms[end] < lim:
        end -= 1
    s = max(0, start * frame_len)
    e = min(len(samples), (end + 1) * frame_len)
    if s == 0 and e == len(samples):
        return samples, False
    return samples[s:e], True


def denoise_spectral(samples: List[float], sr: int) -> Tuple[List[float], bool]:
    """Nếu có `noisereduce` thì dùng spectral subtraction, ngược lại trả nguyên gốc."""
    try:
        import noisereduce  # type: ignore
        import numpy as np  # type: ignore
    except Exception:
        return samples, False
    arr = np.fromiter(samples, dtype=np.float32)
    reduced = noisereduce.reduce_noise(y=arr, sr=sr, stationary=False)
    return reduced.astype(float).tolist(), True


# --------------------------------------------------------------------------- #
# Hàm tổng hợp                                                                  #
# --------------------------------------------------------------------------- #
def preprocess(
    path,
    target_sr: int = TARGET_SR,
    denoise: bool = True,
    trim: bool = True,
    cutoff_hz: float = 80.0,
) -> Tuple[List[float], int, AudioMeta]:
    """
    Đọc + chuẩn hóa audio thành (samples_float, sr, meta).
    Luôn trả về mảng mono, sample rate = target_sr, đã lọc DC và cắt lặng.
    """
    p = Path(path)
    samples, sr, channels = read_audio(p)
    meta = AudioMeta(orig_sr=sr, channels=channels, method="wave-stdlib")
    if channels > 1:
        samples = to_mono(samples, channels)
        meta.mono = True

    if sr != target_sr:
        samples = resample_linear(samples, sr, target_sr)
        meta.resampled = True
        sr = target_sr

    meta.rms_before = round(math.sqrt(sum(v * v for v in samples[: min(len(samples), target_sr)]) / min(len(samples), target_sr)), 5) if samples else 0.0

    samples = highpass(samples, sr, cutoff=cutoff_hz)

    if denoise:
        samples, ok = denoise_spectral(samples, sr)
        if ok:
            meta.denoised = True
            meta.method = "soundfile+noisereduce"

    if trim:
        samples, ok = trim_silence(samples, sr)
        meta.trimmed = ok

    meta.duration_seconds = round(len(samples) / sr, 3) if sr else 0.0
    meta.rms_after = round(math.sqrt(sum(v * v for v in samples[: min(len(samples), target_sr)]) / min(len(samples), target_sr)), 5) if samples else 0.0
    return samples, sr, meta


def write_wav(path, samples: List[float], sr: int = TARGET_SR) -> None:
    """Ghi list[float] thành WAV 16-bit mono (dùng khi gửi lên backend HTTP)."""
    p = Path(path)
    raw = bytearray()
    for v in samples:
        iv = int(max(-1.0, min(1.0, v)) * 32767)
        raw += struct_pack("<h", iv)
    with wave.open(str(p), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sr)
        wf.writeframes(bytes(raw))


# lazy import của struct helpers để module import nhanh
import struct  # noqa: E402


def struct_unpack(fmt: str, raw: bytes):
    return struct.unpack(fmt, raw)


def struct_pack(fmt: str, *args):
    return struct.pack(fmt, *args)
