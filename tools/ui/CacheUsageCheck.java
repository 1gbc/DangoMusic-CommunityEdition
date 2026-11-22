import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import dangomusicce.music.NeteaseDiskCache;

public final class CacheUsageCheck {
    public static void main(String[] args) throws Exception {
        NeteaseDiskCache cache = NeteaseDiskCache.INSTANCE;
        Path sandbox = Paths.get(args[0]).toAbsolutePath().normalize();
        Path directory = cache.getDirectory().toAbsolutePath().normalize();
        if (!directory.startsWith(sandbox)) throw new AssertionError("Requires an isolated cache directory");

        await(cache, 0, 0);
        Files.createDirectories(directory);
        Path audio = directory.resolve(String.join("", Collections.nCopies(64, "a")) + ".audio");
        Path unrelated = directory.resolve("unrelated.audio");
        Files.write(unrelated, new byte[45]);
        Files.write(audio, new byte[200]);
        await(cache, 200, 1);

        Path second = directory.resolve(String.join("", Collections.nCopies(64, "b")) + ".audio");
        Files.write(second, new byte[500]);
        await(cache, 700, 2);
        Files.delete(audio);
        await(cache, 500, 1);

        cache.clear();
        await(cache, 0, 0);
        if (Files.exists(second) || !Files.exists(unrelated)) throw new AssertionError("Clear must only remove audio cache files");
        System.out.println("Cache usage follows growth, external deletion and clear.");
    }

    private static void await(NeteaseDiskCache cache, long bytes, int files) throws InterruptedException {
        long end = System.nanoTime() + 5_000_000_000L;
        do {
            NeteaseDiskCache.Usage actual = cache.usage();
            if (actual != null && actual.getBytes() == bytes && actual.getFileCount() == files &&
                actual.getLimit() == 5L * 1024 * 1024 * 1024) return;
            Thread.sleep(25);
        } while (System.nanoTime() < end);
        throw new AssertionError("Cache usage did not reach " + bytes + " bytes, " + files + " files");
    }
}
