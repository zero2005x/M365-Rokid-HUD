package com.m365bleapp.utils

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Helper class to manage app language/locale settings.
 * Persists user language preference and applies it to the app context.
 */
object LocaleHelper {
    
    private const val PREFS_NAME = "language_prefs"
    private const val KEY_LANGUAGE = "app_language"
    private const val KEY_COUNTRY = "app_country"
    
    /**
     * Supported languages with their display names (in their native language)
     */
    data class LanguageOption(
        val code: String,
        val country: String = "",
        val displayName: String,
        val nativeName: String
    )
    
    val supportedLanguages = listOf(
        LanguageOption("en", "", "English", "English"),
        LanguageOption("zh", "TW", "Chinese (Traditional)", "繁體中文"),
        LanguageOption("zh", "CN", "Chinese (Simplified)", "简体中文"),
        LanguageOption("ja", "", "Japanese", "日本語"),
        LanguageOption("ko", "", "Korean", "한국어"),
        LanguageOption("es", "", "Spanish", "Español"),
        LanguageOption("fr", "", "French", "Français"),
        LanguageOption("it", "", "Italian", "Italiano"),
        LanguageOption("ru", "", "Russian", "Русский"),
        LanguageOption("uk", "", "Ukrainian", "Українська"),
        LanguageOption("ar", "", "Arabic", "العربية")
    )
    
    /**
     * Get the saved language preference, or null if following system default
     */
    fun getSavedLanguage(context: Context): LanguageOption? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val code = prefs.getString(KEY_LANGUAGE, null) ?: return null
        val country = prefs.getString(KEY_COUNTRY, "") ?: ""
        return supportedLanguages.find { it.code == code && it.country == country }
    }
    
    /**
     * Save language preference
     * Pass null to use system default
     */
    fun saveLanguage(context: Context, language: LanguageOption?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (language == null) {
            prefs.edit()
                .remove(KEY_LANGUAGE)
                .remove(KEY_COUNTRY)
                .apply()
        } else {
            prefs.edit()
                .putString(KEY_LANGUAGE, language.code)
                .putString(KEY_COUNTRY, language.country)
                .apply()
        }
    }
    
    /**
     * Check if using system default language
     */
    fun isUsingSystemDefault(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.contains(KEY_LANGUAGE)
    }
    
    /**
     * Get the effective locale to use.
     * If user has saved a preference, use that.
     * Otherwise, check if system locale is supported, if not, default to English.
     */
    fun getEffectiveLocale(context: Context): Locale {
        val savedLanguage = getSavedLanguage(context)
        if (savedLanguage != null) {
            return if (savedLanguage.country.isNotEmpty()) {
                Locale(savedLanguage.code, savedLanguage.country)
            } else {
                Locale(savedLanguage.code)
            }
        }
        
        // Use system locale if supported, otherwise English
        val systemLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }
        
        return if (isLocaleSupported(systemLocale)) {
            systemLocale
        } else {
            Locale.ENGLISH
        }
    }
    
    /**
     * Check if a locale is in our supported list
     */
    private fun isLocaleSupported(locale: Locale): Boolean {
        val langCode = locale.language
        val countryCode = locale.country
        
        return supportedLanguages.any { option ->
            if (option.country.isNotEmpty()) {
                option.code == langCode && option.country == countryCode
            } else {
                option.code == langCode
            }
        }
    }
    
    /**
     * Apply the locale to a context and return the localized context
     */
    fun applyLocale(context: Context): Context {
        val locale = getEffectiveLocale(context)
        return updateContextLocale(context, locale)
    }
    
    /**
     * Update context with a specific locale
     */
    private fun updateContextLocale(context: Context, locale: Locale): Context {
        Locale.setDefault(locale)
        
        val config = Configuration(context.resources.configuration)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale)
            config.setLocales(LocaleList(locale))
            return context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            return context
        }
    }
    
    /**
     * Get the current system locale display name
     */
    fun getSystemLocaleDisplayName(context: Context): String {
        val systemLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }
        return systemLocale.displayName
    }
    
    /**
     * Find matching supported language for system locale, or null if not supported
     */
    fun findMatchingLanguage(context: Context): LanguageOption? {
        val systemLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }
        
        val langCode = systemLocale.language
        val countryCode = systemLocale.country
        
        // First try exact match (language + country)
        supportedLanguages.find { it.code == langCode && it.country == countryCode }?.let { return it }
        
        // Then try language-only match
        return supportedLanguages.find { it.code == langCode && it.country.isEmpty() }
    }
}
