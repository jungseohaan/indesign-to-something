# SPEC-021: 중첩 뱃지 추출 (Nested Badge Extraction)

## 문제

InDesign에서 **뱃지(Oval/Polygon + 짧은 숫자·라벨 텍스트)** 가 **별도 Group으로 분리되지 않고**
질문 본문 텍스트와 같은 Group에 들어있는 경우, 현재의 그룹 단위 `isBadgeGroup` 검사가 실패하여
별도 PNG로 추출되지 않고 페이지 배경(page_bg)에 구워집니다. 배경에 구워지면 HWPX 출력에서
뒤에 배치된 편집 가능한 텍스트박스/이미지에 의해 가려질 수 있습니다.

### 재현 케이스 — 중3영어교과서 2단원 p48 (Spread_u3695)

| 문제번호 | 그룹 구조 | 현재 결과 |
|---------|----------|----------|
| Q1 | `Group u3933 { Group u394a { Oval, TextFrame "1" }, TextFrame (질문) }` | ✅ `badge_14666.png` 로 추출 |
| Q2 | `Group u3905 { Oval, TextFrame "2", TextFrame (질문 29자) }` | ❌ 배경에 구워짐 |
| Q3 | `Group u39dd { Oval, TextFrame "3", TextFrame (질문) }` | ❌ 배경에 구워짐 |
| Q4 | `Group u3995 { Oval, TextFrame "4", TextFrame (질문) }` | ❌ 배경에 구워짐 |
| Q5 | `Group u38a4 { Oval, TextFrame "5", TextFrame (질문) }` | ❌ 배경에 구워짐 |
| Q6 | `Group u3843 { Oval, TextFrame "6", TextFrame (질문) }` | ❌ 배경에 구워짐 |

`isBadgeGroup` 탈락 사유:
- 총 텍스트 길이 29자 > `maxTextLength: 20`
- 그룹 short-side ≈ 132pt > `maxSize: 50`

"Listen + View" (u38d2), "Speak + Present" (u38eb) 말풍선 태그도 유사한 이유로 탈락:
- `Group { Rectangle(82pt×21pt), Polygon(포인터 10pt×8pt), TextFrame("Listen+View") }`
- 종횡비 82/26 ≈ 3.1 > `maxDim/minDim <= 3.0`
- 결과적으로 배경에 구워지지만 텍스트가 `Corporate Rounded` 폰트 이슈 등으로 빈 말풍선만 보임

## 목표

- Q2~Q6 번호 뱃지가 **`badge_*.png` 별도 PNG**로 추출되어 z=10 IN_FRONT_OF_TEXT 로 배치됨
- 원본 배경 PNG에서는 해당 뱃지가 제거되어 이중 렌더링 방지
- 말풍선 태그("Listen + View", "Speak + Present" 등)도 같은 경로로 추출 (종횡비 완화)
- 기존 Q1 (`u394a`), "Grammar"/"Word" 등 이미 작동하는 뱃지는 회귀 없이 유지

## 해결 방안

### Phase 1: 뱃지 패턴 감지 (`findNestedBadgePatterns`)

현재 `isBadgeGroup`은 **Group 전체를 하나의 뱃지 후보**로만 판정. 이를 다음과 같이 확장:

1. **Group 단위 통과** (기존 로직) — 현행 `isBadgeGroup` 를 통과하면 그룹 전체를 뱃지로.
2. **서브 뱃지 패턴 탐색** (신규) — 그룹이 통과 실패해도 그룹 내에서 다음 패턴을 찾음:
   - 하나의 `Oval`/`Polygon`/`Rectangle` (뱃지 도형 후보)
   - 해당 도형의 `geometricBounds` 안에 **완전히(또는 90% 이상) 포함**되는 TextFrame 하나
   - 포함된 TextFrame 의 텍스트 길이 ≤ 3 (번호/짧은 라벨)
   - 도형의 short side ≤ 50pt, 종횡비 ≤ 3:1

패턴이 감지되면 `(뱃지도형, 텍스트프레임)` 페어를 **서브뱃지**로 기록.

### Phase 2: 종횡비 한도 완화

말풍선 태그를 수용하기 위해 `CONFIG.rendering.badge.maxAspectRatio` 설정을 추가 (기본 **4.5**, 현행 하드코딩 3.0).
`isBadgeGroup` 의 `maxDim / minDim > 3.0` 체크를 `> cfg.maxAspectRatio` 로 교체.

### Phase 3: 서브뱃지 렌더링 (`renderSubBadge`)

`exportRenderedTextFrames`의 Pass 1 직후 서브뱃지 패스 추가:

```javascript
// 의사코드
for each (badgeShape, textFrame) in subBadgePatterns:
    parentGroup = badgeShape.parent;  // 보통 질문 Group
    // 페어 외 모든 형제를 임시 숨김
    hidden = [];
    for sibling in parentGroup.allPageItems:
        if sibling !== badgeShape && sibling !== textFrame && sibling !== parentGroup:
            if sibling.visible:
                sibling.visible = false;
                hidden.push(sibling);
    try:
        parentGroup.exportFile(PNG_FORMAT, "badge_<groupId>.png");
        // bounds는 badgeShape.visibleBounds 사용 (페어만 포함)
    finally:
        for item in hidden: item.visible = true;
    // badgeChildIds 에 badgeShape.id, textFrame.id 등록 → 배경에서도 숨김
```

기존 [scripts/extract_indd.jsx:718-733](scripts/extract_indd.jsx#L718-L733) `hiddenEditable` 패턴과 동일한 try/finally 복원 구조 사용.

### Phase 4: 배경 렌더링에서 제외

[scripts/extract_indd.jsx:2650-2658](scripts/extract_indd.jsx#L2650-L2658) 의 `framesToHide` 루프에 서브뱃지 구성원(`badgeShape`, `textFrame`)도 추가. 현재는 `isBadgeGroup` 통과 그룹만 배경에서 숨겨짐.

### 감지 우선순위 — 중복 방지

- Phase 1-①(기존 Group 단위) 통과 시 Phase 1-②(서브 패턴) 는 건너뜀
- Phase 1-② 에서 매칭된 도형/TF 는 `badgeGroupChildIds` 에 등록되어 Pass 2 개별 렌더에서 제외 (기존 로직 재사용)

## 영향 범위

- 중3영어교과서 2단원 p48 Q2~Q6 번호 뱃지
- 동일 구조를 쓰는 다른 단원의 번호 뱃지
- "Listen + View", "Speak + Present", "Read + Write" 말풍선 태그
- 기타 Group 안에 긴 텍스트와 함께 들어있는 짧은 라벨 뱃지

## 수정 파일

| 파일 | 변경 내용 |
|------|----------|
| `scripts/extract_indd.jsx` | `findNestedBadgePatterns()` 추가, `isBadgeGroup` 종횡비 설정화, `exportRenderedTextFrames` Pass 1.5 (서브뱃지 렌더), `collectResolved` 배경 숨김 대상에 서브뱃지 페어 추가 |
| `conversion-config.json` | `rendering.badge.maxAspectRatio` 기본값 4.5 추가 (선택) |

Java 쪽은 기존 `badge_group` / `badge_group_child` 경로를 재사용하므로 **수정 불필요** (서브뱃지도 동일한 `type: "badge_group"` entry 로 rendered_frames 에 등록).

## 검증

- [x] `mvn clean package -q -DskipTests` 빌드 성공 (Java 변경 없음)
- [x] ExtendScript 재실행 후 p48 `rendered_frames/` 에 `badge_14597.png` (Q2 u3905), `badge_14813.png` (Q3 u39dd), `badge_14741.png` (Q4 u3995), `badge_14500.png` (Q5 u38a4), `badge_14403.png` (Q6 u3843) 생성. 5개 모두 Pass 1.5 (서브 뱃지) 경로로 매칭, 각 entry `childIds` 길이 = 2 (도형+숫자 TF)
- [x] `badge_14546.png` ("Listen + View" u38d2) 생성 — `maxAspectRatio` 완화(3.0→4.5)로 Pass 1 (그룹 단위 `isBadgeGroup`) 매칭. 페이지 48 (pageIndex=18)
- [x] `badge_14571.png` ("Read + Write" u38eb) 생성 — 페이지 49 (pageIndex=19). SPEC 본문에 "Speak + Present" 라고 적혔지만 실제 그룹 내용은 "Read + Write" 였음
- [x] `resolved.json renderedFloatingItems` 에 7개 모두 `type: "badge_group"` 으로 등록됨 (Q2~Q6 childIds=[shape, tf]; Listen+View/Read+Write childIds=[전체 그룹 멤버])
- [ ] `page_bg_18.png` 에서 Q2~Q6 번호 원 및 말풍선이 제거됨 (PNG 픽셀 비교 또는 육안 확인 필요)
- [ ] HWPX 출력에서 "2" 번호 뱃지가 더 이상 가려지지 않음 (Hancom Office 육안 확인)
- [ ] 중3국어(박영민) 1p "문학"/"쓰기" 뱃지 등 기존 케이스 회귀 없음
- [ ] 2단원 전체 페이지 변환 시 inline_* 개별 추출 개수 유의미한 증가 없음

## 구현 메모

- SPEC의 Pass 1.5 의사코드(`형제 임시 숨김 → 부모 그룹 exportFile`) 대신 **페어 duplicate → 임시 그룹 묶음 → export → 임시 그룹 제거** 방식으로 구현.
  사유: `doc.groups.add([원본도형, 원본TF])` 는 이미 다른 그룹 소속 페이지 아이템에 대해 "매개변수 잘못됨" 에러를 던져 실패. duplicate 는 원본 상태를 전혀 건드리지 않으므로 try/finally 복원 로직도 불필요.
- `findNestedBadgePattern()` 은 `group.allPageItems` 대신 **직속 자식(rectangles/ovals/polygons/textFrames 컬렉션 + parent 체크)** 만 검사 — 재귀 시 손자 레벨에서 오매칭 위험.
- `conversion-config.json` 에 `maxAspectRatio: 4.5`, `nestedEnabled: true`, `nestedMaxTextLength: 3` 추가 (주석 포함).
- 검증 시 SPEC에 적힌 hex ID(u38eb 등)는 `parseInt("38eb",16)=14571` 로 변환된 decimal ID가 PNG 파일명에 사용됨.
- "Speak + Present"는 SPEC 작성 시점의 오타로 추정 — 실제 그룹 u38eb 의 텍스트는 "Read + Write".

## 상태: 구현 및 ExtendScript 검증 완료 — HWPX 육안 검증 대기
