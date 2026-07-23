# SPEC-061: HWPX 구조 시그니처 골든 게이트 (PR 전 회귀 검증)

> 상태: **완료** (도구 + 과학 u1 골든). 2026-07-23.
> 브랜치 `dev-verify-hwpx-golden`.

## 동기

머지된 PR의 회귀가 며칠 뒤 우연히 발견되는 패턴 반복:
- PR #107 → 반응식 좌변 잘림 (SPEC-059)
- PR #93 → 중첩 표 스타일 흡수 소실 (SPEC-060)

각 PR의 "전체 회귀 체크"가 수동·부분적이었기 때문. 회귀 진단 때마다 쓰던
ad-hoc 검증 스니펫(수식 목록·셀 fill·유출 패턴)을 도구화해 PR 전 게이트로.

## 도구: `scripts/dev/verify_hwpx.py`

HWPX 에서 구조 시그니처를 추출해 골든 JSON 과 비교. stdlib 만 사용.

시그니처 축 (실제 회귀가 났던 축들):
- **수식**: hp:script 전체 목록 multiset (좌변 잘림·수식 소실 검출)
- **표**: 표별 `(행x열, 선두 텍스트 24자)` 키 → 셀 fill 색 집합·fill 셀 수·
  괘선 셀 수 (스타일 흡수 소실 검출; 중첩 표는 자기 셀만 집계)
- **이미지**: pic 수 + curSz 10pt 버킷 히스토그램 (시각물 유실/크기 변화)
- **유령 테이블**: 폭≤0.2pt & 높이≥3pt (유령 스페이서)
- **유출 휴리스틱**: NBSP 8연쇄 이상, □(U+25A1) 수
- **총량**: 섹션/문단/가시문자(0.1% 허용)/BinData 수

## 워크플로우 (PR 전 관례)

```bash
# 1) 변환 (기준 추출물 — 과학 u1 전체)
java -jar converter/target/idml-to-something-1.0.9-cli.jar \
  --convert <extract>/output.idml /tmp/u1.hwpx \
  --links-directory <extract>/Links --include-images --config conversion-config.json

# 2) 골든 비교 (diff 있으면 exit 1)
python3 scripts/dev/verify_hwpx.py /tmp/u1.hwpx --golden test-data/golden/과학u1-p008-049.json

# 3) 의도한 변화만이면 골든 갱신 (PR 에 골든 diff 포함 — 리뷰 대상)
python3 scripts/dev/verify_hwpx.py /tmp/u1.hwpx --write-golden test-data/golden/과학u1-p008-049.json
```

- diff 는 "의도한 변화인가"를 사람이 판정한다. 의도한 변화면 골든을 같은
  PR 에서 갱신 — **골든 diff 자체가 리뷰 아티팩트**가 된다.
- 골든은 특정 추출물에 종속 (기준: `p008-049-20260722-221517` 추출).
  추출기 변경 PR 은 재추출 후 비교 — 추출기발 변화도 같은 방식으로 리뷰.

## 자기검증 (도구 신뢰성)

- 동일 파일 → PASS
- SPEC-059 이전 산출물 → 수식 소실/추가 10건 정확 검출
  (`H_{2}+O_{2} ~ rarrow ~ H_{2}O` 소실 + `~ rarrow ~ H_{2}O` 추가)
- SPEC-060 이전 산출물 → `표 스타일 변화 [2x5|구리의 질량...] fills
  ['#DEE7EB'] → []` 정확 검출

## 골든 기준점

`test-data/golden/과학u1-p008-049.json` — open-indd @SPEC-060 머지 후,
p19/p28/p47 육안 확인 완료 상태의 변환. meta.gitRev 로 생성 시점 기록.

## 한계 / 다음 단계

- 구조 시그니처는 위치·크기 미세 오차를 안 본다 (10pt 버킷) — 레이아웃
  픽셀 회귀는 별도 (시각 프리뷰 비교, 추후).
- 골든이 1개 유닛(과학 u1). 수학 u1 등 다른 유닛 골든 추가는 케이스별로.
- curSz 버킷은 pic 외 도형 크기도 포함 (일관 비교라 신호로는 유효).
