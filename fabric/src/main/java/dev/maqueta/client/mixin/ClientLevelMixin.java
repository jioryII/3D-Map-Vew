package dev.maqueta.client.mixin;

import dev.maqueta.client.world.MapSession;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Block edits re-capture their chunk so the maqueta follows what the player builds. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
	@Inject(method = "sendBlockUpdated", at = @At("HEAD"))
	private void maqueta$onBlockUpdated(BlockPos pos, BlockState old, BlockState current, int flags, CallbackInfo ci) {
		MapSession s = MapSession.get();
		if (s != null && old != current) s.onBlockChanged(pos);
	}
}
