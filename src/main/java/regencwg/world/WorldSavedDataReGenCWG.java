package regencwg.world;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.collect.Maps;
import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldSavedData;

import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.server.FMLServerHandler;
import regencwg.ReGenCWGMod;
import regencwg.ThreadUtils;
import regencwg.world.storage.DiskDataUtil;

public class WorldSavedDataReGenCWG extends WorldSavedData {

	final static String DATA_IDENTIFIER = ReGenCWGMod.MODID+"Data";
	public CompletableFuture<LinkedHashSet<CubePos>> remainingCP = new CompletableFuture<>();
	private AtomicBoolean isInitialized = new AtomicBoolean(false);

	public WorldSavedDataReGenCWG(String name) {
		super(name);
	}

	public void initialize(World world) {
		remainingCP.cancel(true);
		CompletableFuture<LinkedHashSet<CubePos>> future = new CompletableFuture<>();
		ThreadUtils.backgroundExecutor().execute(() -> {
			LinkedHashSet<CubePos> cubes = new LinkedHashSet<>();
			DiskDataUtil.addAllSavedCubePosToSet(world, cubes, future::isCancelled);
			future.complete(cubes);
		});
		remainingCP = future.thenApplyAsync(value -> {
			markDirty();
			isInitialized.set(true);
			return value;
		}, r -> DimensionManager.getWorld(0).getMinecraftServer().addScheduledTask(r));
	}
	
	public boolean isInitialized() {
		return isInitialized.get() && remainingCP.isDone();
	}

	@Override
	public void readFromNBT(NBTTagCompound nbt) {
		isInitialized.set(nbt.getBoolean("isInitialized"));
		int[] remainingCPIA = nbt.getIntArray("remainingCP");
		LinkedHashSet<CubePos> remaining = new LinkedHashSet<>();
		for (int i = 0; i < remainingCPIA.length / 3; i += 3) {
			remaining.add(new CubePos(remainingCPIA[i], remainingCPIA[i + 1], remainingCPIA[i + 2]));
		}
		remainingCP = CompletableFuture.completedFuture(remaining);
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound compound) {
		compound.setBoolean("isInitialized",isInitialized.get());
		int i=-1;
		LinkedHashSet<CubePos> remaining = remainingCP.getNow(null);
		int[] remainingCPIA = new int[remaining == null ? 0 : remaining.size()*3];
		if (remaining != null) {
			for (CubePos pos : remaining) {
				remainingCPIA[++i] = pos.getX();
				remainingCPIA[++i] = pos.getY();
				remainingCPIA[++i] = pos.getZ();
			}
		}
		compound.setIntArray("remainingCP", remainingCPIA);
		return compound;
	}

	public static WorldSavedDataReGenCWG getOrCreateWorldSavedData(World worldIn) {
		WorldSavedDataReGenCWG data = (WorldSavedDataReGenCWG) worldIn.getPerWorldStorage().getOrLoadData(WorldSavedDataReGenCWG.class, WorldSavedDataReGenCWG.DATA_IDENTIFIER);
		if(data == null){
			data = new WorldSavedDataReGenCWG(DATA_IDENTIFIER);
			worldIn.getPerWorldStorage().setData(DATA_IDENTIFIER, data);
		}
		return data;
	}

	public int getRemaining() {
		LinkedHashSet<CubePos> remaining = remainingCP.getNow(null);
		return remaining == null ? -1 : remaining.size();
	}

	public void stop() {
		remainingCP.cancel(true);
		remainingCP = CompletableFuture.completedFuture(new LinkedHashSet<>());
		this.markDirty();
	}
}
