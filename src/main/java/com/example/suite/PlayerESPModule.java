package com.example.suite;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Draws glowing neon 3D bounding boxes around other players, visible through walls.
 *
 * Minecraft 1.21.6+ has no immediate-mode line drawing, so the box edges are built as thin
 * 3D bars (six quads each) and drawn through a custom pipeline with the depth test disabled.
 * The thickness grows with distance so the outline stays about the same width on screen.
 */
public final class PlayerESPModule extends ClientModule {

    private static final RenderPipeline ESP_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath(ModuleManager.MOD_ID, "pipeline/esp_through_walls"))
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withCull(false)
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .build()
    );

    private static final ByteBufferBuilder ALLOCATOR = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
    private static final Vector4f COLOR_MODULATOR = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

    /** Bar half-width at one pixel's worth of world size per metre; multiplied per pass. */
    private static final double PIXEL_WORLD_SIZE_PER_METRE = 0.0013;
    private static final double MIN_HALF_WIDTH = 0.008;

    // Widest and faintest first, thin bright core last.
    private static final float[] PASS_WIDTH_FACTORS = {6.0f, 3.0f, 1.0f};
    private static final int[] PASS_ALPHAS = {26, 64, 255};

    /** RGB without alpha. Neon cyan. Use 0xFF0D1A for neon red. */
    private int rgb = 0x00FFFF;
    private double maxDistance = 256.0;

    private BufferBuilder buffer;
    private MappableRingBuffer vertexBuffer;

    public PlayerESPModule() {
        super("Player ESP", true);
    }

    public void setColorRgb(int rgb) {
        this.rgb = rgb & 0xFFFFFF;
    }

    public void setMaxDistance(double maxDistance) {
        this.maxDistance = Math.max(8.0, maxDistance);
    }

    @Override
    public void init() {
        // Fabric's 1.21.10+ world render events replaced WorldRenderEvents.LAST.
        // BEFORE_TRANSLUCENT is the event the official docs use for custom pipelines.
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(this::render);
    }

    private void render(WorldRenderContext context) {
        if (!isEnabled() || mc.level == null || mc.player == null) {
            return;
        }

        Vec3 camera = context.worldState().cameraRenderState.pos;
        Matrix4f pose = context.matrices().last().pose();
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        double maxDistanceSq = maxDistance * maxDistance;

        for (Player player : mc.level.players()) {
            if (player == mc.player || !player.isAlive() || player.isSpectator()) {
                continue;
            }
            if (mc.player.distanceToSqr(player) > maxDistanceSq) {
                continue;
            }

            // Interpolated position for this frame.
            double x = Mth.lerp(partialTick, player.xo, player.getX());
            double y = Mth.lerp(partialTick, player.yo, player.getY());
            double z = Mth.lerp(partialTick, player.zo, player.getZ());

            // Current bounding box shifted to the interpolated position, then made camera-relative.
            AABB box = player.getBoundingBox().move(x - player.getX(), y - player.getY(), z - player.getZ());
            double minX = box.minX - camera.x;
            double minY = box.minY - camera.y;
            double minZ = box.minZ - camera.z;
            double maxX = box.maxX - camera.x;
            double maxY = box.maxY - camera.y;
            double maxZ = box.maxZ - camera.z;

            double centerX = (minX + maxX) * 0.5;
            double centerY = (minY + maxY) * 0.5;
            double centerZ = (minZ + maxZ) * 0.5;
            double distance = Math.sqrt(centerX * centerX + centerY * centerY + centerZ * centerZ);
            double coreHalfWidth = Math.max(MIN_HALF_WIDTH, distance * PIXEL_WORLD_SIZE_PER_METRE);

            if (buffer == null) {
                buffer = new BufferBuilder(ALLOCATOR, ESP_PIPELINE.getVertexFormatMode(), ESP_PIPELINE.getVertexFormat());
            }

            for (int pass = 0; pass < PASS_ALPHAS.length; pass++) {
                float half = (float) (coreHalfWidth * PASS_WIDTH_FACTORS[pass]);
                int argb = (PASS_ALPHAS[pass] << 24) | rgb;
                addBoxEdges(pose, (float) minX, (float) minY, (float) minZ,
                        (float) maxX, (float) maxY, (float) maxZ, half, argb);
            }
        }

        if (buffer == null) {
            return;
        }

        drawThroughWalls(mc);
    }

    /** Adds the 12 edges of the box [min, max] as thin bars of half-width {@code t}. */
    private void addBoxEdges(Matrix4f pose, float x1, float y1, float z1, float x2, float y2, float z2,
                             float t, int argb) {
        // Four edges parallel to X.
        for (int yi = 0; yi < 2; yi++) {
            for (int zi = 0; zi < 2; zi++) {
                float y = yi == 0 ? y1 : y2;
                float z = zi == 0 ? z1 : z2;
                addBar(pose, x1 - t, y - t, z - t, x2 + t, y + t, z + t, argb);
            }
        }
        // Four edges parallel to Y.
        for (int xi = 0; xi < 2; xi++) {
            for (int zi = 0; zi < 2; zi++) {
                float x = xi == 0 ? x1 : x2;
                float z = zi == 0 ? z1 : z2;
                addBar(pose, x - t, y1 - t, z - t, x + t, y2 + t, z + t, argb);
            }
        }
        // Four edges parallel to Z.
        for (int xi = 0; xi < 2; xi++) {
            for (int yi = 0; yi < 2; yi++) {
                float x = xi == 0 ? x1 : x2;
                float y = yi == 0 ? y1 : y2;
                addBar(pose, x - t, y - t, z1 - t, x + t, y + t, z2 + t, argb);
            }
        }
    }

    /** Adds a solid axis-aligned bar: six faces, four vertices each. */
    private void addBar(Matrix4f pose, float x1, float y1, float z1, float x2, float y2, float z2, int argb) {
        // Bottom (y1)
        vertex(pose, x1, y1, z1, argb);
        vertex(pose, x2, y1, z1, argb);
        vertex(pose, x2, y1, z2, argb);
        vertex(pose, x1, y1, z2, argb);
        // Top (y2)
        vertex(pose, x1, y2, z1, argb);
        vertex(pose, x1, y2, z2, argb);
        vertex(pose, x2, y2, z2, argb);
        vertex(pose, x2, y2, z1, argb);
        // North (z1)
        vertex(pose, x1, y1, z1, argb);
        vertex(pose, x1, y2, z1, argb);
        vertex(pose, x2, y2, z1, argb);
        vertex(pose, x2, y1, z1, argb);
        // South (z2)
        vertex(pose, x1, y1, z2, argb);
        vertex(pose, x2, y1, z2, argb);
        vertex(pose, x2, y2, z2, argb);
        vertex(pose, x1, y2, z2, argb);
        // West (x1)
        vertex(pose, x1, y1, z1, argb);
        vertex(pose, x1, y1, z2, argb);
        vertex(pose, x1, y2, z2, argb);
        vertex(pose, x1, y2, z1, argb);
        // East (x2)
        vertex(pose, x2, y1, z1, argb);
        vertex(pose, x2, y2, z1, argb);
        vertex(pose, x2, y2, z2, argb);
        vertex(pose, x2, y1, z2, argb);
    }

    private void vertex(Matrix4f pose, float x, float y, float z, int argb) {
        buffer.addVertex(pose, x, y, z).setColor(argb);
    }

    private void drawThroughWalls(Minecraft client) {
        MeshData builtBuffer = buffer.buildOrThrow();
        MeshData.DrawState drawState = builtBuffer.drawState();
        VertexFormat format = drawState.format();

        GpuBuffer vertices = upload(drawState, format, builtBuffer);
        draw(client, builtBuffer, drawState, vertices);

        // Rotate so the next frame uses the next buffer in the ring.
        vertexBuffer.rotate();
        buffer = null;
    }

    private GpuBuffer upload(MeshData.DrawState drawState, VertexFormat format, MeshData builtBuffer) {
        int vertexBufferSize = drawState.vertexCount() * format.getVertexSize();

        if (vertexBuffer == null || vertexBuffer.size() < vertexBufferSize) {
            if (vertexBuffer != null) {
                vertexBuffer.close();
            }
            vertexBuffer = new MappableRingBuffer(
                    () -> ModuleManager.MOD_ID + " esp vertices",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE,
                    vertexBufferSize);
        }

        CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
        GpuBufferSlice bufferSlice = vertexBuffer.currentBuffer().slice(0, builtBuffer.vertexBuffer().remaining());
        try (GpuBuffer.MappedView mappedView = commandEncoder.mapBuffer(bufferSlice, false, true)) {
            MemoryUtil.memCopy(builtBuffer.vertexBuffer(), mappedView.data());
        }

        return vertexBuffer.currentBuffer();
    }

    private void draw(Minecraft client, MeshData builtBuffer, MeshData.DrawState drawState, GpuBuffer vertices) {
        GpuBuffer indices;
        VertexFormat.IndexType indexType;

        if (ESP_PIPELINE.getVertexFormatMode() == VertexFormat.Mode.QUADS) {
            builtBuffer.sortQuads(ALLOCATOR, RenderSystem.getProjectionType().vertexSorting());
            indices = ESP_PIPELINE.getVertexFormat().uploadImmediateIndexBuffer(builtBuffer.indexBuffer());
            indexType = builtBuffer.drawState().indexType();
        } else {
            RenderSystem.AutoStorageIndexBuffer shapeIndexBuffer =
                    RenderSystem.getSequentialBuffer(ESP_PIPELINE.getVertexFormatMode());
            indices = shapeIndexBuffer.getBuffer(drawState.indexCount());
            indexType = shapeIndexBuffer.type();
        }

        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrix(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(
                        () -> ModuleManager.MOD_ID + " esp render pass",
                        client.getMainRenderTarget().getColorTextureView(),
                        OptionalInt.empty(),
                        client.getMainRenderTarget().getDepthTextureView(),
                        OptionalDouble.empty())) {
            renderPass.setPipeline(ESP_PIPELINE);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);
            renderPass.setVertexBuffer(0, vertices);
            renderPass.setIndexBuffer(indices, indexType);
            renderPass.drawIndexed(0, 0, drawState.indexCount(), 1);
        }

        builtBuffer.close();
    }

    /** Releases GPU and native buffers. Called from GameRendererMixin when the renderer closes. */
    public void close() {
        ALLOCATOR.close();
        if (vertexBuffer != null) {
            vertexBuffer.close();
            vertexBuffer = null;
        }
    }
}
