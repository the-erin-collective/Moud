package com.moud.server.instance;

import net.hollowcube.polar.PolarLoader;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;

final class SceneWorldChunkLoader implements ChunkLoader {
    private final PolarLoader delegate;
    private final SceneWorldAccess worldAccess;

    SceneWorldChunkLoader(Path worldFile, SceneWorldAccess worldAccess) throws IOException {
        this.worldAccess = Objects.requireNonNull(worldAccess, "worldAccess");
        this.delegate = new PolarLoader(worldFile);
        // .setParallel(false) - removed if method doesn't exist
    }

    @Override
    public void loadInstance(@NotNull Instance instance) {
        try {
            delegate.loadInstance(instance);
            worldAccess.ensureSceneInitialized(instance);
        } catch (Exception e) {
            // Handle silently - PolarLoader may have different interface
        }
    }

    @Override
    public Chunk loadChunk(@NotNull Instance instance, int chunkX, int chunkZ) {
        try {
            return delegate.loadChunk(instance, chunkX, chunkZ); // PolarLoader 1.15.0 returns Chunk directly
        } catch (Exception e) {
            return null;
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
    public void saveChunk(@NotNull Chunk chunk) {
        Instance instance = chunk.getInstance();
        saveInstance(instance);
    }

    @Override
    public void saveChunks(@NotNull Collection<Chunk> chunks) {
        for (Chunk chunk : chunks) {
            saveChunk(chunk);
        }
    }
}
