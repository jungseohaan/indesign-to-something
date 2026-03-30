# SPEC-009: Facing Pages 좌표 보정

## 문제

InDesign facing pages(양면) 문서에서 ExtendScript의 `parentPage.documentOffset`이 잘못된 페이지를 반환하는 경우가 있음. 프레임이 왼쪽 페이지에 있는데 오른쪽 페이지의 pageIndex가 반환되어, 좌표 변환 시 page bounds 차감이 틀어짐.

### 재현 케이스

**중3-1국어(박영민) 17페이지 — "그래 밉다 네가 넌 떠났지만"**

```
프레임 gb: [107.8, 76.0, 167.3, 201.0]  (spread 좌표, mm)
pageIndex: 11  (ExtendScript parentPage.documentOffset)
page[10] bounds: [0, 0, 280, 220]      ← 왼쪽 페이지
page[11] bounds: [0, 220, 280, 440]    ← 오른쪽 페이지

gb.left=76mm → page[10](0~220mm) 범위 안
하지만 pageIndex=11 → page[11] 기준으로 좌표 변환
→ x = 76 - 220 = -144mm → 음수 클램핑 → 위치/크기 오류
```

### 영향

- 프레임 위치가 잘못된 페이지에 배치됨
- 좌표 차감 시 음수 → 클램핑 → 폭/높이 축소
- facing pages 문서의 왼쪽 페이지 프레임이 주로 영향

## 원인 분석

1. InDesign의 `TextFrame.parentPage`가 spread의 **마지막 페이지**를 반환하는 경우 존재
2. 특히 프레임이 spread 경계에 걸치거나, 마스터 페이지에서 상속된 경우 발생
3. `pageRelativeBounds`도 잘못된 pageIndex 기준으로 계산되어 음수 좌표

## 해결 방안

### 방법 A — Java에서 gb 기반 페이지 재판정

`placeTextFrames`에서 `pageIndex`가 잘못된 경우 gb.left로 올바른 페이지 검색.

**주의**: 단순 재판정은 모든 프레임에 영향을 줘서 부작용 발생 (이미 시도, 실패).
- 이유: `geometricBounds`가 이미 `normalizeToPoints()`에서 스케일 적용되어 원본 mm 좌표가 아님
- 또한 일부 프레임은 pageRelativeBounds가 정확하여 재판정하면 오히려 틀어짐

**개선**: 재판정을 **pageRelativeBounds가 음수인 경우에만** 적용:
```java
if (tf.pageRelativeBounds() != null && tf.pageRelativeBounds()[1] < 0) {
    // gb 기반 페이지 재검색
}
```

### 방법 B — ExtendScript에서 pageIndex 보정

`collectTextFrames`에서 `parentPage.documentOffset` 대신 gb 좌표로 페이지 판정:
```javascript
var tfCx = (gb[1] + gb[3]) / 2; // 프레임 중심 X
for (var pi = 0; pi < doc.pages.length; pi++) {
    var pb = doc.pages[pi].bounds;
    if (tfCx >= pb[1] && tfCx < pb[3]) {
        fData.pageIndex = pi;
        break;
    }
}
```

### 방법 C — pageRelativeBounds 직접 사용

`placeTextFrames`에서 `geometricBounds - pageBounds` 대신 `pageRelativeBounds`를 직접 사용:
```java
double[] prb = tf.pageRelativeBounds();
if (prb != null && prb[1] >= 0) {
    x = prb[1]; y = prb[0]; w = prb[3] - prb[1]; h = prb[2] - prb[0];
}
```

**문제**: `pageRelativeBounds`도 잘못된 pageIndex 기준이므로 음수일 수 있음.

### 추천: 방법 B

ExtendScript에서 근본적으로 pageIndex를 gb 기반으로 보정하는 것이 가장 안전.

## 수정 파일

| 파일 | 방법 | 변경 내용 |
|------|------|----------|
| `scripts/extract_indd.jsx` | B | collectTextFrames에서 pageIndex gb 기반 보정 |
| `normalizer/ResolvedToASTBuilder.java` | A | pageRelativeBounds 음수 시 재판정 (보조) |

## 검증

- [ ] 중3국어(박영민) 17p: "그래 밉다..." 프레임 — 올바른 페이지/위치
- [ ] 기존 문서 회귀 없음 (단면 문서, 정상 facing pages)
- [ ] 인간과심리 교과서 (facing pages) 전체 확인

## 상태

- [x] SPEC 작성
- [ ] 방법 B: ExtendScript pageIndex 보정
- [ ] 방법 A: Java 보조 재판정
- [ ] 검증
