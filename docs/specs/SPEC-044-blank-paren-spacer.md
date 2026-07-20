# SPEC-044: 괄호 빈칸 스페이서 보존 (빈 답란 Rectangle → 고정폭 공백)

## 문제

과학 u1 p46 등에서 "원자의 ㉠(        )은/는" 의 괄호 안 공백이 "()" 로 붙어
나온다. 문서 전체 실측 15곳 (개념확인·문제 답란).

원인: 이 "공백"은 스페이스 문자가 아니라 **fill/stroke 없는 인라인 Rectangle**
(실측 28×8.5pt, 고르시오 답란 99×10.5pt)로 폭을 확보한 조판이다. 변환기의
모든 경로가 이 앵커를 버린다 (메모리의 "빈 답란 박스 누락" 문제의 부분집합).

## 해결 — 파싱 직후 한 번만 치환 (화살표 정규화와 같은 전략)

빈 스페이서 사각형을 같은 폭의 **NBSP(U+00A0) 텍스트**로 바꿔, 하류 전 경로가
평범한 텍스트로 다루게 한다. NBSP 인 이유: 일반 스페이스는 HWPX 출력 시
spaceCondenseRatio(50%) 장평 축소를 받아 폭이 절반이 된다.

경로별 대응(하류 수정)은 실패했다 — 같은 빈칸이 4개 이상의 경로로 흘러가고,
AST 에 스페이서 객체(SPACER_RECT)를 넣어도 HWPX 직전에 소실되는 경로가 있다.
계측으로 확인 후 전량 되돌리고 소스 치환으로 전환했다.

## 수정 파일

1. `normalizer/BlankAnchorSpacer.java` (신규) — 판정·생성 공용 헬퍼
   - 판정: Rectangle + 이미지/자식TF 없음 + fill/stroke 없음(IDML) + 2≤w≤110pt,
     0<h≤12pt
   - 생성: NBSP × clamp(round(w/2.5), 2, 48)
2. `idml/IDMLStoryParser.java` — CharacterStyleRange 의 인라인 Rectangle 이
   빈 스페이서면 FFFC+graphic 등록 대신 NBSP 를 content 에 직접 삽입.
   **수식 폰트(BT/EH/NP) 런이면 생략** (기존 EHLexer BOX lexeme 경로 유지)
3. `resolved/ResolvedData.java` — `normalizeToPoints()` finally 에서
   `replaceBlankSpacerAnchorRuns()`: 빈 스페이서 앵커 런을 NBSP 텍스트 런으로
   변환. IDML 쪽 파서 치환과 **대칭**이라 IDML-resolved 텍스트 동일성 게이트가
   유지된다. 수식 폰트 이웃 런 사이면 생략
4. `phase3/InlineFrameHandler.java` — 셸 게이트(`canMaterializeShellTextFromWholeStory`)에
   NBSP 무시 비교 추가 (`frameVisibleText` 는 원문이라 NBSP 가 없음)
5. `equationconverter/idml/EHLexer.java` — lexer 입력에서 NBSP→스페이스 정규화
   (수식 런에 새어든 NBSP 가 radicand 경계를 깨는 것 방지)

## 함정 (실측)

- **resolved geometricBounds 는 normalizeToPoints 전에는 문서 단위(mm 등)** —
  치환 판정을 반드시 정규화 후에 해야 한다 (mm 수치로 판정해 3/13만 잡히던 사례)
- NBSP 는 `String.trim()`/`Character.isWhitespace()` 에 안 걸린다 — blank 판정
  게이트들을 통과하므로 안전하지만, 검증 스크립트 정규식에 NBSP 리터럴을 쓰면
  히어독 인코딩에 깨질 수 있다 (`\xa0` 이스케이프 사용)
- 수식 런 안의 스페이서를 NBSP 로 바꾸면 EH lexer 가 radicand 로 흡수한다
  (`sqrt{15␣␣␣-}` 회귀) — 폰트 가드 + lexer 정규화 이중 방어

## 검증 (2026-07-19)

- 과학 u1: 괄호 빈칸 17곳 전부 NBSP 복원, 빈 "()" 0. SPEC-042 반응식 무회귀
  (첨자 숫자만, ?C 0). frames 930→936(셸 구조화), images 257→247(불가시
  빈 사각형 PNG 미출력)
- 수학 u1: 수식 diff — `6==sqrt{r}` 오병합이 `6=`/`=sqrt{box{~}}`/`=sqrt{r}`
  3개로 정상 분리(+1), `sqrt{15…}` radicand 무결(공백 1칸 차이만). 텍스트 —
  NBSP 삽입 + 일부 셸의 word-run 분할 변화(시각 동등)
