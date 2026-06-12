import { create } from "zustand";
import { invoke } from "@tauri-apps/api/core";
import type {
  ResolvedData,
  ResolvedFont,
  ResolvedColor,
  ResolvedStory,
  ResolvedParagraphStyle,
  ResolvedCharacterStyle,
  ResolvedTextFrame,
  SemanticBlock,
  SemanticBlockDocument,
} from "../types";

interface AstStore {
  astDoc: any | null;
  isLoading: boolean;
  error: string | null;
  selectedPath: string | null;
  expandedPaths: Set<string>;
  currentSectionIndex: number;
  searchQuery: string;
  filterType: string | null;

  // Semantic Block Discovery
  semanticBlocksPath: string | null;
  semanticBlocksDoc: SemanticBlockDocument | null;
  semanticBlocksError: string | null;
  selectedSemanticBlockId: string | null;
  selectedSemanticMemberIds: Set<string>;

  // Resolved 사이드카
  resolvedData: ResolvedData | null;
  resolvedStoryMap: Map<string, ResolvedStory> | null;
  resolvedFontMap: Map<string, ResolvedFont> | null;
  resolvedColorMap: Map<string, ResolvedColor> | null;
  resolvedColorHexMap: Map<string, string> | null;  // colorName → hex
  resolvedPStyleMap: Map<string, ResolvedParagraphStyle> | null;
  resolvedCStyleMap: Map<string, ResolvedCharacterStyle> | null;
  resolvedFrameMap: Map<string, ResolvedTextFrame> | null;  // storyId → frame

  loadAST: (idmlPath: string, jarPath: string) => Promise<void>;
  loadResolved: (path: string) => Promise<void>;
  clearResolved: () => void;
  loadSemanticBlocks: (path: string) => Promise<void>;
  clearSemanticBlocks: () => void;
  selectSemanticBlock: (block: SemanticBlock | null, revealPaths?: string[]) => void;
  selectPath: (path: string | null) => void;
  toggleExpand: (path: string) => void;
  setSection: (index: number) => void;
  setSearchQuery: (q: string) => void;
  setFilterType: (t: string | null) => void;
  expandAll: () => void;
  collapseAll: () => void;
  reset: () => void;

  // 룩업 헬퍼
  getResolvedFont: (fontFamily: string) => ResolvedFont | null;
  getResolvedColor: (colorName: string) => ResolvedColor | null;
  getResolvedColorHex: (colorName: string) => string | null;
  getResolvedStory: (storyId: string) => ResolvedStory | null;
  getResolvedParagraphStyle: (styleName: string) => ResolvedParagraphStyle | null;
  getResolvedCharacterStyle: (styleName: string) => ResolvedCharacterStyle | null;
  getResolvedTextFrame: (storyId: string) => ResolvedTextFrame | null;
}

function collectAllPaths(node: any, path: string, paths: Set<string>) {
  if (!node || typeof node !== "object") return;
  paths.add(path);

  if (node.sections) {
    node.sections.forEach((_: any, i: number) => {
      collectAllPaths(node.sections[i], `${path}.sections[${i}]`, paths);
    });
  }
  if (node.blocks) {
    node.blocks.forEach((_: any, i: number) => {
      collectAllPaths(node.blocks[i], `${path}.blocks[${i}]`, paths);
    });
  }
  if (node.paragraphs && (node.blockType || node.itemType || node.cells === undefined)) {
    node.paragraphs.forEach((_: any, i: number) => {
      collectAllPaths(node.paragraphs[i], `${path}.paragraphs[${i}]`, paths);
    });
  }
  if (node.items) {
    node.items.forEach((_: any, i: number) => {
      collectAllPaths(node.items[i], `${path}.items[${i}]`, paths);
    });
  }
  if (node.rows) {
    node.rows.forEach((_: any, i: number) => {
      collectAllPaths(node.rows[i], `${path}.rows[${i}]`, paths);
    });
  }
  if (node.cells) {
    node.cells.forEach((_: any, i: number) => {
      collectAllPaths(node.cells[i], `${path}.cells[${i}]`, paths);
    });
  }
  if (node.overlayFrames) {
    node.overlayFrames.forEach((_: any, i: number) => {
      collectAllPaths(node.overlayFrames[i], `${path}.overlayFrames[${i}]`, paths);
    });
  }
  if (node.inlineTables) {
    node.inlineTables.forEach((_: any, i: number) => {
      collectAllPaths(node.inlineTables[i], `${path}.inlineTables[${i}]`, paths);
    });
  }
  // Metadata arrays
  if (node.stories) {
    node.stories.forEach((_: any, i: number) => {
      paths.add(`${path}.stories[${i}]`);
    });
    paths.add(`${path}._stories`);
  }
  if (node.fonts) {
    node.fonts.forEach((_: any, i: number) => {
      paths.add(`${path}.fonts[${i}]`);
    });
    paths.add(`${path}._fonts`);
  }
  if (node.paragraphStyles) {
    node.paragraphStyles.forEach((_: any, i: number) => {
      paths.add(`${path}.paragraphStyles[${i}]`);
    });
    paths.add(`${path}._paragraphStyles`);
  }
  if (node.characterStyles) {
    node.characterStyles.forEach((_: any, i: number) => {
      paths.add(`${path}.characterStyles[${i}]`);
    });
    paths.add(`${path}._characterStyles`);
  }
  if (node.backgrounds) {
    node.backgrounds.forEach((_: any, i: number) => {
      paths.add(`${path}.backgrounds[${i}]`);
    });
    paths.add(`${path}._backgrounds`);
  }
  if (node.colors) {
    paths.add(`${path}._colors`);
  }
}

function buildResolvedMaps(data: ResolvedData) {
  const storyMap = new Map<string, ResolvedStory>();
  for (const s of data.stories) {
    storyMap.set(s.id, s);
  }

  const fontMap = new Map<string, ResolvedFont>();
  for (const f of data.fonts) {
    fontMap.set(f.fontFamily, f);
    // name은 "Family\tStyle" 형식일 수 있어서 family로도 검색 가능하게
    if (f.name && f.name !== f.fontFamily) {
      fontMap.set(f.name, f);
    }
  }

  const colorMap = new Map<string, ResolvedColor>();
  const colorHexMap = new Map<string, string>();
  for (const c of data.colors) {
    colorMap.set(c.name, c);
    if (c.hex) {
      colorHexMap.set(c.name, c.hex);
    }
  }

  const pStyleMap = new Map<string, ResolvedParagraphStyle>();
  for (const ps of data.paragraphStyles) {
    pStyleMap.set(ps.name, ps);
  }

  const cStyleMap = new Map<string, ResolvedCharacterStyle>();
  for (const cs of data.characterStyles) {
    cStyleMap.set(cs.name, cs);
  }

  const frameMap = new Map<string, ResolvedTextFrame>();
  if (data.textFrames) {
    for (const tf of data.textFrames) {
      if (tf.storyId) {
        frameMap.set(tf.storyId, tf);
      }
    }
  }

  return { storyMap, fontMap, colorMap, colorHexMap, pStyleMap, cStyleMap, frameMap };
}

export const useAstStore = create<AstStore>((set, get) => ({
  astDoc: null,
  isLoading: false,
  error: null,
  selectedPath: null,
  expandedPaths: new Set<string>(),
  currentSectionIndex: 0,
  searchQuery: "",
  filterType: null,
  semanticBlocksPath: null,
  semanticBlocksDoc: null,
  semanticBlocksError: null,
  selectedSemanticBlockId: null,
  selectedSemanticMemberIds: new Set<string>(),

  // Resolved 사이드카
  resolvedData: null,
  resolvedStoryMap: null,
  resolvedFontMap: null,
  resolvedColorMap: null,
  resolvedColorHexMap: null,
  resolvedPStyleMap: null,
  resolvedCStyleMap: null,
  resolvedFrameMap: null,

  loadAST: async (idmlPath: string, jarPath: string) => {
    set({ isLoading: true, error: null });
    try {
      const result = await invoke<any>("export_ast", {
        idmlPath,
        jarPath,
      });
      set({
        astDoc: result,
        isLoading: false,
        selectedPath: null,
        expandedPaths: new Set(["sections"]),
        currentSectionIndex: 0,
        searchQuery: "",
        filterType: null,
      });
    } catch (e: any) {
      set({ isLoading: false, error: String(e) });
    }
  },

  loadResolved: async (path: string) => {
    try {
      const data = await invoke<ResolvedData>("read_resolved_json", { path });
      const { storyMap, fontMap, colorMap, colorHexMap, pStyleMap, cStyleMap, frameMap } = buildResolvedMaps(data);
      set({
        resolvedData: data,
        resolvedStoryMap: storyMap,
        resolvedFontMap: fontMap,
        resolvedColorMap: colorMap,
        resolvedColorHexMap: colorHexMap,
        resolvedPStyleMap: pStyleMap,
        resolvedCStyleMap: cStyleMap,
        resolvedFrameMap: frameMap,
      });
    } catch (e: any) {
      console.warn("resolved.json 로드 실패 (무시):", e);
      // resolved 로드 실패는 치명적이지 않음 — AST만으로 계속 진행
    }
  },

  clearResolved: () =>
    set({
      resolvedData: null,
      resolvedStoryMap: null,
      resolvedFontMap: null,
      resolvedColorMap: null,
      resolvedColorHexMap: null,
      resolvedPStyleMap: null,
      resolvedCStyleMap: null,
      resolvedFrameMap: null,
    }),

  loadSemanticBlocks: async (path: string) => {
    set({
      semanticBlocksPath: path,
      semanticBlocksError: null,
      semanticBlocksDoc: null,
      selectedSemanticBlockId: null,
      selectedSemanticMemberIds: new Set<string>(),
    });
    try {
      const raw = await invoke<string>("read_text_file", { path });
      const data = JSON.parse(raw) as SemanticBlockDocument;
      set({
        semanticBlocksDoc: data,
        semanticBlocksError: null,
      });
    } catch (e: any) {
      set({
        semanticBlocksDoc: null,
        semanticBlocksError: String(e),
      });
    }
  },

  clearSemanticBlocks: () =>
    set({
      semanticBlocksPath: null,
      semanticBlocksDoc: null,
      semanticBlocksError: null,
      selectedSemanticBlockId: null,
      selectedSemanticMemberIds: new Set<string>(),
    }),

  selectSemanticBlock: (block, revealPaths = []) => {
    const expanded = new Set(get().expandedPaths);
    for (const path of revealPaths) {
      const parts = path.split(".");
      for (let i = 1; i <= parts.length; i++) {
        expanded.add(parts.slice(0, i).join("."));
      }
    }
    set({
      selectedSemanticBlockId: block?.id ?? null,
      selectedSemanticMemberIds: new Set(block?.member_ids ?? []),
      selectedPath: revealPaths[0] ?? get().selectedPath,
      expandedPaths: expanded,
    });
  },

  selectPath: (path) => set({ selectedPath: path }),

  toggleExpand: (path) => {
    const expanded = new Set(get().expandedPaths);
    if (expanded.has(path)) {
      expanded.delete(path);
    } else {
      expanded.add(path);
    }
    set({ expandedPaths: expanded });
  },

  setSection: (index) => set({ currentSectionIndex: index }),

  setSearchQuery: (q) => set({ searchQuery: q }),

  setFilterType: (t) => set({ filterType: t }),

  expandAll: () => {
    const doc = get().astDoc;
    if (!doc) return;
    const paths = new Set<string>();
    collectAllPaths(doc, "root", paths);
    set({ expandedPaths: paths });
  },

  collapseAll: () => {
    set({ expandedPaths: new Set() });
  },

  reset: () =>
    set({
      astDoc: null,
      error: null,
      selectedPath: null,
      expandedPaths: new Set(),
      currentSectionIndex: 0,
      searchQuery: "",
      filterType: null,
      semanticBlocksPath: null,
      semanticBlocksDoc: null,
      semanticBlocksError: null,
      selectedSemanticBlockId: null,
      selectedSemanticMemberIds: new Set<string>(),
      resolvedData: null,
      resolvedStoryMap: null,
      resolvedFontMap: null,
      resolvedColorMap: null,
      resolvedColorHexMap: null,
      resolvedPStyleMap: null,
      resolvedCStyleMap: null,
      resolvedFrameMap: null,
    }),

  // 룩업 헬퍼
  getResolvedFont: (fontFamily: string) => {
    return get().resolvedFontMap?.get(fontFamily) ?? null;
  },

  getResolvedColor: (colorName: string) => {
    return get().resolvedColorMap?.get(colorName) ?? null;
  },

  getResolvedColorHex: (colorName: string) => {
    return get().resolvedColorHexMap?.get(colorName) ?? null;
  },

  getResolvedStory: (storyId: string) => {
    return get().resolvedStoryMap?.get(storyId) ?? null;
  },

  getResolvedParagraphStyle: (styleName: string) => {
    return get().resolvedPStyleMap?.get(styleName) ?? null;
  },

  getResolvedCharacterStyle: (styleName: string) => {
    return get().resolvedCStyleMap?.get(styleName) ?? null;
  },

  getResolvedTextFrame: (storyId: string) => {
    return get().resolvedFrameMap?.get(storyId) ?? null;
  },
}));
