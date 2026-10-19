package mchorse.bbs_mod.network.together;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.actions.types.chat.CommandActionClip;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.network.IBufferReceiver;
import mchorse.bbs_mod.network.ServerNetwork;
import mchorse.bbs_mod.network.ServerPacketCrusher;
import mchorse.bbs_mod.utils.PermissionUtils;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.repos.RepositoryOperation;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Multiplayer collaboration, ported from BBS Together (ChillinWithDylan, MIT) onto BBS FS.
 *
 * <p>The server is only a relay and a registry: it remembers who has which film open (presence),
 * where their mouse is (cursors) and whose form palette is open over which replay (form locks), and
 * passes completed edits from one editor of a film to the others. It never merges film data itself —
 * every client applies the edits it receives to its own copy.
 *
 * <p>Replays are addressed by their stable id, not by their position, so selections, locks and
 * relayed edits survive replays being added, removed and reordered by someone else.
 */
public class TogetherServer
{
    /* Server -> client */
    public static final Identifier CLIENT_PRESENCE = Identifier.of(BBSMod.MOD_ID, "tc1");
    public static final Identifier CLIENT_CURSORS = Identifier.of(BBSMod.MOD_ID, "tc2");
    public static final Identifier CLIENT_FILM_EDIT = Identifier.of(BBSMod.MOD_ID, "tc3");
    public static final Identifier CLIENT_FILM_SAVED = Identifier.of(BBSMod.MOD_ID, "tc4");
    public static final Identifier CLIENT_FILM_LIST = Identifier.of(BBSMod.MOD_ID, "tc5");
    public static final Identifier CLIENT_FORM_LOCKS = Identifier.of(BBSMod.MOD_ID, "tc6");
    public static final Identifier CLIENT_ASSET_MANIFEST = Identifier.of(BBSMod.MOD_ID, "tc7");
    public static final Identifier CLIENT_ASSET_FILE = Identifier.of(BBSMod.MOD_ID, "tc8");
    public static final Identifier CLIENT_TOGGLE_RECORDING = Identifier.of(BBSMod.MOD_ID, "tc9");

    /* Client -> server */
    public static final Identifier SERVER_PRESENCE = Identifier.of(BBSMod.MOD_ID, "ts1");
    public static final Identifier SERVER_CURSOR = Identifier.of(BBSMod.MOD_ID, "ts2");
    public static final Identifier SERVER_FILM_EDIT = Identifier.of(BBSMod.MOD_ID, "ts3");
    public static final Identifier SERVER_FORM_LOCK = Identifier.of(BBSMod.MOD_ID, "ts4");
    public static final Identifier SERVER_ASSET_REQUEST = Identifier.of(BBSMod.MOD_ID, "ts5");
    public static final Identifier SERVER_ASSET_UPLOAD = Identifier.of(BBSMod.MOD_ID, "ts6");

    public static final Logger LOGGER = LoggerFactory.getLogger("BBS Together");

    private static final long ASSET_SEND_INTERVAL_MS = 50L;

    private static final Map<UUID, Presence> presence = new ConcurrentHashMap<>();
    private static final Map<UUID, byte[]> cursors = new ConcurrentHashMap<>();
    private static final Map<UUID, FormLock> formLocks = new ConcurrentHashMap<>();

    /* Chunked packets are reassembled per sender: a single shared buffer mixes up the chunks of two
     * players uploading at the same time, since their chunk counters start from the same number. */
    private static final Map<UUID, ServerPacketCrusher> receivers = new ConcurrentHashMap<>();
    /* Together's chunks come from a separate counter on the client, so they get their own buffers */
    private static final Map<UUID, ServerPacketCrusher> togetherReceivers = new ConcurrentHashMap<>();
    private static final ServerPacketCrusher sender = new ServerPacketCrusher();

    private static final Map<UUID, LinkedBlockingQueue<AssetSendTask>> assetQueues = new ConcurrentHashMap<>();
    private static final Map<UUID, AtomicBoolean> assetDrains = new ConcurrentHashMap<>();

    public static void setup()
    {
        for (Identifier id : List.of(CLIENT_PRESENCE, CLIENT_CURSORS, CLIENT_FILM_EDIT, CLIENT_FILM_SAVED, CLIENT_FILM_LIST, CLIENT_FORM_LOCKS, CLIENT_ASSET_MANIFEST, CLIENT_ASSET_FILE, CLIENT_TOGGLE_RECORDING))
        {
            PayloadTypeRegistry.playS2C().register(ServerNetwork.idFor(id), ServerNetwork.BufPayload.codecFor(ServerNetwork.idFor(id)));
        }

        for (Identifier id : List.of(SERVER_PRESENCE, SERVER_CURSOR, SERVER_FILM_EDIT, SERVER_FORM_LOCK, SERVER_ASSET_REQUEST, SERVER_ASSET_UPLOAD))
        {
            PayloadTypeRegistry.playC2S().register(ServerNetwork.idFor(id), ServerNetwork.BufPayload.codecFor(ServerNetwork.idFor(id)));
        }

        receive(SERVER_PRESENCE, TogetherServer::handlePresence);
        receive(SERVER_CURSOR, TogetherServer::handleCursor);
        receive(SERVER_FILM_EDIT, TogetherServer::handleFilmEdit);
        receive(SERVER_FORM_LOCK, TogetherServer::handleFormLock);
        receive(SERVER_ASSET_REQUEST, TogetherServer::handleAssetRequest);
        receive(SERVER_ASSET_UPLOAD, TogetherServer::handleAssetUpload);
    }

    /** Vanilla clients and clients with an older BBS don't know these channels. */
    private static void sendTo(ServerPlayerEntity player, ServerNetwork.BufPayload payload)
    {
        if (ServerPlayNetworking.canSend(player, payload.getId()))
        {
            ServerPlayNetworking.send(player, payload);
        }
    }

    private static void receive(Identifier id, Handler handler)
    {
        ServerPlayNetworking.registerGlobalReceiver(ServerNetwork.idFor(id), (payload, context) -> handler.handle(context.server(), context.player(), payload.asPacketByteBuf()));
    }

    public static void reset()
    {
        presence.clear();
        cursors.clear();
        formLocks.clear();
        receivers.clear();
        togetherReceivers.clear();
        sender.reset();

        for (LinkedBlockingQueue<AssetSendTask> queue : assetQueues.values())
        {
            queue.clear();
        }

        assetQueues.clear();
        assetDrains.clear();
    }

    /**
     * Chunk reassembly for one sender, see {@link #receivers}. Also used by {@link ServerNetwork}
     * for its own chunked packets.
     */
    public static void receiveChunked(ServerPlayerEntity player, PacketByteBuf buf, IBufferReceiver receiver)
    {
        receivers.computeIfAbsent(player.getUuid(), (k) -> new ServerPacketCrusher()).receive(buf, receiver);
    }

    private static void receiveTogether(ServerPlayerEntity player, PacketByteBuf buf, IBufferReceiver receiver)
    {
        togetherReceivers.computeIfAbsent(player.getUuid(), (k) -> new ServerPacketCrusher()).receive(buf, receiver);
    }

    /* Lifecycle */

    public static void onJoin(MinecraftServer server, ServerPlayerEntity player)
    {
        if (!ServerPlayNetworking.canSend(player, ServerNetwork.idFor(CLIENT_PRESENCE)))
        {
            return;
        }

        /* Assets are the host's own files in singleplayer/LAN, so there is nothing to download */
        if (!server.isHost(new PlayerConfigEntry(player.getGameProfile())))
        {
            sendAssetManifest(server, player);
        }

        sendPresence(player);
    }

    public static void onDisconnect(MinecraftServer server, ServerPlayerEntity player)
    {
        UUID uuid = player.getUuid();
        FormLock lock = formLocks.remove(uuid);

        presence.remove(uuid);
        cursors.remove(uuid);
        receivers.remove(uuid);
        togetherReceivers.remove(uuid);

        LinkedBlockingQueue<AssetSendTask> queue = assetQueues.remove(uuid);

        if (queue != null)
        {
            queue.clear();
        }

        assetDrains.remove(uuid);

        broadcastPresence(server);

        if (lock != null)
        {
            broadcastFormLocks(server, lock.filmId);
        }
    }

    /* Presence */

    private static void handlePresence(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf)
    {
        Presence p = new Presence(buf.readString(), buf.readString(), buf.readBoolean(), buf.readInt(), buf.readBoolean());

        server.execute(() ->
        {
            Presence old = presence.put(player.getUuid(), p);

            if (!p.filmId.equals(old == null ? "" : old.filmId))
            {
                cursors.remove(player.getUuid());
            }

            broadcastPresence(server);
        });
    }

    private static void broadcastPresence(MinecraftServer server)
    {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList())
        {
            sendPresence(player);
        }
    }

    private static void sendPresence(ServerPlayerEntity player)
    {
        PacketByteBuf buf = PacketByteBufs.create();

        buf.writeInt(presence.size());

        for (Map.Entry<UUID, Presence> entry : presence.entrySet())
        {
            Presence p = entry.getValue();

            buf.writeUuid(entry.getKey());
            buf.writeString(p.filmId);
            buf.writeString(p.replayId);
            buf.writeBoolean(p.inEditor);
            buf.writeInt(p.color);
        }

        sendTo(player, ServerNetwork.BufPayload.from(buf, ServerNetwork.idFor(CLIENT_PRESENCE)));
    }

    /** Players other than {@code except} that have the given film open. */
    private static List<ServerPlayerEntity> editorsOf(MinecraftServer server, String filmId, ServerPlayerEntity except)
    {
        List<ServerPlayerEntity> list = new ArrayList<>();

        if (filmId.isEmpty())
        {
            return list;
        }

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList())
        {
            Presence p = presence.get(player.getUuid());

            if (player != except && p != null && p.filmId.equals(filmId))
            {
                list.add(player);
            }
        }

        return list;
    }

    /* Cursors */

    /**
     * The cursor body is opaque to the server (the client knows how to read its timelines), only
     * the film id in front of it matters for routing.
     */
    private static void handleCursor(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf)
    {
        String filmId = buf.readString();
        byte[] body = new byte[buf.readableBytes()];

        buf.readBytes(body);

        server.execute(() ->
        {
            if (body.length == 0)
            {
                cursors.remove(player.getUuid());
            }
            else
            {
                cursors.put(player.getUuid(), body);
            }

            for (ServerPlayerEntity other : editorsOf(server, filmId, player))
            {
                PacketByteBuf out = PacketByteBufs.create();

                out.writeUuid(player.getUuid());
                out.writeBytes(body);

                sendTo(other, ServerNetwork.BufPayload.from(out, ServerNetwork.idFor(CLIENT_CURSORS)));
            }
        });
    }

    /* Film edits */

    /** A completed edit of one value of a film, passed on to everyone else editing that film. */
    private static void handleFilmEdit(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf)
    {
        if (!PermissionUtils.arePanelsAllowed(server, player))
        {
            return;
        }

        receiveTogether(player, buf, (bytes, packetByteBuf) ->
        {
            String filmId = packetByteBuf.readString();
            List<String> path = new ArrayList<>();

            for (int i = 0, c = packetByteBuf.readInt(); i < c; i++)
            {
                path.add(packetByteBuf.readString());
            }

            server.execute(() ->
            {
                List<ServerPlayerEntity> editors = editorsOf(server, filmId, player);

                if (editors.isEmpty())
                {
                    return;
                }

                sender.send(new ArrayList<PlayerEntity>(editors), CLIENT_FILM_EDIT, bytes, (out) ->
                {
                    out.writeString(filmId);
                    out.writeString(player.getName().getString());
                    out.writeInt(path.size());

                    for (String segment : path)
                    {
                        out.writeString(segment);
                    }
                });
            });
        });
    }

    /**
     * Hooked into the film repository requests: saves reach the other editors of the film (as a
     * consistency check, see the client), and any change of the list reaches everyone's film list.
     */
    public static void onFilmRepository(MinecraftServer server, ServerPlayerEntity player, RepositoryOperation op, MapType data)
    {
        server.execute(() ->
        {
            if (op == RepositoryOperation.SAVE)
            {
                String filmId = data.getString("id");
                MapType filmData = data.getMap("data");
                List<ServerPlayerEntity> editors = editorsOf(server, filmId, player);

                if (!editors.isEmpty())
                {
                    sender.send(new ArrayList<PlayerEntity>(editors), CLIENT_FILM_SAVED, filmData, (out) ->
                    {
                        out.writeString(filmId);
                        out.writeString(player.getName().getString());
                    });
                }
            }

            String id = "";
            String id2 = "";

            switch (op)
            {
                case SAVE, DELETE -> id = data.getString("id");
                case ADD_FOLDER, DELETE_FOLDER -> id = data.getString("folder");
                case RENAME, RENAME_FOLDER ->
                {
                    id = data.getString("from");
                    id2 = data.getString("to");
                }
                default ->
                {
                    return;
                }
            }

            for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList())
            {
                if (other == player)
                {
                    continue;
                }

                PacketByteBuf buf = PacketByteBufs.create();

                buf.writeInt(op.ordinal());
                buf.writeString(id);
                buf.writeString(id2);

                sendTo(other, ServerNetwork.BufPayload.from(buf, ServerNetwork.idFor(CLIENT_FILM_LIST)));
            }
        });
    }

    /* Form locks */

    private static void handleFormLock(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf)
    {
        String filmId = buf.readString();
        String replayId = buf.readString();
        boolean locked = buf.readBoolean();

        server.execute(() ->
        {
            FormLock old = locked ? formLocks.put(player.getUuid(), new FormLock(filmId, replayId)) : formLocks.remove(player.getUuid());

            if (old != null && !old.filmId.equals(filmId))
            {
                broadcastFormLocks(server, old.filmId);
            }

            broadcastFormLocks(server, filmId);
        });
    }

    private static void broadcastFormLocks(MinecraftServer server, String filmId)
    {
        List<Map.Entry<UUID, FormLock>> locks = new ArrayList<>();

        for (Map.Entry<UUID, FormLock> entry : formLocks.entrySet())
        {
            if (entry.getValue().filmId.equals(filmId))
            {
                locks.add(entry);
            }
        }

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList())
        {
            PacketByteBuf buf = PacketByteBufs.create();

            buf.writeString(filmId);
            buf.writeInt(locks.size());

            for (Map.Entry<UUID, FormLock> entry : locks)
            {
                buf.writeUuid(entry.getKey());
                buf.writeString(entry.getValue().replayId);
            }

            sendTo(player, ServerNetwork.BufPayload.from(buf, ServerNetwork.idFor(CLIENT_FORM_LOCKS)));
        }
    }

    /* Group recording (/bbs record) */

    public static boolean isRecording(ServerPlayerEntity player)
    {
        return BBSMod.getActions().isRecording(player);
    }

    /**
     * Start everyone in the selection recording their selected replay at once, or, if any of them
     * is already recording, stop all of those. Each client applies its own take to its own replay,
     * and the take reaches the other editors as an ordinary edit of that replay.
     */
    public static int toggleGroupRecording(Collection<ServerPlayerEntity> players)
    {
        List<ServerPlayerEntity> recording = players.stream().filter(TogetherServer::isRecording).toList();
        boolean start = recording.isEmpty();

        for (ServerPlayerEntity player : start ? players : recording)
        {
            PacketByteBuf buf = PacketByteBufs.create();

            buf.writeBoolean(start);

            sendTo(player, ServerNetwork.BufPayload.from(buf, ServerNetwork.idFor(CLIENT_TOGGLE_RECORDING)));
        }

        return start ? players.size() : recording.size();
    }

    /**
     * The command that started a group recording is caught by the recorder of whoever ran it; left
     * in, playing the film back would start another recording every time.
     */
    public static void stripRecordCommands(Clips clips)
    {
        for (Clip clip : new ArrayList<>(clips.get()))
        {
            if (clip instanceof CommandActionClip command && command.command.get().contains("bbs record"))
            {
                clips.remove(clip);
            }
        }
    }

    /* Asset sync */

    private static Path assetsPath()
    {
        File folder = BBSMod.getAssetsFolder();

        return folder == null ? null : folder.toPath().toAbsolutePath().normalize();
    }

    /** Resolve a client-supplied relative path, refusing anything that escapes the assets folder. */
    public static Path resolveAsset(Path root, String relative)
    {
        if (root == null || relative.isEmpty() || relative.startsWith("/") || relative.contains("\\") || relative.contains(":"))
        {
            return null;
        }

        Path resolved = root.resolve(relative).normalize();

        return resolved.startsWith(root) && !resolved.equals(root) ? resolved : null;
    }

    public static void sendAssetManifest(MinecraftServer server, ServerPlayerEntity player)
    {
        Path root = assetsPath();

        if (root == null || !Files.isDirectory(root))
        {
            return;
        }

        Thread thread = new Thread(() ->
        {
            try
            {
                MapType manifest = new MapType();

                Files.walkFileTree(root, new SimpleFileVisitor<>()
                {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
                    {
                        if (attrs.isRegularFile())
                        {
                            manifest.putString(root.relativize(file).toString().replace(File.separatorChar, '/'), sha1(file));
                        }

                        return FileVisitResult.CONTINUE;
                    }
                });

                server.execute(() ->
                {
                    if (server.getPlayerManager().getPlayer(player.getUuid()) != null)
                    {
                        sender.send(player, CLIENT_ASSET_MANIFEST, manifest, null);
                    }
                });
            }
            catch (Exception e)
            {
                LOGGER.error("Failed to build the asset manifest", e);
            }
        }, "BBS-AssetManifest");

        thread.setDaemon(true);
        thread.start();
    }

    public static String sha1(Path path) throws IOException
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");

            try (InputStream in = Files.newInputStream(path))
            {
                byte[] buffer = new byte[8192];
                int read;

                while ((read = in.read(buffer)) != -1)
                {
                    digest.update(buffer, 0, read);
                }
            }

            StringBuilder builder = new StringBuilder();

            for (byte b : digest.digest())
            {
                builder.append(String.format("%02x", b & 0xFF));
            }

            return builder.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new RuntimeException("SHA-1 not available", e);
        }
    }

    private static void handleAssetRequest(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf)
    {
        List<String> paths = new ArrayList<>();

        for (int i = 0, c = buf.readInt(); i < c; i++)
        {
            paths.add(buf.readString());
        }

        Thread thread = new Thread(() ->
        {
            Path root = assetsPath();

            for (String relative : paths)
            {
                Path file = resolveAsset(root, relative);

                if (file == null || !Files.isRegularFile(file))
                {
                    continue;
                }

                try
                {
                    enqueueAsset(server, player, new AssetSendTask(relative, Files.readAllBytes(file), false));
                }
                catch (Exception e)
                {
                    LOGGER.error("Failed to read asset " + relative, e);
                }
            }
        }, "BBS-AssetSend");

        thread.setDaemon(true);
        thread.start();
    }

    /**
     * A client pushing one of its files to the others. The server keeps nothing — it only passes
     * the file on to the players that opted into receiving pushed files.
     */
    private static void handleAssetUpload(MinecraftServer server, ServerPlayerEntity uploader, PacketByteBuf buf)
    {
        if (!PermissionUtils.arePanelsAllowed(server, uploader))
        {
            return;
        }

        receiveTogether(uploader, buf, (bytes, packetByteBuf) ->
        {
            String relative = packetByteBuf.readString();

            if (bytes == null || resolveAsset(Path.of("/assets"), relative) == null)
            {
                LOGGER.warn("{} sent a suspicious asset path: {}", uploader.getName().getString(), relative);

                return;
            }

            server.execute(() ->
            {
                for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList())
                {
                    Presence p = presence.get(other.getUuid());

                    if (other != uploader && p != null && p.receivesAssets)
                    {
                        enqueueAsset(server, other, new AssetSendTask(relative, bytes, true));
                    }
                }
            });
        });
    }

    /**
     * Files go out one at a time with a pause in between, so a big download doesn't clog the
     * connection for everything else (movement, chat, other BBS packets).
     */
    private static void enqueueAsset(MinecraftServer server, ServerPlayerEntity player, AssetSendTask task)
    {
        UUID uuid = player.getUuid();

        assetQueues.computeIfAbsent(uuid, (k) -> new LinkedBlockingQueue<>()).add(task);

        AtomicBoolean draining = assetDrains.computeIfAbsent(uuid, (k) -> new AtomicBoolean(false));

        if (!draining.compareAndSet(false, true))
        {
            return;
        }

        Thread thread = new Thread(() ->
        {
            LinkedBlockingQueue<AssetSendTask> queue = assetQueues.get(uuid);

            try
            {
                /* The flag is dropped before the last look at the queue, so a task added while
                 * this thread was finishing either gets picked up here or starts a new drain */
                do
                {
                    AssetSendTask next;

                    while (queue != null && (next = queue.poll()) != null)
                    {
                        AssetSendTask t = next;

                        server.execute(() ->
                        {
                            ServerPlayerEntity target = server.getPlayerManager().getPlayer(uuid);

                            if (target != null)
                            {
                                sender.send(target, CLIENT_ASSET_FILE, t.bytes, (out) ->
                                {
                                    out.writeString(t.relativePath);
                                    out.writeBoolean(t.isPush);
                                });
                            }
                        });

                        Thread.sleep(ASSET_SEND_INTERVAL_MS);
                    }

                    draining.set(false);
                }
                while (queue != null && !queue.isEmpty() && draining.compareAndSet(false, true));
            }
            catch (InterruptedException e)
            {
                draining.set(false);
                Thread.currentThread().interrupt();
            }
        }, "BBS-AssetDrain-" + uuid);

        thread.setDaemon(true);
        thread.start();
    }

    /* Types */

    @FunctionalInterface
    private interface Handler
    {
        void handle(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf);
    }

    private record Presence(String filmId, String replayId, boolean inEditor, int color, boolean receivesAssets)
    {}

    private record FormLock(String filmId, String replayId)
    {}

    private record AssetSendTask(String relativePath, byte[] bytes, boolean isPush)
    {}
}
