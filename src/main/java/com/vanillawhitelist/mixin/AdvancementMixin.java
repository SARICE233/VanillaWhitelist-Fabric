package com.vanillawhitelist.mixin;

import com.vanillawhitelist.VanillaWhitelistMod;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fabric API 没有成就事件，注入 PlayerAdvancements#award 统计成就数 */
@Mixin(PlayerAdvancements.class)
public class AdvancementMixin {
	@Shadow
	private ServerPlayer player;

	@Inject(method = "award", at = @At("RETURN"))
	private void vwl$onAward(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> cir) {
		if (Boolean.TRUE.equals(cir.getReturnValue()) && player != null) {
			VanillaWhitelistMod.onAdvancementEarned(player, holder.id().toString());
		}
	}
}
