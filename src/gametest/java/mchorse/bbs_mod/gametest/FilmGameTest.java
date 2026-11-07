package mchorse.bbs_mod.gametest;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.entity.ActorEntity;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.ReplayKeyframes;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.client.option.Perspective;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Plays a real film (-Dbbs.gametest.film=/path/Name.dat) the way a player sees it: logs every replay's form tree
 * and dress at tick 0, stands in front of the first dressed replay and screenshots the film playing.
 */
public class FilmGameTest implements FabricClientGameTest
{
    @Override
    public void runTest(ClientGameTestContext context)
    {
        String film = System.getProperty("bbs.gametest.film", "");

        if (film.isEmpty())
        {
            return;
        }

        Path source = Path.of(film);
        String id = source.getFileName().toString().replaceFirst("\\.dat$", "");

        try (TestSingleplayerContext world = context.worldBuilder()
            .adjustSettings((creator) -> creator.setGameMode(WorldCreator.Mode.CREATIVE))
            .create())
        {
            world.getClientWorld().waitForChunksRender();

            List<double[]> spots = world.getServer().computeOnServer((server) ->
            {
                Path films = server.getSavePath(WorldSavePath.ROOT).resolve("bbs/films");

                try
                {
                    Files.createDirectories(films);
                    Files.copy(source, films.resolve(source.getFileName()));
                }
                catch (java.io.IOException e)
                {
                    throw new java.io.UncheckedIOException(e);
                }

                Film loaded = BBSMod.getFilms().load(id);
                List<Replay> replays = loaded.replays.getAllTyped();
                List<double[]> found = new java.util.ArrayList<>();

                System.out.println("[FILM] " + id + ": " + replays.size() + " replays");

                for (int i = 0; i < replays.size(); i++)
                {
                    Replay replay = replays.get(i);
                    ReplayKeyframes k = replay.keyframes;
                    Form form = replay.form.get();
                    double x = k.x.interpolate(0F, 0D), y = k.y.interpolate(0F, 0D), z = k.z.interpolate(0F, 0D);
                    StringBuilder dress = new StringBuilder();

                    for (EquipmentSlot slot : ReplayKeyframes.DRESS_SLOTS)
                    {
                        ItemStack stack = k.getEquipmentChannel(slot).interpolate(0F, ItemStack.EMPTY);

                        if (!stack.isEmpty()) dress.append(slot.getName()).append('=').append(stack.getItem()).append(' ');
                    }

                    String data = form == null ? "null" : DataToString.toString(FormUtils.toData(form), true);

                    System.out.println(String.format(Locale.ROOT, "[FILM] #%d enabled=%s actor=%s pos=%.1f %.1f %.1f dress=[%s]", i, replay.enabled.get(), replay.actor.get(), x, y, z, dress.toString().trim()));
                    System.out.println("[FILM] #" + i + " form=" + data.replace('\n', ' ').substring(0, Math.min(data.length(), 3000)));
                    System.out.println("[FILM] #" + i + " actions=" + replay.actions.get().stream()
                        .map((clip) -> clip.getClass().getSimpleName() + "@" + clip.tick.get()).toList());
                    System.out.println("[FILM] #" + i + " trims=" + java.util.Arrays.stream(ReplayKeyframes.DRESS_SLOTS)
                        .map((slot) -> k.getEquipmentChannel(slot).interpolate(0F, ItemStack.EMPTY).get(net.minecraft.component.DataComponentTypes.TRIM))
                        .filter(java.util.Objects::nonNull).map(Object::toString).toList());

                    if (dress.length() > 0)
                    {
                        found.add(new double[] {x, y, z});
                    }
                }

                return found;
            });

            if (spots.isEmpty())
            {
                System.out.println("[FILM] no dressed replay");

                return;
            }

            double[] spot = spots.get(0);
            int bx = (int) Math.floor(spot[0]), by = (int) Math.floor(spot[1]), bz = (int) Math.floor(spot[2]);

            world.getServer().runCommand("time set noon");
            world.getServer().runCommand("weather clear");
            String tp = String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f 90 10", spot[0] + 3.5, spot[1], spot[2]);

            /* The film is far from spawn: get there and let the chunks load before building anything to stand on */
            world.getServer().runCommand(tp);
            world.getServer().runOnServer((server) -> server.getPlayerManager().getPlayerList().forEach((player) ->
            {
                player.getAbilities().flying = true;
                player.sendAbilitiesUpdate();
            }));
            context.waitTicks(20);
            world.getClientWorld().waitForChunksRender();
            world.getServer().runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", bx - 4, by - 1, bz - 4, bx + 4, by - 1, bz + 4));
            world.getServer().runCommand(tp);
            context.runOnClient((client) ->
            {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                client.options.hudHidden = true;
            });
            context.waitTicks(40);
            /* The same body and dress on a plain actor next to the film's: tells a model problem from a film one */
            world.getServer().runOnServer((server) ->
            {
                Film loaded = BBSMod.getFilms().load(id);

                for (Replay replay : loaded.replays.getAllTyped())
                {
                    ReplayKeyframes k = replay.keyframes;

                    if (k.getEquipmentChannel(EquipmentSlot.CHEST).interpolate(0F, ItemStack.EMPTY).isEmpty()) continue;

                    ActorEntity actor = BBSMod.ACTOR_ENTITY.create(server.getOverworld(), net.minecraft.entity.SpawnReason.COMMAND);

                    actor.refreshPositionAndAngles(spot[0], spot[1], spot[2] + 1.5, 270F, 0F);
                    actor.setHeadYaw(270F);
                    actor.setBodyYaw(270F);
                    actor.setForm(FormUtils.copy(replay.form.get()));

                    for (EquipmentSlot slot : ReplayKeyframes.DRESS_SLOTS) actor.equipStack(slot, k.getEquipmentChannel(slot).interpolate(0F, ItemStack.EMPTY).copy());

                    server.getOverworld().spawnEntity(actor);

                    break;
                }
            });
            world.getServer().runCommand("bbs films @a play \"" + id + "\" false");

            for (int shot = 1; shot <= 4; shot++)
            {
                context.waitTicks(shot == 1 ? 20 : 40);
                context.takeScreenshot("film-" + shot);
            }

            /* Every other dressed replay of the film, one shot each, while it is still playing */
            for (int i = 1; i < spots.size(); i++)
            {
                double[] other = spots.get(i);
                int ox = (int) Math.floor(other[0]), oy = (int) Math.floor(other[1]), oz = (int) Math.floor(other[2]);

                world.getServer().runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", ox - 4, oy - 1, oz - 4, ox + 4, oy - 1, oz + 4));
                world.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f 90 10", other[0] + 3.5, other[1], other[2]));
                context.waitTicks(10);
                context.takeScreenshot("film-replay-" + i);
            }
        }
    }
}
