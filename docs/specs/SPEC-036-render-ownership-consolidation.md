# SPEC-036: Render Ownership Consolidation (SPEC-035 실행 일원화)

> 상태: In Progress (2026-06-14 시작)
> 전제: [SPEC-035](SPEC-035-indesign-render-ownership.md)가 정의한 ObjectPlan을 **실행 단계의 단일 권위**로 승격한다.
> 원칙: 휴리스틱에 예외를 더하지 않는다. 결정은 Planner로 모으고, 실행기는 plan을 따른다.

---

## 문제

렌더 그래픽(page_object/inline_object)의 "배치 vs 억제(suppress)" 결정이 한 파일에 누적되어 통제 불능 상태다.

- `phase6/BackgroundInjector.java` = **3,182 LOC, boolean 헬퍼 58개, SKIP 결정 ~20종**.
- "이 렌더를 floating PNG로 배치할까?"라는 **단일 질문**에 **22가지 서로 다른 신호**가 동원된다:

| 신호 종류 | 개수 | 예 |
|---|---|---|
| 공유 상태 Set | 6+1 | `phase6PlacedIds`, `deferredAnchoredFloatingIds`, `inlineCompleteSimpleButtonLabelIds`, `inlineEditableLabelShellIds`, `cellInlineEmbeddedDomIds`, `customAnchoredInlineIds`(死), `inlineObjectDomIds` |
| ObjectPlan enum | 2 | `visualAction`, `placement` |
| reason 문자열 | 다수 | `image_group_text_hidden`, `complex_graphic_text_hidden`, `editable_label_shell` … |
| boolean 휴리스틱 | 58 | `shouldKeepPairedInlinePageShell`, `canSuppressChildren`, `shouldPreserveSourceChild` … |
| Disposition enum | 2 | `TEXT_BLOCK_PLACED`, `PNG_CONVERT_TO_FLOATING` |

### 근본 원인 — 미완성 마이그레이션

`OwnershipPlanner` 헤더 주석:

> "현재 legacy Phase의 동작을 바꾸지 않고 ObjectPlan과 invariant warning을 기록한다(**관찰 모드**). 이 로그가 안정화되면 Phase 2/3/6/7의 분산 판단을 plan 실행으로 옮긴다."

이 이전이 멈춰 **두 결정 시스템이 공존**한다.
- Planner: 모든 항목에 `visualAction`을 부여하지만, Phase 6/7은 **`DROP_VISUAL`만 존중**하고 긍정 지시(PLACE_*)는 58개 휴리스틱으로 재판정·override.
- 레거시 휴리스틱: childOfGroup, coveredByInlineObjects, paired shell, label shell …

**증상 사례**: page 53 "한겨울 나뭇가지" 캡션 위 일러스트 누락(2026-06-14). Planner는 `image_group_text_hidden`을 알았지만, 별도 휴리스틱(`childOfGroup`+`coveredByInlineObjects`)이 plan과 무관하게 억제 → 둘이 어긋나 영영 사라짐. 임시 수정은 휴리스틱에 예외를 **하나 더 추가**(신호를 줄이지 못하고 늘림).

---

## 목표

1. **단일 권위 plan**: `ObjectPlan.visualAction`(+`visualLayer`,`zOrder`,`placement`)이 배치/억제의 유일한 결정자.
2. **얇은 실행기**: Phase 6/7은 "plan이 floating PNG 배치하라 했나?"만 질의하고 좌표·크롭·배치만 수행.
3. **단일 결정 함수**: Phase 6/7이 같은 `VisualPlacementResolver`를 호출 → 조건 중복·GAP 제거.
4. 22 신호 → 1(`plan.visualAction`). BackgroundInjector 3,182 → ~1,000 LOC.
5. plan↔휴리스틱 충돌형 버그(일러스트 누락류) **구조적 재발 불가**.

### 목표 구조

```
OwnershipPlanner  →  ObjectPlan { visualAction, visualLayer, zOrder, placement }   ← 유일한 결정자
        │
        ▼
VisualPlacementResolver.decide(rg) → PLACE(z,layer) | SKIP(reason)                 ← 공용 결정 함수
        │
   ┌────┴────┐
 Phase6     Phase7   ← 실행만 (좌표/크롭/오버플로우/alpha는 실행기에 잔류)
```

핵심 원칙: **억제 = `visualAction != PLACE_FLOATING_PNG`** 하나로 수렴. "셀 임베드/인라인이 덮음/부모가 구움/완성형 배지"는 전부 *"시각을 다른 채널이 소유"* → Planner가 `DROP_VISUAL` 또는 `placement=INLINE`으로 인코딩.

---

## 해결 방안 (3 Tier, 점진적·골든디프 안전)

각 단계는 **캐릭터화 → 이전 → 삭제**. 매 단계 골든디프(`Contents/section0.xml` md5) 0 또는 *의도된 변화만* 검증.

### Tier 0 — 캐릭터화 하네스 (선행)
- `render-decisions.jsonl`은 이미 항목별 `planVisualAction`/`planTextAction` + 실제 SKIP/PLACE 결과를 기록한다(`recordRenderedDecision`).
- **불일치 리포트 생성기**(`scripts/render_decision_audit.py`): plan과 실제 결정이 어긋나는 항목 추출.
  - plan=`PLACE_FLOATING_PNG` 인데 실제 `SKIP_*` → **누락 위험**(일러스트 버그류)
  - plan=`DROP_VISUAL` 인데 실제 `PLACE` → **중복 위험**
  - plan=`PLACE_TEXT_SHELL`/`PLACE_INLINE_PNG` 인데 floating PLACE → 채널 혼동
- 회귀 기준선: 대표 문서 N개의 `section0.xml` 골든 해시 고정.

### Tier 1 — 무행동변경 구조 정리 (저위험, 골든디프 0 목표)
1. **`VisualPlacementResolver` 추출**: BackgroundInjector 주 루프의 **이른 suppress 게이트**(ownership/Set/childPolicy/inline-coverage 판정, lines ~118–228)를 `decide(rg) → PLACE|SKIP(reason)` 단일 함수로 *순수 이동*. 실행기는 결과만 분기.
   - 좌표·크롭·오버플로우·alpha·SKIP_OUTSIDE_PAGE 등 *실행 단계* skip은 실행기에 잔류.
2. **Phase 7을 같은 resolver로 라우팅**: Phase 7 중복 조건(`shouldDropVisual`/`shouldPlaceFloatingVisual`/`isInlineObjectId`)을 resolver 호출로 대체 → `cellInlineEmbeddedDomIds` GAP 자동 해소.
3. **死 코드 제거**: `customAnchoredInlineIds`(미사용) 삭제.
4. **Set 의미 통합 검토**: `inlineCompleteSimpleButtonLabelIds`/`inlineEditableLabelShellIds`/`cellInlineEmbeddedDomIds`가 모두 "*visual owned by inline channel*"이면 단일 개념으로 병합.

### Tier 2 — Planner 권위화 (핵심, 캐릭터화 주도)
Tier 0 불일치 클래스를 빈도순으로 하나씩:
1. Planner `visualActionOf()` 보강 → 올바른 결과를 plan에 인코딩 (예: 텍스트셸 부모 아래 실제 이미지 자식 = `isRenderedImageFrameDomId` 판정을 **Planner로 이전**).
2. 대응 휴리스틱을 resolver의 `plan.visualAction` 질의로 대체하고 **삭제**.
3. 골든디프로 *그 클래스만* 변하는지(또는 0) 검증.
- 우선순위: 배지 inline/page 쌍 → 텍스트셸/이미지자식 → 라벨셸 → master_graphic.

### Tier 3 — 잔여 정리
- 이전 완료로 dead가 된 헬퍼 삭제(데드코드 분석).
- BackgroundInjector를 *실행*만 남겨 결정 로직 0.
- 문서 동기화([docs/architecture.md](../architecture.md), [CLAUDE.md](../../CLAUDE.md), 본 INDEX).

---

## 수정 파일

- `scripts/render_decision_audit.py` — (신규) 불일치 리포트 생성기 [Tier 0]
- `converter/.../phase6/VisualPlacementResolver.java` — (신규) 공용 결정 함수 [Tier 1]
- `converter/.../phase6/BackgroundInjector.java` — suppress 게이트를 resolver 호출로 대체 [Tier 1~3]
- `converter/.../phase7/RenderableFramePlacer.java` — 동일 resolver 라우팅 [Tier 1]
- `converter/.../normalizer/resolved/ResolvedBuildContext.java` — 死 Set 제거, Set 통합 [Tier 1]
- `converter/.../ownership/OwnershipPlanner.java` — visualAction 분류 보강 [Tier 2]

---

## 검증

- [ ] Tier 0: 불일치 리포트가 page 53 일러스트류 충돌을 데이터로 식별
- [ ] Tier 1: 골든디프 0 (순수 이동 증명), Phase 7 GAP 해소
- [ ] Tier 2: 클래스별 *의도된 변화만* 골든디프에 반영
- [ ] 텍스트 손실 0, `StoryConverterTest`/`OwnershipPlannerTest` 기존 실패 외 신규 0
- [ ] BackgroundInjector LOC 3,182 → 목표 ≤ 1,200

---

## 관련

- 선행: [SPEC-035](SPEC-035-indesign-render-ownership.md) (ObjectPlan 정의·관찰 모드)
- 연관: [SPEC-002](SPEC-002-large-file-split.md)(대형 파일 분할), [SPEC-003](SPEC-003-zorder-semantic-layers.md)(z 레이어)
