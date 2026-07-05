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
package eu.kennytv.maintenance.core.command.subcommand;

import eu.kennytv.maintenance.core.MaintenancePlugin;
import eu.kennytv.maintenance.core.command.CommandInfo;
import eu.kennytv.maintenance.core.util.SenderInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class ToggleCommand extends CommandInfo {

    public ToggleCommand(final MaintenancePlugin plugin) {
        super(plugin, "toggle");
    }

    @Override
    public void execute(final SenderInfo sender, final String[] args) {
        if (args.length == 0 || args.length > 2) {
            sender.send(getHelpMessage());
            return;
        }

        final boolean maintenance = args[0].equalsIgnoreCase("on");
        final String mode;
        if (maintenance && args.length == 2) {
            mode = args[1].toLowerCase(Locale.ROOT);
        } else if (!maintenance && args.length == 1) {
            mode = null;
        } else if (maintenance) {
            mode = null;
        } else {
            sender.send(getHelpMessage());
            return;
        }

        if (maintenance == plugin.isMaintenance()) {
            if (mode != null) {
                plugin.getSettings().setActiveMode(mode);
                return;
            }

            sender.send(getMessage(maintenance ? "alreadyEnabled" : "alreadyDisabled"));
            return;
        }

        plugin.setMaintenance(maintenance, mode);
    }

    @Override
    public List<String> getTabCompletion(final SenderInfo sender, final String[] args) {
        if (args.length != 2 || !args[0].equalsIgnoreCase("on")) {
            return Collections.emptyList();
        }

        final List<String> modes = new ArrayList<>(getSettings().getPingMessages().getKeys());
        modes.remove("default");
        return modes;
    }
}
