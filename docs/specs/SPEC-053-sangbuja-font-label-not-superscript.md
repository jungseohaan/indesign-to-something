# SPEC-053: 상부자 폰트 도형 라벨을 위첨자로 오변환하지 않기 + □(U+E22D) 매핑

> 작성: 2026-07-21. 상태: **구현 완료, 한글 육안 확인 대기**. 우선순위: P1.

## 문제

수학 교과서(중3수학 u5, p174) `오른쪽 그림과 같이 원 O에 외접하는 □ABCD에서` 가
세 가지로 깨졌다:
1. `O`(원 O)가 위첨자로 올라감
2. `□`(사각형 기호)가 엉뚱한 글자(넝 등)로 바뀜
3. `ABCD`가 위첨자로 올라감

## 근본 원인 (두 개, 계측으로 확정)

### 원인 A — `상부자` charStyle 이름만 보고 위첨자화 (문제 1·3)
`MathProcessor.applyPositionFromCharacterStyle`
([MathProcessor.java:1546](../../converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase3/MathProcessor.java#L1546))
이 charStyle 이름에 `상부자` 가 있으면 `superscript(true)` 를 강제. 그러나
`태광10.5:상부자 10.5` 는 "EH상부자 폰트로 도형 라벨(원 O, □ABCD)을 조판"한
스타일명일 뿐 위첨자 위치가 아니다.

- IDML Position = `OpentypePositionFromBaseline` (폰트가 위치 정의, 첨자 아님)
- resolved position = `NORMAL`, superscript = null

즉 **어디에도 위첨자 의도가 없는데 폰트명만 보고 위첨자화**한 오변환. 실측상
도형 라벨 `O`·`ABCD` 뿐 아니라 한글 캡션 `확산하기`·`사고` 까지 위첨자로 깨졌다.

### 원인 B — U+E22D(□) 매핑 누락 (문제 2)
`` 는 이 유닛에서 11회 일관되게 `□ABCD` 의 사각형 기호로 쓰인다. 그러나
PUA→□ 매핑 테이블(`//` → `□`)에 `` 가 빠져 있어
raw PUA 가 그대로 출력 → 한글 폰트에 매핑돼 엉뚱한 글리프로 렌더됐다.

## 해결 방안

### A. 위첨자 오변환 방지 (두 가드)
1. **position=NORMAL 가드**: IDML/resolved position 이 명시적 "첨자 아님"
   (NORMAL 또는 OpentypePositionFromBaseline)이면 폰트명이 `상부자` 여도
   위첨자화하지 않는다. `RunBuilder.applyPositionStyle` 에서 이를 감지해
   `ASTTextRun.explicitNormalPosition` 플래그로 표시하고,
   `applyPositionFromCharacterStyle` 이 이 플래그를 확인.
2. **타당한 첨자 토큰 제한**: 이름-폴백은 숫자·1~2 라틴 글자의 짧은 토큰
   (지수·화학식 계수)에만 적용. 한글·3+ 라틴(ABCD)·기호는 제외.

두 가드를 함께 적용해 `O`(1글자, position 가드로), `ABCD`(4글자, 토큰 가드로),
`확산하기`·`사고`(한글, 토큰 가드로)를 모두 방지.

### B. □ 매핑 추가
` → □` 를 두 매핑 지점에 추가:
- `TextControlNormalizer.java:50-53`
- `HwpxUtil.java:50`

## 수정 파일

1. `core/.../ast/ASTTextRun.java` — `explicitNormalPosition` 필드/getter/setter/copy
2. `converter/.../resolved/phase3/RunBuilder.java` — position=NORMAL 감지 →
   `explicitNormalPosition(true)`, `isNonScriptPosition` 헬퍼
3. `converter/.../resolved/phase3/MathProcessor.java` —
   `applyPositionFromCharacterStyle` 에 position 가드 + 토큰 제한
4. `converter/.../normalizer/TextControlNormalizer.java` — ` → □`
5. `converter/.../converter/HwpxUtil.java` — ` → □`

## 검증

- [x] `mvn -pl converter -am -DskipTests package` — exit 0
- [x] u5 재변환 → p174 `원 O에 외접하는 □ABCD에서` 정상
      (O 위첨자 0, ABCD 위첨자 0, □ 정상, U+E22D raw 없음)
- [x] 회귀 없음: 위첨자 런 22→11개(오변환 11개 제거), 새로 생긴 위첨자 0개.
      덤으로 한글 캡션 `사고`(4→0)·`확산하기`(4→0) 오변환도 해결
- [ ] 한글 육안 확인 (p174)

## 범위 밖 (별개 원인, 미해결)

- `호 AB`·`∠A` 도형 라벨: `상부자` 계열이나 position 가드가 안 걸리는 다른 경로
  (별도 조사 필요)
- `준비 활동` 제목이 위첨자: resolved DOM 이 **position=SUPERSCRIPT 로 잘못 보고**
  (bs=-3.67)한 별개 버그. 상부자 이름 문제 아님
