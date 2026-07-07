/*
 * This file is part of Maintenance - https://github.com/kennytv/Maintenance
 * Copyright (C) 2018-2024 kennytv (https://github.com/kennytv)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package eu.kennytv.maintenance.paper.hook;

import eu.kennytv.maintenance.core.hook.PremiumResolver;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

/**
 * Reads a player's known premium/cracked status from <a href="https://github.com/games647/FastLogin">FastLogin</a>
 * so {@code /whitelist add <name>} can whitelist only the account variant the player actually logs in as (see
 * {@link PremiumResolver}). FastLogin records this whenever a player joins and when staff run its
 * {@code /premium} / {@code /cracked} commands, so it is authoritative for names it has already seen.
 *
 * <p>FastLogin is accessed reflectively on purpose: it is only a soft dependency (whitelisting works fine
 * without it), it is not published to a build repository we depend on, and its API differs between versions.
 * Every failure degrades to {@link AccountType#UNKNOWN}, which leaves the plugin's own both-variants
 * resolution untouched - the hook can never block or break a whitelist add.
 */
public final class FastLoginHook implements PremiumResolver {
    private static final String STORED_PROFILE_CLASS = "com.github.games647.fastlogin.core.storage.StoredProfile";

    private final Object fastLogin;
    private final Method getCore;
    private final Method getStorage;
    private final Method loadProfile;
    private final Method isPremium;
    @Nullable
    private final Method isSaved;

    private FastLoginHook(final Object fastLogin, final Method getCore, final Method getStorage,
                          final Method loadProfile, final Method isPremium, @Nullable final Method isSaved) {
        this.fastLogin = fastLogin;
        this.getCore = getCore;
        this.getStorage = getStorage;
        this.loadProfile = loadProfile;
        this.isPremium = isPremium;
        this.isSaved = isSaved;
    }

    /**
     * Binds the FastLogin API, or returns {@code null} (logging a warning) if it cannot be hooked - e.g. an
     * unsupported FastLogin version - so the caller simply skips the hook.
     */
    @Nullable
    public static FastLoginHook tryHook(final Plugin fastLogin, final Logger logger) {
        try {
            final Method getCore = fastLogin.getClass().getMethod("getCore");
            final Object core = getCore.invoke(fastLogin);
            if (core == null) {
                return null;
            }
            final Method getStorage = core.getClass().getMethod("getStorage");
            final Object storage = getStorage.invoke(core);
            if (storage == null) {
                return null;
            }
            final Method loadProfile = storage.getClass().getMethod("loadProfile", String.class);

            final Class<?> storedProfile = Class.forName(STORED_PROFILE_CLASS, false, fastLogin.getClass().getClassLoader());
            final Method isPremium = storedProfile.getMethod("isPremium");
            Method isSaved;
            try {
                isSaved = storedProfile.getMethod("isSaved");
            } catch (final NoSuchMethodException e) {
                isSaved = null; // Older FastLogin; loadProfile returning null already tells us "unseen".
            }
            return new FastLoginHook(fastLogin, getCore, getStorage, loadProfile, isPremium, isSaved);
        } catch (final ReflectiveOperationException e) {
            // A single-line warning is enough - the fallback (whitelist both variants) is safe and this is
            // expected on unsupported FastLogin versions. Full stack trace only at FINE level so it stays
            // available for debugging without scaring server owners into thinking whitelisting is broken.
            logger.warning("FastLogin is installed but its API could not be hooked ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + "); whitelisting still works and will keep matching both the premium and cracked account.");
            logger.log(Level.FINE, "FastLogin hook stack trace", e);
            return null;
        }
    }

    @Override
    public AccountType accountType(final String name) {
        try {
            final Object core = getCore.invoke(fastLogin);
            if (core == null) {
                return AccountType.UNKNOWN;
            }
            final Object storage = getStorage.invoke(core);
            if (storage == null) {
                return AccountType.UNKNOWN;
            }
            // May hit FastLogin's database - fine, this runs off the main thread.
            final Object profile = loadProfile.invoke(storage, name);
            if (profile == null) {
                return AccountType.UNKNOWN; // FastLogin has no record for this name.
            }
            if (isSaved != null && !(Boolean) isSaved.invoke(profile)) {
                return AccountType.UNKNOWN; // A fresh, unsaved profile: FastLogin has never seen the name.
            }
            return (Boolean) isPremium.invoke(profile) ? AccountType.PREMIUM : AccountType.CRACKED;
        } catch (final ReflectiveOperationException | ClassCastException e) {
            return AccountType.UNKNOWN;
        }
    }
}
