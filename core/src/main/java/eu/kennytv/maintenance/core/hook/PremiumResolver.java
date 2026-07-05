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
package eu.kennytv.maintenance.core.hook;

/**
 * Optional bridge to an external premium/cracked authenticator (e.g. FastLogin) that already knows which
 * account type a username logs in as. When present, it lets {@code /whitelist add <name>} whitelist only the
 * account variant the player actually uses instead of both the premium (Mojang) and offline UUID.
 *
 * <p>On an offline-mode / mixed server the same name can arrive with a Mojang UUID (premium) or an offline
 * UUID derived from the name (cracked). Without this hint the plugin whitelists both to be safe, which also
 * whitelists the premium account of that name - potentially a stranger who owns the Mojang name. A resolver
 * that reports the real account type closes that gap.
 *
 * <p>Implementations must be side-effect free and fail safe: any uncertainty, error or unknown name returns
 * {@link AccountType#UNKNOWN}, which leaves the plugin's own (both-variants) resolution untouched.
 */
public interface PremiumResolver {

    enum AccountType {
        /** The name logs in as a premium (online-mode authenticated) account. */
        PREMIUM,
        /** The name logs in as a cracked (offline) account. */
        CRACKED,
        /** Not known - the plugin keeps its own resolution. */
        UNKNOWN
    }

    /**
     * Returns the account type the given (non-Bedrock) username is known to log in as, or
     * {@link AccountType#UNKNOWN} when it cannot be determined. Called off the main thread.
     */
    AccountType accountType(String name);
}
