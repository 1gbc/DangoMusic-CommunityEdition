import org.lwjgl.util.vector.Matrix4f;
import org.lwjgl.util.vector.Vector3f;
import org.lwjgl.util.vector.Vector4f;

import java.util.Arrays;

/** Check the projected lyric bounds without opening Minecraft or creating a GL context. */
public class WorldLyricCameraCheck {
    private static void check(float pitch, float yaw, float roll, float aspect, float fov, float width,
                              float top, float bottom, float floor, boolean facePlayer, boolean fit) throws Exception {
        check(pitch, yaw, roll, aspect, fov, width, top, bottom, floor, facePlayer, fit, 0f, 0f);
    }

    private static void check(float pitch, float yaw, float roll, float aspect, float fov, float width,
                              float top, float bottom, float floor, boolean facePlayer, boolean fit,
                              float offsetX, float offsetY) throws Exception {
        Matrix4f view = new Matrix4f();
        view.rotate((float)Math.toRadians(roll), new Vector3f(0, 0, 1));
        view.rotate((float)Math.toRadians(pitch), new Vector3f(1, 0, 0));
        view.rotate((float)Math.toRadians(yaw + 180), new Vector3f(0, 1, 0));
        view.translate(new Vector3f(0.25f, -1.4f, 4f));
        Matrix4f projection = new Matrix4f();
        projection.m11 = (float)(1 / Math.tan(Math.toRadians(fov / 2)));
        projection.m00 = projection.m11 / aspect;
        projection.m20 = 0.07f;
        projection.m21 = -0.05f;
        projection.m22 = -1.0003f;
        projection.m23 = -1;
        projection.m32 = -0.10f;
        projection.m33 = 0;

        Class<?> cameraType;
        try {
            cameraType = Class.forName("dangomusicce.gfx.WorldLyricCamera");
        } catch (ClassNotFoundException missingCamera) {
            throw new AssertionError("Multiple 3D lyrics need placement from the actual perspective camera", missingCamera);
        }
        Object camera = cameraType.getConstructor(Matrix4f.class, Matrix4f.class).newInstance(view, projection);
        var place = Arrays.stream(cameraType.getMethods())
                .filter(method -> method.getName().startsWith("place$") && method.getParameterCount() == 9)
                .findFirst().orElseThrow();
        Object pose = place.invoke(camera, 10f, 0.1f, width, top, bottom, floor, facePlayer, offsetX, offsetY);
        if (pose == null) throw new AssertionError("A valid camera must place the lyrics");

        var point = pose.getClass().getMethod("point", float.class, float.class, float.class);
        float scale = (Float)pose.getClass().getMethod("getScale").invoke(pose);
        if (fit ? scale >= 0.1f : scale != 0.1f) {
            throw new AssertionError("Only the oversized layout should shrink. Actual scale " + scale);
        }
        Vector3f origin = (Vector3f)point.invoke(pose, 0f, 0f, 1f);
        Vector4f center = project(origin, view, projection);
        if ((offsetX == 0f || !fit) && Math.abs(center.x / center.w + 0.5f - offsetX) > 0.001f) {
            throw new AssertionError("The left anchor must apply its available horizontal lag");
        }
        Matrix4f worldPush = (Matrix4f)pose.getClass().getMethod("transform", float.class, float.class, float.class)
                .invoke(pose, 0f, 0f, 1f);
        for (float x : new float[]{-width, width}) {
            for (float y : new float[]{top, bottom}) {
                Vector3f p = (Vector3f)point.invoke(pose, x, y, 1f);
                Vector4f renderPoint = Matrix4f.transform(worldPush, new Vector4f(x, y, 0, 1), null);
                if (Math.abs(p.x - renderPoint.x) > 0.001f || Math.abs(p.y - renderPoint.y) > 0.001f
                        || Math.abs(p.z - renderPoint.z) > 0.001f) {
                    throw new AssertionError("Placement and wall probes must use the same transform as the renderer");
                }
                if (p.y < floor - 0.001f) {
                    throw new AssertionError("Above ground should lift the full stack when there is space on screen. Found " + p.y);
                }
                Vector4f corner = project(p, view, projection);
                float nx = corner.x / corner.w;
                float ny = corner.y / corner.w;
                if (nx < -0.941f || nx > -0.049f || Math.abs(ny) > 0.901f) {
                    throw new AssertionError("Every lyric corner should be on the left within the screen. Found " + nx + ", " + ny);
                }
                Vector4f closer = project((Vector3f)point.invoke(pose, x, y, 0.35f), view, projection);
                if (Math.abs(nx - closer.x / closer.w) > 0.001f || Math.abs(ny - closer.y / closer.w) > 0.001f) {
                    throw new AssertionError("Avoiding geometry must preserve the projected position and size");
                }
            }
        }
    }

    private static Vector4f project(Vector3f point, Matrix4f view, Matrix4f projection) {
        Vector4f cameraPoint = Matrix4f.transform(view, new Vector4f(point.x, point.y, point.z, 1), null);
        return Matrix4f.transform(projection, cameraPoint, null);
    }

    private static float[] cameraRelativeAxes(float yaw, float pitch, float roll, boolean tilted) throws Exception {
        Matrix4f view = new Matrix4f().rotate((float)Math.toRadians(roll), new Vector3f(0, 0, 1))
            .rotate((float)Math.toRadians(pitch), new Vector3f(1, 0, 0))
            .rotate((float)Math.toRadians(yaw + 180), new Vector3f(0, 1, 0));
        Matrix4f projection = new Matrix4f();
        projection.m00 = 0.85f;
        projection.m11 = 1.45f;
        projection.m23 = -1f;
        projection.m33 = 0f;

        Class<?> camera = Class.forName("dangomusicce.gfx.WorldLyricCamera");
        Object instance = camera.getConstructor(Matrix4f.class, Matrix4f.class).newInstance(view, projection);
        var place = Arrays.stream(camera.getMethods())
                .filter(m -> m.getName().startsWith("place$") && m.getParameterCount() == 9).findFirst().orElseThrow();
        Object pose = place.invoke(instance, 10f, 0.1f, 25f, -18f, 24f, Float.NaN, tilted, 0f, 0f);
        var point = pose.getClass().getMethod("point", float.class, float.class, float.class);
        Vector3f center = (Vector3f) point.invoke(pose, 0f, 0f, 1f);
        Vector3f right = (Vector3f) point.invoke(pose, 1f, 0f, 1f);
        Vector3f down = (Vector3f) point.invoke(pose, 0f, 1f, 1f);
        Vector4f r = Matrix4f.transform(view, new Vector4f(right.x - center.x, right.y - center.y, right.z - center.z, 0f), null);
        Vector4f d = Matrix4f.transform(view, new Vector4f(down.x - center.x, down.y - center.y, down.z - center.z, 0f), null);
        return new float[]{r.x / r.length(), r.y / r.length(), r.z / r.length(),
            d.x / d.length(), d.y / d.length(), d.z / d.length()};
    }

    private static void checkFixedTilt() throws Exception {
        float[] rest = cameraRelativeAxes(0f, 0f, 0f, true);
        if (rest[2] > -0.2f) throw new AssertionError("The left stack should keep its perspective tilt");
        for (float pitch : new float[]{-90f, -72f, -30f, 44f, 81f, 90f}) {
            float[] moved = cameraRelativeAxes(173f, pitch, 12f, true);
            for (int i = 0; i < rest.length; i++) {
                if (Math.abs(rest[i] - moved[i]) > 0.001f) {
                    throw new AssertionError("Multiline tilt shifted with the camera at pitch " + pitch);
                }
            }
        }
        float[] frontal = cameraRelativeAxes(-143f, 57f, -10f, false);
        if (Math.abs(frontal[2]) > 0.001f || Math.abs(frontal[5]) > 0.001f) {
            throw new AssertionError("Turning off the face-player option keeps the screen-facing layout");
        }
    }

    private static void checkFloorLift(float pitch, float z, float y, float requested, boolean clipped) throws Exception {
        Matrix4f view = new Matrix4f().rotate((float)Math.toRadians(pitch), new Vector3f(1, 0, 0));
        Matrix4f projection = new Matrix4f();
        projection.m11 = (float)(1 / Math.tan(Math.toRadians(35)));
        projection.m23 = -1f;
        projection.m33 = 0f;
        Class<?> type = Class.forName("dangomusicce.gfx.WorldLyricCamera");
        Object camera = type.getConstructor(Matrix4f.class, Matrix4f.class).newInstance(view, projection);
        var clamp = Arrays.stream(type.getMethods()).filter(m -> m.getName().startsWith("limitUpwardLift$"))
            .findFirst().orElseThrow(() -> new AssertionError("Ground lift needs to respect the rendered camera"));
        double sent = (Double)clamp.invoke(camera, 0.0, (double)y, (double)z, (double)requested);
        if (sent < 0.0 || (clipped ? sent >= requested : Math.abs(sent - requested) > 1e-5)) {
            throw new AssertionError("Ground lift at pitch " + pitch + " returned " + sent);
        }
        Vector4f point = project(new Vector3f(0, (float)(y + sent), z), view, projection);
        if (point.w <= 0f || Math.abs(point.y / point.w) > 0.851f) {
            throw new AssertionError("The corrected anchor must be visible at pitch " + pitch);
        }
    }

    private static void checkFloatingLine(float viewPitch, float linePitch, float fov, float strength, boolean needsCorrection)
            throws Exception {
        Matrix4f view = new Matrix4f().translate(new Vector3f(0f, 0f, -0.1f))
            .rotate((float)Math.toRadians(viewPitch), new Vector3f(1, 0, 0))
            .rotate((float)Math.PI, new Vector3f(0, 1, 0));
        Matrix4f projection = new Matrix4f();
        projection.m11 = (float)(1 / Math.tan(Math.toRadians(fov / 2)));
        projection.m00 = projection.m11 / (16f / 9f);
        projection.m22 = -1.0003f;
        projection.m23 = -1f;
        projection.m32 = -0.10f;
        projection.m33 = 0f;

        Class<?> type = Class.forName("dangomusicce.gfx.WorldLyricCamera");
        Object camera = type.getConstructor(Matrix4f.class, Matrix4f.class).newInstance(view, projection);
        var fit = Arrays.stream(type.getMethods()).filter(m -> m.getName().startsWith("fitFloatingLine$"))
            .findFirst().orElseThrow();
        double direction = Math.toRadians(linePitch);
        float halfWidth = 43f;
        float top = -11f;
        float bottom = 17f;
        Matrix4f corrected = (Matrix4f)fit.invoke(camera, 0.0, -10 * Math.sin(direction), 10 * Math.cos(direction),
            0f, linePitch, 0.046f, halfWidth, top, bottom, 10f, Float.NaN, strength);
        if ((corrected != null) != needsCorrection) {
            throw new AssertionError("Unexpected correction at view pitch " + viewPitch + ", line pitch " + linePitch);
        }
        if (corrected == null) return;
        Matrix4f clipTransform = Matrix4f.mul(projection, Matrix4f.mul(view, corrected, null), null);
        for (float x : new float[]{-halfWidth, halfWidth}) {
            for (float y : new float[]{top, bottom}) {
                Vector4f clip = Matrix4f.transform(clipTransform, new Vector4f(x, y, 0f, 1f), null);
                if (clip.w <= 0f || Math.abs(clip.x) > clip.w * 0.981f ||
                        Math.abs(clip.y) > clip.w * 0.981f || Math.abs(clip.z) > clip.w) {
                    throw new AssertionError("Single-line glyph bounds are still outside the viewport: " + clip);
                }
            }
        }
    }

    private static void checkTurnLag() throws Exception {
        Class<?> module = Class.forName("dangomusicce.module.impls.music.LyricsModule");
        Object lyrics = module.getConstructor().newInstance();
        Class<?> angles = Class.forName("dangomusicce.gfx.ViewAngles");
        var angle = angles.getConstructor(float.class, float.class);
        var tick = module.getDeclaredMethod("leftStackTurn", angles, long.class);
        tick.setAccessible(true);
        long time = 1_000_000_000L;
        tick.invoke(lyrics, angle.newInstance(179f, 0f), time);
        Object turned = tick.invoke(lyrics, angle.newInstance(-179f, 10f), time += 16_000_000L);
        var x = turned.getClass().getMethod("getFirst");
        var y = turned.getClass().getMethod("getSecond");
        float lagX = (Float)x.invoke(turned);
        float lagY = (Float)y.invoke(turned);
        if (lagX > -0.01f || lagX < -0.04f || lagY < 0.05f || lagY > 0.20f) {
            throw new AssertionError("Yaw wrap and pitch changes must give a bounded lag: " + lagX + ", " + lagY);
        }
        Object still = turned;
        for (int frame = 0; frame < 40; frame++) {
            still = tick.invoke(lyrics, angle.newInstance(-179f, 10f), time += 16_000_000L);
        }
        if (Math.abs((Float)x.invoke(still)) > 0.002f || Math.abs((Float)y.invoke(still)) > 0.002f) {
            throw new AssertionError("The stack must settle near its resting position when the camera stops");
        }
        Object pole = tick.invoke(lyrics, angle.newInstance(0f, 90f), time + 16_000_000L);
        if (Math.abs((Float)x.invoke(pole)) > 0.002f) {
            throw new AssertionError("Yaw changes at the vertical pole should not jerk the stack sideways");
        }
    }

    private static void checkFloatingSizeDuringTurn(float top, float bottom, float reach) throws Exception {
        Matrix4f projection = new Matrix4f();
        projection.m11 = 1.73f;
        projection.m00 = projection.m11 / (16f / 9f);
        projection.m22 = -1.0003f;
        projection.m23 = -1f;
        projection.m32 = -0.10f;
        projection.m33 = 0f;
        var type = Class.forName("dangomusicce.gfx.WorldLyricCamera");
        var fit = Arrays.stream(type.getMethods()).filter(m -> m.getName().startsWith("fitFloatingLine$"))
                .findFirst().orElseThrow();
        float distance = 10f * reach;
        float scale = 0.046f * reach;
        float halfWidth = 80f;
        float restWidth = 2 * halfWidth * scale * projection.m00 / distance;
        float previousWidth = restWidth;
        for (int frame = 0; frame <= 180; frame++) {
            // The spring can lag behind a quick pitch reversal by several rendered frames.
            float pitch = frame <= 90 ? frame : 180 - frame;
            float guard = Math.max(0f, Math.min(1f, (pitch - 60f) / 20f));
            guard = guard * guard * (3f - 2f * guard);
            Matrix4f view = new Matrix4f().rotate((float)Math.toRadians(pitch), new Vector3f(1, 0, 0))
                    .rotate((float)Math.PI, new Vector3f(0, 1, 0));
            Object camera = type.getConstructor(Matrix4f.class, Matrix4f.class).newInstance(view, projection);
            Matrix4f pose = (Matrix4f)fit.invoke(camera, 0.0, 0.0, (double)distance, 0f, 0f, scale,
                    halfWidth, top, bottom, distance, Float.NaN, guard);
            if (pose == null) {
                pose = new Matrix4f();
                pose.m00 = -scale;
                pose.m11 = -scale;
                pose.m32 = distance;
            }
            Matrix4f clip = Matrix4f.mul(projection, Matrix4f.mul(view, pose, null), null);
            for (float row : new float[]{top, (top + bottom) / 2f, bottom}) {
                Vector4f left = Matrix4f.transform(clip, new Vector4f(-halfWidth, row, 0f, 1f), null);
                Vector4f right = Matrix4f.transform(clip, new Vector4f(halfWidth, row, 0f, 1f), null);
                float width = Math.abs(right.x / right.w - left.x / left.w);
                if (left.w <= 0f || right.w <= 0f || !Float.isFinite(width) || width > restWidth / 0.899f) {
                    throw new AssertionError("3D lyrics grew too large while turning: pitch " + pitch + ", width " + width);
                }
                if (guard == 1f && (Math.abs(left.y / left.w) > 0.981f || Math.abs(left.x / left.w) > 0.981f ||
                        Math.abs(right.x / right.w) > 0.981f)) {
                    throw new AssertionError("3D lyrics left the viewport at steep pitch " + pitch);
                }
                if (row == (top + bottom) / 2f) {
                    if (Math.abs(width - previousWidth) > restWidth * 0.025f) {
                        throw new AssertionError("3D lyric size jumped between frames at pitch " + pitch);
                    }
                    previousWidth = width;
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        checkFixedTilt();
        checkFloatingLine(0f, 0f, 70f, 1f, false);
        checkFloatingLine(52f, 52f, 48f, 0f, false);
        checkFloatingLine(90f, 0f, 70f, 1f, true);
        checkFloatingLine(90f, 59f, 48f, 1f, true);
        checkFloatingLine(-90f, 0f, 70f, 1f, true);
        checkFloatingSizeDuringTurn(-11f, 17f, 1f);
        checkFloatingSizeDuringTurn(-33f, 35f, 1f);
        checkFloatingSizeDuringTurn(-33f, 35f, 0.35f);
        checkTurnLag();
        check(30, 0, 8, 16f / 9, 70, 20, -18, 24, Float.NaN, true, false, 0.13f, 0.17f);
        check(88, -117, -8, 9f / 16, 42, 85, -130, 60, Float.NaN, false, true, -0.16f, -0.20f);
        checkFloorLift(75, -2.6f, -9.65f, 9.7f, true);
        checkFloorLift(-50, -6.43f, 7.66f, 45f, true);
        checkFloorLift(-70, -3.42f, 9.4f, 9.0f, false);
        checkFloorLift(0, -10, -2, 2, false);
        check(0, 0, 0, 16f / 9, 70, 25, -18, 24, Float.NaN, true, false);
        check(0, 0, 0, 16f / 9, 70, 25, -18, 24, 2.2f, true, false);
        check(47, 178, -8, 21f / 9, 91, 25, -18, 24, Float.NaN, false, false);
        check(-90, 293, 12, 4f / 3, 55, 111, -68, 53, Float.NaN, true, true);
        check(88, -117, -8, 9f / 16, 42, 85, -130, 60, Float.NaN, false, true);
        System.out.println("World lyric projection keeps the stack left through camera motion, different aspect ratios, and ground and block avoidance");
    }
}
