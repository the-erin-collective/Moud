package com.moud.server.instance;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.anvil.AnvilLoader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Collection;

public class SafeAnvilLoader implements ChunkLoader {
    private final AnvilLoader delegate;

    public SafeAnvilLoader(Path worldPath) {
        this.delegate = new AnvilLoader(worldPath);
    }

    @Override
    public @Nullable Chunk loadChunk(@NotNull Instance instance, int chunkX, int chunkZ) {
        try {
            return delegate.loadChunk(instance, chunkX, chunkZ);
        } catch (Exception e) {
            // Handle unknown blocks gracefully
            return null;
        }
    }

    @Override
    public void saveChunk(@NotNull Chunk chunk) {
        try {
            delegate.saveChunk(chunk);
        } catch (Exception e) {
            // Handle silently
        }
    }

    @Override
    public void saveInstance(@NotNull Instance instance) {
        try {
            delegate.saveInstance(instance);
        } catch (Exception e) {
            // Handle silently
        }
    }

    @Override
    public void saveChunks(@NotNull Collection<Chunk> chunks) {
        try {
            delegate.saveChunks(chunks);
        } catch (Exception e) {
            // Handle silently
        }
    }
}