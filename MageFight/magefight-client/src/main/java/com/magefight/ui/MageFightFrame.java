package com.magefight.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.beans.PropertyChangeEvent;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;

import com.magefight.content.factory.GamePresetFactory;
import com.magefight.content.model.FighterSpec;
import com.magefight.content.model.MageArchetype;
import com.magefight.content.model.MageSkillTree;
import com.magefight.content.model.SkillTreeNode;
import com.magefight.content.model.SkillVisualProfile;
import com.magefight.content.progress.ArchetypePromotionService;
import com.magefight.content.progress.ArchetypeUnlockService;
import com.magefight.content.progress.MageProgress;
import com.turngame.domain.PlayerState;
import com.turngame.domain.enums.ActionType;
import com.turngame.domain.map.BattleMap;
import com.turngame.domain.map.MapCellPosition;
import com.turngame.domain.skill.SkillCounter;
import com.turngame.domain.skill.SkillDependency;
import com.turngame.domain.skill.SkillTemplate;
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
import com.turngame.server.account.AccountStore;

public class MageFightFrame extends JFrame implements BattleViewPanel.Host {
    private static final String PLAYER_ID = "p-1";
    private static final String BOT_ID = "p-2";
    private static final Color SURFACE_SOFT = new Color(246, 248, 252);
    private static final Color CHIP_BG = new Color(230, 238, 251);
    private static final Color CHIP_TEXT = new Color(38, 74, 126);
    private static final Color CHIP_SUCCESS_BG = new Color(225, 245, 233);
    private static final Color CHIP_SUCCESS_TEXT = new Color(28, 101, 52);
    private static final Color CHIP_WARN_BG = new Color(255, 244, 226);
    private static final Color CHIP_WARN_TEXT = new Color(148, 90, 13);
    private static final Set<String> TILE_AIM_SKILLS = Set.of("Firebolt");

    private final GamePresetFactory presetFactory = new GamePresetFactory();
    private final OnlineStateSyncService onlineStateSyncService = new OnlineStateSyncService(presetFactory);
    private final AccountStore accountStore = AccountStore.shared();
    private final ArchetypePromotionService promotionService = new ArchetypePromotionService();
    private final Consumer<MageProgress> progressSaver;
    private final String accountId;
    private final Color playerSkinColor;
    private final Color playerOutfitColor;
    // 온라인 상대의 외형(서버에서 동기화). 동기화 전 기본값.
    private Color opponentSkinColor = new Color(0xF5E0C8);
    private Color opponentOutfitColor = new Color(0x80A8DC);
    private MageProgress progress;
    private GameNetworkClient networkClient;
    private String onlineOpponentId;

    private GameSession session;
    private FighterSpec playerSpec;
    private FighterSpec botSpec;
    private String playerDisplayName;
    private String opponentDisplayName;
    private BattleMap combatMap;
    private MageArchetype currentArchetype = MageArchetype.APPRENTICE;
    private MageArchetype pendingPromotionTarget;
    private final Map<String, FighterSpec> specs = new HashMap<>();
    private final Map<String, Map<String, Integer>> cooldowns = new HashMap<>();
    private final Random random = new Random();
    private final java.util.concurrent.atomic.AtomicBoolean battleReturnHandled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private JDialog activeResultDialog;
    private boolean resolutionPlaybackActive;
    private int resolutionPlaybackStepIndex;
    private int lastPlayedResolvedWindowIndex;
    private PhaseType currentPhaseType = PhaseType.IMPACT;
    private long currentPhaseStartedAtMs;
    private int currentPhaseDurationMs;
    private Timer phaseFrameTimer;
    private List<GameSession.ResolvedActionView> currentPlaybackActions = List.of();
    private final Map<String, Integer> playbackHpBeforeByPlayer = new HashMap<>();
    private final Map<String, Integer> playbackHpByPlayer = new HashMap<>();
    private final Map<String, MapCellPosition> playbackPositionBeforeByPlayer = new HashMap<>();
    private final Map<String, MapCellPosition> playbackPositionByPlayer = new HashMap<>();
    private String pendingOnlineResultText;
    private boolean pendingOnlineResultDisconnect;
    private boolean promotionEffectActive;
    private long promotionEffectStartedAtMs;
    private String promotionEffectText;
    private final JButton promotionReadyBtn = new JButton("승급 가능");
    private Timer promotionBlinkTimer;
    private boolean promotionBlinkOn;

    private final JLabel matchingStatusLabel = new JLabel("Offline Mode");
    private final JButton findGameBtn = new JButton("Find Game");
    private final JButton cancelMatchmakingBtn = new JButton("Cancel");
    private Timer matchingTimeoutTimer;
    private static final long MATCHMAKING_TIMEOUT_MS = 60000;
    private Timer onlineTurnTimer;
    private int onlineWindowDurationSeconds = 60;
    private int onlineWindowIndex = -1;
    private long onlineWindowDeadlineMs;
    private boolean onlineMyReady;
    private boolean onlineOpponentReady;
    private long lastAppliedStateEventSeq;
    private boolean onlinePausedForReconnect;
    private long onlineReconnectDeadlineEpochMs;
    private JDialog onlineWaitingDialog;
    private String pendingAimedSkillName;
    private MapCellPosition onlineLocalProjectedPlayerPos;
    private int onlineLocalProjectedWindowIndex;

    private enum PhaseType {
        CAST,
        IMPACT
    }

    private record PlaybackPhase(
            GameSession.ResolutionStep step,
            PhaseType phaseType,
            int durationMs
    ) {
    }

    private final JLabel mapLabel = new JLabel();
    private final JLabel turnLabel = new JLabel();
    private final JLabel playerLabel = new JLabel();
    private final JLabel botLabel = new JLabel();
    private final JEditorPane cooldownArea = new JEditorPane();
    private final JTextArea logArea = new JTextArea();
    private final JLabel logSummaryLabel = new JLabel("Recent: -");
    private final JLabel actionFeedbackLabel = new JLabel("행동 준비 완료.");
    private final JLabel coreHpLabel = new JLabel();
    private final JLabel coreEnergyLabel = new JLabel();
    private final JLabel coreRangeLabel = new JLabel();
    private final JLabel coreStateLabel = new JLabel();
    private final JLabel turnOwnerChip = new JLabel();
    private final JLabel timerChip = new JLabel();
    private final JLabel readyChip = new JLabel();
    private final JLabel connectionChip = new JLabel();
    private final JComboBox<String> skillCombo = new JComboBox<>();
    private final JComboBox<MageArchetype> archetypeCombo = new JComboBox<>();
    private final BattleViewPanel firstPersonView;
    private final SkillTreePanel skillTreePanel = new SkillTreePanel();
    private final JCheckBox actionLogFilter = new JCheckBox("Action", true);
    private final JCheckBox combatLogFilter = new JCheckBox("Combat", true);
    private final JCheckBox progressionLogFilter = new JCheckBox("Progress", true);
    private final JCheckBox systemLogFilter = new JCheckBox("System", true);
    private final List<LogEntry> logEntries = new ArrayList<>();

    private enum LogCategory {
        ACTION,
        COMBAT,
        PROGRESSION,
        SYSTEM
    }

    private record LogEntry(int windowIndex, LogCategory category, String message) {
    }

    public MageFightFrame() {
        this(MageProgress.starter(), null, ignored -> {});
    }

    public MageFightFrame(MageProgress initialProgress, String accountId, Consumer<MageProgress> progressSaver) {
        this(initialProgress, accountId, progressSaver, null);
    }

    public MageFightFrame(MageProgress initialProgress, String accountId, Consumer<MageProgress> progressSaver, GameNetworkClient networkClient) {
        super("MageFight - Visible Battle");

        this.firstPersonView = new BattleViewPanel(this);
        this.accountId = accountId;
        AccountStore.CharacterProfile profile = accountId == null ? null : accountStore.characterProfile(accountId).orElse(null);
        this.playerSkinColor = parseHexColor(profile == null ? null : profile.skinColorHex(), new Color(0xF5E0C8));
        this.playerOutfitColor = parseHexColor(profile == null ? null : profile.outfitColorHex(), new Color(0x80A8DC));
        this.playerDisplayName = profile == null ? resolveOnlineNickname() : profile.displayName();
        this.opponentDisplayName = "Bot";
        this.progress = initialProgress == null ? MageProgress.starter() : initialProgress;
        this.progressSaver = progressSaver == null ? ignored -> {} : progressSaver;
        this.networkClient = networkClient;
        configureSkillVisualRegistry();
        this.onlineOpponentId = null;
        this.battleReturnHandled.set(false);
        this.activeResultDialog = null;
        this.resolutionPlaybackActive = false;
        this.resolutionPlaybackStepIndex = 0;
        this.lastPlayedResolvedWindowIndex = -1;
        this.currentPhaseStartedAtMs = 0L;
        this.currentPhaseDurationMs = 1;
        this.pendingOnlineResultText = null;
        this.pendingOnlineResultDisconnect = false;
        this.onlineWindowDurationSeconds = 60;
        this.onlineWindowIndex = -1;
        this.onlineWindowDeadlineMs = 0L;
        this.onlineMyReady = false;
        this.onlineOpponentReady = false;
        this.lastAppliedStateEventSeq = -1L;
        this.onlinePausedForReconnect = false;
        this.onlineReconnectDeadlineEpochMs = 0L;
        this.onlineWaitingDialog = null;
        this.pendingAimedSkillName = null;
        this.onlineLocalProjectedPlayerPos = null;
        this.onlineLocalProjectedWindowIndex = -1;
        stopOnlineTurnTimer();
        this.pendingPromotionTarget = null;
        this.promotionService.resetForNewMatch();
        this.promotionEffectActive = false;
        this.promotionEffectStartedAtMs = 0L;
        this.promotionEffectText = "";
        this.promotionBlinkTimer = null;
        this.promotionBlinkOn = false;
        if (this.progress.selectedArchetype() != null) {
            this.currentArchetype = this.progress.selectedArchetype();
        }

        applyGlobalFont();

        initUi();
        applyFontRecursively(getContentPane(), DEFAULT_FONT);
        refreshArchetypeUi();
        if (networkClient == null) {
            startNewMatch(currentArchetype);
        } else {
            startNetworkMatch();
        }
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                // 온라인 게임이면 서버 연결 종료
                if (networkClient != null && networkClient.isConnected()) {
                    networkClient.disconnect();
                }
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

            private void configureSkillVisualRegistry() {
            BattleRenderToolkit.clearRegisteredSkillVisuals();
            for (SkillVisualProfile profile : presetFactory.createSkillVisualProfiles()) {
                if (profile == null || profile.skillName() == null || profile.skillName().isBlank()) {
                continue;
                }
                BattleRenderToolkit.ProjectileStyle style = null;
                if (profile.style() != null) {
                style = new BattleRenderToolkit.ProjectileStyle(
                    parseHexColor(profile.style().coreColorHex(), new Color(255, 182, 86)),
                    parseHexColor(profile.style().glowColorHex(), new Color(255, 220, 145)),
                    parseHexColor(profile.style().trailColorHex(), new Color(255, 204, 133)),
                    profile.style().launchStartProgress(),
                    profile.style().launchDurationProgress(),
                    profile.style().trailWidth(),
                    profile.style().radius()
                );
                }
                BattleRenderToolkit.registerSkillVisual(
                    profile.skillName(),
                    new BattleRenderToolkit.SkillVisualSpec(
                        style,
                        profile.projectileImagePath(),
                        profile.projectileImageSize()
                    )
                );
            }
        }

    private void initUi() {
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(980, 660);
        setLocationRelativeTo(null);
        setFont(DEFAULT_FONT);
        setLayout(new BorderLayout(8, 8));

        JPanel topPanel = new JPanel(new BorderLayout(6, 6));
        JPanel topTextPanel = new JPanel(new GridLayout(2, 1));
        mapLabel.setFont(DEFAULT_FONT.deriveFont(Font.BOLD, 17f));
        turnLabel.setFont(DEFAULT_FONT.deriveFont(Font.BOLD, 15f));
        topTextPanel.add(mapLabel);
        topTextPanel.add(turnLabel);
        topPanel.add(topTextPanel, BorderLayout.NORTH);

        JPanel statusChipPanel = new JPanel();
        statusChipPanel.setLayout(new BoxLayout(statusChipPanel, BoxLayout.X_AXIS));
        statusChipPanel.setBackground(SURFACE_SOFT);
        statusChipPanel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        styleStatusChip(turnOwnerChip, CHIP_BG, CHIP_TEXT);
        styleStatusChip(timerChip, CHIP_BG, CHIP_TEXT);
        styleStatusChip(readyChip, CHIP_WARN_BG, CHIP_WARN_TEXT);
        styleStatusChip(connectionChip, CHIP_SUCCESS_BG, CHIP_SUCCESS_TEXT);
        statusChipPanel.add(turnOwnerChip);
        statusChipPanel.add(Box.createHorizontalStrut(8));
        statusChipPanel.add(timerChip);
        statusChipPanel.add(Box.createHorizontalStrut(8));
        statusChipPanel.add(readyChip);
        statusChipPanel.add(Box.createHorizontalStrut(8));
        statusChipPanel.add(connectionChip);
        statusChipPanel.add(Box.createHorizontalGlue());
        topPanel.add(statusChipPanel, BorderLayout.SOUTH);

        JPanel coreCardsPanel = new JPanel(new GridLayout(1, 4, 8, 8));
        coreCardsPanel.add(createStatusCard("Core HP", coreHpLabel, new Color(242, 250, 255)));
        coreCardsPanel.add(createStatusCard("Core EN", coreEnergyLabel, new Color(244, 255, 247)));
        coreCardsPanel.add(createStatusCard("Core Range", coreRangeLabel, new Color(255, 249, 241)));
        coreCardsPanel.add(createStatusCard("Core State", coreStateLabel, new Color(248, 244, 255)));
        topPanel.add(coreCardsPanel, BorderLayout.CENTER);
        add(topPanel, BorderLayout.NORTH);

        JPanel centerPanel = new JPanel(new BorderLayout(8, 8));
        firstPersonView.setBorder(BorderFactory.createTitledBorder("First Person View"));
        centerPanel.add(firstPersonView, BorderLayout.CENTER);

        JPanel statusPanel = new JPanel(new GridLayout(1, 2, 10, 10));
        statusPanel.add(createStatusCard("You", playerLabel, new Color(235, 248, 255)));
        statusPanel.add(createStatusCard("Player", botLabel, new Color(255, 245, 238)));
        centerPanel.add(statusPanel, BorderLayout.SOUTH);

        JPanel rightPanel = new JPanel(new BorderLayout(6, 6));
        cooldownArea.setEditable(false);
        cooldownArea.setContentType("text/html");
        cooldownArea.setFont(DEFAULT_FONT);
        cooldownArea.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);

        JPanel archetypePanel = new JPanel(new BorderLayout(6, 6));
        archetypePanel.setBorder(BorderFactory.createTitledBorder("Archetype (Unlock by Conditions)"));

        JPanel pickPanel = new JPanel();
        pickPanel.setLayout(new BoxLayout(pickPanel, BoxLayout.X_AXIS));
        JButton newMatchBtn = new JButton("Start Match");
        JButton checkUnlockBtn = new JButton("Check Unlock");
        configurePromotionReadyButton();
        pickPanel.add(archetypeCombo);
        pickPanel.add(newMatchBtn);
        pickPanel.add(checkUnlockBtn);
        pickPanel.add(promotionReadyBtn);
        archetypePanel.add(pickPanel, BorderLayout.NORTH);

        JPanel matchingPanel = new JPanel();
        matchingPanel.setLayout(new BoxLayout(matchingPanel, BoxLayout.X_AXIS));
        matchingPanel.setBorder(BorderFactory.createTitledBorder("Online Matchmaking"));
        findGameBtn.addActionListener(e -> onFindGameRequested());
        cancelMatchmakingBtn.addActionListener(e -> onCancelMatchmakingRequested());
        cancelMatchmakingBtn.setEnabled(false);
        matchingStatusLabel.setFont(DEFAULT_FONT.deriveFont(Font.BOLD, 13f));
        matchingStatusLabel.setForeground(new Color(100, 100, 100));
        matchingPanel.add(findGameBtn);
        matchingPanel.add(cancelMatchmakingBtn);
        matchingPanel.add(Box.createHorizontalStrut(10));
        matchingPanel.add(matchingStatusLabel);
        archetypePanel.add(matchingPanel, BorderLayout.CENTER);
        
        skillTreePanel.setNodeClickHandler(this::onSkillTreeNodeClicked);
        JScrollPane skillTreeScroll = new JScrollPane(skillTreePanel);
        skillTreeScroll.setPreferredSize(new Dimension(280, 320));
        skillTreeScroll.setMinimumSize(new Dimension(260, 240));
        skillTreeScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        skillTreeScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        skillTreeScroll.getViewport().setBackground(new Color(16, 20, 32));
        archetypePanel.add(skillTreeScroll, BorderLayout.CENTER);

        skillCombo.addActionListener(e -> firstPersonView.repaint());

        rightPanel.setBorder(BorderFactory.createTitledBorder("Battle Hub"));
        rightPanel.setPreferredSize(new Dimension(320, 10));
        rightPanel.setMinimumSize(new Dimension(280, 10));

        JTabbedPane infoTabs = new JTabbedPane();
        infoTabs.setFont(DEFAULT_FONT.deriveFont(Font.BOLD, 13f));
        infoTabs.addTab("Progress", archetypePanel);

        JScrollPane cooldownScroll = new JScrollPane(cooldownArea);
        cooldownScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        infoTabs.addTab("Skills", cooldownScroll);

        JPanel logTabPanel = new JPanel(new BorderLayout(6, 6));
        JPanel logTopPanel = new JPanel(new BorderLayout(0, 6));
        logSummaryLabel.setFont(DEFAULT_FONT.deriveFont(Font.BOLD, 12f));
        logSummaryLabel.setOpaque(true);
        logSummaryLabel.setBackground(new Color(243, 247, 255));
        logSummaryLabel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(214, 223, 237)),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)
        ));
        logTopPanel.add(logSummaryLabel, BorderLayout.NORTH);
        logTopPanel.add(createLogFilterPanel(), BorderLayout.SOUTH);
        logTabPanel.add(logTopPanel, BorderLayout.NORTH);
        logArea.setEditable(false);
        logArea.setFont(DEFAULT_FONT);
        logArea.setLineWrap(true);
        logArea.setRows(10);
        logArea.setWrapStyleWord(true);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        logTabPanel.add(logScroll, BorderLayout.CENTER);
        infoTabs.addTab("Log", logTabPanel);

        rightPanel.add(infoTabs, BorderLayout.CENTER);

        newMatchBtn.addActionListener(e -> onStartMatchRequested());
        checkUnlockBtn.addActionListener(e -> onCheckUnlockRequested());
        promotionReadyBtn.addActionListener(e -> onPromotionReadyRequested());

        JSplitPane battleSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, centerPanel, rightPanel);
        battleSplitPane.setResizeWeight(0.74);
        battleSplitPane.setContinuousLayout(true);
        battleSplitPane.setOneTouchExpandable(true);
        battleSplitPane.setDividerSize(8);
        battleSplitPane.setDividerLocation(0.74);
        add(battleSplitPane, BorderLayout.CENTER);

        add(createControlPanel(), BorderLayout.SOUTH);
    }

    private void configurePromotionReadyButton() {
        promotionReadyBtn.setVisible(false);
        promotionReadyBtn.setFocusable(false);
        promotionReadyBtn.setOpaque(true);
        promotionReadyBtn.setBackground(new Color(255, 241, 166));
        promotionReadyBtn.setToolTipText("조건을 이미 만족했습니다. 원할 때 승급하세요.");
    }

    private void onStartMatchRequested() {
        MageArchetype selected = (MageArchetype) archetypeCombo.getSelectedItem();
        if (selected == null) {
            log("선택 가능한 아키타입이 없습니다.");
            return;
        }
        if (!tryApplySelectedArchetype(selected)) {
            refreshArchetypeUi();
            return;
        }
        startNewMatch(selected);
    }

    private boolean tryApplySelectedArchetype(MageArchetype selected) {
        boolean hadSelection = progress.hasSelectedArchetype();
        if (progress.isSelected(selected)) {
            return true;
        }
        if (!ArchetypeUnlockService.trySelect(selected, progress)) {
            String message = hadSelection ? "승급 실패: " : "호칭 선택 실패: ";
            log(message + ArchetypeUnlockService.lockReason(selected, progress).orElse("조건 불일치"));
            return false;
        }
        String message = hadSelection ? "호칭 승급: " : "호칭 선택: ";
        log(message + selected.displayName() + " (Tier " + selected.tier() + ")");
        persistProgress();
        return true;
    }

    private void onCheckUnlockRequested() {
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
    }

    private void onFindGameRequested() {
        if (networkClient == null || !networkClient.isConnected()) {
            setActionFeedback("서버에 연결되지 않았습니다.", true);
            log(LogCategory.SYSTEM, "오류: 서버 연결이 필요합니다.");
            return;
        }

        if (networkClient.getMatchState() == GameNetworkClient.MatchState.SEARCHING) {
            setActionFeedback("이미 상대를 찾고 있습니다.", false);
            return;
        }

        MageArchetype archetype = progress.selectedArchetype();
        if (archetype == null) {
            setActionFeedback("게임을 시작하기 전에 직업을 선택하세요.", true);
            return;
        }

        FighterSpec onlineSpec = presetFactory.createPlayerSpec(archetype, progress);
        System.out.println("[MageFightFrame] online spec: archetype=" + archetype
                + ", selectedArchetype=" + progress.selectedArchetype()
                + ", learnedInProgress=" + progress.learnedSkillNames()
                + ", specSkills=" + onlineSpec.skills().stream().map(SkillTemplate::name).toList());

        setActionFeedback("상대 찾는 중...", false);
        networkClient.setAppearance(colorToHex(playerSkinColor), colorToHex(playerOutfitColor));
        networkClient.findGame(
                resolveOnlineNickname(),
                toServerCharacterType(archetype),
                accountId,
                resolveCharacterDisplayName(),
                buildOnlineSkillPayload(onlineSpec),
                resolveTurnEnergyCap(archetype)
        );
    }

    static List<Map<String, Object>> buildOnlineSkillPayload(FighterSpec spec) {
        if (spec == null || spec.skills() == null || spec.skills().isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> payload = new ArrayList<>();
        for (SkillTemplate skill : spec.skills()) {
            Map<String, Object> skillPayload = new HashMap<>();
            skillPayload.put("name", skill.name());
            skillPayload.put("baseDamage", skill.baseDamage());
            skillPayload.put("cooldownTurns", skill.cooldownTurns());
            skillPayload.put("baseSuccessProbability", skill.baseSuccessProbability());
            skillPayload.put("failEnergyCost", skill.failEnergyCost());
            skillPayload.put("successEnergyCost", skill.successEnergyCost());
            skillPayload.put("prepareCastMs", skill.prepareCastMs());
            skillPayload.put("isDefenseSkill", skill.isDefenseSkill());
            skillPayload.put("evadeDurationMs", skill.evadeDurationMs());

            Map<String, Object> effectPayload = new HashMap<>();
            effectPayload.put("areaType", skill.effect().areaType().name());
            effectPayload.put("areaRadius", skill.effect().areaRadius());
            effectPayload.put("durationTurns", skill.effect().durationTurns());
            effectPayload.put("areaPatternRows", skill.effect().areaPatternRows());
            skillPayload.put("effect", effectPayload);

            // 스킬 간 상호작용(버프/상쇄)도 함께 보내야 온라인에서 동일하게 적용된다.
            List<Map<String, Object>> depsPayload = new ArrayList<>();
            for (SkillDependency dep : skill.dependencies()) {
                Map<String, Object> d = new HashMap<>();
                d.put("affectedSkillName", dep.affectedSkillName());
                d.put("type", dep.type().name());
                d.put("modifierValue", dep.modifierValue());
                depsPayload.add(d);
            }
            skillPayload.put("dependencies", depsPayload);

            List<Map<String, Object>> countersPayload = new ArrayList<>();
            for (SkillCounter counter : skill.counters()) {
                Map<String, Object> c = new HashMap<>();
                c.put("counterSkillName", counter.counterSkillName());
                c.put("targetSkillName", counter.targetSkillName());
                c.put("type", counter.type().name());
                c.put("damageReductionPercent", counter.damageReductionPercent());
                countersPayload.add(c);
            }
            skillPayload.put("counters", countersPayload);

            payload.add(skillPayload);
        }
        return List.copyOf(payload);
    }

    static int resolveTurnEnergyCap(MageArchetype archetype) {
        if (archetype == null) {
            return 6;
        }
        return switch (archetype) {
            case APPRENTICE -> 6;
            case ELEMENTALIST -> 8;
            case RUNE_SCHOLAR -> 10;
        };
    }

    private String resolveOnlineNickname() {
        if (accountId == null || accountId.isBlank()) {
            return "Player";
        }
        return accountStore.nickname(accountId).orElse(accountId);
    }

    private String toServerCharacterType(MageArchetype archetype) {
        if (archetype == null) {
            return "WARRIOR";
        }
        return switch (archetype) {
            case APPRENTICE, ELEMENTALIST, RUNE_SCHOLAR -> "MAGE";
        };
    }

    private String resolveCharacterDisplayName() {
        if (accountId == null || accountId.isBlank()) {
            return currentPlayerDisplayName();
        }
        return accountStore.characterProfile(accountId)
                .map(AccountStore.CharacterProfile::displayName)
                .orElse(resolveOnlineNickname());
    }

    private void onCancelMatchmakingRequested() {
        if (networkClient == null) {
            return;
        }

        if (networkClient.getMatchState() != GameNetworkClient.MatchState.SEARCHING) {
            return;
        }

        networkClient.cancelMatchmaking();
        setActionFeedback("매칭이 취소되었습니다.", false);
        log(LogCategory.SYSTEM, "매칭 취소됨");
    }

    private void startMatchingTimeoutTimer() {
        stopMatchingTimeoutTimer();
        matchingTimeoutTimer = new Timer(1000, e -> {
            long elapsedMs = networkClient.getMatchSearchElapsedMs();
            long remainingSeconds = (MATCHMAKING_TIMEOUT_MS - elapsedMs) / 1000;

            if (remainingSeconds <= 0) {
                handleMatchingTimeout();
            } else {
                matchingStatusLabel.setText("Searching... (" + remainingSeconds + "s)");
            }
        });
        matchingTimeoutTimer.setRepeats(true);
        matchingTimeoutTimer.start();
    }

    private void stopMatchingTimeoutTimer() {
        if (matchingTimeoutTimer != null) {
            matchingTimeoutTimer.stop();
            matchingTimeoutTimer = null;
        }
    }

    private void handleMatchingTimeout() {
        stopMatchingTimeoutTimer();

        if (networkClient.getMatchState() != GameNetworkClient.MatchState.SEARCHING) {
            return;
        }

        networkClient.cancelMatchmaking();
        log(LogCategory.SYSTEM, "매칭 시간 초과 (60초)");
        setActionFeedback("매칭 시간이 초과되었습니다. 다시 시도하세요.", true);

        JOptionPane.showMessageDialog(
                this,
                "No opponent found within 60 seconds.\nPlease try again.",
                "Matchmaking Timeout",
                JOptionPane.INFORMATION_MESSAGE
        );
    }

    private void onPromotionReadyRequested() {
        if (pendingPromotionTarget == null || !canOfferPromotion(pendingPromotionTarget)) {
            log("현재는 승급 조건을 만족하지 않습니다.");
            updatePromotionReadyButton();
            return;
        }
        int choice = JOptionPane.showConfirmDialog(
                this,
                pendingPromotionTarget.displayName() + "로 승급하시겠습니까?",
                "MageFight",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.INFORMATION_MESSAGE
        );
        if (choice == JOptionPane.YES_OPTION) {
            promoteToArchetype(pendingPromotionTarget,
                    "새로운 길을 받아들였다. 호칭이 " + pendingPromotionTarget.displayName() + "로 승급되었습니다.");
        }
    }

    private JPanel createStatusCard(String title, JLabel contentLabel, Color bg) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.setBackground(bg);

        contentLabel.setFont(DEFAULT_FONT.deriveFont(Font.BOLD, 15f));
        panel.add(contentLabel, BorderLayout.CENTER);
        return panel;
    }

    private void styleStatusChip(JLabel label, Color bg, Color fg) {
        label.setOpaque(true);
        label.setBackground(bg);
        label.setForeground(fg);
        label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(214, 223, 237)),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)
        ));
        label.setFont(DEFAULT_FONT.deriveFont(Font.BOLD, 12f));
        label.setText("-");
    }

    private JPanel createLogFilterPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder("Log Filters"));

        actionLogFilter.addActionListener(e -> refreshLogArea());
        combatLogFilter.addActionListener(e -> refreshLogArea());
        progressionLogFilter.addActionListener(e -> refreshLogArea());
        systemLogFilter.addActionListener(e -> refreshLogArea());

        panel.add(actionLogFilter);
        panel.add(combatLogFilter);
        panel.add(progressionLogFilter);
        panel.add(systemLogFilter);
        return panel;
    }

    private JPanel createControlPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        JPanel actionRow = new JPanel();
        actionRow.setLayout(new BoxLayout(actionRow, BoxLayout.X_AXIS));

        JButton attackBtn = new JButton("Attack");
        JButton defendBtn = new JButton("Defend");
        JButton moveBtn = new JButton("Move");
        JButton skillBtn = new JButton("Use Skill");
        JButton endTurnBtn = new JButton("End Turn");
        JButton surrenderBtn = new JButton("Surrender");

        attackBtn.addActionListener(e -> onAttack());
        defendBtn.addActionListener(e -> onDefend());
        moveBtn.addActionListener(e -> onMove());
        skillBtn.addActionListener(e -> onUseSkill());
        endTurnBtn.addActionListener(e -> onEndTurn());
        surrenderBtn.addActionListener(e -> onSurrender());

        actionRow.add(attackBtn);
        actionRow.add(defendBtn);
        actionRow.add(moveBtn);
        actionRow.add(skillCombo);
        actionRow.add(skillBtn);
        actionRow.add(endTurnBtn);
        actionRow.add(surrenderBtn);

        actionFeedbackLabel.setFont(DEFAULT_FONT.deriveFont(Font.BOLD, 12f));
        actionFeedbackLabel.setForeground(new Color(36, 84, 165));

        panel.add(actionRow, BorderLayout.CENTER);
        panel.add(actionFeedbackLabel, BorderLayout.SOUTH);
        return panel;
    }

    private void onMove() {
        if (!validateMyTurn()) {
            return;
        }

        Optional<MoveDirection> direction = promptMoveDirection();
        if (direction.isEmpty()) {
            setActionFeedback("이동이 취소되었습니다.", false);
            return;
        }

        boolean moved = tryMoveInDirection(PLAYER_ID, direction.get(), "Player");
        if (!moved) {
            log("이동할 수 없습니다. (범위, 점유, 에너지, 쿨타임 확인)");
            setActionFeedback("이동 실패: 범위/점유/에너지를 확인하세요.", true);
        }
    }

    private void onAttack() {
        if (!validateMyTurn()) {
            return;
        }
        int damage = 5 + (playerSpec == null ? 0 : playerSpec.attackBonus());
        executeAction(new AttackAction(PLAYER_ID, resolveTargetId(), damage), "Player uses Attack for " + damage + " dmg.");
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
        if (skillName == null || skillName.isBlank()) {
            log("사용 가능한 스킬이 없습니다.");
            setActionFeedback("스킬 실패: 사용 가능한 스킬이 없습니다.", true);
            return;
        }

        SkillTemplate selectedSkill = resolveSelectedSkillTemplate(skillName);
        if (requiresTileAim(selectedSkill)) {
            pendingAimedSkillName = skillName;
            setActionFeedback("조준 모드: 맵에서 타일을 클릭하세요.", false);
            log(LogCategory.ACTION, skillName + " 조준 대기 중 (맵 클릭)");
            firstPersonView.repaint();
            return;
        }

        executePlayerSkillCast(selectedSkill, Optional.empty());
    }

    private void executePlayerSkillCast(SkillTemplate skill, Optional<MapCellPosition> aimedCell) {
        if (skill == null) {
            String selected = String.valueOf(skillCombo.getSelectedItem());
            log("Skill not found: " + selected);
            setActionFeedback("스킬 실패: 스킬 정보를 찾을 수 없습니다.", true);
            return;
        }

        if (networkClient != null && networkClient.isConnected()) {
            UseSkillAction action = aimedCell
                    .map(cell -> new UseSkillAction(PLAYER_ID, resolveTargetId(), skill.name(), 0, cell.col(), cell.row()))
                    .orElseGet(() -> new UseSkillAction(PLAYER_ID, resolveTargetId(), skill.name(), 0));
            executeAction(action,
                    "Player casts " + skill.name() + ".");
            return;
        }

        int remain = cooldowns.get(PLAYER_ID).getOrDefault(skill.name(), 0);
        if (remain > 0) {
            log("Skill " + skill.name() + " is on cooldown: " + remain + " turns left.");
            setActionFeedback("스킬 실패: 쿨다운 " + remain + "턴 남음", true);
            return;
        }

        int damage = Math.max(5, Math.min(80, 10 + playerSpec.attackBonus() + skill.baseDamage()));
        UseSkillAction action = aimedCell
                .map(cell -> new UseSkillAction(PLAYER_ID, BOT_ID, skill.name(), damage, cell.col(), cell.row()))
                .orElseGet(() -> new UseSkillAction(PLAYER_ID, BOT_ID, skill.name(), damage));
        if (executeAction(action,
                "Player casts " + skill.name() + " for " + damage + " dmg.")) {
            cooldowns.get(PLAYER_ID).put(skill.name(), skill.cooldownTurns());
            int gainedLevels = progress.recordSkillUse(skill.name());
            if (gainedLevels > 0) {
                log(skill.name() + " mastery increased by " + gainedLevels + "; inspiration +" + gainedLevels + ".");
                maybePromptPathShift();
            }
            persistProgress();
            refreshArchetypeUi();
        }
    }

    private String resolveTargetId() {
        if (networkClient != null && networkClient.isConnected() && onlineOpponentId != null && !onlineOpponentId.isBlank()) {
            return onlineOpponentId;
        }
        return BOT_ID;
    }

    private SkillTemplate resolveSelectedSkillTemplate(String skillName) {
        SkillTemplate fromSpec = findSkill(playerSpec, skillName);
        if (fromSpec != null) {
            return fromSpec;
        }
        if (session == null) {
            return null;
        }
        return session.getSkillTemplate(skillName).orElse(null);
    }

    private boolean requiresTileAim(SkillTemplate skill) {
        if (skill == null) {
            return false;
        }
        return TILE_AIM_SKILLS.stream().anyMatch(name -> name.equalsIgnoreCase(skill.name()));
    }

    @Override
    public void onBattleCellClicked(int worldCol, int worldRow) {
        if (pendingAimedSkillName == null || pendingAimedSkillName.isBlank()) {
            return;
        }
        if (!validateMyTurn()) {
            pendingAimedSkillName = null;
            return;
        }

        SkillTemplate skill = resolveSelectedSkillTemplate(pendingAimedSkillName);
        if (skill == null) {
            setActionFeedback("조준 실패: 스킬 정보를 찾을 수 없습니다.", true);
            pendingAimedSkillName = null;
            return;
        }

        Optional<MapCellPosition> actorPosOpt = displayedPositionOf(PLAYER_ID);
        if (actorPosOpt.isEmpty()) {
            setActionFeedback("조준 실패: 현재 위치를 확인할 수 없습니다.", true);
            pendingAimedSkillName = null;
            return;
        }

        MapCellPosition aimedCell = new MapCellPosition(worldCol, worldRow);
        boolean inRange = BattleRenderToolkit.isSkillInRangeForEffect(skill, actorPosOpt.get(), aimedCell);
        if (!inRange) {
            setActionFeedback("유효 범위의 타일을 클릭하세요.", true);
            return;
        }

        pendingAimedSkillName = null;
        executePlayerSkillCast(skill, Optional.of(aimedCell));
    }

    private void onEndTurn() {
        if (!validateMyTurn()) {
            return;
        }
        if (executeAction(new EndTurnAction(PLAYER_ID), "Player ends turn.")) {
            refreshUi();
            if (networkClient == null || !networkClient.isConnected()) {
                scheduleBotTurn();
            }
        }
    }

    private void onSurrender() {
        if (battleReturnHandled.get()) {
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(
                this,
                "정말 기권하시겠습니까?",
                "Surrender",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) {
            setActionFeedback("기권이 취소되었습니다.", false);
            return;
        }

        log("플레이어가 기권했습니다.");
        setActionFeedback("기권 요청 전송됨", true);

        if (networkClient != null && networkClient.isConnected()) {
            networkClient.surrender();
            return;
        }

        battleReturnHandled.set(true);
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, "You surrendered.", "Result", JOptionPane.INFORMATION_MESSAGE);
            dispose();
        });
    }

    private boolean executeAction(GameAction action, String logLine) {
        try {
            // 온라인 게임: 네트워크로 전송
            if (networkClient != null && networkClient.isConnected()) {
                sendNetworkAction(action, logLine);
                setActionFeedback("행동 전송됨: " + actionName(action), false);
                return true;
            }

            // 로컬 게임: 세션에 전송
            session.submitAction(action);
            boolean windowAdvanced = session.consumeWindowAdvancedFlag();
            if (windowAdvanced) {
                tickCooldowns(PLAYER_ID);
                tickCooldowns(BOT_ID);
                log("Window advanced -> cooldowns ticked.");
            }
            log(logLine);
            setActionFeedback("행동 수락됨: " + actionName(action), false);
            int resolvedWindowIndex = session.getLastResolvedWindowIndex();
            boolean hasNewResolution = resolvedWindowIndex > lastPlayedResolvedWindowIndex;
            if (hasNewResolution) {
                lastPlayedResolvedWindowIndex = resolvedWindowIndex;
                playResolutionSteps(session.snapshotLastResolutionSteps());
            } else {
                refreshUi();
                if (session.isFinished()) {
                    showWinner();
                }
            }
            return true;
        } catch (RuntimeException ex) {
            log("Action rejected: " + ex.getMessage());
            setActionFeedback("행동 거부: " + ex.getMessage(), true);
            return false;
        }
    }

    private void sendNetworkAction(GameAction action, String logLine) {
        try {
            if (action instanceof AttackAction attack) {
                networkClient.attack(attack.targetId(), attack.damage());
            } else if (action instanceof DefendAction) {
                networkClient.defend("Defend");
            } else if (action instanceof UseSkillAction skill) {
                networkClient.useSkill(skill.targetId(), skill.skillName(), skill.targetCol(), skill.targetRow());
            } else if (action instanceof MoveAction move) {
                networkClient.move(move.targetCol(), move.targetRow());
            } else if (action instanceof EndTurnAction) {
                networkClient.endTurn();
            }
            log(logLine);
        } catch (Exception e) {
            log("Network action failed: " + e.getMessage());
            setActionFeedback("네트워크 전송 실패: " + e.getMessage(), true);
        }
    }

    private boolean validateMyTurn() {
        // 온라인 게임: 서버가 검증하므로 로컬 검증 스킵
        if (networkClient != null && networkClient.isConnected()) {
            if (onlinePausedForReconnect) {
                setActionFeedback("Opponent disconnected. Waiting for reconnect...", true);
                return false;
            }
            return true;
        }

        // 로컬 게임: 세션 검증
        if (session.isFinished()) {
            showWinner();
            setActionFeedback("게임이 종료되었습니다.", true);
            return false;
        }
        if (resolutionPlaybackActive) {
            log("행동 정산 연출 중입니다. 잠시만 기다려주세요.");
            setActionFeedback("정산 연출 중에는 행동할 수 없습니다.", true);
            return false;
        }
        if (session.isPlayerReady(PLAYER_ID)) {
            log("이미 준비 완료 상태입니다. 다음 윈도우를 기다리세요.");
            setActionFeedback("이미 준비 완료 상태입니다.", true);
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
        if (botSpec == null) {
            return;
        }
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

    @Override
    public SkillTemplate findSkill(FighterSpec spec, String skillName) {
        if (spec == null || spec.skills() == null || skillName == null || skillName.isBlank()) {
            return null;
        }
        return spec.skills().stream()
                .filter(s -> s.name().equalsIgnoreCase(skillName))
                .findFirst()
                .orElse(null);
    }

    private void refreshUi() {
        if (session == null) {
            return;
        }
        PlayerState p1 = session.getPlayerState(PLAYER_ID);
        PlayerState p2 = session.getPlayerState(BOT_ID);
        int displayedP1Hp = displayedHpOf(PLAYER_ID, p1.hp());
        int displayedP2Hp = displayedHpOf(BOT_ID, p2.hp());
        String p1Pos = displayedPositionOf(PLAYER_ID)
                .map(pos -> pos.col() + "," + pos.row())
                .orElse("?,?");
        String p2Pos = displayedPositionOf(BOT_ID)
                .map(pos -> pos.col() + "," + pos.row())
                .orElse("?,?");
        boolean onlineMode = networkClient != null && networkClient.isConnected();

        mapLabel.setText("Map: " + combatMap.name());
        if (onlineMode) {
            turnLabel.setText(onlineTurnText());
        } else {
            String baseTurnText = "Window: " + session.getCurrentWindowIndex() + " | Ready: " + session.getReadyPlayers();
            if (resolutionPlaybackActive) {
                baseTurnText += " | Resolving step " + resolutionPlaybackStepIndex;
            }
            turnLabel.setText(baseTurnText);
        }

        updateTopStatusChips(onlineMode);

        String myTurnEnergy = formatTurnEnergy(p1);
        String meLabel = currentPlayerDisplayName();
        String oppLabel = currentOpponentDisplayName();

        playerLabel.setText("<html><b>" + meLabel + "</b>"
                + "<br/>HP " + displayedP1Hp + "/" + p1.maxHp() + " | EN " + p1.energy() + "/" + p1.maxEnergy()
                + " | TurnEN " + myTurnEnergy
                + "<br/>Class " + (playerSpec == null ? "Unknown" : playerSpec.title())
                + " | Pos " + p1Pos
                + " | Def " + (p1.isDefending() ? "ON" : "OFF")
                + "</html>");
        if (onlineMode) {
            botLabel.setText("<html><b>" + oppLabel + "</b>"
                    + "<br/>HP " + displayedP2Hp + "/" + p2.maxHp()
                    + "<br/>Online battle visibility: minimal</html>");
        } else {
            botLabel.setText("<html><b>" + oppLabel + "</b>"
                    + "<br/>HP " + displayedP2Hp + "/" + p2.maxHp() + " | EN " + p2.energy() + "/" + p2.maxEnergy()
                    + " | TurnEN " + formatTurnEnergy(p2)
                    + "<br/>Class " + (botSpec == null ? "Unknown" : botSpec.title())
                    + " | Pos " + p2Pos
                    + " | Def " + (p2.isDefending() ? "ON" : "OFF")
                    + "</html>");
        }

        coreHpLabel.setText("YOU " + displayedP1Hp + "/" + p1.maxHp()
            + " | " + (onlineMode ? "PLAYER " : "BOT ") + displayedP2Hp + "/" + p2.maxHp());
        coreEnergyLabel.setText("YOU " + p1.energy() + "/" + p1.maxEnergy() + " | Turn " + formatTurnEnergy(p1));
        int distance = distanceBetween(PLAYER_ID, BOT_ID);
        String selectedSkill = String.valueOf(skillCombo.getSelectedItem());
        coreRangeLabel.setText("Dist " + (distance < 0 ? "?" : distance) + " | Skill " + (selectedSkill == null ? "-" : selectedSkill));
        if (onlineMode) {
            coreStateLabel.setText("ONLINE"
                + (resolutionPlaybackActive ? " | Resolving" : " | Action Ready")
                + (onlineOpponentReady ? " | OppReady YES" : " | OppReady NO"));
        } else {
            coreStateLabel.setText("LOCAL" + (resolutionPlaybackActive ? " | Resolving" : " | Action Ready"));
        }

        cooldownArea.setText(buildSkillsHtml(onlineMode));
        firstPersonView.repaint();
        skillTreePanel.repaint();
    }

    private String formatTurnEnergy(PlayerState state) {
        if (state.maxEnergySpendPerWindow() == Integer.MAX_VALUE) {
            int fallbackCap = 6;
            int remain = Math.max(0, fallbackCap - Math.max(0, state.energySpentInWindow()));
            return remain + "/" + fallbackCap;
        }
        int remain = Math.max(0, state.maxEnergySpendPerWindow() - state.energySpentInWindow());
        return remain + "/" + state.maxEnergySpendPerWindow();
    }

    private void updateTopStatusChips(boolean onlineMode) {
        if (onlineMode) {
            long remainingMs = Math.max(0L, onlineWindowDeadlineMs - System.currentTimeMillis());
            turnOwnerChip.setText("TURN: SIMULTANEOUS");
            timerChip.setText("TIMER: " + (remainingMs / 1000L) + "s");
            readyChip.setText((onlineMyReady ? "YOU READY" : "YOU THINKING")
                + " | " + (onlineOpponentReady ? "OPP READY" : "OPP THINKING"));
            connectionChip.setText(networkClient != null && networkClient.isConnected() ? "NET: CONNECTED" : "NET: DISCONNECTED");
        } else {
            turnOwnerChip.setText("TURN: WINDOW " + session.getCurrentWindowIndex());
            timerChip.setText("READY: " + session.getReadyPlayers().size());
            readyChip.setText("LOCAL MATCH");
            connectionChip.setText("NET: OFFLINE");
        }
    }

    private int distanceBetween(String actorId, String targetId) {
        Optional<MapCellPosition> a = displayedPositionOf(actorId);
        Optional<MapCellPosition> b = displayedPositionOf(targetId);
        if (a.isEmpty() || b.isEmpty()) {
            return -1;
        }
        return Math.abs(a.get().col() - b.get().col()) + Math.abs(a.get().row() - b.get().row());
    }

    private String buildSkillsHtml(boolean onlineMode) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body style='font-family:Malgun Gothic; font-size:12px; margin:8px;'>");
        sb.append("<div style='font-weight:bold; font-size:13px; margin-bottom:6px;'>Player Skills</div>");
        if (playerSpec != null) {
            appendCooldownsHtml(sb, PLAYER_ID, playerSpec);
        } else {
            sb.append("<div style='color:#64748B; margin-bottom:8px;'>- synced from server</div>");
        }

        sb.append("<div style='font-weight:bold; font-size:13px; margin:10px 0 6px 0;'>Opponent Skills</div>");
        if (onlineMode) {
            sb.append("<div style='color:#64748B;'>- hidden in online battle</div>");
        } else if (botSpec != null) {
            appendCooldownsHtml(sb, BOT_ID, botSpec);
        } else {
            sb.append("<div style='color:#64748B;'>- synced from server</div>");
        }

        sb.append("</body></html>");
        return sb.toString();
    }

    private void appendCooldownsHtml(StringBuilder sb, String playerId, FighterSpec spec) {
        Map<String, Integer> cdMap = cooldowns.getOrDefault(playerId, Map.of());
        for (SkillTemplate skill : spec.skills()) {
            int remain = cdMap.getOrDefault(skill.name(), 0);
            String stateLabel;
            String stateBg;
            String stateFg;
            if (remain <= 0) {
                stateLabel = "READY";
                stateBg = "#DCFCE7";
                stateFg = "#166534";
            } else if (remain == 1) {
                stateLabel = "SOON";
                stateBg = "#FEF3C7";
                stateFg = "#92400E";
            } else {
                stateLabel = "COOLDOWN";
                stateBg = "#E5E7EB";
                stateFg = "#374151";
            }

            sb.append("<div style='padding:6px 8px; margin:4px 0; border:1px solid #E5E7EB; border-radius:6px; background:#FFFFFF;'>")
                    .append("<span style='font-weight:bold; color:#0F172A;'>")
                    .append(escapeHtml(skill.name()))
                    .append("</span>")
                    .append(" <span style='display:inline-block; padding:1px 6px; border-radius:10px; margin-left:6px; background:")
                    .append(stateBg)
                    .append("; color:")
                    .append(stateFg)
                    .append("; font-weight:bold;'>")
                    .append(stateLabel)
                    .append("</span>")
                    .append("<div style='color:#475569; margin-top:2px;'>")
                    .append("CD ").append(skill.cooldownTurns())
                    .append(" | Remaining ").append(remain)
                    .append("</div>")
                    .append("</div>");
        }
    }

    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private void log(String message) {
        log(classifyLogCategory(message), message);
    }

    private void log(LogCategory category, String message) {
        int windowIndex = session == null ? -1 : session.getCurrentWindowIndex();
        logEntries.add(new LogEntry(windowIndex, category, message));
        if (logEntries.size() > 1500) {
            logEntries.remove(0);
        }
        refreshLogArea();
    }

    private void refreshLogArea() {
        EnumSet<LogCategory> active = activeLogCategories();
        StringBuilder sb = new StringBuilder();
        Integer previousWindow = null;
        List<LogEntry> visibleEntries = new ArrayList<>();
        for (LogEntry entry : logEntries) {
            if (!active.contains(entry.category())) {
                continue;
            }
            visibleEntries.add(entry);
            if (previousWindow == null || previousWindow != entry.windowIndex()) {
                previousWindow = entry.windowIndex();
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append("=== ")
                        .append(entry.windowIndex() < 0 ? "SETUP" : "WINDOW " + entry.windowIndex())
                        .append(" ===\n");
            }
            sb.append("[")
                    .append(categoryTag(entry.category()))
                    .append("] ")
                    .append(entry.message())
                    .append("\n");
        }
        logArea.setText(sb.toString());
        logArea.setCaretPosition(logArea.getDocument().getLength());
        refreshLogSummary(visibleEntries);
    }

    private void refreshLogSummary(List<LogEntry> visibleEntries) {
        if (visibleEntries == null || visibleEntries.isEmpty()) {
            logSummaryLabel.setText("Recent: no events");
            return;
        }

        StringBuilder summary = new StringBuilder("<html><b>Recent:</b> ");
        int start = Math.max(0, visibleEntries.size() - 3);
        for (int i = start; i < visibleEntries.size(); i++) {
            LogEntry entry = visibleEntries.get(i);
            if (i > start) {
                summary.append(" &nbsp;|&nbsp; ");
            }
            summary.append("[")
                    .append(categoryTag(entry.category()))
                    .append("] ")
                    .append(trimForSummary(entry.message(), 34));
        }
        summary.append("</html>");
        logSummaryLabel.setText(summary.toString());
    }

    private String trimForSummary(String text, int limit) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() <= limit) {
            return trimmed;
        }
        return trimmed.substring(0, Math.max(0, limit - 1)) + "...";
    }

    private EnumSet<LogCategory> activeLogCategories() {
        EnumSet<LogCategory> active = EnumSet.noneOf(LogCategory.class);
        if (actionLogFilter.isSelected()) {
            active.add(LogCategory.ACTION);
        }
        if (combatLogFilter.isSelected()) {
            active.add(LogCategory.COMBAT);
        }
        if (progressionLogFilter.isSelected()) {
            active.add(LogCategory.PROGRESSION);
        }
        if (systemLogFilter.isSelected()) {
            active.add(LogCategory.SYSTEM);
        }
        if (active.isEmpty()) {
            active.add(LogCategory.SYSTEM);
        }
        return active;
    }

    private String categoryTag(LogCategory category) {
        return switch (category) {
            case ACTION -> "ACTION";
            case COMBAT -> "COMBAT";
            case PROGRESSION -> "PROGRESS";
            case SYSTEM -> "SYSTEM";
        };
    }

    private LogCategory classifyLogCategory(String message) {
        String lower = message == null ? "" : message.toLowerCase();
        if (lower.contains("mastery") || lower.contains("선택 가능") || lower.contains("승급")
                || lower.contains("learned") || lower.contains("호칭")) {
            return LogCategory.PROGRESSION;
        }
        if (lower.contains("attack") || lower.contains("casts") || lower.contains("dmg")
                || lower.contains("defend") || lower.contains("피격") || lower.contains("적중")) {
            return LogCategory.COMBAT;
        }
        if (lower.contains("moves") || lower.contains("ends turn") || lower.contains("action rejected")
                || lower.contains("행동") || lower.contains("이동") || lower.contains("준비 완료")) {
            return LogCategory.ACTION;
        }
        return LogCategory.SYSTEM;
    }

    private void setActionFeedback(String message, boolean isError) {
        actionFeedbackLabel.setText(message);
        actionFeedbackLabel.setForeground(isError ? new Color(184, 36, 36) : new Color(36, 84, 165));
    }

    private String actionName(GameAction action) {
        if (action == null) {
            return "Unknown";
        }
        if (action instanceof AttackAction) {
            return "Attack";
        }
        if (action instanceof DefendAction) {
            return "Defend";
        }
        if (action instanceof MoveAction) {
            return "Move";
        }
        if (action instanceof UseSkillAction skill) {
            return "Skill(" + skill.skillName() + ")";
        }
        if (action instanceof EndTurnAction) {
            return "End Turn";
        }
        return action.getClass().getSimpleName();
    }

    private void showWinner() {
        String winner = session.getWinnerId();
        if (winner == null) {
            return;
        }

        if (!battleReturnHandled.compareAndSet(false, true)) {
            return;
        }

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
        showResultDialog(text, false);
    }

    private void showResultDialog(String text, boolean disconnectOnExit) {
        SwingUtilities.invokeLater(() -> {
            if (activeResultDialog != null && activeResultDialog.isShowing()) {
                activeResultDialog.dispose();
            }

            Object[] options = {"계속 보기", "나가기"};
            JOptionPane pane = new JOptionPane(
                    text,
                    JOptionPane.INFORMATION_MESSAGE,
                    JOptionPane.DEFAULT_OPTION,
                    null,
                    options,
                    options[0]
            );

            JDialog dialog = pane.createDialog(this, "Result");
            dialog.setModal(false);
            dialog.setAlwaysOnTop(true);
            pane.addPropertyChangeListener((PropertyChangeEvent evt) -> {
                if (!JOptionPane.VALUE_PROPERTY.equals(evt.getPropertyName())) {
                    return;
                }
                Object selected = pane.getValue();
                if (selected == null || JOptionPane.UNINITIALIZED_VALUE.equals(selected)) {
                    return;
                }
                pane.setValue(JOptionPane.UNINITIALIZED_VALUE);
                dialog.dispose();

                if ("나가기".equals(selected)) {
                    if (disconnectOnExit && networkClient != null && networkClient.isConnected()) {
                        networkClient.disconnect();
                    }
                    dispose();
                }
            });

            dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosed(java.awt.event.WindowEvent e) {
                    if (activeResultDialog == dialog) {
                        activeResultDialog = null;
                    }
                }
            });

            activeResultDialog = dialog;
            dialog.setVisible(true);

            // 결과창을 띄운 뒤 연결을 끊는다. (게임 종료 → 서버 매치 정리 + resumable 비움 →
            // 다음 매칭이 완전히 새 게임이 됨. 끊기를 다이얼로그 표시 이후로 둬서
            // 끊기 부작용이 팝업 표시를 막지 않도록 한다.)
            if (disconnectOnExit && networkClient != null && networkClient.isConnected()) {
                networkClient.disconnect();
            }
        });
    }

    private void startNewMatch(MageArchetype archetype) {
        this.currentArchetype = archetype;
        this.onlineOpponentId = null;
        this.pendingOnlineResultText = null;
        this.pendingOnlineResultDisconnect = false;
        this.onlineLocalProjectedPlayerPos = null;
        this.onlineLocalProjectedWindowIndex = -1;
        this.pendingPromotionTarget = null;
        this.promotionService.resetForNewMatch();
        this.battleReturnHandled.set(false);
        this.resolutionPlaybackActive = false;
        stopPhaseFrameAnimation();
        this.resolutionPlaybackStepIndex = 0;
        this.lastPlayedResolvedWindowIndex = -1;
        this.currentPlaybackActions = List.of();
        this.playbackHpBeforeByPlayer.clear();
        this.playbackHpByPlayer.clear();
        this.playbackPositionBeforeByPlayer.clear();
        this.playbackPositionByPlayer.clear();
        this.opponentDisplayName = "Bot";
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
                        MapCellPosition projectedPos = inputProjectedPosition(actorId, actorPos);
                        int nextCol = projectedPos.col() + d[0];
                        int nextRow = projectedPos.row() + d[1];
                        if (nextCol == projectedPos.col() && nextRow == projectedPos.row()) {
                            continue;
                        }
                        if (!session.isInsideMap(nextCol, nextRow)) {
                            continue;
                        }
                        if (!session.isPassableCell(nextCol, nextRow)) {
                            continue;
                        }
                        if (session.isCellOccupied(nextCol, nextRow, actorId)) {
                            continue;
                        }
                        if (executeAction(new MoveAction(actorId, nextCol, nextRow, System.currentTimeMillis()),
                                actorLabel + " moves to (" + nextCol + "," + nextRow + ").")) {
                            rememberOnlineProjectedMove(actorId, nextCol, nextRow);
                            return true;
                        }
                    }
                    return false;
                })).orElse(false);
    }

    private boolean tryMoveInDirection(String actorId, MoveDirection direction, String actorLabel) {
        return session.getPlayerPosition(actorId).map(actorPos -> {
            MapCellPosition projectedPos = inputProjectedPosition(actorId, actorPos);
            int nextCol = projectedPos.col() + direction.deltaCol;
            int nextRow = projectedPos.row() + direction.deltaRow;
            boolean accepted = executeAction(new MoveAction(actorId, nextCol, nextRow, System.currentTimeMillis()),
                    actorLabel + " moves " + direction.label + " to (" + nextCol + "," + nextRow + ").");
            if (accepted) {
                rememberOnlineProjectedMove(actorId, nextCol, nextRow);
            }
            return accepted;
        }).orElse(false);
    }

    private MapCellPosition inputProjectedPosition(String actorId, MapCellPosition actorPos) {
        MapCellPosition projectedPos = session.getProjectedPlayerPosition(actorId).orElse(actorPos);
        if (networkClient != null
                && networkClient.isConnected()
                && PLAYER_ID.equals(actorId)
                && onlineLocalProjectedPlayerPos != null
                && onlineLocalProjectedWindowIndex == onlineWindowIndex) {
            return onlineLocalProjectedPlayerPos;
        }
        return projectedPos;
    }

    private void rememberOnlineProjectedMove(String actorId, int col, int row) {
        if (networkClient == null || !networkClient.isConnected()) {
            return;
        }
        if (!PLAYER_ID.equals(actorId)) {
            return;
        }
        onlineLocalProjectedPlayerPos = new MapCellPosition(col, row);
        onlineLocalProjectedWindowIndex = onlineWindowIndex;
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
        updatePromotionReadyButton();
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
        if (promotionService.isPromptShown()) {
            return;
        }

        Optional<MageArchetype> targetOpt = resolvePromotionTarget();
        if (targetOpt.isEmpty()) {
            return;
        }
        MageArchetype target = targetOpt.get();

        if (!isElementalPathConditionMet()) {
            return;
        }

        if (promotionService.isDeferred()) {
            handleDeferredElementalPath();
            return;
        }

        int choice = promptPromotionChoice(target);
        if (choice == 0) {
            promoteToArchetype(target,
                    "새로운 길을 선택했다. 호칭이 " + target.displayName() + "로 승급되었습니다.");
        } else if (choice == 1) {
            promotionService.setDeferred(true);
            log(target.displayName() + " 승급을 뒤로 미뤘다.");
            updatePromotionReadyButton();
        }
    }

    private boolean isElementalPathConditionMet() {
        MageSkillTree tree = presetFactory.createMageSkillTree(currentArchetype);
        long baseNodes = tree.nodes().stream()
                .filter(node -> node.inspirationCost() > 0 && node.inspirationCost() <= 2)
                .count();
        if (baseNodes == 0) {
            return false;
        }
        long learnedBaseNodes = tree.nodes().stream()
                .filter(node -> node.inspirationCost() > 0 && node.inspirationCost() <= 2)
                .filter(node -> progress.hasLearnedSkill(node.skill().name()))
                .count();
        return learnedBaseNodes * 2 >= baseNodes;
    }

    private void handleDeferredElementalPath() {
        if (promotionService.shouldNotifyDeferredOnce()) {
            String label = pendingPromotionTarget == null ? "승급" : pendingPromotionTarget.displayName() + " 승급";
            log(label + "을 보류했습니다. 우측 '승급 가능' 버튼에서 나중에 선택할 수 있습니다.");
            promotionService.markDeferredNotified();
        }
        updatePromotionReadyButton();
    }

    private int promptPromotionChoice(MageArchetype target) {
        return JOptionPane.showOptionDialog(
                this,
                "당신 앞에 " + target.displayName() + "의 길이 열렸다.",
                "MageFight",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.INFORMATION_MESSAGE,
                null,
                new Object[]{"선택한다", "포기한다"},
                "선택한다");
    }

    private boolean promoteToArchetype(MageArchetype target, String successLog) {
        if (target == null) {
            return false;
        }
        if (!ArchetypeUnlockService.trySelect(target, progress)) {
            String reason = ArchetypeUnlockService.lockReason(target, progress)
                    .orElse("승급 조건을 만족하지 못했습니다.");
            log(target.displayName() + " 승급 실패: " + reason);
            updatePromotionReadyButton();
            return false;
        }
        currentArchetype = target;
        promotionService.markPromptShown();
        promotionService.setDeferred(false);
        applyArchetypeToCurrentMatch(currentArchetype);
        startPromotionEffect(target.displayName() + " 승급");
        log(successLog);
        persistProgress();
        refreshArchetypeUi();
        return true;
    }

    private Optional<MageArchetype> resolvePromotionTarget() {
        Optional<MageArchetype> next = promotionService.findNextPromotionTarget(progress, currentArchetype);
        pendingPromotionTarget = next.orElse(null);
        return next;
    }

    private boolean canOfferPromotion(MageArchetype target) {
        if (target == null) {
            return false;
        }
        return ArchetypeUnlockService.lockReason(target, progress).isEmpty();
    }

    private void updatePromotionReadyButton() {
        resolvePromotionTarget();
        boolean visible = pendingPromotionTarget != null && promotionService.isDeferred() && canOfferPromotion(pendingPromotionTarget);
        promotionReadyBtn.setVisible(visible);
        promotionReadyBtn.setText(visible && pendingPromotionTarget != null ? pendingPromotionTarget.displayName() + " 승급" : "승급 가능");
        if (visible) {
            startPromotionBlink();
        } else {
            stopPromotionBlink();
        }
    }

    private void startPromotionBlink() {
        if (promotionBlinkTimer != null) {
            return;
        }
        String baseText = pendingPromotionTarget == null ? "승급 가능" : pendingPromotionTarget.displayName() + " 승급";
        promotionBlinkOn = false;
        promotionBlinkTimer = new Timer(380, e -> {
            promotionBlinkOn = !promotionBlinkOn;
            if (promotionBlinkOn) {
                promotionReadyBtn.setBackground(new Color(255, 214, 102));
                promotionReadyBtn.setText(baseText + "!");
            } else {
                promotionReadyBtn.setBackground(new Color(255, 241, 166));
                promotionReadyBtn.setText(baseText);
            }
        });
        promotionBlinkTimer.setRepeats(true);
        promotionBlinkTimer.start();
    }

    private void stopPromotionBlink() {
        if (promotionBlinkTimer != null) {
            promotionBlinkTimer.stop();
            promotionBlinkTimer = null;
        }
        promotionBlinkOn = false;
        promotionReadyBtn.setBackground(new Color(255, 241, 166));
        promotionReadyBtn.setText(pendingPromotionTarget == null ? "승급 가능" : pendingPromotionTarget.displayName() + " 승급");
    }

    private void applyArchetypeToCurrentMatch(MageArchetype archetype) {
        if (archetype == null || playerSpec == null) {
            return;
        }
        FighterSpec updatedSpec = presetFactory.createPlayerSpec(archetype, progress);
        playerSpec = updatedSpec;
        specs.put(PLAYER_ID, updatedSpec);

        Map<String, Integer> previousCooldowns = cooldowns.getOrDefault(PLAYER_ID, Map.of());
        Map<String, Integer> mergedCooldowns = new HashMap<>();
        for (SkillTemplate skill : updatedSpec.skills()) {
            mergedCooldowns.put(skill.name(), Math.max(0, previousCooldowns.getOrDefault(skill.name(), 0)));
        }
        cooldowns.put(PLAYER_ID, mergedCooldowns);

        String selectedSkill = String.valueOf(skillCombo.getSelectedItem());
        skillCombo.removeAllItems();
        for (SkillTemplate skill : updatedSpec.skills()) {
            skillCombo.addItem(skill.name());
        }
        if (selectedSkill != null && mergedCooldowns.containsKey(selectedSkill)) {
            skillCombo.setSelectedItem(selectedSkill);
        }

        if (session != null) {
            PlayerState state = session.getPlayerState(PLAYER_ID);
            applyTierEnergyRule(state, archetype);
            configureMovementRules(state, updatedSpec);
            for (SkillTemplate skill : updatedSpec.skills()) {
                session.registerSkill(skill);
                state.registerSkill(skill.name());
            }
        }

        refreshUi();
    }

    private void startPromotionEffect(String text) {
        promotionEffectActive = true;
        promotionEffectStartedAtMs = System.currentTimeMillis();
        promotionEffectText = text == null ? "승급" : text;
        firstPersonView.repaint();
        Timer timer = new Timer(1400, e -> {
            promotionEffectActive = false;
            promotionEffectText = "";
            firstPersonView.repaint();
        });
        timer.setRepeats(false);
        timer.start();
    }

    private void applyFontRecursively(Container container, Font font) {
        for (Component component : container.getComponents()) {
            component.setFont(font);
            if (component instanceof Container childContainer) {
                applyFontRecursively(childContainer, font);
            }
        }
    }

    private void startNetworkMatch() {
        if (networkClient == null) {
            return;
        }
        this.onlineOpponentId = null;
        this.battleReturnHandled.set(false);
        this.lastPlayedResolvedWindowIndex = -1;
        this.pendingOnlineResultText = null;
        this.pendingOnlineResultDisconnect = false;
        this.onlineLocalProjectedPlayerPos = null;
        this.onlineLocalProjectedWindowIndex = -1;

        // 온라인에서도 Skills 탭/콤보가 내 스킬과 쿨다운을 표시하도록 playerSpec과
        // 쿨다운 맵을 초기화한다. (오프라인의 startNewMatch와 동일한 역할.)
        this.playerSpec = presetFactory.createPlayerSpec(currentArchetype, progress);
        this.specs.put(PLAYER_ID, this.playerSpec);
        Map<String, Integer> playerCooldowns = this.cooldowns.computeIfAbsent(PLAYER_ID, k -> new HashMap<>());
        for (SkillTemplate skill : this.playerSpec.skills()) {
            playerCooldowns.putIfAbsent(skill.name(), 0);
        }

        // 매칭 상태 변경 핸들링
        networkClient.setOnMatchStateChanged(state -> {
            switch (state) {
                case SEARCHING:
                    matchingStatusLabel.setText("Searching for opponent...");
                    matchingStatusLabel.setForeground(new Color(255, 128, 0));
                    findGameBtn.setEnabled(false);
                    cancelMatchmakingBtn.setEnabled(true);
                    startMatchingTimeoutTimer();
                    log(LogCategory.SYSTEM, "매칭 검색 시작");
                    break;
                case MATCHED:
                    stopMatchingTimeoutTimer();
                    matchingStatusLabel.setText("Opponent found!");
                    matchingStatusLabel.setForeground(new Color(0, 128, 0));
                    log(LogCategory.SYSTEM, "상대방을 찾았습니다!");
                    break;
                case IDLE:
                    stopMatchingTimeoutTimer();
                    stopOnlineTurnTimer();
                    matchingStatusLabel.setText("Offline Mode");
                    matchingStatusLabel.setForeground(new Color(100, 100, 100));
                    findGameBtn.setEnabled(true);
                    cancelMatchmakingBtn.setEnabled(false);
                    break;
                case DISCONNECTED:
                    stopMatchingTimeoutTimer();
                    stopOnlineTurnTimer();
                    matchingStatusLabel.setText("Disconnected");
                    matchingStatusLabel.setForeground(new Color(200, 0, 0));
                    findGameBtn.setEnabled(false);
                    cancelMatchmakingBtn.setEnabled(false);
                    break;
            }
        });

        // 서버 메시지 핸들링
        networkClient.setOnMessageReceived(msg -> {
            if ("MATCHED".equalsIgnoreCase(msg.type())) {
                handleMatchFound();
            } else if ("MATCH_STARTED".equalsIgnoreCase(msg.type())) {
                handleMatchStarted(msg);
            } else if ("STATE_UPDATED".equalsIgnoreCase(msg.type())) {
                handleStateUpdated(msg);
            } else if ("PLAYER_DISCONNECTED".equalsIgnoreCase(msg.type())) {
                handlePlayerDisconnected(msg);
            } else if ("PLAYER_RECONNECTED".equalsIgnoreCase(msg.type()) || "MATCH_RESUMED".equalsIgnoreCase(msg.type())) {
                handleMatchResumed();
            } else if ("GAME_ENDED".equalsIgnoreCase(msg.type())) {
                handleGameEnded(msg);
            }
        });

        // 에러 핸들링
        networkClient.setOnErrorReceived(errorMsg -> {
            log(LogCategory.SYSTEM, "온라인 오류: " + errorMsg);
            setActionFeedback("Connection error: " + errorMsg, true);
            if (networkClient.getMatchState() == GameNetworkClient.MatchState.SEARCHING) {
                networkClient.cancelMatchmaking();
            }
        });

        networkClient.requestImmediateEventSync();

        log(LogCategory.SYSTEM, "온라인 모드 대기 중...");
        turnLabel.setText("Ready for online match");
    }

    private void handleMatchFound() {
        log(LogCategory.SYSTEM, "매칭 완료! 게임 시작 준비 중...");
        turnLabel.setText("Match found! Starting game...");
        SwingUtilities.invokeLater(this::refreshUi);
    }

    @SuppressWarnings("unchecked")
    private void handleMatchStarted(com.turngame.server.protocol.ResponseMessage msg) {
        String playerId = String.valueOf(msg.payload().get("playerId"));
        String matchId = String.valueOf(msg.payload().get("matchId"));
        networkClient.setMyPlayerId(playerId);
        networkClient.setMatchId(matchId);

        log("게임 시작! (ID: " + playerId + ")");
        turnLabel.setText("Game started! Syncing battle state...");
        networkClient.requestImmediateEventSync();

        refreshUi();
    }

    @SuppressWarnings("unchecked")
    private void handleStateUpdated(com.turngame.server.protocol.ResponseMessage msg) {
        Map<String, Object> payload = msg.payload();
        if (onlinePausedForReconnect) {
            onlinePausedForReconnect = false;
            onlineReconnectDeadlineEpochMs = 0L;
            dismissReconnectWaitingDialog();
        }
        long eventSeq = asLong(payload.get("_eventSeq"), -1L);
        if (eventSeq >= 0 && eventSeq <= lastAppliedStateEventSeq) {
            return;
        }
        if (eventSeq >= 0) {
            lastAppliedStateEventSeq = eventSeq;
        }
        if (networkClient != null) {
            Object matchIdObj = payload.get("matchId");
            if (matchIdObj != null) {
                String payloadMatchId = String.valueOf(matchIdObj);
                if (!payloadMatchId.isBlank() && !"null".equalsIgnoreCase(payloadMatchId)) {
                    networkClient.setMatchId(payloadMatchId);
                }
            }
        }
        OnlineStateSyncService.SyncSnapshot snapshot = applyOnlineSyncSnapshot(payload);
        updateOnlineTurnState(payload);
        startOnlineTurnTimer();
        turnLabel.setText(onlineTurnText());

        int resolvedWindowIndex = snapshot == null ? -1 : snapshot.resolvedWindowIndex();
        List<GameSession.ResolutionStep> steps = snapshot == null ? List.of() : snapshot.resolutionSteps();
        boolean hasNewResolution = resolvedWindowIndex > lastPlayedResolvedWindowIndex;
        if (hasNewResolution && !steps.isEmpty()) {
            lastPlayedResolvedWindowIndex = resolvedWindowIndex;
            playResolutionSteps(steps);
            return;
        }

        refreshUi();
    }

    private OnlineStateSyncService.SyncSnapshot applyOnlineSyncSnapshot(Map<String, Object> payload) {
        if (networkClient == null || !networkClient.isConnected()) {
            return null;
        }
        OnlineStateSyncService.SyncSnapshot snapshot = onlineStateSyncService.synchronize(
                payload,
                networkClient.getMyPlayerId(),
                onlineOpponentId,
                combatMap
        );
        if (snapshot == null) {
            return null;
        }

        combatMap = snapshot.map();
        session = snapshot.session();
        onlineOpponentId = snapshot.opponentId();
        playerDisplayName = snapshot.myCharacterDisplayName();
        opponentDisplayName = snapshot.opponentCharacterDisplayName();
        if (snapshot.opponentSkinColorHex() != null) {
            opponentSkinColor = parseHexColor(snapshot.opponentSkinColorHex(), opponentSkinColor);
        }
        if (snapshot.opponentOutfitColorHex() != null) {
            opponentOutfitColor = parseHexColor(snapshot.opponentOutfitColorHex(), opponentOutfitColor);
        }
        System.out.println("[MageFightFrame] sync ok: mySkills=" + snapshot.mySkillNames().size()
                + ", oppSkinHex=" + snapshot.opponentSkinColorHex()
                + ", oppOutfitHex=" + snapshot.opponentOutfitColorHex());
        syncOnlineSkillCombo(snapshot.mySkillNames());
        // 서버가 보낸 내 스킬 쿨다운을 반영한다 (Skills 탭이 봇전처럼 남은 쿨다운 표시).
        if (snapshot.myCooldowns() != null && !snapshot.myCooldowns().isEmpty()) {
            Map<String, Integer> cd = cooldowns.computeIfAbsent(PLAYER_ID, k -> new HashMap<>());
            cd.clear();
            cd.putAll(snapshot.myCooldowns());
        }
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private void updateOnlineTurnState(Map<String, Object> payload) {
        if (payload == null) {
            return;
        }
        onlineWindowDurationSeconds = asInt(payload.get("windowDurationSeconds"), 60);
        int windowIndex = asInt(payload.get("windowIndex"), -1);
        if (windowIndex != onlineWindowIndex) {
            onlineWindowIndex = windowIndex;
            onlineWindowDeadlineMs = System.currentTimeMillis() + onlineWindowDurationSeconds * 1000L;
            onlineLocalProjectedPlayerPos = null;
            onlineLocalProjectedWindowIndex = -1;
        }

        onlineMyReady = false;
        onlineOpponentReady = false;
        String myId = networkClient == null ? null : networkClient.getMyPlayerId();
        Object readyObj = payload.get("readyPlayers");
        if (readyObj instanceof List<?> readyPlayers) {
            for (Object value : readyPlayers) {
                String readyId = String.valueOf(value);
                if (myId != null && myId.equals(readyId)) {
                    onlineMyReady = true;
                }
                if (onlineOpponentId != null && onlineOpponentId.equals(readyId)) {
                    onlineOpponentReady = true;
                }
            }
        }
    }

    private void startOnlineTurnTimer() {
        if (onlineTurnTimer != null) {
            return;
        }
        onlineTurnTimer = new Timer(1000, e -> {
            if (networkClient == null || !networkClient.isConnected()) {
                stopOnlineTurnTimer();
                return;
            }
            refreshUi();
        });
        onlineTurnTimer.setRepeats(true);
        onlineTurnTimer.start();
    }

    private void stopOnlineTurnTimer() {
        if (onlineTurnTimer == null) {
            return;
        }
        onlineTurnTimer.stop();
        onlineTurnTimer = null;
    }

    private void handlePlayerDisconnected(com.turngame.server.protocol.ResponseMessage msg) {
        Map<String, Object> payload = msg.payload();
        String disconnectedPlayerId = String.valueOf(payload.get("playerId"));
        String myPlayerId = networkClient == null ? null : networkClient.getMyPlayerId();
        if (myPlayerId == null || myPlayerId.equals(disconnectedPlayerId)) {
            return;
        }

        onlinePausedForReconnect = true;
        onlineReconnectDeadlineEpochMs = asLong(payload.get("reconnectDeadlineEpochMs"), 0L);
        setActionFeedback("Opponent disconnected. Waiting for reconnect...", true);
        showReconnectWaitingDialog();
        refreshUi();
    }

    private void handleMatchResumed() {
        if (!onlinePausedForReconnect) {
            return;
        }
        onlinePausedForReconnect = false;
        onlineReconnectDeadlineEpochMs = 0L;
        dismissReconnectWaitingDialog();
        setActionFeedback("Opponent reconnected. Match resumed.", false);
        refreshUi();
    }

    private void showReconnectWaitingDialog() {
        SwingUtilities.invokeLater(() -> {
            if (onlineWaitingDialog != null && onlineWaitingDialog.isShowing()) {
                return;
            }
            long remainSec = onlineReconnectDeadlineEpochMs <= 0L
                    ? 60L
                    : Math.max(0L, (onlineReconnectDeadlineEpochMs - System.currentTimeMillis()) / 1000L);
            JOptionPane pane = new JOptionPane(
                    "Opponent disconnected. Waiting... (" + remainSec + "s)",
                    JOptionPane.INFORMATION_MESSAGE,
                    JOptionPane.DEFAULT_OPTION,
                    null,
                    new Object[]{}
            );
            JDialog dialog = pane.createDialog(this, "Waiting...");
            dialog.setModal(false);
            dialog.setAlwaysOnTop(true);
            dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
            onlineWaitingDialog = dialog;
            dialog.setVisible(true);
        });
    }

    private void dismissReconnectWaitingDialog() {
        SwingUtilities.invokeLater(() -> {
            if (onlineWaitingDialog != null) {
                onlineWaitingDialog.dispose();
                onlineWaitingDialog = null;
            }
        });
    }

    private String onlineTurnText() {
        if (onlinePausedForReconnect) {
            long remainingSec = onlineReconnectDeadlineEpochMs <= 0L
                    ? 0L
                    : Math.max(0L, (onlineReconnectDeadlineEpochMs - System.currentTimeMillis()) / 1000L);
            return "Paused: waiting for opponent reconnect (" + remainingSec + "s)";
        }
        long remainingMs = Math.max(0L, onlineWindowDeadlineMs - System.currentTimeMillis());
        long remainingSec = remainingMs / 1000L;
        String myReadyText = onlineMyReady ? "YOU READY" : "YOU DECIDING";
        String oppReadyText = onlineOpponentReady ? "OPP READY" : "OPP DECIDING";
        return "Window " + Math.max(0, onlineWindowIndex) + " | " + remainingSec + "s | " + myReadyText + " | " + oppReadyText;
    }

    private int asInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private long asLong(Object value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private String currentPlayerDisplayName() {
        if (playerDisplayName != null && !playerDisplayName.isBlank()) {
            return playerDisplayName;
        }
        return "Player";
    }

    private String currentOpponentDisplayName() {
        if (opponentDisplayName != null && !opponentDisplayName.isBlank()) {
            return opponentDisplayName;
        }
        return networkClient != null && networkClient.isConnected() ? "Opponent" : "Bot";
    }

    private void syncOnlineSkillCombo(List<String> skillNames) {
        skillCombo.removeAllItems();
        if (skillNames == null) {
            return;
        }
        for (String skillName : skillNames) {
            if (skillName != null && !skillName.isBlank()) {
                skillCombo.addItem(skillName);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void handleGameEnded(com.turngame.server.protocol.ResponseMessage msg) {
        String winnerId = String.valueOf(msg.payload().get("winnerId"));
        String currentPlayerId = networkClient.getMyPlayerId();
        Map<String, Object> payload = msg.payload();
        String reason = String.valueOf(payload.get("reason"));
        String surrenderedPlayerId = String.valueOf(payload.get("surrenderedPlayerId"));
        System.out.println("[MageFightFrame] GAME_ENDED reason=" + reason + ", winner=" + winnerId
                + ", me=" + currentPlayerId + ", playbackActive=" + resolutionPlaybackActive
                + ", alreadyHandled=" + battleReturnHandled.get());

        OnlineStateSyncService.SyncSnapshot snapshot = applyOnlineSyncSnapshot(payload);
        int resolvedWindowIndex = snapshot == null ? -1 : snapshot.resolvedWindowIndex();
        List<GameSession.ResolutionStep> steps = snapshot == null ? List.of() : snapshot.resolutionSteps();
        boolean hasNewResolution = resolvedWindowIndex > lastPlayedResolvedWindowIndex;
        if (hasNewResolution && !steps.isEmpty()) {
            lastPlayedResolvedWindowIndex = resolvedWindowIndex;
            playResolutionSteps(steps);
        } else {
            refreshUi();
        }

        if (!battleReturnHandled.compareAndSet(false, true)) {
            return;
        }

        if ("PLAYER_SURRENDERED".equalsIgnoreCase(reason)) {
            boolean iWon;
            if (surrenderedPlayerId != null && !surrenderedPlayerId.isBlank() && !"null".equalsIgnoreCase(surrenderedPlayerId)) {
                iWon = !surrenderedPlayerId.equals(currentPlayerId);
            } else {
                iWon = winnerId.equals(currentPlayerId);
            }
            String message = iWon ? "Opponent surrendered!" : "You surrendered.";
            log(iWon ? "상대가 기권했습니다." : "기권으로 패배했습니다.");
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(this, message, "Result", JOptionPane.INFORMATION_MESSAGE);
                if (networkClient != null && networkClient.isConnected()) {
                    networkClient.disconnect();
                }
                dispose();
            });
            return;
        }

        if ("PLAYER_ABANDONED".equalsIgnoreCase(reason)) {
            boolean iWon = winnerId.equals(currentPlayerId);
            String message = iWon
                    ? "Opponent did not return in time. You win!"
                    : "Reconnection timeout. You lose.";
            log(iWon ? "상대 미복귀로 승리!" : "미복귀 패배 처리됨.");
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(this, message, "Result", JOptionPane.INFORMATION_MESSAGE);
                if (networkClient != null && networkClient.isConnected()) {
                    networkClient.disconnect();
                }
                dispose();
            });
            return;
        }

        if (winnerId.equals(currentPlayerId)) {
            log("승리!");
            pendingOnlineResultText = "You win!";
            pendingOnlineResultDisconnect = true;
        } else {
            log("패배!");
            pendingOnlineResultText = "You lose!";
            pendingOnlineResultDisconnect = true;
        }

        if (!resolutionPlaybackActive && pendingOnlineResultText != null) {
            showResultDialog(pendingOnlineResultText, pendingOnlineResultDisconnect);
            pendingOnlineResultText = null;
            pendingOnlineResultDisconnect = false;
        } else if (pendingOnlineResultText != null) {
            // 정산 연출이 끝나면 applyResolutionPhase에서 결과를 띄우지만,
            // 연출이 어떤 이유로 끝나지 않아도 결과가 반드시 뜨도록 안전망 타이머를 건다.
            final String safetyText = pendingOnlineResultText;
            final boolean safetyDisconnect = pendingOnlineResultDisconnect;
            Timer safetyTimer = new Timer(4000, e -> {
                if (pendingOnlineResultText != null) {
                    System.out.println("[MageFightFrame] result safety timer firing (playback did not finish)");
                    resolutionPlaybackActive = false;
                    showResultDialog(safetyText, safetyDisconnect);
                    pendingOnlineResultText = null;
                    pendingOnlineResultDisconnect = false;
                }
            });
            safetyTimer.setRepeats(false);
            safetyTimer.start();
        }
    }

    private void playResolutionSteps(List<GameSession.ResolutionStep> steps) {
        if (steps == null || steps.isEmpty()) {
            refreshUi();
            if (pendingOnlineResultText != null) {
                showResultDialog(pendingOnlineResultText, pendingOnlineResultDisconnect);
                pendingOnlineResultText = null;
                pendingOnlineResultDisconnect = false;
            }
            if (session.isFinished()) {
                showWinner();
            }
            return;
        }

        resolutionPlaybackActive = true;
        resolutionPlaybackStepIndex = 0;
        playbackHpBeforeByPlayer.clear();
        playbackHpByPlayer.clear();
        playbackPositionBeforeByPlayer.clear();
        playbackPositionByPlayer.clear();
        startPhaseFrameAnimation();

        List<PlaybackPhase> phases = buildPlaybackPhases(steps);
        applyResolutionPhase(phases, 0);
    }

    private void applyResolutionPhase(List<PlaybackPhase> phases, int index) {
        if (index >= phases.size()) {
            resolutionPlaybackActive = false;
            currentPhaseType = PhaseType.IMPACT;
            stopPhaseFrameAnimation();
            currentPlaybackActions = List.of();
            playbackHpBeforeByPlayer.clear();
            playbackHpByPlayer.clear();
            playbackPositionBeforeByPlayer.clear();
            playbackPositionByPlayer.clear();
            refreshUi();
            if (pendingOnlineResultText != null) {
                showResultDialog(pendingOnlineResultText, pendingOnlineResultDisconnect);
                pendingOnlineResultText = null;
                pendingOnlineResultDisconnect = false;
            }
            if (session.isFinished()) {
                showWinner();
            }
            return;
        }

        PlaybackPhase phase = phases.get(index);
        GameSession.ResolutionStep step = phase.step();
        resolutionPlaybackStepIndex = step.stepIndex();
        currentPhaseType = phase.phaseType();
        currentPhaseStartedAtMs = System.currentTimeMillis();
        currentPhaseDurationMs = Math.max(1, phase.durationMs());
        currentPlaybackActions = step.actions();
        playbackHpBeforeByPlayer.clear();
        playbackHpBeforeByPlayer.putAll(step.hpBeforeByPlayer());
        playbackPositionBeforeByPlayer.clear();
        playbackPositionBeforeByPlayer.putAll(step.positionBeforeByPlayer());

        playbackHpByPlayer.clear();
        playbackPositionByPlayer.clear();
        if (phase.phaseType() == PhaseType.CAST) {
            playbackHpByPlayer.putAll(step.hpBeforeByPlayer());
            playbackPositionByPlayer.putAll(step.positionBeforeByPlayer());
        } else {
            playbackHpByPlayer.putAll(step.hpAfterByPlayer());
            playbackPositionByPlayer.putAll(step.positionAfterByPlayer());
        }

        log(stepSummary(step, phase.phaseType()));
        refreshUi();

        Timer timer = new Timer(phase.durationMs(), e -> applyResolutionPhase(phases, index + 1));
        timer.setRepeats(false);
        timer.start();
    }

    private void startPhaseFrameAnimation() {
        stopPhaseFrameAnimation();
        phaseFrameTimer = new Timer(16, e -> firstPersonView.repaint());
        phaseFrameTimer.setRepeats(true);
        phaseFrameTimer.start();
    }

    private void stopPhaseFrameAnimation() {
        if (phaseFrameTimer == null) {
            return;
        }
        phaseFrameTimer.stop();
        phaseFrameTimer = null;
    }

    @Override
    public double currentPhaseProgress() {
        long elapsedMs = Math.max(0L, System.currentTimeMillis() - currentPhaseStartedAtMs);
        return Math.max(0.0, Math.min(1.0, elapsedMs / (double) Math.max(1, currentPhaseDurationMs)));
    }

    private List<PlaybackPhase> buildPlaybackPhases(List<GameSession.ResolutionStep> steps) {
        List<PlaybackPhase> phases = new ArrayList<>();
        for (GameSession.ResolutionStep step : steps) {
            int maxPrepareMs = maxPrepareCastMs(step.actions());
            boolean hasSkill = maxPrepareMs > 0;
            if (hasSkill) {
                int castDurationMs = Math.max(300, Math.min(1200, maxPrepareMs));
                phases.add(new PlaybackPhase(step, PhaseType.CAST, castDurationMs));
            }
            phases.add(new PlaybackPhase(step, PhaseType.IMPACT, 700));
        }
        return phases;
    }

    private int maxPrepareCastMs(List<GameSession.ResolvedActionView> actions) {
        int max = 0;
        for (GameSession.ResolvedActionView action : actions) {
            if (action.actionType() != ActionType.USE_SKILL || action.skillName() == null) {
                continue;
            }
            int prepareMs = session.getSkillTemplate(action.skillName())
                    .map(SkillTemplate::prepareCastMs)
                    .orElse(0);
            max = Math.max(max, prepareMs);
        }
        return max;
    }

    private String stepSummary(GameSession.ResolutionStep step, PhaseType phaseType) {
        GameSession.ResolvedActionView myAction = findStepAction(step, PLAYER_ID);
        GameSession.ResolvedActionView enemyAction = findStepAction(step, BOT_ID);

        StringBuilder sb = new StringBuilder();
        sb.append("===\n");
        sb.append("나 - ").append(actionText(myAction, step, phaseType)).append("\n");
        sb.append("상대 - ").append(actionText(enemyAction, step, phaseType)).append("\n");

        if (phaseType == PhaseType.CAST) {
            sb.append("시전 준비 중...\n");
            sb.append("===");
            return sb.toString();
        }

        int myDamageTaken = hpDelta(step, PLAYER_ID);
        int enemyDamageTaken = hpDelta(step, BOT_ID);
        boolean myAimedMiss = isAimedSkillMiss(myAction, step);
        boolean enemyAimedMiss = isAimedSkillMiss(enemyAction, step);
        if (myDamageTaken > 0) {
            sb.append("피격당했습니다!\n");
        }
        if (myAimedMiss) {
            sb.append("내 스킬이 빗나갔습니다!\n");
        }
        int enemyIncomingDamage = incomingDamage(step, BOT_ID);

        if (myAction != null && myAction.actionType() == ActionType.DEFEND) {
            if (myDamageTaken == 0 && isTargetedByAttack(step, PLAYER_ID)) {
                sb.append("전부 방어했습니다!\n");
            } else if (myDamageTaken > 0 && isTargetedByAttack(step, PLAYER_ID)) {
                sb.append("일부 방어했습니다!\n");
            }
        }
        if (enemyAction != null && enemyAction.actionType() == ActionType.DEFEND && isTargetedByAttack(step, BOT_ID)) {
            if (enemyDamageTaken == 0) {
                sb.append("상대가 흘려보냈습니다!\n");
            } else if (enemyIncomingDamage > 0 && enemyDamageTaken < enemyIncomingDamage) {
                sb.append("상대가 일부 방어했습니다!\n");
            } else if (enemyDamageTaken > 0) {
                sb.append("상대를 적중시켰습니다!\n");
            }
        } else if (enemyDamageTaken > 0) {
            sb.append("상대를 적중시켰습니다!\n");
        }
        if (enemyAimedMiss) {
            sb.append("상대 스킬이 빗나갔습니다!\n");
        }
        sb.append("===");
        return sb.toString();
    }

    private boolean isAimedSkillMiss(GameSession.ResolvedActionView action, GameSession.ResolutionStep step) {
        if (action == null || action.actionType() != ActionType.USE_SKILL) {
            return false;
        }
        if (action.targetCol() == null || action.targetRow() == null || action.targetId() == null) {
            return false;
        }
        MapCellPosition targetBefore = step.positionBeforeByPlayer().get(action.targetId());
        if (targetBefore == null) {
            return false;
        }
        return targetBefore.col() != action.targetCol() || targetBefore.row() != action.targetRow();
    }

    private int incomingDamage(GameSession.ResolutionStep step, String targetId) {
        int total = 0;
        for (GameSession.ResolvedActionView action : step.actions()) {
            if ((action.actionType() != ActionType.ATTACK && action.actionType() != ActionType.USE_SKILL)
                    || !targetId.equals(action.targetId())) {
                continue;
            }
            Integer damageValue = action.damage();
            if (damageValue != null) {
                total += Math.max(0, damageValue);
            }
        }
        return total;
    }

    private int hpDelta(GameSession.ResolutionStep step, String playerId) {
        int before = step.hpBeforeByPlayer().getOrDefault(playerId, step.hpAfterByPlayer().getOrDefault(playerId, 0));
        int after = step.hpAfterByPlayer().getOrDefault(playerId, before);
        return Math.max(0, before - after);
    }

    private GameSession.ResolvedActionView findStepAction(GameSession.ResolutionStep step, String actorId) {
        for (GameSession.ResolvedActionView action : step.actions()) {
            if (actorId.equals(action.actorId())) {
                return action;
            }
        }
        return null;
    }

    private boolean isTargetedByAttack(GameSession.ResolutionStep step, String targetId) {
        for (GameSession.ResolvedActionView action : step.actions()) {
            if ((action.actionType() == ActionType.ATTACK || action.actionType() == ActionType.USE_SKILL)
                    && targetId.equals(action.targetId())) {
                return true;
            }
        }
        return false;
    }

    private String actionText(GameSession.ResolvedActionView action, GameSession.ResolutionStep step, PhaseType phaseType) {
        if (action == null) {
            return "";
        }
        if (action.actionType() == ActionType.USE_SKILL) {
            if (phaseType == PhaseType.CAST) {
                return (action.skillName() == null ? "스킬" : action.skillName()) + " (시전)";
            }
            return action.skillName() == null ? "스킬" : action.skillName();
        }
        if (action.actionType() == ActionType.ATTACK) {
            return "공격";
        }
        if (action.actionType() == ActionType.DEFEND) {
            return "방어";
        }
        if (action.actionType() == ActionType.MOVE) {
            if (isBouncedMove(action, step)) {
                return "이동(충돌로 튕김)";
            }
            return "이동";
        }
        return action.actionType().name();
    }

    @Override
    public Optional<MapCellPosition> displayedPositionOf(String playerId) {
        if (resolutionPlaybackActive && playbackPositionByPlayer.containsKey(playerId)) {
            return Optional.ofNullable(playbackPositionByPlayer.get(playerId));
        }
        // 온라인 대전에서는 move를 눌러도 보드에 즉시 반영하지 않는다.
        // 예약된 이동의 실제 위치 변화는 end turn 후 정산 연출(playResolutionSteps)에서만
        // 단계별로 보여준다. (onlineLocalProjectedPlayerPos는 다음 이동 목표 칸을
        // 계산하기 위한 inputProjectedPosition 용도로만 유지된다.)
        return session.getPlayerPosition(playerId);
    }

    private int displayedHpOf(String playerId, int fallback) {
        if (resolutionPlaybackActive && playbackHpByPlayer.containsKey(playerId)) {
            return playbackHpByPlayer.get(playerId);
        }
        return fallback;
    }

    @Override
    public boolean isActorCastingInCurrentStep(String actorId) {
        if (currentPhaseType != PhaseType.CAST) {
            return false;
        }
        for (GameSession.ResolvedActionView action : currentPlaybackActions) {
            if (actorId.equals(action.actorId())
                    && (action.actionType() == ActionType.ATTACK || action.actionType() == ActionType.USE_SKILL)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isDefendingInCurrentStep(String actorId) {
        if (currentPhaseType == PhaseType.CAST) {
            return false;
        }
        for (GameSession.ResolvedActionView action : currentPlaybackActions) {
            if (actorId.equals(action.actorId()) && action.actionType() == ActionType.DEFEND) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isPlayerHitInCurrentStep(String playerId) {
        if (currentPhaseType == PhaseType.CAST) {
            return false;
        }
        if (!isActuallyDamagedInCurrentStep(playerId)) {
            return false;
        }
        for (GameSession.ResolvedActionView action : currentPlaybackActions) {
            if ((action.actionType() == ActionType.ATTACK || action.actionType() == ActionType.USE_SKILL)
                    && playerId.equals(action.targetId())) {
                return true;
            }
        }
        return false;
    }

    private boolean isActuallyDamagedInCurrentStep(String playerId) {
        int beforeHp = playbackHpBeforeByPlayer.getOrDefault(playerId, playbackHpByPlayer.getOrDefault(playerId, 0));
        int afterHp = playbackHpByPlayer.getOrDefault(playerId, beforeHp);
        return afterHp < beforeHp;
    }

    @Override
    public boolean isBouncedInCurrentStep(String actorId) {
        if (currentPhaseType == PhaseType.CAST) {
            return false;
        }
        for (GameSession.ResolvedActionView action : currentPlaybackActions) {
            if (actorId.equals(action.actorId()) && action.actionType() == ActionType.MOVE) {
                if (isBouncedMove(action)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isBouncedMove(GameSession.ResolvedActionView action) {
        MapCellPosition after = playbackPositionByPlayer.get(action.actorId());
        if (after == null || action.targetCol() == null || action.targetRow() == null) {
            return false;
        }
        return after.col() != action.targetCol() || after.row() != action.targetRow();
    }

    private boolean isBouncedMove(GameSession.ResolvedActionView action, GameSession.ResolutionStep step) {
        MapCellPosition after = step.positionAfterByPlayer().get(action.actorId());
        if (after == null || action.targetCol() == null || action.targetRow() == null) {
            return false;
        }
        return after.col() != action.targetCol() || after.row() != action.targetRow();
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

    @Override
    public GameSession session() {
        return session;
    }

    @Override
    public String playerId() {
        return PLAYER_ID;
    }

    @Override
    public String botId() {
        return BOT_ID;
    }

    @Override
    public boolean resolutionPlaybackActive() {
        return resolutionPlaybackActive;
    }

    @Override
    public boolean isCastPhase() {
        return currentPhaseType == PhaseType.CAST;
    }

    @Override
    public List<GameSession.ResolvedActionView> currentPlaybackActions() {
        return currentPlaybackActions;
    }

    @Override
    public MapCellPosition playbackPositionBeforeOf(String actorId) {
        return playbackPositionBeforeByPlayer.get(actorId);
    }

    @Override
    public MapCellPosition playbackPositionAfterOf(String actorId) {
        return playbackPositionByPlayer.get(actorId);
    }

    @Override
    public FighterSpec playerSpec() {
        return playerSpec;
    }

    @Override
    public FighterSpec botSpec() {
        return botSpec;
    }

    @Override
    public MageArchetype currentArchetype() {
        return currentArchetype;
    }

    @Override
    public String selectedSkillName() {
        return String.valueOf(skillCombo.getSelectedItem());
    }

    @Override
    public Font defaultFont() {
        return DEFAULT_FONT;
    }

    @Override
    public boolean promotionEffectActive() {
        return promotionEffectActive;
    }

    @Override
    public long promotionEffectStartedAtMs() {
        return promotionEffectStartedAtMs;
    }

    @Override
    public void clearPromotionEffect() {
        promotionEffectActive = false;
    }

    @Override
    public String promotionEffectText() {
        return promotionEffectText;
    }

    @Override
    public Color playerSkinColor() {
        return playerSkinColor;
    }

    @Override
    public Color playerOutfitColor() {
        return playerOutfitColor;
    }

    @Override
    public Color opponentSkinColor() {
        return opponentSkinColor;
    }

    @Override
    public Color opponentOutfitColor() {
        return opponentOutfitColor;
    }

    @Override
    public boolean onlineMode() {
        return networkClient != null && networkClient.isConnected();
    }

    @Override
    public String selfHudTag() {
        return currentPlayerDisplayName();
    }

    @Override
    public String opponentHudTag() {
        return currentOpponentDisplayName();
    }

    private static Color parseHexColor(String hex, Color fallback) {
        if (hex == null || !hex.matches("^#[0-9a-fA-F]{6}$")) {
            return fallback;
        }
        return new Color(Integer.parseInt(hex.substring(1), 16));
    }

    private static String colorToHex(Color color) {
        if (color == null) {
            return null;
        }
        return String.format("#%06X", color.getRGB() & 0xFFFFFF);
    }
}