# @its/semantic-schemas

시멘틱 레이어의 캐노니컬 스키마(SSOT). TypeScript 시멘틱 레이어와 Java 백엔드 추출기가 같은 JSON 파일을 읽는다.

## 구조

```
packages/semantic-schemas/
├── package.json
├── README.md
└── schemas/
    ├── common.schema.json          공통 라벨 (PAGE_HEADER, BODY_TEXT, FIGURE, ...)
    └── math-reference.schema.json  수학 교과서 라벨 (CHAPTER_TITLE, PROBLEM, ...)
```

## 포맷 명세

[docs/semantic-format.md](../../docs/semantic-format.md) 참조.

## 사용

### TypeScript

```ts
import commonSchema from "@its/semantic-schemas/schemas/common.schema.json";
import mathSchema from "@its/semantic-schemas/schemas/math-reference.schema.json";
```

### Java (Maven 리소스)

`pom.xml`의 `<resources>`에 다음을 추가:

```xml
<resource>
  <directory>${project.basedir}/packages/semantic-schemas/schemas</directory>
  <targetPath>semantic-schemas</targetPath>
</resource>
```

런타임에서:

```java
InputStream is = getClass().getClassLoader()
    .getResourceAsStream("semantic-schemas/common.schema.json");
```

## SPEC

[SPEC-018: 시멘틱 레이어 통합](../../docs/specs/SPEC-018-semantic-extraction.md)
