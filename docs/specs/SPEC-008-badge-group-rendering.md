# SPEC-008: 배지 그룹 객체 통합 렌더링

## 문제

InDesign의 배지 그룹(아이콘 + 짧은 텍스트)이 개별 컴포넌트로 분해되어 수십 개의 PNG로 추출됨.
예: "문학" 배지 → 책 아이콘(도형 20개) + "문학" TextFrame = 25+ 개별 inline_object PNG.

### 현재 동작
1. ExtendScript `exportRenderedTextFrames`에서 그룹 내 개별 아이템을 각각 PNG로 추출
2. 각 PNG가 인라인 객체로 삽입되어 위치가 어긋남
3. 배경 PNG에서도 제외되어 그래픽이 사라짐

### 원하는 동작
- 배지 그룹 전체를 **하나의 PNG**로 렌더
- 인라인 객체 또는 플로팅 이미지로 원본 위치에 배치
- "문학" 텍스트는 PNG 안에 포함 (텍스트 편집 불필요)

## 배지 그룹 감지 조건

| 조건 | 설명 |
|------|------|
| 부모가 Group | `item.parent.constructor.name === "Group"` |
| 그룹 크기가 작음 | 가로/세로 50mm 이하 |
| 짧은 텍스트 (≤10자) | 그룹 내 TextFrame의 텍스트가 짧음 |
| 비검정 색상 또는 배경색 | 장식적 요소 |
| 도형 포함 | 그룹 내 Rectangle/Polygon/Oval 등 |

## 해결 방안

### Phase 1: ExtendScript — 배지 그룹 통합 렌더링

`exportRenderedTextFrames`에서:

1. **그룹 감지**: 인라인 객체의 부모가 Group이면 그룹 전체를 하나의 PNG로 추출
2. **중복 방지**: 이미 그룹 단위로 렌더된 자식 아이템은 개별 추출에서 제외
3. **렌더링**: `group.exportFile(ExportFormat.PNG_FORMAT, outFile)`로 그룹 전체 PNG 생성

```javascript
// 의사 코드
if (item.parent.constructor.name === "Group") {
    var group = item.parent;
    if (!renderedGroupIds[group.id]) {
        // 그룹 전체를 하나의 PNG로 렌더
        group.exportFile(ExportFormat.PNG_FORMAT, outFile);
        renderedGroupIds[group.id] = true;
        // 결과에 그룹 bounds로 등록
        results.push({ id: group.id, bounds: group.geometricBounds, ... });
    }
    continue; // 개별 아이템 추출 건너뜀
}
```

### Phase 2: Java — 배지 그룹 배치

`ResolvedToASTBuilder` 또는 `HwpxImageBuilder`에서:
- `renderedFloatingItems`에서 그룹 ID를 가진 항목을 플로팅 이미지로 배치
- 기존 인라인 객체 경로와 동일하게 처리

## 영향 범위

- "문학", "쓰기" 등 교과 영역 배지
- 번호 + 아이콘 조합 배지
- 작은 장식 그룹 (화살표 + 텍스트 등)

## 수정 파일

| 파일 | 변경 내용 |
|------|----------|
| `scripts/extract_indd.jsx` | 배지 그룹 통합 PNG 추출 |
| `normalizer/ResolvedToASTBuilder.java` | 그룹 배지 배치 (필요 시) |

## 검증

- [ ] 중3국어(박영민) 1p: "문학" 배지 — 하나의 PNG로 렌더
- [ ] 중3국어(박영민) 1p: "쓰기" 배지
- [ ] 기존 인라인 객체 회귀 없음

## 상태

- [x] SPEC 작성
- [ ] Phase 1: ExtendScript 배지 그룹 통합 렌더링
- [ ] Phase 2: Java 배치
- [ ] 검증
