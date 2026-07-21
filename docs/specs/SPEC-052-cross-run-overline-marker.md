# SPEC-052: 런 경계를 넘는 overline(선분) 마커 복원

> 작성: 2026-07-21. 상태: **구현 완료, 한글 육안 확인 대기**. 우선순위: P1.
> 관련: [SPEC-050](SPEC-050-overline-marker-excluded-from-chemical-split.md) (단일 런 overline).

## 문제

수학 교과서(중3수학 u5, p168)에서 `AM과 BM의 길이를 비교해` 의 `AM`·`BM` 위
선분(overline)이 HWPX에서 사라졌다. `Ó`(U+00D3, overline 마커) raw 노출도 없이
그냥 선분만 유실.

## 근본 원인

InDesign DOM 이 이 문단을 `AM`|`Ó`|`BM`|`Ó` 처럼 **overline 마커 `Ó` 를 별도
런으로 분리**했다(resolved.json story 91687 para 0, EH상부자 폰트, style=Italic).

overline 마킹은 `EHFontGlyphMap.applyOverlineMarkers`
([EHFontGlyphMap.java:618](../../converter/src/main/java/kr/dogfoot/hwpxlib/tool/equationconverter/idml/EHFontGlyphMap.java#L618))
와 `TextControlNormalizer.markOverlineSegments` 가 하는데, 이들은
`RunBuilder.createRunFromIDML` 안에서 **런별로 개별 호출**된다. `Ó` 만 있는 런은
그 런 안에 앞 문자(`AM`)가 없어 마커를 못 만들고, lone-`Ó` 는 삭제된다
(applyOverlineMarkers 옛 코드: "감쌀 문자 없으면 marker 버림"). 그 결과
overline 이 유실된다.

SPEC-050 의 `PAÓ=PBÓ` 는 단일 런이라 in-run 역방향 스캔이 `PA`·`PB` 를 찾아
작동했지만, 런 경계를 넘는 이 케이스는 처리하지 못했다. `splitOverlineRuns` 는
이미 만들어진 PUA 마커(``/``)만 처리하고 raw `Ó` 는 보지 않는다.

## 해결 방안

overline 마킹은 런별이라 구조적으로 런 경계를 못 넘으므로, **문단 단위 후처리로
경계를 잇는다**:

1. **lone-`Ó` 를 버리지 않고 경계 마커로 보존** — `applyOverlineMarkers` 에서
   감쌀 문자가 이 런에 없으면(`wrapStart==i`) 삭제 대신 경계 마커 ``
   ("앞 런 끝 대문자에 overline")를 남긴다.

2. **splitOverlineRuns 가 경계 마커를 소비** — `` 를 만나면 직전에 추가된
   `newItems` 의 끝 연속 영문자를 떼어 `overline{…}` ASTEquation 으로 만든다.
   InDesign 이 이름을 낱글자(`A`|`M`)로 쪼갠 경우도 있어, newItems 를 뒤에서부터
   훑어 연속 영문 런을 모두 모은다. 숫자 계수(`2AM̅`)는 overline 밖에 남긴다.

SPEC-050(단일 런)과 충돌 없음: 단일 런은 이미 in-run 마킹으로 `` 마커가
생성되어 lone-`Ó` 경로를 타지 않는다. 새 규칙은 lone/`` 런에만 발동한다.

## 수정 파일

1. `converter/.../equationconverter/idml/EHFontGlyphMap.java`
   — `applyOverlineMarkers`: lone-`Ó` → `` 경계 마커 보존
2. `converter/.../resolved/phase3/RunPostProcessor.java`
   — `splitOverlineRuns`: `` 처리 분기 추가
   — `wrapPreviousTrailingCapitalsAsOverline` 헬퍼 신규 (다중 런 끝 대문자 수집)

## 검증

- [x] `mvn -pl converter -am -DskipTests package` — exit 0
- [x] u5 재변환 → p168 `AM과 BM` 이 `overline{AM}` + `과 ` + `overline{BM}` 로 복원
- [x] `overline{AM}` 7개, `overline{BM}` 5개(수정 전 0개)
- [x] overline 수식 총 104→137개 (cross-run 33개 복원)
- [x] 회귀 없음: `Ó` raw 0, PUA 마커(``/``) 누출 0, 빈 `overline{}` 0,
      SPEC-050 케이스(`overline{PA/PB}` 7/7) 유지, 한글 포함 이상 overline 0
- [ ] 한글 육안 확인 (p168 AM·BM 선분)
