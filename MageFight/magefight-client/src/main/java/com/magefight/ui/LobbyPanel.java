package com.magefight.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * 게임 로비 - 서버 연결, 플레이어 설정, 매칭 대기
 */
public class LobbyPanel extends JPanel {
    /**
     * 매칭 요청 시 서버로 보낼 플레이어 프로필(스킬셋/외형/계정/에너지캡).
     * 매칭은 로비에서 시작되므로 이 정보가 없으면 서버가 starter 스킬·기본색으로 처리한다.
     */
    public record MatchProfile(
            String accountId,
            java.util.List<java.util.Map<String, Object>> skills,
            String skinColorHex,
            String outfitColorHex,
            Integer turnEnergyCap) {}

    private final GameNetworkClient networkClient;
    private final Runnable onGameStarted;
    private final Runnable onBackToLobby;
    private final java.util.function.Supplier<String> nicknameSupplier;
    private final java.util.function.Supplier<String> characterDisplaySupplier;
    private final java.util.function.Supplier<String> serverCharacterTypeSupplier;
    private final java.util.function.Supplier<MatchProfile> matchProfileSupplier;

    private static final String DEFAULT_SERVER_HOST =
        System.getProperty("magefight.server.host", System.getenv().getOrDefault("MAGEFIGHT_SERVER_HOST", "game.yeunsuh.online"));
    private static final int DEFAULT_SERVER_PORT = parseDefaultPort(
        System.getProperty("magefight.server.port", System.getenv().getOrDefault("MAGEFIGHT_SERVER_PORT", "443")));

    private JLabel nicknameValueLabel;
    private JLabel characterValueLabel;
    private JButton findGameButton;
    private JButton resumeGameButton;
    private JButton disconnectButton;
    private JButton backButton;
    private JLabel statusLabel;
    private volatile boolean gameStartTriggered;
    private Timer startSafetyTimer;

    public LobbyPanel(
            GameNetworkClient networkClient,
            Runnable onGameStarted,
            Runnable onBackToLobby,
            java.util.function.Supplier<String> nicknameSupplier,
            java.util.function.Supplier<String> characterDisplaySupplier,
            java.util.function.Supplier<String> serverCharacterTypeSupplier,
            java.util.function.Supplier<MatchProfile> matchProfileSupplier) {
        this.networkClient = networkClient;
        this.onGameStarted = onGameStarted;
        this.onBackToLobby = onBackToLobby;
        this.nicknameSupplier = nicknameSupplier;
        this.characterDisplaySupplier = characterDisplaySupplier;
        this.serverCharacterTypeSupplier = serverCharacterTypeSupplier;
        this.matchProfileSupplier = matchProfileSupplier;
        initUI();
    }

    private void initUI() {
        setLayout(new GridBagLayout());
        setBackground(new Color(40, 40, 40));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // 제목
        JLabel titleLabel = new JLabel("Game Lobby");
        titleLabel.setFont(new Font("Arial", Font.BOLD, 24));
        titleLabel.setForeground(Color.WHITE);
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        add(titleLabel, gbc);

        // 플레이어 설정
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 1;
        JLabel nicknameLabel = new JLabel("Nickname:");
        nicknameLabel.setForeground(Color.WHITE);
        add(nicknameLabel, gbc);

        gbc.gridx = 1;
        nicknameValueLabel = new JLabel();
        nicknameValueLabel.setForeground(new Color(100, 200, 255));
        add(nicknameValueLabel, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        JLabel characterLabel = new JLabel("Character:");
        characterLabel.setForeground(Color.WHITE);
        add(characterLabel, gbc);

        gbc.gridx = 1;
        characterValueLabel = new JLabel();
        characterValueLabel.setForeground(new Color(100, 200, 255));
        add(characterValueLabel, gbc);

        // 버튼
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 1;
        findGameButton = new JButton("Find Game");
        findGameButton.addActionListener(e -> onFindGame());
        add(findGameButton, gbc);

        gbc.gridx = 1;
        resumeGameButton = new JButton("Resume Game");
        resumeGameButton.setEnabled(false);
        resumeGameButton.setVisible(false);
        resumeGameButton.addActionListener(e -> onResumeGame());
        add(resumeGameButton, gbc);

        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 1;
        disconnectButton = new JButton("Disconnect");
        disconnectButton.setEnabled(false);
        disconnectButton.addActionListener(e -> onDisconnect());
        add(disconnectButton, gbc);

        gbc.gridx = 1;
        gbc.gridy = 4;
        gbc.gridwidth = 1;
        backButton = new JButton("Back to Main Lobby");
        backButton.addActionListener(e -> onBackToLobby());
        add(backButton, gbc);

        // 상태
        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.gridwidth = 2;
        statusLabel = new JLabel("Disconnected");
        statusLabel.setForeground(Color.YELLOW);
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        add(statusLabel, gbc);

        refreshFixedPlayerInfo();
    }

    private void onFindGame() {
        gameStartTriggered = false;
        cancelStartSafetyTimer();
        refreshFixedPlayerInfo();
        String nickname = nicknameValueLabel.getText().trim();
        if (nickname.isEmpty()) {
            statusLabel.setText("Nickname unavailable");
            statusLabel.setForeground(Color.RED);
            return;
        }

        String characterType = serverCharacterTypeSupplier == null
                ? "WARRIOR"
                : String.valueOf(serverCharacterTypeSupplier.get());
        if (characterType == null || characterType.isBlank() || "null".equalsIgnoreCase(characterType)) {
            characterType = "WARRIOR";
        }
        final String finalCharacterType = characterType;

        // 비동기 연결
        new Thread(() -> {
            try {
                statusLabel.setText("Connecting...");
                statusLabel.setForeground(Color.YELLOW);

                networkClient.connect(DEFAULT_SERVER_HOST, DEFAULT_SERVER_PORT);
                updateStatus("Connected", Color.GREEN);
                findGameButton.setEnabled(false);
                disconnectButton.setEnabled(true);

                // 매칭 상태 변화 모니터링
                networkClient.setOnMatchStateChanged(state -> {
                    System.out.println("[LobbyPanel] matchState=" + state);
                    switch (state) {
                        case SEARCHING:
                            updateStatus("Searching for opponent...", Color.YELLOW);
                            break;
                        case MATCHED:
                            updateStatus("Opponent found! Starting game...", new Color(0, 200, 0));
                            break;
                        case IDLE:
                            updateStatus("Ready to search", Color.GREEN);
                            break;
                        case DISCONNECTED:
                            updateStatus("Disconnected", Color.RED);
                            break;
                    }
                });

                // 게임 상태 및 메시지 처리
                networkClient.setOnMessageReceived(msg -> {
                    System.out.println("[LobbyPanel] message type=" + msg.type() + ", requestId=" + msg.requestId());
                    if ("MATCHED".equalsIgnoreCase(msg.type())) {
                        networkClient.clearResumableMatch();
                        setResumeAvailable(false);
                        updateStatus("Match confirmed, initializing...", Color.GREEN);
                        String playerId = String.valueOf(msg.payload().get("playerId"));
                        String matchId = String.valueOf(msg.payload().get("matchId"));
                        networkClient.setMyPlayerId(playerId);
                        networkClient.setMatchId(matchId);
                        networkClient.requestImmediateEventSync();
                        scheduleStartSafetyTimer();
                    } else if ("MATCH_STARTED".equalsIgnoreCase(msg.type())) {
                        cancelStartSafetyTimer();
                        String playerId = String.valueOf(msg.payload().get("playerId"));
                        String matchId = String.valueOf(msg.payload().get("matchId"));
                        networkClient.setMyPlayerId(playerId);
                        networkClient.setMatchId(matchId);
                        updateStatus("Match started. Opening battle view...", Color.GREEN);
                        triggerGameStartOnce();
                    } else if ("STATE_UPDATED".equalsIgnoreCase(msg.type())) {
                        cancelStartSafetyTimer();
                        Object matchIdObj = msg.payload().get("matchId");
                        if (matchIdObj != null) {
                            String matchId = String.valueOf(matchIdObj);
                            if (!matchId.isBlank() && !"null".equalsIgnoreCase(matchId)) {
                                networkClient.setMatchId(matchId);
                            }
                        }
                        updateStatus("State synced. Opening battle view...", Color.GREEN);
                        triggerGameStartOnce();
                    }
                });

                networkClient.setOnErrorReceived(error -> {
                    updateStatus("Error: " + error, Color.RED);
                });

                networkClient.setOnDisconnected(v -> {
                    SwingUtilities.invokeLater(() -> {
                        updateStatus("Disconnected", Color.RED);
                        findGameButton.setEnabled(true);
                        setResumeAvailable(false);
                        disconnectButton.setEnabled(false);
                    });
                });

                networkClient.setOnLobbyNotice(notice -> {
                    SwingUtilities.invokeLater(() -> updateStatus(notice, Color.ORANGE));
                });

                // 게임 찾기 요청 (스킬셋/외형/계정/에너지캡을 함께 전송한다.
                // 이게 없으면 서버가 starter 스킬 1개와 기본색으로 매치를 만든다.)
                MatchProfile profile = matchProfileSupplier == null ? null : matchProfileSupplier.get();
                System.out.println("[LobbyPanel] findGame nickname=" + nickname + ", characterType=" + finalCharacterType
                        + ", skills=" + (profile == null || profile.skills() == null ? 0 : profile.skills().size())
                        + ", skin=" + (profile == null ? null : profile.skinColorHex()));
                if (profile != null) {
                    networkClient.setAppearance(profile.skinColorHex(), profile.outfitColorHex());
                    networkClient.findGame(
                            nickname,
                            finalCharacterType,
                            profile.accountId(),
                            characterDisplaySupplier == null ? nickname : characterDisplaySupplier.get(),
                            profile.skills(),
                            profile.turnEnergyCap());
                } else {
                    networkClient.findGame(nickname, finalCharacterType);
                }
                if (networkClient.hasResumableMatch()) {
                    long deadline = networkClient.getResumableReconnectDeadlineEpochMs();
                    long remainSec = Math.max(0L, (deadline - System.currentTimeMillis()) / 1000L);
                    updateStatus("Ongoing match found. Resume within " + remainSec + "s", Color.ORANGE);
                    setResumeAvailable(true);
                    findGameButton.setEnabled(false);
                } else {
                    setResumeAvailable(false);
                }

            } catch (Exception e) {
                updateStatus("Connection failed: " + e.getMessage(), Color.RED);
            }
        }).start();
    }

    private void onResumeGame() {
        new Thread(() -> {
            try {
                updateStatus("Resuming match...", Color.YELLOW);
                networkClient.resumeMatch();
                setResumeAvailable(false);
                updateStatus("Match resumed. Opening battle view...", Color.GREEN);
                triggerGameStartOnce();
            } catch (RuntimeException ex) {
                updateStatus("Resume failed: " + ex.getMessage(), Color.RED);
                setResumeAvailable(networkClient.hasResumableMatch());
            }
        }).start();
    }

    private void onDisconnect() {
        cancelStartSafetyTimer();
        if (networkClient.getMatchState() == GameNetworkClient.MatchState.SEARCHING) {
            networkClient.cancelMatchmaking();
        }
        networkClient.disconnect();
        gameStartTriggered = false;
        updateStatus("Disconnected", Color.RED);
        findGameButton.setEnabled(true);
        setResumeAvailable(false);
        disconnectButton.setEnabled(false);
    }

    private void onBackToLobby() {
        cancelStartSafetyTimer();
        if (networkClient.getMatchState() == GameNetworkClient.MatchState.SEARCHING) {
            networkClient.cancelMatchmaking();
        }
        if (networkClient.isConnected()) {
            networkClient.disconnect();
        }
        gameStartTriggered = false;
        updateStatus("Disconnected", Color.RED);
        findGameButton.setEnabled(true);
        setResumeAvailable(false);
        disconnectButton.setEnabled(false);
        if (onBackToLobby != null) {
            SwingUtilities.invokeLater(onBackToLobby);
        }
    }

    private void triggerGameStartOnce() {
        cancelStartSafetyTimer();
        if (gameStartTriggered) {
            return;
        }
        gameStartTriggered = true;
        updateStatus("Game started!", Color.GREEN);
        System.out.println("[LobbyPanel] triggerGameStartOnce start");
        if (onGameStarted == null) {
            System.err.println("[LobbyPanel] onGameStarted callback is null");
            return;
        }

        Runnable startTask = () -> {
            try {
                onGameStarted.run();
                System.out.println("[LobbyPanel] onGameStarted completed");
            } catch (RuntimeException ex) {
                gameStartTriggered = false;
                updateStatus("Failed to open game: " + ex.getMessage(), Color.RED);
                findGameButton.setEnabled(true);
                disconnectButton.setEnabled(false);
                System.err.println("[LobbyPanel] onGameStarted failed: " + ex.getMessage());
            }
        };


        SwingUtilities.invokeLater(startTask);
    }

    private void updateStatus(String text, Color color) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(text);
            statusLabel.setForeground(color);
        });
    }

    private void refreshFixedPlayerInfo() {
        String nickname = nicknameSupplier == null ? "Player" : String.valueOf(nicknameSupplier.get());
        String characterType = characterDisplaySupplier == null ? "WARRIOR" : String.valueOf(characterDisplaySupplier.get());
        if (nickname == null || nickname.isBlank() || "null".equalsIgnoreCase(nickname)) {
            nickname = "Player";
        }
        if (characterType == null || characterType.isBlank() || "null".equalsIgnoreCase(characterType)) {
            characterType = "WARRIOR";
        }
        if (nicknameValueLabel != null) {
            nicknameValueLabel.setText(nickname);
        }
        if (characterValueLabel != null) {
            characterValueLabel.setText(characterType);
        }
    }

    void refreshPlayerInfo() {
        refreshFixedPlayerInfo();
    }

    private void scheduleStartSafetyTimer() {
        cancelStartSafetyTimer();
        startSafetyTimer = new Timer(1800, e -> {
            if (gameStartTriggered) {
                return;
            }
            System.out.println("[LobbyPanel] start safety timer fired; opening battle view");
            triggerGameStartOnce();
        });
        startSafetyTimer.setRepeats(false);
        startSafetyTimer.start();
    }

    private void cancelStartSafetyTimer() {
        if (startSafetyTimer != null) {
            startSafetyTimer.stop();
            startSafetyTimer = null;
        }
    }

    private void setResumeAvailable(boolean available) {
        SwingUtilities.invokeLater(() -> {
            if (resumeGameButton != null) {
                resumeGameButton.setVisible(available);
                resumeGameButton.setEnabled(available);
            }
        });
    }

    private static int parseDefaultPort(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > 65535) {
                return 443;
            }
            return parsed;
        } catch (NumberFormatException ex) {
            return 443;
        }
    }
}
