package ru.moonlightproject.dev.selene.paper;

import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Empty chunks with one fixed biome for the managed limbo and the interactive CAPTCHA worlds.
 * The fixed biome matters: otherwise every new chunk still samples the vanilla climate noise.
 */
final class VoidChunkGenerator extends ChunkGenerator {
 private static final BiomeProvider SINGLE_BIOME=new BiomeProvider(){
  @Override public @NotNull Biome getBiome(@NotNull WorldInfo worldInfo,int x,int y,int z){return Biome.PLAINS;}
  @Override public @NotNull List<Biome> getBiomes(@NotNull WorldInfo worldInfo){return List.of(Biome.PLAINS);}
 };
 @Override public BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo){return SINGLE_BIOME;}
 @Override public boolean shouldGenerateNoise(){return false;}
 @Override public boolean shouldGenerateSurface(){return false;}
 @Override public boolean shouldGenerateCaves(){return false;}
 @Override public boolean shouldGenerateDecorations(){return false;}
 @Override public boolean shouldGenerateMobs(){return false;}
 @Override public boolean shouldGenerateStructures(){return false;}
}
