package com.bergerkiller.bukkit.mw.commands;

import org.bukkit.Bukkit;
import org.bukkit.World;

import com.bergerkiller.bukkit.mw.Localization;
import com.bergerkiller.bukkit.mw.Permission;
import com.bergerkiller.bukkit.mw.WorldManager;

public class WorldEvacuate extends Command {
	
	public WorldEvacuate() {
		super(Permission.COMMAND_EVACUATE, "world.evacuate");
	}
	
	public void execute() {
		if (args.length != 0) {
			worldname = WorldManager.matchWorld(args[0]);
			if (this.handleWorld()) {
				World w = Bukkit.getServer().getWorld(worldname);
				if (w != null) {
					String message = "";
					for (int i = 1;i < args.length;i++) {
						message += args[i] + " ";
					}
					if (message == "") {
						message = "Your world has been closed down!";
					} else {
						message = message.trim();
					}
					logAction("Evacuated world: " + worldname +  " ('" + message + "')");
					WorldManager.evacuate(w, message);
				} else {
					Localization.WORLD_NOTLOADED.message(sender, worldname);
				}
			}
		} else {
			showInv();
		}
	}
	
}
