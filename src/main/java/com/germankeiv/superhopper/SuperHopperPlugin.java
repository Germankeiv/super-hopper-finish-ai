package com.germankeiv.superhopper;

import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;

public final class SuperHopperPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private NamespacedKey marker;
    private BukkitTask scanner;
    private BukkitTask visualTask;
    private final Map<String, UUID> visuals = new HashMap<>();
    private final Map<UUID, String> languages = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<String> known = new HashSet<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        marker = new NamespacedKey(this, "super_hopper");
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("superhopper")).setExecutor(this);
        startScanner();
        startVisuals();
    }
    @Override public void onDisable() { if (scanner != null) scanner.cancel(); if (visualTask != null) visualTask.cancel(); for (UUID id : visuals.values()) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); } visuals.clear(); }
    private void startScanner() {
        if (scanner != null) scanner.cancel();
        long ticks = Math.max(1, getConfig().getLong("scan-interval", 5)) * 20;
        scanner = Bukkit.getScheduler().runTaskTimer(this, this::scan, ticks, ticks);
    }
    private boolean isSuper(Block block) {
        if (!(block.getState() instanceof Hopper hopper)) return false;
        return hopper.getPersistentDataContainer().has(marker, PersistentDataType.BYTE);
    }
    private String key(Block b) { return b.getWorld().getUID() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ(); }
    private int countChunk(Chunk chunk) {
        int count = 0;
        for (BlockState state : chunk.getTileEntities()) if (state instanceof Hopper && isSuper(state.getBlock())) count++;
        return count;
    }
    private ItemStack item() {
        ItemStack stack = new ItemStack(Material.HOPPER);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "✦ Super Hopper ✦");
        meta.setCustomModelData(770001);
        meta.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
        meta.setLore(List.of(ChatColor.GRAY + "Place to collect nearby dropped items"));
        stack.setItemMeta(meta);
        return stack;
    }
    @EventHandler(ignoreCancelled = true) public void onPlace(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() != Material.HOPPER) return;
        ItemStack held = e.getItemInHand();
        if (!held.hasItemMeta() || !held.getItemMeta().getPersistentDataContainer().has(marker, PersistentDataType.BYTE)) return;
        int limit = Math.max(1, getConfig().getInt("max-per-chunk", 1));
        if (countChunk(e.getBlockPlaced().getChunk()) >= limit) {
            e.setCancelled(true); e.getPlayer().sendMessage(ChatColor.RED + "Super Hopper chunk limit reached."); return;
        }
        // Update the placed hopper tile entity, not the ordinary item.
        Bukkit.getScheduler().runTask(this, () -> {
            if (!(e.getBlockPlaced().getState() instanceof Hopper hopper)) return;
            hopper.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
            hopper.update(true, false);
            known.add(key(e.getBlockPlaced()));
            spawnVisual(e.getBlockPlaced());
        });
    }
    @EventHandler(ignoreCancelled = true) public void onBreak(BlockBreakEvent e) {
        if (!isSuper(e.getBlock())) return;
        e.setDropItems(false);
        known.remove(key(e.getBlock()));
        removeVisual(e.getBlock());
        e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation(), item());
    }
    @EventHandler(ignoreCancelled = true) public void onInteract(org.bukkit.event.player.PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || !isSuper(e.getClickedBlock())) return;
        if (!e.getPlayer().hasPermission("superhopper.use")) return;
        e.setCancelled(true);
        Inventory inv = Bukkit.createInventory(null, 9, "Super Hopper Info");
        ItemStack info = new ItemStack(Material.HOPPER);
        ItemMeta meta = info.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "Super Hopper");
        meta.setLore(List.of("Radius: " + getConfig().getInt("pull-radius", 2), "Scan: " + getConfig().getInt("scan-interval", 5) + "s", "Filters configured in config.yml"));
        info.setItemMeta(meta); inv.setItem(4, info);
        e.getPlayer().openInventory(inv);
    }
    @EventHandler public void onGuiClick(InventoryClickEvent e) {
        if (e.getView().getTitle().equals("Super Hopper Info")) e.setCancelled(true);
    }
    private boolean allowed(Material type) {
        if (!getConfig().getBoolean("filter-enabled", true)) return true;
        String name = type.name();
        List<String> included = getConfig().getStringList("filter-items");
        List<String> excluded = getConfig().getStringList("filter-exclude");
        return (included.isEmpty() || included.stream().anyMatch(s -> s.equalsIgnoreCase(name))) && excluded.stream().noneMatch(s -> s.equalsIgnoreCase(name));
    }
    private void scan() {
        int radius = Math.max(1, Math.min(16, getConfig().getInt("pull-radius", 2)));
        for (World world : Bukkit.getWorlds()) for (Chunk chunk : world.getLoadedChunks()) {
            for (BlockState state : chunk.getTileEntities()) {
                if (!(state instanceof Hopper hopper) || !isSuper(state.getBlock())) continue;
                Location center = state.getLocation().add(.5, .5, .5);
                for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
                    if (!(entity instanceof Item dropped) || !allowed(dropped.getItemStack().getType())) continue;
                    ItemStack leftover = dropped.getItemStack().clone();
                    Map<Integer, ItemStack> remaining = hopper.getInventory().addItem(leftover);
                    if (remaining.isEmpty()) dropped.remove();
                    else dropped.setItemStack(remaining.values().iterator().next());
                }
            }
        }
    }

    // Visual overlay uses an ItemDisplay so regular hoppers keep their normal appearance.
    // The resource pack supplies the purple/cyan custom item model.
    private void spawnVisual(Block block) {
        if (!isSuper(block) || !getConfig().getBoolean("visuals.enabled", true)) return;
        String k = key(block);
        UUID existing = visuals.get(k);
        if (existing != null && Bukkit.getEntity(existing) != null) return;
        Location loc = block.getLocation().add(0.5, 0.55, 0.5);
        ItemDisplay display = block.getWorld().spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(item());
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(-0.5f, -0.5f, -0.5f), new org.joml.Quaternionf(), new org.joml.Vector3f(1.0f, 1.0f, 1.0f), new org.joml.Quaternionf()));
            d.setPersistent(false);
            d.setInvulnerable(true);
            d.setGravity(false);
            d.addScoreboardTag("superhopper_visual");
        });
        visuals.put(k, display.getUniqueId());
    }
    private void removeVisual(Block block) {
        UUID id = visuals.remove(key(block));
        if (id != null) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
    }
    private void startVisuals() {
        if (visualTask != null) visualTask.cancel();
        for (UUID id : visuals.values()) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
        visuals.clear();
        if (!getConfig().getBoolean("visuals.enabled", true)) return;
        // Rebuild visual entities after server restarts, and animate loaded chunks only.
        visualTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (World world : Bukkit.getWorlds()) for (Chunk chunk : world.getLoadedChunks()) {
                for (BlockState state : chunk.getTileEntities()) {
                    if (!(state instanceof Hopper) || !isSuper(state.getBlock())) continue;
                    Block block = state.getBlock();
                    spawnVisual(block);
                    if (!getConfig().getBoolean("visuals.particles", true)) continue;
                    Location l = block.getLocation().add(.5, 1.08, .5);
                    int range = Math.max(4, Math.min(64, getConfig().getInt("visuals.view-distance", 24)));
                    if (world.getNearbyPlayers(l, range).isEmpty()) continue;
                    int count = Math.max(0, Math.min(12, getConfig().getInt("visuals.particle-density", 3)));
                    if (count == 0) continue;
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, l, 1, .25, .06, .25, .005);
                    world.spawnParticle(Particle.PORTAL, l, count, .28, .14, .28, .02);
                    world.spawnParticle(Particle.END_ROD, l, 1, .19, .08, .19, .005);
                }
            }
        }, 20L, Math.max(5L, getConfig().getLong("visuals.particle-interval-ticks", 12)));
    }
    @EventHandler public void onChunkUnload(ChunkUnloadEvent event) {
        for (BlockState state : event.getChunk().getTileEntities()) if (state instanceof Hopper) removeVisual(state.getBlock());
    }
    @EventHandler public void onChunkLoad(ChunkLoadEvent event) {
        Bukkit.getScheduler().runTask(this, () -> {
            for (BlockState state : event.getChunk().getTileEntities()) if (state instanceof Hopper && isSuper(state.getBlock())) spawnVisual(state.getBlock());
        });
    }

    private void say(CommandSender sender, String english, String spanish) {
        sender.sendMessage(ChatColor.AQUA + (sender instanceof Player p && "es".equals(languages.get(p.getUniqueId())) ? spanish : english));
    }
    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) { say(sender, "/superhopper give [player] [amount] | reload | lang <en|es> | info", "/superhopper give [jugador] [cantidad] | reload | lang <en|es> | info"); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "lang" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
                if (args.length < 2 || (!args[1].equalsIgnoreCase("en") && !args[1].equalsIgnoreCase("es"))) { sender.sendMessage("Use /superhopper lang <en|es>"); return true; }
                languages.put(p.getUniqueId(), args[1].toLowerCase(Locale.ROOT)); say(sender, "Language updated.", "Idioma actualizado.");
            }
            case "reload" -> {
                if (!sender.hasPermission("superhopper.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                reloadConfig(); startScanner(); startVisuals(); say(sender, "Configuration reloaded.", "Configuración recargada.");
            }
            case "info" -> say(sender, "Super Hopper 1.0.0 — collect nearby dropped items into marked hoppers.", "Super Hopper 1.0.0 — recoge objetos cercanos en tolvas especiales.");
            case "give" -> {
                if (!sender.hasPermission(getConfig().getString("required-permission", "superhopper.give"))) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                Player target;
                if (args.length >= 2) target = Bukkit.getPlayerExact(args[1]);
                else target = sender instanceof Player p ? p : null;
                if (target == null) { sender.sendMessage("Player not found."); return true; }
                int amount = 1;
                if (args.length >= 3) { try { amount = Integer.parseInt(args[2]); } catch (NumberFormatException ex) { sender.sendMessage("Invalid amount."); return true; } }
                if (amount < 1 || amount > 64) { sender.sendMessage("Amount must be 1-64."); return true; }
                if (sender instanceof Player p) {
                    long now = System.currentTimeMillis();
                    long last = cooldowns.getOrDefault(p.getUniqueId(), 0L);
                    long wait = Math.max(0, getConfig().getLong("cooldown", 10)) * 1000;
                    if (now - last < wait) { sender.sendMessage("Wait before using this command again."); return true; }
                    cooldowns.put(p.getUniqueId(), now);
                }
                ItemStack reward = item(); reward.setAmount(amount);
                target.getInventory().addItem(reward).values().forEach(stack -> target.getWorld().dropItemNaturally(target.getLocation(), stack));
                say(sender, "Gave " + amount + " Super Hopper(s) to " + target.getName(), "Entregadas " + amount + " tolvas a " + target.getName());
            }
            default -> say(sender, "Unknown subcommand.", "Comando desconocido.");
        }
        return true;
    }
}
