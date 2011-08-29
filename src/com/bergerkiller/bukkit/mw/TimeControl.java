package com.bergerkiller.bukkit.mw;

import java.util.HashMap;

import org.bukkit.World;

public class TimeControl {
	
	/**
	 * Returns the time value based on a name
	 * Returns -1 if no time format was detected
	 * Some credits go to CommandBook for their name<>time table!
	 * @param timeName
	 */
	public static long getTime(String timeName) {
		try {
			return (Long.parseLong(timeName) - 8) * 1000 ;
		} catch (Exception ex) {
	        // Or some shortcuts
	        if (timeName.equalsIgnoreCase("dawn")) {
	            return 22000;
	        } else if (timeName.equalsIgnoreCase("sunrise")) {
	            return 23000;
	        } else if (timeName.equalsIgnoreCase("morning")) {
	            return 24000;
	        } else if (timeName.equalsIgnoreCase("day")) {
	            return 24000;
	        } else if (timeName.equalsIgnoreCase("midday")) {
	        	return 28000;
	        } else if (timeName.equalsIgnoreCase("noon")) {
	            return 28000;
	        } else if (timeName.equalsIgnoreCase("afternoon")) {
	            return 30000;
	        } else if (timeName.equalsIgnoreCase("evening")) {
	            return 32000;
	        } else if (timeName.equalsIgnoreCase("sunset")) {
	            return 37000;
	        } else if (timeName.equalsIgnoreCase("dusk")) {
	            return 37500;
	        } else if (timeName.equalsIgnoreCase("night")) {
	            return 38000;
	        } else if (timeName.equalsIgnoreCase("midnight")) {
	            return 16000;
	        }
		}
		return -1;
	}
	
	/**
	 * CommandBook getTime function, credit go to them for this!
	 * @param time - The time to parse
	 * @return The name of this time
	 */
    public static String getTimeString(long time) {
        int hours = (int) ((time / 1000 + 8) % 24);
        int minutes = (int) (60 * (time % 1000) / 1000);
        return String.format("%02d:%02d (%d:%02d %s)",
                hours, minutes, (hours % 12) == 0 ? 12 : hours % 12, minutes,
                hours < 12 ? "am" : "pm");
    }
    
    public static String getTimeString(String worldname, long backup) {
    	Locker l = lockers.get(worldname);
    	if (l == null) {
    		World w = WorldManager.getWorld(worldname);
    		if (w == null) {
    			return getTimeString(backup);
    		} else {
        		return getTimeString(w.getTime());
    		}
    	} else {
    		return getTimeString(l.time);
    	}
    }
    
    /*
     * Time locking saving
     */
    public static void load(String filename) {
    	SafeReader reader = new SafeReader(filename);
    	String textline = null;
    	while ((textline = reader.readNonEmptyLine()) != null) {
    		int spaceindex = textline.indexOf(" ");
    		if (spaceindex != -1) {
    			try {
    				long time = Long.parseLong(textline.substring(0, spaceindex));
    				String worldname = textline.substring(spaceindex + 1).toLowerCase();
    				if (!worldname.equals("")) {
    					TimeControl.lockTime(worldname, time);
    					TimeControl.setLocking(worldname, true);
    				}
    			} catch (Exception ex) {};
    		}
    	}
    	reader.close();
    }
    public static void save(String filename) {
    	SafeWriter writer = new SafeWriter(filename);
    	for (String worldname : lockers.keySet()) {
    		Locker l = lockers.get(worldname);
    		writer.writeLine(lockers.get(worldname).time + " " + worldname);
    	}
    	writer.close();
    }
    
    /*
     * Time locking mechanics
     */
    private static HashMap<String, Locker> lockers = new HashMap<String, Locker>();
    
    public static boolean isLocked(String worldname) {
    	return lockers.containsKey(worldname.toLowerCase());
    }
    
    public static void lockTime(String worldname, long time) {
    	worldname = worldname.toLowerCase();
    	Locker l = lockers.get(worldname);
    	if (l == null) {
    		l = new Locker(worldname, time);
    		lockers.put(worldname, l);
    		l.start();
    	} else {
    		l.time = time;
    		if (l.isRunning()) l.stop();
    		l.start();
    	}
    }
    public static void unlockTime(String worldname) {
    	worldname = worldname.toLowerCase();
    	Locker l = lockers.get(worldname);
    	if (l == null) return;
    	l.stop();
    	lockers.remove(worldname);
    }
    
    /*
     * Sets if the time update task should be running
     * See also: World/Plugin load and unload
     */
    public static boolean setLocking(String worldname, boolean locking) {
    	Locker l = lockers.get(worldname.toLowerCase());
    	if (l != null) {
    		if (l.isRunning() != locking) {
            	if (locking) { 
            		return l.start();
            	} else {
            		l.stop();
            		return true;
            	}
    		}
    	}
    	return false;
    }
    
    private static class Locker implements Runnable {

    	public Locker(String worldname, long time) {
    		this.worldname = worldname;
    		this.time = time;
    	}
    	
    	private int id = -1;
    	private String worldname;
    	private World w;
    	private long time;
    	
    	public boolean isRunning() {
    		return this.id != -1;
    	}
		@Override
		public void run() {
			this.w.setFullTime(time);
		}
		
		public void stop() {
			MyWorlds.plugin.getServer().getScheduler().cancelTask(this.id);
			this.id = -1;
		}
		public boolean start() {
			return this.start(MyWorlds.timeLockInterval);
		}
		public boolean start(long interval) {
			this.w = WorldManager.getWorld(this.worldname);
			if (this.w == null) return false;
			this.id = MyWorlds.plugin.getServer().getScheduler().scheduleSyncRepeatingTask(MyWorlds.plugin, this, 0, interval);
			return true;
		}

    }
    
}