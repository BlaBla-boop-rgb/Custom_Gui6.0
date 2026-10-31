package dev.customgui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Speichert Shop-Link, Inseln und Layout-Einstellungen in config/customgui.json. */
public final class CustomGuiConfig {

    public static class Island {
        public String name = "";
        public String command = "";

        public Island() {
        }

        public Island(String name, String command) {
            this.name = name;
            this.command = command;
        }
    }

    // ---- Auswahlmöglichkeiten für die Einstellungen
    public static final String[] THEME_NAMES = {"Cytooxien", "Klassisch"};
    public static final String[] ACCENT_NAMES = {"Cyan", "Grün", "Lila", "Gold", "Rosa", "Weiß"};
    public static final int[] ACCENTS = {0x3CC8F0, 0x55FF55, 0xB388FF, 0xFFC107, 0xFF5E7E, 0xFFFFFF};
    public static final String[] WIDTH_NAMES = {"Schmal", "Normal", "Breit"};
    public static final int[] PANEL_WIDTHS = {300, 330, 380};
    public static final String[] LAYOUT_NAMES = {"Liste", "Kacheln"};

    // ---- PvP: Hitboxen
    /** Index 0 = RGB-Regenbogen, danach feste Farben (HITBOX_COLORS[i - 1]). */
    public static final String[] HITBOX_COLOR_NAMES = {"RGB", "Rot", "Grün", "Blau", "Weiß", "Gold"};
    public static final int[] HITBOX_COLORS = {0xFF3B3B, 0x3BFF5E, 0x3B7BFF, 0xFFFFFF, 0xFFC107};
    public static final String[] SPEED_NAMES = {"Langsam", "Normal", "Schnell"};
    public static final float[] SPEEDS = {0.15f, 0.4f, 0.9f};
    public static final String[] THICKNESS_NAMES = {"Dünn", "Mittel", "Dick"};
    public static final double[] THICKNESSES = {0.012, 0.025, 0.045};
    public static final int[] RANGES = {32, 64, 128};

    /** Dieser Link wird beim Klick auf "Shop" im Standard-Browser geöffnet. */
    public String shopUrl = "https://www.cytooxien.de/de";
    public List<Island> islands = new ArrayList<>();

    /** 0 = Cytooxien-Stil, 1 = klassisches Design (Regenbogen, abgerundet). */
    public int theme = 0;
    /** Index in ACCENTS. */
    public int accent = 0;
    /** Index in PANEL_WIDTHS: 0 schmal, 1 normal, 2 breit. */
    public int panelWidth = 1;
    /** Inseln pro Seite (3 bis 5). */
    public int rowsPerPage = 4;
    /** 0 = Buttons untereinander, 1 = Kacheln nebeneinander. */
    public int mainLayout = 0;
    public boolean animations = true;
    public boolean particles = true;

    // ---- PvP-Einstellungen: Hitboxen um andere Spieler
    public boolean hitboxEnabled = false;
    /** 0 = RGB-Regenbogen, 1..5 = feste Farbe. */
    public int hitboxColor = 0;
    /** Index in SPEEDS (Regenbogen-Tempo). */
    public int hitboxSpeed = 1;
    /** Index in THICKNESSES. */
    public int hitboxThickness = 1;
    public boolean hitboxThroughWalls = false;
    public boolean hitboxFill = false;
    public boolean hitboxSelf = false;
    /** Index in RANGES (maximale Entfernung in Blöcken). */
    public int hitboxRange = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static CustomGuiConfig instance;

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("customgui.json");
    }

    public static CustomGuiConfig get() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        Path p = path();
        CustomGuiConfig loaded = null;
        try {
            if (Files.exists(p)) {
                loaded = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), CustomGuiConfig.class);
            }
        } catch (Exception e) {
            System.err.println("[CustomGui] Konfiguration konnte nicht gelesen werden: " + e);
        }
        if (loaded == null) {
            loaded = new CustomGuiConfig();
        }
        if (loaded.islands == null) {
            loaded.islands = new ArrayList<>();
        }
        if (loaded.shopUrl == null || loaded.shopUrl.isBlank()) {
            loaded.shopUrl = "https://www.cytooxien.de/de";
        }
        loaded.sanitize();
        instance = loaded;
        save();
    }

    /** Fängt ungültige Werte ab (z. B. von Hand falsch in der JSON-Datei eingetragen). */
    private void sanitize() {
        theme = clamp(theme, 0, THEME_NAMES.length - 1);
        accent = clamp(accent, 0, ACCENTS.length - 1);
        panelWidth = clamp(panelWidth, 0, PANEL_WIDTHS.length - 1);
        rowsPerPage = clamp(rowsPerPage, 3, 5);
        mainLayout = clamp(mainLayout, 0, LAYOUT_NAMES.length - 1);
        hitboxColor = clamp(hitboxColor, 0, HITBOX_COLOR_NAMES.length - 1);
        hitboxSpeed = clamp(hitboxSpeed, 0, SPEEDS.length - 1);
        hitboxThickness = clamp(hitboxThickness, 0, THICKNESSES.length - 1);
        hitboxRange = clamp(hitboxRange, 0, RANGES.length - 1);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** Setzt nur Design und Layout zurück (Inseln, Shop-Link und Taste bleiben erhalten). */
    public void resetLayout() {
        theme = 0;
        accent = 0;
        panelWidth = 1;
        rowsPerPage = 4;
        mainLayout = 0;
        animations = true;
        particles = true;
    }

    public static void save() {
        if (instance == null) return;
        try {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), GSON.toJson(instance), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[CustomGui] Konfiguration konnte nicht gespeichert werden: " + e);
        }
    }
}
