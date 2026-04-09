# SPEC-019: 데스크탑 앱 메뉴 재구성 (시멘틱 레이어 통합)

## 문제

데스크탑 앱은 이미 두 개의 주요 워크플로우를 제공한다 — HWPX 변환 (탭 "HWPX 내보내기")와 시멘틱 레이어 (탭 "시멘틱 레이어"). 그러나 네이티브 메뉴는 HWPX 워크플로우 위주로만 구성되어 있고, 시멘틱 관련 동작은 `SemanticPage` 내부 툴바에만 존재한다. 결과적으로:

1. **시멘틱 동작에 키보드 단축키 부재** — 추출/재분류/스키마 편집 모두 마우스 클릭 필요.
2. **다른 탭에서 접근 불가** — HWPX 탭에서 시멘틱 동작을 트리거하려면 먼저 탭 이동 필요.
3. **탭 전환 단축키 없음** — 두 워크플로우 사이 이동에 단축키가 없다.
4. **Export 일관성 부족** — HWPX 출력은 ConversionPanel 버튼, PPT 출력은 SemanticPage 툴바 버튼, JSON 입출력도 SemanticPage 안에만 — 모든 출력 진입점이 흩어져 있다.
5. **메뉴 가시성** — 신규 사용자가 시멘틱 레이어 기능 자체를 발견하기 어렵다 (탭만 있고 메뉴 항목이 없음).
6. **단축키 충돌 위험** — `View > Toggle DevTools`가 `⌘⇧I`를 사용 중인데 `File > Open InDesign...`도 같은 단축키를 사용한다 ([lib.rs:119](../../desktop/src-tauri/src/lib.rs#L119), [lib.rs:180](../../desktop/src-tauri/src/lib.rs#L180)). 이참에 정리.

## 목표

1. 시멘틱 워크플로우의 핵심 동작을 **네이티브 메뉴로 끌어올린다**.
2. 두 탭 사이 전환을 단축키로 가능하게 한다 (`⌘1` / `⌘2`).
3. 모든 출력 동작을 `File > Export ▸` 서브메뉴로 통일한다.
4. 단축키 충돌을 정리한다.
5. 컨텍스트(현재 탭, 데이터 유무)에 따라 메뉴 항목이 적절히 활성/비활성화되도록 한다 (M3 단계).

## 비목표 (이번 SPEC 범위 밖)

- SemanticPage 툴바 UI 자체 변경. (메뉴는 보조 진입점일 뿐, 툴바는 그대로 둔다.)
- 새로운 시멘틱 기능 추가. (기존 기능을 메뉴로 노출하기만 함.)
- 메뉴 다국어. (한글로 통일하든 영문 그대로 두든 기존 컨벤션 유지.)
- macOS / Windows / Linux별 키 이름 차이 보정 외 OS별 분기 추가. ([lib.rs:96-159](../../desktop/src-tauri/src/lib.rs#L96-L159) 의 기존 패턴 답습)

## 해결 방안

### 1. 새 메뉴 구조

#### File 메뉴

```
File
├── Open InDesign...                    ⌘⌥I    (변경: ⌘⇧I → ⌘⌥I, devtools와 충돌 회피)
├── Open InDesign Folder...             ⌘⌥F    (변경: ⌘⇧F → ⌘⌥F)
├── Open IDML...                        ⌘O     (기존)
├── Open HWPX...                        ⌘⇧O    (기존)
├── ─────────────────
├── Open Semantic Layer (JSON)...       ⌘⌥L   ★ NEW
├── Save Semantic Layer (JSON)...       ⌘⌥S   ★ NEW
├── ─────────────────
├── Export ▸                                   ★ NEW (서브메뉴)
│   ├── HWPX...                         ⌘E     ← 현재 ConversionPanel 버튼
│   ├── Semantic JSON...                ⌘⇧E    ← 현재 saveLayerToFile
│   └── PowerPoint (PPTX)...                   ← 현재 PPTExportModal
├── ─────────────────
├── Clear Extract Cache                        (기존)
└── Close Window
```

#### Semantic 메뉴 (신설)

```
Semantic
├── Extract from AST                    ⌘R     ★ 시멘틱 추출/재추출
├── Re-classify                         ⌘⇧R    ★ activeSchema 재적용
├── ─────────────────
├── Schema ▸
│   ├── Library...                              ★ 사용자/내장 스키마 목록
│   ├── Edit Active Schema...           ⌘⇧M    ★ SchemaEditorModal
│   ├── Generate from AST                       ★ generateSchemaFromAst
│   ├── ─────────────────
│   ├── Import Schema (JSON)...
│   └── Export Active Schema (JSON)...
├── ─────────────────
├── Rule Suggester...                          ★ RuleSuggesterPanel 토글
├── ─────────────────
└── Re-extract Review...                       ★ ReextractReviewModal
```

#### View 메뉴 (확장)

```
View
├── HWPX Converter Tab                  ⌘1     ★ NEW
├── Semantic Layer Tab                  ⌘2     ★ NEW
├── ─────────────────
├── Right Panel ▸                              (converter 탭 전용)
│   ├── AST Detail                      ⌘⇧A    ★ NEW
│   └── PDF Preview                     ⌘⇧P    ★ NEW
├── ─────────────────
├── Semantic Preview ▸                          (시멘틱 탭 전용)
│   ├── Visual                          ⌘⇧V    ★ NEW
│   └── Text                            ⌘⇧T    ★ NEW
├── ─────────────────
├── Toggle Fullscreen                          (기존)
└── Toggle DevTools                     ⌘⌥I    (변경: ⌘⇧I → ⌘⌥I)
```

> **단축키 충돌 정리**:
> - `⌘⇧I` → `Toggle DevTools` 단독 사용으로 정리. `Open InDesign...`은 `⌘⌥I`로 이동.
> - `⌘⇧F` (Open Folder)는 일부 IDE에서 검색 단축키와 충돌 가능 → `⌘⌥F`로 이동.
> - `⌘⇧S`는 일반적 "다른 이름으로 저장" 관용을 비워두기 위해 시멘틱 저장은 `⌘⌥S`.
> - `⌘1`, `⌘2`는 탭 전환 (브라우저 관례).

### 2. 메뉴 → 프론트엔드 이벤트 매핑

기존 `menu-open-indd` 패턴을 따라 새 이벤트를 정의한다.

| 메뉴 항목 ID (Rust) | Emit 이벤트 (TS) | 처리 위치 | 동작 |
|---|---|---|---|
| `open-semantic-layer` | `menu-open-semantic-layer` | App.tsx | `useSemanticStore.loadLayerFromFile()` |
| `save-semantic-layer` | `menu-save-semantic-layer` | App.tsx | `useSemanticStore.saveLayerToFile()` |
| `export-hwpx` | `menu-export-hwpx` | App.tsx | 현재 탭을 `converter`로, ConversionPanel `convert()` 호출 |
| `export-semantic-json` | `menu-export-semantic-json` | App.tsx | `useSemanticStore.saveLayerToFile()` (saveAs) |
| `export-pptx` | `menu-export-pptx` | SemanticPage 또는 store | PPTExportModal open |
| `semantic-extract` | `menu-semantic-extract` | App.tsx | `setCurrentTab("semantic")` + `loadFromAst(astDoc)` |
| `semantic-reclassify` | `menu-semantic-reclassify` | App.tsx | `setCurrentTab("semantic")` + `useSemanticStore.reclassify()` |
| `semantic-schema-library` | `menu-semantic-schema-library` | App.tsx | (TBD) 스키마 라이브러리 모달 — 현재는 SchemaEditorModal에 통합 가능 |
| `semantic-schema-edit` | `menu-semantic-schema-edit` | App.tsx | `setShowSchemaEditor(true)` |
| `semantic-schema-generate` | `menu-semantic-schema-generate` | App.tsx | `generateSchemaFromAst(astDoc)` |
| `semantic-schema-import` | `menu-semantic-schema-import` | App.tsx | (신규) 파일 다이얼로그 → `loadSchema()` |
| `semantic-schema-export` | `menu-semantic-schema-export` | App.tsx | (신규) `activeSchema` JSON 저장 |
| `semantic-rule-suggester` | `menu-semantic-rule-suggester` | App.tsx | `setShowRuleSuggester(true)` |
| `semantic-reextract-review` | `menu-semantic-reextract-review` | App.tsx | `setShowReextractReview(true)` |
| `tab-converter` | `menu-tab-converter` | App.tsx | `setCurrentTab("converter")` |
| `tab-semantic` | `menu-tab-semantic` | App.tsx | `setCurrentTab("semantic")` |
| `right-panel-ast` | `menu-right-panel-ast` | App.tsx | `setRightPanel("ast")` |
| `right-panel-pdf` | `menu-right-panel-pdf` | App.tsx | `setRightPanel("pdf")` |
| `semantic-preview-visual` | `menu-semantic-preview-visual` | SemanticPage | `setPreviewMode("visual")` |
| `semantic-preview-text` | `menu-semantic-preview-text` | SemanticPage | `setPreviewMode("text")` |

> SemanticPage 내부 상태(`previewMode`)에만 의존하는 두 항목은 SemanticPage 안에서 listen 등록한다. 나머지는 App.tsx 한 곳에서 처리.

### 3. 메뉴 항목 활성/비활성 (M3 단계)

활성 조건은 다음 컨텍스트에 따른다:

| 항목 | 활성 조건 |
|---|---|
| Extract from AST | `astDoc != null` |
| Re-classify | `nodes.length > 0 && activeSchema != null` |
| Save Semantic Layer / Export Semantic JSON / Export PPTX | `nodes.length > 0` |
| Edit Active Schema / Export Active Schema | `activeSchema != null` |
| Generate from AST | `astDoc != null` |
| Tab 전환 (⌘1/⌘2) | 항상 |
| Right Panel ▸ | `currentTab === "converter"` |
| Semantic Preview ▸ | `currentTab === "semantic" && nodes.length > 0` |
| Export HWPX | `astDoc != null` |

Tauri 2.0의 [`MenuItem::set_enabled()`](https://docs.rs/tauri/latest/tauri/menu/struct.MenuItem.html)를 사용한다. 단, 동적 동기화는 비용 대비 효과가 작으므로 **M1·M2에서는 항상 활성, 핸들러 안에서 가드만 수행**한다 (조건 미충족 시 toast/alert로 안내).

### 4. 단축키 충돌 정리

| 단축키 | 변경 전 | 변경 후 | 비고 |
|---|---|---|---|
| `⌘⇧I` | Open InDesign / Toggle DevTools (충돌) | Toggle DevTools 단독 | DevTools가 더 자주 쓰임 |
| `⌘⌥I` | (없음) | Open InDesign | 새 단축키 |
| `⌘⇧F` | Open InDesign Folder | (해제) | 검색 단축키 관용 회피 |
| `⌘⌥F` | (없음) | Open InDesign Folder | |
| `⌘1` / `⌘2` | (없음) | Tab 전환 | 브라우저 관례 |
| `⌘E` | (없음) | Export HWPX | |
| `⌘⇧E` | (없음) | Export Semantic JSON | |
| `⌘R` | (없음) | Extract from AST | refresh 관례와 어울림 |
| `⌘⇧R` | (없음) | Re-classify | |
| `⌘⌥L` | (없음) | Open Semantic Layer | |
| `⌘⌥S` | (없음) | Save Semantic Layer | |
| `⌘⇧M` | (없음) | Edit Active Schema | |
| `⌘⇧A` / `⌘⇧P` | (없음) | Right Panel 전환 | |
| `⌘⇧V` / `⌘⇧T` | (없음) | Semantic Preview 전환 | |

## 수정 파일

1. **[desktop/src-tauri/src/lib.rs](../../desktop/src-tauri/src/lib.rs)**
   - `create_menu()`: File 메뉴 항목 추가/단축키 정리, 새 `semantic_menu` Submenu 생성, View 메뉴 확장
   - `on_menu_event()`: 위 표의 ID별 핸들러 추가 (각각 `window.emit("menu-...", ())`)
2. **[desktop/src/App.tsx](../../desktop/src/App.tsx)**
   - `useEffect` 안에 새 `listen()` 등록 (위 표의 이벤트들)
   - 각 이벤트 핸들러: `setCurrentTab` / `setRightPanel` / store 함수 호출
   - cleanup 함수에서 모든 unlisten 처리
3. **[desktop/src/components/SemanticPage.tsx](../../desktop/src/components/SemanticPage.tsx)**
   - `previewMode` 토글 메뉴 이벤트 listen 등록
4. **[desktop/src/stores/useSemanticStore.ts](../../desktop/src/stores/useSemanticStore.ts)** (필요 시)
   - 스키마 import/export 함수가 없으면 추가 (단순 file dialog + JSON read/write)

## 단계별 진행 (마일스톤)

### M1 — 핵심 단축키 + 탭 전환 (즉시)

- View: `⌘1` / `⌘2` 탭 전환
- Semantic 메뉴 신설 (Extract, Re-classify, Schema > Edit/Generate)
- File: Export ▸ 서브 (HWPX, Semantic JSON, PPTX)
- 단축키 충돌 정리 (⌘⇧I 정리)

### M2 — 보조 동작

- File: Open/Save Semantic Layer (JSON 입출력)
- Schema: Library, Import/Export
- Rule Suggester / Re-extract Review 메뉴 항목
- View: Right Panel / Semantic Preview 토글

### M3 — 동적 활성/비활성 (선택)

- 프론트엔드 상태 → Rust 메뉴 enabled state 동기화
- Tauri command 추가: `set_menu_enabled(menu_id, enabled)`
- App.tsx에서 useEffect로 상태 변화 감지하고 sync

## 검증

- [ ] `cd desktop && npm run tauri build` 빌드 성공
- [ ] M1: 모든 신규 단축키가 기대대로 동작 (실측)
- [ ] M1: ⌘1/⌘2 탭 전환이 양방향으로 작동
- [ ] M1: ⌘R로 시멘틱 추출 트리거 (astDoc 있을 때)
- [ ] M1: ⌘E로 HWPX 변환 트리거 (astDoc 있을 때)
- [ ] 단축키 충돌 없음 (특히 macOS 시스템 단축키와)
- [ ] 컨텍스트 미충족 시 alert/toast 안내 (예: astDoc 없는데 ⌘R 누르면 "먼저 InDesign 파일을 여세요")
- [ ] M2: Export Semantic JSON / PPTX 메뉴 항목 동작
- [ ] M3: 메뉴 항목 enabled state가 컨텍스트 따라 변함

## 위험 요소

1. **단축키 충돌** — macOS 시스템 단축키, 브라우저 단축키, IME 단축키와 겹칠 가능성. 실제 사용해보며 조정 필요.
2. **이벤트 리스너 폭증** — App.tsx의 `useEffect` 안에 listen이 많아지면 cleanup 누락 가능성. 헬퍼 함수(`useMenuListener`)로 추출 권장.
3. **탭 전환 + 모달 동시 트리거** — 예: 시멘틱 탭 미진입 상태에서 ⌘⇧M(Edit Schema) 누르면 탭 전환 + 모달이 동시에 — 시점 문제 가능. `setCurrentTab` 후 `setTimeout(0, ...)` 또는 effect로 처리.
4. **메뉴 enabled 동기화 비용** — Rust ↔ TS 양방향 상태 sync는 의외로 까다로움. M3는 후순위로 미룸.
5. **OS 차이** — Windows/Linux는 `Ctrl+Alt+I`가 일부 키보드 레이아웃에서 입력 불가 (예: 일부 EU 레이아웃에서 AltGr 조합과 충돌). 필요 시 OS별 분기.

## 참고

- 메뉴 진입점: [desktop/src-tauri/src/lib.rs:94-220](../../desktop/src-tauri/src/lib.rs#L94)
- 이벤트 처리: [desktop/src/App.tsx:30-70](../../desktop/src/App.tsx#L30-L70)
- 시멘틱 스토어: [desktop/src/stores/useSemanticStore.ts](../../desktop/src/stores/useSemanticStore.ts)
- 시멘틱 페이지 툴바: [desktop/src/components/SemanticPage.tsx:64-148](../../desktop/src/components/SemanticPage.tsx#L64-L148)
- Tauri Menu API: https://docs.rs/tauri/latest/tauri/menu/index.html
