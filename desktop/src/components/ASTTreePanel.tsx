import { useEffect, useRef, useCallback, useMemo } from "react";
import { useAstStore } from "../stores/useAstStore";

// ─── Tree View ──────────────────────────────────────────────────────

function TreeNode({
  node,
  path,
  label,
  icon,
  sub,
}: {
  node: any;
  path: string;
  label: string;
  icon: string;
  sub?: string;
}) {
  const { selectedPath, selectPath, expandedPaths, toggleExpand } =
    useAstStore();
  const isSelected = selectedPath === path;
  const isExpanded = expandedPaths.has(path);
  const children = getChildren(node, path);
  const hasChildren = children.length > 0;
  const depth = path.split(".").length - 1;

  return (
    <div>
      <div
        className={`flex items-center gap-1 px-1 py-0.5 cursor-pointer text-xs hover:bg-blue-50 ${
          isSelected ? "bg-blue-100 text-blue-800" : ""
        }`}
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
        <span className="truncate font-medium">{label}</span>
        {sub && (
          <span className="text-gray-400 truncate ml-1 text-[10px]">
            {sub}
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

  // Document level
  if (node.sections) {
    node.sections.forEach((sec: any, i: number) => {
      const p = `${path}.sections[${i}]`;
      items.push(
        <TreeNode
          key={p}
          node={sec}
          path={p}
          label={`Page ${sec.pageNumber || i + 1}`}
          icon="📃"
          sub={`${sec.blocks?.length || 0} blocks`}
        />
      );
    });
  }

  // Section level → blocks
  if (node.blocks) {
    node.blocks.forEach((block: any, i: number) => {
      const p = `${path}.blocks[${i}]`;
      const bt = block.blockType;
      if (bt === "TEXT_FRAME_BLOCK") {
        items.push(
          <TreeNode
            key={p}
            node={block}
            path={p}
            label="TextFrame"
            icon="T"
            sub={`${block.sourceId} ${block.paragraphs?.length || 0}¶`}
          />
        );
      } else if (bt === "TABLE") {
        items.push(
          <TreeNode
            key={p}
            node={block}
            path={p}
            label="Table"
            icon="#"
            sub={`${block.rowCount}×${block.colCount}`}
          />
        );
      } else if (bt === "FIGURE") {
        items.push(
          <TreeNode
            key={p}
            node={block}
            path={p}
            label={`Figure ${block.kind || ""}`}
            icon="🖼"
            sub={`${block.sourceId}`}
          />
        );
      }
    });
  }

  // TextFrameBlock → paragraphs
  if (node.blockType === "TEXT_FRAME_BLOCK" && node.paragraphs) {
    node.paragraphs.forEach((para: any, i: number) => {
      const p = `${path}.paragraphs[${i}]`;
      const preview = getParaPreview(para);
      items.push(
        <TreeNode
          key={p}
          node={para}
          path={p}
          label={`¶ ${i}`}
          icon="¶"
          sub={preview}
        />
      );
    });
  }

  // Table → rows
  if (node.blockType === "TABLE" && node.rows) {
    node.rows.forEach((row: any, i: number) => {
      const p = `${path}.rows[${i}]`;
      items.push(
        <TreeNode
          key={p}
          node={row}
          path={p}
          label={`Row ${row.rowIndex}`}
          icon="─"
          sub={`h=${row.rowHeight}`}
        />
      );
    });
  }

  // TableRow → cells
  if (node.cells) {
    node.cells.forEach((cell: any, i: number) => {
      const p = `${path}.cells[${i}]`;
      items.push(
        <TreeNode
          key={p}
          node={cell}
          path={p}
          label={`Cell(${cell.rowIndex},${cell.columnIndex})`}
          icon="□"
          sub={
            cell.rowSpan > 1 || cell.columnSpan > 1
              ? `span ${cell.rowSpan}×${cell.columnSpan}`
              : undefined
          }
        />
      );
    });
  }

  // TableCell → paragraphs
  if (node.cells === undefined && node.paragraphs && !node.blockType) {
    node.paragraphs.forEach((para: any, i: number) => {
      const p = `${path}.paragraphs[${i}]`;
      const preview = getParaPreview(para);
      items.push(
        <TreeNode
          key={p}
          node={para}
          path={p}
          label={`¶ ${i}`}
          icon="¶"
          sub={preview}
        />
      );
    });
  }

  // Paragraph → items
  if (node.items) {
    node.items.forEach((item: any, i: number) => {
      const p = `${path}.items[${i}]`;
      const it = item.itemType;
      if (it === "TEXT_RUN") {
        const text =
          item.text?.length > 30
            ? item.text.substring(0, 30) + "…"
            : item.text || "";
        items.push(
          <TreeNode
            key={p}
            node={item}
            path={p}
            label={`"${text}"`}
            icon="—"
            sub={`${item.fontFamily || ""} ${item.fontSizeHwpunits ? item.fontSizeHwpunits / 100 + "pt" : ""}`}
          />
        );
      } else if (it === "INLINE_OBJECT") {
        items.push(
          <TreeNode
            key={p}
            node={item}
            path={p}
            label={item.kind || "INLINE"}
            icon="◆"
            sub={`${item.sourceId || ""}`}
          />
        );
      } else if (it === "BREAK") {
        items.push(
          <TreeNode
            key={p}
            node={item}
            path={p}
            label={item.breakType || "BREAK"}
            icon="↵"
          />
        );
      }
    });
  }

  return items;
}

function getParaPreview(para: any): string {
  if (!para.items) return "";
  const texts: string[] = [];
  for (const item of para.items) {
    if (item.itemType === "TEXT_RUN" && item.text) {
      texts.push(item.text);
    }
  }
  const joined = texts.join("");
  return joined.length > 40 ? joined.substring(0, 40) + "…" : joined;
}

// ─── Page Canvas ────────────────────────────────────────────────────

function PageCanvas({
  section,
  selectedPath,
  onSelectBlock,
}: {
  section: any;
  selectedPath: string | null;
  onSelectBlock: (path: string) => void;
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  const draw = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas || !section?.layout) return;

    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const layout = section.layout;
    const pw = layout.pageWidth;
    const ph = layout.pageHeight;

    const maxW = canvas.parentElement?.clientWidth || 400;
    const maxH = canvas.parentElement?.clientHeight || 300;
    const scale = Math.min((maxW - 20) / (pw / 100), (maxH - 20) / (ph / 100));

    canvas.width = maxW;
    canvas.height = maxH;

    const toX = (v: number) => 10 + (v / 100) * scale;
    const toY = (v: number) => 10 + (v / 100) * scale;
    const toW = (v: number) => (v / 100) * scale;

    ctx.fillStyle = "#f8f8f8";
    ctx.fillRect(0, 0, canvas.width, canvas.height);

    // Page border
    ctx.strokeStyle = "#999";
    ctx.lineWidth = 1;
    ctx.strokeRect(toX(0), toY(0), toW(pw), toW(ph));
    ctx.fillStyle = "#fff";
    ctx.fillRect(toX(0), toY(0), toW(pw), toW(ph));

    // Margins (dashed)
    ctx.setLineDash([3, 3]);
    ctx.strokeStyle = "#ccc";
    ctx.strokeRect(
      toX(layout.marginLeft),
      toY(layout.marginTop),
      toW(pw - layout.marginLeft - layout.marginRight),
      toW(ph - layout.marginTop - layout.marginBottom)
    );
    ctx.setLineDash([]);

    // Blocks
    if (section.blocks) {
      section.blocks.forEach((block: any, i: number) => {
        const bx = toX(block.x || 0);
        const by = toY(block.y || 0);
        const bw = toW(block.width || 0);
        const bh = toW(block.height || 0);

        const blockPath = `root.sections[${section._idx}].blocks[${i}]`;
        const isSel = selectedPath?.startsWith(blockPath);

        const bt = block.blockType;
        if (bt === "TEXT_FRAME_BLOCK") {
          ctx.strokeStyle = isSel ? "#2563eb" : "#93c5fd";
          ctx.lineWidth = isSel ? 2 : 1;
          ctx.strokeRect(bx, by, bw, bh);
          ctx.fillStyle = "#93c5fd";
          ctx.font = "9px sans-serif";
          ctx.fillText(`T ${block.sourceId || ""}`, bx + 2, by + 10);
        } else if (bt === "TABLE") {
          ctx.strokeStyle = isSel ? "#d97706" : "#fbbf24";
          ctx.lineWidth = isSel ? 2 : 1;
          ctx.strokeRect(bx, by, bw, bh);
          ctx.fillStyle = "#fbbf24";
          ctx.font = "9px sans-serif";
          ctx.fillText(
            `Table ${block.rowCount}×${block.colCount}`,
            bx + 2,
            by + 10
          );
        } else if (bt === "FIGURE") {
          ctx.strokeStyle = isSel ? "#059669" : "#6ee7b7";
          ctx.lineWidth = isSel ? 2 : 1;
          ctx.strokeRect(bx, by, bw, bh);
          ctx.fillStyle = "#6ee7b7";
          ctx.font = "9px sans-serif";
          ctx.fillText(`Fig ${block.kind || ""}`, bx + 2, by + 10);
        }
      });
    }
  }, [section, selectedPath]);

  useEffect(() => {
    draw();
  }, [draw]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas?.parentElement) return;
    const ro = new ResizeObserver(() => draw());
    ro.observe(canvas.parentElement);
    return () => ro.disconnect();
  }, [draw]);

  const handleClick = (e: React.MouseEvent<HTMLCanvasElement>) => {
    if (!section?.layout || !section.blocks) return;
    const canvas = canvasRef.current;
    if (!canvas) return;

    const rect = canvas.getBoundingClientRect();
    const mx = e.clientX - rect.left;
    const my = e.clientY - rect.top;

    const layout = section.layout;
    const pw = layout.pageWidth;
    const ph = layout.pageHeight;
    const maxW = canvas.width;
    const maxH = canvas.height;
    const scale = Math.min((maxW - 20) / (pw / 100), (maxH - 20) / (ph / 100));
    const toX = (v: number) => 10 + (v / 100) * scale;
    const toY = (v: number) => 10 + (v / 100) * scale;
    const toW = (v: number) => (v / 100) * scale;

    for (let i = section.blocks.length - 1; i >= 0; i--) {
      const b = section.blocks[i];
      const bx = toX(b.x || 0);
      const by = toY(b.y || 0);
      const bw = toW(b.width || 0);
      const bh = toW(b.height || 0);
      if (mx >= bx && mx <= bx + bw && my >= by && my <= by + bh) {
        onSelectBlock(`root.sections[${section._idx}].blocks[${i}]`);
        return;
      }
    }
  };

  return (
    <canvas
      ref={canvasRef}
      className="w-full h-full"
      onClick={handleClick}
    />
  );
}

// ─── Main Panel ─────────────────────────────────────────────────────

export function ASTTreePanel() {
  const {
    astDoc,
    isLoading,
    error,
    selectedPath,
    selectPath,
    currentSectionIndex,
    setSection,
  } = useAstStore();

  const currentSection = useMemo(() => {
    if (!astDoc?.sections?.[currentSectionIndex]) return null;
    return { ...astDoc.sections[currentSectionIndex], _idx: currentSectionIndex };
  }, [astDoc, currentSectionIndex]);

  const sectionCount = astDoc?.sections?.length || 0;

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
          "IDML 파일을 열어주세요"
        )}
      </div>
    );
  }

  return (
    <div className="h-full flex flex-col min-h-0">
      {/* Header: page selector + summary */}
      <div className="flex items-center gap-2 px-2 py-1 border-b bg-gray-50 shrink-0 text-xs">
        {sectionCount > 0 && (
          <>
            <span className="text-gray-500">Page:</span>
            <select
              value={currentSectionIndex}
              onChange={(e) => setSection(parseInt(e.target.value))}
              className="border rounded px-1 py-0.5 text-xs"
            >
              {astDoc.sections.map((sec: any, i: number) => (
                <option key={i} value={i}>
                  {sec.pageNumber || i + 1}
                </option>
              ))}
            </select>
            <span className="text-gray-400">
              ({sectionCount}p)
            </span>
          </>
        )}
        <span className="text-gray-400 ml-auto">
          {astDoc.fonts?.length || 0}F {astDoc.paragraphStyles?.length || 0}PS
        </span>
      </div>

      {/* Canvas: 40% height */}
      <div className="h-[40%] border-b overflow-hidden bg-gray-50 shrink-0">
        {currentSection ? (
          <PageCanvas
            section={currentSection}
            selectedPath={selectedPath}
            onSelectBlock={(path) => selectPath(path)}
          />
        ) : (
          <div className="flex items-center justify-center h-full text-gray-400 text-xs">
            No section
          </div>
        )}
      </div>

      {/* Tree: 60% height */}
      <div className="flex-1 overflow-auto min-h-0">
        <TreeNode
          node={astDoc}
          path="root"
          label="Document"
          icon="📄"
          sub={`${sectionCount} sections`}
        />
      </div>
    </div>
  );
}
