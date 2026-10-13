"""Require a fresh empty frame sequence and stable identity for each visit."""

from __future__ import annotations

from typing import Any

from .recognition import RecognitionResult


class PresenceGate:
    """State machine separating empty, occupied and unreadable frames.

    Startup/reconnect and a completed visit must see genuine consecutive empty
    frames. Unknown faces, poor images, multi-face frames and network errors do
    not release a visit. A different person replacing someone without a blank
    interval cannot trigger another visit either.
    """

    def __init__(self, stable_frames: int = 3, empty_frames: int = 3) -> None:
        if type(stable_frames) is not int or type(empty_frames) is not int:
            raise ValueError("Frame counts must be integers")
        if stable_frames < 1 or empty_frames < 1:
            raise ValueError("Frame counts must be positive")
        self.stable_frames = stable_frames
        self.empty_frames = empty_frames
        self.reset()

    @property
    def armed(self) -> bool:
        return self._armed

    def reset(self) -> None:
        """Call on camera disconnect, unreadable/stale frame or poll restart."""
        self._armed = False
        self._empty_count = 0
        self._candidate: tuple[str, int] | None = None
        self._stable_count = 0
        self._lowest_similarity: float | None = None

    def observe(self, result: RecognitionResult, *, fresh: bool = True) -> dict[str, Any] | None:
        if not fresh or result.reason in {"invalid_image", "error"}:
            self.reset()
            return None
        truly_empty = result.face_count == 0 and result.reason == "no_face"
        if truly_empty:
            self._candidate = None
            self._stable_count = 0
            self._lowest_similarity = None
            self._empty_count += 1
            if self._empty_count >= self.empty_frames:
                self._armed = True
            return None
        self._empty_count = 0
        if not self._armed:
            return None
        if not result.matched or result.face_count != 1:
            self._candidate = None
            self._stable_count = 0
            self._lowest_similarity = None
            return None
        if result.profile_version is None or result.similarity is None:
            self._candidate = None
            self._stable_count = 0
            self._lowest_similarity = None
            return None
        candidate = (result.employee_id, result.profile_version)
        if candidate == self._candidate:
            self._stable_count += 1
            self._lowest_similarity = min(self._lowest_similarity, result.similarity)
        else:
            self._candidate = candidate
            self._stable_count = 1
            self._lowest_similarity = result.similarity
        if self._stable_count < self.stable_frames:
            return None
        event = {
            "employeeId": result.employee_id,
            "profileVersion": result.profile_version,
            "faceSimilarity": self._lowest_similarity,
        }
        # Latch before sending. A caller must retry this SAME event if delivery
        # fails; resetting the gate here would create a new event for the visit.
        self.reset()
        return event
