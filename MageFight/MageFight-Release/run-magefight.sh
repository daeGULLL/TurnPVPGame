#!/bin/bash

# MageFight 클라이언트 실행 스크립트 (macOS/Linux)
# Java 17 이상이 필요합니다

# Java 버전 확인
if ! command -v java &> /dev/null; then
    echo ""
    echo "[ERROR] Java가 설치되어 있지 않습니다."
    echo "Java 17 이상 설치 필요: https://adoptium.net/"
    echo ""
    exit 1
fi

# JAR 파일 찾기
if [ -f "magefight-client-1.0.0-all.jar" ]; then
    JAR_FILE="magefight-client-1.0.0-all.jar"
elif [ -f "magefight-all.jar" ]; then
    JAR_FILE="magefight-all.jar"
else
    echo ""
    echo "[ERROR] JAR 파일을 찾을 수 없습니다."
    echo "파일명: magefight-client-1.0.0-all.jar 또는 magefight-all.jar"
    echo ""
    exit 1
fi

# JAR 파일 실행
echo ""
echo "MageFight를 시작합니다..."
echo "서버: game.yeunsuh.online:9090"
echo ""

java -Dfile.encoding=UTF-8 -jar "$JAR_FILE"

if [ $? -ne 0 ]; then
    echo ""
    echo "[ERROR] 게임 시작 중 오류가 발생했습니다."
    echo ""
    exit 1
fi
