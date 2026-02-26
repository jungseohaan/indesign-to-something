import { create } from "zustand";
import { invoke } from "@tauri-apps/api/core";

interface AstStore {
  astDoc: any | null;
  isLoading: boolean;
  error: string | null;
  selectedPath: string | null;
  expandedPaths: Set<string>;
  currentSectionIndex: number;
  searchQuery: string;
  filterType: string | null;

  loadAST: (idmlPath: string, jarPath: string) => Promise<void>;
  selectPath: (path: string | null) => void;
  toggleExpand: (path: string) => void;
  setSection: (index: number) => void;
  setSearchQuery: (q: string) => void;
  setFilterType: (t: string | null) => void;
  expandAll: () => void;
  collapseAll: () => void;
  reset: () => void;
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

export const useAstStore = create<AstStore>((set, get) => ({
  astDoc: null,
  isLoading: false,
  error: null,
  selectedPath: null,
  expandedPaths: new Set<string>(),
  currentSectionIndex: 0,
  searchQuery: "",
  filterType: null,

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
    }),
}));
