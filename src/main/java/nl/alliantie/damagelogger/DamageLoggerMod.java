package nl.alliantie.damagelogger;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.advancement.AdvancementProgress;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.Method;


public class DamageLoggerMod implements ModInitializer {

    // Force hardcore players out of the "Game Over" screen by respawning them server-side next tick
    private static final Set<UUID> PENDING_FORCE_RESPAWN = ConcurrentHashMap.newKeySet();

    // ---- Damage anti-spam ----
    private static final long DEFAULT_COOLDOWN_MS = 300;
    private static final long DOT_COOLDOWN_MS = 1500;
    private static final Map<String, Long> LAST_LOG_TIME = new ConcurrentHashMap<>();

    // ---- One-time "first real death" guard ----
    private static final AtomicBoolean FIRST_REAL_DEATH_HANDLED = new AtomicBoolean(false);

    // ---- Run state ----
    private static volatile long runStartMs = -1;
    private static volatile boolean runFailed = false;     // death-fail
    private static volatile boolean runCompleted = false;  // dragon success
    private static volatile long runEndMs = -1;

    // ---- Pin players after failure ----
    private static volatile long pinUntilMs = -1;
    private static volatile RegistryKey<World> deathWorldKey = null;
    private static volatile double deathX = 0, deathY = 0, deathZ = 0;

    private static final double PIN_RADIUS = 0.25;
    private static final int PIN_TELEPORT_INTERVAL_TICKS = 2;
    private static int pinTickCounter = 0;

    // ---- Damage totals (leaderboard) ----
    // UUID -> total damage taken (raw float amount summed)
    private static final Map<UUID, Float> DAMAGE_TAKEN = new ConcurrentHashMap<>();
    private static final Map<UUID, String> LAST_NAME = new ConcurrentHashMap<>();

    // ---- Splits (milestones) ----
    private static final Map<Milestone, SplitRecord> SPLITS = new ConcurrentHashMap<>();
    private static int secondTickCounter = 0;

    // ---- Sidebar via commands ----
    private static final String OBJ_NAME = "alliance_splits";
    private static final Map<Integer, String> SIDEBAR_LINES = new ConcurrentHashMap<>();
    private static volatile boolean sidebarInitialized = false;

    // ---- Persistent splits storage ----
    private static final Path SPLITS_DIR = Paths.get("/opt/minecraft/server/splits");
    private static final Path RUNS_FILE = SPLITS_DIR.resolve("runs.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // best time per milestone (e.g. "IRON" -> ms)
    private static final Map<String, Long> BEST_SPLIT_MS = new ConcurrentHashMap<>();
    // which run it came from
    private static final Map<String, String> BEST_SPLIT_RUN_ID = new ConcurrentHashMap<>();
    private static volatile boolean storageLoaded = false;

    private enum Milestone {
        IRON("minecraft:story/smelt_iron", "IRON"),
        NETHER("minecraft:story/enter_the_nether", "NETHER"),
        FORTRESS("minecraft:nether/find_fortress", "FORT"),
        BLAZE_ROD("minecraft:nether/obtain_blaze_rod", "BLAZE"),
        END("minecraft:story/enter_the_end", "END"),
        DRAGON("minecraft:end/kill_dragon", "DRAGON");

        final Identifier advancementId;
        final String shortLabel;

        Milestone(String advancementId, String shortLabel) {
            this.advancementId = Identifier.of(advancementId);
            this.shortLabel = shortLabel;
        }
    }

    private static final class SplitRecord {
        final UUID playerId;
        final String playerName;
        final long timeMs;

        SplitRecord(UUID playerId, String playerName, long timeMs) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.timeMs = timeMs;
        }
    }

    @Override
    public void onInitialize() {

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity p = handler.getPlayer();
            LAST_NAME.put(p.getUuid(), p.getName().getString());

            if (!storageLoaded) {
                loadStorage(server);
            }

            runCommand(server, "gamerule doImmediateRespawn true");

            if (runStartMs < 0) {
                startNewRunNow();
            }

            ensureSidebar(server);
            renderSidebar(server);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {

            // Force respawn pending players (hardcore Game Over screen workaround)
            if (!PENDING_FORCE_RESPAWN.isEmpty()) {
                for (UUID id : new ArrayList<>(PENDING_FORCE_RESPAWN)) {
                    ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
                    if (p != null) {
                        tryForceRespawn(server, p);

                        // After respawn, force spectator + teleport via commands (reliable)
                        if (runFailed) {
                            forceSpectatorAndTeleportPlayer(server, p);
                        }

                        // ❌ Removed: duplicate changeGameMode + API teleport
                        // (forceSpectatorAndTeleportPlayer already does this reliably)
                    }
                    PENDING_FORCE_RESPAWN.remove(id);
                }
            }

            // Pinning elke tick (alleen actief bij fail + binnen 5s window)
            if (runFailed && pinUntilMs > 0 && System.currentTimeMillis() <= pinUntilMs) {
                enforcePin(server);
            }

            secondTickCounter++;
            if (secondTickCounter >= 20) {
                secondTickCounter = 0;

                // Actionbar timer
                if (runStartMs >= 0) {
                    long now = System.currentTimeMillis();
                    long elapsed = (runFailed || runCompleted) ? (runEndMs - runStartMs) : (now - runStartMs);
                    if (elapsed < 0) elapsed = 0;

                    String timeStr = formatDuration(elapsed);

                    MutableText bar;
                    if (runCompleted) {
                        bar = Text.empty()
                                .append(Text.literal("RUN COMPLETED ").formatted(Formatting.GREEN, Formatting.BOLD))
                                .append(Text.literal("— ").formatted(Formatting.DARK_GRAY))
                                .append(Text.literal(timeStr).formatted(Formatting.GOLD));
                    } else if (runFailed) {
                        bar = Text.empty()
                                .append(Text.literal("RUN FAILED ").formatted(Formatting.DARK_RED, Formatting.BOLD))
                                .append(Text.literal("— ").formatted(Formatting.DARK_GRAY))
                                .append(Text.literal(timeStr).formatted(Formatting.GOLD));
                    } else {
                        bar = Text.literal(timeStr).formatted(Formatting.GOLD);
                    }

                    for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                        p.sendMessage(bar, true);
                    }
                }

                // Splits check only while running
                if (runStartMs >= 0 && !runFailed && !runCompleted) {
                    checkSplits(server);
                }

                // Sidebar refresh
                if (runStartMs >= 0) {
                    ensureSidebar(server);
                    renderSidebar(server);
                }
            }
        });

        // Damage logging + totals
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((LivingEntity entity, DamageSource source, float amount) -> {
            if (!(entity instanceof ServerPlayerEntity player)) return true;
            if (!(entity.getEntityWorld() instanceof ServerWorld world)) return true;
            if (runFailed || runCompleted) return true;

            String type = safeLower(source.getName());
            if (type.contains("generickill")) return true; // ignore /kill noise

            // totals
            DAMAGE_TAKEN.merge(player.getUuid(), amount, Float::sum);
            LAST_NAME.put(player.getUuid(), player.getName().getString());

            logDamageIfNeeded(world.getServer(), player, source, amount);
            return true;
        });

        // End run on first real death (unless already completed)
        ServerLivingEntityEvents.AFTER_DEATH.register((LivingEntity entity, DamageSource source) -> {
            if (!(entity instanceof ServerPlayerEntity player)) return;
            if (!(entity.getEntityWorld() instanceof ServerWorld world)) return;

            if (runCompleted) return;

            String type = safeLower(describeDamageType(source));
            if (type.contains("generickill")) return;

            if (!FIRST_REAL_DEATH_HANDLED.compareAndSet(false, true)) return;

            MinecraftServer server = world.getServer();
            runCommand(server, "gamerule doImmediateRespawn true");

            runFailed = true;
            runCompleted = false;
            runEndMs = System.currentTimeMillis();
            pinUntilMs = runEndMs + 5000;

            deathWorldKey = world.getRegistryKey();
            deathX = player.getX();
            deathY = player.getY();
            deathZ = player.getZ();

            // queue forced respawn for the dead player (hardcore Game Over fix)
            PENDING_FORCE_RESPAWN.add(player.getUuid());

            // Direct force spectator + teleport everyone (command-based, reliable)
            forceSpectatorAndTeleportAll(server);

            // NOW broadcast (after spectator+tp)
            broadcastEndRunFailed(server, player, world, source, describeDamageType(source));


            broadcastEndRunFailed(server, player, world, source, describeDamageType(source));
            broadcastDamageLeaderboard(server);
            saveRunToStorage(server, "FAILED", player.getName().getString());

            ensureSidebar(server);
            renderSidebar(server);
        });
    }

    // ------------------------------------------------------------
    // RUN RESET (start new run on first join)
    // ------------------------------------------------------------

    private static void startNewRunNow() {
        runStartMs = System.currentTimeMillis();
        runFailed = false;
        runCompleted = false;
        runEndMs = -1;

        FIRST_REAL_DEATH_HANDLED.set(false);

        pinUntilMs = -1;
        deathWorldKey = null;
        deathX = deathY = deathZ = 0;

        SPLITS.clear();
        SIDEBAR_LINES.clear();

        DAMAGE_TAKEN.clear();
    }

    // ------------------------------------------------------------
    // CHAT HELPERS
    // ------------------------------------------------------------

    private static void chat(MinecraftServer server, Text msg) {
        server.getPlayerManager().broadcast(msg, false);
    }

    // ------------------------------------------------------------
    // STORAGE (runs.json)
    // ------------------------------------------------------------

    private static void loadStorage(MinecraftServer server) {
        storageLoaded = true;
        BEST_SPLIT_MS.clear();
        BEST_SPLIT_RUN_ID.clear();

        try {
            Files.createDirectories(SPLITS_DIR);

            if (!Files.exists(RUNS_FILE)) {
                JsonObject root = new JsonObject();
                root.addProperty("version", 1);
                root.add("runs", new JsonArray());
                root.add("bestSplits", new JsonObject());
                writeJson(root);
                return;
            }

            try (BufferedReader br = Files.newBufferedReader(RUNS_FILE, StandardCharsets.UTF_8)) {
                JsonElement el = JsonParser.parseReader(br);
                if (el == null || !el.isJsonObject()) return;
                JsonObject root = el.getAsJsonObject();

                JsonObject best = root.has("bestSplits") && root.get("bestSplits").isJsonObject()
                        ? root.getAsJsonObject("bestSplits")
                        : new JsonObject();

                for (String k : best.keySet()) {
                    JsonObject b = best.getAsJsonObject(k);
                    if (b != null && b.has("timeMs")) {
                        BEST_SPLIT_MS.put(k, b.get("timeMs").getAsLong());
                        if (b.has("runId")) {
                            BEST_SPLIT_RUN_ID.put(k, b.get("runId").getAsString());
                        }
                    }
                }
            }
        } catch (Throwable t) {
            chat(server, Text.literal("[Splits] Failed to load runs.json: " + t.getMessage()).formatted(Formatting.RED));
        }
    }

    private static void saveRunToStorage(MinecraftServer server, String endReason, String endPlayerName) {
        try {
            Files.createDirectories(SPLITS_DIR);

            JsonObject root = readJsonOrCreate();
            JsonArray runs = root.has("runs") && root.get("runs").isJsonArray()
                    ? root.getAsJsonArray("runs")
                    : new JsonArray();

            String runId = makeRunId(runStartMs, runEndMs);

            JsonObject run = new JsonObject();
            run.addProperty("runId", runId);
            run.addProperty("startMs", runStartMs);
            run.addProperty("endMs", runEndMs);
            run.addProperty("durationMs", Math.max(0, runEndMs - runStartMs));
            run.addProperty("failed", runFailed);
            run.addProperty("completed", runCompleted);
            run.addProperty("endReason", endReason);
            if (endPlayerName != null) run.addProperty("endPlayer", endPlayerName);

            JsonObject splitsObj = new JsonObject();
            for (Milestone m : Milestone.values()) {
                SplitRecord r = SPLITS.get(m);
                if (r == null) continue;
                JsonObject s = new JsonObject();
                s.addProperty("timeMs", r.timeMs);
                s.addProperty("player", r.playerName);
                s.addProperty("playerUuid", r.playerId.toString());
                splitsObj.add(m.shortLabel, s);
            }
            run.add("splits", splitsObj);

            runs.add(run);
            root.add("runs", runs);

            // Fix BEST_SPLIT_RUN_ID == "current"
            for (Map.Entry<String, String> e : new HashMap<>(BEST_SPLIT_RUN_ID).entrySet()) {
                if ("current".equals(e.getValue())) {
                    BEST_SPLIT_RUN_ID.put(e.getKey(), runId);
                }
            }

            JsonObject best = new JsonObject();
            for (Map.Entry<String, Long> e : BEST_SPLIT_MS.entrySet()) {
                JsonObject b = new JsonObject();
                b.addProperty("timeMs", e.getValue());
                String rid = BEST_SPLIT_RUN_ID.get(e.getKey());
                if (rid != null) b.addProperty("runId", rid);
                best.add(e.getKey(), b);
            }
            root.add("bestSplits", best);
            root.addProperty("version", 1);

            writeJson(root);

            // subtle confirmation
            chat(server, Text.literal("[Splits] Saved run " + runId).formatted(Formatting.DARK_GRAY));
        } catch (Throwable t) {
            chat(server, Text.literal("[Splits] Failed to save run: " + t.getMessage()).formatted(Formatting.RED));
        }
    }

    private static JsonObject readJsonOrCreate() throws IOException {
        if (!Files.exists(RUNS_FILE)) {
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.add("runs", new JsonArray());
            root.add("bestSplits", new JsonObject());
            return root;
        }
        try (BufferedReader br = Files.newBufferedReader(RUNS_FILE, StandardCharsets.UTF_8)) {
            JsonElement el = JsonParser.parseReader(br);
            if (el != null && el.isJsonObject()) return el.getAsJsonObject();
        } catch (Throwable ignored) {}
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("runs", new JsonArray());
        root.add("bestSplits", new JsonObject());
        return root;
    }

    private static void writeJson(JsonObject root) throws IOException {
        Path tmp = RUNS_FILE.resolveSibling("runs.json.tmp");
        try (BufferedWriter bw = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            bw.write(GSON.toJson(root));
            bw.flush();
        }
        try {
            Files.move(tmp, RUNS_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, RUNS_FILE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String makeRunId(long startMs, long endMs) {
        return "run-" + startMs + "-" + endMs;
    }

    private static void tryForceRespawn(MinecraftServer server, ServerPlayerEntity player) {
        try {
            Object pm = server.getPlayerManager();

            // Find a respawnPlayer(...) method that matches our mappings/runtime
            for (Method m : pm.getClass().getMethods()) {
                if (!m.getName().equals("respawnPlayer")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length < 2) continue;
                if (!ServerPlayerEntity.class.isAssignableFrom(p[0])) continue;

                // Common Yarn signatures look like:
                // respawnPlayer(ServerPlayerEntity, boolean)
                // respawnPlayer(ServerPlayerEntity, boolean, RemovalReason)
                // respawnPlayer(ServerPlayerEntity, boolean, Entity.RemovalReason, ...)
                if (p[1] == boolean.class || p[1] == Boolean.class) {
                    Object[] args = new Object[p.length];
                    args[0] = player;
                    args[1] = Boolean.TRUE; // keep inventory / or "alive-ish" depending on version; true is safest here

                    // fill remaining params with null/false defaults
                    for (int i = 2; i < p.length; i++) {
                        if (p[i] == boolean.class) args[i] = Boolean.FALSE;
                        else args[i] = null;
                    }

                    m.invoke(pm, args);
                    return;
                }
            }
        } catch (Throwable ignored) {
            // If reflection fails, we just fall back to manual "Spectate World" click.
        }
    }

    // ------------------------------------------------------------
    // DAMAGE LOGGING (with colors)
    // ------------------------------------------------------------

    private static void logDamageIfNeeded(MinecraftServer server, ServerPlayerEntity player, DamageSource source, float amount) {
        String key = player.getUuidAsString() + "|" + source.getName();
        long now = System.currentTimeMillis();

        long cooldown = DEFAULT_COOLDOWN_MS;

        String srcName = safeLower(source.getName());
        if (srcName.contains("fire") || srcName.contains("lava") || srcName.contains("wither")
                || srcName.contains("poison") || srcName.contains("starve") || srcName.contains("cactus")) {
            cooldown = DOT_COOLDOWN_MS;
        }

        Long last = LAST_LOG_TIME.get(key);
        if (last != null && (now - last) < cooldown) return;
        LAST_LOG_TIME.put(key, now);

        String attacker = resolveAttacker(source);
        String cause = describeCause(source);

        float hp = player.getHealth();
        float max = player.getMaxHealth();

        // [Damage] <NAME> took <DMG> from <attacker> (<cause>) | HP: <hp>/<max>
        MutableText msg = Text.empty()
                .append(Text.literal("[Damage] ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(player.getName().getString()).formatted(Formatting.WHITE))
                .append(Text.literal(" took ").formatted(Formatting.GRAY))
                .append(Text.literal(String.format(Locale.ROOT, "%.1f", amount)).formatted(Formatting.RED, Formatting.BOLD))
                .append(Text.literal(" ").formatted(Formatting.GRAY))
                .append(Text.literal("♥").formatted(Formatting.RED, Formatting.BOLD))
                .append(Text.literal(" from ").formatted(Formatting.GRAY))
                .append(Text.literal(attacker).formatted(Formatting.GRAY))
                .append(Text.literal(" (").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(cause).formatted(Formatting.DARK_GRAY))
                .append(Text.literal(")").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(" | HP: ").formatted(Formatting.GRAY))
                .append(Text.literal(String.format(Locale.ROOT, "%.1f/%.1f", hp, max)).formatted(Formatting.GRAY));

        chat(server, msg);
    }

    private static String resolveAttacker(DamageSource source) {
        Entity attacker = source.getAttacker();
        if (attacker == null) return "environment";

        if (attacker instanceof ServerPlayerEntity p) {
            return "player:" + p.getName().getString();
        }

        return attacker.getType().toString();
    }

    // ------------------------------------------------------------
    // DAMAGE LEADERBOARD (most damage taken this run)
    // ------------------------------------------------------------

    private static void broadcastDamageLeaderboard(MinecraftServer server) {
        if (DAMAGE_TAKEN.isEmpty()) return;

        List<Map.Entry<UUID, Float>> list = new ArrayList<>(DAMAGE_TAKEN.entrySet());
        list.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));

        chat(server, Text.literal(" ").formatted(Formatting.DARK_GRAY));
        chat(server, Text.literal("═══ Most damage taken (this run) ═══").formatted(Formatting.DARK_AQUA, Formatting.BOLD));

        int n = Math.min(5, list.size());
        for (int i = 0; i < n; i++) {
            UUID id = list.get(i).getKey();
            float dmg = list.get(i).getValue();
            String name = LAST_NAME.getOrDefault(id, id.toString().substring(0, 8));

            MutableText line = Text.empty()
                    .append(Text.literal("#" + (i + 1) + " ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal(name).formatted(Formatting.WHITE))
                    .append(Text.literal(" — ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal(String.format(Locale.ROOT, "%.1f", dmg)).formatted(Formatting.RED, Formatting.BOLD))
                    .append(Text.literal(" ♥").formatted(Formatting.RED, Formatting.BOLD));

            chat(server, line);
        }

        chat(server, Text.literal("══════════════════════════════════").formatted(Formatting.DARK_AQUA));
        chat(server, Text.literal(" ").formatted(Formatting.DARK_GRAY));
    }

    // ------------------------------------------------------------
    // PINNING (only for failed runs)
    // ------------------------------------------------------------

    private static void enforcePin(MinecraftServer server) {
        if (deathWorldKey == null) return;

        pinTickCounter++;
        if (pinTickCounter < PIN_TELEPORT_INTERVAL_TICKS) return;
        pinTickCounter = 0;

        ServerWorld targetWorld = server.getWorld(deathWorldKey);
        if (targetWorld == null) return;

        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p.getEntityWorld() != targetWorld) {
                tryTeleport(p, targetWorld, deathX, deathY, deathZ);
                continue;
            }

            double dx = p.getX() - deathX;
            double dy = p.getY() - deathY;
            double dz = p.getZ() - deathZ;

            if ((dx * dx + dy * dy + dz * dz) > (PIN_RADIUS * PIN_RADIUS)) {
                tryTeleport(p, targetWorld, deathX, deathY, deathZ);
            } else {
                try {
                    p.setVelocity(0, 0, 0);
                    p.velocityModified = true;
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void tryTeleport(ServerPlayerEntity p, ServerWorld world, double x, double y, double z) {
        try {
            p.teleport(world, x, y, z,
                    EnumSet.noneOf(net.minecraft.network.packet.s2c.play.PositionFlag.class),
                    p.getYaw(), p.getPitch(), true);
            p.setVelocity(0, 0, 0);
            p.velocityModified = true;
        } catch (Throwable ignored) {
            try {
                p.teleport(x, y, z, false);
            } catch (Throwable ignored2) {}
        }
    }

    // ------------------------------------------------------------
    // SPLITS (+ always announce split + PB compare from JSON + end on DRAGON)
    // ------------------------------------------------------------

    private static void checkSplits(MinecraftServer server) {
        if (!storageLoaded) loadStorage(server);

        for (Milestone m : Milestone.values()) {
            if (SPLITS.containsKey(m)) continue;

            AdvancementEntry adv = server.getAdvancementLoader().get(m.advancementId);
            if (adv == null) continue;

            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                AdvancementProgress prog = p.getAdvancementTracker().getProgress(adv);
                if (prog != null && prog.isDone()) {

                    long t = System.currentTimeMillis() - runStartMs;
                    if (t < 0) t = 0;

                    SplitRecord rec = new SplitRecord(p.getUuid(), p.getName().getString(), t);
                    SplitRecord prev = SPLITS.putIfAbsent(m, rec);

                    if (prev == null) {
                        // ALWAYS announce the split (now includes +/- vs best)
                        broadcastSplit(server, m.shortLabel, rec.timeMs, rec.playerName);

                        // AND maybe PB
                        maybeAnnouncePB(server, m.shortLabel, rec.timeMs, rec.playerName);

                        // End run on DRAGON
                        if (m == Milestone.DRAGON && !runFailed && !runCompleted) {
                            completeRun(server, rec.playerName);
                        }
                    }
                    break;
                }
            }
        }
    }

    private static void broadcastSplit(MinecraftServer server, String milestoneKey, long timeMs, String playerName) {
        if (!storageLoaded) loadStorage(server);

        Long best = BEST_SPLIT_MS.get(milestoneKey);

        MutableText msg = Text.empty()
                .append(Text.literal("⏱ SPLIT ").formatted(Formatting.AQUA, Formatting.BOLD))
                .append(Text.literal(milestoneKey).formatted(Formatting.WHITE, Formatting.BOLD))
                .append(Text.literal(": ").formatted(Formatting.GRAY))
                .append(Text.literal(formatDuration(timeMs)).formatted(Formatting.GOLD, Formatting.BOLD));

        // delta vs best (show slower too)
        if (best == null) {
            msg.append(Text.literal(" (first record)").formatted(Formatting.DARK_GRAY));
        } else {
            long diffMs = timeMs - best; // + = slower, - = faster
            String sign = diffMs >= 0 ? "+" : "-";
            long abs = Math.abs(diffMs);

            Formatting col = diffMs <= 0 ? Formatting.GREEN : Formatting.RED;

            msg.append(Text.literal(" (").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal(sign + formatDuration(abs)).formatted(col, Formatting.BOLD))
                    .append(Text.literal(")").formatted(Formatting.DARK_GRAY));
        }

        msg.append(Text.literal(" — ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(playerName).formatted(Formatting.WHITE));

        chat(server, msg);
    }


    private static void maybeAnnouncePB(MinecraftServer server, String milestoneKey, long newTimeMs, String playerName) {
        Long old = BEST_SPLIT_MS.get(milestoneKey);

        if (old == null || newTimeMs < old) {
            long diff = (old == null) ? 0 : (old - newTimeMs);

            BEST_SPLIT_MS.put(milestoneKey, newTimeMs);
            BEST_SPLIT_RUN_ID.put(milestoneKey, "current");

            MutableText msg = Text.empty()
                    .append(Text.literal("🏁 NEW PB ").formatted(Formatting.GOLD, Formatting.BOLD))
                    .append(Text.literal(milestoneKey).formatted(Formatting.WHITE, Formatting.BOLD))
                    .append(Text.literal(": ").formatted(Formatting.GRAY))
                    .append(Text.literal(formatDuration(newTimeMs)).formatted(Formatting.AQUA, Formatting.BOLD));

            if (old != null) {
                msg.append(Text.literal(" (").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("-" + formatDuration(diff)).formatted(Formatting.GREEN, Formatting.BOLD))
                        .append(Text.literal(")").formatted(Formatting.DARK_GRAY));
            } else {
                msg.append(Text.literal(" (first record)").formatted(Formatting.DARK_GRAY));
            }

            msg.append(Text.literal(" — ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal(playerName).formatted(Formatting.WHITE));

            chat(server, msg);
        }
    }

    private static void completeRun(MinecraftServer server, String winnerName) {
        runCompleted = true;
        runFailed = false;
        runEndMs = System.currentTimeMillis();
        pinUntilMs = -1;

        long elapsed = Math.max(0, runEndMs - runStartMs);

        chat(server, Text.literal(" ").formatted(Formatting.DARK_GRAY));
        chat(server, Text.literal("══════════════════════════════").formatted(Formatting.GREEN));
        chat(server, Text.empty()
                .append(Text.literal("🏆 RUN COMPLETED! ").formatted(Formatting.GREEN, Formatting.BOLD))
                .append(Text.literal("Time: ").formatted(Formatting.GRAY))
                .append(Text.literal(formatDuration(elapsed)).formatted(Formatting.GOLD, Formatting.BOLD))
                .append(Text.literal(" — by ").formatted(Formatting.GRAY))
                .append(Text.literal(winnerName != null ? winnerName : "unknown").formatted(Formatting.WHITE, Formatting.BOLD)));
        chat(server, Text.literal("══════════════════════════════").formatted(Formatting.GREEN));
        chat(server, Text.literal(" ").formatted(Formatting.DARK_GRAY));

        broadcastDamageLeaderboard(server);
        saveRunToStorage(server, "COMPLETED", winnerName);

        // block death-end afterwards
        FIRST_REAL_DEATH_HANDLED.set(true);

        ensureSidebar(server);
        renderSidebar(server);
    }

    // ------------------------------------------------------------
    // SIDEBAR (diff-based, shows ALL milestones with +/- vs best)
    // ------------------------------------------------------------

    private static void ensureSidebar(MinecraftServer server) {
        if (sidebarInitialized) return;

        runCommand(server, "scoreboard objectives add " + OBJ_NAME + " dummy \"SPLITS\"");
        runCommand(server, "scoreboard objectives setdisplay sidebar " + OBJ_NAME);

        sidebarInitialized = true;
    }

    private static void renderSidebar(MinecraftServer server) {
        if (!storageLoaded) loadStorage(server);

        Map<Integer, String> desired = new HashMap<>();
        int score = 15;

        // Status line
        if (runCompleted) {
            desired.put(score, uniqueLine(score, Formatting.GREEN.toString() + "COMPLETED"));
            score--;
        } else if (runFailed) {
            desired.put(score, uniqueLine(score, Formatting.DARK_RED.toString() + "FAILED"));
            score--;
        }

        // Show ALL milestones always (baseline = best split; if achieved show actual + delta)
        for (Milestone m : Milestone.values()) {
            if (score <= 0) break;

            String line = buildMilestoneSidebarLine(m);
            desired.put(score, uniqueLine(score, line));
            score--;
        }

        // Remove scores we no longer use
        for (Map.Entry<Integer, String> e : new ArrayList<>(SIDEBAR_LINES.entrySet())) {
            int s = e.getKey();
            String oldEntry = e.getValue();
            if (!desired.containsKey(s)) {
                if (oldEntry != null && !oldEntry.isEmpty()) {
                    runCommand(server, "scoreboard players reset " + scoreHolder(oldEntry) + " " + OBJ_NAME);
                }
                SIDEBAR_LINES.remove(s);
            }
        }

        // Apply diffs
        for (Map.Entry<Integer, String> e : desired.entrySet()) {
            int s = e.getKey();
            String entry = e.getValue();
            setSidebarLine(server, s, entry);
        }
    }

    private static String buildMilestoneSidebarLine(Milestone m) {
        // best split from JSON
        Long best = BEST_SPLIT_MS.get(m.shortLabel);

        // achieved this run?
        SplitRecord r = SPLITS.get(m);

        // label part
        String label = Formatting.WHITE.toString() + m.shortLabel + " ";

        if (r == null) {
            // Not achieved: show best time if known, else "--:--:--"
            String baseTime = (best != null) ? formatDuration(best) : "--:--:--";
            // Baseline line is gray-ish to indicate "target"
            String line = Formatting.DARK_GRAY.toString() + m.shortLabel + " "
                    + Formatting.GRAY.toString() + baseTime;
            return trimToSafeLength(line, 38);
        }

        // Achieved: show current time
        String current = formatDuration(r.timeMs);

        // If no best known yet: just show current
        if (best == null) {
            String line = label + Formatting.GOLD.toString() + current;
            return trimToSafeLength(line, 38);
        }

        // Delta vs best: + slower (red), - faster (green)
        long diffMs = r.timeMs - best; // + slower, - faster
        String sign = diffMs >= 0 ? "+" : "-";
        long abs = Math.abs(diffMs);

        Formatting deltaCol = (diffMs <= 0) ? Formatting.GREEN : Formatting.RED;
        String delta = deltaCol.toString() + sign + formatDuration(abs);

        // Example: "IRON 00:02:10 (+00:00:07)"
        String line = label
                + Formatting.GOLD.toString() + current
                + Formatting.DARK_GRAY.toString() + " ("
                + delta
                + Formatting.DARK_GRAY.toString() + ")";

        return trimToSafeLength(line, 38);
    }

    private static void setSidebarLine(MinecraftServer server, int scoreValue, String entry) {
        String oldEntry = SIDEBAR_LINES.get(scoreValue);
        if (oldEntry != null && oldEntry.equals(entry)) return;

        if (oldEntry != null && !oldEntry.isEmpty()) {
            runCommand(server, "scoreboard players reset " + scoreHolder(oldEntry) + " " + OBJ_NAME);
        }

        if (entry != null && !entry.isEmpty()) {
            runCommand(server, "scoreboard players set " + scoreHolder(entry) + " " + OBJ_NAME + " " + scoreValue);
            SIDEBAR_LINES.put(scoreValue, entry);
        } else {
            SIDEBAR_LINES.remove(scoreValue);
        }
    }

    /**
     * Ensure each line is unique even if visible text is identical,
     * otherwise Minecraft merges scoreboard entries.
     */
    private static String uniqueLine(int score, String visible) {
        // Append an invisible uniqueness suffix using formatting codes.
        // Different scores get different suffixes.
        return visible + uniqueSuffix(score);
    }

    private static String uniqueSuffix(int score) {
        // Use a selection of formatting codes. They render nothing extra but make the string unique.
        Formatting[] pool = new Formatting[] {
                Formatting.BLACK, Formatting.DARK_BLUE, Formatting.DARK_GREEN, Formatting.DARK_AQUA,
                Formatting.DARK_RED, Formatting.DARK_PURPLE, Formatting.GOLD, Formatting.GRAY,
                Formatting.DARK_GRAY, Formatting.BLUE, Formatting.GREEN, Formatting.AQUA,
                Formatting.RED, Formatting.LIGHT_PURPLE, Formatting.YELLOW, Formatting.WHITE
        };
        Formatting f = pool[Math.floorMod(score, pool.length)];
        // RESET + chosen color makes a unique-but-invisible suffix (no extra characters shown)
        return Formatting.RESET.toString() + f.toString();
    }

    private static String trimToSafeLength(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }

    private static String scoreHolder(String s) {
        // Non-breaking space zodat Brigadier het als één argument ziet
        return s.replace(' ', '\u00A0');
    }

    private static String formatDuration(long ms) {
        long t = ms / 1000;
        long h = t / 3600;
        long m = (t % 3600) / 60;
        long s = t % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    // ------------------------------------------------------------
    // COMMAND HELPER (withSilent if available)
    // ------------------------------------------------------------

    private static void runCommand(MinecraftServer server, String command) {
        try {
            net.minecraft.server.command.ServerCommandSource src = server.getCommandSource();
            try {
                Object silent = src.getClass().getMethod("withSilent").invoke(src);
                if (silent instanceof net.minecraft.server.command.ServerCommandSource) {
                    src = (net.minecraft.server.command.ServerCommandSource) silent;
                }
            } catch (Throwable ignored) {}

            server.getCommandManager().executeWithPrefix(src, command);
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------
    // DAMAGE TYPE / CAUSE
    // ------------------------------------------------------------

    private static String describeDamageType(DamageSource source) {
        try {
            return source.getName();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String describeCause(DamageSource source) {
        try {
            Entity src = source.getSource();
            if (src == null) return source.getName();

            if (src instanceof ProjectileEntity proj) {
                return "projectile:" + proj.getType().toString();
            }
            return source.getName();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String safeLower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------
    // FORCE SPECTATOR AND TELEPORT ALL
    // ------------------------------------------------------------

    private static void forceSpectatorAndTeleportAll(MinecraftServer server) {
        if (deathWorldKey == null) return;

        String dim = deathWorldKey.getValue().toString(); // e.g. "minecraft:overworld"
        int x = (int) Math.floor(deathX);
        int y = (int) Math.floor(deathY + 0.2);
        int z = (int) Math.floor(deathZ);

        // Force spectator (reliable)
        runCommand(server, "gamemode spectator @a");

        // Teleport everyone in the correct dimension context (reliable cross-dimension)
        runCommand(server, "execute in " + dim + " run tp @a " + x + " " + y + " " + z);
    }

    private static void forceSpectatorAndTeleportPlayer(MinecraftServer server, ServerPlayerEntity p) {
        if (deathWorldKey == null || p == null) return;

        String dim = deathWorldKey.getValue().toString();
        int x = (int) Math.floor(deathX);
        int y = (int) Math.floor(deathY + 0.2);
        int z = (int) Math.floor(deathZ);

        String name = p.getName().getString(); // no spaces in MC names

        runCommand(server, "gamemode spectator " + name);
        runCommand(server, "execute in " + dim + " run tp " + name + " " + x + " " + y + " " + z);
    }

    // ------------------------------------------------------------
    // END RUN MESSAGE (FAILED) + death info
    // ------------------------------------------------------------

    private static void broadcastEndRunFailed(MinecraftServer server, ServerPlayerEntity dead, ServerWorld world, DamageSource source, String type) {
        String dim = world.getRegistryKey().getValue().toString();

        chat(server, Text.literal(" ").formatted(Formatting.DARK_GRAY));
        chat(server, Text.literal("══════════════════════════════").formatted(Formatting.DARK_RED));
        chat(server, Text.literal("☠☠☠  END RUN  ☠☠☠").formatted(Formatting.RED, Formatting.BOLD));
        chat(server, Text.literal("RUN FAILED").formatted(Formatting.DARK_RED, Formatting.BOLD));
        chat(server, Text.literal("══════════════════════════════").formatted(Formatting.DARK_RED));

        MutableText deathLine = Text.empty()
                .append(Text.literal("💀 DEATH: ").formatted(Formatting.DARK_RED))
                .append(Text.literal(dead.getName().getString()).formatted(Formatting.WHITE))
                .append(Text.literal(" @ ").formatted(Formatting.GRAY))
                .append(Text.literal(dead.getBlockX() + " " + dead.getBlockY() + " " + dead.getBlockZ()).formatted(Formatting.GOLD))
                .append(Text.literal(" in ").formatted(Formatting.GRAY))
                .append(Text.literal(dim).formatted(Formatting.AQUA))
                .append(Text.literal(" — cause: ").formatted(Formatting.GRAY))
                .append(Text.literal(describeCause(source)).formatted(Formatting.DARK_GRAY))
                .append(Text.literal(" (").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(type).formatted(Formatting.DARK_GRAY))
                .append(Text.literal(")").formatted(Formatting.DARK_GRAY));

        chat(server, deathLine);

        chat(server, Text.literal("Everyone has been forced into SPECTATOR.").formatted(Formatting.GRAY));
        chat(server, Text.literal("Better luck next time.").formatted(Formatting.GRAY, Formatting.ITALIC));
        chat(server, Text.literal("══════════════════════════════").formatted(Formatting.DARK_RED));
        chat(server, Text.literal(" ").formatted(Formatting.DARK_GRAY));
    }
}
