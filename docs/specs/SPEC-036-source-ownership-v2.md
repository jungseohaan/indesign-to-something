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
