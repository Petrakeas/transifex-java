package com.transifex.txnative;

import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.TextUtils;

import com.transifex.common.LocaleData;
import com.transifex.common.Plurals;
import com.transifex.txnative.cache.TxCache;
import com.transifex.txnative.cache.TxStandardCache;
import com.transifex.txnative.missingpolicy.MissingPolicy;
import com.transifex.txnative.missingpolicy.SourceStringPolicy;

import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.PluralsRes;
import androidx.annotation.StringRes;
import androidx.core.text.HtmlCompat;

/**
 * The main class of the framework, responsible for orchestrating all functionality.
 */
public class NativeCore {

    public static final String TAG = NativeCore.class.getSimpleName();

    final Context mContext;
    final LocaleState mLocaleState;
    final TxCache mCache;
    final MissingPolicy mMissingPolicy;

    final Handler mMainHandler;
    final CDSHandlerAndroid mCDSHandler;
    final Resources mDefaultResources;      // Non-localized resources
    final Resources mSourceLocaleResources; // Resources using the source locale

    boolean mTestModeEnabled;
    boolean mSupportSpannableEnabled;

    /**
     * Create an instance of the core SDK class.
     * <p>
     * We initialize and set-up the rest of the SDK classes and enable ViewPump interception.
     *
     * @param applicationContext The application context.
     * @param localeState Keeps track of the available and current locales.
     * @param token The Transifex token that can be used for retrieving translations from CDS.
     * @param cdsHost An optional host for the Content Delivery Service; defaults to the production
     *                host provided by Transifex.
     * @param cache The translation cache that holds the translations from the CDS;
     * {@link com.transifex.txnative.cache.TxStandardCache TxStandardCache} is used if set to
     *              <code>null</code>.
     * @param missingPolicy Determines how to handle translations that are not available;
     * {@link com.transifex.txnative.missingpolicy.SourceStringPolicy SourceStringPolicy} is used
     *                     if set to <code>null</code>.
     */
    public NativeCore(@NonNull Context applicationContext,
                      @NonNull LocaleState localeState,
                      @NonNull String token,
                      @Nullable String cdsHost,
                      @Nullable TxCache cache,
                      @Nullable MissingPolicy missingPolicy) {
        mContext = applicationContext.getApplicationContext();
        mMainHandler = new Handler(mContext.getMainLooper());
        mLocaleState = localeState;
        mLocaleState.setCurrentLocaleListener(mCurrentLocaleListener);
        mCache = (cache != null) ? cache : TxStandardCache.getCache(mContext, null, null);
        mMissingPolicy = (missingPolicy != null) ? missingPolicy : new SourceStringPolicy();

        if (cdsHost == null) {
            cdsHost = CDSHandlerAndroid.CDS_HOST;
        }
        mCDSHandler = new CDSHandlerAndroid(mLocaleState.getTranslatedLocales(), token, null, cdsHost);

        mDefaultResources = Utils.getDefaultLocaleResources(mContext);
        mSourceLocaleResources = Utils.getLocalizedResources(mContext, new Locale(mLocaleState.getSourceLocale()));

        try {
            mContext.getResources().getResourceEntryName(R.plurals.tx_plurals);
        }
        catch (Resources.NotFoundException e) {
            throw new RuntimeException("The strings resources of txnative are not bundled in the app.");
        }
    }

     private final LocaleState.CurrentLocaleListener mCurrentLocaleListener = new LocaleState.CurrentLocaleListener() {
         @Override
         public void onLocaleChanged(@NonNull Locale newLocale, @Nullable String resolvedLocale) {
             // Do nothing
         }
    };

    /**
     * @see TxNative#setTestMode(boolean)
     */
    void setTestMode(boolean enabled) {
        mTestModeEnabled = enabled;
    }

    /**
     * @see TxNative#setSupportSpannable(boolean)
     */
    void setSupportSpannable(boolean enabled) {
        mSupportSpannableEnabled = enabled;
    }

    /**
     * Fetches translations from CDS.
     *
     * @param localeCode If set to <code>null</code>, it will fetch translations for all locales
     *                   as defined in the SDK configuration.
     */
    void fetchTranslations(@Nullable String localeCode) {
        mCDSHandler.fetchTranslationsAsync(localeCode, new CDSHandlerAndroid.FetchTranslationsCallback() {
            @Override
            public void onComplete(final @Nullable LocaleData.TranslationMap translationMap) {
                if (translationMap != null && !translationMap.isEmpty()) {
                    // Update mCache using the fetched translationMap in main thread
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mCache.update(translationMap);
                        }
                    });
                }
            }
        });
    }

    /**
     * Return the value of the provided string resource ID under the current locale.
     * <p>
     * This method has similar semantics to {@link Resources#getText(int)} in terms of handling a
     * non existing ID: throws NotFoundException if the given ID does not exist; the returned
     * string is never <code>null</code>.
     *
     * @return The string data associated with the resource, plus possibly styled text information.
     *
     * @throws Resources.NotFoundException Throws NotFoundException if the given ID does not exist
     */
    @NonNull CharSequence translate(TxResources txResources, @StringRes int id)
            throws Resources.NotFoundException {
        //noinspection ConstantConditions
        return internalTranslate(txResources, id, null, false);
    }

    /**
     * Return the value of the provided string resource ID under the current locale.
     * <p>
     * This method has similar semantics to {@link Resources#getText(int, CharSequence)} in terms of
     * handling a non existing ID: returns <code>def</code>, which can be <code>null</code>, if the given ID
     * does not exist.
     *
     * @return The string data associated with the resource, plus possibly styled text information,
     * or <code>def</code> if <code>id</code> is 0 or not found.
     */
    @Nullable CharSequence translate(TxResources txResources, @StringRes int id, @Nullable CharSequence def) {
        return internalTranslate(txResources, id, def, true);
    }

    /**
     * Return the value of the provided string resource ID under the current locale.
     * <p>
     * If <coode>shouldUseDef</coode> is <code>false</code>, the semantics are described by
     * {@link #translate(TxResources, int)}. If it's <code>true</code>, they are described by
     * {@link #translate(TxResources, int, CharSequence)}.
     *
     * @param txResources TxResources instance.
     * @param id The resource ID.
     * @param def The default CharSequence to return if ID is not found. It's used if shouldUseDef
     *            is true. Otherwise, it's ignored.
     * @param shouldUseDef This controls whether a <code>NotFoundException</code> is thrown or the
     *                     <code>def</code> string is returned when the given <code>ID</code> does
     *                     not exist.
     *
     * @return The string data associated with the resource, plus possibly styled text information,
     * or <code>def</code> if <code>id</code> is 0 or not found and <code>shouldUseDef</code> is true.
     */
    @Nullable
    private CharSequence internalTranslate(
            TxResources txResources, @StringRes int id, @Nullable CharSequence def, boolean shouldUseDef)
            throws Resources.NotFoundException {
        try {
            // We don't want to alter string resources, such as
            // "config_inputEventCompatProcessorOverrideClassName", that belong to the android resource
            // package
            if (txResources.isAndroidStringResource(id)) {
                return txResources.getOriginalText(id);
            }
        }
        catch (Resources.NotFoundException e) {
            // The provided ID does not exist. If "shouldUseDef" is true, we emulate the getText(int)
            // behavior. If it's false, we emulate the getText(int, CharSequence) behavior
            if (shouldUseDef) {
                // Return def if the provided id does not exist.
                return def;
            }
            else {
                // Throw exception if the provided id does not exist.
                throw e;
            }
        }

        if (mTestModeEnabled) {
            return TextUtils.concat("test: ", txResources.getOriginalText(id));
        }

        if (mLocaleState.isSourceLocale()) {
            return txResources.getOriginalText(id);
        }

        String translatedString = null;
        if (mLocaleState.getResolvedLocale() != null) {
            translatedString = mCache.get(txResources.getResourceEntryName(id),
                    mLocaleState.getResolvedLocale());
        }

        // String can be null/empty if:
        // 1. our Cache has not been updated with translations yet
        // 2. the resolved locale is null: there is no app locale that matches the current locale
        // 3. our Cache does not have translations for the resolved locale (this shouldn't happen)
        // 4. the key was not found in the Cache for the resolved locale
        if (TextUtils.isEmpty(translatedString)) {
            CharSequence sourceString = mDefaultResources.getText(id);
            return mMissingPolicy.get(sourceString, id, txResources.getResourceEntryName(id),
                    mLocaleState.getResolvedLocale());
        }

        return getSpannedString(translatedString);
    }

    @NonNull
    CharSequence translateQuantityString(TxResources txResources, @PluralsRes int id, int quantity)
            throws Resources.NotFoundException {

        // We don't want to alter plurals resources that belong to the android resource
        // package
        if (txResources.isAndroidStringResource(id)) {  // A NotFoundException is thrown if the ID is not found
            // A NotFoundException is thrown if the ID is not PluralsRes
            return txResources.getOriginalQuantityText(id, quantity);
        }

        if (mTestModeEnabled) {
            return TextUtils.concat("test: ", txResources.getOriginalQuantityText(id, quantity));
        }

        if (mLocaleState.isSourceLocale()) {
            return txResources.getOriginalQuantityText(id, quantity);
        }

        // Get ICU string from Cache
        String icuString = null;
        if (mLocaleState.getResolvedLocale() != null) {
            icuString = mCache.get(txResources.getResourceEntryName(id),
                    mLocaleState.getResolvedLocale());
        }

        // Get quantity String from ICU string
        String quantityString = null;
        if (icuString != null) {
            quantityString = getLocalizedQuantityString(txResources, icuString, quantity);
        }

        // No ICU string found in cache or no quantity string was rendered
        if (quantityString == null) {
            // Get source string using source locale's plural rules
            CharSequence sourceString = mSourceLocaleResources.getQuantityText(id, quantity);
            return mMissingPolicy.getQuantityString(sourceString, id, quantity,
                    txResources.getResourceEntryName(id), mLocaleState.getResolvedLocale());
        }

        return getSpannedString(quantityString);
    }

    /**
     * Uses the given ICU string to return a quantity string for the given quantity that follows
     * the current locale's plural rules.
     *
     * @param txResources A TxResources instance.
     * @param icuString An ICU string.
     * @param quantity The number used to get the correct string for the current language's plural
     *                 rules.
     *
     * @return A quantity string; <code>null</code> if no matching quantity string was found in the
     * provided <code>icuString</code>
     */
    @Nullable String getLocalizedQuantityString(@NonNull TxResources txResources,
                                                        @NonNull String icuString, int quantity) {
        if (TextUtils.isEmpty(icuString)) {
            return null;
        }

        // Parse ICU string to Plurals
        Plurals plurals = Plurals.fromICUString(icuString);

        // Use Android's localization system to get the correct plural type for the given quantity
        String pluralType = txResources.getOriginalQuantityText(R.plurals.tx_plurals, quantity).toString();

        // Get plural string from Plurals
        String plural = plurals.getPlural(pluralType);

        // Fallback to "other" plural type if the requested plural type is not available
        if (plural == null) {
            plural = plurals.other;
        }

        return plural;
    }

    /**
     * Parses the provided string's tags into spans and returns a {@link SpannedString} object
     * if {@link #setSupportSpannable(boolean)} is set to <code>true</code>. If it's set to
     * <code>false</code> or no tags exist, it returns a {@link String}.
     * <p>
     * If {@link #setSupportSpannable(boolean)} is set to <code>false</code>, the returned
     * String will keep any found tags.
     *
     * @param string A string that may contain markup that can be parsed using
     *               <code>HtmlCompat.fromHtml</code> to return a SpannedString containing spans.
     *
     * @return A {@link Spanned} or String object.
     */
    @NonNull CharSequence getSpannedString(@NonNull String string) {
        if (mSupportSpannableEnabled) {
            // If a span was found, return a "Spanned" object. Otherwise, return "String".
            Spanned spanned = HtmlCompat.fromHtml(string, HtmlCompat.FROM_HTML_MODE_LEGACY);
            if (spanned.getSpans(0, spanned.length(), Object.class).length != 0) {
                return new SpannedString(spanned);
            }
            else {
                return spanned.toString();
            }
        }
        else {
            // This is faster than "fromHTML()" and is enough for most cases
            return string.replace("&lt;", "<");
        }
    }
}
