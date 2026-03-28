# SPEC-005: 조판 결과(Composed Lines) 추출 기반 텍스트 배치

## 문제

현재 파이프라인은 IDML Story XML에서 텍스트를 읽고, Java에서 레이아웃을 재구성한다.
이 방식은 InDesign 조판 엔진의 동작(TextWrap, GREP 스타일, 중첩 스타일, 컬럼 분배 등)을
정확히 재현할 수 없어 다양한 레이아웃 문제가 발생한다.

### 현재 발생하는 문제들

| 문제 | 원인 |
|------|------|
| TextWrap 겹침 | 본문이 겹치는 객체를 피해야 하지만 Java에서 처리 불가 |
| GREP 스타일 색상 누락 | textStyleRanges에 GREP 스타일 미반영 |
| 컬럼 브레이크 부정확 | 문자 수 기반 균등 분배로 근사 |
| 중첩 스타일 누락 | IDML에 명시적 CharacterStyleRange 미출력 |

## 목표

InDesign의 **조판 결과(composed lines)**를 ExtendScript로 추출하여,
Java에서 레이아웃 재구성 없이 **렌더된 그대로** HWPX로 변환한다.

## 해결 방안

### Phase 1: ExtendScript — composedLines 수집

editable TextFrame마다 `lines` 컬렉션에서 조판 결과를 추출한다.

```javascript
// extract_indd.jsx — collectTextFrameData() 내
fData.composedLines = [];
var lines = tf.lines.everyItem().getElements();
for (var li = 0; li < lines.length; li++) {
    var line = lines[li];
    var lineData = {
        bounds: [line.geometricBounds[0], line.geometricBounds[1],
                 line.geometricBounds[2], line.geometricBounds[3]],
        text: line.contents
    };

    // 런 분할: 색상/폰트 변화 지점
    lineData.runs = [];
    var ranges = line.textStyleRanges.everyItem().getElements();
    for (var ri = 0; ri < ranges.length; ri++) {
        var rng = ranges[ri];
        var run = {
            text: rng.contents,
            fillColor: null,
            fontSize: null,
            fontFamily: null,
            fontStyle: null
        };
        try { run.fillColor = rng.fillColor ? rng.fillColor.name : null; } catch (e) {}
        try { run.fontSize = rng.pointSize; } catch (e) {}
        try { run.fontFamily = rng.appliedFont ? rng.appliedFont.fontFamily : null; } catch (e) {}
        try { run.fontStyle = rng.fontStyle; } catch (e) {}
        lineData.runs.push(run);
    }

    fData.composedLines.push(lineData);
}
```

### Phase 2: Java — composedLines 기반 글상자 배치

`ResolvedToASTBuilder.placeTextFrames()`에서:

1. `composedLines`가 있으면 새 경로 사용
2. 연속 라인을 **X 범위(left, right)가 같은 그룹**으로 묶음
3. 각 그룹 = 하나의 ASTTextFrameBlock
   - x = 그룹 내 라인의 최소 left
   - y = 그룹 내 첫 라인의 top
   - width = 그룹 내 라인의 최대 right - 최소 left
   - height = 그룹 내 마지막 라인의 bottom - 첫 라인의 top
4. 각 라인의 runs → ASTParagraph + ASTTextRun으로 변환

```
composedLines 그룹핑 예시:

라인 1: bounds=[50, 40, 60, 170]  ──┐
라인 2: bounds=[60, 40, 70, 170]    ├─ 그룹 A (X: 40~170)
라인 3: bounds=[70, 40, 80, 170]  ──┘
라인 4: bounds=[80, 40, 90, 120]  ──┐  ← TextWrap으로 좁아진 영역
라인 5: bounds=[90, 40, 100, 120]   ├─ 그룹 B (X: 40~120)
라인 6: bounds=[100, 40, 110, 120]──┘
라인 7: bounds=[110, 40, 120, 170]──┐
라인 8: bounds=[120, 40, 130, 170]  ├─ 그룹 C (X: 40~170)
라인 9: bounds=[130, 40, 140, 170]──┘
```

→ 3개의 독립 글상자로 변환

### Phase 3: 단락 경계 보존

InDesign의 `line`은 단락 내 줄바꿈이므로, 단락 경계를 복원해야 한다:
- `line.contents`가 `\r`로 끝나면 단락 끝
- 또는 `line.paragraphs[0]`의 ID 변화를 추적

## 수정 파일

| 파일 | 변경 내용 |
|------|----------|
| `scripts/extract_indd.jsx` | editable 프레임에 composedLines 수집 추가 |
| `resolved/ResolvedTextFrame.java` | composedLines 필드 추가 |
| `resolved/ResolvedDataReader.java` | composedLines JSON 파싱 |
| `normalizer/ResolvedToASTBuilder.java` | composedLines 기반 글상자 배치 경로 추가 |

## 고려사항

- **성능**: `lines` 접근은 `textStyleRanges`보다 느릴 수 있음. 대형 문서에서 프로파일링 필요
- **인라인 객체**: `line.contents`에 `\uFFFC`가 포함되면 인라인 앵커 — 기존 인라인 처리 경로 활용
- **기존 파이프라인 호환**: `composedLines`가 없으면 기존 IDML Story 경로 사용 (fallback)
- **다단 프레임**: 다단은 라인의 X 범위가 자연스럽게 컬럼별로 나뉨 — 그룹핑으로 자동 처리
- **연결 프레임**: 여러 프레임에 걸친 스토리는 프레임별로 `composedLines` 수집

## 검증

- [ ] 인간과심리 24p: TextWrap 겹침 해소
- [ ] 인간과심리 12p: GREP 스타일 색상 분리 (빌헬름 분트)
- [ ] 국어 28p: 2단 컬럼 분배 정확도
- [ ] 국어 251p: 복잡한 TextWrap
- [ ] 기존 변환 결과 회귀 없음

## 상태

- [x] SPEC 작성
- [ ] ExtendScript composedLines 수집
- [ ] Java 파싱 + 글상자 배치
- [ ] 단락 경계 복원
- [ ] 검증
