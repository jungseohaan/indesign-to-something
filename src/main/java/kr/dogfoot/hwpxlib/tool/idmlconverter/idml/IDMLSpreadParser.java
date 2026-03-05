package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;

import org.w3c.dom.*;
import java.util.*;
import static kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLXmlUtils.*;

/**
 * IDML 스프레드 파싱: 페이지, 텍스트프레임, 이미지프레임, 벡터도형, 그룹.
 */
class IDMLSpreadParser {

    // ===== Spread XML 파싱 =====

    static IDMLSpread parseSpread(Document spreadDoc, Set<String> hiddenLayerIds) {
        IDMLSpread spread = new IDMLSpread();

        // Spread 또는 MasterSpread 루트 요소 찾기
        NodeList spreadNodes = spreadDoc.getElementsByTagName("Spread");
        if (spreadNodes.getLength() == 0) {
            spreadNodes = spreadDoc.getElementsByTagName("MasterSpread");
        }
        if (spreadNodes.getLength() == 0) return spread;

        Element spreadElem = (Element) spreadNodes.item(0);
        spread.selfId(spreadElem.getAttribute("Self"));

        // Page, TextFrame, Group 처리 (z-order 순서 추적)
        int[] zOrderCounter = {0};  // 배열로 래핑하여 람다/내부 메서드에서 수정 가능
        NodeList children = spreadElem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element elem = (Element) node;

            // 숨겨진 레이어에 속한 요소는 건너뛴다
            String itemLayer = getAttrOrNull(elem, "ItemLayer");
            if (itemLayer != null && hiddenLayerIds.contains(itemLayer)) continue;

            if ("Page".equals(elem.getTagName())) {
                spread.addPage(parsePage(elem));
            } else if ("TextFrame".equals(elem.getTagName())) {
                IDMLTextFrame frame = parseTextFrame(elem);
                if (frame != null) {
                    spread.addTextFrame(frame);
                }
            } else if ("Rectangle".equals(elem.getTagName())
                    || "Polygon".equals(elem.getTagName())
                    || "Oval".equals(elem.getTagName())) {
                IDMLImageFrame imageFrame = tryParseImageFrame(elem);
                if (imageFrame != null) {
                    imageFrame.zOrder(zOrderCounter[0]++);
                    spread.addImageFrame(imageFrame);
                } else {
                    // 이미지가 없으면 순수 벡터 도형으로 파싱
                    IDMLVectorShape vectorShape = tryParseVectorShape(elem);
                    if (vectorShape != null) {
                        vectorShape.zOrder(zOrderCounter[0]++);
                        spread.addVectorShape(vectorShape);
                    }
                    // 클리핑 자식 수집은 벡터 도형만 대상이므로,
                    // TextFrame 등 다른 자식은 항상 extractGroupsFromFrame으로 추출해야 한다.
                    int tfCountBefore = spread.textFrames().size();
                    double[] frameTransform = IDMLGeometry.parseTransform(
                            elem.getAttribute("ItemTransform"));
                    extractGroupsFromFrame(elem, spread, frameTransform,
                            hiddenLayerIds, zOrderCounter);
                    // 래퍼 Rectangle이 TextFrame을 포함하면 벡터 도형(채우기 이미지)으로 렌더링 억제
                    if (vectorShape != null && spread.textFrames().size() > tfCountBefore) {
                        spread.vectorShapes().remove(vectorShape);
                    }
                }
            } else if ("GraphicLine".equals(elem.getTagName())) {
                // 그래픽 라인도 벡터 도형으로 처리
                IDMLVectorShape vectorShape = tryParseVectorShape(elem);
                if (vectorShape != null) {
                    vectorShape.shapeType(IDMLVectorShape.ShapeType.GRAPHIC_LINE);
                    vectorShape.zOrder(zOrderCounter[0]++);
                    spread.addVectorShape(vectorShape);
                }
            } else if ("Group".equals(elem.getTagName())) {
                double[] groupTransform = IDMLGeometry.parseTransform(
                        elem.getAttribute("ItemTransform"));
                String groupSelfId = elem.getAttribute("Self");
                parseGroupForFrames(elem, spread, groupTransform, hiddenLayerIds, zOrderCounter, groupSelfId);

                // 그룹 구조도 보존 (글상자 변환용)
                IDMLGroup group = parseGroupAsObject(elem, groupTransform, hiddenLayerIds);
                if (group != null) {
                    spread.addGroup(group);
                }
            }
        }

        return spread;
    }

    static IDMLPage parsePage(Element pageElem) {
        IDMLPage page = new IDMLPage();
        page.selfId(pageElem.getAttribute("Self"));
        page.name(pageElem.getAttribute("Name"));
        page.geometricBounds(IDMLGeometry.parseBounds(
                pageElem.getAttribute("GeometricBounds")));
        page.itemTransform(IDMLGeometry.parseTransform(
                pageElem.getAttribute("ItemTransform")));
        page.appliedMasterSpread(getAttrOrNull(pageElem, "AppliedMaster"));

        // MarginPreference
        Element marginPref = getFirstChildElement(pageElem, "MarginPreference");
        if (marginPref != null) {
            page.marginTop(parseDoubleAttrDef(marginPref, "Top", 0));
            page.marginBottom(parseDoubleAttrDef(marginPref, "Bottom", 0));
            page.marginLeft(parseDoubleAttrDef(marginPref, "Left", 0));
            page.marginRight(parseDoubleAttrDef(marginPref, "Right", 0));
            page.columnCount(parseIntAttr(marginPref, "ColumnCount", 1));
            page.columnGutter(parseDoubleAttrDef(marginPref, "ColumnGutter", 12.0));
        }

        return page;
    }

    static IDMLTextFrame parseTextFrame(Element frameElem) {
        String contentType = frameElem.getAttribute("ContentType");
        // ContentType가 "GraphicType"이면 텍스트 프레임이 아님
        if ("GraphicType".equals(contentType)) return null;

        IDMLTextFrame frame = new IDMLTextFrame();
        frame.selfId(frameElem.getAttribute("Self"));
        frame.parentStoryId(getAttrOrNull(frameElem, "ParentStory"));
        frame.geometricBounds(resolveGeometricBounds(frameElem));
        frame.itemTransform(IDMLGeometry.parseTransform(
                frameElem.getAttribute("ItemTransform")));
        frame.appliedObjectStyle(getAttrOrNull(frameElem, "AppliedObjectStyle"));
        frame.fillColor(getAttrOrNull(frameElem, "FillColor"));

        // 폴리곤 경로 추출 (비사각형 프레임 감지용)
        extractTextFramePath(frameElem, frame);
        frame.previousTextFrame(getAttrOrNull(frameElem, "PreviousTextFrame"));
        frame.nextTextFrame(getAttrOrNull(frameElem, "NextTextFrame"));

        // Stroke/Outline properties
        frame.strokeColor(getAttrOrNull(frameElem, "StrokeColor"));
        frame.strokeWeight(parseDoubleAttrDef(frameElem, "StrokeWeight", 0));
        frame.cornerRadius(parseDoubleAttrDef(frameElem, "CornerRadius", 0));
        frame.fillTint(parseDoubleAttrDef(frameElem, "FillTint", 100));
        frame.strokeTint(parseDoubleAttrDef(frameElem, "StrokeTint", 100));

        // StrokeType (Solid, Dashed, Dotted, etc.)
        String strokeType = getAttrOrNull(frameElem, "StrokeType");
        if (strokeType != null) {
            if (strokeType.contains("Dashed")) {
                frame.strokeType("Dashed");
            } else if (strokeType.contains("Dotted")) {
                frame.strokeType("Dotted");
            } else if (strokeType.contains("Solid")) {
                frame.strokeType("Solid");
            } else {
                frame.strokeType(strokeType);
            }
        }

        // Per-corner radius
        double tlRadius = parseDoubleAttrDef(frameElem, "TopLeftCornerRadius", -1);
        double trRadius = parseDoubleAttrDef(frameElem, "TopRightCornerRadius", -1);
        double blRadius = parseDoubleAttrDef(frameElem, "BottomLeftCornerRadius", -1);
        double brRadius = parseDoubleAttrDef(frameElem, "BottomRightCornerRadius", -1);
        if (tlRadius >= 0 || trRadius >= 0 || blRadius >= 0 || brRadius >= 0) {
            double defaultRadius = frame.cornerRadius();
            frame.cornerRadii(new double[]{
                    tlRadius >= 0 ? tlRadius : defaultRadius,
                    trRadius >= 0 ? trRadius : defaultRadius,
                    blRadius >= 0 ? blRadius : defaultRadius,
                    brRadius >= 0 ? brRadius : defaultRadius
            });
        }

        // TextFramePreference에서 컬럼 정보 파싱
        Element tfPref = getFirstChildElement(frameElem, "TextFramePreference");
        if (tfPref != null) {
            frame.columnCount(parseIntAttr(tfPref, "TextColumnCount", 1));
            frame.columnGutter(parseDoubleAttrDef(tfPref, "TextColumnGutter", 12.0));

            // InsetSpacing (속성 단일 값 또는 Properties 리스트)
            String insetStr = tfPref.getAttribute("InsetSpacing");
            if (insetStr != null && !insetStr.isEmpty()) {
                try {
                    double inset = Double.parseDouble(insetStr);
                    frame.insetSpacing(new double[]{inset, inset, inset, inset});
                } catch (NumberFormatException ignored) {}
            }
            if (frame.insetSpacing() == null) {
                Element tfProps = getFirstChildElement(tfPref, "Properties");
                if (tfProps != null) {
                    Element insetElem = getFirstChildElement(tfProps, "InsetSpacing");
                    if (insetElem != null) {
                        List<Element> items = getChildElements(insetElem, "ListItem");
                        if (items.size() >= 4) {
                            try {
                                double[] insets = new double[4];
                                for (int si = 0; si < 4; si++) {
                                    insets[si] = Double.parseDouble(items.get(si).getTextContent().trim());
                                }
                                frame.insetSpacing(insets);
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }

            // 컬럼 유형 (고정 수, 고정 너비, 가변 너비)
            boolean useFixedWidth = "true".equalsIgnoreCase(tfPref.getAttribute("UseFixedColumnWidth"));
            if (useFixedWidth) {
                frame.columnType("FixedWidth");
                frame.columnFixedWidth(parseDoubleAttrDef(tfPref, "TextColumnFixedWidth", 0));
            } else {
                // ColumnWidths가 있으면 FlexibleWidth, 없으면 FixedNumber
                Element props = getFirstChildElement(tfPref, "Properties");
                if (props != null) {
                    Element textColumnWidths = getFirstChildElement(props, "TextColumnWidths");
                    if (textColumnWidths != null) {
                        List<Element> listItems = getChildElements(textColumnWidths, "ListItem");
                        if (!listItems.isEmpty()) {
                            frame.columnType("FlexibleWidth");
                            double[] widths = new double[listItems.size()];
                            for (int w = 0; w < listItems.size(); w++) {
                                String widthText = listItems.get(w).getTextContent();
                                if (widthText != null && !widthText.isEmpty()) {
                                    try {
                                        widths[w] = Double.parseDouble(widthText.trim());
                                    } catch (NumberFormatException ignored) {}
                                }
                            }
                            frame.columnWidths(widths);
                        }
                    }
                }
            }

            // 수직 정렬
            String vJust = getAttrOrNull(tfPref, "VerticalJustification");
            if (vJust != null) {
                frame.verticalJustification(vJust);
            }

            // 텍스트 감싸기 무시
            frame.ignoreWrap("true".equalsIgnoreCase(tfPref.getAttribute("IgnoreWrap")));

            // 단 경계선 (Column Rule)
            frame.useColumnRule("true".equalsIgnoreCase(tfPref.getAttribute("UseColumnRulePlacement")));
            frame.columnRuleWidth(parseDoubleAttrDef(tfPref, "ColumnRuleStrokeWidth", 1.0));
            String ruleType = getAttrOrNull(tfPref, "ColumnRuleStrokeType");
            if (ruleType != null) {
                frame.columnRuleType(ruleType);
            }
            String ruleColor = getAttrOrNull(tfPref, "ColumnRuleStrokeColor");
            if (ruleColor != null) {
                frame.columnRuleColor(ruleColor);
            }
            frame.columnRuleTint(parseDoubleAttrDef(tfPref, "ColumnRuleStrokeTint", 100));
            frame.columnRuleOffset(parseDoubleAttrDef(tfPref, "ColumnRuleOffset", 0));
            frame.columnRuleInsetWidth(parseDoubleAttrDef(tfPref, "ColumnRuleInsetWidth", 0));
        }

        // TextWrapPreference 파싱
        List<Element> twpList = getDescendantElements(frameElem, "TextWrapPreference");
        if (!twpList.isEmpty()) {
            Element twp = twpList.get(0);
            String mode = twp.getAttribute("TextWrapMode");
            if (mode != null && !mode.isEmpty()) {
                frame.textWrapMode(mode);
            }
        }

        return frame;
    }

    /**
     * Rectangle/Polygon/Oval에서 이미지 프레임인지 확인하고 파싱한다.
     * 내부에 Image + Link가 있으면 이미지 프레임.
     */
    static IDMLImageFrame tryParseImageFrame(Element shapeElem) {
        // 내부에 Image, PDF, EPS가 있는지 확인
        List<Element> images = getDescendantElements(shapeElem, "Image");
        if (images.isEmpty()) images = getDescendantElements(shapeElem, "PDF");
        if (images.isEmpty()) images = getDescendantElements(shapeElem, "EPS");
        if (images.isEmpty()) return null;

        Element imageElem = images.get(0);
        List<Element> links = getChildElements(imageElem, "Link");

        IDMLImageFrame frame = new IDMLImageFrame();
        frame.selfId(shapeElem.getAttribute("Self"));
        // 이미지 프레임은 항상 local-space bounds를 사용해야 한다.
        // - imageTransform: 이미지 → local-space 매핑 (클리핑용)
        // - scaledWidth/Height: |scale| × localWidth → 올바른 표시 크기
        // GeometricBounds 속성은 parent-space (post-transform)이므로:
        // - 클리핑: 좌표계 불일치 → 오프셋 오류
        // - 크기: scaledWidth = |scale| × (이미 scaled된 width) → 이중 스케일 오류
        double[] localBounds = computeBoundsFromPathGeometry(shapeElem);
        if (localBounds[0] == 0 && localBounds[1] == 0
                && localBounds[2] == 0 && localBounds[3] == 0) {
            // PathGeometry가 없으면 GeometricBounds 속성을 역변환하여 local-space로 변환
            String boundsAttr = shapeElem.getAttribute("GeometricBounds");
            if (boundsAttr != null && !boundsAttr.isEmpty()) {
                double[] parentBounds = IDMLGeometry.parseBounds(boundsAttr);
                double[] transform = IDMLGeometry.parseTransform(
                        shapeElem.getAttribute("ItemTransform"));
                localBounds = IDMLGeometry.inverseTransformBounds(parentBounds, transform);
            }
        }

        // 이미지의 직접 부모 요소가 shapeElem과 다르면 (예: Rectangle → Polygon → PDF),
        // 자식 프레임(Polygon)이 실제 이미지 컨테이너이므로 그 bounds/transform을 결합한다.
        Element actualContainer = shapeElem;
        Element imageParent = imageElem.getParentNode() instanceof Element
                ? (Element) imageElem.getParentNode() : null;
        if (imageParent != null && imageParent != shapeElem) {
            double[] childBounds = computeBoundsFromPathGeometry(imageParent);
            if (childBounds[0] == 0 && childBounds[1] == 0
                    && childBounds[2] == 0 && childBounds[3] == 0) {
                String childBoundsAttr = imageParent.getAttribute("GeometricBounds");
                if (childBoundsAttr != null && !childBoundsAttr.isEmpty()) {
                    double[] pb = IDMLGeometry.parseBounds(childBoundsAttr);
                    double[] ct = IDMLGeometry.parseTransform(
                            imageParent.getAttribute("ItemTransform"));
                    childBounds = IDMLGeometry.inverseTransformBounds(pb, ct);
                }
            }
            if (!(childBounds[0] == 0 && childBounds[1] == 0
                    && childBounds[2] == 0 && childBounds[3] == 0)) {
                localBounds = childBounds;
                actualContainer = imageParent;
            }
        }

        frame.geometricBounds(localBounds);

        // ItemTransform: 자식 컨테이너를 사용하는 경우 부모 + 자식 transform 결합
        double[] frameTransform = IDMLGeometry.parseTransform(
                shapeElem.getAttribute("ItemTransform"));
        if (actualContainer != shapeElem) {
            double[] childTransform = IDMLGeometry.parseTransform(
                    actualContainer.getAttribute("ItemTransform"));
            frameTransform = CoordinateConverter.combineTransforms(frameTransform, childTransform);
        }
        frame.itemTransform(frameTransform);
        frame.appliedObjectStyle(getAttrOrNull(shapeElem, "AppliedObjectStyle"));

        // 이미지 채색 정보 (그레이스케일 이미지의 InDesign 컬러링)
        // InDesign에서 그레이스케일 이미지에 FillColor를 지정하면
        // 그레이스케일 값을 알파 마스크로 사용하여 해당 색으로 채색한다.
        String imgFillColor = getAttrOrNull(imageElem, "FillColor");
        if (imgFillColor != null && !"Swatch/None".equals(imgFillColor)) {
            frame.imageFillColor(imgFillColor);
            frame.imageFillTint(parseDoubleAttrDef(imageElem, "FillTint", 100));
        }
        String imgSpace = getAttrOrNull(imageElem, "Space");
        if (imgSpace != null) {
            frame.imageColorSpace(imgSpace);
        }

        // 이미지의 ItemTransform (클리핑을 위한 이미지 위치/스케일)
        String imgTransformStr = imageElem.getAttribute("ItemTransform");
        if (imgTransformStr != null && !imgTransformStr.isEmpty()) {
            frame.imageTransform(IDMLGeometry.parseTransform(imgTransformStr));
        }

        // GraphicBounds (원본 이미지 크기)
        Element imgProps = getFirstChildElement(imageElem, "Properties");
        if (imgProps != null) {
            Element graphicBoundsElem = getFirstChildElement(imgProps, "GraphicBounds");
            if (graphicBoundsElem != null) {
                double left = parseDoubleAttrDef(graphicBoundsElem, "Left", 0);
                double top = parseDoubleAttrDef(graphicBoundsElem, "Top", 0);
                double right = parseDoubleAttrDef(graphicBoundsElem, "Right", 0);
                double bottom = parseDoubleAttrDef(graphicBoundsElem, "Bottom", 0);
                frame.graphicBounds(new double[]{left, top, right, bottom});
            }
        }

        if (!links.isEmpty()) {
            Element link = links.get(0);
            frame.linkResourceURI(getAttrOrNull(link, "LinkResourceURI"));
            frame.linkStoredState(getAttrOrNull(link, "StoredState"));
            frame.linkResourceFormat(getAttrOrNull(link, "LinkResourceFormat"));
        }

        // GraphicLayerOption 파싱 (PSD 레이어 가시성 오버라이드)
        List<Element> gloList = getDescendantElements(imageElem, "GraphicLayerOption");
        if (!gloList.isEmpty()) {
            Element glo = gloList.get(0);
            List<Element> layers = getChildElements(glo, "GraphicLayer");
            if (!layers.isEmpty()) {
                boolean hasOverride = false;
                java.util.ArrayList<int[]> layerEntries = new java.util.ArrayList<>();
                for (Element gl : layers) {
                    int id = (int) parseDoubleAttrDef(gl, "Id", -1);
                    if (id < 0) continue;
                    String origVis = gl.getAttribute("OriginalVisibility");
                    String curVis = gl.getAttribute("CurrentVisibility");
                    boolean visible = "true".equals(curVis);
                    layerEntries.add(new int[]{id, visible ? 1 : 0});
                    if (!origVis.equals(curVis)) {
                        hasOverride = true;
                    }
                }
                if (hasOverride) {
                    frame.graphicLayers(layerEntries);
                }
            }
        }

        // TextWrapPreference 파싱
        List<Element> twpList = getDescendantElements(shapeElem, "TextWrapPreference");
        if (!twpList.isEmpty()) {
            Element twp = twpList.get(0);
            String mode = twp.getAttribute("TextWrapMode");
            if (mode != null && !mode.isEmpty()) {
                frame.textWrapMode(mode);
            }
            String side = twp.getAttribute("TextWrapSide");
            if (side != null && !side.isEmpty()) {
                frame.textWrapSide(side);
            }
            Element props = getFirstChildElement(twp, "Properties");
            if (props != null) {
                Element offset = getFirstChildElement(props, "TextWrapOffset");
                if (offset != null) {
                    frame.textWrapTop(parseDoubleAttrDef(offset, "Top", 0));
                    frame.textWrapLeft(parseDoubleAttrDef(offset, "Left", 0));
                    frame.textWrapBottom(parseDoubleAttrDef(offset, "Bottom", 0));
                    frame.textWrapRight(parseDoubleAttrDef(offset, "Right", 0));
                }
            }
        }

        return frame;
    }

    /**
     * Rectangle/Polygon/Oval/GraphicLine을 벡터 도형으로 파싱한다.
     */
    static IDMLVectorShape tryParseVectorShape(Element shapeElem) {
        IDMLVectorShape shape = new IDMLVectorShape();
        shape.selfId(shapeElem.getAttribute("Self"));
        shape.geometricBounds(resolveGeometricBounds(shapeElem));
        shape.itemTransform(IDMLGeometry.parseTransform(
                shapeElem.getAttribute("ItemTransform")));

        // 도형 타입 설정
        String tagName = shapeElem.getTagName();
        if ("Rectangle".equals(tagName)) {
            shape.shapeType(IDMLVectorShape.ShapeType.RECTANGLE);
        } else if ("Polygon".equals(tagName)) {
            shape.shapeType(IDMLVectorShape.ShapeType.POLYGON);
        } else if ("Oval".equals(tagName)) {
            shape.shapeType(IDMLVectorShape.ShapeType.OVAL);
        } else if ("GraphicLine".equals(tagName)) {
            shape.shapeType(IDMLVectorShape.ShapeType.GRAPHIC_LINE);
        }

        // 스타일 속성
        String fillColor = getAttrOrNull(shapeElem, "FillColor");
        String strokeColor = getAttrOrNull(shapeElem, "StrokeColor");
        double strokeWeight = parseDoubleAttrDef(shapeElem, "StrokeWeight", 1.0);

        shape.fillColor(fillColor);
        shape.strokeColor(strokeColor);
        shape.strokeWeight(strokeWeight);

        // 클리핑 프레임 패턴 감지:
        // 1) 외부 Rectangle(채우기 없음)이 내부 자식 도형을 클리핑
        // 2) ContentType=GraphicType인 프레임이 내부 자식 도형을 클리핑 (채우기 유무 무관)
        boolean isGraphicFrame = "GraphicType".equals(shapeElem.getAttribute("ContentType"));
        if (!shape.hasFill() || isGraphicFrame) {
            NodeList childNodes = shapeElem.getChildNodes();
            for (int i = 0; i < childNodes.getLength(); i++) {
                Node child = childNodes.item(i);
                if (child.getNodeType() != Node.ELEMENT_NODE) continue;
                Element childElem = (Element) child;
                String childTag = childElem.getTagName();
                if ("Rectangle".equals(childTag) || "Polygon".equals(childTag)
                        || "Oval".equals(childTag)) {
                    IDMLVectorShape childShape = tryParseVectorShape(childElem);
                    if (childShape != null && childShape.hasFill()) {
                        shape.clippedChild(childShape);
                        break;
                    }
                } else if ("Group".equals(childTag)) {
                    // Group 자식 클리핑: 외부 프레임이 클리핑 마스크, 내부 Group의 도형이 피클리핑 대상
                    double[] groupTransform = IDMLGeometry.parseTransform(
                            childElem.getAttribute("ItemTransform"));
                    collectClippedChildrenFromGroup(childElem, shape, groupTransform);
                }
            }
        }
        shape.cornerRadius(parseDoubleAttrDef(shapeElem, "CornerRadius", 0));

        // 개별 모서리 둥글기 (TopLeftCornerRadius, TopRightCornerRadius, BottomLeftCornerRadius, BottomRightCornerRadius)
        double tlRadius = parseDoubleAttrDef(shapeElem, "TopLeftCornerRadius", -1);
        double trRadius = parseDoubleAttrDef(shapeElem, "TopRightCornerRadius", -1);
        double blRadius = parseDoubleAttrDef(shapeElem, "BottomLeftCornerRadius", -1);
        double brRadius = parseDoubleAttrDef(shapeElem, "BottomRightCornerRadius", -1);
        if (tlRadius >= 0 || trRadius >= 0 || blRadius >= 0 || brRadius >= 0) {
            double defaultRadius = shape.cornerRadius();
            shape.cornerRadii(new double[]{
                    tlRadius >= 0 ? tlRadius : defaultRadius,
                    trRadius >= 0 ? trRadius : defaultRadius,
                    blRadius >= 0 ? blRadius : defaultRadius,
                    brRadius >= 0 ? brRadius : defaultRadius
            });
        }

        // 투명도 (FillTint, StrokeTint: 0~100, 100=불투명)
        // FillTint=0 + 유효한 FillColor → 100%로 보정 (아웃라인된 글리프 등 IDML 내보내기 아티팩트)
        double fillTint = parseDoubleAttrDef(shapeElem, "FillTint", 100);
        if (fillTint == 0 && shape.hasFill()) {
            fillTint = 100;
        }
        shape.fillTint(fillTint);
        shape.strokeTint(parseDoubleAttrDef(shapeElem, "StrokeTint", 100));

        // 라인 끝 모양 (EndCap: ButtEndCap, RoundEndCap, ProjectingEndCap)
        String endCapStr = getAttrOrNull(shapeElem, "EndCap");
        if (endCapStr != null) {
            if (endCapStr.contains("Round")) {
                shape.startCap(IDMLVectorShape.LineCap.ROUND);
                shape.endCap(IDMLVectorShape.LineCap.ROUND);
            } else if (endCapStr.contains("Projecting")) {
                shape.startCap(IDMLVectorShape.LineCap.PROJECTING);
                shape.endCap(IDMLVectorShape.LineCap.PROJECTING);
            }
        }

        // 라인 연결 모양 (EndJoin: MiterEndJoin, RoundEndJoin, BevelEndJoin)
        String endJoinStr = getAttrOrNull(shapeElem, "EndJoin");
        if (endJoinStr != null) {
            if (endJoinStr.contains("Round")) {
                shape.lineJoin(IDMLVectorShape.LineJoin.ROUND);
            } else if (endJoinStr.contains("Bevel")) {
                shape.lineJoin(IDMLVectorShape.LineJoin.BEVEL);
            }
        }
        shape.miterLimit(parseDoubleAttrDef(shapeElem, "MiterLimit", 4.0));

        // StrokeType → 점선 패턴 (Solid, Canned Dashed, Canned Dotted, Japanese Dots 등)
        String strokeType = getAttrOrNull(shapeElem, "StrokeType");
        if (strokeType != null) {
            double[] dash = resolveStrokeDashPattern(strokeType, shape.strokeWeight());
            if (dash != null) {
                shape.dashPattern(dash);
            }
            // Japanese Dots는 CAP_ROUND 필수 (0-길이 대시 → 둥근 점)
            if (strokeType.contains("Japanese Dots")) {
                shape.startCap(IDMLVectorShape.LineCap.ROUND);
                shape.endCap(IDMLVectorShape.LineCap.ROUND);
            }
            // 밑줄 변환용 strokeType 힌트
            if (strokeType.contains("Dotted") || strokeType.contains("Japanese Dots")) {
                shape.strokeTypeHint("dot");
            } else if (strokeType.contains("Dashed") || strokeType.contains("DashedStrokeStyle")) {
                shape.strokeTypeHint("dot");  // 짧은 대시(3-2)도 시각적으로 점선
            }
        }

        // PathPoint 파싱 (Properties/PathGeometry/GeometryPathType/PathPointArray)
        Element props = getFirstChildElement(shapeElem, "Properties");
        if (props != null) {
            Element pathGeom = getFirstChildElement(props, "PathGeometry");
            if (pathGeom != null) {
                List<Element> pathTypes = getChildElements(pathGeom, "GeometryPathType");
                for (Element pathType : pathTypes) {
                    boolean isOpen = "true".equalsIgnoreCase(pathType.getAttribute("PathOpen"));

                    if (pathTypes.size() > 1) {
                        // 복합 경로 (여러 SubPath)
                        IDMLVectorShape.SubPath subPath = shape.startNewSubPath(isOpen);
                        parsePathPoints(pathType, subPath);
                    } else {
                        // 단일 경로
                        shape.pathOpen(isOpen);
                        parsePathPointsToShape(pathType, shape);
                    }
                }
            }
        }

        // GradientFeatherSetting 파싱 (TransparencySetting 하위)
        parseGradientFeather(shapeElem, shape);

        return shape;
    }

    /**
     * GradientFeatherSetting 파싱.
     * TransparencySetting > GradientFeatherSetting에서 각도, 길이, 시작점을 추출.
     */
    static void parseGradientFeather(Element shapeElem, IDMLVectorShape shape) {
        NodeList children = shapeElem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element elem = (Element) node;
            String tag = elem.getTagName();

            if ("TransparencySetting".equals(tag)) {
                // 메인 TransparencySetting > GradientFeatherSetting
                Element gfs = getFirstChildElement(elem, "GradientFeatherSetting");
                if (gfs != null) {
                    double angle = parseDoubleAttrDef(gfs, "Angle", Double.NaN);
                    double length = parseDoubleAttrDef(gfs, "Length", 0);
                    if (!Double.isNaN(angle) && length > 0) {
                        shape.gradientFeatherAngle(angle);
                        shape.gradientFeatherLength(length);
                        String startStr = getAttrOrNull(gfs, "GradientStart");
                        if (startStr != null) {
                            String[] parts = startStr.trim().split("\\s+");
                            if (parts.length >= 2) {
                                try {
                                    shape.gradientFeatherStart(new double[]{
                                            Double.parseDouble(parts[0]),
                                            Double.parseDouble(parts[1])});
                                } catch (NumberFormatException ignored) {}
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * IDML StrokeType → 점선 dash 패턴 (포인트 단위).
     * Solid이면 null 반환 (점선 없음).
     */
    static double[] resolveStrokeDashPattern(String strokeType, double strokeWeight) {
        return resolveStrokeDashPattern(strokeType, strokeWeight, null);
    }

    static double[] resolveStrokeDashPattern(String strokeType, double strokeWeight, IDMLDocument doc) {
        if (strokeType == null || strokeType.contains("Solid")) return null;
        if (strokeWeight <= 0) strokeWeight = 1.0;

        if (strokeType.contains("Canned Dashed 4x4")) {
            return new double[]{4, 4};
        } else if (strokeType.contains("Canned Dashed 3x2")) {
            return new double[]{3, 2};
        } else if (strokeType.contains("Canned Dotted")) {
            return new double[]{strokeWeight, strokeWeight * 2};
        } else if (strokeType.contains("Japanese Dots")) {
            // CAP_ROUND + 0-길이 대시 → 둥근 점. BasicStroke는 양수만 허용하므로 0.01 사용.
            return new double[]{0.01, strokeWeight * 3};
        } else if (strokeType.startsWith("DashedStrokeStyle/")) {
            // 사용자 정의 DashedStrokeStyle — 파싱된 DashArray 사용
            if (doc != null) {
                double[] parsed = doc.getDashedStrokeStyle(strokeType);
                if (parsed != null) return parsed;
            }
            return new double[]{3, 2};  // 기본 짧은 대시 패턴
        } else if (strokeType.contains("Dashed")) {
            return new double[]{6, 4};
        }
        // Wavy, Hash, Diamond 등 복잡한 패턴은 Solid 처리
        return null;
    }

    /**
     * PathPointArray를 SubPath에 파싱한다.
     */
    static void parsePathPoints(Element pathType, IDMLVectorShape.SubPath subPath) {
        Element pointArray = getFirstChildElement(pathType, "PathPointArray");
        if (pointArray == null) return;

        List<Element> points = getChildElements(pointArray, "PathPointType");
        for (Element pt : points) {
            double[] anchor = parsePointAttr(pt, "Anchor");
            double[] left = parsePointAttr(pt, "LeftDirection");
            double[] right = parsePointAttr(pt, "RightDirection");

            if (anchor != null) {
                double lx = (left != null) ? left[0] : anchor[0];
                double ly = (left != null) ? left[1] : anchor[1];
                double rx = (right != null) ? right[0] : anchor[0];
                double ry = (right != null) ? right[1] : anchor[1];

                subPath.addPoint(new IDMLVectorShape.PathPoint(
                        anchor[0], anchor[1], lx, ly, rx, ry));
            }
        }
    }

    /**
     * PathPointArray를 shape에 직접 파싱한다 (단일 경로).
     */
    static void parsePathPointsToShape(Element pathType, IDMLVectorShape shape) {
        Element pointArray = getFirstChildElement(pathType, "PathPointArray");
        if (pointArray == null) return;

        List<Element> points = getChildElements(pointArray, "PathPointType");
        for (Element pt : points) {
            double[] anchor = parsePointAttr(pt, "Anchor");
            double[] left = parsePointAttr(pt, "LeftDirection");
            double[] right = parsePointAttr(pt, "RightDirection");

            if (anchor != null) {
                double lx = (left != null) ? left[0] : anchor[0];
                double ly = (left != null) ? left[1] : anchor[1];
                double rx = (right != null) ? right[0] : anchor[0];
                double ry = (right != null) ? right[1] : anchor[1];

                shape.addPathPoint(new IDMLVectorShape.PathPoint(
                        anchor[0], anchor[1], lx, ly, rx, ry));
            }
        }
    }

    /**
     * "x y" 형식의 포인트 속성을 파싱한다.
     */
    static double[] parsePointAttr(Element elem, String attrName) {
        String val = elem.getAttribute(attrName);
        if (val == null || val.isEmpty()) return null;
        String[] parts = val.trim().split("\\s+");
        if (parts.length < 2) return null;
        try {
            return new double[]{ Double.parseDouble(parts[0]), Double.parseDouble(parts[1]) };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 프레임(Rectangle/Polygon/Oval) 내부의 Group 자식을 탐색하여
     * 벡터 도형/이미지/텍스트 프레임을 추출한다.
     * 부모 프레임의 시각 속성(fill/stroke/corner)을 자식 TextFrame에 전파한다.
     */
    static void extractGroupsFromFrame(Element frameElem, IDMLSpread spread,
                                       double[] frameTransform,
                                       Set<String> hiddenLayerIds,
                                       int[] zOrderCounter) {
        // 부모 프레임의 시각 속성 읽기
        String wrapperFill = getAttrOrNull(frameElem, "FillColor");
        double wrapperFillTint = parseDoubleAttrDef(frameElem, "FillTint", -1);
        String wrapperStroke = getAttrOrNull(frameElem, "StrokeColor");
        double wrapperStrokeWeight = parseDoubleAttrDef(frameElem, "StrokeWeight", 0);
        double wrapperCornerRadius = parseDoubleAttrDef(frameElem, "CornerRadius", 0);

        // 유효한 채우기/선이 있는지 확인
        boolean hasWrapperFill = wrapperFill != null && !wrapperFill.contains("None")
                && !wrapperFill.contains("Paper");
        boolean hasWrapperStroke = wrapperStroke != null && !wrapperStroke.contains("None")
                && wrapperStrokeWeight > 0;

        NodeList children = frameElem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element child = (Element) node;
            if ("TextFrame".equals(child.getTagName())) {
                // Rectangle/Polygon/Oval 내부에 중첩된 TextFrame (테이블 등)
                IDMLTextFrame frame = parseTextFrame(child);
                if (frame != null) {
                    frame.itemTransform(CoordinateConverter.combineTransforms(
                            frameTransform, frame.itemTransform()));
                    // 부모 프레임의 시각 속성 전파
                    if (hasWrapperFill || hasWrapperStroke) {
                        applyWrapperStyle(frame, wrapperFill, wrapperFillTint,
                                wrapperStroke, wrapperStrokeWeight, wrapperCornerRadius);
                    }
                    spread.addTextFrame(frame);
                }
            } else if ("Group".equals(child.getTagName())) {
                double[] groupTransform = IDMLGeometry.parseTransform(
                        child.getAttribute("ItemTransform"));
                double[] combined = CoordinateConverter.combineTransforms(
                        frameTransform, groupTransform);
                String groupSelfId = child.getAttribute("Self");
                parseGroupForFrames(child, spread, combined, hiddenLayerIds,
                        zOrderCounter, groupSelfId,
                        hasWrapperFill ? wrapperFill : null, wrapperFillTint,
                        hasWrapperStroke ? wrapperStroke : null,
                        wrapperStrokeWeight, wrapperCornerRadius);
            }
        }
    }

    /**
     * 부모 프레임의 시각 속성을 자식 TextFrame에 적용한다.
     */
    private static void applyWrapperStyle(IDMLTextFrame frame,
                                           String fillColor, double fillTint,
                                           String strokeColor, double strokeWeight,
                                           double cornerRadius) {
        if (fillColor != null && !fillColor.contains("None") && !fillColor.contains("Paper")) {
            frame.wrapperFillColor(fillColor);
            frame.wrapperFillTint(fillTint);
        }
        if (strokeColor != null && !strokeColor.contains("None") && strokeWeight > 0) {
            frame.wrapperStrokeColor(strokeColor);
            frame.wrapperStrokeWeight(strokeWeight);
        }
        if (cornerRadius > 0) {
            frame.wrapperCornerRadius(cornerRadius);
        }
    }

    /**
     * 클리핑 프레임 내부의 Group에서 벡터 도형 자식을 재귀적으로 수집한다.
     */
    static void collectClippedChildrenFromGroup(Element groupElem,
                                                IDMLVectorShape clipFrame,
                                                double[] accumulatedTransform) {
        NodeList children = groupElem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element elem = (Element) node;
            String tag = elem.getTagName();

            if ("Rectangle".equals(tag) || "Polygon".equals(tag)
                    || "Oval".equals(tag) || "GraphicLine".equals(tag)) {
                IDMLVectorShape childShape = tryParseVectorShape(elem);
                if (childShape != null) {
                    double[] combinedTransform = CoordinateConverter.combineTransforms(
                            accumulatedTransform, childShape.itemTransform());
                    childShape.itemTransform(combinedTransform);
                    clipFrame.addClippedChild(childShape);
                }
            } else if ("Group".equals(tag)) {
                // 중첩 Group: 누적 변환에 현재 Group 변환을 결합
                double[] childGroupTransform = IDMLGeometry.parseTransform(
                        elem.getAttribute("ItemTransform"));
                double[] combined = CoordinateConverter.combineTransforms(
                        accumulatedTransform, childGroupTransform);
                collectClippedChildrenFromGroup(elem, clipFrame, combined);
            }
        }
    }

    /**
     * Group 내부의 TextFrame과 이미지 프레임을 재귀적으로 수집한다.
     */
    static void parseGroupForFrames(Element groupElem, IDMLSpread spread,
                                    double[] accumulatedTransform,
                                    Set<String> hiddenLayerIds,
                                    int[] zOrderCounter,
                                    String groupSelfId) {
        parseGroupForFrames(groupElem, spread, accumulatedTransform, hiddenLayerIds,
                zOrderCounter, groupSelfId, null, -1, null, 0, 0);
    }

    /**
     * Group 내부의 TextFrame과 이미지 프레임을 재귀적으로 수집한다.
     * 부모 프레임의 시각 속성(fill/stroke/corner)을 자식 TextFrame에 전파한다.
     */
    static void parseGroupForFrames(Element groupElem, IDMLSpread spread,
                                    double[] accumulatedTransform,
                                    Set<String> hiddenLayerIds,
                                    int[] zOrderCounter,
                                    String groupSelfId,
                                    String wrapperFill, double wrapperFillTint,
                                    String wrapperStroke, double wrapperStrokeWeight,
                                    double wrapperCornerRadius) {
        // Group 자체가 숨겨진 레이어에 속하면 전체 건너뛰기
        String groupLayer = getAttrOrNull(groupElem, "ItemLayer");
        if (groupLayer != null && hiddenLayerIds.contains(groupLayer)) return;

        NodeList children = groupElem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element elem = (Element) node;

            // 자식 요소 레이어 확인
            String itemLayer = getAttrOrNull(elem, "ItemLayer");
            if (itemLayer != null && hiddenLayerIds.contains(itemLayer)) continue;

            if ("TextFrame".equals(elem.getTagName())) {
                IDMLTextFrame frame = parseTextFrame(elem);
                if (frame != null) {
                    // 프레임의 ItemTransform에 Group 변환을 결합
                    frame.itemTransform(CoordinateConverter.combineTransforms(
                            accumulatedTransform, frame.itemTransform()));
                    frame.parentGroupId(groupSelfId);
                    // 부모 프레임의 시각 속성 전파
                    if (wrapperFill != null || wrapperStroke != null) {
                        applyWrapperStyle(frame, wrapperFill, wrapperFillTint,
                                wrapperStroke, wrapperStrokeWeight, wrapperCornerRadius);
                    }
                    spread.addTextFrame(frame);
                }
            } else if ("Rectangle".equals(elem.getTagName())
                    || "Polygon".equals(elem.getTagName())
                    || "Oval".equals(elem.getTagName())) {
                IDMLImageFrame imageFrame = tryParseImageFrame(elem);
                if (imageFrame != null) {
                    // 이미지 프레임의 ItemTransform에 Group 변환을 결합
                    imageFrame.itemTransform(CoordinateConverter.combineTransforms(
                            accumulatedTransform, imageFrame.itemTransform()));
                    imageFrame.fromGroup(true);
                    imageFrame.zOrder(zOrderCounter[0]++);
                    spread.addImageFrame(imageFrame);
                } else {
                    // 순수 벡터 도형으로 파싱
                    IDMLVectorShape vectorShape = tryParseVectorShape(elem);
                    if (vectorShape != null) {
                        double[] combinedTransform = CoordinateConverter.combineTransforms(
                                accumulatedTransform, vectorShape.itemTransform());

                        vectorShape.itemTransform(combinedTransform);
                        vectorShape.fromGroup(true);
                        vectorShape.parentGroupId(groupSelfId);
                        vectorShape.zOrder(zOrderCounter[0]++);
                        spread.addVectorShape(vectorShape);
                    }
                    // 프레임 내부의 Group/TextFrame 자식도 탐색
                    double[] rectTransform = IDMLGeometry.parseTransform(
                            elem.getAttribute("ItemTransform"));
                    double[] combinedForChildren = CoordinateConverter.combineTransforms(
                            accumulatedTransform, rectTransform);
                    extractGroupsFromFrame(elem, spread, combinedForChildren,
                            hiddenLayerIds, zOrderCounter);
                }
            } else if ("GraphicLine".equals(elem.getTagName())) {
                // 그래픽 라인도 벡터 도형으로 처리
                IDMLVectorShape vectorShape = tryParseVectorShape(elem);
                if (vectorShape != null) {
                    vectorShape.shapeType(IDMLVectorShape.ShapeType.GRAPHIC_LINE);
                    vectorShape.itemTransform(CoordinateConverter.combineTransforms(
                            accumulatedTransform, vectorShape.itemTransform()));
                    vectorShape.fromGroup(true);
                    vectorShape.parentGroupId(groupSelfId);
                    vectorShape.zOrder(zOrderCounter[0]++);
                    spread.addVectorShape(vectorShape);
                }
            } else if ("Group".equals(elem.getTagName())) {
                // 중첩 Group: 누적 변환에 현재 Group 변환을 결합
                double[] childGroupTransform = IDMLGeometry.parseTransform(
                        elem.getAttribute("ItemTransform"));
                double[] combined = CoordinateConverter.combineTransforms(
                        accumulatedTransform, childGroupTransform);
                parseGroupForFrames(elem, spread, combined, hiddenLayerIds, zOrderCounter, groupSelfId,
                        wrapperFill, wrapperFillTint, wrapperStroke, wrapperStrokeWeight, wrapperCornerRadius);
            }
        }
    }

    /**
     * Group 요소를 IDMLGroup 객체로 파싱한다 (구조 보존).
     */
    static IDMLGroup parseGroupAsObject(Element groupElem,
                                        double[] accumulatedTransform,
                                        Set<String> hiddenLayerIds) {
        String groupLayer = getAttrOrNull(groupElem, "ItemLayer");
        if (groupLayer != null && hiddenLayerIds.contains(groupLayer)) return null;

        IDMLGroup group = new IDMLGroup();
        group.selfId(groupElem.getAttribute("Self"));
        group.geometricBounds(IDMLGeometry.parseBounds(
                groupElem.getAttribute("GeometricBounds")));
        group.itemTransform(accumulatedTransform);

        NodeList children = groupElem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element elem = (Element) node;

            String itemLayer = getAttrOrNull(elem, "ItemLayer");
            if (itemLayer != null && hiddenLayerIds.contains(itemLayer)) continue;

            if ("TextFrame".equals(elem.getTagName())) {
                IDMLTextFrame frame = parseTextFrame(elem);
                if (frame != null) {
                    frame.itemTransform(CoordinateConverter.combineTransforms(
                            accumulatedTransform, frame.itemTransform()));
                    group.addTextFrame(frame);
                }
            } else if ("Rectangle".equals(elem.getTagName())
                    || "Polygon".equals(elem.getTagName())
                    || "Oval".equals(elem.getTagName())) {
                IDMLImageFrame imageFrame = tryParseImageFrame(elem);
                if (imageFrame != null) {
                    imageFrame.itemTransform(CoordinateConverter.combineTransforms(
                            accumulatedTransform, imageFrame.itemTransform()));
                    group.addImageFrame(imageFrame);
                } else {
                    IDMLVectorShape vectorShape = tryParseVectorShape(elem);
                    if (vectorShape != null) {
                        vectorShape.itemTransform(CoordinateConverter.combineTransforms(
                                accumulatedTransform, vectorShape.itemTransform()));
                        group.addVectorShape(vectorShape);
                    }
                }
            } else if ("GraphicLine".equals(elem.getTagName())) {
                IDMLVectorShape vectorShape = tryParseVectorShape(elem);
                if (vectorShape != null) {
                    vectorShape.shapeType(IDMLVectorShape.ShapeType.GRAPHIC_LINE);
                    vectorShape.itemTransform(CoordinateConverter.combineTransforms(
                            accumulatedTransform, vectorShape.itemTransform()));
                    group.addVectorShape(vectorShape);
                }
            } else if ("Group".equals(elem.getTagName())) {
                double[] childGroupTransform = IDMLGeometry.parseTransform(
                        elem.getAttribute("ItemTransform"));
                double[] combined = CoordinateConverter.combineTransforms(
                        accumulatedTransform, childGroupTransform);
                IDMLGroup childGroup = parseGroupAsObject(elem, combined, hiddenLayerIds);
                if (childGroup != null) {
                    group.addChildGroup(childGroup);
                }
            }
        }

        // 자식 요소가 전혀 없으면 null 반환
        if (group.textFrames().isEmpty() && group.imageFrames().isEmpty()
                && group.vectorShapes().isEmpty() && group.childGroups().isEmpty()) {
            return null;
        }

        // geometricBounds가 없거나 [0,0,0,0]이면 자식들의 bounds로부터 계산
        double[] gb = group.geometricBounds();
        if (gb == null || (gb[0] == 0 && gb[1] == 0 && gb[2] == 0 && gb[3] == 0)) {
            group.geometricBounds(computeGroupBounds(group));
        }

        return group;
    }

    /**
     * 그룹의 자식 요소들로부터 geometricBounds를 계산한다.
     */
    static double[] computeGroupBounds(IDMLGroup group) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        // 자식의 bounds 중심을 자식의 transform으로 스프레드 좌표계로 변환
        for (IDMLTextFrame tf : group.textFrames()) {
            double[] b = tf.geometricBounds();
            double[] t = tf.itemTransform();
            if (b == null || t == null) continue;
            double[] tl = CoordinateConverter.applyTransform(t, b[1], b[0]);
            double[] br = CoordinateConverter.applyTransform(t, b[3], b[2]);
            if (tl[0] < minX) minX = tl[0]; if (tl[1] < minY) minY = tl[1];
            if (br[0] > maxX) maxX = br[0]; if (br[1] > maxY) maxY = br[1];
        }
        for (IDMLImageFrame img : group.imageFrames()) {
            double[] b = img.geometricBounds();
            double[] t = img.itemTransform();
            if (b == null || t == null) continue;
            double[] tl = CoordinateConverter.applyTransform(t, b[1], b[0]);
            double[] br = CoordinateConverter.applyTransform(t, b[3], b[2]);
            if (tl[0] < minX) minX = tl[0]; if (tl[1] < minY) minY = tl[1];
            if (br[0] > maxX) maxX = br[0]; if (br[1] > maxY) maxY = br[1];
        }
        for (IDMLVectorShape vs : group.vectorShapes()) {
            double[] b = vs.geometricBounds();
            double[] t = vs.itemTransform();
            if (b == null || t == null) continue;
            double[] tl = CoordinateConverter.applyTransform(t, b[1], b[0]);
            double[] br = CoordinateConverter.applyTransform(t, b[3], b[2]);
            if (tl[0] < minX) minX = tl[0]; if (tl[1] < minY) minY = tl[1];
            if (br[0] > maxX) maxX = br[0]; if (br[1] > maxY) maxY = br[1];
        }
        for (IDMLGroup child : group.childGroups()) {
            double[] b = child.geometricBounds();
            double[] t = child.itemTransform();
            if (b == null || t == null) continue;
            double[] tl = CoordinateConverter.applyTransform(t, b[1], b[0]);
            double[] br = CoordinateConverter.applyTransform(t, b[3], b[2]);
            if (tl[0] < minX) minX = tl[0]; if (tl[1] < minY) minY = tl[1];
            if (br[0] > maxX) maxX = br[0]; if (br[1] > maxY) maxY = br[1];
        }

        if (minX == Double.MAX_VALUE) return null;

        // 스프레드 좌표계 기준 영역을 그룹의 transform 역변환으로 로컬 좌표계로 변환
        // 그룹 transform이 단순 이동(identity + translation)인 경우만 역변환 적용
        double[] gt = group.itemTransform();
        if (gt != null) {
            // 단순 이동: a=1,b=0,c=0,d=1 → 역변환은 tx,ty 빼기
            minX -= gt[4]; minY -= gt[5];
            maxX -= gt[4]; maxY -= gt[5];
        }

        return new double[]{minY, minX, maxY, maxX};  // [top, left, bottom, right]
    }

    // ===== GeometricBounds 해석 =====

    /**
     * 요소의 GeometricBounds를 결정한다.
     * 직접 속성이 있으면 사용하고, 없으면 PathGeometry의 PathPointArray에서 계산.
     */
    static double[] resolveGeometricBounds(Element elem) {
        String boundsAttr = elem.getAttribute("GeometricBounds");
        if (boundsAttr != null && !boundsAttr.isEmpty()) {
            return IDMLGeometry.parseBounds(boundsAttr);
        }

        // PathGeometry에서 bounds 계산
        return computeBoundsFromPathGeometry(elem);
    }

    /**
     * 요소 자신의 PathGeometry에서 bounding box를 계산한다.
     */
    static double[] computeBoundsFromPathGeometry(Element elem) {
        // 요소 자신의 Properties/PathGeometry 만 탐색 (자식 도형 제외)
        Element props = getFirstChildElement(elem, "Properties");
        if (props == null) return new double[]{0, 0, 0, 0};

        Element pathGeom = getFirstChildElement(props, "PathGeometry");
        if (pathGeom == null) return new double[]{0, 0, 0, 0};

        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        for (Element pathType : getChildElements(pathGeom, "GeometryPathType")) {
            Element ppa = getFirstChildElement(pathType, "PathPointArray");
            if (ppa == null) continue;
            for (Element pp : getChildElements(ppa, "PathPointType")) {
                // Anchor, LeftDirection, RightDirection 모두 포함
                String[] attrs = {"Anchor", "LeftDirection", "RightDirection"};
                for (String attr : attrs) {
                    String val = pp.getAttribute(attr);
                    if (val == null || val.isEmpty()) continue;
                    String[] parts = val.trim().split("\\s+");
                    if (parts.length >= 2) {
                        double x = Double.parseDouble(parts[0]);
                        double y = Double.parseDouble(parts[1]);
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
            }
        }

        if (minX == Double.MAX_VALUE) {
            return new double[]{0, 0, 0, 0};
        }

        // GeometricBounds: [top, left, bottom, right]
        return new double[]{minY, minX, maxY, maxX};
    }

    /**
     * TextFrame의 PathGeometry에서 Anchor 좌표를 추출하여 저장한다.
     * 4점 사각형이 아닌 비사각형 프레임 감지에 사용된다.
     */
    private static void extractTextFramePath(Element frameElem, IDMLTextFrame frame) {
        Element props = getFirstChildElement(frameElem, "Properties");
        if (props == null) return;
        Element pathGeom = getFirstChildElement(props, "PathGeometry");
        if (pathGeom == null) return;

        java.util.List<double[]> anchors = new java.util.ArrayList<>();
        for (Element pathType : getChildElements(pathGeom, "GeometryPathType")) {
            Element ppa = getFirstChildElement(pathType, "PathPointArray");
            if (ppa == null) continue;
            for (Element pp : getChildElements(ppa, "PathPointType")) {
                String val = pp.getAttribute("Anchor");
                if (val == null || val.isEmpty()) continue;
                String[] parts = val.trim().split("\\s+");
                if (parts.length >= 2) {
                    anchors.add(new double[]{
                            Double.parseDouble(parts[0]),
                            Double.parseDouble(parts[1])});
                }
            }
            break; // 첫 번째 경로만 사용
        }
        if (anchors.size() > 4) {
            double[] px = new double[anchors.size()];
            double[] py = new double[anchors.size()];
            for (int i = 0; i < anchors.size(); i++) {
                px[i] = anchors.get(i)[0];
                py[i] = anchors.get(i)[1];
            }
            frame.localPath(px, py);
        }
    }
}
