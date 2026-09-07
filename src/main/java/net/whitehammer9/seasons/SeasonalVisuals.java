package net.whitehammer9.seasons;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Optional ProtocolLib presentation layer. It never registers or edits server
 * biomes: only outgoing Java-client chunk packets are rewritten.
 */
final class SeasonalVisuals {
    private static final Field PACKET_BUFFER = packetBufferField();
    private final Plugin plugin;
    private final Function<World, String> seasonProvider;
    private final Map<String, Holder<Biome>> palettes = new HashMap<>();

    SeasonalVisuals(SeasonsPlugin plugin, Function<World, String> seasonProvider) {
        this.plugin = plugin;
        this.seasonProvider = seasonProvider;
        DedicatedServer server = ((CraftServer) Bukkit.getServer()).getServer();
        Registry<Biome> biomes = server.registryAccess().lookupOrThrow(Registries.BIOME);
        for (String season : new String[] {"SPRING", "SUMMER", "FALL", "WINTER"}) {
            String configured = plugin.getConfig().getString("visuals."
                    + season.toLowerCase(Locale.ROOT) + "-biome", "PLAINS");
            palettes.put(season, holder(biomes, configured));
        }
    }

    void register() {
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL,
                PacketType.Play.Server.MAP_CHUNK) {
            @Override
            public void onPacketSending(PacketEvent event) {
                Player player = event.getPlayer();
                Holder<Biome> palette = palettes.get(seasonProvider.apply(player.getWorld()));
                if (palette == null) return;
                try {
                    ClientboundLevelChunkPacketData packetData = event.getPacket()
                            .getSpecificModifier(ClientboundLevelChunkPacketData.class).read(0);
                    replacePacketBiomes(packetData, player.getWorld(), palette);
                } catch (Exception exception) {
                    plugin.getLogger().fine("Skipped one seasonal visual packet: " + exception.getClass().getSimpleName());
                }
            }
        });
    }

    private static void replacePacketBiomes(ClientboundLevelChunkPacketData packetData, World world,
            Holder<Biome> palette) throws IllegalAccessException {
        FriendlyByteBuf input = packetData.getReadBuffer();
        PalettedContainerFactory factory = PalettedContainerFactory.create(((CraftWorld) world).getHandle().registryAccess());
        int sectionCount = ((CraftWorld) world).getHandle().getSectionsCount();
        LevelChunkSection[] sections = new LevelChunkSection[sectionCount];
        int size = 0;
        for (int index = 0; index < sectionCount; index++) {
            LevelChunkSection section = new LevelChunkSection(factory);
            section.read(input);
            for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) {
                section.setNoiseBiome(x, y, z, palette);
            }
            sections[index] = section;
            size += section.getSerializedSize();
        }
        FriendlyByteBuf output = new FriendlyByteBuf(Unpooled.buffer(size));
        for (LevelChunkSection section : sections) section.write(output);
        byte[] bytes = new byte[output.readableBytes()];
        output.getBytes(0, bytes);
        PACKET_BUFFER.set(packetData, bytes);
    }

    private static Holder<Biome> holder(Registry<Biome> biomes, String configured) {
        String path = configured.toLowerCase(Locale.ROOT);
        return biomes.get(ResourceKey.create(Registries.BIOME,
                Identifier.fromNamespaceAndPath("minecraft", path))).orElseThrow(
                        () -> new IllegalArgumentException("Unknown vanilla visual biome: " + configured));
    }

    private static Field packetBufferField() {
        try {
            Field field = ClientboundLevelChunkPacketData.class.getDeclaredField("buffer");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unsupported Paper chunk-packet layout", exception);
        }
    }
}
