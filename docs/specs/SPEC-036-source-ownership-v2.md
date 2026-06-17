# SPEC-036: Source Ownership Pipeline v2

> 상태: Active / Canonical
> 목표: 단순한 ownership 결정으로 IDML 원본 비주얼과 HWPX 편집성을 동시에 보존한다.

이 문서가 source ownership의 최종 기준이다. `SPEC-035`와 충돌하면 이 문서를 따른다.
`SPEC-035`는 이관 기록과 배경 설명으로만 사용한다.

## 1. 원칙

1. 원본 source object가 먼저 소유자를 가진다.
2. render candidate는 source owner가 허용한 경우에만 생성된다.
3. 후속 단계는 plan을 실행만 한다.
4. 텍스트 머지, 글자 수/크기 기반 판정, PNG 픽셀 분석 판정은 금지한다.
5. 원본 layer/z/inline/floating 정보는 버리지 않고 plan과 결과에 추적한다.

## 2. 텍스트

- 본문, 제목, 라벨, 설명, 문항, 출처는 HWPX 텍스트다.
- InDesign에서 숨긴 텍스트가 있는 PNG는 textless shell로만 배치한다.
- PNG가 텍스트를 소유할 수 있는 경우는 atomic marker뿐이다.
- atomic marker는 명시적 표식 패턴만 허용한다: `가`, `ㄱ`, `1`, `01`, 체크/선택지 표식.
- 텍스트를 임의로 합치지 않는다. 연결 TextFrame은 원본 thread만 따른다.

## 3. 비주얼

- `PLACE_TEXT_SHELL`: textless PNG/vector shell을 figure로 배치하고, 텍스트는 별도 HWPX TF가 소유한다.
- `PLACE_FLOATING_PNG`: 사진, 삽화, 차트, 완성형 콘텐츠 PNG.
- `PLACE_INLINE_PNG`: 원본이 inline이고 텍스트를 PNG가 소유하는 atomic/graphic only 객체.
- `ABSORB_TEXT_STYLE`: fill/stroke/underline처럼 HWPX 텍스트 속성 또는 1x1 table로 표현 가능한 장식.
- `DROP_VISUAL`: 같은 source의 더 정확한 owner가 있을 때만 사용한다.

## 4. 레이어

원본 정보와 정책 layer를 분리한다.

- 원본: `sourceLayerId`, `sourceLayerName`, `sourceLayerIndex`, `zOrder`, `zOrderKnown`.
- 정책: `BACKGROUND`, `DECORATION`, `CONTENT`, `TEXT`.

정책 layer는 배치 평면을 정하는 용도이고, 원본 layer는 추적/정렬/검증의 근거다.
원본 z가 불명확할 때만 정책 layer로 보정한다.

## 5. 중복 차단

Stage 1에서 source object별 visible output slot을 하나만 만든다.
후속 단계가 `handled set`으로 중복을 막는 구조는 제거한다.

허용되는 조합:

- text source: HWPX TF 한 개
- visual source: PNG/vector/table/style 중 한 개
- shell source: textless shell figure + 별도 text source의 HWPX TF

금지되는 조합:

- complete PNG + 같은 텍스트의 HWPX TF
- parent PNG + child PNG 동시 배치
- inline PNG + floating PNG 동시 배치
- shell을 imageFill TextFrame으로 만들고 그 안에 텍스트를 재생성

### 5.1 그룹 shell + 다중 editable TF

복합 그래픽 그룹이 배경/껍데기/shell 역할을 하고 그 위에 하나 이상의 editable
TextFrame이 올라가는 경우가 있다. 이 경우 shell과 TF를 중복으로 보지 않는다.
서로 다른 ownership channel이다.

Stage 1은 아래처럼 하나의 source bundle로 계획한다.

- parent visual group: `visualAction=PLACE_TEXT_SHELL`
- parent visual group: textless PNG/vector shell만 소유한다.
- parent visual group: parent render가 실제로 포함한 하위 그룹의 그래픽 fragment까지 하나의 shell로 소유한다.
- child TextFrame들: 각각 `textAction=OWNED_BY_HWPX_TEXT`
- child TextFrame들: 각각 원본 좌표/레이어/z/inline 정보를 유지한다.
- child visual fragment들: parent shell이 더 정확한 visual owner이면 `DROP_VISUAL`

이 정책은 `소단원 정리`처럼 하나의 큰 그래픽 껍데기 위에 여러 TF가 올라간 경우에도
같이 적용한다. 큰 그래픽 그룹은 하나의 shell로 보존하고, `소단원`, `정리` 같은 TF는
각각 HWPX 텍스트로 배치한다.

원본 Group 안에 하위 Group이 있고 그 하위 Group이 label shell과 TF를 품는 경우도 같은
규칙을 적용한다. 단, 하위 그래픽 fragment를 drop할 수 있는 것은 parent render의
`sourceObjectIds`/visual source에 그 fragment가 실제 포함되어 parent shell PNG/vector 안에
존재한다고 확인되는 경우뿐이다. 원본 hierarchy상 하위 Group이라는 이유만으로 child shell을
drop하지 않는다. parent render가 해당 fragment를 빠뜨렸다면, child shell은 별도 visible
output으로 남겨 시각 누락을 막는다.

직접 도형을 가진 leaf Group 안에 editable TextFrame이 하나 이상 있으면, 이 leaf Group을
개별 label backdrop보다 먼저 하나의 `PLACE_TEXT_SHELL` source bundle로 추출한다. 이때
leaf Group 안의 모든 직접 도형은 하나의 textless shell이 소유하고, child TextFrame들은
각각 HWPX 텍스트가 소유한다. 상위 layout Group이 이 leaf shell만 감싸고 직접 visual
도형을 갖지 않는다면 상위 Group은 별도 PNG shell을 만들지 않는다. 이는 `제목`/`풀이`처럼
하나의 InDesign 그룹 안에 여러 라벨 도형과 여러 TF가 들어 있는 경우를 하나의 shell로
보존하기 위한 구조 규칙이며, 페이지 번호/문구/좌표/글자 수 기반 예외가 아니다.

중복으로 보는 것은 아래 경우다.

- parent shell PNG와 descendant shell/graphic PNG가 동시에 보이는 경우
- shell PNG 안에 child TF의 텍스트 픽셀이 남아 있는 경우
- child TF를 HWPX 텍스트로 배치하면서 같은 TF를 drawText/imageFill 내부 텍스트로 재생성하는 경우

구현상 duplicate 검증이 필요하더라도 parent plan의 source 정체성을 잘라내지 않는다.
필요하면 `sourceObjectIds`와 별도로 visual source, owned text frame, descendant fragment를
구분하는 메타데이터를 둔다. 중복 차단을 위해 큰 그룹의 source 정보를 축소하면
부분 shell만 살아남아 원본 비주얼이 깨진다.

## 6. Stage

1. Input Prepare: 원본 ID, layer, z, parent/group, inline/floating, owner metadata 수집.
2. Source Ownership Planner: source object별 text/visual slot 확정.
3. Text Builder: HWPX TF, paragraph, table 생성. 임의 merge 금지.
4. Visual Builder: plan에 있는 visual만 배치. 재판정 금지.
5. Validator: 중복, layer, source-slot invariant만 검사.

Stage 책임은 코드 구조에서도 분리한다.

- Stage 1만 ownership 결정을 만든다.
- Stage 2/3은 plan 실행만 한다.
- Stage 4는 새 plan을 만들거나 고치지 않는다.
- 후속 phase가 새 예외를 만들 필요가 있으면 먼저 Stage 1 plan 모델을 고친다.

## 7. 구현 제거 대상

- text length/box size로 semantic label을 판정하는 코드
- PNG alpha/색/밝기를 읽어 shell/text를 분리하는 코드
- bounds/occlusion만으로 텍스트를 누락시키는 코드
- `textOwner=hwpx_tf` PNG를 complete PNG처럼 배치하는 코드
- 후속 phase의 `already handled` 중심 중복 차단
- 원본 thread가 아닌 임의 TextFrame merge

중간 이관 기간에 남는 legacy bridge는 다음 조건을 만족해야 한다.

- 새 정책 판단을 만들지 않는다.
- `ObjectPlan`이 있으면 plan을 우선한다.
- 로그에는 bridge 사유가 아니라 최종 `ObjectPlan` 사유를 남긴다.
- 제거 대상임을 주석으로 명시한다.

## 8. 표현 우선순위

1. 본문 텍스트: HWPX paragraph/run 속성.
2. 편집 가능한 박스/표형 장식: 1x1 table 우선.
3. 원본 shell이 복잡하면 textless PNG/vector shell + HWPX TF.
4. `drawText`는 마지막 fallback이며 정책 판단에는 사용하지 않는다.

## 9. Validator invariant

Validator는 변환을 보정하지 않는다. 아래 조건을 기록하고, strict 모드에서는 실패시킨다.

- 같은 source object의 visible visual slot은 하나다.
- 같은 TextFrame은 `OWNED_BY_PNG`와 `OWNED_BY_HWPX_TEXT`를 동시에 가질 수 없다.
- 같은 source가 inline과 floating으로 동시에 보이면 안 된다.
- `OWNED_BY_HWPX_TEXT` source를 complete PNG처럼 배치하지 않는다.
- `PLACE_TEXT_SHELL`은 소유 텍스트보다 뒤에 있어야 한다.
- source layer/z 정보가 없는 객체는 추적 누락으로 본다.
- parent `PLACE_TEXT_SHELL`이 있는 source bundle에서는 descendant visual fragment가 별도 visible output을 가질 수 없다.
- parent `PLACE_TEXT_SHELL` 위의 owned child TextFrame은 누락되면 안 된다.
- `PLACE_TEXT_SHELL` PNG/vector에는 owned child TextFrame의 텍스트 픽셀이 포함되면 안 된다.

## 10. 개발 플랜

정책 반영은 source identity를 보존하면서 visible output slot만 단일화하는 방향으로 진행한다.

### 10.1 1단계: ObjectPlan 모델 확장

대상:

- `ownership/ObjectPlan.java`
- `ownership/OwnershipPlanValidator.java`
- `ResolvedBuildContext.java`

추가 필드:

- `visualSourceObjectIds`: 실제 visual slot을 소유하는 source id.
- `ownedTextFrameIds`: shell 위에 별도 HWPX 텍스트로 배치될 TF id.
- `descendantVisualObjectIds`: parent shell에 흡수되어 별도 visible output을 가지면 안 되는 하위 visual id.
- `sourceBundleId` 또는 `sourceBundleKey`: parent/child 후보를 같은 source bundle로 묶는 안정 키.

원칙:

- `sourceObjectIds`는 원본 bundle 정체성으로 유지한다.
- 중복 검증과 visual slot 판단은 `visualSourceObjectIds`를 우선한다.
- child TF 소유권 판단은 `ownedTextFrameIds`를 우선한다.
- 중복 차단을 위해 `sourceObjectIds`를 잘라내지 않는다.

삭제/대체 대상:

- `ResolvedBuildContext.trimSourceObjectIdsClaimedBy`
- `OwnershipPlanner.resolveNestedTextShellSources`
- parent plan의 `sourceObjectIds`를 줄여서 중복을 피하는 모든 코드

### 10.2 2단계: Source Bundle Index 생성

대상:

- `OwnershipPlanner`
- `ResolvedData`
- `RenderedGroup`

Stage 1 입력 준비에서 아래 인덱스를 만든다.

- render id -> `RenderedGroup`
- source id -> parent rendered group 후보들
- TextFrame id -> owning shell 후보들
- parent shell -> descendant visual 후보들
- parent shell -> owned child TextFrame들

판정 기준은 원본 정보만 사용한다.

- `RenderedGroup.sourceObjectIds`
- `RenderedGroup.childIds`
- `RenderedGroup.editableTextFrameIds`
- page index
- original layer/z/placement
- group parent/child 관계

금지:

- 글자 수, 박스 크기, 특정 문구, 특정 페이지 번호
- PNG alpha/픽셀 분석
- 후속 phase의 handled set

### 10.3 3단계: Parent Text Shell 선택

대상:

- `OwnershipPlanner`

text-hidden 복합 그룹 중 editable TF를 가진 후보는 parent shell 후보가 된다.

선택 규칙:

1. 같은 source bundle에서 editable TF들을 가장 많이 포함하는 후보를 우선한다.
2. source containment가 더 큰 후보를 우선한다.
3. bounds가 descendant visual들을 포함하는 후보를 우선한다.
4. 원본 layer/z/placement가 더 직접적인 후보를 우선한다.

선택 결과:

- parent plan: `visualAction=PLACE_TEXT_SHELL`
- parent plan: `textAction=DROP_TEXT` 또는 `HIDDEN_SEMANTIC`
- parent plan: `ownedTextFrameIds=[...]`
- parent plan: `descendantVisualObjectIds=[...]`
- child TF plan: `textAction=OWNED_BY_HWPX_TEXT`, `visualAction=DROP_VISUAL`
- descendant visual plan: `visualAction=DROP_VISUAL`, reason=`owned_by_parent_text_shell`

중요:

- parent shell이 텍스트를 소유하지 않는다.
- child TF는 parent shell 내부 drawText로 재생성하지 않는다.
- descendant PNG 조각은 parent shell과 동시에 보이지 않는다.

### 10.4 4단계: Text Builder 고정

대상:

- `phase2/FramePlacer.java`
- `phase3/StoryConverter.java`
- `phase3/InlineFrameHandler.java`

실행 규칙:

- `ownedTextFrameIds`의 TF는 각각 HWPX 텍스트로 배치한다.
- 원본 inline/floating은 plan의 `placement`를 따른다.
- shell이 있다는 이유로 TF bounds를 다시 계산하거나 merge하지 않는다.
- shell 위의 TF에는 outline/fill을 새로 만들지 않는다.
- drawText는 마지막 fallback이며, shell+TF 정책에서는 텍스트 재생성 용도로 쓰지 않는다.

삭제/대체 대상:

- shell 존재 여부로 TF 폭/높이를 후속 보정하는 코드
- inline shell을 floating으로 바꾸는 코드
- TF를 sibling shell 기준으로 확장/축소하는 코드
- 원본 thread가 아닌 텍스트 merge 코드

### 10.5 5단계: Visual Builder 고정

대상:

- `stage3/VisualBuilder.java`
- `stage3/VisualPlacementExecutor.java`
- `phase6/BackgroundInjector.java`

실행 규칙:

- `PLACE_TEXT_SHELL`은 parent shell visual만 배치한다.
- `DROP_VISUAL` descendant는 어떤 executor에서도 되살리지 않는다.
- `PLACE_TEXT_SHELL`은 owned TF보다 뒤에 배치한다.
- Java 단계에서 큰 shell PNG를 child source 기준으로 crop하지 않는다.
- Java 단계에서 여러 조각 PNG를 합성하지 않는다.

삭제/대체 대상:

- Phase 6/7의 중복 suppression bridge
- executor-local handled/covered set
- plan 이후 `textOwner=hwpx_tf` 여부로 PNG 배치 여부를 재판정하는 코드
- `PLACE_TEXT_SHELL`을 imageFill TextFrame + 내부 drawText로 실행하는 경로

### 10.6 6단계: Layer/Z 적용 정리

대상:

- `stage3/VisualZOrderPlanner.java`
- `stage3/VisualPlacementExecutor.java`
- `OwnershipPlanner`

규칙:

- `BACKGROUND`은 HWPX `BEHIND_TEXT`.
- `LABEL_BACKDROP`, `CONTAINER_BACKDROP`, `TEXT_CARD_BACKDROP`은 owned TF 뒤.
- `CONTAINER_OUTLINE`, `FOREGROUND_MASK`는 필요할 때만 text 앞.
- 원본 z/layer가 있으면 추적한다.
- HWPX 평면 차이 때문에 원본 z만으로 최종 앞뒤를 결정하지 않는다.

### 10.7 7단계: Validator strict invariant

대상:

- `ownership/OwnershipPlanValidator.java`

추가 검증:

- parent `PLACE_TEXT_SHELL`의 `descendantVisualObjectIds`가 visible output을 가지면 경고/실패.
- parent `PLACE_TEXT_SHELL`의 `ownedTextFrameIds`가 HWPX TF로 배치되지 않으면 경고/실패.
- child TF가 `OWNED_BY_HWPX_TEXT`와 `OWNED_BY_PNG`를 동시에 가지면 경고/실패.
- 같은 `visualSourceObjectIds`가 두 visible visual에 쓰이면 경고/실패.
- 같은 source bundle이 inline/floating으로 동시에 보이면 경고/실패.
- `sourceObjectIds`가 비정상적으로 축소된 parent shell은 경고/실패.

Validator는 보정하지 않는다. 실패 이유만 기록한다.

### 10.8 8단계: 회귀 실행 순서

먼저 작은 페이지 세트로 ownership JSON을 확인하고, 이후 전체 문서를 변환한다.

1. 첫 페이지 상단 `소단원 정리`: 큰 shell 1개 + TF 2개, descendant PNG 0개 visible.
2. 171 라운드 박스: 추출된 shell이 있으면 TF에 outline/fill 재생성 없음.
3. 174/177 `구절풀이`: shell과 TF가 같은 inline/floating plan을 유지하고 shell이 TF를 덮지 않음.
4. 179 세로쓰기/글자 크기: TF 텍스트 속성은 HWPX run/TF 속성으로 보존.
5. 180 활동 안내: shell + TF 누락 없음.
6. 전체 문서 변환: Stage 4 invariant warning이 새로 늘지 않아야 함.

확인 명령:

- `mvn -pl converter -am -DskipTests package`
- 대상 문서 page subset 변환
- 전체 문서 변환
- ownership plan JSON diff
- HWPX 결과 육안 확인
