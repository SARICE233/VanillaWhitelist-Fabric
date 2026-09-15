package com.vanillawhitelist.mixin;

import com.vanillawhitelist.StatsCollector;
import com.vanillawhitelist.Tracker;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fabric API 没有方块放置事件，这里注入 BlockItem#place 来统计放置数 */
@Mixin(BlockItem.class)
public class VanillaWhitelistMixin {
	@Inject(method = "place", at = @At("RETURN"))
	private void vwl$onBlockPlaced(BlockPlaceContext ctx, CallbackInfoReturnable<InteractionResult> cir) {
		InteractionResult result = cir.getReturnValue();
		if (result == null || !result.consumesAction()) return;
		if (ctx.getPlayer() instanceof ServerPlayer sp) {
			Tracker.onBlockPlace(sp, StatsCollector.dimensionName(sp.level().dimension().identifier()));
		}
	}
}
