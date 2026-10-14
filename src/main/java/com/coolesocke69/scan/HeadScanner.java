package com.coolesocke69.scan;

import com.coolesocke69.CooleSocke69Client;
import com.coolesocke69.config.ModConfig;
import com.coolesocke69.data.HeadEntry;
import com.coolesocke69.data.HeadStore;
import com.coolesocke69.detect.ProfileMatcher;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SkullBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sucht Event-Köpfe in den Daten, die der Client ohnehin hat:
 *
 *  - Beim Laden eines Chunks werden nur dessen Block-Entities geprüft (kein Durchlaufen aller Blöcke).
 *  - Alle {@code rescanIntervalTicks} werden die Block-Entities aller geladenen Chunks erneut geprüft
 *    (fängt nachträglich gesetzte Köpfe ab) – das sind nur wenige Map-Einträge pro Chunk.
 *  - Im gleichen Intervall: Items am Boden / in Item-Frames sowie die "verschwunden"-Prüfung.
 *
 * Ergebnisse landen im {@link HeadStore}; gerendert wird nur aus dem Cache.
 */
public final class HeadScanner {

    /** Höchstens so viele frisch geladene Chunks pro Tick prüfen (Lastspitzen beim Teleport vermeiden). */
    private static final int CHUNKS_PER_TICK = 128;

    private final ProfileMatcher matcher;
    private final HeadStore store;

    private ClientWorld trackedWorld;
    private final LongOpenHashSet loadedChunks = new LongOpenHashSet();
    private final LongArrayFIFOQueue pending = new LongArrayFIFOQueue();
    private int tickCounter;
    /** Statistik für /headfinder status. */
    private int lastSkullCount;
    /** Dauer des letzten kompletten Intervall-Scans (Mikrosekunden). */
    private long lastIntervalMicros;

    public HeadScanner(ProfileMatcher matcher, HeadStore store) {
        this.matcher = matcher;
        this.store = store;
    }

    // ---------------------------------------------------------------- Chunk-Ereignisse

    public void onChunkLoad(ClientWorld world, WorldChunk chunk) {
        syncWorld(world);
        long key = chunk.getPos().toLong();
        loadedChunks.add(key);
        pending.enqueue(key);
    }

    public void onChunkUnload(ClientWorld world, WorldChunk chunk) {
        if (world == trackedWorld) loadedChunks.remove(chunk.getPos().toLong());
    }

    /** Neue Welt (Server/Dimension gewechselt) → Chunk-Liste zurücksetzen. */
    private void syncWorld(ClientWorld world) {
        if (world != trackedWorld) {
            trackedWorld = world;
            loadedChunks.clear();
            pending.clear();
            tickCounter = 0;
        }
    }

    /** Erzwingt einen kompletten Durchlauf im nächsten Tick. */
    public void requestRescan() {
        tickCounter = Integer.MAX_VALUE - 1;
    }

    public int loadedChunkCount() {
        return loadedChunks.size();
    }

    public long lastIntervalMicros() {
        return lastIntervalMicros;
    }

    public int lastSkullCount() {
        return lastSkullCount;
    }

    // ---------------------------------------------------------------- Tick

    public void tick(MinecraftClient mc) {
        ClientWorld world = mc.world;
        if (world == null || !store.isOpen()) {
            if (world == null) syncWorld(null);
            return;
        }
        syncWorld(world);
        ModConfig cfg = CooleSocke69Client.config();
        String dim = dimension(world);

        // 1) frisch geladene Chunks
        int budget = CHUNKS_PER_TICK;
        while (!pending.isEmpty() && budget-- > 0) {
            long key = pending.dequeueLong();
            if (!loadedChunks.contains(key)) continue;
            WorldChunk chunk = loadedChunk(world, ChunkPos.getPackedX(key), ChunkPos.getPackedZ(key));
            if (chunk != null) scanChunk(world, chunk, dim);
        }

        // 2) Intervall: alles erneut prüfen
        if (++tickCounter >= cfg.rescanIntervalTicks) {
            tickCounter = 0;
            long t0 = System.nanoTime();
            int skulls = 0;
            LongIterator it = loadedChunks.iterator();
            while (it.hasNext()) {
                long key = it.nextLong();
                WorldChunk chunk = loadedChunk(world, ChunkPos.getPackedX(key), ChunkPos.getPackedZ(key));
                if (chunk != null) skulls += scanChunk(world, chunk, dim);
                else it.remove(); // ohne Unload-Ereignis entladen (z. B. nach Teleport)
            }
            lastSkullCount = skulls;
            scanEntities(world, dim, cfg);
            if (cfg.foundOnDisappear) checkDisappeared(world, dim);
            lastIntervalMicros = (System.nanoTime() - t0) / 1000;
        }
    }

    /** Prüft die Block-Entities eines Chunks. Gibt die Zahl der Kopf-Block-Entities zurück. */
    private int scanChunk(ClientWorld world, WorldChunk chunk, String dim) {
        int skulls = 0;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (!(be instanceof SkullBlockEntity skull)) continue;
            skulls++;
            ProfileMatcher.Match m = matcher.match(skull.getOwner());
            if (m != null && store.discover(dim, skull.getPos(), HeadEntry.Kind.BLOCK, m.profileName())) {
                announceNew(skull.getPos());
            }
        }
        return skulls;
    }

    /** Köpfe als Item am Boden (nur live) und in Item-Frames (gespeichert). */
    private void scanEntities(ClientWorld world, String dim, ModConfig cfg) {
        if (!cfg.detectItems) {
            store.replaceItems(List.of());
            return;
        }
        List<HeadEntry> items = new ArrayList<>();
        Map<BlockPos, ItemFrameEntity> frames = new HashMap<>();
        for (Entity e : world.getEntities()) {
            if (e instanceof ItemEntity item) {
                ProfileMatcher.Match m = matcher.match(item.getStack());
                if (m != null) {
                    HeadEntry h = new HeadEntry(dim, item.getBlockPos(), HeadEntry.Kind.ITEM, m.profileName());
                    h.entityId = item.getId();
                    h.livePos = item.getEntityPos();
                    items.add(h);
                }
            } else if (e instanceof ItemFrameEntity frame) {
                frames.put(frame.getBlockPos(), frame);
                ProfileMatcher.Match m = matcher.match(frame.getHeldItemStack());
                if (m != null && store.discover(dim, frame.getBlockPos(), HeadEntry.Kind.FRAME, m.profileName())) {
                    announceNew(frame.getBlockPos());
                }
            }
        }
        store.replaceItems(items);

        // Rahmen, der noch existiert, aber keinen passenden Kopf mehr hält → gefunden
        if (cfg.foundOnDisappear) {
            for (HeadEntry h : store.inDimension(dim)) {
                if (h.kind != HeadEntry.Kind.FRAME || h.found) continue;
                ItemFrameEntity frame = frames.get(h.pos());
                if (frame != null && matcher.match(frame.getHeldItemStack()) == null) {
                    store.setFound(h, true, "aus Rahmen entfernt");
                }
            }
        }
    }

    /**
     * Kopf-Block in einem geladenen Chunk nicht mehr vorhanden (oder durch einen anderen Kopf ersetzt)
     * → gilt als eingesammelt.
     */
    private void checkDisappeared(ClientWorld world, String dim) {
        for (HeadEntry h : store.inDimension(dim)) {
            if (h.kind != HeadEntry.Kind.BLOCK || h.found) continue;
            if (!loadedChunks.contains(ChunkPos.toLong(h.x >> 4, h.z >> 4))) continue;
            // Nur echte Chunk-Daten zählen: world.isChunkLoaded() kann für entladene Chunks noch true sein,
            // world.getBlockState() liefert dann void_air aus einem leeren Platzhalter-Chunk.
            WorldChunk chunk = loadedChunk(world, h.x >> 4, h.z >> 4);
            if (chunk == null) continue;
            BlockPos pos = h.pos();
            BlockState state = chunk.getBlockState(pos);
            if (!state.isOf(Blocks.PLAYER_HEAD) && !state.isOf(Blocks.PLAYER_WALL_HEAD)) {
                CooleSocke69Client.LOGGER.info("Kopf bei {} ist verschwunden (jetzt {}) – gilt als gefunden", pos.toShortString(), state.getBlock());
                store.setFound(h, true, "verschwunden");
                continue;
            }
            // Noch ein Kopf, aber mit anderem (bekanntem) Profil → ebenfalls eingesammelt
            if (chunk.getBlockEntity(pos) instanceof SkullBlockEntity skull && skull.getOwner() != null
                    && matcher.match(skull.getOwner()) == null) {
                store.setFound(h, true, "ersetzt");
            }
        }
    }

    private void announceNew(BlockPos pos) {
        if (!CooleSocke69Client.config().notifyNewHeads) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud != null) {
            mc.inGameHud.setOverlayMessage(Text.literal("Neuer Event-Kopf entdeckt: " + pos.getX() + " " + pos.getY() + " " + pos.getZ())
                    .formatted(Formatting.GOLD), false);
        }
    }

    /**
     * Der echte, vollständig geladene Chunk – oder null. Für nicht (mehr) geladene Positionen gibt der Client
     * sonst einen leeren Platzhalter zurück, dessen Blöcke alle void_air sind.
     */
    public static WorldChunk loadedChunk(ClientWorld world, int cx, int cz) {
        WorldChunk c = world.getChunkManager().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (c == null || c instanceof EmptyChunk) return null;
        ChunkPos p = c.getPos();
        return p.x == cx && p.z == cz ? c : null;
    }

    public static String dimension(ClientWorld world) {
        return world.getRegistryKey().getValue().toString();
    }
}
