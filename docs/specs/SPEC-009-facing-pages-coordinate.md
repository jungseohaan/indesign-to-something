# SPEC-009: Facing Pages 좌표 보정 + Spread 직속 프레임 처리

## 문제 1: parentPage가 잘못된 페이지를 반환

InDesign facing pages(양면) 문서에서 ExtendScript의 `parentPage.documentOffset`이 잘못된 페이지를 반환하는 경우가 있음. 프레임이 왼쪽 페이지에 있는데 오른쪽 페이지의 pageIndex가 반환됨.

### 재현 케이스 1

**중3-1국어(박영민) 17페이지 — "그래 밉다 네가 넌 떠났지만"**

```
프레임 gb: [107.8, 76.0, 167.3, 201.0]  (spread 좌표, mm)
pageIndex: 11  (ExtendScript parentPage.documentOffset)
page[10] bounds: [0, 0, 280, 220]      ← 왼쪽 페이지 (gb.left=76 범위 안)
page[11] bounds: [0, 220, 280, 440]    ← 오른쪽 페이지 (잘못 반환됨)
```

## 문제 2: Spread 직속 프레임 (parentPage=null)

InDesign에서 프레임이 페이지가 아닌 **Spread에 직접 속하는** 경우 `parentPage`가 null.
마스터 스프레드에서 상속되거나, 페이지 경계(bleed) 영역에 배치된 프레임.

### 재현 케이스 2

**중3-1국어(박영민) 0페이지 — "01", "02" 번호 텍스트**

```
tf=3181 storyId=3163 ("01 비교하며 깊이 감상하기")
  gb: [-6, 0, 0, 135.2]     ← top=-6mm (bleed 영역)
  parentPage: null
  parent type: Spread        ← 페이지가 아닌 Spread에 직접 속함
  center: y=-3               ← 페이지 위쪽 밖

tf=18311 storyId=18293 ("02 다양한 표현을 활용하여 글 쓰기")
  gb: [-6, 0, 0, 135.2]
  parentPage: null
  parent type: Spread id=17808  ← 다른 Spread
```

### 영향
- "01"은 첫 페이지 배경 PNG에 일부 보이지만 위치가 틀어짐
- "02"는 다른 Spread에 속해 배경 PNG에 아예 안 보임
- 이 프레임들의 텍스트가 editable 글상자로도 배치되지 않음

## 해결 방안

### 문제 1 해결: ExtendScript에서 gb 기반 pageIndex 보정

`collectTextFrames`에서 `parentPage` 대신 gb 중심 좌표로 페이지 매칭:

```javascript
var tfCx = (gb[1] + gb[3]) / 2;
for (var pi = 0; pi < doc.pages.length; pi++) {
    var pb = doc.pages[pi].bounds;
    if (tfCx >= pb[1] && tfCx < pb[3]) {
        fData.pageIndex = doc.pages[pi].documentOffset;
        pageBounds = pb;
        break;
    }
}
```

**주의**: 기존 정상 프레임에 영향 안 주도록 `parentPage`가 null이거나
pageRelativeBounds가 음수인 경우에만 적용.

### 문제 2 해결: Spread 직속 프레임의 페이지 매칭

`collectTextFrames`에서 `parentPage`가 null이면 **visibleBounds 기반**으로 페이지 매칭:

```javascript
if (!tfPage) {
    // Spread 직속 프레임 → visibleBounds 중심으로 페이지 매칭
    var vb = tf.visibleBounds || tf.geometricBounds;
    var cy = (vb[0] + vb[2]) / 2;
    var cx = (vb[1] + vb[3]) / 2;
    // 페이지 경계(bleed) 프레임: cy < 0이면 해당 Spread의 첫 페이지에 매칭
    if (cy < 0) cy = 0;
    for (var pi = 0; pi < doc.pages.length; pi++) {
        var pb = doc.pages[pi].bounds;
        if (cx >= pb[1] && cx < pb[3] && cy >= pb[0] && cy < pb[2]) {
            fData.pageIndex = doc.pages[pi].documentOffset;
            break;
        }
    }
}
```

### Java 보조 처리

`placeTextFrames`에서:
- `pageRelativeBounds`가 음수이면 `pageRelativeBounds` 대신 gb 기반 재계산
- 또는 `pageRelativeBounds`가 정상이면 직접 사용

## 수정 파일

| 파일 | 변경 내용 |
|------|----------|
| `scripts/extract_indd.jsx` | collectTextFrames에서 pageIndex 보정 (문제 1+2) |
| `normalizer/ResolvedToASTBuilder.java` | pageRelativeBounds 음수 시 보조 재판정 |

## 시도한 접근 및 실패 원인

| 접근 | 결과 | 원인 |
|------|------|------|
| Java에서 gb 기반 전체 재판정 | 모든 프레임이 page 0에 모임 | scaleFactor 적용된 좌표와 원본 mm 좌표 혼동 |
| ExtendScript에서 gb 기반 전체 보정 + Java에서 pageRelativeBounds 사용 | 0페이지에 모임 | 기존 정상 프레임의 pageRelativeBounds도 변경됨 |

**핵심 교훈**: 보정은 **문제가 있는 프레임에만** 선택적으로 적용해야 함.

## 검증

- [ ] 중3국어(박영민) 0p: "01", "02" 프레임 — 올바른 위치에 배치
- [ ] 중3국어(박영민) 17p: "그래 밉다..." — 올바른 페이지/크기
- [ ] 기존 문서 회귀 없음 (단면 문서, 정상 facing pages)

## 상태

- [x] SPEC 작성
- [ ] ExtendScript: parentPage null 시 Spread 기반 페이지 매칭
- [ ] ExtendScript: parentPage 잘못된 경우 gb 기반 보정
- [ ] Java: 보조 재판정
- [ ] 검증
