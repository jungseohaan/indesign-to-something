import { useState, useMemo, useEffect } from "react";
import { useAppStore } from "../stores/useAppStore";
import { useAstStore } from "../stores/useAstStore";
import {
  getFontMatch,
  getFontCandidates,
  compareConfidence,
  COMMON_HWPX_FONTS,
  type FontMatch,
  type MatchConfidence,
} from "../utils/fontMapper";

interface FontEntry {
  idmlFont: string;
  defaultHwpx: string;
  confidence: MatchConfidence;
  reason: string;
  candidates: FontMatch[];
}

function makeEntry(idmlFont: string): FontEntry {
  const match = getFontMatch(idmlFont);
  return {
    idmlFont,
    defaultHwpx: match.font,
    confidence: match.confidence,
    reason: match.reason,
    candidates: getFontCandidates(idmlFont, 4),
  };
}

function collectDocumentFonts(): FontEntry[] {
  const { astDoc, resolvedData } = useAstStore.getState();
  const seen = new Set<string>();
  const entries: FontEntry[] = [];

  // AST fonts (IDML 원본)
  if (astDoc?.fonts) {
    for (const f of astDoc.fonts) {
      if (f.fontFamily && !seen.has(f.fontFamily) && !f.fontFamily.includes("BT수식")) {
        seen.add(f.fontFamily);
        entries.push(makeEntry(f.fontFamily));
      }
    }
  }

  // Resolved fonts (InDesign DOM, 추가분만)
  if (resolvedData?.fonts) {
    for (const f of resolvedData.fonts) {
      if (f.fontFamily && !seen.has(f.fontFamily) && !f.fontFamily.includes("BT수식")) {
        seen.add(f.fontFamily);
        entries.push(makeEntry(f.fontFamily));
      }
    }
  }

  // SPEC-014: 신뢰도 낮은 항목(fallback)을 위로 정렬해 사용자 검토 우선
  entries.sort((a, b) => compareConfidence(a.confidence, b.confidence));
  return entries;
}

const CONFIDENCE_BADGE: Record<
  MatchConfidence,
  { label: string; cls: string }
> = {
  exact: { label: "정확", cls: "bg-green-50 text-green-600 border-green-200" },
  partial: { label: "부분", cls: "bg-blue-50 text-blue-600 border-blue-200" },
  keyword: { label: "키워드", cls: "bg-amber-50 text-amber-600 border-amber-200" },
  fallback: { label: "검토", cls: "bg-red-50 text-red-600 border-red-200" },
};

export function FontMappingModal() {
  const {
    showFontMappingModal,
    fontMappings,
    setFontMappings,
    closeFontMappingModal,
  } = useAppStore();

  const fonts = useMemo(() => collectDocumentFonts(), [showFontMappingModal]);

  // 로컬 편집 상태 (적용 전까지 store에 반영하지 않음)
  const [localMappings, setLocalMappings] = useState<Record<string, string>>({});
  const [search, setSearch] = useState("");

  // 모달이 열릴 때 store의 fontMappings를 로컬로 복사
  useEffect(() => {
    if (showFontMappingModal) {
      setLocalMappings({ ...fontMappings });
      setSearch("");
    }
  }, [showFontMappingModal]);

  if (!showFontMappingModal) return null;

  const filteredFonts = search
    ? fonts.filter((f) =>
        f.idmlFont.toLowerCase().includes(search.toLowerCase()) ||
        getMapping(f).toLowerCase().includes(search.toLowerCase())
      )
    : fonts;

  function getMapping(entry: FontEntry): string {
    return localMappings[entry.idmlFont] || entry.defaultHwpx;
  }

  function isOverridden(entry: FontEntry): boolean {
    const mapped = localMappings[entry.idmlFont];
    return mapped !== undefined && mapped !== entry.defaultHwpx;
  }

  function setMapping(idmlFont: string, hwpxFont: string, defaultHwpx: string) {
    setLocalMappings((prev) => {
      const next = { ...prev };
      if (hwpxFont === defaultHwpx) {
        delete next[idmlFont];
      } else {
        next[idmlFont] = hwpxFont;
      }
      return next;
    });
  }

  function handleReset() {
    setLocalMappings({});
  }

  // SPEC-014: 모든 폰트의 추천 매핑을 한 번에 명시적 매핑으로 채운다.
  // 사용자가 검토 후 적용할 수 있도록 localMappings에만 반영.
  function handleApplyRecommendations() {
    const next: Record<string, string> = { ...localMappings };
    for (const f of fonts) {
      if (next[f.idmlFont] === undefined) {
        next[f.idmlFont] = f.defaultHwpx;
      }
    }
    setLocalMappings(next);
  }

  function handleApply() {
    setFontMappings(localMappings);
    closeFontMappingModal();
  }

  const overrideCount = Object.keys(localMappings).length;
  const fallbackCount = fonts.filter((f) => f.confidence === "fallback").length;

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-xl w-[560px] max-h-[80vh] flex flex-col">
        {/* Header */}
        <div className="px-5 pt-5 pb-3 flex items-center justify-between">
          <div>
            <h2 className="text-base font-bold text-gray-800">폰트 매핑</h2>
            <p className="text-xs text-gray-500 mt-1">
              문서에서 사용된 {fonts.length}개 폰트
              {fallbackCount > 0 && (
                <span className="text-red-500 ml-1">
                  · {fallbackCount}개 검토 필요
                </span>
              )}
              {overrideCount > 0 && (
                <span className="text-blue-600 ml-1">({overrideCount}개 변경)</span>
              )}
            </p>
          </div>
          <button
            onClick={closeFontMappingModal}
            className="text-gray-400 hover:text-gray-600 text-lg leading-none"
          >
            &times;
          </button>
        </div>

        {/* Search */}
        <div className="px-5 pb-2">
          <input
            type="text"
            placeholder="폰트 검색..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-full border border-gray-300 rounded px-3 py-1.5 text-sm focus:ring-1 focus:ring-blue-400 focus:border-blue-400"
          />
        </div>

        {/* Font List */}
        <div className="px-5 py-2 flex-1 overflow-y-auto border-y min-h-0">
          {/* Header row */}
          <div className="flex items-center gap-3 pb-2 mb-2 border-b text-xs text-gray-400 font-medium">
            <div className="flex-1">InDesign 폰트</div>
            <div className="w-14 text-center">신뢰도</div>
            <div className="w-5" />
            <div className="w-48">HWPX 폰트</div>
            <div className="w-4" />
          </div>

          {filteredFonts.length === 0 ? (
            <div className="text-sm text-gray-400 py-4 text-center">
              {fonts.length === 0 ? "문서를 먼저 로드하세요" : "검색 결과 없음"}
            </div>
          ) : (
            filteredFonts.map((entry) => {
              const badge = CONFIDENCE_BADGE[entry.confidence];
              return (
                <div key={entry.idmlFont} className="flex items-center gap-3 py-1.5">
                  {/* IDML font name + reason */}
                  <div className="flex-1 min-w-0">
                    <div
                      className="text-sm text-gray-800 truncate"
                      title={entry.idmlFont}
                    >
                      {entry.idmlFont}
                    </div>
                    <div className="text-[10px] text-gray-400 truncate" title={entry.reason}>
                      {entry.reason}
                    </div>
                  </div>

                  {/* Confidence badge */}
                  <div className="w-14 text-center shrink-0">
                    <span
                      className={`text-[10px] px-1.5 py-0.5 rounded border ${badge.cls}`}
                      title={entry.reason}
                    >
                      {badge.label}
                    </span>
                  </div>

                  {/* Arrow */}
                  <div className="w-5 text-gray-300 text-center text-xs">&rarr;</div>

                  {/* HWPX font combobox */}
                  <div className="w-48">
                    <input
                      type="text"
                      list={`hwpx-fonts-${entry.idmlFont}`}
                      value={getMapping(entry)}
                      onChange={(e) =>
                        setMapping(entry.idmlFont, e.target.value, entry.defaultHwpx)
                      }
                      className={`w-full border rounded px-2 py-1 text-sm ${
                        isOverridden(entry)
                          ? "border-blue-400 bg-blue-50"
                          : "border-gray-300"
                      } focus:ring-1 focus:ring-blue-400 focus:border-blue-400`}
                    />
                    <datalist id={`hwpx-fonts-${entry.idmlFont}`}>
                      {/* 후보 먼저, 그다음 공통 폰트 */}
                      {entry.candidates.map((c) => (
                        <option key={`cand-${c.font}`} value={c.font}>
                          {c.reason}
                        </option>
                      ))}
                      {COMMON_HWPX_FONTS.map((f) => (
                        <option key={f} value={f} />
                      ))}
                    </datalist>
                  </div>

                  {/* Override indicator */}
                  <div className="w-4 text-center">
                    {isOverridden(entry) && (
                      <span className="text-blue-500 text-sm" title="사용자 변경">
                        &#9679;
                      </span>
                    )}
                  </div>
                </div>
              );
            })
          )}
        </div>

        {/* Actions */}
        <div className="px-5 py-4 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <button
              onClick={handleReset}
              disabled={overrideCount === 0}
              className="px-4 py-2 text-sm text-gray-600 hover:text-gray-800 hover:bg-gray-100 rounded transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              기본값 복원
            </button>
            <button
              onClick={handleApplyRecommendations}
              disabled={fonts.length === 0}
              className="px-4 py-2 text-sm text-blue-600 hover:bg-blue-50 rounded transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              title="모든 폰트의 추천 매핑을 명시적으로 채워서 한 번에 검토"
            >
              추천 모두 적용
            </button>
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={closeFontMappingModal}
              className="px-4 py-2 text-sm text-gray-500 hover:text-gray-700 rounded transition-colors"
            >
              취소
            </button>
            <button
              onClick={handleApply}
              className="px-5 py-2 bg-blue-600 text-white text-sm rounded hover:bg-blue-700 transition-colors"
            >
              적용
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
