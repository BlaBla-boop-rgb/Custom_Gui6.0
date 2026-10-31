package dev.customgui;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Zeichnet RGB-Hitboxen um andere Spieler. Aufbau nach der Fabric-Doku
 * "Rendering in the World" (26.1.2): Daten werden in der Extraktions-Phase gesammelt
 * und in der Zeichen-Phase als dünne Quader (die 12 Kanten der Hitbox) ausgegeben.
 */
public final class HitboxRenderer {

    private HitboxRenderer() {
    }

    /** Wie vanilla-Debug-Boxen, aber ohne Tiefentest: sichtbar durch Wände. */
    private static final RenderPipeline THROUGH_WALLS = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("customgui", "pipeline/hitbox_through_walls"))
                    .withDepthStencilState(Optional.empty())
                    .build());

    private static final ByteBufferBuilder ALLOCATOR = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static final long START = System.nanoTime();

    private static BufferBuilder buffer;
    private static MappableRingBuffer vertexBuffer;

    /** Unveränderliche Daten für die Zeichen-Phase. */
    private record PlayerBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int index) {
    }

    private record Frame(List<PlayerBox> boxes, boolean rainbow, int fixedRgb, float speed,
                         double thickness, boolean fill, boolean throughWalls, float time) {
    }

    private static volatile Frame frame = null;

    public static void init() {
        LevelRenderEvents.END_EXTRACTION.register(HitboxRenderer::extract);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(HitboxRenderer::draw);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> close());
    }

    // ------------------------------------------------------------------ Extraktion

    private static void extract(LevelExtractionContext context) {
        CustomGuiConfig cfg = CustomGuiConfig.get();
        Minecraft mc = Minecraft.getInstance();
        if (!cfg.hitboxEnabled || mc.level == null || mc.player == null) {
            frame = null;
            return;
        }

        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        double range = CustomGuiConfig.RANGES[cfg.hitboxRange];
        double rangeSq = range * range;

        List<PlayerBox> boxes = new ArrayList<>();
        int index = 0;
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p == mc.player && !cfg.hitboxSelf) continue;
            if (p.isSpectator()) continue;
            if (p.distanceToSqr(mc.player) > rangeSq) continue;

            // Position zwischen den Ticks weich interpolieren, sonst ruckelt die Box
            Vec3 shift = p.getPosition(partial).subtract(p.position());
            AABB bb = p.getBoundingBox().move(shift);
            boxes.add(new PlayerBox(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ, index++));
        }

        if (boxes.isEmpty()) {
            frame = null;
            return;
        }

        boolean rainbow = cfg.hitboxColor == 0;
        int fixed = rainbow ? 0xFFFFFF : CustomGuiConfig.HITBOX_COLORS[cfg.hitboxColor - 1];
        float time = (System.nanoTime() - START) / 1_000_000_000f;
        frame = new Frame(List.copyOf(boxes), rainbow, fixed, CustomGuiConfig.SPEEDS[cfg.hitboxSpeed],
                CustomGuiConfig.THICKNESSES[cfg.hitboxThickness], cfg.hitboxFill, cfg.hitboxThroughWalls, time);
    }

    // ------------------------------------------------------------------ Zeichnen

    private static void draw(LevelRenderContext context) {
        Frame f = frame;
        if (f == null || f.boxes().isEmpty()) return;

        RenderPipeline pipeline = f.throughWalls() ? THROUGH_WALLS : RenderPipelines.DEBUG_FILLED_BOX;

        PoseStack matrices = context.poseStack();
        Vec3 cam = context.levelState().cameraRenderState.pos;

        if (buffer == null) {
            buffer = new BufferBuilder(ALLOCATOR, pipeline.getVertexFormatMode(), pipeline.getVertexFormat());
        }

        Matrix4fc pose = matrices.last().pose();
        for (PlayerBox b : f.boxes()) {
            // Koordinaten relativ zur Kamera -> keine Rundungsfehler bei großen Weltkoordinaten
            double minX = b.minX() - cam.x, minY = b.minY() - cam.y, minZ = b.minZ() - cam.z;
            double maxX = b.maxX() - cam.x, maxY = b.maxY() - cam.y, maxZ = b.maxZ() - cam.z;
            double t = f.thickness();

            if (f.fill()) {
                float[] c = color(f, b.index(), 0f);
                addBox(pose, buffer, minX, minY, minZ, maxX, maxY, maxZ, c[0], c[1], c[2], 0.14f);
            }

            int e = 0;
            // 4 Kanten entlang X
            for (int yi = 0; yi < 2; yi++) {
                for (int zi = 0; zi < 2; zi++) {
                    double y = yi == 0 ? minY : maxY;
                    double z = zi == 0 ? minZ : maxZ;
                    float[] c = color(f, b.index(), e++ / 12f);
                    addBox(pose, buffer, minX - t, y - t, z - t, maxX + t, y + t, z + t, c[0], c[1], c[2], 1f);
                }
            }
            // 4 Kanten entlang Z
            for (int yi = 0; yi < 2; yi++) {
                for (int xi = 0; xi < 2; xi++) {
                    double y = yi == 0 ? minY : maxY;
                    double x = xi == 0 ? minX : maxX;
                    float[] c = color(f, b.index(), e++ / 12f);
                    addBox(pose, buffer, x - t, y - t, minZ - t, x + t, y + t, maxZ + t, c[0], c[1], c[2], 1f);
                }
            }
            // 4 senkrechte Kanten
            for (int xi = 0; xi < 2; xi++) {
                for (int zi = 0; zi < 2; zi++) {
                    double x = xi == 0 ? minX : maxX;
                    double z = zi == 0 ? minZ : maxZ;
                    float[] c = color(f, b.index(), e++ / 12f);
                    addBox(pose, buffer, x - t, minY - t, z - t, x + t, maxY + t, z + t, c[0], c[1], c[2], 1f);
                }
            }
        }

        drawBuffer(Minecraft.getInstance(), pipeline);
    }

    /** RGB-Farbe für eine Kante: Regenbogen wandert über Zeit, Kante und Spieler. */
    private static float[] color(Frame f, int playerIndex, float edgeOffset) {
        if (!f.rainbow()) {
            int rgb = f.fixedRgb();
            return new float[]{((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f};
        }
        float hue = f.time() * f.speed() + edgeOffset + playerIndex * 0.13f;
        hue -= (float) Math.floor(hue);
        return hsv(hue, 0.85f, 1f);
    }

    private static float[] hsv(float h, float s, float v) {
        float scaled = h * 6f;
        int i = (int) scaled;
        float fr = scaled - i;
        float p = v * (1f - s);
        float q = v * (1f - fr * s);
        float t = v * (1f - (1f - fr) * s);
        return switch (i % 6) {
            case 0 -> new float[]{v, t, p};
            case 1 -> new float[]{q, v, p};
            case 2 -> new float[]{p, v, t};
            case 3 -> new float[]{p, q, v};
            case 4 -> new float[]{t, p, v};
            default -> new float[]{v, p, q};
        };
    }

    /** Gefüllter Quader aus 6 Flächen (24 Vertices), siehe Fabric-Doku. */
    private static void addBox(Matrix4fc m, BufferBuilder b,
                               double dx1, double dy1, double dz1, double dx2, double dy2, double dz2,
                               float r, float g, float bl, float a) {
        float minX = (float) dx1, minY = (float) dy1, minZ = (float) dz1;
        float maxX = (float) dx2, maxY = (float) dy2, maxZ = (float) dz2;

        // Front
        b.addVertex(m, minX, minY, maxZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, minY, maxZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, maxY, maxZ).setColor(r, g, bl, a);
        b.addVertex(m, minX, maxY, maxZ).setColor(r, g, bl, a);
        // Back
        b.addVertex(m, maxX, minY, minZ).setColor(r, g, bl, a);
        b.addVertex(m, minX, minY, minZ).setColor(r, g, bl, a);
        b.addVertex(m, minX, maxY, minZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, maxY, minZ).setColor(r, g, bl, a);
        // Left
        b.addVertex(m, minX, minY, minZ).setColor(r, g, bl, a);
        b.addVertex(m, minX, minY, maxZ).setColor(r, g, bl, a);
        b.addVertex(m, minX, maxY, maxZ).setColor(r, g, bl, a);
        b.addVertex(m, minX, maxY, minZ).setColor(r, g, bl, a);
        // Right
        b.addVertex(m, maxX, minY, maxZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, minY, minZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, maxY, minZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, maxY, maxZ).setColor(r, g, bl, a);
        // Top
        b.addVertex(m, minX, maxY, maxZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, maxY, maxZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, maxY, minZ).setColor(r, g, bl, a);
        b.addVertex(m, minX, maxY, minZ).setColor(r, g, bl, a);
        // Bottom
        b.addVertex(m, minX, minY, minZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, minY, minZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, minY, maxZ).setColor(r, g, bl, a);
        b.addVertex(m, minX, minY, maxZ).setColor(r, g, bl, a);
    }

    private static void drawBuffer(Minecraft client, RenderPipeline pipeline) {
        MeshData builtBuffer = buffer.buildOrThrow();
        MeshData.DrawState drawParameters = builtBuffer.drawState();
        VertexFormat format = drawParameters.format();

        GpuBuffer vertices = upload(drawParameters, format, builtBuffer);

        draw(client, pipeline, builtBuffer, drawParameters, vertices, format);

        // Ring-Buffer weiterdrehen, damit wir keinen Buffer nutzen, den die GPU noch braucht
        vertexBuffer.rotate();
        buffer = null;
    }

    private static GpuBuffer upload(MeshData.DrawState drawParameters, VertexFormat format, MeshData builtBuffer) {
        int vertexBufferSize = drawParameters.vertexCount() * format.getVertexSize();

        if (vertexBuffer == null || vertexBuffer.size() < vertexBufferSize) {
            if (vertexBuffer != null) {
                vertexBuffer.close();
            }
            vertexBuffer = new MappableRingBuffer(() -> "customgui hitboxes",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, vertexBufferSize);
        }

        CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
        try (GpuBuffer.MappedView mappedView = commandEncoder.mapBuffer(
                vertexBuffer.currentBuffer().slice(0, builtBuffer.vertexBuffer().remaining()), false, true)) {
            MemoryUtil.memCopy(builtBuffer.vertexBuffer(), mappedView.data());
        }

        return vertexBuffer.currentBuffer();
    }

    private static void draw(Minecraft client, RenderPipeline pipeline, MeshData builtBuffer,
                             MeshData.DrawState drawParameters, GpuBuffer vertices, VertexFormat format) {
        GpuBuffer indices;
        VertexFormat.IndexType indexType;

        if (pipeline.getVertexFormatMode() == VertexFormat.Mode.QUADS) {
            // Transparente Flächen sortieren
            builtBuffer.sortQuads(ALLOCATOR, RenderSystem.getProjectionType().vertexSorting());
            indices = pipeline.getVertexFormat().uploadImmediateIndexBuffer(builtBuffer.indexBuffer());
            indexType = builtBuffer.drawState().indexType();
        } else {
            RenderSystem.AutoStorageIndexBuffer shapeIndexBuffer = RenderSystem.getSequentialBuffer(pipeline.getVertexFormatMode());
            indices = shapeIndexBuffer.getBuffer(drawParameters.indexCount());
            indexType = shapeIndexBuffer.type();
        }

        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrix(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> "customgui hitboxes rendering",
                        client.getMainRenderTarget().getColorTextureView(), OptionalInt.empty(),
                        client.getMainRenderTarget().getDepthTextureView(), OptionalDouble.empty())) {
            renderPass.setPipeline(pipeline);

            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);

            renderPass.setVertexBuffer(0, vertices);
            renderPass.setIndexBuffer(indices, indexType);

            renderPass.drawIndexed(0, 0, drawParameters.indexCount(), 1);
        }

        builtBuffer.close();
    }

    private static void close() {
        ALLOCATOR.close();
        if (vertexBuffer != null) {
            vertexBuffer.close();
            vertexBuffer = null;
        }
    }
}
