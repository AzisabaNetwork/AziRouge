package net.azisaba.aziRouge.dungeon;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.config.MiningGimmickSettings;
import net.azisaba.aziRouge.config.MiningGimmickType;
import net.azisaba.aziRouge.config.MiningOreSettings;
import net.azisaba.aziRouge.config.MiningSettings;
import net.azisaba.aziRouge.math.BlockBox;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Switch;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Random;
import java.util.Set;

public final class MiningService implements Listener {
    private static final int MAX_TRIGGER_HEIGHT_ABOVE_FLOOR = 4;

    private static final List<BlockFace> FACES = List.of(
            BlockFace.NORTH,
            BlockFace.SOUTH,
            BlockFace.EAST,
            BlockFace.WEST,
            BlockFace.UP,
            BlockFace.DOWN
    );

    private final AziRouge plugin;
    private final Map<BlockKey, TriggerGimmick> triggers = new HashMap<>();

    public MiningService(AziRouge plugin) {
        this.plugin = plugin;
    }

    public void clearWorld(World world) {
        triggers.keySet().removeIf(key -> key.worldName().equals(world.getName()));
    }

    public void clearAll() {
        triggers.clear();
    }

    public void populate(World world, List<PlacedPiece> pieces, Random random, ChestPopulator chestPopulator) {
        populate(world, pieces, random, chestPopulator, List.of());
    }

    public void populate(
            World world,
            List<PlacedPiece> pieces,
            Random random,
            ChestPopulator chestPopulator,
            List<MiningPreparedGimmick> preparedGimmicks
    ) {
        MiningSettings settings = plugin.settings().azirouge().mining();
        if (!settings.enabled() || pieces.isEmpty()) {
            plugin.debugLogger().log("mining", "populate_skipped", Map.of(
                    "enabled", settings.enabled(),
                    "pieces", pieces.size(),
                    "world", world.getName()
            ));
            return;
        }
        clearWorld(world);
        plugin.debugLogger().log("mining", "populate_begin", Map.of(
                "oresPerPiece", settings.oresPerPiece(),
                "pieces", pieces.size(),
                "preparedGimmicks", preparedGimmicks.size(),
                "triggersPerDungeon", settings.triggersPerDungeon(),
                "world", world.getName()
        ));

        Set<BlockKey> reservedBlocks = reservedPreparedBlocks(world, preparedGimmicks);
        int oresPlaced = 0;
        for (PlacedPiece piece : pieces) {
            for (int index = 0; index < settings.oresPerPiece(); index++) {
                MiningOreSettings ore = selectOre(settings.ores(), piece.depth(), random);
                if (ore != null) {
                    SurfacePlacement placement = placeSurfaceBlock(world, piece.worldBounds(), ore.material(), settings.backingMaterial(), random, reservedBlocks);
                    if (placement != null) {
                        oresPlaced++;
                        plugin.debugLogger().log("mining", "ore_placed", Map.of(
                                "depth", piece.depth(),
                                "material", ore.material(),
                                "piece", piece.template().id(),
                                "x", placement.block().getX(),
                                "y", placement.block().getY(),
                                "z", placement.block().getZ()
                        ));
                    }
                } else {
                    plugin.debugLogger().log("mining", "ore_skipped_no_definition", Map.of(
                            "depth", piece.depth(),
                            "piece", piece.template().id()
                    ));
                }
            }
        }

        List<PlacedPiece> ordered = new ArrayList<>(pieces);
        ordered.sort(Comparator.comparingInt(PlacedPiece::depth));
        List<MiningPreparedGimmick> remainingPreparedGimmicks = new ArrayList<>(preparedGimmicks);
        int placed = registerFixedPreparedGimmicks(remainingPreparedGimmicks, chestPopulator);
        placed += installRedstoneDoorGimmicks(remainingPreparedGimmicks);
        placed += registerRandomPreparedGimmicks(
                world,
                ordered,
                remainingPreparedGimmicks,
                chestPopulator,
                reservedBlocks,
                random,
                settings,
                settings.triggersPerDungeon() - placed
        );
        for (PlacedPiece piece : ordered) {
            if (placed >= settings.triggersPerDungeon()) {
                break;
            }
            SurfacePlacement placement = placeTriggerSurfaceBlock(world, piece.worldBounds(), settings.triggerMaterial(), settings.backingMaterial(), random, reservedBlocks);
            if (placement == null) {
                plugin.debugLogger().log("mining", "trigger_no_surface", Map.of(
                        "depth", piece.depth(),
                        "piece", piece.template().id()
                ));
                continue;
            }
            Optional<TriggerGimmick> preparedGimmick = selectPreparedGimmick(
                    remainingPreparedGimmicks,
                    placement.block(),
                    settings.minTriggerGimmickDistance(),
                    chestPopulator
            );
            TriggerTarget target = preparedGimmick.isPresent()
                    ? null
                    : selectTriggerTarget(world, pieces, piece, placement.block(), settings.minTriggerGimmickDistance()).orElse(null);
            if (preparedGimmick.isEmpty() && target == null) {
                placement.block().setType(settings.backingMaterial(), false);
                plugin.debugLogger().log("mining", "trigger_no_distant_target", Map.of(
                        "depth", piece.depth(),
                        "minDistance", settings.minTriggerGimmickDistance(),
                        "piece", piece.template().id(),
                        "x", placement.block().getX(),
                        "y", placement.block().getY(),
                        "z", placement.block().getZ()
                ));
                continue;
            }
            TriggerGimmick gimmick = preparedGimmick.orElseGet(() -> createDynamicGimmick(target, random, chestPopulator));
            if (gimmick == null) {
                placement.block().setType(settings.backingMaterial(), false);
                plugin.debugLogger().log("mining", "trigger_skipped_no_weighted_gimmick", Map.of(
                        "depth", piece.depth(),
                        "piece", piece.template().id(),
                        "x", placement.block().getX(),
                        "y", placement.block().getY(),
                        "z", placement.block().getZ()
                ));
                continue;
            }
            triggers.put(BlockKey.of(placement.block()), gimmick);
            placed++;
            plugin.debugLogger().log("mining", "trigger_placed", Map.of(
                    "gimmick", gimmick.type(),
                    "distance", gimmick.path().size() - 1,
                    "piece", piece.template().id(),
                    "targetPiece", gimmick.targetPiece().template().id(),
                    "targetDepth", gimmick.targetPiece().depth(),
                    "target", format(gimmick.target()),
                    "x", placement.block().getX(),
                    "y", placement.block().getY(),
                    "z", placement.block().getZ()
            ));
        }
        plugin.debugLogger().log("mining", "populate_complete", Map.of(
                "ores", oresPlaced,
                "preparedRemaining", remainingPreparedGimmicks.size(),
                "registeredTriggers", triggers.size(),
                "triggersPlaced", placed,
                "world", world.getName()
        ));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        TriggerGimmick gimmick = triggers.remove(BlockKey.of(event.getBlock()));
        if (gimmick == null) {
            return;
        }
        plugin.debugLogger().log("mining", "trigger_broken", Map.of(
                "gimmick", gimmick.type(),
                "player", event.getPlayer().getName(),
                "targetPiece", gimmick.targetPiece().template().id(),
                "x", event.getBlock().getX(),
                "y", event.getBlock().getY(),
                "z", event.getBlock().getZ()
        ));
        removeLinkedTriggers(BlockKey.of(event.getBlock()), gimmick);
        runChain(event.getBlock().getLocation().toCenterLocation(), gimmick);
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        clearWorld(event.getWorld());
    }

    private SurfacePlacement placeSurfaceBlock(World world, BlockBox bounds, Material material, Material backingMaterial, Random random) {
        return placeSurfaceBlock(world, bounds, material, backingMaterial, random, Set.of());
    }

    private SurfacePlacement placeSurfaceBlock(
            World world,
            BlockBox bounds,
            Material material,
            Material backingMaterial,
            Random random,
            Set<BlockKey> excludedBlocks
    ) {
        List<SurfacePlacement> candidates = surfacePlacements(world, bounds, excludedBlocks);
        if (candidates.isEmpty()) {
            plugin.debugLogger().log("mining", "no_surface", Map.of(
                    "bounds", bounds,
                    "world", world.getName()
            ));
            return null;
        }
        SurfacePlacement placement = candidates.get(random.nextInt(candidates.size()));
        placeSurfacePlacement(placement, material, backingMaterial);
        return placement;
    }

    private SurfacePlacement placeTriggerSurfaceBlock(
            World world,
            BlockBox bounds,
            Material material,
            Material backingMaterial,
            Random random,
            Set<BlockKey> excludedBlocks
    ) {
        List<SurfacePlacement> candidates = triggerSurfacePlacements(world, bounds, excludedBlocks);
        if (candidates.isEmpty()) {
            plugin.debugLogger().log("mining", "no_reachable_trigger_surface", Map.of(
                    "bounds", bounds,
                    "world", world.getName()
            ));
            return null;
        }
        SurfacePlacement placement = candidates.get(random.nextInt(candidates.size()));
        placeSurfacePlacement(placement, material, backingMaterial);
        return placement;
    }

    private void placeSurfacePlacement(SurfacePlacement placement, Material material, Material backingMaterial) {
        placement.block().setType(material, false);
        Block backing = placement.block().getRelative(placement.face().getOppositeFace());
        if (backing.isPassable()) {
            backing.setType(backingMaterial, false);
        }
    }

    private List<SurfacePlacement> surfacePlacements(World world, BlockBox bounds, Set<BlockKey> excludedBlocks) {
        List<SurfacePlacement> placements = new ArrayList<>();
        for (int x = bounds.minX() + 1; x <= bounds.maxX() - 1; x++) {
            for (int y = bounds.minY() + 1; y <= bounds.maxY() - 1; y++) {
                for (int z = bounds.minZ() + 1; z <= bounds.maxZ() - 1; z++) {
                    Block air = world.getBlockAt(x, y, z);
                    if (!air.isPassable()) {
                        continue;
                    }
                    for (BlockFace face : FACES) {
                        Block target = air.getRelative(face);
                        if (excludedBlocks.contains(BlockKey.of(target))) {
                            continue;
                        }
                        if (isGoodSurfaceTarget(target, face.getOppositeFace())) {
                            placements.add(new SurfacePlacement(target, face.getOppositeFace()));
                        }
                    }
                }
            }
        }
        return placements;
    }

    private List<SurfacePlacement> triggerSurfacePlacements(World world, BlockBox bounds, Set<BlockKey> excludedBlocks) {
        return surfacePlacements(world, bounds, excludedBlocks).stream()
                .filter(this::isReachableTriggerSurface)
                .toList();
    }

    private boolean isReachableTriggerSurface(SurfacePlacement placement) {
        Block triggerBlock = placement.block();
        Block air = triggerBlock.getRelative(placement.face());
        Block floor = triggerFloorBelow(air);
        if (floor == null) {
            return false;
        }
        if (placement.face() == BlockFace.UP) {
            return true;
        }
        int heightAboveFloor = triggerBlock.getY() - floor.getY();
        return heightAboveFloor >= 0 && heightAboveFloor <= MAX_TRIGGER_HEIGHT_ABOVE_FLOOR;
    }

    private Block triggerFloorBelow(Block air) {
        Block cursor = air;
        for (int distance = 1; distance <= MAX_TRIGGER_HEIGHT_ABOVE_FLOOR + 2; distance++) {
            cursor = cursor.getRelative(BlockFace.DOWN);
            if (!cursor.isPassable() && cursor.getType().isSolid()) {
                return cursor;
            }
        }
        return null;
    }

    private boolean isGoodSurfaceTarget(Block block, BlockFace airFace) {
        if (!block.getType().isSolid() || !block.getType().isOccluding()) {
            return false;
        }
        if (block.getState() instanceof org.bukkit.block.Container) {
            return false;
        }
        int passableFaces = 0;
        for (BlockFace face : FACES) {
            if (block.getRelative(face).isPassable()) {
                passableFaces++;
            }
        }
        if (passableFaces != 1) {
            return false;
        }
        return block.getRelative(airFace).isPassable()
                && !block.getRelative(airFace.getOppositeFace()).isPassable();
    }

    private Set<BlockKey> reservedPreparedBlocks(World world, List<MiningPreparedGimmick> preparedGimmicks) {
        Set<BlockKey> reserved = new HashSet<>();
        for (MiningPreparedGimmick prepared : preparedGimmicks) {
            for (Location trigger : prepared.fixedTriggerBlocks()) {
                reserved.add(BlockKey.of(trigger.getBlock()));
            }
            for (BlockBox box : prepared.carveBoxes()) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    for (int y = box.minY(); y <= box.maxY(); y++) {
                        for (int z = box.minZ(); z <= box.maxZ(); z++) {
                            reserved.add(new BlockKey(world.getName(), x, y, z));
                        }
                    }
                }
            }
        }
        if (!reserved.isEmpty()) {
            plugin.debugLogger().log("mining", "reserved_prepared_blocks", Map.of(
                    "blocks", reserved.size(),
                    "world", world.getName()
            ));
        }
        return reserved;
    }

    private Optional<TriggerTarget> selectTriggerTarget(World world, List<PlacedPiece> pieces, PlacedPiece triggerPiece, Block triggerBlock, int minDistance) {
        Location start = triggerBlock.getLocation().toCenterLocation();
        return pieces.stream()
                .filter(piece -> piece.depth() > triggerPiece.depth())
                .map(piece -> createTriggerTarget(world, piece, start))
                .flatMap(Optional::stream)
                .filter(target -> target.path().size() - 1 >= minDistance)
                .max(Comparator
                        .comparingInt((TriggerTarget target) -> target.piece().depth())
                        .thenComparingInt(target -> target.path().size()));
    }

    private Optional<TriggerTarget> createTriggerTarget(World world, PlacedPiece piece, Location start) {
        Location target = centerOf(piece.worldBounds(), world);
        List<Location> path = passableChainPath(start, target);
        if (path.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TriggerTarget(piece, target, path));
    }

    private TriggerGimmick createGimmick(TriggerTarget target, Random random, ChestPopulator chestPopulator) {
        MiningGimmickType type = selectDynamicGimmick(random);
        if (type == null) {
            return null;
        }
        return new TriggerGimmick(type, target.location(), target.path(), List.of(), target.piece(), chestPopulator, List.of());
    }

    private TriggerGimmick createDynamicGimmick(TriggerTarget target, Random random, ChestPopulator chestPopulator) {
        return createGimmick(target, random, chestPopulator);
    }

    private Optional<TriggerGimmick> selectPreparedGimmick(
            List<MiningPreparedGimmick> preparedGimmicks,
            Block triggerBlock,
            int minDistance,
            ChestPopulator chestPopulator
    ) {
        Location start = triggerBlock.getLocation().toCenterLocation();
        for (MiningPreparedGimmick prepared : new ArrayList<>(preparedGimmicks)) {
            if (prepared.type() != MiningGimmickType.OPEN_DOOR) {
                continue;
            }
            if (!prepared.fixedTriggerBlocks().isEmpty()) {
                continue;
            }
            if (!prepared.triggerSideSources().isEmpty()) {
                continue;
            }
            List<Location> path = preparedChainPath(start, prepared);
            if (path.size() - 1 < minDistance) {
                continue;
            }
            preparedGimmicks.remove(prepared);
            plugin.debugLogger().log("mining", "prepared_gimmick_selected", Map.of(
                    "distance", path.size() - 1,
                    "type", prepared.type(),
                    "target", format(prepared.target()),
                    "targetPiece", prepared.targetPiece().template().id()
            ));
            return Optional.of(new TriggerGimmick(
                    prepared.type(),
                    prepared.target(),
                    path,
                    prepared.carveBoxes(),
                    prepared.targetPiece(),
                    chestPopulator,
                    List.of()
            ));
        }
        return Optional.empty();
    }

    private int registerRandomPreparedGimmicks(
            World world,
            List<PlacedPiece> pieces,
            List<MiningPreparedGimmick> preparedGimmicks,
            ChestPopulator chestPopulator,
            Set<BlockKey> reservedBlocks,
            Random random,
            MiningSettings settings,
            int maxRegistrations
    ) {
        if (maxRegistrations <= 0) {
            return 0;
        }

        int registered = 0;
        for (MiningPreparedGimmick prepared : new ArrayList<>(preparedGimmicks)) {
            if (registered >= maxRegistrations) {
                break;
            }
            if (prepared.type() != MiningGimmickType.OPEN_DOOR && prepared.type() != MiningGimmickType.TUNNEL_BREAKTHROUGH) {
                continue;
            }
            if (!prepared.fixedTriggerBlocks().isEmpty()) {
                continue;
            }

            if (prepared.triggerSideSources().size() == 2) {
                if (registerSideTriggerPair(world, pieces, prepared, chestPopulator, reservedBlocks, random, settings)) {
                    preparedGimmicks.remove(prepared);
                    registered++;
                } else {
                    resolveUntriggeredConnectionGimmick(prepared);
                    preparedGimmicks.remove(prepared);
                }
                continue;
            }

            Optional<PreparedTriggerPlacement> selected = selectPreparedTriggerPlacement(
                    world,
                    pieces,
                    prepared,
                    reservedBlocks,
                    random,
                    settings.minTriggerGimmickDistance()
            );
            if (selected.isEmpty()) {
                plugin.debugLogger().log("mining", "prepared_gimmick_no_trigger_surface", Map.of(
                        "minDistance", settings.minTriggerGimmickDistance(),
                        "target", format(prepared.target()),
                        "targetPiece", prepared.targetPiece().template().id(),
                        "type", prepared.type()
                ));
                continue;
            }

            PreparedTriggerPlacement triggerPlacement = selected.get();
            placeSurfacePlacement(triggerPlacement.placement(), settings.triggerMaterial(), settings.backingMaterial());
            Block triggerBlock = triggerPlacement.placement().block();
            List<Location> path = completePreparedPath(triggerPlacement.path(), prepared);
            triggers.put(BlockKey.of(triggerBlock), new TriggerGimmick(
                    prepared.type(),
                    prepared.target(),
                    path,
                    prepared.carveBoxes(),
                    prepared.targetPiece(),
                    chestPopulator,
                    List.of()
            ));
            reservedBlocks.add(BlockKey.of(triggerBlock));
            preparedGimmicks.remove(prepared);
            registered++;
            plugin.debugLogger().log("mining", "prepared_trigger_placed", Map.of(
                    "distance", path.size() - 1,
                    "gimmick", prepared.type(),
                    "piece", triggerPlacement.piece().template().id(),
                    "target", format(prepared.target()),
                    "targetPiece", prepared.targetPiece().template().id(),
                    "x", triggerBlock.getX(),
                    "y", triggerBlock.getY(),
                    "z", triggerBlock.getZ()
            ));
        }
        return registered;
    }

    private boolean registerSideTriggerPair(
            World world,
            List<PlacedPiece> pieces,
            MiningPreparedGimmick prepared,
            ChestPopulator chestPopulator,
            Set<BlockKey> reservedBlocks,
            Random random,
            MiningSettings settings
    ) {
        Set<BlockKey> excludedBlocks = new HashSet<>(reservedBlocks);
        List<PreparedTriggerPlacement> placements = new ArrayList<>();
        for (Location sideSource : prepared.triggerSideSources()) {
            ReverseChainSearch search = reverseSideChainSearch(world, sideSource);
            Optional<PreparedTriggerPlacement> selected = selectPreparedTriggerPlacement(
                    world,
                    pieces,
                    prepared,
                    excludedBlocks,
                    random,
                    settings.minTriggerGimmickDistance(),
                    search,
                    true
            );
            if (selected.isEmpty()) {
                plugin.debugLogger().log("mining", "prepared_side_trigger_missing", Map.of(
                        "source", format(sideSource),
                        "target", format(prepared.target()),
                        "targetPiece", prepared.targetPiece().template().id(),
                        "type", prepared.type()
                ));
                return false;
            }
            PreparedTriggerPlacement placement = selected.get();
            placements.add(placement);
            excludedBlocks.add(BlockKey.of(placement.placement().block()));
        }

        List<BlockKey> linkedTriggerBlocks = placements.stream()
                .map(placement -> BlockKey.of(placement.placement().block()))
                .toList();
        for (PreparedTriggerPlacement triggerPlacement : placements) {
            placeSurfacePlacement(triggerPlacement.placement(), settings.triggerMaterial(), settings.backingMaterial());
            Block triggerBlock = triggerPlacement.placement().block();
            List<Location> path = completePreparedPath(triggerPlacement.path(), prepared);
            triggers.put(BlockKey.of(triggerBlock), new TriggerGimmick(
                    prepared.type(),
                    prepared.target(),
                    path,
                    prepared.carveBoxes(),
                    prepared.targetPiece(),
                    chestPopulator,
                    linkedTriggerBlocks
            ));
            reservedBlocks.add(BlockKey.of(triggerBlock));
            plugin.debugLogger().log("mining", "prepared_side_trigger_placed", Map.of(
                    "distance", path.size() - 1,
                    "gimmick", prepared.type(),
                    "piece", triggerPlacement.piece().template().id(),
                    "target", format(prepared.target()),
                    "targetPiece", prepared.targetPiece().template().id(),
                    "x", triggerBlock.getX(),
                    "y", triggerBlock.getY(),
                    "z", triggerBlock.getZ()
            ));
        }
        return true;
    }

    private void resolveUntriggeredConnectionGimmick(MiningPreparedGimmick prepared) {
        if (prepared.type() == MiningGimmickType.TUNNEL_BREAKTHROUGH) {
            carvePreparedTunnel(prepared.target().getWorld(), prepared.carveBoxes(), prepared.target());
        } else if (prepared.type() == MiningGimmickType.OPEN_DOOR) {
            openNearestDoor(prepared.target().getWorld(), prepared.target());
        }
        plugin.debugLogger().log("mining", "prepared_connection_gimmick_disarmed", Map.of(
                "target", format(prepared.target()),
                "targetPiece", prepared.targetPiece().template().id(),
                "type", prepared.type()
        ));
    }

    private List<Location> completePreparedPath(List<Location> path, MiningPreparedGimmick prepared) {
        if (path.isEmpty()) {
            return path;
        }
        List<Location> completed = new ArrayList<>(path);
        if (prepared.type() == MiningGimmickType.TUNNEL_BREAKTHROUGH) {
            List<Location> tunnelPath = tunnelBreakthroughChainPath(path.getLast().getBlock(), prepared.carveBoxes());
            if (!tunnelPath.isEmpty()) {
                completed.addAll(tunnelPath.subList(1, tunnelPath.size()));
            }
        } else if (prepared.type() == MiningGimmickType.OPEN_DOOR
                && !BlockKey.of(path.getLast().getBlock()).equals(BlockKey.of(prepared.target().getBlock()))) {
            List<Location> doorPath = passableChainPath(path.getLast(), prepared.target());
            if (!doorPath.isEmpty()) {
                completed.addAll(doorPath.subList(1, doorPath.size()));
            }
        }
        return completed;
    }

    private Optional<PreparedTriggerPlacement> selectPreparedTriggerPlacement(
            World world,
            List<PlacedPiece> pieces,
            MiningPreparedGimmick prepared,
            Set<BlockKey> reservedBlocks,
            Random random,
            int minDistance
    ) {
        ReverseChainSearch search = prepared.type() == MiningGimmickType.TUNNEL_BREAKTHROUGH
                ? reverseTunnelChainSearch(world, prepared)
                : reversePreparedChainSearch(world, prepared);
        return selectPreparedTriggerPlacement(world, pieces, prepared, reservedBlocks, random, minDistance, search, false);
    }

    private Optional<PreparedTriggerPlacement> selectPreparedTriggerPlacement(
            World world,
            List<PlacedPiece> pieces,
            MiningPreparedGimmick prepared,
            Set<BlockKey> reservedBlocks,
            Random random,
            int minDistance,
            ReverseChainSearch search,
            boolean allowCloserFallback
    ) {
        if (search.previous().isEmpty()) {
            return Optional.empty();
        }

        List<PreparedTriggerPlacement> preferred = new ArrayList<>();
        List<PreparedTriggerPlacement> fallback = new ArrayList<>();
        for (PlacedPiece piece : pieces) {
            for (SurfacePlacement placement : triggerSurfacePlacements(world, piece.worldBounds(), reservedBlocks)) {
                List<Location> path = triggerPathToReverseSearch(world, placement.block(), search.previous());
                if (path.isEmpty()) {
                    continue;
                }
                PreparedTriggerPlacement candidate = new PreparedTriggerPlacement(piece, placement, path);
                if (path.size() - 1 >= minDistance) {
                    preferred.add(candidate);
                } else if (allowCloserFallback) {
                    fallback.add(candidate);
                }
            }
        }
        if (!preferred.isEmpty()) {
            return Optional.of(preferred.get(random.nextInt(preferred.size())));
        }
        if (!fallback.isEmpty()) {
            fallback.sort(Comparator.comparingInt((PreparedTriggerPlacement candidate) -> candidate.path().size()).reversed());
            int longest = fallback.getFirst().path().size();
            List<PreparedTriggerPlacement> furthest = fallback.stream()
                    .filter(candidate -> candidate.path().size() == longest)
                    .toList();
            plugin.debugLogger().log("mining", "prepared_side_trigger_distance_fallback", Map.of(
                    "distance", Math.max(0, longest - 1),
                    "minDistance", minDistance,
                    "target", format(prepared.target()),
                    "type", prepared.type()
            ));
            return Optional.of(furthest.get(random.nextInt(furthest.size())));
        }
        return Optional.empty();
    }

    private List<Location> preparedChainPath(Location start, MiningPreparedGimmick prepared) {
        List<Location> path = passableChainPath(start, prepared.target());
        if (!path.isEmpty() || prepared.type() != MiningGimmickType.TUNNEL_BREAKTHROUGH) {
            return path;
        }
        return tunnelPreparedChainPath(start, prepared);
    }

    private void removeLinkedTriggers(BlockKey activated, TriggerGimmick gimmick) {
        if (gimmick.linkedTriggerBlocks().isEmpty()) {
            return;
        }
        for (BlockKey linked : gimmick.linkedTriggerBlocks()) {
            if (linked.equals(activated)) {
                continue;
            }
            TriggerGimmick removed = triggers.remove(linked);
            if (removed == null) {
                continue;
            }
            World world = plugin.getServer().getWorld(linked.worldName());
            if (world != null) {
                Block block = world.getBlockAt(linked.x(), linked.y(), linked.z());
                block.setType(Material.AIR, false);
                world.spawnParticle(Particle.BLOCK, block.getLocation().toCenterLocation(), 8, 0.2D, 0.2D, 0.2D, 0.0D, plugin.settings().azirouge().mining().triggerMaterial().createBlockData());
                world.playSound(block.getLocation(), Sound.BLOCK_STONE_BREAK, 0.5F, 1.6F);
            }
            plugin.debugLogger().log("mining", "linked_trigger_removed", Map.of(
                    "gimmick", gimmick.type(),
                    "x", linked.x(),
                    "y", linked.y(),
                    "z", linked.z()
            ));
        }
    }

    private int registerFixedPreparedGimmicks(List<MiningPreparedGimmick> preparedGimmicks, ChestPopulator chestPopulator) {
        int registeredGimmicks = 0;
        for (MiningPreparedGimmick prepared : new ArrayList<>(preparedGimmicks)) {
            if (prepared.fixedTriggerBlocks().isEmpty()) {
                continue;
            }
            int registeredTriggers = 0;
            List<BlockKey> linkedTriggerBlocks = prepared.fixedTriggerBlocks().stream()
                    .map(location -> BlockKey.of(location.getBlock()))
                    .toList();
            for (Location triggerLocation : prepared.fixedTriggerBlocks()) {
                Block triggerBlock = triggerLocation.getBlock();
                triggerBlock.setType(plugin.settings().azirouge().mining().triggerMaterial(), false);
                List<Location> path = prepared.type() == MiningGimmickType.TUNNEL_BREAKTHROUGH
                        ? tunnelBreakthroughChainPath(triggerBlock, prepared.carveBoxes())
                        : passableChainPath(triggerBlock.getLocation().toCenterLocation(), prepared.target());
                if (path.isEmpty()) {
                    plugin.debugLogger().log("mining", "fixed_trigger_no_path", Map.of(
                            "target", format(prepared.target()),
                            "type", prepared.type(),
                            "x", triggerBlock.getX(),
                            "y", triggerBlock.getY(),
                            "z", triggerBlock.getZ()
                    ));
                    continue;
                }
                triggers.put(BlockKey.of(triggerBlock), new TriggerGimmick(
                        prepared.type(),
                        prepared.target(),
                        path,
                        prepared.carveBoxes(),
                        prepared.targetPiece(),
                        chestPopulator,
                        linkedTriggerBlocks
                ));
                registeredTriggers++;
                plugin.debugLogger().log("mining", "fixed_trigger_registered", Map.of(
                        "distance", path.size() - 1,
                        "target", format(prepared.target()),
                        "targetPiece", prepared.targetPiece().template().id(),
                        "type", prepared.type(),
                        "x", triggerBlock.getX(),
                        "y", triggerBlock.getY(),
                        "z", triggerBlock.getZ()
                ));
            }
            if (registeredTriggers > 0) {
                registeredGimmicks++;
                preparedGimmicks.remove(prepared);
            }
        }
        return registeredGimmicks;
    }

    private int installRedstoneDoorGimmicks(List<MiningPreparedGimmick> preparedGimmicks) {
        int installed = 0;
        for (MiningPreparedGimmick prepared : new ArrayList<>(preparedGimmicks)) {
            if (prepared.type() != MiningGimmickType.REDSTONE_DOOR) {
                continue;
            }
            RedstoneDoorGimmick gimmick = installRedstoneDoor(prepared.target().getBlock());
            if (gimmick.buttonBlocks().isEmpty()) {
                plugin.debugLogger().log("mining", "redstone_door_install_failed", Map.of(
                        "door", format(prepared.target()),
                        "targetPiece", prepared.targetPiece().template().id()
                ));
                continue;
            }
            preparedGimmicks.remove(prepared);
            installed++;
            plugin.debugLogger().log("mining", "redstone_door_installed", Map.of(
                    "buttons", gimmick.buttonBlocks().stream().map(block -> format(block.getLocation())).toList(),
                    "door", format(gimmick.doorBlock().getLocation()),
                    "lineBlocks", gimmick.signalPath().size(),
                    "targetPiece", prepared.targetPiece().template().id()
            ));
        }
        return installed;
    }

    private RedstoneDoorGimmick installRedstoneDoor(Block doorBlock) {
        BlockFace firstDirection = bestRedstoneDirection(doorBlock);
        List<Location> signalPath = new ArrayList<>();
        List<Block> buttons = new ArrayList<>();
        installRedstoneDoorSide(doorBlock, firstDirection, signalPath).ifPresent(buttons::add);
        installRedstoneDoorSide(doorBlock, firstDirection.getOppositeFace(), signalPath).ifPresent(buttons::add);
        return new RedstoneDoorGimmick(doorBlock, List.copyOf(buttons), List.copyOf(signalPath));
    }

    private Optional<Block> installRedstoneDoorSide(Block doorBlock, BlockFace direction, List<Location> signalPath) {
        int length = 8;
        for (int step = 1; step < length; step++) {
            Block line = doorBlock.getRelative(direction, step);
            ensureRedstoneSupport(line);
            if (step == 4) {
                placeRepeater(line, direction);
            } else {
                line.setType(Material.REDSTONE_WIRE, false);
            }
            signalPath.add(line.getLocation().toCenterLocation());
        }

        Block buttonBlock = doorBlock.getRelative(direction, length);
        ensureRedstoneSupport(buttonBlock);
        BlockData data = Material.STONE_BUTTON.createBlockData();
        if (data instanceof Switch button) {
            button.setFace(Switch.Face.FLOOR);
            button.setFacing(direction.getOppositeFace());
            buttonBlock.setBlockData(button, false);
        } else {
            buttonBlock.setType(Material.STONE_BUTTON, false);
        }
        signalPath.add(buttonBlock.getLocation().toCenterLocation());
        return Optional.of(buttonBlock);
    }

    private BlockFace bestRedstoneDirection(Block doorBlock) {
        BlockFace best = BlockFace.NORTH;
        int bestScore = Integer.MIN_VALUE;
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            int score = 0;
            for (int step = 1; step <= 8; step++) {
                Block block = doorBlock.getRelative(face, step);
                if (block.isPassable()) {
                    score += 2;
                }
                if (!block.getRelative(BlockFace.DOWN).isPassable()) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = face;
            }
        }
        return best;
    }

    private void ensureRedstoneSupport(Block line) {
        Block support = line.getRelative(BlockFace.DOWN);
        if (support.isPassable()) {
            support.setType(Material.STONE, false);
        }
        if (!line.isPassable()) {
            line.setType(Material.AIR, false);
        }
    }

    private void placeRepeater(Block block, BlockFace direction) {
        ensureRedstoneSupport(block);
        BlockData data = Material.REPEATER.createBlockData();
        if (data instanceof Directional directional) {
            directional.setFacing(direction);
        }
        block.setBlockData(data, false);
    }

    private List<Location> tunnelBreakthroughChainPath(Block triggerBlock, List<BlockBox> carveBoxes) {
        World world = triggerBlock.getWorld();
        Set<BlockKey> blocks = tunnelCarveBlockKeys(world, carveBoxes);

        BlockKey start = BlockKey.of(triggerBlock);
        if (!blocks.contains(start)) {
            blocks.add(start);
        }
        List<BlockKey> path = new ArrayList<>();
        Set<BlockKey> visited = new HashSet<>();
        Queue<BlockKey> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            BlockKey current = queue.poll();
            path.add(current);
            for (BlockFace face : FACES) {
                BlockKey next = current.relative(face);
                if (!blocks.contains(next) || !visited.add(next)) {
                    continue;
                }
                queue.add(next);
            }
        }
        List<Location> locations = path.stream()
                .map(key -> new Location(world, key.x() + 0.5D, key.y() + 0.5D, key.z() + 0.5D))
                .toList();
        plugin.debugLogger().log("mining", "tunnel_chain_prepared", Map.of(
                "blocks", blocks.size(),
                "reachableBlocks", locations.size(),
                "start", format(triggerBlock.getLocation())
        ));
        return locations;
    }

    private Set<BlockKey> tunnelCarveBlockKeys(World world, List<BlockBox> carveBoxes) {
        Set<BlockKey> blocks = new HashSet<>();
        for (BlockBox box : carveBoxes) {
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        blocks.add(new BlockKey(world.getName(), x, y, z));
                    }
                }
            }
        }
        return blocks;
    }

    private List<Location> tunnelPreparedChainPath(Location start, MiningPreparedGimmick prepared) {
        List<Location> best = List.of();
        for (Location target : tunnelAirTouchingTargets(start.getWorld(), prepared.carveBoxes())) {
            List<Location> path = passableChainPath(start, target);
            if (path.isEmpty()) {
                continue;
            }
            if (best.isEmpty() || path.size() > best.size()) {
                best = path;
            }
        }
        return best;
    }

    private ReverseChainSearch reverseTunnelChainSearch(World world, MiningPreparedGimmick prepared) {
        Queue<BlockKey> queue = new ArrayDeque<>();
        Set<BlockKey> sources = new HashSet<>();
        Map<BlockKey, BlockKey> previous = new HashMap<>();
        int maxVisited = 4096;

        for (Location target : tunnelAirTouchingTargets(world, prepared.carveBoxes())) {
            BlockKey key = BlockKey.of(target.getBlock());
            if (sources.add(key)) {
                queue.add(key);
                previous.put(key, null);
            }
        }

        while (!queue.isEmpty() && previous.size() <= maxVisited) {
            BlockKey current = queue.poll();
            for (BlockFace face : FACES) {
                BlockKey next = current.relative(face);
                if (previous.containsKey(next)) {
                    continue;
                }
                Block block = world.getBlockAt(next.x(), next.y(), next.z());
                if (!isChainPassable(block)) {
                    continue;
                }
                previous.put(next, current);
                queue.add(next);
            }
        }

        plugin.debugLogger().log("mining", "tunnel_reverse_chain_prepared", Map.of(
                "reachableBlocks", previous.size(),
                "sources", sources.size(),
                "target", format(prepared.target()),
                "targetPiece", prepared.targetPiece().template().id()
        ));
        return new ReverseChainSearch(previous);
    }

    private ReverseChainSearch reversePreparedChainSearch(World world, MiningPreparedGimmick prepared) {
        ReverseChainSearch search = reverseSideChainSearch(world, prepared.target());
        plugin.debugLogger().log("mining", "prepared_reverse_chain_prepared", Map.of(
                "reachableBlocks", search.previous().size(),
                "target", format(prepared.target()),
                "targetPiece", prepared.targetPiece().template().id(),
                "type", prepared.type()
        ));
        return search;
    }

    private ReverseChainSearch reverseSideChainSearch(World world, Location sourceLocation) {
        BlockKey source = BlockKey.of(sourceLocation.getBlock());
        Queue<BlockKey> queue = new ArrayDeque<>();
        Map<BlockKey, BlockKey> previous = new HashMap<>();
        int maxVisited = 4096;

        queue.add(source);
        previous.put(source, null);
        while (!queue.isEmpty() && previous.size() <= maxVisited) {
            BlockKey current = queue.poll();
            for (BlockFace face : FACES) {
                BlockKey next = current.relative(face);
                if (previous.containsKey(next)) {
                    continue;
                }
                Block block = world.getBlockAt(next.x(), next.y(), next.z());
                if (!isChainPassable(block)) {
                    continue;
                }
                previous.put(next, current);
                queue.add(next);
            }
        }
        return new ReverseChainSearch(previous);
    }

    private List<Location> triggerPathToReverseSearch(
            World world,
            Block triggerBlock,
            Map<BlockKey, BlockKey> previous
    ) {
        BlockKey triggerKey = BlockKey.of(triggerBlock);
        List<Location> best = List.of();
        for (BlockFace face : FACES) {
            BlockKey neighbor = triggerKey.relative(face);
            if (!previous.containsKey(neighbor)) {
                continue;
            }
            List<Location> path = rebuildPath(world, previous, neighbor);
            Collections.reverse(path);
            path.addFirst(triggerBlock.getLocation().toCenterLocation());
            if (best.isEmpty() || path.size() > best.size()) {
                best = path;
            }
        }
        return best;
    }

    private List<Location> tunnelAirTouchingTargets(World world, List<BlockBox> carveBoxes) {
        List<Location> targets = new ArrayList<>();
        for (BlockBox box : carveBoxes) {
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        Block block = world.getBlockAt(x, y, z);
                        if (hasPassableNeighborOutsideBox(block, box)) {
                            targets.add(block.getLocation().toCenterLocation());
                        }
                    }
                }
            }
        }
        return targets;
    }

    private boolean hasPassableNeighborOutsideBox(Block block, BlockBox box) {
        for (BlockFace face : FACES) {
            Block neighbor = block.getRelative(face);
            if (!box.contains(neighbor.getX(), neighbor.getY(), neighbor.getZ()) && neighbor.isPassable()) {
                return true;
            }
        }
        return false;
    }

    private MiningGimmickType selectDynamicGimmick(Random random) {
        List<MiningGimmickSettings> gimmicks = plugin.settings().azirouge().mining().gimmicks().stream()
                .filter(gimmick -> gimmick.type() != MiningGimmickType.OPEN_DOOR
                        && gimmick.type() != MiningGimmickType.REDSTONE_DOOR
                        && gimmick.type() != MiningGimmickType.TUNNEL_BREAKTHROUGH)
                .filter(gimmick -> gimmick.weight() > 0)
                .toList();
        if (gimmicks.isEmpty()) {
            return null;
        }
        int total = gimmicks.stream().mapToInt(MiningGimmickSettings::weight).sum();
        int cursor = random.nextInt(Math.max(1, total));
        for (MiningGimmickSettings gimmick : gimmicks) {
            cursor -= gimmick.weight();
            if (cursor < 0) {
                return gimmick.type();
            }
        }
        return null;
    }

    private MiningOreSettings selectOre(List<MiningOreSettings> ores, int depth, Random random) {
        int total = 0;
        for (MiningOreSettings ore : ores) {
            if (ore.matchesDepth(depth)) {
                total += ore.weight();
            }
        }
        if (total <= 0) {
            return null;
        }
        int cursor = random.nextInt(total);
        for (MiningOreSettings ore : ores) {
            if (!ore.matchesDepth(depth)) {
                continue;
            }
            cursor -= ore.weight();
            if (cursor < 0) {
                return ore;
            }
        }
        return null;
    }

    private void runChain(Location start, TriggerGimmick gimmick) {
        List<Location> path = gimmick.path();
        int steps = Math.max(0, path.size() - 1);
        plugin.debugLogger().log("mining", "chain_begin", Map.of(
                "gimmick", gimmick.type(),
                "start", format(start),
                "steps", steps,
                "target", format(gimmick.target())
        ));
        List<Integer> delaySteps = chainDelaySteps(gimmick);
        Set<Integer> soundDelaySteps = new HashSet<>();
        int maxDelayStep = 0;
        for (int index = 0; index < path.size(); index++) {
            int step = index;
            Location point = path.get(step);
            int delayStep = delaySteps.get(step);
            boolean playStepSounds = soundDelaySteps.add(delayStep);
            maxDelayStep = Math.max(maxDelayStep, delayStep);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                point.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, point, 8, 0.12D, 0.12D, 0.12D, 0.0D);
                point.getWorld().spawnParticle(Particle.CLOUD, point, 3, 0.12D, 0.12D, 0.12D, 0.0D);
                if (playStepSounds) {
                    point.getWorld().playSound(point, Sound.ENTITY_GENERIC_SMALL_FALL, 0.1F, 1.0F);
                    point.getWorld().playSound(point, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.1F, 1.0F);
                    point.getWorld().playSound(point, Sound.BLOCK_WOOD_BREAK, 0.75F, 0.2F + delayStep * 0.04F);
                }
                if (gimmick.type() == MiningGimmickType.TUNNEL_BREAKTHROUGH) {
                    Block block = point.getBlock();
                    if (!block.isPassable() && block.getType() != Material.SPRUCE_DOOR) {
                        block.setType(Material.AIR, false);
                        point.getWorld().spawnParticle(Particle.BLOCK, point, 8, 0.2D, 0.2D, 0.2D, 0.0D, Material.COBBLESTONE.createBlockData());
                        if (playStepSounds) {
                            point.getWorld().playSound(point, Sound.ENTITY_GENERIC_SMALL_FALL, 0.1F, 1.0F);
                            point.getWorld().playSound(point, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.1F, 1.0F);
                            point.getWorld().playSound(point, Sound.BLOCK_WOOD_BREAK, 0.65F, 0.8F);
                        }
                    }
                }
            }, (long) delayStep * plugin.settings().azirouge().mining().chainStepTicks());
        }
        plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> activateGimmick(gimmick),
                (long) maxDelayStep * plugin.settings().azirouge().mining().chainStepTicks()
        );
    }

    private List<Integer> chainDelaySteps(TriggerGimmick gimmick) {
        List<Location> path = gimmick.path();
        List<Integer> sequential = new ArrayList<>(path.size());
        for (int index = 0; index < path.size(); index++) {
            sequential.add(index);
        }
        if (path.isEmpty()
                || gimmick.type() != MiningGimmickType.TUNNEL_BREAKTHROUGH
                || gimmick.carveBoxes().isEmpty()) {
            return sequential;
        }

        Set<BlockKey> carveBlocks = tunnelCarveBlockKeys(path.getFirst().getWorld(), gimmick.carveBoxes());
        int firstCarveStep = -1;
        for (int index = 0; index < path.size(); index++) {
            if (carveBlocks.contains(BlockKey.of(path.get(index).getBlock()))) {
                firstCarveStep = index;
                break;
            }
        }
        if (firstCarveStep < 0) {
            return sequential;
        }

        Map<BlockKey, Integer> spreadDistances = tunnelSpreadDistances(
                BlockKey.of(path.get(firstCarveStep).getBlock()),
                carveBlocks
        );
        List<Integer> delaySteps = new ArrayList<>(sequential);
        for (int index = firstCarveStep; index < path.size(); index++) {
            Integer spreadDistance = spreadDistances.get(BlockKey.of(path.get(index).getBlock()));
            if (spreadDistance != null) {
                delaySteps.set(index, firstCarveStep + spreadDistance);
            }
        }
        return delaySteps;
    }

    private Map<BlockKey, Integer> tunnelSpreadDistances(BlockKey start, Set<BlockKey> carveBlocks) {
        Map<BlockKey, Integer> distances = new HashMap<>();
        if (!carveBlocks.contains(start)) {
            return distances;
        }

        Queue<BlockKey> queue = new ArrayDeque<>();
        queue.add(start);
        distances.put(start, 0);
        while (!queue.isEmpty()) {
            BlockKey current = queue.poll();
            int nextDistance = distances.get(current) + 1;
            for (BlockFace face : FACES) {
                BlockKey next = current.relative(face);
                if (!carveBlocks.contains(next) || distances.containsKey(next)) {
                    continue;
                }
                distances.put(next, nextDistance);
                queue.add(next);
            }
        }
        return distances;
    }

    private void activateGimmick(TriggerGimmick gimmick) {
        World world = gimmick.target().getWorld();
        plugin.debugLogger().log("mining", "gimmick_activate", Map.of(
                "gimmick", gimmick.type(),
                "target", format(gimmick.target()),
                "targetPiece", gimmick.targetPiece().template().id(),
                "world", world.getName()
        ));
        switch (gimmick.type()) {
            case TUNNEL_BREAKTHROUGH -> carvePreparedTunnel(gimmick);
            case OPEN_DOOR -> openNearestDoor(world, gimmick.target());
            case REDSTONE_DOOR -> openNearestDoor(world, gimmick.target());
            case SUMMON_CHEST -> {
                boolean spawned = gimmick.chestPopulator().populateGuaranteedRoom(world, gimmick.targetPiece(), new Random());
                plugin.debugLogger().log("mining", "gimmick_chest_result", Map.of(
                        "spawned", spawned,
                        "targetPiece", gimmick.targetPiece().template().id()
                ));
            }
        }
        world.playSound(gimmick.target(), Sound.ENTITY_GENERIC_EXPLODE, 0.6F, 1.4F);
        world.spawnParticle(Particle.CLOUD, gimmick.target(), 25, 1.0D, 0.7D, 1.0D, 0.03D);
    }

    private void carveSmallTunnel(Location center) {
        World world = center.getWorld();
        int blocks = 0;
        for (int z = -2; z <= 2; z++) {
            for (int y = 0; y <= 2; y++) {
                world.getBlockAt(center.getBlockX(), center.getBlockY() + y, center.getBlockZ() + z).setType(Material.AIR, false);
                blocks++;
            }
        }
        plugin.debugLogger().log("mining", "gimmick_tunnel_result", Map.of(
                "blocks", blocks,
                "center", format(center)
        ));
    }

    private void carvePreparedTunnel(TriggerGimmick gimmick) {
        if (gimmick.carveBoxes().isEmpty()) {
            carveSmallTunnel(gimmick.target());
            return;
        }
        carvePreparedTunnel(gimmick.target().getWorld(), gimmick.carveBoxes(), gimmick.target());
    }

    private void carvePreparedTunnel(World world, List<BlockBox> carveBoxes, Location target) {
        int blocks = 0;
        for (BlockBox box : carveBoxes) {
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        world.getBlockAt(x, y, z).setType(Material.AIR, false);
                        blocks++;
                    }
                }
            }
        }
        plugin.debugLogger().log("mining", "gimmick_tunnel_result", Map.of(
                "blocks", blocks,
                "prepared", true,
                "target", format(target)
        ));
    }

    private void openNearestDoor(World world, Location target) {
        for (int radius = 1; radius <= 16; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int y = -4; y <= 4; y++) {
                    for (int z = -radius; z <= radius; z++) {
                        Block block = world.getBlockAt(target.getBlockX() + x, target.getBlockY() + y, target.getBlockZ() + z);
                        if (block.getBlockData() instanceof Openable openable) {
                            openable.setOpen(true);
                            block.setBlockData(openable, true);
                            block.getWorld().playSound(block.getLocation(), Sound.BLOCK_IRON_DOOR_OPEN, 1, 1);
                            plugin.debugLogger().log("mining", "gimmick_door_result", Map.of(
                                    "found", true,
                                    "x", block.getX(),
                                    "y", block.getY(),
                                    "z", block.getZ()
                            ));
                            return;
                        }
                    }
                }
            }
        }
        plugin.debugLogger().log("mining", "gimmick_door_result", Map.of(
                "found", false,
                "target", format(target)
        ));
    }

    private Location centerOf(BlockBox box, World world) {
        return new Location(
                world,
                box.minX() + (box.sizeX() / 2.0D),
                box.minY() + 1.0D,
                box.minZ() + (box.sizeZ() / 2.0D)
        );
    }

    private List<Location> passableChainPath(Location start, Location end) {
        World world = start.getWorld();
        BlockKey startKey = BlockKey.of(start.getBlock());
        BlockKey endKey = BlockKey.of(end.getBlock());
        Queue<BlockKey> queue = new ArrayDeque<>();
        Set<BlockKey> visited = new HashSet<>();
        Map<BlockKey, BlockKey> previous = new HashMap<>();
        int maxVisited = 4096;

        queue.add(startKey);
        visited.add(startKey);
        while (!queue.isEmpty() && visited.size() <= maxVisited) {
            BlockKey current = queue.poll();
            if (current.equals(endKey)) {
                return rebuildPath(world, previous, current);
            }
            for (BlockFace face : FACES) {
                BlockKey next = current.relative(face);
                if (visited.contains(next)) {
                    continue;
                }
                Block block = world.getBlockAt(next.x(), next.y(), next.z());
                if (!next.equals(endKey) && !isChainPassable(block)) {
                    continue;
                }
                visited.add(next);
                previous.put(next, current);
                queue.add(next);
            }
        }
        return List.of();
    }

    private boolean isChainPassable(Block block) {
        return block.isPassable() || isWoodenDoor(block);
    }

    private boolean isWoodenDoor(Block block) {
        String name = block.getType().name();
        return name.endsWith("_DOOR") && block.getBlockData() instanceof Openable && block.getType() != Material.IRON_DOOR;
    }

    private List<Location> rebuildPath(World world, Map<BlockKey, BlockKey> previous, BlockKey end) {
        List<Location> path = new ArrayList<>();
        BlockKey cursor = end;
        while (cursor != null) {
            path.add(new Location(world, cursor.x() + 0.5D, cursor.y() + 0.5D, cursor.z() + 0.5D));
            cursor = previous.get(cursor);
        }
        Collections.reverse(path);
        return path;
    }

    private String format(Location location) {
        return location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }

    private record SurfacePlacement(Block block, BlockFace face) {
    }

    private record TriggerTarget(PlacedPiece piece, Location location, List<Location> path) {
    }

    private record PreparedTriggerPlacement(PlacedPiece piece, SurfacePlacement placement, List<Location> path) {
    }

    private record ReverseChainSearch(Map<BlockKey, BlockKey> previous) {
    }

    private record TriggerGimmick(
            MiningGimmickType type,
            Location target,
            List<Location> path,
            List<BlockBox> carveBoxes,
            PlacedPiece targetPiece,
            ChestPopulator chestPopulator,
            List<BlockKey> linkedTriggerBlocks
    ) {
    }

    private record RedstoneDoorGimmick(Block doorBlock, List<Block> buttonBlocks, List<Location> signalPath) {
    }

    private record BlockKey(String worldName, int x, int y, int z) {
        private static BlockKey of(Block block) {
            return new BlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        }

        private BlockKey relative(BlockFace face) {
            return new BlockKey(worldName, x + face.getModX(), y + face.getModY(), z + face.getModZ());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof BlockKey key
                    && x == key.x
                    && y == key.y
                    && z == key.z
                    && Objects.equals(worldName, key.worldName);
        }

        @Override
        public int hashCode() {
            int result = worldName.hashCode();
            result = 31 * result + x;
            result = 31 * result + y;
            result = 31 * result + z;
            return result;
        }
    }
}
