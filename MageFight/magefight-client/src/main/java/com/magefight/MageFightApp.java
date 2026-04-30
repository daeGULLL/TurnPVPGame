package com.magefight;

import com.magefight.ui.MageFightLauncher;

import javax.swing.SwingUtilities;

public class MageFightApp {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            MageFightLauncher launcher = new MageFightLauncher();
            launcher.setVisible(true);
        });
    }
}