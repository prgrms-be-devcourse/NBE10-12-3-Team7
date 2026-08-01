# 온프레미스 CD용 Jenkins. 공식 이미지에 docker CLI를 얹어 호스트 도커 데몬(docker.sock)을 조작한다.
# 클라우드의 GitHub Actions 대응 — 다만 "GitHub이 우리 호스트에서 코드를 실행"하는 게 아니라
# "우리 호스트가 GitHub에서 코드를 당겨온다"는 점이 다르다(public 리포 + self-hosted runner 위험 회피).
FROM jenkins/jenkins:lts-jdk21

# docker CLI + compose 플러그인. 데몬은 설치하지 않는다 — 호스트 데몬을 socket으로 쓴다(DooD).
USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl gnupg \
 && install -m 0755 -d /etc/apt/keyrings \
 && curl -fsSL https://download.docker.com/linux/debian/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg \
 && chmod a+r /etc/apt/keyrings/docker.gpg \
 && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
      > /etc/apt/sources.list.d/docker.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends docker-ce-cli docker-compose-plugin \
 && rm -rf /var/lib/apt/lists/*

# 플러그인을 이미지에 굽는다 — 웹 UI 설치 마법사를 거치지 않고 매번 같은 상태로 뜨게 한다.
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

# 설정 마법사 비활성 + JCasC로 초기 설정 주입. 힙은 맥 자원 경쟁을 감안해 768m로 제한.
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false -Xmx768m"
ENV CASC_JENKINS_CONFIG=/var/jenkins_home/casc.yaml

# root 유지: /var/run/docker.sock 은 호스트에서 root 소유라 jenkins 유저로는 열 수 없다.
# 이 컨테이너가 사실상 호스트 root 권한을 갖는다는 뜻이다(../onprem-modernization.md 보안 항목 참고).
USER root
