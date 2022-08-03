package shadows.gateways;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringUtil;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent.RegisterRenderers;
import net.minecraftforge.client.event.ParticleFactoryRegisterEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import shadows.gateways.client.GatewayParticle;
import shadows.gateways.client.GatewayRenderer;
import shadows.gateways.entity.GatewayEntity;
import shadows.gateways.gate.Gateway;
import shadows.gateways.gate.Reward;
import shadows.gateways.item.GateOpenerItem;
import shadows.placebo.json.RandomAttributeModifier;
import shadows.placebo.util.AttributeHelper;

@EventBusSubscriber(bus = Bus.MOD, value = Dist.CLIENT, modid = GatewaysToEternity.MODID)
public class GatewaysToEternityClient {

	@SubscribeEvent
	public static void setup(FMLClientSetupEvent e) {
		e.enqueueWork(() -> {
			Minecraft.getInstance().getItemColors().register((stack, tint) -> {
				Gateway gate = GateOpenerItem.getGate(stack);
				if (gate != null) return gate.getColor().getValue();
				return 0xFFFFFF;
			}, GatewayObjects.GATE_OPENER);
		});
		MinecraftForge.EVENT_BUS.addListener(GatewaysToEternityClient::bossRenderPre);
		MinecraftForge.EVENT_BUS.addListener(GatewaysToEternityClient::tooltip);
	}

	@SubscribeEvent
	public static void eRenders(RegisterRenderers e) {
		e.registerEntityRenderer(GatewayObjects.GATEWAY, GatewayRenderer::new);
	}

	@SubscribeEvent
	public static void factories(ParticleFactoryRegisterEvent e) {
		Minecraft.getInstance().particleEngine.register(GatewayObjects.GLOW, GatewayParticle.Factory::new);
	}

	@SubscribeEvent
	@SuppressWarnings("deprecation")
	public static void stitch(TextureStitchEvent.Pre e) {
		if (e.getAtlas().location().equals(TextureAtlas.LOCATION_PARTICLES)) {
			e.addSprite(new ResourceLocation(GatewaysToEternity.MODID, "particle/glow"));
		}
	}

	public static void tooltip(ItemTooltipEvent e) {
		if (e.getItemStack().getItem() == GatewayObjects.GATE_OPENER) {
			Gateway gate = GateOpenerItem.getGate(e.getItemStack());
			List<Component> tooltips = e.getToolTip();
			if (gate == null) {
				tooltips.add(new TextComponent("Errored gate opener - no stored gateway"));
				return;
			}

			Component comp = new TranslatableComponent("Total Waves: %s", gate.getNumWaves()).withStyle(ChatFormatting.GRAY);
			tooltips.add(comp);

			if (Screen.hasShiftDown()) {
				int wave = 0;
				if (e.getPlayer() != null) {
					wave = (e.getPlayer().tickCount / 50) % gate.getNumWaves();
				}
				comp = new TranslatableComponent("Wave %s", wave + 1).withStyle(ChatFormatting.GREEN, ChatFormatting.UNDERLINE);
				tooltips.add(comp);
				tooltips.add(Component.nullToEmpty(null));
				comp = new TranslatableComponent("-Entities: ").withStyle(ChatFormatting.BLUE);
				tooltips.add(comp);
				Map<EntityType<?>, Integer> counts = new HashMap<>();
				for (Pair<EntityType<?>, CompoundTag> entity : gate.getWave(wave).entities()) {
					counts.put(entity.getKey(), counts.getOrDefault(entity.getKey(), 0) + 1);
				}
				for (Map.Entry<EntityType<?>, Integer> counted : counts.entrySet()) {
					comp = new TranslatableComponent(" - %sx %s ", counted.getValue(), new TranslatableComponent(counted.getKey().getDescriptionId())).withStyle(ChatFormatting.BLUE);
					tooltips.add(comp);
				}
				if (!gate.getWave(wave).modifiers().isEmpty()) {
					comp = new TranslatableComponent("-Modifiers: ").withStyle(ChatFormatting.RED);
					tooltips.add(comp);
					for (RandomAttributeModifier inst : gate.getWave(wave).modifiers()) {
						comp = AttributeHelper.toComponent(inst.getAttribute(), inst.genModifier(e.getPlayer().getRandom()));
						comp = new TranslatableComponent(" - %s", comp.getString()).withStyle(ChatFormatting.RED);
						tooltips.add(comp);
					}
				}
				comp = new TranslatableComponent("-Rewards: ").withStyle(ChatFormatting.GOLD);
				tooltips.add(comp);
				for (Reward r : gate.getWave(wave).rewards()) {
					r.appendHoverText(c -> {
						tooltips.add(new TranslatableComponent(" - %s", c).withStyle(ChatFormatting.GOLD));
					});
				}
			} else {
				comp = new TranslatableComponent("Hold Shift to see wave info").withStyle(ChatFormatting.GRAY);
				tooltips.add(comp);
			}
			if (Screen.hasControlDown()) {
				comp = new TranslatableComponent("Completion Rewards").withStyle(ChatFormatting.YELLOW, ChatFormatting.UNDERLINE);
				tooltips.add(comp);
				tooltips.add(Component.nullToEmpty(null));
				comp = new TranslatableComponent("- %s Experience", gate.getCompletionXp()).withStyle(ChatFormatting.YELLOW);
				tooltips.add(comp);
				for (Reward r : gate.getRewards()) {
					r.appendHoverText(c -> {
						tooltips.add(new TranslatableComponent("- %s", c).withStyle(ChatFormatting.YELLOW));
					});
				}
			} else {
				comp = new TranslatableComponent("Hold Ctrl to see completion rewards").withStyle(ChatFormatting.GRAY);
				tooltips.add(comp);
			}

		}
	}

	public static final ResourceLocation BARS = new ResourceLocation("textures/gui/bars.png");

	public static void bossRenderPre(RenderGameOverlayEvent.BossInfo event) {
		BossEvent boss = event.getBossEvent();
		String name = boss.getName().getString();
		if (name.startsWith("GATEWAY_ID")) {
			Level level = Minecraft.getInstance().level;
			event.setCanceled(true);
			if (level.getEntity(Integer.valueOf(name.substring(10))) instanceof GatewayEntity gate) {
				int color = gate.getGateway().getColor().getValue();
				int r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
				RenderSystem.setShaderColor(r / 255F, g / 255F, b / 255F, 1.0F);
				RenderSystem.setShaderTexture(0, BARS);
				PoseStack stack = event.getMatrixStack();

				int wave = gate.getWave() + 1;
				int maxWave = gate.getGateway().getNumWaves();
				int enemies = gate.getActiveEnemies();
				int maxEnemies = gate.getCurrentWave().entities().size();

				int x = event.getX();
				int y = event.getY();
				int y2 = y + event.getIncrement();
				Gui.blit(stack, x, y, 200, 0, 6 * 5 * 2, 182, 5, 256, 256);
				Gui.blit(stack, x, y2, 200, 0, 6 * 5 * 2, 182, 5, 256, 256);

				float waveProgress = 1F / maxWave;
				float progress = waveProgress * (maxWave - wave + 1);
				if (gate.isWaveActive()) progress -= waveProgress * ((float) (maxEnemies - enemies) / maxEnemies);

				int i = (int) (progress * 183.0F);
				if (i > 0) Gui.blit(stack, x, y, 200, 0, 6 * 5 * 2 + 5, i, 5, 256, 256);

				float maxTime = gate.getCurrentWave().maxWaveTime();
				if (gate.isWaveActive()) {
					i = (int) ((maxTime - gate.getTicksActive()) / maxTime * 183.0F);
					if (i > 0) Gui.blit(stack, x, y2, 200, 0, 6 * 5 * 2 + 5, i, 5, 256, 256);
				} else {
					maxTime = gate.getCurrentWave().setupTime();
					i = (int) (gate.getTicksActive() / maxTime * 183.0F);
					if (i > 0) Gui.blit(stack, x, y2, 200, 0, 6 * 5 * 2 + 5, i, 5, 256, 256);
				}
				RenderSystem.setShaderColor(1, 1, 1, 1);
				Font font = Minecraft.getInstance().font;

				int width = Minecraft.getInstance().getWindow().getGuiScaledWidth();
				Component component = new TextComponent(gate.getCustomName().getString()).withStyle(ChatFormatting.GOLD);
				int strWidth = font.width(component);
				int textX = width / 2 - strWidth / 2;
				int textY = y - 9;
				font.drawShadow(stack, component, textX, textY, 16777215);
				event.setIncrement(event.getIncrement() * 2);
				textY = y2 - 9;

				int time = (int) maxTime - gate.getTicksActive();
				String str = String.format("Wave: %d/%d | Time: %s | Enemies: %d", wave, maxWave, StringUtil.formatTickDuration(time), enemies);
				if (!gate.isWaveActive()) {
					if (gate.isLastWave()) {
						str = "Gate Completed!";
					} else str = String.format("Wave %d starting in %s", wave, StringUtil.formatTickDuration(time));
				}
				component = new TextComponent(str).withStyle(ChatFormatting.GREEN);
				strWidth = font.width(component);
				textX = width / 2 - strWidth / 2;
				font.drawShadow(stack, component, textX, textY, 16777215);
			}
		}
	}

}
