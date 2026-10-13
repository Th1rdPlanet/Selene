package ru.moonlightproject.dev.selene.paper;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class PacketEventsInventoryProtection implements InventoryProtection {
    private final InventoryFirewall firewall = new InventoryFirewall();

    PacketEventsInventoryProtection() {
        PacketEvents.getAPI().getEventManager().registerListener(firewall);
    }

    @Override public Object open(Player player) {
        User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        if (user != null) firewall.gate.open(user);
        return user;
    }

    @Override public void authenticate(Object connection) {
        firewall.gate.authenticate((User) connection);
    }

    @Override public void close(Object connection) {
        firewall.gate.close((User) connection);
    }

    @Override public void sendVirtualOffhand(Object connection, ItemStack item) {
        ((User) connection).sendPacketSilently(
            new WrapperPlayServerSetSlot(0, 0, 45, SpigotConversionUtil.fromBukkitItemStack(item)));
    }
}
