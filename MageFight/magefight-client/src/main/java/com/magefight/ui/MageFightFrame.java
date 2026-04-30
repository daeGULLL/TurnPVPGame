package com.magefight.ui;

import com.magefight.content.factory.GamePresetFactory;
import com.magefight.content.model.FighterSpec;
import com.magefight.content.model.MageArchetype;
import com.magefight.content.model.MageSkillTree;
import com.magefight.content.model.SkillTreeNode;
import com.turngame.domain.skill.SkillTemplate;
import com.magefight.content.progress.ArchetypeUnlockService;
import com.magefight.content.progress.MageProgress;
import com.turngame.domain.PlayerState;
import com.turngame.domain.map.BattleMap;
import com.turngame.domain.map.MapCellPosition;
import com.turngame.domain.skill.SkillEffect;
import com.turngame.engine.GameSession;
import com.turngame.engine.TurnManager;
import com.turngame.engine.command.AttackAction;
import com.turngame.engine.command.DefendAction;
import com.turngame.engine.command.EndTurnAction;
import com.turngame.engine.command.GameAction;
import com.turngame.engine.command.MoveAction;
import com.turngame.engine.command.UseSkillAction;
import com.turngame.engine.rules.BasicRuleSet;
import com.turngame.event.EventBus;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.ScrollPaneConstants;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Dimension;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Optional;
import java.util.function.Consumer;

public class MageFightFrame extends JFrame {
    private static final String PLAYER_ID = "p-1";
    private static final String BOT_ID = "p-2";

    private final GamePresetFactory presetFactory = new GamePresetFactory();
    private final Consumer<MageProgress> progressSaver;
    private MageProgress progress;

    private GameSession session;
    private FighterSpec playerSpec;
    private FighterSpec botSpec;
    private BattleMap combatMap;
    private MageArchetype currentArchetype = MageArchetype.APPRENTICE;
    private final Map<String, FighterSpec> specs = new HashMap<>();
    private final Map<String, Map<String, Integer>> cooldowns = new HashMap<>();
    private final Random random = new Random();
    private boolean elementalPathPromptShown;
    private boolean battleReturnHandled;

    private final JLabel mapLabel = new JLabel();
    private final JLabel turnLabel = new JLabel();
    private final JLabel playerLabel = new JLabel();
    private final JLabel botLabel = new JLabel();
    private final JTextArea cooldownArea = new JTextArea();
    private final JTextArea logArea = new JTextArea();
    private final JComboBox<String> skillCombo = new JComboBox<>();
    private final JComboBox<MageArchetype> archetypeCombo = new JComboBox<>();
    private final JPanel firstPersonView = new FirstPersonBattlePanel();
    private final SkillTreePanel skillTreePanel = new SkillTreePanel();

    public MageFightFrame() {
        this(MageProgress.starter(), null, progress -> {});
    }

    public MageFightFrame(MageProgress progress, String accountId, Consumer<MageProgress> progressSaver) {
        super("MageFight - Visible Battle");

        this.progress = progress == null ? MageProgress.starter() : progress;
        this.progressSaver = progressSaver == null ? ignored -> {} : progressSaver;
        this.battleReturnHandled = false;
        if (this.progress.selectedArchetype() != null) {
            this.currentArchetype = this.progress.selectedArchetype();
        }

        applyGlobalFont();

        initUi();
        applyFontRecursively(this, DEFAULT_FONT);
        refreshArchetypeUi();
        startNewMatch(currentArchetype);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                persistProgress();
            }
        });
    }

    private static final Font DEFAULT_FONT = new Font("Malgun Gothic", Font.PLAIN, 14);

    private void applyGlobalFont() {
        UIManager.put("Label.font", DEFAULT_FONT);
        UIManager.put("Button.font", DEFAULT_FONT);
        UIManager.put("TextArea.font", DEFAULT_FONT);
        UIManager.put("TextField.font", DEFAULT_FONT);
        UIManager.put("ComboBox.font", DEFAULT_FONT);
        UIManager.put("TitledBorder.font", DEFAULT_FONT);
        UIManager.put("Panel.font", DEFAULT_FONT);
    }

    private void initUi() {
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(920, 640);
        setLocationRelativeTo(null);
        setFont(DEFAULT_FONT);
        setLayout(new BorderLayout(8, 8));

        JPanel topPanel = new JPanel(new GridLayout(2, 1));
        mapLabel.setFont(DEFAULT_FONT.deriveFont(Font.BOLD, 16f));
        turnLabel.setFont(DEFAULT_FONT.deriveFont(Font.BOLD, 16f));
        topPanel.add(mapLabel);
        topPanel.add(turnLabel);
        add(topPanel, BorderLayout.NORTH);

        JPanel centerPanel = new JPanel(new BorderLayout(8, 8));
        firstPersonView.setBorder(BorderFactory.createTitledBorder("First Person View"));
        centerPanel.add(firstPersonView, BorderLayout.CENTER);

        JPanel statusPanel = new JPanel(new GridLayout(1, 2, 10, 10));
        statusPanel.add(createStatusCard("Player", playerLabel, new Color(235, 248, 255)));
        statusPanel.add(createStatusCard("Bot", botLabel, new Color(255, 245, 238)));
        centerPanel.add(statusPanel, BorderLayout.SOUTH);
        add(centerPanel, BorderLayout.CENTER);

        JPanel rightPanel = new JPanel(new BorderLayout(6, 6));
        cooldownArea.setEditable(false);
        cooldownArea.setFont(DEFAULT_FONT);
        cooldownArea.setLineWrap(true);
        cooldownArea.setWrapStyleWord(true);

        JPanel archetypePanel = new JPanel(new BorderLayout(6, 6));
        archetypePanel.setBorder(BorderFactory.createTitledBorder("Archetype (Unlock by Conditions)"));

        JPanel pickPanel = new JPanel();
        pickPanel.setLayout(new BoxLayout(pickPanel, BoxLayout.X_AXIS));
        JButton newMatchBtn = new JButton("Start Match");
        JButton checkUnlockBtn = new JButton("Check Unlock");
        pickPanel.add(archetypeCombo);
        pickPanel.add(newMatchBtn);
        pickPanel.add(checkUnlockBtn);
        archetypePanel.add(pickPanel, BorderLayout.NORTH);
        skillTreePanel.setNodeClickHandler(this::onSkillTreeNodeClicked);
        JScrollPane skillTreeScroll = new JScrollPane(skillTreePanel);
        skillTreeScroll.setPreferredSize(new Dimension(360, 320));
        skillTreeScroll.setMinimumSize(new Dimension(320, 260));
        skillTreeScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        skillTreeScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        skillTreeScroll.getViewport().setBackground(new Color(16, 20, 32));
        archetypePanel.add(skillTreeScroll, BorderLayout.CENTER);

        skillCombo.addActionListener(e -> firstPersonView.repaint());

        rightPanel.setBorder(BorderFactory.createTitledBorder("Battle Info"));
        rightPanel.add(archetypePanel, BorderLayout.NORTH);
        JScrollPane cooldownScroll = new JScrollPane(cooldownArea);
        cooldownScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        rightPanel.add(cooldownScroll, BorderLayout.CENTER);

        newMatchBtn.addActionListener(e -> {
            MageArchetype selected = (MageArchetype) archetypeCombo.getSelectedItem();
            if (selected == null) {
                log("선택 가능한 아키타입이 없습니다.");
                return;
            }
            if (!progress.hasSelectedArchetype()) {
                if (!ArchetypeUnlockService.trySelect(selected, progress)) {
                    log("호칭 선택 실패: " + ArchetypeUnlockService.lockReason(selected, progress).orElse("조건 불일치"));
                    refreshArchetypeUi();
                    return;
                }
                log("호칭 선택: " + selected.displayName() + " (Tier " + selected.tier() + ")");
                persistProgress();
            } else if (!progress.isSelected(selected)) {
                log("이미 호칭이 고정되어 변경할 수 없습니다: " + progress.selectedArchetype().displayName());
                return;
            }
            startNewMatch(selected);
        });

        checkUnlockBtn.addActionListener(e -> {
            List<MageArchetype> selectable = ArchetypeUnlockService.autoUnlockEligible(progress);
            if (selectable.isEmpty()) {
                if (progress.selectedArchetype() != null) {
                    log("이미 선택된 호칭: " + progress.selectedArchetype().displayName());
                } else {
                    log("현재 선택 가능한 호칭이 없습니다.");
                }
            } else {
                for (MageArchetype archetype : selectable) {
                    log("선택 가능: " + archetype.displayName() + " (Tier " + archetype.tier() + ")");
                }
            }
            refreshArchetypeUi();
        });

        add(rightPanel, BorderLayout.EAST);

        JPanel bottomPanel = new JPanel(new BorderLayout(8, 8));
        bottomPanel.add(createControlPanel(), BorderLayout.NORTH);

        logArea.setEditable(false);
        logArea.setFont(DEFAULT_FONT);
        logArea.setLineWrap(true);
        logArea.setRows(8);
        logArea.setWrapStyleWord(true);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        bottomPanel.add(logScroll, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    private JPanel createStatusCard(String title, JLabel contentLabel, Color bg) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.setBackground(bg);

        contentLabel.setFont(DEFAULT_FONT.deriveFont(Font.BOLD, 15f));
        panel.add(contentLabel, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createControlPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));

        JButton attackBtn = new JButton("Attack");
        JButton defendBtn = new JButton("Defend");
        JButton moveBtn = new JButton("Move");
        JButton skillBtn = new JButton("Use Skill");
        JButton endTurnBtn = new JButton("End Turn");

        attackBtn.addActionListener(e -> onAttack());
        defendBtn.addActionListener(e -> onDefend());
        moveBtn.addActionListener(e -> onMove());
        skillBtn.addActionListener(e -> onUseSkill());
        endTurnBtn.addActionListener(e -> onEndTurn());

        panel.add(attackBtn);
        panel.add(defendBtn);
        panel.add(moveBtn);
        panel.add(skillCombo);
        panel.add(skillBtn);
        panel.add(endTurnBtn);
        return panel;
    }

    private void onMove() {
        if (!validateMyTurn()) {
            return;
        }

        Optional<MoveDirection> direction = promptMoveDirection();
        if (direction.isEmpty()) {
            return;
        }

        boolean moved = tryMoveInDirection(PLAYER_ID, direction.get(), "Player");
        if (!moved) {
            log("이동할 수 없습니다. (범위, 점유, 에너지, 쿨타임 확인)");
        }
    }

    private void onAttack() {
        if (!validateMyTurn()) {
            return;
        }
        int damage = 5 + playerSpec.attackBonus();
        executeAction(new AttackAction(PLAYER_ID, BOT_ID, damage), "Player uses Attack for " + damage + " dmg.");
    }

    private void onDefend() {
        if (!validateMyTurn()) {
            return;
        }
        executeAction(new DefendAction(PLAYER_ID), "Player uses Defend.");
    }

    private void onUseSkill() {
        if (!validateMyTurn()) {
            return;
        }

        String skillName = String.valueOf(skillCombo.getSelectedItem());
        SkillTemplate skill = findSkill(playerSpec, skillName);
        if (skill == null) {
            log("Skill not found: " + skillName);
            return;
        }

        int remain = cooldowns.get(PLAYER_ID).getOrDefault(skill.name(), 0);
        if (remain > 0) {
            log("Skill " + skill.name() + " is on cooldown: " + remain + " turns left.");
            return;
        }

        int damage = Math.max(5, Math.min(80, 10 + playerSpec.attackBonus() + skill.baseDamage()));
        cooldowns.get(PLAYER_ID).put(skill.name(), skill.cooldownTurns());
        if (executeAction(new UseSkillAction(PLAYER_ID, BOT_ID, skill.name(), damage),
                "Player casts " + skill.name() + " for " + damage + " dmg.")) {
            int gainedLevels = progress.recordSkillUse(skill.name());
            if (gainedLevels > 0) {
                log(skill.name() + " mastery increased by " + gainedLevels + "; inspiration +" + gainedLevels + ".");
                maybePromptPathShift();
            }
            persistProgress();
            refreshArchetypeUi();
        }
    }

    private void onEndTurn() {
        if (!validateMyTurn()) {
            return;
        }
        if (executeAction(new EndTurnAction(PLAYER_ID), "Player ends turn.")) {
            refreshUi();
            scheduleBotTurn();
        }
    }

    private boolean executeAction(GameAction action, String logLine) {
        try {
            session.submitAction(action);
            if (session.consumeWindowAdvancedFlag()) {
                tickCooldowns(PLAYER_ID);
                tickCooldowns(BOT_ID);
                log("Window advanced -> cooldowns ticked.");
            }
            log(logLine);
            refreshUi();
            if (session.isFinished()) {
                showWinner();
            }
            return true;
        } catch (RuntimeException ex) {
            log("Action rejected: " + ex.getMessage());
            return false;
        }
    }

    private boolean validateMyTurn() {
        if (session.isFinished()) {
            showWinner();
            return false;
        }
        if (session.isPlayerReady(PLAYER_ID)) {
            log("이미 준비 완료 상태입니다. 다음 윈도우를 기다리세요.");
            return false;
        }
        return true;
    }

    private void scheduleBotTurn() {
        Timer botTimer = new Timer(700, e -> runBotTurn());
        botTimer.setRepeats(false);
        botTimer.start();
    }

    private void runBotTurn() {
        if (session.isFinished() || session.isPlayerReady(BOT_ID)) {
            return;
        }

        SkillTemplate availableSkill = botSpec.skills().stream()
                .filter(s -> cooldowns.get(BOT_ID).getOrDefault(s.name(), 0) == 0)
                .findFirst()
                .orElse(null);

        int roll = random.nextInt(100);
        if (roll < 40 && tryMoveToward(BOT_ID, PLAYER_ID, "Bot")) {
            log("Bot repositions toward the player.");
        } else if (availableSkill != null && roll < 75) {
            int skillDamage = Math.max(5, Math.min(80, 10 + botSpec.attackBonus() + availableSkill.baseDamage()));
            cooldowns.get(BOT_ID).put(availableSkill.name(), availableSkill.cooldownTurns());
            executeAction(new UseSkillAction(BOT_ID, PLAYER_ID, availableSkill.name(), skillDamage),
                    "Bot casts " + availableSkill.name() + " for " + skillDamage + " dmg.");
        } else if (roll < 90) {
            int damage = 5 + botSpec.attackBonus();
            executeAction(new AttackAction(BOT_ID, PLAYER_ID, damage), "Bot attacks for " + damage + " dmg.");
        } else {
            if (!tryMoveToward(BOT_ID, PLAYER_ID, "Bot") && availableSkill != null) {
                int skillDamage = Math.max(5, Math.min(80, 10 + botSpec.attackBonus() + availableSkill.baseDamage()));
                cooldowns.get(BOT_ID).put(availableSkill.name(), availableSkill.cooldownTurns());
                executeAction(new UseSkillAction(BOT_ID, PLAYER_ID, availableSkill.name(), skillDamage),
                        "Bot casts " + availableSkill.name() + " for " + skillDamage + " dmg.");
            } else {
                executeAction(new DefendAction(BOT_ID), "Bot defends.");
            }
        }

        if (!session.isFinished() && !session.isPlayerReady(BOT_ID)) {
            if (executeAction(new EndTurnAction(BOT_ID), "Bot ends turn.")) {
                refreshUi();
            }
        }
    }

    private void tickCooldowns(String playerId) {
        Map<String, Integer> map = cooldowns.get(playerId);
        if (map == null) {
            return;
        }

        map.replaceAll((k, v) -> Math.max(0, v - 1));
    }

    private Map<String, Integer> initCooldowns(FighterSpec spec) {
        Map<String, Integer> map = new HashMap<>();
        for (SkillTemplate skill : spec.skills()) {
            map.put(skill.name(), 0);
        }
        return map;
    }

    private SkillTemplate findSkill(FighterSpec spec, String skillName) {
        return spec.skills().stream()
                .filter(s -> s.name().equalsIgnoreCase(skillName))
                .findFirst()
                .orElse(null);
    }

    private void refreshUi() {
        PlayerState p1 = session.getPlayerState(PLAYER_ID);
        PlayerState p2 = session.getPlayerState(BOT_ID);
        String p1Pos = session.getPlayerPosition(PLAYER_ID).map(pos -> pos.col() + "," + pos.row()).orElse("?,?");
        String p2Pos = session.getPlayerPosition(BOT_ID).map(pos -> pos.col() + "," + pos.row()).orElse("?,?");

        mapLabel.setText("Map: " + combatMap.name());
        turnLabel.setText("Window: " + session.getCurrentWindowIndex() + " | Ready: " + session.getReadyPlayers());

        playerLabel.setText("HP=" + p1.hp() + "/" + p1.maxHp()
            + " | EN=" + p1.energy() + "/" + p1.maxEnergy()
            + " | TurnEN=" + (p1.maxEnergySpendPerWindow() == Integer.MAX_VALUE ? "∞/∞" : Math.max(0, p1.maxEnergySpendPerWindow() - p1.energySpentInWindow()) + "/" + p1.maxEnergySpendPerWindow())
            + " | Defending=" + p1.isDefending()
            + " | Pos=" + p1Pos
            + " | Class=" + playerSpec.title());
        botLabel.setText("HP=" + p2.hp() + "/" + p2.maxHp()
            + " | EN=" + p2.energy() + "/" + p2.maxEnergy()
            + " | TurnEN=" + (p2.maxEnergySpendPerWindow() == Integer.MAX_VALUE ? "∞/∞" : Math.max(0, p2.maxEnergySpendPerWindow() - p2.energySpentInWindow()) + "/" + p2.maxEnergySpendPerWindow())
            + " | Defending=" + p2.isDefending()
            + " | Pos=" + p2Pos
            + " | Class=" + botSpec.title());

        StringBuilder sb = new StringBuilder();
        sb.append("Player skills\n");
        appendCooldowns(sb, PLAYER_ID, playerSpec);
        sb.append("\nBot skills\n");
        appendCooldowns(sb, BOT_ID, botSpec);
        cooldownArea.setText(sb.toString());
        firstPersonView.repaint();
        skillTreePanel.repaint();
    }

    private void appendCooldowns(StringBuilder sb, String playerId, FighterSpec spec) {
        Map<String, Integer> cdMap = cooldowns.get(playerId);
        for (SkillTemplate skill : spec.skills()) {
            int remain = cdMap.getOrDefault(skill.name(), 0);
            sb.append("- ").append(skill.name())
                    .append(" (cd ").append(skill.cooldownTurns())
                    .append(") => ").append(remain).append("\n");
        }
    }

    private void log(String message) {
        logArea.append(message + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void showWinner() {
        String winner = session.getWinnerId();
        if (winner == null) {
            return;
        }

        if (battleReturnHandled) {
            return;
        }
        battleReturnHandled = true;

        String text = PLAYER_ID.equals(winner) ? "You Win!" : "Bot Wins";
        log("Game Ended. Winner: " + winner);
        if (PLAYER_ID.equals(winner)) {
            progress.registerWin();
            persistProgress();
            List<MageArchetype> selectable = ArchetypeUnlockService.autoUnlockEligible(progress);
            for (MageArchetype archetype : selectable) {
                log("선택 가능: " + archetype.displayName() + " (Tier " + archetype.tier() + ")");
            }
            refreshArchetypeUi();
        }
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, text, "Result", JOptionPane.INFORMATION_MESSAGE);
            dispose();
        });
    }

    private void startNewMatch(MageArchetype archetype) {
        this.currentArchetype = archetype;
        this.elementalPathPromptShown = false;
        this.battleReturnHandled = false;
        this.playerSpec = presetFactory.createPlayerSpec(archetype, progress);

        MageArchetype botArchetype = switch (archetype) {
            case APPRENTICE -> MageArchetype.ELEMENTALIST;
            case ELEMENTALIST -> MageArchetype.RUNE_SCHOLAR;
            case RUNE_SCHOLAR -> MageArchetype.RUNE_SCHOLAR;
        };
        this.botSpec = presetFactory.createBotSpec(botArchetype);
        this.combatMap = presetFactory.createMap();

        specs.clear();
        specs.put(PLAYER_ID, playerSpec);
        specs.put(BOT_ID, botSpec);

        cooldowns.clear();
        cooldowns.put(PLAYER_ID, initCooldowns(playerSpec));
        cooldowns.put(BOT_ID, initCooldowns(botSpec));

        EventBus eventBus = new EventBus();
        this.session = new GameSession("magefight-local", new BasicRuleSet(), new TurnManager(List.of(PLAYER_ID, BOT_ID)), eventBus, combatMap);
        this.session.addPlayer(PLAYER_ID, playerSpec.character());
        this.session.addPlayer(BOT_ID, botSpec.character());

        applyTierEnergyRule(session.getPlayerState(PLAYER_ID), archetype);
        applyTierEnergyRule(session.getPlayerState(BOT_ID), botArchetype);

        configureMovementRules(session.getPlayerState(PLAYER_ID), playerSpec);
        configureMovementRules(session.getPlayerState(BOT_ID), botSpec);

        for (SkillTemplate skill : playerSpec.skills()) {
            session.registerSkill(skill);
            session.getPlayerState(PLAYER_ID).registerSkill(skill.name());
        }
        for (SkillTemplate skill : botSpec.skills()) {
            session.registerSkill(skill);
            session.getPlayerState(BOT_ID).registerSkill(skill.name());
        }

        skillCombo.removeAllItems();
        for (SkillTemplate skill : playerSpec.skills()) {
            skillCombo.addItem(skill.name());
        }

        log("New match started.");
        log("Player archetype: " + archetype.displayName() + " (Tier " + archetype.tier() + ")");
        log("Map: " + combatMap.name() + " - " + combatMap.description());
        refreshArchetypeUi();
        refreshUi();
        scheduleBotTurn();
    }

    private boolean tryMoveToward(String actorId, String targetId, String actorLabel) {
        return session.getPlayerPosition(actorId).flatMap(actorPos ->
                session.getPlayerPosition(targetId).map(targetPos -> {
                    int[][] deltas = {
                            {Integer.compare(targetPos.col(), actorPos.col()), 0},
                            {0, Integer.compare(targetPos.row(), actorPos.row())},
                            {1, 0},
                            {-1, 0},
                            {0, 1},
                            {0, -1}
                    };

                    for (int[] d : deltas) {
                        MapCellPosition projectedPos = session.getProjectedPlayerPosition(actorId).orElse(actorPos);
                        int nextCol = projectedPos.col() + d[0];
                        int nextRow = projectedPos.row() + d[1];
                        if (nextCol == projectedPos.col() && nextRow == projectedPos.row()) {
                            continue;
                        }
                        if (!session.isInsideMap(nextCol, nextRow)) {
                            continue;
                        }
                        if (session.isCellOccupied(nextCol, nextRow, actorId)) {
                            continue;
                        }
                        if (executeAction(new MoveAction(actorId, nextCol, nextRow, System.currentTimeMillis()),
                                actorLabel + " moves to (" + nextCol + "," + nextRow + ").")) {
                            return true;
                        }
                    }
                    return false;
                })).orElse(false);
    }

    private boolean tryMoveInDirection(String actorId, MoveDirection direction, String actorLabel) {
        return session.getPlayerPosition(actorId).map(actorPos -> {
            MapCellPosition projectedPos = session.getProjectedPlayerPosition(actorId).orElse(actorPos);
            int nextCol = projectedPos.col() + direction.deltaCol;
            int nextRow = projectedPos.row() + direction.deltaRow;
            if (!session.isInsideMap(nextCol, nextRow)) {
                return false;
            }
            if (session.isCellOccupied(nextCol, nextRow, actorId)) {
                return false;
            }
            return executeAction(new MoveAction(actorId, nextCol, nextRow, System.currentTimeMillis()),
                    actorLabel + " moves " + direction.label + " to (" + nextCol + "," + nextRow + ").");
        }).orElse(false);
    }

    private Optional<MoveDirection> promptMoveDirection() {
        Object choice = JOptionPane.showInputDialog(
                this,
                "이동 방향을 선택하세요.",
                "Move Direction",
                JOptionPane.QUESTION_MESSAGE,
                null,
                MoveDirection.values(),
                MoveDirection.UP);
        if (choice instanceof MoveDirection direction) {
            return Optional.of(direction);
        }
        return Optional.empty();
    }

    private void configureMovementRules(PlayerState state, FighterSpec spec) {
        for (SkillTemplate skill : spec.skills()) {
            String name = skill.name().toLowerCase();
            if (name.contains("blink") || name.contains("teleport")) {
                state.registerMovementRuleModifier(skill.name(), 1000, 2, true);
            } else if (name.contains("dash") || name.contains("step")) {
                state.registerMovementRuleModifier(skill.name(), 1500, 2, false);
            }
        }
    }

    private void applyTierEnergyRule(PlayerState state, MageArchetype archetype) {
        if (state == null || archetype == null) {
            return;
        }
        int cap = switch (archetype) {
            case APPRENTICE -> 6;
            case ELEMENTALIST -> 8;
            case RUNE_SCHOLAR -> 10;
        };
        state.setMaxEnergySpendPerWindow(cap);
    }

    private void refreshArchetypeUi() {
        List<MageArchetype> selectable = new ArrayList<>(ArchetypeUnlockService.autoUnlockEligible(progress));
        archetypeCombo.removeAllItems();
        if (progress.selectedArchetype() != null) {
            archetypeCombo.addItem(progress.selectedArchetype());
        } else {
            for (MageArchetype archetype : selectable) {
                archetypeCombo.addItem(archetype);
            }
        }
        if (progress.selectedArchetype() != null) {
            archetypeCombo.setSelectedItem(progress.selectedArchetype());
        } else {
            archetypeCombo.setSelectedItem(currentArchetype);
        }
        MageSkillTree tree = presetFactory.createMageSkillTree(currentArchetype);
        skillTreePanel.setContext(progress, tree);
        skillTreePanel.setFooterMessage("현재 열려 있는 스킬을 클릭해 영감을 소모하고 습득할 수 있습니다.");
    }

    private void onSkillTreeNodeClicked(SkillTreeNode node) {
        MageSkillTree tree = presetFactory.createMageSkillTree(currentArchetype);
        if (node == null) {
            return;
        }

        StringBuilder description = new StringBuilder();
        description.append(node.skill().name()).append("\n\n");
        description.append("숙련도 Lv ").append(progress.getSkillMastery(node.skill().name())).append("\n");
        description.append("필요 영감: ").append(node.inspirationCost()).append("\n");
        description.append("범위: ").append(node.skill().effect().areaType()).append(" R")
                .append(node.skill().effect().areaRadius()).append("\n");
        description.append("쿨다운: ").append(node.skill().cooldownTurns()).append(" turn(s)\n");
        description.append("기본 피해: ").append(node.skill().baseDamage()).append("\n");
        description.append("선행 스킬: ")
                .append(node.prerequisiteSkills().isEmpty() ? "없음" : String.join(", ", node.prerequisiteSkills())).append("\n\n");
        if (progress.hasLearnedSkill(node.skill().name())) {
            description.append("상태: 이미 습득됨\n");
        } else if (progress.canLearnSkill(node, tree)) {
            description.append("상태: 지금 습득 가능\n");
        } else {
            description.append("상태: 아직 봉인됨\n");
        }

        boolean learnable = progress.canLearnSkill(node, tree);
        Object[] options = learnable
                ? new Object[]{"습득한다", "닫기"}
                : new Object[]{"닫기"};
        int choice = JOptionPane.showOptionDialog(
                this,
                description.toString(),
                "Skill Detail",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.INFORMATION_MESSAGE,
                null,
                options,
                options[0]);

        if (!learnable || choice != 0) {
            return;
        }

        if (!progress.learnSkill(node, tree)) {
            JOptionPane.showMessageDialog(this, "습득할 수 없습니다.", "MageFight", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        if (playerSpec != null && session != null) {
            if (playerSpec.skills().stream().noneMatch(skill -> skill.name().equalsIgnoreCase(node.skill().name()))) {
                List<SkillTemplate> updatedSkills = new ArrayList<>(playerSpec.skills());
                updatedSkills.add(node.skill());
                playerSpec = new FighterSpec(playerSpec.character(), updatedSkills);
                skillCombo.addItem(node.skill().name());
                cooldowns.get(PLAYER_ID).put(node.skill().name(), 0);
                session.registerSkill(node.skill());
                session.getPlayerState(PLAYER_ID).registerSkill(node.skill().name());
                log(node.skill().name() + " learned and added to the current match.");
            }
        }

        persistProgress();
        refreshArchetypeUi();
        refreshUi();
        maybePromptPathShift();
    }

    private void maybePromptPathShift() {
        if (elementalPathPromptShown || currentArchetype != MageArchetype.APPRENTICE) {
            return;
        }

        MageSkillTree tree = presetFactory.createMageSkillTree(currentArchetype);
        long baseNodes = tree.nodes().stream()
                .filter(node -> node.inspirationCost() > 0 && node.inspirationCost() <= 2)
                .count();
        if (baseNodes == 0) {
            return;
        }

        long learnedBaseNodes = tree.nodes().stream()
                .filter(node -> node.inspirationCost() > 0 && node.inspirationCost() <= 2)
                .filter(node -> progress.hasLearnedSkill(node.skill().name()))
                .count();

        if (learnedBaseNodes * 2 < baseNodes) {
            return;
        }

        int choice = JOptionPane.showOptionDialog(
                this,
                "당신은 원소술의 길이 열리는 것을 보았다.",
                "MageFight",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.INFORMATION_MESSAGE,
                null,
                new Object[]{"선택한다", "포기한다"},
                "선택한다");
        elementalPathPromptShown = true;
        if (choice == 0) {
            log("원소술의 길을 선택했다.");
        } else if (choice == 1) {
            log("원소술의 길을 뒤로 미뤘다.");
        }
    }

    private void applyFontRecursively(Container container, Font font) {
        for (Component component : container.getComponents()) {
            component.setFont(font);
            if (component instanceof Container childContainer) {
                applyFontRecursively(childContainer, font);
            }
        }
    }

    private void persistProgress() {
        progressSaver.accept(progress);
    }

    private enum MoveDirection {
        UP("위", 0, -1),
        DOWN("아래", 0, 1),
        LEFT("왼쪽", -1, 0),
        RIGHT("오른쪽", 1, 0);

        private final String label;
        private final int deltaCol;
        private final int deltaRow;

        MoveDirection(String label, int deltaCol, int deltaRow) {
            this.label = label;
            this.deltaCol = deltaCol;
            this.deltaRow = deltaRow;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final class FirstPersonBattlePanel extends JPanel {
        private static final int VIEW_MARGIN = 24;
        private static final int VIEWPORT_RADIUS = 2;
        private static final int CELL_SIZE = 72;

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            paintBackground(g2, w, h);

            if (session == null) {
                drawInfo(g2, "Preparing battle view...", w, h);
                g2.dispose();
                return;
            }

            MapCellPosition playerPos = session.getPlayerPosition(PLAYER_ID).orElse(null);
            MapCellPosition enemyPos = session.getPlayerPosition(BOT_ID).orElse(null);
            if (playerPos == null || enemyPos == null) {
                drawInfo(g2, "No target in sight", w, h);
                g2.dispose();
                return;
            }

            drawBattleGrid(g2, w, h, playerPos, enemyPos);
            drawInfo(g2, "Player at " + positionText(playerPos) + " | Enemy at " + positionText(enemyPos), w, h);
            g2.dispose();
        }

        private void paintBackground(Graphics2D g2, int w, int h) {
            g2.setPaint(new GradientPaint(0, 0, new Color(220, 240, 255), 0, h, new Color(58, 67, 56)));
            g2.fillRect(0, 0, w, h);
        }

        private void drawBattleGrid(Graphics2D g2, int w, int h, MapCellPosition playerPos, MapCellPosition enemyPos) {
            BattleMap map = session.getBattleMap();
            int rows = Math.max(1, map.rows());
            int cols = Math.max(1, map.cols());

            int viewportCols = Math.min(cols, VIEWPORT_RADIUS * 2 + 1);
            int viewportRows = Math.min(rows, VIEWPORT_RADIUS * 2 + 1);

            int startCol = clamp(playerPos.col() - viewportCols / 2, 0, Math.max(0, cols - viewportCols));
            int startRow = clamp(playerPos.row() - viewportRows / 2, 0, Math.max(0, rows - viewportRows));

            int availableWidth = w - VIEW_MARGIN * 2;
            int availableHeight = h - 110;
            int cellW = Math.max(CELL_SIZE, availableWidth / viewportCols);
            int cellH = Math.max(CELL_SIZE, availableHeight / viewportRows);
            int gridW = cellW * viewportCols;
            int gridH = cellH * viewportRows;
            int originX = (w - gridW) / 2;
            int originY = 70;

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
                        drawUnit(g2, x, y, cellW, cellH, playerSpec == null ? MageArchetype.APPRENTICE : currentArchetype, true, "YOU");
                    }
                    if (enemyCell) {
                        drawUnit(g2, x, y, cellW, cellH, currentOrBotArchetype(), false, "BOT");
                    }
                }
            }

            g2.setColor(new Color(255, 255, 255, 100));
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawRoundRect(originX - 6, originY - 6, gridW + 12, gridH + 12, 18, 18);
        }

        private void paintCell(Graphics2D g2, int x, int y, int cellW, int cellH, int worldCol, int worldRow, int startRow,
                               boolean playerCell, boolean enemyCell, boolean sameCell, String[] layoutRows) {
            double depth = 1.0 - ((worldRow - startRow) / (double) Math.max(1, layoutRows.length));
            depth = Math.max(0.15, Math.min(1.0, depth));

            Color base = new Color(46, 61, 47);
            Color highlight = new Color(76, 106, 86);
            Color fog = new Color(8, 10, 14, 40);
            Color cellColor = blend(base, highlight, 1.0 - depth * 0.65);
            g2.setColor(cellColor);
            g2.fillRoundRect(x + 3, y + 3, cellW - 6, cellH - 6, 18, 18);

            if (worldRow >= 0 && worldRow < layoutRows.length) {
                String rowText = layoutRows[worldRow];
                if (worldCol >= 0 && worldCol < rowText.length()) {
                    char tile = rowText.charAt(worldCol);
                    g2.setColor(new Color(255, 255, 255, 90));
                    g2.setFont(DEFAULT_FONT.deriveFont(Font.BOLD, 12f));
                    g2.drawString(String.valueOf(tile), x + 11, y + 20);
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
            if (playerSpec == null) {
                return;
            }

            String skillName = String.valueOf(skillCombo.getSelectedItem());
            SkillTemplate skill = findSkill(playerSpec, skillName);
            if (skill == null || skill.effect() == null || playerPos == null) {
                return;
            }

            int dx = Math.abs(worldCol - playerPos.col());
            int dy = Math.abs(worldRow - playerPos.row());
            boolean inRange = switch (skill.effect().areaType()) {
                case STATIC -> dx + dy <= skill.effect().areaRadius();
                case DYNAMIC -> Math.max(dx, dy) <= skill.effect().areaRadius();
            };
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

            Color robeColor = switch (archetype) {
                case ELEMENTALIST -> new Color(230, 114, 72);
                case RUNE_SCHOLAR -> new Color(120, 132, 219);
                case APPRENTICE -> new Color(128, 168, 220);
            };

            g2.setColor(new Color(0, 0, 0, 100));
            g2.fillOval(ux + 6, uy + unitH - 8, unitW - 12, 14);

            g2.setColor(robeColor);
            int[] robeX = {ux + unitW / 2, ux + unitW - 6, ux + 6};
            int[] robeY = {uy + 12, uy + unitH, uy + unitH};
            g2.fillPolygon(robeX, robeY, 3);

            g2.setColor(new Color(245, 224, 200));
            int headSize = Math.max(14, (int) (unitW * 0.28));
            g2.fillOval(ux + (unitW - headSize) / 2, uy + 2, headSize, headSize);

            g2.setColor(player ? new Color(255, 255, 255, 220) : new Color(255, 239, 153));
            g2.setFont(DEFAULT_FONT.deriveFont(Font.BOLD, 11f));
            g2.drawString(tag, ux + 8, uy - 2);
        }

        private Color blend(Color a, Color b, double ratio) {
            ratio = Math.max(0.0, Math.min(1.0, ratio));
            int red = (int) Math.round(a.getRed() * (1.0 - ratio) + b.getRed() * ratio);
            int green = (int) Math.round(a.getGreen() * (1.0 - ratio) + b.getGreen() * ratio);
            int blue = (int) Math.round(a.getBlue() * (1.0 - ratio) + b.getBlue() * ratio);
            return new Color(red, green, blue);
        }

        private int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        private String positionText(MapCellPosition position) {
            return "(" + position.col() + "," + position.row() + ")";
        }

        private MageArchetype currentOrBotArchetype() {
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
            g2.setFont(DEFAULT_FONT.deriveFont(Font.BOLD, 12f));
            g2.drawString(message, 22, h - 23);
        }
    }
}