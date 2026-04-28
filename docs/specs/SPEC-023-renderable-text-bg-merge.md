# SPEC-023: 외곽선 텍스트 배지의 라운드 사각형 배경 병합

## 문제

`exportRenderedTextFrames` Pass 2 의 `renderable` TextFrame 처리 경로에서, 텍스트가 외곽선만 있고
배경 라운드 사각형은 별개의 PageItem 으로 존재하는 경우 PNG 에 텍스트만 캡처됨.

### 재현 케이스 — 중3영어 1단원 p11 (Spread_u40a)

```
Spread_u40a (top-level)
├─ Rectangle u490   FillColor=C70 M20  CornerRadius=17  bounds=[118,286]~[258,306]   ← Big Idea / Everyday Communication 배경
├─ Rectangle u491   FillColor=C23 M80  CornerRadius=56  bounds=[280,286]~[395,306]   ← Read 배경
├─ Rectangle u492   FillColor=C80 Y100 CornerRadius=17  bounds=[420,286]~[535,306]   ← Write On 배경
├─ TextFrame  u4a6  CornerRadius=17.86  text="Everyday Communication" (외곽선)
├─ TextFrame  u4bc  CornerRadius=17.86  text="Read" (외곽선)
└─ TextFrame  u4d2  CornerRadius=17.86  text="Write On" (외곽선)
```

- TextFrame `u4d2` 단독 export → `frame_1234.png` 에 외곽선 텍스트만 포함, 초록 라운드 배경 누락
- 시각적으로 배지(라운드 + 외곽선 텍스트)인데 추출 결과는 텍스트 PNG 만 나옴 → 본문 흐름에 PNG 배치 시 단순 외곽선만 보임

## 목표

- `renderable` 로 분류된 TextFrame 이 같은 페이지의 배경 도형(Rectangle/Polygon/Oval) 위에 놓여 있으면
  배경 도형 + TextFrame 을 한 PNG 로 병합 추출
- p11 "Everyday Communication" / "Read" / "Write On" 배지가 라운드 사각형 + 외곽선 텍스트로 합쳐짐
- 단독 외곽선 텍스트(배경 없는 케이스)는 기존 동작 유지

## 해결 방안

### 감지 — `findRenderableTextBackground(textFrame, candidates)`

후보 자격:
1. **타입**: `Rectangle | Polygon | Oval`
2. **fill 보유**: `fillColor.name !== "None" && !== "[None]"` (보이는 배경)
3. **같은 페이지**: `parentPage.id` 일치
4. **부모 영역**: `parent` 가 `Spread/Page/MasterSpread` 이거나 TextFrame 의 조상 중 하나
5. **위치 포함**: 후보의 visibleBounds 가 TextFrame visibleBounds 의 80% 이상 포함 (단순 contains)
6. **크기 제한**: 후보 면적 ≤ TextFrame 면적 × 6 (페이지 전면 배경 제외)
7. **z-order**: 후보가 TextFrame 보다 뒤(allPageItems index 큰 쪽)

매칭 후 SPEC-022 의 duplicate → 임시 그룹 → export → remove 패턴 재사용.
배경 도형은 `badgeGroupChildIds` 에 등록 → page_bg 에서 자동 숨김.

### 수정 위치

`scripts/extract_indd.jsx` Pass 2 (line 884~) — `classifyTextFrame === "renderable"` 분기 직후
배경 후보 검색 → 매칭 시 합성 PNG, 미매칭 시 기존 단독 export.

## 수정 파일

| 파일 | 변경 내용 |
|------|----------|
| `scripts/extract_indd.jsx` | `findRenderableTextBackground()` 추가, Pass 2 합성 분기 |

## 구현 메모

- 초기 시도: `bgItem.duplicate() + item.duplicate() → groups.add() → exportFile()` 으로 한 PNG 합성.
  - Everyday Communication, Write On 은 정상 합성 (bg + 외곽선 텍스트).
  - Read 만 합성 PNG 에 텍스트가 누락되는 현상 발생 (이유 불명, InDesign duplicate/group 동작상 비결정).
- 최종 채택: **bg 와 TF 를 별도 PNG 로 추출하고 z-order 로 겹침** (`renderedFrames` 에 두 entry).
  - bg PNG 의 `zOrder` 는 allItems 에서 bg 의 실제 index → Phase7 역매핑 시 TF 뒤로 안전하게 배치.
  - bg 도형은 `framesToHide` (exportPageBackgrounds) + `badgeGroupChildIds` 양쪽에 등록 → page_bg 와 중복 안 됨.
- 처음에는 `bg width / tf width` 비율 필터 도입을 검토했으나, 별도 PNG 방식에서는 비율과 무관하게 동작하므로 도입하지 않음.

## 검증

- [x] 빌드 성공
- [x] p11 "Everyday Communication" / "Read" / "Write On" 배지 = 라운드 배경 + 외곽선 텍스트 (사용자 육안 확인)
- [x] 페이지 배경 PNG 에서 라운드 사각형 중복 제거됨

## 상태: 구현 완료
