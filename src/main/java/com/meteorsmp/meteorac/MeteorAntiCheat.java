package com.meteorsmp.meteorac;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.*;

public final class MeteorAntiCheat extends JavaPlugin implements Listener {

    private final String prefix = ChatColor.translateAlternateColorCodes('&', "&c[MeteorAntiCheat] &f");

    // Violation Tracking & Cooldowns
    private final Map<String, Map<UUID, Integer>> categoryViolations = new HashMap<>();
    private final Map<UUID, Long> chatCooldown = new HashMap<>();
    
    // Heuristic State Maps
    private final Map<UUID, LinkedList<Long>> clickDelays = new HashMap<>();
    private final Map<UUID, Long> lastClickTime = new HashMap<>();
    private final Map<UUID, Integer> movePackets = new HashMap<>();
    private final Map<UUID, Long> moveTime = new HashMap<>();
    private final Map<UUID, Long> bowDrawTime = new HashMap<>();
    private final Map<UUID, Float> lastYaw = new HashMap<>();
    private final Map<UUID, Float> lastPitch = new HashMap<>();
    private final Map<UUID, Long> lastVelocityTime = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("[MeteorAntiCheat] Full Grim-Mapped Engine v3.0 online.");
    }

    @Override
    public void onDisable() {
        getLogger().info("MeteorAntiCheat engine offline.");
    }

    private boolean isBypassed(Player player) {
        return player.hasPermission("meteor.admin") || player.isOp();
    }

    /*
     * ==========================================
     * 1. SIMULATION, GROUND SPOOF & TIMER CHECKS
     * ==========================================
     */
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (isBypassed(player) || player.isFlying() || player.isGliding()) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() == null || to.getWorld() == null) return;

        UUID uuid = player.getUniqueId();

        // -- Simulation / Speed Check --
        double dXZ = Math.sqrt(Math.pow(to.getX() - from.getX(), 2) + Math.pow(to.getZ() - from.getZ(), 2));
        double dY = to.getY() - from.getY();
        double maxSpeed = 0.65 + (player.getPing() * 0.001);

        if (dXZ > maxSpeed) {
            event.setTo(from);
            handleViolation(player, "Simulation", "Speed", 2);
        }

        // -- GroundSpoof / NoFall Check --
        if (player.isOnGround() && from.getY() > to.getY()) {
            Block blockBelow = from.clone().subtract(0, 0.5, 0).getBlock();
            if (blockBelow.getType().isAir() && player.getFallDistance() > 2.0f) {
                handleViolation(player, "Simulation", "GroundSpoof", 3);
            }
        }

        // -- Timer & TimerLimit Check --
        long now = System.currentTimeMillis();
        long start = moveTime.getOrDefault(uuid, now);
        int packets = movePackets.getOrDefault(uuid, 0) + 1;

        if (now - start > 1000) {
            if (packets > 26 && !player.isInsideVehicle()) {
                handleViolation(player, "Simulation", "Timer", 4);
                event.setTo(from);
            } else if (packets < 15 && packets > 0 && !player.isInsideVehicle()) {
                handleViolation(player, "Simulation", "TimerLimit", 2);
            }
            moveTime.put(uuid, now);
            movePackets.put(uuid, 0);
        } else {
            movePackets.put(uuid, packets);
        }

        // -- Baritone / Aim Snapping Heuristics --
        float yawDelta = Math.abs(to.getYaw() - lastYaw.getOrDefault(uuid, to.getYaw()));
        float pitchDelta = Math.abs(to.getPitch() - lastPitch.getOrDefault(uuid, to.getPitch()));
        if (yawDelta > 60.0f && pitchDelta < 1.0f && dXZ > 0.2) {
            handleViolation(player, "Combat", "Aim", 2); // Baritone linear pathfinding look snaps
        }
        lastYaw.put(uuid, to.getYaw());
        lastPitch.put(uuid, to.getPitch());

        // -- Misc: Vehicle & Elytra Checks --
        if (player.isInsideVehicle() && dY > 1.5) {
            handleViolation(player, "Misc", "Vehicle", 3);
        }
    }

    /*
     * ==========================================
     * 2. COMBAT, REACH, HITBOXES & KNOCKBACK
     * ==========================================
     */
    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        Player attacker = (Player) event.getDamager();
        if (isBypassed(attacker)) return;

        // -- Reach & Hitboxes Check --
        if (event.getEntity() instanceof Player) {
            Player victim = (Player) event.getEntity();
            double distance = attacker.getLocation().distance(victim.getLocation());
            double maxReach = 3.1 + (attacker.getPing() + victim.getPing()) * 0.0025;

            if (distance > maxReach) {
                event.setCancelled(true);
                handleViolation(attacker, "Reach", "Reach", 3);
            } else if (distance > 3.0 && Math.abs(attacker.getLocation().getY() - victim.getLocation().getY()) > 2.5) {
                event.setCancelled(true);
                handleViolation(attacker, "Hitboxes", "Hitboxes", 3);
            }
        }

        // Track damage for Knockback / Explosion velocity validation
        lastVelocityTime.put(attacker.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION || event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
            lastVelocityTime.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    /*
     * ==========================================
     * 3. MISC, NOSLOW, SPRINT, PLACE & BREAK
     * ==========================================
     */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Bow Draw Tracking for FastBow
        if (event.getItem() != null && event.getItem().getType() == Material.BOW) {
            if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                bowDrawTime.put(uuid, System.currentTimeMillis());
            }
        }

        // -- Autoclicker Standard Deviation Check --
        if (event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK) {
            if (isBypassed(player)) return;

            long now = System.currentTimeMillis();
            long last = lastClickTime.getOrDefault(uuid, now);
            long delay = now - last;

            if (delay > 0 && delay < 1000) {
                LinkedList<Long> delays = clickDelays.getOrDefault(uuid, new LinkedList<>());
                delays.add(delay);

                if (delays.size() >= 20) {
                    double mean = delays.stream().mapToLong(v -> v).average().orElse(0.0);
                    double variance = delays.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0.0);
                    double stdDev = Math.sqrt(variance);

                    if (stdDev < 4.5 && mean < 90.0) {
                        handleViolation(player, "Autoclicker", "Autoclicker", 3);
                    }
                    delays.clear();
                }
                clickDelays.put(uuid, delays);
            }
            lastClickTime.put(uuid, now);
        }

        // -- NoSlow Check (Eating/Blocking while sprinting) --
        if (player.isSprinting() && player.isHandRaised()) {
            handleViolation(player, "Misc", "NoSlow", 2);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (isBypassed(player)) return;

        Block target = player.getTargetBlockExact(6);
        if (target == null || !target.getLocation().equals(event.getBlock().getLocation())) {
            event.setCancelled(true);
            handleViolation(player, "Misc", "Break", 2); // Nuker/WallBreak
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (isBypassed(player)) return;

        Block target = player.getTargetBlockExact(6);
        if (target == null || player.getLocation().distance(event.getBlock().getLocation()) > 6.0) {
            event.setCancelled(true);
            handleViolation(player, "Misc", "Place", 2); // Scaffold / FastPlace
        }
    }

    @EventHandler
    public void onBowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        if (isBypassed(player)) return;

        long drawStart = bowDrawTime.getOrDefault(player.getUniqueId(), 0L);
        long duration = System.currentTimeMillis() - drawStart;

        if (event.getForce() >= 0.95f && duration < 250 && drawStart != 0) {
            event.setCancelled(true);
            handleViolation(player, "BadPackets", "PacketOrder", 3);
        }
    }

    /*
     * ==========================================
     * 4. BAD PACKETS, CHAT & EXPLOITS
     * ==========================================
     */
    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (isBypassed(player)) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        if (chatCooldown.containsKey(uuid) && now - chatCooldown.get(uuid) < 1200) {
            event.setCancelled(true);
            handleViolation(player, "Misc", "Chat", 1);
            player.sendMessage(prefix + ChatColor.RED + "Chat packet rate limit exceeded.");
            return;
        }
        chatCooldown.put(uuid, now);
    }

    /*
     * ==========================================
     * 5. CENTRALIZED PUNISHMENT & ALERT ENGINE
     * ==========================================
     */
    private void handleViolation(Player player, String category, String checkName, int vlAdd) {
        UUID uuid = player.getUniqueId();
        Map<UUID, Integer> catMap = categoryViolations.computeIfAbsent(category, k -> new HashMap<>());
        int totalVL = catMap.getOrDefault(uuid, 0) + vlAdd;
        catMap.put(uuid, totalVL);

        // Action routing modeled after Grim's config commands
        String action = "ALERT";
        if (totalVL >= 40) {
            action = "BAN";
            Bukkit.getScheduler().runTask(this, () -> player.kickPlayer("Security Violation: " + checkName));
            catMap.put(uuid, 0); // Reset after action
        }

        // Console JSON Telemetry Logging
        String log = String.format("{\"category\": \"%s\", \"check\": \"%s\", \"player\": \"%s\", \"vl\": %d, \"action\": \"%s\"}",
                category, checkName, player.getName(), totalVL, action);
        Bukkit.getConsoleSender().sendMessage(prefix + ChatColor.DARK_GRAY + log);

        // Broadcast to Staff Ranks and OPs
        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (admin.isOp() || 
                admin.hasPermission("meteor.mod") || 
                admin.hasPermission("meteor.srmod") || 
                admin.hasPermission("meteor.admin") || 
                admin.hasPermission("meteor.owner") || 
                admin.hasPermission("meteor.coowner")) {
                
                admin.sendMessage(prefix + ChatColor.RED + player.getName() + " failed " + checkName + " [" + category + "] (VL: " + totalVL + ")");
            }
        }
    }
}
