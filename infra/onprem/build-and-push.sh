#!/usr/bin/env bash
# app/next 이미지를 빌드해 사설 레지스트리(Zot)에 push한다. 클라우드의 "빌드 → ECR push"에 대응.
# 실행 전 zot이 떠 있어야 한다:  docker compose --env-file .env up -d zot
#
# gradle 빌드는 backend/Dockerfile 안에서(멀티스테이지 temurin:21-jdk) 수행되므로
# 호스트에 JDK가 없어도 된다 — 별도 ./gradlew 선행 빌드는 필요 없다.
set -euo pipefail
cd "$(dirname "$0")"

REG="${IMAGE_REGISTRY:-localhost:5000}"
TAG="${IMAGE_TAG:-latest}"

# $REG(태그에 박히는 주소)는 "호스트 도커 데몬이 보는" 주소다 — push/pull을 실제로 수행하는 건
# CLI가 아니라 데몬이기 때문이다. 반면 아래 사전 확인 curl은 이 스크립트가 도는 자리에서 나간다.
# 호스트에서 실행하면 둘이 같지만, Jenkins 컨테이너 안에서 실행하면 localhost가 서로 다르므로
# REGISTRY_PROBE로 컨테이너 네트워크상의 주소(zot:5000)를 따로 준다.
PROBE="${REGISTRY_PROBE:-http://$REG/v2/}"
echo "▶ 레지스트리 확인: $PROBE (태그 주소: $REG)"
curl -sf "$PROBE" >/dev/null || { echo "✗ $PROBE 에 접속 불가. zot이 떠 있는지 확인"; exit 1; }

echo "▶ app 이미지 빌드"
docker build -t "$REG/dongnemarket-app:$TAG" ../../backend
echo "▶ next 이미지 빌드"
docker build -t "$REG/dongnemarket-next:$TAG" ../../frontend

echo "▶ push"
docker push "$REG/dongnemarket-app:$TAG"
docker push "$REG/dongnemarket-next:$TAG"

echo "▶ 카탈로그"
curl -s "${PROBE%/v2/}/v2/_catalog"
echo
