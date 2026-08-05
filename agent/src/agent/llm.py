"""생성 LLM 클라이언트 — NVIDIA NIM (OpenAI 호환).

런타임 답변 생성용. 포폴/학습 목적이라 로컬 qwen3:4b 대신 NIM을 쓴다
(질문·근거가 클라우드로 감 — 의식적 수용). 임베딩·검색은 로컬 bge-m3 유지.
base_url/model/key만 바꾸면 다른 OpenAI 호환 프로바이더로 교체 가능.
"""

from __future__ import annotations

from langsmith.wrappers import wrap_openai
from openai import OpenAI

from agent.config import settings

# wrap_openai: LANGSMITH_TRACING=true면 이 클라이언트의 NIM 호출이 그래프 트레이스에 nested로 잡힌다.
# 트레이싱이 꺼져 있으면 no-op으로 그냥 통과한다.
_client = wrap_openai(OpenAI(base_url=settings.nim_base_url, api_key=settings.nim_api_key))


def generate(system: str, user: str) -> str:
    """system+user 메시지로 NIM에 생성을 요청하고 답변 텍스트를 반환한다."""
    resp = _client.chat.completions.create(
        model=settings.nim_model,
        temperature=settings.agent_temperature,
        max_tokens=settings.agent_num_predict,
        messages=[
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
    )
    return (resp.choices[0].message.content or "").strip()
