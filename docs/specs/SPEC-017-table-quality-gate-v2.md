# SPEC-017: 테이블 셀 품질 게이트 v2

## 문제

Phase 4 `TableBuilder.hasInlineObjectsInTable` 게이트는 인라인 객체가 포함된 테이블을 통째로 PNG fallback으로 분기한다. 직전 패치(SPEC-013 후속 작업, 임계값 30→60자)로 중3과학 p28의 배지 중복 1차 fix는 됐으나, 다음 한계가 남는다.

1. **테이블 단위 분기** — 셀 하나라도 트리거되면 표 전체가 이미지가 된다. 60셀 표에 배지 셀이 1개만 있어도 전체 표가 검색/편집 불가가 된다.
2. **PNG가 없으면 무력** — ExtendScript의 단독 표 렌더링 대상이 한정적이라, 게이트가 트리거돼도 `renderedFloatingItems`에서 표 PNG를 못 찾으면 그대로 ASTTable 경로로 떨어지면서 배지가 페이지 배경 PNG와 ASTTable inline 양쪽에 중복 표시된다 (현재 stderr에 경고만 출력).
3. **임계값 단일** — `textLen < 60`이라는 단일 컷오프로 "장식 셀"과 "본문 셀"을 구분하는데, 도큐먼트별로 이상적인 값이 다르다. JVM 옵션(`-Dtable.qualityGate.maxTextLength`)으로 오버라이드 가능하지만 사용자가 발견하기 어렵다.
4. **중첩 테이블 미감지** — `IDMLTableCell`이 nested table을 노출하지 않아 "셀 안에 표" 구조는 게이트가 알아채지 못한다. 결과적으로 nested table은 항상 ASTTable 경로로 가서 깨진다.
5. **conversion-config.json 외부화 부재** — 임계값/게이트 정책이 코드 안에 박혀 있어 사용자가 도큐먼트별로 튜닝할 수 없다.

## 목표

1. 셀 단위 분기로 표 전체가 PNG가 되는 비율을 줄인다 (장식 셀만 floating image, 본문 셀은 ASTTable 유지).
2. PNG fallback 후보인데 PNG가 없는 경우 자동 보완 경로를 제공한다.
3. 게이트 정책을 `conversion-config.json` 의 `tables.qualityGate` 섹션으로 외부화한다.
4. 중첩 테이블을 감지하여 반드시 PNG fallback으로 분기한다.
5. 진단 로그를 정형화하여 변환 후 어떤 표가 어떤 경로로 갔는지 한 번에 파악할 수 있게 한다.

## 해결 방안

### 1. 셀 단위 분기 (핵심)

현재: `hasInlineObjectsInTable` 한 번 실행 후 표 전체 PNG 또는 표 전체 ASTTable.

변경: 두 단계로 분리.

```java
// 1단계: 표를 일단 ASTTable로 변환
ASTTable astTable = ASTTableConverter.convertTableSimple(...);

// 2단계: 각 셀을 검사. 게이트 조건 매치 → 그 셀의 인라인 객체를 floating image로 추출
//        + 셀의 텍스트는 ASTTable에 그대로 둠
for (cell in astTable.cells) {
    CellGateResult r = evaluateCell(cell);
    if (r.action == EXTRACT_INLINE_AS_FLOATING) {
        // 인라인 객체를 cell에서 제거하고 ASTFigure로 페이지에 배치
        promoteInlineToFloating(cell, sections.get(pageIdx), r);
    } else if (r.action == REPLACE_WHOLE_CELL_WITH_PNG) {
        // 셀 박스 좌표 영역을 PNG 패치로 덮음
        replaceCellWithPng(cell, sections.get(pageIdx), r);
    }
}
```

`CellGateResult` 종류:
- `KEEP_AS_TEXTBOX` — 정상 셀, ASTTable에 그대로 (default)
- `EXTRACT_INLINE_AS_FLOATING` — 셀 안 인라인 객체만 페이지 위 floating image로 옮김. 텍스트는 ASTTable 유지
- `REPLACE_WHOLE_CELL_WITH_PNG` — 셀 박스 영역을 PNG 패치로 덮음 (단독 표 렌더 대신 셀 영역만 컷)
- `FALLBACK_WHOLE_TABLE_PNG` — 표 전체 PNG (기존 동작) — 중첩 테이블, 다수 셀 트리거 등 극단적 케이스

### 2. PNG 보완 경로

게이트 트리거 + `renderedFloatingItems`에서 표/셀 PNG를 못 찾았을 때:

옵션 A — **페이지 배경 PNG에서 셀 영역 잘라내기**
- `resolved.json` 에 페이지 배경 PNG가 이미 있고, 셀의 페이지 좌표(x,y,w,h)를 안다
- Java `BufferedImage`로 그 영역만 crop → 임시 파일 → ASTFigure
- 새로운 ExtendScript 호출 없이 가능

옵션 B — **ExtendScript 측 동적 요청**
- Java가 변환 중에 "이 셀 PNG가 필요" 라고 stderr에 표시 → 사용자가 다음 변환 시 ExtendScript 옵션을 켜서 다시 추출
- 캐시 친화적이지만 1회차 변환에서는 도움 안 됨

옵션 A를 우선 구현. 페이지 배경 PNG가 충분히 고해상도(600DPI, memory에 기록됨)이면 셀 cropping으로도 품질 유지 가능.

### 3. 게이트 정책 외부화

`conversion-config.json` 에 신규 섹션:

```json
"tables": {
  "qualityGate": {
    "maxTextLengthWithInline": 60,
    "_comment": "셀 텍스트가 이 길이 미만 + 인라인 객체 포함 → 인라인을 floating으로 추출",
    "preferCellLevel": true,
    "_comment_preferCellLevel": "true면 셀 단위 분기 (기본). false면 이전 동작(표 전체 분기)",
    "fallbackToBackgroundCrop": true,
    "_comment_fallback": "단독 PNG 못 찾으면 페이지 배경에서 셀 영역 crop 시도",
    "nestedTableForcesPng": true,
    "_comment_nested": "중첩 테이블 감지 시 무조건 표 전체 PNG (default true)"
  }
}
```

`ConversionConfig.tablesQualityGate()` 접근자 추가. `TableBuilder`가 이 객체를 통해 정책을 읽음.

### 4. 중첩 테이블 감지

`IDMLTableCell`에 `nestedTableCount()` 또는 `hasNestedTable()` 추가가 필요한데, 현재 파서가 셀 안의 `<Table>` 요소를 처리하지 않는다. 두 가지 접근:

옵션 A — **파서 확장**: `IDMLStoryParser`에 nested table 감지를 추가하고 셀 모델에 카운트만 노출 (실제 변환은 안 함). 가벼운 변경.

옵션 B — **간접 감지**: `cell.paragraphs()` 의 텍스트에서 특정 패턴(빈 단락 다수, 매우 긴 paragraphRefs 등)으로 추정. 부정확하지만 파서 변경 없음.

옵션 A 권장.

### 5. 진단 리포트

변환 종료 시 stderr에 정형 요약:

```
[Phase 4] Tables: 12 total
  · 8 → ASTTable (cell-level gate, no extraction)
  · 2 → ASTTable + 3 inline figures extracted (cells: 5)
  · 1 → whole-table PNG fallback (rendered)
  · 1 → whole-table PNG fallback (background-cropped)
  · 0 → ASTTable forced (PNG missing, badge duplication risk!)
```

- 마지막 줄이 0이 아니면 사용자가 즉시 알 수 있게 강조 (ANSI red 또는 `WARNING:` 접두사)
- `--debug-ast` 와 함께 사용 시 각 표의 분기 결정과 게이트 조건을 DebugMeta.notes 로 기록 (SPEC-015 연동)

## 수정 파일

### Java
1. `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase4/TableBuilder.java` — 셀 단위 분기 구현, 정책 외부화 적용, 진단 리포트
2. `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase4/CellGateEvaluator.java` — **신규** 셀 단위 평가 로직
3. `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase4/CellPngExtractor.java` — **신규** 페이지 배경 PNG에서 셀 영역 crop
4. `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/ConversionConfig.java` — `tables.qualityGate` 파싱 + DTO
5. `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/idml/IDMLStoryParser.java` — 셀 안 nested table 감지
6. `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/idml/IDMLTableCell.java` — `nestedTableCount` 필드 추가

### Config
7. `conversion-config.json` — `tables.qualityGate` 섹션 추가 (기본값 + 주석)

## 검증

- [ ] `mvn clean package -q -DskipTests` 빌드 성공
- [ ] 중3과학 p28: 배지 중복 없음 + 표 본문 텍스트 검색 가능 (셀 단위 분기로 본문 셀은 ASTTable 유지)
- [ ] 표가 많은 문서: 변환 후 진단 리포트가 stderr에 출력되고 "PNG missing" 카운트가 0
- [ ] 중첩 테이블 포함 문서: 해당 표는 자동으로 표 전체 PNG fallback
- [ ] `tables.qualityGate.preferCellLevel: false` 로 설정 시 기존 동작(표 전체 분기) 복원
- [ ] `--debug-ast` 활성화 시 표/셀 블록의 `debug.notes`에 게이트 결정 사유 기록
- [ ] 회귀: 표가 없는 문서, 단순 표 문서는 영향 없음

## 의존

- **선행**: SPEC-013 완료 (TableBuilder가 phase4 모듈로 분리되어 있어야 변경 범위 작음) ✅
- **연동**: SPEC-015 — DebugMeta.notes 로 게이트 결정 사유 기록
- 독립 작업 가능 (다른 SPEC과 충돌 없음)

## 위험

- **셀 단위 인라인 추출 시 좌표 계산 오류 가능** — 셀의 페이지 절대 좌표를 계산해야 floating image의 위치가 정확. 표 nested 시 좌표 누적 주의
- **배경 PNG crop 화질** — 페이지 배경 PNG가 600DPI라도 셀이 작으면 흐릿할 수 있음. 임계 셀 크기 이하는 crop 거부
- **nested table 파서 확장** — `IDMLStoryParser`는 SPEC-013 분리 대상에서 빠진 레거시 파서. 수정 시 기존 단순 표 파싱 회귀 주의
- **conversion-config.json 외부화 시 하위 호환** — 기존 사용자가 `tables` 섹션 없이 사용하므로 default 값으로 기존 동작과 동일해야 함

## 상태: 대기
