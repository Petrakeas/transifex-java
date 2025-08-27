package com.transifex.txnative.cache;

import androidx.annotation.AnyRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * An extension of {@link TxCache} interface that adds a {@link #get(String, int, String)} method
 * that also supplies the resource ID.
 * <p>
 * Classes that implement this interface, will have the overloaded method called instead of the
 * original.
 */
public interface TxCacheExtended extends TxCache {

    /**
     * Get the translation for a certain key and locale pair.
     *
     * <p>
     * Similar to {@link #get(String, String)} but also provides the resource ID of the string.
     *
     * @param key The key of the string.
     * @param id The resource ID of the string.
     * @param locale The locale code.
     *
     * @see #get(String, String)
     */
    @Nullable String get(@NonNull String key, @AnyRes int id, @NonNull String locale);

    @Override
    default @Nullable String get(@NonNull String key, @NonNull String locale) {
        return get(key, 0, locale);
    }
}
