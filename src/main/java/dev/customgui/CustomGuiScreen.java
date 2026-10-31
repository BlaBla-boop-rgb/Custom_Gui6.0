package dev.customgui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CustomGuiScreen extends Screen {

    private static final int TAB_COUNT = 4;
    private static final String[] TAB_NAMES = {"Hauptmen\u00FC", "Inseln", "PvP", "Einstellungen"};

    private enum Tab { HOME, ISLANDS, PVP, SETTINGS }

    /** Ein animierter Button. hover läuft weich zwischen 0 und 1. */
    private static final class Btn {
        final String label;
        final String sub;     // zweite Zeile (links- oder kachel-ausgerichtet)
        final String tip;     // Hinweistext unten im Panel
        final int accent;     // 0xRRGGBB, Farbe im klassischen Design / für feste Farben
        final Runnable action;
        boolean left = false;
        boolean themed = false;  // true = nimmt im Cytooxien-Design die gewählte Akzentfarbe
        boolean active = false;  // true = dauerhaft hervorgehoben (z. B. beim Tastenwechsel)
        int x, y, w, h;
        float hover = 0f;
        boolean wasHovered = false;

        Btn(String label, String sub, String tip, int accent, Runnable action) {
            this.label = label;
            this.sub = sub;
            this.tip = tip;
            this.accent = accent;
            this.action = action;
        }

        Btn at(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            return this;
        }

        Btn themed() {
            this.themed = true;
            return this;
        }

        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final List<Btn> buttons = new ArrayList<>();
    private final float[] tabHover = new float[TAB_COUNT];
    private final boolean[] tabWasHovered = new boolean[TAB_COUNT];

    private Tab tab = Tab.HOME;
    private boolean formMode = false;
    private boolean listening = false;   // wartet auf neue Taste für "Menü öffnen"
    private String formName = "";
    private String formCmd = "";
    private String formError = "";
    private int page = 0;

    private EditBox nameBox;
    private EditBox cmdBox;

    // Layout (hängt von den Einstellungen ab)
    private int pw, ph, rowW, rowsPerPage, rowPitch;
    private int px, py;

    private final int[] tabX = new int[TAB_COUNT];
    private final int[] tabW = new int[TAB_COUNT];
    private float indicatorX = -1f;
    private float indicatorW = -1f;
    private long openedAt = System.nanoTime();
    private long lastFrame = System.nanoTime();

    public CustomGuiScreen() {
        super(Component.literal("Custom GUI"));
    }

    // ------------------------------------------------------------------ Aufbau

    private void computeLayout() {
        CustomGuiConfig c = CustomGuiConfig.get();
        pw = CustomGuiConfig.PANEL_WIDTHS[c.panelWidth];
        rowsPerPage = c.rowsPerPage;
        rowPitch = rowsPerPage >= 5 ? 32 : 36;
        rowW = pw - 36;
        ph = Math.max(262, 68 + rowsPerPage * rowPitch + 48);
        px = (this.width - pw) / 2;
        py = Math.max(2, (this.height - ph) / 2);
        computeTabs();
    }

    /** Tab-Breiten richten sich nach dem Text, damit auch 4 Tabs ins Panel passen. */
    private void computeTabs() {
        int gap = 4;
        int total = gap * (TAB_COUNT - 1);
        for (int i = 0; i < TAB_COUNT; i++) {
            tabW[i] = this.font.width(Component.literal(TAB_NAMES[i]).withStyle(ChatFormatting.BOLD)) + 16;
            total += tabW[i];
        }
        int x = px + (pw - total) / 2;
        for (int i = 0; i < TAB_COUNT; i++) {
            tabX[i] = x;
            x += tabW[i] + gap;
        }
    }

    @Override
    protected void init() {
        computeLayout();

        nameBox = new EditBox(this.font, px + 30, py + 82, pw - 60, 20, Component.literal("Name"));
        nameBox.setMaxLength(24);
        nameBox.setHint(Component.literal("z. B. Markshop").withStyle(ChatFormatting.DARK_GRAY));
        nameBox.setValue(formName);
        nameBox.setResponder(s -> formName = s);
        this.addWidget(nameBox);

        cmdBox = new EditBox(this.font, px + 30, py + 128, pw - 60, 20, Component.literal("Befehl"));
        cmdBox.setMaxLength(100);
        cmdBox.setHint(Component.literal("z. B. /warp Markshop").withStyle(ChatFormatting.DARK_GRAY));
        cmdBox.setValue(formCmd);
        cmdBox.setResponder(s -> formCmd = s);
        this.addWidget(cmdBox);

        updateBoxes();
        rebuild();
        openedAt = System.nanoTime();
        lastFrame = openedAt;
    }

    /** Wird nach einer Layout-Änderung aufgerufen: alles neu berechnen, ohne das Fenster neu zu öffnen. */
    private void applyLayout() {
        computeLayout();
        nameBox.setX(px + 30);
        nameBox.setY(py + 82);
        nameBox.setWidth(pw - 60);
        cmdBox.setX(px + 30);
        cmdBox.setY(py + 128);
        cmdBox.setWidth(pw - 60);
        indicatorX = -1f;
        indicatorW = -1f;
        rebuild();
    }

    private void updateBoxes() {
        boolean show = formMode && tab == Tab.ISLANDS;
        nameBox.setVisible(show);
        nameBox.active = show;
        cmdBox.setVisible(show);
        cmdBox.active = show;
        if (show) {
            this.setFocused(nameBox);
            nameBox.setFocused(true);
        }
    }

    /** Baut die Buttons für den aktuellen Tab neu auf. */
    private void rebuild() {
        buttons.clear();
        CustomGuiConfig cfg = CustomGuiConfig.get();

        if (tab == Tab.HOME) {
            if (cfg.mainLayout == 1) {
                // Kacheln
                int gap = 8;
                int bx = px + 20;
                int bw = (pw - 40 - gap) / 2;
                buttons.add(new Btn("Spawn", "/spawn", "Führt /spawn aus", 0x55FFFF,
                        () -> runCommand("spawn")).themed().at(bx, py + 72, bw, 60));
                buttons.add(new Btn("Shop", "Im Browser", "Öffnet den Shop im Browser", 0x55FF55,
                        this::openShop).themed().at(bx + bw + gap, py + 72, bw, 60));
                buttons.add(new Btn("Schließen", "", "Schließt dieses Menü", 0xFF5555,
                        this::onClose).at(bx, py + 72 + 60 + gap, pw - 40, 30));
            } else {
                int bw = Math.min(220, pw - 80);
                int bx = px + (pw - bw) / 2;
                buttons.add(new Btn("Spawn", "", "Führt /spawn aus", 0x55FFFF,
                        () -> runCommand("spawn")).themed().at(bx, py + 74, bw, 30));
                buttons.add(new Btn("Shop", "", "Öffnet den Shop im Browser", 0x55FF55,
                        this::openShop).themed().at(bx, py + 114, bw, 30));
                buttons.add(new Btn("Schließen", "", "Schließt dieses Menü", 0xFF5555,
                        this::onClose).at(bx, py + 154, bw, 30));
            }
            return;
        }

        if (tab == Tab.PVP) {
            buildPvp(cfg);
            return;
        }

        if (tab == Tab.SETTINGS) {
            buildSettings(cfg);
            return;
        }

        // ---- Inseln-Tab
        if (formMode) {
            int by = py + 172;
            buttons.add(new Btn("Speichern", "", "Insel speichern", 0x55FF55,
                    this::saveIsland).at(px + pw / 2 - 120, by, 116, 28));
            buttons.add(new Btn("Abbrechen", "", "Zurück zur Liste", 0xFF5555, () -> {
                formMode = false;
                formError = "";
                updateBoxes();
                rebuild();
            }).at(px + pw / 2 + 4, by, 116, 28));
            return;
        }

        List<CustomGuiConfig.Island> list = cfg.islands;
        int pages = pageCount();
        page = Math.max(0, Math.min(page, pages - 1));
        int rx = px + (pw - rowW) / 2;
        int rowH = rowPitch - 6;

        for (int i = 0; i < rowsPerPage; i++) {
            int idx = page * rowsPerPage + i;
            if (idx >= list.size()) break;
            CustomGuiConfig.Island is = list.get(idx);
            int ry = py + 68 + i * rowPitch;

            String cmd = is.command;
            Btn row = new Btn(is.name, "/" + cmd, "Klicke, um dich zu " + is.name + " zu bringen", 0x55FFFF,
                    () -> runCommand(cmd)).themed().at(rx, ry, rowW - 30, rowH);
            row.left = true;
            buttons.add(row);

            buttons.add(new Btn("x", "", "Insel aus der Liste entfernen", 0xFF5555, () -> {
                list.remove(idx);
                CustomGuiConfig.save();
                rebuild();
            }).at(rx + rowW - 24, ry, 24, rowH));
        }

        int by = py + 68 + rowsPerPage * rowPitch + 4;
        if (pages > 1) {
            buttons.add(new Btn("<", "", "Vorherige Seite", 0xFFFF55, () -> {
                page = (page - 1 + pageCount()) % pageCount();
                rebuild();
            }).themed().at(rx, by, 24, 26));
            buttons.add(new Btn("+ Insel hinzufügen (" + (page + 1) + "/" + pages + ")", "",
                    "Neue Insel mit Befehl speichern", 0xFF55FF,
                    this::openForm).themed().at(rx + 30, by, rowW - 60, 26));
            buttons.add(new Btn(">", "", "Nächste Seite", 0xFFFF55, () -> {
                page = (page + 1) % pageCount();
                rebuild();
            }).themed().at(rx + rowW - 24, by, 24, 26));
        } else {
            buttons.add(new Btn("+ Insel hinzufügen", "", "Neue Insel mit Befehl speichern", 0xFF55FF,
                    this::openForm).themed().at(rx, by, rowW, 26));
        }
    }

    /** Einstellungs-Tab: Taste ändern und Layout/Design anpassen. */
    private void buildSettings(CustomGuiConfig cfg) {
        int kx = px + 20;
        int kw = pw - 40 - 70;

        Btn key = new Btn(listening ? "Drücke eine Taste ..." : "Menü-Taste: " + keyName(), "",
                "Klicken, dann neue Taste drücken (Esc = abbrechen)", 0xFFFF55, () -> {
            listening = !listening;
            rebuild();
        }).themed().at(kx, py + 78, kw, 22);
        key.active = listening;
        buttons.add(key);

        buttons.add(new Btn("Standard", "", "Setzt die Menü-Taste auf H zurück", 0xFFFF55,
                this::resetKey).themed().at(kx + kw + 6, py + 78, 64, 22));

        List<Btn> grid = new ArrayList<>();
        grid.add(new Btn("Design: " + CustomGuiConfig.THEME_NAMES[cfg.theme], "",
                "Wechselt zwischen Cytooxien-Stil und dem klassischen Design", 0x55FFFF, () -> {
            cfg.theme = (cfg.theme + 1) % CustomGuiConfig.THEME_NAMES.length;
            changed();
        }));
        grid.add(new Btn("Akzent: " + CustomGuiConfig.ACCENT_NAMES[cfg.accent], "",
                "Akzentfarbe des Cytooxien-Designs", 0x55FFFF, () -> {
            cfg.accent = (cfg.accent + 1) % CustomGuiConfig.ACCENTS.length;
            changed();
        }));
        grid.add(new Btn("Breite: " + CustomGuiConfig.WIDTH_NAMES[cfg.panelWidth], "",
                "Wie breit das Menü ist", 0x55FFFF, () -> {
            cfg.panelWidth = (cfg.panelWidth + 1) % CustomGuiConfig.PANEL_WIDTHS.length;
            changed();
            applyLayout();
        }));
        grid.add(new Btn("Inseln/Seite: " + cfg.rowsPerPage, "",
                "Wie viele Inseln pro Seite angezeigt werden (3 bis 5)", 0x55FFFF, () -> {
            cfg.rowsPerPage = cfg.rowsPerPage >= 5 ? 3 : cfg.rowsPerPage + 1;
            page = 0;
            changed();
            applyLayout();
        }));
        grid.add(new Btn("Menü: " + CustomGuiConfig.LAYOUT_NAMES[cfg.mainLayout], "",
                "Hauptmenü als Liste oder als Kacheln anzeigen", 0x55FFFF, () -> {
            cfg.mainLayout = (cfg.mainLayout + 1) % CustomGuiConfig.LAYOUT_NAMES.length;
            changed();
        }));
        grid.add(new Btn("Animationen: " + (cfg.animations ? "An" : "Aus"), "",
                "Gleit-, Hover- und Glanz-Effekte ein- oder ausschalten", 0x55FFFF, () -> {
            cfg.animations = !cfg.animations;
            changed();
        }));
        grid.add(new Btn("Sterne: " + (cfg.particles ? "An" : "Aus"), "",
                "Schwebende Partikel im Hintergrund", 0x55FFFF, () -> {
            cfg.particles = !cfg.particles;
            changed();
        }));
        grid.add(new Btn("Layout-Reset", "",
                "Design und Layout auf Standard zurücksetzen (Inseln bleiben)", 0xFF5555, () -> {
            cfg.resetLayout();
            page = 0;
            changed();
            applyLayout();
        }));

        addGrid(grid, py + 120, true);
    }

    /** Ordnet Einstellungs-Buttons in zwei Spalten an. */
    private void addGrid(List<Btn> grid, int gy, boolean lastIsSpecial) {
        int kx = px + 20;
        int gap = 6;
        int gw = (pw - 40 - gap) / 2;
        int pitch = 26;
        int bh = 22;
        for (int i = 0; i < grid.size(); i++) {
            int col = i % 2;
            int row = i / 2;
            Btn b = grid.get(i);
            if (!(lastIsSpecial && i == grid.size() - 1)) b.themed();
            buttons.add(b.at(kx + col * (gw + gap), gy + row * pitch, gw, bh));
        }
    }

    /** PvP-Reiter: Hitboxen um andere Spieler. */
    private void buildPvp(CustomGuiConfig cfg) {
        List<Btn> grid = new ArrayList<>();
        grid.add(new Btn("Hitboxen: " + (cfg.hitboxEnabled ? "An" : "Aus"), "",
                "Zeigt Hitboxen um andere Spieler an", 0x55FF55, () -> {
            cfg.hitboxEnabled = !cfg.hitboxEnabled;
            changed();
        }));
        grid.add(new Btn("Farbe: " + CustomGuiConfig.HITBOX_COLOR_NAMES[cfg.hitboxColor], "",
                "RGB = wandernder Regenbogen, sonst eine feste Farbe", 0x55FFFF, () -> {
            cfg.hitboxColor = (cfg.hitboxColor + 1) % CustomGuiConfig.HITBOX_COLOR_NAMES.length;
            changed();
        }));
        grid.add(new Btn("Tempo: " + CustomGuiConfig.SPEED_NAMES[cfg.hitboxSpeed], "",
                "Wie schnell der Regenbogen wandert", 0x55FFFF, () -> {
            cfg.hitboxSpeed = (cfg.hitboxSpeed + 1) % CustomGuiConfig.SPEEDS.length;
            changed();
        }));
        grid.add(new Btn("Linien: " + CustomGuiConfig.THICKNESS_NAMES[cfg.hitboxThickness], "",
                "Dicke der Hitbox-Linien", 0x55FFFF, () -> {
            cfg.hitboxThickness = (cfg.hitboxThickness + 1) % CustomGuiConfig.THICKNESSES.length;
            changed();
        }));
        grid.add(new Btn("Durch W\u00E4nde: " + (cfg.hitboxThroughWalls ? "An" : "Aus"), "",
                "Hitboxen auch hinter Bl\u00F6cken sichtbar machen", 0x55FFFF, () -> {
            cfg.hitboxThroughWalls = !cfg.hitboxThroughWalls;
            changed();
        }));
        grid.add(new Btn("F\u00FCllung: " + (cfg.hitboxFill ? "An" : "Aus"), "",
                "Zus\u00E4tzlich eine leicht durchsichtige Fl\u00E4che", 0x55FFFF, () -> {
            cfg.hitboxFill = !cfg.hitboxFill;
            changed();
        }));
        grid.add(new Btn("Eigene Box: " + (cfg.hitboxSelf ? "An" : "Aus"), "",
                "Auch deine eigene Hitbox zeichnen (3. Person)", 0x55FFFF, () -> {
            cfg.hitboxSelf = !cfg.hitboxSelf;
            changed();
        }));
        grid.add(new Btn("Reichweite: " + CustomGuiConfig.RANGES[cfg.hitboxRange] + "m", "",
                "Bis zu welcher Entfernung Hitboxen angezeigt werden", 0x55FFFF, () -> {
            cfg.hitboxRange = (cfg.hitboxRange + 1) % CustomGuiConfig.RANGES.length;
            changed();
        }));
        addGrid(grid, py + 80, false);
    }

    /** Speichert die Konfiguration und baut die Buttons mit den neuen Beschriftungen neu auf. */
    private void changed() {
        CustomGuiConfig.save();
        rebuild();
    }

    private int pageCount() {
        int n = CustomGuiConfig.get().islands.size();
        return Math.max(1, (n + rowsPerPage - 1) / rowsPerPage);
    }

    private void openForm() {
        formMode = true;
        formError = "";
        updateBoxes();
        rebuild();
    }

    private void saveIsland() {
        String name = formName.trim();
        String cmd = formCmd.trim();
        while (cmd.startsWith("/")) {
            cmd = cmd.substring(1).trim();
        }
        if (name.isEmpty()) {
            formError = "Bitte gib einen Namen ein.";
            return;
        }
        if (cmd.isEmpty()) {
            formError = "Bitte gib einen Befehl ein.";
            return;
        }
        List<CustomGuiConfig.Island> list = CustomGuiConfig.get().islands;
        list.add(new CustomGuiConfig.Island(name, cmd));
        CustomGuiConfig.save();

        formName = "";
        formCmd = "";
        nameBox.setValue("");
        cmdBox.setValue("");
        formError = "";
        formMode = false;
        page = pageCount() - 1;
        updateBoxes();
        rebuild();
    }

    // ------------------------------------------------------------------ Taste

    private static String keyName() {
        return CustomGuiMod.OPEN_KEY.getTranslatedKeyMessage().getString();
    }

    private void resetKey() {
        KeyMapping k = CustomGuiMod.OPEN_KEY;
        k.setKey(k.getDefaultKey());
        KeyMapping.resetMapping();
        Minecraft.getInstance().options.save();
        listening = false;
        rebuild();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (listening) {
            if (event.key() != GLFW.GLFW_KEY_ESCAPE) {
                CustomGuiMod.OPEN_KEY.setKey(InputConstants.Type.KEYSYM.getOrCreate(event.key()));
                KeyMapping.resetMapping();
                Minecraft.getInstance().options.save();
            }
            listening = false;
            rebuild();
            return true;
        }
        return super.keyPressed(event);
    }

    // ------------------------------------------------------------------ Aktionen

    private void runCommand(String command) {
        String cmd = command;
        while (cmd.startsWith("/")) {
            cmd = cmd.substring(1);
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            mc.getConnection().sendCommand(cmd);
        }
        this.onClose();
    }

    private void openShop() {
        try {
            openInBrowser(CustomGuiConfig.get().shopUrl);
        } catch (Exception e) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.sendSystemMessage(
                        Component.literal("Shop-Link konnte nicht geöffnet werden: " + e.getMessage())
                                .withStyle(ChatFormatting.RED));
            }
        }
        this.onClose();
    }

    /** Öffnet einen http(s)-Link im Standard-Browser (nur mit Java-Mitteln, ohne Minecraft-API). */
    private static void openInBrowser(String url) throws Exception {
        URI uri = URI.create(url.trim());
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Es sind nur http(s)-Links erlaubt");
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        ProcessBuilder pb;
        if (os.contains("win")) {
            pb = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", uri.toString());
        } else if (os.contains("mac")) {
            pb = new ProcessBuilder("open", uri.toString());
        } else {
            pb = new ProcessBuilder("xdg-open", uri.toString());
        }
        pb.start();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ Rendern

    @Override
    public void extractRenderState(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float delta) {
        super.extractRenderState(gfx, mouseX, mouseY, delta); // Hintergrund

        CustomGuiConfig cfg = CustomGuiConfig.get();
        boolean classic = cfg.theme == 1;
        boolean anim = cfg.animations;
        int accentC = 0xFF000000 | CustomGuiConfig.ACCENTS[cfg.accent];

        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastFrame) / 1_000_000_000f);
        lastFrame = now;
        float time = (now - openedAt) / 1_000_000_000f;
        float fxTime = anim ? time : 0f;

        float open = 1f;
        int slide = 0;
        if (anim) {
            open = Math.min(1f, time / 0.35f);
            open = 1f - (float) Math.pow(1f - open, 3);
            slide = Math.round((1f - open) * 18f);
        }
        boolean interactive = open > 0.95f;

        if (cfg.particles) {
            drawStars(gfx, fxTime, open, classic ? 0.5f : accentHue(accentC));
        }

        int x1 = px, y1 = py + slide, x2 = px + pw, y2 = py + ph + slide;

        if (classic) {
            // Panel mit Schatten
            roundRect(gfx, x1 - 3, y1 - 3, x2 + 3, y2 + 3, withAlpha(0xFF000000, open * 0.25f));
            roundRect(gfx, x1, y1, x2, y2, withAlpha(0xFF3A3A55, open));
            roundRect(gfx, x1 + 1, y1 + 1, x2 - 1, y2 - 1, withAlpha(0xFF14141E, open * 0.96f));

            // Verlaufs-Header (leichter Glanz oben)
            for (int i = 0; i < 40; i++) {
                float a = (1f - i / 40f) * 0.10f;
                gfx.fill(x1 + 2, y1 + 2 + i, x2 - 2, y1 + 3 + i, withAlpha(0xFFFFFFFF, open * a));
            }

            // Regenbogen-Leiste
            for (int sx = x1 + 8; sx < x2 - 8; sx += 4) {
                float hue = (sx - x1) / (float) pw + fxTime * 0.12f;
                gfx.fill(sx, y1 + 4, Math.min(sx + 4, x2 - 8), y1 + 6, withAlpha(hsv(hue, 0.55f, 1f), open));
            }

            // Titel mit wanderndem Farbverlauf
            String title = "Mein Custom GUI";
            int totalW = 0;
            for (int i = 0; i < title.length(); i++) {
                totalW += this.font.width(boldChar(title.charAt(i)));
            }
            int tx = this.width / 2 - totalW / 2;
            for (int i = 0; i < title.length(); i++) {
                Component c = boldChar(title.charAt(i));
                int col = hsv(0.5f + 0.18f * (float) Math.sin(fxTime * 1.5f + i * 0.45f), 0.45f, 1f);
                gfx.text(this.font, c, tx, y1 + 14, withAlpha(col, open));
                tx += this.font.width(c);
            }
        } else {
            // Cytooxien-Stil: flach, dunkelblau, eckig, Akzentlinie unter dem Kopf
            gfx.fill(x1 - 2, y1 - 2, x2 + 2, y2 + 2, withAlpha(0xFF000000, open * 0.35f));
            gfx.fill(x1, y1, x2, y2, withAlpha(0xFF22304F, open));
            gfx.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, withAlpha(0xFF0D1220, open * 0.97f));
            gfx.fill(x1 + 1, y1 + 1, x2 - 1, y1 + 29, withAlpha(0xFF121B31, open));
            gfx.fill(x1 + 1, y1 + 29, x2 - 1, y1 + 31, withAlpha(accentC, open));

            int tx = x1 + 14;
            int ty = y1 + 11;
            Component a = Component.literal("Cytooxien").withStyle(ChatFormatting.BOLD);
            gfx.text(this.font, a, tx, ty, withAlpha(0xFFFFFFFF, open));
            tx += this.font.width(a);
            Component b = Component.literal(" \u00BB ").withStyle(ChatFormatting.BOLD);
            gfx.text(this.font, b, tx, ty, withAlpha(accentC, open));
            tx += this.font.width(b);
            Component c = Component.literal("Men\u00FC");
            gfx.text(this.font, c, tx, ty, withAlpha(0xFFA0AEC0, open));

            Component url = Component.literal("cytooxien.net");
            gfx.text(this.font, url, x2 - 14 - this.font.width(url), ty, withAlpha(0xFF5A6A8A, open));
        }

        drawTabs(gfx, mouseX, mouseY - slide, dt, open, slide, interactive, classic, anim, accentC);

        // Inhalt je Tab
        if (tab == Tab.PVP) {
            drawLabel(gfx, "PvP-Hitboxen", px + 20, py + 66 + slide, open);
            drawLabel(gfx, "Vorschau", px + 20, py + 204 + slide, open);
            drawHitboxPreview(gfx, px + 84, py + 190 + slide, open, time);
            if (!cfg.hitboxEnabled) {
                Component off = Component.literal("Hitboxen sind ausgeschaltet").withStyle(ChatFormatting.DARK_GRAY);
                gfx.text(this.font, off, px + 120, py + 204 + slide, withAlpha(0xFFFFFFFF, open));
            }
        } else if (tab == Tab.SETTINGS) {
            drawLabel(gfx, "Tastenbelegung", px + 20, py + 66 + slide, open);
            drawLabel(gfx, "Layout & Design", px + 20, py + 108 + slide, open);
        } else if (tab == Tab.ISLANDS) {
            if (formMode) {
                drawLabel(gfx, "Name der Insel", px + 30, py + 70 + slide, open);
                drawLabel(gfx, "Befehl (zum Hinkommen)", px + 30, py + 116 + slide, open);
                nameBox.extractRenderState(gfx, mouseX, mouseY, delta);
                cmdBox.extractRenderState(gfx, mouseX, mouseY, delta);
                if (!formError.isEmpty()) {
                    Component err = Component.literal(formError).withStyle(ChatFormatting.RED);
                    gfx.text(this.font, err, this.width / 2 - this.font.width(err) / 2,
                            py + 156 + slide, withAlpha(0xFFFFFFFF, open));
                }
            } else if (CustomGuiConfig.get().islands.isEmpty()) {
                Component e1 = Component.literal("Noch keine Inseln gespeichert.").withStyle(ChatFormatting.GRAY);
                Component e2 = Component.literal("F\u00FCge deine erste Insel hinzu!").withStyle(ChatFormatting.DARK_GRAY);
                gfx.text(this.font, e1, this.width / 2 - this.font.width(e1) / 2, py + 110 + slide,
                        withAlpha(0xFFFFFFFF, open));
                gfx.text(this.font, e2, this.width / 2 - this.font.width(e2) / 2, py + 124 + slide,
                        withAlpha(0xFFFFFFFF, open));
            }
        }

        // Buttons
        Btn hoveredBtn = null;
        for (Btn b : buttons) {
            boolean hovered = interactive && b.contains(mouseX, mouseY - slide);
            if (hovered) hoveredBtn = b;
            if (hovered && !b.wasHovered) {
                playUi(1.8f);
            }
            b.wasHovered = hovered;
            float target = hovered ? 1f : 0f;
            if (anim) {
                b.hover += (target - b.hover) * (1f - (float) Math.exp(-dt * 14f));
            } else {
                b.hover = target;
            }
            drawButton(gfx, b, slide, fxTime, open, classic, anim, accentC);
        }

        // Hinweistext unten
        if (hoveredBtn != null && !hoveredBtn.tip.isEmpty()) {
            Component tip = Component.literal(hoveredBtn.tip).withStyle(ChatFormatting.GRAY);
            gfx.text(this.font, tip, this.width / 2 - this.font.width(tip) / 2,
                    py + ph - 11 + slide, withAlpha(0xFFFFFFFF, open));
        }
    }

    private void drawTabs(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float dt, float open,
                          int slide, boolean interactive, boolean classic, boolean anim, int accentC) {
        int tabH = 20;
        int ty = py + 34 + slide;

        int selected = tab.ordinal();
        float targetX = tabX[selected];
        float targetW = tabW[selected];
        if (indicatorX < 0 || !anim) {
            indicatorX = targetX;
            indicatorW = targetW;
        }
        float k = 1f - (float) Math.exp(-dt * 12f);
        indicatorX += (targetX - indicatorX) * k;
        indicatorW += (targetW - indicatorW) * k;

        for (int i = 0; i < TAB_COUNT; i++) {
            int tx = tabX[i];
            int tw = tabW[i];
            boolean hovered = interactive && mouseX >= tx && mouseX < tx + tw
                    && mouseY >= py + 34 && mouseY < py + 34 + tabH;
            if (hovered && !tabWasHovered[i] && i != selected) playUi(1.8f);
            tabWasHovered[i] = hovered;
            float target = hovered ? 1f : 0f;
            tabHover[i] = anim ? tabHover[i] + (target - tabHover[i]) * (1f - (float) Math.exp(-dt * 14f)) : target;

            if (tabHover[i] > 0.02f) {
                box(gfx, classic, tx, ty, tx + tw, ty + tabH,
                        withAlpha(0xFFFFFFFF, open * tabHover[i] * 0.07f));
            }
            int base = i == selected ? 0xFFFFFFFF : 0xFF9A9AB0;
            int col = lerpColor(base, 0xFFFFFFFF, tabHover[i]);
            Component c = Component.literal(TAB_NAMES[i]).withStyle(ChatFormatting.BOLD);
            gfx.text(this.font, c, tx + tw / 2 - this.font.width(c) / 2,
                    ty + tabH / 2 - this.font.lineHeight / 2, withAlpha(col, open));
        }
        // gleitender Unterstrich
        int underline = classic ? 0xFF55FFFF : accentC;
        int ux = Math.round(indicatorX);
        gfx.fill(ux + 6, ty + tabH + 1, ux + Math.round(indicatorW) - 6, ty + tabH + 3,
                withAlpha(underline, open));
    }

    /** Kleine Vorschau einer Hitbox (Rahmen) in der gewählten Farbe. */
    private void drawHitboxPreview(GuiGraphicsExtractor gfx, int x, int y, float open, float time) {
        CustomGuiConfig cfg = CustomGuiConfig.get();
        int w = 22, h = 36;
        int th = 1 + cfg.hitboxThickness; // 1..3 Pixel
        float speed = CustomGuiConfig.SPEEDS[cfg.hitboxSpeed];

        if (cfg.hitboxFill) {
            gfx.fill(x, y, x + w, y + h, withAlpha(previewColor(cfg, time, speed, 0f), open * 0.15f));
        }
        gfx.fill(x, y, x + w, y + th, withAlpha(previewColor(cfg, time, speed, 0.00f), open));                  // oben
        gfx.fill(x + w - th, y, x + w, y + h, withAlpha(previewColor(cfg, time, speed, 0.25f), open));          // rechts
        gfx.fill(x, y + h - th, x + w, y + h, withAlpha(previewColor(cfg, time, speed, 0.50f), open));          // unten
        gfx.fill(x, y, x + th, y + h, withAlpha(previewColor(cfg, time, speed, 0.75f), open));                  // links
    }

    private static int previewColor(CustomGuiConfig cfg, float time, float speed, float offset) {
        if (cfg.hitboxColor == 0) {
            return hsv(time * speed + offset, 0.85f, 1f);
        }
        return 0xFF000000 | CustomGuiConfig.HITBOX_COLORS[cfg.hitboxColor - 1];
    }

    private void drawLabel(GuiGraphicsExtractor gfx, String text, int x, int y, float open) {
        gfx.text(this.font, Component.literal(text).withStyle(ChatFormatting.GRAY), x, y,
                withAlpha(0xFFFFFFFF, open));
    }

    private void drawStars(GuiGraphicsExtractor gfx, float time, float open, float baseHue) {
        for (int i = 0; i < 36; i++) {
            float fx = ((i * 0.61803f) % 1f) * this.width;
            float speed = 6f + (i % 5) * 4f;
            float fy = this.height - ((time * speed + i * 41f) % this.height);
            float twinkle = 0.5f + 0.5f * (float) Math.sin(time * 2f + i);
            int size = i % 4 == 0 ? 2 : 1;
            int col = hsv(baseHue + (i % 7) * 0.06f, 0.35f, 1f);
            gfx.fill((int) fx, (int) fy, (int) fx + size, (int) fy + size,
                    withAlpha(col, open * (0.15f + 0.30f * twinkle)));
        }
    }

    private void drawButton(GuiGraphicsExtractor gfx, Btn b, int slide, float time, float open,
                            boolean classic, boolean anim, int themeAccent) {
        float h = b.active ? 1f : b.hover;
        int grow = classic ? Math.round(h * 2f) : 0;
        int lift = classic ? Math.round(h * 2f) : 0;
        int x1 = b.x - grow;
        int y1 = b.y + slide - grow - lift;
        int x2 = b.x + b.w + grow;
        int y2 = b.y + slide + b.h + grow - lift;
        int accent = (b.themed && !classic) ? themeAccent : (0xFF000000 | b.accent);

        if (classic) {
            // Glow
            if (h > 0.02f) {
                for (int i = 4; i >= 1; i--) {
                    roundRect(gfx, x1 - i, y1 - i, x2 + i, y2 + i, withAlpha(accent, open * h * 0.07f));
                }
            }
            int border = lerpColor(0xFF44445A, accent, h);
            int bg = lerpColor(0xFF24243A, mixWithBlack(accent, 0.42f), h * 0.85f);
            roundRect(gfx, x1, y1, x2, y2, withAlpha(border, open));
            roundRect(gfx, x1 + 1, y1 + 1, x2 - 1, y2 - 1, withAlpha(bg, open));

            // Akzentstreifen links
            int stripe = Math.round(2 + h * 3);
            gfx.fill(x1 + 3, y1 + 4, x1 + 3 + stripe, y2 - 4, withAlpha(accent, open * (0.35f + 0.65f * h)));

            // Glanzstreifen beim Hover
            if (anim && h > 0.02f) {
                float t = (time * 0.9f) % 1.4f - 0.2f;
                int sx = x1 + Math.round(t * (x2 - x1));
                int l = Math.max(x1 + 2, sx);
                int r = Math.min(x2 - 2, sx + 10);
                if (r > l) {
                    gfx.fill(l, y1 + 2, r, y2 - 2, withAlpha(0xFFFFFFFF, open * h * 0.16f));
                }
            }
        } else {
            // Cytooxien-Stil: flach und eckig
            int border = lerpColor(0xFF2A3A5C, accent, h);
            int bg = lerpColor(0xFF141C30, mixWithBlack(accent, 0.30f), h * 0.9f);
            gfx.fill(x1, y1, x2, y2, withAlpha(border, open));
            gfx.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, withAlpha(bg, open));
            int stripe = Math.round(2 + h * 2);
            gfx.fill(x1 + 1, y1 + 1, x1 + 1 + stripe, y2 - 1, withAlpha(accent, open * (0.45f + 0.55f * h)));
        }

        int textColor = lerpColor(0xFFDDDDDD, 0xFFFFFFFF, h);
        Component label = Component.literal(b.label).withStyle(ChatFormatting.BOLD);
        boolean hasSub = !b.sub.isEmpty();
        if (b.left) {
            gfx.text(this.font, label, x1 + 12, y1 + 5, withAlpha(textColor, open));
            if (hasSub) {
                Component sub = Component.literal(b.sub).withStyle(ChatFormatting.GRAY);
                gfx.text(this.font, sub, x1 + 12, y1 + 17, withAlpha(0xFFFFFFFF, open));
            }
        } else if (hasSub) {
            // Kachel: Titel mittig, darunter kleine Zeile
            int cy = (y1 + y2) / 2;
            gfx.text(this.font, label, (x1 + x2) / 2 - this.font.width(label) / 2,
                    cy - this.font.lineHeight, withAlpha(textColor, open));
            Component sub = Component.literal(b.sub).withStyle(ChatFormatting.GRAY);
            gfx.text(this.font, sub, (x1 + x2) / 2 - this.font.width(sub) / 2,
                    cy + 3, withAlpha(0xFFFFFFFF, open));
        } else {
            gfx.text(this.font, label, (x1 + x2) / 2 - this.font.width(label) / 2,
                    (y1 + y2) / 2 - this.font.lineHeight / 2 + 1, withAlpha(textColor, open));
        }
    }

    // ------------------------------------------------------------------ Eingabe

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true; // Klick auf ein Textfeld
        }
        if (event.button() == 0) {
            // Tabs
            int tabH = 20;
            for (int i = 0; i < TAB_COUNT; i++) {
                int tx = tabX[i];
                if (event.x() >= tx && event.x() < tx + tabW[i]
                        && event.y() >= py + 34 && event.y() < py + 34 + tabH) {
                    Tab target = Tab.values()[i];
                    if (target != tab) {
                        tab = target;
                        formMode = false;
                        listening = false;
                        formError = "";
                        page = 0;
                        updateBoxes();
                        rebuild();
                        playUi(1.0f);
                    }
                    return true;
                }
            }
            // Buttons
            for (Btn b : buttons) {
                if (b.contains(event.x(), event.y())) {
                    playUi(1.0f);
                    b.action.run();
                    return true;
                }
            }
        }
        return false;
    }

    private void playUi(float pitch) {
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, pitch));
    }

    // ------------------------------------------------------------------ Helfer

    private static Component boldChar(char c) {
        return Component.literal(String.valueOf(c)).withStyle(ChatFormatting.BOLD);
    }

    /** Rechteck: im klassischen Design mit abgerundeten Ecken (Radius 2), sonst eckig. */
    private static void box(GuiGraphicsExtractor gfx, boolean rounded, int x1, int y1, int x2, int y2, int color) {
        if (rounded) {
            roundRect(gfx, x1, y1, x2, y2, color);
        } else {
            gfx.fill(x1, y1, x2, y2, color);
        }
    }

    /** Rechteck mit abgerundeten Ecken (Radius 2), ohne überlappende Flächen. */
    private static void roundRect(GuiGraphicsExtractor gfx, int x1, int y1, int x2, int y2, int color) {
        gfx.fill(x1, y1 + 2, x2, y2 - 2, color);
        gfx.fill(x1 + 2, y1, x2 - 2, y1 + 1, color);
        gfx.fill(x1 + 1, y1 + 1, x2 - 1, y1 + 2, color);
        gfx.fill(x1 + 1, y2 - 2, x2 - 1, y2 - 1, color);
        gfx.fill(x1 + 2, y2 - 1, x2 - 2, y2, color);
    }

    /** Farbton (0..1) einer RGB-Farbe, damit die Sterne zur Akzentfarbe passen. */
    private static float accentHue(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float d = max - min;
        if (d < 0.0001f) return 0.55f; // Weiß/Grau: bläulicher Standard
        float hue;
        if (max == r) {
            hue = ((g - b) / d) % 6f;
        } else if (max == g) {
            hue = (b - r) / d + 2f;
        } else {
            hue = (r - g) / d + 4f;
        }
        hue /= 6f;
        return hue < 0 ? hue + 1f : hue;
    }

    private static int hsv(float h, float s, float v) {
        h = h - (float) Math.floor(h);
        float scaled = h * 6f;
        int i = (int) scaled;
        float f = scaled - i;
        float p = v * (1f - s);
        float q = v * (1f - f * s);
        float t = v * (1f - (1f - f) * s);
        float r = v, g = t, b = p;
        switch (i % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return 0xFF000000 | (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }

    private static int withAlpha(int argb, float factor) {
        int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, factor)));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    private static int lerpColor(int c1, int c2, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = lerp((c1 >>> 24) & 0xFF, (c2 >>> 24) & 0xFF, t);
        int r = lerp((c1 >> 16) & 0xFF, (c2 >> 16) & 0xFF, t);
        int g = lerp((c1 >> 8) & 0xFF, (c2 >> 8) & 0xFF, t);
        int b = lerp(c1 & 0xFF, c2 & 0xFF, t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerp(int a, int b, float t) {
        return Math.round(a + (b - a) * t);
    }

    private static int mixWithBlack(int argb, float keep) {
        int r = Math.round(((argb >> 16) & 0xFF) * keep);
        int g = Math.round(((argb >> 8) & 0xFF) * keep);
        int b = Math.round((argb & 0xFF) * keep);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
