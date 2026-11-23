package dangomusicce.platform

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL33

/** 游戏和字体模组拥有的贴图：临时修改后必须恢复真实原值，不能假定默认通道布局。 */
class BorrowedGlyphTextures : AutoCloseable {
    private var previousTexture = Gl.currentTexture()
    private val originals = LinkedHashMap<Int, IntArray>()
    private val swizzled = HashSet<Int>()

    fun bind(texture: Int, gray: Boolean) {
        Gl.bindTexture(texture)
        if (originals.containsKey(texture)) return
        val values = IntArray(6)
        for (i in 0 until 4) values[i] = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, SWIZZLE[i])
        if (values.take(4).any { it !in VALID_SWIZZLE } || values.take(4).all { it == GL11.GL_ZERO }) {
            for (i in 0 until 4) values[i] = IDENTITY[i]
        }
        values[4] = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER)
        values[5] = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER)
        originals[texture] = values
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
        if (gray) {
            val graySwizzle = intArrayOf(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_RED)
            for (i in 0 until 4) GL11.glTexParameteri(GL11.GL_TEXTURE_2D, SWIZZLE[i], graySwizzle[i])
            swizzled += texture
        }
    }

    companion object {
        private val SWIZZLE = intArrayOf(
            GL33.GL_TEXTURE_SWIZZLE_R,
            GL33.GL_TEXTURE_SWIZZLE_G,
            GL33.GL_TEXTURE_SWIZZLE_B,
            GL33.GL_TEXTURE_SWIZZLE_A,
        )
        private val IDENTITY = intArrayOf(GL11.GL_RED, GL11.GL_GREEN, GL11.GL_BLUE, GL11.GL_ALPHA)
        private val VALID_SWIZZLE = setOf(GL11.GL_RED, GL11.GL_GREEN, GL11.GL_BLUE, GL11.GL_ALPHA, GL11.GL_ZERO, GL11.GL_ONE)

        private var pass: BorrowedGlyphTextures? = null

        fun forPass(): BorrowedGlyphTextures = pass ?: BorrowedGlyphTextures().also { pass = it }

        fun endPass() {
            val p = pass ?: return
            pass = null
            p.previousTexture = Gl.currentTexture()
            p.close()
        }
    }

    override fun close() {
        Imm.flush()
        try {
            for ((texture, values) in originals) {
                Gl.bindTexture(texture)
                if (texture in swizzled) for (i in 0 until 4) GL11.glTexParameteri(GL11.GL_TEXTURE_2D, SWIZZLE[i], values[i])
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, values[4])
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, values[5])
            }
        } finally {
            Gl.bindTexture(previousTexture)
        }
    }
}
