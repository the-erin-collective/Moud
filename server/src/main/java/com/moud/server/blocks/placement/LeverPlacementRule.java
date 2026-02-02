package com.moud.server.blocks.placement;

import net.minestom.server.instance.block.Block;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

final class LeverPlacementRule extends AttachablePlacementRule {
    LeverPlacementRule(@NotNull Block block) {
        super(block);
    }
}

