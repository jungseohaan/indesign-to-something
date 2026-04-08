# SPEC-012: Resolved 속성 적용 경로 통합

## 문제

`ResolvedToASTBuilder`에서 텍스트 런(run)에 폰트/사이즈/색상 같은 속성을 적용하는 코드 경로가 **두 곳 이상**으로 흩어져 있다. 이로 인해 한쪽 경로만 수정하면 다른 경로에서 회귀가 발생한다.

### 구체 사례 (open-indd 브랜치 진행 중 이슈)

- 33페이지 "예쁜" 텍스트:
  - resolved 속성: `fontSize=13pt`, `letterSpacing=0`, `fillColor=#ff00ff`
  - 결과: `fontSize=10pt`, `letterSpacing=-10`, `fillColor=#000000`
- 직전 커밋(`afc462b5`)에서 `createRunFromIDML`에 "resolved → ParagraphStyle → default" 우선순위를 적용했지만 해당 텍스트는 GREP 스타일 분할 경로(`splitIdmlRunByResolvedRuns`)를 통과하기 때문에 미반영
- 같은 우선순위 로직이 인라인 텍스트 프레임 빌드, 표 셀 빌드 경로에도 각각 따로 존재할 가능성

### 영향

- 색상/폰트/사이즈 회귀가 반복적으로 발생
- 한 사례를 고치면 다른 사례가 깨짐
- 디버깅 시 어느 경로를 통과했는지 추적이 어려움

## 목표

1. 텍스트 런에 resolved 속성을 적용하는 단일 진실 공급원(SSoT) 함수 확립
2. 모든 호출 경로가 동일 우선순위 규칙 사용
3. 우선순위 규칙: `resolved → CharacterStyle → ParagraphStyle → default`
4. 회귀 차단: 한 곳을 고치면 모든 경로가 자동 반영

## 해결 방안

### 1. 공통 헬퍼 추출

`ResolvedToASTBuilder` 내부 또는 `normalizer/resolved/RunBuilder.java`로 분리:

```java
public final class RunPropertyResolver {
    /**
     * resolved 속성을 ASTTextRun에 적용한다.
     * 이미 설정된 값은 덮어쓰지 않는다 (호출 전 파라그래프 스타일 적용 금지).
     *
     * 우선순위: resolved → characterStyle → paragraphStyle → default
     */
    public static void apply(
        ASTTextRun astRun,
        ResolvedRun resolvedRun,
        StyleContext styleContext  // CharacterStyle + ParagraphStyle 묶음
    ) {
        applyFontFamily(astRun, resolvedRun, styleContext);
        applyFontSize(astRun, resolvedRun, styleContext);
        applyTextColor(astRun, resolvedRun, styleContext);
        applyLetterSpacing(astRun, resolvedRun, styleContext);
        applyFontStyle(astRun, resolvedRun, styleContext);
        // ... 기타 속성
    }

    private static void applyFontFamily(...) { /* 단일 우선순위 로직 */ }
    private static void applyFontSize(...) { /* ... */ }
    private static void applyTextColor(...) { /* ... */ }
}
```

각 `applyXxx`는 동일 패턴:
```java
if (resolvedRun != null && resolvedRun.fontSize() > 0) {
    astRun.fontSizeHwpunits(toHwpunits(resolvedRun.fontSize()));
} else if (styleContext.characterStyle != null && styleContext.characterStyle.fontSize > 0) {
    astRun.fontSizeHwpunits(toHwpunits(styleContext.characterStyle.fontSize));
} else if (styleContext.paragraphStyle != null && styleContext.paragraphStyle.fontSize > 0) {
    astRun.fontSizeHwpunits(toHwpunits(styleContext.paragraphStyle.fontSize));
}
// 그 외엔 호출자가 default 처리
```

### 2. 호출 경로 식별 및 교체

다음 위치를 모두 찾아서 동일 헬퍼로 교체:

- `createRunFromIDML` (직전 커밋 `afc462b5`에서 수정한 곳)
- `splitIdmlRunByResolvedRuns` (GREP 스타일 분할 경로 — 이번 SPEC의 핵심)
- 인라인 텍스트 프레임 안의 단락 빌드 경로
- 표 셀 단락 빌드 경로
- resolved-only fallback 경로 (IDML Story 매칭 실패 시)

### 3. 중간 구조 `StyleContext`

호출 전에 ParagraphStyle/CharacterStyle을 미리 적용해 ASTRun에 값을 채워두지 말고, **속성 미설정 상태로 헬퍼에 넘긴다**. 헬퍼가 단일 진실 공급원이 되도록.

### 4. resolveColorToHex 일관화

색상 해석은 이미 `resolveColorToHex(...)` + `colorResolver` fallback이 있으나, 호출 시점이 분기마다 다름. RunPropertyResolver 안으로 일원화.

### 5. 디버깅 메타데이터 (선택)

`ASTTextRun`에 `debug.appliedFrom = "resolved" | "characterStyle" | "paragraphStyle" | "default"` 필드를 추가해 어느 경로에서 값이 왔는지 추적 가능하게 함. AST export 시에만 출력.

이 부분은 SPEC-015(AST 디버깅 가시성)와 연동.

## 수정 파일

1. `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/RunPropertyResolver.java` — **신규** 단일 진실 공급원 헬퍼
2. `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/ResolvedToASTBuilder.java` — `createRunFromIDML`, `splitIdmlRunByResolvedRuns`, 인라인 빌드, 표 셀 빌드 등 모든 호출 지점에서 헬퍼 호출로 교체. 우선순위 분기 코드 제거
3. `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/ast/ASTTextRun.java` — (선택) `debug` 메타 필드 추가

## 검증

- [ ] 빌드 성공: `mvn clean package -q -DskipTests`
- [ ] 33페이지 "예쁜" 텍스트: `fontSize=13pt`, `letterSpacing=0`, `fillColor=#ff00ff` 정확히 반영
- [ ] 직전 커밋(`afc462b5`)에서 고친 fontFamily/fontSize/fillColor도 GREP 분할 경로에서 동일 동작
- [ ] CLI 회귀 테스트: 중3 영어 1단원, 중3 국어 4단원, 22개정 비상 고등 논술 5종 변환
- [ ] 폰트 매핑 테스트: HU츄러스, 함초롬돋움 매핑 그대로 적용
- [ ] resolved 없을 때(레거시 경로)는 영향 없음

## 위험

- 이미 동작 중인 회귀 테스트 결과를 깨뜨릴 수 있음 — 변경 전후 testFile 비교 필수
- "이미 설정된 값은 덮어쓰지 않는다" 가정이 깨지면 복합 결함 발생

## 상태: 대기
