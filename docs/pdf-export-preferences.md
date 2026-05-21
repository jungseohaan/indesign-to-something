# InDesign PDF Export Preferences 전체 속성 가이드

`app.pdfExportPreferences`로 설정 가능한 모든 속성.

> 참고: [InDesign ExtendScript API - PDFExportPreference](https://www.indesignjs.de/extendscriptAPI/indesign-latest/#PDFExportPreference.html)

---

## 1. 이미지 압축/해상도

프리뷰용 PDF는 이 섹션이 파일 크기에 가장 큰 영향을 미침.

| 속성 | 타입 | 범위 | 설명 |
|------|------|------|------|
| colorBitmapCompression | BitmapCompression | | 컬러 이미지 압축 방식 |
| colorBitmapQuality | CompressionQuality | | 컬러 이미지 압축 품질 |
| colorBitmapSampling | Sampling | | 컬러 이미지 리샘플링 방식 |
| colorBitmapSamplingDPI | Number | 9-2400 | 컬러 이미지 리샘플링 해상도 |
| colorTileSize | Number | 128-2048 | JPEG2000용 컬러 타일 크기 |
| grayscaleBitmapCompression | BitmapCompression | | 그레이스케일 압축 방식 |
| grayscaleBitmapQuality | CompressionQuality | | 그레이스케일 압축 품질 |
| grayscaleBitmapSampling | Sampling | | 그레이스케일 리샘플링 방식 |
| grayscaleBitmapSamplingDPI | Number | 9-2400 | 그레이스케일 리샘플링 해상도 |
| grayTileSize | Number | 128-2048 | JPEG2000용 그레이 타일 크기 |
| monochromeBitmapCompression | MonoBitmapCompression | | 흑백 이미지 압축 방식 |
| monochromeBitmapSampling | Sampling | | 흑백 리샘플링 방식 |
| monochromeBitmapSamplingDPI | Number | 9-2400 | 흑백 리샘플링 해상도 |
| thresholdToCompressColor | Number | 1-10 | 컬러 압축 적용 최소 DPI 비율 |
| thresholdToCompressGray | Number | 1-10 | 그레이 압축 적용 최소 DPI 비율 |
| thresholdToCompressMonochrome | Number | 1-10 | 흑백 압축 적용 최소 DPI 비율 |
| cropImagesToFrames | Boolean | | 프레임 밖 이미지 데이터 제외 |
| compressTextAndLineArt | Boolean | | 텍스트/벡터 ZIP 압축 |

### Enum 값

**BitmapCompression**: `AUTO_COMPRESSION`, `JPEG`, `ZIP`, `JPEG_2000`, `NONE`

**CompressionQuality**: `MINIMUM`, `LOW`, `MEDIUM`, `HIGH`, `MAXIMUM`, `FOUR_BIT`, `EIGHT_BIT`

**Sampling**: `NONE`, `DOWNSAMPLE`, `SUBSAMPLE`, `BICUBIC_DOWNSAMPLE`

### 프리뷰용 추천 설정

```javascript
app.pdfExportPreferences.colorBitmapSampling = Sampling.BICUBIC_DOWNSAMPLE;
app.pdfExportPreferences.colorBitmapSamplingDPI = 150;
app.pdfExportPreferences.colorBitmapCompression = BitmapCompression.JPEG;
app.pdfExportPreferences.colorBitmapQuality = CompressionQuality.MEDIUM;
app.pdfExportPreferences.grayscaleBitmapSamplingDPI = 150;
app.pdfExportPreferences.monochromeBitmapSamplingDPI = 300;
app.pdfExportPreferences.cropImagesToFrames = true;
app.pdfExportPreferences.compressTextAndLineArt = true;
```

---

## 2. 일반/페이지

| 속성 | 타입 | 설명 |
|------|------|------|
| acrobatCompatibility | AcrobatCompatibility | Acrobat 호환 버전 |
| pageRange | PageRange / String | 내보낼 페이지 범위 (예: `"1-5"`, `PageRange.ALL_PAGES`) |
| exportReaderSpreads | Boolean | 스프레드 단위로 내보내기 |
| exportAsSinglePages | Boolean | 페이지별 별도 PDF 파일 생성 |
| singlePagesPDFSuffix | String | 개별 PDF 파일 접미사 |
| exportWhichLayers | ExportLayerOptions | 내보낼 레이어 선택 |
| exportLayers | Boolean | 레이어를 Acrobat 레이어로 저장 |
| exportHiddenSpread | Boolean | 숨긴 스프레드 포함 |
| exportNonprintingObjects | Boolean | 비인쇄 객체 포함 |
| exportGuidesAndGrids | Boolean | 가이드/그리드 포함 |
| optimizePDF | Boolean | 빠른 웹 보기 최적화 |
| generateThumbnails | Boolean | 썸네일 생성 |
| viewPDF | Boolean | 내보내기 후 자동 열기 |

**AcrobatCompatibility**: `ACROBAT_4` (PDF 1.3), `ACROBAT_5` (1.4), `ACROBAT_6` (1.5), `ACROBAT_7` (1.6), `ACROBAT_8` (1.7)

---

## 3. 색상

| 속성 | 타입 | 설명 |
|------|------|------|
| pdfColorSpace | PDFColorSpace | 색 공간 |
| pdfDestinationProfile | PDFProfileSelector / String | 대상 색상 프로파일 |
| includeICCProfiles | ICCProfiles / Boolean | ICC 프로파일 포함 |
| simulateOverprint | Boolean | 오버프린트 시뮬레이션 |

**PDFColorSpace**: `UNCHANGED_COLOR_SPACE`, `CMYK`, `RGB`, `REPURPOSE_CMYK`, `REPURPOSE_RGB`

---

## 4. 재단선/마크

| 속성 | 타입 | 설명 |
|------|------|------|
| bleedTop / Bottom / Inside / Outside | Number | 재단 여백 |
| useDocumentBleedWithPDF | Boolean | 문서 재단 설정 사용 |
| includeSlugWithPDF | Boolean | 슬러그 영역 포함 |
| cropMarks | Boolean | 재단 마크 |
| bleedMarks | Boolean | 재단 여백 마크 |
| registrationMarks | Boolean | 레지스트레이션 마크 |
| colorBars | Boolean | 컬러 바 |
| pageInformationMarks | Boolean | 페이지 정보 마크 |
| pageMarksOffset | Number | 마크 오프셋 |
| pdfMarkType | MarkTypes / String | 마크 유형 |
| printerMarkWeight | PDFMarkWeight | 마크 선 두께 |

---

## 5. 보안

| 속성 | 타입 | 설명 |
|------|------|------|
| useSecurity | Boolean | 보안 활성화 |
| openDocumentPassword | String | 문서 열기 비밀번호 |
| changeSecurityPassword | String | 권한 변경 비밀번호 |
| disallowPrinting | Boolean | 인쇄 금지 |
| disallowHiResPrinting | Boolean | 고해상도 인쇄 금지 |
| disallowCopying | Boolean | 복사 금지 |
| disallowChanging | Boolean | 변경 금지 |
| disallowDocumentAssembly | Boolean | 문서 조합 금지 |
| disallowFormFillIn | Boolean | 양식 입력 금지 |
| disallowNotes | Boolean | 주석 금지 |
| disallowExtractionForAccessibility | Boolean | 접근성 추출 금지 |
| disallowPlaintextMetadata | Boolean | 메타데이터 제한 |

---

## 6. 폰트/구조

| 속성 | 타입 | 설명 |
|------|------|------|
| subsetFontsBelow | Number (0-100) | 폰트 서브셋 임계값 (%). 100이면 항상 서브셋 |
| includeBookmarks | Boolean | 북마크 포함 |
| includeHyperlinks | Boolean | 하이퍼링크 포함 |
| includeStructure | Boolean | 태그 PDF 생성 |
| preserveInDesignEditingCapabilities | Boolean | InDesign 편집 정보 유지 |

---

## 7. 뷰어 설정

| 속성 | 타입 | 설명 |
|------|------|------|
| pdfDisplayTitle | PdfDisplayTitleOptions | 제목 표시 옵션 |
| pdfMagnification | PdfMagnificationOptions | 배율 옵션 |
| pdfPageLayout | PageLayoutOptions | 페이지 레이아웃 |
| openInFullScreen | Boolean | 전체 화면 모드 |
| defaultDocumentLanguage | String | 문서 언어 (ISO 코드) |

---

## 8. PDF/X 표준

| 속성 | 타입 | 설명 |
|------|------|------|
| standardsCompliance | PDFXStandards | PDF/X 표준 준수 |
| outputCondition | String | 출력 조건 |
| outputConditionName | String | 출력 조건 이름 |
| ocRegistry | String | 출력 조건 레지스트리 URL |
| pdfXProfile | PDFProfileSelector / String | PDF/X 색상 프로파일 |

---

## 9. OPI

| 속성 | 타입 | 설명 |
|------|------|------|
| omitBitmaps | Boolean | 비트맵을 OPI 링크로 대체 |
| omitEPS | Boolean | EPS를 OPI 링크로 대체 |
| omitPDF | Boolean | PDF를 OPI 링크로 대체 |

---

## 10. 투명도

| 속성 | 타입 | 설명 |
|------|------|------|
| appliedFlattenerPreset | FlattenerPreset | 투명도 병합 프리셋 |
| ignoreSpreadOverrides | Boolean | 스프레드별 병합 오버라이드 무시 |

---

## 현재 프로젝트 설정 (`extract_indd.jsx`)

> 상세 설정 근거 및 정책: [docs/specs/SPEC-pdf-export.md](specs/SPEC-pdf-export.md)

```javascript
// 링크 재연결 (INDD 옆 Links/ 폴더에서 검색)
for (var li = 0; li < doc.links.length; li++) {
    var link = doc.links[li];
    if (link.status === LinkStatus.LINK_OUT_OF_DATE) link.update();
    else if (link.status === LinkStatus.LINK_MISSING) {
        var f = File(linksDirLinks + "/" + link.name);
        if (f.exists) { link.relink(f); link.update(); }
    }
}

// 이미지: 300 DPI + JPEG HIGH
app.pdfExportPreferences.colorBitmapSampling = Sampling.BICUBIC_DOWNSAMPLE;
app.pdfExportPreferences.colorBitmapSamplingDPI = 300;
app.pdfExportPreferences.colorBitmapCompression = BitmapCompression.JPEG;
app.pdfExportPreferences.colorBitmapQuality = CompressionQuality.HIGH;
app.pdfExportPreferences.grayscaleBitmapSampling = Sampling.BICUBIC_DOWNSAMPLE;
app.pdfExportPreferences.grayscaleBitmapSamplingDPI = 300;
app.pdfExportPreferences.grayscaleBitmapCompression = BitmapCompression.JPEG;
app.pdfExportPreferences.grayscaleBitmapQuality = CompressionQuality.HIGH;
app.pdfExportPreferences.monochromeBitmapSampling = Sampling.BICUBIC_DOWNSAMPLE;
app.pdfExportPreferences.monochromeBitmapSamplingDPI = 1200;

// 기타
app.pdfExportPreferences.cropImagesToFrames = true;
app.pdfExportPreferences.compressTextAndLineArt = true;
app.pdfExportPreferences.acrobatCompatibility = AcrobatCompatibility.ACROBAT_7;
app.pdfExportPreferences.subsetFontsBelow = 100;
app.pdfExportPreferences.optimizePDF = true;
app.pdfExportPreferences.exportReaderSpreads = spreadMode;
```