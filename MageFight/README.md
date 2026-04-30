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

## Project Notes

- `magefight-content` owns the data model for archetypes, skill trees, progression, and unlock rules.
- `magefight-client` renders the launcher, lobby, skill tree, and battle screen.
- The module split is intentional: the engine stays reusable, while MageFight can keep evolving as a game-specific client without mixing concerns.

## AI Usage Notes

- I planned the overall flow and game elements, then reviewed and adjusted the code generated with AI assistance.
- The concrete MageFight implementation was produced with AI support, while I handled structure and behavior review.
- This README focuses on project intent and module responsibilities without overstating personal implementation work.
