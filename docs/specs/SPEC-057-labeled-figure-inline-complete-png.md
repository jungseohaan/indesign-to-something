# SPEC-057: 라벨 달린 삽화 그룹의 시각 유실 — 인라인 통짜 PNG 승격

> 상태: **완료** — 구현·산출물 검증·한글 육안 확인 통과. 2026-07-23.
> 브랜치 `spec-057-labeled-figure-shell-decompose`.

## 문제

과학 u1 p47 실험 장치 삽화(그림+라벨 식초/핀치 집게/탄산수소 나트륨/석회수,
인라인 앵커 Group 211293, 74×40mm)가:
1. 삽화(Rectangle 211294 + placed Image)가 **통째로 유실** — 빈 표 골격만 남음
2. 라벨들이 폭 28pt 1×1 테이블에 **cellMargin left 62mm**(셀보다 6배)가 박혀
   전폭 회색 바처럼 렌더, 텍스트는 오른쪽 끝에 몰림

## 원인

1. **텍스트 중복 강등이 시각까지 DROP**: `object_plans.jsx`
   `_resolveObjectPlanDuplicateTextOwners` — 그룹 bundle plan 이 라벨 TF 소유를
   canonical 라벨 셸(direct_child_shell_slot)들에 전부 뺏기면
   `visualAction=DROP_VISUAL` 로 통째 강등된다. 그룹 plan 만이 삽화의 유일한
   시각 소유자인데도 함께 버려져 소유자 없는 시각이 됨. 페이지 배경 평면은
   인라인 아이템을 숨기므로 삽화가 어디에도 렌더되지 않음.
2. **셸 여백 단위 혼합**: `InlineFrameHandler.applySingleInlineShellChildTextMargins`
   — plan bounds 는 추출기 원단위(mm), TF `geometricBounds` 는
   `normalizeToPoints` 로 pt. page-local 정규화가 두 단위를 섞어 62mm 유령
   여백을 만들었다 (`pageRelativeBounds` 는 정상이었음 — 음수처럼 보인 값은
   pt/mm 혼합 산술의 결과).

## 수정

### 추출기 (`scripts/indd/object_plans.jsx`)

강등 지점에서, canonical 셸들이 가져가지 않은 시각 루트(placed Image 등)가
남아 있고 배치가 INLINE 이면 — 자식 셸 분해 대신 **그룹 전체를 통짜 PNG 로
승격**한다 (`_objectPlanReclaimInlineCompletePngAfterTextDemotion`):
- 그룹 plan: `OWNED_BY_PNG + PLACE_INLINE_PNG + COMPLETE_PNG`,
  `completePngTextAllowed=true`, ownedTextFrameIds 복원
- canonical 라벨 셸 plan 들은 강등(DROP) — 중복 렌더 방지
- **export 는 그룹 루트 단독** (`exportSourceObjectIds=[primary]`): 소스 15개를
  개별 복제→재그룹하면 앵커 해제로 위치가 흩어져 캔버스가 97×64mm 로 부풀고
  그림이 작게 앉는다. 루트 단독이면 그룹 통째 복제 export(배지 검증 경로)로
  캔버스 == 그룹 bounds.
- 판정 헬퍼: `_objectPlanRescuedVisualRootsAfterTextDemotion` — claimed(셸 소유)
  서브트리를 피해 하강하며 시각 재료를 가진 clean 루트 탐지.

### Java (`normalizer/resolved/phase3/InlineFrameHandler.java`)

1. `applySingleInlineShellChildTextMargins` — plan bounds 를 pt 로 환산해 TF
   geometric(pt)과 **같은 공간에서 직접 차분** (containsBounds 통과 시).
   기존 정규화 폴백은 유지. → 라벨 cellMargin 176pt → 5~16pt 정상화.
2. `loadInlineObject` 통짜 PNG 알파 가시영역 재스케일에 **크기 게이트**
   (`bh ≤ 50pt && bw ≤ 100pt`): 작은 배지/마커 전용 보정이 큰 삽화에 적용되면
   plan bounds 크기가 왜곡된다. 작은 배지는 기존 동작 그대로.

## 검증 (p47 재추출 → 변환 → HWPX + 한글 육안)

- 통짜 PNG: 캔버스 종횡비 1.854 == 그룹 1.853, 그림+라벨 4개+지시선 포함
- 배치 74.0×39.9mm == 원본 그룹 크기, flow 에 라벨 테이블 잔존 0
- 라벨 cellMargin 정상화 (17609hu → 1514hu 등)
- p19 회귀 없음 (SPEC-048 게이지/SPEC-056 답란)
- 한글 육안 확인 완료

## 함정 메모

- **plan bounds(mm) vs TF geometricBounds(pt)** — resolved TF 좌표는
  normalizeToPoints 로 pt 지만 object-plans.json 의 plan bounds 는 추출기
  원단위(mm)다. 둘을 섞는 좌표 산술은 조용히 유령 오프셋을 만든다. 차분 계산은
  같은 단위로 환산 후 할 것.
- **`_duplicateNestedCompletePngForExport` 는 exportSourceObjectIds 개수에
  민감**: 여러 자식 id 를 주면 개별 복제→재그룹으로 캔버스가 부푼다. 그룹
  전체 export 는 루트 id 하나만.
- 텍스트 중복 강등(`textOwnershipResolution`)은 plan 의 시각 소유까지 지울 수
  있다 — demote 전에 "이 plan 만 아는 시각 소스" 존재를 확인할 것.
