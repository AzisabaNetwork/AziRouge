package net.azisaba.aziRouge.dungeon;

import net.azisaba.aziRouge.config.DoorSettings;
import net.azisaba.aziRouge.config.GenerationSettings;
import net.azisaba.aziRouge.config.PluginSettings;
import net.azisaba.aziRouge.debug.DebugLogger;
import net.azisaba.aziRouge.math.BlockBox;
import net.azisaba.aziRouge.math.Direction;
import net.azisaba.aziRouge.math.IntVector3;
import net.azisaba.aziRouge.math.Rotation;
import net.azisaba.aziRouge.schematic.SchematicAdapter;
import net.azisaba.aziRouge.schematic.SchematicPlacementException;
import net.azisaba.aziRouge.template.EntranceTemplate;
import net.azisaba.aziRouge.template.LoadedTemplates;
import net.azisaba.aziRouge.template.PieceTemplate;
import net.azisaba.aziRouge.template.TemplateLoadException;
import net.azisaba.aziRouge.template.TemplateManager;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class DungeonGenerator {
    private final JavaPlugin plugin;
    private final DebugLogger debugLogger;
    private final TemplateManager templateManager;
    private final SchematicAdapter schematicAdapter;
    private final EnemyPlacementService enemyPlacementService;

    public DungeonGenerator(
            JavaPlugin plugin,
            DebugLogger debugLogger,
            TemplateManager templateManager,
            SchematicAdapter schematicAdapter,
            EnemyPlacementService enemyPlacementService
    ) {
        this.plugin = plugin;
        this.debugLogger = debugLogger;
        this.templateManager = templateManager;
        this.schematicAdapter = schematicAdapter;
        this.enemyPlacementService = enemyPlacementService;
    }

    public DungeonGenerationResult generate(GenerationExecutionRequest request, PluginSettings settings)
            throws TemplateLoadException, SchematicPlacementException {
        if (!schematicAdapter.isAvailable()) {
            throw new SchematicPlacementException("Schematic adapter is unavailable: " + schematicAdapter.describeAvailability());
        }

        LoadedTemplates templates = templateManager.load(request.templatePatterns());
        PieceTemplate startTemplate = templates.pieces().get(request.startPieceId());
        if (startTemplate == null) {
            throw new TemplateLoadException("Unknown start piece: " + request.startPieceId());
        }

        GenerationSettings generation = settings.generation();
        Random random = new Random(request.seed());
        int targetPieceCount = generation.resolveTargetPieceCount(random);
        int maxDepth = request.maxDepthOverride() == null ? generation.maxDepth() : Math.max(1, request.maxDepthOverride());
        List<PlacedPiece> pieces = new ArrayList<>();
        List<PieceConnection> connections = new ArrayList<>();
        List<Frontier> frontiers = new ArrayList<>();
        Set<String> usedEntrances = new HashSet<>();

        PlacedPiece startPiece = createPlacedPiece(startTemplate, Rotation.NONE, request.origin(), 0, 0);
        pieces.add(startPiece);
        activateEntrances(startPiece, usedEntrances, frontiers, random, maxDepth, generation);

        while (pieces.size() < targetPieceCount && !frontiers.isEmpty()) {
            Frontier frontier = frontiers.remove(random.nextInt(frontiers.size()));
            if (usedEntrances.contains(frontier.entrance.key()) || frontier.entrance.piece().depth() >= maxDepth) {
                continue;
            }

            PlacementAttempt attempt = tryPlace(frontier, pieces, templates, random, generation, maxDepth);
            if (attempt == null) {
                debugLogger.log("generation", "frontier_exhausted", Map.of(
                        "depth", frontier.entrance.piece().depth(),
                        "entrance", frontier.entrance.template().id(),
                        "piece", frontier.entrance.piece().template().id()
                ));
                continue;
            }

            usedEntrances.add(frontier.entrance.key());
            usedEntrances.add(attempt.childEntrance.key());
            pieces.add(attempt.piece);
            connections.add(new PieceConnection(frontier.entrance, attempt.childEntrance));
            activateEntrances(attempt.piece, usedEntrances, frontiers, random, maxDepth, generation);
        }

        for (PlacedPiece piece : pieces) {
            schematicAdapter.paste(request.world(), piece);
        }

        for (PieceConnection connection : connections) {
            carveConnection(request.world(), connection);
            maybePlaceDoor(request.world(), connection, random, settings.door());
        }

        List<EnemySpawnReservation> reservations = enemyPlacementService.plan(pieces, settings.enemies());
        debugLogger.log("generation", "complete", Map.of(
                "connections", connections.size(),
                "maxDepth", maxDepth,
                "pieces", pieces.size(),
                "seed", request.seed(),
                "target", targetPieceCount,
                "world", request.world().getName()
        ));
        return new DungeonGenerationResult(
                request.seed(),
                targetPieceCount,
                pieces.size(),
                connections.size(),
                reservations
        );
    }

    private PlacementAttempt tryPlace(
            Frontier frontier,
            List<PlacedPiece> pieces,
            LoadedTemplates templates,
            Random random,
            GenerationSettings settings,
            int maxDepth
    ) {
        List<PieceTemplate> weightedPieces = weightedShuffle(new ArrayList<>(templates.pieces().values()), random);
        for (PieceTemplate candidateTemplate : weightedPieces) {
            List<EntranceTemplate> candidateEntrances = new ArrayList<>(candidateTemplate.entrances());
            Collections.shuffle(candidateEntrances, random);
            for (EntranceTemplate candidateEntrance : candidateEntrances) {
                Rotation rotation = Rotation.fromFacing(candidateEntrance.facing(), frontier.entrance.worldFacing().opposite());
                IntVector3 origin = computeChildOrigin(frontier.entrance, candidateEntrance, rotation);
                PlacedPiece placedPiece = createPlacedPiece(candidateTemplate, rotation, origin, frontier.entrance.piece().depth() + 1, pieces.size());
                if (placedPiece.depth() > maxDepth) {
                    continue;
                }
                if (intersectsExisting(placedPiece.worldBounds(), pieces)) {
                    debugLogger.log("generation", "candidate_rejected", Map.of(
                            "candidate", candidateTemplate.id(),
                            "depth", placedPiece.depth(),
                            "origin", format(origin),
                            "reason", "collision",
                            "rotation", rotation.degrees()
                    ));
                    continue;
                }
                PlacedEntrance placedEntrance = findEntrance(placedPiece, candidateEntrance.id());
                if (!frontier.entrance.canConnectTo(placedEntrance)) {
                    debugLogger.log("generation", "candidate_rejected", Map.of(
                            "candidate", candidateTemplate.id(),
                            "childEntrance", candidateEntrance.id(),
                            "origin", format(origin),
                            "reason", "adjacency",
                            "rotation", rotation.degrees()
                    ));
                    continue;
                }
                debugLogger.log("generation", "candidate_accepted", Map.of(
                        "candidate", candidateTemplate.id(),
                        "childEntrance", candidateEntrance.id(),
                        "depth", placedPiece.depth(),
                        "origin", format(origin),
                        "rotation", rotation.degrees()
                ));
                return new PlacementAttempt(placedPiece, placedEntrance);
            }
        }
        return null;
    }

    private void activateEntrances(
            PlacedPiece piece,
            Set<String> usedEntrances,
            List<Frontier> frontiers,
            Random random,
            int maxDepth,
            GenerationSettings settings
    ) {
        if (piece.depth() >= maxDepth) {
            return;
        }

        List<PlacedEntrance> available = new ArrayList<>();
        for (PlacedEntrance entrance : piece.entrances()) {
            if (!usedEntrances.contains(entrance.key())) {
                available.add(entrance);
            }
        }
        if (available.isEmpty()) {
            return;
        }

        Collections.shuffle(available, random);
        Set<PlacedEntrance> selected = new LinkedHashSet<>();
        selected.add(available.get(0));
        for (int index = 1; index < available.size(); index++) {
            if (random.nextDouble() <= settings.branchChance()) {
                selected.add(available.get(index));
            }
        }
        for (PlacedEntrance entrance : selected) {
            frontiers.add(new Frontier(entrance));
        }
    }

    private PlacedPiece createPlacedPiece(PieceTemplate template, Rotation rotation, IntVector3 origin, int depth, int index) {
        BlockBox worldBounds = template.bounds().rotate(rotation).offset(origin);
        PlacedPiece base = new PlacedPiece(index, template, rotation, origin, depth, worldBounds, List.of());
        List<PlacedEntrance> entrances = new ArrayList<>(template.entrances().size());
        for (EntranceTemplate entranceTemplate : template.entrances()) {
            BlockBox plane = entranceTemplate.planeBox().rotate(rotation).offset(origin);
            Direction worldFacing = rotation.rotate(entranceTemplate.facing());
            BlockBox opening = plane.extend(worldFacing.opposite(), entranceTemplate.width() - 1);
            entrances.add(new PlacedEntrance(base, entranceTemplate, worldFacing, plane, opening));
        }

        PlacedPiece full = new PlacedPiece(index, template, rotation, origin, depth, worldBounds, List.of());
        List<PlacedEntrance> rebound = new ArrayList<>(entrances.size());
        for (PlacedEntrance entrance : entrances) {
            rebound.add(new PlacedEntrance(full, entrance.template(), entrance.worldFacing(), entrance.planeBox(), entrance.openingBox()));
        }
        return new PlacedPiece(index, template, rotation, origin, depth, worldBounds, List.copyOf(rebound));
    }

    private IntVector3 computeChildOrigin(PlacedEntrance parentEntrance, EntranceTemplate childEntrance, Rotation rotation) {
        BlockBox childPlane = childEntrance.planeBox().rotate(rotation);
        BlockBox parentPlane = parentEntrance.planeBox();

        int x;
        int y = parentPlane.minY() + centeredOffset(parentPlane.sizeY(), childPlane.sizeY()) - childPlane.minY();
        int z;

        switch (parentEntrance.worldFacing()) {
            case NORTH -> {
                x = parentPlane.minX() + centeredOffset(parentPlane.sizeX(), childPlane.sizeX()) - childPlane.minX();
                z = parentPlane.minZ() - childPlane.minZ() - 1;
            }
            case SOUTH -> {
                x = parentPlane.minX() + centeredOffset(parentPlane.sizeX(), childPlane.sizeX()) - childPlane.minX();
                z = parentPlane.maxZ() + 1 - childPlane.minZ();
            }
            case EAST -> {
                x = parentPlane.maxX() + 1 - childPlane.minX();
                z = parentPlane.minZ() + centeredOffset(parentPlane.sizeZ(), childPlane.sizeZ()) - childPlane.minZ();
            }
            case WEST -> {
                x = parentPlane.minX() - childPlane.minX() - 1;
                z = parentPlane.minZ() + centeredOffset(parentPlane.sizeZ(), childPlane.sizeZ()) - childPlane.minZ();
            }
            default -> throw new IllegalStateException("Unsupported direction");
        }
        return new IntVector3(x, y, z);
    }

    private int centeredOffset(int parentSize, int childSize) {
        return Math.floorDiv(parentSize - childSize, 2);
    }

    private boolean intersectsExisting(BlockBox candidate, List<PlacedPiece> pieces) {
        for (PlacedPiece piece : pieces) {
            if (candidate.intersects(piece.worldBounds())) {
                return true;
            }
        }
        return false;
    }

    private PlacedEntrance findEntrance(PlacedPiece piece, String entranceId) {
        for (PlacedEntrance entrance : piece.entrances()) {
            if (entrance.template().id().equals(entranceId)) {
                return entrance;
            }
        }
        throw new IllegalArgumentException("Entrance not found: " + entranceId + " on piece " + piece.template().id());
    }

    private List<PieceTemplate> weightedShuffle(List<PieceTemplate> templates, Random random) {
        List<PieceTemplate> remaining = new ArrayList<>(templates);
        List<PieceTemplate> ordered = new ArrayList<>(templates.size());
        while (!remaining.isEmpty()) {
            double totalWeight = 0.0D;
            for (PieceTemplate template : remaining) {
                totalWeight += template.weight();
            }
            double cursor = random.nextDouble() * totalWeight;
            PieceTemplate selected = remaining.get(0);
            for (PieceTemplate template : remaining) {
                cursor -= template.weight();
                if (cursor <= 0.0D) {
                    selected = template;
                    break;
                }
            }
            ordered.add(selected);
            remaining.remove(selected);
        }
        return ordered;
    }

    private void carveConnection(World world, PieceConnection connection) {
        if (!connection.parentEntrance().canConnectTo(connection.childEntrance())) {
            debugLogger.log("carve", "skipped", Map.of(
                    "child", connection.childEntrance().key(),
                    "parent", connection.parentEntrance().key(),
                    "reason", "adjacency_check_failed"
            ));
            return;
        }
        carveBox(world, connection.parentEntrance().openingBox());
        carveBox(world, connection.childEntrance().openingBox());
        debugLogger.log("carve", "applied", Map.of(
                "childBox", format(connection.childEntrance().openingBox()),
                "parentBox", format(connection.parentEntrance().openingBox())
        ));
    }

    private void carveBox(World world, BlockBox box) {
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }
    }

    private void maybePlaceDoor(World world, PieceConnection connection, Random random, DoorSettings settings) {
        if (!settings.enabled() || random.nextDouble() > settings.chance()) {
            return;
        }

        PlacedEntrance target = null;
        if (connection.childEntrance().isDoorCompatible()) {
            target = connection.childEntrance();
        } else if (connection.parentEntrance().isDoorCompatible()) {
            target = connection.parentEntrance();
        }
        if (target == null) {
            return;
        }

        BlockBox plane = target.planeBox();
        int x = plane.minX();
        int y = plane.minY();
        int z = plane.minZ();

        BlockData lowerData = settings.material().createBlockData();
        if (!(lowerData instanceof Door lowerDoor)) {
            plugin.getLogger().warning("Configured door material is not a valid door block: " + settings.material());
            return;
        }
        Door upperDoor = (Door) settings.material().createBlockData();
        BlockFace facing = BlockFace.valueOf(target.worldFacing().name());
        lowerDoor.setFacing(facing);
        lowerDoor.setHalf(Bisected.Half.BOTTOM);
        lowerDoor.setHinge(random.nextBoolean() ? Door.Hinge.LEFT : Door.Hinge.RIGHT);
        upperDoor.setFacing(facing);
        upperDoor.setHalf(Bisected.Half.TOP);
        upperDoor.setHinge(lowerDoor.getHinge());

        world.getBlockAt(x, y, z).setBlockData(lowerDoor, false);
        world.getBlockAt(x, y + 1, z).setBlockData(upperDoor, false);
        debugLogger.log("door", "placed", Map.of(
                "material", settings.material(),
                "piece", target.piece().template().id(),
                "position", x + "," + y + "," + z
        ));
    }

    private String format(IntVector3 vector) {
        return vector.x() + "," + vector.y() + "," + vector.z();
    }

    private String format(BlockBox box) {
        return format(box.min()) + "->" + format(box.max());
    }

    private record Frontier(PlacedEntrance entrance) {
    }

    private record PlacementAttempt(PlacedPiece piece, PlacedEntrance childEntrance) {
    }
}
