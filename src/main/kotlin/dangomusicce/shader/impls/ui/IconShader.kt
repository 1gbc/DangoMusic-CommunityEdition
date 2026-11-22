package dangomusicce.shader.impls.ui

import dangomusicce.shader.GlShader
import dangomusicce.ui.LayoutBounds
import dangomusicce.ui.Size
import dangomusicce.ui.VectorIcon
import dangomusicce.utils.Color
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL20
import org.lwjgl.util.vector.Matrix4f

class IconShader : GlShader("ui/shape.vert","ui/icon.frag") {
    private val matrix=BufferUtils.createFloatBuffer(16)
    private val segments=BufferUtils.createFloatBuffer(VectorIcon.MAX_SEGMENTS*4)
    private var segmentLocation=-1
    override fun uniformsFields()=arrayOf("u_Transform","u_ScreenSize","u_Bounds","u_Color","u_Opacity",
        "u_StrokeWidth","u_Filled","u_SegmentCount","u_Segments[0]")
    fun setup(transform: Matrix4f, screen: Size, bounds: LayoutBounds, icon: VectorIcon, color: Color, opacity: Float) {
        matrix.clear(); transform.store(matrix); matrix.flip(); setUniformMat4("u_Transform",matrix)
        setUniform2f("u_ScreenSize",screen.width,screen.height)
        setUniform4f("u_Bounds",bounds.x,bounds.y,bounds.width,bounds.height)
        setUniformColor("u_Color",color); setUniform1f("u_Opacity",opacity)
        setUniform1f("u_StrokeWidth",icon.strokeWidth); setUniform1i("u_Filled",if(icon.filled) 1 else 0)
        setUniform1i("u_SegmentCount",icon.segments.size/4)
        if(segmentLocation<0) segmentLocation=GL20.glGetUniformLocation(getProgramID(),"u_Segments[0]")
        segments.clear(); segments.put(icon.segments); segments.flip(); GL20.glUniform4(segmentLocation,segments)
    }
}
