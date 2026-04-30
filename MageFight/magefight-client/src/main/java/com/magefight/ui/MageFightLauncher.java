package com.magefight.ui;

import com.magefight.content.model.MageArchetype;
import com.magefight.content.progress.ArchetypeUnlockService;
import com.magefight.content.progress.MageProgress;
import com.turngame.server.account.AccountStore;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Font;
import java.util.List;

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

    private String accountId;
    private MageProgress progress = MageProgress.starter();
    private GameNetworkClient networkClient;

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

        panel.add(info, BorderLayout.CENTER);
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
        if (id.isBlank() || password.isBlank()) {
            authMessage.setText("Account and password are required.");
            return;
        }

        accountStore.login(id, password).ifPresentOrElse(session -> {
            accountId = session.accountId();
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

        accountLabel.setText("Account: " + accountId + " / " + accountStore.nickname(accountId).orElse(accountId));
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
        LobbyPanel lobbyPanel = new LobbyPanel(networkClient, () -> startOnlineGame());
        cardPanel.add(lobbyPanel, "onlineMatch");
    }

    private void showOnlineMatchCard() {
        cards.show(cardPanel, "onlineMatch");
    }

    private void startOnlineGame() {
        MageArchetype selected = (MageArchetype) archetypeCombo.getSelectedItem();
        if (selected == null) {
            selected = progress.selectedArchetype();
        }
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "No archetype selected", "MageFight", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // 온라인 게임 시작
        MageFightFrame frame = new MageFightFrame(progress, accountId, p -> accountStore.saveProgress(accountId,
                AccountStore.AccountProgressSnapshot.fromFields(
                        p.wins(),
                        p.selectedArchetype() == null ? null : p.selectedArchetype().name(),
                        p.skillMasteryLevels(),
                        p.skillPracticePoints(),
                        p.inspirationPoints()
                )), networkClient);
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
}