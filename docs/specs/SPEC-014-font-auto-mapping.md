# SPEC-014: 폰트 매핑 자동화

## 문제

InDesign 원본 폰트가 시스템에 없으면 한컴 내장 폰트로 매핑해야 하는데, 현재는 수동이다.

- `conversion-config.json`에 매핑을 한 줄씩 직접 추가 (HU츄러스→가는공한, 210 속삭임OTF→중간안상수체 등)
- 신규 문서마다 미매핑 폰트 발견 → 변환 → 결과 보고 누락 인식 → 수동 추가 → 재변환
- **워드 간격(공백) 과대** 이슈: 매핑 폰트의 space glyph가 원본보다 16~39% 넓음 (memory에 기록됨). 매핑이 단순 이름 대치만 하기 때문
- 폰트 스타일 매핑(Bold/Light/Regular)도 별도 경험적 규칙 (`FontMapper.java`의 weight threshold)

## 목표

1. 신규 문서 변환 시 미매핑 폰트를 **자동으로 후보 추천**
2. 사용자는 추천 결과를 검토 후 한 번에 적용
3. 매핑 폰트가 원본과 시각적으로 너무 다르면 사전 경고
4. 폰트 매핑 모달의 UX를 "수동 입력"에서 "검토 후 승인"으로 전환
5. 글로벌 매핑과 프로젝트별 매핑 분리

## 해결 방안

### 1. 사전 분석 단계 (변환 시작 전)

변환 시작 시 IDML의 폰트 목록을 수집하고 다음 정보를 만든다:

```json
{
  "fonts": [
    {
      "originalName": "HU츄러스130",
      "originalFamily": "HU츄러스",
      "originalStyle": "130",
      "isAvailable": false,
      "currentMapping": null,
      "candidates": [
        { "name": "가는공한", "score": 0.82, "reason": "한글 가는 계열" },
        { "name": "함초롬돋움 Light", "score": 0.71, "reason": "weight 유사" }
      ],
      "warnings": ["space glyph가 원본보다 28% 넓음"]
    }
  ]
}
```

이 분석은 `analyze_idml` Tauri 커맨드를 확장하거나 신규 `analyze_fonts` 커맨드로 추가.

### 2. 후보 매칭 알고리즘

폰트 이름 정규화 후 다음 규칙을 가중 합산:

| 규칙 | 가중치 | 설명 |
|---|---|---|
| 동일 가족명 | 1.0 | 정규화된 family 일치 |
| 한글/영문 구분 동일 | 0.3 | 한글 폰트 ↔ 한글, 영문 ↔ 영문 |
| 굵기 인접 (±100) | 0.2 | weight 추정값 비교 |
| 스타일 키워드 일치 | 0.2 | "고딕"/"명조"/"바탕"/"돋움" |
| 사용자 매핑 이력 | 0.3 | 과거 동일 폰트를 매핑한 적 있음 |

이름 정규화 규칙:
- 공백/하이픈/숫자 접미사 제거
- "OTF", "TTF", 벤더 접두사("HU", "Adobe", "210") 제거
- 한글 표기 통일 ("츄러스" ↔ "추러스")

### 3. 글리프 폭 검증

매핑 후보가 정해지면, **공백 글리프(' ')** 의 폭을 측정하여 원본과 비교:

- 원본 폭: IDML의 폰트 메트릭 (없으면 한컴 폰트 평균값으로 fallback)
- 매핑 폭: 시스템 폰트 메트릭 (Java AWT FontMetrics 또는 사전 계산 테이블)
- 차이 ±10% 이내 → OK
- 10~25% → 경고
- 25% 초과 → 심각 경고 + 다른 후보 추천

이 검증은 워드 간격 회귀를 사전에 차단.

### 4. 폰트 매핑 모달 UX 개선

```
폰트 매핑 검토

미매핑 폰트 (3)
┌─────────────────────────────────────────────────┐
│ HU츄러스130                                      │
│   → 추천: 가는공한 (82% 일치)                    │
│   ⚠ space glyph 28% 넓음                         │
│   [적용] [다른 후보 보기] [수동 입력]            │
├─────────────────────────────────────────────────┤
│ 210 속삭임OTF                                    │
│   → 추천: 중간안상수체 (76% 일치)                │
│   ✓ space glyph 정상                             │
│   [적용] [다른 후보 보기] [수동 입력]            │
└─────────────────────────────────────────────────┘

[모두 추천대로 적용]   [건너뛰기]   [취소]
```

### 5. 글로벌 / 프로젝트 매핑 분리

현재는 단일 `conversion-config.json`에 모두 들어감. 분리:

- **글로벌 매핑**: `~/Library/Application Support/idml-to-hwpx/font-mappings.global.json`
  - 사용자가 한 번 매핑하면 시스템 전반에 재사용
- **프로젝트 매핑**: 변환 대상 INDD 폴더의 `idml-to-hwpx.json`
  - 특정 문서/시리즈에만 적용
  - 글로벌보다 우선

조회 순서: 프로젝트 → 글로벌 → 추천 알고리즘

### 6. FontMapper.java 정리

현재 `FontMapper.java`의 weight threshold 로직(예: ≤400→720, ≤700→240)도 매칭 알고리즘의 일부로 흡수.
설정으로 외부화해서 데이터 변경만으로 조정 가능하게 함.

## 수정 파일

### Java
1. `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/converter/FontMapper.java` — weight threshold 외부화, 글리프 폭 측정 메서드 추가
2. `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/font/FontCandidateMatcher.java` — **신규** 후보 매칭 알고리즘
3. `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/font/FontMetricsCache.java` — **신규** 시스템 폰트 메트릭 사전 캐시
4. `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/ConverterCLI.java` — `--analyze-fonts` 옵션 추가

### Rust / Frontend
5. `desktop/src-tauri/src/commands/conversion.rs` — `analyze_fonts` 커맨드 (Java CLI 래퍼)
6. `desktop/src-tauri/src/lib.rs` — 커맨드 등록
7. `desktop/src/components/FontMappingModal.tsx` — 추천 UI, 경고 표시, 후보 목록
8. `desktop/src/stores/useAppStore.ts` — `analyzeFonts` 액션, 글로벌/프로젝트 매핑 분리

### Config
9. `conversion-config.json` — 기존 매핑 그대로 유지 (하위 호환)
10. `~/Library/Application Support/idml-to-hwpx/font-mappings.global.json` — 신규 위치

## 검증

- [ ] `mvn clean package -q -DskipTests` 빌드 성공
- [ ] 데스크탑 빌드 성공
- [ ] 신규 INDD 추출 시 미매핑 폰트 자동 검출
- [ ] HU츄러스130 → 가는공한 추천 (현재 conversion-config.json의 결과와 동일)
- [ ] 글리프 폭 경고: 매핑 폰트의 space가 원본보다 ±25% 차이 시 경고
- [ ] 사용자가 "모두 추천대로 적용" → 글로벌 매핑 자동 저장
- [ ] 두 번째 변환 시 글로벌 매핑 자동 사용 (재추천 안 함)
- [ ] 회귀: 기존 conversion-config.json 사용자도 영향 없음

## 의존

- 독립적. SPEC-012/SPEC-013와 병렬 진행 가능.

## 위험

- 시스템 폰트 메트릭 측정이 OS별로 차이 — Java AWT 사용 시 macOS/Windows/Linux 동작 검증 필요
- "공백 폭이 다르면 경고" 정책이 false positive 가능 — 임계값 튜닝 필요
- 자동 추천이 사용자가 원하는 폰트와 다르면 오히려 불편 → 반드시 검토 단계 거쳐야 함

## 상태: 대기
