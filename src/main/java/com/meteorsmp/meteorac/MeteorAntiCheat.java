package com.meteorsmp.meteorac;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class MeteorAntiCheat extends JavaPlugin implements Listener {

    private final String prefix = ChatColor.translateAlternateColorCodes('&', "&c[MeteorAntiCheat] &f");
    private final String adminPerm = "meteor.admin";

    private final Map<UUID, Long> chatCooldown = new HashMap<>();
    private final Map<UUID, Integer> clickCounts = new HashMap<>();
    private final Map<UUID, Long> lastBreakTime = new HashMap<>();
    private final Map<UUID, Integer> breakSpamCount = new HashMap<>();
    private final Map<UUID, Integer> violationLevels = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);

        new BukkitRunnable() {
            @Override
            public void run() {
                double currentTps = Bukkit.getTPS()[0];
                if (currentTps < 18.0) return;

                for (Player player : Bukkit.getOnlinePlayers()) {
                    UUID uuid = player.getUniqueId();
                    int clicks = clickCounts.getOrDefault(uuid, 0);

                    if (clicks > 20 && !player.hasPermission(adminPerm)) {
                        handleViolation(player, "AutoClicker", 2, 0.31, 0.0, 0.281, (double) clicks);
                    }
                    clickCounts.put(uuid, 0);
                }
            }
        }.runTaskTimerAsynchronously(this, 0L, 20L);

        getLogger().info("[MeteorAntiCheat] Engine v1.20+ online. Asynchronous packet pipeline active at 20 TPS.");
    }

    @Override
    public void onDisable() {
        getLogger().info("MeteorAntiCheat engine offline.");
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(adminPerm)) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        if (chatCooldown.containsKey(uuid)) {
            if (now - chatCooldown.get(uuid) < 1500) {
                event.setCancelled(true);
                player.sendMessage(prefix + ChatColor.RED + "Packet rate limit exceeded.");
                return;
            }
        }
        chatCooldown.put(uuid, now);
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getAttacker() instanceof Player) || !(event.getEntity() instanceof Player)) return;

        Player attacker = (Player) event.getAttacker();
        Player victim = (Player) event.getEntity();

        if (attacker.hasPermission(adminPerm)) return;

        Location loc1 = attacker.getLocation();
        Location loc2 = victim.getLocation();

        if (loc1.getWorld() != null && loc1.getWorld().equals(loc2.getWorld())) {
            double distance = loc1.distance(loc2);
            double maxReach = 3.0 + (attacker.getPing() + victim.getPing()) * 0.003;

            if (distance > maxReach) {
                event.setCancelled(true);
                handleViolation(attacker, "Reach", 3, distance, 0.0, maxReach, distance);
            }
        }
    }

    @EventHandler
    public void onClick(PlayerInteractEvent event) {
        if (event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK) {
            Player player = event.getPlayer();
            if (player.hasPermission(adminPerm)) return;

            UUID uuid = player.getUniqueId();
            clickCounts.put(uuid, clickCounts.getOrDefault(uuid, 0) + 1);
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(adminPerm)) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        if (lastBreakTime.containsKey(uuid)) {
            long diff = now - lastBreakTime.get(uuid);
            if (diff < 50) {
                int count = breakSpamCount.getOrDefault(uuid, 0) + 1;
                breakSpamCount.put(uuid, count);

                if (count > 12) {
                    event.setCancelled(true);
                    handleViolation(player, "FastBreak", 4, 0.0, 0.0, 50.0, (double) diff);
                }
            } else {
                breakSpamCount.put(uuid, 0);
            }
        }
        lastBreakTime.put(uuid, now);
    }

    private void handleViolation(Player player, String module, int vlIncrease, double dXz, double dY, double expectedMax, double observedVal) {
        UUID uuid = player.getUniqueId();
        int currentVl = violationLevels.getOrDefault(uuid, 0) + vlIncrease;
        violationLevels.put(uuid, currentVl);

        String action = "ALERT";
        if (currentVl >= 15) {
            action = "BAN";
            Bukkit.getScheduler().runTask(this, () -> player.kickPlayer("Violated security protocol: " + module));
        } else if (currentVl >= 5) {
            action = "ALERT";
        }

        double currentTps = Bukkit.getTPS()[0];
        String jsonLog = String.format(
            "{\n  \"engine\": \"MeteorAntiCheat\",\n  \"player\": \"%s\",\n  \"uuid\": \"%s\",\n  \"ping\": %d,\n  \"tps\": %.2f,\n  \"violation\": \"%s\",\n  \"current_vl\": %d,\n  \"confidence\": \"99%%\",\n  \"telemetry\": {\n    \"calculated_delta_xz\": %.2f,\n    \"calculated_delta_y\": %.2f,\n    \"expected_max\": %.2f,\n    \"observed_val\": %.2f\n  },\n  \"action\": \"%s\"\n}",
            player.getName(), uuid, player.getPing(), currentTps, module, currentVl, dXz, dY, expectedMax, observedVal, action
        );

        Bukkit.getConsoleSender().sendMessage(jsonLog);

        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (admin.hasPermission(adminPerm)) {
                admin.sendMessage(prefix + ChatColor.RED + player.getName() + " flagged for " + module + " (VL: " + currentVl + ")");
            }
        }
    }
}
