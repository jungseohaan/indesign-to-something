import { useAppStore } from "../stores/useAppStore";

export function LayoutDetailPanel() {
  const { selectedSpread, selectedPage } = useAppStore();

  if (selectedSpread) {
    return (
      <div className="h-full overflow-auto p-4">
        <h3 className="font-medium text-sm mb-3">Spread Layout</h3>
        <div className="space-y-2 text-sm">
          <div className="grid grid-cols-2 gap-2">
            <div className="text-gray-500">ID</div>
            <div>{selectedSpread.id}</div>
            <div className="text-gray-500">Pages</div>
            <div>{selectedSpread.page_count}</div>
            <div className="text-gray-500">Size</div>
            <div>
              {Math.round(selectedSpread.total_width)} x{" "}
              {Math.round(selectedSpread.total_height)} pt
            </div>
            <div className="text-gray-500">Text Frames</div>
            <div>{selectedSpread.text_frame_count}</div>
            <div className="text-gray-500">Image Frames</div>
            <div>{selectedSpread.image_frame_count}</div>
            <div className="text-gray-500">Vectors</div>
            <div>{selectedSpread.vector_count}</div>
            {selectedSpread.master_spread_name && (
              <>
                <div className="text-gray-500">Master</div>
                <div>{selectedSpread.master_spread_name}</div>
              </>
            )}
          </div>
        </div>
      </div>
    );
  }

  if (selectedPage) {
    return (
      <div className="h-full overflow-auto p-4">
        <h3 className="font-medium text-sm mb-3">Page Layout</h3>
        <div className="space-y-2 text-sm">
          <div className="grid grid-cols-2 gap-2">
            <div className="text-gray-500">Name</div>
            <div>{selectedPage.name}</div>
            <div className="text-gray-500">Size</div>
            <div>
              {Math.round(selectedPage.width)} x{" "}
              {Math.round(selectedPage.height)} pt
            </div>
            <div className="text-gray-500">Margins</div>
            <div>
              T:{Math.round(selectedPage.margin_top)} B:
              {Math.round(selectedPage.margin_bottom)} L:
              {Math.round(selectedPage.margin_left)} R:
              {Math.round(selectedPage.margin_right)}
            </div>
            <div className="text-gray-500">Columns</div>
            <div>{selectedPage.column_count}</div>
            <div className="text-gray-500">Frames</div>
            <div>{selectedPage.frames.length}</div>
            {selectedPage.master_spread && (
              <>
                <div className="text-gray-500">Master</div>
                <div>{selectedPage.master_spread}</div>
              </>
            )}
          </div>
        </div>
      </div>
    );
  }

  return null;
}
