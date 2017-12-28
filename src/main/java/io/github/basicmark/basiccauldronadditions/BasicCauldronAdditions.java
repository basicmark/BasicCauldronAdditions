package io.github.basicmark.basiccauldronadditions;

import java.util.*;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Hopper;
import org.bukkit.event.block.*;
import org.bukkit.material.Cauldron;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public class BasicCauldronAdditions extends JavaPlugin implements Listener {
	BukkitTask task = null;
	Set<Block> checkBlocks;

	public void onEnable(){
		checkBlocks = new HashSet<Block>();
		getServer().getPluginManager().registerEvents(this, this);
	}

	public void onDisable(){
	}

	public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
		if (cmd.getName().equalsIgnoreCase("bca")){
			sender.sendMessage("BasicCauldronAdditions version 1.0");
			return true;
		}
		return false;
	}

	private void cauldronConsumeWater(final Block cauldronBlock) {
		/*
			Change 1 tick later as if this function is called from CauldronLevelChangeEvent
			the update gets overwritten.
		*/
		new BukkitRunnable() {
			public void run() {
				BlockState cauldronState = cauldronBlock.getState();
				cauldronState.setRawData((byte) 3);
				cauldronState.update(true);
			}
		}.runTaskLater(this,1);
		BlockState state = cauldronBlock.getRelative(BlockFace.UP).getState();
		state.setType(Material.AIR);
		state.update(true);
	}

    private boolean isSourceBlock(Block block) {
        if ((block.getType() == Material.STATIONARY_WATER) && (block.getState().getRawData() == 0)) {
            return true;
        }
        if ((block.getType() == Material.WATER) && (block.getState().getRawData() == 0)) {
            return true;
        }
        return false;
    }

    private int getAdjancentSourceBlocks(Block block) {
        int sources = 0;
        if (isSourceBlock(block.getRelative(BlockFace.EAST))) {
            sources += 1;
        }
        if (isSourceBlock(block.getRelative(BlockFace.WEST))) {
            sources += 1;
        }
        if (isSourceBlock(block.getRelative(BlockFace.NORTH))) {
            sources += 1;
        }
        if (isSourceBlock(block.getRelative(BlockFace.SOUTH))) {
            sources += 1;
        }
        return sources;
    }

	/*
		Bukkit has no event for detecting water source being created
		so to achieve this we monitor the BlockFromToEvent which informs
		us that water/lava is moving and where to, if the to location has
		the right conditions to form a source block then add it to the
		checkBlock set and kick the task to check if a source block has
		formed or not, if the conditions have changed or the block is not
		in a loaded chunk anymore then remove the block from checkBlock.
		Finally when checkBlock becomes empty cancel the task.
	 */

	private void addSourceBlockToCheck(Block block) {
		checkBlocks.add(block);
		if (task == null) {
			task = new BukkitRunnable() {
				public void run() {
					Iterator<Block> iter = checkBlocks.iterator();
					while(iter.hasNext()) {
						Block b = iter.next();
						if (!b.getChunk().isLoaded()) {
							iter.remove();
						}
						if (b.getType() == Material.STATIONARY_WATER) {
							Block cauldronBlock = b.getRelative(BlockFace.DOWN);
							if (cauldronBlock.getType() == Material.CAULDRON) {
								Cauldron cauldron = (Cauldron) cauldronBlock.getState().getData();
								if (cauldron.isEmpty()) {
									cauldronConsumeWater(b.getRelative(BlockFace.DOWN));
								}
							}
							iter.remove();
						}
						if (getAdjancentSourceBlocks(b) < 2) {
							iter.remove();
						}
					}
					if (checkBlocks.isEmpty()) {
						cancel();
						task = null;
					}
				}
			}.runTaskTimer(this,1, 1);
		}
	}

	@EventHandler
	public void onBlockFromToEvent(BlockFromToEvent event)
	{
		Block block = event.getToBlock();
		int sources = getAdjancentSourceBlocks(block);
		if (sources >= 2) {
			if (block.getRelative(BlockFace.DOWN).getType() == Material.CAULDRON) {
				addSourceBlockToCheck(block);
			}
		}
	}

	@EventHandler
	public void onCauldronChange(CauldronLevelChangeEvent event) {
		final Block block = event.getBlock();
		if (block.getType() == Material.CAULDRON) {
			Cauldron cauldron = (Cauldron) block.getState().getData();
			if (event.getNewLevel() == 0) {
				Block aboveBlock = block.getRelative(BlockFace.UP);
				if (isSourceBlock(aboveBlock)) {
					event.setNewLevel(3);
					/*
						Setting the level above doesn't seem to change the result
						of the operation (i.e. the cauldron is still empty so
						call cauldronConsumeWater which will (later) update the
						cauldron level.
					 */
					cauldronConsumeWater(block);
				}
			}
		}
	}

	private void createItem(Block cauldron, ItemStack item) {
		Block underBlock = cauldron.getRelative(BlockFace.DOWN);
		boolean created = false;

		// Attempt to add the dropped item(s) in a hopper if there is one below ...
		if (underBlock.getType() == Material.HOPPER) {
			Hopper hopper = (Hopper) underBlock.getState();
			HashMap<Integer, ItemStack> remain = hopper.getInventory().addItem(item);
			if (remain.isEmpty()) {
				created = true;
			}
		}

		// ... otherwise drop it on the ground.
		if (!created) {
			cauldron.getWorld().dropItem(cauldron.getLocation(), item);
		}
	}

	@EventHandler
	public void onPlayerInteract(PlayerInteractEvent event) {
		Block clickedBlock = event.getClickedBlock();
		ItemStack clickedItem = event.getItem();
		Player player = event.getPlayer();

		if ((event.getAction() == Action.RIGHT_CLICK_BLOCK) && (clickedBlock.getType() == Material.CAULDRON)) {
			Cauldron cauldron = (Cauldron) clickedBlock.getState().getData();
			if ((clickedItem != null) && (clickedItem.getType() == Material.CONCRETE_POWDER)) {
				if (cauldron.isFull()) {
					// Take one or more powder and drop a new itemstack by/under the cauldron
					if (clickedItem.getAmount() != 0) {
						ItemStack toDrop = new ItemStack(Material.CONCRETE, 1);
						toDrop.setDurability(clickedItem.getDurability());

						// Fire a cauldron change level event which will trigger the refill
						CauldronLevelChangeEvent changeEvent = new CauldronLevelChangeEvent(clickedBlock, player, CauldronLevelChangeEvent.ChangeReason.UNKNOWN, 3, 0);
						getServer().getPluginManager().callEvent(changeEvent);
						if (changeEvent.isCancelled()) {
							return;
						}

						// Update the level based on what the level change event did
						BlockState cauldronState = clickedBlock.getState();
						cauldronState.setRawData((byte) changeEvent.getNewLevel());
						cauldronState.update();

						// Take the item away from the player
						clickedItem.setAmount(clickedItem.getAmount() - 1);

						// Drop the created item
						createItem(clickedBlock, toDrop);
					}
				}
				event.setCancelled(true);
			}
			if ((clickedItem != null) && (clickedItem.getType() == Material.LAVA_BUCKET)) {
				if (cauldron.isEmpty()) {
					Block aboveBlock = clickedBlock.getRelative(BlockFace.UP);
					if ((aboveBlock.getType() == Material.WATER) || ((aboveBlock.getType() == Material.STATIONARY_WATER))) {
						ItemStack toDrop = new ItemStack(Material.OBSIDIAN, 1);
						// Consume the lava
						clickedItem.setType(Material.BUCKET);

						// Drop the created item
						createItem(clickedBlock, toDrop);
					}
				}
				event.setCancelled(true);
			}
		}
	}
}

