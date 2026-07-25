# SPEC-075: 그래픽+짧은텍스트 버튼/배지 그룹 통PNG

> 상태: **구현 완료, 회귀 PASS**. 2026-07-25.
> 사례: 영어 u1 p16 YES/NO 버튼 — 글자가 안 보이고 줄바꿈됨.

## 문제

YES/NO·T/F·숫자 배지처럼 **짧은 텍스트프레임 여럿 + 그래픽(배경 박스·구분선)** 이 한
그룹으로 묶인 버튼형 UI 를, 편집 셸(`inline_editable_text_shell_composite`)로 재현하면
좌우/격자 배치가 무너진다. 영어 u1 p16 의 YES/NO 는 글자가 안 보이거나 좁은 셀 안에서
줄바꿈돼 깨졌다.

원본 구조(YES/NO, group 16661):
- Rectangle 16665 — 둥근 배경 박스(그룹 전체 폭)
- GraphicLine 16666 — 중앙 세로 구분선
- TextFrame 16709 "YES" (좌측 칸) + TextFrame 16687 "NO" (우측 칸)

두 칸이 정확히 맞닿는 2-셀 버튼이라, 각 TF 를 독립 편집 프레임으로 풀면 배치·크기가
어긋난다. 한글에서 이런 그리드 버튼을 편집 텍스트로 재현할 방법이 없다.

## 목표

이런 버튼/배지 그룹은 **그룹째 통짜 PNG 로 굽는다**(편집성 포기). 원본 조판을 그대로
이미지化해 시각 충실도를 확보한다. 단, 실질 편집표(스케줄표·단어형성표 등)까지 삼키면
안 된다.

## 해결 방안

`object_plans.jsx` 의 번들 정규화(`_normalizeObjectPlanBundle`)에 새 판정을 추가한다.
기존 로직은 손대지 않고 **editable-shell 브랜치보다 앞**에 게이트를 넣어 가로챈다.

### 판정 조건 (`_objectPlanBundleIsGraphicShortTextButtonGroup`)

`pass.inline_objects` 번들의 소스 트리에서:
1. **짧은 텍스트프레임(3자 이하) 2개 이상**
2. **그래픽 객체**(Rectangle/Polygon/Oval/GraphicLine/Image) 존재
3. **긴 텍스트프레임(4자 이상)이 3개 미만** — 3개 이상이면 실질 편집표로 보고 제외

3번 가드가 핵심이다. 편집 발문·표(page5 STEP2 발문표, page9 스케줄표, page13
단어형성표)는 긴 TF 가 다수라 걸러진다. YES/NO·배지·드롭캡은 긴 TF 0~2개라 통과한다.

### 번들 생성 (`_graphicShortTextButtonGroupCompletePngBundle`)

테이블셀 통PNG(`_tableCellInlineComplexFormCompletePngBundle`)와 동일한 통PNG 골격:
- `materialization = COMPLETE_PNG`
- `textAction = OWNED_BY_PNG` (텍스트를 PNG 에 구움)
- `visualAction = PLACE_INLINE_PNG`
- `hiddenTextFrameIds = []`, `hiddenVisualSourceObjectIds = []` (숨김 없음 — 그룹째 export)
- `ownershipSlot = CONTENT_VISUAL_SLOT`

slotRole/reason 만 `graphic_short_text_button_group_complete_png` 로 구분.

## 수정 파일

1. `scripts/indd/object_plans.jsx`
   - `_objectPlanBundleIsGraphicShortTextButtonGroup` 판정 추가
   - `_objectPlanTextFrameCharCount` 헬퍼 추가(공백·마커 제외 글자 수)
   - `_graphicShortTextButtonGroupCompletePngBundle` 번들 함수 추가
   - `_normalizeObjectPlanBundle` 디스패처에 editable-shell 브랜치 앞 게이트 추가

## 검증

- [x] 빌드/문법: `node --check` PASS
- [x] 클린 재추출(영어 u1 p10-29, 220dpi): 새 slotRole plan **정확히 10개** 생성.
      전부 YES/NO 버튼(group 16661 등, src=7). page6×3, page13×2, page16×2, page17×3.
      표·발문은 하나도 안 걸림
- [x] PNG 육안: inline_16661.png 등에 둥근 박스+세로 구분선+"YES"/"NO" 글자 온전
- [x] 회귀: 내 변경 stash 한 baseline(동일 코드 시점) 재생성 후 순수 diff — **5건 전부
      의도한 변화**:
  - `picCount 345→355` (+10, YES/NO 통PNG 추가)
  - `표 수 변화 [1x1|YES NO] 10→0` (편집 셸이 만들던 1x1 표 소멸)
  - `이미지 크기버킷 4x1 4→14` (+10, 그 PNG)
  - `paragraphs 681→671` (-10, 편집 문단 흡수)
  - `visibleChars 11969→11919` (-50 = YES 3자+NO 2자 × 10)
  - object-plan 레벨: 10개 root 가 baseline 에서 정확히 `inline_editable_text_shell_composite`
    였고 1:1 로 통PNG 전환됨. 다른 번들 무변경
- [!] 골든(test-data/golden/영어u1-p010-029.json)은 gitRev 3528eb24=PR#136 시점이라
      SPEC-072/073/074 머지분이 빠진 **stale** 상태. 직접 비교 시 picCount 100→355 등
      432건 오염 diff — 이 SPEC 과 무관. **골든 갱신은 별건**(이 브랜치 머지 후 최신
      코드로 재생성 필요)

## 주의사항

- **골든이 stale 하면 diff 가 전부 오염된다.** 영어 u1 골든은 3528eb24(#136) 시점이라
  이후 #144(SPEC-072)·#146(SPEC-073)·#147(SPEC-074) 머지분이 반영 안 됨. SPEC-074 가
  누락 이미지 렌더를 살려 picCount 가 정상적으로 +되는데, 이걸 이 SPEC 의 회귀로 오인하면
  안 된다. 반드시 **같은 코드 시점 baseline** 을 재생성해 비교할 것
- 긴 TF 임계값 3은 관측 기반이다(버튼/배지는 긴 TF 0~2, 표는 3+). 다른 교과서에서
  임계 근처 케이스가 나오면 재조정 필요
