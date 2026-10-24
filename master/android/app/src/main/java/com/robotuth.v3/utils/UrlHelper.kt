package com.robotuth.v3.utils

object UrlHelper {
    
    /**
     * Normalize URL to ensure consistent trailing slash handling.
     * Always removes trailing slash to avoid duplication.
     */
    fun normalizeUrl(url: String): String {
        return url.trimEnd('/')
    }
    
    /**
     * Ensure URL ends with trailing slash (for base URLs).
     */
    fun ensureTrailingSlash(url: String): String {
        return if (url.endsWith("/")) url else "$url/"
    }
}
