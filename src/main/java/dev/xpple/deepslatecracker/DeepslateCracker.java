package dev.xpple.deepslatecracker;

import com.mojang.brigadier.CommandDispatcher;
import dev.xpple.deepslatecracker.command.commands.CrackCommand;
import dev.xpple.deepslatecracker.command.commands.SourceCommand;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.commands.CommandBuildContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class DeepslateCracker implements ClientModInitializer {

    public static final String MOD_ID = "deepslatecracker";

    static {
        String libraryName = System.mapLibraryName("z3");
        libraryName = libraryName.startsWith("lib") ? libraryName : "lib" + libraryName;
        String jLibraryName = System.mapLibraryName("z3java");
        jLibraryName = jLibraryName.startsWith("lib") ? jLibraryName : "lib" + jLibraryName;
        ModContainer modContainer = FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow();
        Path filePath, jFilePath;
        try {
            Path tempDir = Files.createTempDirectory("z3");
            filePath = tempDir.resolve(libraryName);
            jFilePath = tempDir.resolve(jLibraryName);
            Files.copy(modContainer.findPath(libraryName).orElseThrow(), filePath, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(modContainer.findPath(jLibraryName).orElseThrow(), jFilePath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        System.load(filePath.toAbsolutePath().toString());
        System.load(jFilePath.toAbsolutePath().toString());
    }

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register(DeepslateCracker::registerCommands);
    }

    private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext buildContext) {
        CrackCommand.register(dispatcher);
        SourceCommand.register(dispatcher);
    }
}
