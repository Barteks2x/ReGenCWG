package regencwg;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.cubicgen.customcubic.CustomGeneratorSettings;
import io.github.opencubicchunks.cubicchunks.cubicgen.customcubic.CustomGeneratorSettings.IntAABB;
import io.github.opencubicchunks.cubicchunks.cubicgen.customcubic.populator.DefaultDecorator;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

public class ReGenArea {
	final IntAABB area;
	final DefaultDecorator.Ores ores;
	
	public ReGenArea(CustomGeneratorSettings settingsIn) {
		this(intAabb(-300000, -300000, -300000, 300000, 300000, 300000), settingsIn);
	}

	private static IntAABB intAabb(int x1, int y1, int z1, int x2, int y2, int z2) {
		IntAABB aabb = new IntAABB();
		aabb.minX = x1;
		aabb.minY = y1;
		aabb.minZ = z1;
		aabb.maxX = x2;
		aabb.maxY = y2;
		aabb.maxZ = z2;
		return aabb;
	}

	public ReGenArea(IntAABB areaIn, CustomGeneratorSettings settingsIn) {
		area = areaIn;
		ores = new DefaultDecorator.Ores(settingsIn);
	}

	public void generateIfInArea(World world, CubePos pos, Biome biome) {
		if (area.contains(pos.getX(), pos.getY(), pos.getZ()))
			ores.generate(world, world.rand, pos, biome);
	}
}
