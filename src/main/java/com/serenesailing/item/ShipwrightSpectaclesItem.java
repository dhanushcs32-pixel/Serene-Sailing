package com.serenesailing.item;

import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Shipwright's Spectacles: antique 18th-century frameless pince-nez / wire-bridge spectacles.
 *
 * <p>Wearable in {@link EquipmentSlot#HEAD} hands-free (or held in hand). When equipped or held,
 * activates the shipwright's inspection overlay, revealing connected ship blocks, vital
 * attachments (helm, sails, rudder, capstan, wind vane), hull watertightness and structural bounds.</p>
 */
public class ShipwrightSpectaclesItem extends Item implements Equipable {

    public ShipwrightSpectaclesItem(Item.Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public EquipmentSlot getEquipmentSlot() {
        return EquipmentSlot.HEAD;
    }

    /** NeoForge extension overload for equipment slot check. */
    public EquipmentSlot getEquipmentSlot(ItemStack stack) {
        return EquipmentSlot.HEAD;
    }

    @Override
    public Holder<SoundEvent> getEquipSound() {
        return Holder.direct(SoundEvents.ARMOR_EQUIP_IRON.value());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return this.swapWithEquipmentSlot(this, level, player, hand);
    }

    /**
     * Checks if the given entity is actively wearing the spectacles on their head
     * or holding them in either hand.
     */
    public static boolean isEquippedOrHeld(LivingEntity entity) {
        if (entity == null) {
            return false;
        }
        if (entity.getItemBySlot(EquipmentSlot.HEAD).getItem() instanceof ShipwrightSpectaclesItem) {
            return true;
        }
        if (entity.getMainHandItem().getItem() instanceof ShipwrightSpectaclesItem) {
            return true;
        }
        return entity.getOffhandItem().getItem() instanceof ShipwrightSpectaclesItem;
    }
}

