package com.turngame.server.account;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class AccountStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path STORE_PATH = Path.of(System.getProperty("user.home"), ".turngame", "accounts.json");
    private static final AccountStore INSTANCE = new AccountStore();

    private final Map<String, AccountRecord> accounts = new HashMap<>();

    private AccountStore() {
        load();
    }

    public static AccountStore shared() {
        return INSTANCE;
    }

    public synchronized boolean accountExists(String accountId) {
        return accounts.containsKey(normalize(accountId));
    }

    public synchronized boolean createAccount(String accountId, String password, String nickname) {
        String normalizedId = normalize(accountId);
        if (normalizedId.isBlank() || password == null || password.isBlank() || accounts.containsKey(normalizedId)) {
            return false;
        }

        AccountRecord record = new AccountRecord();
        record.accountId = normalizedId;
        record.passwordHash = hashPassword(password);
        record.nickname = nickname == null || nickname.isBlank() ? normalizedId : nickname.trim();
        record.progress = AccountProgressSnapshot.empty();
        accounts.put(normalizedId, record);
        persist();
        return true;
    }

    public synchronized Optional<AccountSession> login(String accountId, String password) {
        AccountRecord record = accounts.get(normalize(accountId));
        if (record == null || !record.passwordHash.equals(hashPassword(password))) {
            return Optional.empty();
        }
        return Optional.of(record.toSession());
    }

    public synchronized void saveProgress(String accountId, AccountProgressSnapshot progress) {
        AccountRecord record = accounts.get(normalize(accountId));
        if (record == null) {
            return;
        }
        record.progress = progress.copy();
        persist();
    }

    public synchronized Optional<AccountProgressSnapshot> loadProgress(String accountId) {
        AccountRecord record = accounts.get(normalize(accountId));
        if (record == null) {
            return Optional.empty();
        }
        return Optional.of(record.progress.copy());
    }

    public synchronized Optional<String> nickname(String accountId) {
        AccountRecord record = accounts.get(normalize(accountId));
        return record == null ? Optional.empty() : Optional.of(record.nickname);
    }

    private void load() {
        if (!Files.exists(STORE_PATH)) {
            return;
        }

        try {
            String json = Files.readString(STORE_PATH, StandardCharsets.UTF_8);
            AccountStoreFile file = GSON.fromJson(json, AccountStoreFile.class);
            if (file != null && file.accounts != null) {
                accounts.clear();
                accounts.putAll(file.accounts);
            }
        } catch (IOException ignored) {
            // Start with an empty store if the file cannot be read.
        }
    }

    private void persist() {
        try {
            Files.createDirectories(STORE_PATH.getParent());
            AccountStoreFile file = new AccountStoreFile();
            file.accounts = accounts;
            Files.writeString(STORE_PATH, GSON.toJson(file), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Persistence is best-effort.
        }
    }

    private static String normalize(String accountId) {
        return accountId == null ? "" : accountId.trim().toLowerCase();
    }

    private static String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashed);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private static final class AccountStoreFile {
        Map<String, AccountRecord> accounts = new HashMap<>();
    }

    private static final class AccountRecord {
        String accountId;
        String passwordHash;
        String nickname;
        AccountProgressSnapshot progress;

        AccountSession toSession() {
            return new AccountSession(accountId, nickname, progress.copy());
        }
    }

    public record AccountSession(String accountId, String nickname, AccountProgressSnapshot progress) {
    }

    public static final class AccountProgressSnapshot {
        int wins;
        String selectedArchetype;
        Map<String, Integer> skillMasteryLevels = new HashMap<>();
        Map<String, Integer> skillPracticePoints = new HashMap<>();
        int inspirationPoints;

        static AccountProgressSnapshot empty() {
            return new AccountProgressSnapshot();
        }

        public AccountProgressSnapshot copy() {
            AccountProgressSnapshot snapshot = new AccountProgressSnapshot();
            snapshot.wins = wins;
            snapshot.selectedArchetype = selectedArchetype;
            snapshot.skillMasteryLevels = skillMasteryLevels == null ? new HashMap<>() : new HashMap<>(skillMasteryLevels);
            snapshot.skillPracticePoints = skillPracticePoints == null ? new HashMap<>() : new HashMap<>(skillPracticePoints);
            snapshot.inspirationPoints = inspirationPoints;
            return snapshot;
        }

        public int wins() {
            return wins;
        }

        public void wins(int wins) {
            this.wins = Math.max(0, wins);
        }

        public String selectedArchetype() {
            return selectedArchetype;
        }

        public void selectedArchetype(String selectedArchetype) {
            this.selectedArchetype = selectedArchetype;
        }

        public Map<String, Integer> skillMasteryLevels() {
            return skillMasteryLevels;
        }

        public void skillMasteryLevels(Map<String, Integer> skillMasteryLevels) {
            this.skillMasteryLevels = skillMasteryLevels == null ? new HashMap<>() : new HashMap<>(skillMasteryLevels);
        }

        public Map<String, Integer> skillPracticePoints() {
            return skillPracticePoints;
        }

        public void skillPracticePoints(Map<String, Integer> skillPracticePoints) {
            this.skillPracticePoints = skillPracticePoints == null ? new HashMap<>() : new HashMap<>(skillPracticePoints);
        }

        public int inspirationPoints() {
            return inspirationPoints;
        }

        public void inspirationPoints(int inspirationPoints) {
            this.inspirationPoints = Math.max(0, inspirationPoints);
        }

        public static AccountProgressSnapshot fromFields(int wins, String selectedArchetype, Map<String, Integer> skillMasteryLevels) {
            return fromFields(wins, selectedArchetype, skillMasteryLevels, new HashMap<>(), 0);
        }

        public static AccountProgressSnapshot fromFields(
                int wins,
                String selectedArchetype,
                Map<String, Integer> skillMasteryLevels,
                Map<String, Integer> skillPracticePoints,
                int inspirationPoints
        ) {
            AccountProgressSnapshot snapshot = new AccountProgressSnapshot();
            snapshot.wins = Math.max(0, wins);
            snapshot.selectedArchetype = selectedArchetype;
            snapshot.skillMasteryLevels = skillMasteryLevels == null ? new HashMap<>() : new HashMap<>(skillMasteryLevels);
            snapshot.skillPracticePoints = skillPracticePoints == null ? new HashMap<>() : new HashMap<>(skillPracticePoints);
            snapshot.inspirationPoints = Math.max(0, inspirationPoints);
            return snapshot;
        }
    }
}