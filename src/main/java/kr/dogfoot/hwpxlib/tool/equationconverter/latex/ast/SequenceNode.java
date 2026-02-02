package kr.dogfoot.hwpxlib.tool.equationconverter.latex.ast;

import java.util.ArrayList;
import java.util.List;

public class SequenceNode extends AstNode {
    private final List<AstNode> children;

    public SequenceNode() {
        this.children = new ArrayList<AstNode>();
    }

    public SequenceNode(List<AstNode> children) {
        this.children = children;
    }

    public AstNodeType nodeType() {
        return AstNodeType.SEQUENCE;
    }

    public List<AstNode> children() {
        return children;
    }

    public void addChild(AstNode child) {
        children.add(child);
    }
}
