import { useMemo, useState } from "react";
import { useAstStore } from "../stores/useAstStore";

// ─── Helpers ────────────────────────────────────────────────────────

function hwpuToMm(v: number): string {
  return (v / 7200 * 25.4).toFixed(1);
}

function hwpuToPt(v: number): string {
  return (v / 100).toFixed(1);
}

const HWPUNIT_KEYS = new Set([
  "x", "y", "width", "height",
  "pageWidth", "pageHeight",
  "marginTop", "marginBottom", "marginLeft", "marginRight",
  "insetTop", "insetBottom", "insetLeft", "insetRight",
  "spaceBefore", "spaceAfter", "firstLineIndent", "leftMargin", "rightMargin",
  "textWrapTop", "textWrapLeft", "textWrapBottom", "textWrapRight",
  "textMarginTop", "textMarginLeft", "textMarginBottom", "textMarginRight",
  "overlayX", "overlayY", "overlayParentWidth", "overlayParentHeight",
  "containerWidth", "containerHeight", "imageOffsetX", "imageOffsetY",
  "columnGutter", "rowHeight", "borderWidth",
  "position",
  "shadingLeftOffset", "shadingRightOffset", "shadingTopOffset", "shadingBottomOffset",
]);

const COLOR_KEYS = new Set([
  "fillColor", "textColor", "strokeColor", "borderColor", "shadingColor", "color", "hex",
]);

const CHILD_ARRAY_KEYS = new Set([
  "paragraphs", "items", "blocks", "rows", "cells", "sections",
  "overlayFrames", "inlineTables",
  "stories", "fonts", "paragraphStyles", "characterStyles", "backgrounds",
  "columnWidths", "tabStops", "linkedFrameIds", "pages",
]);

// ─── Resolve path to node ───────────────────────────────────────────

function resolveNode(doc: any, path: string): any {
  if (!doc || !path) return null;
  const parts = path.replace(/^root\.?/, "").split(".");
  let current = doc;
  for (const part of parts) {
    if (!part || !current) break;
    // Handle virtual group paths like _stories, _fonts, etc.
    if (part.startsWith("_")) {
      const realKey = part.substring(1);
      if (current[realKey]) {
        current = { _group: realKey, items: current[realKey] };
        if (realKey === "colors") {
          current = { _group: "colors", colorMap: doc.colors };
        }
      } else {
        return null;
      }
      continue;
    }
    const match = part.match(/^(\w+)\[(\d+)\]$/);
    if (match) {
      current = current[match[1]]?.[parseInt(match[2])];
    } else {
      current = current[part];
    }
  }
  return current;
}

// ─── Breadcrumb ─────────────────────────────────────────────────────

function Breadcrumb({ path }: { path: string }) {
  const { selectPath } = useAstStore();
  if (!path) return null;

  const segments: { label: string; fullPath: string }[] = [];
  const parts = path.split(".");
  let accum = "";
  for (const part of parts) {
    accum = accum ? `${accum}.${part}` : part;
    // Prettify labels
    let label = part;
    const arrMatch = part.match(/^(\w+)\[(\d+)\]$/);
    if (arrMatch) {
      label = `${arrMatch[1]}[${arrMatch[2]}]`;
    }
    if (part === "root") label = "doc";
    segments.push({ label, fullPath: accum });
  }

  return (
    <div className="flex items-center gap-0.5 flex-wrap">
      {segments.map((seg, i) => (
        <span key={seg.fullPath} className="flex items-center gap-0.5">
          {i > 0 && <span className="text-gray-300">›</span>}
          <button
            onClick={() => selectPath(seg.fullPath)}
            className="text-blue-500 hover:text-blue-700 hover:underline"
          >
            {seg.label}
          </button>
        </span>
      ))}
    </div>
  );
}

// ─── Value Renderer ─────────────────────────────────────────────────

function ColorSwatch({ color }: { color: string }) {
  return (
    <span
      className="inline-block w-3 h-3 rounded-sm border border-gray-300 align-middle ml-1"
      style={{ backgroundColor: color }}
    />
  );
}

function renderValue(key: string, value: any): React.ReactNode {
  if (value === null || value === undefined) {
    return <span className="text-gray-300">null</span>;
  }
  if (typeof value === "boolean") {
    return (
      <span className={value ? "text-green-600" : "text-red-400"}>
        {String(value)}
      </span>
    );
  }
  if (typeof value === "number") {
    // HWPUNIT → mm conversion
    if (HWPUNIT_KEYS.has(key) && value !== 0) {
      if (key === "fontSizeHwpunits") {
        return (
          <span>
            <span className="text-blue-600">{value}</span>
            <span className="text-gray-400 ml-1">({hwpuToPt(value)}pt)</span>
          </span>
        );
      }
      return (
        <span>
          <span className="text-blue-600">{value}</span>
          <span className="text-gray-400 ml-1">({hwpuToMm(value)}mm)</span>
        </span>
      );
    }
    return <span className="text-blue-600">{value}</span>;
  }
  if (typeof value === "string") {
    // fontSizeHwpunits as key
    if (key === "fontSizeHwpunits") {
      const num = parseInt(value);
      if (!isNaN(num)) {
        return (
          <span>
            <span className="text-amber-700">"{value}"</span>
            <span className="text-gray-400 ml-1">({hwpuToPt(num)}pt)</span>
          </span>
        );
      }
    }
    // Color swatch
    if (COLOR_KEYS.has(key) && value.startsWith("#")) {
      return (
        <span>
          <span className="text-amber-700">"{value}"</span>
          <ColorSwatch color={value} />
        </span>
      );
    }
    return <span className="text-amber-700">"{value}"</span>;
  }
  if (Array.isArray(value)) {
    if (value.length === 0) return <span className="text-gray-300">[]</span>;
    return <span className="text-gray-500">[{value.length} items]</span>;
  }
  if (typeof value === "object") {
    const keys = Object.keys(value);
    return (
      <span className="text-gray-500">{`{${keys.slice(0, 3).join(", ")}${keys.length > 3 ? "…" : ""}}`}</span>
    );
  }
  return String(value);
}

// ─── Expandable Value ───────────────────────────────────────────────

function ExpandableValue({ keyName, value }: { keyName: string; value: any }) {
  const [expanded, setExpanded] = useState(false);

  if (Array.isArray(value) && value.length > 0) {
    return (
      <div>
        <button
          onClick={() => setExpanded(!expanded)}
          className="text-gray-500 hover:text-gray-700"
        >
          {expanded ? "▾" : "▸"} [{value.length} items]
        </button>
        {expanded && (
          <div className="ml-3 mt-0.5 border-l border-gray-200 pl-2">
            {value.map((item, i) => (
              <div key={i} className="py-0.5">
                {typeof item === "object" && item !== null ? (
                  <ExpandableObject obj={item} />
                ) : (
                  <span>
                    <span className="text-gray-400">[{i}]</span>{" "}
                    {renderValue(keyName, item)}
                  </span>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    );
  }

  if (typeof value === "object" && value !== null && !Array.isArray(value)) {
    return (
      <div>
        <button
          onClick={() => setExpanded(!expanded)}
          className="text-gray-500 hover:text-gray-700"
        >
          {expanded ? "▾" : "▸"} {`{${Object.keys(value).slice(0, 3).join(", ")}${Object.keys(value).length > 3 ? "…" : ""}}`}
        </button>
        {expanded && (
          <div className="ml-3 mt-0.5 border-l border-gray-200 pl-2">
            <ExpandableObject obj={value} />
          </div>
        )}
      </div>
    );
  }

  return <>{renderValue(keyName, value)}</>;
}

function ExpandableObject({ obj }: { obj: any }) {
  return (
    <table className="w-full">
      <tbody>
        {Object.entries(obj).map(([k, v]) => (
          <tr key={k} className="border-b border-gray-50">
            <td className="py-0.5 pr-2 font-medium text-gray-500 whitespace-nowrap align-top text-[10px]">
              {k}
            </td>
            <td className="py-0.5 text-gray-800 break-all text-[10px]">
              {renderValue(k, v)}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

// ─── Preview Sections ───────────────────────────────────────────────

function TextRunPreview({ node }: { node: any }) {
  return (
    <div className="px-3 py-2 bg-gray-50 border-b text-xs">
      <div className="text-[10px] text-gray-400 mb-1">미리보기</div>
      <div className="font-medium text-gray-800">
        "{node.text}"
      </div>
      <div className="text-gray-500 mt-0.5">
        {node.fontFamily || ""} {node.fontSizeHwpunits ? `${(node.fontSizeHwpunits / 100).toFixed(1)}pt` : ""}
        {node.fontStyle && node.fontStyle !== "normal" ? ` ${node.fontStyle}` : ""}
        {node.textColor && (
          <span className="ml-1">
            {node.textColor}
            <ColorSwatch color={node.textColor} />
          </span>
        )}
        {node.underline && " underline"}
        {node.strikeThrough && " strikethrough"}
        {node.superscript && " sup"}
        {node.subscript && " sub"}
        {node.grepMathFont && <span className="text-purple-500 ml-1">[GREP Math]</span>}
      </div>
    </div>
  );
}

function EquationPreview({ node }: { node: any }) {
  return (
    <div className="px-3 py-2 bg-gray-50 border-b text-xs">
      <div className="text-[10px] text-gray-400 mb-1">HWP Script</div>
      <pre className="font-mono text-sm bg-gray-100 rounded p-2 text-gray-800 whitespace-pre-wrap break-all">
        {node.hwpScript || "(empty)"}
      </pre>
      {node.sourceType && (
        <div className="text-gray-500 mt-1">
          source: {node.sourceType}
        </div>
      )}
    </div>
  );
}

function ParagraphPreview({ node }: { node: any }) {
  if (!node.items?.length) return null;
  return (
    <div className="px-3 py-2 bg-gray-50 border-b text-xs">
      <div className="text-[10px] text-gray-400 mb-1">
        미리보기 {node.alignment && `[${node.alignment}]`}
      </div>
      <div className="space-y-0.5">
        {node.items.map((item: any, i: number) => {
          if (item.itemType === "TEXT_RUN") {
            const text = item.text?.length > 60 ? item.text.substring(0, 60) + "…" : item.text || "";
            return (
              <div key={i} className="text-gray-800">
                <span className="text-gray-400 mr-1 text-[10px]">{item.fontFamily || "T"}</span>
                "{text}"
              </div>
            );
          }
          if (item.itemType === "EQUATION") {
            return (
              <div key={i} className="text-purple-700 font-mono text-[10px]">
                <span className="text-purple-400 mr-1">[EQ]</span>
                {item.hwpScript?.substring(0, 50) || ""}
              </div>
            );
          }
          if (item.itemType === "INLINE_OBJECT") {
            return (
              <div key={i} className="text-green-700 text-[10px]">
                <span className="text-green-400 mr-1">[{item.kind || "OBJ"}]</span>
                {item.sourceId || ""} {item.width && item.height ? `${(item.width / 7200 * 25.4).toFixed(1)}×${(item.height / 7200 * 25.4).toFixed(1)}mm` : ""}
              </div>
            );
          }
          if (item.itemType === "BREAK") {
            return (
              <div key={i} className="text-gray-400 text-[10px]">
                [{item.breakType || "BREAK"}]
              </div>
            );
          }
          return null;
        })}
      </div>
    </div>
  );
}

function StylePreview({ node, doc }: { node: any; doc: any }) {
  if (!node.basedOnStyleRef && !node.styleId) return null;

  // Trace inheritance chain
  const chain: string[] = [];
  let current = node;
  const visited = new Set<string>();
  while (current) {
    const name = current.styleName || current.styleId || "?";
    if (visited.has(name)) break;
    visited.add(name);
    chain.push(name);
    if (!current.basedOnStyleRef) break;
    // Find parent in doc
    const allStyles = [...(doc?.paragraphStyles || []), ...(doc?.characterStyles || [])];
    current = allStyles.find((s: any) => s.styleId === current.basedOnStyleRef);
  }

  if (chain.length <= 1) return null;

  return (
    <div className="px-3 py-2 bg-gray-50 border-b text-xs">
      <div className="text-[10px] text-gray-400 mb-1">스타일 상속 체인</div>
      <div className="text-gray-800 font-medium">
        {chain.join(" → ")}
      </div>
    </div>
  );
}

function ColorGroupPreview({ node }: { node: any }) {
  if (!node?.colorMap) return null;
  const entries = Object.entries(node.colorMap);
  return (
    <div className="px-3 py-2 bg-gray-50 border-b text-xs">
      <div className="text-[10px] text-gray-400 mb-1">색상 팔레트</div>
      <div className="flex flex-wrap gap-1">
        {entries.map(([ref, hex]: [string, any]) => (
          <div
            key={ref}
            className="flex items-center gap-1 px-1.5 py-0.5 bg-white border rounded"
            title={`${ref}: ${hex}`}
          >
            <span
              className="inline-block w-3 h-3 rounded-sm border border-gray-200"
              style={{ backgroundColor: String(hex) }}
            />
            <span className="text-[10px] text-gray-600">{String(hex)}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

// ─── Story Content View ─────────────────────────────────────────────

function StoryContentView({ storyView, doc }: { storyView: any; doc: any }) {
  const sv = storyView as { storyId: string; storyMeta: any; paragraphs: any[]; frames: any[] };

  return (
    <div className="text-xs overflow-auto h-full">
      {/* Story header */}
      <div className="px-3 py-2 bg-gray-50 border-b">
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium text-gray-700">📖 {sv.storyId}</span>
          <span className="text-[10px] text-gray-400">
            {sv.paragraphs.length}¶ · {sv.frames.length} frames
          </span>
        </div>
        {sv.storyMeta?.pages && (
          <div className="text-[10px] text-gray-400 mt-0.5">
            pages: {sv.storyMeta.pages.join(", ")}
          </div>
        )}
      </div>

      {/* Paragraphs as annotated content */}
      <div className="p-2 space-y-1">
        {sv.paragraphs.map((para: any, pi: number) => (
          <StoryParagraph key={pi} para={para} index={pi} doc={doc} />
        ))}
      </div>
    </div>
  );
}

function StoryParagraph({ para, index, doc }: { para: any; index: number; doc: any }) {
  const align = para.alignment || "";
  const styleName = para.paragraphStyleName || para.paragraphStyleRef || "";

  return (
    <div className="border rounded bg-white">
      {/* Paragraph header */}
      <div className="flex items-center gap-1.5 px-2 py-0.5 bg-gray-50 border-b text-[10px] text-gray-500">
        <span className="font-mono font-medium text-gray-600">¶{index}</span>
        {align && <span className="px-1 py-0 rounded bg-blue-50 text-blue-600">{align}</span>}
        {styleName && <span className="px-1 py-0 rounded bg-purple-50 text-purple-600">{styleName}</span>}
        {para.spaceBefore > 0 && (
          <span className="text-gray-400" title="spaceBefore">↕{hwpuToMm(para.spaceBefore)}mm</span>
        )}
        {para.firstLineIndent > 0 && (
          <span className="text-gray-400" title="firstLineIndent">⇥{hwpuToMm(para.firstLineIndent)}mm</span>
        )}
      </div>

      {/* Items — inline content with markup annotations */}
      <div className="px-2 py-1.5 leading-relaxed">
        {para.items?.length > 0 ? (
          para.items.map((item: any, ii: number) => (
            <StoryItem key={ii} item={item} doc={doc} />
          ))
        ) : (
          <span className="text-gray-300 italic">(empty)</span>
        )}
      </div>
    </div>
  );
}

function buildStyleTag(item: any): string {
  const parts: string[] = [];
  if (item.fontFamily) parts.push(item.fontFamily);
  if (item.fontSizeHwpunits) parts.push(`${(item.fontSizeHwpunits / 100).toFixed(1)}pt`);
  if (item.fontStyle && item.fontStyle !== "normal") parts.push(item.fontStyle);
  if (item.underline) parts.push("U");
  if (item.strikeThrough) parts.push("S̶");
  if (item.superscript) parts.push("sup");
  if (item.subscript) parts.push("sub");
  if (item.textColor) parts.push(item.textColor);
  if (item.grepMathFont) parts.push("GREP-Math");
  if (item.characterStyleName || item.characterStyleRef) {
    const cs = item.characterStyleName || item.characterStyleRef;
    if (cs !== "[None]") parts.push(`[${cs}]`);
  }
  return parts.join(" ");
}

function StoryItem({ item, doc }: { item: any; doc: any }) {
  if (item.itemType === "TEXT_RUN") {
    const styleTag = buildStyleTag(item);
    const hasStyle = styleTag.length > 0;
    return (
      <span className="inline">
        {hasStyle && (
          <span className="text-[9px] font-mono text-teal-600 align-top">
            &lt;{styleTag}&gt;
          </span>
        )}
        <span
          className="text-gray-800"
          style={{
            fontWeight: (item.fontStyle === "bold" || item.fontStyle === "Bold" || item.fontStyle === "bold-italic") ? 700 : undefined,
            fontStyle: (item.fontStyle === "italic" || item.fontStyle === "Italic" || item.fontStyle === "bold-italic") ? "italic" : undefined,
            textDecoration: [
              item.underline ? "underline" : "",
              item.strikeThrough ? "line-through" : "",
            ].filter(Boolean).join(" ") || undefined,
          }}
        >
          {item.text}
        </span>
        {item.textColor && (
          <ColorSwatch color={item.textColor} />
        )}
      </span>
    );
  }

  if (item.itemType === "EQUATION") {
    return (
      <span className="inline-block mx-0.5 my-0.5 px-1.5 py-0.5 bg-purple-50 border border-purple-200 rounded text-[10px]">
        <span className="text-purple-400 font-mono">&lt;eq</span>
        {item.sourceType && (
          <span className="text-purple-400 font-mono"> src="{item.sourceType}"</span>
        )}
        <span className="text-purple-400 font-mono">&gt;</span>
        <span className="font-mono text-purple-800 mx-0.5">{item.hwpScript || "(empty)"}</span>
        <span className="text-purple-400 font-mono">&lt;/eq&gt;</span>
      </span>
    );
  }

  if (item.itemType === "INLINE_OBJECT") {
    const kind = item.kind || "INLINE";
    const dim = item.width && item.height
      ? ` ${(item.width / 7200 * 25.4).toFixed(1)}×${(item.height / 7200 * 25.4).toFixed(1)}mm`
      : "";
    const imgPath = item.imagePath || "";
    const imgName = imgPath ? imgPath.split("/").pop() : "";
    return (
      <span className="inline-block mx-0.5 my-0.5 px-1.5 py-0.5 bg-green-50 border border-green-200 rounded text-[10px]">
        <span className="text-green-500 font-mono">&lt;{kind}</span>
        {dim && <span className="text-green-500 font-mono">{dim}</span>}
        {item.sourceId && <span className="text-green-500 font-mono"> id="{item.sourceId}"</span>}
        {imgName && <span className="text-green-600 font-mono"> img="{imgName}"</span>}
        {item.anchoredPosition && (
          <span className="text-green-500 font-mono"> pos="{item.anchoredPosition}"</span>
        )}
        <span className="text-green-500 font-mono"> /&gt;</span>
      </span>
    );
  }

  if (item.itemType === "BREAK") {
    return (
      <span className="inline-block mx-0.5 text-orange-400 font-mono text-[10px]">
        &lt;{item.breakType || "BR"} /&gt;
      </span>
    );
  }

  return null;
}

// ─── Detail Content ─────────────────────────────────────────────────

function DetailContent({ node, doc }: { node: any; doc: any }) {
  if (!node) {
    return (
      <div className="p-4 text-gray-400 text-sm">
        트리에서 노드를 선택하면 속성이 표시됩니다.
      </div>
    );
  }

  // Story content view — full story with inline markup
  if (node._storyView) {
    return <StoryContentView storyView={node._storyView} doc={doc} />;
  }

  // Preview based on node type
  let preview: React.ReactNode = null;
  if (node.itemType === "TEXT_RUN" && node.text) {
    preview = <TextRunPreview node={node} />;
  } else if (node.itemType === "EQUATION") {
    preview = <EquationPreview node={node} />;
  } else if (node.items && !node.itemType) {
    // Paragraph-like node
    preview = <ParagraphPreview node={node} />;
  } else if (node.styleId || node.styleName) {
    preview = <StylePreview node={node} doc={doc} />;
  } else if (node._group === "colors") {
    preview = <ColorGroupPreview node={node} />;
  }

  return (
    <div className="text-xs overflow-auto h-full">
      {preview}
      <div className="p-2">
        <table className="w-full">
          <tbody>
            {Object.entries(node).map(([key, value]) => {
              // Skip internal and large child arrays
              if (key === "_group" || key === "_idx" || key === "items" || key === "colorMap") return null;
              if (CHILD_ARRAY_KEYS.has(key) && Array.isArray(value) && (value as any[]).length > 0 && typeof (value as any[])[0] === "object") {
                // Show count only for complex child arrays
                return (
                  <tr key={key} className="border-b border-gray-100">
                    <td className="py-1 pr-2 font-medium text-gray-600 whitespace-nowrap align-top">
                      {key}
                    </td>
                    <td className="py-1 text-gray-500">
                      [{(value as any[]).length} items] — 트리에서 탐색
                    </td>
                  </tr>
                );
              }
              // Expandable arrays/objects (simple ones like columnWidths, tabStops)
              if (Array.isArray(value) || (typeof value === "object" && value !== null)) {
                return (
                  <tr key={key} className="border-b border-gray-100">
                    <td className="py-1 pr-2 font-medium text-gray-600 whitespace-nowrap align-top">
                      {key}
                    </td>
                    <td className="py-1 text-gray-800 break-all">
                      <ExpandableValue keyName={key} value={value} />
                    </td>
                  </tr>
                );
              }
              return (
                <tr key={key} className="border-b border-gray-100">
                  <td className="py-1 pr-2 font-medium text-gray-600 whitespace-nowrap align-top">
                    {key}
                  </td>
                  <td className="py-1 text-gray-800 break-all">
                    {key === "fontSizeHwpunits" && typeof value === "number" ? (
                      <span>
                        <span className="text-blue-600">{value}</span>
                        <span className="text-gray-400 ml-1">({hwpuToPt(value)}pt)</span>
                      </span>
                    ) : (
                      renderValue(key, value)
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}

// ─── Main Panel ─────────────────────────────────────────────────────

export function ASTDetailPanel() {
  const { astDoc, selectedPath } = useAstStore();

  const selectedNode = useMemo(
    () => resolveNode(astDoc, selectedPath || ""),
    [astDoc, selectedPath]
  );

  return (
    <div className="h-full flex flex-col min-h-0">
      <div className="px-2 py-1 border-b bg-gray-50 text-[10px] text-gray-500 font-medium shrink-0">
        {selectedPath ? <Breadcrumb path={selectedPath} /> : "No selection"}
      </div>
      <div className="flex-1 overflow-auto min-h-0">
        <DetailContent node={selectedNode} doc={astDoc} />
      </div>
    </div>
  );
}
