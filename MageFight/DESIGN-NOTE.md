# MageFight 분리 설계 노트

## 목적

엔진 코드는 재사용 가능하게 유지하면서, 게임 전용 콘텐츠와 클라이언트 UI를 분리하는 것이 목표입니다.

초기 기획에서는 소프트웨어 아키텍처와 MageFight의 큰 요구사항을 먼저 설계한 뒤, 구현 세부를 채워 넣는 순서로 진행했습니다. 이렇게 나누어 두면 엔진은 일반화된 상태로 유지되고, MageFight는 게임 고유의 색을 담는 층으로 역할을 분리할 수 있습니다.

## 모듈 경계

- `magefight-content`
  - MageFight 전용 콘텐츠를 담당합니다: 아키타입, 스킬 트리, 성장 규칙, 프리셋 팩토리.
  - 공통 엔진 타입은 `turngame-engine`에 의존합니다.
- `magefight-client`
  - 데스크톱 런처와 전투 UI를 담당합니다.
  - `magefight-content`와 `turngame-engine` 모두에 의존합니다.

## 왜 이 분리가 유리한가

- 엔진은 일반화된 상태로 유지되어 다른 게임에도 재사용할 수 있습니다.
- 게임 콘텐츠는 UI 껍데기와 독립적으로 진화할 수 있습니다.
- 나중에 클라이언트를 교체해도 콘텐츠 계층을 다시 작성할 필요가 적습니다.

## 기획 책임

- 아키텍처 초안, 모듈 경계, MageFight의 핵심 기능 목록은 직접 설계했습니다.
- 이후의 구체적인 구현 세부는 vibe coding과 코딩 보조를 통해 확장했습니다.

## 구현 원칙

- 이벤트 기반 구조로 엔진과 UI의 결합을 낮게 유지했습니다.
- `engine` / `server` / `client` / `content`를 나눠 책임 경계를 분명히 했습니다.
- 멀티스레드 환경에서는 세션 상태와 플레이어 상태의 일관성을 우선했습니다.
- Factory, Command, Strategy 같은 고전 패턴을 확장 가능성 중심으로 적용했습니다.

## 로컬 빌드 전제

`turngame-engine`은 로컬 Maven 저장소에 설치되어 있거나, 같은 워크스페이스에서 먼저 빌드되어 있어야 MageFight를 빌드할 수 있습니다.

---

# English Design Note

## Goal

Keep engine code reusable while separating game-specific content from the client UI.

The original planning goal was to design the software architecture and the major MageFight requirements first, then fill in the implementation details afterward. That separation made it easier to keep the engine generic and let MageFight carry the game-specific flavor.

## Module Boundaries

- `magefight-content`
  - Owns MageFight-only content: archetypes, skill trees, progress rules, and preset factories.
  - Depends on `turngame-engine` for shared engine types.
- `magefight-client`
  - Owns the desktop launcher and battle UI.
  - Depends on both `magefight-content` and `turngame-engine`.

## Why This Split Helps

- The engine stays generic and can be reused by other games.
- Game content can evolve independently from the UI shell.
- The client can be replaced later without rewriting the content layer.

## Planning Ownership

- Architecture sketching, module boundaries, and the major MageFight feature list were planned manually.
- Most of the concrete implementation details were then expanded with vibe coding and coding assistance.

## Implementation Principles

- An event-driven structure keeps the engine and UI loosely coupled.
- `engine` / `server` / `client` / `content` are split so responsibilities stay clear.
- In a multithreaded environment, consistency of session and player state comes first.
- Factory, Command, and Strategy are applied with extensibility in mind.

## Local Build Assumption

`turngame-engine` must be available in the local Maven repository or built in the same workspace before building MageFight.