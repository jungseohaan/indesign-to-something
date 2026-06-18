# SPEC-036: Source Ownership Pipeline v2

> 상태: Active / Canonical
> 목표: 단순한 ownership 결정으로 IDML 원본 비주얼과 HWPX 편집성을 동시에 보존한다.

이 문서가 source ownership의 최종 기준이다. `SPEC-035`와 충돌하면 이 문서를 따른다.
`SPEC-035`는 이관 기록과 배경 설명으로만 사용한다.

## 1. 원칙

1. Stage 1만 source object의 text/visual owner, placement, layer를 결정한다.
2. 후속 단계는 `ObjectPlan`을 실행만 한다.
3. editable text는 HWPX가 소유한다.
4. textless shell은 원본 InDesign 추출물만 쓴다.
5. 같은 source bundle의 visible visual owner는 하나다.
6. 원본 layer/z/inline/floating 정보는 plan과 결과에 추적한다.

## 2. 텍스트

- 본문, 제목, 라벨, 설명, 문항, 출처는 HWPX 텍스트다.
- PNG가 텍스트를 소유할 수 있는 경우는 atomic marker뿐이다.
- atomic marker는 명시적 표식 패턴만 허용한다: `가`, `ㄱ`, `1`, `01`, 체크/선택지 표식.
- atomic object는 complete PNG 여부나 leaf node 여부가 아니라 원본 source bundle의
  ownership root로 먼저 식별한다.
  marker bundle은 `marker shell root + label TextFrame + 그 자손`으로 닫혀 있어야 하고,
  graphic-only bundle은 하나의 원본 ownership root Group과 그 자손으로 닫혀 있어야 한다.
- ownership root Group은 leaf Group일 필요가 없다. 하위 도형/텍스트 조각이 함께 있어야
  하나의 원본 시각 단위가 되는 경우에는 비-leaf Group도 atomic object가 될 수 있다.
  예: 벡터 글자, 외곽선, 보조선을 포함한 로고/라벨 그룹.
- extractor는 이런 graphic-only ownership root를 `reason=graphic_ownership_root`,
  `atomicObjectKind=GRAPHIC_ONLY`로 기록한다.
- 반대로 자식 중 독립 atomic 후보가 여러 개 있고 서로 다른 역할의 시각 단위이면,
  부모 Group은 atomic object가 아니라 container다. container render는 후보로 만들 수
  있지만 최종 visible owner가 되려면 별도 composite/background 정책을 통과해야 한다.
- extractor는 닫힌 atomic bundle을 찾으면 `atomicObjectKind`,
  `atomicSourceObjectIds`, `atomicOwnedTextFrameIds`, `atomicVisualSourceObjectIds`를
  resolved metadata에 기록한다. Java는 이 메타데이터를 우선 검증하고, 새 atomic
  bundle을 후속 단계에서 만들지 않는다.
- 닫힌 atomic bundle과 정확히 맞는 render만 atomic object의 visual owner가 될 수 있다.
  table carrier, parent TextFrame, parent table, unrelated group source가 섞인 render는 atomic object owner가 아니다.
- atomic object의 표현 방식은 둘 중 하나다.
  - `COMPLETE_PNG`: `textAction=OWNED_BY_PNG`, `visualAction=PLACE_INLINE_PNG|PLACE_FLOATING_PNG`
  - `TEXTLESS_SHELL_WITH_TF`: `textAction=OWNED_BY_HWPX_TEXT`, `visualAction=PLACE_TEXT_SHELL`
  - `GRAPHIC_ONLY`: `textAction=DROP_TEXT`, `visualAction=PLACE_INLINE_PNG|PLACE_FLOATING_PNG`
- `TEXTLESS_SHELL_WITH_TF`의 shell은 extractor가 만든 textless PNG/vector만 사용하고,
  label TF는 HWPX text로 배치한다.
- inline `TEXTLESS_SHELL_WITH_TF`는 `pageItems`에 원본 Group bridge가 없어도
  `renderedFloatingItems`의 atomic metadata와 ObjectPlan이 일치하면 실행 대상이다.
  실행 단계는 IDML tree를 재판정하지 않고, extractor가 기록한
  `atomicOwnedTextFrameIds`/`editableTextFrameIds`를 따라 shell+TF를 한 인라인 객체로 만든다.
- table cell 안의 inline `TEXTLESS_SHELL_WITH_TF`도 같은 규칙을 따른다.
  셀의 직접 텍스트가 ORC뿐이고 child TextFrame story가 HWPX text owner여도,
  해당 ORC에 `INLINE + PLACE_TEXT_SHELL` plan이 있으면 셀 단락 빌드를 중단하지 않는다.
  table cell early-return은 별도 floating/placed TF 중복을 막기 위한 것이며,
  plan이 있는 inline atomic shell+TF를 숨기는 근거가 될 수 없다.
- resolved story run은 `isInlineAnchor=true` 또는 `anchoredObjectId != null`이면 인라인
  anchor다. `anchoredObjectId`가 있는데 boolean flag가 비어 있는 추출 결과도 anchor로
  소비해야 하며, 이를 일반 text run처럼 무시하거나 병합하지 않는다.
- IDML story와 resolved story 중 `anchoredObjectId` 계약이 resolved에만 명확하면
  resolved story structure가 우선한다. 이때 후속 단계가 anchor 여부를 재판정하지 않는다.
- 텍스트를 임의로 합치지 않는다. 연결 TextFrame은 원본 thread만 따른다.

## 3. 비주얼

- `PLACE_TEXT_SHELL`: textless PNG/vector shell을 원본 placement에 맞게 배치한다.
  floating shell은 별도 visual로, inline shell은 하나의 inline carrier 안에서 실행한다.
- `PLACE_FLOATING_PNG`: 사진, 삽화, 차트, 완성형 콘텐츠 PNG.
- `PLACE_INLINE_PNG`: 원본이 inline인 complete marker 또는 graphic-only atomic 객체.
- `ABSORB_TEXT_STYLE`: fill/stroke/underline처럼 HWPX 텍스트 속성 또는 1x1 table로 표현 가능한 장식.
- `DROP_VISUAL`: 같은 source의 더 정확한 owner가 있을 때만 사용한다.

## 4. Textless Shell

아래 조건을 만족하면 complete PNG가 아니라 textless shell이다.

- `visualOwner=indesign_png`
- `textOwner=hwpx_tf`
- `textHiddenBeforeExport=true` 또는 `containsText=false`
- `containsEditableText`가 true가 아니다.
- `editableTextFrameIds` 또는 source TextFrame이 HWPX text owner로 존재한다.
- 실제 추출 file이 있다.

실행 규칙:

- extractor가 source object/group을 복제한다.
- 복제본에서 HWPX가 소유할 TextFrame/table text만 제거한다.
- 복제본의 fill/stroke/corner/path/group clipping은 InDesign `exportFile()`로 그대로 PNG화한다.
- Java 변환기는 `ObjectPlan.file`의 textless shell PNG/vector를 `PLACE_TEXT_SHELL`로 배치만 한다.
- shell 위의 editable TextFrame은 각각 HWPX 텍스트로 배치한다.
- `PLACE_TEXT_SHELL`이 소유한 TF/table carrier는 텍스트만 배치한다. carrier의
  HWPX table border/fill, drawText outline/fill, wrapper rect는 모두 비활성화한다.
- `placement=INLINE`인 shell은 하나의 inline carrier로 실행한다.
  carrier는 `ObjectPlan.file`의 추출 shell PNG를 image brush로 쓰고,
  텍스트는 editable drawText/subList로 둔다.
- inline companion으로 승격된 shell plan은 textless shell 파일과 inline bounds를 가진다.
  `inline_*`가 editable text까지 구운 complete PNG이면 shell 파일로 쓰지 않는다.
  같은 source bundle의 `textHiddenBeforeExport=true` / `containsEditableText=false` 추출물을 shell 파일로 쓴다.
- 실행 단계는 `ObjectPlan.file/bounds`가 없으면 shell을 만들지 않는다.
  `RenderedGroup.file/bounds`, table/TF bounds, fill/stroke/corner 값으로 대체하지 않는다.
- inline text shell carrier는 `ObjectPlan.file`의 textless shell PNG에서 투명/종이색 여백만 crop할 수 있다.
  이 crop은 ownership 재판정이 아니라 HWPX inline carrier 안에 넣기 위한 image preparation이다.
  crop 후에도 carrier 크기와 placement는 `ObjectPlan.bounds`를 따른다.
- HWPX imgBrush가 PNG alpha를 검정으로 렌더링하는 경우, crop된 shell PNG만 white matte로 인코딩할 수 있다.
  이 matte는 textless shell 픽셀 보존용이며 complete text PNG 생성이나 file/bounds fallback이 아니다.

금지:

- Java 단계에서 `ResolvedPageItem`의 fill/stroke/corner 값을 읽어 shell PNG를 새로 그리기
- table/TF bounds를 보고 rounded rectangle, pill, label box를 임의 생성하기
- HWPX drawText/shape/table border를 shell 대체물로 만들기
- 추출된 shell이 있는데 TF outline/fill을 다시 그리기
- shell PNG 안에 editable text를 다시 굽기
- inline shell PNG를 흰색 불투명 캔버스로 flatten해서 원본 크롭/알파를 잃게 하기
- `ObjectPlan.file` 대신 같은 source id의 다른 render file을 fallback으로 사용하기
- `PLACE_TEXT_SHELL` 실행 중 HWPX fill/stroke/outline을 추가해 shell을 보정하기
- editable text가 남아 있는 `inline_*` complete PNG를 `PLACE_TEXT_SHELL`의 shell 이미지로 사용하기

원본 shell 추출이 실패하면 변환 단계에서 비슷한 shell을 만들지 않는다.
실패를 드러내고 extractor/ownership metadata를 수정한다.

### 4.1 Shell Placement

`page_object`로 추출된 textless shell은 기본적으로 floating shell이다.
inline으로 배치할 수 있는 것은 render candidate 자체가 `inline_object`인 경우뿐이다.

inline 인정 조건:

- render candidate 자체가 `inline_object`이다.
- 또는 같은 source bundle의 `page_object` textless shell을 실행하기 위한 같은 dom id의
  `inline_object` companion이다. 이 경우 visible owner는 inline companion이고,
  page_object shell plan은 drop된다.

inline 불인정 조건:

- `sourceObjectIds` 안의 descendant/child source 중 일부가 inline이다.
- child TextFrame이 inline source를 가졌거나 inline object 안에 포함된 적이 있다.
- 같은 bundle 안에 inline fragment가 섞여 있다.
- 같은 dom id의 inline companion이 있다.

즉, descendant의 inline 흔적은 parent `page_object` shell의 placement를 inline으로
바꾸는 근거가 될 수 없다. editable TF를 별도 HWPX 텍스트로 소유하는 page_object
shell은 `placement=FLOATING`이다.
후속 단계는 이 결정을 뒤집거나 inline executor로 라우팅하지 않는다.

### 4.2 Table-Only Carrier

TextFrame 안의 실제 편집 텍스트가 IDML table에 있고 `TextFrame.contents`에는 ORC만 남는 경우도
같은 규칙을 따른다. table text는 HWPX table/text가 소유하고, parent shell은
`PLACE_TEXT_SHELL`로 배치한다. table border/fill은 원본 shell을 대체하거나 중복 생성하지 않는다.

본문 story의 table marker가 wrapper table을 만들고, 그 셀 안의 nested TextFrame/table이
실제 표 내용을 소유하는 경우에는 `anchored_table` plan 하나가 wrapper와 nested table을
동시에 소유한다. 이때 nested table TextFrame은 별도 `text_frame:table_only`
`PLACE_TABLE_STYLE` plan을 만들지 않는다. 같은 nested table source가 inline anchored table과
page-level table로 동시에 배치되면 정책 위반이다.

## 5. 그룹과 중복

Group은 leaf 여부가 아니라 source ownership root 여부로 판단한다.

- atomic ownership root: 자손이 함께 있어야 하나의 시각 단위가 되는 Group.
  이 Group의 자손은 root visual에 흡수되고 별도 visible output을 만들지 않는다.
- container Group: 독립 atomic root 또는 독립 visual unit을 여러 개 담는 Group.
  이 Group은 기본적으로 visible owner가 아니며, 자식 atomic root들이 각각 owner가 된다.
- composite/background Group: 표지 배경처럼 container 전체가 하나의 배경 shell로
  의미를 가질 때만 별도 정책으로 visible owner가 될 수 있다. 이 경우에도 자식 TF는
  HWPX 텍스트로 소유하고, 자식 visual의 중복 배치는 Stage 1에서 닫는다.

editable TF가 포함된 atomic ownership root는 두 ownership channel로 나눈다.

- parent visual group: textless shell만 소유한다.
- child TextFrame/table: HWPX text/table만 소유한다.
- descendant visual fragment: parent shell이 실제 포함하면 `DROP_VISUAL`, 포함하지 못하면 별도 visual owner로 남긴다.

중복 차단은 source 정보를 자르지 않고 `visualSourceObjectIds`, `ownedTextFrameIds`,
`descendantVisualObjectIds`로 구분한다.

허용되는 조합:

- text source: HWPX TF 한 개
- visual source: PNG/vector/table/style 중 한 개
- shell source: textless shell figure + 별도 text source의 HWPX TF

금지되는 조합:

- complete PNG + 같은 텍스트의 HWPX TF
- parent PNG + child PNG 동시 배치
- inline PNG + floating PNG 동시 배치
- floating shell을 imageFill TextFrame으로 만들고 그 안에 텍스트를 재생성
- inline shell을 page-level floating object로 승격

## 6. 레이어

원본 layer/z와 정책 layer를 분리한다.

- 원본: `sourceLayerId`, `sourceLayerName`, `sourceLayerIndex`, `zOrder`, `zOrderKnown`.
- 정책: `BACKGROUND`, `DECORATION`, `CONTENT`, `TEXT`.

정책 layer는 HWPX 배치 평면을 정하는 용도이고, 원본 layer/z는 추적과 검증의 근거다.
원본 z가 불명확할 때만 정책 layer로 보정한다.

## 7. Stage

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

## 8. 구현 제거 대상

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

## 9. 표현 우선순위

1. 본문 텍스트: HWPX paragraph/run 속성.
2. 편집 가능한 박스/표형 장식: 1x1 table 우선.
3. 원본 shell이 복잡하면 textless PNG/vector shell + HWPX TF.
4. `drawText`는 마지막 fallback이며 정책 판단에는 사용하지 않는다.

단, 1x1 table은 editable text carrier다. 원본에서 별도 shell이 추출된 경우
1x1 table border/fill은 shell을 대체하거나 중복 생성하면 안 된다.

## 10. Validator invariant

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
- `PLACE_TEXT_SHELL`이 Java-drawn synthetic shell이면 안 된다. extractor origin 또는 원본 vector source를 가져야 한다.
- table-only carrier shell은 `PLACE_TEXT_SHELL` parent와 `PLACE_TABLE_STYLE` carrier가 같은 source bundle으로 추적되어야 한다.

## 11. 개발 플랜

정책 반영은 source identity를 보존하면서 visible output slot만 단일화하는 방향으로 진행한다.

### 11.1 1단계: ObjectPlan 모델 확장

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

### 11.2 2단계: Source Bundle Index 생성

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

### 11.3 3단계: Parent Text Shell 선택

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
- child TF는 floating parent shell 내부 drawText로 재생성하지 않는다.
- inline companion shell은 하나의 inline carrier 안에서만 추출 shell image brush와 editable drawText를 함께 둘 수 있다.
- descendant PNG 조각은 parent shell과 동시에 보이지 않는다.

### 11.4 4단계: Text Builder 고정

대상:

- `phase2/FramePlacer.java`
- `phase3/StoryConverter.java`
- `phase3/InlineFrameHandler.java`

실행 규칙:

- `ownedTextFrameIds`의 TF는 각각 HWPX 텍스트로 배치한다.
- 원본 inline/floating은 plan의 `placement`를 따른다.
- shell이 있다는 이유로 TF bounds를 다시 계산하거나 merge하지 않는다.
- shell 위의 TF에는 outline/fill을 새로 만들지 않는다.
- drawText는 마지막 fallback이다. 단, `INLINE + PLACE_TEXT_SHELL`의 실행 carrier에서는
  floating 전환을 피하고 편집 가능한 인라인 객체를 유지하기 위해 editable drawText를 허용한다.

삭제/대체 대상:

- shell 존재 여부로 TF 폭/높이를 후속 보정하는 코드
- inline shell을 floating으로 바꾸는 코드
- TF를 sibling shell 기준으로 확장/축소하는 코드
- 원본 thread가 아닌 텍스트 merge 코드

### 11.5 5단계: Visual Builder 고정

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
- floating `PLACE_TEXT_SHELL`을 imageFill TextFrame + 내부 drawText로 실행하는 경로
- inline `PLACE_TEXT_SHELL`을 page-level floating으로 바꾸는 경로

### 11.6 6단계: Layer/Z 적용 정리

대상:

- `stage3/VisualZOrderPlanner.java`
- `stage3/VisualPlacementExecutor.java`
- `OwnershipPlanner`

규칙:

- `BACKGROUND`, `CONTAINER_BACKDROP`, `TEXT_CARD_BACKDROP`은 HWPX `BEHIND_TEXT`.
- `LABEL_BACKDROP`은 HWPX `IN_FRONT_OF_TEXT`의 낮은 zOrder에 둔다. HWPX에서 `BEHIND_TEXT`는 소유 TF 뒤가 아니라 본문/테이블 전체 뒤로 밀려 보이지 않을 수 있기 때문이다.
- `INLINE + PLACE_TEXT_SHELL`이고 원본 추출 PNG shell이 있는 경우, 실행은 1x1 table cell background가 아니라 inline `rect + drawText`로 한다. HWPX table background는 뷰어에서 shell이 보이지 않는 경우가 있으므로, 추출 shell과 editable text를 하나의 인라인 객체로 묶어 보존한다.
- `CONTAINER_OUTLINE`, `FOREGROUND_MASK`는 필요할 때만 text 앞.
- 원본 z/layer가 있으면 추적한다.
- HWPX 평면 차이 때문에 원본 z만으로 최종 앞뒤를 결정하지 않는다.

### 11.7 7단계: Validator strict invariant

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

### 11.8 8단계: 회귀 실행 순서

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
