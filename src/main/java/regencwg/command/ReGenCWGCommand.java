package regencwg.command;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.concurrent.CompletableFuture;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;

import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorldServer;
import net.minecraftforge.common.WorldWorkerManager;
import net.minecraftforge.fml.server.FMLServerHandler;
import regencwg.ReGenCWGMod;
import regencwg.world.WorldSavedDataReGenCWG;

public class ReGenCWGCommand extends CommandBase {

	@Override
	public String getName() {
		return "regencwg";
	}

	@Override
	public String getUsage(ICommandSender sender) {
		return "/" + getName() + " [remaining|reset|stop|finish] <dimension_id>";
	}

	@Override
	public int getRequiredPermissionLevel() {
		return 2;
	}

	@Override
	public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
		if (args.length < 2) {
			throw new WrongUsageException(getUsage(sender), new Object[0]);
		} else {
			int dimension = 0;
			try {
				dimension = Integer.parseInt(args[1]);
			} catch (NumberFormatException e) {
				throw new WrongUsageException(args[1] + " is not a valid dimension id.");
			}
			WorldServer world = server.getWorld(dimension);
			WorldSavedDataReGenCWG data = WorldSavedDataReGenCWG.getOrCreateWorldSavedData(world);
			if (args[0].equals("remaining")) {
				sender.sendMessage(new TextComponentString("Remaining cubes: " + data.getRemaining()));
			} else if (args[0].equals("reset")) {
				data.initialize(world);
				data.remainingCP.whenCompleteAsync((positions, ex) -> {
					if (positions == null) {
						ReGenCWGMod.logger.catching(ex);
						sender.sendMessage(new TextComponentString("Reset failed: " + ex + ". See log for details"));
						return;
					}
					sender.sendMessage(new TextComponentString(
							"All saved on disk cubes will be repopulated with ores. Remaining cubes: "
									+ data.getRemaining()));
				}, server::addScheduledTask);

			} else if (args[0].equals("stop")) {
				data.stop();
				sender.sendMessage(
						new TextComponentString("Repopulation stopped. ReGenCWG will not alter existing cubes."));
			} else if (args[0].equals("finish")) {
				if (checkForWatchdog()) {
					sender.sendMessage(new TextComponentString(
							"Watchdog is active. If population process will be interrupted by watchdog, cube data may be corrupted. Restart server with watchdog disabled before launching this command."));
					return;
				}
				sender.sendMessage(new TextComponentString("Starting population process. This will take time."));
				WeakReference<ICommandSender> senderRef = new WeakReference<>(sender);
				CompletableFuture<Void> replacedFuture = schedulePopulation(world, data, senderRef);
				replacedFuture.whenCompleteAsync((val, ex) -> {
					String message = "ReGenCWG population worker@" + Integer.toHexString(hashCode()) + ": " + (ex == null ? "Done" : " Cancelled");
					ICommandSender senderVal = senderRef.get();
					ReGenCWGMod.logger.info(message);
					if (senderVal != null) {
						try {
							senderVal.sendMessage(new TextComponentString(message));
						} catch (Exception ex2) {
							ReGenCWGMod.logger.catching(ex2);
							senderRef.clear();
						}					}
				}, server::addScheduledTask);
			} else if (args[0].equals("replace")) {
				if (checkForWatchdog()) {
					sender.sendMessage(new TextComponentString(
							"Watchdog is active. If replacing process will be interrupted by watchdog, cube data may be corrupted. Restart server with watchdog disabled before launching this command."));
					return;
				}
				sender.sendMessage(new TextComponentString("Starting block replacing process. This will take time."));
				WeakReference<ICommandSender> senderRef = new WeakReference<>(sender);
				CompletableFuture<Integer> replacedFuture = ReGenCWGMod.eventHandler.runReplacer(world, senderRef);
				replacedFuture.whenCompleteAsync((replaced, exception) -> {
					ICommandSender senderVal = senderRef.get();
					String msg = exception == null ?
					             "ReGenCWG: Replacing job is done. " + replaced + " cubes was altered." :
					             "ReGenCWG: Replacing job cancelled";
					ReGenCWGMod.logger.info(msg);
					if (senderVal != null) {
						try {
							senderVal.sendMessage(new TextComponentString(msg));
						} catch (Exception ex) {
							ReGenCWGMod.logger.catching(ex);
							senderRef.clear();
						}
					}
				}, server::addScheduledTask);
			} else if (args[0].equals("replace-config")) {
				if (args.length != 3)
					throw new WrongUsageException(getUsage(sender), new Object[0]);
				ReGenCWGMod.eventHandler.addReplacerConfig(dimension, args[2]);
			} else {
				throw new WrongUsageException(getUsage(sender), new Object[0]);
			}

		}
	}

	private CompletableFuture<Void> schedulePopulation(WorldServer world, WorldSavedDataReGenCWG data, WeakReference<ICommandSender> senderRef) {
		return data.remainingCP.thenComposeAsync(positions -> {
			CompletableFuture<Void> populationFuture = new CompletableFuture<>();
			WorldWorkerManager.IWorker worker = new WorldWorkerManager.IWorker() {
				private int cubesSinceCleanup;
				private int cubesDone;

				@Override
				public boolean hasWork() {
					if (positions != data.remainingCP.getNow(null)) {
						String message = "ReGenCWG population worker@" + Integer.toHexString(hashCode()) + ": cancelled (remaining chunks reset)";
						ReGenCWGMod.logger.info(message);
						ICommandSender senderVal = senderRef.get();
						if (senderVal != null) {
							try {
								senderVal.sendMessage(new TextComponentString(message));
							} catch (Exception ex) {
								ReGenCWGMod.logger.catching(ex);
								senderRef.clear();
							}
						}
						return false;
					}
					return !positions.isEmpty();
				}

				@Override
				public boolean doWork() {
					Iterator<CubePos> iterator = positions.iterator();
					CubePos next = iterator.next();
					iterator.remove();
					data.setDirty(true);
					ReGenCWGMod.eventHandler.populate(next, ((ICubicWorld) world).getCubeFromCubeCoords(next), world);
					cubesDone++;
					cubesSinceCleanup++;
					if ((cubesDone & 1023) == 0) {
						String message = "ReGenCWG population worker@" + Integer.toHexString(hashCode()) + ": " + positions.size() + " cubes left";
						ReGenCWGMod.logger.info(message);
						ICommandSender senderVal = senderRef.get();
						if (senderVal != null) {
							try {
								senderVal.sendMessage(new TextComponentString(message));
							} catch (Exception ex) {
								ReGenCWGMod.logger.catching(ex);
								senderRef.clear();
							}
						}
					}
					if (cubesSinceCleanup > 200) {
						((ICubicWorldServer) world).unloadOldCubes();
						cubesSinceCleanup = 0;
					}
					if (!hasWork()) {
						populationFuture.complete(null);
					}
					return hasWork();
				}
			};
			WorldWorkerManager.addWorker(worker);
			return populationFuture;
		});
	}

	private boolean checkForWatchdog() {
		for (Thread t : Thread.getAllStackTraces().keySet()) {
			if (t.getName().equals("Server Watchdog")) {
				return true;
			}
		}
		return false;
	}
}
