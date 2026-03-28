# [SPEC-PDF] PDF 프리뷰 생성 설정 및 정책

## 문제

InDesign에서 PDF 프리뷰를 내보낼 때 이미지 품질이 36~87 DPI로 매우 낮았음.

### 근본 원인

1. **링크 미해석**: `checkLinksAtOpen = false`로 InDesign이 파일을 열 때 이미지 링크를 해석하지 않음. 결과적으로 문서 내장 저해상도 미리보기(36 DPI)를 사용하여 PDF를 생성.
2. **설정 미적용**: 초기에는 `pdfExportPreferences`를 설정하지 않아 InDesign의 마지막 사용 설정(불확실)에 의존.

### 발견 과정

- `pdfimages -list preview.pdf`로 실제 임베디드 이미지 해상도 확인 → 36 DPI
- 소스 이미지(.psd/.tif)는 300 DPI인데 PDF에서 36 DPI → 링크 미연결로 저해상도 프리뷰 사용 확인
- `doc.links` 순회 결과 88개 중 75개가 `LinkStatus.LINK_MISSING` 상태

## 해결 방안

### 1단계: 링크 자동 재연결

PDF 내보내기 직전에 누락된 링크를 INDD 파일 옆의 `Links/` 폴더에서 검색하여 재연결.

```javascript
// extract_indd.jsx — PDF 내보내기 전
for (var li = 0; li < doc.links.length; li++) {
    var link = doc.links[li];
    if (link.status === LinkStatus.NORMAL) continue;
    if (link.status === LinkStatus.LINK_OUT_OF_DATE) {
        link.update();
    } else if (link.status === LinkStatus.LINK_MISSING && linksDirLinks.exists) {
        var linkFile = File(linksDirLinks + "/" + link.name);
        if (linkFile.exists) {
            link.relink(linkFile);
            link.update();
        }
    }
}
```

**링크 검색 경로**: `{INDD파일 디렉토리}/Links/{파일명}`

> `checkLinksAtOpen = false`는 유지한다. 문서를 열 때 링크 다이얼로그를 억제해야 headless 실행이 가능하므로, 링크 해석은 열기 이후 스크립트에서 명시적으로 수행한다.

### 2단계: PDF 내보내기 설정

#### 현재 적용 설정

| 카테고리 | 속성 | 값 | 설명 |
|----------|------|-----|------|
| **컬러 이미지** | colorBitmapSampling | `BICUBIC_DOWNSAMPLE` | 고품질 보간법 |
| | colorBitmapSamplingDPI | `300` | 인쇄 표준 해상도 |
| | colorBitmapCompression | `JPEG` | 손실 압축 (파일 크기 절약) |
| | colorBitmapQuality | `HIGH` | JPEG 품질 상 |
| **그레이스케일** | grayscaleBitmapSampling | `BICUBIC_DOWNSAMPLE` | 고품질 보간법 |
| | grayscaleBitmapSamplingDPI | `300` | 인쇄 표준 해상도 |
| | grayscaleBitmapCompression | `JPEG` | 손실 압축 |
| | grayscaleBitmapQuality | `HIGH` | JPEG 품질 상 |
| **흑백** | monochromeBitmapSampling | `BICUBIC_DOWNSAMPLE` | 고품질 보간법 |
| | monochromeBitmapSamplingDPI | `1200` | 흑백은 고해상도 필요 |
| **일반** | cropImagesToFrames | `true` | 프레임 밖 이미지 데이터 제외 |
| | compressTextAndLineArt | `true` | 텍스트/벡터 ZIP 압축 |
| | optimizePDF | `true` | 빠른 웹 보기 최적화 |
| **호환성** | acrobatCompatibility | `ACROBAT_7` (PDF 1.6) | 투명도/레이어 지원 |
| **폰트** | subsetFontsBelow | `100` | 항상 서브셋 (파일 크기 절약) |
| **페이지** | exportReaderSpreads | `spreadMode` | 스프레드 모드 인자에 따름 |

#### 설정 선택 근거

| 선택지 | 파일 크기 | 이미지 DPI | 비고 |
|--------|-----------|-----------|------|
| Sampling.NONE + ZIP | 251 MB | 원본 유지 (최대 2266) | 무손실, 너무 큼 |
| **BICUBIC 300 + JPEG HIGH** | **13 MB** | **300 DPI** | **채택. 프리뷰 최적** |
| BICUBIC 150 + JPEG MEDIUM | ~3 MB | 150 DPI | 화면 전용, 품질 부족 |
| 설정 없음 (링크 미연결) | 2.8 MB | 36 DPI | 이전 상태. 사용 불가 |

### 3단계: pdfOnly 모드

`extract_indd.jsx`의 6번째 인자 `pdfOnly = "1"`로 IDML/resolved 추출을 생략하고 PDF만 재생성할 수 있음.

```bash
# test.sh에서 pdf 액션으로 호출
./test.sh 중3영어교과서/u1 pdf
```

## 수정 파일

1. `scripts/extract_indd.jsx`
   - 링크 자동 재연결 로직 추가 (단계 4)
   - PDF 내보내기 설정 적용 (단계 5)
   - `pdfOnly` 모드 추가 (6번째 인자)
2. `test.sh`
   - `pdf` 액션 추가: InDesign에서 PDF만 재생성

## 주의사항

### InDesign 프리셋 로컬라이제이션

InDesign 한글 버전에서 PDF 내보내기 프리셋 이름이 한글로 로컬라이징됨.
- `[High Quality Print]` → `[고품질 인쇄]`
- `[Smallest File Size]` → `[최소 파일 크기]`
- `[Press Quality]` → `[인쇄 품질]`

ExtendScript에서 이 이름을 매칭하려면 인코딩 문제가 발생할 수 있으므로, **프리셋 대신 개별 속성을 직접 설정하는 방식을 사용**한다.

### 다운샘플링 동작

- `BICUBIC_DOWNSAMPLE 300 DPI`는 "300 DPI 이상인 이미지를 300 DPI로 축소"를 의미
- 300 DPI 미만인 이미지는 원본 해상도가 유지됨 (업샘플링하지 않음)
- `thresholdToCompressColor` (기본값 1.5)에 의해, 실제로는 300 × 1.5 = 450 DPI 이상인 이미지만 다운샘플됨

### checkLinksAtOpen = false 정책

| 단계 | 링크 체크 | 이유 |
|------|----------|------|
| 문서 열기 | `false` | 다이얼로그 억제 (headless 필수) |
| IDML 내보내기 | 불필요 | IDML은 이미지 미포함 |
| resolved 수집 | 불필요 | DOM 속성만 수집 |
| **PDF 내보내기** | **명시적 재연결** | 고해상도 이미지 필요 |

## 검증

- [x] 링크 재연결: 88개 중 75개 자동 재연결 성공
- [x] 이미지 DPI: 36 → 303 DPI (컬러), 200 DPI (그레이, 원본 유지)
- [x] 파일 크기: 2.8 MB → 13 MB (허용 범위)
- [x] `pdfimages -list`로 실제 임베디드 해상도 확인
- [x] `test.sh 중3영어교과서/u1 pdf`로 PDF 재생성 테스트

## 참고

- API 레퍼런스: [docs/pdf-export-preferences.md](../pdf-export-preferences.md)
- `pdfimages -list <pdf>`: PDF 내 실제 이미지 해상도 확인 도구 (poppler-utils)

## 상태: 완료