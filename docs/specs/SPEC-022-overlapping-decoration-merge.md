# SPEC-022: 뱃지 위에 떠 있는 외곽선 데코 합성

## 문제

InDesign에서 **뱃지 그룹과 시각적으로 한 덩어리지만 구조적으로는 별개**인 데코(주로 outlined text — 폰트가 폴리곤으로 변환된 글자) 가
뱃지 PNG에 포함되지 않고 페이지 배경(page_bg)에만 렌더링되어, **뱃지를 위에 덮어쓸 때 데코가 가려지는 현상**.

### 재현 케이스 — 중3영어교과서 2단원 p46 (Spread_u2f14)

```
Group u2f8d (조부모, 긴 안내문 포함)
└── Group u2fa4 (Step 1 + Survey 카드 합성)
    └── Group u2faa  ← 뱃지로 매칭됨 (Survey only)
        ├─ Rectangle u2fab    (노란-초록 카드 본체)
        ├─ TextFrame u2fbf    "Survey"
        ├─ GraphicLine u2fc2  (수평 구분선)
        └─ Polygon u2fc3      (모서리 폴드)

Group u2f2f  ← 스프레드 최상위, 뱃지와 시각적으로 인접
├─ Polygon u2f30 (76 points) ─┐
├─ Polygon u2f31 (76 points)  │ "Step 1" 글자 외곽선
└─ ... (12개 폴리곤)            ┘
```

- `isBadgeGroup` 은 `u2faa` 만 매칭 → `badge_12202.png` 추출
- `u2f2f` ("Step 1" 외곽선) 은 별도 매칭 없이 `page_bg_16.png` 에 렌더링됨
- HWPX 출력 시 `badge_12202.png` 가 z=10 IN_FRONT_OF_TEXT 로 배치되어 `page_bg` 의 "Step 1" 영역을 **불투명하게 덮음** → "Step 1" 이 가려짐

원본 시각적 의도: "Step 1" 헤더 + Survey 카드가 한 디자인 단위 → 뱃지 PNG 에 같이 들어가야 함.

## 목표

- 뱃지 그룹과 **시각적으로 겹치는 외부 형제 아이템**(특히 outlined text 폴리곤) 을 자동 감지하여
  - (a) 뱃지 PNG 에 함께 포함시켜 단일 이미지로 추출
  - (b) `page_bg` 에서 해당 아이템을 숨겨 중복 렌더링 방지
- p46 Survey 뱃지 + Step 1 외곽선이 한 PNG 로 합쳐짐
- 다른 페이지의 동일 패턴(Step 2/3 라벨 등) 자동 처리
- 기존 단일 뱃지 케이스 회귀 없음

## 해결 방안

### Phase 1: 인접 데코 감지 (`findOverlappingDecorations`)

Pass 1 에서 뱃지 그룹이 매칭된 직후, 다음 조건의 형제 아이템들을 수집:

1. **위치 조건**: 아이템의 `visibleBounds` 가 뱃지의 `visibleBounds` 와
   - 50% 이상 겹치거나, 또는
   - 뱃지 위/아래로 5pt 이내 인접하면서 가로 범위가 50% 이상 겹침
2. **타입 조건**: `Polygon` 또는 `Polygon`만 들어있는 `Group` (outlined text 패턴)
   - `TextFrame`(편집 가능한 텍스트) 은 제외 — 이미 다른 경로로 처리됨
   - `Image/EPS/PDF` 는 제외 — 본문 이미지일 가능성 높음
3. **크기 조건**: 아이템의 minDim ≤ 뱃지 maxDim × 1.5 (너무 큰 데코 제외)

### Phase 2: 합성 렌더링 (`renderBadgeWithDecorations`)

기존 Pass 1 의 단일 그룹 export 대신:

```javascript
// 의사코드
if (overlapping.length > 0) {
    var dups = [];
    dups.push(grp.duplicate());
    for (var ov in overlapping) dups.push(ov.duplicate());
    var tempGrp = doc.groups.add(dups);
    var combinedBounds = unionBounds(grp.visibleBounds, ...overlapping.bounds);
    try {
        tempGrp.exportFile(PNG_FORMAT, "badge_<id>.png");
    } finally {
        tempGrp.remove();
    }
    // childIds 에 overlapping 아이템의 id 등록 → page_bg 에서 숨김
} else {
    // 기존 단일 그룹 export 로직
    grp.exportFile(...);
}
```

기존 SPEC-021 Pass 1.5 에서 검증된 **duplicate → 임시 그룹 → export → 제거** 패턴 재사용.

### Phase 3: 배경 숨김 (`exportPageBackgrounds`)

[scripts/extract_indd.jsx:2867-2881](scripts/extract_indd.jsx#L2867-L2881) `framesToHide` 에 overlapping decoration 들도 추가
(이미 `badgeChildIds` 에 등록되어 있으므로 기존 hide 로직 재사용 가능).

### 설정

```jsonc
"badge": {
    ...
    "decorationMerge": {
        "enabled": true,
        "minOverlapRatio": 0.5,
        "adjacencyTolerance": 5  // pt
    }
}
```

## 영향 범위

- 중3영어교과서 2단원 p46 "Step 1 + Survey", p46 "Step 2 + ?", p47 "Step 3"
- 동일 패턴: outlined text 라벨 + 뱃지 디자인이 분리되어 있는 모든 페이지
- 기존 `badge_*` 추출 로직: 겹치는 데코가 없을 때는 동일 동작 유지

## 수정 파일

| 파일 | 변경 내용 |
|------|----------|
| `scripts/extract_indd.jsx` | `findOverlappingDecorations()` 추가, Pass 1 에 합성 렌더링 분기, 배경 숨김 대상 확장 |
| `conversion-config.json` | `rendering.badge.decorationMerge` 섹션 추가 (선택) |

Java 쪽은 기존 `badge_group` 경로를 그대로 사용 — 수정 불필요.

## 검증

- [x] `mvn clean package -q -DskipTests` 빌드 성공 (Java 변경 없음)
- [x] p46 `rendered_frames/badge_12202.png` 에 "Step 1" 외곽선 포함됨 — Polygon u2fc4 (id=12228) 이 같은 page 의 인접 데코로 매칭되어 합성 PNG 생성
- [x] `page_bg_16.png` 에 "Step 1" 사라짐 (badgeChildIds 등록 → exportPageBackgrounds 의 `framesToHide` 에서 숨김)
- [ ] HWPX 출력에서 "Step 1" 이 Survey 뱃지 위에 보임 (Hancom Office 육안 확인)
- [ ] p46 "Step 2", p47 "Step 3" 도 같은 패턴으로 처리됨 (별도 페이지 검증 필요)
- [ ] 기존 단순 뱃지(예: Q1 번호 뱃지) 회귀 없음
- [ ] outlined text 가 아닌 큰 본문 이미지가 잘못 합쳐지지 않음

## 구현 메모

- **page 단위 필터 필수**: 처음 구현 시 `doc.allPageItems` 가 모든 페이지 아이템을 반환하기 때문에 다른 페이지의 우연한 좌표 겹침으로 잘못 매칭됨 (예: 30페이지 그룹이 46페이지 뱃지와 좌표상 겹침). `parentPage.id` 비교로 같은 page 인 후보만 허용.
- **adjacency 기본값**: 처음 5pt(=1.76mm) 였으나 실제 "Step 1" 라벨과 카드 사이 간격이 5.81mm 였음 → 기본 20pt(≈7mm) 로 상향. 더 큰 라벨에는 conversion-config.json 으로 추가 조정 가능.
- **합성 export 방식**: SPEC-021 의 `duplicate → 임시 그룹 → exportFile → 임시 그룹 제거` 패턴을 그대로 사용. 원본 페이지 아이템은 건드리지 않음.
- **데코 후보 자격 (조상 체인 허용)**: `parent === Spread/Page/MasterSpread` 또는 `parent.id ∈ 뱃지 조상 체인(부모/조부모 …)`. 처음에는 직속 부모 Group 만 허용했으나 다음 케이스가 발견됨:
  - **Step 1 (u2fc4)**: 뱃지 `u2faa` → 부모 `u2fa4` → 조부모 `u2f8d` → Spread. 데코는 Spread 직속 → topLevel 허용으로 매칭.
  - **Step 2 (u2f8a)**: 뱃지 `u2f70` → 부모 `u2f69` → Spread. 데코는 부모 Group `u2f69` 의 형제 → 직속 부모 허용으로 매칭.
  - **Step 3 (u3143)**: 뱃지 `u3129` → 부모 `u3127` → 조부모 `u3126` → 증조부 `u310f` → Spread. 데코는 조부모 `u3126` 의 직속 자식 → 조상 체인(최대 6 hop) 까지 허용.
- 매칭 데코는 `badgeGroupChildIds` 에 등록되어 SPEC-021 의 hide 로직으로 자동 처리됨.
- **부작용 가능성**: 조상 체인 허용으로 가까이 있는 작은 폴리곤(아이콘 등)이 같이 매칭될 수 있음. 예: p47 Self-Check 뱃지 위 cloud-upload 아이콘. 시각적으로 디자인 의도와 일치하면 OK, 아니면 `decorationMergeAdjacency` 를 페이지별로 줄이거나 추가 필터(점 수 / 색상 분석) 도입 검토.

## 상태: 구현 완료 — HWPX 육안 검증 대기 (Step 1/2/3 모두 합성 OK, Self-Check + cloud 아이콘 merge 의도 확인 필요)
