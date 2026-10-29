package ru.moonlightproject.dev.selene.paper;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.inventory.ItemStack;
import ru.moonlightproject.dev.exoleaf.api.ExoLeaf;
import ru.moonlightproject.dev.exoleaf.api.PlayerRestrictions;

/**
 * Inventory protection through the ExoLeaf core: the player is restricted before joining, so the
 * server never sends the real inventory, recipe book or advancements until authentication.
 * Loaded reflectively only when the ExoLeaf API exists.
 */
final class ExoLeafInventoryProtection implements InventoryProtection, Listener {

    private final PlayerRestrictions restrictions = ExoLeaf.api().restrictions();

    /** Restrict as early as possible; the server sends the inventory during the join sequence. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void preLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            restrictions.restrict(event.getUniqueId());
        }
    }

    @Override public Object open(Player player) {
        restrictions.restrict(player.getUniqueId());
        return player;
    }

    @Override public void authenticate(Object connection) {
        restrictions.release(((Player) connection).getUniqueId());
    }

    @Override public void close(Object connection) {
        // ExoLeaf clears the restriction itself when the player quits.
    }

    @Override public void sendVirtualMainHand(Object connection, ItemStack item, int heldSlot) {
        restrictions.sendVirtualItem((Player) connection, 36 + heldSlot, item);
    }

    @Override public void shutdown() {
    }

    static String name() {
        return "ExoLeaf " + ExoLeaf.api().version();
    }
}
