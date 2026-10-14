package com.headfinder.test;

import com.headfinder.HeadFinderClient;
import com.headfinder.config.ModConfig;
import com.headfinder.data.HeadEntry;
import com.headfinder.detect.ProfileMatcher;
import com.headfinder.gui.ConfigScreen;
import com.headfinder.gui.HeadListScreen;
import com.headfinder.scan.HeadScanner;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Automatischer In-Game-Test: gradlew runClientGameTest
 *
 * Startet einen echten Dedicated Server (wie Hugo SMP), platziert Event-Köpfe per Befehl und prüft:
 * Erkennung (Textur ODER Name, verschiedene UUIDs), Items/Rahmen, Rechtsklick, Verschwinden,
 * Speicherung über Neuverbindung und Reset. Screenshots: build/run/clientGameTest/screenshots.
 */
public class HeadFinderClientGameTest implements FabricClientGameTest {

    private static final String URL = "http://textures.minecraft.net/texture/3d8c1f399f9a74ce148300cb6fa79fbc13ba44f9a52c25ce9e452b49f5b40a10";
    private static final String OTHER_URL = "http://textures.minecraft.net/texture/1a4af718455d4aab528e7a61f86fa25e6a369d1768dcb13f7df319a713eb810b";

    private static final BlockPos A = new BlockPos(5, -60, 5);   // stehend, volles Profil (UUID-Beispiel 1)
    private static final BlockPos B = new BlockPos(8, -59, 3);   // Wandkopf, volles Profil (UUID-Beispiel 2)
    private static final BlockPos C = new BlockPos(12, -60, -4); // nur Textur passt (anderer Name)
    private static final BlockPos D = new BlockPos(-6, -60, 7);  // nur Name passt (andere Textur)
    private static final BlockPos E = new BlockPos(-3, -60, -5); // passt nicht
    private static final BlockPos F = new BlockPos(2, -60, 8);   // Item-Frame

    @Override
    public void runTest(ClientGameTestContext context) {
        // immer ohne Ton
        context.runOnClient(c -> c.options.getSoundVolumeOption(SoundCategory.MASTER).setValue(0.0));
        context.runOnClient(c -> HeadFinderClient.replaceConfig(new ModConfig()));

        unitChecks();
        if (phase("main")) mainPhase(context);
        if (phase("stress")) stressPhase(context);
        log("ALLE TESTS BESTANDEN");
    }

    /** Nur bestimmte Phasen: gradlew runClientGameTest -Ponly=main,stress */
    private static boolean phase(String name) {
        String only = System.getProperty("headfinder.only", "");
        return only.isEmpty() || only.contains(name);
    }

    // ---------------------------------------------------------------- Phase 1: Funktionen

    private void mainPhase(ClientGameTestContext context) {
        try (TestDedicatedServerContext server = context.worldBuilder().setUseConsistentSettings(true).createServer()) {
            try (TestServerConnection connection = server.connect()) {
                connection.getClientWorld().waitForChunksRender();
                context.runOnClient(c -> HeadFinderClient.store().resetAll());
                buildScene(server);
                server.runCommand("tp @a -14 -58 1 facing 5 -60 3");
                context.waitTicks(10);

                // ---- 1) Erkennung
                waitUntil(context, c -> HeadFinderClient.store().countDiscovered() >= 6, "6 Köpfe entdeckt");
                context.waitTicks(25);
                int discovered = context.computeOnClient(c -> HeadFinderClient.store().countDiscovered());
                check(discovered == 6, "genau 6 Treffer (4 Blöcke, 1 Rahmen, 1 Item), war " + discovered);
                for (BlockPos p : new BlockPos[]{A, B, C, D}) check(entry(context, p, HeadEntry.Kind.BLOCK) != null, "Kopf bei " + p.toShortString() + " erkannt");
                check(entry(context, E, HeadEntry.Kind.BLOCK) == null, "fremder Kopf bei E nicht erkannt");
                check(entry(context, F, HeadEntry.Kind.FRAME) != null, "Kopf im Item-Frame erkannt");
                check(context.computeOnClient(c -> HeadFinderClient.store().all().stream().anyMatch(e -> e.kind == HeadEntry.Kind.ITEM)), "Kopf als Item am Boden erkannt");
                check(context.computeOnClient(c -> HeadFinderClient.store().countFound()) == 0, "noch nichts gefunden");
                context.takeScreenshot("01_markierungen_durch_wand");

                // ---- 2) Rechtsklick → gefunden, wird nicht mehr markiert
                server.runCommand("tp @a 5.5 -60 2.5 facing 5.5 -60 5.5");
                context.waitTicks(10);
                context.runOnClient(c -> c.interactionManager.interactBlock(c.player, Hand.MAIN_HAND,
                        new BlockHitResult(Vec3d.ofCenter(A), Direction.NORTH, A, false)));
                context.waitTicks(2);
                HeadEntry a = entry(context, A, HeadEntry.Kind.BLOCK);
                check(a != null && a.found && a.foundReason.equals("Rechtsklick"), "Rechtsklick markiert A als gefunden");

                // ---- 3) Block verschwindet → gefunden
                server.runCommand("setblock " + B.getX() + " " + B.getY() + " " + B.getZ() + " minecraft:air");
                waitUntil(context, c -> found(B), "B nach Verschwinden gefunden");
                check(entry(context, B, HeadEntry.Kind.BLOCK).foundReason.equals("verschwunden"), "Grund: verschwunden");
                check(context.computeOnClient(c -> HeadFinderClient.store().countFound()) == 2, "2 gefunden");

                server.runCommand("tp @a -14 -58 1 facing 5 -60 3");
                context.waitTicks(15);
                context.takeScreenshot("02_gefundene_ausgeblendet");
                context.runOnClient(c -> {
                    HeadFinderClient.config().hideFound = false;
                    HeadFinderClient.onConfigEdited();
                });
                context.waitTicks(3);
                context.takeScreenshot("03_gefundene_gruen");
                context.runOnClient(c -> {
                    HeadFinderClient.config().hideFound = true;
                    HeadFinderClient.onConfigEdited();
                });
            }

            // ---- 4) Speicherung über Neuverbindung
            try (TestServerConnection connection = server.connect()) {
                connection.getClientWorld().waitForChunksRender();
                context.waitTicks(30);
                check(found(context, A) && found(context, B), "Gefunden-Status nach Neuverbindung erhalten");
                check(context.computeOnClient(c -> HeadFinderClient.store().countDiscovered()) == 6, "6 Köpfe nach Neuverbindung");

                // ---- 5) Reset per Chat-Befehl
                context.runOnClient(c -> c.player.networkHandler.sendChatCommand("headfinder reset"));
                context.waitTicks(2);
                check(!found(context, A), "A nach /headfinder reset wieder offen");
                waitUntil(context, c -> found(B), "verschwundener Kopf B wird erneut als gefunden erkannt");

                // ---- 6) Ziel setzen + Bildschirme
                context.runOnClient(c -> c.player.networkHandler.sendChatCommand("headfinder target " + C.getX() + " " + C.getY() + " " + C.getZ()));
                context.waitTicks(2);
                check(context.computeOnClient(c -> HeadFinderClient.store().targetKey() != null
                        && HeadFinderClient.store().targetKey().equals(HeadEntry.key(HeadScanner.dimension(c.world), C, HeadEntry.Kind.BLOCK))), "Ziel C gesetzt");
                server.runCommand("tp @a -14 -58 1 facing 5 -60 3");
                context.waitTicks(15);
                context.takeScreenshot("04_ziel_gesetzt");
                context.runOnClient(c -> c.player.networkHandler.sendChatCommand("headfinder list"));
                context.waitTicks(5);
                context.takeScreenshot("05_chat_liste");
                context.setScreen(() -> new HeadListScreen(null));
                context.waitTicks(5);
                context.takeScreenshot("06_liste");
                context.setScreen(() -> new ConfigScreen(null));
                context.waitTicks(5);
                context.takeScreenshot("07_einstellungen");
                context.setScreen(() -> null);
            }
        }
    }

    // ---------------------------------------------------------------- Phase 2: 200 Köpfe (Event hat ~100)

    private static final int STRESS_COUNT = 200;

    private void stressPhase(ClientGameTestContext context) {
        // 200 eindeutige Positionen im Radius ~100 um den Spawn, jede mit eigener UUID
        java.util.Random rnd = new java.util.Random(69);
        java.util.LinkedHashSet<BlockPos> positions = new java.util.LinkedHashSet<>();
        while (positions.size() < STRESS_COUNT) {
            int x = rnd.nextInt(201) - 100;
            int z = rnd.nextInt(201) - 100;
            int y = switch (positions.size() % 3) {
                case 0 -> -62;                      // versteckt in der Erde
                case 1 -> -60;                      // am Boden
                default -> -55 + rnd.nextInt(15);   // schwebend
            };
            positions.add(new BlockPos(x, y, z));
        }

        try (TestDedicatedServerContext server = context.worldBuilder().setUseConsistentSettings(true).createServer()) {
            try (TestServerConnection connection = server.connect()) {
                connection.getClientWorld().waitForChunksRender();
                context.runOnClient(c -> HeadFinderClient.store().resetAll());
                server.runCommand("gamemode spectator @a");
                server.runCommand("forceload add -112 -112 112 112");
                int i = 0;
                for (BlockPos p : positions) {
                    String uuid = "[I;" + (1000 + i) + "," + (i * 7919) + "," + (-i - 1) + "," + (i * 31 + 5) + "]";
                    server.runCommand(setblock(p, "minecraft:player_head[rotation=" + (i % 16) + "]", profile("FindEventHead", uuid, URL)));
                    i++;
                }
                // Gebiet ablaufen, damit der Client alle Chunks einmal bekommt
                long t0 = System.currentTimeMillis();
                for (int gx = -80; gx <= 80; gx += 80) {
                    for (int gz = -80; gz <= 80; gz += 80) {
                        server.runCommand("tp @a " + gx + " -40 " + gz);
                        context.waitTicks(40);
                    }
                }
                server.runCommand("tp @a 0 -40 0");
                waitUntil(context, c -> HeadFinderClient.store().countDiscovered() >= STRESS_COUNT, "alle " + STRESS_COUNT + " Köpfe entdeckt");
                log("Dauer bis alle entdeckt: " + (System.currentTimeMillis() - t0) / 1000.0 + " s (inkl. Ablaufen)");
                context.waitTicks(30);
                int n = context.computeOnClient(c -> HeadFinderClient.store().countDiscovered());
                check(n == STRESS_COUNT, "genau " + STRESS_COUNT + " entdeckt, war " + n);
                for (BlockPos p : positions) {
                    if (entry(context, p, HeadEntry.Kind.BLOCK) == null) throw new AssertionError("FEHLGESCHLAGEN: Kopf fehlt bei " + p.toShortString());
                }
                log("OK: jede der " + STRESS_COUNT + " Positionen ist einzeln gespeichert (UUID egal)");

                // Leistung messen: von oben, alle 200 im Blick (Reichweite 256)
                server.runCommand("tp @a 0 10 -130 facing 0 -60 0");
                context.waitTicks(40);
                context.runOnClient(c -> {
                    com.headfinder.render.HeadRenderer.maxRenderMicros = 0;
                    HeadFinderClient.scanner().requestRescan();
                });
                long[] render = new long[1];
                long[] scan = new long[1];
                int frames = 0;
                long fpsSum = 0;
                for (int k = 0; k < 10; k++) {
                    context.waitTicks(20);
                    render[0] += context.computeOnClient(c -> com.headfinder.render.HeadRenderer.lastRenderMicros);
                    scan[0] = Math.max(scan[0], context.computeOnClient(c -> HeadFinderClient.scanner().lastIntervalMicros()));
                    fpsSum += context.computeOnClient(MinecraftClient::getCurrentFps);
                    frames++;
                }
                long maxRender = context.computeOnClient(c -> com.headfinder.render.HeadRenderer.maxRenderMicros);
                int chunks = context.computeOnClient(c -> HeadFinderClient.scanner().loadedChunkCount());
                log(String.format(java.util.Locale.ROOT,
                        "LEISTUNG mit %d Köpfen: Zeichnen Ø %.2f ms (max %.2f ms) pro Bild, Intervall-Scan max %.2f ms über %d Chunks (1x pro Sekunde), Ø %d FPS",
                        STRESS_COUNT, render[0] / (double) frames / 1000.0, maxRender / 1000.0, scan[0] / 1000.0, chunks, fpsSum / frames));
                check(render[0] / frames < 5000, "Zeichnen unter 5 ms pro Bild");
                check(scan[0] < 20000, "Intervall-Scan unter 20 ms");
                context.takeScreenshot("10_200_koepfe_von_oben");

                // Rechtsklick auf 3 Köpfe → 197 offen
                server.runCommand("gamemode creative @a");
                List<BlockPos> list = new java.util.ArrayList<>(positions);
                for (int k = 1; k <= 3; k++) {
                    BlockPos p = list.get(k * 3 + 1); // am Boden liegende Köpfe
                    server.runCommand("tp @a " + (p.getX() + 0.5) + " " + (p.getY()) + " " + (p.getZ() - 2.5) + " facing " + (p.getX() + 0.5) + " " + p.getY() + " " + (p.getZ() + 0.5));
                    context.waitTicks(5);
                    context.runOnClient(c -> c.interactionManager.interactBlock(c.player, Hand.MAIN_HAND,
                            new BlockHitResult(Vec3d.ofCenter(p), Direction.NORTH, p, false)));
                    context.waitTicks(2);
                    HeadEntry e = entry(context, p, HeadEntry.Kind.BLOCK);
                    log("Klick " + k + " auf " + p.toShortString() + ": Modus " + context.computeOnClient(c -> c.interactionManager.getCurrentGameMode())
                            + ", Eintrag " + (e == null ? "fehlt" : (e.found ? "gefunden (" + e.foundReason + ")" : "offen")));
                }
                check(context.computeOnClient(c -> HeadFinderClient.store().countFound()) == 3, "3 von 200 per Rechtsklick gefunden");

                // Weit weg: Chunks werden entladen, Positionen bleiben in der Liste
                server.runCommand("forceload remove all");
                server.runCommand("gamemode spectator @a");
                server.runCommand("tp @a 2000 -40 2000");
                context.waitTicks(60);
                int loaded = context.computeOnClient(c -> HeadFinderClient.scanner().lastSkullCount());
                check(context.computeOnClient(c -> HeadFinderClient.store().countDiscovered()) == STRESS_COUNT,
                        "nach dem Wegteleportieren weiterhin 200 in der Liste (Kopf-Blöcke geladen: " + loaded + ")");
                check(context.computeOnClient(c -> HeadFinderClient.store().countFound()) == 3, "gefunden-Status bleibt");
                context.takeScreenshot("11_weit_weg_hud");
                context.setScreen(() -> new HeadListScreen(null));
                context.waitTicks(5);
                context.takeScreenshot("12_liste_200");
                context.setScreen(() -> null);
            }
        }
    }

    // ---------------------------------------------------------------- Szene

    private static String b64(String url) {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String profile(String name, String uuid, String url) {
        return "{name:\"" + name + "\"," + (uuid != null ? "id:" + uuid + "," : "")
                + "properties:[{name:\"textures\",value:\"" + b64(url) + "\"}]}";
    }

    private static String item(String profile) {
        return "{id:\"minecraft:player_head\",count:1,components:{\"minecraft:profile\":" + profile + "}}";
    }

    private static void buildScene(TestDedicatedServerContext s) {
        s.runCommand("time set noon");
        s.runCommand("gamemode creative @a");
        String eventA = profile("FindEventHead", "[I;796675297,1605646362,-1612251638,1847512745]", URL);
        String eventB = profile("FindEventHead", "[I;-985605085,-1751895339,-1510190575,1474657843]", URL);
        s.runCommand(setblock(A, "minecraft:player_head[rotation=8]", eventA));
        s.runCommand("setblock 8 -59 4 minecraft:stone");
        s.runCommand(setblock(B, "minecraft:player_wall_head[facing=north]", eventB));
        s.runCommand(setblock(C, "minecraft:player_head", profile("Irgendwer", "[I;1,2,3,4]", URL)));
        s.runCommand(setblock(D, "minecraft:player_head", profile("FindEventHead", "[I;5,6,7,8]", OTHER_URL)));
        s.runCommand(setblock(E, "minecraft:player_head", profile("Steve2", "[I;9,10,11,12]", OTHER_URL)));
        s.runCommand("summon minecraft:item_frame " + F.getX() + " " + F.getY() + " " + F.getZ() + " {Facing:1b,Fixed:1b,Item:" + item(eventA) + "}");
        s.runCommand("summon minecraft:item -2 -60 3 {PickupDelay:32767,Age:-32768,Item:" + item(eventB) + "}");
        // Wand zwischen Spieler und Köpfen (Durchsicht testen)
        s.runCommand("fill -10 -60 -10 -10 -55 12 minecraft:stone_bricks");
    }

    private static String setblock(BlockPos p, String block, String profile) {
        return "setblock " + p.getX() + " " + p.getY() + " " + p.getZ() + " " + block + "{profile:" + profile + "}";
    }

    // ---------------------------------------------------------------- Prüfungen

    private static void unitChecks() {
        // Übernahme der alten Config (Version 1.0.0 hieß "Coole Socke 69")
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("headfinder-migration");
            java.nio.file.Path oldDir = tmp.resolve("coole_socke_69");
            java.nio.file.Files.createDirectories(oldDir.resolve("found"));
            java.nio.file.Files.writeString(oldDir.resolve("found/mp_test.json"), "{\"heads\":[]}");
            java.nio.file.Path newDir = tmp.resolve("head_finder");
            check(HeadFinderClient.migrateConfigDir(oldDir, newDir), "alte Config wird übernommen");
            check(java.nio.file.Files.exists(newDir.resolve("found/mp_test.json")) && !java.nio.file.Files.exists(oldDir), "gespeicherte Köpfe liegen danach im neuen Ordner");
            java.nio.file.Files.createDirectories(oldDir);
            check(!HeadFinderClient.migrateConfigDir(oldDir, newDir), "vorhandene neue Config wird nie überschrieben");
        } catch (java.io.IOException e) {
            throw new AssertionError("Migrationstest fehlgeschlagen", e);
        }
        check(ProfileMatcher.decodeTextureHash(b64(URL)).equals("3d8c1f399f9a74ce148300cb6fa79fbc13ba44f9a52c25ce9e452b49f5b40a10"), "Base64-Textur dekodiert");
        check(ProfileMatcher.textureHash("https://textures.minecraft.net/texture/3D8C1F399F9A74CE148300CB6FA79FBC13BA44F9A52C25CE9E452B49F5B40A10")
                .equals(ProfileMatcher.textureHash(URL)), "http/https und Großschreibung egal");
        check(ProfileMatcher.parseUuid("[I; 796675297, 1605646362, -1612251638, 1847512745]") != null, "Int-Array-UUID lesbar");
        check(ProfileMatcher.parseUuid("[I; -985605085, -1751895339, -1510190575, 1474657843]") != null, "Int-Array-UUID 2 lesbar");
    }

    private static HeadEntry entry(ClientGameTestContext context, BlockPos p, HeadEntry.Kind kind) {
        return context.computeOnClient(c -> HeadFinderClient.store().get(HeadEntry.key(HeadScanner.dimension(c.world), p, kind)));
    }

    private static boolean found(ClientGameTestContext context, BlockPos p) {
        return context.computeOnClient(c -> found(p));
    }

    private static boolean found(BlockPos p) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c.world == null) return false;
        HeadEntry e = HeadFinderClient.store().get(HeadEntry.key(HeadScanner.dimension(c.world), p, HeadEntry.Kind.BLOCK));
        return e != null && e.found;
    }

    private static void waitUntil(ClientGameTestContext context, java.util.function.Predicate<MinecraftClient> cond, String what) {
        try {
            context.waitFor(cond, 200);
        } catch (RuntimeException e) {
            throw new AssertionError("Zeitüberschreitung: " + what, e);
        }
        log("OK: " + what);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FEHLGESCHLAGEN: " + what);
        log("OK: " + what);
    }

    private static void log(String message) {
        System.out.println("[HeadFinderTest] " + message);
    }
}
