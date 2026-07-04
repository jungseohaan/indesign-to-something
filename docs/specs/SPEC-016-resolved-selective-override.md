# SPEC-016: 고신뢰 매칭 기반 Resolved 선택적 오버라이드

## 배경

[SPEC-012](SPEC-012-resolved-priority-unified.md)는 resolved 우선순위를 도입하려 했으나 회귀가 발생해 `IDML CR → resolved → PS` 순으로 복원되었다. 그 결과 다음 케이스는 **미해결 상태**로 남아 있다:

- **33페이지 "예쁜" 텍스트** (22개정 비상 고등 논술)
  - IDML CR: `fontSize=10pt`, `letterSpacing=-10`, `fillColor=#000000`
  - resolved: `fontSize=13pt`, `letterSpacing=0`, `fillColor=#ff00ff` (GREP 스타일 결과)
  - 기대: resolved 값 적용 (사용자가 InDesign에서 본 실제 결과)
  - 현재: IDML CR 값 적용 → 10pt 검정 출력

이 케이스를 해결하려면 resolved를 우선해야 하지만, SPEC-012 검증에서 발견된 것처럼 `splitIdmlRunByResolvedRuns`의 rr 매칭이 불완전하면 잘못된 resolved 런이 적용되어 **다른** 회귀를 유발한다.

## 문제

현재 `splitIdmlRunByResolvedRuns`는 IDML 텍스트에 대해 resolved 런을 **텍스트 접두사 매칭**으로 찾는다:

```java
while (!remaining.isEmpty() && rIdx < resolvedRuns.size()) {
    ResolvedRun rr = resolvedRuns.get(rIdx);
    if (normRemaining.startsWith(normRRText)) {
        // 매칭 성공
    } else if (remaining.startsWith(rrText.substring(0, Math.min(3, rrText.length())))) {
        // 부분 매칭 (3자) → 다음 런 키워드로 분할
    } else {
        rIdx++; // 매칭 실패 → 다음 런 시도
    }
}
```

### 실패 케이스

1. **접두사 매칭 실패 → 건너뛰기**: 특수 문자(`\uFFFC`, 탭, 제어 문자)로 인해 경계가 틀어지면 잘못된 런이 선택됨
2. **findDefaultResolvedRun 폴백**: 매칭이 아예 없으면 "가장 긴 텍스트" 런을 선택 — 색상/폰트가 전혀 다른 런일 수 있음
3. **부분 매칭**: 앞 3자만 맞으면 매칭으로 인정 — 한글 3자는 자주 충돌
4. **단락 내 동일 텍스트**: "이" 같은 조사는 여러 런에 반복 → 순서 추적이 어긋남

### SPEC-012 초기 시도의 회귀

"발생합니다" 런이 IDML CR의 파랑(#3380ff)에서 잘못된 rr 매칭으로 검정(#000000)으로 변함. rr 매칭 신뢰도를 고려하지 않고 **무조건 resolved 우선**으로 한 것이 원인.

## 목표

1. resolved 속성 오버라이드를 **매칭 신뢰도가 높을 때만** 적용
2. "예쁜" 케이스(정확한 매칭) 해결
3. "발생합니다" 케이스(부정확한 매칭) 회귀 없음
4. 회귀 테스트 대표 5문서에서 변경 전 대비 bit-identical 혹은 의도적인 개선만 있음

## 해결 방안

### 접근 A — 세그먼트별 신뢰도 플래그 (MVP)

`splitIdmlRunByResolvedRuns`가 각 세그먼트에 `confidence` 플래그를 붙여 반환하고, `createRunFromIDML`이 이 플래그를 받아 고신뢰일 때만 resolved로 오버라이드한다.

#### 신뢰도 기준

| 조건 | 신뢰도 |
|---|---|
| resolved 런 텍스트 전체가 IDML 세그먼트와 정확히 일치 (정규화 후) | **HIGH** |
| 접두사 매칭 but 남은 텍스트가 다음 resolved 런과도 일치 | **HIGH** |
| 부분 매칭(앞 3자) 후 다음 런 키워드로 분할 성공 | **MEDIUM** |
| `findDefaultResolvedRun` 폴백 | **LOW** |
| 매칭 실패로 rIdx 건너뛴 경우 | **LOW** |

#### 적용 규칙

- **HIGH**: fontFamily, textColor는 resolved 오버라이드 가능. fontSize는 IDML effective size가 없을 때만 resolved 폴백
- **MEDIUM**: textColor는 resolved 오버라이드 가능. fontSize는 IDML effective size가 없을 때만 resolved 폴백
- **LOW**: 오버라이드 없음 (기존 IDML CR 우선 유지)

#### API

```java
// 신뢰도 enum
public enum MatchConfidence { HIGH, MEDIUM, LOW }

// splitIdmlRunByResolvedRuns 내부 세그먼트 구조
class Segment {
    String text;
    ResolvedRun rr;
    MatchConfidence confidence;
}

// createRunFromIDML에 새 파라미터 추가
private ASTTextRun createRunFromIDML(
    IDMLCharacterRun cr, String text, ResolvedRun rr,
    StyleContext sc, MatchConfidence confidence) {
    ...
}
```

### 접근 B — 문자 위치 기반 정렬 (장기)

resolved.json에 각 런의 `startCharIndex`를 출력하고, IDML Story도 문자 오프셋을 추적해 **위치 기반**으로 정렬한다. 텍스트 내용이 아니라 문자 위치로 매칭하므로 특수문자/중복 텍스트에 강건하다.

- 장점: 매칭 정확도 근본 해결
- 단점: ExtendScript 변경 + resolved.json 스키마 확장 + 양쪽 문자 오프셋 계산 필요
- 이 SPEC 범위 외, 후속 SPEC(SPEC-016b)으로 분리

### 접근 C — charStyle 연쇄 비교

IDML CR의 `appliedCharacterStyle`과 resolved 런의 `charStyle`이 다르면 **GREP/중첩 스타일이 적용된 것**이라고 판단해 resolved를 우선. 같으면 IDML CR 사용.

- 장점: 구현이 단순
- 단점: GREP 스타일이 inline override로 들어간 경우(스타일 이름이 같은 경우) 감지 못함
- 보조 힌트로만 활용 (신뢰도 계산 입력)

## 단계별 실행

### Phase 1: 세그먼트 신뢰도 도입 (접근 A)

1. `MatchConfidence` enum 신규
2. `splitIdmlRunByResolvedRuns` 내부에 세그먼트 정보 구조체 도입
3. 매칭 조건별로 confidence 할당
4. `createRunFromIDML`에 confidence 파라미터 추가
5. confidence가 HIGH일 때 fontFamily/textColor를 resolved로 덮어쓸 수 있음
6. fontSize는 CharacterRun 또는 ParagraphStyle에서 effective size가 확인되면 항상 IDML 값을 유지하고, IDML 쪽 크기 정보가 없을 때만 resolved를 폴백으로 사용
7. `horizontalScale`/`verticalScale`은 fontSize를 대체하지 않는다. resolved 런에서 두 scale 값이 같더라도 이를 proportional font-size로 흡수하지 않는다.
8. IDML Story/CharacterRun 경로의 글자 비율은 IDML CharacterRun/CharacterStyle/GREP/ParagraphStyle에 명시된 값만 HWPX ratio로 전달한다. IDML에 없는 resolved-only scale은 frame/group render scale일 수 있으므로 editable text run에 주입하지 않는다.
9. `RunPropertyResolver`에 `resolveWithOverride()` 변형 메서드 추가

### Phase 2: "예쁜" 케이스 검증

1. 33페이지 "예쁜" 텍스트가 있는 INDD 재추출
2. 변환 후 HWPX에서 해당 런의 속성 확인
3. 기대값: `fontSize=13pt`, `letterSpacing=0`, `textColor=#ff00ff`

### Phase 3: 회귀 테스트

1. "발생합니다" 런이 파랑(#3380ff)으로 유지되는지 확인 (section0.xml diff)
2. 대표 5문서 변환 → before/after diff
3. 의도적 개선(HIGH 매칭 케이스에서 resolved 적용)과 회귀(IDML이 맞았는데 덮어쓰기)를 구분해 리포트

### Phase 4: 신뢰도 임계값 튜닝

1. 발견된 오탐/누락 케이스를 기반으로 HIGH/MEDIUM 경계 조정
2. 필요 시 `conversion-config.json`에 `resolvedOverrideMode = strict|lenient|off` 옵션 추가

## 수정 파일

1. `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/MatchConfidence.java` — 신규 enum
2. `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/ResolvedToASTBuilder.java`
   - `splitIdmlRunByResolvedRuns`: 세그먼트 구조체로 전환, confidence 계산
   - `createRunFromIDML`: confidence 파라미터 추가
   - 기타 호출 지점도 MatchConfidence.LOW 기본값으로 전달
3. `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/RunPropertyResolver.java`
   - `resolveFontFamilyWithConfidence()`, `resolveFontSizeHwpunitsWithConfidence()`,
     `resolveTextColorHexWithConfidence()` 추가
   - 기존 메서드는 호환 유지

## 검증

### 빌드
- [ ] `mvn clean package -q -DskipTests` 성공

### "예쁜" 케이스 해결
- [ ] 33페이지 "예쁜" 런의 fontSize=13pt
- [ ] 33페이지 "예쁜" 런의 letterSpacing=0 (또는 0에 가까움)
- [ ] 33페이지 "예쁜" 런의 fillColor=#ff00ff

### 회귀 차단
- [ ] "발생합니다" 런이 #3380ff로 유지 (22개정 비상 고등 논술 section0.xml 비교)
- [ ] 대표 5문서 변환 결과:
  - [ ] 22개정 비상 고등 논술 — HIGH 매칭된 개선 외에 회귀 없음
  - [ ] 중3-2 국어 1단원(2) — 회귀 없음
  - [ ] 중3 과학 6단원 — 회귀 없음
  - [ ] 중3 영어 1단원 — 회귀 없음
  - [ ] 수식 많은 문서 1종 — 회귀 없음

### 측정
- [ ] 전체 런 중 HIGH/MEDIUM/LOW 비율 로그 출력 (`[SPEC-016] HIGH=1234 MEDIUM=56 LOW=789`)
- [ ] HIGH 매칭에서 실제 resolved 오버라이드가 적용된 건수

## 위험

- 신뢰도 판정 규칙이 모든 케이스를 커버하지 못할 수 있음 — 발견되는 대로 튜닝 필요
- "발생합니다" 같은 케이스가 HIGH로 잘못 판정되면 다시 회귀 발생 → 신뢰도 계산 로직은 보수적으로 시작
- 회귀 테스트가 `before`의 bit-identical을 요구하면 HIGH 개선도 걸러짐 → "의도된 차이" 화이트리스트 필요

## 의존

- **선행**: SPEC-012 (완료 — 헬퍼 구조가 이미 존재)
- **독립**: SPEC-013(Phase 모듈 분리), SPEC-014(폰트 자동), SPEC-015(디버깅)와 병렬 가능

## 후속

- **SPEC-016b**: 문자 위치 기반 정렬 (접근 B) — resolved.json 스키마 확장 필요

## 상태: Phase 1 완료 (부분 해결 + 회귀 없음)

### 구현 결과

- `MatchConfidence.java` 신규 enum (HIGH / MEDIUM / LOW)
- `splitIdmlRunByResolvedRuns`에서 세그먼트별로 신뢰도 계산
  - 정확 접두사 매칭 → HIGH
  - 부분 매칭(앞 3자) + 다음 런 키워드 분할 성공 → MEDIUM
  - 분할 실패 / 루프 탈출 후 남은 텍스트 → LOW
  - findDefaultResolvedRun 폴백 → LOW 강등
- `RunPropertyResolver`에 `resolveXxxWithConfidence` 변형 3종 추가
  - HIGH: fontFamily + textColor resolved 오버라이드, fontSize는 IDML effective size 우선
  - MEDIUM: textColor resolved 오버라이드, fontSize는 IDML effective size 우선
  - LOW: 모두 IDML CR 우선 (기존 동작)
- `createRunFromIDML`에 confidence 파라미터 추가, 기본값(LOW)은 래퍼 메서드로 호환

### 검증 결과

**테스트 문서**: 22개정 비상 고등 논술-1(008~043) — "예쁜" 텍스트 2회 등장

| 런 | charPrID | height | textColor | spacing | 평가 |
|---|---|---|---|---|---|
| "예쁜" (첫 번째) | 340 | 1000 (10pt) | #000000 | -10 | **미해결** (LOW 매칭) |
| "'예쁜'이" (두 번째) | 345 | **1300 (13pt)** | **#ff00ff** | **0** | **해결** (HIGH 매칭) |

두 번째 인스턴스는 기대대로 13pt 마젠타로 정상 출력. 첫 번째는 경계가 달라 매칭이 LOW로 분류되어 여전히 IDML CR(10pt 검정)이 적용됨. **부분 해결**.

### 회귀 테스트

- **22개정 비상 고등 논술 (QR코드, 33페이지)**: section0.xml / header.xml MD5가
  SPEC-012 복원 버전과 완전 일치 → **"발생합니다" 회귀 없음**
- **중3-2 국어 1단원(2), 24p**: warnings=0
- **7페이지 소형 문서**: warnings=0
- **고등 인간과 심리 (7p)**: warnings=0

### 잔존 과제

1. **첫 번째 "예쁜" 케이스**: 매칭 신뢰도가 LOW로 판정되는 원인 분석 필요. 어떤
   경계 조건에서 LOW로 분류되는지 로그 출력 기능 추가 후 튜닝.
2. **카운트 로그**: 전체 런 중 HIGH / MEDIUM / LOW 비율 리포트 미구현
3. **접근 B (문자 위치 기반 정렬)**: resolved.json 스키마에 startCharIndex 추가 →
   근본 해결. 별도 SPEC-016b로 분리.

### 구조적 성과

- 회귀 없이 resolved 선택적 오버라이드 경로가 열렸음
- 헬퍼(`RunPropertyResolver`)가 단일 진실 공급원으로 동작
- 신뢰도 계산 로직은 `splitIdmlRunByResolvedRuns` 한 곳에 집중되어 튜닝이 쉬움
