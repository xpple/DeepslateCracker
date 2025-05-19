package dev.xpple.deepslatecracker;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public class DeepslateCrackerPreLaunch implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        System.setProperty("z3.skipLibraryLoad", "true");
    }
}

