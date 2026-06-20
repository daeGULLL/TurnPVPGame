# 최소 프로토콜 및 엔진 API

## 메시지 타입

와이어 포맷은 `RequestMessage(type, requestId, payload)`와 `ResponseMessage(type, requestId, payload)`를 사용하며, `payload`는 문자열 키 기반 맵입니다.

### 전송 계층

- **클라 → 서버 액션**: HTTP `POST /api/action` (결정적 ack/에러 처리).
- **서버 → 클라 이벤트**: 플레이어별 이벤트 큐(단조 증가 `seq`)에 쌓고, 연결된 전송 수단으로 전달.
  - WebSocket `GameWebSocketEndpoint` (`/events`, port+1)
  - SSE `GET /api/events/stream`
  - 롱폴링 `GET /api/events?after=<seq>`
- 기타 REST: `POST /api/join`, `POST /api/disconnect`, `POST /api/resume`, `GET /health`.
- 클라이언트는 `connectionMode` 0(WS)→1(SSE)→2(롱폴) 폴백 체인으로 수신해, 본인이 행동하지 않는 동안에도 이벤트를 놓치지 않습니다.

### Client -> Server

- `ACCOUNT_CREATE`
  - `accountId`, `password`, `nickname`
- `ACCOUNT_LOGIN`
  - `accountId`, `password`
- `JOIN` / `FIND_GAME`
  - `accountId` optional, `nickname`, `characterType`
  - 매칭용 추가 필드(선택): `skills`(전체 스킬셋), `skinColorHex`, `outfitColorHex`, `turnEnergyCap`
- `ACTION`
  - `matchId`
  - `actionType` one of `ATTACK`, `DEFEND`, `USE_SKILL`, `MOVE`, `END_TURN`, `SURRENDER`
  - `targetId`, `damage` for `ATTACK`
  - `evadeSkillName`, `evadeStartTimeMs` for `DEFEND`
  - `targetId`, `skillName`, (`targetCol`, `targetRow`) for `USE_SKILL`
  - `targetCol`, `targetRow` for `MOVE`
- `RESUME`
  - `playerId`, `matchId` (끊긴 매치 재접속)
- `DISCONNECT`
  - `playerId`
- `PING`

### Server -> Client

- `ACCOUNT_CREATED`
  - `accountId`, `nickname`
- `ACCOUNT_LOGGED_IN`
  - `accountId`, `nickname`, `progress`
- `JOINED`
  - `playerId`, `nickname`, `accountId`, `characterType`, `skinColorHex`, `outfitColorHex`
  - 재접속 가능 시: `canResume`, `resumableMatchId`, `reconnectDeadlineEpochMs`
  - 이전 매치가 오프라인 중 종료된 경우: `lastGameResult`
- `MATCHED`
  - `matchId`, `playerId` (정식 매치 시작 직전 알림)
- `MATCH_STARTED`
  - `matchId`, `playerId`, `players`, `map`, `character`, `skills`, `firstTurnPlayerId`
- `STATE_UPDATED`
  - `matchId`, `turnPlayerId`, `windowIndex`, `windowDurationSeconds`, `readyPlayers`, `map`, `mapRows`, `mapCols`
  - `resolvedWindowIndex`, `resolutionSteps`(정산 연출: 단계별 before/after HP·위치)
  - `players[]`: 각 항목에 `hp/maxHp`, `energy/maxEnergy/energySpentInWindow`, `mapCol/mapRow`, `skills`, `skillCooldowns`, `skinColorHex/outfitColorHex` 등
- `GAME_ENDED`
  - `matchId`, `winnerId`, `reason`(`HP_ZERO` | `PLAYER_SURRENDERED` | `PLAYER_ABANDONED`), (`surrenderedPlayerId`)
- `PLAYER_DISCONNECTED` / `PLAYER_RECONNECTED` / `MATCH_RESUMED`
  - `matchId`, `playerId`, (`reconnectDeadlineEpochMs`)
- `ERROR`
  - `code`, `message`

## 엔진 API 범위

재사용 가능한 엔진 표면은 의도적으로 작게 유지합니다.

- `GameSession`
  - `addPlayer(...)`
  - `registerSkill(SkillTemplate)`
  - `submitAction(GameAction)`
  - `getCurrentPlayerId()`
  - `getCurrentWindowIndex()`
  - `getWindowDurationSeconds()`
  - `isPlayerReady(playerId)`
  - `getReadyPlayers()`
  - `consumeWindowAdvancedFlag()`
  - `getMatchId()`, `isFinished()`, `getWinnerId()`
  - `getPlayerState(playerId)`, `getPlayerPosition(playerId)`
  - `isInsideMap(col, row)`, `isCellOccupied(col, row, exceptPlayerId)`
- `PlayerState`
  - HP, 에너지, 방어, 이동 규칙, 스킬 숙련도, 창 단위 에너지 사용량을 추적합니다.
- `BattleMap`
  - 맵 생성과 렌더링을 위한 rows/cols/layout의 불변 정의입니다.
- `SkillTemplate`
  - 서버, 클라이언트, 콘텐츠 모듈이 공유하는 불변 스킬 정의입니다.
- `GameAction`
  - `AttackAction`, `DefendAction`, `UseSkillAction`, `MoveAction`, `EndTurnAction`

## 호환성 메모

- `JOINED`는 현재 기존 클라이언트가 사용하는 join acknowledgement 타입입니다.
- `ACCOUNT_*` 메시지는 계정이 없는 흐름에서는 선택 사항이지만, MageFight 런처에서는 사용합니다.
- `GameSession`은 여전히 콘텐츠 비의존적입니다. 게임 전용 아키타입과 스킬 트리는 엔진 밖에 둡니다.

---

# English Notes

## Minimal Protocol And Engine API

The wire format uses `RequestMessage(type, requestId, payload)` and `ResponseMessage(type, requestId, payload)` where `payload` is a string-keyed map.

### Transport

- **Client → server actions**: HTTP `POST /api/action` (deterministic ack/error).
- **Server → client events**: queued per player (monotonic `seq`) and delivered over whichever transport is connected:
  - WebSocket `GameWebSocketEndpoint` (`/events`, port+1)
  - SSE `GET /api/events/stream`
  - long-poll `GET /api/events?after=<seq>`
- Other REST: `POST /api/join`, `POST /api/disconnect`, `POST /api/resume`, `GET /health`.
- The client uses a `connectionMode` fallback chain 0 (WS) → 1 (SSE) → 2 (long-poll) so an idle player never misses events.

### Client -> Server

- `ACCOUNT_CREATE`
  - `accountId`, `password`, `nickname`
- `ACCOUNT_LOGIN`
  - `accountId`, `password`
- `JOIN` / `FIND_GAME`
  - `accountId` optional, `nickname`, `characterType`
  - optional matchmaking fields: `skills` (full skill set), `skinColorHex`, `outfitColorHex`, `turnEnergyCap`
- `ACTION`
  - `matchId`
  - `actionType` one of `ATTACK`, `DEFEND`, `USE_SKILL`, `MOVE`, `END_TURN`, `SURRENDER`
  - `targetId`, `damage` for `ATTACK`
  - `evadeSkillName`, `evadeStartTimeMs` for `DEFEND`
  - `targetId`, `skillName`, (`targetCol`, `targetRow`) for `USE_SKILL`
  - `targetCol`, `targetRow` for `MOVE`
- `RESUME`
  - `playerId`, `matchId`
- `DISCONNECT`
  - `playerId`
- `PING`

### Server -> Client

- `ACCOUNT_CREATED`
  - `accountId`, `nickname`
- `ACCOUNT_LOGGED_IN`
  - `accountId`, `nickname`, `progress`
- `JOINED`
  - `playerId`, `nickname`, `accountId`, `characterType`, `skinColorHex`, `outfitColorHex`
  - when resumable: `canResume`, `resumableMatchId`, `reconnectDeadlineEpochMs`
  - if the previous match ended while offline: `lastGameResult`
- `MATCHED`
  - `matchId`, `playerId` (sent just before the match formally starts)
- `MATCH_STARTED`
  - `matchId`, `playerId`, `players`, `map`, `character`, `skills`, `firstTurnPlayerId`
- `STATE_UPDATED`
  - `matchId`, `turnPlayerId`, `windowIndex`, `windowDurationSeconds`, `readyPlayers`, `map`, `mapRows`, `mapCols`
  - `resolvedWindowIndex`, `resolutionSteps` (resolution playback: per-step before/after HP & positions)
  - `players[]`: each has `hp/maxHp`, `energy/maxEnergy/energySpentInWindow`, `mapCol/mapRow`, `skills`, `skillCooldowns`, `skinColorHex/outfitColorHex`, …
- `GAME_ENDED`
  - `matchId`, `winnerId`, `reason` (`HP_ZERO` | `PLAYER_SURRENDERED` | `PLAYER_ABANDONED`), (`surrenderedPlayerId`)
- `PLAYER_DISCONNECTED` / `PLAYER_RECONNECTED` / `MATCH_RESUMED`
  - `matchId`, `playerId`, (`reconnectDeadlineEpochMs`)
- `ERROR`
  - `code`, `message`

## Engine API Surface

The reusable engine surface is intentionally small:

- `GameSession`
  - `addPlayer(...)`
  - `registerSkill(SkillTemplate)`
  - `submitAction(GameAction)`
  - `getCurrentPlayerId()`
  - `getCurrentWindowIndex()`
  - `getWindowDurationSeconds()`
  - `isPlayerReady(playerId)`
  - `getReadyPlayers()`
  - `consumeWindowAdvancedFlag()`
  - `getMatchId()`, `isFinished()`, `getWinnerId()`
  - `getPlayerState(playerId)`, `getPlayerPosition(playerId)`
  - `isInsideMap(col, row)`, `isCellOccupied(col, row, exceptPlayerId)`
- `PlayerState`
  - tracks HP, energy, defense, movement rules, skill mastery, and per-window energy spending
- `BattleMap`
  - immutable rows/cols/layout definition for map generation and rendering
- `SkillTemplate`
  - immutable skill definition shared by server, client, and content modules
- `GameAction`
  - `AttackAction`, `DefendAction`, `UseSkillAction`, `MoveAction`, `EndTurnAction`

## Compatibility Notes

- `JOINED` is the current join acknowledgement type used by the existing clients.
- `ACCOUNT_*` messages are optional for non-account flows but are used by the MageFight launcher.
- `GameSession` remains content-agnostic; game-specific archetypes and skill trees live outside the engine.