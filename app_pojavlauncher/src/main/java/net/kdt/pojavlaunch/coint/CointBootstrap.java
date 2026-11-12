package net.kdt.pojavlaunch.coint;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;

import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.LWJGL3ifyUtils;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.IOException;

/** COINT defaults and adaptation of an explicitly imported local GTNH pack. */
public final class CointBootstrap {
    private CointBootstrap() {}

    public static void initializeDefaults(Context context, SharedPreferences preferences) {
        if (preferences.getBoolean("coint.defaults.v1", false)) return;
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        int totalMiB = (int) (info.totalMem / (1024L * 1024L));
        // A larger heap made the tested Android device kill the process. Leave
        // memory for native rendering, JVM metadata and Android, not just Java.
        int heapMiB = totalMiB >= 6144 ? 3072 : 2048;
        SharedPreferences.Editor editor = preferences.edit();
        if (!preferences.contains("allocation")) editor.putInt("allocation", heapMiB);
        editor.putBoolean("coint.defaults.v1", true).apply();
    }

    public static boolean prepareGtnh(MinecraftProfile profile) throws IOException {
        if (profile.lastVersionId == null || !profile.lastVersionId.startsWith("1.7.10")) return false;
        File gameDirectory = Tools.getGameDirPath(profile);
        File[] candidates = new File(gameDirectory, "mods").listFiles(file -> file.isFile()
                && file.getName().startsWith("lwjgl3ify-3.") && file.getName().endsWith(".jar"));
        if (candidates == null || candidates.length == 0) return false;
        if (candidates.length != 1) throw new IOException("The pack contains multiple LWJGL3ify mods");

        JMinecraftVersionList.Version version = LWJGL3ifyUtils.installJson(candidates[0]);
        if (version.id == null || version.javaVersion == null) {
            throw new IOException("Incomplete LWJGL3ify launch profile");
        }
        CointConfigPatch.disableInFile(new File(gameDirectory, "config/lwjgl3ify.cfg"),
                "B:sharedContext", "B:linuxCreateAppDesktopEntry");
        CointConfigPatch.disableInFile(new File(gameDirectory,
                "config/Betterloadingscreen/betterloadingscreen.cfg"), "B:useMinecraft", "B:playSound");
        CointConfigPatch.disableInFile(new File(gameDirectory, "config/splash.properties"), "enabled");
        profile.lastVersionId = version.id;
        profile.pojavRendererName = "opengles_mobileglues";
        // If the runtime is absent the normal launch flow offers its download.
        profile.javaDir = MultiRTUtils.getExactJreName(version.javaVersion.majorVersion);
        return true;
    }
}
