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
package eu.kennytv.maintenance.core.util;

import com.google.common.io.CharStreams;
import com.google.gson.JsonObject;
import eu.kennytv.maintenance.core.MaintenancePlugin;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves Bedrock players (Geyser/Floodgate) to their Floodgate UUID via the Geyser global API.
 * <p>
 * Floodgate derives a player's UUID from their Xbox XUID by placing it in the least significant bits
 * of a UUID while keeping the most significant bits at 0 ({@code new UUID(0, xuid)}). This means
 * Bedrock players can be whitelisted offline (by gamertag) and recognized on join without requiring
 * a compile-time dependency on Floodgate.
 */
public final class GeyserApiUtil {

    private static final String XUID_ENDPOINT = "https://api.geysermc.org/v2/xbox/xuid/";

    private GeyserApiUtil() {
    }

    /**
     * Checks whether the given UUID belongs to a Bedrock player.
     * Floodgate UUIDs always have their most significant bits set to 0.
     *
     * @param uuid player uuid
     * @return true if the uuid is a Floodgate (Bedrock) uuid
     */
    public static boolean isBedrockUuid(final UUID uuid) {
        return uuid.getMostSignificantBits() == 0;
    }

    /**
     * Looks up a Bedrock player by their gamertag and returns the matching Floodgate profile.
     *
     * @param gamertag      the Bedrock gamertag (without the Floodgate prefix)
     * @param storedName    the name to store for the profile (usually the prefixed gamertag)
     * @return the resolved profile, or null if no Bedrock player with that gamertag exists
     * @throws IOException if the lookup request fails
     */
    @Blocking
    @Nullable
    public static ProfileLookup lookupBedrockProfile(final String gamertag, final String storedName) throws IOException {
        final String encoded = URLEncoder.encode(gamertag, StandardCharsets.UTF_8);
        final HttpURLConnection connection = (HttpURLConnection) URI.create(XUID_ENDPOINT + encoded).toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", "ProxyWhitelist");

        final int status = connection.getResponseCode();
        if (status == 404) {
            // No Bedrock player with that gamertag
            return null;
        }
        if (status != 200) {
            throw new IOException("Geyser API returned status " + status + " for gamertag " + gamertag);
        }

        try (final InputStream in = connection.getInputStream()) {
            final String output = CharStreams.toString(new InputStreamReader(in, StandardCharsets.UTF_8));
            final JsonObject json = MaintenancePlugin.GSON.fromJson(output, JsonObject.class);
            if (json == null || !json.has("xuid")) {
                return null;
            }

            final long xuid = json.getAsJsonPrimitive("xuid").getAsLong();
            return new ProfileLookup(new UUID(0, xuid), storedName);
        }
    }
}
