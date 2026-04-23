# SPEC-020: 인라인 객체 PNG 누락 — \x16 앵커 마커 + 빈 컨테이너 보존

## 문제

ExtendScript가 추출한 인라인 객체 PNG(`inline_<id>.png`) 안에 (a) 일러스트 + (b) 라운드 외곽선 박스가 함께 그려져 있고 그 박스 내부에 텍스트 입력 칸(자식 TextFrame)이 anchor 된 패턴이 변환 결과에서 누락된다.

### 재현 케이스

- 입력: `중3영어교과서/2단원_(030-049)/2단원_(030-049).indd`, 페이지 48
- 데이터: `inline_17123.png` 정상 생성 (버스정류장 그림 + 라운드 외곽선 박스)
- 자식 구조: `Group 17123 (anchored, isInline=true) → Rectangle 17140 (라운드 박스, stroke=Black, CornerRadius=8.5) → TextFrame 17160 (빈 입력칸, fill=None, stroke=None, "A: You're ___ cut in line. (supposed to)")`
- 결과: HWPX BinData에 `inline_17123.png` 포함되지 않음 → 페이지 48에서 일러스트 + 라운드 박스 모두 사라짐

### 두 단계의 원인

#### 1차 원인 (실제 root cause): ExtendScript anchor 마커 누락

`scripts/extract_indd.jsx` 의 텍스트 분할 로직이 **U+FFFC만 인라인 anchor 마커로 인식**하지만, InDesign은 위치 모드에 따라 anchor 마커 문자가 다르다:
- `U+FFFC` (OBJECT REPLACEMENT CHARACTER): "Inline" 위치 anchor
- `U+0016` (SYNCHRONOUS IDLE): "Above Line" 또는 "Custom" 위치 anchor

페이지 48의 anchor 6개 모두 `\u0016` 마커였고, 결과적으로 `resolved.json` 의 모든 anchor 런이 `isInlineAnchor=null`, `anchoredObjectId=null` 로 저장되어 Phase 3 StoryConverter가 `loadInlineObject()` 를 호출하지 못함.

#### 2차 원인 (예방적 수정): 빈 컨테이너 자식이 있을 때 PNG 폐기

`StoryConverter.loadInlineObject()` 의 자식 TextFrame 검사 로직이 자식이 inline 또는 editable 이고 텍스트가 있으면 무조건 PNG를 폐기한다 (텍스트 중복 방지 의도). 하지만 PNG가 텍스트를 포함하지 않고 시각적 컨테이너(일러스트/외곽선)만 담는 경우도 같은 처리를 받아 그래픽 전체가 사라진다.

1차 원인이 해결되면 이 분기로 진입하는 케이스가 늘어나므로 함께 보강한다.

## 목표

1. ExtendScript가 `\u0016` anchor 마커도 인라인 객체로 매핑해 `anchoredObjectId` 를 채운다.
2. Phase 3 StoryConverter가 자식 TextFrame이 빈 컨테이너(fill=None, stroke=None)이면 PNG를 폐기하지 않는다.

## 해결 방안

### 1. ExtendScript: 마커 처리 확장

`extract_indd.jsx` 텍스트 런 분할 분기에서 마커 검사와 split을 두 문자 모두 처리하도록 변경:
- `runText.indexOf("\uFFFC") >= 0 || runText.indexOf("\u0016") >= 0`
- `parts = runText.split(/[\uFFFC\u0016]/)`
- 문자별 검사: `rcContent === "\uFFFC" || rcContent === "\u0016"`

### 2. StoryConverter: 빈 컨테이너 예외

`loadInlineObject()` 의 자식 검사 루프에서 자식 TextFrame이 빈 컨테이너로 판단되면 drop 결정을 건너뛴다. 헬퍼: `isNoneColor()`, `isEmptyContainer()`.

### 부수 영향

- **badge_group**: badge_group_child 도 fill/stroke 가 None 인 경우가 많지만, badge PNG는 별도 우선 분기에서 처리됨 ([StoryConverter.java:2275-2281](../../src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase3/StoryConverter.java#L2275-L2281)). 영향 없음.
- **page_background PNG**: 별도 type, 별도 경로. 영향 없음.
- **자체 fill/stroke가 있는 자식 TextFrame**: 기존대로 PNG 폐기 (텍스트가 PNG 안에 진짜 그려져 있을 가능성). 회귀 위험 낮음.
- **U+FFFC 기반 인라인 처리**: 기존 동작과 정확히 동일하게 유지.

## 수정 파일

1. `scripts/extract_indd.jsx` — 텍스트 런 anchor 마커 분기에 `\u0016` 추가
2. `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase3/StoryConverter.java` — `isEmptyContainer()` 헬퍼 추가, `loadInlineObject()` 자식 루프에서 빈 컨테이너 시 `continue`

## 검증

- [x] 빌드 성공: `mvn clean package -q -DskipTests`
- [ ] 페이지 48 재추출 → resolved.json 의 anchor 런 6개에 `isInlineAnchor=true`, `anchoredObjectId=<int>` 채워짐
- [ ] 변환 후 HWPX BinData에 `inline_17123.png` 포함 (md5 일치)
- [ ] HWPX 출력 페이지 48 시각 확인: 버스정류장 그림 + 라운드 박스 표시
- [ ] 다른 페이지/문서 회귀 없음 (badge_group, 기존 \uFFFC anchor 처리)
