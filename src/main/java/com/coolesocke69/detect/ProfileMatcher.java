package com.coolesocke69.detect;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.coolesocke69.config.ModConfig;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Prüft, ob ein Kopf-Profil zu einem der Suchprofile passt.
 *
 * 1.21.11: {@code SkullBlockEntity#getOwner()} liefert ein {@link ProfileComponent} (Mojang: ResolvableProfile),
 * das entweder statisch (mit Properties) oder dynamisch (nur Name/UUID) ist. {@link ProfileComponent#getGameProfile()}
 * liefert in beiden Fällen das (Teil-)Profil; {@link GameProfile} ist ein Record mit name()/id()/properties().
 * Die "textures"-Property ist Base64-JSON: {"textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/<hash>"}}}.
 *
 * Verglichen wird nur der Hash am Ende der URL (Kleinbuchstaben), damit http/https und Schreibweise egal sind.
 */
public final class ProfileMatcher {

    /** Vorbereitete Vergleichswerte aus der Config. */
    private record Compiled(Set<String> names, Set<String> textureHashes, Set<UUID> uuids) {
    }

    /** Ergebnis eines Treffers (für Anzeige/Log). */
    public record Match(String profileName, String textureHash) {
    }

    private static final int CACHE_LIMIT = 4096;

    /** Base64-Wert → Texture-Hash (oder "" wenn nicht lesbar); dekodieren ist teuer, Werte wiederholen sich. */
    private final Map<String, String> textureCache = new HashMap<>();
    private Compiled compiled = new Compiled(Set.of(), Set.of(), Set.of());

    /** Nach jeder Config-Änderung aufrufen. */
    public void rebuild(ModConfig cfg) {
        Set<String> names = new HashSet<>();
        Set<String> hashes = new HashSet<>();
        Set<UUID> uuids = new HashSet<>();
        for (ModConfig.SearchProfile p : cfg.profiles) {
            if (!p.enabled) continue;
            if (!p.name.isEmpty()) names.add(p.name.toLowerCase(Locale.ROOT));
            String h = textureHash(p.textureUrl);
            if (!h.isEmpty()) hashes.add(h);
            if (cfg.matchUuid) {
                for (String u : p.uuids) {
                    UUID id = parseUuid(u);
                    if (id != null) uuids.add(id);
                }
            }
        }
        compiled = new Compiled(names, hashes, uuids);
    }

    public boolean hasCriteria() {
        Compiled c = compiled;
        return !c.names.isEmpty() || !c.textureHashes.isEmpty() || !c.uuids.isEmpty();
    }

    /** Kopf-Item (player_head) mit passendem Profil? */
    public Match match(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isOf(Items.PLAYER_HEAD)) return null;
        return match(stack.get(DataComponentTypes.PROFILE));
    }

    /** Profil eines Kopf-Blocks oder -Items prüfen. Treffer, wenn Texture ODER Name (ODER optional UUID) passt. */
    public Match match(ProfileComponent component) {
        if (component == null) return null;
        Compiled c = compiled;
        GameProfile gp = component.getGameProfile();

        String name = component.getName().orElse(gp != null ? gp.name() : "");
        if (name == null) name = "";
        String hash = gp != null ? textureHashOf(gp.properties()) : "";

        if (!hash.isEmpty() && c.textureHashes.contains(hash)) return new Match(name, hash);
        if (!name.isEmpty() && c.names.contains(name.toLowerCase(Locale.ROOT))) return new Match(name, hash);
        if (!c.uuids.isEmpty() && gp != null && gp.id() != null && c.uuids.contains(gp.id())) return new Match(name, hash);
        return null;
    }

    /** Hash der Skin-Textur aus der "textures"-Property, "" wenn keine vorhanden. */
    public String textureHashOf(PropertyMap properties) {
        if (properties == null || properties.isEmpty()) return "";
        for (Property p : properties.get("textures")) {
            String v = p.value();
            if (v == null || v.isEmpty()) continue;
            String h = textureCache.get(v);
            if (h == null) {
                h = decodeTextureHash(v);
                if (textureCache.size() > CACHE_LIMIT) textureCache.clear();
                textureCache.put(v, h);
            }
            if (!h.isEmpty()) return h;
        }
        return "";
    }

    // ---------------------------------------------------------------- Hilfen (statisch, testbar)

    /** Base64-Property → Hash der SKIN-URL ("" bei Fehler). */
    public static String decodeTextureHash(String base64) {
        try {
            String json = new String(Base64.getMimeDecoder().decode(base64.trim()), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject textures = root.getAsJsonObject("textures");
            if (textures == null) return "";
            JsonObject skin = textures.getAsJsonObject("SKIN");
            if (skin == null) return "";
            JsonElement url = skin.get("url");
            return url == null ? "" : textureHash(url.getAsString());
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Volle URL (http/https, mit/ohne Host) oder reiner Hash → Hash in Kleinbuchstaben. */
    public static String textureHash(String urlOrHash) {
        if (urlOrHash == null) return "";
        String s = urlOrHash.trim();
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        return s.toLowerCase(Locale.ROOT);
    }

    /** "xxxxxxxx-xxxx-…" oder Int-Array "[I; a, b, c, d]" → UUID, sonst null. */
    public static UUID parseUuid(String s) {
        if (s == null) return null;
        String t = s.trim();
        try {
            if (t.startsWith("[")) {
                String inner = t.substring(1, t.endsWith("]") ? t.length() - 1 : t.length());
                if (inner.toUpperCase(Locale.ROOT).startsWith("I;")) inner = inner.substring(2);
                String[] parts = inner.split(",");
                if (parts.length != 4) return null;
                long[] v = new long[4];
                for (int i = 0; i < 4; i++) v[i] = Integer.parseInt(parts[i].trim()) & 0xFFFFFFFFL;
                return new UUID(v[0] << 32 | v[1], v[2] << 32 | v[3]);
            }
            if (t.length() == 32 && !t.contains("-")) {
                t = t.substring(0, 8) + "-" + t.substring(8, 12) + "-" + t.substring(12, 16) + "-" + t.substring(16, 20) + "-" + t.substring(20);
            }
            return UUID.fromString(t);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Für Befehle: lesbare Beschreibung der aktiven Kriterien. */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        Compiled c = compiled;
        for (String n : c.names) out.add("Name: " + n);
        for (String h : c.textureHashes) out.add("Textur: …" + h.substring(Math.max(0, h.length() - 12)));
        for (UUID u : c.uuids) out.add("UUID: " + u);
        return out;
    }
}
