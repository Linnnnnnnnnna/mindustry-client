package mindustry.client;

import arc.*;
import arc.math.*;
import arc.util.*;
import mindustry.client.antigrief.*;
import mindustry.client.navigation.*;
import mindustry.client.ui.ChangelogDialog;
import mindustry.client.utils.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.input.*;
import mindustry.net.*;
import mindustry.world.*;

import static arc.Core.*;
import static mindustry.Vars.*;

public class Client {
    public static void initialize() {
        registerCommands();

        Events.on(WorldLoadEvent.class, event -> {
            Main.INSTANCE.setPluginNetworking(false);
            if (Time.timeSinceMillis(ClientVars.lastSyncTime) > 5000) {
                TileLogs.INSTANCE.reset(world);
            }
            PowerInfo.initialize();
            Navigation.stopFollowing();
            Navigation.obstacles.clear();
            ClientVars.configs.clear();
            ui.unitPicker.found = null;
            control.input.lastVirusWarning = null;

            ClientVars.showingTurrets = ClientVars.hidingUnits = ClientVars.hidingBlocks = ClientVars.dispatchingBuildPlans = false;

            if (state.rules.pvp) ui.announce("[scarlet]Don't use a client in pvp, it's uncool!", 5);
        });

        Events.on(EventType.ClientLoadEvent.class, event -> {
            int changeHash = Core.files.internal("changelog").readString().hashCode(); // Display changelog if the file contents have changed & on first run. (this is really scuffed lol)
            if (settings.getInt("changeHash") != changeHash) ChangelogDialog.INSTANCE.show();
            settings.put("changeHash", changeHash);

            if (settings.getBool("debug")) Log.level = Log.LogLevel.debug; // Set log level to debug if the setting is checked
            if (Core.settings.getBool("discordrpc")) platform.startDiscord();

            Autocomplete.autocompleters.add(new BlockEmotes());
            Autocomplete.autocompleters.add(new PlayerCompletion());
            Autocomplete.autocompleters.add(new CommandCompletion());
            Autocomplete.initialize();
            Navigation.navigator.init();
        });
    }


    public static void update() {
        Navigation.update();
        PowerInfo.update();
        Spectate.INSTANCE.update();

        if (!ClientVars.configs.isEmpty()) {
                try {
                    if (ClientVars.configRateLimit.allow(Administration.Config.interactRateWindow.num() * 1000L, Administration.Config.interactRateLimit.num())) {
                        ConfigRequest req = ClientVars.configs.removeLast();
                        Tile tile = world.tile(req.x, req.y);
                        if (tile != null) {
//                            Object initial = tile.build.config();
                            req.run();
//                            Timer.schedule(() -> {
//                                 if(tile.build != null && tile.build.config() == initial) configs.addLast(req); TODO: This can also cause loops
//                                 if(tile.build != null && req.value != tile.build.config()) configs.addLast(req); TODO: This infinite loops if u config something twice, find a better way to do this
//                            }, net.client() ? netClient.getPing()/1000f+.05f : .025f);
                        }
                    }
                } catch (Exception e) { Log.err(e); }
        }
    }

    private static void registerCommands(){
        ClientVars.clientCommandHandler.<Player>register("help", "[page]", "Lists all client commands.", (args, player) -> {
            if(args.length > 0 && !Strings.canParseInt(args[0])){
                player.sendMessage("[scarlet]'page' must be a number.");
                return;
            }
            int commandsPerPage = 6;
            int page = args.length > 0 ? Strings.parseInt(args[0]) : 1;
            int pages = Mathf.ceil((float)ClientVars.clientCommandHandler.getCommandList().size / commandsPerPage);

            page --;

            if(page >= pages || page < 0){
                player.sendMessage("[scarlet]'page' must be a number between[orange] 1[] and[orange] " + pages + "[scarlet].");
                return;
            }

            StringBuilder result = new StringBuilder();
            result.append(Strings.format("[orange]-- Client Commands Page[lightgray] @[gray]/[lightgray]@[orange] --\n\n", (page+1), pages));

            for(int i = commandsPerPage * page; i < Math.min(commandsPerPage * (page + 1), ClientVars.clientCommandHandler.getCommandList().size); i++){
                CommandHandler.Command command = ClientVars.clientCommandHandler.getCommandList().get(i);
                result.append("[orange] !").append(command.text).append("[white] ").append(command.paramText).append("[lightgray] - ").append(command.description).append("\n");
            }
            player.sendMessage(result.toString());
        });

        ClientVars.clientCommandHandler.<Player>register("unit", "<unit-name>", "Swap to specified unit", (args, player) ->
            ui.unitPicker.findUnit(content.units().copy().sort(b -> BiasedLevenshtein.biasedLevenshtein(args[0], b.name)).first())
        );

        ClientVars.clientCommandHandler.<Player>register("go","[x] [y]", "Navigates to (x, y) or the last coordinates posted to chat", (args, player) -> {
            try {
                if (args.length == 2) ClientVars.lastSentPos.set(Float.parseFloat(args[0]), Float.parseFloat(args[1]));
                Navigation.navigateTo(ClientVars.lastSentPos.cpy().scl(tilesize));
            } catch(NumberFormatException | IndexOutOfBoundsException e) {
                player.sendMessage("[scarlet]Invalid coordinates, format is [x] [y] Eg: !go 10 300 or !go");
            }
        });

        ClientVars.clientCommandHandler.<Player>register("lookat","[x] [y]", "Moves camera to (x, y) or the last coordinates posted to chat", (args, player) -> {
            try {
                DesktopInput.panning = true;
                if (args.length == 2) ClientVars.lastSentPos.set(Float.parseFloat(args[0]), Float.parseFloat(args[1]));
                Spectate.INSTANCE.spectate(ClientVars.lastSentPos.cpy().scl(tilesize));
            } catch(NumberFormatException | IndexOutOfBoundsException e) {
                player.sendMessage("[scarlet]Invalid coordinates, format is [x] [y] Eg: !lookat 10 300 or !lookat");
            }
        });

        ClientVars.clientCommandHandler.<Player>register("here", "[message...]", "Prints your location to chat with an optional message", (args, player) ->
            Call.sendChatMessage(Strings.format("@(@, @)", args.length == 0 ? "" : args[0] + " ", player.tileX(), player.tileY()))
        );

        ClientVars.clientCommandHandler.<Player>register("cursor", "[message...]", "Prints cursor location to chat with an optional message", (args, player) ->
            Call.sendChatMessage(Strings.format("@(@, @)", args.length == 0 ? "" : args[0] + " ", control.input.rawTileX(), control.input.rawTileY()))
        );

        ClientVars.clientCommandHandler.<Player>register("builder", "[options...]", "Starts auto build with optional arguments, prioritized from first to last.", (args, player) ->
            Navigation.follow(new BuildPath(args.length  == 0 ? "" : args[0])) // TODO: This is so scuffed lol
        );

        ClientVars.clientCommandHandler.<Player>register("tp", "<x> <y>", "Teleports to (x, y), only works on servers without strict mode enabled.", (args, player) -> {
            try {
                NetClient.setPosition(World.unconv(Float.parseFloat(args[0])), World.unconv(Float.parseFloat(args[1])));
            } catch(Exception e) {
                player.sendMessage("[scarlet]Invalid coordinates, format is <x> <y> Eg: !tp 10 300");
            }
        });

        ClientVars.clientCommandHandler.<Player>register("", "[message...]", "Lets you start messages with an !", (args, player) ->
            Call.sendChatMessage("!" + (args.length == 1 ? args[0] : ""))
        );

        ClientVars.clientCommandHandler.<Player>register("shrug", "[message...]", "Sends the shrug unicode emoji with an optional message", (args, player) ->
            Call.sendChatMessage("¯\\_(ツ)_/¯ " + (args.length == 1 ? args[0] : ""))
        );

        ClientVars.clientCommandHandler.<Player>register("login", "[name] [pw]", "Used for CN. [scarlet]Don't use this if you care at all about security.", (args, player) -> {
            if (args.length == 2) settings.put("cnpw", args[0] + " "  + args[1]);
            else Call.sendChatMessage("/login " + settings.getString("cnpw", ""));
        });

        ClientVars.clientCommandHandler.<Player>register("js", "<code...>", "Runs JS on the client.", (args, player) ->
            player.sendMessage("[accent]" + mods.getScripts().runConsole(args[0]))
        );
    }
}