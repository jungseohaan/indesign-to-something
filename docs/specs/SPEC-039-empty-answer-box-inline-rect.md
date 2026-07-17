# SPEC-039: 인라인 빈 답란 박스(Rectangle) 배치

> **상태: 완료(2026-07-18)**. 실제 근본 원인은 아래 "원인" 초안(순회 누락·shell 미생성)이
> 아니라, 근호 vinculum 판별의 부동소수 오차였다. inline_object PNG 배치 경로는 이미
> 정상 동작했고, 박스가 그 경로에 닿기 전에 vinculum 으로 오판돼 **삭제**되던 것이 문제.
> 1줄 수정(vinculum 종횡비 임계값 2:1)으로 해결. 자세한 내용은 맨 아래 "해결(실제)" 참고.

## 문제

부등호 채우기 문제의 **빈 답란 네모칸**(부등호 <, > 넣을 칸)이 결과 HWPX에서 전부 누락된다.

- 실측: 수학 1단원 p26 "문제 6 · 다음 빈칸에 부등호 <,> 중 알맞은 것을 쓰시오"
  - 원본: `⑴ -√7 □ 0`, `⑵ √(1/2) □ -√2`, `⑶ 5/2 □ √8`, `⑷ -√(2/3) □ -1`
  - 결과: 네모칸(□)이 모두 사라져 `-√7  0` 처럼 공백만 남음

## 원인

빈 답란 박스는 **인라인 Rectangle 도형**이다(TextFrame 아님).

- 원본 IDML(`Story_u53498.xml`): 각 문항 부등호 자리에 인라인 Rectangle 앵커
  (u534c9, u534d2, u534e0, u534db). 14.2×14.2pt 정사각형, fill=Paper(흰색),
  stroke=C40M8(연한 파란 테두리), weight=0.8 — 보이는 답란 박스.
- resolved.json: 이 Rectangle들이 존재한다.
  - `pageItems`: `type=Rectangle, isInline=true, anchoredPosition=INLINE_POSITION,
    fillColorName=Paper, strokeColorName=C=40 M=8 Y=0 K=0, strokeWeight=0.8`
  - `renderedFloatingItems`: `type=inline_object, file=rendered_frames/inline_341193.png`
    — **PNG 렌더까지 되어 있음**(297바이트, 실제 파일 존재).

즉 데이터·PNG는 다 있는데 **배치 단계에서 누락**된다. 두 층위의 문제:

1. **문단 순회 누락**: 4개 박스 앵커 중 1개(u534e0/341216)만
   `InlineFrameHandler.loadPlannedInlineAnchorItems`에 진입하고, 나머지 3개는
   `buildParagraphContent`의 인라인 앵커 순회(FFFC↔inlineIds 매칭)에서 빠진다.
   → 한 문단에 빈 박스 앵커가 2개인데 FFFC/inlineAnchors 매칭이 1개만 잡는 것으로 추정.

2. **빈 박스 shell 미생성**: 진입한 341216 도 `hasDirectExecutableInlinePlan=true`
   이지만 적절한 inline shell 을 못 만들어 최종 배치 안 됨.
   - `tryInlineEmptyFilledBoxAsFrame`(빈 채움 박스 → INLINE_TEXT_FRAME 변환 로직)이
     이미 있으나 **호출되는 곳이 없다(dead)** + `ctx.resolvedData.getTextFrame(domId)`
     로 **TextFrame 만 조회**해서 Rectangle 도형은 애초에 못 잡는다.
   - renderedFloatingItems 의 inline_object PNG 를 인라인 이미지로 배치하는 명확한
     경로도 이 앵커에는 연결 안 됨.

## 목표

인라인 Rectangle 빈 답란 박스가 원본 위치에 테두리+채움 있는 빈 상자로 배치되도록 한다.
문단에 박스가 여러 개면 전부 배치한다.

## 해결 방안 (후보)

두 방식 중 택일 또는 조합:

**A. 벡터 빈 박스(INLINE_TEXT_FRAME)로 배치** — 편집 가능, 선명
- `tryInlineEmptyFilledBoxAsFrame`를 인라인 그래픽 Rectangle 도형에도 적용하도록 확장:
  `getTextFrame` 실패 시 resolved `pageItems`의 Rectangle(isInline, fill/stroke)로 폴백.
- 이 헬퍼를 실제 호출 경로에 연결(현재 dead).
- 크기·fill·stroke·weight·cornerRadius 를 pageItem 속성에서 채운다.

**B. inline_object PNG를 인라인 이미지로 배치** — 원본 픽셀 그대로
- renderedFloatingItems 의 `file=inline_*.png` 를 해당 앵커의 인라인 이미지로 배치.
- 이미 유사 경로(line 3585/3618 inline_object bounds/file 사용)가 있으니 연결만.

권장: **A 우선**(빈칸은 단순 도형이라 벡터가 선명·경량, 220dpi PNG보다 나음).
단 1번(문단 순회 누락)을 먼저 고쳐야 4개 다 잡힌다.

## 수정 파일 (예정)

1. `.../resolved/phase3/StoryLoader.java` — buildParagraphContent 인라인 앵커 순회에서
   한 문단의 복수 박스 앵커(FFFC↔inlineIds)가 전부 매칭되도록 수정.
2. `.../resolved/phase3/InlineFrameHandler.java`
   - `tryInlineEmptyFilledBoxAsFrame` 를 인라인 Rectangle 도형 지원으로 확장.
   - 이 헬퍼를 `loadPlannedInlineAnchorItems` 배치 경로에 연결.

## 검증

- [ ] 빌드 성공(`mvn clean package -q -DskipTests`)
- [ ] p26 문제 6: 빈 박스 4개(⑴⑵⑶⑷) 모두 배치, 테두리+흰 채움 유지
- [ ] 회귀: 다른 단원·과학 유닛에서 인라인 도형/이미지 배치 무변화

## 해결(실제)

조사 결과 배치 경로(inline_object PNG)는 이미 정상이었고, 진짜 원인은 **근호 vinculum
판별의 부동소수 오차**였다.

- 앞선 근호 작업(커밋 1a6bd9f5)에서 근호 지붕 스페이서 Rectangle 을 EM SPACE 로
  치환하려고 `allInlineGraphicsAreVinculum` 을 도입했는데, 판별 조건이
  `widthPoints() > heightPoints()`(폭 > 높이)였다.
- 빈 답란 박스는 14.17322×14.17304(사실상 정사각형)인데 **부동소수 오차로 폭이
  높이보다 0.0002 크다**. 그래서 vinculum 으로 오판 → 박스 앵커가 삭제되고 radicand
  에 흡수됨. 이 때문에 앞서 "vinculum 처리 후 빈 박스가 사라진" 것.

**수정**: 종횡비 임계값을 넣어 `width >= height * 2.0` 일 때만 vinculum 으로 인정한다.
근호 스페이서는 28.3×5.7(≈5:1)이라 통과, 빈 박스(≈1:1)는 제외된다. 1줄 변경.

`.../resolved/phase3/StoryLoader.java` `allInlineGraphicsAreVinculum`.

## 검증(완료)

- [x] 빌드 성공
- [x] p26 문제 6: 빈 박스 4개 배치(문단당 2개씩, inline_object PNG). math_u1 pic +11
      (부등호 문제 여러 개의 빈칸 복원).
- [x] p22 근호 항 간격 EM SPACE 유지(`sqrt{15} -sqrt{0.81}…`) — 스페이서 5:1은
      여전히 vinculum 인정.
- [x] 회귀: math_u2/3/5·sci_u1/3/5 pic·EM SPACE 무변화. u1 EM SPACE 32→22 는
      회귀 아님 — 잘못 치환됐던 EM SPACE 10개가 올바른 빈 박스로 전환된 것.

## 참고

- 같은 p26 (3)번 GREP 분수 깨짐(;2%;→5/2)은 별건으로 이미 해결(커밋 313afc59).
- 근호 항 간격용 투명 스페이서 Rectangle(28.3×5.7 가로 막대)은 이번 빈 박스와 다름 —
  그건 근호 작업에서 EM SPACE 로 치환 처리함. 이번 수정으로 둘의 구분이 종횡비로 명확해짐.
