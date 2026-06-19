package com.magefight.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.util.List;
import java.util.Random;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;

import com.magefight.content.model.MageArchetype;
import com.magefight.content.progress.ArchetypeUnlockService;
import com.magefight.content.progress.MageProgress;
import com.turngame.server.account.AccountStore;

public class MageFightLauncher extends JFrame {
    private static final Font DEFAULT_FONT = new Font("Malgun Gothic", Font.PLAIN, 14);

    private final AccountStore accountStore = AccountStore.shared();
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);

    private final JTextField accountField = new JTextField(16);
    private final JPasswordField passwordField = new JPasswordField(16);
    private final JTextField nicknameField = new JTextField(16);
    private final JLabel authMessage = new JLabel(" ");

    private final JLabel accountLabel = new JLabel();
    private final JLabel progressLabel = new JLabel();
    private final JComboBox<MageArchetype> archetypeCombo = new JComboBox<>();
    private final JLabel archetypeHint = new JLabel();
    private final CharacterPreviewPanel characterPreviewPanel = new CharacterPreviewPanel();
    private final JTextField characterNameField = new JTextField(14);
    private final JButton skinColorButton = new JButton("Skin Color");
    private final JButton outfitColorButton = new JButton("Outfit Color");
    private final JButton randomPresetButton = new JButton("Random Preset");
    private final JButton saveCharacterButton = new JButton("Save Character");
    private final Random random = new Random();

    private Color selectedSkinColor = new Color(0xF5E0C8);
    private Color selectedOutfitColor = new Color(0x80A8DC);

        private static final int CHARACTER_NAME_MIN_LEN = 2;
        private static final int CHARACTER_NAME_MAX_LEN = 16;
        private static final List<String> RESERVED_CHARACTER_NAMES = List.of(
            "admin", "administrator", "gm", "operator", "system", "bot", "moderator"
        );

    private String accountId;
    private MageProgress progress = MageProgress.starter();
    private GameNetworkClient networkClient;
    private LobbyPanel onlineLobbyPanel;

    public MageFightLauncher() {
        super("MageFight - Login");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(760, 520);
        setLocationRelativeTo(null);
        setFont(DEFAULT_FONT);

        buildLoginCard();
        buildLobbyCard();
        buildOnlineMatchCard();

        setContentPane(cardPanel);
        showLogin();
    }

    private void buildLoginCard() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));

        JLabel title = new JLabel("MageFight Account");
        title.setFont(DEFAULT_FONT.deriveFont(Font.BOLD, 24f));
        panel.add(title, BorderLayout.NORTH);

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(row("Account", accountField));
        form.add(row("Password", passwordField));
        form.add(row("Nickname", nicknameField));

        JPanel buttons = new JPanel();
        JButton loginBtn = new JButton("Login");
        JButton createBtn = new JButton("Create Account");
        loginBtn.addActionListener(e -> handleLogin());
        createBtn.addActionListener(e -> handleCreate());
        buttons.add(loginBtn);
        buttons.add(createBtn);

        authMessage.setForeground(new Color(176, 59, 59));

        panel.add(form, BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);

        JPanel wrapper = new JPanel(new BorderLayout(8, 8));
        wrapper.setBorder(BorderFactory.createEmptyBorder(24, 48, 24, 48));
        wrapper.add(panel, BorderLayout.CENTER);
        wrapper.add(authMessage, BorderLayout.SOUTH);

        cardPanel.add(wrapper, "login");
    }

    private void buildLobbyCard() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));

        JLabel title = new JLabel("My Character");
        title.setFont(DEFAULT_FONT.deriveFont(Font.BOLD, 24f));
        panel.add(title, BorderLayout.NORTH);

        JPanel info = new JPanel();
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        accountLabel.setFont(DEFAULT_FONT.deriveFont(Font.BOLD, 16f));
        progressLabel.setFont(DEFAULT_FONT.deriveFont(Font.PLAIN, 15f));
        archetypeHint.setFont(DEFAULT_FONT.deriveFont(Font.PLAIN, 14f));
        info.add(accountLabel);
        info.add(progressLabel);
        info.add(archetypeHint);

        JPanel characterPanel = new JPanel(new BorderLayout(8, 8));
        characterPanel.setBorder(BorderFactory.createTitledBorder("Character Preview"));
        characterPreviewPanel.setPreferredSize(new Dimension(280, 220));
        characterPanel.add(characterPreviewPanel, BorderLayout.CENTER);

        JPanel characterControls = new JPanel(new GridLayout(3, 2, 8, 8));
        characterControls.add(new JLabel("Name"));
        characterControls.add(characterNameField);
        characterControls.add(skinColorButton);
        characterControls.add(outfitColorButton);
        characterControls.add(randomPresetButton);
        characterControls.add(saveCharacterButton);
        characterPanel.add(characterControls, BorderLayout.SOUTH);

        skinColorButton.addActionListener(e -> chooseSkinColor());
        outfitColorButton.addActionListener(e -> chooseOutfitColor());
        randomPresetButton.addActionListener(e -> applyRandomColorPreset());
        saveCharacterButton.addActionListener(e -> saveCharacterProfile());

        JPanel selection = new JPanel();
        selection.setBorder(BorderFactory.createTitledBorder("Select Current Title"));
        JButton refreshBtn = new JButton("Refresh");
        JButton startBtn = new JButton("Start Battle (Local)");
        JButton onlineBtn = new JButton("Find Online Match");
        refreshBtn.addActionListener(e -> refreshLobby());
        startBtn.addActionListener(e -> startBattle());
        onlineBtn.addActionListener(e -> showOnlineMatchCard());
        selection.add(archetypeCombo);
        selection.add(refreshBtn);
        selection.add(startBtn);
        selection.add(onlineBtn);

        JPanel center = new JPanel(new BorderLayout(10, 10));
        center.add(info, BorderLayout.NORTH);
        center.add(characterPanel, BorderLayout.CENTER);
        panel.add(center, BorderLayout.CENTER);
        panel.add(selection, BorderLayout.SOUTH);

        cardPanel.add(panel, "lobby");
    }

    private JPanel row(String label, JTextField field) {
        JPanel row = new JPanel(new BorderLayout(8, 8));
        JLabel text = new JLabel(label);
        text.setPreferredSize(new java.awt.Dimension(110, 24));
        row.add(text, BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        return row;
    }

    private void handleCreate() {
        String id = accountField.getText().trim();
        String password = new String(passwordField.getPassword());
        String nickname = nicknameField.getText().trim();
        if (id.isBlank() || password.isBlank()) {
            authMessage.setText("Account and password are required.");
            return;
        }
        if (!accountStore.createAccount(id, password, nickname)) {
            authMessage.setText("Unable to create account. It may already exist.");
            return;
        }
        authMessage.setText("Account created. Log in to continue.");
    }

    private void handleLogin() {
        String id = accountField.getText().trim();
        String password = new String(passwordField.getPassword());
        String enteredNickname = nicknameField.getText().trim();
        if (id.isBlank() || password.isBlank()) {
            authMessage.setText("Account and password are required.");
            return;
        }

        accountStore.login(id, password).ifPresentOrElse(session -> {
            accountId = session.accountId();
            if (!enteredNickname.isBlank()) {
                accountStore.updateNickname(accountId, enteredNickname);
            }
            progress = loadProgressSnapshot(session.progress());
            authMessage.setText(" ");
            refreshLobby();
            showLobby();
        }, () -> authMessage.setText("Invalid account or password."));
    }

    private void refreshLobby() {
        if (accountId == null) {
            return;
        }

        accountLabel.setText("Login ID: " + accountId + " | Account Name: " + accountStore.nickname(accountId).orElse(accountId));
        progressLabel.setText("Lv " + progress.level() + " | Wins " + progress.wins() + " | Selected "
                + (progress.selectedArchetype() == null ? "none" : progress.selectedArchetype().displayName()));

        archetypeCombo.removeAllItems();
        List<MageArchetype> selectable = progress.selectedArchetype() == null
                ? ArchetypeUnlockService.autoUnlockEligible(progress)
                : List.of(progress.selectedArchetype());
        if (selectable.isEmpty()) {
            archetypeHint.setText("No archetype is currently available.");
        } else if (progress.selectedArchetype() == null) {
            archetypeHint.setText("Choose one title that is currently unlocked.");
        } else {
            archetypeHint.setText("Current title is fixed. Only this title will be used in battle.");
        }

        for (MageArchetype archetype : selectable) {
            archetypeCombo.addItem(archetype);
        }
        if (progress.selectedArchetype() != null) {
            archetypeCombo.setSelectedItem(progress.selectedArchetype());
        }

        loadCharacterProfile();
    }

    private void loadCharacterProfile() {
        if (accountId == null) {
            return;
        }
        AccountStore.CharacterProfile profile = accountStore.characterProfile(accountId)
                .orElse(new AccountStore.CharacterProfile(currentNickname(), "#F5E0C8", "#80A8DC"));
        characterNameField.setText(profile.displayName());
        selectedSkinColor = parseHexColor(profile.skinColorHex(), new Color(0xF5E0C8));
        selectedOutfitColor = parseHexColor(profile.outfitColorHex(), new Color(0x80A8DC));
        characterPreviewPanel.setDisplayName(profile.displayName());
        characterPreviewPanel.setSkinColor(selectedSkinColor);
        characterPreviewPanel.setOutfitColor(selectedOutfitColor);
    }

    private void chooseSkinColor() {
        Color chosen = JColorChooser.showDialog(this, "Choose Skin Color", selectedSkinColor);
        if (chosen == null) {
            return;
        }
        selectedSkinColor = chosen;
        characterPreviewPanel.setSkinColor(chosen);
    }

    private void chooseOutfitColor() {
        Color chosen = JColorChooser.showDialog(this, "Choose Outfit Color", selectedOutfitColor);
        if (chosen == null) {
            return;
        }
        selectedOutfitColor = chosen;
        characterPreviewPanel.setOutfitColor(chosen);
    }

    private void saveCharacterProfile() {
        if (accountId == null) {
            return;
        }
        String displayName = characterNameField.getText() == null ? "" : characterNameField.getText().trim();
        String validationError = validateCharacterName(displayName);
        if (validationError != null) {
            JOptionPane.showMessageDialog(this, validationError, "MageFight", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        boolean saved = accountStore.updateCharacterProfile(
                accountId,
                displayName,
                toHex(selectedSkinColor),
                toHex(selectedOutfitColor)
        );
        if (!saved) {
            JOptionPane.showMessageDialog(this, "Failed to save character profile.", "MageFight", JOptionPane.ERROR_MESSAGE);
            return;
        }
        characterPreviewPanel.setDisplayName(displayName);
        refreshLobby();
    }

    private void applyRandomColorPreset() {
        ColorPreset[] presets = {
                new ColorPreset(new Color(0xF5E0C8), new Color(0x80A8DC)),
                new ColorPreset(new Color(0xE7C7A7), new Color(0xD67648)),
                new ColorPreset(new Color(0xD1A27A), new Color(0x7884DB)),
                new ColorPreset(new Color(0x8F6240), new Color(0x5FB6A4)),
                new ColorPreset(new Color(0x6D4327), new Color(0xB66AA4))
        };
        ColorPreset chosen = presets[random.nextInt(presets.length)];
        selectedSkinColor = chosen.skinColor();
        selectedOutfitColor = chosen.outfitColor();
        characterPreviewPanel.setSkinColor(selectedSkinColor);
        characterPreviewPanel.setOutfitColor(selectedOutfitColor);
    }

    private String validateCharacterName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "Name cannot be empty.";
        }
        if (displayName.length() < CHARACTER_NAME_MIN_LEN || displayName.length() > CHARACTER_NAME_MAX_LEN) {
            return "Name must be 2-16 characters.";
        }
        if (!displayName.matches("^[0-9A-Za-z가-힣 _-]+$")) {
            return "Name may only contain Korean/English letters, numbers, space, _ and -.";
        }
        for (String reserved : RESERVED_CHARACTER_NAMES) {
            if (reserved.equalsIgnoreCase(displayName)) {
                return "That name is reserved.";
            }
        }
        return null;
    }

    private static String toHex(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static Color parseHexColor(String hex, Color fallback) {
        if (hex == null || !hex.matches("^#[0-9a-fA-F]{6}$")) {
            return fallback;
        }
        return new Color(Integer.parseInt(hex.substring(1), 16));
    }

    private void startBattle() {
        if (accountId == null) {
            return;
        }

        MageArchetype selected = (MageArchetype) archetypeCombo.getSelectedItem();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Select a title first.", "MageFight", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        if (!progress.hasSelectedArchetype()) {
            if (!ArchetypeUnlockService.trySelect(selected, progress)) {
                JOptionPane.showMessageDialog(this,
                        ArchetypeUnlockService.lockReason(selected, progress).orElse("Condition not met"),
                        "MageFight",
                        JOptionPane.INFORMATION_MESSAGE);
                refreshLobby();
                return;
            }
            accountStore.saveProgress(accountId, AccountStore.AccountProgressSnapshot.fromFields(
                    progress.wins(),
                    progress.selectedArchetype() == null ? null : progress.selectedArchetype().name(),
                    progress.skillMasteryLevels(),
                    progress.skillPracticePoints(),
                    progress.inspirationPoints()
            ));
        }

        MageFightFrame frame = new MageFightFrame(progress, accountId, p -> accountStore.saveProgress(accountId,
                AccountStore.AccountProgressSnapshot.fromFields(
                        p.wins(),
                        p.selectedArchetype() == null ? null : p.selectedArchetype().name(),
                    p.skillMasteryLevels(),
                    p.skillPracticePoints(),
                    p.inspirationPoints()
                )));
        frame.setVisible(true);
        setVisible(false);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                progress = pReload();
                refreshLobby();
                showLobby();
            }

            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                progress = pReload();
                refreshLobby();
                showLobby();
            }
        });
    }

    private MageProgress pReload() {
        return accountStore.loadProgress(accountId)
                .map(this::loadProgressSnapshot)
                .orElse(progress);
    }

    private MageProgress loadProgressSnapshot(AccountStore.AccountProgressSnapshot snapshot) {
        if (snapshot == null) {
            return MageProgress.starter();
        }

        MageProgress loaded = new MageProgress(
                snapshot.wins(),
                snapshot.selectedArchetype() == null ? null : MageArchetype.valueOf(snapshot.selectedArchetype()),
                snapshot.skillMasteryLevels(),
                snapshot.skillPracticePoints(),
                snapshot.inspirationPoints()
        );
        if (snapshot.selectedArchetype() == null && snapshot.skillMasteryLevels().isEmpty()) {
            return MageProgress.starter();
        }
        return loaded;
    }

    private void showLogin() {
        cards.show(cardPanel, "login");
    }

    private void showLobby() {
        cards.show(cardPanel, "lobby");
        setVisible(true);
        toFront();
        requestFocus();
    }

    private void buildOnlineMatchCard() {
        networkClient = new GameNetworkClient();
        onlineLobbyPanel = new LobbyPanel(
                networkClient,
                this::startOnlineGame,
                this::showLobby,
                this::currentNickname,
                this::currentArchetypeDisplay,
                this::currentServerCharacterType
        );
        cardPanel.add(onlineLobbyPanel, "onlineMatch");
    }

    private String currentArchetypeDisplay() {
        MageArchetype selected = (MageArchetype) archetypeCombo.getSelectedItem();
        if (selected == null) {
            selected = progress.selectedArchetype();
        }
        if (selected == null) {
            return "WARRIOR";
        }
        return selected.name();
    }

    private String currentNickname() {
        if (accountId == null || accountId.isBlank()) {
            return "Player";
        }
        return accountStore.nickname(accountId).orElse(accountId);
    }

    private String currentServerCharacterType() {
        MageArchetype selected = (MageArchetype) archetypeCombo.getSelectedItem();
        if (selected == null) {
            selected = progress.selectedArchetype();
        }
        if (selected == null) {
            return "WARRIOR";
        }
        return switch (selected) {
            case APPRENTICE, ELEMENTALIST, RUNE_SCHOLAR -> "MAGE";
        };
    }

    private void showOnlineMatchCard() {
        if (onlineLobbyPanel != null) {
            onlineLobbyPanel.refreshPlayerInfo();
        }
        cards.show(cardPanel, "onlineMatch");
    }

    private void startOnlineGame() {
        System.out.println("[MageFightLauncher] startOnlineGame invoked");
        // 온라인 게임 시작
        MageFightFrame frame = new MageFightFrame(progress, accountId, p -> accountStore.saveProgress(accountId,
                AccountStore.AccountProgressSnapshot.fromFields(
                        p.wins(),
                        p.selectedArchetype() == null ? null : p.selectedArchetype().name(),
                        p.skillMasteryLevels(),
                        p.skillPracticePoints(),
                        p.inspirationPoints()
                )), networkClient);
                System.out.println("[MageFightLauncher] frame created, setting visible");
        frame.setVisible(true);
        setVisible(false);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                networkClient.disconnect();
                progress = pReload();
                refreshLobby();
                showLobby();
            }

            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                networkClient.disconnect();
                progress = pReload();
                refreshLobby();
                showLobby();
            }
        });
    }

    private final class CharacterPreviewPanel extends JPanel {
        private String displayName = "Player";
        private Color skinColor = new Color(0xF5E0C8);
        private Color outfitColor = new Color(0x80A8DC);

        void setDisplayName(String displayName) {
            this.displayName = (displayName == null || displayName.isBlank()) ? "Player" : displayName;
            repaint();
        }

        void setSkinColor(Color skinColor) {
            this.skinColor = skinColor == null ? new Color(0xF5E0C8) : skinColor;
            repaint();
        }

        void setOutfitColor(Color outfitColor) {
            this.outfitColor = outfitColor == null ? new Color(0x80A8DC) : outfitColor;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            g2.setPaint(new GradientPaint(0, 0, new Color(220, 240, 255), 0, h, new Color(58, 67, 56)));
            g2.fillRoundRect(0, 0, w, h, 14, 14);

            int unitW = Math.max(70, w / 4);
            int unitH = Math.max(100, h / 2);
            int ux = (w - unitW) / 2;
            int uy = (h - unitH) / 2 - 8;

            g2.setColor(new Color(0, 0, 0, 100));
            g2.fillOval(ux + 6, uy + unitH - 8, unitW - 12, 14);

            g2.setColor(outfitColor);
            int[] robeX = {ux + unitW / 2, ux + unitW - 6, ux + 6};
            int[] robeY = {uy + 12, uy + unitH, uy + unitH};
            g2.fillPolygon(robeX, robeY, 3);

            int headSize = Math.max(16, (int) (unitW * 0.28));
            g2.setColor(skinColor);
            g2.fillOval(ux + (unitW - headSize) / 2, uy + 2, headSize, headSize);

            g2.setColor(new Color(255, 255, 255, 220));
            g2.setFont(DEFAULT_FONT.deriveFont(Font.BOLD, 11f));
            g2.drawString("YOU", ux + 8, uy - 2);

            g2.setColor(new Color(20, 24, 35, 180));
            g2.fillRoundRect(12, h - 42, Math.max(120, w - 24), 28, 12, 12);
            g2.setColor(Color.WHITE);
            g2.setFont(DEFAULT_FONT.deriveFont(Font.BOLD, 12f));
            g2.drawString(displayName, 22, h - 23);

            g2.setColor(new Color(255, 255, 255, 110));
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawRoundRect(2, 2, w - 5, h - 5, 14, 14);

            g2.dispose();
        }
    }

    private record ColorPreset(Color skinColor, Color outfitColor) {
    }
}