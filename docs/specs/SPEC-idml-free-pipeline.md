# SPEC: 하이브리드 변환 파이프라인 (ResolvedToASTBuilder)

> **상태**: 구현 완료, 검수 중 (Step 3c)
> **브랜치**: `open-indd`
> **최종 업데이트**: 2026-03-27

## 문제

기존 파이프라인은 IDML 중심 + resolved.json 보조:
- 좌표 변환이 5+ 단계에서 발생 → 좌표 버그 빈발
- IDML 좌표(spread 기준)와 resolved 좌표(page 기준) 단위 불일치
- z-order/레이어 순서가 IDML과 InDesign DOM에서 다름
- Java 벡터 래스터화가 느리고 품질 낮음 (84초)
- Stage1~4 파이프라인이 복잡하고 디버그 어려움

## 목표

IDML은 Story XML(텍스트/단락 구조)만 사용, 나머지는 resolved.json + ExtendScript PNG로 대체.
좌표 변환 1회. Java 래스터화 완전 비활성화. 변환 속도 4.5초.

## 원칙 (변경됨)

~~0. IDML은 변환 파이프라인에서 사용하지 않음~~ → **IDML Story XML은 텍스트 소스로 사용** (하이브리드)

1. **텍스트**: IDML Story XML (정확한 단락 구조, 문단 중복 없음)
2. **좌표/스타일**: resolved.json (ExtendScript 추출, page-relative points)
3. **그래픽**: ExtendScript 페이지 배경 PNG + 인라인 객체 PNG
4. **Java 렌더링 금지** (fallback에서도 안 함, 경고만 출력)
5. 모든 좌표는 pointsToHwpunits() 1회 변환
6. HWPX 내보내기는 AST의 최종 좌표만 사용

### IDML을 텍스트 소스로 유지하는 이유

- resolved.json의 Story 텍스트는 InDesign DOM 기반 → 단락 수가 IDML과 불일치 (예: 31파라 vs 22파라)
- IDML Story XML은 정확한 단락 경계, CharacterStyleRange, ParagraphStyleRange 보존
- resolved에서 부족한 스타일 속성 (색상, 자간, 행간)을 resolved에서 보강

## 새 파이프라인

```
.indd → [ExtendScript] → .idml + resolved.json + 페이지 PNG
                               ↓
                    IDML Fonts.xml/Styles.xml 파싱 (Phase 0)
                               ↓
                    ResolvedToASTBuilder (Phase 1~6)
                     ├─ Phase 1: 페이지/섹션 빌드
                     ├─ Phase 2: TextFrame 배치 (편집 가능만)
                     ├─ Phase 3: Story 텍스트 변환 (IDML + resolved 하이브리드)
                     ├─ Phase 4: 테이블 변환
                     ├─ Phase 5: Figure/Image 배치
                     └─ Phase 6: 페이지 배경 PNG 주입
                               ↓
                    ASTDocument (최종 HWPUNIT 좌표)
                               ↓
                    ASTToHwpxConverter → .hwpx
```

### 좌표 흐름 (1회 변환)
```
ExtendScript: page-relative points
     ↓ (resolved.json에 저장)
ResolvedDataReader: double[] (points)
     ↓ (pointsToHwpunits() 1회)
ResolvedToASTBuilder: long (HWPUNIT)
     ↓ (변환 없음)
ASTToHwpxConverter: 그대로 사용
     ↓
HWPX XML 출력
```

## Phase 상세

### Phase 0: IDML 스타일 파싱
- `Fonts.xml` → FontFamily/FontStyle 레지스트리
- `Styles.xml` → ParagraphStyle/CharacterStyle 속성 체인
- `StylePropertyResolver` → 스타일 상속 해석 (Tracking, Leading, FillColor 등)

### Phase 1: 페이지/섹션 빌드
- resolved `pages[]` → ASTSection + ASTPageLayout
- 좌표: page bounds/margins → pointsToHwpunits()

### Phase 2: TextFrame 배치
- **편집 가능한 TextFrame만** 배치 (배경 PNG에서 숨겨진 것)
- 편집 불가 = masterPageItem, \u0018(자동 페이지 번호), 상단/하단 10% 짧은 텍스트, Group+비블랙+10자 이하
- `isInline=true` → 부모 Story의 단락에 인라인 객체로 삽입
- `isInline=false` → ASTTextFrameBlock으로 섹션에 추가
- facing pages 좌표 보정 (스프레드 → 페이지 상대)
- 프레임 분할: `frameVisibleText` 기반 스레드 체인 분할

### Phase 3: Story 텍스트 변환 (하이브리드)
- **IDML Story XML**: CharacterStyleRange/ParagraphStyleRange → 정확한 단락 구조
- **resolved 보강**: 색상(IDML 스와치→hex), 자간(Tracking), 행간(Leading), baselineShift
- BT 수식 폰트: BTFontEquationConverter (다중문자 첨자, `_{n-1}`)
- NP 수식 폰트: NPFontEquationConverter
- EH 수식 폰트: 단락 수준 `paraHasMathSymbols` 체크, 3+ 문자 영단어 리셋
- GREP 수식 감지: 단일 라틴 문자만 유지, 다중 문자 리셋
- `splitLatinVarsInMixedText()`: 한국어 사이 단일 라틴 → ASTEquation 분리
- 폰트 매핑: FontMapper (conversion-config.json 기반)

### Phase 4: 테이블 변환
- resolved `tables.cells[]` → ASTTable + ASTTableRow + ASTTableCell
- Grid TextFrame → 2D 좌표 클러스터링 기반 ASTTable

### Phase 5: Figure/Image 배치
- 렌더 PNG → ASTFigure
- PNG 없으면 경고만 출력 (Java 렌더링 금지)

### Phase 6: 페이지 배경 PNG 주입
- `page_bg_*.png` → ASTFigure (BEHIND_TEXT, z=0)
- 편집 TextFrame이 숨겨진 상태에서 InDesign이 렌더링

## ExtendScript 추출 (extract_indd.jsx)

### 페이지 배경 PNG (`exportPageBackgrounds`)
1. 편집 가능한 TextFrame 감지
2. 해당 TextFrame의 텍스트를 투명하게 변경 (fillColor=None, strokeColor=None)
3. 페이지 전체를 PNG로 렌더링 (220dpi)
4. 텍스트 색상 복원

### 편집 TextFrame 분류
| 분류 | 조건 | 처리 |
|------|------|------|
| 배경 | masterPageItem | 배경 PNG에 포함 |
| 배경 | \u0018 (자동 페이지 번호) | 배경 PNG에 포함 |
| 배경 | 상단/하단 10% + 짧은 텍스트 | 배경 PNG에 포함 |
| 배경 | Group + 10자 이하 + 비블랙 | 배경 PNG에 포함 |
| 편집 가능 | 그 외 | 숨김 후 텍스트로 변환 |

### 인라인 객체 PNG
- 편집 TextFrame 내 앵커 객체 → 개별 PNG 렌더링
- bounds width + PNG 종횡비로 높이 보정

### 배지 추출 (`isBadgeGroup`)
- Group 내 도형 + TextFrame(1~15자) → 단일 PNG

### 장식 텍스트 렌더링 (`isRenderableTextFrame`)
- 회전, TextPath, 비블랙, 흰색/희미한 텍스트 → PNG

## 단위 변환

| 항목 | IDML/InDesign | HWPX | 공식 |
|------|--------------|------|------|
| 행간 (Leading) | pt (절대값) | PERCENT | `(leading / fontSize) × 100` |
| 자간 (Tracking) | 1/1000em | % | `tracking / 10` |
| baselineShift | pt | % | `(shift / fontSize) × 100` |
| 좌표 | points | HWPUNIT | `pt × 100` |

## 설정 (conversion-config.json)

| 키 | 기본값 | 설명 |
|----|--------|------|
| `pngExportResolution` | 220 | 페이지 배경 PNG 해상도 (DPI) |
| `groupShapeMaxTextLength` | 20 | Group 내 배경 판별 최대 텍스트 길이 |
| `spaceCondenseRatio` | 50 | 공백 장평 축소 비율 |

## 레거시 파이프라인 (유지)

resolved.json이 없으면 기존 파이프라인 사용:
```
IDML → IDMLNormalizer (Stage1~4) → ResolvedMerger → ASTToHwpxConverter → .hwpx
```

## 알려진 이슈

1. **배경/글상자 중복**: facing pages spread에서 편집 TextFrame 숨김 실패 → 배경 PNG에도 포함
2. **빈 네모 □ 누락**: 인라인 입력 박스 변환 미지원
3. **수식 콤마 뒤 공백**: `=,(-` → `=, (-`
4. **EH 인코딩 런 분리**: Û(0xDB) 포함 런 → 한국어 텍스트까지 수식 그룹 진입
5. **인라인 객체 납작**: bounds 높이 부정확 → PNG 종횡비 보정 (부분 해결)
6. **Group 도형+텍스트 분류**: Group 내 도형은 배경, 텍스트는 편집 가능으로 분리하는 로직 미완성

## 수정 파일

| 파일 | 역할 |
|------|------|
| `scripts/extract_indd.jsx` | ExtendScript 추출 (페이지 배경, 인라인, 배지, 장식) |
| `normalizer/ResolvedToASTBuilder.java` | 새 파이프라인 메인 (~1700줄) |
| `normalizer/ASTMathGrouper.java` | 수식 그룹 감지 |
| `converter/HwpxEnumMapper.java` | IDML→HWPX enum 매핑 |
| `converter/HwpxParagraphBuilder.java` | 문단 빌더 (행간, 수식 높이) |
| `converter/HwpxTextBoxBuilder.java` | 글상자 빌더 (셀 여백, 너비 확장) |
| `resolved/ResolvedData.java` | resolved.json 데이터 컨테이너 |
| `resolved/ResolvedDataReader.java` | JSON 파서 (Gson lenient) |
| `conversion-config.json` | 변환 설정 |

## 검증

- [x] 빌드 성공
- [x] 중3영어교과서 1단원 변환
- [ ] 중3영어교과서 2단원 변환 (진행 중)
- [ ] 중3-1국어(박영민) u1 변환
- [ ] 중3-1국어(박현숙) u3-2 변환
- [x] 좌표 정확성 (page-relative)
- [x] 폰트 매핑 유지
- [x] 공백 장평 축소 유지
- [x] 배경 이미지 z-order 정상
- [x] 변환 속도 4.5초
