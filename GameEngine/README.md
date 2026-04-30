# Turn Game Engine

멀티플레이어 턴제 게임을 위한 공용 엔진 프로젝트입니다.

이 저장소의 핵심 목표는 **특정 게임에 종속되지 않는 턴제 전투 엔진**을 만드는 것입니다. 규칙, 턴 처리, 네트워크 동기화, 상태 검증, 리플레이 저장을 엔진이 담당하고, 게임 고유의 테마와 UI는 바깥 모듈이 맡습니다. MageFight는 이 엔진을 실제로 사용한 첫 사례입니다.

## 역할
- 서버에서 전투 상태를 관리하고 턴 순서를 조정합니다.
- 클라이언트와 JSON 메시지를 주고받아 상태를 동기화합니다.
- 액션 검증, 데미지 계산, 사망 처리, 타임아웃 종료를 일관되게 처리합니다.
- 매치 이벤트를 기록해 리플레이를 남깁니다.

## 포함 기술
- 객체지향 설계
- 디자인 패턴: Strategy, Command, Observer, Factory Method
- 제네릭과 컬렉션
- 람다와 함수형 스타일
- 멀티스레드 기초: 타임아웃, 서버 루프, 자동 종료 처리

## 실행 순서
1. 엔진 모듈을 먼저 빌드합니다.
2. 서버를 실행합니다.
3. 콘솔 클라이언트 2개를 실행하거나, 클라이언트 1개와 봇 1개를 연결합니다.

```bash
mvn package
java -cp target/turngame-engine-1.0.0.jar com.turngame.server.ServerMain 9090 20

# 같은 서버에 접속하는 예시
java -cp target/turngame-engine-1.0.0.jar com.turngame.client.ConsoleClient 127.0.0.1 9090 userA WARRIOR
java -cp target/turngame-engine-1.0.0.jar com.turngame.client.ConsoleClient 127.0.0.1 9090 userB ROGUE

# 봇을 붙이는 경우
java -cp target/turngame-engine-1.0.0.jar com.turngame.ai.BotPlayerAgent 127.0.0.1 9090 botA MAGE
```

## 구현 상태
- `JOIN`, `MATCH_STARTED`, `ACTION`, `STATE_UPDATED` JSON 메시지 처리
- `ACTION` 4종: `ATTACK`, `USE_SKILL`, `DEFEND`, `END_TURN`
- 턴 타임아웃: `ScheduledExecutorService` 기반 자동 `END_TURN`
- AI 봇 연결: `STATE_UPDATED`를 보고 자동 행동
- 서버 입력 검증: 매치 불일치, 잘못된 `actionType`, 데미지 범위, 사망 플레이어 행동 제한
- 리플레이 저장: 매치별 `replays/<matchId>.jsonl` 이벤트 로그 생성
- Factory Method 적용: 캐릭터, 스킬, 맵 생성 팩토리와 매치 시작 payload 제공

## 설계 관점
- 엔진은 **게임 규칙과 전투 진행**만 알고, UI나 테마는 모릅니다.
- 클라이언트는 **플레이어 입력과 화면 표현**만 담당합니다.
- 팩토리와 이벤트 시스템을 통해 새 캐릭터 타입이나 새 액션을 추가할 때 수정 범위를 줄였습니다.
- 멀티스레드와 타임아웃은 실전형 서버 흐름을 만들기 위한 핵심 요소입니다.

## 구조 이해
- `server`: 세션, 매치, 상태 검증, 네트워크 수신
- `client`: 콘솔 기반 입력 예시
- `ai`: 봇 플레이어 예시
- `engine`: 턴 규칙, 커맨드 처리, 판정 로직
- `domain`: 전투에 쓰이는 공통 모델

## 콘솔 명령
- `attack <targetId> <damage>`
- `skill <targetId> <skillName>`
- `defend`
- `end`
