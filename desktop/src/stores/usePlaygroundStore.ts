import { create } from "zustand";
import { invoke } from "@tauri-apps/api/core";
import { open, save } from "@tauri-apps/plugin-dialog";
import type {
  IDMLStructure,
  MasterSpreadInfo,
  ImagePreview,
  CreateIdmlResult,
  TemplateSchema,
  DataItem,
} from "../types";

export interface PageSpec {
  id: string;
  masterSpreadId: string | null;
  textFrame: boolean;
  textFrameWidth: number | null;
  textFrameHeight: number | null;
}

let nextPageId = 1;
function generatePageId(): string {
  return `page-${nextPageId++}`;
}

function createPage(masterSpreadId: string | null): PageSpec {
  return {
    id: generatePageId(),
    masterSpreadId,
    textFrame: masterSpreadId !== null,
    textFrameWidth: null,
    textFrameHeight: null,
  };
}

interface PlaygroundState {
  // File
  idmlPath: string | null;
  jarPath: string | null;
  masterSpreads: MasterSpreadInfo[];
  isLoading: boolean;

  // Schema
  schema: TemplateSchema | null;
  isExtracting: boolean;

  // Data Items
  items: DataItem[];

  // Pages
  pages: PageSpec[];

  // Selection & Preview
  selectedMaster: MasterSpreadInfo | null;
  masterPreview: ImagePreview | null;
  isGeneratingPreview: boolean;

  // Creation
  isCreating: boolean;
  createResult: CreateIdmlResult | null;

  // Inline Text Frames
  inlineCount: number;

  // Text Frame Mode
  tfMode: "master" | "custom";

  // Status
  error: string | null;

  // Actions
  initJarPath: () => Promise<void>;
  openFile: () => Promise<void>;
  selectMaster: (master: MasterSpreadInfo) => Promise<void>;
  createIdml: () => Promise<void>;
  mergeIdml: () => Promise<void>;
  clearError: () => void;
  reset: () => void;

  // Page Actions
  addPage: () => void;
  removePage: (id: string) => void;
  setPageMaster: (id: string, masterSpreadId: string | null) => void;
  movePageUp: (id: string) => void;
  movePageDown: (id: string) => void;

  // Text Frame Actions
  toggleTextFrame: (id: string) => void;
  setTextFrameSize: (
    id: string,
    width: number | null,
    height: number | null
  ) => void;

  // Inline Count Actions
  setInlineCount: (count: number) => void;

  // Text Frame Mode Actions
  setTfMode: (mode: "master" | "custom") => void;

  // Data Item Actions
  setItemField: (index: number, field: string, value: string) => void;
  addItem: () => void;
  removeItem: (index: number) => void;
  loadDataFile: () => Promise<void>;
}

export const usePlaygroundStore = create<PlaygroundState>((set, get) => ({
  idmlPath: null,
  jarPath: null,
  masterSpreads: [],
  isLoading: false,
  schema: null,
  isExtracting: false,
  items: [],
  pages: [],
  selectedMaster: null,
  masterPreview: null,
  isGeneratingPreview: false,
  isCreating: false,
  createResult: null,
  inlineCount: 5,
  tfMode: "master" as const,
  error: null,

  initJarPath: async () => {
    try {
      const path = await invoke<string>("get_jar_path");
      set({ jarPath: path });
    } catch (e) {
      set({ error: `JAR 파일을 찾을 수 없습니다: ${e}` });
    }
  },

  openFile: async () => {
    try {
      const selected = await open({
        multiple: false,
        filters: [{ name: "IDML Files", extensions: ["idml"] }],
      });

      if (!selected) return;

      const idmlPath = selected as string;
      set({
        idmlPath,
        masterSpreads: [],
        selectedMaster: null,
        masterPreview: null,
        createResult: null,
        schema: null,
        items: [],
        pages: [],
        error: null,
        isLoading: true,
        isExtracting: true,
      });

      const { jarPath } = get();
      if (!jarPath) {
        set({ error: "JAR 파일을 찾을 수 없습니다.", isLoading: false, isExtracting: false });
        return;
      }

      // Run analyze and extract-schema in parallel
      const [structure, schema] = await Promise.all([
        invoke<IDMLStructure>("analyze_idml", { path: idmlPath, jarPath }),
        invoke<TemplateSchema>("extract_template_schema", { sourcePath: idmlPath, jarPath }),
      ]);

      const masters = structure.master_spreads || [];
      const firstMasterId = masters.length > 0 ? masters[0].id : null;

      // Create empty items with schema fields
      const emptyItem: DataItem = {};
      if (schema.itemFields) {
        for (const field of schema.itemFields) {
          emptyItem[field] = "";
        }
      }

      set({
        masterSpreads: masters,
        schema,
        items: [{ ...emptyItem }],
        pages: [createPage(firstMasterId)],
        isLoading: false,
        isExtracting: false,
      });
    } catch (e) {
      set({ error: `분석 실패: ${e}`, isLoading: false, isExtracting: false });
    }
  },

  selectMaster: async (master: MasterSpreadInfo) => {
    set({
      selectedMaster: master,
      masterPreview: null,
      isGeneratingPreview: true,
    });

    const { idmlPath, jarPath } = get();
    if (!idmlPath || !jarPath) {
      set({ isGeneratingPreview: false });
      return;
    }

    try {
      const preview = await invoke<ImagePreview>("generate_master_preview", {
        idmlPath,
        masterId: master.id,
        jarPath,
      });
      set({ masterPreview: preview, isGeneratingPreview: false });
    } catch (e) {
      console.error("마스터 프리뷰 생성 실패:", e);
      set({ isGeneratingPreview: false });
    }
  },

  createIdml: async () => {
    const { idmlPath, jarPath, pages, masterSpreads, inlineCount, tfMode } = get();
    if (!idmlPath || !jarPath) return;

    const idmlFilename = idmlPath
      .substring(idmlPath.lastIndexOf("/") + 1)
      .replace(".idml", "");
    const idmlDir = idmlPath.substring(0, idmlPath.lastIndexOf("/"));
    const defaultOutputName = `${idmlFilename}-template.idml`;

    const outputPath = await save({
      filters: [{ name: "IDML Files", extensions: ["idml"] }],
      defaultPath: `${idmlDir}/${defaultOutputName}`,
    });

    if (!outputPath) return;

    set({ isCreating: true, createResult: null, error: null });

    try {
      const pageSpecs = pages.map((p) => p.masterSpreadId || "none");

      const textFrameSpecs = pages.map((p) => {
        if (!p.textFrame || !p.masterSpreadId) return "none";
        if (p.textFrameWidth == null && p.textFrameHeight == null) return "auto";
        const master = masterSpreads.find((m) => m.id === p.masterSpreadId);
        const w =
          p.textFrameWidth ??
          (master
            ? (master.page_width -
                master.margin_left -
                master.margin_right -
                Math.max(0, (master.column_count || 1) - 1) *
                  (master.column_gutter || 0)) /
              (master.column_count || 1)
            : 481.89);
        const h =
          p.textFrameHeight ??
          (master
            ? master.page_height - master.margin_top - master.margin_bottom
            : 728.5);
        return `${w}x${h}`;
      });

      const result = await invoke<CreateIdmlResult>(
        "create_idml_from_masters",
        {
          sourcePath: idmlPath,
          outputPath,
          masterIds: null,
          pageSpecs,
          textFrameSpecs,
          inlineCount: inlineCount > 0 ? inlineCount : null,
          tfMode,
          validate: true,
          jarPath,
        }
      );

      set({ createResult: result, isCreating: false });
    } catch (e) {
      set({ error: `IDML 생성 실패: ${e}`, isCreating: false });
    }
  },

  mergeIdml: async () => {
    const { idmlPath, jarPath, pages, items, tfMode, schema } = get();
    if (!idmlPath || !jarPath || !schema) return;

    // Filter out empty items
    const nonEmptyItems = items.filter((item) =>
      Object.values(item).some((v) => v.trim() !== "")
    );
    if (nonEmptyItems.length === 0) {
      set({ error: "문항 데이터를 입력해주세요." });
      return;
    }

    const idmlFilename = idmlPath
      .substring(idmlPath.lastIndexOf("/") + 1)
      .replace(".idml", "");
    const idmlDir = idmlPath.substring(0, idmlPath.lastIndexOf("/"));
    const defaultOutputName = `${idmlFilename}-merged.idml`;

    const outputPath = await save({
      filters: [{ name: "IDML Files", extensions: ["idml"] }],
      defaultPath: `${idmlDir}/${defaultOutputName}`,
    });

    if (!outputPath) return;

    set({ isCreating: true, createResult: null, error: null });

    try {
      const firstMasterId = schema.masterSpreads.length > 0
        ? schema.masterSpreads[0].id
        : "u102";

      const mergeData = {
        pages: pages.map((p) => ({
          master: p.masterSpreadId || firstMasterId,
          textFrame: p.textFrame,
        })),
        tfMode,
        items: nonEmptyItems,
      };

      const result = await invoke<CreateIdmlResult>("merge_idml", {
        sourcePath: idmlPath,
        dataJson: JSON.stringify(mergeData),
        outputPath,
        validate: true,
        jarPath,
      });

      set({ createResult: result, isCreating: false });
    } catch (e) {
      set({ error: `IDML 머지 실패: ${e}`, isCreating: false });
    }
  },

  // Page Actions
  addPage: () => {
    const { pages, masterSpreads } = get();
    const firstMasterId = masterSpreads.length > 0 ? masterSpreads[0].id : null;
    set({ pages: [...pages, createPage(firstMasterId)] });
  },

  removePage: (id: string) => {
    const { pages } = get();
    if (pages.length <= 1) return;
    set({ pages: pages.filter((p) => p.id !== id) });
  },

  setPageMaster: (id: string, masterSpreadId: string | null) => {
    const { pages } = get();
    set({
      pages: pages.map((p) =>
        p.id === id
          ? {
              ...p,
              masterSpreadId,
              textFrame: masterSpreadId !== null ? p.textFrame : false,
            }
          : p
      ),
    });
  },

  movePageUp: (id: string) => {
    const { pages } = get();
    const idx = pages.findIndex((p) => p.id === id);
    if (idx <= 0) return;
    const newPages = [...pages];
    [newPages[idx - 1], newPages[idx]] = [newPages[idx], newPages[idx - 1]];
    set({ pages: newPages });
  },

  movePageDown: (id: string) => {
    const { pages } = get();
    const idx = pages.findIndex((p) => p.id === id);
    if (idx < 0 || idx >= pages.length - 1) return;
    const newPages = [...pages];
    [newPages[idx], newPages[idx + 1]] = [newPages[idx + 1], newPages[idx]];
    set({ pages: newPages });
  },

  // Text Frame Actions
  toggleTextFrame: (id: string) => {
    const { pages } = get();
    set({
      pages: pages.map((p) =>
        p.id === id ? { ...p, textFrame: !p.textFrame } : p
      ),
    });
  },

  setTextFrameSize: (
    id: string,
    width: number | null,
    height: number | null
  ) => {
    const { pages } = get();
    set({
      pages: pages.map((p) =>
        p.id === id
          ? { ...p, textFrameWidth: width, textFrameHeight: height }
          : p
      ),
    });
  },

  setInlineCount: (count: number) => set({ inlineCount: Math.max(0, count) }),

  setTfMode: (mode: "master" | "custom") => set({ tfMode: mode }),

  // Data Item Actions
  setItemField: (index: number, field: string, value: string) => {
    const { items } = get();
    const newItems = [...items];
    newItems[index] = { ...newItems[index], [field]: value };
    set({ items: newItems });
  },

  addItem: () => {
    const { items, schema } = get();
    const emptyItem: DataItem = {};
    if (schema?.itemFields) {
      for (const field of schema.itemFields) {
        emptyItem[field] = "";
      }
    }
    set({ items: [...items, emptyItem] });
  },

  removeItem: (index: number) => {
    const { items } = get();
    if (items.length <= 1) return;
    set({ items: items.filter((_, i) => i !== index) });
  },

  loadDataFile: async () => {
    try {
      const selected = await open({
        multiple: false,
        filters: [{ name: "JSON Files", extensions: ["json"] }],
      });

      if (!selected) return;

      const filePath = selected as string;
      // Read JSON file via fetch or Tauri's fs API - use invoke workaround
      const response = await fetch(`asset://localhost/${filePath}`).catch(() => null);
      let text: string;
      if (response?.ok) {
        text = await response.text();
      } else {
        // Fallback: read via Rust
        text = await invoke<string>("read_text_file", { path: filePath }).catch(() => {
          throw new Error("파일을 읽을 수 없습니다.");
        });
      }

      const data = JSON.parse(text);

      // Support both { items: [...] } and direct array format
      const loadedItems: DataItem[] = Array.isArray(data) ? data : (data.items || []);
      if (loadedItems.length === 0) {
        set({ error: "JSON에 문항 데이터가 없습니다." });
        return;
      }

      // Also load pages and tfMode if present
      const updates: Partial<PlaygroundState> = { items: loadedItems };
      if (data.pages && Array.isArray(data.pages)) {
        const { masterSpreads } = get();
        const newPages = data.pages.map((p: { master: string; textFrame: boolean }) => {
          const page = createPage(p.master || (masterSpreads[0]?.id ?? null));
          page.textFrame = p.textFrame !== false;
          return page;
        });
        (updates as any).pages = newPages;
      }
      if (data.tfMode) {
        (updates as any).tfMode = data.tfMode;
      }

      set(updates as any);
    } catch (e) {
      set({ error: `JSON 로드 실패: ${e}` });
    }
  },

  clearError: () => set({ error: null }),

  reset: () =>
    set({
      idmlPath: null,
      masterSpreads: [],
      selectedMaster: null,
      masterPreview: null,
      createResult: null,
      schema: null,
      items: [],
      pages: [],
      inlineCount: 5,
      tfMode: "master" as const,
      error: null,
      isLoading: false,
      isExtracting: false,
      isGeneratingPreview: false,
      isCreating: false,
    }),
}));
