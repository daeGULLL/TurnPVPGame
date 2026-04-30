# 멀티플레이어 턴제 게임 프로젝트

## 프로젝트명
**Turn Game Engine + MageFight**
- **GameEngine**: 멀티플레이어 턴제 게임 엔진 (코어 게임 로직)
- **MageFight**: GameEngine 기반 GUI 클라이언트 및 콘텐츠 확장

---

## 프로그램 개요

이 프로젝트는 **객체지향 심화, 디자인 패턴, 제네릭/컬렉션, 람다, 멀티스레드 기초**를 멀티플레이어 게임 시스템에 적용해본 프로젝트입니다.

**과제 요구사항 충족:**
- ✅ **수업 범위 기술 활용**: 객체지향 심화, 디자인 패턴, 제네릭/컬렉션, 람다, 멀티스레드 등을 모두 포함
- ✅ **고급 기법 다수 사용**: 이벤트 기반 아키텍처, 멀티스레드 타임아웃, 제네릭 EventBus 등
- ✅ **개략적 설계와 요구사항 기획**: 이벤트-드리븐 구조로 느슨한 결합을 목표로 한 아키텍처 기획
- ✅ **미니프로젝트 규모**: 완전한 네트워크 게임 엔진 + GUI 클라이언트

**GameEngine**은 소켓 통신 기반의 멀티플레이어 게임 서버와 콘솔 클라이언트를 제공하며, **MageFight**는 Swing GUI를 통해 완성도 높은 게임 클라이언트 경험을 제공합니다.

분리 목적은 명확합니다. **GameEngine은 다른 게임에도 재활용 가능한 공용 엔진**으로 유지하고, MageFight는 그 엔진 위에 얹는 게임별 콘텐츠와 UI로 분리했습니다. 즉, 엔진은 범용 로직을 담당하고 MageFight는 마도사 테마에 맞는 아키타입, 스킬 트리, 진행 규칙, 전투 UI를 담당합니다.

본 프로젝트에서 제가 주도적으로 수행한 부분은 **게임 동작에 대한 개략적인 소프트웨어 아키텍처 설계**와 **MageFight의 핵심 게임 요소(직업, 진행도, 스킬 트리, UI 구조) 기획**입니다. 상세 구현은 AI 보조 도구를 활용해 확장했으며, 생성된 코드는 제가 직접 전수 검토하고 다듬어 완성도를 높였습니다.

주요 특징:
- 네트워크 기반 멀티플레이어 턴제 전투 시스템 (소켓 기반)
- JSON 프로토콜을 통한 구조화된 서버-클라이언트 통신
- AI 봇 플레이어 자동 전투 기능
- 스레드 타임아웃 기반 자동 턴 진행
- GUI 기반 캐릭터 진행 상황 관리 및 전투 가시화
- 도형 기반 스킬 트리 UI와 fog-of-war 해제 연출
- 스킬 사용 누적에 따른 숙련도 포인트 / 영감 포인트 성장 시스템
- 플레이어 계정 및 캐릭터 진행 상황 영속성

---

## 사용한 주요 자바 개념

### 객체지향 설계
- **패키지 구조**: `engine`, `client`, `server`, `domain`, `event`, `factory` 등 계층별 분리
- **상속 및 다형성**: 플레이어 타입별 구현 (`ConsoleClient`, `BotPlayerAgent`)
- **캡슐화**: private 멤버 변수와 public 접근 메서드

### 디자인 패턴 (Gang of Four - GoF 클래식 패턴)

이 프로젝트에서 사용한 전략, 커맨드, 옵저버, 팩토리 메서드 패턴은 모두 **GoF(Gang of Four)**에서 널리 알려진 고전 디자인 패턴입니다.

**1. Strategy Pattern (전략 패턴)**
- 위치: `com.turngame.engine.rules.BasicRuleSet`
- 용도: 게임 규칙을 교체 가능한 전략으로 정의
- 효과: 새로운 규칙 세트 추가 시 기존 코드 수정 없음 (Open/Closed 원칙)

**2. Command Pattern (커맨드 패턴)**
- 위치: `com.turngame.engine.command.*` (AttackAction, EndTurnAction, DefendAction, UseSkillAction)
- 용도: 게임 액션을 객체로 캡슐화하여 큐 저장 및 실행 취소 등 지원 가능
- 효과: 액션 처리 로직과 실행 로직 분리 (Single Responsibility)

**3. Observer Pattern (옵저버 패턴)**
- 위치: `com.turngame.event.EventBus` (발행-구독 시스템)
- 용도: 게임 이벤트 발생 시 모든 구독자에게 알림 (이벤트 기반 아키텍처)
- 효과: 느슨한 결합으로 서버-클라이언트, UI-엔진 간 의존성 제거
- 고급 활용: **제네릭을 활용한 타입 안전 EventBus** (`EventBus<T extends GameEvent>`)

**4. Factory Method Pattern (팩토리 메서드 패턴)**
- 위치: `com.turngame.factory.*` (CharacterFactory, SkillFactory, MapFactory)
- 용도: 캐릭터, 스킬, 맵 생성 로직을 한 곳에 집중
- 효과: 생성 로직 변경 시 클라이언트 코드 영향 없음 (Creator/Product 분리)

### 컬렉션 및 제네릭 (고급 활용)
- **제네릭 EventBus**: `EventBus<T extends GameEvent>` → 타입 안전성 + 유연성
- **컬렉션 활용**:
  - `List<GameAction>`: 턴마다 수행할 액션 큐
  - `Map<String, FighterSpec>`: 플레이어 상태 관리
  - `HashMap<String, Integer>`: 스킬 숙련도 저장소
  - `ConcurrentHashMap` 검토: 멀티스레드 환경에서의 스레드 안전성
- **와일드카드 타입**: `List<? extends GameAction>` 활용으로 하위 타입 포함

### 멀티스레드 기초 (실전 구현)
- **ScheduledExecutorService**: 턴 타임아웃 자동 처리
  ```java
  scheduler.schedule(() -> submitAction(new EndTurnAction(playerId)), 30, TimeUnit.SECONDS);
  ```
- **Thread 풀 관리**: `ServerSocket` + 스레드 풀로 다중 클라이언트 동시 처리
- **동시성 문제 해결**:
  - `synchronized` 또는 `ReentrantLock`을 통한 공유 자원 보호
  - 플레이어 상태 및 게임 세션의 스레드 안전성 확보

### 함수형 프로그래밍 (람다 & Functional Interface)
- **람다 표현식**: 이벤트 리스너 등록 (선언적 프로그래밍)
  ```java
  eventBus.subscribe(TurnStartedEvent.class, e -> updateTurnUI(e.playerId()));
  ```
- **Consumer, Function, Supplier**: 콜백 및 함수형 인터페이스 활용
- **메서드 레퍼런스**: `System.out::println` 등으로 간결한 코드
- **Optional**: null 안전성 (`accountStore.login(id, password).ifPresentOrElse(...)`)

### GUI (Swing)
- `JFrame`, `JPanel`, `JComboBox`, `JTextArea` 등 컴포넌트 활용
- `CardLayout`: 로그인 및 로비 화면 전환
- `BorderLayout`, `BoxLayout`: 레이아웃 관리

### 네트워크 통신
- **Socket 프로그래밍**: TCP 기반 클라이언트-서버 통신
- **JSON 직렬화**: `Gson` 라이브러리 사용 (메시지 프로토콜)

---

## 클래스 구성 설명

### GameEngine 모듈

#### Core Engine (`com.turngame.engine`)
- `GameSession`: 게임 세션 관리 (플레이어, 턴, 액션 처리)
- `TurnManager`: 턴 순서 관리
- `BattleMap`: 전투 맵 정보
- `FighterSpec`: 플레이어 상태 (HP, 스탯, 스킬)

#### Server (`com.turngame.server`)
- `ServerMain`: 서버 진입점 (포트 및 최대 플레이어 설정)
- `GameServer`: 멀티 클라이언트 관리 및 게임 세션 조율
- `ClientHandler`: 개별 클라이언트 연결 처리
- `AccountStore`: 플레이어 계정 저장

#### Client (`com.turngame.client`)
- `ConsoleClient`: 콘솔 기반 플레이어 클라이언트
- `GameClient`: 서버와의 통신 로직

#### AI (`com.turngame.ai`)
- `BotPlayerAgent`: 자동 행동 AI 플레이어

#### Command 패턴 (`com.turngame.engine.command`)
- `GameAction`: 게임 액션 인터페이스
- `AttackAction`, `DefendAction`, `UseSkillAction`, `EndTurnAction`: 각 액션 구현

#### Event System (`com.turngame.event`)
- `EventBus`: 발행-구독 이벤트 시스템
- `GameEvent`, `TurnStartedEvent`, `GameEndedEvent` 등: 이벤트 타입

#### Factory (`com.turngame.factory`)
- `CharacterFactory`, `SkillFactory`, `MapFactory`: 게임 객체 생성

### MageFight 모듈

#### magefight-content
- `MageArchetype`: 플레이어 직업 (APPRENTICE, ELEMENTALIST, RUNE_SCHOLAR)
- `MageProgress`: 캐릭터 진행 상황 (레벨, 선택된 직업, 스킬 숙련도, 숙련도 포인트, 영감 포인트)
- `ArchetypeUnlockService`: 직업 선택 조건 관리
- `MageSkillTreeFactory`: 아키타입별 스킬 트리 정의 및 연결
- `CharacterLevelCalculator`: 경험치로부터 레벨 계산

이 모듈은 게임 엔진이 아니라 **MageFight 전용 콘텐츠 계층**입니다. 캐릭터 성장 규칙, 스킬 트리, 직업 선택 조건처럼 게임 규칙의 상위 개념을 갖고 있으며, 엔진의 전투 시스템과 독립적으로 진화할 수 있도록 설계했습니다.

#### magefight-client
- `MageFightApp`: 애플리케이션 진입점
- `MageFightLauncher`: 로그인 및 로비 화면
- `MageFightFrame`: 전투 UI 및 게임 플레이 화면
- `SkillTreePanel`: 도형 기반 스킬 트리 렌더링 및 습득 UI
- `FirstPersonBattlePanel`: 1인칭 전투 시각화

이 모듈은 실제 사용자가 보는 **클라이언트 껍데기(UI shell)** 입니다. 로그인, 로비, 전투 화면 전환, 스킬 선택, 결과 표시 같은 상호작용을 맡고, 콘텐츠나 전투 규칙은 `magefight-content`와 `turngame-engine`에 위임합니다.

---

## 실행 방법

### IntelliJ IDEA에서 실행

#### 1단계: 프로젝트 열기
1. **File** → **Open** → 프로젝트 루트 경로의 `pom.xml` 선택
2. "Open as Project" 클릭 (GameEngine과 MageFight 모듈 자동 로드)

만약 이미 target 디렉토리들 내에 jar 파일이 존재함이 확인되었으면 2단계의 절차는 건너뛰고 ServerMain과 MageFightApp 2개만 실행하면 됩니다. 

#### 2단계: 빌드
**우측 Maven 패널**에서:
```
GameEngine → Lifecycle → install (더블클릭)
↓
MageFight → Lifecycle → package (더블클릭)
```

#### 3단계: 실행

**GameEngine (서버 + 콘솔 클라이언트)**
```
GameEngine → src/main/java/com/turngame/server/ServerMain.java
  → 클래스명 좌측 ▶ 클릭
  → Program arguments: 9090 20
```

**MageFight (GUI 클라이언트)**
```
MageFight → src/main/java/com/magefight/MageFightApp.java
  → ▶ 클릭
  → Swing GUI 창 자동 실행
```

---

## 주요 기능 설명

### GameEngine 기능
- **턴제 전투 시스템**: 플레이어가 액션을 제출하면 큐에 저장되고, 양쪽이 모두 END_TURN을 눌러야 모든 액션이 **동시에** 적용됨
  - 이를 통해 공정한 게임 흐름 보장 (서로의 액션이 상대방 선택 전에 영향을 주지 않음)
- **4가지 액션**: ATTACK (공격), DEFEND (방어), USE_SKILL (스킬 사용), END_TURN (턴 종료)
  - 스킬, 공격, 방어는 제출 시 즉시 큐에 저장되고, 턴 윈도우 종료 후 순차 적용
- **스킬 시전 유연성**: 스킬은 범위 안에 대상이 없더라도 시전 가능하며, 이 경우 피해만 발생하지 않고 시전 자체는 처리됨
- **턴 EN 예약 방식**: 턴 에너지 한도는 액션을 고르는 즉시 차감되고, 실제 HP/총 에너지 감소는 윈도우 종료 시점에 반영됨
- **턴 타임아웃**: 자동 END_TURN (30초 이상 응답 없을 경우)
- **턴 EN 제한**: 봇전에서 무한 턴 EN이 되지 않도록 제한값 적용
- **AI 봇 플레이어**: STATE_UPDATED 메시지를 받고 자동 행동
- **JSON 프로토콜**: 모든 통신이 구조화된 JSON으로 진행
- **리플레이 저장**: 매치 종료 시 `replays/<matchId>.jsonl` 이벤트 로그 생성
- **입력 검증**: 잘못된 액션, 범위 밖 데미지, 사망 플레이어 행동 제한
- **데미지 및 이펙트 동시 적용**: 모든 플레이어의 액션이 동시에 적용되므로 예측 가능한 게임 진행

### MageFight 기능
- **로그인/계정 생성**: 플레이어 계정 및 비밀번호 관리
- **직업 선택 시스템**: APPRENTICE → ELEMENTALIST → RUNE_SCHOLAR 순 진행
- **진행 상황 저장**: 레벨, 선택된 직업, 스킬 숙련도 자동 저장
- **스킬 성장 시스템**: 스킬 사용 누적 시 숙련도 포인트가 쌓이고, 10포인트마다 숙련도 1레벨 상승
- **GUI 전투**: Swing을 통한 시각적 전투 UI
- **1인칭 전투 뷰**: 게임보드 및 플레이어/봇 상태 실시간 표시
- **스킬 시스템**: 플레이어 레벨에 따라 언락되는 스킬 트리

---

## 본인이 구현한 부분 (수업 범위 + 고급 기법 활용)

### 📌 GameEngine 핵심 구현

#### 1. **멀티플레이어 네트워크 아키텍처** (멀티스레드 기초)
- `ServerSocket` + `ClientHandler` 스레드로 다중 클라이언트 동시 처리
- 각 클라이언트마다 별도의 스레드에서 독립적인 게임 진행
- JSON 기반 프로토콜로 구조화된 메시지 통신

#### 2. **이벤트 기반 아키텍처** (Observer Pattern + 제네릭)
- **제네릭 EventBus**: `EventBus<T extends GameEvent>` 타입 안전성 확보
- 느슨한 결합으로 게임 로직과 UI 분리 (높은 재사용성)
- 본인의 창의적 설계: 전통적 Observer 패턴에 **Generic Type Parameter** 추가

#### 3. **Command 패턴 기반 액션 시스템** (객체지향 심화)
- `GameAction` 인터페이스 + 4가지 구체적 액션 클래스 (`AttackAction`, `DefendAction`, `UseSkillAction`, `EndTurnAction`)
- 액션을 **큐에 저장**하고 순차 실행 가능 (나중에 리플레이 기능 확장 가능)
- 각 액션의 **유효성 검증 로직** 독립적으로 구현 (Single Responsibility Principle)

#### 4. **턴 타임아웃 자동 처리** (멀티스레드 심화)
```java
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(10);
scheduler.schedule(
    () -> session.submitAction(new EndTurnAction(playerId)), 
    30, TimeUnit.SECONDS
);
```
- `ScheduledExecutorService` 활용으로 30초 자동 턴 종료
- 별도 타이머 스레드로 비동기 실행

#### 5. **Factory Method 패턴** (디자인 패턴)
- `CharacterFactory`, `SkillFactory`, `MapFactory`로 객체 생성 로직 중앙화
- 새로운 캐릭터/스킬 추가 시 팩토리만 수정 (확장성)

#### 6. **JSON 프로토콜 설계 & 직렬화**
- `Gson` 라이브러리로 자바 객체 ↔ JSON 양방향 변환
- 프로토콜 정의: `JOIN`, `MATCH_STARTED`, `ACTION`, `STATE_UPDATED` 등
- 네트워크 통신의 신뢰성 확보

### 📌 MageFight GUI 구현 (고급 기법)

#### 1. **Multi-Module Maven 프로젝트** (확장성)
- `magefight-parent` (POM), `magefight-content`, `magefight-client` 분리
- GameEngine과 MageFight의 의존성 관계 체계적으로 관리

#### 2. **Swing GUI 고급 기법**
- **CardLayout**: 로그인 화면 ↔ 로비 화면 ↔ 전투 화면 전환 관리
- **BorderLayout + BoxLayout 조합**: 복잡한 레이아웃 유연하게 구성
- 스레드 안전성: GUI 업데이트를 `SwingUtilities.invokeLater()`로 메인 스레드에서 처리

#### 3. **캐릭터 진행 상황 관리 시스템** (제네릭 + 컬렉션)
```java
Map<String, Integer> skillMasteryLevels = new HashMap<>();
progress.getSkillMastery(skillName);  // 제네릭 컬렉션 활용
```
- 플레이어 계정별 진행 상황 저장 (영속성)
- 직업 언락 조건 관리 (`ArchetypeUnlockService`)

#### 4. **직업 시스템 & 조건 기반 로직** (Strategy Pattern)
- APPRENTICE (레벨 1) → ELEMENTALIST (레벨 2) → RUNE_SCHOLAR (레벨 3) 순차 진행
- 각 직업마다 스탯 범위 다름 (enum으로 정의)
- 본인의 창의적 설계: 게임 진행에 따른 **자동 언락 시스템**

#### 5. **플레이어 계정 저장소** (캡슐화)
- `AccountStore` (싱글톤 패턴)으로 중앙 관리
- 로그인/계정 생성 기능
- 진행 상황 자동 저장 (로컬 파일 또는 메모리)

#### 6. **람다 & 함수형 프로그래밍 활용**
```java
accountStore.login(id, password).ifPresentOrElse(
    session -> { /* 로그인 성공 */ },
    () -> { /* 로그인 실패 */ }
);
```
- `Optional` 활용으로 null 안전성 확보
- 콜백 함수 (`Consumer`)로 진행 상황 저장

---

## AI 활용 여부 및 활용 범위

### ✅ AI 활용: **Yes (GitHub Copilot)**

실제 코딩은 AI가 생성한 코드를 중심으로 진행했으며, 저는 전체 흐름과 요구사항을 정리한 뒤 결과물을 검토하고 필요한 부분을 수정하는 역할을 수행했습니다.

#### 🎯 제가 주도한 부분

1. **대략적인 흐름 설계와 아이디어 정리**
   - 게임이 어떤 순서로 동작해야 하는지 개략적인 구조를 정리
   - MageFight에 들어갈 직업, 진행도, 스킬 트리, UI 흐름을 기획
   - GameEngine과 MageFight를 분리해야 하는 이유를 정리

2. **AI가 생성한 코드 검토**
   - AI가 제안한 코드의 동작 여부와 구조를 확인
   - 컴파일 오류, 연결 오류, UI 흐름 문제를 점검
   - 필요 시 가독성을 높이거나 로직 간 연결을 직접 조정

3. **문서와 설명 보완**
   - README와 설계 노트를 검토하면서 내용 정리
   - 평가자가 읽기 쉽도록 프로젝트 의도와 구조를 설명

#### 🔎 요약하자면

- 핵심 아이디어와 설계 방향은 **제가 직접 기획**했습니다.
- 실제 구현 코드는 **AI의 보조**를 받았습니다.
- 최종 결과물은 **제가 전수 검토하며 논리적 오류와 어색한 부분을 수정 및 보완**했습니다.

#### AI를 사용한 이유

1. **시간 효율**: 반복적인 보일러플레이트(Boilerplate) 코드 작성 속도를 높이기 위해
2. **검토 중심 학습**: 구현 결과를 읽고 검토하는 과정 자체가 설계 역량 학습에 도움이 되기 때문에
3. **문서화 보조**: 설명문과 예시 코드를 깔끔하게 정리하는 데 도움을 받기 위해

---

## 휴먼 인 더 루프(Human-in-the-Loop) 방식을 채택한 이유
1. **프로젝트의 본질**: 코드 작성 자체보다 소프트웨어의 설계와 구조를 이해하고 설명하는 것이 더 중요했기 때문입니다.
2. **핵심 역량 집중**: 시스템의 데이터 흐름 설계와 비즈니스 요구사항 정리는 개발자의 고유 권한이자 책임이므로 직접 수행해야 의미가 있다고 판단했습니다.
3. **품질 보증**: AI가 생성한 코드라 할지라도 치명적인 버그나 아키텍처 결함이 있을 수 있어, 최종적인 검토와 수정은 직접 진행해야 안정성을 담보할 수 있기 때문입니다.

---

## 프로젝트 구조
```text
workspace/
├── GameEngine/
│   ├── src/main/java/com/turngame/
│   │   ├── engine/       (게임 엔진 코어)
│   │   ├── server/       (게임 서버)
│   │   ├── client/       (콘솔 클라이언트)
│   │   ├── ai/           (AI 봇)
│   │   ├── domain/       (도메인 모델)
│   │   ├── event/        (이벤트 시스템)
│   │   ├── factory/      (팩토리 패턴)
│   │   └── replay/       (리플레이 저장)
│   └── target/           (컴파일된 JAR 및 클래스)
│
├── MageFight/
│   ├── magefight-client/
│   │   └── src/main/java/com/magefight/ui/  (Swing GUI)
│   │
│   ├── magefight-content/
│   │   └── src/main/java/com/magefight/
│   │       ├── model/    (캐릭터 모델)
│   │       ├── progress/ (진행 상황 관리)
│   │       └── factory/  (콘텐츠 팩토리)
│   │
│   └── pom.xml           (부모 POM)
│
├── pom.xml               (멀티모듈 루트)
└── README.md             (이 파일)
```

---

## 기술 스택
- **언어**: Java 17
- **빌드**: Apache Maven
- **GUI**: Java Swing
- **라이브러리**: Gson (JSON 직렬화)
- **IDE**: IntelliJ IDEA
