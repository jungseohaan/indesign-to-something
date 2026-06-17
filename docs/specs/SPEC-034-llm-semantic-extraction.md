# SPEC-034: LLM 기반 교수자료 생성 (IDML → PPT 슬라이드)

> **상태**: Draft (2026-06-04)  
> **관련**: [SPEC-018](SPEC-018-semantic-extraction.md) (TypeScript PPTX 출력), [SPEC-markdown](SPEC-markdown-export.md)

---

## 목표

InDesign 교과서(IDML)를 입력으로 받아, 사용자 정의 **기본 프롬프트**(`base_prompt.txt`)를 기반으로 GROQ/Anthropic LLM이 교수자료 슬라이드 콘텐츠를 생성한다.

```
IDML
  │
  ▼
AST (Phase 0~7)
  │
  ▼  [단원/섹션 추출]
섹션별 텍스트 청크
  │
  ├── base_prompt.txt  (교수자료 지시 — 사용자 작성)
  │
  ▼
GROQ / Anthropic LLM
  │
  ▼
teaching_material.json  ← ★ SSOT
  │
  ├── [Java] PPTX 직접 생성 (Apache POI, M2)
  └── [Desktop App] TypeScript PPTX 렌더 (SPEC-018 경로, 선택)
```

---

## 프롬프트 레이어링 설계

프롬프트는 **기본(base) + 교과서별(extra)** 두 레이어로 구성된다.

```
prompts/
  base_prompt.txt            # 공통 기본 지시 (모든 교과서 적용)
  extra/
    중3영어_박영민.txt          # 교과서별 추가 지시 (선택)
    중3국어_천재교육.txt
    중2수학_비상교육.txt
```

LLM에 전달되는 최종 시스템 프롬프트:

```
[기본 지시]
{base_prompt.txt 전체 내용}

[이 교과서 추가 지시]
{extra/중3영어_박영민.txt 전체 내용}   ← 없으면 이 섹션 생략
```

- **base_prompt.txt**: 슬라이드 레이아웃, 수준, 공통 규칙
- **extra prompt**: 특정 교과서의 특수 구조(예: "이 교과서는 Listen & Speak 섹션이 있음"), 출판사별 스타일, 강조 포인트 추가
- `teaching_material.json`에 사용된 프롬프트 파일 목록 기록 (`promptFiles` 필드)

### CLI 사용법

```bash
# 기본 프롬프트만
java -jar ... --teach prompts/base_prompt.txt --convert input.idml out.json

# 기본 + 교과서별 추가
java -jar ... --teach prompts/base_prompt.txt \
              --teach-extra prompts/extra/중3영어_박영민.txt \
              --convert input.idml out.json

# prompts/ 디렉토리를 지정하면 base_prompt.txt 자동 로드
# + --textbook-id로 extra 자동 매핑
java -jar ... --teach-dir prompts/ \
              --textbook-id 중3영어_박영민 \
              --convert input.idml out.json
```

### TeachingPromptLoader 설계

```java
class TeachingPromptLoader {
    // 기본 프롬프트만
    static String load(String basePromptPath);

    // 기본 + extra 병합
    static String load(String basePromptPath, String extraPromptPath);

    // prompts/ 디렉토리 + textbookId로 자동 탐색
    // base: {dir}/base_prompt.txt
    // extra: {dir}/extra/{textbookId}.txt (없으면 생략)
    static String loadFromDir(String promptsDir, String textbookId);
}
```

## base_prompt.txt 예시

```
이 교과서 단원을 중학교 교사가 수업에서 쓸 PPT로 변환해주세요.
각 단원마다 다음 슬라이드를 만들어주세요:

1. 제목 슬라이드 — 단원 제목, 학년/과목
2. 학습 목표 슬라이드 — 불릿 3~5개
3. 핵심 어휘 슬라이드 — 단어 + 뜻 + 예문 (최대 8개)
4. 본문 요약 슬라이드 — 핵심 내용 불릿 4~6개
5. 연습 문제 슬라이드 — 문제 2~3개

규칙:
- 각 슬라이드에 교사 노트(notes) 포함
- 중학교 2~3학년 수준 언어 사용
- 영어 교과서이므로 핵심 표현은 영어 원문 유지
- 반드시 JSON 형식으로만 응답
```

## extra/중3영어_박영민.txt 예시

```
이 교과서는 다음 섹션 구조를 따릅니다:
- Listen & Speak: 대화문 중심, 롤플레이 슬라이드 추가
- Read & Think: 본문 독해, 핵심 문장 하이라이트 포함
- Grammar: 문법 규칙 슬라이드 별도 생성 (예시 3개 포함)

단원당 슬라이드 수를 최대 12장으로 제한해주세요.
```

---

## teaching_material.json 스키마

```json
{
  "source": "중학교영어3-1단원",
  "generatedAt": "2026-06-04T12:00:00Z",
  "promptFile": "base_prompt.txt",
  "model": "llama-3.3-70b-versatile",
  "units": [
    {
      "unitIndex": 1,
      "unitTitle": "나와 세계를 잇는 언어",
      "sourcePages": [10, 29],
      "slides": [
        {
          "index": 1,
          "layout": "title",
          "title": "나와 세계를 잇는 언어",
          "subtitle": "중학교 영어 3학년 1단원",
          "notes": "오늘 수업 소개: 영어가 세계를 잇는 도구임을 강조"
        },
        {
          "index": 2,
          "layout": "bullets",
          "title": "학습 목표",
          "bullets": [
            "영어로 자신을 소개할 수 있다.",
            "세계 각지의 문화 표현을 이해한다.",
            "핵심 어휘 10개를 활용한 문장을 만들 수 있다."
          ],
          "notes": "목표를 학생들이 직접 읽게 한다."
        },
        {
          "index": 3,
          "layout": "vocabulary",
          "title": "핵심 어휘",
          "items": [
            {"word": "connect", "meaning": "연결하다", "example": "English connects people worldwide."}
          ],
          "notes": "플래시카드 방식으로 퀴즈 가능"
        }
      ]
    }
  ]
}
```

`layout` 종류: `title` | `bullets` | `vocabulary` | `qa` | `image` | `custom`

---

## 아키텍처

### 신규 Java 파일

| 파일 | 역할 |
|------|------|
| `llm/AIClient.java` | OpenAI/Anthropic/GROQ HTTP 클라이언트 (HttpURLConnection, 외부 의존성 없음) |
| `llm/LLMConfig.java` | apiKey, model, timeout DTO |
| `llm/LLMException.java` | Checked exception |
| `llm/DocumentChunker.java` | AST → 단원/섹션 단위 텍스트 청크 |
| `llm/TeachingPromptLoader.java` | `base_prompt.txt` 로드 |
| `llm/TeachingMaterialGenerator.java` | 메인 오케스트레이터 |
| `llm/SlideContent.java` | 슬라이드 데이터 모델 |
| `llm/TeachingUnit.java` | 단원 데이터 모델 |
| `llm/TeachingMaterialWriter.java` | `teaching_material.json` 직렬화 (Gson) |
| `pptx/PptxRenderer.java` | `teaching_material.json` → `.pptx` (Apache POI, M2) |

### 수정 파일

| 파일 | 변경 내용 |
|------|----------|
| `ConverterCLI.java` | `--teach` 플래그 추가 |
| `pom.xml` | Apache POI `poi-ooxml` 의존성 추가 (M2) |

---

## AIClient 설계

```java
// GROQ
POST https://api.groq.com/openai/v1/chat/completions
Authorization: Bearer {GROQ_API_KEY}
{"model": "llama-3.3-70b-versatile", "temperature": 0.3,
 "response_format": {"type": "json_object"},
 "messages": [{"role":"system","content":"{base_prompt}"},
               {"role":"user","content":"{section_text}"}]}

// Anthropic 폴백
POST https://api.anthropic.com/v1/messages
x-api-key: {ANTHROPIC_API_KEY}
anthropic-version: 2023-06-01
{"model": "claude-haiku-4-5-20251001", "max_tokens": 4096, ...}
```

**모델 우선순위**: `GROQ_API_KEY` 있으면 GROQ → 없으면 `ANTHROPIC_API_KEY` → 둘 다 없으면 오류.

---

## DocumentChunker 설계

```java
// AST → 단원별 텍스트 청크
List<DocumentChunk> chunk(ASTDocument doc);

class DocumentChunk {
    int unitIndex;
    String unitTitle;        // unit_title 스타일 단락에서 감지
    List<Integer> pages;
    String textContent;      // 섹션 내 텍스트 (마크다운 형식)
    List<String> imageRefs;  // 포함된 이미지 경로
}
```

- 단원 경계 감지: 스타일명에 `제목1`/`단원`/`Unit`/`Chapter` 포함 단락
- 단원이 없으면 페이지 10개 단위로 분할
- 텍스트는 기존 `ASTToMarkdownConverter` 활용 (섹션 범위 지정 오버로드)

---

## TeachingMaterialGenerator 설계

```java
public TeachingMaterial generate(
        ASTDocument doc,
        String basePromptPath,
        LLMConfig config) throws LLMException;

// 내부 흐름:
// 1. TeachingPromptLoader.load(basePromptPath) → systemPrompt
// 2. DocumentChunker.chunk(doc) → List<DocumentChunk>
// 3. 각 청크별:
//    a. userPrompt 조립 (청크 텍스트 + "JSON으로만 응답" 지시)
//    b. AIClient.complete(systemPrompt, userPrompt)
//    c. 응답 파싱 → List<SlideContent>
//    d. 파싱 실패 시 재시도 1회 → 그래도 실패 시 fallback 슬라이드
// 4. TeachingMaterial 조립 + TeachingMaterialWriter.write()
```

---

## CLI 사용법

```bash
# 기본: teaching_material.json 생성
java -jar idml-to-something-1.0.9-cli.jar \
  --teach base_prompt.txt \
  --convert input.idml teaching_material.json \
  --resolved resolved.json

# PPTX 직접 생성 (M2)
java -jar ... --teach base_prompt.txt \
  --convert input.idml output.pptx \
  --resolved resolved.json

# LLM 연결 테스트
java -jar ... --test-llm
```

---

## PptxRenderer (M2)

Apache POI `poi-ooxml`로 `.pptx` 직접 생성.

```xml
<!-- pom.xml 추가 -->
<dependency>
  <groupId>org.apache.poi</groupId>
  <artifactId>poi-ooxml</artifactId>
  <version>5.2.5</version>
</dependency>
```

슬라이드 레이아웃별 매핑:

| layout | POI 처리 |
|--------|---------|
| `title` | 제목 + 부제목 placeholder |
| `bullets` | 제목 + 내용 텍스트박스 (불릿 자동 들여쓰기) |
| `vocabulary` | 2열 표 (단어 | 뜻+예문) |
| `qa` | 질문/답 텍스트박스 |
| `image` | 이미지 삽입 (imageRef 경로) |

발표자 노트(`notes`)는 모든 슬라이드 노트 영역에 삽입.

---

## 구현 단계

### M1: LLM 연결 + JSON 생성
- `AIClient`, `LLMConfig`, `LLMException` 구현
- `DocumentChunker`, `TeachingPromptLoader` 구현
- `TeachingMaterialGenerator` + `TeachingMaterialWriter`
- CLI `--teach` + `--test-llm` 플래그
- 검증: 중3영어 1단원 → `teaching_material.json` 확인

### M2: PPTX 렌더링
- `pom.xml`에 `poi-ooxml` 추가
- `PptxRenderer` 구현 (5개 레이아웃)
- CLI에서 출력 파일이 `.pptx`이면 자동 렌더
- 검증: 생성된 PPTX를 LibreOffice/PowerPoint에서 육안 확인

### M3: 품질 개선 (선택)
- PPTX 슬라이드 마스터/테마 적용 (`.potx` 템플릿 기반)
- base_prompt를 `conversion-config.json`에 경로 설정 가능하게
- 이미지 삽입 (`imageRef` → PNG 포함)

---

## 검증

- [ ] M1: `--test-llm` ping 성공
- [ ] M1: GROQ_API_KEY 없을 때 명확한 에러 메시지 출력
- [ ] M1: `teaching_material.json` 스키마 검증 (단원 제목, 슬라이드 수)
- [ ] M2: `output.pptx` 파일 생성, PowerPoint에서 열림
- [ ] M2: 슬라이드 5종 레이아웃 모두 정상 렌더
- [ ] API 키 로그 노출 없음 (마스킹 확인)
- [ ] 빌드: `mvn clean package -q -DskipTests` 성공

---

## 주의사항

- **API 키 마스킹**: `AIClient` 에러 메시지에 키 원문 포함 금지. 앞 8자 + `****`
- **외부 의존성**: M1은 `HttpURLConnection`만 (pom.xml 변경 없음). M2에서 `poi-ooxml` 추가
- **응답 파싱 강건성**: LLM이 JSON 외 텍스트를 섞어 반환할 수 있음 → `{}` 중괄호 범위 추출 후 파싱
- **rate limiting**: GROQ 무료 티어 분당 6,000 tokens. 200페이지 교과서 ≈ 40청크. 청크 간 1초 딜레이 기본값
- **Gson**: 이미 pom.xml에 있음 (`com.google.gson`). JSON 파싱에 사용
