# CLAUDE.md — indesign-to-something

## 프로젝트 개요

IDML(Adobe InDesign) → HWPX(한글) 변환기. Java 백엔드 + Tauri(Rust) 데스크탑 앱 + React 프론트엔드.

## 빌드 & 실행

```bash
# Java (Maven) — JAR 빌드
mvn clean package -q -DskipTests
# 결과: target/idml-to-something-1.0.9-cli.jar (fat JAR)

# CLI 실행 (macOS)
/opt/homebrew/opt/openjdk/bin/java -jar target/idml-to-something-1.0.9-cli.jar --convert input.idml output.hwpx

# Desktop 앱 빌드
cd desktop && npm run tauri build

# Desktop 개발 모드
cd desktop && npm run tauri dev
```

## 아키텍처

### 변환 파이프라인

```
.indd → [ExtendScript] → .idml + resolved.json
                              ↓
                         IDMLLoader
                              ↓
                    IDMLNormalizer (4단계)
                     ├─ Stage1_Flatten      → FlattenedObjectPool
                     ├─ Stage2_InlineDetect → 인라인/플로팅 분류
                     ├─ Stage3_CollapseInlines → 인라인 트리 정리
                     └─ Stage4_BuildAST     → ASTDocument
                              ↓
                  [Resolved 보강 — 선택적]
                     ├─ ResolvedMerger.enrich()    → 스타일/색상 보강
                     └─ ResolvedFrameDistributor   → 프레임별 문단 재배치
                              ↓
                    ASTToHwpxConverter
                     ├─ HwpxParagraphBuilder
                     ├─ HwpxTextBoxBuilder
                     ├─ HwpxTableBuilder
                     └─ HwpxImageBuilder
                              ↓
                          .hwpx 출력
```

### 핵심 모듈

| 모듈 | 위치 | 역할 |
|------|------|------|
| IDML 파서 | `idml/` | IDML ZIP 로딩, XML 파싱 |
| 정규화 | `normalizer/` | 4단계 파이프라인 (Stage1~4) |
| AST | `ast/` | 중간 표현 (ASTDocument, ASTSection, ASTBlock...) |
| Resolved | `resolved/` | InDesign DOM 데이터 보강 (색상, 스타일, 프레임 배치) |
| 변환기 | `converter/` | AST → HWPX 변환, Builder 패턴 |
| CLI | `ConverterCLI.java` | 명령행 인터페이스 |

### Desktop 앱 구조

| 레이어 | 기술 | 위치 |
|--------|------|------|
| Frontend | React 18 + Zustand + Tailwind | `desktop/src/` |
| Bridge | Tauri 2.0 (Rust) | `desktop/src-tauri/src/` |
| Backend | Java CLI (subprocess) | `src/main/java/...` |
| ExtendScript | InDesign DOM 추출 | `scripts/extract_indd.jsx` |

## 주요 파일 경로

```
src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/
├── ConverterCLI.java              # CLI 엔트리포인트
├── IDMLToHwpxConverter.java       # 변환 파사드
├── ConvertOptions.java            # 변환 옵션
├── ast/
│   ├── ASTDocument.java           # AST 루트
│   ├── ASTSection.java            # 페이지 단위 섹션
│   ├── ASTTextFrameBlock.java     # 텍스트 프레임 블록
│   ├── ASTParagraph.java          # 문단
│   ├── ASTTextRun.java            # 텍스트 런
│   ├── ASTSerializer.java         # AST → JSON
│   └── ASTDeserializer.java       # JSON → AST
├── normalizer/
│   ├── IDMLNormalizer.java        # 정규화 오케스트레이터
│   ├── Stage1_Flatten.java        # 객체 평탄화
│   ├── Stage2_InlineDetect.java   # 인라인 감지
│   ├── Stage3_CollapseInlines.java # 인라인 정리
│   └── Stage4_BuildAST.java       # AST 빌드
├── converter/
│   ├── ASTToHwpxConverter.java    # HWPX 변환 메인
│   ├── HwpxTextBoxBuilder.java    # 글상자 빌더
│   ├── HwpxParagraphBuilder.java  # 문단 빌더
│   ├── HwpxTableBuilder.java      # 표 빌더
│   └── HwpxImageBuilder.java      # 이미지 빌더
├── resolved/
│   ├── ResolvedData.java          # resolved 데이터 컨테이너
│   ├── ResolvedDataReader.java    # JSON 파서 (Gson lenient)
│   ├── ResolvedMerger.java        # AST 보강
│   └── ResolvedFrameDistributor.java # 프레임 문단 재배치
└── idml/
    ├── IDMLLoader.java            # IDML ZIP 로더
    └── IDMLDocument.java          # IDML 문서 모델

desktop/src-tauri/src/
├── commands.rs                    # Tauri 커맨드 핸들러
├── indesign.rs                    # InDesign ExtendScript 호출
└── lib.rs                         # Tauri 앱 설정

desktop/src/stores/
├── useAppStore.ts                 # 앱 상태 (파일, 변환, INDD)
└── useAstStore.ts                 # AST 뷰어 상태
```

## 코딩 컨벤션

- **Java 8** 호환 (람다 OK, var 불가)
- **접근자 패턴**: `field()` getter, `field(value)` setter (JavaBean 스타일 아님)
- **단위**: HWPUNIT (1pt = 100 hwpunit, 1inch = 7200 hwpunit)
- **ID 형식**: IDML `u` + hex (`u1735`), InDesign DOM decimal (`5941`), 변환: `parseInt("1735", 16) = 5941`
- **커밋 메시지**: 한글, 기능 설명 중심 (예: "resolved.json 보강 파이프라인, 다단 N개 글상자 변환")
- **브랜치**: `main` (릴리스), 기능 브랜치에서 개발

## SPEC 기반 개발 워크플로우

### 새 기능/수정 시

1. **SPEC 작성** → `docs/specs/<feature-name>.md`
   - 문제 정의, 영향 범위, 해결 방안, 수정 파일 목록
2. **리뷰** → SPEC 검토 후 구현 시작
3. **구현** → SPEC의 수정 파일 목록 순서대로
4. **검증** → `mvn clean package -q -DskipTests` 빌드, CLI 테스트
5. **결과** → SPEC에 완료 상태 기록

### SPEC 템플릿

```markdown
# [기능명]

## 문제
현재 상황과 문제점

## 목표
기대하는 결과

## 해결 방안
기술적 접근 방법

## 수정 파일
1. `path/to/File.java` — 변경 내용
2. `path/to/File2.java` — 변경 내용

## 검증
- [ ] 빌드 성공
- [ ] 테스트 케이스
```

## 알려진 이슈 & 주의사항

- **ExtendScript JSON**: 제어 문자를 이스케이프하지 못함 → Gson lenient 모드 필수
- **문단 인덱스 불일치**: IDML(AST)과 InDesign DOM(resolved)의 문단 수가 다름 → 텍스트 기반 매핑 사용
- **HWPX 연결 글상자**: 한글은 연결 글상자 체인에서 후속 프레임의 명시적 콘텐츠를 무시 → distributed 프레임은 linkListIDRef=0으로 해제
- **Java 경로**: macOS에서 `/opt/homebrew/opt/openjdk/bin/java` (Homebrew)
