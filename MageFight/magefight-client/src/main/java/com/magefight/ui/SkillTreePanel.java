package com.magefight.ui;

import com.magefight.content.model.MageArchetype;
import com.magefight.content.model.MageSkillTree;
import com.magefight.content.model.SkillTreeNode;
import com.magefight.content.progress.MageProgress;

import javax.swing.JPanel;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JScrollBar;
import javax.swing.Scrollable;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import java.awt.Cursor;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.geom.Point2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public class SkillTreePanel extends JPanel implements Scrollable {
    private static final Font TITLE_FONT = new Font("Malgun Gothic", Font.BOLD, 16);
    private static final Font BODY_FONT = new Font("Malgun Gothic", Font.PLAIN, 13);
    private static final Font SMALL_FONT = new Font("Malgun Gothic", Font.PLAIN, 11);

    private MageProgress progress;
    private MageSkillTree tree;
    private Consumer<SkillTreeNode> nodeClickHandler = node -> { };
    private String footerMessage = "스킬 노드를 클릭하면 영감을 소비해 습득할 수 있습니다.";
    private final Map<String, Shape> nodeBounds = new HashMap<>();
    private Point2D.Double dragAnchor;
    private java.awt.Point scrollAnchor;

    public SkillTreePanel() {
        setPreferredSize(new Dimension(1280, 980));
        setMinimumSize(new Dimension(1080, 840));
        setOpaque(false);
        setAutoscrolls(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                onClick(e);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                dragAnchor = new Point2D.Double(e.getX(), e.getY());
                scrollAnchor = currentScrollPosition();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragAnchor = null;
                scrollAnchor = null;
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                onDrag(e);
            }
        });
    }

    public void setContext(MageProgress progress, MageSkillTree tree) {
        this.progress = progress;
        this.tree = tree;
        revalidate();
        repaint();
    }

    public void setNodeClickHandler(Consumer<SkillTreeNode> nodeClickHandler) {
        this.nodeClickHandler = nodeClickHandler == null ? node -> { } : nodeClickHandler;
    }

    public void setFooterMessage(String footerMessage) {
        this.footerMessage = footerMessage == null ? "" : footerMessage;
        repaint();
    }

    private void onClick(MouseEvent event) {
        if (tree == null || progress == null) {
            return;
        }

        if (dragAnchor != null && dragDistance(event) > 6.0) {
            return;
        }

        for (SkillTreeNode node : tree.nodes()) {
            Shape shape = nodeBounds.get(node.skill().name());
            if (shape != null && shape.contains(event.getPoint())) {
                nodeClickHandler.accept(node);
                return;
            }
        }
    }

    private void onDrag(MouseEvent event) {
        if (dragAnchor == null || scrollAnchor == null) {
            return;
        }

        JScrollPane scrollPane = findScrollPane();
        if (scrollPane == null) {
            return;
        }

        int nextX = scrollAnchor.x - (int) (event.getX() - dragAnchor.x);
        int nextY = scrollAnchor.y - (int) (event.getY() - dragAnchor.y);
        JScrollBar horizontal = scrollPane.getHorizontalScrollBar();
        JScrollBar vertical = scrollPane.getVerticalScrollBar();
        if (horizontal != null) {
            horizontal.setValue(clamp(nextX, horizontal.getMinimum(), horizontal.getMaximum() - horizontal.getVisibleAmount()));
        }
        if (vertical != null) {
            vertical.setValue(clamp(nextY, vertical.getMinimum(), vertical.getMaximum() - vertical.getVisibleAmount()));
        }
    }

    private double dragDistance(MouseEvent event) {
        if (dragAnchor == null) {
            return 0.0;
        }
        double dx = event.getX() - dragAnchor.x;
        double dy = event.getY() - dragAnchor.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private JViewport findViewport() {
        Component parent = getParent();
        while (parent != null && !(parent instanceof JViewport)) {
            parent = parent.getParent();
        }
        return (JViewport) parent;
    }

    private JScrollPane findScrollPane() {
        return (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, this);
    }

    private java.awt.Point currentScrollPosition() {
        JScrollPane scrollPane = findScrollPane();
        if (scrollPane == null) {
            return null;
        }
        JScrollBar horizontal = scrollPane.getHorizontalScrollBar();
        JScrollBar vertical = scrollPane.getVerticalScrollBar();
        int x = horizontal == null ? 0 : horizontal.getValue();
        int y = vertical == null ? 0 : vertical.getValue();
        return new java.awt.Point(x, y);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int width = getWidth();
        int height = getHeight();
        paintBackground(g2, width, height);

        if (tree == null || progress == null) {
            paintEmptyState(g2, width, height);
            g2.dispose();
            return;
        }

        nodeBounds.clear();
        Map<String, Point2D.Double> positions = layoutNodes(width, height);
        paintHeader(g2, width);
        paintLinks(g2, positions);
        paintNodes(g2, positions);
        paintFooter(g2, width, height);

        g2.dispose();
    }

    private void paintBackground(Graphics2D g2, int width, int height) {
        g2.setPaint(new GradientPaint(0, 0, new Color(22, 30, 45), 0, height, new Color(11, 15, 23)));
        g2.fillRoundRect(0, 0, width, height, 28, 28);

        g2.setColor(new Color(255, 255, 255, 18));
        for (int i = 0; i < width; i += 22) {
            g2.drawLine(i, 0, i + 64, height);
        }
    }

    private void paintEmptyState(Graphics2D g2, int width, int height) {
        g2.setColor(new Color(255, 255, 255, 180));
        g2.setFont(TITLE_FONT);
        drawCentered(g2, "Skill Tree", width / 2, height / 2 - 10);
        g2.setFont(BODY_FONT);
        drawCentered(g2, "로그인 후 진행 상태가 표시됩니다.", width / 2, height / 2 + 18);
    }

    private void paintHeader(Graphics2D g2, int width) {
        g2.setFont(TITLE_FONT);
        g2.setColor(new Color(252, 253, 255));
        String title = "Progress: Lv " + progress.level() + " / Wins " + progress.wins();
        g2.drawString(title, 18, 28);

        g2.setFont(BODY_FONT);
        String selected = progress.selectedArchetype() == null ? "마도수련생" : progress.selectedArchetype().displayName();
        g2.setColor(new Color(203, 222, 255));
        g2.drawString(selected + " (Inspiration " + progress.inspirationPoints() + ")", 18, 49);

        g2.setColor(new Color(255, 255, 255, 120));
        g2.drawLine(18, 58, width - 18, 58);
    }

    private void paintLinks(Graphics2D g2, Map<String, Point2D.Double> positions) {
        Stroke oldStroke = g2.getStroke();
        g2.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (SkillTreeNode node : tree.nodes()) {
            Point2D.Double from = positions.get(node.skill().name());
            if (from == null) {
                continue;
            }
            for (String prereq : node.prerequisiteSkills()) {
                Point2D.Double to = positions.get(prereq);
                if (to == null) {
                    continue;
                }
                boolean unlocked = progress.hasLearnedSkill(node.skill().name()) && progress.hasLearnedSkill(prereq);
                g2.setColor(unlocked ? new Color(125, 226, 255, 175) : new Color(132, 143, 158, 90));
                g2.draw(new Line2D.Double(to.x, to.y, from.x, from.y));
            }
        }
        g2.setStroke(oldStroke);
    }

    private void paintNodes(Graphics2D g2, Map<String, Point2D.Double> positions) {
        for (SkillTreeNode node : tree.nodes()) {
            Point2D.Double point = positions.get(node.skill().name());
            if (point == null) {
                continue;
            }

            boolean learned = progress.hasLearnedSkill(node.skill().name());
            boolean learnable = progress.canLearnSkill(node, tree);
            Color fill = learned
                    ? new Color(54, 196, 169)
                    : learnable
                    ? new Color(255, 183, 77)
                    : new Color(90, 98, 118);
            Color border = learned
                    ? new Color(220, 255, 246)
                    : learnable
                    ? new Color(255, 244, 219)
                    : new Color(177, 182, 194, 170);

            Shape shape = new RoundRectangle2D.Double(point.x - 90, point.y - 46, 180, 92, 26, 26);
            nodeBounds.put(node.skill().name(), shape);

            if (!learned) {
                g2.setColor(new Color(255, 255, 255, 20));
                g2.fill(new Ellipse2D.Double(point.x - 98, point.y - 54, 196, 108));
            }

            g2.setColor(new Color(0, 0, 0, 90));
            g2.fill(shape);

            g2.setColor(fill);
            g2.fill(shape);
            g2.setColor(border);
            g2.setStroke(new BasicStroke(2.5f));
            g2.draw(shape);

            if (learnable && !learned) {
                g2.setColor(new Color(255, 255, 255, 120));
                g2.draw(new Ellipse2D.Double(point.x - 100, point.y - 56, 200, 112));
            }

            paintRangePreview(g2, node, point);
            paintNodeText(g2, node, point, learned, learnable);
        }
    }

    private void paintNodeText(Graphics2D g2, SkillTreeNode node, Point2D.Double point, boolean learned, boolean learnable) {
        g2.setColor(learned ? new Color(12, 24, 26) : Color.WHITE);
        g2.setFont(BODY_FONT.deriveFont(Font.BOLD, 13f));
        drawCentered(g2, node.skill().name(), (int) point.x - 28, (int) point.y - 18);

        g2.setFont(SMALL_FONT);
        g2.setColor(learned ? new Color(12, 24, 26, 200) : new Color(255, 255, 255, 215));
        drawCentered(g2, "Lv " + progress.getSkillMastery(node.skill().name()), (int) point.x - 28, (int) point.y + 3);
        drawCentered(g2, "Insp " + node.inspirationCost(), (int) point.x - 28, (int) point.y + 18);

        if (!learned && !learnable) {
            g2.setColor(new Color(255, 255, 255, 95));
            g2.setFont(SMALL_FONT.deriveFont(Font.BOLD));
            drawCentered(g2, "안개 속", (int) point.x - 28, (int) point.y + 34);
        } else if (learnable && !learned) {
            g2.setColor(new Color(255, 250, 236));
            g2.setFont(SMALL_FONT.deriveFont(Font.BOLD));
            drawCentered(g2, "클릭하여 습득", (int) point.x - 28, (int) point.y + 34);
        }
    }

    private void paintRangePreview(Graphics2D g2, SkillTreeNode node, Point2D.Double point) {
        int cellSize = 8;
        int gridSize = 7;
        int previewWidth = gridSize * cellSize;
        int previewHeight = gridSize * cellSize;
        int startX = (int) point.x + 22;
        int startY = (int) point.y - previewHeight / 2;

        g2.setColor(new Color(0, 0, 0, 70));
        g2.fillRoundRect(startX - 4, startY - 4, previewWidth + 8, previewHeight + 8, 10, 10);

        int radius = Math.max(0, Math.min(3, node.skill().effect().areaRadius()));
        int center = gridSize / 2;
        for (int row = 0; row < gridSize; row++) {
            for (int col = 0; col < gridSize; col++) {
                int dx = Math.abs(col - center);
                int dy = Math.abs(row - center);
                boolean active = switch (node.skill().effect().areaType()) {
                    case STATIC -> dx + dy <= radius;
                    case DYNAMIC -> Math.max(dx, dy) <= radius;
                };
                if (active) {
                    g2.setColor(progress.hasLearnedSkill(node.skill().name())
                            ? new Color(92, 232, 209, 220)
                            : new Color(255, 187, 89, 210));
                } else {
                    g2.setColor(new Color(255, 255, 255, 25));
                }
                g2.fillRect(startX + col * cellSize, startY + row * cellSize, cellSize - 1, cellSize - 1);
            }
        }

        g2.setColor(new Color(255, 255, 255, 120));
        g2.drawRoundRect(startX - 2, startY - 2, previewWidth + 4, previewHeight + 4, 8, 8);
    }

    private void paintFooter(Graphics2D g2, int width, int height) {
        g2.setColor(new Color(0, 0, 0, 130));
        g2.fillRoundRect(18, height - 54, width - 36, 36, 18, 18);
        g2.setColor(new Color(255, 255, 255, 220));
        g2.setFont(SMALL_FONT);
        g2.drawString(footerMessage, 30, height - 31);
    }

    private Map<String, Point2D.Double> layoutNodes(int width, int height) {
        Map<String, Point2D.Double> positions = new HashMap<>();
        if (tree == null) {
            return positions;
        }

        Point2D.Double center = new Point2D.Double(width * 0.50, height * 0.48);
        for (SkillTreeNode node : tree.nodes()) {
            positions.put(node.skill().name(), positionFor(node, center, width, height));
        }
        return positions;
    }

    private Point2D.Double positionFor(SkillTreeNode node, Point2D.Double center, int width, int height) {
        String key = node.skill().name().toLowerCase();
        if (key.contains("마나 방출")) {
            return center;
        }
        if (key.contains("firebolt")) {
            return new Point2D.Double(width * 0.22, height * 0.45);
        }
        if (key.contains("arcane sigil") || key.contains("rune ward")) {
            return new Point2D.Double(width * 0.74, height * 0.36);
        }
        if (key.contains("blink cut")) {
            return new Point2D.Double(width * 0.74, height * 0.66);
        }
        if (key.contains("mana burst")) {
            return new Point2D.Double(width * 0.52, height * 0.80);
        }

        int hash = Math.abs(Objects.hash(node.skill().name(), node.branchId()));
        double angle = Math.toRadians(35 + (hash % 250));
        double radius = 95 + (hash % 3) * 76;
        return new Point2D.Double(
                center.x + Math.cos(angle) * radius,
                center.y + Math.sin(angle) * radius * 0.82
        );
    }

    private void drawCentered(Graphics2D g2, String text, int centerX, int baselineY) {
        int textWidth = g2.getFontMetrics().stringWidth(text);
        g2.drawString(text, centerX - textWidth / 2, baselineY);
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(java.awt.Rectangle visibleRect, int orientation, int direction) {
        return 24;
    }

    @Override
    public int getScrollableBlockIncrement(java.awt.Rectangle visibleRect, int orientation, int direction) {
        return 120;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return false;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}