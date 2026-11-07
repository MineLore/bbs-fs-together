package mchorse.bbs_mod.gametest;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.entity.ActorEntity;
import mchorse.bbs_mod.forms.FormUtils;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.client.option.Perspective;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Screenshots of what BBS draws in a real client (run/screenshots), in singleplayer and on a
 * dedicated server: armor on a morphed player and on an actor next to them, then a video form.
 * Armor pieces are of different materials, so a missing slot is obvious at a glance.
 */
public class RenderGameTest implements FabricClientGameTest
{
    private static final String VIDEO = "videos/gametest.mp4";
    private static final String STEVE = "{id:\"bbs:model\",model:\"player/steve\"}";

    @Override
    public void runTest(ClientGameTestContext context)
    {
        Path video = context.computeOnClient((client) -> client.runDirectory.toPath().resolve("config/bbs/assets/" + VIDEO));

        makeVideo(video);

        context.runOnClient((client) ->
        {
            client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
            client.options.hudHidden = true;
        });

        try (TestSingleplayerContext world = context.worldBuilder()
            .adjustSettings((creator) -> creator.setGameMode(WorldCreator.Mode.CREATIVE))
            .create())
        {
            world.getClientWorld().waitForChunksRender();
            scene(context, world.getServer(), "sp");
        }

        Properties properties = new Properties();

        properties.setProperty("level-type", "minecraft\\:flat");
        properties.setProperty("gamemode", "creative");
        properties.setProperty("spawn-protection", "0");

        try (TestDedicatedServerContext server = context.worldBuilder().createServer(properties);
             TestServerConnection connection = server.connect())
        {
            connection.getClientWorld().waitForChunksRender();
            scene(context, server, "mp");
        }
    }

    private static void scene(ClientGameTestContext context, TestServerContext server, String prefix)
    {
        server.runCommand("time set noon");
        server.runCommand("weather clear");
        server.runCommand("gamemode creative @a");
        server.runCommand("tp @a 0.5 -60 0.5 180 0");
        server.runCommand("item replace entity @a armor.head with minecraft:iron_helmet");
        server.runCommand("item replace entity @a armor.chest with minecraft:diamond_chestplate");
        server.runCommand("item replace entity @a armor.legs with minecraft:golden_leggings");
        server.runCommand("item replace entity @a armor.feet with minecraft:netherite_boots");
        server.runCommand("item replace entity @a weapon.offhand with minecraft:shield");

        context.waitTicks(40);
        context.takeScreenshot(prefix + "-armor-vanilla");

        server.runCommand("bbs morph @a " + STEVE);
        server.runOnServer((s) ->
        {
            ServerWorld world = s.getOverworld();
            ActorEntity actor = BBSMod.ACTOR_ENTITY.create(world, SpawnReason.COMMAND);

            actor.refreshPositionAndAngles(-1.5, -60, 0.5, 180F, 0F);
            actor.setHeadYaw(180F);
            actor.setBodyYaw(180F);
            actor.setForm(FormUtils.fromData(DataToString.mapFromString(STEVE)));
            actor.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            actor.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
            actor.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.GOLDEN_LEGGINGS));
            actor.equipStack(EquipmentSlot.FEET, new ItemStack(Items.NETHERITE_BOOTS));
            actor.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            world.spawnEntity(actor);
        });

        context.waitTicks(60);
        context.takeScreenshot(prefix + "-armor-bbs");

        server.runCommand("bbs morph @a {id:\"bbs:video\",video:\"assets:" + VIDEO + "\"}");
        context.waitTicks(100);
        context.takeScreenshot(prefix + "-video-form");

        server.runCommand("kill @e[type=bbs:actor]");
        server.runCommand("bbs morph @a");
    }

    /** A few seconds of ffmpeg's test pattern - a video form that shows nothing is then obvious. */
    private static void makeVideo(Path video)
    {
        try
        {
            Files.createDirectories(video.getParent());

            Process process = new ProcessBuilder("ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=20:duration=5", "-pix_fmt", "yuv420p", video.toString())
                .redirectErrorStream(true)
                .redirectOutput(new File(video + ".log"))
                .start();

            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0)
            {
                throw new IllegalStateException("ffmpeg could not make the test video, see " + video + ".log");
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }
}
