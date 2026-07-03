package com.eoframework.mixin.client.access;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WrappedGoal.class)
public interface WrappedGoalAccessor {
    @Accessor("goal")
    Goal eof$getGoal();
}