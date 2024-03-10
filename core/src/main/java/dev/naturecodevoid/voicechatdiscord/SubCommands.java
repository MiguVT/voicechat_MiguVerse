package dev.naturecodevoid.voicechatdiscord;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import dev.naturecodevoid.voicechatdiscord.audiotransfer.DiscordBot;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.mojang.brigadier.arguments.BoolArgumentType.bool;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;
import static com.mojang.brigadier.builder.RequiredArgumentBuilder.argument;
import static dev.naturecodevoid.voicechatdiscord.Constants.RELOAD_CONFIG_PERMISSION;
import static dev.naturecodevoid.voicechatdiscord.Core.*;
import static dev.naturecodevoid.voicechatdiscord.GroupManager.*;
import static dev.naturecodevoid.voicechatdiscord.util.Util.getArgumentOr;

/**
 * Subcomandos para /dvc
 */
public final class SubCommands {
    @SuppressWarnings("unchecked")
    public static <S> LiteralArgumentBuilder<S> build(LiteralArgumentBuilder<S> builder) {
        return (LiteralArgumentBuilder<S>) ((LiteralArgumentBuilder<Object>) builder)
                .then(literal("start").executes(wrapInTry(SubCommands::start)))
                .then(literal("stop").executes(wrapInTry(SubCommands::stop)))
                .then(literal("reloadconfig").executes(wrapInTry(SubCommands::reloadConfig)))
                .then(literal("checkforupdate").executes(wrapInTry(SubCommands::checkForUpdate)))
                .then(literal("togglewhisper").executes(wrapInTry(SubCommands::toggleWhisper)))
                .then(literal("group").executes(GroupCommands::help)
                        .then(literal("list").executes(wrapInTry(GroupCommands::list)))
                        .then(literal("create")
                                // Sí, esto es un poco complicado, todo porque tendríamos que usar una mezcla para agregar un ArgumentType personalizado
                                // así que en lugar de eso simplemente usamos literales para el tipo de grupo
                                .then(argument("name", string()).executes(wrapInTry(GroupCommands.create(Group.Type.NORMAL)))
                                        .then(argument("password", string()).executes(wrapInTry(GroupCommands.create(Group.Type.NORMAL)))
                                                .then(literal("normal").executes(wrapInTry(GroupCommands.create(Group.Type.NORMAL)))
                                                        .then(argument("persistent", bool()).executes(wrapInTry(GroupCommands.create(Group.Type.NORMAL))))
                                                )
                                                .then(literal("open").executes(wrapInTry(GroupCommands.create(Group.Type.OPEN)))
                                                        .then(argument("persistent", bool()).executes(wrapInTry(GroupCommands.create(Group.Type.OPEN))))
                                                )
                                                .then(literal("isolated").executes(wrapInTry(GroupCommands.create(Group.Type.ISOLATED)))
                                                        .then(argument("persistent", bool()).executes(wrapInTry(GroupCommands.create(Group.Type.ISOLATED))))
                                                )
                                        )
                                )
                        )
                        .then(literal("join")
                                .then(argument("id", integer(1)).executes(wrapInTry(GroupCommands::join))
                                        .then(argument("password", string()).executes(wrapInTry(GroupCommands::join)))
                                )
                        )
                        .then(literal("info").executes(wrapInTry(GroupCommands::info)))
                        .then(literal("leave").executes(wrapInTry(GroupCommands::leave)))
                        .then(literal("remove")
                                .then(argument("id", integer(1)).executes(wrapInTry(GroupCommands::remove)))
                        )
                );
    }

    @SuppressWarnings("CallToPrintStackTrace")
    private static Command<Object> wrapInTry(Consumer<CommandContext<?>> function) {
        return (sender) -> {
            try {
                function.accept(sender);
                return 1;
            } catch (Throwable e) {
                e.printStackTrace();
                throw new RuntimeException(e); // De esta manera, el usuario verá "Ocurrió un error al ejecutar este comando"
            }
        };
    }

    private static void start(CommandContext<?> sender) {
        if (!platform.isValidPlayer(sender)) {
            platform.sendMessage(sender, "<red>¡Debes ser un jugador para usar este comando!");
            return;
        }

        ServerPlayer player = platform.commandContextToPlayer(sender);

        DiscordBot bot = getBotForPlayer(player.getUuid(), true);

        DiscordBot botForPlayer = getBotForPlayer(player.getUuid());
        if (botForPlayer != null) {
            platform.sendMessage(player, "<red>¡Ya has iniciado un chat de voz! <yellow>Reiniciando tu sesión...");
            botForPlayer.stop();
        }

        if (bot == null) {
            platform.sendMessage(
                    player,
                    "<red>Actualmente no hay bots disponibles. Espere a que algun usuario se salga de la llamada con uno de los bots."
            );
            return;
        }

        if (botForPlayer == null)
            platform.sendMessage(
                    player,
                    "<yellow>Iniciando un chat de voz..." + (!bot.hasLoggedIn ? " esto podría llevar un momento ya que tenemos que iniciar sesión en el bot." : "")
            );

        bot.player = player;
        new Thread(() -> {
            bot.login();
            bot.start();
        }).start();
    }

    private static void stop(CommandContext<?> sender) {
        if (!platform.isValidPlayer(sender)) {
            platform.sendMessage(sender, "<red>¡Debes ser un jugador para usar este comando!");
            return;
        }

        ServerPlayer player = platform.commandContextToPlayer(sender);

        DiscordBot bot = getBotForPlayer(player.getUuid());
        if (bot == null) {
            platform.sendMessage(player, "<red>¡Debes iniciar un chat de voz antes de poder usar este comando!");
            return;
        }

        platform.sendMessage(player, "<yellow>Deteniendo el bot...");

        new Thread(() -> {
            bot.stop();

            platform.sendMessage(sender, "<green>¡Se ha detenido el bot correctamente!");
        }).start();
    }

    private static void reloadConfig(CommandContext<?> sender) {
        if (!platform.isOperator(sender) && !platform.hasPermission(
                sender,
                RELOAD_CONFIG_PERMISSION
        )) {
            platform.sendMessage(
                    sender,
                    "<red>¡Debes ser un operador o tener el permiso `" + RELOAD_CONFIG_PERMISSION + "` para usar este comando!"
            );
            return;
        }

        platform.sendMessage(sender, "<yellow>Deteniendo bots...");

        new Thread(() -> {
            for (DiscordBot bot : bots)
                if (bot.player != null)
                    platform.sendMessage(
                            bot.player,
                            "<red>La configuración se está recargando lo que detiene todos los bots. Usa <white>/dvc start <red>para reiniciar tu sesión."
                    );
            stopBots();

            platform.sendMessage(sender, "<green>¡Se han detenido los bots correctamente! <yellow>Recargando configuración...");

            loadConfig();

            platform.sendMessage(
                    sender,
                    "<green>¡Configuración recargada correctamente! Usando " + bots.size() + " bot" + (bots.size() != 1 ? "s" : "") + "."
            );
        }).start();
    }

    private static void checkForUpdate(CommandContext<?> sender) {
        if (!platform.isOperator(sender)) {
            platform.sendMessage(
                    sender,
                    "<red>¡Debes ser un operador para usar este comando!"
            );
            return;
        }

        platform.sendMessage(sender, "<yellow>Buscando actualizaciones...");

        new Thread(() -> {
            if (UpdateChecker.checkForUpdate())
                platform.sendMessage(sender, Objects.requireNonNullElse(UpdateChecker.updateMessage, "<red>No se encontró ninguna actualización."));
            else
                platform.sendMessage(sender, "<red>Se produjo un error al buscar actualizaciones. Comprueba la consola para ver el mensaje de error.");
        }).start();
    }

    private static void toggleWhisper(CommandContext<?> sender) {
        if (!platform.isValidPlayer(sender)) {
            platform.sendMessage(sender, "<red>¡Debes ser un jugador para usar este comando!");
            return;
        }

        ServerPlayer player = platform.commandContextToPlayer(sender);

        DiscordBot bot = getBotForPlayer(player.getUuid());
        if (bot == null) {
            platform.sendMessage(player, "<red>¡Debes iniciar un chat de voz antes de poder usar este comando!");
            return;
        }

        boolean whispering = !bot.sender.isWhispering();
        bot.sender.whispering(whispering);

        platform.sendMessage(sender, whispering ? "<green>¡Comenzaste a susurrar!" : "<green>¡Dejaste de susurrar!");
    }

    private static final class GroupCommands {
        private static boolean checkIfGroupsEnabled(CommandContext<?> sender) {
            if (!api.getServerConfig().getBoolean("enable_groups", true)) {
                platform.sendMessage(sender, "<red>Los grupos están deshabilitados actualmente.");
                return true;
            }
            return false;
        }

        @SuppressWarnings("SameReturnValue")
        private static int help(CommandContext<?> sender) {
            platform.sendMessage(
                    sender,
                    """
                            <red>Subcomandos disponibles:
                             - `<white>/dvc group list<red>`: Lista de grupos
                             - `<white>/dvc group create <nombre> [contraseña] [tipo] [persistente]<red>`: Crea un grupo
                             - `<white>/dvc group join <ID><red>`: Unirse a un grupo
                             - `<white>/dvc group info<red>`: Obtener información sobre tu grupo actual
                             - `<white>/dvc group leave<red>`: Abandonar tu grupo actual
                             - `<white>/dvc group remove <ID><red>`: Elimina un grupo persistente si no hay nadie en él
                            Consulta <white>https://github.com/naturecodevoid/voicechat-discord#dvc-group<red> para obtener más información sobre cómo utilizar estos comandos."""
            );
            return 1;
        }

        private static void list(CommandContext<?> sender) {
            if (checkIfGroupsEnabled(sender)) return;

            Collection<Group> apiGroups = api.getGroups();

            if (apiGroups.isEmpty())
                platform.sendMessage(sender, "<red>No hay grupos actualmente.");
            else {
                StringBuilder groupsMessage = new StringBuilder("<green>Grupos:\n");

                for (Group group : apiGroups) {
                    int friendlyId = groupFriendlyIds.get(group.getId());
                    platform.debugVerbose("ID amigable para " + group.getId() + " (" + group.getName() + ") es " + friendlyId);

                    String playersMessage = "<red>No hay jugadores";
                    List<ServerPlayer> players = groupPlayers.get(group.getId());
                    if (!players.isEmpty())
                        playersMessage = players.stream().map(player -> platform.getName(player)).collect(Collectors.joining(", "));

                    groupsMessage.append("<green> - ")
                            .append(group.getName())
                            .append(" (ID es ")
                            .append(friendlyId)
                            .append("): ")
                            .append(group.hasPassword() ? "<red>Tiene contraseña" : "<green>No tiene contraseña")
                            .append(group.isPersistent() ? "<yellow>, persistente" : "")
                            .append(".<green> El tipo de grupo es ")
                            .append(
                                    group.getType() == Group.Type.NORMAL ? "normal" :
                                            group.getType() == Group.Type.OPEN ? "abierto" :
                                                    group.getType() == Group.Type.ISOLATED ? "aislado" :
                                                            "desconocido"
                            )
                            .append(". Jugadores: ")
                            .append(playersMessage)
                            .append("\n");
                }

                platform.sendMessage(sender, groupsMessage.toString().trim());
            }
        }

        private static Consumer<CommandContext<?>> create(Group.Type type) {
            return (sender) -> {
                if (!platform.isValidPlayer(sender)) {
                    platform.sendMessage(sender, "<red>¡Debes ser un jugador para usar este comando!");
                    return;
                }

                if (checkIfGroupsEnabled(sender)) return;

                String name = sender.getArgument("nombre", String.class);
                String password = getArgumentOr(sender, "contraseña", String.class, null);
                if (password != null)
                    if (password.trim().isEmpty())
                        password = null;
                Boolean persistente = getArgumentOr(sender, "persistente", Boolean.class, false);
                assert persistente != null;

                VoicechatConnection connection = Objects.requireNonNull(api.getConnectionOf(platform.commandContextToPlayer(sender)));
                if (connection.getGroup() != null) {
                    platform.sendMessage(sender, "<red>¡Ya estás en un grupo!");
                    return;
                }

                Group group = api.groupBuilder()
                        .setName(name)
                        .setPassword(password)
                        .setType(type)
                        .setPersistent(persistente)
                        .build();
                connection.setGroup(group);

                platform.sendMessage(sender, "<green>¡Grupo creado correctamente!");
            };
        }

        private static void join(CommandContext<?> sender) {
            if (!platform.isValidPlayer(sender)) {
                platform.sendMessage(sender, "<red>¡Debes ser un jugador para usar este comando!");
                return;
            }

            if (checkIfGroupsEnabled(sender)) return;

            Integer friendlyId = sender.getArgument("ID", Integer.class);
            UUID groupId = groupFriendlyIds.getKey(friendlyId);
            if (groupId == null) {
                platform.sendMessage(sender, "<red>ID de grupo no válido. Utiliza <white>/dvc group list<red> para ver todos los grupos.");
                return;
            }

            Group group = Objects.requireNonNull(api.getGroup(groupId));
            if (group.hasPassword()) {
                String inputPassword = getArgumentOr(sender, "contraseña", String.class, null);
                if (inputPassword != null)
                    if (inputPassword.trim().isEmpty())
                        inputPassword = null;

                if (inputPassword == null) {
                    platform.sendMessage(sender, "<red>El grupo tiene una contraseña y no has proporcionado una. Vuelve a ejecutar el comando, incluyendo la contraseña.");
                    return;
                }

                String groupPassword = getPassword(group);
                if (groupPassword == null) {
                    platform.sendMessage(sender, "<red>Dado que el grupo tiene una contraseña, necesitamos verificar si la contraseña que proporcionaste es correcta. Sin embargo, no pudimos obtener la contraseña del grupo (el propietario del servidor puede ver el error en la consola). Es posible que necesites actualizar Simple Voice Chat Discord Bridge.");
                    return;
                }

                if (!inputPassword.equals(groupPassword)) {
                    platform.sendMessage(sender, "<red>La contraseña que proporcionaste es incorrecta. Puede que quieras rodear la contraseña con comillas si la contraseña contiene espacios.");
                    return;
                }
            }

            VoicechatConnection connection = Objects.requireNonNull(api.getConnectionOf(platform.commandContextToPlayer(sender)));
            if (connection.getGroup() != null) {
                platform.sendMessage(sender, "<red>¡Ya estás en un grupo! Sal del grupo usando <white>/dvc group leave<red>, y luego únete a este grupo.");
                return;
            }
            if (!connection.isInstalled() && getBotForPlayer(platform.commandContextToPlayer(sender).getUuid()) == null) {
                platform.sendMessage(sender, "<red>¡Debes tener el mod instalado o iniciar un chat de voz antes de poder usar este comando!");
                return;
            }
            connection.setGroup(group);

            platform.sendMessage(sender, "<green>¡Te has unido correctamente al grupo \"" + group.getName() + "\". Usa <white>/dvc group info<green> para ver información sobre el grupo, y <white>/dvc group leave<green> para abandonar el grupo.");
        }

        private static void info(CommandContext<?> sender) {
            if (!platform.isValidPlayer(sender)) {
                platform.sendMessage(sender, "<red>¡Debes ser un jugador para usar este comando!");
                return;
            }

            if (checkIfGroupsEnabled(sender)) return;

            VoicechatConnection connection = Objects.requireNonNull(api.getConnectionOf(platform.commandContextToPlayer(sender)));
            Group group = connection.getGroup();
            if (group == null) {
                platform.sendMessage(sender, "<red>No estás en un grupo!");
                return;
            }

            List<ServerPlayer> players = groupPlayers.get(group.getId());
            String playersMessage = players.stream().map(player -> platform.getName(player)).collect(Collectors.joining(", "));
            String message = "<green>Actualmente estás en \"" + group.getName() + "\". " +
                    (group.hasPassword() ? "<red>tiene una contraseña<green>" : "no tiene una contraseña") + (group.isPersistent() ? " y es persistente." : ".") +
                    " El tipo de grupo es " +
                    (group.getType() == Group.Type.NORMAL ? "normal" :
                            group.getType() == Group.Type.OPEN ? "abierto" :
                                    group.getType() == Group.Type.ISOLATED ? "aislado" :
                                            "desconocido") +
                    ". Jugadores: " + playersMessage;

            platform.sendMessage(sender, message);
        }

        private static void leave(CommandContext<?> sender) {
            if (!platform.isValidPlayer(sender)) {
                platform.sendMessage(sender, "<red>¡Debes ser un jugador para usar este comando!");
                return;
            }

            if (checkIfGroupsEnabled(sender)) return;

            VoicechatConnection connection = Objects.requireNonNull(api.getConnectionOf(platform.commandContextToPlayer(sender)));
            if (connection.getGroup() == null) {
                platform.sendMessage(sender, "<red>No estás en un grupo!");
                return;
            }
            connection.setGroup(null);

            platform.sendMessage(sender, "<green>¡Has abandonado correctamente el grupo.");
        }

        private static void remove(CommandContext<?> sender) {
            if (checkIfGroupsEnabled(sender)) return;

            Integer friendlyId = sender.getArgument("ID", Integer.class);
            UUID groupId = groupFriendlyIds.getKey(friendlyId);
            if (groupId == null) {
                platform.sendMessage(sender, "<red>ID de grupo no válido. Utiliza <white>/dvc group list<red> para ver todos los grupos.");
                return;
            }

            if (!api.removeGroup(groupId)) {
                platform.sendMessage(sender, "<red>No se pudo eliminar el grupo. Esto significa que o tiene jugadores o no es persistente.");
                return;
            }

            platform.sendMessage(sender, "<green>¡Se eliminó correctamente el grupo!");
        }
    }
}
