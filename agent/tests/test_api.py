"""FastAPI 스모크 테스트 — 라이브 모델 없이 통과해야 한다."""

from fastapi.testclient import TestClient

from agent.api import app

client = TestClient(app)


def test_health_returns_ok():
    # when: 헬스체크를 호출하면
    response = client.get("/health")

    # then: 200과 ok 상태를 준다
    assert response.status_code == 200
    assert response.json()["data"]["status"] == "ok"


def test_legal_ask_emergency_envelope():
    # when: 긴급 신호 질문을 보내면 (규칙컷 → NIM 없이)
    response = client.post("/agent/legal/ask", json={"question": "지금 위협받고 있어요"})

    # then: {success, data} 봉투로 112 안내·inScope=False를 돌려준다
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["data"]["inScope"] is False
    assert body["data"]["emergency"] is True
    assert "112" in body["data"]["answer"]
    assert body["data"]["sources"] == []
