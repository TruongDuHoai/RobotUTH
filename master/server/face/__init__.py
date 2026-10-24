"""
Face Recognition Module
"""

from .cache import FaceCache, get_face_cache
from .schemas import (
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
)
from .service import FaceInsightService, get_face_service
from .store import FaceStore, get_face_store

__all__ = [
    "get_face_service",
    "FaceInsightService",
    "get_face_store",
    "FaceStore",
    "get_face_cache",
    "FaceCache",
    "FaceBox",
    "FaceDetectRequest",
    "FaceDetectResponse",
    "FaceEnrollRequest",
    "FaceEnrollResponse",
    "FaceRecognizeRequest",
    "FaceRecognizeResponse",
    "FaceInfo",
    "FaceListResponse",
    "FaceDeleteResponse",
    "AttendanceRecord",
    "AttendanceHistoryResponse",
]
