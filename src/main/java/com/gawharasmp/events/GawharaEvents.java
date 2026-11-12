package com.gawharasmp.events;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.FallingBlock;
import org.bukkit.util.Vector;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

public final class GawharaEvents extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private Economy economy;
    private BukkitTask ticker;
    private BukkitTask autoScheduler;
    private ActiveEvent activeEvent;
    private File stateFile;
    private YamlConfiguration state;

    private enum EventType {
        KOTH("koth"), TREASURE("treasure"), METEOR("meteor");
        private final String key;
        EventType(String key) { this.key = key; }
        static EventType parse(String value) {
            if (value == null) return null;
            for (EventType type : values()) if (type.key.equalsIgnoreCase(value)) return type;
            return null;
        }
    }

    private static final class ActiveEvent {
        private final EventType type;
        private final long startedAt;
        private final long endsAt;
        private final Location center;
        private final BossBar bossBar;
        private Location cacheLocation;
        private String originalBlockData;
        private FallingBlock meteorEntity;
        private UUID captureOwner;
        private int captureSeconds;
        private int lastAnnouncement = -1;

        private ActiveEvent(EventType type, long startedAt, long endsAt, Location center, BossBar bossBar) {
            this.type = type;
            this.startedAt = startedAt;
            this.endsAt = endsAt;
            this.center = center;
            this.bossBar = bossBar;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!hookEconomy()) {
            getLogger().severe("Vault economy provider was not found. Install Vault and an economy plugin such as EssentialsX Economy.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        stateFile = new File(getDataFolder(), "state.yml");
        state = YamlConfiguration.loadConfiguration(stateFile);
        recoverStaleCache();

        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand command = getCommand("gevents");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }

        ticker = Bukkit.getScheduler().runTaskTimer(this, this::tickEvent, 20L, 20L);
        startAutomaticEvents();
        getLogger().info("GawharaEvents enabled. Vault economy hooked successfully.");
    }

    @Override
    public void onDisable() {
        if (ticker != null) ticker.cancel();
        if (autoScheduler != null) autoScheduler.cancel();
        if (activeEvent != null) {
            cleanupCache(activeEvent);
            activeEvent.bossBar.removeAll();
            activeEvent = null;
        }
    }

    private boolean hookEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> registration = getServer().getServicesManager().getRegistration(Economy.class);
        if (registration == null) return false;
        economy = registration.getProvider();
        return economy != null;
    }

    private void startAutomaticEvents() {
        if (autoScheduler != null) autoScheduler.cancel();
        if (!getConfig().getBoolean("auto-events.enabled", false)) return;
        long firstDelay = Math.max(60L, getConfig().getLong("auto-events.first-delay-seconds", 300L));
        long interval = Math.max(300L, getConfig().getLong("auto-events.interval-seconds", 10800L));
        autoScheduler = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (activeEvent != null) return;
            List<String> allowed = getConfig().getStringList("auto-events.types");
            List<EventType> choices = new ArrayList<>();
            for (String value : allowed) {
                EventType type = EventType.parse(value);
                if (type != null) choices.add(type);
            }
            if (choices.isEmpty()) return;
            EventType chosen = choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
            startEvent(chosen, null);
        }, firstDelay * 20L, interval * 20L);
    }

    private void tickEvent() {
        if (activeEvent == null) return;
        ActiveEvent event = activeEvent;
        long now = System.currentTimeMillis();
        long remaining = Math.max(0L, (event.endsAt - now + 999L) / 1000L);
        if (remaining <= 0L) {
            finishEvent(null, true);
            return;
        }

        if (event.type == EventType.KOTH) {
            updateKoth(event);
        } else {
            double total = Math.max(1L, (event.endsAt - event.startedAt) / 1000L);
            event.bossBar.setProgress(Math.max(0.0, Math.min(1.0, remaining / total)));
            String cacheTitle = getConfig().getString("bossbar.cache-title", "&b&lGAWHARA EVENT &8» &f%event% &8| &e%time%s")
                    .replace("%event%", eventName(event.type)).replace("%time%", String.valueOf(remaining));
            event.bossBar.setTitle(color(cacheTitle));
            if (event.type == EventType.TREASURE) sendTreasureHint(event, remaining);
        }
    }

    private void updateKoth(ActiveEvent event) {
        double radius = Math.max(1.0, getConfig().getDouble("events.koth.radius", 7.0));
        List<Player> contenders = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().equals(event.center.getWorld())) continue;
            if (player.getGameMode().name().equals("CREATIVE") || player.getGameMode().name().equals("SPECTATOR")) continue;
            if (player.getLocation().distanceSquared(event.center) <= radius * radius) contenders.add(player);
        }

        if (contenders.size() == 1) {
            Player holder = contenders.get(0);
            if (!holder.getUniqueId().equals(event.captureOwner)) {
                event.captureOwner = holder.getUniqueId();
                event.captureSeconds = 0;
                broadcast("messages.koth-started", "%player%", holder.getName());
            }
            event.captureSeconds++;
            int required = Math.max(5, getConfig().getInt("events.koth.capture-seconds", 60));
            double progress = Math.max(0.0, Math.min(1.0, (double) event.captureSeconds / required));
            event.bossBar.setProgress(progress);
            String kothTitle = getConfig().getString("bossbar.koth-title", "&b&lKOTH &8» &f%player% &8| &e%progress%%")
                    .replace("%player%", holder.getName())
                    .replace("%progress%", String.valueOf(Math.min(100, (event.captureSeconds * 100) / required)));
            event.bossBar.setTitle(color(kothTitle));
            if (event.captureSeconds >= required) finishEvent(holder, false);
        } else {
            event.captureOwner = null;
            event.captureSeconds = 0;
            event.bossBar.setProgress(0.0);
            String title = contenders.size() > 1
                    ? getConfig().getString("bossbar.koth-contested", "&c&lKOTH CONTESTED")
                    : getConfig().getString("bossbar.koth-empty", "&b&lKING OF THE HILL &8» &fStand in the capture zone");
            event.bossBar.setTitle(color(title));
        }
    }

    private void sendTreasureHint(ActiveEvent event, long remaining) {
        int every = Math.max(15, getConfig().getInt("events.treasure.hint-interval-seconds", 45));
        int elapsed = (int) ((System.currentTimeMillis() - event.startedAt) / 1000L);
        if (elapsed == 0 || elapsed % every != 0 || event.lastAnnouncement == elapsed) return;
        event.lastAnnouncement = elapsed;
        int accuracy = elapsed < 90 ? 100 : (elapsed < 180 ? 50 : 20);
        int x = roundTo(event.cacheLocation.getBlockX(), accuracy);
        int z = roundTo(event.cacheLocation.getBlockZ(), accuracy);
        String message = getConfig().getString("messages.treasure-hint", "&bTreasure clue: &f%world% &7near X:&f%x% &7Z:&f%z%");
        broadcastRaw(message.replace("%world%", event.cacheLocation.getWorld().getName())
                .replace("%x%", String.valueOf(x)).replace("%z%", String.valueOf(z)));
    }

    private int roundTo(int value, int unit) {
        return Math.round((float) value / unit) * unit;
    }

    private boolean startEvent(EventType type, CommandSender starter) {
        if (activeEvent != null) {
            send(starter, "messages.already-running", "%event%", eventName(activeEvent.type));
            return false;
        }
        if (!getConfig().getBoolean("events." + type.key + ".enabled", true)) {
            send(starter, "messages.event-disabled", "%event%", eventName(type));
            return false;
        }
        Location configured = readLocation(type);
        if (configured == null || configured.getWorld() == null) {
            send(starter, "messages.location-not-set", "%event%", type.key);
            if (starter == null) getLogger().warning("Automatic event skipped because its location is not configured: " + type.key);
            return false;
        }

        int duration = Math.max(30, getConfig().getInt("events." + type.key + ".duration-seconds", type == EventType.KOTH ? 600 : 480));
        BarColor barColor = type == EventType.KOTH ? BarColor.BLUE : (type == EventType.TREASURE ? BarColor.PURPLE : BarColor.YELLOW);
        BossBar bar = Bukkit.createBossBar(color(getConfig().getString("bossbar.start-title", "&b&lGAWHARA EVENTS")), barColor, BarStyle.SEGMENTED_10);
        bar.setVisible(true);
        for (Player player : Bukkit.getOnlinePlayers()) bar.addPlayer(player);

        ActiveEvent event = new ActiveEvent(type, System.currentTimeMillis(), System.currentTimeMillis() + duration * 1000L, configured, bar);
        if (type != EventType.KOTH) {
            Location cacheLocation = findCacheLocation(configured, getConfig().getInt("events." + type.key + ".search-radius", 250));
            if (cacheLocation == null) {
                bar.removeAll();
                send(starter, "messages.no-safe-location");
                if (starter == null) getLogger().warning("Automatic event skipped: no safe cache location for " + type.key);
                return false;
            }
            Block block = cacheLocation.getBlock();
            event.cacheLocation = cacheLocation;
            event.originalBlockData = block.getBlockData().getAsString();
            savePendingCache(cacheLocation, event.originalBlockData);
            block.setType(Material.CHEST);
            if (block.getType() != Material.CHEST) {
                clearPendingCache();
                bar.removeAll();
                send(starter, "messages.no-safe-location");
                if (starter == null) getLogger().warning("Automatic event skipped: could not place event cache for " + type.key);
                return false;
            }
            if (type == EventType.METEOR) {
                World world = cacheLocation.getWorld();
                world.spawnParticle(Particle.FLAME, cacheLocation.clone().add(0.5, 0.5, 0.5), 90, 1.4, 0.8, 1.4, 0.03);
                try {
                    Location sky = cacheLocation.clone().add(0.5, 22.0, 0.5);
                    event.meteorEntity = world.spawnFallingBlock(sky, Bukkit.createBlockData("minecraft:magma_block"));
                    event.meteorEntity.setDropItem(false);
                    event.meteorEntity.setHurtEntities(false);
                    event.meteorEntity.setVelocity(new Vector(0.0, -1.25, 0.0));
                } catch (IllegalArgumentException ex) {
                    getLogger().warning("Could not spawn the visual meteor: " + ex.getMessage());
                }
                world.playSound(cacheLocation, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.75f);
            } else {
                cacheLocation.getWorld().spawnParticle(Particle.END_ROD, cacheLocation.clone().add(0.5, 0.5, 0.5), 35, 0.7, 0.7, 0.7, 0.02);
            }
        }

        activeEvent = event;
        announceStart(event);
        return true;
    }

    private Location readLocation(EventType type) {
        ConfigurationSection section = getConfig().getConfigurationSection("locations." + type.key);
        if (section == null) return null;
        World world = Bukkit.getWorld(section.getString("world", "world"));
        if (world == null) return null;
        return new Location(world, section.getDouble("x"), section.getDouble("y", 64.0), section.getDouble("z"));
    }

    private Location findCacheLocation(Location center, int radius) {
        World world = center.getWorld();
        int safeRadius = Math.max(1, radius);
        for (int attempt = 0; attempt < 80; attempt++) {
            int x = center.getBlockX() + ThreadLocalRandom.current().nextInt(-safeRadius, safeRadius + 1);
            int z = center.getBlockZ() + ThreadLocalRandom.current().nextInt(-safeRadius, safeRadius + 1);
            Block ground = world.getHighestBlockAt(x, z);
            Block candidate = ground.getRelative(BlockFace.UP);
            if (candidate.getY() + 24 >= world.getMaxHeight()) continue;
            if (candidate.getType().isAir() && ground.getType().isSolid()) return candidate.getLocation();
        }
        Block fallback = center.getBlock().getType().isAir() ? center.getBlock() : center.getBlock().getRelative(BlockFace.UP);
        if (fallback.getType().isAir() && fallback.getRelative(BlockFace.DOWN).getType().isSolid()) return fallback.getLocation();
        return null;
    }

    private void announceStart(ActiveEvent event) {
        String key = "messages." + event.type.key + "-start";
        String text = getConfig().getString(key, "&bAn event has started: &f%event%&b!");
        text = text.replace("%event%", eventName(event.type));
        if (event.type == EventType.METEOR) {
            text = text.replace("%world%", event.cacheLocation.getWorld().getName())
                    .replace("%x%", String.valueOf(event.cacheLocation.getBlockX()))
                    .replace("%z%", String.valueOf(event.cacheLocation.getBlockZ()));
        }
        broadcastRaw(text);
        String subtitle = getConfig().getString("messages.event-subtitle", "&7Use &b/gevents &7for event information.");
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendTitle(color("&b&lGAWHARA EVENTS"), color(subtitle), 10, 50, 15);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.4f);
        }
    }

    private void finishEvent(Player winner, boolean timedOut) {
        if (activeEvent == null) return;
        ActiveEvent event = activeEvent;
        activeEvent = null;
        cleanupCache(event);
        event.bossBar.removeAll();

        if (winner != null) {
            double reward = Math.max(0.0, getConfig().getDouble("rewards." + event.type.key, 500.0));
            EconomyResponse response = economy.depositPlayer(winner, reward);
            if (response != null && response.transactionSuccess()) {
                String msg = getConfig().getString("messages.winner", "&a%player% won &e%reward% &afrom %event%!");
                broadcastRaw(msg.replace("%player%", winner.getName()).replace("%reward%", economy.format(reward)).replace("%event%", eventName(event.type)));
                winner.playSound(winner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            } else {
                winner.sendMessage(color(getConfig().getString("messages.reward-error", "&cThe reward could not be deposited. Contact staff.")));
                String reason = response == null ? "Vault returned no response" : response.errorMessage;
                getLogger().warning("Vault could not deposit the " + event.type.key + " reward for " + winner.getName() + ": " + reason);
            }
        } else if (timedOut) {
            broadcastRaw(getConfig().getString("messages.event-ended", "&c%event% ended. No winner this time.")
                    .replace("%event%", eventName(event.type)));
        }
    }

    private void cleanupCache(ActiveEvent event) {
        if (event.meteorEntity != null && event.meteorEntity.isValid()) {
            event.meteorEntity.remove();
            event.meteorEntity = null;
        }
        if (event.cacheLocation == null || event.originalBlockData == null) return;
        Block block = event.cacheLocation.getBlock();
        if (block.getType() == Material.CHEST) {
            block.setBlockData(Bukkit.createBlockData(event.originalBlockData));
        }
        clearPendingCache();
    }

    private void savePendingCache(Location location, String oldData) {
        state.set("pending-cache.world", location.getWorld().getName());
        state.set("pending-cache.x", location.getBlockX());
        state.set("pending-cache.y", location.getBlockY());
        state.set("pending-cache.z", location.getBlockZ());
        state.set("pending-cache.original", oldData);
        saveState();
    }

    private void recoverStaleCache() {
        if (!state.contains("pending-cache.world")) return;
        World world = Bukkit.getWorld(state.getString("pending-cache.world"));
        if (world != null) {
            Block block = world.getBlockAt(state.getInt("pending-cache.x"), state.getInt("pending-cache.y"), state.getInt("pending-cache.z"));
            if (block.getType() == Material.CHEST) {
                try {
                    block.setBlockData(Bukkit.createBlockData(state.getString("pending-cache.original", "minecraft:air")));
                } catch (IllegalArgumentException ex) {
                    getLogger().warning("Could not restore an old event cache block: " + ex.getMessage());
                }
            }
        }
        clearPendingCache();
    }

    private void clearPendingCache() {
        state.set("pending-cache", null);
        saveState();
    }

    private void saveState() {
        try {
            state.save(stateFile);
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Could not save event state", ex);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCacheInteract(PlayerInteractEvent event) {
        if (activeEvent == null || activeEvent.cacheLocation == null) return;
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null || !sameBlock(clicked.getLocation(), activeEvent.cacheLocation)) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        finishEvent(player, false);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMeteorLand(EntityChangeBlockEvent event) {
        if (activeEvent == null || activeEvent.meteorEntity == null) return;
        if (!event.getEntity().getUniqueId().equals(activeEvent.meteorEntity.getUniqueId())) return;
        event.setCancelled(true);
        event.getEntity().remove();
        activeEvent.meteorEntity = null;
        Location impact = activeEvent.cacheLocation.clone().add(0.5, 0.5, 0.5);
        World world = impact.getWorld();
        world.spawnParticle(Particle.EXPLOSION, impact, 3, 0.5, 0.5, 0.5, 0.0);
        world.spawnParticle(Particle.FLAME, impact, 100, 1.2, 0.8, 1.2, 0.04);
        world.playSound(impact, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.7f);
        broadcastRaw("&6&lMETEOR IMPACT! &fThe meteor cache is ready to claim!");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCacheBreak(BlockBreakEvent event) {
        if (activeEvent == null || activeEvent.cacheLocation == null) return;
        if (!sameBlock(event.getBlock().getLocation(), activeEvent.cacheLocation)) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(color(getConfig().getString("messages.cache-protected", "&eRight-click the event chest to claim the reward.")));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (activeEvent != null) activeEvent.bossBar.addPlayer(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (activeEvent != null) activeEvent.bossBar.removePlayer(event.getPlayer());
    }

    private boolean sameBlock(Location first, Location second) {
        return first.getWorld() != null && second.getWorld() != null
                && first.getWorld().getUID().equals(second.getWorld().getUID())
                && first.getBlockX() == second.getBlockX()
                && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            showEventList(sender);
            return true;
        }
        if (args[0].equalsIgnoreCase("help")) {
            showHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status":
                showStatus(sender);
                return true;
            case "list":
                showEventList(sender);
                return true;
            case "start":
                if (!requireAdmin(sender)) return true;
                if (args.length < 2) { sendRaw(sender, "&cUsage: /gevents start <koth|treasure|meteor>"); return true; }
                EventType type = EventType.parse(args[1]);
                if (type == null) { sendRaw(sender, "&cUnknown event type. Use koth, treasure or meteor."); return true; }
                startEvent(type, sender);
                return true;
            case "stop":
                if (!requireAdmin(sender)) return true;
                if (activeEvent == null) { sendRaw(sender, "&cThere is no active event."); return true; }
                EventType stopped = activeEvent.type;
                finishEvent(null, false);
                broadcastRaw(getConfig().getString("messages.stopped", "&c%event% was stopped by staff.").replace("%event%", eventName(stopped)));
                return true;
            case "setlocation":
                if (!requireAdmin(sender)) return true;
                if (!(sender instanceof Player)) { sendRaw(sender, "&cOnly players can set event locations."); return true; }
                if (args.length < 2) { sendRaw(sender, "&cUsage: /gevents setlocation <koth|treasure|meteor>"); return true; }
                EventType locationType = EventType.parse(args[1]);
                if (locationType == null) { sendRaw(sender, "&cUnknown event type."); return true; }
                Player player = (Player) sender;
                Location loc = player.getLocation().getBlock().getLocation();
                getConfig().set("locations." + locationType.key + ".world", loc.getWorld().getName());
                getConfig().set("locations." + locationType.key + ".x", loc.getX() + 0.5);
                getConfig().set("locations." + locationType.key + ".y", loc.getY());
                getConfig().set("locations." + locationType.key + ".z", loc.getZ() + 0.5);
                saveConfig();
                sendRaw(sender, "&aSaved &f" + locationType.key + " &alocation at &f" + loc.getWorld().getName()
                        + " &7(X: &f" + loc.getBlockX() + "&7, Y: &f" + loc.getBlockY() + "&7, Z: &f" + loc.getBlockZ() + "&7). ");
                return true;
            case "reload":
                if (!requireAdmin(sender)) return true;
                reloadConfig();
                startAutomaticEvents();
                sendRaw(sender, "&aGawharaEvents configuration reloaded.");
                return true;
            default:
                showHelp(sender);
                return true;
        }
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission("gawharaevents.admin")) return true;
        sendRaw(sender, getConfig().getString("messages.no-permission", "&cYou do not have permission."));
        return false;
    }

    private void showHelp(CommandSender sender) {
        sendRaw(sender, "&8&m-------------&b✦ &lGAWHARA EVENTS &8&m-------------");
        sendRaw(sender, "&b/gevents &7- Show event information");
        sendRaw(sender, "&b/gevents status &7- Current event status");
        if (sender.hasPermission("gawharaevents.admin")) {
            sendRaw(sender, "&b/gevents start <type> &7- Start koth, treasure or meteor");
            sendRaw(sender, "&b/gevents setlocation <type> &7- Set event location");
            sendRaw(sender, "&b/gevents stop &7- Stop current event");
            sendRaw(sender, "&b/gevents reload &7- Reload configuration");
        }
    }

    private void showEventList(CommandSender sender) {
        sendRaw(sender, "&8&m-------------&b✦ &lGawhara Events &8&m-------------");
        for (EventType type : EventType.values()) {
            boolean enabled = getConfig().getBoolean("events." + type.key + ".enabled", true);
            double reward = Math.max(0.0, getConfig().getDouble("rewards." + type.key, 0.0));
            String status = enabled ? "&aEnabled" : "&cDisabled";
            sendRaw(sender, "&8• " + eventName(type) + " &8| " + status + " &8| &e" + economy.format(reward));
        }
        if (activeEvent == null) {
            sendRaw(sender, "&7No event is running right now.");
        } else {
            long seconds = Math.max(0L, (activeEvent.endsAt - System.currentTimeMillis() + 999L) / 1000L);
            sendRaw(sender, "&bNow running: " + eventName(activeEvent.type) + " &8| &f" + seconds + "s left");
        }
        sendRaw(sender, "&7Use &b/gevents status &7for more details.");
    }

    private void showStatus(CommandSender sender) {
        if (activeEvent == null) {
            sendRaw(sender, getConfig().getString("messages.no-event", "&7There is currently no active event."));
            return;
        }
        long seconds = Math.max(0L, (activeEvent.endsAt - System.currentTimeMillis() + 999L) / 1000L);
        sendRaw(sender, getConfig().getString("messages.status", "&bActive event: &f%event% &8| &bTime left: &f%time%s")
                .replace("%event%", eventName(activeEvent.type)).replace("%time%", String.valueOf(seconds)));
        if (activeEvent.type == EventType.KOTH) {
            String holder = activeEvent.captureOwner == null ? "None" : Bukkit.getOfflinePlayer(activeEvent.captureOwner).getName();
            sendRaw(sender, "&7Current hill holder: &f" + (holder == null ? "None" : holder)
                    + " &8| &7Progress: &f" + activeEvent.captureSeconds + "s");
        } else {
            sendRaw(sender, "&7Cache world: &f" + activeEvent.cacheLocation.getWorld().getName());
        }
    }

    private void send(CommandSender sender, String path, String... replacements) {
        if (sender == null) return;
        String text = getConfig().getString(path, "");
        for (int i = 0; i + 1 < replacements.length; i += 2) text = text.replace(replacements[i], replacements[i + 1]);
        sendRaw(sender, text);
    }

    private void sendRaw(CommandSender sender, String text) {
        if (sender != null) sender.sendMessage(color(getConfig().getString("settings.prefix", "&8[&bGawharaEvents&8] &r") + text));
    }

    private void broadcast(String path, String... replacements) {
        String text = getConfig().getString(path, "");
        for (int i = 0; i + 1 < replacements.length; i += 2) text = text.replace(replacements[i], replacements[i + 1]);
        broadcastRaw(text);
    }

    private void broadcastRaw(String text) {
        Bukkit.broadcastMessage(color(getConfig().getString("settings.prefix", "&8[&bGawharaEvents&8] &r") + text));
    }

    private String eventName(EventType type) {
        return getConfig().getString("event-names." + type.key, type.key.toUpperCase(Locale.ROOT));
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(Arrays.asList("status", "list", "help"));
            if (sender.hasPermission("gawharaevents.admin")) options.addAll(Arrays.asList("start", "stop", "setlocation", "reload"));
            return filter(options, args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("start") || args[0].equalsIgnoreCase("setlocation"))) {
            return filter(Arrays.asList("koth", "treasure", "meteor"), args[1]);
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> values, String prefix) {
        List<String> result = new ArrayList<>();
        for (String value : values) if (value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) result.add(value);
        return result;
    }
}
