package com.magefight.ui;

import javax.swing.*;
import java.awt.*;

/**
 * 게임 로비 - 서버 연결, 플레이어 설정, 매칭 대기
 */
public class LobbyPanel extends JPanel {
    private final GameNetworkClient networkClient;
    private final Runnable onGameStarted;

    private static final String SERVER_HOST = "game.yeunsuh.online";
    private static final int SERVER_PORT = 9090;

    private JTextField nicknameField;
    private JComboBox<String> characterCombo;
    private JButton findGameButton;
    private JButton disconnectButton;
    private JLabel statusLabel;

    public LobbyPanel(GameNetworkClient networkClient, Runnable onGameStarted) {
        this.networkClient = networkClient;
        this.onGameStarted = onGameStarted;
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

        // 서버 정보 (고정)
        gbc.gridy = 1;
        gbc.gridwidth = 2;
        JLabel serverInfoLabel = new JLabel("Server: " + SERVER_HOST + ":" + SERVER_PORT);
        serverInfoLabel.setForeground(new Color(100, 200, 255));
        serverInfoLabel.setFont(new Font("Arial", Font.PLAIN, 12));
        add(serverInfoLabel, gbc);

        // 플레이어 설정
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 1;
        JLabel nicknameLabel = new JLabel("Nickname:");
        nicknameLabel.setForeground(Color.WHITE);
        add(nicknameLabel, gbc);

        gbc.gridx = 1;
        nicknameField = new JTextField("Player", 15);
        add(nicknameField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 3;
        JLabel characterLabel = new JLabel("Character:");
        characterLabel.setForeground(Color.WHITE);
        add(characterLabel, gbc);

        gbc.gridx = 1;
        characterCombo = new JComboBox<>(new String[]{"APPRENTICE", "WARRIOR", "MAGE"});
        add(characterCombo, gbc);

        // 버튼
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 1;
        findGameButton = new JButton("Find Game");
        findGameButton.addActionListener(e -> onFindGame());
        add(findGameButton, gbc);

        gbc.gridx = 1;
        disconnectButton = new JButton("Disconnect");
        disconnectButton.setEnabled(false);
        disconnectButton.addActionListener(e -> onDisconnect());
        add(disconnectButton, gbc);

        // 상태
        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.gridwidth = 2;
        statusLabel = new JLabel("Disconnected");
        statusLabel.setForeground(Color.YELLOW);
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        add(statusLabel, gbc);
    }

    private void onFindGame() {
        String nickname = nicknameField.getText().trim();
        if (nickname.isEmpty()) {
            statusLabel.setText("Enter nickname");
            statusLabel.setForeground(Color.RED);
            return;
        }

        String characterType = (String) characterCombo.getSelectedItem();

        // 비동기 연결 (고정된 서버로)
        new Thread(() -> {
            try {
                statusLabel.setText("Connecting...");
                statusLabel.setForeground(Color.YELLOW);

                networkClient.connect(SERVER_HOST, SERVER_PORT);
                updateStatus("Connected", Color.GREEN);
                findGameButton.setEnabled(false);
                disconnectButton.setEnabled(true);

                // 게임 시작 대기
                networkClient.setOnMessageReceived(msg -> {
                    if ("MATCH_STARTED".equalsIgnoreCase(msg.type())) {
                        String playerId = String.valueOf(msg.payload().get("playerId"));
                        String matchId = String.valueOf(msg.payload().get("matchId"));
                        networkClient.setMyPlayerId(playerId);
                        networkClient.setMatchId(matchId);
                        
                        SwingUtilities.invokeLater(() -> {
                            updateStatus("Game started!", Color.GREEN);
                            if (onGameStarted != null) {
                                onGameStarted.run();
                            }
                        });
                    }
                });

                networkClient.setOnErrorReceived(error -> {
                    updateStatus("Error: " + error, Color.RED);
                });

                networkClient.setOnDisconnected(v -> {
                    SwingUtilities.invokeLater(() -> {
                        updateStatus("Disconnected", Color.RED);
                        findGameButton.setEnabled(true);
                        disconnectButton.setEnabled(false);
                    });
                });

                // 매칭 요청
                networkClient.joinGame(nickname, characterType);
                updateStatus("Waiting for match...", Color.YELLOW);

            } catch (Exception e) {
                updateStatus("Connection failed: " + e.getMessage(), Color.RED);
            }
        }).start();
    }

    private void onDisconnect() {
        networkClient.disconnect();
        updateStatus("Disconnected", Color.RED);
        findGameButton.setEnabled(true);
        disconnectButton.setEnabled(false);
    }

    private void updateStatus(String text, Color color) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(text);
            statusLabel.setForeground(color);
        });
    }
}
