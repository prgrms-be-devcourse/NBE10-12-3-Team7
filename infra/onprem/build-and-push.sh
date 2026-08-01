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

echo "▶ 레지스트리 확인: $REG"
curl -sf "http://$REG/v2/" >/dev/null || { echo "✗ $REG 에 접속 불가. zot이 떠 있는지 확인"; exit 1; }

echo "▶ app 이미지 빌드"
docker build -t "$REG/dongnemarket-app:$TAG" ../../backend
echo "▶ next 이미지 빌드"
docker build -t "$REG/dongnemarket-next:$TAG" ../../frontend

echo "▶ push"
docker push "$REG/dongnemarket-app:$TAG"
docker push "$REG/dongnemarket-next:$TAG"

echo "▶ 카탈로그"
curl -s "http://$REG/v2/_catalog"
echo
