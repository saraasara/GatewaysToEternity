package dev.shadowsoffire.gateways.gate;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.shadowsoffire.gateways.Gateways;
import dev.shadowsoffire.gateways.codec.GatewayCodecs;
import dev.shadowsoffire.gateways.entity.GatewayEntity;
import dev.shadowsoffire.placebo.codec.PlaceboCodecs.CodecProvider;
import dev.shadowsoffire.placebo.json.ItemAdapter;
import dev.shadowsoffire.placebo.json.NBTAdapter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * A Reward is provided when a gateway wave is finished.
 */
public interface Reward extends CodecProvider<Reward> {

    public static final BiMap<ResourceLocation, Codec<? extends Reward>> CODECS = HashBiMap.create();

    public static final Codec<Reward> CODEC = GatewayCodecs.mapBacked("Gateway Reward", CODECS);

    /**
     * Method ref to public net.minecraft.world.entity.LivingEntity m_7625_(Lnet/minecraft/world/damagesource/DamageSource;Z)V # dropFromLootTable
     */
    public static final Method dropFromLootTable = ObfuscationReflectionHelper.findMethod(LivingEntity.class, "m_7625_", DamageSource.class, boolean.class);
    public static final MethodHandle DROP_LOOT = lootMethodHandle();

    /**
     * Called when this reward is to be granted to the player, either on gate or wave completion.
     *
     * @param level    The level the gateway is in
     * @param gate     The gateway entity
     * @param summoner The summoning player
     * @param list     When generating item rewards, add them to this list instead of directly to the player or the world.
     */
    public void generateLoot(ServerLevel level, GatewayEntity gate, Player summoner, Consumer<ItemStack> list);

    public void appendHoverText(Consumer<Component> list);

    public static void initSerializers() {
        register("stack", StackReward.CODEC);
        register("stack_list", StackListReward.CODEC);
        register("entity_loot", EntityLootReward.CODEC);
        register("loot_table", LootTableReward.CODEC);
        register("chanced", ChancedReward.CODEC);
        register("command", CommandReward.CODEC);
        register("experience", XpReward.CODEC);
    }

    private static void register(String id, Codec<? extends Reward> codec) {
        CODECS.put(Gateways.loc(id), codec);
    }

    private static MethodHandle lootMethodHandle() {
        dropFromLootTable.setAccessible(true);
        try {
            return MethodHandles.lookup().unreflect(dropFromLootTable);
        }
        catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Provides a single stack as a reward.
     */
    public static record StackReward(ItemStack stack) implements Reward {

        // Formatter::off
        public static Codec<StackReward> CODEC = RecordCodecBuilder.create(inst -> inst
            .group(
                ItemAdapter.CODEC.fieldOf("stack").forGetter(StackReward::stack))
            .apply(inst, StackReward::new));
        // Formatter::on

        @Override
        public void generateLoot(ServerLevel level, GatewayEntity gate, Player summoner, Consumer<ItemStack> list) {
            list.accept(this.stack.copy());
        }

        @Override
        public void appendHoverText(Consumer<Component> list) {
            list.accept(Component.translatable("reward.gateways.stack", this.stack.getCount(), this.stack.getHoverName()));
        }

        @Override
        public Codec<? extends Reward> getCodec() {
            return CODEC;
        }
    }

    /**
     * Provides a list of stacks as a reward.
     */
    public static record StackListReward(List<ItemStack> stacks) implements Reward {

        // Formatter::off
        public static Codec<StackListReward> CODEC = RecordCodecBuilder.create(inst -> inst
            .group(
                ItemAdapter.CODEC.listOf().fieldOf("stacks").forGetter(StackListReward::stacks))
            .apply(inst, StackListReward::new));
        // Formatter::on

        @Override
        public void generateLoot(ServerLevel level, GatewayEntity gate, Player summoner, Consumer<ItemStack> list) {
            this.stacks.forEach(s -> list.accept(s.copy()));
        }

        @Override
        public void appendHoverText(Consumer<Component> list) {
            for (ItemStack stack : this.stacks) {
                list.accept(Component.translatable("reward.gateways.stack", stack.getCount(), stack.getHoverName()));
            }
        }

        @Override
        public Codec<? extends Reward> getCodec() {
            return CODEC;
        }
    }

    /**
     * Provides multiple rolls of an entity's loot table as a reward.
     */
    public static record EntityLootReward(EntityType<?> type, @Nullable CompoundTag nbt, int rolls) implements Reward {

        // Formatter::off
        public static Codec<EntityLootReward> CODEC = RecordCodecBuilder.create(inst -> inst
            .group(
                ForgeRegistries.ENTITY_TYPES.getCodec().fieldOf("entity").forGetter(EntityLootReward::type),
                NBTAdapter.EITHER_CODEC.optionalFieldOf("nbt").forGetter(r -> Optional.ofNullable(r.nbt)),
                Codec.INT.fieldOf("rolls").forGetter(EntityLootReward::rolls))
            .apply(inst, (type, nbt, rolls) -> new EntityLootReward(type, nbt.orElse(null), rolls)));
        // Formatter::on

        @Override
        public void generateLoot(ServerLevel level, GatewayEntity gate, Player summoner, Consumer<ItemStack> list) {
            try {
                List<ItemEntity> items = new ArrayList<>();

                Entity entity = this.type.create(level);
                entity.getPersistentData().putBoolean("apoth.no_pinata", true);
                for (int i = 0; i < this.rolls; i++) {
                    if (this.nbt != null) entity.load(this.nbt);
                    entity.moveTo(summoner.getX(), summoner.getY(), summoner.getZ(), 0, 0);
                    DamageSource src = new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(DamageTypes.GENERIC_KILL), summoner);
                    entity.hurt(src, 1);
                    entity.captureDrops(items);
                    DROP_LOOT.invoke(entity, level.damageSources().playerAttack(summoner), true);
                }
                entity.remove(RemovalReason.DISCARDED);

                items.stream().map(ItemEntity::getItem).forEach(list);
            }
            catch (Throwable e) {
                e.printStackTrace();
            }
        }

        @Override
        public void appendHoverText(Consumer<Component> list) {
            list.accept(Component.translatable("reward.gateways.entity", this.rolls, Component.translatable(this.type.getDescriptionId())));
        }

        @Override
        public Codec<? extends Reward> getCodec() {
            return CODEC;
        }
    }

    /**
     * Provides a roll of a single loot table as a reward.
     */
    public static record LootTableReward(ResourceLocation table, int rolls, String desc) implements Reward {

        // Formatter::off
        public static Codec<LootTableReward> CODEC = RecordCodecBuilder.create(inst -> inst
            .group(
                ResourceLocation.CODEC.fieldOf("loot_table").forGetter(LootTableReward::table),
                Codec.INT.fieldOf("rolls").forGetter(LootTableReward::rolls),
                Codec.STRING.fieldOf("desc").forGetter(LootTableReward::desc))
            .apply(inst, LootTableReward::new));
        // Formatter::on

        @Override
        public void generateLoot(ServerLevel level, GatewayEntity gate, Player summoner, Consumer<ItemStack> list) {
            LootTable realTable = level.getServer().getLootData().getLootTable(this.table);
            for (int i = 0; i < this.rolls; i++) {
                LootParams.Builder ctx = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, gate.getPosition(1));
                ctx.withLuck(summoner.getLuck()).withParameter(LootContextParams.THIS_ENTITY, summoner).withParameter(LootContextParams.TOOL, summoner.getMainHandItem());
                realTable.getRandomItems(ctx.create(LootContextParamSets.CHEST)).forEach(list);
            }
        }

        @Override
        public void appendHoverText(Consumer<Component> list) {
            list.accept(Component.translatable("reward.gateways.loot_table", this.rolls, this.desc.isEmpty() ? this.table : Component.translatable(this.desc)));
        }

        @Override
        public Codec<? extends Reward> getCodec() {
            return CODEC;
        }
    }

    /**
     * Wraps a reward with a random chance applied to it.
     */
    public static record ChancedReward(Reward reward, float chance) implements Reward {

        // Formatter::off
        public static Codec<ChancedReward> CODEC = RecordCodecBuilder.create(inst -> inst
            .group(
                Reward.CODEC.fieldOf("reward").forGetter(ChancedReward::reward),
                Codec.FLOAT.fieldOf("chance").forGetter(ChancedReward::chance))
            .apply(inst, ChancedReward::new));
        // Formatter::on

        protected static final DecimalFormat fmt = new DecimalFormat("##.##%");

        @Override
        public void generateLoot(ServerLevel level, GatewayEntity gate, Player summoner, Consumer<ItemStack> list) {
            if (level.random.nextFloat() < this.chance) this.reward.generateLoot(level, gate, summoner, list);
        }

        @Override
        public void appendHoverText(Consumer<Component> list) {
            this.reward.appendHoverText(c -> {
                list.accept(Component.translatable("reward.gateways.chance", fmt.format(this.chance), c));
            });
        }

        @Override
        public Codec<? extends Reward> getCodec() {
            return CODEC;
        }
    }

    /**
     * Provides a roll of a single loot table as a reward.
     */
    public static record CommandReward(String command, String desc) implements Reward {

        public static Codec<CommandReward> CODEC = RecordCodecBuilder.create(inst -> inst
            .group(
                Codec.STRING.fieldOf("command").forGetter(CommandReward::command),
                Codec.STRING.fieldOf("desc").forGetter(CommandReward::desc))
            .apply(inst, CommandReward::new));

        @Override
        public void generateLoot(ServerLevel level, GatewayEntity gate, Player summoner, Consumer<ItemStack> list) {
            String realCmd = this.command.replace("<summoner>", summoner.getGameProfile().getName());
            level.getServer().getCommands().performPrefixedCommand(gate.createCommandSourceStack(), realCmd);
        }

        @Override
        public void appendHoverText(Consumer<Component> list) {
            list.accept(Component.translatable(this.desc));
        }

        @Override
        public Codec<? extends Reward> getCodec() {
            return CODEC;
        }
    }

    /**
     * Provides a certain amount of XP as a reward.
     */
    public static record XpReward(int xp, int orbSize) implements Reward {

        public static Codec<XpReward> CODEC = RecordCodecBuilder.create(inst -> inst
            .group(
                Codec.INT.fieldOf("experience").forGetter(XpReward::xp),
                Codec.INT.optionalFieldOf("orb_size", 5).forGetter(XpReward::orbSize))
            .apply(inst, XpReward::new));

        @Override
        public void generateLoot(ServerLevel level, GatewayEntity gate, Player summoner, Consumer<ItemStack> list) {
            int remaining = this.xp;
            while (remaining > 0) {
                remaining -= orbSize;
                level.addFreshEntity(new ExperienceOrb(level, gate.getX(), gate.getY(), gate.getZ(), orbSize));
            }
        }

        @Override
        public void appendHoverText(Consumer<Component> list) {
            list.accept(Component.translatable("tooltip.gateways.experience", this.xp).withStyle(Style.EMPTY.withColor(0xFCFF00)));
        }

        @Override
        public Codec<? extends Reward> getCodec() {
            return CODEC;
        }
    }
}