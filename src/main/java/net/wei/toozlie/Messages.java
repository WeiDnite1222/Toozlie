package net.wei.toozlie;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.caption.Caption;
import org.incendo.cloud.caption.CaptionProvider;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

public final class Messages {
    private static final Locale FALLBACK_LOCALE = Locale.US;
    private static final String BUNDLE_BASE_NAME = "messages";

    private final Toozlie plugin;

    public Messages(Toozlie plugin) {
        this.plugin = plugin;
    }

    public CaptionProvider<CommandSender> cloudCaptionProvider() {
        return (Caption caption, CommandSender sender) -> raw(sender, "cloud." + caption.key());
    }

    public String get(CommandSender sender, String key, String... replacements) {
        return replace(raw(sender, key), replacements);
    }

    public String get(Locale locale, String key, String... replacements) {
        return replace(raw(locale, key), replacements);
    }

    public String raw(CommandSender sender, String key) {
        return raw(locale(sender), key);
    }

    public String raw(Locale locale, String key) {
        ResourceBundle bundle = bundleOrNull(locale);
        if (bundle != null && bundle.containsKey(key)) {
            return bundle.getString(key);
        }

        ResourceBundle defaultBundle = bundleOrNull(defaultLocale());
        if (defaultBundle != null && defaultBundle.containsKey(key)) {
            return defaultBundle.getString(key);
        }

        ResourceBundle fallback = bundleOrNull(FALLBACK_LOCALE);
        return fallback != null && fallback.containsKey(key) ? fallback.getString(key) : null;
    }

    public Locale defaultLocale() {
        return localeTag(plugin.config == null ? "en_US" : plugin.config.language);
    }

    public Locale locale(CommandSender sender) {
        if (plugin.config == null || !"auto".equalsIgnoreCase(plugin.config.language)) {
            return defaultLocale();
        }

        if (sender instanceof Player player) {
            return localeTag(player.getLocale());
        }

        return defaultLocale();
    }

    private ResourceBundle bundleOrNull(Locale locale) {
        Locale selected = locale == null ? FALLBACK_LOCALE : locale;
        try {
            return ResourceBundle.getBundle(
                    BUNDLE_BASE_NAME,
                    selected,
                    ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_DEFAULT)
            );
        } catch (MissingResourceException ignored) {
            return null;
        }
    }

    private Locale localeTag(String tag) {
        if (tag == null || tag.isBlank() || "auto".equalsIgnoreCase(tag)) {
            return FALLBACK_LOCALE;
        }

        String normalized = tag.replace('-', '_');
        String[] parts = normalized.split("_", 3);
        if (parts.length == 1) {
            return Locale.forLanguageTag(parts[0]);
        }
        if (parts.length == 2) {
            return new Locale(parts[0], parts[1]);
        }
        return new Locale(parts[0], parts[1], parts[2]);
    }

    private String replace(String message, String... replacements) {
        if (message == null) {
            return "";
        }

        String result = message;
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            if (replacements[i] == null) {
                throw new RuntimeException("Message "+message+" replacements index are not correct.");
            }
            result = result.replace("{" + replacements[i] + "}", replacements[i + 1] == null ? "" : replacements[i + 1]);
        }
        return result;
    }
}
