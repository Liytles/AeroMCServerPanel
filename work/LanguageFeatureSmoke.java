package com.aerogroup.mcpanel;

import javafx.scene.Group;

public final class LanguageFeatureSmoke {
    public static void main(String[] args) throws Exception {
        LanguageManager.apply(new Group(), "en");
        require("Server offline".equals(LanguageManager.text("Sunucu kapalı")), "exact English translation");
        require("6 players online".equals(LanguageManager.text("6 oyuncu online")), "dynamic English translation");
        require("Create backup".equals(LanguageManager.text("Yedek al")), "combo-box value translation");
        require("CRISIS MODE HISTORY".equals(LanguageManager.text("KRİZ MODU GEÇMİŞİ")), "crisis history translation");
        require("Also add Spark (recommended)".equals(LanguageManager.text("Spark'ı da ekle (önerilir)")), "Spark setup translation");

        LanguageManager.apply(new Group(), "tr");
        require("Sunucu kapalı".equals(LanguageManager.text("Server offline")), "reverse Turkish translation");

        PanelConfig config = new PanelConfig();
        config.setLiveMapEnabled(false);
        config.save();
        require(!PanelConfig.load().isLiveMapEnabled(), "disabled live-map preference");
        config.setLiveMapEnabled(true);
        config.setAutomaticCredentialVaultEnabled(true);
        config.setExarotonReadinessCheckEnabled(false);
        config.setAutomaticUpdateCheckEnabled(false);
        config.setFeatureTourCompleted(true);
        config.setUpdateChannel("beta");
        config.setCrisisModeEnabled(true);
        config.setCrisisTpsThreshold(15.5);
        config.setCrisisRamThreshold(92);
        config.setCrisisTriggerSeconds(14);
        config.setCrisisRecoverySeconds(45);
        config.setCrisisCooldownSeconds(120);
        config.save();
        require(PanelConfig.load().isLiveMapEnabled(), "enabled live-map preference");
        require(PanelConfig.load().isAutomaticCredentialVaultEnabled(), "automatic credential-vault preference");
        require(!PanelConfig.load().isExarotonReadinessCheckEnabled(), "disabled Exaroton readiness preference");
        require(!PanelConfig.load().isAutomaticUpdateCheckEnabled(), "disabled automatic update preference");
        require(PanelConfig.load().isFeatureTourCompleted(), "completed feature-tour preference");
        require("beta".equals(PanelConfig.load().getUpdateChannel()), "beta update channel preference");
        require(PanelConfig.load().isCrisisModeEnabled(), "enabled crisis-mode preference");
        require(PanelConfig.load().getCrisisTpsThreshold() == 15.5, "crisis TPS preference");
        require(PanelConfig.load().getCrisisTriggerSeconds() == 14, "crisis trigger preference");
        require(PanelConfig.load().getCrisisRecoverySeconds() == 45, "crisis recovery preference");
        require(PanelConfig.load().getCrisisCooldownSeconds() == 120, "crisis cooldown preference");

        System.out.println("language-and-live-map-ok");
    }

    private static void require(boolean condition, String feature) {
        if (!condition) throw new IllegalStateException("Smoke test failed: " + feature);
    }
}
