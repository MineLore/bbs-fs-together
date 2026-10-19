package mchorse.bbs_mod.network.together;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.api.client.events.FilmEditEvents;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.Films;
import mchorse.bbs_mod.film.Recorder;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.network.ClientNetwork;
import mchorse.bbs_mod.network.ClientPacketCrusher;
import mchorse.bbs_mod.network.ServerNetwork;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.film.UIClips;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.UIScreen;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.UIKeyframeDopeSheet;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.CollectionUtils;
import mchorse.bbs_mod.utils.DataPath;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.repos.RepositoryOperation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Client half of the multiplayer collaboration (BBS Together), see {@code TogetherServer}.
 *
 * <p>Almost nothing here is pushed by the editor itself: presence is read off the dashboard every
 * tick, edits come off {@link FilmEditEvents}, and the UI only calls in to draw the other players.
 */
public class TogetherClient
{
    public static final IKey FILM_SYNCED = L10n.lang("bbs.ui.together.film_synced");
    public static final IKey FORM_LOCKED = L10n.lang("bbs.ui.together.form_locked");
    public static final IKey IN_EDITOR = L10n.lang("bbs.ui.together.in_editor");
    public static final IKey IN_EDITOR_ONE = L10n.lang("bbs.ui.together.in_editor_one");
    public static final IKey ASSETS_DOWNLOADED = L10n.lang("bbs.ui.together.assets_downloaded");

    public static final int PANEL_CAMERA = 0;
    public static final int PANEL_REPLAY = 1;

    private static final long CURSOR_INTERVAL_MS = 50L;
    /* A save from someone else replaces the whole film, which would throw away whatever was edited
     * here and hasn't reached them yet. The edits travel live, so the save is only needed to catch
     * up after something went missing — and is held off while this player is busy editing. */
    private static final long SAVED_SYNC_QUIET_MS = 3000L;
    private static final int HEAD_SIZE = 10;

    public static final Map<UUID, Presence> presence = new ConcurrentHashMap<>();
    public static final Map<UUID, Cursor> cursors = new ConcurrentHashMap<>();
    /* film id -> replay id -> owner */
    public static final Map<String, Map<String, UUID>> formLocks = new ConcurrentHashMap<>();

    private static final ClientPacketCrusher crusher = new ClientPacketCrusher();

    private static Presence lastPresence;
    private static long lastCursorTime;
    private static boolean lastCursorOver;
    private static int lastMouseX = Integer.MIN_VALUE;
    private static int lastMouseY = Integer.MIN_VALUE;
    private static final Set<BaseValue> pendingEdits = new LinkedHashSet<>();
    private static Film pendingFilm;
    private static long lastLocalEdit;
    private static boolean outOfSync;
    private static final AtomicInteger pendingDownloads = new AtomicInteger();
    private static int downloadTotal;

    public static void setup()
    {
        receive(TogetherServer.CLIENT_PRESENCE, TogetherClient::handlePresence);
        receive(TogetherServer.CLIENT_CURSORS, TogetherClient::handleCursor);
        receive(TogetherServer.CLIENT_FILM_EDIT, TogetherClient::handleFilmEdit);
        receive(TogetherServer.CLIENT_FILM_SAVED, TogetherClient::handleFilmSaved);
        receive(TogetherServer.CLIENT_FILM_LIST, TogetherClient::handleFilmList);
        receive(TogetherServer.CLIENT_FORM_LOCKS, TogetherClient::handleFormLocks);
        receive(TogetherServer.CLIENT_ASSET_MANIFEST, TogetherClient::handleAssetManifest);
        receive(TogetherServer.CLIENT_ASSET_FILE, TogetherClient::handleAssetFile);
        receive(TogetherServer.CLIENT_TOGGLE_RECORDING, TogetherClient::handleToggleRecording);

        FilmEditEvents.CHANGED.register(TogetherClient::onFilmEdited);
        ClientTickEvents.END_CLIENT_TICK.register((client) -> tick(client));
        HudRenderCallback.EVENT.register((context, tickCounter) -> renderHud(context));
    }

    private static void receive(Identifier id, Consumer<PacketByteBuf> handler)
    {
        ClientPlayNetworking.registerGlobalReceiver(ServerNetwork.idFor(id), (payload, context) -> handler.accept(payload.asPacketByteBuf()));
    }

    private static void send(Identifier id, PacketByteBuf buf)
    {
        ClientPlayNetworking.send(ServerNetwork.BufPayload.from(buf, ServerNetwork.idFor(id)));
    }

    public static void reset()
    {
        presence.clear();
        cursors.clear();
        formLocks.clear();
        crusher.reset();
        pendingEdits.clear();
        pendingFilm = null;
        lastPresence = null;
        outOfSync = false;
        pendingDownloads.set(0);
    }

    private static boolean isActive()
    {
        return ClientNetwork.isIsBBSModOnServer() && MinecraftClient.getInstance().player != null;
    }

    private static UUID localUuid()
    {
        MinecraftClient mc = MinecraftClient.getInstance();

        return mc.player == null ? null : mc.player.getUuid();
    }

    private static UIFilmPanel getFilmPanel()
    {
        UIDashboard dashboard = BBSModClient.getDashboardIfCreated();

        return dashboard == null ? null : dashboard.getPanel(UIFilmPanel.class);
    }

    private static Film getOpenFilm()
    {
        UIFilmPanel panel = getFilmPanel();

        return panel == null ? null : panel.getData();
    }

    /** Whether anybody but this player has the given film open. */
    public static boolean hasOtherEditors(String filmId)
    {
        UUID local = localUuid();

        for (Map.Entry<UUID, Presence> entry : presence.entrySet())
        {
            if (!entry.getKey().equals(local) && entry.getValue().filmId.equals(filmId))
            {
                return true;
            }
        }

        return false;
    }

    public static int getColor(UUID uuid)
    {
        Presence p = presence.get(uuid);

        return (p == null ? Colors.WHITE : p.color) | Colors.A100;
    }

    public static String getName(UUID uuid)
    {
        ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
        PlayerListEntry entry = handler == null ? null : handler.getPlayerListEntry(uuid);

        return entry == null ? "?" : entry.getProfile().name();
    }

    /* Tick */

    private static void tick(MinecraftClient client)
    {
        if (!isActive())
        {
            return;
        }

        sendPresence();
        flushEdits();
    }

    private static void sendPresence()
    {
        UIDashboard dashboard = BBSModClient.getDashboardIfCreated();
        UIFilmPanel panel = getFilmPanel();
        Film film = panel == null ? null : panel.getData();
        Replay replay = film == null ? null : panel.replayEditor.getReplay();
        boolean inEditor = dashboard != null && panel != null && UIScreen.getCurrentMenu() == dashboard && dashboard.getPanels().panel == panel;
        Presence current = new Presence(
            film == null ? "" : film.getId(),
            replay == null ? "" : replay.getId(),
            inEditor,
            BBSSettings.primaryColor.get(),
            BBSSettings.togetherAssetReceive.get()
        );

        if (current.equals(lastPresence))
        {
            return;
        }

        lastPresence = current;

        PacketByteBuf buf = PacketByteBufs.create();

        buf.writeString(current.filmId);
        buf.writeString(current.replayId);
        buf.writeBoolean(current.inEditor);
        buf.writeInt(current.color);
        buf.writeBoolean(current.receivesAssets);

        send(TogetherServer.SERVER_PRESENCE, buf);
    }

    private static void handlePresence(PacketByteBuf buf)
    {
        Map<UUID, Presence> map = new HashMap<>();

        for (int i = 0, c = buf.readInt(); i < c; i++)
        {
            map.put(buf.readUuid(), new Presence(buf.readString(), buf.readString(), buf.readBoolean(), buf.readInt(), false));
        }

        MinecraftClient.getInstance().execute(() ->
        {
            presence.keySet().retainAll(map.keySet());
            presence.putAll(map);

            /* Whoever left the film (or the server) takes their cursor with them */
            cursors.keySet().removeIf((uuid) ->
            {
                Presence p = presence.get(uuid);
                Film film = getOpenFilm();

                return p == null || film == null || !p.filmId.equals(film.getId());
            });
        });
    }

    /* Live edits */

    private static void onFilmEdited(Film film, List<BaseValue> values, FilmEditEvents.Cause cause)
    {
        if (!isActive() || film != getOpenFilm())
        {
            return;
        }

        lastLocalEdit = System.currentTimeMillis();

        if (!hasOtherEditors(film.getId()))
        {
            return;
        }

        if (pendingFilm != film)
        {
            pendingEdits.clear();
            pendingFilm = film;
        }

        for (BaseValue value : values)
        {
            pendingEdits.add(promote(value));
        }
    }

    /**
     * The value that actually gets sent for a changed value. Form property tracks are created
     * lazily on whichever client first looks at them, so an indexed path into one may not exist on
     * the other side — the whole property group of the replay always does.
     */
    private static BaseValue promote(BaseValue value)
    {
        for (BaseValue parent = value; parent != null; parent = parent.getParent())
        {
            if (parent instanceof FormProperties)
            {
                return parent;
            }
        }

        return value;
    }

    private static void flushEdits()
    {
        if (pendingEdits.isEmpty())
        {
            return;
        }

        Film film = pendingFilm;
        List<BaseValue> values = new ArrayList<>(pendingEdits);

        pendingEdits.clear();

        if (film != getOpenFilm())
        {
            return;
        }

        for (BaseValue value : values)
        {
            /* A value inside another that is being sent anyway is already in its data */
            if (hasPendingAncestor(value, values))
            {
                continue;
            }

            sendEdit(film, value);
        }
    }

    private static void sendEdit(Film film, BaseValue value)
    {
        DataPath path = value.getPath();

        crusher.send(MinecraftClient.getInstance().player, TogetherServer.SERVER_FILM_EDIT, value.toData(), (buf) ->
        {
            buf.writeString(film.getId());
            buf.writeInt(path.strings.size());

            for (String segment : path.strings)
            {
                buf.writeString(segment);
            }
        });
    }

    private static boolean hasPendingAncestor(BaseValue value, List<BaseValue> values)
    {
        for (BaseValue parent = value.getParent(); parent != null; parent = parent.getParent())
        {
            if (values.contains(parent))
            {
                return true;
            }
        }

        return false;
    }

    private static void handleFilmEdit(PacketByteBuf buf)
    {
        crusher.receive(buf, (bytes, packetByteBuf) ->
        {
            String filmId = packetByteBuf.readString();
            String editor = packetByteBuf.readString();
            List<String> path = new ArrayList<>();

            for (int i = 0, c = packetByteBuf.readInt(); i < c; i++)
            {
                path.add(packetByteBuf.readString());
            }

            BaseType data = DataStorageUtils.readFromBytes(bytes);

            MinecraftClient.getInstance().execute(() -> applyEdit(filmId, path, data));
        });
    }

    private static void applyEdit(String filmId, List<String> segments, BaseType data)
    {
        UIFilmPanel panel = getFilmPanel();
        Film film = panel == null ? null : panel.getData();

        if (film == null || !film.getId().equals(filmId) || data == null)
        {
            return;
        }

        DataPath path = new DataPath(false);

        path.strings.addAll(segments);

        BaseValue value = film.findRecursively(path);

        if (value == null)
        {
            /* Nothing here to put it in; the next save from them brings the film back in line */
            outOfSync = true;

            return;
        }

        Replay selected = panel.replayEditor.getReplay();
        String selectedId = selected == null ? null : selected.getId();
        Clip clip = panel.cameraEditor.getClip();
        int clipTick = clip == null ? -1 : clip.tick.get();
        int clipLayer = clip == null ? -1 : clip.layer.get();

        value.fromData(data);
        refresh(panel, film, segments, selectedId, clip, clipTick, clipLayer);
    }

    /**
     * Bring the editor back in line with data that changed under it. Values replaced wholesale
     * (lists rebuild their elements) leave the editor holding objects that are no longer in the
     * film, so the selected replay and clip are looked up again by what identifies them.
     */
    private static void refresh(UIFilmPanel panel, Film film, List<String> path, String selectedReplayId, Clip clip, int clipTick, int clipLayer)
    {
        int replays = path.indexOf("replays");
        /* A list, a whole replay, or what decides a replay's channels: everything about the replays
         * is rebuilt. A keyframe further down only needs the editors to read their values again. */
        boolean structural = path.size() <= 1
            || (replays >= 0 && path.size() <= replays + 2)
            || (replays >= 0 && path.size() == replays + 3 && (path.get(replays + 2).equals("form") || path.get(replays + 2).equals("properties")));

        UIReplaysEditor editor = panel.replayEditor;

        if (structural)
        {
            Replay replay = selectedReplayId == null ? null : (Replay) film.replays.get(selectedReplayId);

            if (replay != editor.getReplay())
            {
                editor.setReplay(replay, false, UIReplaysEditor.OrbitReaction.KEEP);
            }
            else
            {
                editor.updateChannelsList();
            }

            editor.replaysList.replays.refreshReplayList();
            panel.getController().createEntities();
        }

        if (clip != null && !film.camera.get().contains(clip))
        {
            Clip match = null;

            for (Clip c : film.camera.get())
            {
                if (c.tick.get() == clipTick && c.layer.get() == clipLayer)
                {
                    match = c;

                    break;
                }
            }

            panel.cameraEditor.pickClip(match);
        }

        clampKeyframeSelection(editor);
        panel.fillData();
    }

    /** Keyframes may have been removed under the selection, which keeps only indices. */
    private static void clampKeyframeSelection(UIReplaysEditor editor)
    {
        if (editor.keyframeEditor == null || editor.keyframeEditor.view == null)
        {
            return;
        }

        for (UIKeyframeSheet sheet : editor.keyframeEditor.view.getGraph().getSheets())
        {
            int size = sheet.channel.getKeyframes().size();

            for (Integer index : new ArrayList<>(sheet.selection.getIndices()))
            {
                if (index >= size)
                {
                    sheet.selection.remove(index);
                }
            }
        }
    }

    private static void handleFilmSaved(PacketByteBuf buf)
    {
        crusher.receive(buf, (bytes, packetByteBuf) ->
        {
            String filmId = packetByteBuf.readString();
            String saver = packetByteBuf.readString();
            BaseType data = DataStorageUtils.readFromBytes(bytes);

            MinecraftClient.getInstance().execute(() ->
            {
                UIFilmPanel panel = getFilmPanel();
                Film film = panel == null ? null : panel.getData();

                if (film == null || !film.getId().equals(filmId) || data == null || !data.isMap())
                {
                    return;
                }

                boolean busy = !pendingEdits.isEmpty()
                    || System.currentTimeMillis() - lastLocalEdit < SAVED_SYNC_QUIET_MS
                    || BBSModClient.getFilms().getRecorder() != null;

                if (busy && !outOfSync)
                {
                    return;
                }

                if (film.toData().toString().equals(data.toString()))
                {
                    outOfSync = false;

                    return;
                }

                Replay selected = panel.replayEditor.getReplay();
                Clip clip = panel.cameraEditor.getClip();

                film.fromData(data);
                outOfSync = false;
                refresh(panel, film, List.of(), selected == null ? null : selected.getId(), clip, clip == null ? -1 : clip.tick.get(), clip == null ? -1 : clip.layer.get());

                UIContext context = panel.getContext();

                if (context != null)
                {
                    context.notifyInfo(FILM_SYNCED.format(saver, filmId));
                }
            });
        });
    }

    private static void handleFilmList(PacketByteBuf buf)
    {
        RepositoryOperation op = RepositoryOperation.values()[buf.readInt()];
        String id = buf.readString();
        String id2 = buf.readString();

        MinecraftClient.getInstance().execute(() ->
        {
            UIFilmPanel panel = getFilmPanel();

            if (panel == null)
            {
                return;
            }

            Film film = panel.getData();

            if (op == RepositoryOperation.SAVE)
            {
                if (!panel.overlay.namesList.hasInHierarchy(id))
                {
                    panel.overlay.namesList.addFile(id);
                }

                return;
            }

            if (op == RepositoryOperation.DELETE && film != null && film.getId().equals(id))
            {
                panel.fill(null);
            }
            else if (op == RepositoryOperation.RENAME && film != null && film.getId().equals(id))
            {
                film.setId(id2);
            }

            panel.requestNames();
        });
    }

    /* Cursors */

    /** Called by the film editor after it has drawn itself: report our cursor, draw everyone else's. */
    public static void renderFilmPanel(UIFilmPanel panel, UIContext context)
    {
        Film film = panel.getData();

        if (!isActive() || film == null || !hasOtherEditors(film.getId()))
        {
            return;
        }

        sendCursor(panel, film, context);

        if (BBSSettings.togetherCursors.get())
        {
            renderCursors(panel, film, context);
        }
    }

    private static int getPanelType(UIFilmPanel panel)
    {
        if (panel.cameraEditor.isVisible())
        {
            return PANEL_CAMERA;
        }

        return panel.replayEditor.isVisible() ? PANEL_REPLAY : -1;
    }

    private static UIKeyframes getKeyframes(UIFilmPanel panel)
    {
        UIReplaysEditor editor = panel.replayEditor;

        return editor.keyframeEditor == null ? null : editor.keyframeEditor.view;
    }

    private static void sendCursor(UIFilmPanel panel, Film film, UIContext context)
    {
        long now = System.currentTimeMillis();

        if (now - lastCursorTime < CURSOR_INTERVAL_MS || (context.mouseX == lastMouseX && context.mouseY == lastMouseY))
        {
            return;
        }

        int type = getPanelType(panel);
        PacketByteBuf body = PacketByteBufs.create();
        boolean over = false;

        if (type == PANEL_CAMERA)
        {
            UIClips clips = panel.cameraEditor.clips;

            if (clips.area.isInside(context))
            {
                int layerH = Math.max(clips.toLayerY(0) - clips.toLayerY(1), 1);
                float layer = (clips.toLayerY(0) + layerH - context.mouseY) / (float) layerH;

                body.writeByte(PANEL_CAMERA);
                body.writeString("");
                body.writeFloat((float) clips.fromGraphX(context.mouseX));
                body.writeFloat(layer);
                body.writeString("");

                List<Integer> selection = clips.getSelection();

                body.writeInt(selection.size());

                for (int index : selection)
                {
                    body.writeString("");
                    body.writeInt(index);
                }

                over = true;
            }
        }
        else if (type == PANEL_REPLAY)
        {
            UIKeyframes view = getKeyframes(panel);
            Replay replay = panel.replayEditor.getReplay();

            if (view != null && replay != null && view.area.isInside(context) && view.getGraph() instanceof UIKeyframeDopeSheet sheet)
            {
                UIKeyframeSheet hovered = sheet.getSheet(context.mouseY);

                if (hovered != null)
                {
                    int top = sheet.getDopeSheetY(hovered);
                    float fraction = (context.mouseY - top) / (float) Math.max(sheet.getTrackHeight(hovered), 1);

                    body.writeByte(PANEL_REPLAY);
                    body.writeString(replay.getId());
                    body.writeFloat((float) view.fromGraphX(context.mouseX));
                    body.writeFloat(Math.max(0F, Math.min(0.999F, fraction)));
                    body.writeString(hovered.id);

                    List<Map.Entry<String, Integer>> selection = new ArrayList<>();

                    for (UIKeyframeSheet s : sheet.getSheets())
                    {
                        for (int index : s.selection.getIndices())
                        {
                            selection.add(new AbstractMap.SimpleEntry<>(s.id, index));
                        }
                    }

                    body.writeInt(selection.size());

                    for (Map.Entry<String, Integer> entry : selection)
                    {
                        body.writeString(entry.getKey());
                        body.writeInt(entry.getValue());
                    }

                    over = true;
                }
            }
        }

        lastCursorTime = now;
        lastMouseX = context.mouseX;
        lastMouseY = context.mouseY;

        /* Off the timelines it is enough to say so once */
        if (!over && !lastCursorOver)
        {
            return;
        }

        lastCursorOver = over;

        PacketByteBuf buf = PacketByteBufs.create();

        buf.writeString(film.getId());

        if (over)
        {
            buf.writeBytes(body);
        }

        send(TogetherServer.SERVER_CURSOR, buf);
    }

    private static void handleCursor(PacketByteBuf buf)
    {
        UUID uuid = buf.readUuid();

        if (buf.readableBytes() == 0)
        {
            MinecraftClient.getInstance().execute(() -> cursors.remove(uuid));

            return;
        }

        int type = buf.readByte();
        String replayId = buf.readString();
        float tick = buf.readFloat();
        float y = buf.readFloat();
        String track = buf.readString();
        List<Map.Entry<String, Integer>> selection = new ArrayList<>();

        for (int i = 0, c = buf.readInt(); i < c; i++)
        {
            selection.add(new AbstractMap.SimpleEntry<>(buf.readString(), buf.readInt()));
        }

        MinecraftClient.getInstance().execute(() ->
        {
            Cursor cursor = cursors.get(uuid);

            if (cursor == null || cursor.type != type || !cursor.replayId.equals(replayId))
            {
                cursors.put(uuid, new Cursor(type, replayId, tick, y, track, selection));
            }
            else
            {
                cursor.target(tick, y, track, selection);
            }
        });
    }

    private static void renderCursors(UIFilmPanel panel, Film film, UIContext context)
    {
        int type = getPanelType(panel);
        Replay replay = panel.replayEditor.getReplay();

        for (Map.Entry<UUID, Cursor> entry : cursors.entrySet())
        {
            Cursor cursor = entry.getValue();
            Presence p = presence.get(entry.getKey());

            if (p == null || !p.filmId.equals(film.getId()) || cursor.type != type)
            {
                continue;
            }

            int color = getColor(entry.getKey());

            cursor.interpolate();

            if (type == PANEL_CAMERA)
            {
                renderCameraCursor(panel.cameraEditor.clips, film, cursor, color, entry.getKey(), context);
            }
            else if (type == PANEL_REPLAY && replay != null && cursor.replayId.equals(replay.getId()))
            {
                renderReplayCursor(getKeyframes(panel), cursor, color, entry.getKey(), context);
            }
        }
    }

    private static void renderCameraCursor(UIClips clips, Film film, Cursor cursor, int color, UUID uuid, UIContext context)
    {
        int layerH = Math.max(clips.toLayerY(0) - clips.toLayerY(1), 1);
        int bottom = clips.toLayerY(0) + layerH;
        List<Clip> list = film.camera.get();

        context.batcher.clip(clips.area, context);

        for (Map.Entry<String, Integer> selected : cursor.selection)
        {
            Clip clip = CollectionUtils.getSafe(list, selected.getValue());

            if (clip != null)
            {
                int x1 = clips.toGraphX(clip.tick.get());
                int x2 = Math.max(clips.toGraphX(clip.tick.get() + clip.duration.get()), x1 + 2);
                int y1 = clips.toLayerY(clip.layer.get());

                context.batcher.outline(x1 - 1, y1 - 1, x2 + 1, y1 + layerH + 1, color, 2);
            }
        }

        int x = clips.toGraphX(cursor.tick);
        int y = (int) (bottom - cursor.y * layerH);

        renderPointer(context, x, y, color, uuid);
        context.batcher.unclip(context);
    }

    private static void renderReplayCursor(UIKeyframes view, Cursor cursor, int color, UUID uuid, UIContext context)
    {
        if (view == null || !(view.getGraph() instanceof UIKeyframeDopeSheet sheets))
        {
            return;
        }

        context.batcher.clip(view.area, context);

        for (Map.Entry<String, Integer> selected : cursor.selection)
        {
            UIKeyframeSheet sheet = sheets.getSheet(selected.getKey());
            Keyframe keyframe = sheet == null ? null : sheet.channel.get(selected.getValue());

            if (keyframe != null)
            {
                int kx = view.toGraphX(keyframe.getTick());
                int ky = sheets.getDopeSheetY(sheet) + sheets.getTrackHeight(sheet) / 2;

                context.batcher.outline(kx - 5, ky - 5, kx + 5, ky + 5, color, 2);
            }
        }

        UIKeyframeSheet sheet = sheets.getSheet(cursor.track);

        if (sheet != null && sheets.getSheets().contains(sheet))
        {
            int x = view.toGraphX(cursor.tick);
            int y = (int) (sheets.getDopeSheetY(sheet) + cursor.y * sheets.getTrackHeight(sheet));

            renderPointer(context, x, y, color, uuid);
        }

        context.batcher.unclip(context);
    }

    private static void renderPointer(UIContext context, int x, int y, int color, UUID uuid)
    {
        context.batcher.icon(Icons.CURSOR, color, x, y);
        context.batcher.textCard(getName(uuid), x + 12, y + 12, Colors.WHITE, Colors.setA(color, 0.75F));
    }

    /* Heads */

    /** Heads of the other players that have the given film open, right-aligned at {@code right}. */
    public static void renderFilmHeads(UIContext context, String filmId, int right, int y, int h)
    {
        if (!isActive() || !BBSSettings.togetherHeads.get())
        {
            return;
        }

        List<UUID> players = new ArrayList<>();
        UUID local = localUuid();

        for (Map.Entry<UUID, Presence> entry : presence.entrySet())
        {
            if (!entry.getKey().equals(local) && entry.getValue().filmId.equals(filmId))
            {
                players.add(entry.getKey());
            }
        }

        renderHeads(context, players, right, y + (h - HEAD_SIZE) / 2);
    }

    /** Heads of the other players whose selected replay is this one. */
    public static void renderReplayHeads(UIContext context, String filmId, String replayId, int right, int y, int h)
    {
        if (!isActive() || !BBSSettings.togetherHeads.get())
        {
            return;
        }

        List<UUID> players = new ArrayList<>();
        UUID local = localUuid();

        for (Map.Entry<UUID, Presence> entry : presence.entrySet())
        {
            Presence p = entry.getValue();

            if (!entry.getKey().equals(local) && p.filmId.equals(filmId) && p.replayId.equals(replayId))
            {
                players.add(entry.getKey());
            }
        }

        renderHeads(context, players, right, y + (h - HEAD_SIZE) / 2);
    }

    private static void renderHeads(UIContext context, List<UUID> players, int right, int y)
    {
        ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();

        if (players.isEmpty() || handler == null)
        {
            return;
        }

        int x = right - players.size() * (HEAD_SIZE + 2);
        DrawContext drawContext = context.batcher.getContext();

        for (UUID uuid : players)
        {
            PlayerListEntry entry = handler.getPlayerListEntry(uuid);

            if (entry != null)
            {
                context.batcher.box(x - 1, y - 1, x + HEAD_SIZE + 1, y + HEAD_SIZE + 1, getColor(uuid));
                PlayerSkinDrawer.draw(drawContext, entry.getSkinTextures(), x, y, HEAD_SIZE);

                x += HEAD_SIZE + 2;
            }
        }
    }

    /* Other players in the editor */

    /**
     * Editing hides the players around (see {@code MorphRenderer.hidePlayer}); other people in the
     * editor fly about as cameras, so they are hidden too instead of floating through the shot.
     */
    public static boolean isInEditor(AbstractClientPlayerEntity player)
    {
        Presence p = isActive() ? presence.get(player.getUuid()) : null;

        return p != null && p.inEditor && !player.getUuid().equals(localUuid());
    }

    /** Playback in the editor affects the world, which is worth knowing while just playing. */
    private static void renderHud(DrawContext context)
    {
        if (!isActive() || UIScreen.getCurrentMenu() instanceof UIDashboard)
        {
            return;
        }

        UUID local = localUuid();
        int count = 0;

        for (Map.Entry<UUID, Presence> entry : presence.entrySet())
        {
            if (entry.getValue().inEditor && !entry.getKey().equals(local))
            {
                count += 1;
            }
        }

        if (count > 0)
        {
            MinecraftClient mc = MinecraftClient.getInstance();
            String message = "⚠ " + (count == 1 ? IN_EDITOR_ONE.get() : IN_EDITOR.format(count).get());

            context.drawCenteredTextWithShadow(mc.textRenderer, Text.literal(message).formatted(Formatting.RED), context.getScaledWindowWidth() / 2, context.getScaledWindowHeight() - 52, Colors.WHITE | Colors.A100);
        }
    }

    /* Form locks */

    private static void handleFormLocks(PacketByteBuf buf)
    {
        String filmId = buf.readString();
        Map<String, UUID> locks = new HashMap<>();

        for (int i = 0, c = buf.readInt(); i < c; i++)
        {
            UUID uuid = buf.readUuid();

            locks.putIfAbsent(buf.readString(), uuid);
        }

        MinecraftClient.getInstance().execute(() ->
        {
            if (locks.isEmpty())
            {
                formLocks.remove(filmId);
            }
            else
            {
                formLocks.put(filmId, locks);
            }
        });
    }

    /** The other player that has the form palette of this replay open, or null. */
    public static UUID getFormLockOwner(String filmId, String replayId)
    {
        Map<String, UUID> locks = isActive() ? formLocks.get(filmId) : null;
        UUID owner = locks == null ? null : locks.get(replayId);

        return owner == null || owner.equals(localUuid()) ? null : owner;
    }

    public static void sendFormLock(String filmId, String replayId, boolean locked)
    {
        if (!isActive())
        {
            return;
        }

        PacketByteBuf buf = PacketByteBufs.create();

        buf.writeString(filmId);
        buf.writeString(replayId);
        buf.writeBoolean(locked);

        send(TogetherServer.SERVER_FORM_LOCK, buf);
    }

    /* Group recording */

    /** The same as the record key, only started and stopped for everyone by /bbs record. */
    private static void handleToggleRecording(PacketByteBuf buf)
    {
        boolean start = buf.readBoolean();

        MinecraftClient.getInstance().execute(() ->
        {
            UIFilmPanel panel = getFilmPanel();
            Film film = panel == null ? null : panel.getData();
            Films films = BBSModClient.getFilms();

            if (film == null)
            {
                return;
            }

            if (start)
            {
                Replay replay = panel.replayEditor.getReplay();
                int index = film.replays.getList().indexOf(replay);

                if (films.getRecorder() == null && index >= 0)
                {
                    films.startRecording(film, index, 0);
                }

                return;
            }

            Recorder recorder = films.getRecorder() == null ? null : films.stopRecording();

            if (recorder == null || recorder.hasNotStarted())
            {
                return;
            }

            /* Only into the replay itself: the take of everyone else recording at the same time is
             * on its way here as an edit of their own replay, and an edit of the whole film (which
             * the record key makes) would cross it and overwrite it */
            Replay replay = CollectionUtils.getSafe(film.replays.getList(), recorder.exception);

            if (replay != null)
            {
                recorder.keyframes.compressItemChannels();
                BaseValue.edit(replay.keyframes, (keyframes) -> keyframes.copyOver(recorder.keyframes, 0));

                /* The editor hands its edits out only while it is drawn, and a group take usually
                 * ends with the screen closed — sent right away, before our own save reaches the
                 * others and makes them think they missed something */
                lastLocalEdit = System.currentTimeMillis();

                if (hasOtherEditors(film.getId()))
                {
                    sendEdit(film, replay.keyframes);
                }

                panel.forceSave();
            }
        });
    }

    /* Asset sync */

    private static void handleAssetManifest(PacketByteBuf buf)
    {
        crusher.receive(buf, (bytes, packetByteBuf) ->
        {
            boolean download = BBSSettings.togetherAssetDownload.get();
            boolean upload = BBSSettings.togetherAssetUpload.get();

            if ((!download && !upload) || !(DataStorageUtils.readFromBytes(bytes) instanceof MapType manifest))
            {
                return;
            }

            Thread thread = new Thread(() -> syncAssets(manifest, download, upload), "BBS-AssetManifestCheck");

            thread.setDaemon(true);
            thread.start();
        });
    }

    private static void syncAssets(MapType manifest, boolean download, boolean upload)
    {
        try
        {
            Path root = BBSMod.getAssetsFolder().toPath().toAbsolutePath().normalize();
            Map<String, String> server = new HashMap<>();

            for (Map.Entry<String, BaseType> entry : manifest)
            {
                server.put(entry.getKey(), entry.getValue().asString());
            }

            if (download)
            {
                List<String> needed = new ArrayList<>();

                for (Map.Entry<String, String> entry : server.entrySet())
                {
                    Path local = TogetherServer.resolveAsset(root, entry.getKey());

                    if (local != null && (!Files.isRegularFile(local) || !entry.getValue().equalsIgnoreCase(TogetherServer.sha1(local))))
                    {
                        needed.add(entry.getKey());
                    }
                }

                if (!needed.isEmpty())
                {
                    TogetherServer.LOGGER.info("Requesting {} asset(s) from the server", needed.size());
                    pendingDownloads.set(needed.size());
                    downloadTotal = needed.size();

                    MinecraftClient.getInstance().execute(() ->
                    {
                        /* Batched, so that a large folder doesn't make one oversized packet */
                        for (int from = 0; from < needed.size(); from += 200)
                        {
                            List<String> batch = needed.subList(from, Math.min(from + 200, needed.size()));
                            PacketByteBuf request = PacketByteBufs.create();

                            request.writeInt(batch.size());

                            for (String path : batch)
                            {
                                request.writeString(path);
                            }

                            send(TogetherServer.SERVER_ASSET_REQUEST, request);
                        }
                    });
                }
            }

            if (upload && Files.isDirectory(root))
            {
                List<Map.Entry<String, byte[]>> files = new ArrayList<>();

                try (var stream = Files.walk(root))
                {
                    for (Path local : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator)
                    {
                        String relative = root.relativize(local).toString().replace(File.separatorChar, '/');
                        String sha1 = server.get(relative);

                        if (sha1 == null || !sha1.equalsIgnoreCase(TogetherServer.sha1(local)))
                        {
                            files.add(new AbstractMap.SimpleEntry<>(relative, Files.readAllBytes(local)));
                        }
                    }
                }

                if (!files.isEmpty())
                {
                    MinecraftClient.getInstance().execute(() ->
                    {
                        for (Map.Entry<String, byte[]> file : files)
                        {
                            crusher.send(MinecraftClient.getInstance().player, TogetherServer.SERVER_ASSET_UPLOAD, file.getValue(), (out) -> out.writeString(file.getKey()));
                        }
                    });
                }
            }
        }
        catch (Exception e)
        {
            TogetherServer.LOGGER.error("Asset sync failed", e);
        }
    }

    private static void handleAssetFile(PacketByteBuf buf)
    {
        crusher.receive(buf, (bytes, packetByteBuf) ->
        {
            String relative = packetByteBuf.readString();
            boolean push = packetByteBuf.readableBytes() > 0 && packetByteBuf.readBoolean();

            if (push ? !BBSSettings.togetherAssetReceive.get() : !BBSSettings.togetherAssetDownload.get())
            {
                return;
            }

            Path root = BBSMod.getAssetsFolder().toPath().toAbsolutePath().normalize();
            Path destination = TogetherServer.resolveAsset(root, relative);

            if (destination == null)
            {
                TogetherServer.LOGGER.warn("Server sent a suspicious asset path: {}", relative);

                return;
            }

            byte[] content = bytes == null ? new byte[0] : bytes;
            Thread thread = new Thread(() ->
            {
                try
                {
                    Files.createDirectories(destination.getParent());
                    Files.write(destination, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
                catch (Exception e)
                {
                    TogetherServer.LOGGER.error("Failed to write asset " + relative, e);
                }

                if (!push && pendingDownloads.decrementAndGet() == 0)
                {
                    MinecraftClient.getInstance().execute(TogetherClient::onDownloadsFinished);
                }
            }, "BBS-AssetWrite");

            thread.setDaemon(true);
            thread.start();
        });
    }

    private static void onDownloadsFinished()
    {
        TogetherServer.LOGGER.info("Assets from the server are up to date");

        UIDashboard dashboard = BBSModClient.getDashboardIfCreated();

        if (dashboard != null && dashboard.context != null)
        {
            dashboard.context.notifyInfo(ASSETS_DOWNLOADED.format(downloadTotal));
        }
    }

    /* Types */

    public record Presence(String filmId, String replayId, boolean inEditor, int color, boolean receivesAssets)
    {}

    public static class Cursor
    {
        public final int type;
        public final String replayId;
        public float tick;
        public float y;
        public String track;
        public List<Map.Entry<String, Integer>> selection;

        private float targetTick;
        private float targetY;

        public Cursor(int type, String replayId, float tick, float y, String track, List<Map.Entry<String, Integer>> selection)
        {
            this.type = type;
            this.replayId = replayId;
            this.tick = this.targetTick = tick;
            this.y = this.targetY = y;
            this.track = track;
            this.selection = selection;
        }

        public void target(float tick, float y, String track, List<Map.Entry<String, Integer>> selection)
        {
            /* A jump to another track is not something to glide across the other tracks for */
            if (!track.equals(this.track))
            {
                this.y = y;
            }

            this.targetTick = tick;
            this.targetY = y;
            this.track = track;
            this.selection = selection;
        }

        public void interpolate()
        {
            this.tick += (this.targetTick - this.tick) * 0.25F;
            this.y += (this.targetY - this.y) * 0.25F;
        }
    }
}
