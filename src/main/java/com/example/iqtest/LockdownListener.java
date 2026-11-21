package com.example.iqtest;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Everything that locks players down before the test, and applies the IQ-based limits after it. */
public final class LockdownListener implements Listener {

    private final IQTest plugin;
    private final Map<UUID, Long> lastChat = new ConcurrentHashMap<>();

    LockdownListener(IQTest plugin) {
        this.plugin = plugin;
    }

    /** Cancels the event if the player still has to take the test. */
    private void lock(Cancellable event, Player player) {
        if (plugin.isLocked(player)) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ join / quit / respawn

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        plugin.applyState(p);
        if (plugin.isLocked(p)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline() && plugin.isLocked(p)) plugin.welcome(p);
            }, 20L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.endSession(e.getPlayer().getUniqueId());
        lastChat.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> plugin.applyState(p));
    }

    // ------------------------------------------------------------------ frozen: movement and actions

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!plugin.isLocked(p)) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        // Looking around is fine; any change of position (walking, jumping, falling) is not.
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        lock(e, e.getPlayer());
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        lock(e, e.getPlayer());
        // Nobody can mess with a locked player either (ride, leash, trade...).
        if (e.getRightClicked() instanceof Player target && plugin.isLocked(target)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        lock(e, e.getPlayer());
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        lock(e, e.getPlayer());
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        lock(e, e.getPlayer());
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        lock(e, e.getPlayer());
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent e) {
        lock(e, e.getPlayer());
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent e) {
        lock(e, e.getPlayer());
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p) lock(e, p);
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p) lock(e, p);
    }

    // ------------------------------------------------------------------ untouchable

    /** Covers every kind of damage, including hits from other players. */
    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) lock(e, p);
    }

    /** A locked player can't hurt anyone else either (e.g. with a projectile). */
    @EventHandler
    public void onDamageBy(EntityDamageByEntityEvent e) {
        Player attacker = null;
        if (e.getDamager() instanceof Player direct) {
            attacker = direct;
        } else if (e.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            attacker = shooter;
        }
        if (attacker != null && plugin.isLocked(attacker)) e.setCancelled(true);
    }

    @EventHandler
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (e.getTarget() instanceof Player p && plugin.isLocked(p)) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ sprint, commands, chat

    @EventHandler
    public void onSprint(PlayerToggleSprintEvent e) {
        Player p = e.getPlayer();
        if (!e.isSprinting()) return;
        boolean block = plugin.isLocked(p);
        if (!block) {
            Integer iq = plugin.getIq(p.getUniqueId());
            block = iq != null && !plugin.tierFor(iq).sprint();
        }
        if (block) {
            e.setCancelled(true);
            p.setSprinting(false);
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!plugin.isLocked(p)) return;

        String message = e.getMessage().trim();
        String label = message.substring(1).split(" ")[0].toLowerCase();
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);

        if (plugin.getConfig().getStringList("lockdown.allowed-commands").contains(label)) return;
        e.setCancelled(true);
        p.sendMessage(plugin.component("command-locked"));
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (plugin.isLocked(p)) {
            e.setCancelled(true);
            p.sendMessage(plugin.component("chat-locked"));
            return;
        }

        Integer iq = plugin.getIq(p.getUniqueId());
        if (iq == null) return; // bypass-permission players have no limits
        IQTest.Tier tier = plugin.tierFor(iq);

        String plain = PlainTextComponentSerializer.plainText().serialize(e.message());
        if (tier.maxChat() > 0 && plain.length() > tier.maxChat()) {
            e.setCancelled(true);
            p.sendMessage(plugin.component("chat-too-long",
                    Placeholder.unparsed("max", String.valueOf(tier.maxChat()))));
            return;
        }

        if (tier.chatCooldown() > 0) {
            long now = System.currentTimeMillis();
            Long last = lastChat.get(p.getUniqueId());
            long waitMs = last == null ? 0 : last + tier.chatCooldown() * 1000L - now;
            if (waitMs > 0) {
                e.setCancelled(true);
                p.sendMessage(plugin.component("chat-cooldown",
                        Placeholder.unparsed("seconds", String.valueOf((waitMs + 999) / 1000))));
                return;
            }
            lastChat.put(p.getUniqueId(), now);
        }
    }

    // ------------------------------------------------------------------ the test GUI

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof IQTest.TestHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        plugin.handleClick(p, e.getRawSlot());
    }

    @EventHandler
    public void onInvDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof IQTest.TestHolder) e.setCancelled(true);
    }

    @EventHandler
    public void onInvClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof IQTest.TestHolder)) return;
        if (e.getPlayer() instanceof Player p) plugin.reopenIfNeeded(p);
    }
}
