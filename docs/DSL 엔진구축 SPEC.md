자바-코틀린 하이브리드 아키텍처 기반의 인디자인(IDML) to HWPX 변환 규칙 엔진(Rule Engine) 구축을 위한 기술 사양서(SPEC)입니다.

이 문서는 코어 엔진의 안정성을 유지하면서, 규칙 정의부를 코틀린 DSL로 분리하여 AI Agent 기반의 바이브 코딩 생산성을 극대화하는 것을 목적으로 합니다.

---

# [SPEC] 자바-코틀린 하이브리드 기반 HWPX 변환 DSL 엔진 구축 사양서

## 1. 개요 (Overview)

* **문서 목적:** 기존 Java 기반 변환 시스템의 서식 매핑/변환 로직을 가독성이 높은 Kotlin 내부 DSL(Domain-Specific Language) 구조로 분리·이관하기 위한 기술 규격을 정의함.
* **배경:** 복잡한 XML 파싱/생성 로직(Java)과 빈번하게 변경되는 출판 서식 규칙(Kotlin)을 격리하여, 시스템 안정성을 확보하고 AI 및 현업 담당자의 규칙 수정 생산성을 극대화함.

---

## 2. 시스템 아키텍처 및 제약 사항 (Architecture & Constraints)

### 2.1 기술 스택 (Tech Stack)

* **Core / Infrastructure:** Java 17 이상 (기존 레거시 엔진 유지)
* **Rule Layer / DSL:** Kotlin 1.9+ (JVM 대상)
* **Build Tool:** Gradle 7.x/8.x (Java-Kotlin 조인트 컴파일 설정 필수)

### 2.2 레이어 간 데이터 흐름 (Data Flow)

```
[Java Core: IdmlParser] ──(파싱)──> [Java/Kotlin: ElementContext (DTO)]
                                                  │
                                                  ▼ (참조 전달)
[Java Core: MainConverter] ──(호출)──> [Kotlin Core: RuleRegistry]
                                                  │
                                                  ▼ (규칙 매칭 및 세팅)
[Java Core: HwpxGenerator] <──(값 변경)── [Kotlin Config: Rules.kt (DSL)]

```

### 2.3 제약 및 개발 원칙

1. **코어 엔진 수정 금지:** Java로 작성된 IDML 파서 및 HWPX 파일 생성기(ZIP/XML 컴파일러)의 코어 로직은 원칙적으로 수정하지 않는다.
2. **참조에 의한 변경 (Pass-by-Reference):** Kotlin 룰 엔진은 Java로부터 변환 컨텍스트(DTO) 객체를 넘겨받아 내부 필드 값을 직접 수정(Mutate)하는 방식으로 동작한다.
3. **상호 운용성 보장:** Kotlin 소스 코드는 Java에서 패키지 참조 및 인스턴스 접근이 완벽히 가능하도록 `Companion Object` 또는 `@JvmStatic`을 적극 활용한다.

---

## 3. 인터페이스 및 도메인 모델 스펙 (Data Model Spec)

### 3.1 변환 데이터 컨텍스트 (Java DTO)

인디자인에서 추출된 개별 요소의 서식 정보 및 HWPX로 변환될 타겟 서식 정보를 담는 공통 객체입니다.

```java
package com.publisher.converter.domain;

public class ElementContext {
    // Source 데이터 (InDesign 측 정보 - Read Only)
    private final String sourceStyleName;
    private final String textContent;

    // Target 데이터 (HWPX 측 정보 - Rule Engine에 의해 수정됨)
    private String targetFontFamily = "함초롬돋움"; 
    private double targetFontSize = 10.0;
    private int targetLineSpacing = 160;          // % 단위
    private double targetLetterSpacing = 0.0;     // % 단위
    private double targetIndent = 0.0;            // mm 단위

    public ElementContext(String sourceStyleName, String textContent) {
        this.sourceStyleName = sourceStyleName;
        this.textContent = textContent;
    }

    // Getters and Mutator Setters...
}

```

---

## 4. Kotlin 내부 DSL 및 규칙 엔진 규격 (DSL & Engine Spec)

### 4.1 DSL 빌더 디자인 (Kotlin 코어 코드)

자연어 스타일 구문을 지원하기 위한 수신 객체 지정 람다 기반의 빌더 규격입니다.

```kotlin
package com.publisher.converter.rule

import com.publisher.converter.domain.ElementContext

// 개별 규칙을 표현하는 도메인 빌더 클래스
class ParagraphRuleBuilder(val sourceStyle: String) {
    var fontName: String? = null
    var fontSize: Double? = null
    var lineSpacing: Int? = null

    // 자연어 흐름을 연출하기 위한 스코프 함수
    fun convert(block: ParagraphRuleBuilder.() -> Unit) {
        this.block()
    }
}

// 규칙들을 관리하고 Java 레이어와 통신하는 레지스트리
class HwpxRuleRegistry private constructor() {
    private val ruleMap = mutableMapOf<String, ParagraphRuleBuilder>()

    companion object {
        @JvmStatic
        val instance = HwpxRuleRegistry()
    }

    // 규칙 등록을 위한 DSL 진입점
    fun registerRule(sourceStyle: String, init: ParagraphRuleBuilder.() -> Unit) {
        val builder = ParagraphRuleBuilder(sourceStyle)
        builder.init()
        ruleMap[sourceStyle] = builder
    }

    // Java 엔진이 호출할 매칭 로직
    @JvmStatic
    fun applyRule(context: ElementContext) {
        ruleMap[context.sourceStyleName]?.let { rule ->
            rule.fontName?.let { context.targetFontFamily = it }
            rule.fontSize?.let { context.targetFontSize = it }
            rule.lineSpacing?.let { context.targetLineSpacing = it }
        }
    }
}

```

---

## 5. 규칙 정의 규격 (Rules Configuration Spec)

AI Agent 및 현업 담당자가 규칙을 추가/수정하는 전용 설정 파일 스펙입니다. 완벽한 선언형 및 자연어 유사 구조를 유지합니다.

* **파일 위치:** `src/main/kotlin/com/publisher/converter/config/ConversionRules.kt`

```kotlin
package com.publisher.converter.config

import com.publisher.converter.rule.HwpxRuleRegistry

fun loadConversionRules() {
    val r = HwpxRuleRegistry.instance

    // 초등 국어 본문 스타일 규칙
    r.registerRule("ParagraphStyle/국어_본문_기본") {
        convert {
            fontName = "함초롬돋움"
            fontSize = 11.0
            lineSpacing = 160
        }
    }

    // 중등 수학 문제 번호 스타일 규칙
    r.registerRule("ParagraphStyle/Math_Question_No") {
        convert {
            fontName = "함초롬바탕"
            fontSize = 10.5
            lineSpacing = 130
        }
    }
}

```

---

## 6. 빌드 및 하이브리드 컴파일 설정 (Build Configuration)

Java 소스 코드가 Kotlin으로 작성된 규칙 레지스트리를 컴파일 타임에 완벽히 참조할 수 있도록 조인트 컴파일 구성을 정의합니다.

* **파일 위치:** `build.gradle`

```groovy
plugins {
    id 'java'
    id 'org.jetbrains.kotlin.jvm' version '1.9.22'
}

group = 'com.publisher'
version = '1.0-SNAPSHOT'

repositories {
    mavenCentral()
}

dependencies {
    implementation "org.jetbrains.kotlin:kotlin-stdlib"
    testImplementation 'org.junit.jupiter:junit-jupiter-api:5.10.0'
}

// 중요: Kotlin 컴파일러가 Java 컴파일러보다 먼저 작동하여 상호 참조 인터페이스를 생성함
compileKotlin {
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = ["-Xjsr305=strict"] // 자바 Null 안정성 강화
    }
}

compileJava {
    targetCompatibility = "17"
    sourceCompatibility = "17"
}

```

---

## 7. AI Agent(바이브 코딩) 가드레일 가이드

AI 가 규칙을 안전하게 생성할 수 있도록 프롬프트 컨텍스트로 주입할 가드레일 규칙입니다.

1. **수정 권한 제한:** AI는 오직 `ConversionRules.kt` 내부의 `loadConversionRules()` 함수 내부에 규칙 블록(`r.registerRule(...) { ... }`)을 추가하거나 수정하는 행위만 수행할 수 있다.
2. **타입 안전성 준수:** 모든 스타일 이름은 인디자인 IDML 스펙에 준하여 `ParagraphStyle/` 또는 `CharacterStyle/` 접두사를 포함해야 한다.
3. **검증 자동화:** 규칙 수정 후 `gradlew test` 명령을 강제 실행하여, 기존 변환 결과물과 데이터 무결성이 일치하는지 검증하는 회귀 테스트 단계를 거쳐야 한다.