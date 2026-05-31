package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;

/**
 * Shared path and request classification for auth-related filters.
 */
public final class SecurityBypassPaths {

    private SecurityBypassPaths() {
    }

    public static boolean isInternalHealthOrInfoPath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        String normalized = path.startsWith("/") ? path : "/" + path;
        return "/health".equals(normalized)
                || normalized.startsWith("/_floci/")
                || "/_floci".equals(normalized)
                || normalized.startsWith("/_localstack/")
                || "/_localstack".equals(normalized);
    }

    public static boolean isPresignedUrlRequest(ContainerRequestContext ctx) {
        return ctx.getUriInfo().getQueryParameters().containsKey("X-Amz-Algorithm");
    }

    public static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }
}
