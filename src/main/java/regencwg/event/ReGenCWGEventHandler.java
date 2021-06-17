package regencwg.event;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import com.google.common.collect.ImmutableList;
import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.world.CubeWatchEvent;
import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorldServer;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubeGenerator;
import io.github.opencubicchunks.cubicchunks.core.CommonEventHandler;
import io.github.opencubicchunks.cubicchunks.cubicgen.customcubic.CustomGeneratorSettings;
import io.github.opencubicchunks.cubicchunks.cubicgen.customcubic.CustomGeneratorSettings.IntAABB;
import io.github.opencubicchunks.cubicchunks.cubicgen.customcubic.CustomTerrainGenerator;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.WorldWorkerManager;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.server.FMLServerHandler;
import regencwg.ReGenArea;
import regencwg.ReGenCWGMod;
import regencwg.ThreadUtils;
import regencwg.world.BlockReplaceConfig;
import regencwg.world.WorldSavedDataReGenCWG;
import regencwg.world.storage.DiskDataUtil;

import net.minecraft.command.ICommandSender;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

public class ReGenCWGEventHandler {

	Int2ObjectMap<List<ReGenArea>> oresAtDimension = new Int2ObjectOpenHashMap<List<ReGenArea>>();
	Int2ObjectMap<BlockReplaceConfig> blockReplaceConfigAtDimension = new Int2ObjectOpenHashMap<BlockReplaceConfig>();
	private static final String FILE_NAME = "custom_generator_settings.json";
	private static final String RC_FILE_NAME = "replace_config.json";

	public ReGenCWGEventHandler() {
		ImmutableList<ReGenArea> list = ImmutableList.<ReGenArea>builder().build();
		oresAtDimension.defaultReturnValue(list);
	}

	@SubscribeEvent
	public void onWorldLoadEvent(WorldEvent.Load event) {
		World world = event.getWorld();
		if (world.isRemote || !(world instanceof WorldServer)) {
			return;
		}
		world.getMinecraftServer().addScheduledTask(() -> {
			String settingString = loadJsonStringFromSaveFolder(event.getWorld(), FILE_NAME);
			CustomGeneratorSettings config = CustomGeneratorSettings.fromJson(settingString);
			ArrayList<ReGenArea> areas = new ArrayList<>();
			if (!config.standardOres.list.isEmpty() || !config.periodicGaussianOres.list.isEmpty()) {
				areas.add(new ReGenArea(config));
			}
			this.addReGenAreasToList(areas, config);
			WorldSavedDataReGenCWG data = WorldSavedDataReGenCWG.getOrCreateWorldSavedData(world);
			oresAtDimension.put(event.getWorld().provider.getDimension(), areas);
			if (!data.isInitialized()) {
				data.initialize(world);
			}

			File rcSettingFile = getSettingsFile(event.getWorld(), RC_FILE_NAME);
			if (rcSettingFile.exists()) {
				BlockReplaceConfig brc = BlockReplaceConfig.fromFile(rcSettingFile);
				if (!brc.replaceMap.isEmpty()) {
					blockReplaceConfigAtDimension.put(event.getWorld().provider.getDimension(), brc);
				}
			}
		});
	}
	
	private void addReGenAreasToList(List<ReGenArea> areas, CustomGeneratorSettings setting) {
		for (Entry<IntAABB, CustomGeneratorSettings> entry : setting.cubeAreas.map) {
			if (!entry.getValue().standardOres.list.isEmpty() || !entry.getValue().periodicGaussianOres.list.isEmpty()) {
				areas.add(new ReGenArea(entry.getKey(), entry.getValue()));
			}
			this.addReGenAreasToList(areas, entry.getValue());
		}
	}

	@SubscribeEvent
	public void onCubeWatchEvent(CubeWatchEvent event) {
		World world = (World) event.getWorld();
		WorldSavedDataReGenCWG data = WorldSavedDataReGenCWG.getOrCreateWorldSavedData(world);
		LinkedHashSet<CubePos> remaining = data.remainingCP.getNow(null);
		if (remaining == null) {
			return;
		}
		CubePos pos = event.getCubePos();
		if (remaining.remove(pos)) {
			this.populate(pos, event.getCube(), world);
		}
		data.markDirty();
	}

	public CompletableFuture<Integer> runReplacer(World world, WeakReference<ICommandSender> sender) {
		int dimensionId = world.provider.getDimension();
		if (!blockReplaceConfigAtDimension.containsKey(dimensionId)) {
			return CompletableFuture.completedFuture(0);
		}
		int dimId = world.provider.getDimension();

		BlockReplaceConfig rc = blockReplaceConfigAtDimension.get(dimensionId);
		CompletableFuture<Set<CubePos>> futureCubesToRegen = new CompletableFuture<>();
		ThreadUtils.backgroundExecutor().execute(() -> {
			Set<CubePos> positions = new LinkedHashSet<>();
			DiskDataUtil.addAllSavedCubePosToSet(world, positions, futureCubesToRegen::isCancelled);
			futureCubesToRegen.complete(positions);
		});
		return futureCubesToRegen.thenCompose(cubes -> {
			CompletableFuture<Integer> workerFuture = new CompletableFuture<>();
			WorldWorkerManager.IWorker worker = new WorldWorkerManager.IWorker() {
				final Iterator<CubePos> iterator = cubes.iterator();
				int affectedCubes;
				int totalCubes;
				int cubesSinceCleanup = 200;
				@Override
				public boolean hasWork() {
					return !workerFuture.isCancelled() && iterator.hasNext();
				}

				@Override
				public boolean doWork() {
					if (workerFuture.isCancelled()) {
						return false;
					}
					cubesSinceCleanup++;
					totalCubes++;
					World world = DimensionManager.getWorld(dimId);
					if (rc.runOneTask(world, iterator.next())) {
						affectedCubes++;
					}
					if (!hasWork()) {
						workerFuture.complete(affectedCubes);
					}
					if ((totalCubes & 1023) == 0) {
						String message = "ReGenCWG replace worker@" + Integer.toHexString(hashCode()) + ": processed " + totalCubes + " of " + cubes.size() + " cubes";
						ReGenCWGMod.logger.info(message);
						ICommandSender senderVal = sender.get();
						if (senderVal != null) {
							try {
								senderVal.sendMessage(new TextComponentString(message));
							} catch (Exception ex) {
								ReGenCWGMod.logger.catching(ex);
								sender.clear();
							}
						}
					}
					if (cubesSinceCleanup > 200) {
						((ICubicWorldServer) world).unloadOldCubes();
						cubesSinceCleanup = 0;
					}
					return hasWork();
				}
			};
			WorldWorkerManager.addWorker(worker);
			return workerFuture;
		});
	}

	public void populate(CubePos pos, ICube cube, World world) {
		List<ReGenArea> ores = oresAtDimension.get(world.provider.getDimension());
		for (ReGenArea area : ores) {
			area.generateIfInArea(world, pos, cube.getBiome(pos.getCenterBlockPos()));
		}
	}

	private static File getSettingsFile(World world, String fileName) {
		File worldDirectory = world.getSaveHandler().getWorldDirectory();
		String subfolder = world.provider.getSaveFolder();
		if (subfolder == null)
			subfolder = "";
		else
			subfolder += "/";
		File settings = new File(worldDirectory, "./" + subfolder + "data/" + ReGenCWGMod.MODID + "/" + fileName);
		return settings;
	}

	public static String loadJsonStringFromSaveFolder(World world, String fileName) {
		File settings = getSettingsFile(world, fileName);
		if (settings.exists()) {
			try (FileReader reader = new FileReader(settings)) {
				CharBuffer sb = CharBuffer.allocate((int) settings.length());
				reader.read(sb);
				sb.flip();
				return sb.toString();
			} catch (IOException e) {
				e.printStackTrace();
			}
		} else {
			ReGenCWGMod.logger.info("No settings provided at path:" + settings.toString());
		}
		return null;
	}

	public void addReplacerConfig(int dimension, String string) {
		BlockReplaceConfig brc = BlockReplaceConfig.fromString(string);
		blockReplaceConfigAtDimension.put(dimension, brc);
	}
}
