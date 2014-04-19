package com.bergerkiller.bukkit.mw.commands;

import java.util.HashSet;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import com.bergerkiller.bukkit.mw.Localization;
import com.bergerkiller.bukkit.mw.Permission;
import com.bergerkiller.bukkit.mw.Portal;
import com.bergerkiller.bukkit.mw.WorldManager;

public class TeleportPortal extends Command {

	public TeleportPortal() {
		super(Permission.COMMAND_TPP, "tpp");
	}

	public void execute() {
		if (args.length >= 1) {
			Player[] targets = null;
			String dest = null;
			if (args.length > 1) {
				HashSet<Player> found = new HashSet<Player>();
				for (int i = 0; i < args.length - 1; i++) {
					Player player = Bukkit.getServer().getPlayer(args[i]);
					if (player == null) {
						message(ChatColor.RED + "Player '" + args[i] + "' has not been found!");
					} else {
						found.add(player);
					}
				}
				targets = found.toArray(new Player[0]);
				dest = args[args.length - 1];
			} else if (sender instanceof Player) {
				targets = new Player[] {(Player) sender};
				dest = args[0];
			} else {
				sender.sendMessage("This command is only for players!");
				return;
			}
			if (targets.length > 0) {
				//Get prefered world
				World world = targets[0].getWorld();
				if (player != null) world = player.getWorld();
				//Get portal
				Location tele = Portal.getPortalLocation(dest, world.getName(), true);
				if (tele != null) {
					Portal portal = Portal.getNear(tele, 3);
					if (portal != null) {
						//Perform portal teleports
						int succcount = 0;
						for (Player target : targets) {
							if (Permission.canTeleport(target, portal)) {
								if (portal.teleportSelf(target)) {
									//Success
									succcount++;
								}
							} else if (targets[0] == sender) {
								// Sender is the target, notify permissions error
								Localization.PORTAL_NOTELEPORT.message(sender);
							}
						}
						if (targets.length > 1 || targets[0] != sender) {
							message(ChatColor.YELLOW.toString() + succcount + "/" + targets.length + 
									" Players have been teleported to portal '" + dest + "'!");
						}
					} else {
						message(ChatColor.RED + "The portal world is not loaded!");
					}
				} else {
					//Match world
					String worldname = WorldManager.matchWorld(dest);
					if (worldname != null) {
						World w = WorldManager.getWorld(worldname);
						if (w != null) {
							//Perform world teleports
							int succcount = 0;
							for (Player target : targets) {
								if (Permission.canTeleport(target, w)) {
									if (WorldManager.teleportToWorld(target, w)) {
										//Success
										succcount++;
									}
								} else if (targets[0] == sender) {
									// Sender is the target, notify permissions error
									Localization.WORLD_NOTELEPORT.message(sender);
								}
							}
							// Show message, but don't if only the sender was teleported
							// He already receives an enter message by the teleport listener
							if (targets.length > 1 || targets[0] != sender) {
								message(ChatColor.YELLOW.toString() + succcount + "/" + targets.length + 
										" Players have been teleported to world '" + w.getName() + "'!");
							}
						} else {
							message(ChatColor.YELLOW + "World '" + worldname + "' is not loaded!");
						}
					} else {
						Localization.PORTAL_NOTFOUND.message(sender, dest);
						listPortals(Portal.getPortals());
					}
				}
			}
		} else {
			showInv();
		}	
	}
	
}
