# SPEC-049: 테이블스타일 숨김 문서의 페이지 평면 캐시 활성화

> 작성: 2026-07-21. 상태: **구현 완료, 캐시 재사용 육안 검증 대기**. 우선순위: P1.
> 상위 컨텍스트: [SPEC-030](SPEC-030-indesign-extraction-performance.md) (추출 속도 개선).

## 문제

`single-textless-plane` 그래픽스 모드의 **페이지 평면 캐시**(`output/cache/page_textless_plane/`)는
페이지당 PNG 1장(`page_textless_plane_p{N}.png`)을 캐시해, 같은 INDD를 재추출할 때
`06b1_pageTextlessGroups_exportDone` 구간(가장 큰 단일 병목)을 파일 복사 수준으로 축소한다.

그러나 **표(테이블스타일 소스)를 포함하는 문서에서는 이 캐시가 저장·복원 모두
영구적으로 비활성화**된다. 원인은 다음 두 가드다:

- 복원: `_restoreCachedSingleTextlessPagePlanes`
  ([extraction_orchestrator.jsx:716-720](../../scripts/indd/extraction_orchestrator.jsx#L716))
  — `pagePlaneHiddenTableStyleSourceObjectIdsByPage` 가 비어있지 않으면
  `reason: table_style_source_hide_requires_fresh_page_plane` 로 skip.
- 저장: `_storeCachedSingleTextlessPagePlanes`
  ([extraction_orchestrator.jsx:822-826](../../scripts/indd/extraction_orchestrator.jsx#L822))
  — 같은 조건에서 `reason: table_style_source_hide_not_cached` 로 skip.

교과서 대부분이 표를 포함하므로, 실질적으로 **이 캐시는 표 없는 소수 문서에서만
작동**한다. 표가 있는 문서는 매 재추출마다 페이지 평면을 처음부터 다시 만든다.

### 실측 (중3과학 u1, 8-49p, 42페이지)

`output/issues/중3과학교과서/u1/p008-049-20260721-125619` fresh 추출 (총 671초).

`single-textless-page-plane-export.json` 의 `perfBreakdown` (ms):

| 항목 | 시간 |
|---|---:|
| collectInlineMs | 12.0s |
| hideInlineMs | 10.9s |
| collectTextMs | 43.5s |
| hideTextMs | 15.9s |
| **prepTotalMs** | **82.7s** |
| **exportLoopMs** | **76.5s** |
| restoreMs | 14.8s |
| **06b1 합계** | **≈174s (전체의 26%)** |

캐시 산출물:
- `single-textless-page-plane-cache-restore.json`: `status: skipped, reason: table_style_source_hide_requires_fresh_page_plane`
- `single-textless-page-plane-cache-store.json`: `status: skipped, reason: table_style_source_hide_not_cached`

이 문서는 표 51개를 포함해 `pagePlaneHiddenTableStyleSourceObjectIdsByPage` 가
비어있지 않으므로, **캐시가 저장조차 되지 않아 다음 재추출도 174초를 반복**한다.

## 근본 원인 분석

가드는 "페이지 평면이 테이블스타일 숨김 상태에 의존하는데, page hash가 그 숨김
상태를 반영하지 못하므로, 오염된 캐시를 안전하게 재사용할 수 없다"는 보수적
가정에서 나왔다.

그러나 두 사실이 이 가정을 완화한다:

1. **숨김 집합은 결정론적이다.** `pagePlaneHiddenTableStyleSourceObjectIdsByPage`
   는 `_tableStyleSourceObjectIdsByPageForPagePlaneHide(sourceItems)`
   ([extraction_plan_builder.jsx:7609-7612](../../scripts/indd/extraction_plan_builder.jsx#L7609))
   로 `sourceItems` 에서 순수하게 도출된다. 같은 INDD → 같은 sourceItems → 같은
   숨김 집합. 세션 간 랜덤성이 없다.

2. **캐시 키는 이미 INDD 신원 기반이다.** 캐시 디렉토리는
   `page_textless_plane/{indd sha}/extract-v{version}/single-textless-plane-{mode}`
   로, INDD 경로/크기/mtime + 추출기 버전 + 그래픽스 모드 + perf 모드로 키가
   정해진다. 파일명 `_pagePlaneCacheFileName`
   ([extraction_orchestrator.jsx:668-674](../../scripts/indd/extraction_orchestrator.jsx#L668))
   은 page hash 인자를 **의도적으로 무시**한다(세션-로컬 DOM id 오염 회피 주석 참조).

따라서 "같은 INDD의 같은 페이지는 항상 같은 테이블스타일 숨김 집합으로 렌더된
페이지 평면을 만든다". INDD 신원이 바뀌면(파일 수정 → mtime/size 변경) 캐시
디렉토리 키 자체가 달라져 자연히 무효화된다. **숨김이 있다는 사실만으로
캐시를 버릴 이유가 없다.**

유일한 잔여 리스크는 **추출기 코드가 바뀌어 같은 sourceItems에서 다른 숨김
집합을 만드는 경우**인데, 이는 이미 캐시 키의 `extract-v{version}` 세그먼트가
방어한다(추출기 버전이 오르면 캐시 디렉토리가 갈린다).

## 목표

표(테이블스타일 숨김)를 포함하는 문서에서도 페이지 평면 캐시를 저장·복원하여,
재추출 시 `06b1` 174초 구간을 파일 복사 수준으로 축소한다.

- fresh(캐시 miss) 추출 시간은 변하지 않는다 (첫 추출은 여전히 풀 렌더).
- **재추출(캐시 hit) 시간이 표 있는 문서에서 -170초 규모로 단축**된다.
- 출력 correctness 회귀 0건 — 캐시된 페이지 평면 PNG는 fresh 렌더와 픽셀
  동일해야 한다(같은 숨김 집합·같은 dpi로 만든 것이므로).

## 해결 방안

### 접근: 숨김 서명을 캐시 파일명에 포함

캐시를 무조건 비활성화하는 대신, **테이블스타일 숨김 집합의 결정론적 서명**을
캐시 파일명에 넣어 "같은 페이지 + 같은 숨김 집합 = 같은 캐시 파일"을 보장한다.
숨김 집합이 (추출기 변경 등으로) 달라지면 파일명이 달라져 자동으로 miss → 안전.

1. **숨김 서명 함수 추가** — `pagePlaneHiddenTableStyleSourceObjectIdsByPage` 를
   페이지별로 정렬된 object id 목록으로 직렬화해 짧은 해시(FNV/djb2류)로 요약.
   전체 문서 단위 1개 서명으로 충분(모든 페이지 평면이 같은 추출 실행에서
   같은 숨김 정책으로 생성되므로).

2. **저장 가드 완화** — `_storeCachedSingleTextlessPagePlanes` 의
   `table_style_source_hide_not_cached` early-return 제거. 대신 숨김 서명을
   파일명에 반영해 저장.

3. **복원 가드 완화** — `_restoreCachedSingleTextlessPagePlanes` 의
   `table_style_source_hide_requires_fresh_page_plane` early-return 제거. 같은
   서명의 캐시 파일이 있으면 hit, 없으면 miss(정상 fresh).

4. **파일명 서명 반영** — `_pagePlaneCacheFileName(pageIndex, pageHash, hideSig)`
   로 확장. `hideSig` 가 있으면 `page_textless_plane_p{N}_{hideSig}.png`,
   없으면 기존 `page_textless_plane_p{N}.png` (하위 호환).

> **보수적 범위 결정**: 이번 SPEC은 `table_style_source_hide` 가드만 완화한다.
> `source_bundle_text_range_shell_hide` / `mixed_bundle_placed_visual_hide` 두
> 가드는 동일 논리로 완화 가능하나, 별도 검증이 필요하므로 이 SPEC 범위 밖으로
> 둔다(같은 서명 메커니즘을 확장하면 됨).

## 수정 파일

1. `scripts/indd/extraction_orchestrator.jsx`
   - `_pagePlaneHideSignature(ctx)` 신규 — 숨김 맵 → 결정론적 서명 문자열
   - `_pagePlaneCacheFileName(pageIndex, pageHash, hideSig)` — 서명 인자 추가
   - `_restoreCachedSingleTextlessPagePlanes` — table-style 가드 제거, 서명 사용
   - `_storeCachedSingleTextlessPagePlanes` — table-style 가드 제거, 서명 사용

## 검증

- [ ] `mvn -pl converter -am -DskipTests package` (변환기 무영향 확인)
- [ ] 중3과학 u1 (8-49p) 1차 추출 → 캐시 store 성공 확인
      (`single-textless-page-plane-cache-store.json` `status: ok`)
- [ ] 동일 케이스 2차 추출 → 캐시 hit 확인
      (`cache-restore.json` `status: hit`, `06b1` 구간 ≈0초)
- [ ] 1차/2차 출력 HWPX diff 0 (페이지 평면 PNG 픽셀 동일)
- [ ] 표 없는 문서 회귀 없음 (기존 캐시 경로 유지)
