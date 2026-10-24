"""
Attendance API - Face Recognition Endpoints
"""

import logging

from fastapi import APIRouter, HTTPException

from ..config import FACE_ENABLED, FACE_MATCH_THRESHOLD, FACE_TOP_K
from ..face import (
    AttendanceHistoryResponse,
    AttendanceRecord,
    FaceBox,
    FaceDeleteResponse,
    FaceDetectRequest,
    FaceDetectResponse,
    FaceEnrollRequest,
    FaceEnrollResponse,
    FaceInfo,
    FaceListResponse,
    FaceRecognizeRequest,
    FaceRecognizeResponse,
    get_face_cache,
    get_face_service,
    get_face_store,
)

router = APIRouter(prefix="/api/attendance", tags=["attendance"])
logger = logging.getLogger(__name__)


def _ensure_face_enabled() -> None:
    """Face recognition is off by default (FACE_ENABLED=false, 2026-10-05).

    Every face endpoint fails closed with 503 while disabled; the module stays
    importable and the code path intact so re-enabling is one env var.
    """
    if not FACE_ENABLED:
        raise HTTPException(503, "Face recognition is disabled")


@router.post("/face/detect", response_model=FaceDetectResponse)
async def detect(req: FaceDetectRequest):
    _ensure_face_enabled()
    svc = get_face_service()
    try:
        faces = svc.detect(req.image_base64)
        boxes = [
            FaceBox(x1=f["x1"], y1=f["y1"], x2=f["x2"], y2=f["y2"], confidence=f["confidence"])
            for f in faces
        ]
        return FaceDetectResponse(success=True, faces=boxes, count=len(boxes))
    except Exception as e:
        logger.exception("detect error")
        raise HTTPException(500, str(e))


@router.post("/face/enroll", response_model=FaceEnrollResponse)
async def enroll(req: FaceEnrollRequest):
    _ensure_face_enabled()
    svc = get_face_service()
    try:
        faces = svc.detect(req.image_base64)
        if not faces:
            raise HTTPException(400, "Không phát hiện khuôn mặt")
        if len(faces) > 1:
            raise HTTPException(400, "Nhiều khuôn mặt — chỉ dùng 1 khuôn mặt")

        emb = faces[0].get("embedding")
        if not emb:
            raise HTTPException(500, "Không lấy được embedding")

        store = get_face_store()
        face_id = store.enroll(req.name, emb, req.user_id)
        get_face_cache().cache_person(
            face_id,
            {"id": face_id, "name": req.name, "user_id": req.user_id},
            3600,
        )
        return FaceEnrollResponse(success=True, face_id=face_id, name=req.name, message=f"Đã lưu '{req.name}'")
    except HTTPException:
        raise
    except Exception as e:
        logger.exception("enroll error")
        raise HTTPException(500, str(e))


@router.post("/face/recognize", response_model=FaceRecognizeResponse)
async def recognize(req: FaceRecognizeRequest):
    _ensure_face_enabled()
    svc = get_face_service()
    try:
        faces = svc.detect(req.image_base64)
        if not faces:
            return _not_recognized("Không phát hiện khuôn mặt")

        emb = faces[0].get("embedding")
        if not emb:
            return _not_recognized("Không lấy được embedding")

        cache = get_face_cache()
        cached = cache.get_recognition(emb)
        if cached:
            get_face_store().log_attendance(cached["id"])
            return _recognized(cached, "Nhận ra từ cache")

        result = get_face_store().recognize(emb, FACE_TOP_K, FACE_MATCH_THRESHOLD)
        if not result:
            return _not_recognized("Không nhận ra")

        cache.set_recognition(emb, result, 3)
        get_face_store().log_attendance(result["id"])
        return _recognized(result, f"Nhận ra: {result['name']}")
    except Exception as e:
        logger.exception("recognize error")
        raise HTTPException(500, str(e))


@router.get("/face/history", response_model=AttendanceHistoryResponse)
async def get_history(limit: int = 100):
    _ensure_face_enabled()
    try:
        store = get_face_store()
        history = store.get_attendance_history(limit=limit)
        return AttendanceHistoryResponse(
            success=True,
            history=[
                AttendanceRecord(
                    record_id=r["record_id"],
                    face_id=r["face_id"],
                    name=r["name"],
                    user_id=r.get("user_id"),
                    checkin_time=r["checkin_time"],
                )
                for r in history
            ],
            count=len(history),
        )
    except Exception as e:
        logger.exception("history error")
        raise HTTPException(500, str(e))


@router.get("/face/list", response_model=FaceListResponse)
async def list_faces():
    _ensure_face_enabled()
    try:
        faces = get_face_store().list_all()
        items = [
            FaceInfo(id=f["id"], name=f["name"], user_id=f.get("user_id"), created_at=f.get("created_at"))
            for f in faces
        ]
        return FaceListResponse(success=True, faces=items, count=len(items))
    except Exception as e:
        logger.exception("list error")
        raise HTTPException(500, str(e))


@router.delete("/face/{face_id}", response_model=FaceDeleteResponse)
async def delete_face(face_id: str):
    _ensure_face_enabled()
    try:
        ok = get_face_store().delete(face_id)
        if not ok:
            raise HTTPException(404, f"Không tìm thấy face_id={face_id}")
        get_face_cache().delete_person(face_id)
        return FaceDeleteResponse(success=True, message=f"Đã xóa {face_id}")
    except HTTPException:
        raise
    except Exception as e:
        logger.exception("delete error")
        raise HTTPException(500, str(e))


def _recognized(result: dict, message: str) -> FaceRecognizeResponse:
    return FaceRecognizeResponse(
        success=True,
        recognized=True,
        name=result["name"],
        face_id=result["id"],
        confidence=result.get("confidence"),
        message=message,
    )


def _not_recognized(message: str) -> FaceRecognizeResponse:
    return FaceRecognizeResponse(success=True, recognized=False, message=message)
