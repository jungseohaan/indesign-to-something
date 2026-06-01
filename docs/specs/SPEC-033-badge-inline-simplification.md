# SPEC-033: 배지 인라인 처리 단순화 — textHiddenBeforeExport 의존성 제거

> 작성: 2026-06-01. 상태: **구현 완료 (2026-06-01) / 미테스트**.
> 관련: SPEC-028 (인라인 앵커 Group 중복), SPEC-025 (텍스트 이미지 렌더링 제거)

## 문제

### 1. SPEC-028 미해결 — inline 앵커 ③ 중복 렌더링

페이지 46(박현숙 1단원 소(2)) `③ 매체 자료의 공정성 평가 기준` 타이틀에서:
- Phase 7 → `badge_group` PNG floating 배치
- Phase 3 → 같은 Group을 inline PIC로도 임베드 (비율 왜곡, 6.48×12.27pt)

→ 큰 ③ floating + 작은 "●" PIC가 동시 표시, 줄바꿈 유발.

### 2. textHiddenBeforeExport 조건 분기 과다

extract_indd.jsx가 badge PNG 내보내기 전 inline TF를 **skip했던 로직**이 Java로 전달되어:
- `badgePngHasTextBaked = !isTextHiddenBeforeExport()` 분기 (Phase 2)
- `hasBadgePng = isTextHiddenBeforeExport()` 분기 (Phase 3)
- `loadInlineObject`에서 `return null` (Phase 3)

→ 코드 가독성 저하, 새 케이스 추가 시 모든 분기에 패치 필요.

## 해결 방향

### A. extract_indd.jsx: inline TF도 badge PNG export 전 hide

```diff
- if (isInlineItem(__hItem)) continue; // inline TF는 투명 구멍 방식으로 텍스트 표시
```

→ **모든 TF 텍스트**가 badge PNG export 전 숨겨짐 → `textHiddenBeforeExport`는 항상 true.

### B. Java Phase 3: tryInlineGroupAsSingleBadge 우선 시도

기존 `tryInlineGroupAsSingleBadge`는 이미 inline_object PNG 또는 badge_group PNG가 있으면 `return null`로 위임했다. 변경 방향:
- PNG 위임 early-return **제거** → 항상 HWPX native INLINE_TEXT_FRAME 시도
- 성공 시: INLINE_TEXT_FRAME (텍스트 검색 가능)
- 실패 시(자식 TF 없음, 배경 도형 없음): 기존 PNG fallback

→ SPEC-028 해결: inline ③ Group이 INLINE_TEXT_FRAME으로 처리되면, Phase 7의 badge_group PNG skip 조건(`alsoInline`)이 발동 → 중복 방지.

### C. Java: textHiddenBeforeExport 조건 단순화

| 위치 | 변경 전 | 변경 후 |
|------|---------|---------|
| `FramePlacer` | `badgePngHasTextBaked = !isTextHiddenBeforeExport()` 로 phase3Reachable 판별 | 제거 (항상 INLINE_TEXT_FRAME 시도) |
| `InlineFrameHandler.tryInlineGroupAsSingleBadge` | `hasBadgePng = isTextHiddenBeforeExport()` → 텍스트 길이 제한 해제 | `hasBadgePng` = badge_group PNG가 있기만 하면 됨 (textHidden 체크 제거) |
| `InlineFrameHandler.loadInlineObject` | `textHiddenBeforeExport=false` 시 `return null` | 제거 (항상 통과) |

`textHiddenBeforeExport` 필드는 `RenderedGroup`에 보존 (하위 호환, 기존 resolved.json).

### D. 디버그 로그 제거

`[DBG-Badge]` System.out.println 17개 → 제거.

## 수정 파일

1. `scripts/extract_indd.jsx` — inline TF hide skip 제거 (1줄 삭제, 이미 uncommitted에 포함)
2. `src/.../phase3/InlineFrameHandler.java` — tryInlineGroupAsSingleBadge early-return 제거, DBG 로그 제거
3. `src/.../phase2/FramePlacer.java` — badgePngHasTextBaked 제거 (이미 uncommitted에 포함)
4. `src/.../phase7/RenderableFramePlacer.java` — 주석 정리

## 검증

- [ ] 빌드 성공: `mvn clean package -q -DskipTests`
- [ ] 페이지 46: ③ 하나만 렌더링 (floating 없이 inline 또는 inline 하나만)
- [ ] 다른 배지 페이지(단순/섹션 배지) 회귀 없음
- [ ] [DBG-Badge] 로그가 콘솔에 출력되지 않음
