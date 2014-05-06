package com.bergerkiller.bukkit.mw;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;

import com.evilmidget38.UUIDFetcher;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.WorldCreator;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import com.bergerkiller.bukkit.common.MaterialTypeProperty;
import com.bergerkiller.bukkit.common.nbt.CommonTagCompound;
import com.bergerkiller.bukkit.common.reflection.classes.RegionFileCacheRef;
import com.bergerkiller.bukkit.common.reflection.classes.RegionFileRef;
import com.bergerkiller.bukkit.common.utils.CommonUtil;
import com.bergerkiller.bukkit.common.utils.EntityUtil;
import com.bergerkiller.bukkit.common.utils.LogicUtil;
import com.bergerkiller.bukkit.common.utils.MaterialUtil;
import com.bergerkiller.bukkit.common.utils.ParseUtil;
import com.bergerkiller.bukkit.common.utils.StreamUtil;
import com.bergerkiller.bukkit.common.utils.WorldUtil;

public class WorldManager {
	private static final MaterialTypeProperty SAFE_SPAWN_AIR;
	private static final MaterialTypeProperty SAFE_SPAWN_SURFACE;
	static {
		HashSet<Material> safeAir = new HashSet<Material>();
		HashSet<Material> safeSurface = new HashSet<Material>();
		try {
			boolean isSafeSurface;
			for (Material type : Material.values()) {
				LogicUtil.addOrRemove(safeSurface, type, isSafeSurface = MaterialUtil.ISSOLID.get(type));
				LogicUtil.addOrRemove(safeAir, type, !isSafeSurface && 
						!MaterialUtil.SUFFOCATES.get(type) && !MaterialUtil.ISPRESSUREPLATE.get(type));
			}
			LogicUtil.removeArray(safeAir, Material.WATER, Material.STATIONARY_WATER, Material.LAVA, Material.STATIONARY_LAVA);
			LogicUtil.removeArray(safeAir, Material.TRIPWIRE, Material.LEAVES, Material.PORTAL, Material.ENDER_PORTAL);
			LogicUtil.removeArray(safeSurface, Material.LEAVES, Material.DRAGON_EGG, Material.PORTAL, Material.ENDER_PORTAL);
		} catch (Throwable t) {
			t.printStackTrace();
		}
		SAFE_SPAWN_AIR = new MaterialTypeProperty(safeAir.toArray(new Material[0]));
		SAFE_SPAWN_SURFACE = new MaterialTypeProperty(safeSurface.toArray(new Material[0]));
	}

	/**
	 * Closes all file streams associated with the world specified.
	 * 
	 * @param world to close
	 */
	public static void closeWorldStreams(World world) {
		// Wait until all chunks of this world are saved
		WorldUtil.saveToDisk(world);
		// Close the region files
		synchronized (RegionFileCacheRef.TEMPLATE.getType()) {
			try {
				String worldPart = "." + File.separator + world.getName();
				Iterator<Entry<File, Object>> iter = RegionFileCacheRef.FILES.entrySet().iterator();
				Entry<File, Object> entry;
				while (iter.hasNext()) {
					entry = iter.next();
					if (!entry.getKey().toString().startsWith(worldPart) || entry.getValue() == null) {
						continue;
					}
					try {
						RegionFileRef.close.invoke(entry.getValue());
						iter.remove();
					} catch (Exception ex) {
						ex.printStackTrace();
					}
				}
			} catch (Exception ex) {
				MyWorlds.plugin.log(Level.WARNING, "Exception while removing world reference for '" + world.getName() + "'!");
				ex.printStackTrace();
			}
		}
	}

	/*
	 * Get or set the respawn point of a world
	 */
	public static void setSpawnLocation(String forWorld, Location destination) {
		setSpawn(forWorld, new Position(destination));
	}
	public static void setSpawn(String forWorld, Position destination) {
		WorldConfig.get(forWorld).spawnPoint = destination;
	}
	public static Position getRespawn(String ofWorld) {
		return WorldConfig.get(ofWorld).spawnPoint;
	}
	public static Location getRespawnLocation(String ofWorld) {
		Position pos = getRespawn(ofWorld);
		if (pos != null) {
			Location loc = pos.toLocation();
			if (loc.getWorld() != null) {
				return loc;
			}
		}
		return null;
	}
	public static Location getRespawnLocation(World ofWorld) {
		return getRespawnLocation(ofWorld.getName());
	}
	
	/*
	 * Gets a possible teleport position on a certain world
	 */
	public static Location getSpawnLocation(World onWorld) {
		Position[] pos = getSpawnPoints(onWorld);
		if (pos.length > 0) return pos[0].toLocation();
		return onWorld.getSpawnLocation();
	}

	/**
	 * Gets the Spawn Position of a World, which permits unloaded worlds to be checked
	 * 
	 * @param onWorldName of the World to get the spawn of
	 * @return Spawn position
	 */
	public static Position getSpawnPosition(String onWorldName) {
		WorldConfig worldC = WorldConfig.get(onWorldName);
		// Same world spawn
		if (worldC.spawnPoint.getWorldName().equalsIgnoreCase(onWorldName)) {
			return worldC.spawnPoint;
		}
		// Loop other worlds with a possible spawn point there
		for (WorldConfig wc : WorldConfig.all()) {
			if (wc.spawnPoint.getWorldName().equalsIgnoreCase(onWorldName)) {
				return wc.spawnPoint;
			}
		}
		// No spawn available, request it from the World (if loaded)
		World world = worldC.getWorld();
		if (world != null) {
			return new Position(world.getSpawnLocation());
		}
		// Read from level.dat (if available)
		CommonTagCompound data = worldC.getData();
		if (data != null) {
			double[] pos = data.getValue("Pos", double[].class);
			float[] rot = data.getValue("Rotation", float[].class);
			return new Position(onWorldName, pos[0], pos[1], pos[2], rot[0], rot[1]);
		}
		// Absolutely NO idea, return a generic position
		return new Position(onWorldName, 0, 64, 0);
	}

	public static Position[] getSpawnPoints() {
		Collection<WorldConfig> all = WorldConfig.all();
		Position[] pos = new Position[all.size()];
		int i = 0;
		for (WorldConfig wc : all) {
			pos[i] = wc.spawnPoint;
			i++;
		}
		return pos;
	}
	public static Position[] getSpawnPoints(World onWorld) {
		return getSpawnPoints(onWorld.getName());
	}
	public static Position[] getSpawnPoints(String onWorld) {
		ArrayList<Position> pos = new ArrayList<Position>();
		for (Position p : getSpawnPoints()) {
			if (p.getWorldName().equalsIgnoreCase(onWorld)) {
				pos.add(p);
			}
		}
		return pos.toArray(new Position[0]);
	}

	/*
	 * Chunk generators
	 */
	public static String[] getGeneratorPlugins() {
		ArrayList<String> gens = new ArrayList<String>();
		for (Plugin plugin : Bukkit.getServer().getPluginManager().getPlugins()) {
			try {
				String mainclass = plugin.getDescription().getMain();
				Class<?> cmain = Class.forName(mainclass);
				if (cmain == null) continue;
				if (cmain.getMethod("getDefaultWorldGenerator", String.class, String.class).getDeclaringClass() != JavaPlugin.class) {
					gens.add(plugin.getDescription().getName());
				}
			} catch (Throwable t) {}
		}
		return gens.toArray(new String[0]);
	}

	public static String fixGeneratorName(String name) {
		if (LogicUtil.nullOrEmpty(name)) {
			return null;
		}
		String genName = name;
		String args = "";
		int index = name.indexOf(":");
		if (index != -1) {
			args = name.substring(index + 1);
			genName = name.substring(0, index);
		}
		if (genName.isEmpty()) {
			// Only arguments (meant for default generator)
			return ":" + args;
		} else {
			//get plugin name
			final String pname = ParseUtil.parseArray(getGeneratorPlugins(), genName.toLowerCase(), null);
			if (pname == null) {
				// Not found
				return null;
			} else {
				// Found: append arguments
				return pname + ":" + args;
			}
		}
	}
	public static ChunkGenerator getGenerator(String worldname, String name) {
		if (name == null) {
			return null;
		}
		String id = "";
		int index = name.indexOf(":");
		if (index != -1) {
			id = name.substring(index + 1);
			name = name.substring(0, index);
		}
		Plugin plug = Bukkit.getServer().getPluginManager().getPlugin(name);
		if (plug != null) {
			return plug.getDefaultWorldGenerator(worldname, id);
		} else {
			return null;
		}
	}

	/*
	 * General world fields
	 */
	public static long getSeed(String seed) {
		if (seed == null) return 0;
		long seedval = 0;
		try {
			seedval = Long.parseLong(seed);
		} catch (Exception ex) {
			seedval = seed.hashCode();
		}
		return seedval;
	}

	public static long getRandomSeed(String seed) {
		long seedval = getSeed(seed);
		if (seedval == 0) {
			seedval = new Random().nextLong();
		}
		return seedval;
	}

	public static String getWorldName(CommandSender sender, String[] args, boolean useAlternative) {
		String alternative = (args == null || args.length == 0) ? null : args[args.length - 1];
		return getWorldName(sender, alternative, useAlternative);
	}

	public static String getWorldName(CommandSender sender, String alternative, boolean useAlternative) {
		String worldname = null;
		if (useAlternative) {
			worldname = WorldManager.matchWorld(alternative);
		} else if (sender instanceof Player) {
			worldname = ((Player) sender).getWorld().getName();
		} else {
			for (World w : Bukkit.getServer().getWorlds()) {
				worldname = w.getName();
				break;
			}
		}
		return worldname;
	}

	public static String matchWorld(String matchname) {
		if (matchname == null || matchname.isEmpty()) {
			return null;
		}
		Collection<String> worldnames = WorldUtil.getLoadableWorlds();
		for (String worldname : worldnames) {
			if (worldname.equalsIgnoreCase(matchname)) return worldname;
		}
		matchname = matchname.toLowerCase();
		for (String worldname : worldnames) {
			if (worldname.toLowerCase().contains(matchname)) return worldname;
		}
		return null;
	}

	public static World getWorld(String worldname) {
		if (worldname == null) return null;
		try {
			return Bukkit.getServer().getWorld(worldname);
		} catch (Exception ex) {
			return null;
		}
	}

	public static boolean isLoaded(String worldname) {
		return getWorld(worldname) != null;
	}

	public static boolean worldExists(String worldname) {
		return worldname != null && WorldUtil.isLoadableWorld(worldname);
	}

	public static World getOrCreateWorld(String worldname) {
		World w = getWorld(worldname);
		if (w != null) return w;
		return createWorld(worldname, 0);
	}

	public static boolean unload(World world) {
		if (world != null) {
			LoadChunksTask.abortWorld(world, true);
			return Bukkit.getServer().unloadWorld(world, world.isAutoSave());
		}
		return false;
	}

	/**
	 * Creates a new World
	 * 
	 * @param worldname to create
	 * @param seed to use
	 * @return The created World, or null on failure
	 */
	public static World createWorld(String worldname, long seed) {
		return createWorld(worldname, seed, null);
	}

	/**
	 * Creates a new World
	 * 
	 * @param worldname to create
	 * @param seed to use
	 * @param sender to send creation problems to if they occur (null to ignore)
	 * @return The created World, or null on failure
	 */
	public static World createWorld(String worldname, long seed, CommandSender sender) {
		WorldConfig wc = WorldConfig.get(worldname);
		final boolean load = wc.isInitialized();
		String chunkGeneratorName = wc.getChunkGeneratorName();
		StringBuilder msg = new StringBuilder();
		if (load) {
			msg.append("Loading");
		} else {
			msg.append("Generating");
		}
		msg.append(" world '").append(worldname).append("'");
		if (seed == 0) {
			if (chunkGeneratorName != null) {
				msg.append(" using chunk generator: '").append(chunkGeneratorName).append("'");
			}
		} else {
			msg.append(" using seed ").append(seed);
			if (chunkGeneratorName != null) {
				msg.append(" and chunk generator: '").append(chunkGeneratorName).append("'");
			}
		}
		MyWorlds.plugin.log(Level.INFO, msg.toString());
		
		final int retrycount = 3;
		World w = null;
		int i = 0;
		ChunkGenerator cgen = null;
		try {
			if (chunkGeneratorName != null) {
				cgen = getGenerator(worldname, chunkGeneratorName);
			}
		} catch (Exception ex) {}
		if (cgen == null) {
			if (chunkGeneratorName != null) {
				msg.setLength(0);
				msg.append("World '").append(worldname);
				msg.append("' could not be created because the chunk generator '");
				msg.append(chunkGeneratorName).append("' was not found!");
				MyWorlds.plugin.log(Level.SEVERE, msg.toString());
				if (sender != null) {
					sender.sendMessage(ChatColor.RED + msg.toString());
				}
				return null;
			}
		}
		Exception failReason = null;
		for (i = 0; i < retrycount + 1; i++) {
			try {
				WorldCreator c = new WorldCreator(worldname);
				wc.worldmode.apply(c);
				if (seed != 0) {
					c.seed(seed);
				}
				c.generator(cgen);
				w = c.createWorld();
			} catch (Exception ex) {
				failReason = ex;
			}
			if (w != null) break;
		}
		if (w == null) {
			MyWorlds.plugin.log(Level.WARNING, "World creation failed after " + i + " retries!");
			if (failReason != null) {
				if (sender != null) {
					sender.sendMessage(ChatColor.RED + "Failed to create world: " + failReason.getMessage());
				}
				CommonUtil.filterStackTrace(failReason).printStackTrace();
			}
		} else if (i == 1) {
			MyWorlds.plugin.log(Level.INFO, "World creation succeeded after 1 retry!");
		} else if (i > 0) {
			MyWorlds.plugin.log(Level.INFO, "World creation succeeded after " + i + " retries!");
		}
		return w;
	}

	public static Location getEvacuation(Player player) {
		World world = player.getWorld();
		String[] portalnames;
		if (Permission.COMMAND_SPAWN.has(player) || Permission.COMMAND_TPP.has(player)) {
			for (Position pos : getSpawnPoints()) {
				Location loc = pos.toLocation();
				if (loc.getWorld() == null || loc.getWorld() == world) {
					continue;
				}
				if (Permission.canEnter(player, loc.getWorld())) {
					return loc;
				}
			}
			portalnames = Portal.getPortals();
		} else {
			portalnames = Portal.getPortals(world);
		}
		for (String name : portalnames) {
			if (Permission.canEnterPortal(player, name)) {
				Location loc = Portal.getPortalLocation(name, player.getWorld().getName(), true);
				if (loc == null || loc.getWorld() == null || loc.getWorld() == world) {
					continue;
				}
				if (Permission.canEnter(player, loc.getWorld())) {
					return loc;
				}
			}
		}
		return null;
	}

	public static void evacuate(World world, String message) {
		for (Player player : world.getPlayers()) {
			Location loc = getEvacuation(player);
			if (loc != null) {
				player.teleport(loc);
				player.sendMessage(ChatColor.RED + message);
			} else {
				player.kickPlayer(message);
			}
		}
	}
	public static void teleportToClonedWorld(World from, World cloned) {
		for (Player p : from.getPlayers().toArray(new Player[0])) {
			Location loc = p.getLocation();
			loc.setWorld(cloned);
			p.teleport(loc);
		}
	}

	/**
	 * Tries to find a (personal) spawn location to teleport players to.
	 * If last position remembering is turned on and a last position is known, 
	 * this Location is returned. Otherwise, the world spawn point is returned.
	 * 
	 * @param player to find the world spawn for
	 * @param world to find the world spawn in
	 * @return Spawn location
	 */
	public static Location getPlayerWorldSpawn(Player player, World world) {
		if (WorldConfig.get(world).rememberLastPlayerPosition || Permission.GENERAL_KEEPLASTPOS.has(player)) {
			// Player is already in the world to go to...so we just return that instead
			if (player.getWorld() == world) {
				return player.getLocation();
			}

			// Figure out the last position of the player on the world
			Location location = MWPlayerDataController.readLastLocation(player, world);
			if (location != null) {
				return location;
			}
		}
		return getSpawnLocation(world);
	}

	/**
	 * Teleports a player to a world. If the world allows the last player position
	 * on the world to be used, this is used instead. If no last position is known,
	 * or last position remembering is disabled, the player is teleported to the
	 * world spawn position.
	 * 
	 * @param player to teleport
	 * @param world to teleport to
	 * @return True if successful, False if not
	 */
	public static boolean teleportToWorld(Player player, World world) {
		return EntityUtil.teleport(player, getPlayerWorldSpawn(player, world));
	}

	/**
	 * Teleports all players from one world to the other.
	 * 
	 * @param from world to teleport all players from
	 * @param to world to teleport to
	 * @see #teleportToWorld(Player, World)
	 */
	public static void teleportAllPlayersToWorld(World from, World to) {
		for (Player p : from.getPlayers().toArray(new Player[0])) {
			teleportToWorld(p, to);
		}
	}

	/**
	 * Looks for a suitable place to spawn near the Start Location specified.
	 * 
	 * @param startLocation
	 * @return A safe location to spawn at
	 */
	public static Location getSafeSpawn(Location startLocation) {
		return getSafeSpawn(startLocation, true);
	}

	/**
	 * Looks for a suitable place to spawn near the Start Location specified.
	 * 
	 * @param startLocation
	 * @param allowPortals - whether portals can be designated as a safe spawn
	 * @return A safe location to spawn at
	 */
	public static Location getSafeSpawn(Location startLocation, boolean allowPortals) {
		// First, ask the internal spawn finding logic to do this for us
		// This COULD fail, proper detection for that is key!
		if (allowPortals) {
			Location pos = WorldUtil.findSpawnLocation(startLocation, false);
			if (pos != null) {
				// Sometimes an odd 128-height location is returned (nether)
				// This check prevents that from being used here
				int posX = pos.getBlockX();
				int posY = pos.getBlockY();
				int posZ = pos.getBlockZ();
				if (posX != 0 || posZ != 0 || (posY != 128 && posY != 256)) {
					return pos;
				}
			}
		}
		// Do it ourselves
		final World world = startLocation.getWorld();
		final int blockX = startLocation.getBlockX();
		final int blockY = startLocation.getBlockY();
		final int blockZ = startLocation.getBlockZ();
		final int startY = world.getEnvironment() == Environment.NETHER ? 127 : (world.getMaxHeight() - 1);
		final Random random = new Random();
		int y, x = blockX, z = blockZ;
		int alterX = blockX, alterY = blockY, alterZ = blockZ;
		int airCounter;
		Material type;
		for (int retry = 0; retry < 200; retry++) {
			airCounter = world.getEnvironment() == Environment.NETHER ? 0 : 2;
			for (y = startY; y > 0; y--) {
				type = WorldUtil.getBlockType(world, x, y, z);
				if (SAFE_SPAWN_AIR.get(type)) {
					// Air block - continue
					airCounter++;
				} else {
					// Safe spawn is only there with 2 air-blocks above the surface
					if (airCounter >= 2) {
						if (SAFE_SPAWN_SURFACE.get(type)) {
							// Safe place to spawn on - ENDING
							return new Location(world, x + 0.5, y + 1.5, z + 0.5);
						} else {
							// Unsafe place but still a surface...set as alternative
							alterX = x;
							alterY = y + 1;
							alterZ = z;
							break;
						}
					}
					airCounter = 0;
				}
			}

			// Obtain a new column to look at
			// For the first 5 calls, probe around a bit < 8 blocks away
			if (retry <= 5) {
				x = blockX + random.nextInt(8) - 3;
				z = blockZ + random.nextInt(8) - 3;
			} else {
				x = blockX + random.nextInt(128) - 63;
				z = blockZ + random.nextInt(128) - 63;
			}
		}
		// Return the alternative (unsafe) location
		// This could occur if spawning in an ocean, for example
		return new Location(world, alterX + 0.5, alterY + 0.5, alterZ + 0.5);
	}

	/**
	 * Repairs the chunk region file
	 * Returns -1 if the file had to be removed
	 * Returns -2 if we had no access
	 * Returns -3 if file removal failed (from -1)
	 * Returns the amount of changed chunks otherwise
	 * @param chunkfile
	 * @param backupfolder
	 * @return
	 */
	public static int repairRegion(File chunkfile, File backupfolder) {
		MyWorlds.plugin.log(Level.INFO, "Performing repairs on region file: " + chunkfile.getName());
		RandomAccessFile raf = null;
		try {
			String name = chunkfile.getName().substring(2);
			String xname = name.substring(0, name.indexOf('.'));
			name = name.substring(xname.length() + 1);
			String zname = name.substring(0, name.indexOf('.'));
			
			int xOffset = 32 * Integer.parseInt(xname);
			int zOffset = 32 * Integer.parseInt(zname);
			
			raf = new RandomAccessFile(chunkfile, "rw");
			File backupfile = new File(backupfolder, chunkfile.getName());
			int[] locations = new int[1024];
			for (int i = 0; i < 1024; i++) {
				locations[i] = raf.readInt();
			}
			//Validate the data
			int editcount = 0;
			int chunkX = 0;
			int chunkZ = 0;
			
			//x = 5
			//z = 14
			//i = 5 + 14 * 32 = 453
			//x = i % 32 = 5
			//z = [(453 - 5)]  448 / 32 = 
			
			byte[] data = new byte[8096];
			for (int i = 0; i < locations.length; i++) {
				chunkX = i % 32;
				chunkZ = (i - chunkX) >> 5;
				chunkX += xOffset;
				chunkZ += zOffset;
				int location = locations[i];
				if (location == 0) {
					continue;
				}
				try {
					int offset = location >> 8;
                    int size = location & 255;
                    long seekindex = (long) (offset * 4096);
					raf.seek(seekindex);
					int length = raf.readInt();
					//Read and test the data
					if (length > 4096 * size) {
						editcount++;
						locations[i] = 0;
						MyWorlds.plugin.log(Level.WARNING, "Invalid length: " + length + " > 4096 * " + size);
						//Invalid length
					} else if (size > 0 && length > 0) {
						byte version = raf.readByte();
						if (data.length < length + 10) {
							data = new byte[length + 10];
						}
						raf.read(data, 0, length - 1);
						ByteArrayInputStream bais = new ByteArrayInputStream(data, 0, length - 1);
						//Try to load it all...
						DataInputStream stream;
						if (version == 1) {
							stream = new DataInputStream(new BufferedInputStream(new GZIPInputStream(bais)));
						} else if (version == 2) {
							stream = new DataInputStream(new BufferedInputStream(new InflaterInputStream(bais)));
						} else {
							stream = null;
							//Unknown version
							MyWorlds.plugin.log(Level.WARNING, "Unknown region version: " + version + " (we probably need an update here!)");
						}
						if (stream != null) {
							
							//Validate the stream and close
							try {
								CommonTagCompound comp = CommonTagCompound.readFromUncompressed(stream);
								if (comp == null) {
									editcount++;
									locations[i] = 0;
									MyWorlds.plugin.log(Level.WARNING, "Invalid tag compound at chunk " + chunkX + "/" + chunkZ);
								} else {
									//correct location?
									if (comp.containsKey("Level")) {
										CommonTagCompound level = comp.createCompound("Level");
										int xPos = level.getValue("xPos", Integer.MIN_VALUE);
										int zPos = level.getValue("zPos", Integer.MIN_VALUE);
										//valid coordinates?
										if (xPos != chunkX || zPos != chunkZ) {
											MyWorlds.plugin.log(Level.WARNING, "Chunk [" + xPos + "/" + zPos + "] was stored at [" + chunkX + "/" + chunkZ + "], moving...");
											level.putValue("xPos", chunkX);
											level.putValue("zPos", chunkZ);
											//rewrite to stream
											ByteArrayOutputStream baos = new ByteArrayOutputStream(8096);
									        OutputStream outputstream = new DeflaterOutputStream(baos);
									        level.writeToUncompressed(outputstream);
									        outputstream.close();
									        //write to region file
									        raf.seek(seekindex);
									        byte[] newdata = baos.toByteArray();
									        raf.writeInt(newdata.length + 1);
									        raf.writeByte(2);
									        raf.write(newdata, 0, newdata.length);
									        editcount++;
										}
									} else {
										editcount++;
										locations[i] = 0;
										MyWorlds.plugin.log(Level.WARNING, "Invalid tag compound at chunk " + chunkX + "/" + chunkZ);
									}
								}
							} catch (ZipException ex) {
								//Invalid.
								editcount++;
								locations[i] = 0;
								MyWorlds.plugin.log(Level.WARNING, "Chunk at position " + chunkX + "/" + chunkZ + " is not in a valid ZIP format (it's corrupted, and thus lost)");
							} catch (Exception ex) {
								//Invalid.
								editcount++;
								locations[i] = 0;
								MyWorlds.plugin.log(Level.WARNING, "Failed to properly read chunk at position " + chunkX + "/" + chunkZ + ":");
								ex.printStackTrace();
							}
							stream.close();
						}
					}
				} catch (Exception ex) {
					editcount++;
					locations[i] = 0;
					ex.printStackTrace();
				}
			}
			if (editcount > 0) {
				if ((backupfolder.exists() || backupfolder.mkdirs()) && StreamUtil.tryCopyFile(chunkfile, backupfile)) {
					//Write out the new locations
					raf.seek(0);
					for (int location : locations) {
						raf.writeInt(location);
					}
				} else {
					MyWorlds.plugin.log(Level.WARNING, "Failed to make a copy of the file, no changes are made.");
					return -2;
				}
			}
			//Done.
			raf.close();
			return editcount;
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		try {
			if (raf != null) raf.close();
		} catch (Exception ex) {}
		
		try {
			chunkfile.delete();
			return -1;
		} catch (Exception ex) {
			return -3;
		}
	}

	/**
	 * Converts a given world's player folder to the new 1.8 UUID format
	 * @param world
	 * @return
	 */
	public static boolean convertWorldPlayerData(World world) {
		// Don't convert the main world -- this should be handled by the server itself
		if (WorldUtil.getWorlds().iterator().next() == world) {
			return false;
		}

		MyWorlds.plugin.log(Level.INFO, "Beginning UUID conversion for world '" + world.getName() + "'");

		File input = new File(WorldConfig.get(world).getWorldFolder(), "players");
		File output = new File(input.getParentFile(), "playerdata");
		File error = new File(input.getParentFile(), "unknownplayers");

		// Create playerdata directory
		if (!output.exists() && !output.mkdirs()) {
			MyWorlds.plugin.log(Level.WARNING, "Error creating: " + output.getAbsolutePath());
			return false;
		}

		// We have nothing to do if there's no old player folder, consider it migrated
		if (input.exists() && input.isDirectory()) {
			File[] files = input.listFiles();

			if (files == null) {
				return false;
			}

			ArrayList<String> names = new ArrayList<String>();

			// Iterate through all files in players dir
			for (File file : files) {
				String name = null;
				String filename = file.getName();

				if (filename.toLowerCase(Locale.ROOT).endsWith(".dat")) {
					name = filename.substring(0, filename.length() - ".dat".length());
				}

				// Make sure we have a player name
				if (name != null && name.length() > 0) {
					names.add(name);
				}
			}

			Map<String, UUID> uuids = getPlayerIds(names);
			int migratedCount = 0;

			//for (Map.Entry<String, UUID> entry : uuids.entrySet()) {
			for (String name : names) {
				UUID uuid = uuids.get(name);
				File inputDat = new File(input, name + ".dat");
				File outputDat = null;

				// We didn't get a UUID back, move to unknownplayers folder
				if (uuid == null) {
					MyWorlds.plugin.log(Level.WARNING, "Unable to find UUID for player '" + name + "', moving to unknownplayers");
					outputDat = new File(error, name + ".dat");
				}
				// Move data file to playerdata folder
				else {
					outputDat = new File(output, uuid.toString() + ".dat");
				}

				boolean success = migratePlayerFile(name, inputDat, outputDat);

				// Increment migrated count for display purposes
				if (success && uuid != null) {
					migratedCount++;
				}
			}

			// Show summary
			if (names.size() > 0) {
				MyWorlds.plugin.log(Level.INFO, "Migrated " + migratedCount + "/" + names.size() + " data files!");
			}
		}

		return true;
	}

	/**
	 * Gets a map of all valid UUIDs for a given list of player names.
	 * It is possible for a name not to be in the returned map; this means that
	 * the server is in online mode and the name is not valid in the Mojang API.
	 * @param names
	 * @return
	 */
	public static Map<String, UUID> getPlayerIds(List<String> names) {
		final int DELAY_BETWEEN_FAILURES = 1000 * 5;
		final int MAX_FAIL_COUNT = 3;

		Map<String, UUID> uuids = null;

		boolean onlineMode = Bukkit.getServer().getOnlineMode();

		// Use a live Mojang API lookup if the server is running in online mode
		if (onlineMode) {
			UUIDFetcher fetcher = new UUIDFetcher(names);
			int failCount = 0;

			while (true) {
				try {
					uuids = fetcher.call();
					break;
				} catch (Exception e) {
					failCount++;

					MyWorlds.plugin.log(Level.SEVERE, "Exception while fetching UUIDs from Mojang's API:");
					e.printStackTrace();

					// We've reached our max fail count, abort load
					if (failCount == MAX_FAIL_COUNT) {
						MyWorlds.plugin.log(Level.SEVERE, "*** Unable to convert UUIDs due to Mojang API being unaccessible.");
						MyWorlds.plugin.log(Level.SEVERE, "*** Bailing out for safety reasons (eg. player data loss)!");
						MyWorlds.plugin.log(Level.SEVERE, "*** Please start the server again when the API is back up,");
						MyWorlds.plugin.log(Level.SEVERE, "*** or manually remove player data pending conversion.");
						throw new RuntimeException("Unable to convert UUIDs due to Mojang API being unaccessible");
					}

					// Try fetching again in a few seconds
					try {
						MyWorlds.plugin.log(Level.SEVERE, "Retrying in " + DELAY_BETWEEN_FAILURES + " ms...");
						Thread.sleep(DELAY_BETWEEN_FAILURES);
					}
					catch (InterruptedException ignored) {
						// Do nothing
					}
				}
			}
		}
		// Generate UUIDs based on the name for offline mode
		else {
			uuids = new HashMap<String, UUID>();

			for (String name : names) {
				UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
				uuids.put(name, uuid);
			}
		}

		return uuids;
	}

	private static boolean migratePlayerFile(String lastKnownName, File inputDat, File outputDat) {
		// Create output directory
		if (!outputDat.getParentFile().exists() && !outputDat.getParentFile().mkdirs()) {
			MyWorlds.plugin.log(Level.WARNING, "Error creating: " + outputDat.getParentFile().getAbsolutePath());
			return false;
		}

		if (outputDat.exists()) {
			MyWorlds.plugin.log(Level.WARNING, "Player data for '" + lastKnownName + "' already exists, not overwriting");
			return false;
		}

		// Comply with Bukkit's lastKnownName field
		CommonTagCompound root = null;

		try {
			root = CommonTagCompound.readFrom(inputDat);
		} catch (IOException exception) {
			MyWorlds.plugin.log(Level.WARNING, "Error reading: " + inputDat.getAbsolutePath());
		}

		if (root == null) {
			return false;
		}

		if (!root.containsKey("bukkit")) {
			root.put("bukkit", new CommonTagCompound());
		}

		CommonTagCompound data = (CommonTagCompound) root.get("bukkit");
		data.putValue("lastKnownName", lastKnownName);

		// Write fixed dat file
		try {
			root.writeTo(inputDat);
		} catch (IOException exception) {
			MyWorlds.plugin.log(Level.WARNING, "Error writing updated file: " + inputDat.getAbsolutePath());
			return false;
		}

		// Move data file to new location in playerdata
		if (!inputDat.renameTo(outputDat)) {
			MyWorlds.plugin.log(Level.WARNING, "Could not convert file for '" + lastKnownName + "'");
			return false;
		}

		return true;
	}

	@Deprecated
	public static boolean generateData(String worldname, String seed) {
		return WorldConfig.get(worldname).resetData(seed);
	}

	@Deprecated
	public static boolean generateData(String worldname, long seed) {
	    return WorldConfig.get(worldname).resetData(seed);
	}

	@Deprecated
	public static CommonTagCompound createData(String worldname, long seed) {
		return WorldConfig.get(worldname).createData(seed);
	}

	@Deprecated
	public static CommonTagCompound getData(String worldname) {
		return WorldConfig.get(worldname).getData();
	}

	@Deprecated
	public static boolean setData(String worldname, CommonTagCompound data) {
    	return WorldConfig.get(worldname).setData(data);
	}

	@Deprecated
	public static File getDataFile(String worldname) {
		return WorldConfig.get(worldname).getDataFile();
	}

	@Deprecated
	public static File getUIDFile(String worldname) {
		return WorldConfig.get(worldname).getUIDFile();
	}

	@Deprecated
	public static long getWorldSize(String worldname) {
		return WorldConfig.get(worldname).getWorldSize();
	}

	@Deprecated
	public static WorldInfo getInfo(String worldname) {
		return WorldConfig.get(worldname).getInfo();
	}

	@Deprecated
	public static boolean isBroken(String worldname) {
		return WorldConfig.get(worldname).isBroken();
	}

	@Deprecated
	public static boolean isInitialized(String worldname) {
		return WorldConfig.get(worldname).isInitialized();
	}

	@Deprecated
	public static boolean copyWorld(String worldname, String newname) {
		return WorldConfig.get(worldname).copyTo(WorldConfig.get(newname));
	}

	@Deprecated
	public static boolean deleteWorld(String worldname) {
		return WorldConfig.get(worldname).deleteWorld();
	}
}