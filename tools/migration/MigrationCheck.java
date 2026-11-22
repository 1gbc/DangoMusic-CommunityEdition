// Headless regression check for the DangoMusic GTNH Edition -> Community Edition data migration (LegacyData).
// Build first (./gradlew build), then from this folder (Git Bash, Windows):
//   J=../../build/libs/dangomusicce-<version>.jar
//   L=<log4j-api-2.0-beta9.jar from the Gradle cache>
//   javac -cp "$J;$L" -d out MigrationCheck.java
//   LOCALAPPDATA="$(cygpath -w "$(mktemp -d)")" java -cp "out;$J;$L" MigrationCheck
// LOCALAPPDATA must point at an empty temporary folder: the check writes fake accounts there.
import dangomusicce.LegacyData;
import dangomusicce.platform.PlayerKeyBindingKt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

/** Headless check of the GTNH Edition -> Community Edition data migration. */
public class MigrationCheck {
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) failures++;
    }

    static void write(Path p, String s) throws Exception {
        Files.createDirectories(p.getParent());
        Files.write(p, s.getBytes(StandardCharsets.UTF_8));
    }

    static String read(Path p) throws Exception {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("dmce-mig");

        // 1. Config: copied once, component caches skipped, old kept.
        Path cfg = root.resolve("config");
        Path old = cfg.resolve("dangomusicgtnh");
        write(old.resolve("settings.json"), "{\"old\":true}");
        write(old.resolve("fonts/My Font.ttf"), "font");
        write(old.resolve("lyrics/apple/1.lrc"), "[00:01]hi");
        write(old.resolve("apple-player/bundled-abc/DangoApplePlayer.exe"), "exe");
        write(old.resolve("bilibili-helper/bundled-x/helper.exe"), "exe");
        write(old.resolve("groups.json"), "[]");
        // leftover staging from an interrupted run must not break the copy
        write(cfg.resolve("dangomusicce.migrating/stale.txt"), "stale");
        LegacyData.migrateConfig(cfg.toFile());
        Path neu = cfg.resolve("dangomusicce");
        check(Files.isRegularFile(neu.resolve("settings.json")) && read(neu.resolve("settings.json")).contains("old"), "settings.json copied");
        check(Files.isRegularFile(neu.resolve("fonts/My Font.ttf")), "fonts copied");
        check(Files.isRegularFile(neu.resolve("lyrics/apple/1.lrc")), "nested lyrics copied");
        check(Files.isRegularFile(neu.resolve("groups.json")), "server groups.json copied");
        check(!Files.exists(neu.resolve("apple-player")) && !Files.exists(neu.resolve("bilibili-helper")), "component caches skipped");
        check(!Files.exists(neu.resolve("stale.txt")) && !Files.exists(cfg.resolve("dangomusicce.migrating")), "stale staging removed");
        check(Files.isRegularFile(old.resolve("settings.json")) && Files.exists(old.resolve("apple-player")), "old directory untouched");

        // 2. Second start: new settings are not overwritten.
        write(neu.resolve("settings.json"), "{\"new\":true}");
        write(old.resolve("settings.json"), "{\"old\":2}");
        LegacyData.migrateConfig(cfg.toFile());
        check(read(neu.resolve("settings.json")).contains("new"), "existing new config not overwritten");

        // 3. Fresh install: nothing to copy, nothing created.
        Path cfg2 = root.resolve("config2");
        Files.createDirectories(cfg2);
        LegacyData.migrateConfig(cfg2.toFile());
        check(!Files.exists(cfg2.resolve("dangomusicce")), "fresh install creates nothing");

        // 4. Accounts.
        Path oldAcc = root.resolve("local/DangoMusicGTNH/accounts/k1");
        write(oldAcc.resolve("netease/session.json"), "cookie");
        Path newAcc = root.resolve("local/DangoMusicCE/accounts/k1");
        LegacyData.INSTANCE.migrateAccounts(oldAcc, newAcc);
        check(Files.isRegularFile(newAcc.resolve("netease/session.json")), "accounts copied");

        // 4b. Accounts through the real accountDirectory path: the old folder name is the hash of the OLD config dir.
        Path game = Files.createTempDirectory("dmce-game");
        Path oldClient = game.resolve(".minecraft/config/dangomusicgtnh");
        Path newClient = game.resolve(".minecraft/config/dangomusicce");
        Files.createDirectories(oldClient);
        Files.createDirectories(newClient);
        String local = System.getenv("LOCALAPPDATA");
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        StringBuilder oldKey = new StringBuilder();
        for (byte b : md.digest(oldClient.toRealPath().toString().getBytes(StandardCharsets.UTF_8))) oldKey.append(String.format("%02x", b & 255));
        write(Path.of(local, "DangoMusicGTNH", "accounts", oldKey.toString(), "netease", "session.json"), "cookie2");
        Path accountDir = dangomusicce.music.SessionFiles.INSTANCE.accountDirectory(newClient);
        check(accountDir.startsWith(Path.of(local).toRealPath().resolve("DangoMusicCE").resolve("accounts")), "new accounts root (" + accountDir + ")");
        check(Files.isRegularFile(accountDir.resolve("netease/session.json")), "accountDirectory migrates old logins via old config hash");

        // 5. Key names.
        check(LegacyData.INSTANCE.keyName("key.dangomusicgtnh.open_player").equals("key.dangomusicce.open_player"), "old key name mapped");
        check(LegacyData.INSTANCE.keyName("key.dangomusicce.stop_playback").equals("key.dangomusicce.stop_playback"), "new key name unchanged");

        // 6. options.txt: old and new lines, new wins.
        Path options = root.resolve("options.txt");
        Files.write(options, Arrays.asList(
            "key_key.dangomusicce.next:49",
            "key_key.dangomusicgtnh.next:20",
            "key_key.dangomusicgtnh.open_player:50",
            "key_key.attack:-100"), StandardCharsets.UTF_8);
        Map<String, String> keys = PlayerKeyBindingKt.savedPlayerKeys(options);
        check("50".equals(keys.get("key.dangomusicce.open_player")), "old options.txt binding read under new name");
        check("49".equals(keys.get("key.dangomusicce.next")), "new binding wins over old");
        check(keys.size() == 2, "only DangoMusic bindings read (" + keys + ")");

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
