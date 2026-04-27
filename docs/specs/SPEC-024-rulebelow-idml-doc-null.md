# SPEC-024: 빈칸 TextFrame RuleBelow 감지 실패

## 문제

영어교과서 1단원 페이지 28의 "I'm ___ ___ \n ___ learn many recipes." 같은 빈칸 채우기 문장에서
빈칸이 underscore 문자로 변환되지 않고 일반 공백으로 출력되거나 밑줄이 누락됨.

## 원인

[StoryConverter.java:2082-2113](src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase3/StoryConverter.java#L2082-L2113)
의 `tryInlineTextFrameAsRun()`에서 빈 텍스트 인라인 TextFrame이 RuleBelow 단락 스타일을 가지는지
확인하는데, 스타일 정의를 조회하려면 `IDMLDocument`가 필요하다.

```java
kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument idmlDoc =
        ctx.idmlDocumentSupplier != null ? ctx.idmlDocumentSupplier.get() : null;
```

그러나 `IDMLDocument`는 lazy 초기화로, 처음 사용 전에 `ctx.ensureIdmlInfra.run()`을 호출해야 한다.
이 호출이 빠져 있어서 `idmlDoc=null`이 되고, `IDMLStyleDef.ruleBelowOn()` 검사가 항상 false로 평가됨.

결과: `#학생용 답+선`처럼 ParagraphStyle에 `RuleBelow="true"`가 설정된 빈칸 TextFrame이
underscore 변환 경로(line 2098-2108)를 타지 못하고 `return null`로 빠짐 →
`createSpaceRunForEmptyAnchor` 폴백(공백 + underline=true)으로 처리되어
빈칸 모양이 일관되지 않음.

## 영향 범위

- 빈 inline TextFrame + ParagraphStyle의 RuleBelow=true 조합을 사용하는 모든 빈칸 채우기 문제
- 영어교과서 단원 전반의 답안 박스(스타일명 "답+선" 패턴)

## 해결 방안

`idmlDocumentSupplier.get()` 호출 전에 `ctx.ensureIdmlInfra.run()`을 호출하여 IDML 인프라를
초기화한다. 이 함수는 idempotent (`if (idmlDocument != null) return;`).

## 수정 파일

| 파일 | 변경 내용 |
|------|----------|
| `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase3/StoryConverter.java` | `tryInlineTextFrameAsRun`의 RuleBelow 검사 직전에 `ctx.ensureIdmlInfra.run()` 호출 추가 |

## 검증

- [x] `mvn clean package -q -DskipTests` 빌드 성공
- [x] 중3영어교과서 1단원 page 28 변환:
  - 변환 후 `<hp:t>__________</hp:t>` 3개가 각 빈칸 위치에 생성됨 (이전: 빈 단일 공백)
  - `[DBG-RB]` trace로 `idmlDoc=true`, `sd.ruleBelowOn()=true`, `hasRuleBelow=true` 확인

## 추가 발견: 이중 밑줄 (2026-04-27)

### 문제
RuleBelow underscore 변환이 부활하니 부모 Rectangle이 PNG로 별도 렌더된 경우 **밑줄 두 줄 겹침** 발생.

영어교과서 1단원 page 28 "I'm ___ ___ \n ___ learn many recipes." 박스:
- 부모 Rectangle (id 19811, bounds [156.83, 33.72, 172.89, 97.72] mm)이 `inline_19811.png`로 렌더 → 박스 외곽선 + 빈칸 밑줄 3개를 PNG에 포함
- 라이브 TextFrame 19812(같은 bounds)는 글상자로 변환되어 텍스트 위에 오버레이
- 라이브 텍스트 안의 빈칸 inline TextFrame이 underscore 변환되면 **PNG 밑줄 + underscore 글자 두 줄**이 약간의 y 오프셋으로 겹쳐 보임

### 해결
빈칸 inline TextFrame의 `geometricBounds`(pt)를 `allRenderedFloatingItems()`의 `inline_object` 타입 PNG bounds(mm × scaleFactor)와 비교하여, 이 빈칸을 **포함**하는 inline_object PNG가 있으면 underscore 변환 건너뜀 → 라이브 텍스트는 그냥 빈 공간이 되어 PNG의 밑줄 한 줄만 보임.

자기 자신은 매칭 후보에서 제외 (rg.id() == anchoredObjectId).

## 상태: 구현 완료
