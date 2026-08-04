// 온프레미스 CD 파이프라인. Jenkins가 GitHub에서 develop을 당겨와 이미지를 만들고,
// 사설 레지스트리(Zot)에 올린 뒤 호스트의 온프레미스 스택을 새 이미지로 교체한다.
//
// 빌드 컨텍스트는 Jenkins 워크스페이스(방금 체크아웃한 커밋)를 쓰고,
// 배포는 호스트 리포 경로의 compose 파일·.env를 쓴다 — 비밀값은 리포가 아니라 호스트에 산다.
// 두 경로가 다른 이유: 컨테이너 안에서 `docker compose up`을 해도 바인드 마운트 경로는
// 호스트 기준으로 해석되므로, 워크스페이스 경로로 띄우면 nginx.conf 같은 파일을 찾지 못한다.
pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    environment {
        IMAGE_REGISTRY = "${env.IMAGE_REGISTRY ?: 'localhost:5000'}"
        IMAGE_TAG      = "build-${env.BUILD_NUMBER}"
        HOST_ONPREM    = "${env.HOST_REPO_PATH}/infra/onprem"
    }

    stages {
        stage('빌드 · Zot push') {
            steps {
                dir('infra/onprem') {
                    sh './build-and-push.sh'
                }
            }
        }

        stage('배포') {
            steps {
                // IMAGE_TAG를 셸 환경으로 넘기면 compose가 .env의 값보다 우선 적용한다 →
                // latest가 아니라 이번 빌드 번호로 고정 배포된다(어떤 커밋이 떠 있는지 추적 가능).
                sh '''
                    cd "$HOST_ONPREM"
                    IMAGE_TAG="$IMAGE_TAG" docker compose --env-file .env pull app next
                    IMAGE_TAG="$IMAGE_TAG" docker compose --env-file .env up -d app next
                '''
            }
        }

        stage('검증') {
            steps {
                sh '''
                    for i in $(seq 1 30); do
                      code=$(curl -s -o /dev/null -w "%{http_code}" http://nginx/api/products || echo 000)
                      [ "$code" = "200" ] && echo "api=200 (${i}0초)" && exit 0
                      sleep 10
                    done
                    echo "기동 확인 실패"; exit 1
                '''
            }
        }
    }

    post {
        success { echo "배포 완료 — 태그 ${IMAGE_TAG}" }
        failure { echo "실패 — 이전 이미지가 그대로 떠 있다. 로그 확인 후 재실행할 것" }
    }
}
