package dangomusicce.platform

import dangomusicce.DangoMusic
import dangomusicce.client.ClientMusic
import dangomusicce.music.AudioOutput
import dangomusicce.music.MusicPlayer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import java.nio.file.Path
import java.util.IdentityHashMap
import kotlin.math.floor

/** 高版本客户端游戏接口（M0 阶段部分渲染/音频能力为占位或基础实现）。 */
object ModernPlatform : Platform {

    private val mc: Minecraft get() = Minecraft.getInstance()

    override val configDir: Path
        get() = mc.gameDirectory.toPath().resolve("config").resolve(DangoMusic.MOD_ID)

    override val playerName: String get() = mc.user.name

    override val languageCode: String? get() = mc.options?.languageCode

    override val windowWidth: Int get() = mc.window.width
    override val windowHeight: Int get() = mc.window.height

    override val mouseX: Int
        get() = (mc.mouseHandler.xpos() * mc.window.width / mc.window.screenWidth.coerceAtLeast(1)).toInt()

    override val mouseY: Int
        get() = (mc.mouseHandler.ypos() * mc.window.height / mc.window.screenHeight.coerceAtLeast(1)).toInt()

    override fun isMouseDown(button: Int): Boolean = McCompat.isMouseDown(button)

    override fun isKeyDown(key: Int): Boolean {
        if (key in -100..-1) return isMouseDown(key + 100)
        val glfw = KeyMap.toGlfw(key)
        return glfw > 0 && glfwKey(glfw)
    }

    override fun syncKeyBindings(bindings: Collection<PlayerKeyBinding>) = ModernKeyBindings.sync(bindings)

    override fun isPlayerKeyModifier(keyCode: Int): Boolean = ModernKeyBindings.isModifierKey(keyCode)

    override fun activePlayerKeyModifier(): String? = ModernKeyBindings.activeModifier()

    override fun playerKeyModifierActive(action: PlayerAction): Boolean = ModernKeyBindings.modifierActive(action)

    override fun playerKeyDisplayName(action: PlayerAction): String? = ModernKeyBindings.displayName(action)

    private fun glfwKey(key: Int): Boolean = McCompat.isGlfwKeyDown(key)

    private val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    override val ctrlDown: Boolean
        get() = if (isMac) glfwKey(GlfwKeys.GLFW_KEY_LEFT_SUPER) || glfwKey(GlfwKeys.GLFW_KEY_RIGHT_SUPER)
        else glfwKey(GlfwKeys.GLFW_KEY_LEFT_CONTROL) || glfwKey(GlfwKeys.GLFW_KEY_RIGHT_CONTROL)

    override var clipboard: String
        get() = mc.keyboardHandler.clipboard
        set(value) {
            mc.keyboardHandler.clipboard = value
        }

    override val inGame: Boolean get() = McCompat.currentScreen() == null && mc.level != null

    override val inMainMenu: Boolean get() = McCompat.gameLoaded(mc) && McCompat.currentScreen() is TitleScreen

    override val chatOpen: Boolean get() = McCompat.currentScreen() is ChatScreen

    override val guiScale: Int get() = mc.window.guiScale.toInt()

    override val fps: Int get() = mc.fps

    override val fov: Float get() = mc.options.fov().get().toFloat()

    override val pingMs: Int
        get() {
            val p = mc.player ?: return 0
            if (mc.hasSingleplayerServer()) return 0
            return mc.connection?.getPlayerInfo(p.uuid)?.latency ?: 0
        }

    override val player: PlayerPose?
        get() = mc.player?.let { p -> PlayerPose(p.x, p.eyeY, p.z, p.yRot, p.xRot) }

    override val dimension: Int
        get() {
            val key = mc.level?.dimension() ?: return 0
            return when (key) {
                Level.OVERWORLD -> 0
                Level.NETHER -> -1
                Level.END -> 1
                else -> key.location().toString().hashCode()
            }
        }

    override val skyDarken: Int? get() = mc.level?.getSkyDarken()

    private val envProbe = BlockPos.MutableBlockPos()

    override val environment: ListenerEnvironment?
        get() {
            val p = mc.player ?: return null
            val level = mc.level ?: return null
            if (!MusicPlayer.active) return null
            val eyeX = floor(p.x).toInt()
            val eyeY = floor(p.eyeY).toInt()
            val eyeZ = floor(p.z).toInt()
            envProbe.set(eyeX, eyeY, eyeZ)
            val sky = level.canSeeSky(envProbe)
            val rain = when {
                !level.isRainingAt(envProbe) -> 0f
                level.isThundering -> 1f
                else -> 0.5f
            }
            var hits = 0
            for (d in RAY_DIRECTIONS) {
                for (step in 1..ENCLOSURE_RANGE) {
                    envProbe.set(
                        floor(p.x + d[0] * step).toInt(),
                        floor(p.eyeY + d[1] * step).toInt(),
                        floor(p.z + d[2] * step).toInt(),
                    )
                    val state = level.getBlockState(envProbe)
                    if (!state.isAir && state.fluidState.isEmpty && !isAcousticLeaves(state) &&
                        !state.getCollisionShape(level, envProbe).isEmpty
                    ) {
                        hits++
                        break
                    }
                }
            }
            return ListenerEnvironment(p.isUnderWater, sky, hits / RAY_DIRECTIONS.size.toFloat(), rain)
        }

    private fun isAcousticLeaves(state: BlockState): Boolean =
        state.block is LeavesBlock || state.`is`(BlockTags.LEAVES)

    private val probe = BlockPos.MutableBlockPos()
    private val absorptionCache = IdentityHashMap<BlockState, Float>()

    override fun soundAbsorption(x: Int, y: Int, z: Int): Float {
        val level = mc.level ?: return -1f
        probe.set(x, y, z)
        val state = level.getBlockState(probe)
        return absorptionCache.getOrPut(state) {
            if (state.isAir || isAcousticLeaves(state) || !state.fluidState.isEmpty || state.getCollisionShape(level, probe).isEmpty) -1f
            else soundAbsorptionOf(state.soundType)
        }
    }

    override fun clearFraction(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double): Double {
        val level = mc.level ?: return 1.0
        val dx = x1 - x0
        val dy = y1 - y0
        val dz = z1 - z0
        val len2 = dx * dx + dy * dy + dz * dz
        if (!len2.isFinite() || len2 < 1e-8) return 1.0
        val hit = try {
            level.clip(
                ClipContext(
                    Vec3(x0, y0, z0),
                    Vec3(x1, y1, z1),
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    CollisionContext.empty(),
                ),
            )
        } catch (_: RuntimeException) {
            null
        } ?: return 1.0
        if (hit.type != HitResult.Type.BLOCK) return 1.0
        val loc = hit.location
        val t = ((loc.x - x0) * dx + (loc.y - y0) * dy + (loc.z - z0) * dz) / len2
        return t.coerceIn(0.0, 1.0)
    }

    override fun showChatMessage(message: String, copyText: String, copyHint: String): Boolean =
        McCompat.showChatMessage(message, copyText, copyHint)

    override fun openMusicScreen() {
        ClientMusic.open(McCompat.currentScreen())
    }

    override fun openAudioOutput(sampleRate: Int, channels: Int): AudioOutput? =
        GameAudioOutput.open(sampleRate, channels)

    override val hasGameAudio: Boolean get() = true
}

private fun soundAbsorptionOf(sound: SoundType): Float = when (sound) {
    SoundType.STONE, SoundType.DEEPSLATE, SoundType.TUFF, SoundType.CALCITE, SoundType.BASALT,
    SoundType.NETHER_BRICKS, SoundType.NETHERRACK -> 0.03f
    SoundType.METAL, SoundType.COPPER -> 0.04f
    SoundType.GLASS -> 0.05f
    SoundType.WOOD, SoundType.BAMBOO_WOOD, SoundType.CHERRY_WOOD, SoundType.NETHER_WOOD -> 0.12f
    SoundType.GRASS, SoundType.ROOTED_DIRT, SoundType.MUD -> 0.3f
    SoundType.GRAVEL, SoundType.SAND, SoundType.SOUL_SAND -> 0.35f
    SoundType.MOSS -> 0.45f
    SoundType.SNOW, SoundType.POWDER_SNOW -> 0.5f
    SoundType.WOOL -> 0.7f
    else -> 0.1f
}

private const val ENCLOSURE_RANGE = 6

private val RAY_DIRECTIONS: List<DoubleArray> = run {
    val d = 0.57735
    listOf(
        doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(-1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0),
        doubleArrayOf(0.0, -1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(0.0, 0.0, -1.0),
        doubleArrayOf(d, d, d), doubleArrayOf(d, d, -d), doubleArrayOf(-d, d, d), doubleArrayOf(-d, d, -d),
        doubleArrayOf(d, -d, d), doubleArrayOf(d, -d, -d), doubleArrayOf(-d, -d, d), doubleArrayOf(-d, -d, -d),
    )
}
