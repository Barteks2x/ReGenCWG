package regencwg.world.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.function.BooleanSupplier;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorldServer;
import io.github.opencubicchunks.cubicchunks.api.world.storage.ICubicStorage;
import io.github.opencubicchunks.cubicchunks.core.server.chunkio.ICubeIO;
import io.github.opencubicchunks.cubicchunks.core.world.ICubeProviderInternal;
import regencwg.ReGenCWGMod;

import net.minecraft.world.World;

public class DiskDataUtil {

	public static void addAllSavedCubePosToSet(World world, Set<CubePos> posSet, BooleanSupplier isInterrupted) {
		try {
			// TODO: add public API in CC for this
			ICubicWorldServer cubicWorld = (ICubicWorldServer) world;
			ICubeProviderInternal.Server cubeCache = (ICubeProviderInternal.Server) cubicWorld.getCubeCache();
			ICubeIO cubeIo = cubeCache.getCubeIO();
			Field storageField = cubeIo.getClass().getDeclaredField("storage");
			storageField.setAccessible(true);
			ICubicStorage storage = (ICubicStorage) storageField.get(cubeIo);
			storage.forEachCube(e -> {
				if (isInterrupted.getAsBoolean()) {
					throw new StoppedException();
				}
				posSet.add(e);
			});
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (StoppedException e) {
			ReGenCWGMod.logger.info("Collecting cubes stopped");
		}
	}

	private static class StoppedException extends RuntimeException {
	}
}
