# SPEC-074: Group 으로 묶인 배치 이미지 유실 — 렌더 pass 미등록

> 상태: **구현 완료, 20페이지 회귀 PASS**. 2026-07-25.
> 사례: 영어 u1 p13 동영상 미리보기 — 여성·남학생 대화 사진이 통째로 누락.

## 문제

배치 이미지가 통째로 사라진다. p13 에서 "동영상 속 남학생…" 발문 아래 대화 사진(실사)이
빠지고, 그 위에 얹힌 플레이 버튼·컨트롤바만 남거나 배경이 비었다.

## 원본 구조

스크린샷의 사진은 **동영상 미리보기 그룹**이다. 그룹 `1874` 안에:

- Image `20321` — 실사 대화 사진 (Rectangle 1876 안)
- Polygon `1901` — 플레이 버튼
- Rectangle `1902` + 자식 폴리곤들 — 컨트롤바(재생바)

즉 사진·버튼·컨트롤바가 하나의 Group 으로 묶여 있다.

## 원인

이 그룹은 object-plan 이 정상 생성된다.

```
passId: pass.image_textless_groups
candidateId: cand.pass.image_textless_groups.composite.page.3.src.1874.20321.n20...
ownershipSlot: CONTENT_VISUAL_SLOT   materialization: EXTRACTED_PNG_VECTOR
file: null   ← PNG 가 안 만들어졌다
```

**`pass.image_textless_groups` 에 렌더 실행기가 없다.** `extraction_passes.jsx` 레지스트리에는
`image_placed_frames` 와 `decoration_groups` 만 등록돼 있고 이 pass 는 없다(git 이력에도
등록된 적 없음). plan 빌더·소유권 로직 등 **13곳이 이 pass 이름을 참조**하는데 정작 렌더가
빠져 있어, 후보는 만들어지지만 PNG 가 생성되지 않고 `file=null` 로 유실된다.

### 왜 이 pass 로 가나

`extraction_plan_builder.jsx:6465`, `:6666` 두 곳과 `extraction_base_candidates.jsx:1424`
에서 갈린다. 배치 이미지 트리를 가진 플로팅 소스가 **Group 이면 `image_textless_groups`,
아니면 `image_placed_frames`** 로 간다.

우리 사진은 Group(1874)으로 묶여 있어 죽은 pass 로 갔다. 같은 페이지의 다른 사진들
(2612 여학생, 2669 마술도구, 2723 미술실)은 Group 이 아니라 `image_placed_frames` 로 가서
정상 렌더됐다.

## 해결 방안

처음엔 pass 이름을 `image_placed_frames` 로 바꾸려 했으나, **downstream 소유권 로직이 이
pass 이름에 강하게 의존**한다 — 번들 판정(`_plannerBundleIsClosedImageTextlessGroup`),
스코어링(`object_plans` +520, `source_slot_registry` +70), validation 등 13곳. 이름을 바꾸면
그 로직이 전부 깨진다.

그래서 **누락된 렌더 pass 를 등록**하는 쪽으로 고친다. 소유권 로직은 그대로 두고 렌더만
살리므로 회귀 범위가 좁다.

1. `extraction_passes.jsx` — `pass.image_textless_groups` 를 다른 배치 이미지와 같은
   `exportInlineObjects` 실행기로 등록 (mode=TEXTLESS_CANDIDATE, purpose=CONTENT_CANDIDATE)
2. `extraction_orchestrator.jsx` — `image_placed_frames` 렌더 직후에 이 pass 의 렌더 호출
   추가, 결과를 `renderedFloatingItems` 에 병합

## 수정 파일

1. `scripts/indd/extraction_passes.jsx` — pass 등록
2. `scripts/indd/extraction_orchestrator.jsx` — 렌더 호출

## 검증

- [x] p13 클린 재추출: `inline_1874_p4_pass_image_textless_groups_*.png` 생성,
      실사 사진+플레이버튼+컨트롤바 온전. 최종 HWPX 에 65KB 이미지로 내장,
      `renderedFloatingItems` 에 bounds 와 함께 매칭
- [x] 한글 육안: 사진 복원 확인 (사용자 "살아났어")
- [x] 영어 u1 p010-029 전체 회귀: image_textless_groups PNG **7개 생성**, 내장 이미지 정확히 **+7**(362→369). 유실 4개 페이지 전부 복원
- [x] 부작용 없음: 텍스트 변화는 이번 수정과 무관함을 입증했다. 처음엔 T/F 체크표
      2쌍이 사라진 것처럼 보였으나, 수정을 stash 한 baseline(p29)에도 없어 이 손실은
      **이번 브랜치 이전에 이미 발생**한 별건이다(다른 SPEC 계열). 비교 기준선을 잘못
      골랐던 것 — 옛 코드 시점 산출물과 대조했다
- 별건 관찰: T/F 체크표(영어 u1 p29 "일치하면 T에…" 정답표) 유실. 발문은 남고 T/F 셀만
  사라짐. 이번 수정 이전 시점부터 이미 없음. 별도 조사 필요

## 주의사항

- **plan 이 참조하는 pass 이름과 렌더 레지스트리가 어긋날 수 있다.** 후보는 만들어지는데
  `file=null` 이면 그 pass 가 `extraction_passes.jsx` 에 등록됐는지부터 확인할 것
- pass 이름 변경은 위험하다 — 소유권/번들/스코어 로직 13곳이 이름에 의존한다.
  렌더가 빠졌으면 **이름을 바꾸지 말고 렌더를 등록**할 것
