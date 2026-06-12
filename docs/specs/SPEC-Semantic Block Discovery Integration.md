# SPEC-SBD-001

# Semantic Block Detector Pipeline

Version 1.0

## 현재 결정 사항

1. 구현 위치는 Java converter 내부로 한다.

2. 입력 시점은 HWPX 변환 직전의 Java ASTDocument이다.

3. 개발 중에는 항상 `.semantic-blocks.json`을 생성한다. 안정화 후 옵션화한다.

4. 출력은 기존 semantic-layer가 아니라 새 타입 `SemanticBlock`이다.

5. v1 범위는 Stage 1~5이다. Block Classifier와 Confidence Scorer는 약한 힌트와 검수 보조값으로만 둔다.

6. 우선순위는 `block_type` 정확도보다 `member_ids` 정확도이다.

7. UI는 먼저 block 목록/페이지 뷰를 만들고, block 클릭 시 member object를 highlight한다.

8. 규칙은 JSON rule profile로 외부화한다.

9. 필요하면 ASTDocument와 함께 ResolvedBuildContext, OwnershipPlan의 read-only 정보를 참조할 수 있다.

---

## 목적

Layout AST의 Physical Object를 분석하여 교육 콘텐츠의 Semantic Block을 생성한다.

Semantic Block은 이후 Structure Detector의 입력이 된다.

---

## 고도화 플랜

### Phase A: AST-only precision pass

목표는 추가 입력 없이 현재 ASTDocument만으로 과병합과 잡음 member를 줄이는 것이다.

1. Page profile을 만든다.
   - page size, margin, column 수를 읽는다.
   - 페이지 상단/하단의 짧은 반복 텍스트를 header/footer 후보로 표시한다.
   - 순수 쪽번호 텍스트를 SemanticBlock 후보에서 제외한다.

2. Anchor detection을 강화한다.
   - paragraph style, text keyword, question prefix 외에 대표 font size와 짧은 제목형 텍스트를 반영한다.
   - `concept_start`, `activity_start`, `question_start`, `example_start`, `generic_start`를 실제로 구분한다.
   - caption/source/page-label 성격의 텍스트는 anchor에서 제외한다.

3. Candidate builder의 과병합을 줄인다.
   - 명시 anchor가 없더라도 현재 후보와 y gap이 과도하게 크면 새 fallback block을 시작한다.
   - column jump가 큰 경우 fallback block을 분리한다.
   - 같은 group/container 신호는 merge 우선순위를 높인다.

4. Review output을 풍부하게 한다.
   - `member_boxes`를 필수 리뷰 정보로 취급한다.
   - block-level bbox는 fallback으로만 사용하고, UI는 member-level highlight를 우선한다.

### Phase B: Enriched object graph pass

목표는 Resolved/OwnershipPlan 신호를 read-only로 받아 정확도를 높이는 것이다.

1. ObjectPlan의 text/visual ownership, placement, visualLayer를 Semantic Block input으로 제공한다.
2. sourceObjectIds 기반 parent-child 관계와 visible output 중복 정책을 block 후보에 반영한다.
3. container/background/label/outline 후보를 별도 relation graph로 제공한다.
4. caption/source/image 관계를 precompute한다.

구현 현황:

- `resolvedData`를 detector에 read-only hint로 전달한다.
- rendered item id, `page_obj_*`, `page_object_*`, IDML hex id, decimal DOM id를 같은 source key 집합으로 정규화한다.
- `editableTextFrameIds`와 `sourceObjectIds` 관계가 있으면 좌표상 멀어도 visual을 해당 block에 강하게 attach한다.
- `placementAllowed=false` 또는 resolved ownership상 skip 대상인 visual은 review member에서 제외한다.
- 결과 JSON의 `source.semantic_context`와 `signals.resolved_relation_score`로 resolved 관계 사용 여부를 노출한다.

### Phase C: Review feedback learning

목표는 리뷰 결과를 문서군별 profile로 재사용하는 것이다.

1. reviewed JSON에서 move/merge/split/approve/issue label을 edit log로 저장한다.
2. style profile과 ignore profile을 문서군별로 축적한다.
3. 새 문서 추출 시 profile을 detector score 보정값으로 사용한다.

---

# 입력

Layout AST

---

예

{
"id": "txt_000001",
"node_type": "text_frame",
"page": 12,
"bbox": [100,120,420,160],

"paragraph_style": "개념제목",

"object_style": null,

"content": "분모가 같은 분수의 덧셈"
}

---

# 출력

Semantic Blocks

---

{
"id": "sb_000001",

"block_type": "concept",

"member_ids": [
"txt_000001",
"txt_000002",
"img_000001"
],

"confidence": 0.91
}

---

v1 출력 계약

```json
{
  "version": "sbd-1",
  "source": {
    "document_name": "source.indd",
    "ast_source": "ASTDocument",
    "generated_at": "ISO-8601"
  },
  "blocks": [
    {
      "id": "sb_000001",
      "member_ids": ["txt_000001", "img_000001"],
      "member_boxes": [
        {
          "id": "txt_000001",
          "page": 12,
          "bbox": [100, 120, 430, 180],
          "kind": "text",
          "role": "text_frame"
        },
        {
          "id": "img_000001",
          "page": 12,
          "bbox": [120, 190, 420, 310],
          "kind": "figure",
          "role": "visual",
          "visual_layer": "CONTENT_VISUAL"
        }
      ],
      "anchor_id": "txt_000001",
      "page_start": 12,
      "page_end": 12,
      "bbox": [100, 120, 430, 310],
      "reading_order": 1,
      "block_type": "unknown",
      "confidence": 0.91,
      "signals": {
        "anchor_score": 0.94,
        "container_score": 0.88,
        "image_score": 0.72
      }
    }
  ]
}
```

필수 원칙

`member_ids`는 실제 AST object id를 사용한다.

`member_boxes`는 리뷰 UI용 위치 계약이다. 각 항목은 `id`, `page`, `bbox`, `kind`, `role`을 가진다.

같은 object id는 기본적으로 하나의 SemanticBlock에만 속한다.

컨테이너 장식, 배경 박스, 말풍선 껍데기처럼 의미 단위의 경계를 설명하는 객체는 member에 포함할 수 있다.

페이지 배경, 마스터 장식, 반복 머리말/꼬리말은 별도 정책이 없으면 member에서 제외한다.

`block_type`은 v1에서 `unknown`을 허용한다.

---

# 전체 파이프라인

Layout AST

↓

Reading Order Builder

↓

Anchor Detector

↓

Candidate Builder

↓

Container Analyzer

↓

Image Attachment

↓

Block Classifier

↓

Confidence Scorer

↓

Semantic Blocks

---

# Stage 1

Reading Order Builder

---

목적

페이지 내 객체 순서 생성

---

입력

Layout Nodes

---

출력

Ordered Nodes

---

예

txt_001

txt_002

img_001

txt_003

txt_004

---

규칙

1. Column 분리

2. 위→아래

3. 왼쪽→오른쪽

4. Text Thread 우선

---

# Stage 2

Anchor Detector

---

목적

Semantic Block 시작점 탐지

---

출력

Anchor List

---

예

{
"node_id": "txt_001",

"anchor_type": "concept_start",

"score": 0.94
}

---

판단 근거

paragraph_style

object_style

font_size

font_weight

keyword

layout_position

---

JSON rule profile v1

```json
{
  "version": "sbd-rules-1",
  "anchor_rules": [
    {
      "id": "paragraph-style-concept-title",
      "when": {
        "node_type": "text_frame",
        "paragraph_style_contains_any": ["개념", "제목", "학습", "활동", "문제"]
      },
      "anchor_type": "generic_start",
      "score": 0.80
    },
    {
      "id": "keyword-question",
      "when": {
        "text_matches_any": ["^\\d+\\.", "^\\([0-9]+\\)", "활동", "문제", "생각해"]
      },
      "anchor_type": "question_start",
      "score": 0.75
    }
  ],
  "container_rules": {
    "same_group_bonus": 0.25,
    "same_container_bonus": 0.20,
    "background_box_bonus": 0.15,
    "max_cross_column_merge_distance_pt": 12
  },
  "image_attachment_rules": {
    "same_group_score": 0.95,
    "same_container_score": 0.90,
    "caption_score": 0.85,
    "nearest_candidate_score": 0.60
  }
}
```

규칙 profile은 detector의 기본값을 대체하지 않고 점수 보정값으로 시작한다.

---

Anchor 종류

concept_start

example_start

question_start

activity_start

generic_start

---

# Stage 3

Candidate Builder

---

목적

Anchor 기반 Block 후보 생성

---

규칙

Anchor A

↓

다음 Anchor 전까지

↓

Candidate

---

예

Anchor

개념 제목

본문

이미지

↓

Candidate

---

출력

{
"candidate_id": "cand_001",

"member_ids": [
"txt_001",
"txt_002",
"img_001"
]
}

---

# Stage 4

Container Analyzer

---

목적

레이아웃 컨테이너 보정

---

분석 대상

Object Style

Group

Background Box

Table

Panel

---

규칙

동일 Container 내부 객체는
동일 Candidate 우선

Container 판단은 bounds만으로 확정하지 않는다.

우선순위는 Group, Object Style, 실제 포함 관계, Background Box, Panel 순서로 둔다.

컨테이너 자체가 보이는 장식이면 SemanticBlock member에 포함한다.

컨테이너가 페이지 배경이나 마스터 반복 장식이면 제외한다.

---

출력

Container Graph

---

# Stage 5

Image Attachment

---

목적

이미지를 Candidate에 연결

---

우선순위

1. 동일 Group

2. 동일 Container

3. Caption 연결

4. 최근접 Candidate

이미지는 단독 의미 단위보다 주변 텍스트와 결합되는 경우를 우선한다.

Caption 후보는 이미지와 가까운 짧은 text_frame, 출처 표기, 번호 표기를 포함한다.

최근접 Candidate는 같은 page와 같은 column 안에서만 기본 적용한다.

column을 넘어가는 연결은 group/container/caption 신호가 있을 때만 허용한다.

---

출력

{
"image_id": "img_001",

"candidate_id": "cand_001",

"score": 0.88
}

---

# Stage 6

Block Classifier

---

목적

Candidate 타입 결정

---

입력

Candidate

---

Feature

anchor_type

style_distribution

keyword_distribution

image_count

bbox_shape

container_type

---

출력

block_type

---

v1 타입

concept

example

question

activity

image_only

unknown

---

예

{
"candidate_id": "cand_001",

"block_type": "concept",

"confidence": 0.87
}

---

# Stage 7

Confidence Scorer

---

목적

신뢰도 계산

---

항목

anchor_score

container_score

image_score

style_score

classification_score

---

출력

0.0 ~ 1.0

---

예

{
"block_id": "sb_001",

"confidence": 0.91
}

---

# Semantic Block 생성

최종 결과

---

{
"id": "sb_000001",

"block_type": "concept",

"member_ids": [
"txt_001",
"txt_002",
"img_001"
],

"page_start": 12,

"page_end": 12,

"bbox": [100,120,430,310],

"confidence": 0.91
}

---

# Human Review 대상

confidence < 0.80

자동 검수 큐 등록

---

# Golden Sample 초안

대상 문서

`22개정_중등국어(박)_3-2학기-1단원(1)(006~031)수정본`

초기 검수 범위

page 6~8

목표

실제 block type보다 member grouping을 먼저 검수한다.

대표 검수 질문

1. 제목/본문/이미지/캡션이 하나의 의미 단위일 때 같은 block에 들어갔는가?

2. 페이지 장식, 마스터 배경, 반복 머리말/꼬리말이 block member에 섞이지 않았는가?

3. 활동/문항 단위가 다음 활동/문항까지 과하게 합쳐지지 않았는가?

4. 이미지가 가장 가까운 객체가 아니라 실제 관련 텍스트 block에 붙었는가?

5. 사용자가 block을 클릭했을 때 member object highlight만으로 그룹이 이해되는가?

초안 파일

`test-data/semantic-blocks/park-3-2-unit1-006-031.expected.semantic-blocks.json`

---

수정 가능 항목

ADD_MEMBER

REMOVE_MEMBER

MERGE_BLOCK

SPLIT_BLOCK

CHANGE_TYPE

---

# 성능 목표

Anchor Detection

Precision > 90%

---

Candidate Generation

Recall > 90%

---

Semantic Block

Human 수정률 < 20%

---

# 비목표

Structure 생성

단원 구조 생성

교수자료 생성

문제-정답 연결

과목 추론

본 모듈 범위 아님

---

# 핵심 정의

Semantic Block Detector의 역할은

"이 객체는 개념이다"

를 판단하는 것이 아니라

"이 객체들은 하나의 의미 단위를 구성한다"

를 판단하는 것이다.

Block Type은 부가 정보이며,
가장 중요한 산출물은 Member Grouping이다.
