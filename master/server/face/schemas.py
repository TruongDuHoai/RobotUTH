"""
Face Recognition Schemas
"""

from typing import List, Optional

from pydantic import BaseModel


class FaceBox(BaseModel):
    x1: int
    y1: int
    x2: int
    y2: int
    confidence: float


class FaceDetectRequest(BaseModel):
    image_base64: str


class FaceDetectResponse(BaseModel):
    success: bool
    faces: List[FaceBox]
    count: int


class FaceEnrollRequest(BaseModel):
    name: str
    image_base64: str
    user_id: Optional[str] = None


class FaceEnrollResponse(BaseModel):
    success: bool
    face_id: Optional[str] = None
    name: Optional[str] = None
    message: str


class FaceRecognizeRequest(BaseModel):
    image_base64: str


class FaceRecognizeResponse(BaseModel):
    success: bool
    recognized: bool
    name: Optional[str] = None
    face_id: Optional[str] = None
    confidence: Optional[float] = None
    message: str


class FaceInfo(BaseModel):
    id: str
    name: str
    user_id: Optional[str] = None
    created_at: Optional[str] = None


class FaceListResponse(BaseModel):
    success: bool
    faces: List[FaceInfo]
    count: int


class FaceDeleteResponse(BaseModel):
    success: bool
    message: str


class AttendanceRecord(BaseModel):
    record_id: str
    face_id: str
    name: str
    user_id: Optional[str] = None
    checkin_time: str


class AttendanceHistoryResponse(BaseModel):
    success: bool
    history: List[AttendanceRecord]
    count: int
