/*
 * Copyright (c) 2026.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.tang.intellij.lua.guieditor;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves GUI resource paths without executing Lua code. */
public final class GuiResourceResolver {
    private final @Nullable Path projectBase;
    private final @Nullable Path currentFile;
    private final List<Path> configuredRoots;
    private final Map<Path, CacheEntry> cache = new ConcurrentHashMap<>();

    public GuiResourceResolver(@Nullable String projectBasePath,
                               @Nullable String currentFilePath,
                               @NotNull List<String> configuredResourceRoots) {
        projectBase = toPath(projectBasePath);
        currentFile = toPath(currentFilePath);
        List<Path> roots = new ArrayList<>();
        for (String raw : configuredResourceRoots) {
            String expanded = GuiEditorPathMatcher.expandProjectMacro(raw, projectBasePath);
            Path path = toPath(expanded);
            if (path != null) {
                roots.add(path);
            }
        }
        configuredRoots = List.copyOf(roots);
    }

    public @Nullable Path resolve(@Nullable String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return null;
        }
        String cleaned = resourcePath.trim().replace('\\', '/');
        if (cleaned.startsWith("@")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.startsWith("file://")) {
            cleaned = cleaned.substring("file://".length());
        }
        Path direct = toPath(cleaned);
        if (direct != null && direct.isAbsolute() && Files.isRegularFile(direct)) {
            return direct;
        }

        Set<Path> candidates = new LinkedHashSet<>();
        for (Path root : configuredRoots) {
            addCandidates(candidates, root, cleaned);
        }
        if (projectBase != null) {
            addCandidates(candidates, projectBase, cleaned);
        }
        if (currentFile != null) {
            Path parent = currentFile.getParent();
            int guard = 0;
            while (parent != null && guard++ < 12) {
                addCandidates(candidates, parent, cleaned);
                if ("GUIExport".equalsIgnoreCase(String.valueOf(parent.getFileName()))) {
                    addCandidates(candidates, parent, cleaned);
                    if (parent.getParent() != null) {
                        addCandidates(candidates, parent.getParent(), cleaned);
                    }
                }
                if (projectBase != null && parent.equals(projectBase)) {
                    break;
                }
                parent = parent.getParent();
            }
        }

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public @Nullable BufferedImage load(@Nullable String resourcePath) {
        Path resolved = resolve(resourcePath);
        if (resolved == null) {
            return null;
        }
        try {
            long modified = Files.getLastModifiedTime(resolved).toMillis();
            long size = Files.size(resolved);
            CacheEntry entry = cache.get(resolved);
            if (entry != null && entry.modified == modified && entry.size == size) {
                BufferedImage cached = entry.image.get();
                if (cached != null) {
                    return cached;
                }
            }
            BufferedImage image = ImageIO.read(resolved.toFile());
            if (image != null) {
                cache.put(resolved, new CacheEntry(modified, size, new SoftReference<>(image)));
            }
            return image;
        } catch (IOException ignored) {
            return null;
        }
    }

    public void clearCache() {
        cache.clear();
    }

    private static void addCandidates(Set<Path> candidates, Path root, String cleaned) {
        String relative = cleaned;
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        if (!relative.isEmpty()) {
            candidates.add(root.resolve(relative).normalize());
        }
        if (relative.startsWith("res/")) {
            candidates.add(root.resolve(relative.substring(4)).normalize());
            candidates.add(root.resolve("GUIExport").resolve(relative).normalize());
        } else {
            candidates.add(root.resolve("res").resolve(relative).normalize());
        }
    }

    private static @Nullable Path toPath(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Path.of(raw).toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private record CacheEntry(long modified, long size, SoftReference<BufferedImage> image) {
    }
}
