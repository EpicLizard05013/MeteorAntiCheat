package com.meteorsmp.meteorac;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.util.Vector;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class MeteorAntiCheat extends JavaPlugin implements Listener, PluginMessageListener, CommandExecutor {

    private final String prefix = ChatColor.translateAlternateColorCodes('&', "&c[MeteorAntiCheat] &f");

    // Violation Tracking & Cooldowns
    private final Map<String, Map<UUID, Integer>> categoryViolations = new HashMap<>();
    private final Map<UUID, String> playerNameCache = new HashMap<>();
    
    // Heuristic State Maps
    private final Map<UUID, LinkedList<Long>> clickDelays = new HashMap<>();
    private final Map<UUID, Long> lastClickTime = new HashMap<>();
    private final Map<UUID, Integer> movePackets = new HashMap<>();
    private final Map<UUID, Long> moveTime = new HashMap<>();
    private final Map<UUID, Long> bowDrawTime = new HashMap<>();
    private final Map<UUID, Float> lastYaw = new HashMap<>();
    private final Map<UUID, Float> lastPitch = new HashMap<>();
    private final Map<UUID, Integer> aimBuffer = new HashMap<>();
    
    // Advanced State & Client Trackers
    private final Set<UUID> openInventories = new HashSet<>();
    private final Map<UUID, Vector> expectedVelocity = new HashMap<>();
    private final Map<UUID, Long> velocityTime = new HashMap<>();
    private final Map<UUID, Long> totemPopTime = new HashMap<>();
    private final Map<UUID, String> playerClientBrands = new HashMap<>();

    // Auto Anchor Heuristics
    private final Map<UUID, Long> lastAnchorInteractTime = new HashMap<>();
    private final Map<UUID, Integer> anchorActionBuffer = new HashMap<>();

    // Control States
    private boolean testingMode = false;
    private final Set<UUID> alertRecipients = new HashSet<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        
        // Register Command Executor
        if (getCommand("meteorac") != null) {
            getCommand("meteorac").setExecutor(this);
        }
        
        // Register Plugin Messaging Channels for Client Brand Detection
        getServer().getMessenger().registerIncomingPluginChannel(this, "minecraft:brand", this);
        try {
            getServer().getMessenger().registerIncomingPluginChannel(this, "MC|Brand", this);
        } catch (Exception ignored) {}

        getLogger().info("[MeteorAntiCheat] Enterprise Engine v4.4 (AutoAnchor & Alert System Enabled) online.");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this, "minecraft:brand");
        try {
            getServer().getMessenger().unregisterIncomingPluginChannel(this, "MC|Brand");
        } catch (Exception ignored) {}
        getLogger().info("MeteorAntiCheat engine offline.");
    }

    private boolean isBypassed(Player player) {
        // If testing mode is active, owner bypasses, but opped staff / srmod can test checks.
        if (testingMode && (player.isOp() || player.hasPermission("meteor.srmod"))) {
            return false;
        }
        return player.hasPermission("meteor.bypass") || player.hasPermission("meteor.owner");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        playerNameCache.put(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    /*
     * ==========================================
     * 1. COMMAND EXECUTOR (/meteorac)
     * ==========================================
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!label.equalsIgnoreCase("meteorac")) return false;

        if (!sender.hasPermission("meteor.admin") && !sender.isOp()) {
            sender.sendMessage(prefix + ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(prefix + ChatColor.YELLOW + "Subcommands:");
            sender.sendMessage(ChatColor.WHITE + "/meteorac perms <player> - Toggle alert subscriptions for a player");
            sender.sendMessage(ChatColor.WHITE + "/meteorac test - Toggle global anti-cheat testing mode");
            sender.sendMessage(ChatColor.WHITE + "/meteorac logs <player> - View active violation records");
            return true;
        }

        if (args[0].equalsIgnoreCase("test")) {
            testingMode = !testingMode;
            sender.sendMessage(prefix + ChatColor.YELLOW + "Testing Mode is now: " + (testingMode ? ChatColor.GREEN + "ENABLED (Staff can be flagged)" : ChatColor.RED + "DISABLED"));
            return true;
        }

        if (args[0].equalsIgnoreCase("perms")) {
            if (args.length < 2) {
                sender.sendMessage(prefix + ChatColor.RED + "Usage: /meteorac perms <player>");
                return true;
            }
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(prefix + ChatColor.RED + "Player not found online!");
                return true;
            }

            UUID targetUuid = target.getUniqueId();
            if (alertRecipients.contains(targetUuid)) {
                alertRecipients.remove(targetUuid);
                sender.sendMessage(prefix + ChatColor.YELLOW + target.getName() + " will no longer receive anti-cheat alerts.");
                target.sendMessage(prefix + ChatColor.RED + "You have been removed from anti-cheat alerts.");
            } else {
                alertRecipients.add(targetUuid);
                sender.sendMessage(prefix + ChatColor.GREEN + target.getName() + " will now receive anti-cheat alerts!");
                target.sendMessage(prefix + ChatColor.GREEN + "You have been added to anti-cheat alerts.");
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("logs")) {
            if (args.length < 2) {
                sender.sendMessage(prefix + ChatColor.RED + "Usage: /meteorac logs <player>");
                return true;
            }
            String targetName = args[1];
            sender.sendMessage(prefix + ChatColor.YELLOW + "--- Violation Logs for " + targetName + " ---");

            boolean found = false;
            for (Map.Entry<String, Map<UUID, Integer>> entry : categoryViolations.entrySet()) {
                String category = entry.getKey();
                for (Map.Entry<UUID, Integer> playerEntry : entry.getValue().entrySet()) {
                    UUID uuid = playerEntry.getKey();
                    String cachedName = playerNameCache.get(uuid);
                    if (cachedName != null && cachedName.equalsIgnoreCase(targetName)) {
                        int vl = playerEntry.getValue();
                        sender.sendMessage(ChatColor.WHITE + "Category [" + ChatColor.YELLOW + category + ChatColor.WHITE + "] Total VL: " + ChatColor.RED + vl);
                        found = true;
                    }
                }
            }

            if (!found) {
                sender.sendMessage(ChatColor.GRAY + "No recorded violations found for this player.");
            }
            return true;
        }

        sender.sendMessage(prefix + ChatColor.RED + "Unknown subcommand. Type /meteorac for help.");
        return true;
    }

    /*
     * ==========================================
     * 2. CLIENT BRAND DETECTOR LISTENER
     * ==========================================
     */
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals("minecraft:brand") && !channel.equals("MC|Brand")) return;

        try {
            String brand;
            if (message.length > 0) {
                brand = new String(message, 1, message.length - 1, StandardCharsets.UTF_8);
            } else {
                brand = new String(message, StandardCharsets.UTF_8);
            }

            playerClientBrands.put(player.getUniqueId(), brand);
            playerNameCache.put(player.getUniqueId(), player.getName());

            String log = String.format("{\"category\": \"System\", \"check\": \"ClientBrand\", \"player\": \"%s\", \"brand\": \"%s\"}",
                    player.getName(), brand);
            getLogger().info(log);

            for (Player admin : Bukkit.getOnlinePlayers()) {
                if (alertRecipients.contains(admin.getUniqueId()) || admin.hasPermission("meteor.admin") || admin.hasPermission("meteor.mod") || admin.isOp()) {
                    admin.sendMessage(prefix + ChatColor.YELLOW + player.getName() + " connected using client: " + ChatColor.WHITE + brand);
                }
            }
        } catch (Exception e) {
            playerClientBrands.put(player.getUniqueId(), "Unknown");
        }
    }

    /*
     * ==========================================
     * 3. MOVEMENT, SIMULATION & WORLD EXPLOITS
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
        playerNameCache.put(uuid, player.getName());
        
        double dXZ = Math.sqrt(Math.pow(to.getX() - from.getX(), 2) + Math.pow(to.getZ() - from.getZ(), 2));
        double dY = to.getY() - from.getY();
        double maxSpeed = 0.65 + (player.getPing() * 0.001);

        // -- Simulation / Speed --
        if (dXZ > maxSpeed) {
            event.setTo(from);
            handleViolation(player, "Movement", "Speed", 2);
        }

        // -- GroundSpoof / NoFall --
        if (player.isOnGround() && from.getY() > to.getY()) {
            Block blockBelow = from.clone().subtract(0, 0.5, 0).getBlock();
            if (blockBelow.getType().isAir() && player.getFallDistance() > 2.0f) {
                handleViolation(player, "Movement", "GroundSpoof", 3);
            }
        }

        // -- Jesus (Water Walk) Check --
        Material belowMat = from.clone().subtract(0, 0.1, 0).getBlock().getType();
        Material atMat = from.getBlock().getType();
        if ((belowMat == Material.WATER || belowMat == Material.LAVA) && atMat == Material.AIR) {
            if (dY == 0.0 && !player.isInsideVehicle() && !player.isSwimming()) {
                handleViolation(player, "Movement", "Jesus", 3);
            }
        }

        // -- InventoryMove Check --
        if (openInventories.contains(uuid) && dXZ > 0.15 && player.getFallDistance() == 0 && !player.isInsideVehicle()) {
            handleViolation(player, "Exploit", "InventoryMove", 2);
            event.setTo(from);
        }

        // -- Timer & TimerLimit --
        long now = System.currentTimeMillis();
        long start = moveTime.getOrDefault(uuid, now);
        int packets = movePackets.getOrDefault(uuid, 0) + 1;

        if (now - start > 1000) {
            if (packets > 26 && !player.isInsideVehicle()) {
                handleViolation(player, "Movement", "Timer", 4);
                event.setTo(from);
            } else if (packets < 15 && packets > 0 && !player.isInsideVehicle()) {
                handleViolation(player, "Movement", "TimerLimit", 2);
            }
            moveTime.put(uuid, now);
            movePackets.put(uuid, 0);
        } else {
            movePackets.put(uuid, packets);
        }

        // -- Velocity / Anti-Knockback Check --
        if (expectedVelocity.containsKey(uuid)) {
            long timeSinceVel = System.currentTimeMillis() - velocityTime.getOrDefault(uuid, 0L);
            if (timeSinceVel < 500) {
                Vector expected = expectedVelocity.get(uuid);
                if (expected.getY() > 0.1 && dY <= 0 && player.isOnGround()) {
                    handleViolation(player, "Combat", "Velocity", 3);
                    expectedVelocity.remove(uuid);
                }
            } else {
                expectedVelocity.remove(uuid);
            }
        }

        // -- Baritone / Aim Snapping Heuristics (Buffered) --
        float yawDelta = Math.abs(to.getYaw() - lastYaw.getOrDefault(uuid, to.getYaw()));
        float pitchDelta = Math.abs(to.getPitch() - lastPitch.getOrDefault(uuid, to.getPitch()));
        
        if (yawDelta > 180.0f) yawDelta = 360.0f - yawDelta;

        if (yawDelta > 120.0f && pitchDelta < 0.5f && dXZ > 0.2) {
            int buffer = aimBuffer.getOrDefault(uuid, 0) + 1;
            aimBuffer.put(uuid, buffer);
            
            if (buffer >= 3) {
                handleViolation(player, "Combat", "Aim", 1);
                aimBuffer.put(uuid, 0);
            }
        } else {
            int buffer = aimBuffer.getOrDefault(uuid, 0);
            if (buffer > 0) aimBuffer.put(uuid, buffer - 1);
        }

        lastYaw.put(uuid, to.getYaw());
        lastPitch.put(uuid, to.getPitch());
    }

    /*
     * ==========================================
     * 4. COMBAT, KNOCKBACK & EXPLOITS
     * ==========================================
     */
    @EventHandler
    public void onVelocity(PlayerVelocityEvent event) {
        Player player = event.getPlayer();
        if (isBypassed(player)) return;
        
        if (event.getVelocity().lengthSquared() > 0.1) {
            expectedVelocity.put(player.getUniqueId(), event.getVelocity());
            velocityTime.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onTotemPop(EntityResurrectEvent event) {
        if (event.getEntity() instanceof Player) {
            totemPopTime.put(event.getEntity().getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        Player attacker = (Player) event.getDamager();
        if (isBypassed(attacker)) return;

        if (event.getEntity() instanceof Player) {
            Player victim = (Player) event.getEntity();
            double distance = attacker.getLocation().distance(victim.getLocation());
            double maxReach = 3.1 + (attacker.getPing() + victim.getPing()) * 0.0025;

            if (distance > maxReach) {
                event.setCancelled(true);
                handleViolation(attacker, "Combat", "Reach", 3);
            } else if (distance > 3.0 && Math.abs(attacker.getLocation().getY() - victim.getLocation().getY()) > 2.5) {
                event.setCancelled(true);
                handleViolation(attacker, "Combat", "Hitboxes", 3);
            }
        }
    }

    /*
     * ==========================================
     * 5. INVENTORY & PLAYER EXPLOITS
     * ==========================================
     */
    @EventHandler
    public void onInvOpen(InventoryOpenEvent event) {
        openInventories.add(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onInvClose(InventoryCloseEvent event) {
        openInventories.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (isBypassed(player)) return;

        if (event.getCurrentItem() != null && event.getCurrentItem().getType() == Material.TOTEM_OF_UNDYING) {
            long timeSincePop = System.currentTimeMillis() - totemPopTime.getOrDefault(player.getUniqueId(), 0L);
            if (timeSincePop > 0 && timeSincePop < 50) {
                event.setCancelled(true);
                handleViolation(player, "Exploit", "AutoTotem", 5);
            }
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        playerNameCache.put(uuid, player.getName());

        // -- Auto Anchor Exploit Check --
        if (event.getClickedBlock() != null && event.getClickedBlock().getType() == Material.RESPAWN_ANCHOR) {
            if (!isBypassed(player)) {
                long now = System.currentTimeMillis();
                long last = lastAnchorInteractTime.getOrDefault(uuid, 0L);
                long diff = now - last;

                if (diff > 0 && diff < 85) {
                    int buffer = anchorActionBuffer.getOrDefault(uuid, 0) + 1;
                    anchorActionBuffer.put(uuid, buffer);
                    if (buffer >= 3) {
                        event.setCancelled(true);
                        handleViolation(player, "Exploit", "AutoAnchor", 4);
                        anchorActionBuffer.put(uuid, 0);
                    }
                } else {
                    anchorActionBuffer.put(uuid, Math.max(0, anchorActionBuffer.getOrDefault(uuid, 0) - 1));
                }
                lastAnchorInteractTime.put(uuid, now);
            }
        }

        if (event.getItem() != null && event.getItem().getType() == Material.BOW) {
            if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                bowDrawTime.put(uuid, System.currentTimeMillis());
            }
        }

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
                        handleViolation(player, "Combat", "Autoclicker", 3);
                    }
                    delays.clear();
                }
                clickDelays.put(uuid, delays);
            }
            lastClickTime.put(uuid, now);
        }

        if (player.isSprinting() && player.isHandRaised()) {
            handleViolation(player, "Movement", "NoSlow", 2);
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
            handleViolation(player, "Exploit", "FastBow", 3);
        }
    }

    /*
     * ==========================================
     * 6. PUNISHMENT ROUTING
     * ==========================================
     */
    private void handleViolation(Player player, String category, String checkName, int vlAdd) {
        UUID uuid = player.getUniqueId();
        playerNameCache.put(uuid, player.getName());
        
        Map<UUID, Integer> catMap = categoryViolations.computeIfAbsent(category, k -> new HashMap<>());
        int totalVL = catMap.getOrDefault(uuid, 0) + vlAdd;
        catMap.put(uuid, totalVL);

        String action = "ALERT";
        if (totalVL >= 40) {
            action = "BAN";
            Bukkit.getScheduler().runTask(this, () -> player.kickPlayer("Security Violation: " + checkName));
            catMap.put(uuid, 0); 
        }

        String log = String.format("{\"category\": \"%s\", \"check\": \"%s\", \"player\": \"%s\", \"vl\": %d, \"action\": \"%s\"}",
                category, checkName, player.getName(), totalVL, action);
        Bukkit.getConsoleSender().sendMessage(prefix + ChatColor.DARK_GRAY + log);

        for (Player admin : Bukkit.getOnlinePlayers()) {
            boolean isStaff = admin.isOp() || admin.hasPermission("meteor.mod") || admin.hasPermission("meteor.srmod") || admin.hasPermission("meteor.admin") || admin.hasPermission("meteor.owner");
            boolean isSubscribed = alertRecipients.contains(admin.getUniqueId());

            if (isStaff || isSubscribed) {
                admin.sendMessage(prefix + ChatColor.RED + player.getName() + " failed " + checkName + " [" + category + "] (VL: " + totalVL + ")");
            }
        }
    }
}
