import { useMemo } from "react";
import { useAstStore } from "../stores/useAstStore";

// ─── Helpers ────────────────────────────────────────────────────────

function hwpuToMm(v: number): string {
  return (v / 7200 * 25.4).toFixed(1);
}

function dimMm(w: number, h: number): string {
  return `${hwpuToMm(w)}×${hwpuToMm(h)}mm`;
}

function nodeMatchesSearch(node: any, label: string, sub: string | undefined, query: string): boolean {
  if (!query) return true;
  const q = query.toLowerCase();
  if (label.toLowerCase().includes(q)) return true;
  if (sub && sub.toLowerCase().includes(q)) return true;
  if (node?.text && node.text.toLowerCase().includes(q)) return true;
  if (node?.hwpScript && node.hwpScript.toLowerCase().includes(q)) return true;
  if (node?.sourceId && node.sourceId.toLowerCase().includes(q)) return true;
  if (node?.fontFamily && node.fontFamily.toLowerCase().includes(q)) return true;
  if (node?.styleName && node.styleName.toLowerCase().includes(q)) return true;
  if (node?.storyId && node.storyId.toLowerCase().includes(q)) return true;
  return false;
}

function nodeMatchesFilter(node: any, filterType: string | null): boolean {
  if (!filterType) return true;
  if (node?.itemType === filterType) return true;
  if (node?.blockType === filterType) return true;
  return false;
}

function highlightText(text: string, query: string): React.ReactNode {
  if (!query) return text;
  const idx = text.toLowerCase().indexOf(query.toLowerCase());
  if (idx === -1) return text;
  return (
    <>
      {text.substring(0, idx)}
      <mark className="bg-yellow-200 rounded px-0.5">{text.substring(idx, idx + query.length)}</mark>
      {text.substring(idx + query.length)}
    </>
  );
}

// ─── Story data builder ─────────────────────────────────────────────

interface StoryView {
  storyId: string;
  storyMeta: any;
  paragraphs: any[];
  frames: { block: any; pageNumber: number; sectionIdx: number; blockIdx: number }[];
  tables: { block: any; pageNumber: number; sectionIdx: number; blockIdx: number }[];
}

function buildStoryViews(astDoc: any): {
  stories: StoryView[];
  standaloneTables: { block: any; pageNumber: number; sectionIdx: number; blockIdx: number }[];
  figures: { block: any; pageNumber: number; sectionIdx: number; blockIdx: number }[];
} {
  const storyMap = new Map<string, StoryView>();
  const standaloneTables: { block: any; pageNumber: number; sectionIdx: number; blockIdx: number }[] = [];
  const figures: { block: any; pageNumber: number; sectionIdx: number; blockIdx: number }[] = [];

  // Initialize from story metadata
  if (astDoc.stories) {
    for (const story of astDoc.stories) {
      storyMap.set(story.storyId, {
        storyId: story.storyId,
        storyMeta: story,
        paragraphs: [],
        frames: [],
        tables: [],
      });
    }
  }

  // Collect blocks from all sections
  if (astDoc.sections) {
    astDoc.sections.forEach((sec: any, si: number) => {
      const pageNum = sec.pageNumber || si + 1;
      if (sec.blocks) {
        sec.blocks.forEach((block: any, bi: number) => {
          const info = { block, pageNumber: pageNum, sectionIdx: si, blockIdx: bi };
          if (block.blockType === "TEXT_FRAME_BLOCK") {
            const sid = block.storyId || "";
            let sv = storyMap.get(sid);
            if (!sv) {
              sv = { storyId: sid, storyMeta: null, paragraphs: [], frames: [], tables: [] };
              storyMap.set(sid, sv);
            }
            sv.frames.push(info);
            // Collect paragraphs (avoid duplicates from linked frames)
            if (block.paragraphs?.length > 0 && sv.paragraphs.length === 0) {
              sv.paragraphs = block.paragraphs;
            }
          } else if (block.blockType === "TABLE") {
            // Check if this table belongs to a story (via parent text frame context)
            // Tables at block level are standalone
            standaloneTables.push(info);
          } else if (block.blockType === "FIGURE") {
            figures.push(info);
          }
        });
      }
    });
  }

  const stories = Array.from(storyMap.values()).filter(s => s.paragraphs.length > 0 || s.frames.length > 0);
  return { stories, standaloneTables, figures };
}

// ─── Tree View ──────────────────────────────────────────────────────

function TreeNode({
  node,
  path,
  label,
  icon,
  sub,
  badge,
}: {
  node: any;
  path: string;
  label: string;
  icon: string;
  sub?: string;
  badge?: string;
}) {
  const { selectedPath, selectPath, expandedPaths, toggleExpand, searchQuery, filterType } =
    useAstStore();
  const isSelected = selectedPath === path;
  const isExpanded = expandedPaths.has(path);
  const children = getChildren(node, path);
  const hasChildren = children.length > 0;
  const depth = path.split(".").length - 1;

  const isMatch = nodeMatchesSearch(node, label, sub, searchQuery);
  const isFilterMatch = nodeMatchesFilter(node, filterType);
  const dimmed = (searchQuery && !isMatch) || (filterType && !isFilterMatch);

  return (
    <div>
      <div
        className={`flex items-center gap-1 px-1 py-0.5 cursor-pointer text-xs hover:bg-blue-50 ${
          isSelected ? "bg-blue-100 text-blue-800" : ""
        } ${dimmed ? "opacity-30" : ""}`}
        style={{ paddingLeft: depth * 14 + 4 }}
        onClick={() => {
          selectPath(path);
          if (hasChildren) toggleExpand(path);
        }}
      >
        <span className="w-3 text-center text-gray-400 shrink-0">
          {hasChildren ? (isExpanded ? "▾" : "▸") : " "}
        </span>
        <span className="shrink-0 w-4 text-center">{icon}</span>
        <span className="truncate font-medium">
          {searchQuery ? highlightText(label, searchQuery) : label}
        </span>
        {badge && (
          <span className="shrink-0 px-1 py-0 rounded text-[9px] bg-gray-200 text-gray-600">
            {badge}
          </span>
        )}
        {sub && (
          <span className="text-gray-400 truncate ml-1 text-[10px]">
            {searchQuery ? highlightText(sub, searchQuery) : sub}
          </span>
        )}
      </div>
      {isExpanded && children}
    </div>
  );
}

function getChildren(node: any, path: string): React.ReactNode[] {
  if (!node || typeof node !== "object") return [];
  const items: React.ReactNode[] = [];

  // ── Document root: story-centric view ──
  if (node._docView) {
    const view = node._docView as ReturnType<typeof buildStoryViews>;
    const doc = node._doc;

    // Stories
    view.stories.forEach((sv, i) => {
      const p = `${path}.story[${i}]`;
      const paraCount = sv.paragraphs.length;
      const frameCount = sv.frames.length;
      const pageList = sv.storyMeta?.pages?.join(",") || sv.frames.map((f: any) => f.pageNumber).join(",");
      items.push(
        <TreeNode
          key={p}
          node={{ _storyView: sv, _doc: doc }}
          path={p}
          label={sv.storyId}
          icon="📖"
          badge={`${paraCount}¶ ${frameCount}F`}
          sub={pageList ? `p.${pageList}` : ""}
        />
      );
    });

    // Standalone tables
    if (view.standaloneTables.length > 0) {
      const gp = `${path}._tables`;
      items.push(
        <TreeNode
          key={gp}
          node={{ _tableList: view.standaloneTables }}
          path={gp}
          label="Tables"
          icon="#"
          sub={`${view.standaloneTables.length}`}
        />
      );
    }

    // Figures
    if (view.figures.length > 0) {
      const gp = `${path}._figures`;
      items.push(
        <TreeNode
          key={gp}
          node={{ _figureList: view.figures }}
          path={gp}
          label="Figures"
          icon="🖼"
          sub={`${view.figures.length}`}
        />
      );
    }

    // Metadata groups
    if (doc.fonts?.length > 0) {
      items.push(
        <TreeNode key={`${path}._fonts`}
          node={{ _group: "fonts", items: doc.fonts }} path={`${path}._fonts`}
          label="Fonts" icon="🔤" sub={`${doc.fonts.length}`} />
      );
    }
    if (doc.paragraphStyles?.length > 0) {
      items.push(
        <TreeNode key={`${path}._paragraphStyles`}
          node={{ _group: "paragraphStyles", items: doc.paragraphStyles }} path={`${path}._paragraphStyles`}
          label="Paragraph Styles" icon="📝" sub={`${doc.paragraphStyles.length}`} />
      );
    }
    if (doc.characterStyles?.length > 0) {
      items.push(
        <TreeNode key={`${path}._characterStyles`}
          node={{ _group: "characterStyles", items: doc.characterStyles }} path={`${path}._characterStyles`}
          label="Character Styles" icon="🅰" sub={`${doc.characterStyles.length}`} />
      );
    }
    if (doc.colors && Object.keys(doc.colors).length > 0) {
      items.push(
        <TreeNode key={`${path}._colors`}
          node={{ _group: "colors", colorMap: doc.colors }} path={`${path}._colors`}
          label="Colors" icon="🎨" sub={`${Object.keys(doc.colors).length}`} />
      );
    }
    if (doc.backgrounds?.length > 0) {
      items.push(
        <TreeNode key={`${path}._backgrounds`}
          node={{ _group: "backgrounds", items: doc.backgrounds }} path={`${path}._backgrounds`}
          label="Backgrounds" icon="🖼" sub={`${doc.backgrounds.length}`} />
      );
    }
    return items;
  }

  // ── Story view: paragraphs first, then linked frames ──
  if (node._storyView) {
    const sv = node._storyView as StoryView;

    // Paragraphs (content)
    sv.paragraphs.forEach((para: any, i: number) => {
      const p = `${path}.para[${i}]`;
      const preview = getParaPreview(para);
      const align = para.alignment ? para.alignment.charAt(0).toUpperCase() : "";
      const itemCounts = countItems(para);
      items.push(
        <TreeNode
          key={p}
          node={para}
          path={p}
          label={`P${i}`}
          icon="¶"
          badge={itemCounts}
          sub={align ? `${align} ${preview}` : preview}
        />
      );
    });

    // Linked frames (container info)
    if (sv.frames.length > 0) {
      const fp = `${path}._frames`;
      items.push(
        <TreeNode
          key={fp}
          node={{ _frameList: sv.frames }}
          path={fp}
          label="Frames"
          icon="📦"
          sub={`${sv.frames.length} linked`}
        />
      );
    }

    return items;
  }

  // ── Frame list ──
  if (node._frameList) {
    (node._frameList as any[]).forEach((fi: any, i: number) => {
      const block = fi.block;
      const p = `${path}.frame[${i}]`;
      const pos = block.x && block.y ? `(${hwpuToMm(block.x)},${hwpuToMm(block.y)})` : "";
      const dim = block.width && block.height ? dimMm(block.width, block.height) : "";
      const cols = block.columnCount > 1 ? ` ${block.columnCount}col` : "";
      items.push(
        <TreeNode
          key={p}
          node={block}
          path={p}
          label={`Frame p.${fi.pageNumber}`}
          icon="T"
          sub={`${block.sourceId || ""} ${dim} ${pos}${cols}`}
        />
      );
    });
    return items;
  }

  // ── Table list ──
  if (node._tableList) {
    (node._tableList as any[]).forEach((ti: any, i: number) => {
      const block = ti.block;
      const p = `${path}.table[${i}]`;
      items.push(
        <TreeNode
          key={p}
          node={{ ...block, blockType: "TABLE" }}
          path={p}
          label={`Table p.${ti.pageNumber}`}
          icon="#"
          sub={`${block.rowCount}×${block.colCount} ${block.width && block.height ? dimMm(block.width, block.height) : ""}`}
        />
      );
    });
    return items;
  }

  // ── Figure list ──
  if (node._figureList) {
    (node._figureList as any[]).forEach((fi: any, i: number) => {
      const block = fi.block;
      const p = `${path}.figure[${i}]`;
      items.push(
        <TreeNode
          key={p}
          node={block}
          path={p}
          label={`Figure p.${fi.pageNumber}`}
          icon="🖼"
          sub={`${block.kind || ""} ${block.width && block.height ? dimMm(block.width, block.height) : ""}`}
        />
      );
    });
    return items;
  }

  // ── Metadata groups ──
  if (node._group === "fonts") {
    node.items.forEach((font: any, i: number) => {
      const p = path.replace("._fonts", `.fonts[${i}]`);
      items.push(
        <TreeNode key={p} node={font} path={p}
          label={font.fontFamily || `font_${i}`} icon="🔤" sub={font.fontType || ""} />
      );
    });
  }
  if (node._group === "paragraphStyles") {
    node.items.forEach((style: any, i: number) => {
      const p = path.replace("._paragraphStyles", `.paragraphStyles[${i}]`);
      items.push(
        <TreeNode key={p} node={style} path={p}
          label={style.styleName || style.styleId || `style_${i}`} icon="📝"
          sub={style.basedOnStyleRef ? `← ${style.basedOnStyleRef}` : ""} />
      );
    });
  }
  if (node._group === "characterStyles") {
    node.items.forEach((style: any, i: number) => {
      const p = path.replace("._characterStyles", `.characterStyles[${i}]`);
      items.push(
        <TreeNode key={p} node={style} path={p}
          label={style.styleName || style.styleId || `style_${i}`} icon="🅰"
          sub={style.fontFamily || ""} />
      );
    });
  }
  if (node._group === "colors") {
    Object.entries(node.colorMap).forEach(([ref, hex]: [string, any]) => {
      const p = `${path}.${ref}`;
      items.push(
        <TreeNode key={p} node={{ colorRef: ref, hex }} path={p}
          label={ref} icon="🎨" sub={String(hex)} />
      );
    });
  }
  if (node._group === "backgrounds") {
    node.items.forEach((bg: any, i: number) => {
      const p = path.replace("._backgrounds", `.backgrounds[${i}]`);
      items.push(
        <TreeNode key={p} node={bg} path={p}
          label={`Page ${bg.pageNumber || i + 1}`} icon="🖼"
          sub={bg.pixelWidth && bg.pixelHeight ? `${bg.pixelWidth}×${bg.pixelHeight}px` : ""} />
      );
    });
  }

  // ── Paragraph → items ──
  if (node.items && !node._group) {
    node.items.forEach((item: any, i: number) => {
      const p = `${path}.items[${i}]`;
      const it = item.itemType;
      if (it === "TEXT_RUN") {
        const text = item.text?.length > 40
          ? item.text.substring(0, 40) + "…"
          : item.text || "";
        const fontInfo = item.fontFamily || "";
        const sizeInfo = item.fontSizeHwpunits ? `${(item.fontSizeHwpunits / 100).toFixed(1)}pt` : "";
        const colorInfo = item.textColor || "";
        const styleFlags = [
          item.fontStyle && item.fontStyle !== "normal" ? item.fontStyle : "",
          item.underline ? "U" : "",
          item.strikeThrough ? "S" : "",
          item.superscript ? "sup" : "",
          item.subscript ? "sub" : "",
          item.grepMathFont ? "math" : "",
        ].filter(Boolean).join(" ");
        items.push(
          <TreeNode key={p} node={item} path={p}
            label={`"${text}"`} icon="—"
            badge={styleFlags || undefined}
            sub={`${fontInfo} ${sizeInfo} ${colorInfo}`.trim()} />
        );
      } else if (it === "INLINE_OBJECT") {
        const kind = item.kind || "INLINE";
        let sub = "";
        if (kind === "INLINE_TEXT_FRAME") {
          sub = `${item.paragraphs?.length || 0}¶`;
        } else if (item.width && item.height) {
          sub = dimMm(item.width, item.height);
        }
        if (item.sourceId) sub = `${item.sourceId} ${sub}`;
        items.push(
          <TreeNode key={p} node={item} path={p}
            label={kind} icon="◆" badge={item.anchoredPosition || undefined}
            sub={sub.trim()} />
        );
      } else if (it === "BREAK") {
        items.push(
          <TreeNode key={p} node={item} path={p}
            label={item.breakType || "BREAK"} icon="↵" />
        );
      } else if (it === "EQUATION") {
        const script = item.hwpScript?.length > 40
          ? item.hwpScript.substring(0, 40) + "…"
          : item.hwpScript || "";
        items.push(
          <TreeNode key={p} node={item} path={p}
            label={script || "equation"} icon="∑"
            badge={item.sourceType || undefined} />
        );
      }
    });
  }

  // ── InlineObject → overlayFrames ──
  if (node.itemType === "INLINE_OBJECT" && node.overlayFrames?.length > 0) {
    node.overlayFrames.forEach((frame: any, i: number) => {
      const p = `${path}.overlayFrames[${i}]`;
      const kind = frame.kind || "OVERLAY";
      let sub = frame.sourceId || "";
      if (frame.width && frame.height) sub += ` ${dimMm(frame.width, frame.height)}`;
      items.push(
        <TreeNode key={p} node={frame} path={p}
          label={kind} icon="◇" sub={sub.trim()} />
      );
    });
  }

  // ── InlineObject → paragraphs (INLINE_TEXT_FRAME) ──
  if (node.itemType === "INLINE_OBJECT" && node.paragraphs?.length > 0) {
    node.paragraphs.forEach((para: any, i: number) => {
      const p = `${path}.paragraphs[${i}]`;
      const preview = getParaPreview(para);
      items.push(
        <TreeNode key={p} node={para} path={p}
          label={`P${i}`} icon="¶" sub={preview} />
      );
    });
  }

  // ── InlineObject → inlineTables ──
  if (node.itemType === "INLINE_OBJECT" && node.inlineTables?.length > 0) {
    node.inlineTables.forEach((tbl: any, i: number) => {
      const p = `${path}.inlineTables[${i}]`;
      items.push(
        <TreeNode key={p} node={{ ...tbl, blockType: "TABLE" }} path={p}
          label="Table" icon="#" sub={`${tbl.rowCount}×${tbl.colCount}`} />
      );
    });
  }

  // ── Table → rows ──
  if (node.blockType === "TABLE" && node.rows) {
    node.rows.forEach((row: any, i: number) => {
      const p = `${path}.rows[${i}]`;
      items.push(
        <TreeNode key={p} node={row} path={p}
          label={`Row ${row.rowIndex ?? i}`} icon="─"
          sub={row.rowHeight ? `h=${hwpuToMm(row.rowHeight)}mm` : ""} />
      );
    });
  }

  // ── TableRow → cells ──
  if (node.cells) {
    node.cells.forEach((cell: any, i: number) => {
      const p = `${path}.cells[${i}]`;
      const spanInfo = (cell.rowSpan > 1 || cell.columnSpan > 1)
        ? `span ${cell.rowSpan}×${cell.columnSpan}`
        : undefined;
      items.push(
        <TreeNode key={p} node={cell} path={p}
          label={`Cell(${cell.rowIndex},${cell.columnIndex})`} icon="□"
          sub={spanInfo} />
      );
    });
  }

  // ── TableCell → paragraphs ──
  if (node.cells === undefined && node.paragraphs && !node.blockType && !node.itemType && !node._group && !node._storyView) {
    node.paragraphs.forEach((para: any, i: number) => {
      const p = `${path}.paragraphs[${i}]`;
      const preview = getParaPreview(para);
      items.push(
        <TreeNode key={p} node={para} path={p}
          label={`P${i}`} icon="¶" sub={preview} />
      );
    });
  }

  // TextFrameBlock as linked frame → container info only (no paragraphs, those are in Story)

  return items;
}

function getParaPreview(para: any): string {
  if (!para.items) return "";
  const parts: string[] = [];
  for (const item of para.items) {
    if (item.itemType === "TEXT_RUN" && item.text) {
      parts.push(item.text);
    } else if (item.itemType === "EQUATION") {
      parts.push("[EQ]");
    } else if (item.itemType === "INLINE_OBJECT") {
      parts.push(`[${item.kind || "OBJ"}]`);
    } else if (item.itemType === "BREAK") {
      parts.push(`[${item.breakType || "BR"}]`);
    }
  }
  const joined = parts.join("");
  return joined.length > 50 ? joined.substring(0, 50) + "…" : joined;
}

function countItems(para: any): string {
  if (!para.items) return "";
  let t = 0, eq = 0, obj = 0;
  for (const item of para.items) {
    if (item.itemType === "TEXT_RUN") t++;
    else if (item.itemType === "EQUATION") eq++;
    else if (item.itemType === "INLINE_OBJECT") obj++;
  }
  const parts: string[] = [];
  if (t > 0) parts.push(`${t}T`);
  if (eq > 0) parts.push(`${eq}EQ`);
  if (obj > 0) parts.push(`${obj}OBJ`);
  return parts.join(" ");
}

// ─── Main Panel ─────────────────────────────────────────────────────

export function ASTTreePanel() {
  const {
    astDoc,
    isLoading,
    error,
    selectedPath,
    searchQuery,
    setSearchQuery,
    filterType,
    setFilterType,
    expandAll,
    collapseAll,
  } = useAstStore();

  const docView = useMemo(() => {
    if (!astDoc) return null;
    return buildStoryViews(astDoc);
  }, [astDoc]);

  const sectionCount = astDoc?.sections?.length || 0;
  const storyCount = docView?.stories.length || 0;

  if (!astDoc) {
    return (
      <div className="flex items-center justify-center h-full text-gray-400 text-sm">
        {isLoading ? (
          <div className="text-center">
            <div className="mb-2">AST 로딩 중...</div>
            <div className="text-xs">IDML 정규화 진행 중</div>
          </div>
        ) : error ? (
          <div className="text-center text-red-400">
            <div className="mb-1">AST 로드 실패</div>
            <div className="text-xs">{error}</div>
          </div>
        ) : (
          "인디자인 문서 (INDD) 또는 폴더를 열어주세요"
        )}
      </div>
    );
  }

  return (
    <div className="h-full flex flex-col min-h-0">
      {/* Header */}
      <div className="flex items-center gap-2 px-2 py-1 border-b bg-gray-50 shrink-0 text-xs">
        <span className="font-medium text-gray-700">
          {storyCount} stories
        </span>
        <span className="text-gray-400">|</span>
        <span className="text-gray-500">
          {sectionCount}p {astDoc.fonts?.length || 0}F {astDoc.paragraphStyles?.length || 0}PS
        </span>
      </div>

      {/* Toolbar */}
      <div className="flex items-center gap-1.5 px-2 py-1 border-b bg-gray-50 shrink-0 text-xs">
        <button
          onClick={expandAll}
          className="px-1.5 py-0.5 border rounded hover:bg-gray-100 text-gray-600"
          title="전체 펼침"
        >
          펼침
        </button>
        <button
          onClick={collapseAll}
          className="px-1.5 py-0.5 border rounded hover:bg-gray-100 text-gray-600"
          title="전체 접기"
        >
          접기
        </button>
        <div className="flex-1 relative">
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="검색..."
            className="w-full border rounded px-2 py-0.5 text-xs pl-5"
          />
          <span className="absolute left-1.5 top-1/2 -translate-y-1/2 text-gray-400 text-[10px]">🔍</span>
          {searchQuery && (
            <button
              onClick={() => setSearchQuery("")}
              className="absolute right-1.5 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600 text-[10px]"
            >
              ✕
            </button>
          )}
        </div>
        <select
          value={filterType || ""}
          onChange={(e) => setFilterType(e.target.value || null)}
          className="border rounded px-1 py-0.5 text-xs"
        >
          <option value="">All</option>
          <option value="TEXT_RUN">TEXT_RUN</option>
          <option value="EQUATION">EQUATION</option>
          <option value="INLINE_OBJECT">INLINE_OBJ</option>
          <option value="FIGURE">FIGURE</option>
          <option value="TABLE">TABLE</option>
        </select>
      </div>

      {/* Tree */}
      <div className="flex-1 overflow-auto min-h-0">
        <TreeNode
          node={{ _docView: docView, _doc: astDoc }}
          path="root"
          label="Document"
          icon="📄"
          sub={`${sectionCount}p ${storyCount} stories`}
        />
      </div>
    </div>
  );
}
