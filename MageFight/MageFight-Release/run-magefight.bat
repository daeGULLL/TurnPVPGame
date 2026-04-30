@echo off
REM MageFight 클라이언트 실행 스크립트 (Windows)
REM Java 17 이상이 필요합니다

setlocal enabledelayedexpansion

REM Java 버전 확인
java -version >nul 2>&1
if !errorlevel! neq 0 (
    echo.
    echo [ERROR] Java가 설치되어 있지 않습니다.
    echo Java 17 이상 설치 필요: https://adoptium.net/
    echo.
    pause
    exit /b 1
)

REM JAR 파일 찾기
if exist "magefight-client-1.0.0-all.jar" (
    set JAR_FILE=magefight-client-1.0.0-all.jar
) else if exist "magefight-all.jar" (
    set JAR_FILE=magefight-all.jar
) else (
    echo.
    echo [ERROR] JAR 파일을 찾을 수 없습니다.
    echo 파일명: magefight-client-1.0.0-all.jar 또는 magefight-all.jar
    echo.
    pause
    exit /b 1
)

REM JAR 파일 실행
echo.
echo MageFight를 시작합니다...
echo 서버: game.yeunsuh.online:9090
echo.

java -Dfile.encoding=UTF-8 -jar !JAR_FILE!

if !errorlevel! neq 0 (
    echo.
    echo [ERROR] 게임 시작 중 오류가 발생했습니다.
    echo.
    pause
    exit /b 1
)
