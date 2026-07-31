"""FastAPI 얇은 입구. 거래 법률 도우미 그래프를 HTTP로 노출한다.

응답은 백엔드 관례(ApiResponse)와 결을 맞춰 {success, data} 봉투로 감싼다.
"""

from fastapi import FastAPI
from pydantic import BaseModel

from agent.graph import graph

app = FastAPI(title="MarketON AI Agent", version="0.1.0")


class AskRequest(BaseModel):
    question: str
    conversationId: str | None = None


class Source(BaseModel):
    title: str
    snippet: str


class AskData(BaseModel):
    answer: str
    sources: list[Source] = []
    inScope: bool
    grounded: bool
    emergency: bool


class ApiResponse(BaseModel):
    success: bool = True
    data: AskData


@app.get("/health")
def health() -> dict:
    """헬스체크 — 서비스 기동 확인용."""
    return {"success": True, "data": {"status": "ok"}}


@app.post("/agent/legal/ask", response_model=ApiResponse)
def legal_ask(request: AskRequest) -> ApiResponse:
    """거래 법률 도우미 그래프를 한 번 실행해 답변·출처를 반환한다."""
    result = graph.invoke({"question": request.question})
    data = AskData(
        answer=result.get("answer", ""),
        sources=result.get("sources", []),
        inScope=bool(result.get("in_scope", False)),
        grounded=bool(result.get("grounded", False)),
        emergency=bool(result.get("emergency", False)),
    )
    return ApiResponse(data=data)
