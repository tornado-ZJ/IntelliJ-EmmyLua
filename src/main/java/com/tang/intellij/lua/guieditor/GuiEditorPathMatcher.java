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

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Fast, PSI-free path routing used by the file-editor provider.
 */
public final class GuiEditorPathMatcher {
    public static final String PROJECT_DIR_MACRO = "$PROJECT_DIR$";

    private GuiEditorPathMatcher() {
    }

    public static boolean isManagedLuaFile(@NotNull Project project,
                                           @NotNull VirtualFile file,
                                           @NotNull GuiEditorProjectSettings settings) {
        if (!settings.isEnabled() || file.isDirectory()) {
            return false;
        }
        String extension = file.getExtension();
        if (extension == null || !"lua".equalsIgnoreCase(extension)) {
            return false;
        }
        return matchesAnyRoot(file.getPath(), settings.getSourceRoots(), project.getBasePath(), SystemInfo.isWindows);
    }

    public static boolean matchesAnyRoot(@NotNull String filePath,
                                         @NotNull List<String> roots,
                                         @Nullable String projectBasePath,
                                         boolean windowsCaseInsensitive) {
        for (String root : roots) {
            if (matchesPath(filePath, root, projectBasePath, windowsCaseInsensitive)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Public for deterministic unit tests; it deliberately does not touch the file system.
     */
    public static boolean matchesPath(@NotNull String filePath,
                                      @NotNull String configuredRoot,
                                      @Nullable String projectBasePath,
                                      boolean windowsCaseInsensitive) {
        String file = normalize(filePath, projectBasePath, windowsCaseInsensitive);
        String root = normalize(configuredRoot, projectBasePath, windowsCaseInsensitive);
        if (file.isEmpty() || root.isEmpty()) {
            return false;
        }
        String descendantPrefix = root.endsWith("/") ? root : root + "/";
        return file.equals(root) || file.startsWith(descendantPrefix);
    }

    public static @NotNull String expandProjectMacro(@NotNull String value, @Nullable String projectBasePath) {
        String trimmed = value.trim();
        if (projectBasePath == null || projectBasePath.isBlank()) {
            return trimmed;
        }
        if (trimmed.equals(PROJECT_DIR_MACRO)) {
            return projectBasePath;
        }
        if (trimmed.startsWith(PROJECT_DIR_MACRO + "/") || trimmed.startsWith(PROJECT_DIR_MACRO + "\\")) {
            return projectBasePath + trimmed.substring(PROJECT_DIR_MACRO.length());
        }
        if (!isPortableAbsolute(trimmed)) {
            return projectBasePath + "/" + trimmed;
        }
        return trimmed;
    }

    public static @Nullable String findMatchingRoot(@NotNull Project project,
                                                    @NotNull VirtualFile file,
                                                    @NotNull GuiEditorProjectSettings settings) {
        String base = project.getBasePath();
        for (String root : settings.getSourceRoots()) {
            if (matchesPath(file.getPath(), root, base, SystemInfo.isWindows)) {
                return expandProjectMacro(root, base);
            }
        }
        return null;
    }

    private static @NotNull String normalize(@NotNull String raw,
                                             @Nullable String projectBasePath,
                                             boolean windowsCaseInsensitive) {
        String value = expandProjectMacro(raw, projectBasePath).trim().replace('\\', '/');
        if (value.isEmpty()) {
            return "";
        }

        String prefix = "";
        int start = 0;
        if (value.startsWith("//")) {
            prefix = "//";
            start = 2;
        } else if (value.startsWith("/")) {
            prefix = "/";
            start = 1;
        } else if (value.length() >= 3 && Character.isLetter(value.charAt(0))
                && value.charAt(1) == ':' && value.charAt(2) == '/') {
            prefix = value.substring(0, 3);
            start = 3;
        }

        Deque<String> parts = new ArrayDeque<>();
        String remainder = value.substring(start);
        for (String part : remainder.split("/+")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!parts.isEmpty() && !"..".equals(parts.peekLast())) {
                    parts.removeLast();
                } else if (prefix.isEmpty()) {
                    parts.addLast(part);
                }
            } else {
                parts.addLast(part);
            }
        }

        String joined = String.join("/", parts);
        String normalized;
        if (prefix.endsWith("/") || prefix.equals("//")) {
            normalized = prefix + joined;
        } else if (!prefix.isEmpty() && !joined.isEmpty()) {
            normalized = prefix + "/" + joined;
        } else {
            normalized = prefix + joined;
        }

        while (normalized.length() > 1 && normalized.endsWith("/")
                && !(normalized.length() == 3 && normalized.charAt(1) == ':')) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (windowsCaseInsensitive) {
            normalized = normalized.toLowerCase(Locale.ROOT);
        }
        return normalized;
    }

    private static boolean isPortableAbsolute(@NotNull String path) {
        String value = path.replace('\\', '/');
        return value.startsWith("/")
                || value.startsWith("//")
                || (value.length() >= 3 && Character.isLetter(value.charAt(0))
                && value.charAt(1) == ':' && value.charAt(2) == '/');
    }
}
