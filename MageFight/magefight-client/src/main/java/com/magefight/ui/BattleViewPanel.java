package com.magefight.ui;

import com.magefight.content.model.FighterSpec;
import com.magefight.content.model.MageArchetype;
import com.turngame.domain.enums.ActionType;
import com.turngame.domain.map.BattleMap;
import com.turngame.domain.map.MapCellPosition;
import com.turngame.domain.skill.SkillEffect;
import com.turngame.domain.skill.SkillTemplate;
import com.turngame.engine.GameSession;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.util.List;
import java.util.Optional;

final class BattleViewPanel extends JPanel {
    interface Host {
        GameSession session();

        String playerId();

        String botId();

        Optional<MapCellPosition> displayedPositionOf(String playerId);

        boolean resolutionPlaybackActive();

        boolean isCastPhase();

        double currentPhaseProgress();

        List<GameSession.ResolvedActionView> currentPlaybackActions();

        boolean isPlayerHitInCurrentStep(String playerId);

        boolean isBouncedInCurrentStep(String actorId);

        boolean isActorCastingInCurrentStep(String actorId);

        boolean isDefendingInCurrentStep(String actorId);

        MapCellPosition playbackPositionBeforeOf(String actorId);

        MapCellPosition playbackPositionAfterOf(String actorId);

        FighterSpec playerSpec();

        FighterSpec botSpec();

        MageArchetype currentArchetype();

        String selectedSkillName();

        SkillTemplate findSkill(FighterSpec spec, String skillName);

        Font defaultFont();

        boolean promotionEffectActive();

        long promotionEffectStartedAtMs();

        void clearPromotionEffect();

        String promotionEffectText();

        Color playerSkinColor();

        Color playerOutfitColor();

        boolean onlineMode();

        String selfHudTag();

        String opponentHudTag();

        void onBattleCellClicked(int worldCol, int worldRow);
    }

    private static final int VIEW_MARGIN = 24;
    private static final int VIEWPORT_RADIUS = 2;
    private static final int CELL_SIZE = 72;

    private final Host host;
    private int lastStartCol;
    private int lastStartRow;
    private int lastOriginX;
    private int lastOriginY;
    private int lastCellW;
    private int lastCellH;
    private int lastViewportCols;
    private int lastViewportRows;
    private boolean hasViewportLayout;

    BattleViewPanel(Host host) {
        this.host = host;
        this.hasViewportLayout = false;
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                onGridMouseClicked(event.getX(), event.getY());
            }
        });
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();
        paintBackground(g2, w, h);

        if (host.session() == null) {
            drawInfo(g2, "Preparing battle view...", w, h);
            g2.dispose();
            return;
        }

        MapCellPosition playerPos = host.displayedPositionOf(host.playerId()).orElse(null);
        MapCellPosition enemyPos = host.displayedPositionOf(host.botId()).orElse(null);
        if (playerPos == null || enemyPos == null) {
            drawInfo(g2, "No target in sight", w, h);
            g2.dispose();
            return;
        }

        drawBattleGrid(g2, w, h, playerPos, enemyPos);
        drawInfo(g2, host.selfHudTag() + " at " + positionText(playerPos) + " | "
            + host.opponentHudTag() + " at " + positionText(enemyPos), w, h);
        g2.dispose();
    }

    private void paintBackground(Graphics2D g2, int w, int h) {
        g2.setPaint(new GradientPaint(0, 0, new Color(220, 240, 255), 0, h, new Color(58, 67, 56)));
        g2.fillRect(0, 0, w, h);
    }

    private void drawBattleGrid(Graphics2D g2, int w, int h, MapCellPosition playerPos, MapCellPosition enemyPos) {
        BattleMap map = host.session().getBattleMap();
        int rows = Math.max(1, map.rows());
        int cols = Math.max(1, map.cols());

        int viewportCols = Math.min(cols, VIEWPORT_RADIUS * 2 + 1);
        int viewportRows = Math.min(rows, VIEWPORT_RADIUS * 2 + 1);

        int startCol = BattleRenderToolkit.clamp(playerPos.col() - viewportCols / 2, 0, Math.max(0, cols - viewportCols));
        int startRow = BattleRenderToolkit.clamp(playerPos.row() - viewportRows / 2, 0, Math.max(0, rows - viewportRows));

        int availableWidth = w - VIEW_MARGIN * 2;
        int availableHeight = h - 110;
        int cellW = Math.max(CELL_SIZE, availableWidth / viewportCols);
        int cellH = Math.max(CELL_SIZE, availableHeight / viewportRows);
        int gridW = cellW * viewportCols;
        int gridH = cellH * viewportRows;
        int originX = (w - gridW) / 2;
        int originY = 70;
        rememberViewportLayout(startCol, startRow, originX, originY, cellW, cellH, viewportCols, viewportRows);

        String[] layoutRows = map.layoutRows().toArray(String[]::new);
        for (int viewRow = 0; viewRow < viewportRows; viewRow++) {
            for (int viewCol = 0; viewCol < viewportCols; viewCol++) {
                int worldCol = startCol + viewCol;
                int worldRow = startRow + viewRow;
                int x = originX + viewCol * cellW;
                int y = originY + viewRow * cellH;

                boolean playerCell = worldCol == playerPos.col() && worldRow == playerPos.row();
                boolean enemyCell = worldCol == enemyPos.col() && worldRow == enemyPos.row();
                boolean sameCell = playerCell && enemyCell;

                paintCell(g2, x, y, cellW, cellH, worldCol, worldRow, startRow, playerCell, enemyCell, sameCell, layoutRows);
                paintSkillRangeOverlay(g2, x, y, cellW, cellH, worldCol, worldRow, playerPos);

                if (playerCell) {
                    drawUnit(g2, x, y, cellW, cellH, host.playerSpec() == null ? MageArchetype.APPRENTICE : host.currentArchetype(), true, host.selfHudTag());
                    drawCastingAura(g2, x, y, cellW, cellH, host.playerId());
                    drawDefenseAura(g2, x, y, cellW, cellH, host.playerId());
                    drawBounceMarker(g2, x, y, cellW, cellH, host.playerId());
                }
                if (enemyCell) {
                    drawUnit(g2, x, y, cellW, cellH, currentOrBotArchetype(), false, host.opponentHudTag());
                    drawCastingAura(g2, x, y, cellW, cellH, host.botId());
                    drawDefenseAura(g2, x, y, cellW, cellH, host.botId());
                    drawBounceMarker(g2, x, y, cellW, cellH, host.botId());
                }

                drawMoveTrail(g2, x, y, cellW, cellH, worldCol, worldRow);
            }
        }

        drawCastProjectiles(g2, startCol, startRow, originX, originY, cellW, cellH);
    drawMissEffects(g2, startCol, startRow, originX, originY, cellW, cellH);
        drawPromotionOverlay(g2, w, h, playerPos, startCol, startRow, originX, originY, cellW, cellH);

        g2.setColor(new Color(255, 255, 255, 100));
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawRoundRect(originX - 6, originY - 6, gridW + 12, gridH + 12, 18, 18);
    }

    private void drawMissEffects(Graphics2D g2, int startCol, int startRow, int originX, int originY, int cellW, int cellH) {
        if (!host.resolutionPlaybackActive() || host.isCastPhase()) {
            return;
        }

        double pulse = host.currentPhaseProgress();
        int alpha = Math.max(70, (int) (210 * (1.0 - pulse)));

        for (GameSession.ResolvedActionView action : host.currentPlaybackActions()) {
            if (!isAimedMissAction(action)) {
                continue;
            }

            MapCellPosition missCell = new MapCellPosition(action.targetCol(), action.targetRow());
            Point2D center = BattleRenderToolkit.cellCenter(missCell, startCol, startRow, originX, originY, cellW, cellH);
            int radius = Math.max(14, Math.min(cellW, cellH) / 4);

            g2.setColor(new Color(255, 94, 94, alpha));
            g2.setStroke(new BasicStroke(3f));
            g2.drawLine((int) center.getX() - radius, (int) center.getY() - radius,
                    (int) center.getX() + radius, (int) center.getY() + radius);
            g2.drawLine((int) center.getX() + radius, (int) center.getY() - radius,
                    (int) center.getX() - radius, (int) center.getY() + radius);

            g2.setColor(new Color(255, 185, 185, Math.max(40, alpha - 60)));
            g2.drawOval((int) center.getX() - radius - 6, (int) center.getY() - radius - 6,
                    (radius + 6) * 2, (radius + 6) * 2);
        }
    }

    private boolean isAimedMissAction(GameSession.ResolvedActionView action) {
        if (action.actionType() != ActionType.USE_SKILL) {
            return false;
        }
        if (action.targetCol() == null || action.targetRow() == null || action.targetId() == null) {
            return false;
        }
        MapCellPosition targetBefore = host.playbackPositionBeforeOf(action.targetId());
        if (targetBefore == null) {
            return false;
        }
        return targetBefore.col() != action.targetCol() || targetBefore.row() != action.targetRow();
    }

    private void rememberViewportLayout(int startCol, int startRow, int originX, int originY,
                                        int cellW, int cellH, int viewportCols, int viewportRows) {
        lastStartCol = startCol;
        lastStartRow = startRow;
        lastOriginX = originX;
        lastOriginY = originY;
        lastCellW = cellW;
        lastCellH = cellH;
        lastViewportCols = viewportCols;
        lastViewportRows = viewportRows;
        hasViewportLayout = true;
    }

    private void onGridMouseClicked(int mouseX, int mouseY) {
        if (!hasViewportLayout || lastCellW <= 0 || lastCellH <= 0) {
            return;
        }
        int relX = mouseX - lastOriginX;
        int relY = mouseY - lastOriginY;
        if (relX < 0 || relY < 0) {
            return;
        }
        int viewCol = relX / lastCellW;
        int viewRow = relY / lastCellH;
        if (viewCol < 0 || viewCol >= lastViewportCols || viewRow < 0 || viewRow >= lastViewportRows) {
            return;
        }
        int worldCol = lastStartCol + viewCol;
        int worldRow = lastStartRow + viewRow;
        host.onBattleCellClicked(worldCol, worldRow);
    }

    private void drawPromotionOverlay(Graphics2D g2, int w, int h, MapCellPosition playerPos,
                                      int startCol, int startRow, int originX, int originY, int cellW, int cellH) {
        if (!host.promotionEffectActive() || playerPos == null) {
            return;
        }
        double t = (System.currentTimeMillis() - host.promotionEffectStartedAtMs()) / 1400.0;
        if (t >= 1.0) {
            host.clearPromotionEffect();
            return;
        }

        Point2D center = BattleRenderToolkit.cellCenter(playerPos, startCol, startRow, originX, originY, cellW, cellH);
        int maxRadius = Math.max(80, Math.min(w, h) / 4);
        int r = Math.max(24, (int) (maxRadius * t));
        int alpha = Math.max(0, (int) (180 * (1.0 - t)));

        g2.setColor(new Color(255, 215, 120, alpha));
        g2.fillOval((int) center.getX() - r, (int) center.getY() - r, r * 2, r * 2);

        g2.setColor(new Color(255, 245, 200, Math.max(0, alpha - 40)));
        g2.setStroke(new BasicStroke(3f));
        g2.drawOval((int) center.getX() - (r + 10), (int) center.getY() - (r + 10), (r + 10) * 2, (r + 10) * 2);

        g2.setColor(new Color(20, 24, 35, Math.max(90, alpha / 2)));
        int boxW = 220;
        int boxH = 34;
        int boxX = (w - boxW) / 2;
        int boxY = 18;
        g2.fillRoundRect(boxX, boxY, boxW, boxH, 14, 14);
        g2.setColor(new Color(255, 233, 160, Math.max(120, alpha)));
        g2.setFont(host.defaultFont().deriveFont(Font.BOLD, 15f));
        g2.drawString(host.promotionEffectText(), boxX + 62, boxY + 22);
    }

    private void drawCastProjectiles(Graphics2D g2, int startCol, int startRow, int originX, int originY, int cellW, int cellH) {
        if (!host.resolutionPlaybackActive() || !host.isCastPhase()) {
            return;
        }

        double phaseProgress = host.currentPhaseProgress();

        for (GameSession.ResolvedActionView action : host.currentPlaybackActions()) {
            if (action.actionType() != ActionType.USE_SKILL || action.targetId() == null) {
                continue;
            }

            SkillTemplate template = host.session().getSkillTemplate(action.skillName()).orElse(null);
            if (template == null) {
                continue;
            }

            MapCellPosition from = host.displayedPositionOf(action.actorId()).orElse(null);
            MapCellPosition to;
            if (action.targetCol() != null && action.targetRow() != null) {
                to = new MapCellPosition(action.targetCol(), action.targetRow());
            } else {
                to = host.displayedPositionOf(action.targetId()).orElse(null);
            }
            if (from == null || to == null) {
                continue;
            }
            if (!BattleRenderToolkit.isSkillInRangeForEffect(template, from, to)) {
                continue;
            }

            BattleRenderToolkit.ProjectileStyle style = BattleRenderToolkit.projectileStyleFor(action.skillName());
            double travelProgress = (phaseProgress - style.launchStartProgress()) / Math.max(0.001, style.launchDurationProgress());
            travelProgress = Math.max(0.0, Math.min(1.0, travelProgress));
            if (travelProgress <= 0.0) {
                continue;
            }

            Point2D fromCenter = BattleRenderToolkit.cellCenter(from, startCol, startRow, originX, originY, cellW, cellH);
            Point2D toCenter = BattleRenderToolkit.cellCenter(to, startCol, startRow, originX, originY, cellW, cellH);

            double px = fromCenter.getX() + (toCenter.getX() - fromCenter.getX()) * travelProgress;
            double py = fromCenter.getY() + (toCenter.getY() - fromCenter.getY()) * travelProgress;

            BattleRenderToolkit.drawProjectileTrail(g2, fromCenter, px, py, travelProgress, style, action.skillName());
            BattleRenderToolkit.drawProjectileCore(g2, px, py, style, action.skillName());
        }
    }

    private void paintCell(Graphics2D g2, int x, int y, int cellW, int cellH, int worldCol, int worldRow, int startRow,
                           boolean playerCell, boolean enemyCell, boolean sameCell, String[] layoutRows) {
        double depth = 1.0 - ((worldRow - startRow) / (double) Math.max(1, layoutRows.length));
        depth = Math.max(0.15, Math.min(1.0, depth));

        char tile = host.session().getTileAt(worldCol, worldRow);
        Color[] palette = switch (tile) {
            case '#' -> new Color[]{new Color(58, 58, 64), new Color(120, 122, 132)};
            case 'P' -> new Color[]{new Color(82, 70, 55), new Color(150, 128, 96)};
            case 'S' -> new Color[]{new Color(31, 76, 98), new Color(82, 172, 207)};
            default -> new Color[]{new Color(46, 61, 47), new Color(76, 106, 86)};
        };
        Color base = palette[0];
        Color highlight = palette[1];
        Color fog = new Color(8, 10, 14, 40);
        Color cellColor = BattleRenderToolkit.blend(base, highlight, 1.0 - depth * 0.65);
        g2.setColor(cellColor);
        g2.fillRoundRect(x + 3, y + 3, cellW - 6, cellH - 6, 18, 18);

        if (worldRow >= 0 && worldRow < layoutRows.length) {
            String rowText = layoutRows[worldRow];
            if (worldCol >= 0 && worldCol < rowText.length()) {
                g2.setColor(new Color(255, 255, 255, 90));
                g2.setFont(host.defaultFont().deriveFont(Font.BOLD, 12f));
                g2.drawString(String.valueOf(tile), x + 11, y + 20);
            }
        }

        switch (tile) {
            case 'S' -> {
                g2.setColor(new Color(120, 230, 255, 120));
                g2.fillRoundRect(x + 10, y + 10, cellW - 20, cellH - 20, 14, 14);
            }
            case '#', 'P' -> {
                g2.setColor(new Color(220, 220, 220, 70));
                g2.setStroke(new BasicStroke(2f));
                g2.drawLine(x + 14, y + 14, x + cellW - 14, y + cellH - 14);
                g2.drawLine(x + cellW - 14, y + 14, x + 14, y + cellH - 14);
            }
            default -> {
            }
        }

        g2.setColor(new Color(255, 255, 255, 30));
        g2.drawRoundRect(x + 3, y + 3, cellW - 6, cellH - 6, 18, 18);

        if (!playerCell && !enemyCell) {
            g2.setColor(fog);
            g2.fillRoundRect(x + 3, y + 3, cellW - 6, cellH - 6, 18, 18);
        }

        if (sameCell) {
            g2.setColor(new Color(255, 224, 130, 80));
            g2.fillRoundRect(x + 3, y + 3, cellW - 6, cellH - 6, 18, 18);
        }
    }

    private void paintSkillRangeOverlay(Graphics2D g2, int x, int y, int cellW, int cellH, int worldCol, int worldRow, MapCellPosition playerPos) {
        if (host.playerSpec() == null) {
            return;
        }

        SkillTemplate skill = host.findSkill(host.playerSpec(), host.selectedSkillName());
        if (skill == null || skill.effect() == null || playerPos == null) {
            return;
        }

        MapCellPosition targetCell = new MapCellPosition(worldCol, worldRow);
        boolean inRange = BattleRenderToolkit.isSkillInRangeForEffect(skill, playerPos, targetCell);
        if (!inRange) {
            return;
        }

        Color overlay = skill.effect().areaType() == SkillEffect.AreaType.STATIC
                ? new Color(255, 160, 84, 52)
                : new Color(95, 214, 255, 52);
        Color border = skill.effect().areaType() == SkillEffect.AreaType.STATIC
                ? new Color(255, 191, 117, 170)
                : new Color(148, 235, 255, 170);
        g2.setColor(overlay);
        g2.fillRoundRect(x + 6, y + 6, cellW - 12, cellH - 12, 16, 16);
        g2.setColor(border);
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(x + 6, y + 6, cellW - 12, cellH - 12, 16, 16);
    }

    private void drawUnit(Graphics2D g2, int x, int y, int cellW, int cellH, MageArchetype archetype, boolean player, String tag) {
        int unitW = (int) (cellW * 0.56);
        int unitH = (int) (cellH * 0.74);
        int ux = x + (cellW - unitW) / 2;
        int uy = y + (cellH - unitH) / 2 + 4;

        if (host.resolutionPlaybackActive() && host.isPlayerHitInCurrentStep(player ? host.playerId() : host.botId())) {
            int shake = (int) (Math.sin(System.currentTimeMillis() / 25.0) * 4);
            ux += shake;
        }
        if (host.resolutionPlaybackActive() && host.isBouncedInCurrentStep(player ? host.playerId() : host.botId())) {
            int bounceShake = (int) (Math.cos(System.currentTimeMillis() / 20.0) * 3);
            uy += bounceShake;
        }

        Color robeColor = player
                ? host.playerOutfitColor()
                : switch (archetype) {
            case ELEMENTALIST -> new Color(230, 114, 72);
            case RUNE_SCHOLAR -> new Color(120, 132, 219);
            case APPRENTICE -> new Color(128, 168, 220);
        };
        Color skinColor = player ? host.playerSkinColor() : new Color(245, 224, 200);

        g2.setColor(new Color(0, 0, 0, 100));
        g2.fillOval(ux + 6, uy + unitH - 8, unitW - 12, 14);

        g2.setColor(robeColor);
        int[] robeX = {ux + unitW / 2, ux + unitW - 6, ux + 6};
        int[] robeY = {uy + 12, uy + unitH, uy + unitH};
        g2.fillPolygon(robeX, robeY, 3);

        g2.setColor(skinColor);
        int headSize = Math.max(14, (int) (unitW * 0.28));
        g2.fillOval(ux + (unitW - headSize) / 2, uy + 2, headSize, headSize);

        g2.setColor(player ? new Color(255, 255, 255, 220) : new Color(255, 239, 153));
        g2.setFont(host.defaultFont().deriveFont(Font.BOLD, 11f));
        g2.drawString(tag, ux + 8, uy - 2);
    }

    private void drawCastingAura(Graphics2D g2, int x, int y, int cellW, int cellH, String actorId) {
        if (!host.resolutionPlaybackActive() || !host.isActorCastingInCurrentStep(actorId)) {
            return;
        }

        Point2D center = new Point2D.Double(x + cellW / 2.0, y + cellH / 2.0);
        int radius = Math.max(18, Math.min(cellW, cellH) / 3);
        g2.setColor(new Color(255, 208, 120, 120));
        g2.fillOval((int) center.getX() - radius, (int) center.getY() - radius, radius * 2, radius * 2);
        g2.setColor(new Color(255, 244, 201, 180));
        Stroke oldStroke = g2.getStroke();
        g2.setStroke(new BasicStroke(2f));
        g2.drawOval((int) center.getX() - radius, (int) center.getY() - radius, radius * 2, radius * 2);
        g2.setStroke(oldStroke);
    }

    private void drawDefenseAura(Graphics2D g2, int x, int y, int cellW, int cellH, String actorId) {
        if (!host.resolutionPlaybackActive() || !host.isDefendingInCurrentStep(actorId)) {
            return;
        }
        int pad = 12;
        g2.setColor(new Color(132, 214, 255, 100));
        g2.fillRoundRect(x + pad, y + pad, cellW - pad * 2, cellH - pad * 2, 16, 16);
        g2.setColor(new Color(187, 233, 255, 180));
        Stroke oldStroke = g2.getStroke();
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(x + pad, y + pad, cellW - pad * 2, cellH - pad * 2, 16, 16);
        g2.setStroke(oldStroke);
    }

    private void drawBounceMarker(Graphics2D g2, int x, int y, int cellW, int cellH, String actorId) {
        if (!host.resolutionPlaybackActive() || !host.isBouncedInCurrentStep(actorId)) {
            return;
        }
        g2.setColor(new Color(255, 138, 128, 210));
        g2.setFont(host.defaultFont().deriveFont(Font.BOLD, 20f));
        g2.drawString("!", x + cellW - 20, y + Math.max(24, cellH / 3));
    }

    private void drawMoveTrail(Graphics2D g2, int x, int y, int cellW, int cellH, int worldCol, int worldRow) {
        if (!host.resolutionPlaybackActive()) {
            return;
        }

        drawMoveTrailForActor(g2, x, y, cellW, cellH, worldCol, worldRow, host.playerId(), new Color(170, 220, 255, 170));
        drawMoveTrailForActor(g2, x, y, cellW, cellH, worldCol, worldRow, host.botId(), new Color(255, 190, 160, 170));
    }

    private void drawMoveTrailForActor(Graphics2D g2, int x, int y, int cellW, int cellH, int worldCol, int worldRow, String actorId, Color color) {
        MapCellPosition before = host.playbackPositionBeforeOf(actorId);
        MapCellPosition after = host.playbackPositionAfterOf(actorId);
        if (before == null || after == null) {
            return;
        }
        if (before.col() == after.col() && before.row() == after.row()) {
            return;
        }

        if ((worldCol == before.col() && worldRow == before.row()) || (worldCol == after.col() && worldRow == after.row())) {
            g2.setColor(color);
            g2.setStroke(new BasicStroke(3f));
            int cx = x + cellW / 2;
            int cy = y + cellH / 2;
            int radius = Math.max(6, Math.min(cellW, cellH) / 8);
            g2.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);
        }
    }

    private String positionText(MapCellPosition position) {
        return "(" + position.col() + "," + position.row() + ")";
    }

    private MageArchetype currentOrBotArchetype() {
        FighterSpec botSpec = host.botSpec();
        if (botSpec == null) {
            return MageArchetype.APPRENTICE;
        }
        String title = botSpec.title();
        if (MageArchetype.ELEMENTALIST.displayName().equals(title)) {
            return MageArchetype.ELEMENTALIST;
        }
        if (MageArchetype.RUNE_SCHOLAR.displayName().equals(title)) {
            return MageArchetype.RUNE_SCHOLAR;
        }
        return MageArchetype.APPRENTICE;
    }

    private void drawInfo(Graphics2D g2, String message, int w, int h) {
        g2.setColor(new Color(20, 24, 35, 180));
        g2.fillRoundRect(12, h - 42, Math.min(320, w - 24), 28, 12, 12);
        g2.setColor(Color.WHITE);
        g2.setFont(host.defaultFont().deriveFont(Font.BOLD, 12f));
        g2.drawString(message, 22, h - 23);
    }
}
