# SPEC-070: 표에 담긴 텍스트가 PNG 로 구워지는 문제

> 상태: **구현 완료, 육안 확인 대기**. 2026-07-24.
> 사례: 영어 u1 p10 "Look Ahead" 흰 박스 — 본문 전체가 편집 텍스트 대신 PNG.

## 문제

흰 Look Ahead 박스의 셸 PNG 에 본문이 통째로 구워진다. `단원의 내용을 살펴보고`,
`Functions`, `I'm glad we have the new school garden.`, `Forms`, `Read` 가
전부 그림이다. Java 가 만드는 편집 텍스트와 겹쳐 중복이 되고, 그림 쪽 텍스트는
검색·편집이 불가능하다.

## 원인

**본문이 표(table) 안에 있다.** story 1605 는 문단이 아니라 12행 테이블 셀에 내용을
담고 있다. 그런데 `_textLengthOfItem` 은 `item.contents` 만 읽는데, 표가 든 프레임의
contents 는 표 앵커 제어문자뿐이라 **길이 0** 으로 나온다.

그 결과 프레임 1623 이 `hasText=false` / `textLength=0` 으로 기록되고,
"텍스트 있는 프레임" 만 모으는 `hiddenTextFrameIds` 에서 빠진다. export 시 숨길
대상이 아니므로 표 텍스트가 그대로 렌더된다.

실측: plan 의 `hiddenVisualSourceObjectIds` 에는 `[1601, 1623]` 둘 다 있는데,
export 가 실제로 참조하는 `hiddenTextFrameIds` 에는 `[1601]`(Look Ahead 배지 라벨,
10자)만 있었다.

CLAUDE.md 의 "표 전용 TF 는 hasText=false" 함정이 셸 export 경로에서 재발한 것이다.

## 해결 방안

### 1. `hasText` 가 표 셀 텍스트를 인정하도록

`storyHasVisibleTableCellText` 는 바로 위에서 이미 계산되고 있었으나 `hasText` 에
반영되지 않았다. 이를 반영해 표에 보이는 텍스트가 있으면 `hasText=true` 로 만든다.
`textLength` 는 원래 의미(직접 contents 길이)를 유지한다.

### 2. contentOpacity 숨김이 표 셀까지 지우도록

`hideOneTextFrameContent` 는 `tf.contentTransparencySettings.opacity = 0` 을 먼저
시도하고 성공하면 **즉시 return** 한다. 그런데 이 설정은 프레임 안 표에는 닿지
않는다 — 표는 자체 콘텐츠로 독립 렌더된다. 표 셀을 처리하는 코드가
`preferTextPaintOnly` 경로에 있었지만 조기 return 때문에 도달하지 못했다.

`_hideTableCellTextForExport` / `_restoreTableCellTextForExport` 를 추가해
contentOpacity 경로에서도 셀 텍스트 투명도를 0 으로 만들고 복원한다.

## 수정 파일

1. `scripts/indd/source_index.jsx` — `hasText` 에 `storyHasVisibleTableCellText` 반영
2. `scripts/indd/render_prep.jsx` — 표 셀 텍스트 숨김/복원 헬퍼 추가,
   contentOpacity 경로에 연결

## 검증

- [x] 영어 u1 p10 클린 재추출: 1623 이 `hasText=true` 로 기록, `ownedTextFrameIds`
      에 `[1601, 1623]` 포함
- [x] 셸 PNG 육안: 본문 텍스트 완전 소멸, 흰 박스 + 빨간 배지 그래픽만 남음
- [x] 최종 HWPX 에 `Functions`/`Forms`/`Read`/`단원의 내용` 편집 텍스트로 존재
- [ ] 영어 u1 p010-029 전체 회귀 — **`hasText` 소비처가 76곳이라 파급 확인 필요**
- [ ] 한글 육안 확인

## 주의사항

- **`hasText` 는 파급이 넓다** (소비처 76곳). 표가 든 프레임의 소유권 판정이 다른
  페이지에서 달라질 수 있으므로 회귀 확인 필수
- **프레임 contentOpacity 는 안쪽 표에 닿지 않는다.** 표는 자체 콘텐츠로 독립 렌더된다.
  텍스트를 숨기는 새 경로를 만들 때마다 표 셀을 따로 처리해야 한다
- 숨김 목록은 `hiddenVisualSourceObjectIds` 가 아니라 **`hiddenTextFrameIds`** 가
  export 에 쓰인다. 전자에만 넣으면 숨겨지지 않는다
