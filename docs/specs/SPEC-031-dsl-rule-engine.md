# SPEC-031 — Java-Kotlin 하이브리드 HWPX 변환 DSL 규칙 엔진 구축

> 상태: **Done** (2026-05-27 구현 완료)
> 원본 초안: `docs/DSL 엔진구축 SPEC.md`

---

## 1. 개요 (Overview)

**문제:** 교과서별 서식 규칙(폰트, 줄간격, 들여쓰기 등)이 현재 `CharPrFactory.java`, `ParaPrFactory.java`, `FontMapper.java`, `font-mapping.json` 네 곳에 분산·하드코딩되어 있다. 새 교과서 추가 시마다 Java 코드를 직접 수정해야 하며, AI Agent 기반 바이브 코딩으로 안전하게 규칙을 추가하기 어렵다.

**목표:** 빈번하게 변경되는 "출판 서식 규칙 정의" 파트를 Kotlin 내부 DSL 파일 한 곳(`ConversionRules.kt`)으로 분리한다. 코어 파이프라인(Phase 0~7, ASTToHwpxConverter) 코드는 수정하지 않는다.

---

## 2. 아키텍처 및 제약 (Architecture & Constraints)

### 2.1 기술 스택

| 레이어 | 기술 | 비고 |
|--------|------|------|
| Core / Infrastructure | **Java 8** | 기존 코드 전면 유지 (`source=8 target=8`) |
| Rule Layer / DSL | **Kotlin 1.9+ (JVM target 1.8)** | 신규 추가 |
| Build | **Maven** (`kotlin-maven-plugin`) | Gradle 전환 없이 조인트 컴파일 |

### 2.2 레이어 간 데이터 흐름

```
Phase 3 StoryConverter
       │  IDML CharacterRun + resolved.json
       ▼
  ASTTextRun / ASTParagraph  ←── 기존 AST 모델 (변경 없음)
       │
       ▼
HwpxParagraphBuilder
  ├─ CharPrFactory.buildCharPr(run, ctx)
  │       └──(추가)── HwpxRuleRegistry.applyCharRule(ruleCtx)
  └─ ParaPrFactory.resolveParaLong(para, style)
            └──(추가)── HwpxRuleRegistry.applyParaRule(ruleCtx)
                              │
                              ▼
                    ConversionRules.kt  ←── AI/담당자가 수정하는 유일한 파일
```

### 2.3 개발 원칙

1. **코어 엔진 수정 금지**: Phase 0~7 파이프라인, `ASTToHwpxConverter`, IDML 파서 로직은 건드리지 않는다.
2. **참조에 의한 변경**: Kotlin 레지스트리는 `RuleContext` DTO를 받아 필드를 직접 수정(Mutate)한다.
3. **Java 8 호환**: Kotlin 코드는 `@JvmStatic`, `Companion object`를 사용해 Java에서 정적 호출 가능하도록 한다. `var`/람다는 Java 8 바이트코드로 컴파일된다.
4. **점진적 이관**: JSON 기반 `font-mapping.json`은 유지하되, 스타일별 세밀한 규칙(줄간격 예외, 들여쓰기 특례 등)을 Kotlin DSL로 우선 이관한다.

---

## 3. 인터페이스 및 도메인 모델 스펙

### 3.1 RuleContext (신규 Java DTO)

> 파일: `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/rule/RuleContext.java`

```java
package kr.dogfoot.hwpxlib.tool.idmlconverter.rule;

/**
 * 변환 규칙 엔진용 DTO. CharPrFactory / ParaPrFactory가 채워서 넘기고,
 * Kotlin RuleRegistry가 target 필드를 Mutate한다.
 */
public class RuleContext {

    // ── Source (Read-Only) ─────────────────────────────────────────
    /** IDML ParagraphStyle Self 값. 예: "ParagraphStyle/대단원 설명" */
    public final String paragraphStyleRef;
    /** IDML CharacterStyle Self 값. 예: "CharacterStyle/#대단원 제목" */
    public final String characterStyleRef;
    /** 해당 런의 텍스트 내용 (빈 단락이면 "") */
    public final String textContent;

    // ── Target (Kotlin Rule Engine이 Mutate) ───────────────────────
    /** HWPX 한글 폰트 패밀리 */
    public String targetKoFont = null;
    /** HWPX 영문 폰트 패밀리 */
    public String targetEnFont = null;
    /** 폰트 크기 (pt) */
    public Double targetFontSizePt = null;
    /** 줄간격 (%, 예: 160) */
    public Integer targetLineSpacingPct = null;
    /** 자간 (%, 예: -5) */
    public Double targetLetterSpacingPct = null;
    /** 왼쪽 들여쓰기 (mm) */
    public Double targetLeftIndentMm = null;
    /** 단락 앞 여백 (mm) */
    public Double targetSpaceBeforeMm = null;

    public RuleContext(String paragraphStyleRef, String characterStyleRef, String textContent) {
        this.paragraphStyleRef = paragraphStyleRef;
        this.characterStyleRef = characterStyleRef;
        this.textContent = textContent != null ? textContent : "";
    }
}
```

---

## 4. Kotlin 규칙 엔진 코어 스펙

> 파일: `src/main/kotlin/kr/dogfoot/hwpxlib/tool/idmlconverter/rule/HwpxRuleRegistry.kt`

```kotlin
package kr.dogfoot.hwpxlib.tool.idmlconverter.rule

// ── DSL 빌더 ─────────────────────────────────────────────────────

class ParagraphRuleBuilder(val styleRef: String) {
    var koFont: String? = null
    var enFont: String? = null
    var fontSizePt: Double? = null
    var lineSpacingPct: Int? = null
    var letterSpacingPct: Double? = null
    var leftIndentMm: Double? = null
    var spaceBeforeMm: Double? = null

    fun convert(block: ParagraphRuleBuilder.() -> Unit) = this.block()
}

class CharacterRuleBuilder(val styleRef: String) {
    var koFont: String? = null
    var enFont: String? = null
    var fontSizePt: Double? = null
    var letterSpacingPct: Double? = null
}

// ── 레지스트리 ────────────────────────────────────────────────────

class HwpxRuleRegistry private constructor() {

    private val paraRules = mutableMapOf<String, ParagraphRuleBuilder>()
    private val charRules = mutableMapOf<String, CharacterRuleBuilder>()

    companion object {
        @JvmStatic
        val instance = HwpxRuleRegistry()

        /** Java의 CharPrFactory / ParaPrFactory에서 호출 */
        @JvmStatic
        fun applyParaRule(ctx: RuleContext) = instance.applyPara(ctx)

        @JvmStatic
        fun applyCharRule(ctx: RuleContext) = instance.applyChar(ctx)
    }

    fun paragraphRule(styleRef: String, init: ParagraphRuleBuilder.() -> Unit) {
        paraRules[styleRef] = ParagraphRuleBuilder(styleRef).apply(init)
    }

    fun characterRule(styleRef: String, init: CharacterRuleBuilder.() -> Unit) {
        charRules[styleRef] = CharacterRuleBuilder(styleRef).apply(init)
    }

    private fun applyPara(ctx: RuleContext) {
        paraRules[ctx.paragraphStyleRef]?.let { r ->
            r.koFont?.let          { ctx.targetKoFont = it }
            r.enFont?.let          { ctx.targetEnFont = it }
            r.fontSizePt?.let      { ctx.targetFontSizePt = it }
            r.lineSpacingPct?.let  { ctx.targetLineSpacingPct = it }
            r.letterSpacingPct?.let{ ctx.targetLetterSpacingPct = it }
            r.leftIndentMm?.let    { ctx.targetLeftIndentMm = it }
            r.spaceBeforeMm?.let   { ctx.targetSpaceBeforeMm = it }
        }
    }

    private fun applyChar(ctx: RuleContext) {
        charRules[ctx.characterStyleRef]?.let { r ->
            r.koFont?.let          { ctx.targetKoFont = it }
            r.enFont?.let          { ctx.targetEnFont = it }
            r.fontSizePt?.let      { ctx.targetFontSizePt = it }
            r.letterSpacingPct?.let{ ctx.targetLetterSpacingPct = it }
        }
    }
}
```

---

## 5. 규칙 정의 파일 스펙

> **AI Agent 및 담당자가 수정하는 유일한 파일**
> 파일: `src/main/kotlin/kr/dogfoot/hwpxlib/tool/idmlconverter/config/ConversionRules.kt`

```kotlin
package kr.dogfoot.hwpxlib.tool.idmlconverter.config

import kr.dogfoot.hwpxlib.tool.idmlconverter.rule.HwpxRuleRegistry

fun loadConversionRules() {
    val r = HwpxRuleRegistry.instance

    // ── 단락 스타일 규칙 ─────────────────────────────────────────

    r.paragraphRule("ParagraphStyle/대단원 설명") {
        convert {
            koFont = "윤고딕"
            fontSizePt = 10.5
            lineSpacingPct = 170
        }
    }

    r.paragraphRule("ParagraphStyle/교과역량") {
        convert {
            koFont = "윤고딕"
            fontSizePt = 9.0
            lineSpacingPct = 150
            leftIndentMm = 4.0
        }
    }

    r.paragraphRule("ParagraphStyle/활동 번호") {
        convert {
            koFont = "윤명조"
            fontSizePt = 10.0
            lineSpacingPct = 140
        }
    }

    r.paragraphRule("ParagraphStyle/표빈공간") {
        convert {
            lineSpacingPct = 100
            spaceBeforeMm = 0.0
        }
    }

    // ── 문자 스타일 규칙 ─────────────────────────────────────────

    r.characterRule("CharacterStyle/#대단원 제목") {
        koFont = "윤고딕"
        fontSizePt = 24.0
        letterSpacingPct = -3.0
    }

    r.characterRule("CharacterStyle/#소단원 명") {
        koFont = "윤고딕"
        fontSizePt = 14.0
        letterSpacingPct = -2.0
    }

    r.characterRule("CharacterStyle/윤고딕130") {
        koFont = "윤고딕"
        letterSpacingPct = -5.0
    }
}
```

---

## 6. Maven 빌드 설정 (조인트 컴파일)

> 파일: `pom.xml` — 기존 `<build><plugins>` 섹션에 추가

```xml
<properties>
    <!-- 기존 properties에 추가 -->
    <kotlin.version>1.9.22</kotlin.version>
</properties>

<dependencies>
    <!-- 기존 의존성에 추가 -->
    <dependency>
        <groupId>org.jetbrains.kotlin</groupId>
        <artifactId>kotlin-stdlib-jdk8</artifactId>
        <version>${kotlin.version}</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <!-- kotlin-maven-plugin: Java보다 먼저 컴파일되어 Java가 참조 가능 -->
        <plugin>
            <groupId>org.jetbrains.kotlin</groupId>
            <artifactId>kotlin-maven-plugin</artifactId>
            <version>${kotlin.version}</version>
            <executions>
                <execution>
                    <id>compile</id>
                    <goals><goal>compile</goal></goals>
                    <configuration>
                        <sourceDirs>
                            <sourceDir>${project.basedir}/src/main/kotlin</sourceDir>
                            <sourceDir>${project.basedir}/src/main/java</sourceDir>
                        </sourceDirs>
                    </configuration>
                </execution>
            </executions>
            <configuration>
                <jvmTarget>1.8</jvmTarget> <!-- Java 8 호환 유지 -->
            </configuration>
        </plugin>

        <!-- 기존 maven-compiler-plugin: Kotlin 클래스를 참조하도록 순서 보장 -->
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <source>8</source>
                <target>8</target>
            </configuration>
            <executions>
                <!-- default-compile을 kotlin 이후 단계로 이동 -->
                <execution>
                    <id>default-compile</id>
                    <phase>none</phase>
                </execution>
                <execution>
                    <id>java-compile</id>
                    <phase>compile</phase>
                    <goals><goal>compile</goal></goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

---

## 7. 기존 파이프라인 연동 (Hook Points)

### 7.1 CharPrFactory 연동

> 파일: `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/converter/CharPrFactory.java`

기존 `buildCharPr()` (또는 해당 메서드) 내에서 `RuleContext`를 생성해 레지스트리를 호출하는 코드를 추가한다.

```java
// 기존 코드에서 폰트/크기 결정 직전에 삽입
RuleContext ruleCtx = new RuleContext(
    run.paragraphStyleRef(),   // ASTTextRun에서 가져옴
    run.characterStyleRef(),
    run.text()
);
HwpxRuleRegistry.applyCharRule(ruleCtx);

// 이후 ruleCtx.targetKoFont가 non-null이면 우선 적용
if (ruleCtx.targetKoFont != null) {
    fontFamily = ruleCtx.targetKoFont;
}
```

### 7.2 ParaPrFactory 연동

> 파일: `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/converter/ParaPrFactory.java`

```java
// resolveParaLong() 또는 createOverrideParaPr() 내 줄간격/여백 결정 직전에 삽입
RuleContext ruleCtx = new RuleContext(para.styleRef(), null, "");
HwpxRuleRegistry.applyParaRule(ruleCtx);

if (ruleCtx.targetLineSpacingPct != null) {
    lineSpacing = ruleCtx.targetLineSpacingPct;
}
```

### 7.3 규칙 초기화 시점

> 파일: `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/IDMLToHwpxConverter.java`

변환 시작 전에 한 번만 호출한다.

```java
// convert() 메서드 초입부에 추가
ConversionRulesKt.loadConversionRules();
```

---

## 8. 수정 파일 목록

| 파일 | 변경 유형 | 내용 |
|------|----------|------|
| `src/main/java/.../rule/RuleContext.java` | **신규** | DTO |
| `src/main/kotlin/.../rule/HwpxRuleRegistry.kt` | **신규** | DSL 코어 + 레지스트리 |
| `src/main/kotlin/.../config/ConversionRules.kt` | **신규** | 규칙 정의 (AI 수정 전용) |
| `pom.xml` | **수정** | kotlin-maven-plugin 추가 |
| `converter/CharPrFactory.java` | **수정** | `currentParaStyleRef` 필드, `charPrCacheKey`에 characterStyleRef 추가, `applyCharRule()` 훅 |
| `converter/ParaPrFactory.java` | **수정** | `applyParaRule()` 훅 — 마진·줄간격 override |
| `converter/HwpxParagraphBuilder.java` | **수정** | `hasDslParaRules` bypass, `currentParaStyleRef` 세팅 |
| `IDMLToHwpxConverter.java` | **수정** | `loadConversionRules()` static initializer 호출 |

---

## 9. AI Agent(바이브 코딩) 가드레일

AI가 규칙을 안전하게 생성하기 위한 제약 규칙:

1. **수정 권한 제한**: AI는 오직 `ConversionRules.kt`의 `loadConversionRules()` 함수 내부에 `r.paragraphRule(...)` 또는 `r.characterRule(...)` 블록을 추가/수정하는 행위만 수행할 수 있다.

2. **스타일 이름 규칙**: 모든 스타일 이름은 IDML Styles.xml의 `Self=` 속성값 그대로 사용한다. `ParagraphStyle/` 또는 `CharacterStyle/` 접두사를 반드시 포함해야 한다.

3. **폰트 이름 규칙**: 대체 폰트명은 `font-mapping.json`의 `hwpxFontMetrics` 섹션에 정의된 폰트명만 사용한다.

4. **수치 단위 준수**:
   - `fontSizePt`: pt 단위 소수 (예: `10.5`)
   - `lineSpacingPct`: 퍼센트 정수 (예: `160`)
   - `letterSpacingPct`: 퍼센트 소수, 음수 허용 (예: `-3.0`)
   - `leftIndentMm` / `spaceBeforeMm`: mm 단위 소수

5. **검증 필수**: 규칙 수정 후 `mvn clean package -q -DskipTests` 빌드 성공 확인.

---

## 10. 검증 체크리스트

- [x] `mvn clean package -q -DskipTests` 빌드 성공 (2026-05-27)
- [x] `HwpxRuleRegistry.applyParaRule()` 런타임 단위 호출 확인 — `targetLineSpacingPct=100` 반환
- [x] 중3 영어 1단원 CLI 변환 결과 회귀 없음 (warnings=0, pages=48)
- [x] `ParagraphStyle/표빈공간` 3종 규칙이 HWPX header에 `lineSpacing value="100"` 3건으로 반영됨

## 11. 구현 중 발견된 트랩 (재발 방지)

### 트랩: paragraphStyleRef 접두사 불일치

**현상**: DSL 규칙을 `"ParagraphStyle/표빈공간"`으로 등록했으나 실제 `ASTParagraph.paragraphStyleRef()`는 `"표빈공간"` (접두사 없음) 형태로 저장됨. `hasParaRule()` 호출 결과가 항상 false.

**원인**: resolved.json 경로(StoryConverter)는 `rp.styleName()` → 짧은 형식. IDML 경로(StoryLoader)는 `ip.appliedParagraphStyle()` → 긴 형식. AST에 짧은 형식이 최종 저장됨.

**해결**: `HwpxRuleRegistry`의 `normalizeParaRef`/`normalizeCharRef` 함수로 등록·조회 시 모두 접두사 제거. `ConversionRules.kt`에서는 두 형식 모두 사용 가능.

### 트랩: letterSpacingPct 단위

SPEC 원안의 `letterSpacingPct` (%) 파라미터는 실제 구현에서 `letterSpacing` (HWPX ‰ 단위 Short)으로 변경. HWPX CharPr의 자간은 퍼밀(‰) 단위이므로 `-30` = -3%. 규칙 작성 시 `letterSpacing = -30` 형태로 지정.
