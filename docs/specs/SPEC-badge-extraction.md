# SPEC: 배지(Badge) 추출 및 변환

## 개요

InDesign 문서에서 "배지" 객체(채워진 도형 + 짧은 텍스트 조합)를 감지하여 통합 PNG로 렌더링하고, HWPX 변환 시 단일 이미지로 삽입하는 기능.

## 문제

배지는 InDesign에서 Group 내에 배경 도형(Rectangle/Polygon/Oval)과 텍스트 레이블(TextFrame)이 결합된 구조. 개별적으로 변환하면:
- 벡터 도형 렌더링과 텍스트 위치가 미세하게 어긋남
- 도형+텍스트 겹침의 z-order 재현이 복잡
- 그라데이션/효과가 적용된 배경을 벡터로 재현 불가

→ InDesign이 직접 렌더링한 PNG를 사용하면 원본 충실도 100% 보장.

## 배지 판별 기준

`isBadgeGroup(group)` — 다음 **전체 조건** 충족:

| 조건 | 상세 | 이유 |
|------|------|------|
| 도형 존재 | Rectangle/Polygon/Oval/GraphicLine 중 1개 이상 | 배경 도형이 필수 |
| 짧은 텍스트 | TextFrame 1개, 공백 제거 후 1~15자 | 라벨 텍스트 (단어~짧은 구) |
| 이미지 없음 | images/EPS/PDF 0개 | 이미지 프레임은 배지가 아님 |
| 하위 그룹 없음 | 중첩 Group 0개 | 복합 레이아웃 제외 |
| 크기 제한 | 폭 ≤ 150pt, 높이 ≤ 150pt | 소형 UI 요소만 대상 |

### 판별 예시

```
✅ 배지: [둥근 사각형(파란 배경)] + [TextFrame("활동 방법")]  → 50×30pt
✅ 배지: [원형(빨간 배경)] + [TextFrame("STEP 1")]          → 40×40pt
❌ 비배지: [사각형] + [TextFrame(200자 본문)]                 → 텍스트 15자 초과
❌ 비배지: [사각형(이미지 포함)] + [TextFrame("캡션")]        → 이미지 존재
❌ 비배지: [Group > [Group + TextFrame]]                     → 하위 그룹 존재
❌ 비배지: [사각형] + [TextFrame("제목")]                     → 200×80pt, 크기 초과
```

## 추출 파이프라인

### Phase 1: ExtendScript (extract_indd.jsx)

```
Pass 1: 배지 그룹 감지 및 렌더링
─────────────────────────────────
for each Group in doc.allPageItems:
    if !isBadgeGroup(group): skip

    1. childIds[], childTextFrameIds[] 수집
    2. group.exportFile(PNG) → rendered_frames/badge_{domId}.png
       - 해상도: 220 DPI (conversion-config.json의 pngExportResolution)
       - 투명 배경, 안티앨리어싱, 최대 품질
    3. visibleBounds (폴백: geometricBounds) → 페이지 상대 좌표로 변환
    4. resolved.json에 기록:
       a. type="badge_group" 엔트리 (자식 ID 목록 포함)
       b. 각 자식 TextFrame용 type="badge_group_child" 가상 엔트리
    5. badgeGroupChildIds[childTfId] = true (Pass 2 스킵용)

Pass 2: 개별 TextFrame/TextPath 렌더링
──────────────────────────────────────
for each TextFrame/TextPath in doc.allPageItems:
    if badgeGroupChildIds[item.id]: skip  ← 배지 자식은 건너뜀
    ... 기존 개별 렌더링 로직 ...
```

### Phase 2: resolved.json 데이터 구조

```json
// 배지 그룹 본체
{
    "id": 5941,
    "type": "badge_group",
    "file": "rendered_frames/badge_5941.png",
    "bounds": [12.5, 30.2, 42.1, 110.8],
    "pageIndex": 0,
    "childIds": [5942, 5943, 5944],
    "childTextFrameIds": [5943]
}

// 배지 자식 TextFrame (가상 엔트리)
{
    "id": 5943,
    "type": "badge_group_child",
    "file": "rendered_frames/badge_5941.png",
    "bounds": [12.5, 30.2, 42.1, 110.8],
    "pageIndex": 0,
    "badgeGroupId": 5941
}
```

### Phase 3: Java 변환 파이프라인

```
resolved.json 로드 (ResolvedDataReader)
    ├─ RenderedGroup 파싱: type, childIds, childTextFrameIds, badgeGroupId
    └─ renderedTextFrameMap에 등록
        ↓
buildBadgeGroupIndex() (ResolvedData)
    ├─ type="badge_group" 엔트리 순회
    ├─ childIds: DOM decimal → IDML hex 변환
    │   예: 5942 → "u" + Integer.toHexString(5942) → "u1736"
    └─ badgeGroupShapeIdmlIds (Set<String>)에 저장
        ↓
Stage4 AST 빌드 (ASTPageProcessor)
    └─ processVectorShapes():
        vectorShapes.removeIf(s -> resolvedData.isShapeInBadgeGroup(s.selfId()))
        → 배지 소속 도형을 AST에서 제거 (PNG로 대체 예정)
        ↓
Rendered TextFrame 교체 (IDMLToHwpxConverter Phase 1.7a)
    ├─ 플로팅 배지:
    │   ASTTextFrameBlock → ASTFigure
    │   - 위치: bounds 기반 페이지 상대 좌표
    │   - 크기: bounds 폭 × PNG 종횡비 높이
    │   - 이미지: badge_XXXX.png 바이트
    └─ 인라인 배지:
        ASTInlineObject.kind → IMAGE
        - imageData: PNG 바이트
        - pixelWidth/Height: PNG 실제 크기
```

## 관련 파일

### ExtendScript
| 파일 | 함수/영역 | 역할 |
|------|-----------|------|
| `scripts/extract_indd.jsx:508-558` | `isBadgeGroup()` | 배지 판별 |
| `scripts/extract_indd.jsx:300-379` | Pass 1 루프 | 배지 PNG 렌더링 + resolved 기록 |
| `scripts/extract_indd.jsx:381-500` | Pass 2 루프 | 배지 자식 TextFrame 스킵 |

### Java — 데이터 모델
| 파일 | 클래스/메서드 | 역할 |
|------|-------------|------|
| `resolved/RenderedGroup.java:14-47` | RenderedGroup | type, childIds, badgeGroupId 필드 |
| `resolved/ResolvedData.java:231-257` | buildBadgeGroupIndex(), isShapeInBadgeGroup() | 배지 인덱스 빌드 + 조회 |
| `resolved/ResolvedDataReader.java:303-324` | parseRenderedGroup() | JSON → RenderedGroup 파싱 |

### Java — 변환 로직
| 파일 | 메서드/라인 | 역할 |
|------|-----------|------|
| `IDMLToHwpxConverter.java:101-104` | buildBadgeGroupIndex() 호출 | 배지 인덱스 초기화 |
| `normalizer/ASTPageProcessor.java:417-420` | processVectorShapes() | 배지 소속 벡터 도형 제거 |
| `IDMLToHwpxConverter.java:337-425` | replaceFloatingRenderedTextFrames() | 플로팅 배지 → ASTFigure |
| `IDMLToHwpxConverter.java:520-567` | replaceInlineRenderedInParagraph() | 인라인 배지 → IMAGE |

## ID 변환 매핑

InDesign DOM과 IDML은 다른 ID 체계를 사용:

```
InDesign DOM: decimal (예: 5942)
IDML:         "u" + hex (예: "u1736")

변환: "u" + Integer.toHexString(5942) = "u1736"
역변환: Integer.parseInt("1736", 16) = 5942
```

- ExtendScript: DOM ID로 수집 (childIds: [5942, 5943, 5944])
- Java: IDML hex ID로 변환 후 Set에 저장
- 조회: IDML VectorShape.selfId() → isShapeInBadgeGroup("u1736")

## 실패 처리

| 실패 시점 | 동작 | 결과 |
|-----------|------|------|
| ExtendScript PNG 내보내기 실패 | try-catch로 건너뜀 | 배지 미등록 → 개별 도형/텍스트로 변환 |
| resolved.json에 배지 없음 | badgeGroupShapeIdmlIds 비어있음 | 모든 도형 정상 처리 |
| 배지 PNG 파일 누락 | replaceFloating/Inline에서 스킵 | 원본 TextFrameBlock 유지 |
| bounds 정보 없음 | AST 블록 크기 + PNG 종횡비로 폴백 | 위치 근사 |

## 검증

- [ ] 배지 포함 문서 변환 시 배지가 단일 PNG로 표시
- [ ] 배지 없는 문서 변환 시 기존 동작과 동일
- [ ] 배지 자식 도형이 중복 렌더링되지 않음
- [ ] 배지 자식 TextFrame이 중복 렌더링되지 않음
- [ ] 150pt 초과 그룹이 배지로 감지되지 않음
