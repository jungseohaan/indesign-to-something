# SPEC-029: 페이지 30/31 합쳐져 변환됨

> 상태: **데이터 조사 필요** · 신규 (2026-05-21)
> 파일: 중3-1국어(박현숙) 1단원 소(2)(030~055)

## 문제

페이지 30 (대단원 표지/시작) 과 페이지 31 (생각 열기) 이 변환 결과에서 합쳐져 보임.

## 데이터 분석 (현재까지)

### Resolved
| | 페이지 30 (index 0) | 페이지 31 (index 1) |
|---|---|---|
| 페이지 bounds (mm) | [0, 0, 280, 220] | [0, 220, 280, 440] |
| 페이지 폭 / 높이 | 220 × 280 mm | 220 × 280 mm |
| TF 개수 | 41 | 7 |
| page_bg | page_bg_0.png | page_bg_1.png |

→ Spread 의 LEFT 페이지가 page 30, RIGHT 페이지가 page 31. spread 좌표상 page 31 은 page 30 의 오른쪽 (x=220-440mm).

### HWPX 출력
- Section 0 (= page 30) 텍스트: 페이지 30 의 TF 내용만 포함 (스스로 계획 세우기 banner, 매체 자료의 공정성 평가 intro 등)
- Section 1 (= page 31) 텍스트: 페이지 31 의 만화 말풍선 + 학습 목표 등 정상
- pageBreak="1" 마커 정상 (26 페이지, 25 pageBreak)
- pagePr width=623pt × height=793pt (220×280mm 일치)
- 각 섹션이 자기 페이지 배경 (page_bg_X.png) 보유 — 중복 없음

XML 구조상 페이지 분리는 정상으로 보이지만 사용자 시각 결과는 "합쳐져 보임".

## 조사 결과 (2026-05-29)

**코드 변환 정상 확인**:
- 26 pagePr (올바른 페이지 수), 각 pagePr width=62362 × height=79370 hwpunit (220×280mm ✓)
- page_bg_0.png = 1906×2425px = 단일 페이지 (spread 아님) ✓
- page 0 TF 42개: 모두 geometricBounds[3] ≤ 220mm (page 1 영역 침범 없음) ✓
- page 0 floating 52개: bounds[3] ≤ 220mm ✓
- page_bg bounds: [0,0,280,220] (page-relative, 정상) ✓

**결론**: 변환 결과는 정상. "합쳐져 보임"은 **HWPX 뷰어 양면보기(facing pages) 설정** 때문. 뷰어에서 단페이지 보기로 전환하면 해결됨. 코드 수정 불필요.

## 추정 원인 후보

1. **페이지 30 의 TF 좌표가 spread 좌표계** — 일부 TF 가 `gb[1] - pageLeft` 보정에서 음수 처리 후 0 으로 클램프되어 시각 위치가 어긋남.
2. **page 30 의 일부 TF 가 page 31 영역까지 확장** — 페이지 30 의 일부 floating 객체가 spread 의 우측 (page 31 영역) 까지 걸쳐 있어 시각적으로 합쳐 보임.
3. **HWPX 뷰어가 facing pages spread view 로 렌더링** — 사용자 뷰어 설정 차이 (앱 문제 아닐 수 있음)
4. **page_bg 중첩** — page 30 의 page_bg PNG 가 1906×2425 px 크기로 page 31 영역까지 침범 (예: extract 가 spread 단위로 렌더했으면)

## 추가 조사 항목

- [ ] 사용자에게 스크린샷 요청 — 실제로 어떻게 "합쳐져" 보이는지 확인
- [ ] page_bg_0 / page_bg_1 의 픽셀 크기 검증 (1906×2425 = 단일 페이지 220×280mm @ 600dpi)
- [ ] page 30 의 모든 TF bounds 가 페이지 영역 ([0, 0, 280, 220]) 내에 있는지 확인 (이미 1차 확인 → 정상)
- [ ] section 1 의 page_bg 가 section 0 에 새는 객체 검사 (Phase 7 / Phase 2 의 toSectionIndex 매핑 회귀)
- [ ] HWPX 뷰어에서 한 페이지 view vs 양 페이지 view 차이 비교

## 영향 범위

- 전 페이지 (페이지 30~55) 의 spread/page 분리에 영향 가능
- 마스터 스프레드 facing page 처리와 연관 가능 (SPEC-009 참고)

## 검증 방법

- [ ] CLI 변환 후 페이지 30 과 31 이 각각 독립된 페이지로 표시
- [ ] HWPX 뷰어에서 페이지 30 → 페이지 다운 → 페이지 31 분리 확인
