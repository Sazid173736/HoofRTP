package com.hoofmc.hoofrtp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class HoofRTP extends JavaPlugin implements Listener {

    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, BukkitTask> pendingTasks = new HashMap<>();
    private final Random random = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("HoofRTP enabled (5s warmup + move cancel + 60s cooldown, no queue).");
    }

    @Override
    public void onDisable() {
        for (BukkitTask task : pendingTasks.values()) {
            task.cancel();
        }
        pendingTasks.clear();
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private String getGuiTitle() {
        return color(getConfig().getString("gui-title", "&d&lHoofMC"));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use /rtp.");
            return true;
        }

        if (!player.hasPermission("hoofrtp.use")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to use /rtp.");
            return true;
        }

        if (args.length > 0) {
            handleWorldSelection(player, args[0].toLowerCase());
            return true;
        }

        openRtpGui(player);
        return true;
    }

    private void openRtpGui(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, getGuiTitle());

        // Slot 11: Grass Block (Overworld)
        gui.setItem(11, createMenuItem(
                Material.GRASS_BLOCK,
                color(getConfig().getString("items.overworld.name", "&a&lOverworld")),
                color(getConfig().getString("items.overworld.lore", "&7Click to randomly teleport in Overworld!"))
        ));

        // Slot 13: Netherrack (Nether)
        gui.setItem(13, createMenuItem(
                Material.NETHERRACK,
                color(getConfig().getString("items.nether.name", "&c&lNether")),
                color(getConfig().getString("items.nether.lore", "&7Click to randomly teleport in Nether!"))
        ));

        // Slot 15: End Stone (The End)
        gui.setItem(15, createMenuItem(
                Material.END_STONE,
                color(getConfig().getString("items.end.name", "&e&lThe End")),
                color(getConfig().getString("items.end.lore", "&7Click to randomly teleport in The End!"))
        ));

        player.openInventory(gui);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
    }

    private ItemStack createMenuItem(Material material, String name, String loreLine) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(Arrays.asList("", loreLine));
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!event.getView().getTitle().equals(getGuiTitle())) return;

        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot == 11) {
            player.closeInventory();
            handleWorldSelection(player, "overworld");
        } else if (slot == 13) {
            player.closeInventory();
            handleWorldSelection(player, "nether");
        } else if (slot == 15) {
            player.closeInventory();
            handleWorldSelection(player, "end");
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!pendingTasks.containsKey(uuid)) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        // Cancel if player moves to a different block or shifts noticeably
        if (from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ()
                || from.distanceSquared(to) > 0.04) {
            cancelPendingRtp(player, true);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cancelPendingRtp(event.getPlayer(), false);
    }

    private void cancelPendingRtp(Player player, boolean sendMessage) {
        BukkitTask task = pendingTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
            if (sendMessage) {
                player.sendMessage(ChatColor.RED + "RTP cancelled because you moved!");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.7f);
            }
        }
    }

    private void handleWorldSelection(Player player, String key) {
        String configKey = switch (key) {
            case "world", "overworld" -> "overworld";
            case "nether", "world_nether" -> "nether";
            case "end", "the_end", "world_the_end" -> "end";
            default -> null;
        };

        if (configKey == null) {
            player.sendMessage(ChatColor.RED + "Invalid world option.");
            return;
        }

        if (!getConfig().getBoolean("worlds." + configKey + ".enabled", true)) {
            player.sendMessage(ChatColor.RED + "RTP is currently disabled for this world.");
            return;
        }

        UUID uuid = player.getUniqueId();
        if (pendingTasks.containsKey(uuid)) {
            player.sendMessage(ChatColor.YELLOW + "You already have an RTP in progress! Stand still.");
            return;
        }

        int cooldownSec = getConfig().getInt("cooldown-seconds", 60);
        long now = System.currentTimeMillis();
        Long lastUsed = cooldowns.get(uuid);
        if (lastUsed != null) {
            long elapsedSec = (now - lastUsed) / 1000;
            if (elapsedSec < cooldownSec) {
                long remaining = cooldownSec - elapsedSec;
                player.sendMessage(ChatColor.YELLOW + "You can use /rtp again in " + remaining + "s.");
                return;
            }
        }

        String worldName = getConfig().getString("worlds." + configKey + ".world-name", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            player.sendMessage(ChatColor.RED + "World '" + worldName + "' is not loaded on this server.");
            return;
        }

        int delaySec = Math.max(1, getConfig().getInt("teleport-delay-seconds", 5));

        // Start 60s cooldown on RTP trigger as requested
        cooldowns.put(uuid, now);
        player.sendMessage(ChatColor.AQUA + "Teleporting in " + delaySec + " seconds... Don't move!");

        BukkitTask task = new BukkitRunnable() {
            int secondsLeft = delaySec;

            @Override
            public void run() {
                pendingTasks.remove(uuid);

                if (!player.isOnline()) {
                    return;
                }

                Location safeLoc = findSafeLocation(world, configKey);
                if (safeLoc == null) {
                    player.sendMessage(ChatColor.RED + "Could not find a safe spot right now. Please try again later.");
                    return;
                }

                player.teleport(safeLoc);
                player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
                player.sendMessage(ChatColor.GREEN + "Teleported to " + safeLoc.getBlockX() + ", " + safeLoc.getBlockY() + ", " + safeLoc.getBlockZ() + "!");
            }
        }.runTaskLater(this, delaySec * 20L);

        pendingTasks.put(uuid, task);
    }

    private Location findSafeLocation(World world, String configKey) {
        int maxRadius = Math.max(500, getConfig().getInt("worlds." + configKey + ".max-radius", 5000));
        int minRadius = Math.max(0, Math.min(maxRadius - 100, getConfig().getInt("worlds." + configKey + ".min-radius", 250)));
        int maxAttempts = Math.max(5, getConfig().getInt("max-attempts", 25));

        for (int i = 0; i < maxAttempts; i++) {
            int angle = random.nextInt(360);
            double rad = Math.toRadians(angle);
            int dist = minRadius + random.nextInt(maxRadius - minRadius + 1);

            int x = (int) (Math.cos(rad) * dist);
            int z = (int) (Math.sin(rad) * dist);

            if (world.getEnvironment() == World.Environment.NETHER) {
                for (int y = 115; y >= 32; y--) {
                    Block ground = world.getBlockAt(x, y, z);
                    Block feet = world.getBlockAt(x, y + 1, z);
                    Block head = world.getBlockAt(x, y + 2, z);
                    if (isSafeGround(ground) && feet.isPassable() && head.isPassable()) {
                        return new Location(world, x + 0.5, y + 1.0, z + 0.5);
                    }
                }
            } else {
                int y = world.getHighestBlockYAt(x, z);
                if (y <= world.getMinHeight() + 1) continue;

                Block ground = world.getBlockAt(x, y, z);
                Block feet = world.getBlockAt(x, y + 1, z);
                Block head = world.getBlockAt(x, y + 2, z);

                if (isSafeGround(ground) && feet.isPassable() && head.isPassable()) {
                    return new Location(world, x + 0.5, y + 1.0, z + 0.5);
                }
            }
        }
        return null;
    }

    private boolean isSafeGround(Block ground) {
        Material type = ground.getType();
        return ground.isSolid()
                && type != Material.WATER
                && type != Material.LAVA
                && type != Material.MAGMA_BLOCK
                && type != Material.CACTUS
                && type != Material.FIRE;
    }
}