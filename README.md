# InDesign to Something

Adobe InDesign(IDML)을 HWPX(한글) 및 기타 형식으로 변환하는 Java 라이브러리입니다.

## 개요

```
┌─────────────┐     ┌──────────────────┐     ┌──────────────┐
│   IDML      │ ──> │   Intermediate   │ ──> │    HWPX      │
│  (InDesign) │     │     (JSON)       │     │   (한글)     │
└─────────────┘     └──────────────────┘     └──────────────┘
```

InDesign IDML 파일을 중간 표현(Intermediate JSON)으로 파싱한 후, HWPX 형식으로 변환합니다.

## 주요 기능

### IDML → HWPX 변환
- **텍스트 프레임**: 텍스트, 문단, 문자 스타일 변환
- **이미지 프레임**: 이미지 추출 및 삽입
- **수식 변환**: NP 폰트 기반 수식 → HWP 수식 스크립트
- **페이지 필터링**: 특정 페이지 범위만 변환
- **레이어 필터링**: 숨겨진 레이어, 편집 지시(조판지시서) 프레임 자동 제외

### 수식 변환
- LaTeX → HWP 수식 스크립트
- MathML → HWP 수식 스크립트
- NP 폰트 글리프 → HWP 수식 스크립트

### 유틸리티
- 이미지 삽입 (ImageInserter)
- 텍스트 추출 (TextExtractor)
- 빈 HWPX 파일 생성 (BlankFileMaker)
- 객체 검색 (ObjectFinder)

## 설치

### Maven

```xml
<dependency>
    <groupId>kr.dogfoot</groupId>
    <artifactId>hwpxlib</artifactId>
    <version>1.0.8</version>
</dependency>
```

### Gradle

```groovy
implementation 'kr.dogfoot:hwpxlib:1.0.8'
```

## 사용법

### IDML → HWPX 변환

```java
import kr.dogfoot.hwpxlib.tool.idmlconverter.IDMLToHwpxConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertOptions;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertResult;

// 기본 변환
ConvertResult result = IDMLToHwpxConverter.convert("input.idml", "output.hwpx");

// 옵션 설정
ConvertOptions options = new ConvertOptions()
    .startPage(1)
    .endPage(10)
    .includeImages(true)
    .convertEquations(true);

ConvertResult result = IDMLToHwpxConverter.convert("input.idml", "output.hwpx", options);
```

### 중간 표현(Intermediate) JSON 활용

```java
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.*;

// IDML → Intermediate JSON
IntermediateDocument doc = IDMLToHwpxConverter.toIntermediate("input.idml");
String json = JsonSerializer.toJson(doc);

// Intermediate JSON → HWPX
IntermediateDocument doc = JsonDeserializer.fromJson(json);
IDMLToHwpxConverter.fromIntermediate(doc, "output.hwpx");
```

### 수식 변환

```java
import kr.dogfoot.hwpxlib.tool.equationconverter.*;

// LaTeX → HWP Script
String hwpScript = LatexToHwpConverter.convert("\\frac{a}{b} + \\sqrt{c}");
// 결과: "{a} over {b} + sqrt {c}"

// MathML → HWP Script
String hwpScript = MathMLToHwpConverter.convert(mathmlString);
```

### 이미지 삽입

```java
import kr.dogfoot.hwpxlib.tool.imageinserter.ImageInserter;

HWPXFile hwpx = HWPXReader.read("document.hwpx");
ImageInserter inserter = new ImageInserter(hwpx);

// 이미지 등록 및 삽입
inserter.insertImage("image.png", x, y, width, height);

HWPXWriter.write(hwpx, "output.hwpx");
```

## 중간 표현(Intermediate) 스키마

IDML 문서를 JSON으로 표현한 중간 포맷입니다. 자세한 내용은 [docs/intermediate-schema.md](docs/intermediate-schema.md)를 참조하세요.

```json
{
  "version": "1.0",
  "sourceFormat": "IDML",
  "layout": { "defaultPageWidth": 1512000, "defaultPageHeight": 2139120 },
  "fonts": [...],
  "paragraphStyles": [...],
  "characterStyles": [...],
  "pages": [
    {
      "pageNumber": 1,
      "frames": [
        {
          "frameType": "text",
          "x": 216000, "y": 252000,
          "paragraphs": [...]
        }
      ]
    }
  ]
}
```

## 단위

- **HWPUNIT**: 1/7200 inch (HWP/HWPX 내부 단위)
  - 1pt = 100 HWPUNIT
  - 1mm ≈ 283.46 HWPUNIT
  - 1inch = 7200 HWPUNIT

## 프로젝트 구조

```
src/main/java/kr/dogfoot/hwpxlib/
├── reader/          # HWPX 파일 읽기
├── writer/          # HWPX 파일 쓰기
├── object/          # HWPX 객체 모델
└── tool/
    ├── idmlconverter/       # IDML → HWPX 변환
    │   ├── idml/            # IDML 문서 모델
    │   ├── intermediate/    # 중간 표현 모델
    │   └── converter/       # 변환기
    ├── equationconverter/   # 수식 변환
    └── imageinserter/       # 이미지 삽입
```

## 관련 프로젝트

이 프로젝트는 [hwpxlib](https://github.com/neolord0/hwpxlib)를 기반으로 합니다.

- [hwplib](https://github.com/neolord0/hwplib) - HWP 파일 라이브러리
- [hwpxlib](https://github.com/neolord0/hwpxlib) - HWPX 파일 라이브러리 (원본)
- [hwp2hwpx](https://github.com/neolord0/hwp2hwpx) - HWP → HWPX 변환
- [hwpxlib_ext](https://github.com/neolord0/hwpxlib_ext) - hwpxlib 확장 (암호화 등)

## 참고 문서

- [OWPML 문서](http://www.hancom.com/etc/hwpDownload.do?gnb0=269&gnb1=271&gnb0=101&gnb1=140) - 한글과컴퓨터 공개 문서
- [IDML 스펙](https://www.adobe.com/devnet/indesign/documentation.html) - Adobe InDesign IDML 문서

## 라이선스

Apache-2.0 License

---

*"본 제품은 한글과컴퓨터의 HWP 문서 파일(.hwp) 공개 문서를 참고하여 개발하였습니다."*
