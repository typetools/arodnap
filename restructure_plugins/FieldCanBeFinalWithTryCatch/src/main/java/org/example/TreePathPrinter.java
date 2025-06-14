package org.example;

import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreePath;
import com.sun.source.tree.Tree;

public class TreePathPrinter {

    public static String getTreePathString(TreePath path) {
        StringBuilder sb = new StringBuilder();
        sb.append("TreePath:\n");
        while (path != null) {
            Tree tree = path.getLeaf();
            sb.append(tree.getKind()).append(": ").append(tree.toString()).append("\n");
            path = path.getParentPath();
        }
        return sb.toString();
    }

    public static String getMethodName(TreePath path) {
        while (path != null) {
            Tree tree = path.getLeaf();
            if (tree instanceof MethodTree) {
                return ((MethodTree) tree).getName().toString();
            }
            path = path.getParentPath();
        }
        return null; // Return null if no MethodTree is found in the path
    }
}