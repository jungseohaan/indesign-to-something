# SPEC-007: 폰트 매핑 개선

## 현재 상태

### 알고리즘
1. `conversion-config.json`의 `fontMapping.mappings`에서 **정확한 이름 매칭**
2. 미매칭 시 키워드 기반 폴백 (명조/고딕/weight 판별)
3. 최종 폴백: 한컴돋움/한컴바탕

### 한계
- 새 교과서마다 누락 폰트 수동 추가 필요
- ratio(장평 보정)가 경험적 수치 — 정확도 부족
- 폰트 카테고리(명조/고딕/손글씨/장식) 자동 분류 불가
- 영문 전용 폰트의 한글 폴백이 부정확

## 개선 방안

### Phase 1: 폰트 메트릭 자동 비교 (ratio 자동 계산)

**목표**: 원본 폰트와 매핑 폰트의 글리프 폭을 비교하여 ratio를 자동 계산

**방법**:
1. ExtendScript에서 원본 폰트의 대표 글리프 폭 측정
   ```javascript
   // "가나다라" 등 대표 한글 문자의 실제 렌더링 폭 측정
   var testFrame = doc.textFrames.add();
   testFrame.contents = "가나다라마바사아자차카타파하";
   testFrame.parentStory.characters[0].appliedFont = font;
   var actualWidth = testFrame.geometricBounds[3] - testFrame.geometricBounds[1];
   ```
2. 매핑 폰트에서 동일 문자의 폭 측정
3. `ratio = mappedWidth / originalWidth`

**구현 위치**: `extract_indd.jsx` — `collectFonts()` 함수에 메트릭 수집 추가

### Phase 2: 폰트 카테고리 자동 분류

**목표**: 폰트 이름과 메트릭으로 카테고리 자동 판별

| 카테고리 | 판별 기준 | 매핑 대상 |
|---------|---------|---------|
| 명조(세리프) | 이름에 명조/Myungjo/Serif/바탕/부리 | 한컴바탕 계열 |
| 고딕(산세리프) | 이름에 고딕/Gothic/Sans/돋움/굴림 | 한컴 윤고딕 계열 |
| 손글씨 | 이름에 필기/Hand/펜/쓰다 | 한컴돋움 |
| 장식 | 이름에 OTF + 숫자(210 등) | weight별 윤고딕 230/240/250 |
| 영문 전용 | 한글 글리프 없음 | Arial/Times New Roman |

**구현**: `FontMapper.java`의 키워드 매칭 확장

### Phase 3: 한컴한글 설치 폰트 기반 매칭

**목표**: 한컴한글에서 사용 가능한 폰트 목록을 기반으로 최적 매핑

**방법**:
1. 한컴한글 설치 폰트 목록 수집 (한컴 윤고딕/바탕, 함초롬, 나눔 등)
2. 원본 폰트와 가장 유사한 설치 폰트 자동 선택
3. 유사도 기준: 카테고리 일치 + weight 근사 + 글리프 폭 비율

### Phase 4: 글로벌 폰트 임베딩

**목표**: `glbal-fonts` 디렉토리의 OTF 파일을 HWPX에 임베딩

**방법**:
1. 매핑되지 않은 폰트의 원본 OTF 파일이 `glbal-fonts`에 있으면 HWPX에 포함
2. HWPX `BinData/`에 폰트 파일 추가 + `header.xml`에 fontface 등록
3. 매핑 불필요 — 원본 폰트 그대로 사용

**장점**: 가장 정확한 렌더링 (원본 폰트 사용)
**단점**: HWPX 파일 크기 증가, 한컴한글의 임베딩 폰트 지원 필요

## 우선순위

1. **Phase 2** (카테고리 자동 분류) — 가장 쉽고 즉시 효과
2. **Phase 4** (글로벌 폰트 임베딩) — 가장 정확하지만 한컴 지원 확인 필요
3. **Phase 1** (메트릭 자동 비교) — ExtendScript 수정 필요
4. **Phase 3** (설치 폰트 매칭) — 환경 의존적

## 상태

- [x] SPEC 작성
- [ ] Phase 2: 카테고리 자동 분류
- [ ] Phase 4: 글로벌 폰트 임베딩 가능성 조사
- [ ] Phase 1: 메트릭 자동 비교
- [ ] Phase 3: 설치 폰트 매칭
