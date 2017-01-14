package com.replaymod.recording.packet;

import com.replaymod.core.utils.Restrictions;
import com.replaymod.replaystudio.data.Marker;
import com.replaymod.replaystudio.replay.ReplayFile;
import com.replaymod.replaystudio.replay.ReplayMetaData;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.DataWatcher;
import net.minecraft.entity.Entity;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.*;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class PacketListener extends ChannelInboundHandlerAdapter {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Logger logger = LogManager.getLogger();

    private final ReplayFile replayFile;

    private final ResourcePackRecorder resourcePackRecorder;

    private final ExecutorService saveService = Executors.newSingleThreadExecutor();
    private final DataOutputStream packetOutputStream;

    private ReplayMetaData metaData;

    private ChannelHandlerContext context = null;

    private final long startTime;
    private long lastSentPacket;
    private long timePassedWhilePaused;
    private volatile boolean serverWasPaused;

    /**
     * Used to keep track of the last metadata save job submitted to the save service and
     * as such prevents unnecessary writes.
     */
    private final AtomicInteger lastSaveMetaDataId = new AtomicInteger();

    public PacketListener(ReplayFile replayFile, ReplayMetaData metaData) throws IOException {
        this.replayFile = replayFile;
        this.metaData = metaData;
        this.resourcePackRecorder = new ResourcePackRecorder(replayFile);
        this.packetOutputStream = new DataOutputStream(replayFile.writePacketData());
        this.startTime = metaData.getDate();

        saveMetaData();
    }

    private void saveMetaData() {
        int id = lastSaveMetaDataId.incrementAndGet();
        saveService.submit(() -> {
            if (lastSaveMetaDataId.get() != id) {
                return; // Another job has been scheduled, it will do the hard work.
            }
            try {
                synchronized (replayFile) {
                    replayFile.writeMetaData(metaData);
                }
            } catch (IOException e) {
                logger.error("Writing metadata:", e);
            }
        });
    }

    public void save(Packet packet) {
        try {
            if(packet instanceof S0CPacketSpawnPlayer) {
                UUID uuid = ((S0CPacketSpawnPlayer) packet).getPlayer();
                Set<String> uuids = new HashSet<>(Arrays.asList(metaData.getPlayers()));
                uuids.add(uuid.toString());
                metaData.setPlayers(uuids.toArray(new String[uuids.size()]));
                saveMetaData();
            }

            byte[] bytes = getPacketData(packet);
            long now = System.currentTimeMillis();
            saveService.submit(() -> {
                if (serverWasPaused) {
                    timePassedWhilePaused = now - startTime - lastSentPacket;
                    serverWasPaused = false;
                }
                int timestamp = (int) (now - startTime - timePassedWhilePaused);
                lastSentPacket = timestamp;
                try {
                    packetOutputStream.writeInt(timestamp);
                    packetOutputStream.writeInt(bytes.length);
                    packetOutputStream.write(bytes);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch(Exception e) {
            logger.error("Writing packet:", e);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        metaData.setDuration((int) lastSentPacket);
        saveMetaData();

        saveService.shutdown();
        try {
            saveService.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            logger.error("Waiting for save service termination:", e);
        }

        synchronized (replayFile) {
            try {
                replayFile.save();
                replayFile.close();
            } catch (IOException e) {
                logger.error("Saving replay file:", e);
            }
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if(ctx == null) {
            if(context == null) {
                return;
            } else {
                ctx = context;
            }
        }
        this.context = ctx;

        if (msg instanceof Packet) {
            try {
                Packet packet = (Packet) msg;

                if(packet instanceof S0DPacketCollectItem) {
                    if(mc.thePlayer != null ||
                            ((S0DPacketCollectItem) packet).getEntityID() == mc.thePlayer.getEntityId()) {
                        super.channelRead(ctx, msg);
                        return;
                    }
                }

                if (packet instanceof S48PacketResourcePackSend) {
                    save(resourcePackRecorder.handleResourcePack((S48PacketResourcePackSend) packet));
                    return;
                }

                if (packet instanceof FMLProxyPacket) {
                    // This packet requires special handling
                    ((FMLProxyPacket) packet).toS3FPackets().forEach(this::save);
                    super.channelRead(ctx, msg);
                    return;
                }

                save(packet);

                if (packet instanceof S3FPacketCustomPayload) {
                    S3FPacketCustomPayload p = (S3FPacketCustomPayload) packet;
                    if (Restrictions.PLUGIN_CHANNEL.equals(p.getChannelName())) {
                        packet = new S40PacketDisconnect(new ChatComponentText("Please update to view this replay."));
                        save(packet);
                    }
                }
            } catch(Exception e) {
                logger.error("Handling packet for recording:", e);
            }

        }

        super.channelRead(ctx, msg);
    }

    @SuppressWarnings("unchecked")
    private byte[] getPacketData(Packet packet) throws IOException {
        if(packet instanceof S0FPacketSpawnMob) {
            S0FPacketSpawnMob p = (S0FPacketSpawnMob) packet;
            if (p.field_149043_l == null) {
                p.field_149043_l = new DataWatcher(null);
                if(p.func_149027_c() != null) {
                    for(DataWatcher.WatchableObject wo : (List<DataWatcher.WatchableObject>) p.func_149027_c()) {
                        p.field_149043_l.addObject(wo.getDataValueId(), wo.getObject());
                    }
                }
            }
        }

        if(packet instanceof S0CPacketSpawnPlayer) {
            S0CPacketSpawnPlayer p = (S0CPacketSpawnPlayer) packet;
            if (p.watcher == null) {
                p.watcher = new DataWatcher(null);
                if(p.func_148944_c() != null) {
                    for(DataWatcher.WatchableObject wo : p.func_148944_c()) {
                        p.watcher.addObject(wo.getDataValueId(), wo.getObject());
                    }
                }
            }
        }

        Integer packetId = EnumConnectionState.PLAY.getPacketId(EnumPacketDirection.CLIENTBOUND, packet);
        if (packetId == null) {
            throw new IOException("Unknown packet type:" + packet.getClass());
        }
        ByteBuf byteBuf = Unpooled.buffer();
        PacketBuffer packetBuffer = new PacketBuffer(byteBuf);
        packetBuffer.writeVarIntToBuffer(packetId);
        packet.writePacketData(packetBuffer);

        byteBuf.readerIndex(0);
        byte[] array = new byte[byteBuf.readableBytes()];
        byteBuf.readBytes(array);

        byteBuf.release();
        return array;
    }

    public void addMarker() {
        Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        int timestamp = (int) (System.currentTimeMillis() - startTime);

        Marker marker = new Marker();
        marker.setTime(timestamp);
        marker.setX(view.posX);
        marker.setY(view.posY);
        marker.setZ(view.posZ);
        marker.setYaw(view.rotationYaw);
        marker.setPitch(view.rotationPitch);
        // Roll is always 0
        saveService.submit(() -> {
            synchronized (replayFile) {
                try {
                    Set<Marker> markers = replayFile.getMarkers().or(HashSet::new);
                    markers.add(marker);
                    replayFile.writeMarkers(markers);
                } catch (IOException e) {
                    logger.error("Writing markers:", e);
                }
            }
        });
    }

    public void setServerWasPaused() {
        this.serverWasPaused = true;
    }
}
