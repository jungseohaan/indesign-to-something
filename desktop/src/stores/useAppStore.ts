import { create } from "zustand";
import { invoke } from "@tauri-apps/api/core";
import { open, save } from "@tauri-apps/plugin-dialog";
import { listen } from "@tauri-apps/api/event";
import type {
  IDMLStructure,
  ConvertOptions,
  ConvertResult,
  ProgressEvent,
  LogEvent,
  FrameInfo,
  ImagePreview,
  TextFrameDetail,
  SpreadInfo,
  PageInfo,
  MasterSpreadInfo,
} from "../types";

interface AppState {
  // File
  idmlPath: string | null;
  idmlStructure: IDMLStructure | null;
  jarPath: string | null;

  // Settings
  spreadBased: boolean;
  vectorDpi: 96 | 150;
  includeImages: boolean;
  linksDirectory: string | null;
  startPage: number | null;
  endPage: number | null;

  // Preview
  selectedImage: FrameInfo | null;  // 선택된 이미지 또는 벡터
  previewImages: ImagePreview[];
  isGeneratingPreview: boolean;

  // Text Frame Detail
  selectedTextFrame: FrameInfo | null;
  textFrameDetail: TextFrameDetail | null;
  isLoadingTextDetail: boolean;

  // Spread/Page Selection
  selectedSpread: SpreadInfo | null;
  selectedPage: PageInfo | null;

  // Master Spread Preview
  selectedMaster: MasterSpreadInfo | null;
  masterPreview: ImagePreview | null;
  isGeneratingMasterPreview: boolean;

  // Status
  isAnalyzing: boolean;
  isConverting: boolean;
  progress: ProgressEvent | null;
  conversionLogs: string[];
  error: string | null;
  result: ConvertResult | null;

  // Actions
  selectFile: () => Promise<void>;
  selectHwpxFile: () => Promise<void>;
  analyzeFile: () => Promise<void>;
  startConversion: () => Promise<void>;
  setSpreadBased: (v: boolean) => void;
  setVectorDpi: (v: 96 | 150) => void;
  setIncludeImages: (v: boolean) => void;
  setLinksDirectory: (v: string | null) => void;
  setStartPage: (v: number | null) => void;
  setEndPage: (v: number | null) => void;
  clearError: () => void;
  clearLogs: () => void;
  initJarPath: () => Promise<void>;
  selectImage: (frame: FrameInfo) => Promise<void>;
  selectTextFrame: (frame: FrameInfo) => Promise<void>;
  selectFrame: (frame: FrameInfo) => Promise<void>;
  selectSpread: (spread: SpreadInfo) => void;
  selectPage: (page: PageInfo) => void;
  selectMaster: (master: MasterSpreadInfo) => Promise<void>;
  clearSelection: () => void;
}

export const useAppStore = create<AppState>((set, get) => ({
  // Initial state
  idmlPath: null,
  idmlStructure: null,
  jarPath: null,
  spreadBased: false,
  vectorDpi: 150,
  includeImages: true,
  linksDirectory: null,
  startPage: null,
  endPage: null,
  selectedImage: null,
  previewImages: [],
  isGeneratingPreview: false,
  selectedTextFrame: null,
  textFrameDetail: null,
  isLoadingTextDetail: false,
  selectedSpread: null,
  selectedPage: null,
  selectedMaster: null,
  masterPreview: null,
  isGeneratingMasterPreview: false,
  isAnalyzing: false,
  isConverting: false,
  progress: null,
  conversionLogs: [],
  error: null,
  result: null,

  initJarPath: async () => {
    try {
      const path = await invoke<string>("get_jar_path");
      set({ jarPath: path });
    } catch (e) {
      set({ error: `JAR 파일을 찾을 수 없습니다: ${e}` });
    }
  },

  selectFile: async () => {
    try {
      const selected = await open({
        multiple: false,
        filters: [{ name: "IDML Files", extensions: ["idml"] }],
      });

      if (selected) {
        set({
          idmlPath: selected as string,
          idmlStructure: null,
          error: null,
          result: null,
          selectedImage: null,
          previewImages: [],
        });

        // Automatically analyze after selecting
        await get().analyzeFile();
      }
    } catch (e) {
      set({ error: `파일 선택 실패: ${e}` });
    }
  },

  analyzeFile: async () => {
    const { idmlPath, jarPath } = get();
    if (!idmlPath || !jarPath) return;

    set({ isAnalyzing: true, error: null });

    try {
      const structure = await invoke<IDMLStructure>("analyze_idml", {
        path: idmlPath,
        jarPath,
      });
      set({ idmlStructure: structure });
    } catch (e) {
      set({ error: `분석 실패: ${e}` });
    } finally {
      set({ isAnalyzing: false });
    }
  },

  selectImage: async (frame: FrameInfo) => {
    // 이미지와 벡터만 선택 가능
    if (frame.type !== "image" && frame.type !== "vector") return;

    set({ selectedImage: frame });

    const { previewImages, idmlPath, jarPath } = get();
    if (!idmlPath || !jarPath) return;

    // 이미지와 벡터 모두 frame.id를 키로 사용 (트랜스폼 적용된 렌더링)
    const previewKey = frame.type === "image"
      ? `image:${frame.id}`
      : `vector:${frame.id}`;

    // 이미 미리보기가 있으면 새로 생성하지 않음
    const existingPreview = previewImages.find(
      (p) => p.original_path === previewKey
    );
    if (existingPreview) return;

    set({ isGeneratingPreview: true });

    try {
      if (frame.type === "image") {
        // 이미지 미리보기 생성 (트랜스폼, 클리핑 적용)
        const preview = await invoke<ImagePreview>("generate_image_preview", {
          idmlPath,
          frameId: frame.id,
          jarPath,
        });

        set((state) => ({
          previewImages: [...state.previewImages, preview],
        }));
      } else if (frame.type === "vector") {
        // 벡터 미리보기 생성
        const preview = await invoke<ImagePreview>("generate_vector_preview", {
          idmlPath,
          frameId: frame.id,
          jarPath,
        });

        set((state) => ({
          previewImages: [...state.previewImages, preview],
        }));
      }
    } catch (e) {
      console.error("미리보기 생성 실패:", e);
      // 미리보기 생성 실패해도 에러 표시하지 않음
    } finally {
      set({ isGeneratingPreview: false });
    }
  },

  startConversion: async () => {
    const {
      idmlPath,
      jarPath,
      spreadBased,
      vectorDpi,
      includeImages,
      linksDirectory,
      startPage,
      endPage,
    } = get();

    if (!idmlPath || !jarPath) return;

    // 출력 파일명 생성: {filename}-{mode}-{dpi}dpi.hwpx
    const idmlFilename = idmlPath.substring(idmlPath.lastIndexOf("/") + 1).replace(".idml", "");
    const mode = spreadBased ? "spread" : "page";
    const defaultOutputName = `${idmlFilename}-${mode}-${vectorDpi}dpi.hwpx`;
    const idmlDir = idmlPath.substring(0, idmlPath.lastIndexOf("/"));

    // Ask for output path
    const outputPath = await save({
      filters: [{ name: "HWPX Files", extensions: ["hwpx"] }],
      defaultPath: `${idmlDir}/${defaultOutputName}`,
    });

    if (!outputPath) return;

    set({ isConverting: true, error: null, progress: null, result: null, conversionLogs: [] });

    // Listen for progress events
    const unlistenProgress = await listen<ProgressEvent>("conversion-progress", (event) => {
      set({ progress: event.payload });
    });

    // Listen for log events
    const unlistenLog = await listen<LogEvent>("conversion-log", (event) => {
      set((state) => ({
        conversionLogs: [...state.conversionLogs, event.payload.message],
      }));
    });

    try {
      // Links 디렉토리: 설정되지 않은 경우 IDML 파일과 같은 레벨의 Links 폴더 사용
      let effectiveLinksDir = linksDirectory;
      if (!effectiveLinksDir && idmlPath) {
        const idmlDir = idmlPath.substring(0, idmlPath.lastIndexOf("/"));
        effectiveLinksDir = `${idmlDir}/Links`;
      }

      const options: ConvertOptions = {
        spread_based: spreadBased,
        vector_dpi: vectorDpi,
        include_images: includeImages,
        links_directory: effectiveLinksDir,
        start_page: startPage,
        end_page: endPage,
      };

      console.log("Converting with options:", options);

      const result = await invoke<ConvertResult>("convert_idml", {
        inputPath: idmlPath,
        outputPath,
        options,
        jarPath,
      });

      set({ result });
    } catch (e) {
      set({ error: `변환 실패: ${e}` });
    } finally {
      unlistenProgress();
      unlistenLog();
      set({ isConverting: false, progress: null });
    }
  },

  setSpreadBased: (v) => set({ spreadBased: v }),
  setVectorDpi: (v) => set({ vectorDpi: v }),
  setIncludeImages: (v) => set({ includeImages: v }),
  setLinksDirectory: (v) => set({ linksDirectory: v }),
  setStartPage: (v) => set({ startPage: v }),
  setEndPage: (v) => set({ endPage: v }),
  clearError: () => set({ error: null }),
  clearLogs: () => set({ conversionLogs: [] }),

  selectHwpxFile: async () => {
    try {
      const selected = await open({
        multiple: false,
        filters: [{ name: "HWPX Files", extensions: ["hwpx"] }],
      });

      if (!selected) return;

      const hwpxPath = selected as string;
      const { jarPath } = get();
      if (!jarPath) {
        set({ error: "JAR 파일을 찾을 수 없습니다." });
        return;
      }

      // 출력 파일명 생성: {filename}.idml
      const hwpxFilename = hwpxPath.substring(hwpxPath.lastIndexOf("/") + 1).replace(".hwpx", "");
      const hwpxDir = hwpxPath.substring(0, hwpxPath.lastIndexOf("/"));
      const defaultOutputName = `${hwpxFilename}.idml`;

      // Ask for output path
      const outputPath = await save({
        filters: [{ name: "IDML Files", extensions: ["idml"] }],
        defaultPath: `${hwpxDir}/${defaultOutputName}`,
      });

      if (!outputPath) return;

      set({ isConverting: true, error: null, progress: null, result: null, conversionLogs: [] });

      // Listen for progress events
      const unlistenProgress = await listen<ProgressEvent>("conversion-progress", (event) => {
        set({ progress: event.payload });
      });

      // Listen for log events
      const unlistenLog = await listen<LogEvent>("conversion-log", (event) => {
        set((state) => ({
          conversionLogs: [...state.conversionLogs, event.payload.message],
        }));
      });

      try {
        const result = await invoke<ConvertResult>("convert_hwpx_to_idml", {
          inputPath: hwpxPath,
          outputPath,
          jarPath,
        });

        set({ result });
      } catch (e) {
        set({ error: `HWPX → IDML 변환 실패: ${e}` });
      } finally {
        unlistenProgress();
        unlistenLog();
        set({ isConverting: false, progress: null });
      }
    } catch (e) {
      set({ error: `파일 선택 실패: ${e}` });
    }
  },

  selectTextFrame: async (frame: FrameInfo) => {
    if (frame.type !== "text") return;

    set({ selectedTextFrame: frame, textFrameDetail: null, isLoadingTextDetail: true });

    const { idmlPath, jarPath } = get();
    if (!idmlPath || !jarPath) {
      set({ isLoadingTextDetail: false });
      return;
    }

    try {
      const detail = await invoke<TextFrameDetail>("get_text_frame_detail", {
        idmlPath,
        frameId: frame.id,
        jarPath,
      });
      set({ textFrameDetail: detail });
    } catch (e) {
      console.error("텍스트 프레임 상세 정보 로드 실패:", e);
    } finally {
      set({ isLoadingTextDetail: false });
    }
  },

  selectFrame: async (frame: FrameInfo) => {
    // 프레임 타입에 따라 적절한 선택 함수 호출
    if (frame.type === "text") {
      set({ selectedImage: null, selectedMaster: null, masterPreview: null });
      await get().selectTextFrame(frame);
    } else if (frame.type === "image" || frame.type === "vector") {
      set({ selectedTextFrame: null, textFrameDetail: null, selectedMaster: null, masterPreview: null });
      await get().selectImage(frame);
    }
  },

  selectSpread: (spread: SpreadInfo) => {
    set({
      selectedSpread: spread,
      selectedPage: null,
      selectedImage: null,
      selectedTextFrame: null,
      textFrameDetail: null,
      selectedMaster: null,
      masterPreview: null,
    });
  },

  selectPage: (page: PageInfo) => {
    set({
      selectedPage: page,
      selectedSpread: null,
      selectedImage: null,
      selectedTextFrame: null,
      textFrameDetail: null,
      selectedMaster: null,
      masterPreview: null,
    });
  },

  selectMaster: async (master: MasterSpreadInfo) => {
    set({
      selectedMaster: master,
      masterPreview: null,
      isGeneratingMasterPreview: true,
      selectedSpread: null,
      selectedPage: null,
      selectedImage: null,
      selectedTextFrame: null,
      textFrameDetail: null,
    });

    const { idmlPath, jarPath } = get();
    if (!idmlPath || !jarPath) {
      set({ isGeneratingMasterPreview: false });
      return;
    }

    try {
      const preview = await invoke<ImagePreview>("generate_master_preview", {
        idmlPath,
        masterId: master.id,
        jarPath,
      });
      set({ masterPreview: preview });
    } catch (e) {
      console.error("마스터 스프레드 미리보기 생성 실패:", e);
    } finally {
      set({ isGeneratingMasterPreview: false });
    }
  },

  clearSelection: () => {
    set({
      selectedSpread: null,
      selectedPage: null,
      selectedImage: null,
      selectedTextFrame: null,
      textFrameDetail: null,
      selectedMaster: null,
      masterPreview: null,
    });
  },
}));
