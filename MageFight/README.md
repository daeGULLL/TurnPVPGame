# MageFight

MageFight는 재사용 가능한 턴제 전투 엔진(`turngame-engine`) 위에 얹힌 **콘텐츠 + 표현 계층**입니다. 두 개의 Maven 모듈로 나뉩니다.

- `magefight-content`: 아키타입, 스킬 트리, 성장/해금 규칙, 프리셋 팩토리 등 **게임 고유 데이터 모델**.
- `magefight-client`: Swing 기반 **런처와 전투 UI**, 그리고 서버와 통신하는 네트워크 클라이언트.

이 분리는 게임 고유 규칙을 엔진(`GameEngine` = `turngame-engine`)과 떼어 두기 위한 의도된 설계입니다. 엔진은 도메인/규칙/네트워크 서버를 제공하고, MageFight는 그 위에서 콘텐츠와 화면을 담당합니다.

화면의 목표는 단순한 텍스트 나열이 아니라, 로그인부터 로비, 아키타입 성장, 스킬 해금, 전투 정산까지 한 번에 읽히는 전투형 UI를 만드는 것입니다.

> 모듈 분리 근거와 설계 원칙은 [DESIGN-NOTE.md](DESIGN-NOTE.md), 엔진/프로토콜 상세는 [GameEngine/PROTOCOL_API.md](../GameEngine/PROTOCOL_API.md)도 함께 참고하세요.

---

## 워크스페이스 구조

```
workspace/                       (root pom: workspace-root)
├── GameEngine/                  artifact: turngame-engine  (Java 17)
│   └── com.turngame
│       ├── domain/              핵심 도메인 모델
│       │   ├── character/       GameCharacter (스탯/보너스)
│       │   ├── skill/           SkillTemplate, SkillEffect, SkillDependency, SkillCounter
│       │   ├── map/             BattleMap, MapCellPosition
│       │   ├── defense/         DefenseState, EvadeWindow
│       │   ├── enums/           ActionType, CharacterType
│       │   ├── PlayerState      HP/에너지/방어 등 플레이어 상태
│       │   └── PlayerSkillMastery
│       ├── engine/              게임 진행 코어
│       │   ├── GameSession      상태 머신 (행동 큐 → 정산 → 승패)
│       │   ├── TurnManager      윈도우/턴 순서/ready 관리
│       │   ├── command/         GameAction: Attack/Defend/UseSkill/Move/EndTurn
│       │   └── rules/           RuleSet, BasicRuleSet, SkillResolver, DamageResolver
│       ├── event/               EventBus + Turn/Action/GameEnded 이벤트
│       ├── factory/             character/skill/map 팩토리 (+Provider)
│       ├── replay/              ReplayRecorder (이벤트 기록)
│       └── server/              네트워크 릴레이 서버
│           ├── HttpRelayServerMain   서버 진입점 (main)
│           ├── HttpRelayServer       REST + 이벤트 큐 + 매칭
│           ├── GameWebSocketEndpoint WebSocket(/events)
│           ├── account/AccountStore  계정/캐릭터 프로필
│           └── protocol/             Request/ResponseMessage
│
└── MageFight/                   parent: magefight-parent
    ├── magefight-content/       artifact: magefight-content
    │   └── com.magefight.content
    │       ├── factory/         GamePresetFactory (스펙/스킬트리/맵 생성)
    │       ├── model/           FighterSpec, MageArchetype, MageSkillTree, SkillTreeNode…
    │       └── progress/        MageProgress, 성장/해금/승급 서비스
    └── magefight-client/        artifact: magefight-client (shade → -all.jar)
        └── com.magefight
            ├── MageFightApp           클라이언트 진입점
            └── ui/
                ├── MageFightLauncher       로그인·계정·아키타입·캐릭터 커스터마이즈
                ├── MageFightFrame          전투 프레임 (BattleViewPanel.Host)
                ├── LobbyPanel              매칭 로비 (연결/Find Game)
                ├── BattleViewPanel         전투 보드 렌더링
                ├── SkillTreePanel          스킬트리 시각화/습득
                ├── GameNetworkClient       WS/SSE/Polling 네트워크 추상화
                └── OnlineStateSyncService  서버 상태 → 로컬 GameSession 스냅샷
```

### 모듈 의존 방향

```
magefight-client  ─▶  magefight-content  ─▶  turngame-engine
        └───────────────────────────────────▶  turngame-engine
```

엔진은 어느 것에도 의존하지 않습니다. 콘텐츠는 엔진 도메인만 사용하고, 클라이언트는 둘 다 사용합니다.

---

## 핵심 설계

### 턴/윈도우 + 동시 정산

- 한 "윈도우(window)" 동안 각 플레이어는 **여러 행동을 큐에 쌓을** 수 있습니다(이동·공격·스킬·방어). 이때 화면은 즉시 바뀌지 않습니다.
- 양쪽이 모두 `END_TURN`을 보내면, 서버가 큐에 쌓인 행동을 **단계별(step)로 동시에 정산**합니다.
- 각 정산 단계는 `ResolutionStep`(단계별 before/after HP·위치)으로 기록되고, 클라이언트는 이를 받아 **차근차근 연출**로 재생합니다.
- 정산 후 에너지가 리셋되고 다음 윈도우로 넘어갑니다(`TurnManager.nextTurn()`).

### 자원/이동 규칙

- 각 행동은 에너지를 소비하며, **윈도우당 사용 가능한 에너지 상한**(`maxEnergySpendPerWindow`, 아키타입별)이 행동 횟수를 제한합니다.
- 이동은 `moveRange`(기본 1칸)로 **1회 이동 거리**가 제한되고, **이동 횟수는 에너지**가 제한합니다. 충돌 시 양쪽이 밀려납니다.
- 공격은 인접(직교) 시에만 적중하고, 스킬은 `SkillEffect`의 범위(반경/패턴)와 성공 확률로 판정됩니다.

### 스킬

- `SkillTemplate`: 데미지/쿨다운/성공확률/에너지비용/시전시간/범위효과 + **스킬 간 상호작용**(`dependencies` 버프, `counters` 무효화/감소).
- 쿨다운은 윈도우마다 감소하고, 숙련도(`PlayerSkillMastery`)로 성공률·시전시간이 변합니다.

### 성장/해금 (magefight-content)

- `MageProgress`가 승수·선택 아키타입·스킬 숙련도·영감(inspiration) 포인트를 보관합니다.
- 스킬트리(`MageSkillTree`)에서 영감을 소비해 노드를 **습득**하고, 조건을 만족하면 상위 아키타입으로 **승급**합니다.
- 온라인 매칭 시 클라이언트는 `GamePresetFactory.createPlayerSpec(아키타입, progress)`로 만든 **전체 스킬셋과 캐릭터 외형**을 서버로 전송합니다.

---

## 네트워크 아키텍처

> 과거 문서의 "TCP Socket / ClientHandler" 설명은 더 이상 유효하지 않습니다. 실제 구조는 **HTTP 릴레이 서버 + 이벤트 큐**이며, 클라이언트는 여러 전송 방식을 폴백 체인으로 사용합니다.

```
   클라이언트 A (magefight-client)        클라이언트 B
        │  REST(액션/조인)  ▲ 이벤트            │
        ▼                  │                    ▼
 ┌───────────────────────────────────────────────────┐
 │  HttpRelayServer  (turngame-engine, 기본 :9090)     │
 │   REST:  /api/join  /api/action  /api/disconnect    │
 │          /api/events(long-poll)  /api/events/stream(SSE)  /health │
 │   WebSocket: GameWebSocketEndpoint  /events  (:9091 = port+1)     │
 │   내부: 플레이어별 이벤트 큐(seq), 매칭, 정산(GameSession)         │
 └───────────────────────────────────────────────────┘
```

- **서버 → 클라이언트 이벤트 전송**: 플레이어별 **이벤트 큐**(단조 증가 seq)에 쌓고, 연결된 전송 수단으로 내보냅니다.
- **클라이언트 수신 전송 폴백**(`GameNetworkClient.connectionMode`): `0 = WebSocket`, `1 = SSE`, `2 = long-poll`. 어떤 경로가 막혀도 가만히 있는 쪽이 이벤트(상대 행동/정산/매치 종료)를 놓치지 않도록 설계되어 있습니다.
- **액션 전송**은 결정적 ack/에러 처리를 위해 항상 HTTP `POST /api/action`을 사용합니다.

### 주요 메시지 타입

**클라이언트 → 서버** (`RequestMessage`): `JOIN` / `FIND_GAME`, `ACTION`(actionType: `ATTACK|DEFEND|USE_SKILL|MOVE|END_TURN`, 또는 `SURRENDER`), `RESUME`, `DISCONNECT`, `PING`.

**서버 → 클라이언트** (`ResponseMessage`): `JOINED`, `MATCHED`, `MATCH_STARTED`, `STATE_UPDATED`, `GAME_ENDED`(reason: `HP_ZERO|PLAYER_SURRENDERED|PLAYER_ABANDONED`), `PLAYER_DISCONNECTED` / `PLAYER_RECONNECTED` / `MATCH_RESUMED`, `ERROR`.

`STATE_UPDATED.payload`는 `matchId`, `turnPlayerId`, `windowIndex`, `players[]`(hp/energy/position/skills/cooldown 등), `resolvedWindowIndex`, `resolutionSteps[]`(정산 연출) 등을 포함합니다. 전체 스키마는 [PROTOCOL_API.md](../GameEngine/PROTOCOL_API.md) 참고.

---

## 빌드

1. 최상위 [workspace root](../pom.xml)를 열면 `GameEngine`과 `MageFight`가 함께 로드됩니다. (IntelliJ는 루트 `pom.xml`을 import)
2. `GameEngine`이 먼저 빌드되어 로컬 Maven 저장소에 설치되어 있어야 합니다.
3. `MageFight` 디렉터리에서 `mvn package`를 실행하면 두 모듈이 함께 빌드되고, `magefight-client`에서 실행용 fat JAR(`magefight-client-1.0.0-all.jar`)이 만들어집니다.

> Java 17 기준입니다.

## 실행

클라이언트 진입점은 `com.magefight.MageFightApp`, 서버 진입점은 `com.turngame.server.HttpRelayServerMain`입니다.

### 로컬 게임 (싱글플레이 vs 봇)

1. `MageFightApp` 실행
2. 로그인 후 "Start Battle (Local)" 클릭
3. 봇(AI)과 싱글플레이 시작

개발/테스트용으로는 IDE에서 `com.magefight.MageFightApp`을 직접 실행하는 방식이 가장 간단합니다. CLI로 실행하려면:

```bash
cd GameEngine
mvn clean package

cd ../MageFight
mvn -pl magefight-client -am exec:java -Dexec.mainClass=com.magefight.MageFightApp
```

`magefight-client`는 `magefight-content`와 `GameEngine`에 의존하므로 단일 plain JAR만으로는 실행되지 않습니다. 배포에는 아래 릴리스 번들을 사용하세요.

### 온라인 멀티플레이

**1. 서버 빌드 & 실행** (호스팅 머신)
```bash
cd GameEngine
mvn clean package

# 인자: <httpPort> <턴 타임아웃 초>. HTTP=9090, WebSocket=9091(port+1) 사용
java -cp target/turngame-engine-1.0.0.jar com.turngame.server.HttpRelayServerMain 9090 20
```

**2. Cloudflare 설정**
- DNS A 레코드: `game.yeunsuh.online`(또는 `yeunsuh.online`) → 서버 IP
- SSL/TLS: Full (strict) 또는 Flexible
- 방화벽에서 9090(+ WebSocket용 9091) 허용
- 주의: Cloudflare 프록시(오렌지 구름)가 켜진 상태에서는 커스텀 포트 9090이 차단될 수 있습니다. 게임 서브도메인을 **DNS only(회색 구름)**로 두거나, Cloudflare 지원 포트로 변경하세요. 클라이언트는 `:9090` 직결 실패 시 자동으로 `https://game.yeunsuh.online` 경로로 폴백합니다.

**3. 클라이언트 연결**
1. `MageFightApp` 실행 → 로그인 → 아키타입/캐릭터 설정
2. "Find Online Match" → 로비에서 호스트/포트/닉네임 확인
3. "Find Game" → 상대 매칭 대기 → 매칭되면 전투 시작

---

## 배포 (다른 사람에게 전달)

JAR 하나만 보내는 방식은 외부 의존성 때문에 권장하지 않습니다. fat JAR + 실행 스크립트를 ZIP으로 묶어 전달하세요.

```text
MageFight-Release/
├── magefight-client-1.0.0-all.jar
├── run-magefight.bat        (Windows)
├── run-magefight.sh         (macOS/Linux)
└── QUICKSTART.txt
```

```bash
# 1) fat JAR 빌드
cd MageFight
mvn clean package          # → magefight-client/target/magefight-client-1.0.0-all.jar

# 2) 배포 폴더 구성 후 ZIP (PowerShell)
Compress-Archive -Path MageFight-Release -DestinationPath MageFight-v1.0.0.zip
```

사용자는 ZIP을 풀고 Java 17+ 설치 확인 후 스크립트만 실행하면 됩니다. 서버 주소는 기본값으로 `game.yeunsuh.online:9090`에 연결됩니다.

---

## 프로젝트 메모

- `turngame-engine`(GameEngine): 도메인 모델 + 규칙 엔진 + HTTP/WebSocket 릴레이 서버. 어디에도 의존하지 않는 재사용 코어.
- `magefight-content`: 아키타입·스킬트리·성장·해금의 데이터 모델.
- `magefight-client`: 런처·로비·스킬트리·전투 화면 + 네트워크 클라이언트.
- 모듈 분리는 의도된 설계입니다. 엔진은 재사용 가능하게 두고, MageFight는 게임 전용 클라이언트로 독립적으로 확장합니다.

---

# English Notes

MageFight is the **content + presentation layer** on top of a reusable turn-based battle engine (`turngame-engine`, in the `GameEngine` project). It is split into two Maven modules:

- `magefight-content`: archetypes, skill trees, progression/unlock rules, preset factories (game-specific **data model**).
- `magefight-client`: the Swing launcher & battle UI plus the network client.

The split keeps game-specific rules separate from the reusable engine. The engine owns the domain, rules, and relay server; MageFight owns content and screens.

### Project layout

Three artifacts under one workspace root:
`turngame-engine` (GameEngine) ← `magefight-content` ← `magefight-client`. The engine depends on nothing; content uses only engine domain types; the client uses both. See the Korean "워크스페이스 구조" tree above for package-level detail, and [DESIGN-NOTE.md](DESIGN-NOTE.md) for the rationale.

### Core design

- **Turn windows + simultaneous resolution**: during a window each player *queues* multiple actions (move/attack/skill/defend) — the board does **not** change yet. When both send `END_TURN`, the server resolves the queued actions **step by step, simultaneously**, recording each `ResolutionStep` (before/after HP & positions). The client replays these as an animation. Energy resets and the next window begins.
- **Resources/movement**: each action costs energy; a per-window energy cap (per archetype) limits how many actions you take. `moveRange` (default 1) limits a *single* move's distance, while energy limits the *number* of moves. Attacks need orthogonal adjacency; skills are range- and probability-checked.
- **Skills**: `SkillTemplate` carries damage/cooldown/success/cost/cast-time/area plus cross-skill `dependencies` (buffs) and `counters` (negation/reduction). Cooldowns tick per window; mastery affects success and cast time.
- **Progression** (magefight-content): `MageProgress` tracks wins, archetype, skill mastery, and inspiration; you learn skill-tree nodes and promote archetypes. On matchmaking the client sends the full computed spec (skills + appearance) to the server.

### Network architecture

The earlier "TCP Socket / ClientHandler" description is **obsolete**. The real design is an **HTTP relay server with a per-player event queue**:

- Server: `HttpRelayServer` (default port 9090) exposes REST endpoints `/api/join`, `/api/action`, `/api/disconnect`, plus event delivery via `/api/events` (long-poll) and `/api/events/stream` (SSE), plus a WebSocket endpoint `GameWebSocketEndpoint` at `/events` on port+1 (9091), and `/health`.
- The server pushes per-player events (monotonic `seq`) to whichever transport is connected.
- Client receive-transport fallback (`GameNetworkClient.connectionMode`): `0 = WebSocket`, `1 = SSE`, `2 = long-poll`, so an idle player never misses opponent/resolution/end-of-game events. Actions are always sent via HTTP `POST /api/action` for deterministic acks.

**Message types** — Client→Server: `JOIN`/`FIND_GAME`, `ACTION` (`ATTACK|DEFEND|USE_SKILL|MOVE|END_TURN`, or `SURRENDER`), `RESUME`, `DISCONNECT`, `PING`. Server→Client: `JOINED`, `MATCHED`, `MATCH_STARTED`, `STATE_UPDATED`, `GAME_ENDED` (`HP_ZERO|PLAYER_SURRENDERED|PLAYER_ABANDONED`), `PLAYER_DISCONNECTED`/`PLAYER_RECONNECTED`/`MATCH_RESUMED`, `ERROR`. Full schema in [PROTOCOL_API.md](../GameEngine/PROTOCOL_API.md).

### Build & run

- Java 17. Open the root `pom.xml` so both projects load. Build `GameEngine` first, then `mvn package` in `MageFight` (produces `magefight-client-1.0.0-all.jar` via the shade plugin).
- Client entry: `com.magefight.MageFightApp`. Server entry: `com.turngame.server.HttpRelayServerMain <httpPort> <turnTimeoutSec>` (HTTP on the given port, WebSocket on port+1).
- Local vs bot: run the app and click "Start Battle (Local)". Online: run the server, then in the client click "Find Online Match" → "Find Game".

### Distribution

Ship a fat JAR + launch scripts in a ZIP (a bare JAR won't run due to dependencies):

```text
MageFight-Release/
├── magefight-client-1.0.0-all.jar
├── run-magefight.bat / run-magefight.sh
└── QUICKSTART.txt
```

The default server address is `game.yeunsuh.online:9090`.

## AI Usage Notes

- I planned the overall flow and game elements, then reviewed and adjusted AI-assisted code.
- The concrete MageFight implementation was produced with AI support; I handled structure and behavior review.
- This README focuses on project intent, structure, and module responsibilities.
