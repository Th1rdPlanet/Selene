package ru.moonlightproject.dev.selene.paper;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

interface InventoryProtection {
    Object open(Player player);
    void authenticate(Object connection);
    void close(Object connection);
    void sendVirtualOffhand(Object connection, ItemStack item);
}
