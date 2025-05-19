package dev.xpple.deepslatecracker.command.commands;

import com.microsoft.z3.BitVecExpr;
import com.microsoft.z3.BitVecNum;
import com.microsoft.z3.Context;
import com.microsoft.z3.FPExpr;
import com.microsoft.z3.Model;
import com.microsoft.z3.Solver;
import com.microsoft.z3.Status;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.xpple.deepslatecracker.command.CustomClientCommandSource;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.mojang.brigadier.arguments.IntegerArgumentType.*;
import static dev.xpple.clientarguments.arguments.CEnumArgument.*;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.*;

@SuppressWarnings("CommentedOutCode")
public class CrackCommand {

    // leave one thread for the OS, and one to avoid blocking the client thread, but ensure at least one is used
    private static final int MAX_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);

    private static final SimpleCommandExceptionType NOT_IN_OVERWORLD_EXCEPTION = new SimpleCommandExceptionType(Component.translatable("commands.deepslatecracker:crack.notInOverworld"));
    private static final SimpleCommandExceptionType NOT_LOADED_EXCEPTION = new SimpleCommandExceptionType(Component.translatable("commands.deepslatecracker:crack.notLoaded"));
    private static final SimpleCommandExceptionType ALREADY_CRACKING_EXCEPTION = new SimpleCommandExceptionType(Component.translatable("commands.deepslatecracker:crack.alreadyCracking"));

    private static final RandomSupport.Seed128bit DEEPSLATE_HASH = RandomSupport.seedFromHashOf(ResourceLocation.withDefaultNamespace("deepslate").toString());
    private static final long STAFFORD_MIX_1 = -4658895280553007687L;
    private static final long STAFFORD_MIX_2 = -7723592293110705685L;
    private static final double D_7 = Mth.map(7, 0, 8, 1.0, 0.0);

    private static ExecutorService crackingExecutor = null;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (crackingExecutor != null) {
                crackingExecutor.shutdownNow();
            }
        }));
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("deepslatecracker:crack")
            .executes(ctx -> crack(CustomClientCommandSource.of(ctx.getSource())))
            .then(argument("threads", integer(1, MAX_THREADS))
                .executes(ctx -> crack(CustomClientCommandSource.of(ctx.getSource()), getInteger(ctx, "threads")))
                .then(argument("deepslategeneration", enumArg(DeepslateGeneration.class))
                    .executes(ctx -> crack(CustomClientCommandSource.of(ctx.getSource()), getInteger(ctx, "threads"), getEnum(ctx, "deepslategeneration"))))));
    }

    private static int crack(CustomClientCommandSource source) throws CommandSyntaxException {
        return crack(source, MAX_THREADS);
    }

    private static int crack(CustomClientCommandSource source, int threads) throws CommandSyntaxException {
        return crack(source, threads, DeepslateGeneration.NORMAL);
    }

    private static int crack(CustomClientCommandSource source, int threads, DeepslateGeneration deepslateGen) throws CommandSyntaxException {
        ResourceKey<Level> dimension = source.getDimension();
        if (dimension != Level.OVERWORLD) {
            throw NOT_IN_OVERWORLD_EXCEPTION.create();
        }

        if (crackingExecutor != null && !crackingExecutor.isTerminated()) {
            throw ALREADY_CRACKING_EXCEPTION.create();
        }

        ClientChunkCache chunkSource = source.getWorld().getChunkSource();
        BlockPos position = BlockPos.containing(source.getPosition());
        ChunkPos centerChunkPos = new ChunkPos(position);

        List<BlockPos> deepslatePositions = new ArrayList<>();
        for (ChunkPos chunkPos : ChunkPos.rangeClosed(centerChunkPos, 2).toList()) {
            scanChunk(deepslatePositions, chunkSource.getChunk(chunkPos.x, chunkPos.z, false));
        }

        BigInteger min = BigInteger.valueOf(Long.MIN_VALUE);
        BigInteger max = BigInteger.valueOf(Long.MAX_VALUE);
        BigInteger total = max.subtract(min).add(BigInteger.ONE);
        BigInteger chunkSize = total.divide(BigInteger.valueOf(threads));

        crackingExecutor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            BigInteger start = min.add(BigInteger.valueOf(i).multiply(chunkSize));
            BigInteger end = i == threads - 1 ? max : start.add(chunkSize).subtract(BigInteger.ONE);
            crackingExecutor.submit(() -> startCracking(source, deepslatePositions, deepslateGen, start.longValue(), end.longValue()));
        }
        source.sendFeedback(Component.translatable("commands.deepslatecracker:crack.started", threads));
        crackingExecutor.shutdown();
        return threads;
    }

    private static void scanChunk(List<BlockPos> deepslatePositions, LevelChunk chunk) throws CommandSyntaxException {
        if (chunk == null) {
            throw NOT_LOADED_EXCEPTION.create();
        }

        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();

        // deepslate generates at 0 <= y <= 7
        // deepslate is rarest at y = 7, therefore yielding the most information
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(7));

        for (int x = 0; x < LevelChunkSection.SECTION_WIDTH; x++) {
            for (int z = 0; z < LevelChunkSection.SECTION_WIDTH; z++) {
                if (section.getBlockState(x, 7 & 15, z).is(Blocks.DEEPSLATE)) {
                    deepslatePositions.add(new BlockPos(startX + x, 7, startZ + z));
                }
            }
        }
    }

    private static int startCracking(CustomClientCommandSource source, List<BlockPos> deepslatePositions, DeepslateGeneration deepslateGen, long min, long max) {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("model", "true");
        try (Context ctx = new Context(cfg)) {
            Solver solver = ctx.mkSolver();
            BitVecExpr seedExpr = ctx.mkBVConst("seedExpr", 64);

            // min <= seedExpr <= max
            solver.add(ctx.mkBVSLE(ctx.mkBV(min, 64), seedExpr));
            solver.add(ctx.mkBVSLE(seedExpr, ctx.mkBV(max, 64)));

            // this.random = settings.getRandomSource().newInstance(levelSeed).forkPositional();
            // long low = seed ^ RandomSupport.SILVER_RATIO_64;
            BitVecExpr low = ctx.mkBVXOR(seedExpr, ctx.mkBV(RandomSupport.SILVER_RATIO_64, 64));
            // long high = low + RandomSupport.GOLDEN_RATIO_64;
            BitVecExpr high = ctx.mkBVAdd(low, ctx.mkBV(RandomSupport.GOLDEN_RATIO_64, 64));
            // return new RandomSupport.Seed128bit(RandomSupport.mixStafford13(this.seedLo), RandomSupport.mixStafford13(this.seedHi));
            low = mixStafford13(ctx, low);
            high = mixStafford13(ctx, high);
            // return new XoroshiroRandomSource.XoroshiroPositionalRandomFactory(this.randomNumberGenerator.nextLong(), this.randomNumberGenerator.nextLong());
            BitVecExpr aL = advanceLow(ctx, low, high);
            BitVecExpr aH = advanceHigh(ctx, low, high);
            low = nextLong(ctx, low, high);
            high = nextLong(ctx, aL, aH);
            // Seed128bit seed128bit = RandomSupport.seedFromHashOf("minecraft:deepslate");
            // return new XoroshiroRandomSource(seed128bit.xor(this.seedLo, this.seedHi));
            // return new RandomSupport.Seed128bit(this.seedLo ^ seedLo, this.seedHi ^ seedHi);
            low = ctx.mkBVXOR(low, ctx.mkBV(DEEPSLATE_HASH.seedLo(), 64));
            high = ctx.mkBVXOR(high, ctx.mkBV(DEEPSLATE_HASH.seedHi(), 64));
            // return new XoroshiroRandomSource.XoroshiroPositionalRandomFactory(this.randomNumberGenerator.nextLong(), this.randomNumberGenerator.nextLong());
            aL = advanceLow(ctx, low, high);
            aH = advanceHigh(ctx, low, high);
            low = nextLong(ctx, low, high);
            high = nextLong(ctx, aL, aH);

            for (BlockPos pos : deepslatePositions) {
                // return new XoroshiroRandomSource(Mth.getSeed(x, y, z) ^ this.seedLo, this.seedHi);
                // return randomSource.nextFloat() < Mth.map(y, 0, 8, 1.0, 0.0);
                //noinspection deprecation
                solver.add(ctx.mkFPLt(nextFloat(ctx, ctx.mkBVXOR(ctx.mkBV(Mth.getSeed(pos), 64), low), high), ctx.mkFP(D_7, ctx.mkFPSort32())));
            }

            if (solver.check() != Status.SATISFIABLE) {
                sendError(source, Component.translatable("commands.deepslatecracker:crack.noSeedFound"));
                return 0;
            }

            Model model = solver.getModel();
            BitVecNum s = (BitVecNum) model.evaluate(seedExpr, false);
            long seed = s.getLong();
            sendFeedback(source, Component.translatable("commands.deepslatecracker:crack.success", ComponentUtils.copyOnClickText(Long.toString(seed))));
            crackingExecutor.shutdownNow();
            return (int) seed;
        }
    }

    // seed = (seed ^ seed >>> 30) * -4658895280553007687L;
    // seed = (seed ^ seed >>> 27) * -7723592293110705685L;
    // return seed ^ seed >>> 31;
    private static BitVecExpr mixStafford13(Context ctx, BitVecExpr seed) {
        seed = ctx.mkBVMul(ctx.mkBVXOR(seed, ctx.mkBVLSHR(seed, ctx.mkBV(30, 64))), ctx.mkBV(STAFFORD_MIX_1, 64));
        seed = ctx.mkBVMul(ctx.mkBVXOR(seed, ctx.mkBVLSHR(seed, ctx.mkBV(27, 64))), ctx.mkBV(STAFFORD_MIX_2, 64));
        return ctx.mkBVXOR(seed, ctx.mkBVLSHR(seed, ctx.mkBV(31, 64)));
    }

    // return (float)nextBits(24) * XoroshiroRandomSource.FLOAT_UNIT;
    private static FPExpr nextFloat(Context ctx, BitVecExpr low, BitVecExpr high) {
        FPExpr nextBitsCasted = ctx.mkFPToFP(ctx.mkFPRoundNearestTiesToEven(), nextBits(ctx, low, high, 24), ctx.mkFPSort32(), true);
        return ctx.mkFPMul(ctx.mkFPRoundNearestTiesToEven(), nextBitsCasted, ctx.mkFP(XoroshiroRandomSource.FLOAT_UNIT, ctx.mkFPSort32()));
    }

    // return nextLong() >>> 64 - bits;
    private static BitVecExpr nextBits(Context ctx, BitVecExpr low, BitVecExpr high, int bits) {
        return ctx.mkBVLSHR(nextLong(ctx, low, high), ctx.mkBV(64 - bits, 64));
    }

    // return Long.rotateLeft(low + high, 17) + low;
    private static BitVecExpr nextLong(Context ctx, BitVecExpr low, BitVecExpr high) {
        return ctx.mkBVAdd(ctx.mkBVRotateLeft(17, ctx.mkBVAdd(low, high)), low);
    }

    // long m = high ^ low
    // return Long.rotateLeft(low, 49) ^ m ^ m << 21
    private static BitVecExpr advanceLow(Context ctx, BitVecExpr low, BitVecExpr high) {
        BitVecExpr m = ctx.mkBVXOR(high, low);
        return ctx.mkBVXOR(ctx.mkBVXOR(ctx.mkBVRotateLeft(49, low), m), ctx.mkBVSHL(m, ctx.mkBV(21, 64)));
    }

    // return Long.rotateLeft(high ^ low, 28);
    private static BitVecExpr advanceHigh(Context ctx, BitVecExpr low, BitVecExpr high) {
        return ctx.mkBVRotateLeft(28, ctx.mkBVXOR(high, low));
    }

    private static void sendFeedback(CustomClientCommandSource source, Component feedback) {
        source.getClient().schedule(() -> source.sendFeedback(feedback));
    }

    private static void sendError(CustomClientCommandSource source, Component error) {
        source.getClient().schedule(() -> source.sendError(error));
    }

    private enum DeepslateGeneration implements StringRepresentable {
        NORMAL,
        PAPER1_18;

        @Override
        public @NotNull String getSerializedName() {
            return this.name();
        }
    }
}
