package net.whitehammer9.seasons;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Levelled;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.TimeSkipEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Original Paper API implementation; it intentionally has no NMS dependencies. */
public final class SeasonsPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Map<UUID, WorldSeason> worldSeasons = new ConcurrentHashMap<>();
    private final Map<UUID, Long> observedMinecraftDays = new ConcurrentHashMap<>();
    private final Queue<ChunkRefresh> visualRefreshQueue = new ConcurrentLinkedQueue<>();
    private final Set<ChunkRefresh> queuedVisualRefreshes = ConcurrentHashMap.newKeySet();
    private final Set<UUID> setupReminderShown = ConcurrentHashMap.newKeySet();
    private int environmentStage;
    private boolean fastVisualRefresh;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadWorldState();
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("seasons").setExecutor(this);
        getCommand("seasons").setTabCompleter(this);
        Bukkit.getScheduler().runTaskTimer(this, this::advanceCalendar, 100L, 100L);
        Bukkit.getScheduler().runTaskTimer(this, this::updateWeather, 200L,
                Math.max(200L, getConfig().getLong("weather.check-interval-ticks", 12000L)));
        Bukkit.getScheduler().runTaskTimer(this, this::runEnvironmentPass, 40L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::refreshVisibleChunks, 40L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, this::applySeasonEffects, 40L, 40L);
        Bukkit.getScheduler().runTaskTimer(this, this::updateSeasonActionBar, 40L,
                Math.max(20L, getConfig().getLong("action-bar.update-interval-ticks", 40L)));
        if (getConfig().getBoolean("visuals.enabled", true)) {
            if (Bukkit.getPluginManager().getPlugin("ProtocolLib") != null) {
                new SeasonalVisuals(this, this::currentSeasonId).register();
            } else {
                getLogger().warning("ProtocolLib is not installed; seasonal client colors are disabled.");
            }
        }
        getLogger().info("Seasons enabled with " + worldSeasons.size() + " configured world(s).");
    }

    @Override
    public void onDisable() {
        saveWorldState();
    }

    private void loadWorldState() {
        worldSeasons.clear();
        for (World world : Bukkit.getWorlds()) {
            String path = worldPath(world);
            if (getConfig().getBoolean(path + ".enabled", false)) {
                String saved = getConfig().getString(path + ".season", Season.SPRING.name());
                Season season = Season.parse(saved);
                worldSeasons.put(world.getUID(), new WorldSeason(season == null ? Season.SPRING : season,
                        Math.max(1, getConfig().getInt(path + ".day", 1)),
                        getConfig().getBoolean(path + ".transition", false),
                        Math.max(0, getConfig().getInt(path + ".transition-day", 0))));
            }
            observedMinecraftDays.put(world.getUID(), world.getFullTime() / 24000L);
        }
    }

    private void saveWorldState() {
        for (Map.Entry<UUID, WorldSeason> entry : worldSeasons.entrySet()) {
            World world = Bukkit.getWorld(entry.getKey());
            if (world == null) continue;
            String path = worldPath(world);
            WorldSeason state = entry.getValue();
            getConfig().set(path + ".enabled", true);
            getConfig().set(path + ".season", state.season.name());
            getConfig().set(path + ".day", state.day);
            getConfig().set(path + ".transition", state.transition);
            getConfig().set(path + ".transition-day", state.transitionDay);
        }
        saveConfig();
    }

    private void advanceCalendar() {
        if (!getConfig().getBoolean("season.automatic-advance", true)) return;
        if (getConfig().getBoolean("season.advance-on-sleep-only", true)) return;
        int daysPerSeason = Math.max(1, getConfig().getInt("season.days-per-season", 14));
        int transitionDays = Math.max(0, getConfig().getInt("season.transition-days", 3));
        for (World world : Bukkit.getWorlds()) {
            WorldSeason state = worldSeasons.get(world.getUID());
            if (state == null) continue;
            long today = world.getFullTime() / 24000L;
            Long previous = observedMinecraftDays.put(world.getUID(), today);
            if (previous == null || today <= previous) continue;
            for (long day = previous; day < today; day++) {
                advanceOneDay(world, state, daysPerSeason, transitionDays);
            }
        }
        saveWorldState();
    }

    private void advanceOneDay(World world, WorldSeason state, int daysPerSeason, int transitionDays) {
        if (state.transition) {
            state.transitionDay++;
            if (state.transitionDay > transitionDays) {
                state.transition = false;
                state.transitionDay = 0;
                state.season = state.season.next();
                state.day = 1;
                queueVisibleChunks(world, true);
                notifySeasonEntered(world, state.season);
            }
            return;
        }
        state.day++;
        if (state.day <= daysPerSeason) return;
        if (transitionDays == 0) {
            state.season = state.season.next();
            state.day = 1;
            queueVisibleChunks(world, true);
            notifySeasonEntered(world, state.season);
            return;
        }
        state.day = daysPerSeason;
        state.transition = true;
        state.transitionDay = 1;
        Bukkit.broadcastMessage(color("&b[SimpleSeasons] &f" + world.getName() + " entered the &e"
                + state.transitionName() + " &ftransition."));
    }

    @EventHandler(ignoreCancelled = true)
    public void onNightSkipped(TimeSkipEvent event) {
        if (event.getSkipReason() != TimeSkipEvent.SkipReason.NIGHT_SKIP
                || !getConfig().getBoolean("season.automatic-advance", true)
                || !getConfig().getBoolean("season.advance-on-sleep-only", true)) return;
        WorldSeason state = worldSeasons.get(event.getWorld().getUID());
        if (state == null) return;
        advanceOneDay(event.getWorld(), state,
                Math.max(1, getConfig().getInt("season.days-per-season", 14)),
                Math.max(0, getConfig().getInt("season.transition-days", 3)));
        saveWorldState();
    }

    private void notifySeasonEntered(World world, Season season) {
        String message = getConfig().getString("messages.season-entered",
                "&b[SimpleSeasons] &fYou now have entered &e<season>&f.");
        String formatted = color(message.replace("<season>", season.displayName()));
        world.getPlayers().forEach(player -> player.sendMessage(formatted));
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.isOp()
                || worldSeasons.containsKey(player.getWorld().getUID())
                || !getConfig().getBoolean("messages.setup-reminder.enabled", true)
                || !setupReminderShown.add(player.getUniqueId())) return;
        String message = getConfig().getString("messages.setup-reminder.text",
                "&e[SimpleSeasons] &fThis world is not set up yet. Type &b/seasons enable <world>&f to enable seasons here.");
        player.sendMessage(color(message.replace("<world>", player.getWorld().getName())));
    }

    private void updateWeather() {
        for (World world : Bukkit.getWorlds()) {
            WorldSeason state = worldSeasons.get(world.getUID());
            if (state == null) continue;
            int chance = Math.max(0, Math.min(100, getConfig().getInt("weather.chance."
                    + state.season.name().toLowerCase(Locale.ROOT), 40)));
            world.setStorm(ThreadLocalRandom.current().nextInt(100) < chance);
        }
    }

    private void runEnvironmentPass() {
        if (!getConfig().getBoolean("environment.enabled", true)) return;
        int radius = Math.max(0, Math.min(6, getConfig().getInt("environment.scan-radius-chunks", 3)));
        int stageCount = activeSnowfall() ? Math.max(1, Math.min(16,
                getConfig().getInt("environment.snow-scan-stages-per-pass", 4))) : 1;
        int firstStage = environmentStage;
        environmentStage = Math.floorMod(environmentStage + stageCount, 16);
        for (Player player : Bukkit.getOnlinePlayers()) {
            World world = player.getWorld();
            WorldSeason state = worldSeasons.get(world.getUID());
            if (state == null) continue;
            int centerX = player.getLocation().getBlockX() >> 4;
            int centerZ = player.getLocation().getBlockZ() >> 4;
            for (int offset = 0; offset < stageCount; offset++) {
                int stage = Math.floorMod(firstStage + offset, 16);
                int baseX = (stage & 3) * 4;
                int baseZ = (stage >> 2) * 4;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (!world.isChunkLoaded(centerX + dx, centerZ + dz)) continue;
                        for (int localX = 0; localX < 4; localX++) {
                            for (int localZ = 0; localZ < 4; localZ++) {
                                int x = (centerX + dx) * 16 + baseX + localX;
                                int z = (centerZ + dz) * 16 + baseZ + localZ;
                                updateColumn(world, x, z, state.season);
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean activeSnowfall() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            World world = player.getWorld();
            WorldSeason state = worldSeasons.get(world.getUID());
            if (state != null && state.season == Season.WINTER && world.hasStorm()) return true;
        }
        return false;
    }

    private void updateColumn(World world, int x, int z, Season season) {
        Block top = world.getHighestBlockAt(x, z);
        if (top.getLightFromSky() == 0 || top.getLightFromBlocks() >= 12) return;
        Block ground = findGroundSurface(top);
        if (season == Season.WINTER) {
            if (getConfig().getBoolean("environment.freeze-water-in-winter", true)) freezeWater(top);
            if (ground != top && getConfig().getBoolean("environment.freeze-water-in-winter", true)) freezeWater(ground);
            if (world.hasStorm() && getConfig().getBoolean("environment.snow-in-winter", true)) {
                placeSnow(top);
                if (ground != top && getConfig().getBoolean("environment.snow-on-ground-under-trees", true)) {
                    placeSnow(ground);
                }
            }
        } else if (getConfig().getBoolean("environment.thaw-ice-outside-winter", true)) {
            thawIce(top);
            if (ground != top) thawIce(ground);
        }
    }

    private Block findGroundSurface(Block top) {
        if (!isTreeBlock(top.getType())) return top;
        World world = top.getWorld();
        for (int y = top.getY() - 1; y >= world.getMinHeight(); y--) {
            Block candidate = world.getBlockAt(top.getX(), y, top.getZ());
            if (candidate.getType().isAir() || isTreeBlock(candidate.getType())) continue;
            return candidate;
        }
        return top;
    }

    private boolean isTreeBlock(Material material) {
        return Tag.LEAVES.isTagged(material) || Tag.LOGS.isTagged(material);
    }

    private void freezeWater(Block block) {
        if (block.getType() == Material.WATER && block.getBlockData() instanceof Levelled levelled
                && levelled.getLevel() == 0) {
            block.setType(Material.ICE, false);
        }
    }

    private void placeSnow(Block block) {
        if (!block.getType().isSolid() || block.getType() == Material.ICE) return;
        Block above = block.getRelative(0, 1, 0);
        if (above.getType() == Material.AIR) above.setType(Material.SNOW, false);
    }

    private void thawIce(Block block) {
        if (block.getType() == Material.ICE) block.setType(Material.WATER, false);
        if (block.getType() == Material.SNOW || block.getType() == Material.SNOW_BLOCK) {
            block.setType(Material.AIR, false);
        }
    }

    private void applySeasonEffects() {
        if (!getConfig().getBoolean("effects.enabled", true)) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            WorldSeason state = worldSeasons.get(player.getWorld().getUID());
            if (state == null) continue;
            if (getConfig().getBoolean("effects.require-sky-exposure", true) && !isExposedToSky(player)) continue;
            boolean daytime = isDaytime(player.getWorld());
            if (state.season == Season.WINTER
                    && getConfig().getBoolean("effects.winter.unarmored-slowness", true)
                    && !wearingArmor(player)) {
                int amplifier = Math.max(0, getConfig().getInt(daytime
                        ? "effects.winter.slowness-amplifier"
                        : "effects.winter.night-slowness-amplifier", daytime ? 0 : 1));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, amplifier, true, false, true));
            }
            if (state.season == Season.SUMMER
                    && getConfig().getBoolean("effects.summer.hot-armor-nausea", true)
                    && (!getConfig().getBoolean("effects.summer.daytime-heat-only", true) || daytime)
                    && wearingHotArmor(player)) {
                int amplifier = Math.max(0, getConfig().getInt("effects.summer.nausea-amplifier", 0));
                player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 80, amplifier, true, false, true));
            }
        }
    }

    private void updateSeasonActionBar() {
        if (!getConfig().getBoolean("action-bar.enabled", true)) return;
        String template = getConfig().getString("action-bar.message", "&bSeason: &e<season>");
        for (Player player : Bukkit.getOnlinePlayers()) {
            WorldSeason state = worldSeasons.get(player.getWorld().getUID());
            if (state == null) continue;
            String message = color(template.replace("<season>", state.season.displayName()));
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(message));
        }
    }

    private boolean isExposedToSky(Player player) {
        return player.getWorld().getEnvironment() == World.Environment.NORMAL
                && player.getLocation().getBlock().getLightFromSky() > 0;
    }

    private boolean isDaytime(World world) {
        long time = world.getTime();
        return time >= 0 && time < 12300;
    }

    private boolean wearingArmor(Player player) {
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (item != null && item.getType() != Material.AIR) return true;
        }
        return false;
    }

    private boolean wearingHotArmor(Player player) {
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (item == null) continue;
            String material = item.getType().name();
            if (material.startsWith("CHAINMAIL_") || material.startsWith("IRON_") || material.startsWith("DIAMOND_")) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onCropGrow(BlockGrowEvent event) {
        WorldSeason state = worldSeasons.get(event.getBlock().getWorld().getUID());
        if (state != null && state.season == Season.WINTER
                && getConfig().getBoolean("crops.pause-growth-in-winter", true)
                && event.getBlock().getBlockData() instanceof Ageable) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onNaturalSpawn(CreatureSpawnEvent event) {
        WorldSeason state = worldSeasons.get(event.getLocation().getWorld().getUID());
        if (state == null || event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL
                || event.getLocation().getBlock().isLiquid()) return;
        int chance = Math.max(0, Math.min(100, getConfig().getInt("mobs.extra-spawn-chance-percent", 8)));
        if (ThreadLocalRandom.current().nextInt(100) >= chance) return;
        List<String> configured = getConfig().getStringList("mobs." + state.season.name().toLowerCase(Locale.ROOT));
        List<EntityType> types = new ArrayList<>();
        for (String value : configured) {
            try {
                EntityType type = EntityType.valueOf(value.toUpperCase(Locale.ROOT));
                if (type.isAlive() && type.isSpawnable()) types.add(type);
            } catch (IllegalArgumentException ignored) { }
        }
        if (types.isEmpty()) return;
        EntityType type = types.get(ThreadLocalRandom.current().nextInt(types.size()));
        Entity spawned = event.getLocation().getWorld().spawnEntity(event.getLocation().add(1, 0, 1), type);
        spawned.setPersistent(false);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("seasons.use")) {
            sender.sendMessage(color("&cYou do not have permission."));
            return true;
        }
        String action = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        if (List.of("set", "next", "enable", "disable", "reload").contains(action)
                && !sender.hasPermission("seasons.admin")) {
            sender.sendMessage(color("&cYou do not have permission."));
            return true;
        }
        if (action.equals("reload")) {
            reloadConfig();
            loadWorldState();
            sender.sendMessage(color("&aSeasons configuration reloaded."));
            return true;
        }
        World world = resolveWorld(sender, args, action);
        if (world == null) return true;
        switch (action) {
            case "info" -> showInfo(sender, world);
            case "enable" -> { enableWorld(world); sender.sendMessage(color("&aSeasons enabled in " + world.getName())); }
            case "disable" -> { worldSeasons.remove(world.getUID()); getConfig().set(worldPath(world) + ".enabled", false); saveConfig(); sender.sendMessage(color("&eSeasons disabled in " + world.getName())); }
            case "set" -> setSeason(sender, world, args);
            case "next" -> { WorldSeason state = stateFor(world); state.season = state.season.next(); state.day = 1; state.transition = false; state.transitionDay = 0; queueVisibleChunks(world, true); saveWorldState(); showInfo(sender, world); }
            default -> sender.sendMessage(color("&e/seasons [info|set|next|enable|disable|reload]"));
        }
        return true;
    }

    private World resolveWorld(CommandSender sender, String[] args, String action) {
        int worldIndex = action.equals("set") ? 2 : 1;
        if (args.length > worldIndex) {
            World world = Bukkit.getWorld(args[worldIndex]);
            if (world == null) sender.sendMessage(color("&cUnknown world: " + args[worldIndex]));
            return world;
        }
        if (sender instanceof Player player) return player.getWorld();
        sender.sendMessage(color("&cConsole must provide a world name."));
        return null;
    }

    private void setSeason(CommandSender sender, World world, String[] args) {
        if (args.length < 2) { sender.sendMessage(color("&cUsage: /seasons set <season> [world]")); return; }
        Season season = Season.parse(args[1]);
        if (season == null) { sender.sendMessage(color("&cChoose spring, summer, fall, or winter.")); return; }
        WorldSeason state = stateFor(world);
        state.season = season;
        state.day = 1;
        state.transition = false;
        state.transitionDay = 0;
        queueVisibleChunks(world, true);
        saveWorldState();
        showInfo(sender, world);
    }

    private void enableWorld(World world) {
        worldSeasons.putIfAbsent(world.getUID(), new WorldSeason(Season.SPRING, 1));
        saveWorldState();
    }

    private WorldSeason stateFor(World world) {
        return worldSeasons.computeIfAbsent(world.getUID(), ignored -> new WorldSeason(Season.SPRING, 1));
    }

    private void showInfo(CommandSender sender, World world) {
        WorldSeason state = worldSeasons.get(world.getUID());
        if (state == null) sender.sendMessage(color("&eSeasons is disabled in " + world.getName() + "."));
        else sender.sendMessage(color("&b" + world.getName() + ": &e" + state.displayName()));
    }

    String currentSeasonId(World world) {
        WorldSeason state = worldSeasons.get(world.getUID());
        return state == null ? Season.SPRING.name() : state.season.name();
    }

    private void queueVisibleChunks(World world, boolean fast) {
        if (!getConfig().getBoolean("visuals.enabled", true)
                || Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) return;
        if (fast) fastVisualRefresh = true;
        int radius = Math.max(0, Math.min(10, getConfig().getInt("visuals.refresh-radius-chunks", 5)));
        for (Player player : world.getPlayers()) {
            Chunk center = player.getLocation().getChunk();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int x = center.getX() + dx;
                    int z = center.getZ() + dz;
                    if (!world.isChunkLoaded(x, z)) continue;
                    ChunkRefresh refresh = new ChunkRefresh(world.getUID(), x, z);
                    if (queuedVisualRefreshes.add(refresh)) visualRefreshQueue.add(refresh);
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void refreshVisibleChunks() {
        int configured = fastVisualRefresh
                ? getConfig().getInt("visuals.season-change-chunks-per-tick", 12)
                : getConfig().getInt("visuals.refresh-chunks-per-tick", 3);
        int limit = Math.max(1, Math.min(32, configured));
        for (int i = 0; i < limit; i++) {
            ChunkRefresh refresh = visualRefreshQueue.poll();
            if (refresh == null) {
                fastVisualRefresh = false;
                return;
            }
            queuedVisualRefreshes.remove(refresh);
            World world = Bukkit.getWorld(refresh.worldId);
            if (world != null && world.isChunkLoaded(refresh.x, refresh.z)) world.refreshChunk(refresh.x, refresh.z);
        }
    }

    private String worldPath(World world) { return "worlds." + world.getUID(); }
    private String color(String text) { return ChatColor.translateAlternateColorCodes('&', text); }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return prefix(args[0], List.of("info", "set", "next", "enable", "disable", "reload"));
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) return prefix(args[1], List.of("spring", "summer", "fall", "winter"));
        if ((args.length == 2 && !args[0].equalsIgnoreCase("set")) || (args.length == 3 && args[0].equalsIgnoreCase("set"))) {
            return prefix(args[args.length - 1], Bukkit.getWorlds().stream().map(World::getName).toList());
        }
        return List.of();
    }

    private List<String> prefix(String input, List<String> values) {
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(input.toLowerCase(Locale.ROOT))).toList();
    }

    private enum Season {
        SPRING, SUMMER, FALL, WINTER;
        Season next() { return values()[(ordinal() + 1) % values().length]; }
        String displayName() { return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT); }
        static Season parse(String value) { try { return valueOf(value.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ex) { return null; } }
    }

    private static final class WorldSeason {
        private Season season;
        private int day;
        private boolean transition;
        private int transitionDay;
        private WorldSeason(Season season, int day) { this(season, day, false, 0); }
        private WorldSeason(Season season, int day, boolean transition, int transitionDay) {
            this.season = season;
            this.day = day;
            this.transition = transition;
            this.transitionDay = transitionDay;
        }
        private String transitionName() { return season.displayName() + " to " + season.next().displayName(); }
        private String displayName() {
            return transition ? transitionName() + " (transition day " + transitionDay + ")"
                    : season.displayName() + " (day " + day + ")";
        }
    }

    private record ChunkRefresh(UUID worldId, int x, int z) { }
}
