package com.moud.server.blocks.placement;

import net.minestom.server.instance.block.Block;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

final class ButtonPlacementRule extends AttachablePlacementRule {
    ButtonPlacementRule(@NotNull Block block) {
        super(block);
    }
}

