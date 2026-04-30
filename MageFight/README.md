# MageFight

MageFight는 두 개의 Maven 모듈로 나뉜 게임 클라이언트입니다.

- `magefight-content`: 아키타입, 스킬 트리, 성장 규칙, 프리셋 팩토리를 담당합니다.
- `magefight-client`: Swing 기반 런처와 전투 UI를 담당합니다.

이 분리는 게임 고유 규칙을 재사용 가능한 엔진과 분리하기 위한 것입니다. 즉, MageFight는 `turngame-engine` 위에 얹히는 콘텐츠와 표현 계층입니다.

화면의 목표는 단순한 텍스트 나열이 아니라, 로그인부터 로비, 아키타입 성장, 스킬 해금의 안개, 전투 상태까지 한 번에 읽히는 전투형 UI를 만드는 것입니다.

## 빌드

1. 먼저 `GameEngine` 프로젝트를 빌드하고, 로컬 Maven 저장소에 설치되어 있어야 합니다.
2. 가능하면 최상위 [workspace root](../pom.xml)를 열어 `GameEngine`과 `MageFight`를 함께 로드하세요.
3. 이 디렉터리에서 `mvn package`를 실행하면 `MageFight` 두 모듈이 함께 빌드됩니다.

## 실행

실행 진입점은 `magefight-client` 모듈의 `com.magefight.MageFightApp`입니다.

IntelliJ에서 열 때는 루트 `pom.xml`을 가져와서 두 모듈이 모두 인식되도록 하세요.

### 로컬 게임 (싱글플레이)

1. MageFightApp을 실행
2. 로그인 후 "Start Battle (Local)" 버튼 클릭
3. 봇(AI)과 싱글플레이 게임 시작

#### 로컬에서 바로 실행하는 방법

개발/테스트용으로는 IDE에서 `com.magefight.MageFightApp`을 직접 실행하는 방식이 가장 간단합니다.

CLI로 실행하려면 먼저 `GameEngine`과 `MageFight`를 빌드해 두고, 이 순서대로 실행하세요.

```bash
cd GameEngine
mvn clean package

cd ../MageFight
mvn -pl magefight-client -am exec:java -Dexec.mainClass=com.magefight.MageFightApp
```

이 프로젝트는 `magefight-client`가 `magefight-content`와 `GameEngine`에 의존하므로, 단일 JAR만으로는 바로 실행되지 않습니다. 배포용으로는 아래의 릴리스 패키지 방식이 필요합니다.

### 온라인 멀티플레이 (네트워크)

#### 서버 호스팅 (Cloudflare)

**1. 서버 빌드**
```bash
cd GameEngine
mvn clean package
```

**2. 서버 실행** (클라우드플레어 호스팅 머신)
```bash
# yeunsuh.online (또는 서브도메인)에서 포트 9090 listen
java -cp target/turngame-engine-1.0.0.jar com.turngame.server.ServerMain 9090 20
```

**3. Cloudflare 설정**
- DNS A 레코드 추가:
  - `game.yeunsuh.online` → 서버 IP
  - 또는 `yeunsuh.online` → 서버 IP
- Cloudflare > SSL/TLS: Full (strict) 또는 Flexible 설정
- 방화벽 규칙에서 포트 9090 허용

#### 클라이언트 연결

1. MageFightApp 실행
2. 로그인 후 "Find Online Match" 버튼 클릭
3. 로비 화면에서:
   - **Server Host**: `game.yeunsuh.online` (또는 `yeunsuh.online`)
   - **Server Port**: `9090`
   - **Nickname**: 플레이어 닉네임
   - **Character**: 아키타입 선택
4. "Find Game" 버튼 클릭
5. 다른 플레이어가 연결될 때까지 대기
6. 매칭 완료 → 게임 시작

#### 네트워크 구조

```
클라이언트1 (Player1)
    ↓ (TCP Socket)
    
GameServer (yeunsuh.online:9090)
├── ClientHandler-1: Player1 통신
├── ClientHandler-2: Player2 통신
└── GameSession: 게임 상태 관리
    ↓ (TCP Socket)
    
클라이언트2 (Player2)
```

#### 프로토콜

모든 통신은 JSON 기반입니다:

**클라이언트 → 서버**
```json
{
  "type": "ACTION",
  "requestId": "uuid",
  "payload": {
    "matchId": "match-id",
    "actionType": "ATTACK|DEFEND|USE_SKILL|MOVE|END_TURN",
    "targetId": "p-2",
    "damage": 5
  }
}
```

**서버 → 클라이언트**
```json
{
  "type": "STATE_UPDATED",
  "requestId": "uuid",
  "payload": {
    "matchId": "match-id",
    "turnPlayerId": "p-1",
    "players": [
      {"playerId": "p-1", "hp": 80, "maxHp": 100},
      {"playerId": "p-2", "hp": 95, "maxHp": 100}
    ]
  }
}
```

자세한 프로토콜은 [PROTOCOL_API.md](../GameEngine/PROTOCOL_API.md) 참고

#### 사용자 입장에서의 실행

1. ZIP 파일 다운로드 및 압축 해제
2. Java 17 이상 설치 확인
3. `run-magefight.bat` (Windows) 또는 `run-magefight.sh` (macOS/Linux) 실행
4. 로그인 → 아키타입 선택 → "Find Online Match" → 게임 시작

**서버 정보는 자동으로 `game.yeunsuh.online:9090`으로 설정됩니다.**

## 프로젝트 메모

- `magefight-content`는 아키타입, 스킬 트리, 성장, 해금 규칙의 데이터 모델을 소유합니다.
- `magefight-client`는 런처, 로비, 스킬 트리, 전투 화면을 렌더링합니다.
- 모듈 분리는 의도된 설계입니다. 엔진은 재사용 가능하게 두고, MageFight는 게임 전용 클라이언트로 독립적으로 확장할 수 있게 했습니다.


---

# English Notes

MageFight is split into two Maven modules.

- `magefight-content`: archetypes, skill trees, progression logic, and preset factories.
- `magefight-client`: Swing launcher and battle UI.

This split keeps game-specific rules separate from the reusable engine. In practice, MageFight is the content and presentation layer on top of `turngame-engine`.

The visual goal is not just to show text data, but to present progression as a readable battle-oriented UI: login, lobby, archetype evolution, skill unlock fog, and battle status.

## Build

1. Build and install the engine project first if it is not already available in your local Maven repository.
2. Prefer opening the top-level [workspace root](../pom.xml) so `GameEngine` and `MageFight` are loaded together.
3. From this directory, run `mvn package` to build both MageFight modules.

## Run

Run the client entry point from the `magefight-client` module: `com.magefight.MageFightApp`.

If you open the project in IntelliJ, import the root `pom.xml` so both modules are loaded.

### Local Play (Singleplayer vs Bot)

1. Run MageFightApp
2. Log in and click "Start Battle (Local)"
3. Play against the AI bot locally

#### Run locally from the command line

For development and testing, the simplest option is to run `com.magefight.MageFightApp` directly from your IDE.

If you want to run from the command line, build both `GameEngine` and `MageFight` first, then run:

```bash
cd GameEngine
mvn clean package

cd ../MageFight
mvn -pl magefight-client -am exec:java -Dexec.mainClass=com.magefight.MageFightApp
```

This project depends on `magefight-content` and `GameEngine`, so a single plain JAR is not enough by itself. For distribution, use a release bundle.

### Online Multiplayer

#### Server Hosting (Cloudflare)

**1. Build Server**
```bash
cd GameEngine
mvn clean package
```

**2. Run Server** (on your Cloudflare-hosted machine)
```bash
# Listen on port 9090 at yeunsuh.online (or subdomain)
java -cp target/turngame-engine-1.0.0.jar com.turngame.server.ServerMain 9090 20
```

**3. Cloudflare Configuration**
- Add DNS A record:
  - `game.yeunsuh.online` → Your server IP
  - Or `yeunsuh.online` → Your server IP
- Cloudflare > SSL/TLS: Full (strict) or Flexible
- Firewall Rules: Allow port 9090

#### Client Connection

1. Run MageFightApp
2. Log in and click "Find Online Match"
3. In the lobby screen:
   - **Server Host**: `game.yeunsuh.online` (or `yeunsuh.online`)
   - **Server Port**: `9090`
   - **Nickname**: Your player nickname
   - **Character**: Select archetype
4. Click "Find Game"
5. Wait for another player to connect
6. Match starts → Game begins

#### Network Architecture

```
Client1 (Player1)
    ↓ (TCP Socket)
    
GameServer (yeunsuh.online:9090)
├── ClientHandler-1: Player1 communication
├── ClientHandler-2: Player2 communication
└── GameSession: Game state management
    ↓ (TCP Socket)
    
Client2 (Player2)
```

#### Communication Protocol

All communication is JSON-based:

**Client → Server**
```json
{
  "type": "ACTION",
  "requestId": "uuid",
  "payload": {
    "matchId": "match-id",
    "actionType": "ATTACK|DEFEND|USE_SKILL|MOVE|END_TURN",
    "targetId": "p-2",
    "damage": 5
  }
}
```

**Server → Client**
```json
{
  "type": "STATE_UPDATED",
  "requestId": "uuid",
  "payload": {
    "matchId": "match-id",
    "turnPlayerId": "p-1",
    "players": [
      {"playerId": "p-1", "hp": 80, "maxHp": 100},
      {"playerId": "p-2", "hp": 95, "maxHp": 100}
    ]
  }
}
```

For full protocol details, see [PROTOCOL_API.md](../GameEngine/PROTOCOL_API.md)

#### End-user flow

1. Download and unzip the release
2. Confirm Java 17+ is installed
3. Run `run-magefight.bat` (Windows) or `run-magefight.sh` (macOS/Linux)
4. Log in → Select archetype → "Find Online Match" → Game starts

**The server address `game.yeunsuh.online:9090` is hardcoded and cannot be changed.**

## Project Notes

- `magefight-content` owns the data model for archetypes, skill trees, progression, and unlock rules.
- `magefight-client` renders the launcher, lobby, skill tree, and battle screen.
- The module split is intentional: the engine stays reusable, while MageFight can keep evolving as a game-specific client without mixing concerns.

## AI Usage Notes

- I planned the overall flow and game elements, then reviewed and adjusted the code generated with AI assistance.
- The concrete MageFight implementation was produced with AI support, while I handled structure and behavior review.
- This README focuses on project intent and module responsibilities without overstating personal implementation work.
