package com.magefight.ui;

import com.turngame.domain.map.MapCellPosition;
import com.turngame.domain.skill.SkillTemplate;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.geom.Point2D;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class BattleRenderToolkit {
    private static final Map<String, SkillVisualSpec> REGISTERED_VISUALS = new ConcurrentHashMap<>();
    private static final Map<String, BufferedImage> IMAGE_CACHE = new ConcurrentHashMap<>();

    private BattleRenderToolkit() {
    }

    record SkillVisualSpec(
            ProjectileStyle projectileStyle,
            String projectileImagePath,
            Integer projectileImageSize
    ) {
    }

    record ProjectileStyle(
            Color core,
            Color glow,
            Color trail,
            double launchStartProgress,
            double launchDurationProgress,
            float trailWidth,
            int radius
    ) {
    }

    static ProjectileStyle projectileStyleFor(String skillName) {
        String key = skillName == null ? "" : skillName.toLowerCase();

        SkillVisualSpec registered = REGISTERED_VISUALS.get(key);
        if (registered != null && registered.projectileStyle() != null) {
            return registered.projectileStyle();
        }

        if (isLightningSkillKey(key)) {
            return new ProjectileStyle(
                    new Color(252, 247, 170, 240),
                    new Color(213, 233, 255, 170),
                    new Color(194, 218, 255, 190),
                    0.80,
                    0.18,
                    2.6f,
                    5
            );
        }
        if (isIceSkillKey(key)) {
            return new ProjectileStyle(
                    new Color(171, 236, 255, 230),
                    new Color(121, 210, 247, 140),
                    new Color(170, 234, 255, 170),
                    0.56,
                    0.40,
                    2.0f,
                    6
            );
        }
        return new ProjectileStyle(
                new Color(255, 182, 86, 235),
                new Color(255, 220, 145, 135),
                new Color(255, 204, 133, 165),
                0.62,
                0.34,
                2.4f,
                7
        );
    }

    static void drawProjectileTrail(
            Graphics2D g2,
            Point2D fromCenter,
            double px,
            double py,
            double travelProgress,
            ProjectileStyle style,
            String skillName
    ) {
        String key = skillName == null ? "" : skillName.toLowerCase();

        if (isLightningSkillKey(key)) {
            drawLightningTrail(g2, fromCenter, px, py, style);
            return;
        }

        g2.setColor(style.trail());
        g2.setStroke(new BasicStroke(style.trailWidth()));
        g2.drawLine((int) fromCenter.getX(), (int) fromCenter.getY(), (int) px, (int) py);

        if (isIceSkillKey(key)) {
            g2.setColor(new Color(220, 248, 255, 160));
            int shard = Math.max(3, style.radius() / 2);
            g2.drawLine((int) px - shard, (int) py, (int) px + shard, (int) py);
            g2.drawLine((int) px, (int) py - shard, (int) px, (int) py + shard);
        } else {
            int glowR = Math.max(4, style.radius() + (int) Math.round(4 * (1.0 - travelProgress)));
            g2.setColor(new Color(style.glow().getRed(), style.glow().getGreen(), style.glow().getBlue(), 120));
            g2.fillOval((int) px - glowR, (int) py - glowR, glowR * 2, glowR * 2);
        }
    }

    static void drawProjectileCore(Graphics2D g2, double px, double py, ProjectileStyle style) {
        drawProjectileCore(g2, px, py, style, null);
    }

    static void drawProjectileCore(Graphics2D g2, double px, double py, ProjectileStyle style, String skillName) {
        BufferedImage sprite = resolveProjectileImage(skillName);
        if (sprite != null) {
            int drawSize = resolveProjectileImageSize(skillName, style);
            g2.drawImage(sprite, (int) px - drawSize / 2, (int) py - drawSize / 2, drawSize, drawSize, null);
            return;
        }
        int r = Math.max(4, style.radius());
        g2.setColor(style.glow());
        g2.fillOval((int) px - (r + 3), (int) py - (r + 3), (r + 3) * 2, (r + 3) * 2);
        g2.setColor(style.core());
        g2.fillOval((int) px - r, (int) py - r, r * 2, r * 2);
        g2.setColor(new Color(255, 255, 255, 180));
        g2.drawOval((int) px - r, (int) py - r, r * 2, r * 2);
    }

    static void registerSkillVisual(String skillName, SkillVisualSpec visualSpec) {
        if (skillName == null || skillName.isBlank() || visualSpec == null) {
            return;
        }
        REGISTERED_VISUALS.put(skillName.trim().toLowerCase(), visualSpec);
    }

    static void clearRegisteredSkillVisuals() {
        REGISTERED_VISUALS.clear();
        IMAGE_CACHE.clear();
    }

    private static BufferedImage resolveProjectileImage(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return null;
        }
        SkillVisualSpec spec = REGISTERED_VISUALS.get(skillName.trim().toLowerCase());
        if (spec == null || spec.projectileImagePath() == null || spec.projectileImagePath().isBlank()) {
            return null;
        }
        String imagePath = spec.projectileImagePath().trim();
        return IMAGE_CACHE.computeIfAbsent(imagePath, BattleRenderToolkit::loadImage);
    }

    private static int resolveProjectileImageSize(String skillName, ProjectileStyle style) {
        if (skillName != null && !skillName.isBlank()) {
            SkillVisualSpec spec = REGISTERED_VISUALS.get(skillName.trim().toLowerCase());
            if (spec != null && spec.projectileImageSize() != null && spec.projectileImageSize() > 0) {
                return spec.projectileImageSize();
            }
        }
        return Math.max(16, style.radius() * 3);
    }

    private static BufferedImage loadImage(String imagePath) {
        if (imagePath == null || imagePath.isBlank()) {
            return null;
        }
        try {
            if (imagePath.startsWith("/")) {
                try (InputStream in = BattleRenderToolkit.class.getResourceAsStream(imagePath)) {
                    return in == null ? null : ImageIO.read(in);
                }
            }
            Path path = Path.of(imagePath);
            if (!Files.exists(path)) {
                return null;
            }
            return ImageIO.read(path.toFile());
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    static boolean isSkillInRangeForEffect(SkillTemplate template, MapCellPosition actorPos, MapCellPosition targetPos) {
        if (template.effect() == null) {
            return false;
        }
        if (template.effect().areaType() == com.turngame.domain.skill.SkillEffect.AreaType.STATIC) {
            int deltaCol = targetPos.col() - actorPos.col();
            int deltaRow = targetPos.row() - actorPos.row();
            return template.effect().includesOffset(deltaCol, deltaRow);
        }
        int radius = Math.max(0, template.effect().areaRadius());
        return actorPos.chebyshevDistanceTo(targetPos) <= radius;
    }

    static Point2D cellCenter(MapCellPosition pos, int startCol, int startRow, int originX, int originY, int cellW, int cellH) {
        int viewCol = pos.col() - startCol;
        int viewRow = pos.row() - startRow;
        double cx = originX + viewCol * cellW + (cellW / 2.0);
        double cy = originY + viewRow * cellH + (cellH / 2.0);
        return new Point2D.Double(cx, cy);
    }

    static Color blend(Color a, Color b, double ratio) {
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        int red = (int) Math.round(a.getRed() * (1.0 - ratio) + b.getRed() * ratio);
        int green = (int) Math.round(a.getGreen() * (1.0 - ratio) + b.getGreen() * ratio);
        int blue = (int) Math.round(a.getBlue() * (1.0 - ratio) + b.getBlue() * ratio);
        return new Color(red, green, blue);
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean isIceSkillKey(String key) {
        return key.contains("ice") || key.contains("frost") || key.contains("빙") || key.contains("얼음");
    }

    private static boolean isLightningSkillKey(String key) {
        return key.contains("lightning")
                || key.contains("thunder")
                || key.contains("shock")
                || key.contains("번개")
                || key.contains("뢰")
                || key.contains("arc");
    }

    private static void drawLightningTrail(Graphics2D g2, Point2D fromCenter, double px, double py, ProjectileStyle style) {
        g2.setColor(style.trail());
        g2.setStroke(new BasicStroke(style.trailWidth()));
        int segments = 5;
        int prevX = (int) fromCenter.getX();
        int prevY = (int) fromCenter.getY();
        for (int i = 1; i <= segments; i++) {
            double t = i / (double) segments;
            int x = (int) (fromCenter.getX() + (px - fromCenter.getX()) * t);
            int y = (int) (fromCenter.getY() + (py - fromCenter.getY()) * t);
            if (i < segments) {
                int jitter = (i % 2 == 0 ? 1 : -1) * 6;
                y += jitter;
            }
            g2.drawLine(prevX, prevY, x, y);
            prevX = x;
            prevY = y;
        }
    }
}
