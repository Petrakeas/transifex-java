package com.transifex.txnative.cache;

import androidx.annotation.AnyRes;
import androidx.annotation.NonNull;

/**
 * Helper class that calls the appropriate "get()" method depending on the interface implementation.
 * <p>
 * <ul>
 *   <li>{@link TxCacheExtended} → calls {@code get(key, id, locale)}</li>
 *   <li>{@link TxCache} → calls {@code get(key, locale)}</li>
 * </ul>
 */
public class TxCacheHelper {

    public static String get(@NonNull TxCache txCache, @NonNull String key, @AnyRes int id, @NonNull String locale) {
        if (txCache instanceof TxCacheExtended) {
            return ((TxCacheExtended) txCache).get(key, id, locale);
        }
        else {
            return txCache.get(key, locale);
        }
    }
}
