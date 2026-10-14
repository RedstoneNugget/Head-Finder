package com.headfinder;

import com.headfinder.command.HeadCommands;
import com.headfinder.config.ModConfig;
import com.headfinder.data.HeadStore;
import com.headfinder.detect.ProfileMatcher;
import com.headfinder.gui.ConfigScreen;
import com.headfinder.gui.HeadListScreen;
import com.headfinder.hud.HeadHud;
import com.headfinder.render.HeadRenderer;
import com.headfinder.scan.HeadScanner;
import com.headfinder.scan.InteractionTracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Head Finder – rein clientseitige Fabric-Mod (Mod-ID "head_finder"), findet Event-Köpfe.
 *
 * Es gibt keine Server-Komponente und keine eigenen Netzwerkpakete: ausgewertet werden nur Chunks,
 * Block-Entities und Entities, die der Client vom Server sowieso bekommt.
 */
public class HeadFinderClient implements ClientModInitializer {

    public static final String MOD_ID = "head_finder";
    public static final Logger LOGGER = LoggerFactory.getLogger("Head Finder");

    private static ModConfig config;
    private static long configSaveAt = -1;
    private static final ProfileMatcher MATCHER = new ProfileMatcher();
    private static final HeadStore STORE = new HeadStore();
    private static final HeadScanner SCANNER = new HeadScanner(MATCHER, STORE);

    public static KeyBinding listKey;
    public static KeyBinding toggleEspKey;
    public static KeyBinding configKey;

    public static ModConfig config() {
        if (config == null) {
            config = ModConfig.load();
            MATCHER.rebuild(config);
        }
        return config;
    }

    public static HeadStore store() {
        return STORE;
    }

    public static ProfileMatcher matcher() {
        return MATCHER;
    }

    public static HeadScanner scanner() {
        return SCANNER;
    }

    /** Nach jeder Änderung (Menü, Befehl): sofort anwenden, Speichern gebündelt kurz danach. */
    public static void onConfigEdited() {
        config().sanitize();
        MATCHER.rebuild(config);
        SCANNER.requestRescan();
        configSaveAt = System.currentTimeMillis() + 1000;
    }

    /** Config-Datei neu einlesen (z. B. nach Bearbeiten von Hand). */
    public static void reloadConfig() {
        config = ModConfig.load();
        MATCHER.rebuild(config);
        SCANNER.requestRescan();
    }

    /** Config komplett ersetzen (für Tests). */
    public static void replaceConfig(ModConfig c) {
        config = c;
        onConfigEdited();
    }

    public static void saveConfigNow() {
        configSaveAt = -1;
        config().save();
    }

    @Override
    public void onInitializeClient() {
        migrateOldConfig();
        config();

        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
        listKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.head_finder.list", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, category));
        toggleEspKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.head_finder.toggle_esp", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
        configKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.head_finder.config", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));

        ClientChunkEvents.CHUNK_LOAD.register(SCANNER::onChunkLoad);
        ClientChunkEvents.CHUNK_UNLOAD.register(SCANNER::onChunkUnload);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> STORE.open(serverKey(client)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> STORE.close());
        ClientTickEvents.END_CLIENT_TICK.register(HeadFinderClient::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> {
            if (configSaveAt >= 0) saveConfigNow();
            STORE.close();
        });

        new InteractionTracker(MATCHER, STORE).register();
        HeadRenderer.register();
        HeadHud.register();
        HeadCommands.register();

        LOGGER.info("Head Finder geladen – K öffnet die Kopf-Liste, /headfinder für Befehle");
    }

    /**
     * Version 1.0.0 hieß "Coole Socke 69" und speicherte unter config/coole_socke_69.
     * Beim ersten Start von Head Finder wird der Ordner einmalig übernommen (Einstellungen + gefundene Köpfe).
     */
    private static void migrateOldConfig() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        if (migrateConfigDir(configDir.resolve("coole_socke_69"), ModConfig.dir())) {
            LOGGER.info("Einstellungen aus config/coole_socke_69 nach config/{} übernommen", MOD_ID);
        }
    }

    /** Verschiebt {@code oldDir} nach {@code newDir}, falls es den alten Ordner gibt und den neuen noch nicht. */
    public static boolean migrateConfigDir(Path oldDir, Path newDir) {
        if (!Files.isDirectory(oldDir) || Files.exists(newDir)) return false;
        try {
            Files.move(oldDir, newDir);
            return true;
        } catch (IOException e) {
            LOGGER.warn("Alte Einstellungen aus {} konnten nicht übernommen werden", oldDir, e);
            return false;
        }
    }

    /**
     * Schlüssel für die Speicherdatei: Einzelspieler-Weltname bzw. Serveradresse.
     * Dimensionen werden innerhalb der Datei getrennt.
     */
    public static String serverKey(MinecraftClient mc) {
        if (mc.isIntegratedServerRunning() && mc.getServer() != null) {
            return "sp_" + mc.getServer().getSaveProperties().getLevelName();
        }
        ServerInfo info = mc.getCurrentServerEntry();
        if (info != null && info.address != null && !info.address.isBlank()) return "mp_" + info.address;
        return "unknown";
    }

    private static void tick(MinecraftClient mc) {
        ModConfig cfg = config();
        // Falls JOIN verpasst wurde (z. B. Mod-Reload): Speicher trotzdem öffnen
        if (mc.world != null && !STORE.isOpen()) STORE.open(serverKey(mc));

        while (listKey.wasPressed()) {
            if (mc.currentScreen == null && mc.world != null) mc.setScreen(new HeadListScreen(null));
        }
        while (configKey.wasPressed()) {
            if (mc.currentScreen == null) mc.setScreen(new ConfigScreen(null));
        }
        while (toggleEspKey.wasPressed()) {
            cfg.espEnabled = !cfg.espEnabled;
            onConfigEdited();
            if (mc.inGameHud != null) {
                mc.inGameHud.setOverlayMessage(Text.literal("Markierungen: " + (cfg.espEnabled ? "an" : "aus"))
                        .formatted(cfg.espEnabled ? Formatting.GREEN : Formatting.GRAY), false);
            }
        }

        SCANNER.tick(mc);
        STORE.tick();
        if (configSaveAt >= 0 && System.currentTimeMillis() >= configSaveAt) saveConfigNow();
    }
}
