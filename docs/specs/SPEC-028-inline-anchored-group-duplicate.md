# SPEC-028: 인라인 앵커 Group 중복 렌더링

> 상태: **데이터 조사 단계** · 신규 (2026-05-21)
> 참고: SPEC-025

## 문제

페이지 46 (중3-1국어(박현숙) 1단원 소(2)) 의 `소단원 마무리 > 매체 자료의 공정성 평가 기준` 타이틀에서, 원형 숫자 ③ 가 **중복 렌더링**되고 "●"처럼 보이는 작은 잔존 PIC 가 본문 줄에 끼어들어 줄바꿈을 유발한다.

### 예상 vs 실제

| | 시각 |
|---|---|
| **원본 (InDesign)** | `③ 매체 자료의 공정성 평가 기준` — 한 줄 |
| **변환 결과 (현재)** | 큰 ③ 위 (line 1) + `● 매체 자료의 공정성 평가 기준` (line 2). "●" 위치에 작은 PIC 가 잔존 |

## 데이터 분석

| 객체 | ID | 위치 | 크기 | 출처 |
|------|----|------|------|------|
| 큰 ③ (floating) | `image161` ← `badge_11693.png` | x=492.76pt y=119.06pt (PAPER) | 42.52 × 42.52 pt | `renderedTextFrames` (`badge_group`, page 15) |
| 작은 ③ (inline) | inline PIC inside title `<hp:p paraPrIDRef="753">` | inline | 6.48 × 12.27 pt (비례 왜곡) | IDML Story `u2f92` inline anchored Group `u4e07` |

### IDML 구조

```xml
<ParagraphStyleRange AppliedParagraphStyle=".../06_소단원마 1제목">
  <CharacterStyleRange ...>
    <Group Self="u4e07">  <!-- 인라인 앵커 Group -->
      <Oval Self="u4e09" FillColor="Color/C=0 M=60 Y=90 K=0" .../>
      <TextFrame Self="u4e1d">"3"</TextFrame>
    </Group>
  </CharacterStyleRange>
  <CharacterStyleRange ...>
    <Content>  <?ACE 7?>매체 자료의 공정성 평가 기준</Content>
  </CharacterStyleRange>
</ParagraphStyleRange>
```

### 추정 원인

1. extract_indd.jsx 에서 같은 ③ Group 을 **두 번 등록**:
   - `renderedTextFrames` 의 `badge_group` (`badge_11693.png`) — page 15 (이전 페이지)
   - `renderedFloatingItems` 가 아닌 IDML inline anchor — title paragraph 의 inline PIC
2. Phase 7 가 `badge_11693.png` 를 floating 으로 배치 (이 때 page 15 → section 16 으로 잘못 매핑된 듯 — 별도 이슈 가능)
3. Phase 3 가 inline anchor 를 별도 PIC 으로 임베드 (크기 6.48×12.27 = 종횡비 왜곡 → 시각적으로 작은 "●" 처럼 보임)

## 추가 조사 항목

- [ ] `badge_11693` 이 진짜 page 15 에 있는지, 아니면 page 16 의 inline anchor 와 동일 객체인지 확인
- [ ] inline PIC 의 종횡비 왜곡 원인 (6.48×12.27 비대칭) — `loadInlineObject` 의 bounds 계산 검증
- [ ] Phase 7 `alsoInline` 검사가 `badge_group` 아닌 일반 `renderable` 에도 작동하는지 확인
- [ ] 같은 inline anchored Group 이 두 경로로 들어가는 다른 케이스 수집 (회귀 표면 측정)

## 해결 방향 후보

- **A**: Phase 7 가 `badge_group` 인 Group 을 placement 할 때, 같은 ID 가 inline PIC 로도 임베드되면 skip (중복 방지). `alsoInline` 검사 확장.
- **B**: extract_indd.jsx 가 inline anchored Group 을 `badge_group` 으로 중복 등록하지 않도록 분기. 단, 이는 다른 케이스에 부작용 가능성.
- **C**: Phase 3 가 `loadInlineObject` 로 가져온 PIC 종횡비가 크게 왜곡되면 (예: 1:2 이상) 별도 floating 으로 이관 + inline 자리 비움.

## 영향 범위

- 페이지 46 외에도 inline anchored Group 이 있는 모든 페이지 (소단원 타이틀, 활동 번호 등) 검토 필요.

## 검증 방법

- [ ] CLI 변환 후 `page 46` 의 타이틀이 한 줄로 표시
- [ ] 작은 inline ³ PIC 가 시각적으로 사라짐 (큰 ³ floating 하나만 또는 inline 하나만)
- [ ] 다른 페이지의 inline 번호 라벨 (예: ①, ②) 회귀 없음
