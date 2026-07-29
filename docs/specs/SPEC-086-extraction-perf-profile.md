# SPEC-086: 풀 추출 성능 프로파일 (수학 u1 40p, 945초)

## 문제

수학 u1(010-049, 40p) 풀 추출이 16분(945초) 소요 (2026-07-29 사용자 보고).
직전 풀 추출(동일 문서, 구 추출기)은 790초 — 풀 추출 자체가 원래 13분대의
무거운 작업이고, 이번 +155초는 특정 단계가 아니라 전 구간 10~20% 분산 증가라
동시 부하(빌드·타 세션 변환) 영향이 지배적으로 보인다. 단, 오늘 머지로
`object_plans.jsx` 가 +1,000줄 커진 만큼 계획 단계의 유의미한 증가도 섞여 있다
(objectPlans_built 37→56초).

## 실측 프로파일 (13dc743a, `_phase_timing.log`)

| 구간 | 소요(초) | 내역 |
|------|---------|------|
| 계획 수립(03d) | 313 | readItems 67, objectPlans 56+14, plannerBundles 39, buildExecutionCandidates 19, normalizeSlots 18, sourceDeclared 셸 26, subsumed sync/suppress ~30 |
| PNG export | 257 | pageTextlessGroups 138, inlineObjects(276개) 96, collectEditableFrameIds 23 |
| **미계측 공백** | **85** | `09d_extractionResults`(608s) → `10_collectResolved`(693s) 사이 — 구 추출도 동일 위치 69초 공백. 타이밍 태그 없음 |
| resolved 수집(10) | 195 | collectStories 57(문단 루프 32), collectTextFrames 63, collectPageItems 28, 기타 47 |
| 문서 열기/링크 | 15 | open 9 + fix_links 6 |
| JSON 쓰기/마감 | 80 | writeJson 23, writeObjectPlans 13, PDF export 1.6, 마감 31 |

보조 계측(`_source_index_stats.json` 등):
- 소스 아이템 4,944개 (TextFrame 904, Group 1,130, Polygon 1,342…)
- **`sourceInfoCacheHits: 0` / misses 4,974** — 소스 인덱스 캐시가 한 번도
  히트하지 않음. 캐시 키 또는 조회 경로 확인 필요
- 스토리 941개/문단 1,441/런 4,658 — 문단 루프 32.1초 (문단당 ~22ms)

## 최적화 후보 (impact 순)

1. **미계측 85초 공백 규명** — 09d와 10 사이에 타이밍 태그가 없다. 구 추출도
   69초로 재현되는 상수 비용. 태그를 삽입해 정체(렌더 프레임 수집? GC? 대기?)를
   밝히는 것이 최우선 (배보다 큰 배꼽일 수 있음)
2. **sourceInfoCache 무효** — 4,974 miss/0 hit. 히트만 돼도 readItems 67초
   일부 절감 가능. 캐시 키 불일치 여부 조사
3. **objectPlans/plannerBundles 성장 관리** — 오늘 +1,000줄로 37→56초.
   [[objectplans-perf-regression-1c3ec362]] 교훈(루프 속 DOM 접근·호출부 메모이즈)
   재점검. 특히 subsumed 이후 sync/suppress 재실행 계열(~30초)은 패스 통합 여지
4. **PNG export 257초** — InDesign export 호출 자체가 지배적이라 코드 최적화
   여지는 작음. 페이지 평면 캐시(SPEC-049)가 extractor 변경 시 무효화되는 것은
   정합성상 의도된 동작. dev 루프는 text-only 로 이미 회피 중
5. resolved 수집 195초 — 문단당 22ms 는 DOM 왕복 비용. composedLines 수집과
   함께 배치 읽기(getElements) 가능성 검토

## 비고

- extractor(jsx) 변경 → fingerprint 변경 → 캐시 무효화 → 풀 재추출은 SPEC-011
  의 의도된 동작. jsx 를 건드린 날의 첫 변환은 풀 추출 비용을 감수해야 한다
- 이번 945초 측정은 동시 부하(mvn 빌드, prism 세션 변환, tauri dev)가 겹친
  환경이라 상한치로 볼 것. 재현 측정 시 단독 실행 권장

## 검증

- [ ] 1번: 타이밍 태그 삽입 후 재추출로 공백 정체 규명
- [ ] 2번: 캐시 키 조사 및 히트율 확인
