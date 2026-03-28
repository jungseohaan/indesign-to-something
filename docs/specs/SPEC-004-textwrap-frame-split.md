# SPEC-004: TextWrap 기반 본문 프레임 분할

## 문제

InDesign에서 큰 본문 텍스트 프레임 위에 작은 프레임(이미지, 주석 글상자 등)이 겹쳐 있을 때, TextWrap 설정으로 본문 텍스트가 겹치는 객체를 피해 흐른다. 하지만 HWPX 변환 시 모든 프레임이 PAPER 기준 절대 좌표 1x1 테이블로 배치되므로, 프레임끼리 텍스트가 겹친다.

### 현재 구현 (shrinkOverlappingFrames)

- 방법 1: 큰 프레임의 폭을 줄여 겹침 방지
- **한계**: 겹치는 객체가 프레임 내부 여러 곳에 분산되어 있으면 단순 폭 축소로 해결 불가

### 재현 케이스

**고등_인간과 심리_(008~045) — 24페이지 (pageIdx=16)**

메인 프레임 tf=34295: `gb=[22, 38, 254, 173]` (232mm × 135mm)

겹치는 프레임 5개:
| ID | bounds (mm) | 위치 |
|------|-------------|------|
| 37025 | [30, 156, 57, 193] | 우측 상단 |
| 36746 | [83, 39, 89, 64] | 좌측 중간 |
| 34374 | [137, 129, 142, 173] | 우측 하단 |
| 34351 | [137, 41, 142, 85] | 좌측 하단 |
| 34328 | [137, 84, 142, 128] | 중앙 하단 |

## 목표

본문 텍스트가 겹치는 객체를 피해 흐르도록 변환. InDesign의 TextWrap 동작을 근사하게 재현.

## 해결 방안

### 방법 2 — TextWrap 기반 본문 프레임 분할

1. **겹침 감지**: 같은 페이지의 editable 프레임 간 겹침을 감지
2. **영역 분할**: 겹치는 객체의 bounds로 메인 프레임을 수평/수직 슬라이스로 분할
   - 겹치는 객체 위: 원래 폭
   - 겹치는 객체 옆: 좁은 폭 (객체를 피한 영역)
   - 겹치는 객체 아래: 원래 폭
3. **텍스트 리플로우**: 분할된 각 영역에 텍스트를 재분배
4. **HWPX 출력**: 분할된 영역 각각을 독립 1x1 테이블로 배치

### 구현 위치

| 단계 | 파일 | 설명 |
|------|------|------|
| 겹침 감지 | `ResolvedToASTBuilder.java` | `shrinkOverlappingFrames()` 확장 |
| 영역 분할 | `ResolvedToASTBuilder.java` | 새 메서드: `splitFrameByOverlaps()` |
| 텍스트 리플로우 | `ResolvedToASTBuilder.java` | 분할된 ASTTextFrameBlock에 단락 재분배 |
| HWPX 출력 | `HwpxTextBoxBuilder.java` | 기존 `convertSingleColumnTable()` 활용 |

### 알고리즘 상세

```
입력: mainFrame, overlappingFrames[]

1. overlappingFrames를 Y좌표(top)로 정렬
2. mainFrame의 Y 범위를 슬라이스로 분할:
   for each overlap:
     - slice_above: mainFrame.top ~ overlap.top (원래 폭)
     - slice_beside: overlap.top ~ overlap.bottom (overlap을 피한 폭)
     - slice_below: overlap.bottom ~ next_overlap.top (원래 폭)
3. 같은 Y 범위에 여러 overlap이 있으면 좌우로도 분할
4. 각 슬라이스에 텍스트를 문자 수 기반으로 분배
5. 각 슬라이스를 독립 ASTTextFrameBlock으로 생성
```

### 고려사항

- **텍스트 리플로우 정확도**: InDesign의 조판 엔진 없이 문자 수 기반 근사치 사용
- **폰트 메트릭**: 줄 수 계산 시 폰트 크기, 줄간격 필요
- **인라인 객체**: 분할 시 인라인 객체의 위치 보존 필요
- **성능**: 프레임 분할로 HWPX 객체 수 증가
- **TextWrap 오프셋**: InDesign TextWrapOffset (여백)을 반영해야 정확한 배치

### ExtendScript 보강 필요

- `textWrapMode`, `textWrapOffset`을 resolved.json에 수집 (현재 IDML에서만 읽음)
- 겹치는 객체의 실제 TextWrap 설정 확인 (None이면 분할 불필요)

## 검증

- [ ] 고등_인간과 심리 24페이지: 본문 텍스트가 5개 겹치는 프레임을 피해 흐름
- [ ] 중3-1국어 251페이지: 복잡한 객체 블록 textwrap
- [ ] 기존 변환 결과에 회귀 없음

## 상태

- [ ] SPEC 작성 완료
- [ ] ExtendScript TextWrap 수집
- [ ] 프레임 분할 알고리즘
- [ ] 텍스트 리플로우
- [ ] 검증
