# SPEC-011: ExtendScript 추출 캐싱 & 증분 추출

## 문제

대용량 InDesign 문서 추출 시간이 변환 파이프라인 전체의 병목이다.

- 537개 스토리 문서 기준: 추출에만 10분 이상 (Java 변환은 4.5초)
- 동일 INDD를 디버깅 목적으로 여러 번 변환할 때마다 전 페이지 재추출
- 600초 절대 타임아웃에 자주 걸려서 직전 커밋(`d56c3eec`)에서 3600초 + 정체 600초 감지로 완화했지만 근본 해결은 아님
- 데스크탑 앱은 사용자가 같은 문서를 반복 변환하며 결과를 확인하는 워크플로우인데, 매번 추출이 완료되기를 기다려야 함

## 목표

1. 동일 INDD를 두 번째 변환할 때 추출을 0초로 단축 (캐시 적중 시)
2. INDD가 부분 수정되었을 때 변경된 부분만 재추출 (증분)
3. 디버깅 시 특정 페이지/스토리만 빠르게 재추출하는 경로 제공
4. 캐시 무효화 조건이 명확하고 사용자가 신뢰할 수 있어야 함

## 해결 방안

### 1. 캐시 키 설계

캐시 키는 INDD 자체의 콘텐츠 해시 + 의존 자산의 변경 감지를 포함한다.

```
cacheKey = sha1(
  inddPath_canonical
  + indd.mtime
  + indd.size
  + linksManifest.hash    // Links 폴더 내 파일 mtime/size 목록
  + extractScriptVersion  // extract_indd.jsx의 버전 상수
  + configHash            // conversion-config.json 변경 시 무효화
)
```

`extractScriptVersion`은 ExtendScript 파일 상단에 상수로 박아 두어 스크립트 수정 시 자동 무효화.

### 2. 캐시 위치

```
~/Library/Caches/idml-to-hwpx/extracts/<cacheKey>/
  ├─ output.idml
  ├─ resolved.json
  ├─ preview.pdf
  ├─ rendered/                 (배경 PNG, 인라인 객체 PNG)
  ├─ _meta.json                (캐시 키 원자료, 추출 시각)
  └─ _links_manifest.json      (캐시 작성 당시 Links 스냅샷)
```

기존 임시 디렉토리(`/var/folders/.../indd-extract-*`) 대신 영구 캐시 디렉토리 사용.

### 3. 캐시 조회 흐름 (Rust 측)

`extract_indd` Tauri 커맨드 진입 시:

1. `cacheKey` 계산
2. 캐시 디렉토리 존재 확인
3. `_links_manifest.json` 다시 비교 (Links만 바뀌었을 수 있음)
4. 모든 키가 일치하면 → ExtendScript 호출 없이 캐시 경로 반환
5. 일부 불일치 → 증분 모드로 진행

### 4. 증분 추출 (Phase 2)

증분은 까다로우므로 **단계 1에서는 캐시 적중/실패만** 구현하고, 증분은 후속 작업으로 분리한다.

후속 작업의 골자:
- 페이지 단위 저장: `rendered/page-{n}.png`, `stories/{storyId}.json`을 분리해서 페이지별로 갱신 가능하게 함
- ExtendScript에 `--only-pages 1,3,5` 옵션 추가
- 변경 감지: 페이지 수 동일 + Links 일부만 변경 → 영향 페이지만 재렌더

### 5. 디버깅용 페이지 선택 추출

ExtendScript는 이미 `startPage`/`endPage` arguments를 받지만 데스크탑 앱 UI에 노출되지 않음. 변환 패널에 "특정 페이지만 추출(디버깅)" 토글 + 페이지 범위 입력 추가.

이건 캐시와 별개로 즉시 적용 가능.

### 6. 캐시 무효화 UX

- 데스크탑 앱 메뉴에 "추출 캐시 비우기" 추가
- 캐시 적중 시 변환 패널 진행률에 `(캐시 사용)` 표시
- 캐시 디렉토리 크기/항목 수를 About 다이얼로그에 노출

## 수정 파일

1. `desktop/src-tauri/src/indesign.rs` — `extract_indd` 진입에 캐시 조회/저장 로직 추가, `cacheKey` 계산 함수
2. `desktop/src-tauri/src/commands/extract.rs` — 캐시 디렉토리 헬퍼, 캐시 비우기 커맨드 추가
3. `desktop/src-tauri/src/lib.rs` — `clear_extract_cache` 커맨드 등록, 메뉴 항목 추가
4. `scripts/extract_indd.jsx` — `EXTRACT_SCRIPT_VERSION` 상수 추가, Links 매니페스트 출력 추가
5. `desktop/src/components/ConversionPanel.tsx` — "캐시 사용" 표시, 페이지 범위 입력
6. `desktop/src/stores/useAppStore.ts` — 캐시 적중 이벤트 처리

## 검증

- [ ] 빌드 성공: `cd desktop && npm run tauri build`
- [ ] 동일 INDD 두 번째 변환 시 추출 0초 (캐시 적중 로그 확인)
- [ ] INDD를 수정한 후 변환 시 캐시 무효화되어 재추출
- [ ] Links 폴더 파일 하나만 교체 후 변환 시 캐시 무효화
- [ ] `extract_indd.jsx` 수정 후 변환 시 캐시 무효화
- [ ] 캐시 비우기 메뉴 동작
- [ ] 페이지 범위 추출 동작 (디버깅용)
- [ ] 537 스토리 대용량 문서 첫 변환은 정상, 두 번째는 즉시
- [ ] 회귀: 캐시 미사용 경로(첫 추출)도 기존 동작과 동일

## 후속 작업 (별도 SPEC)

- **SPEC-011a**: 증분 추출 (페이지 단위 캐시 갱신)
- **SPEC-011b**: 캐시 자동 청소 정책 (LRU, 용량 상한)

## 상태: 대기
