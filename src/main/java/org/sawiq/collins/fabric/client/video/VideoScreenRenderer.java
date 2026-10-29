package org.sawiq.collins.fabric.client.video;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Brightness;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.sawiq.collins.fabric.client.config.CollinsClientConfig;
import org.sawiq.collins.fabric.client.state.ScreenState;

public final class VideoScreenRenderer {

    private static final double EPS = 0.01; // насколько “над блоком” рисуем

    private VideoScreenRenderer() {}

    public static void init() {
        // 26.3: the level renderer no longer hands out a MultiBufferSource
        // during END_MAIN. World geometry is "submitted" to a
        // SubmitNodeCollector while the frame is being collected and drawn by
        // Mojang later in the frame, so we hook COLLECT_SUBMITS and hand our
        // quads over as custom geometry.
        LevelRenderEvents.COLLECT_SUBMITS.register(VideoScreenRenderer::onCollectSubmits);
    }

    private static void onCollectSubmits(LevelRenderContext ctx) {
        PoseStack matrices = ctx.poseStack();
        SubmitNodeCollector collector = ctx.submitNodeCollector();
        if (matrices == null || collector == null) return;

        Minecraft client = Minecraft.getInstance();
        // 26.3: the per-frame camera lives in the level render state.
        CameraRenderState cameraState = ctx.levelState() != null ? ctx.levelState().cameraRenderState : null;
        Vec3 cam = cameraState != null && cameraState.pos != null
                ? cameraState.pos
                : ctx.gameRenderer().mainCamera().position();

        if (!CollinsClientConfig.get().renderVideo) {
            return;
        }

        matrices.pushPose();
        matrices.translate(-cam.x, -cam.y, -cam.z);

        for (VideoScreen screen : VideoScreenManager.all()) {
            ScreenState st = screen.state();
            if (!VideoScreenManager.isCompatibleWithCurrentWorld(st, client)) continue;
            screen.renderPlayback();
            // Render the screen only if a video texture is currently
            // bound. Drawing a flat placeholder when no video is playing
            // (the original 26.1 port did this) leaves a stale dark
            // panel hanging in the world long after `/collins stop`,
            // which players reasonably read as "the screen never goes
            // away even when nothing is playing".
            if (screen.hasTexture()) {
                drawScreen(matrices, collector, cam, st, screen.textureId());
            }
        }

        matrices.popPose();
    }

    private static void drawScreen(PoseStack matrices,
                                   SubmitNodeCollector collector,
                                   Vec3 cam,
                                   ScreenState s,
                                   Identifier textureId) {

        RenderType layer = RenderTypes.entityCutoutZOffset(textureId);
        // The collector copies the current pose at submit time and calls the
        // renderer later in the frame with that pose and a buffer for `layer`.
        collector.submitCustomGeometry(matrices, layer, (entry, vc) -> emitScreen(entry, vc, cam, s));
    }

    private static void emitScreen(PoseStack.Pose entry,
                                   VertexConsumer vc,
                                   Vec3 cam,
                                   ScreenState s) {
        int minX = s.minX(), maxX = s.maxX();
        int minY = s.minY(), maxY = s.maxY();
        int minZ = s.minZ(), maxZ = s.maxZ();

        int overlay = OverlayTexture.NO_OVERLAY;
        int light = Brightness.FULL_BRIGHT.pack();

        if (s.axis() == 0) { // XY, Z фиксирован
            double zPlane = minZ + 0.5;
            boolean frontIsNegative = cam.z < zPlane;
            double z = frontIsNegative ? (minZ - EPS) : ((maxZ + 1.0) + EPS);

            double x1 = minX,     y1 = minY;
            double x2 = maxX + 1, y2 = minY;
            double x3 = maxX + 1, y3 = maxY + 1;
            double x4 = minX,     y4 = maxY + 1;

            float nx = 0, ny = 0, nz = (float) (frontIsNegative ? -1.0 : +1.0);

            quadTwoSidedNoMirrorU(vc, entry, frontIsNegative,
                    x2, y1, z,  x1, y2, z,  x4, y3, z,  x3, y4, z,
                    overlay, light, nx, ny, nz);

        } else if (s.axis() == 1) { // XZ, Y фиксирован
            double yPlane = minY + 0.5;
            boolean frontIsNegative = cam.y < yPlane;
            double y = frontIsNegative ? (minY - EPS) : ((maxY + 1.0) + EPS);

            double x1 = minX,     z1 = minZ;
            double x2 = maxX + 1, z2 = minZ;
            double x3 = maxX + 1, z3 = maxZ + 1;
            double x4 = minX,     z4 = maxZ + 1;

            float nx = 0, ny = (float) (frontIsNegative ? -1.0 : +1.0), nz = 0;

            quadTwoSidedNoMirrorU(vc, entry, frontIsNegative,
                    x1, y, z1,  x2, y, z2,  x3, y, z3,  x4, y, z4,
                    overlay, light, nx, ny, nz);

        } else { // axis == 2, YZ, X фиксирован
            double xPlane = minX + 0.5;
            boolean frontIsNegative = cam.x < xPlane;
            double x = frontIsNegative ? (minX - EPS) : ((maxX + 1.0) + EPS);

            double y1 = minY,     z1 = minZ;
            double y2 = minY,     z2 = maxZ + 1;
            double y3 = maxY + 1, z3 = maxZ + 1;
            double y4 = maxY + 1, z4 = minZ;

            float nx = (float) (frontIsNegative ? -1.0 : +1.0), ny = 0, nz = 0;

            quadTwoSidedNoMirrorU(vc, entry, frontIsNegative,
                    x, y1, z1,  x, y2, z2,  x, y3, z3,  x, y4, z4,
                    overlay, light, nx, ny, nz);
        }
    }

    private static void quadTwoSidedNoMirrorU(VertexConsumer vc, PoseStack.Pose e,
                                              boolean flipUFront,
                                              double x1, double y1, double z1,
                                              double x2, double y2, double z2,
                                              double x3, double y3, double z3,
                                              double x4, double y4, double z4,
                                              int overlay, int light,
                                              float nx, float ny, float nz) {

        if (!flipUFront) {
            v(vc, e, x1, y1, z1, 0, 1, overlay, light, nx, ny, nz);
            v(vc, e, x2, y2, z2, 1, 1, overlay, light, nx, ny, nz);
            v(vc, e, x3, y3, z3, 1, 0, overlay, light, nx, ny, nz);
            v(vc, e, x4, y4, z4, 0, 0, overlay, light, nx, ny, nz);

            v(vc, e, x1, y1, z1, 1, 1, overlay, light, -nx, -ny, -nz);
            v(vc, e, x2, y2, z2, 0, 1, overlay, light, -nx, -ny, -nz);
            v(vc, e, x3, y3, z3, 0, 0, overlay, light, -nx, -ny, -nz);
            v(vc, e, x4, y4, z4, 1, 0, overlay, light, -nx, -ny, -nz);
            return;
        }

        v(vc, e, x1, y1, z1, 1, 1, overlay, light, nx, ny, nz);
        v(vc, e, x2, y2, z2, 0, 1, overlay, light, nx, ny, nz);
        v(vc, e, x3, y3, z3, 0, 0, overlay, light, nx, ny, nz);
        v(vc, e, x4, y4, z4, 1, 0, overlay, light, nx, ny, nz);

        v(vc, e, x1, y1, z1, 0, 1, overlay, light, -nx, -ny, -nz);
        v(vc, e, x2, y2, z2, 1, 1, overlay, light, -nx, -ny, -nz);
        v(vc, e, x3, y3, z3, 1, 0, overlay, light, -nx, -ny, -nz);
        v(vc, e, x4, y4, z4, 0, 0, overlay, light, -nx, -ny, -nz);
    }

    private static void v(VertexConsumer vc,
                          PoseStack.Pose entry,
                          double x, double y, double z,
                          float u, float v,
                          int overlay, int light,
                          float nx, float ny, float nz) {

        Vector3f p = new Vector3f((float) x, (float) y, (float) z);
        entry.pose().transformPosition(p);

        Vector3f n = entry.transformNormal(nx, ny, nz, new Vector3f());
        n.normalize();

        int color = 0xFFFFFFFF;
        // 26.1 / Mojang: VertexConsumer dropped the combined `vertex(...)`
        // overload in favour of a chained API. Order matches the standard
        // entity vertex format expected by RenderTypes.entityCutoutZOffset.
        vc.addVertex(p.x, p.y, p.z)
                .setColor(color)
                .setUv(u, v)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(n.x, n.y, n.z);
    }
}
