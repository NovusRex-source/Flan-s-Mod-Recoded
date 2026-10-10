package com.flansmod.recoded.trenches

import net.minecraft.world.level.block.HorizontalDirectionalBlock
import com.flansmod.recoded.fortification.Fortifications
import com.flansmod.recoded.gamemode.BattleMasterBlockEntity
import com.flansmod.recoded.gamemode.BattleMode
import com.flansmod.recoded.gamemode.Post
import com.flansmod.recoded.gamemode.TeamFlagBlock
import com.flansmod.recoded.gamemode.TeamFlagBlockEntity
import com.flansmod.recoded.registry.FlansBlocks
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LanternBlock
import net.minecraft.world.level.block.state.BlockState
import kotlin.math.abs

/**
 * Builds a Trenches battlefield in front of a Battle Master (in the direction its manager looks): a strip of churned
 * ground with shell craters, [TrenchSettings.trenches] trench lines [TrenchSettings.spacing] blocks apart (a two-wide
 * channel with duckboards, plank revetment and sandbag parapets on both sides, gaps to climb out), and a concrete
 * headquarters bunker at each end, open towards the field. The first team's headquarters is the one next to the Battle
 * Master. Registers the flag posts (bases and trench lines), puts a border around the field and switches the battle to
 * Trenches mode. Earlier posts of the Battle Master are removed.
 */
object TrenchField {
    /** Depth of a headquarters bunker along the lane. */
    const val BASE_DEPTH = 9
    /** Extra ground beside the trench lines. */
    const val MARGIN = 4
    private const val BASE_HALF = 5

    fun build(master: BattleMasterBlockEntity, level: ServerLevel, facing: Direction): Boolean {
        val teams = master.settings.teams
        if (master.running || teams.size < 2 || facing.axis == Direction.Axis.Y) return false
        val s = master.settings.trenches
        val count = s.trenches.coerceIn(1, 12)
        val spacing = s.spacing.coerceIn(8, 40)
        val half = s.width.coerceIn(5, 41) / 2
        val length = BASE_DEPTH * 2 + spacing * (count + 1)
        val right = facing.clockWise
        val start = master.blockPos.relative(facing, 4)
        val y0 = master.blockPos.y - 1
        fun at(a: Int, p: Int, dy: Int) = BlockPos(start.x + facing.stepX * a + right.stepX * p, y0 + dy, start.z + facing.stepZ * a + right.stepZ * p)
        fun set(pos: BlockPos, state: BlockState) = level.setBlock(pos, state, Block.UPDATE_CLIENTS)

        // Away with an earlier layout and anything left from the last battle.
        TrenchRules.end(master)
        for (post in master.state.posts.values) if (level.getBlockState(post.blockPos).block is TeamFlagBlock) level.removeBlock(post.blockPos, false)
        master.state = master.state.copy(posts = emptyMap())

        val random = RandomSource.create(master.blockPos.asLong())
        val surface = listOf(Blocks.COARSE_DIRT, Blocks.COARSE_DIRT, Blocks.DIRT, Blocks.PODZOL, Blocks.ROOTED_DIRT, Blocks.GRAVEL)
        for (a in -1..length) for (p in -half - MARGIN..half + MARGIN) {
            for (dy in -3..-1) set(at(a, p, dy), Blocks.DIRT.defaultBlockState())
            set(at(a, p, 0), surface[random.nextInt(surface.size)].defaultBlockState())
            for (dy in 1..10) set(at(a, p, dy), Blocks.AIR.defaultBlockState())
        }

        val lines = (0 until count).map { BASE_DEPTH + spacing * (it + 1) }
        // Shell craters in no man's land.
        repeat((count + 1) * 4) {
            val a = BASE_DEPTH + 3 + random.nextInt((length - BASE_DEPTH * 2 - 6).coerceAtLeast(1))
            if (lines.any { abs(a - it) <= 3 }) return@repeat
            val p = random.nextInt(half * 2 - 1) - half + 1
            for (da in -1..1) for (dp in -1..1) {
                set(at(a + da, p + dp, 0), Blocks.AIR.defaultBlockState())
                set(at(a + da, p + dp, -1), (if (random.nextBoolean()) Blocks.MUD else Blocks.COARSE_DIRT).defaultBlockState())
            }
            set(at(a, p, -1), Blocks.AIR.defaultBlockState())
            set(at(a, p, -2), Blocks.MUD.defaultBlockState())
        }

        val posts = mutableMapOf<BlockPos, Post>()
        val planks = Blocks.SPRUCE_PLANKS.defaultBlockState()
        lines.forEachIndexed { i, a ->
            for (p in -half..half) {
                for (row in a..a + 1) {
                    set(at(row, p, 0), Blocks.AIR.defaultBlockState())
                    set(at(row, p, -1), planks)
                }
                for (row in listOf(a - 1, a + 2)) {
                    set(at(row, p, 0), planks)
                    // Every fourth block no sandbags: a step to climb out.
                    set(at(row, p, 1), if (Math.floorMod(p, 4) == 0) Blocks.AIR.defaultBlockState() else Fortifications.SANDBAG_SLAB.defaultBlockState())
                }
            }
            for (row in a..a + 1) for (p in listOf(-half - 1, half + 1)) set(at(row, p, 0), planks)
            val flag = at(a, 0, 0)
            set(flag, FlansBlocks.TEAM_FLAG.defaultBlockState())
            posts[flag] = Post(listOf(flag.x, flag.y, flag.z), hill = true, label = "${i + 1}")
        }

        val baseA = headquarters(level, ::at, 0, 1, facing)
        val baseB = headquarters(level, ::at, length - BASE_DEPTH, -1, facing)
        posts[baseA] = Post(listOf(baseA.x, baseA.y, baseA.z), teams[0].name, label = "HQ ${teams[0].name}")
        posts[baseB] = Post(listOf(baseB.x, baseB.y, baseB.z), teams[1].name, label = "HQ ${teams[1].name}")

        val corner1 = at(-1, -half - MARGIN, 0)
        val corner2 = at(length, half + MARGIN, 0)
        master.state = master.state.copy(
            settings = master.settings.copy(mode = BattleMode.TRENCHES, fillWithBots = false, scoreLimit = 0, captureSeconds = 3, blockDamage = false),
            border = listOf(listOf(corner1.x, corner1.y, corner1.z), listOf(corner2.x, corner2.y, corner2.z)),
        )
        for ((pos, post) in posts) {
            (level.getBlockEntity(pos) as? TeamFlagBlockEntity)?.let { it.master = master.blockPos; it.setChanged() }
            master.setPost(pos, post)
        }
        master.syncScoreboard()
        return true
    }

    /**
     * A concrete bunker from lane position [a0] (depth [BASE_DEPTH]), its open front towards [toward] (+1: further along
     * the lane), firing slits to the front and sides, a doorway at the back. Returns its flag post (inside, centre).
     */
    private fun headquarters(level: ServerLevel, at: (Int, Int, Int) -> BlockPos, a0: Int, toward: Int, facing: Direction): BlockPos {
        fun set(pos: BlockPos, state: BlockState) = level.setBlock(pos, state, Block.UPDATE_CLIENTS)
        val concrete = Fortifications.REINFORCED_CONCRETE.defaultBlockState()
        val frontRow = if (toward > 0) a0 + BASE_DEPTH - 1 else a0
        val backRow = if (toward > 0) a0 else a0 + BASE_DEPTH - 1
        for (a in a0 until a0 + BASE_DEPTH) for (p in -BASE_HALF..BASE_HALF) {
            set(at(a, p, 0), concrete)
            set(at(a, p, 4), concrete)
            val wall = a == a0 || a == a0 + BASE_DEPTH - 1 || abs(p) == BASE_HALF
            for (dy in 1..3) set(at(a, p, dy), if (wall) concrete else Blocks.AIR.defaultBlockState())
        }
        val out = if (toward > 0) facing else facing.opposite
        for (p in -1..1) for (dy in 1..2) set(at(frontRow, p, dy), Blocks.AIR.defaultBlockState())
        for (p in listOf(-4, -3, 3, 4)) set(at(frontRow, p, 2), Fortifications.BUNKER_EMBRASURE.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, out))
        for (dy in 1..2) set(at(backRow, 0, dy), Blocks.AIR.defaultBlockState())
        val middle = a0 + BASE_DEPTH / 2
        set(at(middle, -BASE_HALF, 2), Fortifications.BUNKER_EMBRASURE.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, facing.counterClockWise))
        set(at(middle, BASE_HALF, 2), Fortifications.BUNKER_EMBRASURE.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, facing.clockWise))
        for (p in listOf(-3, 3)) set(at(middle, p, 3), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true))
        val flag = at(middle, 0, 1)
        set(flag, FlansBlocks.TEAM_FLAG.defaultBlockState())
        return flag
    }
}
