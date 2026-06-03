import { create } from "zustand";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { open, save } from "@tauri-apps/plugin-dialog";
import type {
  IDMLStructure,
  SpreadInfo,
  PageInfo,
  FrameInfo,
  ImagePreview,
  ConvertResult,
  ProgressEvent,
  LogEvent,
  TextFrameDetail,
  MasterSpreadInfo,
  InddExtractResult,
  InddExtractionProgress,
  InddFolderScanResult,
  BatchFileResult,
  ExtractStats,
} from "../types";
import { useAstStore } from "./useAstStore";

interface AppState {
  // JAR
  jarPath: string | null;

  // File
  idmlPath: string | null;
  isAnalyzing: boolean;
  structure: IDMLStructure | null;

  // INDD Extraction
  sourceType: "idml" | "indd" | null;
  inddPath: string | null;
  resolvedJsonPath: string | null;
  previewPdfPath: string | null;
  isExtracting: boolean;
  extractionPhase: string | null;
  extractionMessage: string | null;

  // Selection
  selectedSpread: SpreadInfo | null;
  selectedPage: PageInfo | null;
  selectedImage: FrameInfo | null;
  selectedTextFrame: FrameInfo | null;
  selectedMaster: MasterSpreadInfo | null;

  // Preview
  previewImages: ImagePreview[];
  isGeneratingPreview: boolean;
  textFrameDetail: TextFrameDetail | null;
  isLoadingTextDetail: boolean;
  masterPreview: ImagePreview | null;
  isGeneratingMasterPreview: boolean;

  // Conversion
  isConverting: boolean;
  progress: ProgressEvent | null;
  result: ConvertResult | null;
  error: string | null;
  conversionLogs: LogEvent[];
  spreadBased: boolean;
  vectorDpi: 96 | 150;
  layoutMode: "preserve" | "editable";
  // SPEC-030: InDesign 추출 성능 모드. fast=150dpi+pdf skip, standard=220dpi+pdf, high=300dpi+pdf
  perfMode: "fast" | "standard" | "high";
  // SPEC-011: 디버그용 페이지 범위 추출 (0=전체)
  debugStartPage: number;
  debugEndPage: number;
  noPreview: boolean;
  // InDesign
  indesignPath: string | null;

  // Font Mapping
  fontMappings: Record<string, string>;
  showFontMappingModal: boolean;

  // Remembered paths
  lastOpenDir: string | null;
  lastExportDir: string | null;

  // Batch Processing
  showBatchModal: boolean;
  batchScanResult: InddFolderScanResult | null;
  selectedFolderPaths: string[];          // 멀티 폴더 선택 목록
  batchQueue: string[];
  batchBasePath: string | null;
  batchOutputDir: string | null;
  batchCurrentIndex: number;
  batchResults: BatchFileResult[];
  isBatchProcessing: boolean;
  batchCancelled: boolean;
  batchCurrentPhaseMessage: string | null; // 현재 phase 진행 메시지 (heartbeat 포함)

  // Extract Stats
  lastExtractStats: ExtractStats | null;

  // 분할 추출: 0 = 단일 세션, N = N페이지 단위 청크
  extractChunkSize: number;

  // Actions
  initJarPath: () => Promise<void>;
  selectFile: () => Promise<void>;
  selectInddFile: () => Promise<void>;
  selectHwpxFile: () => Promise<void>;
  selectSpread: (spread: SpreadInfo) => void;
  selectPage: (page: PageInfo) => void;
  selectFrame: (frame: FrameInfo) => void;
  selectMaster: (master: MasterSpreadInfo) => void;
  clearSelection: () => void;
  startConversion: () => Promise<void>;
  setSpreadBased: (v: boolean) => void;
  setVectorDpi: (v: 96 | 150) => void;
  setLayoutMode: (v: "preserve" | "editable") => void;
  setPerfMode: (v: "fast" | "standard" | "high") => void;
  setNoPreview: (v: boolean) => void;
  setDebugPageRange: (start: number, end: number) => void;
  setExtractChunkSize: (v: number) => void;
  clearError: () => void;
  setFontMappings: (mappings: Record<string, string>) => void;
  openFontMappingModal: () => void;
  closeFontMappingModal: () => void;
  selectInddFolder: () => Promise<void>;
  addFolders: () => Promise<void>;
  removeFolderFromBatch: (folderPath: string) => void;
  startBatch: (selectedPaths: string[]) => Promise<void>;
  closeBatchModal: () => void;
  cancelBatch: () => void;
  // 시멘틱 레이어: INDD → 추출 → AST 로드만
  extractForSemantic: () => Promise<void>;
  isExtractingForSemantic: boolean;
  extractSemanticError: string | null;
}

export const useAppStore = create<AppState>((set, get) => ({
  jarPath: null,
  idmlPath: null,
  isAnalyzing: false,
  structure: null,
  sourceType: null,
  inddPath: null,
  resolvedJsonPath: null,
  previewPdfPath: null,
  isExtracting: false,
  extractionPhase: null,
  extractionMessage: null,
  selectedSpread: null,
  selectedPage: null,
  selectedImage: null,
  selectedTextFrame: null,
  selectedMaster: null,
  previewImages: [],
  isGeneratingPreview: false,
  textFrameDetail: null,
  isLoadingTextDetail: false,
  masterPreview: null,
  isGeneratingMasterPreview: false,
  isConverting: false,
  progress: null,
  result: null,
  error: null,
  conversionLogs: [],
  spreadBased: false,
  vectorDpi: 150,
  layoutMode: "preserve",
  perfMode: (localStorage.getItem("perfMode") as "fast" | "standard" | "high") || "standard",
  noPreview: localStorage.getItem("noPreview") === "true",
  debugStartPage: 0,
  debugEndPage: 0,
  indesignPath: null,
  fontMappings: {},
  showFontMappingModal: false,
  lastOpenDir: localStorage.getItem("lastOpenDir"),
  lastExportDir: localStorage.getItem("lastExportDir"),
  showBatchModal: false,
  batchScanResult: null,
  selectedFolderPaths: [],
  batchQueue: [],
  batchBasePath: null,
  batchOutputDir: null,
  batchCurrentIndex: -1,
  batchResults: [],
  isBatchProcessing: false,
  batchCancelled: false,
  batchCurrentPhaseMessage: null,
  lastExtractStats: null,
  extractChunkSize: parseInt(localStorage.getItem("extractChunkSize") || "0", 10) || 0,
  isExtractingForSemantic: false,
  extractSemanticError: null,

  initJarPath: async () => {
    try {
      const jarPath = await invoke<string>("get_jar_path");
      set({ jarPath });
    } catch (e) {
      console.error("Failed to get JAR path:", e);
    }
    try {
      const indesignPath = await invoke<string>("check_indesign");
      set({ indesignPath });
    } catch {
      set({ indesignPath: null });
    }
  },

  selectFile: async () => {
    const path = await open({
      filters: [{ name: "IDML", extensions: ["idml"] }],
    });
    if (!path) return;
    set({
      idmlPath: path,
      sourceType: "idml",
      inddPath: null,
      resolvedJsonPath: null,
      isExtracting: false,
      extractionPhase: null,
      isAnalyzing: true,
      structure: null,
      selectedSpread: null,
      selectedPage: null,
      selectedImage: null,
      selectedTextFrame: null,
      selectedMaster: null,
      previewImages: [],
      textFrameDetail: null,
      masterPreview: null,
      result: null,
      error: null,
    });

    try {
      const structure = await invoke<IDMLStructure>("analyze_idml", {
        path,
        jarPath: get().jarPath,
      });
      set({ structure, isAnalyzing: false });

      // AST 자동 로드
      const jarPath = get().jarPath;
      if (jarPath) {
        useAstStore.getState().loadAST(path, jarPath);
      }
      // IDML 직접 열기: resolved 사이드카 없음
      useAstStore.getState().clearResolved();
    } catch (e: any) {
      set({ isAnalyzing: false, error: String(e) });
    }
  },

  selectInddFile: async () => {
    const { lastOpenDir } = get();
    const path = await open({
      filters: [{ name: "InDesign", extensions: ["indd"] }],
      defaultPath: lastOpenDir ?? undefined,
    });
    if (!path) return;

    const parentDir = path.substring(0, path.lastIndexOf("/"));
    localStorage.setItem("lastOpenDir", parentDir);
    set({ lastOpenDir: parentDir });

    // 배치 흐름으로 자동 처리 (단일 파일도 배치 모달 사용)
    set({
      showBatchModal: true,
      batchScanResult: {
        direct_files: [path],
        subfolder_files: [],
      },
      batchBasePath: parentDir,
      batchResults: [],
      batchCurrentIndex: -1,
      isBatchProcessing: false,
      batchCancelled: false,
    });
  },

  selectHwpxFile: async () => {
    const file = await open({
      filters: [{ name: "HWPX", extensions: ["hwpx"] }],
    });
    if (!file) return;
    // HWPX file handling placeholder
  },

  selectSpread: (spread) => {
    set({
      selectedSpread: spread,
      selectedPage: null,
      selectedImage: null,
      selectedTextFrame: null,
      selectedMaster: null,
    });
  },

  selectPage: (page) => {
    set({
      selectedPage: page,
      selectedSpread: null,
      selectedImage: null,
      selectedTextFrame: null,
      selectedMaster: null,
    });
  },

  selectFrame: (frame) => {
    if (frame.type === "text") {
      set({
        selectedTextFrame: frame,
        selectedImage: null,
        selectedSpread: null,
        selectedPage: null,
        selectedMaster: null,
        isLoadingTextDetail: true,
        textFrameDetail: null,
      });
      // Load text frame detail
      invoke<TextFrameDetail>("get_text_frame_detail", {
        idmlPath: get().idmlPath,
        frameId: frame.id,
        jarPath: get().jarPath,
      })
        .then((detail) => set({ textFrameDetail: detail, isLoadingTextDetail: false }))
        .catch(() => set({ isLoadingTextDetail: false }));
    } else if (frame.type === "image" || frame.type === "vector") {
      set({
        selectedImage: frame,
        selectedTextFrame: null,
        selectedSpread: null,
        selectedPage: null,
        selectedMaster: null,
        isGeneratingPreview: true,
      });
      const cmd =
        frame.type === "image"
          ? "generate_image_preview"
          : "generate_vector_preview";
      invoke<ImagePreview>(cmd, {
        idmlPath: get().idmlPath,
        frameId: frame.id,
        jarPath: get().jarPath,
      })
        .then((preview) => {
          preview.original_path = `${frame.type}:${frame.id}`;
          set((s) => ({
            previewImages: [...s.previewImages, preview],
            isGeneratingPreview: false,
          }));
        })
        .catch(() => set({ isGeneratingPreview: false }));
    } else if (frame.type === "group") {
      // Group 선택: selectedImage에 group frame 저장 (preview panel에서 처리)
      set({
        selectedImage: frame,
        selectedTextFrame: null,
        selectedSpread: null,
        selectedPage: null,
        selectedMaster: null,
        isGeneratingPreview: false,
      });
    }
  },

  selectMaster: (master) => {
    set({
      selectedMaster: master,
      selectedSpread: null,
      selectedPage: null,
      selectedImage: null,
      selectedTextFrame: null,
      isGeneratingMasterPreview: true,
      masterPreview: null,
    });
    invoke<ImagePreview>("generate_master_preview", {
      idmlPath: get().idmlPath,
      masterId: master.id,
      jarPath: get().jarPath,
    })
      .then((preview) =>
        set({ masterPreview: preview, isGeneratingMasterPreview: false })
      )
      .catch(() => set({ isGeneratingMasterPreview: false }));
  },

  clearSelection: () => {
    set({
      selectedSpread: null,
      selectedPage: null,
      selectedImage: null,
      selectedTextFrame: null,
      selectedMaster: null,
      textFrameDetail: null,
      masterPreview: null,
    });
  },

  startConversion: async () => {
    const { idmlPath, jarPath, spreadBased, vectorDpi, layoutMode, inddPath, resolvedJsonPath, fontMappings } = get();
    if (!idmlPath) return;

    // 기본 파일명: 원본 파일명에서 확장자를 .hwpx로 변경
    const sourcePath = inddPath || idmlPath;
    const defaultName = sourcePath
      ? sourcePath.replace(/^.*[/\\]/, "").replace(/\.[^.]+$/, ".hwpx")
      : undefined;

    const outputPath = await save({
      defaultPath: defaultName,
      filters: [{ name: "HWPX", extensions: ["hwpx"] }],
    });
    if (!outputPath) return;

    set({ isConverting: true, progress: null, result: null, error: null, conversionLogs: [] });

    const unlisten = await listen<ProgressEvent>(
      "conversion-progress",
      (event) => {
        set({ progress: event.payload });
      }
    );

    const unlistenLog = await listen<LogEvent>(
      "conversion-log",
      (event) => {
        set((state) => ({ conversionLogs: [...state.conversionLogs, event.payload] }));
      }
    );

    try {
      // INDD에서 추출한 경우 원본 옆 Links 폴더 경로를 전달
      const linksDir = inddPath ? inddPath.replace(/[/\\][^/\\]+$/, "") + "/Links" : null;

      const result = await invoke<ConvertResult>("convert_idml", {
        inputPath: idmlPath,
        outputPath,
        options: {
          spread_based: spreadBased,
          vector_dpi: vectorDpi,
          include_images: true,
          resolved_json_path: resolvedJsonPath,
          layout_mode: layoutMode,
          font_map: Object.keys(fontMappings).length > 0 ? fontMappings : null,
          links_directory: linksDir,
        },
        jarPath,
      });
      set({ result, isConverting: false });

      // 변환 완료 후: preview PDF를 HWPX와 같은 폴더에 복사
      const { previewPdfPath } = get();
      const outputDir = outputPath.replace(/[/\\][^/\\]+$/, "");
      const outputBaseName = outputPath.replace(/^.*[/\\]/, "").replace(/\.[^.]+$/, "");
      let copiedPdfPath: string | null = null;

      if (previewPdfPath) {
        try {
          copiedPdfPath = `${outputDir}/${outputBaseName}.pdf`;
          await invoke("copy_file", { src: previewPdfPath, dst: copiedPdfPath });
        } catch {
          copiedPdfPath = null;
        }
      }

      // HWPX / PDF 파일 열기 (noPreview 모드 시 건너뜀)
      if (!get().noPreview) {
        try {
          await invoke("open_file", { path: outputPath });
        } catch {
          // 열기 실패해도 변환 자체는 성공
        }
        if (copiedPdfPath) {
          try {
            await invoke("open_file", { path: copiedPdfPath });
          } catch {}
        }
      }
    } catch (e: any) {
      set({ error: String(e), isConverting: false });
    } finally {
      unlisten();
      unlistenLog();
    }
  },

  setSpreadBased: (v) => set({ spreadBased: v }),
  setVectorDpi: (v) => set({ vectorDpi: v }),
  setLayoutMode: (v) => set({ layoutMode: v }),
  setPerfMode: (v) => {
    localStorage.setItem("perfMode", v);
    set({ perfMode: v });
  },
  setNoPreview: (v) => {
    localStorage.setItem("noPreview", String(v));
    set({ noPreview: v });
  },
  setDebugPageRange: (start, end) => set({ debugStartPage: start, debugEndPage: end }),
  setExtractChunkSize: (v) => {
    localStorage.setItem("extractChunkSize", String(v));
    set({ extractChunkSize: v });
  },
  clearError: () => set({ error: null }),
  setFontMappings: (mappings) => set({ fontMappings: mappings }),
  openFontMappingModal: () => set({ showFontMappingModal: true }),
  closeFontMappingModal: () => set({ showFontMappingModal: false }),

  selectInddFolder: async () => {
    const { lastOpenDir } = get();
    const selected = await open({
      directory: true,
      multiple: true,
      title: "InDesign 폴더 선택",
      defaultPath: lastOpenDir ?? undefined,
    });
    if (!selected) return;
    const folders = Array.isArray(selected) ? selected : [selected as string];
    if (folders.length === 0) return;

    localStorage.setItem("lastOpenDir", folders[0]);
    set({ lastOpenDir: folders[0] });

    await _mergeFoldersIntoBatch(folders, true, get, set);
  },

  addFolders: async () => {
    const { lastOpenDir } = get();
    const selected = await open({
      directory: true,
      multiple: true,
      title: "폴더 추가",
      defaultPath: lastOpenDir ?? undefined,
    });
    if (!selected) return;
    const folders = Array.isArray(selected) ? selected : [selected as string];
    if (folders.length === 0) return;

    await _mergeFoldersIntoBatch(folders, false, get, set);
  },

  removeFolderFromBatch: (folderPath: string) => {
    set((s) => {
      const newFolderPaths = s.selectedFolderPaths.filter((p) => p !== folderPath);
      // 해당 폴더 소속 파일 제거 후 ScanResult 재구성
      const prev = s.batchScanResult;
      if (!prev) return { selectedFolderPaths: newFolderPaths };
      const newDirect = prev.direct_files.filter(
        (p) => !p.startsWith(folderPath + "/") && !p.startsWith(folderPath + "\\")
      );
      const newSubfolders = prev.subfolder_files.filter(
        (sf) => sf.folder_path !== folderPath && !sf.folder_path.startsWith(folderPath + "/") && !sf.folder_path.startsWith(folderPath + "\\")
      );
      return {
        selectedFolderPaths: newFolderPaths,
        batchScanResult: { direct_files: newDirect, subfolder_files: newSubfolders },
      };
    });
  },

  startBatch: async (selectedPaths: string[]) => {
    if (selectedPaths.length === 0) return;

    // 출력 폴더 선택
    const { lastExportDir } = get();
    const outputDir = await open({
      directory: true,
      title: "내보내기 폴더 선택",
      defaultPath: lastExportDir ?? undefined,
    });
    if (!outputDir) return;

    localStorage.setItem("lastExportDir", outputDir as string);
    set({ lastExportDir: outputDir as string });

    const { jarPath, batchBasePath, spreadBased, vectorDpi, layoutMode, fontMappings } = get();
    if (!jarPath) {
      set({ error: "JAR 파일을 찾을 수 없습니다." });
      return;
    }

    const results: BatchFileResult[] = selectedPaths.map((p) => ({
      path: p,
      filename: p.replace(/^.*[/\\]/, ""),
      status: "pending" as const,
    }));

    set({
      showBatchModal: true,
      batchQueue: selectedPaths,
      batchOutputDir: outputDir,
      batchCurrentIndex: 0,
      batchResults: results,
      isBatchProcessing: true,
      batchCancelled: false,
    });

    for (let i = 0; i < selectedPaths.length; i++) {
      // 중단 확인
      if (get().batchCancelled) break;

      // 배치 파일 간 딜레이 (InDesign 안정화)
      if (i > 0) {
        await new Promise((r) => setTimeout(r, 3000));
      }

      const inddPath = selectedPaths[i];
      set({ batchCurrentIndex: i });

      // 상태 업데이트: extracting + 시작 시각 기록
      const startedAt = Date.now();
      set((s) => ({
        batchResults: s.batchResults.map((r, idx) =>
          idx === i ? { ...r, status: "extracting" as const, startedAt, extractStartedAt: startedAt } : r
        ),
      }));

      // 추출 진행 이벤트: 캐시 적중 감지 + phase 메시지 저장
      const unlistenExtractProgress = await listen<InddExtractionProgress>(
        "indd-extraction-progress",
        (event) => {
          if (event.payload.phase === "cached") {
            const idx = get().batchCurrentIndex;
            set((s) => ({
              batchResults: s.batchResults.map((r, j) =>
                j === idx ? { ...r, cached: true } : r
              ),
            }));
          }
          set({ batchCurrentPhaseMessage: event.payload.message });
        }
      );

      try {
        const { debugStartPage, debugEndPage, perfMode, extractChunkSize } = get();
        // 1. InDesign으로 추출 (디버그 페이지 범위가 있으면 일부만)
        const extractResult = await invoke<InddExtractResult>("extract_indd", {
          inddPath,
          jarPath,
          spreadMode: spreadBased,
          startPage: debugStartPage,
          endPage: debugEndPage,
          perfMode,
          chunkSize: extractChunkSize > 0 ? extractChunkSize : null,
        });

        if (get().batchCancelled) break;

        if (extractResult.extract_stats) {
          set({ lastExtractStats: extractResult.extract_stats });
        }

        // 상태 업데이트: converting + 변환 시작 시각
        const convertStartedAt = Date.now();
        set((s) => ({
          batchResults: s.batchResults.map((r, idx) =>
            idx === i ? { ...r, status: "converting" as const, convertStartedAt } : r
          ),
        }));

        // 2. 출력 경로 계산 (원본 폴더 구조 유지)
        let relativeSubdir = "";
        if (batchBasePath) {
          const inddDir = inddPath.replace(/[/\\][^/\\]+$/, "");
          if (inddDir.startsWith(batchBasePath)) {
            relativeSubdir = inddDir.substring(batchBasePath.length).replace(/^[/\\]/, "");
          }
        }

        const outputSubdir = relativeSubdir
          ? `${outputDir}/${relativeSubdir}`
          : outputDir;

        // 디렉토리 생성
        await invoke("create_dir", { path: outputSubdir });

        const inddFilename = inddPath.replace(/^.*[/\\]/, "").replace(/\.[^.]+$/, "");
        const hwpxOutputPath = `${outputSubdir}/${inddFilename}.hwpx`;

        // 3. HWPX 변환
        const batchLinksDir = inddPath.replace(/[/\\][^/\\]+$/, "") + "/Links";
        await invoke<ConvertResult>("convert_idml", {
          inputPath: extractResult.idml_path,
          outputPath: hwpxOutputPath,
          options: {
            spread_based: spreadBased,
            vector_dpi: vectorDpi,
            include_images: true,
            resolved_json_path: extractResult.resolved_json_path,
            layout_mode: layoutMode,
            font_map: Object.keys(fontMappings).length > 0 ? fontMappings : null,
            links_directory: batchLinksDir,
          },
          jarPath,
        });

        // 4. preview PDF 복사
        let pdfOutputPath: string | null = null;
        if (extractResult.preview_pdf_path) {
          try {
            pdfOutputPath = `${outputSubdir}/${inddFilename}.pdf`;
            await invoke("copy_file", { src: extractResult.preview_pdf_path, dst: pdfOutputPath });
          } catch {
            pdfOutputPath = null;
          }
        }

        // 성공 + 완료 시각
        set((s) => ({
          batchResults: s.batchResults.map((r, idx) =>
            idx === i ? { ...r, status: "done" as const, completedAt: Date.now() } : r
          ),
          batchCurrentPhaseMessage: null,
        }));

        // 변환된 파일을 즉시 열기 (noPreview 모드 시 건너뜀)
        if (!get().noPreview) {
          try { await invoke("open_file", { path: hwpxOutputPath }); } catch {}
          if (pdfOutputPath) {
            try { await invoke("open_file", { path: pdfOutputPath }); } catch {}
          }
        }

        // 단일 파일일 때 AST 자동 로드 (시멘틱 레이어 탭용)
        if (selectedPaths.length === 1 && jarPath) {
          useAstStore.getState().loadAST(extractResult.idml_path, jarPath);
          if (extractResult.resolved_json_path) {
            useAstStore.getState().loadResolved(extractResult.resolved_json_path);
          }
          set({ idmlPath: extractResult.idml_path, resolvedJsonPath: extractResult.resolved_json_path ?? null });
        }
      } catch (e: any) {
        // 실패 — 다음 파일 계속
        set((s) => ({
          batchResults: s.batchResults.map((r, idx) =>
            idx === i ? { ...r, status: "error" as const, error: String(e), completedAt: Date.now() } : r
          ),
          batchCurrentPhaseMessage: null,
        }));
      } finally {
        unlistenExtractProgress();
      }
    }

    set({ isBatchProcessing: false, batchCurrentIndex: -1 });
  },

  extractForSemantic: async () => {
    const { lastOpenDir } = get();
    const path = await open({
      filters: [{ name: "InDesign", extensions: ["indd"] }],
      defaultPath: lastOpenDir ?? undefined,
    });
    if (!path) return;

    const parentDir = path.substring(0, path.lastIndexOf("/"));
    localStorage.setItem("lastOpenDir", parentDir);
    set({ lastOpenDir: parentDir, isExtractingForSemantic: true, extractSemanticError: null, inddPath: path });

    try {
      const jarPath = get().jarPath;
      if (!jarPath) throw new Error("JAR 경로를 찾을 수 없습니다");

      // 1. InDesign ExtendScript로 추출
      const { debugStartPage, debugEndPage, perfMode, extractChunkSize } = get();
      const extractResult = await invoke<InddExtractResult>("extract_indd", {
        inddPath: path,
        jarPath,
        spreadMode: false,
        startPage: debugStartPage,
        endPage: debugEndPage,
        perfMode,
        chunkSize: extractChunkSize > 0 ? extractChunkSize : null,
      });

      // 2. AST 로드
      await useAstStore.getState().loadAST(extractResult.idml_path, jarPath);

      // 3. Resolved 사이드카 로드
      if (extractResult.resolved_json_path) {
        await useAstStore.getState().loadResolved(extractResult.resolved_json_path);
      }

      set({
        idmlPath: extractResult.idml_path,
        resolvedJsonPath: extractResult.resolved_json_path ?? null,
        isExtractingForSemantic: false,
      });
    } catch (e: any) {
      set({ isExtractingForSemantic: false, extractSemanticError: String(e) });
    }
  },

  closeBatchModal: () => set({
    showBatchModal: false,
    batchScanResult: null,
    selectedFolderPaths: [],
    batchResults: [],
    isBatchProcessing: false,
    batchCurrentPhaseMessage: null,
  }),

  cancelBatch: () => {
    set({ batchCancelled: true });
    invoke("cancel_current").catch(() => {});
  },
}));

// 폴더 목록을 스캔하여 기존 batchScanResult에 병합하는 헬퍼
async function _mergeFoldersIntoBatch(
  folders: string[],
  replace: boolean,
  get: () => AppState,
  set: (partial: Partial<AppState> | ((s: AppState) => Partial<AppState>)) => void
) {
  const { selectedFolderPaths, batchScanResult } = get();

  // 중복 제거
  const existing = new Set(selectedFolderPaths);
  const newFolders = folders.filter((f) => !existing.has(f));
  if (newFolders.length === 0 && !replace) return;

  const baseFolderPaths = replace ? folders : [...selectedFolderPaths, ...newFolders];
  const foldersToScan = replace ? folders : newFolders;

  let merged: InddFolderScanResult = replace
    ? { direct_files: [], subfolder_files: [] }
    : (batchScanResult ?? { direct_files: [], subfolder_files: [] });

  for (const folder of foldersToScan) {
    try {
      const result = await invoke<InddFolderScanResult>("scan_indd_folder", { path: folder });
      // 단일 폴더의 직접 파일을 서브폴더 항목으로 합산 (폴더 그룹 유지)
      const folderName = folder.replace(/^.*[/\\]/, "");
      const allFromFolder = [
        ...result.direct_files.map((p) => ({ path: p, filename: p.replace(/^.*[/\\]/, "") })),
        ...result.subfolder_files.flatMap((sf) => sf.indd_files),
      ];
      if (allFromFolder.length > 0) {
        merged = {
          direct_files: merged.direct_files,
          subfolder_files: [
            ...merged.subfolder_files,
            { folder_path: folder, folder_name: folderName, indd_files: allFromFolder },
            // result.subfolder_files는 allFromFolder에 이미 포함 → 재추가 금지 (중복 방지)
          ],
        };
      }
    } catch {
      // 스캔 실패한 폴더는 건너뜀
    }
  }

  const allFiles = [
    ...merged.direct_files,
    ...merged.subfolder_files.flatMap((sf) => sf.indd_files.map((f) => f.path)),
  ];
  if (allFiles.length === 0) {
    set({ error: "선택한 폴더에서 InDesign 문서를 찾을 수 없습니다." });
    return;
  }

  set({
    showBatchModal: true,
    batchScanResult: merged,
    selectedFolderPaths: baseFolderPaths,
    batchBasePath: baseFolderPaths[0] ?? null,
    batchResults: [],
    batchCurrentIndex: -1,
    isBatchProcessing: false,
    batchCancelled: false,
  });
}
