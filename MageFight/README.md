# MageFight

MageFight는 Turn Game Engine 위에서 동작하는 전투형 게임 클라이언트입니다.
프로젝트의 핵심 목표는 엔진과 게임 콘텐츠/UI를 분리해 재사용성과 확장성을 확보하는 것입니다.

## 모듈 구성

- `GameEngine`
  - 턴 처리, 액션 검증, 상태 동기화, 매치메이킹, 재접속 처리, 리플레이 기록 담당
- `MageFight`
  - `magefight-content`: 아키타입, 스킬 트리, 성장/해금 규칙
  - `magefight-client`: Swing 기반 런처, 로비, 전투 UI

## 전투 처리 방식 (현재 구현 기준)

- 전투는 윈도우(window) 기반 동시 행동 처리 방식입니다.
- 각 플레이어는 윈도우 동안 행동을 큐에 쌓고, `END_TURN`으로 확정합니다.
- 양쪽이 준비되면 서버가 큐를 정산하고 결과를 상태 이벤트로 전파합니다.
- 클라이언트는 `resolutionSteps`를 받아 단계별 전투 연출을 재생합니다.

즉, 실시간 즉시 반영형이 아니라 정산형 턴제 구조입니다.

## 빌드

1. 먼저 `GameEngine`을 빌드해 로컬 Maven 저장소에 설치합니다.
2. 가능하면 최상위 `pom.xml`(workspace root)에서 두 프로젝트를 함께 로드합니다.
3. `MageFight` 디렉터리에서 `mvn package`를 실행합니다.

권장 순서:

```bash
cd GameEngine
mvn clean package

cd ../MageFight
mvn clean package
```

## 실행

- 클라이언트 진입점: `com.magefight.MageFightApp`
- IDE에서는 루트 `pom.xml` 기준으로 모듈을 함께 열어 실행하는 방식을 권장합니다.

### 로컬 게임 (싱글플레이)

1. MageFightApp 실행
2. 로그인 후 "Start Battle (Local)" 클릭
3. 봇과 전투 시작

## 온라인 멀티플레이 (현재 네트워크 구조)

기존 단순 TCP 소켓 1:1 모델 설명은 현재 코드와 다릅니다.
현재는 HTTP Relay 중심 + 이벤트 채널 구조입니다.

### 서버

- 서버 진입점: `com.turngame.server.HttpRelayServerMain`
- 주요 API 컨텍스트:
  - `/api/join`
  - `/api/action`
  - `/api/events`
  - `/api/events/stream`
  - `/api/disconnect`
  - `/api/resume`
- 상태 확인: `/health`
- WebSocket 서버는 HTTP 포트 + 1 포트에서 동작

### 클라이언트 연결/진행 흐름

1. 로비에서 `Find Game` 요청
2. 매칭 수신 (`MATCHED`)
3. 전투 시작 신호 및 상태 동기화 (`MATCH_STARTED`, `STATE_UPDATED`)
4. 플레이어 액션은 HTTP `/api/action`으로 전송
5. 이벤트는 WebSocket 우선 수신, 필요 시 즉시 동기화 요청

### 이벤트 전달과 동기화

- 서버 이벤트는 시퀀스(`_eventSeq`) 기반으로 관리됩니다.
- 클라이언트는 마지막 적용 시퀀스를 기준으로 중복을 제거합니다.
- WebSocket 재연결 시 누락 이벤트를 재동기화할 수 있도록 설계되어 있습니다.
- 이벤트 수신 경로는 WebSocket + HTTP 이벤트 조회/스트림을 함께 고려한 구조입니다.

## 이동/스킬/에너지 규칙 (요약)

`BasicRuleSet` 기준으로 다음이 적용됩니다.

- 이동(MOVE)
  - 맵 경계, 통과 가능 타일, 생존 여부 검증
  - 같은 윈도우에서 큐에 쌓인 이전 이동을 반영한 "예상 위치" 기준 검증
  - 한 번의 이동 가능 거리(`moveRange`)와 윈도우 에너지 제한을 함께 적용
- 스킬(USE_SKILL)
  - 스킬 템플릿/사거리/조준 좌표/성공 판정 기반 처리
  - 성공/실패에 따른 에너지 비용 및 피해 반영
- 공격/방어/턴종료
  - 인접 판정, 방어 상태, 에너지 소모, 턴 종료 정산 규칙 반영

## 기권/종료/연결 끊김 처리

### 기권 (`SURRENDER`)

- 클라이언트가 `SURRENDER` 액션을 전송하면 서버가 `GAME_ENDED` 이벤트를 생성합니다.
- 종료 payload에는 `winnerId`, `reason`, `surrenderedPlayerId`가 포함됩니다.
- 서버는 매치 참가자에게 종료 이벤트를 전송하고 매치를 정리합니다.

### 재접속 유예

- 플레이어 연결이 끊기면 상대에게 `PLAYER_DISCONNECTED` 이벤트를 전달하고,
  재접속 유예 시간(`reconnectDeadlineEpochMs`)을 부여합니다.
- 유예 내 복귀 시 `PLAYER_RECONNECTED`/`MATCH_RESUMED` 흐름으로 진행합니다.
- 유예 내 미복귀 시 상대 승 처리(`PLAYER_ABANDONED`)로 종료됩니다.

### 로비 복귀/이어하기

- 클라이언트는 재개 가능한 매치(`canResume`, `resumableMatchId`)를 감지하고
  로비에서 `Resume Game` 흐름을 제공할 수 있습니다.

## UI 동작 원칙 (온라인 전투)

- 온라인 상태는 서버 `STATE_UPDATED`를 기준으로 동기화합니다.
- 정산 연출은 `resolutionSteps`가 새 윈도우로 갱신되었을 때 재생됩니다.
- 재접속 대기 중에는 "Waiting..." 다이얼로그와 남은 시간 표시가 노출됩니다.

## Cloudflare 및 운영 메모

- 기본 서버 호스트는 `game.yeunsuh.online` 기준으로 구성되어 있습니다.
- 클라이언트 기본 포트는 환경/시스템 속성으로 주입 가능합니다.
- Cloudflare 프록시/포트 정책에 따라 직접 포트 접근이 제한될 수 있으므로,
  DNS/SSL/방화벽 구성을 실제 운영 환경에 맞게 설정해야 합니다.

## 배포

배포는 fat JAR + 실행 스크립트 번들을 권장합니다.

예시:

```text
MageFight-Release/
├── magefight-client-1.0.0-all.jar
├── run-magefight.bat
├── run-magefight.sh
├── QUICKSTART.txt
└── README.txt (optional)
```

## 프로젝트 메모

- `magefight-content`는 게임 데이터/성장 규칙의 소유자입니다.
- `magefight-client`는 런처/로비/전투 UI와 온라인 동기화 표현 계층입니다.
- 엔진은 재사용 가능하도록 유지하고, MageFight는 게임 전용 클라이언트로 확장합니다.

---

# English Notes

MageFight is a battle-oriented game client built on top of the reusable turn-based engine.
The main design goal is clear separation between engine logic and game-specific content/UI.

## Module Structure

- `GameEngine`
  - turn windows, action validation, synchronization, matchmaking, reconnect handling, replay recording
- `MageFight`
  - `magefight-content`: archetypes, skill trees, progression and unlock rules
  - `magefight-client`: Swing launcher, lobby, battle UI

## Current Online Architecture

The implementation is no longer a simple direct TCP client model.
It is an HTTP relay-centered architecture with event channels.

Server endpoints include:

- `/api/join`
- `/api/action`
- `/api/events`
- `/api/events/stream`
- `/api/disconnect`
- `/api/resume`
- `/health`

WebSocket is used for real-time event delivery (server port + 1), while actions are sent through HTTP for deterministic request/response handling.

## Match Flow

1. Client requests matchmaking (`Find Game`)
2. Match signals arrive (`MATCHED`)
3. Battle state sync begins (`MATCH_STARTED`, `STATE_UPDATED`)
4. Players submit actions via `/api/action`
5. Server resolves queued actions per window and sends updated state

## Sync and Event Ordering

- Events are sequence-based (`_eventSeq`).
- Client deduplicates by last applied sequence.
- Reconnect paths include missed-event synchronization.

## Surrender / Disconnect / Resume

- `SURRENDER` produces `GAME_ENDED` with `winnerId`, `reason`, `surrenderedPlayerId`.
- On disconnect, server broadcasts `PLAYER_DISCONNECTED` with reconnect deadline.
- If reconnection succeeds in time, match resumes (`PLAYER_RECONNECTED` / `MATCH_RESUMED`).
- If not, the match ends with abandonment reason.

## Build and Run

```bash
cd GameEngine
mvn clean package

cd ../MageFight
mvn clean package
```

Client entry point: `com.magefight.MageFightApp`.

## Distribution

Recommended delivery is a release bundle containing a fat JAR plus launch scripts.

## AI Usage Notes

- The project structure and gameplay flow were planned first.
- AI-assisted code was reviewed and adjusted to match desired behavior and architecture.
