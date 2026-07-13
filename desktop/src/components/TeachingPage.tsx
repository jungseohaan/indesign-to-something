import { useState, useEffect } from "react";
import { invoke } from "@tauri-apps/api/core";
import { open, save, message } from "@tauri-apps/plugin-dialog";
import { listen } from "@tauri-apps/api/event";
import { useAppStore } from "../stores/useAppStore";
import type { InddExtractResult, InddExtractionProgress, ExtractStats } from "../types";

interface ProgressEvent {
  current: number;
  total: number;
  message: string;
}

interface LogEvent {
  message: string;
  timestamp: number;
}

interface SlideItem {
  index: number;
  layout: string;
  title?: string;
  subtitle?: string;
  bullets?: string[];
  items?: Record<string, string>[];
  notes?: string;
}

interface TeachingUnit {
  unitIndex: number;
  unitTitle: string;
  sourcePages: number[];
  slides: SlideItem[];
}

interface TeachingMaterial {
  source: string;
  generatedAt: string;
  model: string;
  units: TeachingUnit[];
}

interface SemanticBlock {
  block_id?: string;
  block_type?: string;
  title?: string | null;
  raw_text?: string;
  page_start?: number;
  page_end?: number;
  image_refs?: string[];
}

interface TeachingBlock {
  teaching_block_id?: string;
  title?: string | null;
  block_type?: string;
  semantic_blocks?: string[];
  page_start?: number;
  page_end?: number;
}

interface DefaultTeachingPrompt {
  path: string;
  content: string;
}

const LAYOUT_LABELS: Record<string, string> = {
  title: "제목",
  bullets: "목록",
  vocabulary: "어휘",
  qa: "문제",
  image: "이미지",
  custom: "기타",
};

const API_KEY_ERROR_PATTERNS = [
  /api\s*키\s*(없음|오류|유효하지)/i,
  /api.key.*(not found|missing|invalid)/i,
  /HTTP\s*401/i,
  /unauthorized/i,
  /authentication.*failed/i,
  /GROQ_API_KEY/,
  /ANTHROPIC_API_KEY/,
  /OPENAI_API_KEY/,
  /LLMException.*키/i,
  /invalid.*api.*key/i,
];

function isApiKeyError(msg: string): boolean {
  return API_KEY_ERROR_PATTERNS.some((p) => p.test(msg));
}

function extractApiKeyErrorDetail(msg: string): string {
  if (/HTTP\s*401/i.test(msg) || /unauthorized/i.test(msg)) {
    return "API 키가 유효하지 않습니다. .env 파일의 API 키를 확인하세요.\n\n" + msg;
  }
  if (/api\s*키\s*없음/i.test(msg) || /GROQ_API_KEY|ANTHROPIC_API_KEY|OPENAI_API_KEY/.test(msg)) {
    return "API 키를 찾을 수 없습니다.\n\n프로젝트 루트에 .env 파일을 만들고 API 키를 설정하세요.\n예: OPENAI_API_KEY=sk-...\n\n" + msg;
  }
  return "API 키 오류가 발생했습니다.\n\n" + msg;
}

export function TeachingPage() {
  const { jarPath, indesignPath } = useAppStore((s) => ({
    jarPath: s.jarPath,
    indesignPath: s.indesignPath,
  }));

  // Own INDD state (independent from converter tab)
  const [inddPath, setInddPath] = useState<string | null>(null);
  const [idmlPath, setIdmlPath] = useState<string | null>(null);
  const [resolvedJsonPath, setResolvedJsonPath] = useState<string | null>(null);
  const [linksDirectory, setLinksDirectory] = useState<string | null>(null);
  const [isExtracting, setIsExtracting] = useState(false);
  const [extractionMessage, setExtractionMessage] = useState<string | null>(null);
  const [extractError, setExtractError] = useState<string | null>(null);
  const [extractStats, setExtractStats] = useState<ExtractStats | null>(null);
  const [extractFromCache, setExtractFromCache] = useState(false);

  // Prompt config
  const [defaultPromptPath, setDefaultPromptPath] = useState("");
  const [defaultPromptContent, setDefaultPromptContent] = useState("");
  const [defaultPromptError, setDefaultPromptError] = useState<string | null>(null);
  const [defaultPromptLoading, setDefaultPromptLoading] = useState(false);

  const [extraPromptPath, setExtraPromptPath] = useState("");
  const [extraPromptContent, setExtraPromptContent] = useState("");
  const [extraPromptDirty, setExtraPromptDirty] = useState(false);

  const [outputPath, setOutputPath] = useState("");

  // Generation state
  const [isGenerating, setIsGenerating] = useState(false);
  const [progress, setProgress] = useState<ProgressEvent | null>(null);
  const [logs, setLogs] = useState<string[]>([]);
  const [material, setMaterial] = useState<TeachingMaterial | null>(null);
  const [semanticBlocks, setSemanticBlocks] = useState<SemanticBlock[] | null>(null);
  const [teachingBlocks, setTeachingBlocks] = useState<TeachingBlock[] | null>(null);
  const [htmlPath, setHtmlPath] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [expandedUnits, setExpandedUnits] = useState<Set<number>>(new Set([0]));

  // On mount: pre-populate from converter tab's already-extracted state (silent)
  useEffect(() => {
    const s = useAppStore.getState();
    if (s.inddPath && s.idmlPath && s.sourceType === "indd") {
      setInddPath(s.inddPath);
      setIdmlPath(s.idmlPath);
      setResolvedJsonPath(s.resolvedJsonPath);
      const parentDir = s.inddPath.replace(/[/\\][^/\\]+$/, "");
      setLinksDirectory(parentDir + "/Links");
      const base = s.inddPath.replace(/^.*[/\\]/, "").replace(/\.[^.]+$/, "");
      setOutputPath(`${parentDir}/${base}_teaching.json`);
    }
  }, []);

  // Load the fixed base prompt from prompts/base_prompt.txt.
  useEffect(() => {
    setDefaultPromptLoading(true);
    setDefaultPromptError(null);
    invoke<DefaultTeachingPrompt>("get_default_teaching_prompt", { jarPath: jarPath || null })
      .then((prompt) => {
        setDefaultPromptPath(prompt.path);
        setDefaultPromptContent(prompt.content);
      })
      .catch((e) => {
        setDefaultPromptPath("");
        setDefaultPromptContent("");
        setDefaultPromptError(String(e));
      })
      .finally(() => setDefaultPromptLoading(false));
  }, [jarPath]);

  // Load file content when extraPromptPath changes
  useEffect(() => {
    if (!extraPromptPath) { setExtraPromptContent(""); setExtraPromptDirty(false); return; }
    invoke<string>("read_text_file", { path: extraPromptPath })
      .then((content) => { setExtraPromptContent(content); setExtraPromptDirty(false); })
      .catch(() => {});
  }, [extraPromptPath]);

  async function saveExtraPrompt() {
    if (!extraPromptPath || !extraPromptDirty) return;
    await invoke("write_text_file", { path: extraPromptPath, content: extraPromptContent });
    setExtraPromptDirty(false);
  }

  async function askOutputPath(inddFilePath: string): Promise<string | null> {
    const base = inddFilePath.replace(/^.*[/\\]/, "").replace(/\.[^.]+$/, "");
    const dir = inddFilePath.replace(/[/\\][^/\\]+$/, "");
    return await save({
      defaultPath: `${dir}/${base}_teaching.json`,
      filters: [{ name: "JSON", extensions: ["json"] }],
    });
  }

  function applyExtraction(
    inddFilePath: string,
    idml: string,
    resolved: string | null,
    chosenOutput: string,
    stats: ExtractStats | null,
    fromCache: boolean,
  ) {
    setInddPath(inddFilePath);
    setIdmlPath(idml);
    setResolvedJsonPath(resolved);
    const parentDir = inddFilePath.replace(/[/\\][^/\\]+$/, "");
    setLinksDirectory(parentDir + "/Links");
    setOutputPath(chosenOutput);
    setExtractStats(stats);
    setExtractFromCache(fromCache);
  }

  async function openIndd() {
    const path = await open({
      filters: [{ name: "InDesign", extensions: ["indd"] }],
    });
    if (!path || typeof path !== "string") return;

    // 1순위: 변환 탭이 이미 같은 파일을 추출해 놓은 경우 → 재추출 없이 바로 사용
    const s = useAppStore.getState();
    if (s.sourceType === "indd" && s.inddPath === path && s.idmlPath) {
      setExtractError(null);
      setMaterial(null);
      setSemanticBlocks(null);
      setTeachingBlocks(null);
      setError(null);
      const parentDir = path.replace(/[/\\][^/\\]+$/, "");
      const base = path.replace(/^.*[/\\]/, "").replace(/\.[^.]+$/, "");
      applyExtraction(path, s.idmlPath, s.resolvedJsonPath, `${parentDir}/${base}_teaching.json`, s.lastExtractStats, true);
      return;
    }

    // 2순위: extract_indd 호출 (디스크 캐시 자동 활용)
    setInddPath(path);
    setIdmlPath(null);
    setResolvedJsonPath(null);
    setLinksDirectory(null);
    setIsExtracting(true);
    setExtractionMessage("준비 중...");
    setExtractError(null);
    setMaterial(null);
    setSemanticBlocks(null);
    setTeachingBlocks(null);
    setError(null);

    const unlistenProgress = await listen<InddExtractionProgress>(
      "indd-extraction-progress",
      (e) => setExtractionMessage(e.payload.message)
    );

    try {
      const extractMode = useAppStore.getState().extractMode;
      const graphicsMode = useAppStore.getState().graphicsMode;
      const result = await invoke<InddExtractResult>("extract_indd", {
        inddPath: path,
        jarPath,
        spreadMode: false,
        startPage: null,
        endPage: null,
        perfMode: "fast",
        skipPdf: true,
        extractMode,
        graphicsMode,
        chunkSize: null,
      });
      setIsExtracting(false);
      setExtractionMessage(null);
      unlistenProgress();
      const parentDir = path.replace(/[/\\][^/\\]+$/, "");
      const base = path.replace(/^.*[/\\]/, "").replace(/\.[^.]+$/, "");
      applyExtraction(path, result.idml_path, result.resolved_json_path, `${parentDir}/${base}_teaching.json`, result.extract_stats, false);
    } catch (e: any) {
      setExtractError(String(e));
      setInddPath(null);
      setIsExtracting(false);
      setExtractionMessage(null);
      unlistenProgress();
    }
  }

  async function browseExtraPrompt() {
    const selected = await open({
      filters: [{ name: "텍스트", extensions: ["txt"] }],
      multiple: false,
    });
    if (selected && typeof selected === "string") setExtraPromptPath(selected);
  }

  async function browseOutput() {
    const selected = await save({
      defaultPath: outputPath || "teaching_material.json",
      filters: [{ name: "JSON", extensions: ["json"] }],
    });
    if (selected) setOutputPath(selected);
  }

  async function generate() {
    if (!idmlPath || !jarPath || defaultPromptLoading || defaultPromptError) return;

    // 수정된 내용 먼저 파일에 저장
    if (extraPromptDirty) await saveExtraPrompt();

    // 저장 경로 확정 — 생성 직전에 물어봄
    const chosenOutput = await save({
      defaultPath: outputPath || "teaching_material.json",
      filters: [{ name: "JSON", extensions: ["json"] }],
    });
    if (!chosenOutput) return;
    setOutputPath(chosenOutput);

    setIsGenerating(true);
    setProgress(null);
    setLogs([]);
    setMaterial(null);
    setSemanticBlocks(null);
    setTeachingBlocks(null);
    setHtmlPath(null);
    setError(null);

    const unlistenProgress = await listen<ProgressEvent>("teaching-progress", (e) => {
      setProgress(e.payload);
    });
    const unlistenLog = await listen<LogEvent>("teaching-log", (e) => {
      setLogs((prev) => [...prev, e.payload.message]);
    });

    try {
      const result = await invoke<{ html_path?: string | null }>("generate_teaching", {
        inputPath: idmlPath,
        outputPath: chosenOutput,
        promptPath: null,
        extraPromptPath: extraPromptPath || null,
        jarPath,
        resolvedJsonPath: resolvedJsonPath || null,
        linksDirectory: linksDirectory || null,
        htmlTemplatePath: null,
      });
      if (result?.html_path) setHtmlPath(result.html_path);
    } catch (e: any) {
      const errMsg = String(e);
      if (isApiKeyError(errMsg)) {
        const detail = extractApiKeyErrorDetail(errMsg);
        await message(detail, { title: "API 키 오류", kind: "error" });
        setError(detail);
      } else {
        setError(`생성 실패: ${errMsg}\n\n출력 경로: ${chosenOutput}`);
      }
      return;
    }

    // 파일 읽기 — generate_teaching 성공 후 별도 처리
    try {
      const raw = await invoke<string>("read_text_file", { path: chosenOutput });
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) {
        setSemanticBlocks(parsed);
        setTeachingBlocks(null);
        setMaterial(null);
      } else if (parsed?.semantic_blocks && Array.isArray(parsed.semantic_blocks)) {
        setSemanticBlocks(parsed.semantic_blocks);
        setTeachingBlocks(Array.isArray(parsed.teaching_blocks) ? parsed.teaching_blocks : []);
        setMaterial(null);
      } else {
        setMaterial(parsed);
        setSemanticBlocks(null);
        setTeachingBlocks(null);
      }
    } catch (e: any) {
      setError(`결과 파일 읽기 실패: ${chosenOutput}\n\n${String(e)}`);
    } finally {
      setIsGenerating(false);
      setProgress(null);
      unlistenProgress();
      unlistenLog();
    }
  }

  function toggleUnit(idx: number) {
    setExpandedUnits((prev) => {
      const next = new Set(prev);
      next.has(idx) ? next.delete(idx) : next.add(idx);
      return next;
    });
  }

  const inddLoaded = !!idmlPath;
  const indesignAvailable = !!indesignPath;
  const canGenerate = inddLoaded && !!jarPath && !defaultPromptLoading && !defaultPromptError && !isGenerating && !isExtracting;
  const inddFilename = inddPath ? inddPath.replace(/^.*[/\\]/, "") : null;

  return (
    <div className="flex flex-col h-full min-h-0 bg-white">

      {/* 설정 패널 — 스크롤 가능, 최대 60% 높이 */}
      <div className="shrink-0 overflow-y-auto border-b bg-gray-50" style={{ maxHeight: "60%" }}>
        <div className="px-5 py-4 space-y-4">

          {/* INDD 파일 행 */}
          <div className="flex items-center gap-3">
            <div className="flex-1 min-w-0">
              {isExtracting ? (
                <span className="text-xs text-purple-600 animate-pulse">
                  {extractionMessage || "추출 중..."}
                </span>
              ) : inddFilename ? (
                <div className="flex items-center gap-2">
                  <span className="text-sm text-gray-700 font-medium truncate">{inddFilename}</span>
                  <span className="px-1.5 py-0.5 text-[10px] font-medium bg-purple-100 text-purple-600 rounded shrink-0">
                    InDesign
                  </span>
                  {inddLoaded && (
                    <span className="text-xs text-green-600 shrink-0">추출 완료</span>
                  )}
                </div>
              ) : (
                <span className="text-sm text-gray-400 italic">INDD 파일을 열어 시작하세요</span>
              )}
            </div>
            <button
              onClick={openIndd}
              disabled={isExtracting || isGenerating || !indesignAvailable}
              className="px-4 py-1.5 bg-purple-500 text-white text-sm rounded hover:bg-purple-600 disabled:opacity-50 disabled:cursor-not-allowed shrink-0"
            >
              {isExtracting ? "추출 중..." : "INDD 열기"}
            </button>
            {!indesignAvailable && (
              <span className="text-xs text-red-400 shrink-0">InDesign 미설치</span>
            )}
          </div>

          {extractError && (
            <div className="px-3 py-2 bg-red-50 border border-red-200 rounded text-xs text-red-700">
              {extractError}
            </div>
          )}

          {/* 추출 결과 요약 */}
          {inddLoaded && (
            <div className="px-3 py-2 bg-green-50 border border-green-200 rounded text-xs text-green-800 space-y-1">
              <div className="flex items-center gap-2 flex-wrap">
                {extractFromCache && (
                  <span className="px-1.5 py-0.5 bg-green-200 text-green-700 rounded font-medium">캐시</span>
                )}
                {extractStats ? (
                  <>
                    <span>{extractStats.page_count}페이지</span>
                    {extractStats.image_frame_count > 0 && <span>· 이미지 {extractStats.image_frame_count}개</span>}
                    {extractStats.badge_count > 0 && <span>· 배지 {extractStats.badge_count}개</span>}
                    {extractStats.shape_count > 0 && <span>· 도형 {extractStats.shape_count}개</span>}
                    {extractStats.elapsed_ms > 0 && (
                      <span className="ml-auto text-green-600">{(extractStats.elapsed_ms / 1000).toFixed(1)}초</span>
                    )}
                  </>
                ) : (
                  <span>추출 완료</span>
                )}
              </div>
              <div className="flex gap-3 text-green-700 font-mono truncate">
                <span className="truncate" title={idmlPath ?? ""}>IDML: {idmlPath?.replace(/^.*[/\\]/, "")}</span>
                {resolvedJsonPath && <span className="shrink-0">resolved.json ✓</span>}
              </div>
            </div>
          )}

          {/* 프롬프트 설정 — INDD 로드 후 활성화 */}
          <div className={`space-y-3 ${!inddLoaded ? "opacity-40 pointer-events-none" : ""}`}>

            {/* 기본 프롬프트 */}
            <div className="space-y-1.5">
              <div className="flex items-center gap-2">
                <span className="text-gray-600 text-xs font-medium w-20 shrink-0">기본 프롬프트</span>
                <div className="flex-1 text-xs border rounded px-2 py-1.5 font-mono bg-gray-100 text-gray-600 truncate">
                  {defaultPromptLoading ? "prompts/base_prompt.txt 로드 중..." : defaultPromptPath || "prompts/base_prompt.txt"}
                </div>
                {defaultPromptError && (
                  <span className="text-xs text-red-500 shrink-0">로드 실패</span>
                )}
              </div>
              {defaultPromptError ? (
                <div className="px-3 py-2 bg-red-50 border border-red-200 rounded text-xs text-red-700">
                  {defaultPromptError}
                </div>
              ) : defaultPromptContent && (
                <textarea
                  value={defaultPromptContent}
                  readOnly
                  spellCheck={false}
                  className="w-full text-xs font-mono border rounded px-3 py-2 bg-gray-50 resize-y leading-relaxed text-gray-600"
                  style={{ minHeight: "140px", maxHeight: "320px" }}
                  placeholder="기본 프롬프트 내용이 여기에 표시됩니다..."
                />
              )}
            </div>

            {/* 추가 프롬프트 (선택) */}
            <div className="space-y-1.5">
              <div className="flex items-center gap-2">
                <span className="text-gray-600 text-xs font-medium w-20 shrink-0">추가 프롬프트</span>
                <input
                  type="text"
                  value={extraPromptPath}
                  onChange={(e) => setExtraPromptPath(e.target.value)}
                  placeholder="교과서별 추가 지침 (선택)"
                  className="flex-1 text-xs border rounded px-2 py-1.5 font-mono text-gray-500 bg-white"
                />
                <button
                  onClick={browseExtraPrompt}
                  className="px-2 py-1.5 text-xs border rounded hover:bg-gray-100 bg-white shrink-0"
                >
                  찾아보기
                </button>
                {extraPromptDirty && (
                  <button
                    onClick={saveExtraPrompt}
                    className="px-2 py-1.5 text-xs rounded bg-amber-500 text-white hover:bg-amber-600 shrink-0"
                  >
                    저장
                  </button>
                )}
              </div>
              {extraPromptPath && (
                <textarea
                  value={extraPromptContent}
                  onChange={(e) => { setExtraPromptContent(e.target.value); setExtraPromptDirty(true); }}
                  onBlur={saveExtraPrompt}
                  spellCheck={false}
                  className="w-full text-xs font-mono border rounded px-3 py-2 bg-white resize-y leading-relaxed"
                  style={{ minHeight: "80px", maxHeight: "200px" }}
                  placeholder="추가 프롬프트 내용이 여기에 표시됩니다..."
                />
              )}
            </div>

            {/* 출력 경로 + 생성 버튼 */}
            <div className="flex items-center gap-2">
              <span className="text-gray-600 text-xs font-medium w-20 shrink-0">출력 파일</span>
              <input
                type="text"
                value={outputPath}
                onChange={(e) => setOutputPath(e.target.value)}
                placeholder="teaching_material.json"
                className="flex-1 text-xs border rounded px-2 py-1.5 font-mono bg-white"
              />
              <button
                onClick={browseOutput}
                className="px-2 py-1.5 text-xs border rounded hover:bg-gray-100 bg-white shrink-0"
              >
                찾아보기
              </button>
              <button
                onClick={generate}
                disabled={!canGenerate}
                className="px-4 py-1.5 text-sm bg-blue-600 text-white rounded hover:bg-blue-700 disabled:opacity-40 disabled:cursor-not-allowed shrink-0"
              >
                {isGenerating ? "생성 중..." : "Semantic Block 생성"}
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* 진행 상황 */}
      {isGenerating && (
        <div className="shrink-0 px-5 py-3 border-b bg-blue-50">
          {progress && (
            <div className="mb-2">
              <div className="flex justify-between text-xs text-blue-700 mb-1">
                <span>{progress.message}</span>
                <span>{progress.current}/{progress.total}</span>
              </div>
              <div className="w-full bg-blue-200 rounded-full h-1.5">
                <div
                  className="bg-blue-600 h-1.5 rounded-full transition-all"
                  style={{ width: `${Math.round((progress.current / Math.max(progress.total, 1)) * 100)}%` }}
                />
              </div>
            </div>
          )}
          <div className="max-h-24 overflow-y-auto text-xs text-gray-600 font-mono space-y-0.5">
            {logs.slice(-8).map((log, i) => (
              <div key={i} className="truncate">{log}</div>
            ))}
          </div>
        </div>
      )}

      {/* 에러 */}
      {error && (
        <div className="shrink-0 mx-5 mt-3 px-3 py-2 bg-red-50 border border-red-200 rounded text-sm text-red-700">
          {error}
        </div>
      )}

      {/* 결과 */}
      <div className="flex-1 overflow-y-auto px-5 py-4">
        {semanticBlocks ? (
          <div>
            <div className="mb-4 flex items-center gap-3 text-xs text-gray-500">
              <span>{semanticBlocks.length}개 Semantic Block</span>
              {teachingBlocks && (
                <>
                  <span>·</span>
                  <span>{teachingBlocks.length}개 Teaching Block</span>
                </>
              )}
              <div className="ml-auto flex items-center gap-2">
                <button
                  onClick={() => invoke("open_file", { path: outputPath }).catch(() => {})}
                  className="px-2 py-0.5 border rounded text-xs hover:bg-gray-100"
                >
                  JSON 파일 열기
                </button>
              </div>
            </div>

            {teachingBlocks && teachingBlocks.length > 0 && (
              <div className="mb-5">
                <div className="mb-2 text-xs font-medium text-gray-500">Teaching Blocks</div>
                <div className="space-y-2">
                  {teachingBlocks.map((block, idx) => (
                    <div key={block.teaching_block_id ?? idx} className="border rounded-lg px-4 py-3 bg-blue-50/40">
                      <div className="flex items-start gap-2">
                        <span className="text-xs font-mono text-blue-700 bg-blue-100 px-1.5 py-0.5 rounded shrink-0">
                          {block.teaching_block_id ?? `TB-${String(idx + 1).padStart(3, "0")}`}
                        </span>
                        {block.block_type && (
                          <span className="text-xs px-1.5 py-0.5 rounded bg-white text-blue-700 border border-blue-100 shrink-0">
                            {block.block_type}
                          </span>
                        )}
                        {(block.page_start || block.page_end) && (
                          <span className="text-xs text-gray-400 shrink-0">
                            p.{block.page_start ?? block.page_end}
                            {block.page_end && block.page_end !== block.page_start ? `-${block.page_end}` : ""}
                          </span>
                        )}
                        <div className="flex-1 min-w-0">
                          <div className="text-sm font-medium text-gray-800 truncate">{block.title}</div>
                          {block.semantic_blocks && block.semantic_blocks.length > 0 && (
                            <div className="mt-1 text-xs text-gray-500 font-mono truncate">
                              {block.semantic_blocks.join(", ")}
                            </div>
                          )}
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}

            <div className="mb-2 text-xs font-medium text-gray-500">Raw Semantic Blocks</div>
            <div className="space-y-2">
              {semanticBlocks.map((block, idx) => (
                <div key={block.block_id ?? idx} className="border rounded-lg px-4 py-3">
                  <div className="flex items-start gap-2">
                    <span className="text-xs font-mono text-gray-500 bg-gray-100 px-1.5 py-0.5 rounded shrink-0">
                      {block.block_id ?? `SB-${String(idx + 1).padStart(3, "0")}`}
                    </span>
                    {block.block_type && (
                      <span className="text-xs px-1.5 py-0.5 rounded bg-blue-100 text-blue-700 shrink-0">
                        {block.block_type}
                      </span>
                    )}
                    {(block.page_start || block.page_end) && (
                      <span className="text-xs text-gray-400 shrink-0">
                        p.{block.page_start ?? block.page_end}
                        {block.page_end && block.page_end !== block.page_start ? `-${block.page_end}` : ""}
                      </span>
                    )}
                    <div className="flex-1 min-w-0">
                      {block.title && (
                        <div className="text-sm font-medium text-gray-800 truncate">{block.title}</div>
                      )}
                      <div className="mt-1 text-xs text-gray-600 whitespace-pre-wrap line-clamp-4">
                        {block.raw_text}
                      </div>
                      {block.image_refs && block.image_refs.length > 0 && (
                        <div className="mt-2 text-xs text-gray-400 font-mono truncate">
                          images: {block.image_refs.join(", ")}
                        </div>
                      )}
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </div>
        ) : material ? (
          <div>
            <div className="mb-4 flex items-center gap-3 text-xs text-gray-500">
              <span>모델: <span className="font-mono">{material.model}</span></span>
              <span>·</span>
              <span>{material.units.length}개 단원</span>
              <span>·</span>
              <span>{material.units.reduce((s, u) => s + u.slides.length, 0)}개 슬라이드</span>
              <div className="ml-auto flex items-center gap-2">
                {htmlPath && (
                  <button
                    onClick={() => invoke("open_file", { path: htmlPath }).catch(() => {})}
                    className="px-3 py-1 bg-indigo-600 text-white rounded text-xs hover:bg-indigo-700"
                  >
                    슬라이드 열기
                  </button>
                )}
                <button
                  onClick={() => invoke("open_file", { path: outputPath }).catch(() => {})}
                  className="px-2 py-0.5 border rounded text-xs hover:bg-gray-100"
                >
                  JSON 파일 열기
                </button>
              </div>
            </div>

            <div className="space-y-3">
              {material.units.map((unit, uidx) => (
                <div key={uidx} className="border rounded-lg overflow-hidden">
                  <button
                    onClick={() => toggleUnit(uidx)}
                    className="w-full flex items-center justify-between px-4 py-2.5 bg-gray-100 hover:bg-gray-200 text-left"
                  >
                    <div className="flex items-center gap-2">
                      <span className="text-xs font-mono text-gray-500 bg-gray-200 px-1.5 py-0.5 rounded">
                        {unit.unitIndex}단원
                      </span>
                      <span className="text-sm font-medium text-gray-800">{unit.unitTitle}</span>
                      <span className="text-xs text-gray-500">p.{unit.sourcePages.join(", ")}</span>
                    </div>
                    <div className="flex items-center gap-2">
                      <span className="text-xs text-gray-500">{unit.slides.length}슬라이드</span>
                      <span className="text-gray-400">{expandedUnits.has(uidx) ? "▲" : "▼"}</span>
                    </div>
                  </button>

                  {expandedUnits.has(uidx) && (
                    <div className="divide-y">
                      {unit.slides.map((slide, sidx) => (
                        <div key={sidx} className="px-4 py-2.5">
                          <div className="flex items-start gap-2">
                            <span className="mt-0.5 text-xs font-mono text-gray-400 w-5 shrink-0">
                              {slide.index}
                            </span>
                            <span className="mt-0.5 text-xs px-1.5 py-0.5 rounded bg-blue-100 text-blue-700 shrink-0">
                              {LAYOUT_LABELS[slide.layout] ?? slide.layout}
                            </span>
                            <div className="flex-1 min-w-0">
                              <div className="text-sm font-medium text-gray-800 truncate">{slide.title}</div>
                              {slide.subtitle && (
                                <div className="text-xs text-gray-500 truncate">{slide.subtitle}</div>
                              )}
                              {slide.bullets && (
                                <ul className="mt-1 space-y-0.5">
                                  {slide.bullets.slice(0, 3).map((b, bi) => (
                                    <li key={bi} className="text-xs text-gray-600 truncate">· {b}</li>
                                  ))}
                                  {slide.bullets.length > 3 && (
                                    <li className="text-xs text-gray-400">+{slide.bullets.length - 3}개 더...</li>
                                  )}
                                </ul>
                              )}
                              {slide.items && (
                                <div className="mt-1 text-xs text-gray-500">항목 {slide.items.length}개</div>
                              )}
                              {slide.notes && (
                                <div className="mt-1 text-xs text-amber-600 truncate">📝 {slide.notes}</div>
                              )}
                            </div>
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>
        ) : !isGenerating ? (
          <div className="flex flex-col items-center justify-center h-full text-gray-400 text-sm gap-2">
            <span className="text-4xl">📚</span>
            <p>INDD 파일을 열고 프롬프트를 선택하세요</p>
            <p className="text-xs">InDesign AST를 Semantic Block JSON 배열로 추출합니다</p>
          </div>
        ) : null}
      </div>
    </div>
  );
}
