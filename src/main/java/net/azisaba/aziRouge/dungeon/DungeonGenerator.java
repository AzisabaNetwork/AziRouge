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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
    private final ChestPopulator chestPopulator;
    private final TreasurePopulator treasurePopulator;
    private final TrapPopulator trapPopulator;

    public DungeonGenerator(
            JavaPlugin plugin,
            DebugLogger debugLogger,
            TemplateManager templateManager,
            SchematicAdapter schematicAdapter,
            EnemyPlacementService enemyPlacementService,
            ChestPopulator chestPopulator,
            TreasurePopulator treasurePopulator,
            TrapPopulator trapPopulator
    ) {
        this.plugin = plugin;
        this.debugLogger = debugLogger;
        this.templateManager = templateManager;
        this.schematicAdapter = schematicAdapter;
        this.enemyPlacementService = enemyPlacementService;
        this.chestPopulator = chestPopulator;
        this.treasurePopulator = treasurePopulator;
        this.trapPopulator = trapPopulator;
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
        int maxDepth = request.maxDepthOverride() == null ? generation.maxDepth() : Math.max(1, request.maxDepthOverride());
        Random random = new Random(request.seed());
        GenerationPlan plan = buildGenerationPlan(generation, templates, maxDepth, random);
        int targetPieceCount = plan.targetPieceCount();
        List<PlacedPiece> pieces = new ArrayList<>();
        List<PieceConnection> connections = new ArrayList<>();
        List<Frontier> frontiers = new ArrayList<>();
        Set<String> usedEntrances = new HashSet<>();
        Set<String> blockedEntrances = new HashSet<>();
        Map<String, Integer> placedByPieceId = new HashMap<>();
        int[] placedPerDepth = new int[maxDepth + 1];

        PlacedPiece startPiece = createPlacedPiece(startTemplate, Rotation.NONE, request.origin(), 0, 0);
        pieces.add(startPiece);
        incrementPieceCount(placedByPieceId, startPiece.template().id());
        placedPerDepth[0] = 1;
        debugLogger.log("generation", "plan", Map.of(
                "depthTargets", format(plan.targetPiecesPerDepth()),
                "maxDepth", maxDepth,
                "target", targetPieceCount
        ));
        activateEntrances(startPiece, usedEntrances, blockedEntrances, frontiers, random, maxDepth, generation, plan, placedPerDepth);

        while (pieces.size() < targetPieceCount && !frontiers.isEmpty()) {
            Frontier frontier = selectFrontier(frontiers, random, plan, placedPerDepth);
            frontiers.remove(frontier);
            if (usedEntrances.contains(frontier.entrance.key()) || frontier.entrance.piece().depth() >= maxDepth) {
                continue;
            }

            PlacementAttempt attempt = tryPlace(frontier, pieces, templates, random, maxDepth, generation, placedByPieceId);
            if (attempt == null) {
                blockedEntrances.add(frontier.entrance.key());
                debugLogger.log("generation", "frontier_exhausted", Map.of(
                        "depth", frontier.entrance.piece().depth(),
                        "entrance", frontier.entrance.template().id(),
                        "piece", frontier.entrance.piece().template().id()
                ));
                activateEntrances(frontier.entrance.piece(), usedEntrances, blockedEntrances, frontiers, random, maxDepth, generation, plan, placedPerDepth);
                continue;
            }

            usedEntrances.add(frontier.entrance.key());
            usedEntrances.add(attempt.childEntrance.key());
            pieces.add(attempt.piece);
            incrementPieceCount(placedByPieceId, attempt.piece.template().id());
            placedPerDepth[attempt.piece.depth()]++;
            connections.add(new PieceConnection(frontier.entrance, attempt.childEntrance));
            activateEntrances(attempt.piece, usedEntrances, blockedEntrances, frontiers, random, maxDepth, generation, plan, placedPerDepth);
        }

        for (PlacedPiece piece : pieces) {
            schematicAdapter.paste(request.world(), piece);
        }

        List<PieceConnection> allConnections = collectConnections(pieces, connections);
        for (PieceConnection connection : allConnections) {
            carveConnection(request.world(), connection);
            maybePlaceDoor(request.world(), connection, random, settings.door());
        }

        int treasureCount = 0;
        int trapCount = 0;
        for (PlacedPiece piece : pieces) {
            chestPopulator.populateRoom(request.world(), piece);
            treasureCount += treasurePopulator.populateRoom(request.world(), piece);
            trapCount += trapPopulator.populateRoom(request.world(), piece);
        }
        treasureCount += populateMissingTreasures(request.world(), pieces, settings.azirouge().treasure().minPerDungeon(), treasureCount, random);
        trapCount += populateMissingTraps(request.world(), pieces, settings.azirouge().traps().minPerDungeon(), trapCount, random);

        List<EnemySpawnReservation> reservations = enemyPlacementService.plan(pieces, settings.enemies());
        Location spawnLocation = resolveSpawnLocation(request.world(), startPiece);
        debugLogger.log("generation", "complete", Map.of(
                "connections", allConnections.size(),
                "maxDepth", maxDepth,
                "pieces", pieces.size(),
                "seed", request.seed(),
                "target", targetPieceCount,
                "traps", trapCount,
                "treasures", treasureCount,
                "world", request.world().getName()
        ));
        return new DungeonGenerationResult(
                request.seed(),
                targetPieceCount,
                pieces.size(),
                allConnections.size(),
                reservations,
                List.copyOf(pieces),
                spawnLocation
        );
    }

    private int populateMissingTreasures(World world, List<PlacedPiece> pieces, int minimum, int current, Random random) {
        int missing = Math.max(0, minimum - current);
        if (missing <= 0 || pieces.isEmpty()) {
            return 0;
        }

        int spawned = 0;
        for (PlacedPiece piece : preferredGimmickPieces(pieces)) {
            if (spawned >= missing) {
                break;
            }
            int count = treasurePopulator.populateGuaranteedRoom(world, piece, random);
            spawned += count;
            debugLogger.log("treasure", "minimum_attempt", Map.of(
                    "piece", piece.template().id(),
                    "spawned", count,
                    "targetMissing", missing
            ));
        }
        return spawned;
    }

    private int populateMissingTraps(World world, List<PlacedPiece> pieces, int minimum, int current, Random random) {
        int missing = Math.max(0, minimum - current);
        if (missing <= 0 || pieces.isEmpty()) {
            return 0;
        }

        int spawned = 0;
        for (PlacedPiece piece : preferredGimmickPieces(pieces)) {
            if (spawned >= missing) {
                break;
            }
            int count = trapPopulator.populateGuaranteedRoom(world, piece, random);
            spawned += count;
            debugLogger.log("trap", "minimum_attempt", Map.of(
                    "piece", piece.template().id(),
                    "spawned", count,
                    "targetMissing", missing
            ));
        }
        return spawned;
    }

    private List<PlacedPiece> preferredGimmickPieces(List<PlacedPiece> pieces) {
        List<PlacedPiece> ordered = new ArrayList<>(pieces);
        ordered.sort((left, right) -> Integer.compare(right.depth(), left.depth()));
        return ordered;
    }

    private PlacementAttempt tryPlace(
            Frontier frontier,
            List<PlacedPiece> pieces,
            LoadedTemplates templates,
            Random random,
            int maxDepth,
            GenerationSettings settings,
            Map<String, Integer> placedByPieceId
    ) {
        List<PlacementCandidate> candidates = new ArrayList<>();
        boolean hasUnmetMinimumCandidate = false;
        for (PieceTemplate candidateTemplate : templates.pieces().values()) {
            int placedCount = placedByPieceId.getOrDefault(candidateTemplate.id(), 0);
            if (placedCount >= candidateTemplate.maxGenerations()) {
                continue;
            }
            List<EntranceTemplate> candidateEntrances = new ArrayList<>(candidateTemplate.entrances());
            Collections.shuffle(candidateEntrances, random);
            for (EntranceTemplate candidateEntrance : candidateEntrances) {
                Rotation rotation = Rotation.fromFacing(candidateEntrance.facing(), frontier.entrance.worldFacing().opposite());
                for (IntVector3 origin : computeChildOrigins(frontier.entrance, candidateTemplate, candidateEntrance, rotation)) {
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
                    PlacedPiece deniedNeighbor = findDeniedAdjacentPiece(placedPiece, pieces);
                    if (deniedNeighbor != null) {
                        debugLogger.log("generation", "candidate_rejected", Map.of(
                                "candidate", candidateTemplate.id(),
                                "neighbor", deniedNeighbor.template().id(),
                                "origin", format(origin),
                                "reason", "adjacent_piece_denied",
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
                    int adjacentCount = adjacentPieceCount(placedPiece, pieces);
                    double weight = candidateWeight(candidateTemplate, placedCount, adjacentCount, settings);
                    boolean unmetMinimum = placedCount < candidateTemplate.minGenerations();
                    hasUnmetMinimumCandidate = hasUnmetMinimumCandidate || unmetMinimum;
                    candidates.add(new PlacementCandidate(placedPiece, placedEntrance, weight, adjacentCount, unmetMinimum));
                }
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        PlacementCandidate selected = selectPlacementCandidate(candidates, hasUnmetMinimumCandidate, random);
        debugLogger.log("generation", "candidate_accepted", Map.of(
                "adjacentCount", selected.adjacentCount(),
                "candidate", selected.piece().template().id(),
                "childEntrance", selected.childEntrance().template().id(),
                "depth", selected.piece().depth(),
                "origin", format(selected.piece().origin()),
                "rotation", selected.piece().rotation().degrees(),
                "weight", selected.weight()
        ));
        return new PlacementAttempt(selected.piece(), selected.childEntrance());
    }

    private void activateEntrances(
            PlacedPiece piece,
            Set<String> usedEntrances,
            Set<String> blockedEntrances,
            List<Frontier> frontiers,
            Random random,
            int maxDepth,
            GenerationSettings settings,
            GenerationPlan plan,
            int[] placedPerDepth
    ) {
        if (piece.depth() >= maxDepth) {
            return;
        }

        List<PlacedEntrance> available = new ArrayList<>();
        for (PlacedEntrance entrance : piece.entrances()) {
            if (!usedEntrances.contains(entrance.key())
                    && !blockedEntrances.contains(entrance.key())
                    && !hasQueuedFrontier(entrance, frontiers)) {
                available.add(entrance);
            }
        }
        if (available.isEmpty()) {
            return;
        }

        int nextDepth = piece.depth() + 1;
        int remainingBudget = plan.remainingForDepth(nextDepth, placedPerDepth);
        if (remainingBudget <= 0) {
            return;
        }

        Collections.shuffle(available, random);
        int desiredCount = resolveDesiredFrontierCount(available.size(), remainingBudget, settings, random);
        int requiredConnections = Math.max(0, piece.template().minEntranceConnections()
                - connectedEntranceCount(piece, usedEntrances)
                - queuedEntranceCount(piece, frontiers));
        desiredCount = Math.max(desiredCount, requiredConnections);
        desiredCount = Math.min(desiredCount, available.size());
        desiredCount = Math.min(desiredCount, remainingBudget);
        for (int index = 0; index < desiredCount; index++) {
            frontiers.add(new Frontier(available.get(index)));
        }
    }

    private int connectedEntranceCount(PlacedPiece piece, Set<String> usedEntrances) {
        int count = 0;
        for (PlacedEntrance entrance : piece.entrances()) {
            if (usedEntrances.contains(entrance.key())) {
                count++;
            }
        }
        return count;
    }

    private int queuedEntranceCount(PlacedPiece piece, List<Frontier> frontiers) {
        int count = 0;
        for (Frontier frontier : frontiers) {
            if (frontier.entrance.piece().index() == piece.index()) {
                count++;
            }
        }
        return count;
    }

    private boolean hasQueuedFrontier(PlacedEntrance entrance, List<Frontier> frontiers) {
        for (Frontier frontier : frontiers) {
            if (frontier.entrance.key().equals(entrance.key())) {
                return true;
            }
        }
        return false;
    }

    private PlacedPiece createPlacedPiece(PieceTemplate template, Rotation rotation, IntVector3 origin, int depth, int index) {
        TransformedPieceGeometry geometry = transformGeometry(template, rotation);
        BlockBox worldBounds = geometry.relativeBounds().offset(origin);
        List<IntVector3> strollPoints = createStrollPoints(worldBounds);
        PlacedPiece base = new PlacedPiece(index, template, rotation, origin, depth, worldBounds, strollPoints, List.of());
        List<PlacedEntrance> entrances = new ArrayList<>(geometry.entrances().size());
        for (TransformedEntranceGeometry transformedEntrance : geometry.entrances()) {
            BlockBox plane = transformedEntrance.relativePlane().offset(origin);
            BlockBox opening = transformedEntrance.relativeOpening().offset(origin);
            entrances.add(new PlacedEntrance(base, transformedEntrance.template(), transformedEntrance.worldFacing(), plane, opening));
        }

        PlacedPiece full = new PlacedPiece(index, template, rotation, origin, depth, worldBounds, strollPoints, List.of());
        List<PlacedEntrance> rebound = new ArrayList<>(entrances.size());
        for (PlacedEntrance entrance : entrances) {
            rebound.add(new PlacedEntrance(full, entrance.template(), entrance.worldFacing(), entrance.planeBox(), entrance.openingBox()));
        }
        return new PlacedPiece(index, template, rotation, origin, depth, worldBounds, strollPoints, List.copyOf(rebound));
    }

    private List<IntVector3> createStrollPoints(BlockBox bounds) {
        int minX = interiorMin(bounds.minX(), bounds.maxX());
        int maxX = interiorMax(bounds.minX(), bounds.maxX());
        int minZ = interiorMin(bounds.minZ(), bounds.maxZ());
        int maxZ = interiorMax(bounds.minZ(), bounds.maxZ());
        int y = bounds.minY() + 1;

        List<IntVector3> points = new ArrayList<>();
        addDistinctPoint(points, midpoint(minX, maxX), y, midpoint(minZ, maxZ));
        addDistinctPoint(points, minX, y, minZ);
        addDistinctPoint(points, minX, y, maxZ);
        addDistinctPoint(points, maxX, y, minZ);
        addDistinctPoint(points, maxX, y, maxZ);
        return List.copyOf(points);
    }

    private void addDistinctPoint(List<IntVector3> points, int x, int y, int z) {
        IntVector3 point = new IntVector3(x, y, z);
        if (!points.contains(point)) {
            points.add(point);
        }
    }

    private void incrementPieceCount(Map<String, Integer> placedByPieceId, String pieceId) {
        placedByPieceId.merge(pieceId, 1, Integer::sum);
    }

    private int midpoint(int min, int max) {
        return min + ((max - min) / 2);
    }

    private List<IntVector3> computeChildOrigins(
            PlacedEntrance parentEntrance,
            PieceTemplate childTemplate,
            EntranceTemplate childEntrance,
            Rotation rotation
    ) {
        TransformedPieceGeometry geometry = transformGeometry(childTemplate, rotation);
        TransformedEntranceGeometry transformedEntrance = geometry.findEntrance(childEntrance.id());
        BlockBox childPlane = transformedEntrance.relativePlane();
        BlockBox parentPlane = parentEntrance.planeBox();
        int y = parentPlane.minY() - childPlane.minY();

        switch (parentEntrance.worldFacing()) {
            case NORTH -> {
                int z = parentPlane.minZ() - childPlane.minZ() - 1;
                int x = centeredOriginForAxis(parentPlane.minX(), parentPlane.sizeX(), childPlane.minX(), childPlane.sizeX());
                return List.of(new IntVector3(x, y, z));
            }
            case SOUTH -> {
                int z = parentPlane.maxZ() + 1 - childPlane.minZ();
                int x = centeredOriginForAxis(parentPlane.minX(), parentPlane.sizeX(), childPlane.minX(), childPlane.sizeX());
                return List.of(new IntVector3(x, y, z));
            }
            case EAST -> {
                int x = parentPlane.maxX() + 1 - childPlane.minX();
                int z = centeredOriginForAxis(parentPlane.minZ(), parentPlane.sizeZ(), childPlane.minZ(), childPlane.sizeZ());
                return List.of(new IntVector3(x, y, z));
            }
            case WEST -> {
                int x = parentPlane.minX() - childPlane.minX() - 1;
                int z = centeredOriginForAxis(parentPlane.minZ(), parentPlane.sizeZ(), childPlane.minZ(), childPlane.sizeZ());
                return List.of(new IntVector3(x, y, z));
            }
            default -> throw new IllegalStateException("Unsupported direction");
        }
    }

    private int centeredOriginForAxis(int parentMin, int parentSize, int childMin, int childSize) {
        return parentMin + centeredOffset(parentSize, childSize) - childMin;
    }

    private int centeredOffset(int parentSize, int childSize) {
        return Math.floorDiv(parentSize - childSize, 2);
    }

    private int overlapSize(int minA, int maxA, int minB, int maxB) {
        int overlapMin = Math.max(minA, minB);
        int overlapMax = Math.min(maxA, maxB);
        return overlapMin > overlapMax ? 0 : overlapMax - overlapMin + 1;
    }

    private int resolveDesiredFrontierCount(int availableCount, int remainingBudget, GenerationSettings settings, Random random) {
        if (availableCount <= 0 || remainingBudget <= 0) {
            return 0;
        }
        double expected = settings.expectedActivatedEntranceCount(availableCount);
        int desired = (int) Math.floor(expected);
        double fraction = expected - desired;
        if (fraction > 0.0D && random.nextDouble() < fraction) {
            desired++;
        }
        desired = Math.max(1, desired);
        desired = Math.min(desired, availableCount);
        return Math.min(desired, remainingBudget);
    }

    private boolean intersectsExisting(BlockBox candidate, List<PlacedPiece> pieces) {
        for (PlacedPiece piece : pieces) {
            if (candidate.intersects(piece.worldBounds())) {
                return true;
            }
        }
        return false;
    }

    private PlacementCandidate selectPlacementCandidate(List<PlacementCandidate> candidates, boolean requireUnmetMinimum, Random random) {
        List<PlacementCandidate> pool = requireUnmetMinimum
                ? candidates.stream().filter(PlacementCandidate::unmetMinimum).toList()
                : candidates;
        if (pool.isEmpty()) {
            pool = candidates;
        }

        double totalWeight = 0.0D;
        for (PlacementCandidate candidate : pool) {
            totalWeight += candidate.weight();
        }
        double cursor = random.nextDouble() * totalWeight;
        PlacementCandidate selected = pool.get(0);
        for (PlacementCandidate candidate : pool) {
            cursor -= candidate.weight();
            if (cursor <= 0.0D) {
                selected = candidate;
                break;
            }
        }
        return selected;
    }

    private double candidateWeight(PieceTemplate template, int placedCount, int adjacentCount, GenerationSettings settings) {
        int extraAdjacentCount = Math.max(0, adjacentCount - 1);
        double adjacentPenalty = 1.0D + extraAdjacentCount * settings.adjacentPiecePenalty();
        double minimumBoost = placedCount < template.minGenerations() ? 8.0D : 1.0D;
        return Math.max(0.0001D, template.weight() * minimumBoost / adjacentPenalty);
    }

    private int adjacentPieceCount(PlacedPiece candidate, List<PlacedPiece> pieces) {
        int count = 0;
        for (PlacedPiece piece : pieces) {
            if (areAdjacent(candidate.worldBounds(), piece.worldBounds())) {
                count++;
            }
        }
        return count;
    }

    private PlacedPiece findDeniedAdjacentPiece(PlacedPiece candidate, List<PlacedPiece> pieces) {
        for (PlacedPiece piece : pieces) {
            if (!areAdjacent(candidate.worldBounds(), piece.worldBounds())) {
                continue;
            }
            if (!allowsAdjacency(candidate.template(), piece.template())) {
                return piece;
            }
        }
        return null;
    }

    private boolean allowsAdjacency(PieceTemplate left, PieceTemplate right) {
        return left.allowsAdjacentPiece(right.id()) && right.allowsAdjacentPiece(left.id());
    }

    private PlacedEntrance findEntrance(PlacedPiece piece, String entranceId) {
        for (PlacedEntrance entrance : piece.entrances()) {
            if (entrance.template().id().equals(entranceId)) {
                return entrance;
            }
        }
        throw new IllegalArgumentException("Entrance not found: " + entranceId + " on piece " + piece.template().id());
    }

    private Frontier selectFrontier(List<Frontier> frontiers, Random random, GenerationPlan plan, int[] placedPerDepth) {
        List<Frontier> prioritized = new ArrayList<>();
        int deepestNeededDepth = Integer.MIN_VALUE;
        for (Frontier frontier : frontiers) {
            int childDepth = frontier.entrance.piece().depth() + 1;
            if (childDepth > plan.maxDepth() || plan.remainingForDepth(childDepth, placedPerDepth) <= 0) {
                continue;
            }
            if (childDepth > deepestNeededDepth) {
                deepestNeededDepth = childDepth;
                prioritized.clear();
            }
            if (childDepth == deepestNeededDepth) {
                prioritized.add(frontier);
            }
        }
        List<Frontier> pool = prioritized.isEmpty() ? frontiers : prioritized;
        return weightedFrontier(pool, random);
    }

    private Frontier weightedFrontier(List<Frontier> frontiers, Random random) {
        double totalWeight = 0.0D;
        for (Frontier frontier : frontiers) {
            totalWeight += frontierWeight(frontier);
        }
        double cursor = random.nextDouble() * totalWeight;
        Frontier selected = frontiers.get(0);
        for (Frontier frontier : frontiers) {
            cursor -= frontierWeight(frontier);
            if (cursor <= 0.0D) {
                selected = frontier;
                break;
            }
        }
        return selected;
    }

    private double frontierWeight(Frontier frontier) {
        return Math.max(1.0D, frontier.entrance.piece().template().entrances().size());
    }

    private GenerationPlan buildGenerationPlan(
            GenerationSettings settings,
            LoadedTemplates templates,
            int maxDepth,
            Random random
    ) {
        int[] targetPerDepth = new int[maxDepth + 1];
        targetPerDepth[0] = 1;
        int minimumFromPieces = totalMinimumGenerations(templates);
        int minTarget = Math.max(settings.minPieceCountForDepth(maxDepth), minimumFromPieces);
        int maxTarget = Math.max(minTarget, settings.maxPieceCountForDepth(maxDepth));
        int rawTarget = randomBetween(minTarget, maxTarget, random);
        int target = Math.max(minTarget, Math.min(maxTarget, (int) Math.round(rawTarget * settings.depthPredictionMultiplier())));
        int remaining = Math.max(0, target - 1);

        for (int depth = 1; depth <= maxDepth && remaining > 0; depth++) {
            targetPerDepth[depth] = 1;
            remaining--;
        }

        for (int depth = maxDepth; depth >= 1 && remaining > 0; depth--) {
            int depthBudget = Math.max(0, settings.maxPiecesPerDepth() - targetPerDepth[depth]);
            int add = Math.min(depthBudget, remaining);
            targetPerDepth[depth] += add;
            remaining -= add;
        }

        for (int depth = 1; depth <= maxDepth && remaining > 0; depth++) {
            targetPerDepth[depth]++;
            remaining--;
        }

        return new GenerationPlan(maxDepth, target, targetPerDepth);
    }

    private int totalMinimumGenerations(LoadedTemplates templates) {
        int total = 0;
        for (PieceTemplate template : templates.pieces().values()) {
            total += template.minGenerations();
        }
        return Math.max(1, total);
    }

    private int randomBetween(int min, int max, Random random) {
        if (max <= min) {
            return min;
        }
        return min + random.nextInt(max - min + 1);
    }

    private List<PieceConnection> collectConnections(List<PlacedPiece> pieces, List<PieceConnection> explicitConnections) {
        List<PieceConnection> collected = new ArrayList<>(explicitConnections);
        Set<String> seen = new LinkedHashSet<>();
        for (PieceConnection connection : explicitConnections) {
            seen.add(connectionKey(connection.parentEntrance(), connection.childEntrance()));
        }

        for (int leftIndex = 0; leftIndex < pieces.size(); leftIndex++) {
            PlacedPiece left = pieces.get(leftIndex);
            for (int rightIndex = leftIndex + 1; rightIndex < pieces.size(); rightIndex++) {
                PlacedPiece right = pieces.get(rightIndex);
                if (!areAdjacent(left.worldBounds(), right.worldBounds())) {
                    continue;
                }
                if (!allowsAdjacency(left.template(), right.template())) {
                    debugLogger.log("generation", "adjacent_connection_blocked", Map.of(
                            "left", left.template().id(),
                            "right", right.template().id(),
                            "reason", "adjacent_piece_denied"
                    ));
                    continue;
                }
                for (PlacedEntrance leftEntrance : left.entrances()) {
                    for (PlacedEntrance rightEntrance : right.entrances()) {
                        if (!leftEntrance.canConnectTo(rightEntrance)) {
                            continue;
                        }
                        String key = connectionKey(leftEntrance, rightEntrance);
                        if (!seen.add(key)) {
                            continue;
                        }
                        collected.add(new PieceConnection(leftEntrance, rightEntrance));
                        debugLogger.log("generation", "adjacent_connection_detected", Map.of(
                                "left", leftEntrance.key(),
                                "right", rightEntrance.key(),
                                "pieces", left.template().id() + "<->" + right.template().id()
                        ));
                    }
                }
            }
        }
        return collected;
    }

    private boolean areAdjacent(BlockBox left, BlockBox right) {
        boolean adjacentOnX = left.maxX() + 1 == right.minX() || right.maxX() + 1 == left.minX();
        boolean adjacentOnZ = left.maxZ() + 1 == right.minZ() || right.maxZ() + 1 == left.minZ();
        boolean overlapY = overlapSize(left.minY(), left.maxY(), right.minY(), right.maxY()) > 0;
        boolean overlapZ = overlapSize(left.minZ(), left.maxZ(), right.minZ(), right.maxZ()) > 0;
        boolean overlapX = overlapSize(left.minX(), left.maxX(), right.minX(), right.maxX()) > 0;
        return (adjacentOnX && overlapY && overlapZ) || (adjacentOnZ && overlapY && overlapX);
    }

    private String connectionKey(PlacedEntrance first, PlacedEntrance second) {
        String left = first.key();
        String right = second.key();
        return left.compareTo(right) <= 0 ? left + "|" + right : right + "|" + left;
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

    private Location resolveSpawnLocation(World world, PlacedPiece piece) {
        BlockBox bounds = piece.worldBounds();
        int minX = interiorMin(bounds.minX(), bounds.maxX());
        int maxX = interiorMax(bounds.minX(), bounds.maxX());
        int minZ = interiorMin(bounds.minZ(), bounds.maxZ());
        int maxZ = interiorMax(bounds.minZ(), bounds.maxZ());
        int centerX = (bounds.minX() + bounds.maxX()) / 2;
        int centerZ = (bounds.minZ() + bounds.maxZ()) / 2;
        int minFeetY = Math.max(bounds.minY() + 1, world.getMinHeight() + 1);
        int maxFeetY = Math.min(bounds.maxY() - 1, world.getMaxHeight() - 2);
        Location best = null;
        int bestDistance = Integer.MAX_VALUE;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int distance = Math.abs(x - centerX) + Math.abs(z - centerZ);
                if (best != null && distance > bestDistance) {
                    continue;
                }
                for (int y = minFeetY; y <= maxFeetY; y++) {
                    Block floor = world.getBlockAt(x, y - 1, z);
                    Block feet = world.getBlockAt(x, y, z);
                    Block head = world.getBlockAt(x, y + 1, z);
                    if (!floor.getType().isSolid() || !feet.isPassable() || !head.isPassable()) {
                        continue;
                    }
                    best = new Location(world, x + 0.5D, y, z + 0.5D, 0.0F, 0.0F);
                    bestDistance = distance;
                    break;
                }
            }
        }

        if (best != null) {
            return best;
        }
        return new Location(world, centerX + 0.5D, Math.max(bounds.minY() + 1, world.getMinHeight() + 1), centerZ + 0.5D, 0.0F, 0.0F);
    }

    private int interiorMin(int min, int max) {
        return max - min >= 2 ? min + 1 : min;
    }

    private int interiorMax(int min, int max) {
        return max - min >= 2 ? max - 1 : max;
    }

    private String format(int[] values) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(index).append(':').append(values[index]);
        }
        return builder.toString();
    }

    private record Frontier(PlacedEntrance entrance) {
    }

    private record PlacementAttempt(PlacedPiece piece, PlacedEntrance childEntrance) {
    }

    private record PlacementCandidate(
            PlacedPiece piece,
            PlacedEntrance childEntrance,
            double weight,
            int adjacentCount,
            boolean unmetMinimum
    ) {
    }

    private record GenerationPlan(int maxDepth, int targetPieceCount, int[] targetPiecesPerDepth) {
        private int remainingForDepth(int depth, int[] placedPerDepth) {
            if (depth < 0 || depth >= targetPiecesPerDepth.length) {
                return 0;
            }
            return Math.max(0, targetPiecesPerDepth[depth] - placedPerDepth[depth]);
        }
    }

    private record TransformedPieceGeometry(BlockBox relativeBounds, List<TransformedEntranceGeometry> entrances) {
        private TransformedEntranceGeometry findEntrance(String entranceId) {
            for (TransformedEntranceGeometry entrance : entrances) {
                if (entrance.template().id().equals(entranceId)) {
                    return entrance;
                }
            }
            throw new IllegalArgumentException("Entrance not found: " + entranceId);
        }
    }

    private record TransformedEntranceGeometry(
            EntranceTemplate template,
            Direction worldFacing,
            BlockBox relativePlane,
            BlockBox relativeOpening
    ) {
    }

    private TransformedPieceGeometry transformGeometry(PieceTemplate template, Rotation rotation) {
        BlockBox normalizedBounds = normalizeBounds(template.bounds());
        BlockBox rotatedBounds = normalizedBounds.rotate(rotation);
        IntVector3 shift = negate(rotatedBounds.min());
        BlockBox relativeBounds = rotatedBounds.offset(shift);
        List<TransformedEntranceGeometry> entrances = new ArrayList<>(template.entrances().size());
        for (EntranceTemplate entrance : template.entrances()) {
            BlockBox normalizedPlane = normalizeBox(entrance.planeBox(), template.bounds().min());
            BlockBox rotatedPlane = normalizedPlane.rotate(rotation);
            BlockBox relativePlane = rotatedPlane.offset(shift);
            Direction worldFacing = rotation.rotate(entrance.facing());
            BlockBox relativeOpening = relativePlane.extend(worldFacing.opposite(), entrance.width() - 1);
            entrances.add(new TransformedEntranceGeometry(entrance, worldFacing, relativePlane, relativeOpening));
        }
        return new TransformedPieceGeometry(relativeBounds, List.copyOf(entrances));
    }

    private BlockBox normalizeBounds(BlockBox bounds) {
        return BlockBox.fromPoints(
                new IntVector3(0, 0, 0),
                new IntVector3(bounds.sizeX() - 1, bounds.sizeY() - 1, bounds.sizeZ() - 1)
        );
    }

    private BlockBox normalizeBox(BlockBox box, IntVector3 baseMin) {
        return BlockBox.fromPoints(box.min().subtract(baseMin), box.max().subtract(baseMin));
    }

    private IntVector3 negate(IntVector3 vector) {
        return new IntVector3(-vector.x(), -vector.y(), -vector.z());
    }
}
