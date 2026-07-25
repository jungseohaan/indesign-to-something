# SPEC-076 — 인라인 통PNG 그룹 복제 시 비텍스트 시각 자식 유실 (p25 탐구 배지)

## 문제

과학 U1 p25(local 18) "정리해 볼까 1." 문단의 **탐구1 / 탐구2 배지**가 통PNG(COMPLETE_PNG)에서
숫자 "1" / "2" 만 남고 "탐구" 글자·배경 알약이 통째로 사라진다.

배지 구조(그룹 132669):
- `132671` Rectangle — 배경 알약, **C19 M72 Y0 K38 진보라**
- `132672` Oval — 숫자 뒤 원, **FillTint=0 (투명 컨테이너)**
- `132696` TextFrame "탐구" — **Color/Paper = 흰색** (알약 위 흰 글자)
- `132673` TextFrame "1" — **C19 M72 Y0 K38 진보라**

plan 은 정상이다:
- 슬롯 `table_cell_inline_complex_form_complete_png` → `materialization=COMPLETE_PNG`,
  `exportSourceObjectIds=[132669]`(그룹 루트), `hiddenTextFrameIds=[]`(아무것도 안 숨김),
  `ownedTextFrameIds=[132673,132696]`.

그런데 렌더된 `inline_132669.png` 는 345바이트에 오른쪽 진보라 "1" 만 있고 왼쪽(알약+탐구)이 빈다.

## 원인

`scripts/indd/render_inline_objects.jsx` 의 `_duplicateNestedCompletePngForExport`(그룹 복제 후 export)가
복제 대상 id 를 `_completePngDuplicateSourceIds` 로 정하는데, 이 함수는

```
add(exportSourceObjectIds)          // [132669] — 그룹 루트
if COMPLETE_PNG && OWNED_BY_PNG:
    add(ownedTextFrameIds)          // [132673, 132696]
    add(editableTextFrameIds)       // [132673, 132696]
```

로 **그룹 루트 + 텍스트프레임만** 모은다. 복제 루프는 `item.id`(그룹 루트)를 skip 하므로
실제 복제·재그룹 대상은 **{ "1" TF, "탐구" TF } 두 개뿐** — 배경 알약(132671)·오벌(132672)
같은 **비텍스트 시각 자식이 복제 집합에 아예 없다.**

결과 export 그룹 = {흰 "탐구" TF + 진보라 "1" TF}. 배경 알약이 없으니
- 흰 "탐구" 는 흰/투명 바탕에 묻혀 실종,
- 진보라 "1" 만 남는다.

두 배지가 색·구조가 같아 동일하게 깨진다. (투명배경 export 는 부차적 요인 — 알약만 있으면
흰 텍스트는 알약 위에서 정상 표시된다. 근본 원인은 시각 자식 복제 누락.)

## 목표

그룹 루트 하나가 통PNG export 대상일 때 그룹째 복제해 **모든 자식(알약·원·텍스트)** 이
원래 배치 그대로 PNG 에 구워지게 한다.

## 해결 방안

`_duplicateNestedCompletePngForExport` 에서, export 대상이 그룹 루트 자신뿐일 때
(`exportSourceObjectIds === [item.id]` 이고 item 이 Group) 자식 재그룹 대신
`item.duplicate(spread)` 로 그룹째 복제한다.

- 숨김 대상(`hiddenVisualSourceObjectIds` / out-of-scope 자식)은 이 함수 호출 **전**
  원본에 선적용(`_hideItemsForExport`)되므로 그룹째 복제해도 숨김이 유지된다.
- 통짜 그룹 복제는 line 1230 폴백(`copies.length===0`)과 동일 의미 — 새 경로가 아니라
  잘못 좁혀진 경로를 우회하는 것.
- CLAUDE.md 트랩("export 는 그룹 루트 id 하나만 — 자식 여러 개를 주면 캔버스가 부푼다")과
  일치: 루트 하나면 부풀지 않는다.

## 수정 파일

1. `scripts/indd/render_inline_objects.jsx` — `_duplicateNestedCompletePngForExport`:
   `if (!spread) return null;` 직후, 그룹 루트 단독 export 시 `item.duplicate(spread)` 조기 반환

## 검증

- [x] 재추출(p25/local 18, `--content-mode full`) → `inline_132669.png`(345B→874B) /
      `inline_132722.png`(→952B) 가 알약+탐구+숫자 온전히 포함
- [x] PNG 육안 확인 — "탐구 ①" / "탐구 ②" 진보라 알약 + 흰 탐구 + 원형 숫자 정상 (에이전트 확인)
- [ ] 한글에서 배지 인라인 배치 최종 육안 확인 (사용자)
- [x] 골든 게이트 실행 — 8-49 전체 재추출+변환 완료. **단, 골든(과학u1-p008-049.json)이
      7/23 생성분으로 stale**: 이후 머지된 SPEC-074/075(#147/#149, 통PNG 배지 다수)·#153
      (placed media 페이지평면 분리)·화학식 작업이 미반영 → 골든 diff 635건은 대부분 그 누적분.
      picCount 336→700(이미지 2배), 수식 `H_2O`→`rm H_2O` 등이 그 증거.
- [x] **내 변경 격리 검증**: 8-49 전체에서 새 경로(그룹루트 단독 COMPLETE_PNG Group) 대상 36개.
      대표 출력 육안 확인 — 탐구①/②(132669/132722), 섹션 제목바(79389 "아이오딘화…",
      80119 "달걀 껍데기…"), 탐구 배지(144932) 모두 정상. 회귀 없음, 오히려 구 cherry-pick에서
      시각자식 누락됐을 배지들이 함께 교정됨.
- [ ] (별건) 골든 재생성 — SPEC-074/075/#153 누락 반영 필요. SPEC-076 단독 변화가 아니라
      3개 머지분 포함이므로 전용 단계에서 합의된 config 로 갱신 권장.

## 회귀 분석

SPEC-075 버튼 그룹 통PNG(`_graphicShortTextButtonGroupCompletePngBundle`)도 같은
`_duplicateNestedCompletePngForExport` 경로를 쓰지만, 그 경우 `item`(스토리 앵커를 든 조상)이
그룹 루트와 **다르므로** 새 조건(`exportSourceObjectIds===[item.id]`)에 걸리지 않고 기존 동작을
유지한다. 탐구 배지는 `item` 자체가 그룹 루트라 새 경로를 탄다. 수정은 이 케이스에만 국한된다.
