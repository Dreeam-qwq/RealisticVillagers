package me.matsubara.realisticvillagers.entity.v1_20_r4.villager.ai.behaviour;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import me.matsubara.realisticvillagers.entity.v1_20_r4.villager.VillagerNPC;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.bukkit.entity.AbstractVillager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@SuppressWarnings("OptionalGetWithoutIsPresent")
public class TradeWithVillager extends Behavior<Villager> {

    private static final Item[] WHEAT_SINGLETON_ARRAY = {Items.WHEAT};
    private @NotNull Item @Nullable [] trades = null;
    private static final int INTERACT_DIST_SQR = 5;

    public TradeWithVillager() {
        super(ImmutableMap.of(
                MemoryModuleType.INTERACTION_TARGET, MemoryStatus.VALUE_PRESENT,
                MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES, MemoryStatus.VALUE_PRESENT));
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, Villager villager) {
        if (villager instanceof VillagerNPC npc && npc.isDoingNothing(true)) return false;

        Brain<Villager> brain = villager.getBrain();
        return brain.getMemory(MemoryModuleType.INTERACTION_TARGET)
                .filter(living -> living.getType() == EntityType.VILLAGER
                        && isEntityVisible(brain, living)
                        && (!(living instanceof VillagerNPC npc) || npc.isDoingNothing(true)))
                .isPresent();
    }

    private boolean isEntityVisible(@NotNull Brain<Villager> brain, LivingEntity living) {
        Optional<NearestVisibleLivingEntities> optional = brain.getMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES);
        return optional.isPresent() && optional.get().contains(living);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager villager, long time) {
        return checkExtraStartConditions(level, villager);
    }

    @Override
    protected void start(ServerLevel level, @NotNull Villager villager, long time) {
        Villager target = (Villager) villager.getBrain().getMemory(MemoryModuleType.INTERACTION_TARGET).get();
        BehaviorUtils.lockGazeAndWalkToEachOther(villager, target, VillagerNPC.WALK_SPEED.get(), 2);
        trades = figureOutWhatIAmWillingToTrade(villager, target);
    }

    @Override
    protected void tick(ServerLevel level, @NotNull Villager villager, long time) {
        Villager target = (Villager) villager.getBrain().getMemory(MemoryModuleType.INTERACTION_TARGET).get();
        if (villager.distanceToSqr(target) > INTERACT_DIST_SQR) return;

        BehaviorUtils.lockGazeAndWalkToEachOther(villager, target, VillagerNPC.WALK_SPEED.get(), 2);
        villager.gossip(level, target, time);

        // Target should have at least 1 slot empty before giving food.
        if (((AbstractVillager) target.getBukkitEntity()).getInventory().firstEmpty() == -1) return;

        if (villager.hasExcessFood() && (isFarmer(villager) || target.wantsMoreFood())) {
            throwHalfStack(villager, Villager.FOOD_POINTS_KEY_ARRAY, target);
        }

        SimpleContainer inventory = villager.getInventory();

        if (isFarmer(target) && inventory.countItem(Items.WHEAT) > Items.WHEAT.getDefaultMaxStackSize() / 2) {
            throwHalfStack(villager, WHEAT_SINGLETON_ARRAY, target);
        }

        if (this.trades != null && inventory.hasAnyOf(trades)) {
            throwHalfStack(villager, trades, target);
        }
    }

    private boolean isFarmer(@NotNull Villager villager) {
        return villager.getVillagerData().getProfession() == VillagerProfession.FARMER;
    }

    @Override
    protected void stop(ServerLevel level, @NotNull Villager villager, long time) {
        villager.getBrain().eraseMemory(MemoryModuleType.INTERACTION_TARGET);
    }

    private static @NotNull Item @Nullable [] figureOutWhatIAmWillingToTrade(@NotNull Villager villager, @NotNull Villager target) {
        @NotNull Item @Nullable [] targetItems = target.getVillagerData().getProfession().requestedItems();
        if (targetItems == null) {
            return null;
        }
        @NotNull Item @Nullable [] villagerItems = villager.getVillagerData().getProfession().requestedItems();
        if (villagerItems == null) {
            return targetItems;
        }
        if (targetItems == villagerItems) {
            return null;
        }
        Item[] willingToTrade = new Item[targetItems.length];
        int willingToTradeSize = 0;
        forImmutableSet:
        for (Item item : targetItems) {
            for (Item item2 : villagerItems) {
                if (item == item2) {
                    continue forImmutableSet;
                }
            }
            willingToTrade[willingToTradeSize] = item;
            willingToTradeSize++;
        }
        return Arrays.copyOf(willingToTrade, willingToTradeSize);
    }

    private static void throwHalfStack(@NotNull Villager villager, @NotNull Item @NotNull [] items, LivingEntity target) {
        SimpleContainer inventory = villager.getInventory();

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack item = inventory.getItem(i);
            if (item.isEmpty()) continue;

            Item type = item.getItem();
            boolean inValidItems = false;
            for (Item validItem : items) {
                if (validItem == type) {
                    inValidItems = true;
                    break;
                }
            }
            if (inValidItems) continue;

            int amount;
            if (item.getCount() > item.getMaxStackSize() / 2) {
                amount = item.getCount() / 2;
            } else if (item.getCount() > 24) {
                amount = item.getCount() - 24;
            } else continue;


            item.shrink(amount);
            BehaviorUtils.throwItem(villager, new ItemStack(type, amount), target.position());
            break;
        }
    }
}