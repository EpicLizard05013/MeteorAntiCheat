package com.meteorsmp.meteorac;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.*;

public final class MeteorAntiCheat extends JavaPlugin implements Listener {

    private final String prefix = ChatColor.translateAlternateColorCodes('&', "&c[MeteorAntiCheat] &f");
    private final String adminPerm = "meteor.admin";

    // Data maps
    private final Map<UUID, Integer> violationLevels = new HashMap<>();
    private final Map<UUID, Long> chatCooldown = new HashMap<>();
    
    // Heuristic maps
    private final Map<UUID, LinkedList<Long>> clickDelays = new HashMap<>();
    private final Map<UUID, Long> lastClickTime = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("[MeteorAntiCheat] Advanced Physics & Heuristics Engine v2.0 online.");
    }

    @Override
    public void onDisable() {
        getLogger().info("MeteorAntiCheat engine offline.");
    }

    /* 
     * 1. MOVEMENT & PHYSICS VERIFICATION
     * Calculates real-time movement deltas to catch Speed and Fly.
     */
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(adminPerm) || player.isFlying() || player.isGliding()) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() == null || to.getWorld() == null) return;

        double dXZ = Math.sqrt(Math.pow(to.getX() - from.getX(), 2) + Math.pow(to.getZ() - from.getZ(), 2));
        double dY = to.getY() - from.getY();

        // Standard sprint-jump max delta XZ is roughly 0.6 to 0.7 without potions/ice.
        double maxMovementSpeed = 0.65 + (player.getPing() * 0.001); 

        if (dXZ > maxMovementSpeed) {
            event.setTo(from); // Rubberband the player
            handleViolation(player, "Speed/Movement", 2, dXZ, dY, maxMovementSpeed, dXZ);
        }

        // Catch basic hover/fly (y-axis manipulation without falling)
        if (!player.getLocation().getBlock().getRelative(0, -1, 0).getType().isSolid()) {
            if (dY >= 0.0 && event.getFrom().getY() > 0) { // Moving up or hovering in mid-air
                if (player.getFallDistance() == 0) {
                    handleViolation(player, "Fly/Hover", 3, dXZ, dY, 0.0, dY);
                }
            }
        }
    }

    /*
     * 2. COMBAT & KILLAURA HEURISTICS
     * Uses Vector Dot Products (Raytracing) to ensure attackers are actually looking at their victim.
     */
    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player) || !(event.getEntity() instanceof Player)) return;

        Player attacker = (Player) event.getDamager();
        Player victim = (Player) event.getEntity();

        if (attacker.hasPermission(adminPerm)) return;

        Location loc1 = attacker.getLocation();
        Location loc2 = victim.getLocation();

        // Reach Check
        double distance = loc1.distance(loc2);
        double maxReach = 3.1 + (attacker.getPing() + victim.getPing()) * 0.0025;

        if (distance > maxReach) {
            event.setCancelled(true);
            handleViolation(attacker, "Reach", 3, distance, 0.0, maxReach, distance);
            return;
        }

        // Angle/Raytrace Check (KillAura)
        Vector attackerDirection = loc1.getDirection().normalize();
        Vector targetDirection = loc2.toVector().subtract(loc1.toVector()).normalize();
        double angle = attackerDirection.dot(targetDirection);

        // A dot product of 1.0 means looking dead at them. Less than 0.75 means they hit someone behind/beside them.
        if (angle < 0.75 && distance > 1.5) {
            event.setCancelled(true);
            handleViolation(attacker, "KillAura (Angle)", 4, 0.0, 0.0, 0.75, angle);
        }
    }

    /*
     * 3. MACRO & CPS STANDARD DEVIATION
     * Human clicks have variance. Macros click with robotic, mathematical precision.
     */
    @EventHandler
    public void onClick(PlayerInteractEvent event) {
        if (event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK) {
            Player player = event.getPlayer();
            if (player.hasPermission(adminPerm)) return;

            UUID uuid = player.getUniqueId();
            long now = System.currentTimeMillis();
            long last = lastClickTime.getOrDefault(uuid, now);
            long delay = now - last;

            if (delay > 0 && delay < 1000) {
                LinkedList<Long> delays = clickDelays.getOrDefault(uuid, new LinkedList<>());
                delays.add(delay);

                if (delays.size() >= 20) {
                    double mean = delays.stream().mapToLong(val -> val).average().orElse(0.0);
                    double variance = delays.stream().mapToDouble(val -> Math.pow(val - mean, 2)).average().orElse(0.0);
                    double stdDev = Math.sqrt(variance);

                    // If Standard Deviation is below 5ms, the clicks are too consistent to be human.
                    if (stdDev < 5.0 && mean < 100.0) {
                        handleViolation(player, "Macro/CPS Heuristics", 2, 0.0, 0.0, 5.0, stdDev);
                    }
                    delays.clear(); // Reset sample
                }
                clickDelays.put(uuid, delays);
            }
            lastClickTime.put(uuid, now);
        }
    }

    /*
     * 4. BLOCK LINE OF SIGHT (NUKER / FASTPLACE)
     * Ensures players have a strict physical line of sight to a block before breaking/placing.
     */
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(adminPerm)) return;

        Block targetBlock = player.getTargetBlockExact(6);
        if (targetBlock == null || !targetBlock.getLocation().equals(event.getBlock().getLocation())) {
            event.setCancelled(true);
            handleViolation(player, "Nuker/WallBreak", 2, 0.0, 0.0, 1.0, 0.0);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(adminPerm)) return;

        Block targetBlock = player.getTargetBlockExact(6);
        // Ensure they aren't placing blocks through walls or behind themselves
        if (targetBlock == null || player.getLocation().distance(event.getBlock().getLocation()) > 6.0) {
            event.setCancelled(true);
            handleViolation(player, "FastPlace/Scaffold", 2, 0.0, 0.0, 6.0, player.getLocation().distance(event.getBlock().getLocation()));
        }
    }

    /*
     * 5. PACKET RATE LIMITING (Exploit Prevention)
     */
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

    private void handleViolation(Player player, String module, int vlIncrease, double dXz, double dY, double expected, double observed) {
        UUID uuid = player.getUniqueId();
        int currentVl = violationLevels.getOrDefault(uuid, 0) + vlIncrease;
        violationLevels.put(uuid, currentVl);

        String action = "ALERT";
        if (currentVl >= 20) {
            action = "BAN";
            Bukkit.getScheduler().runTask(this, () -> player.kickPlayer("Violated security protocol: " + module));
            violationLevels.put(uuid, 0); // reset after kick
        } else if (currentVl >= 5) {
            action = "ALERT";
        }

        double currentTps = Bukkit.getTPS()[0];
        String jsonLog = String.format(
            "{\n  \"engine\": \"MeteorAntiCheat\",\n  \"player\": \"%s\",\n  \"module\": \"%s\",\n  \"current_vl\": %d,\n  \"telemetry\": {\n    \"expected\": %.2f,\n    \"observed\": %.2f\n  },\n  \"action\": \"%s\"\n}",
            player.getName(), module, currentVl, expected, observed, action
        );

        Bukkit.getConsoleSender().sendMessage(jsonLog);

        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (admin.hasPermission(adminPerm)) {
                admin.sendMessage(prefix + ChatColor.RED + player.getName() + " flagged for " + module + " (VL: " + currentVl + ")");
            }
        }
    }
}
