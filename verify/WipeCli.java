import app.freespacewiper.engine.EngineListener;
import app.freespacewiper.engine.Phase;
import app.freespacewiper.engine.Snapshot;
import app.freespacewiper.engine.WipeConfig;
import app.freespacewiper.engine.WipeEngine;
import app.freespacewiper.engine.WipeMode;

import java.io.File;
import java.util.Arrays;

/**
 * Runs the app's wipe engine, with the same parameters the app uses, on a desktop JVM.
 * Usage: WipeCli <dir> <RANDOM|ZERO> <threads> <passes> <resume:true|false> [sweep:true|false]
 */
public class WipeCli {
    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        WipeConfig cfg = WipeConfig.builder(dir)
                .mode(WipeMode.parse(a[1]))
                .threads(Integer.parseInt(a[2]))
                .passes(Integer.parseInt(a[3]))
                .resume(Boolean.parseBoolean(a[4]))
                .sweep(a.length <= 5 || Boolean.parseBoolean(a[5]))
                .build();
        EngineListener log = new EngineListener() {
            @Override
            public synchronized void onEvent(Event event, Object... args) {
                System.out.println("  " + event + " " + Arrays.toString(args));
            }

            @Override
            public void onPassStarting(int pass) {
            }
        };
        WipeEngine engine = new WipeEngine(new JvmPlatform(), cfg, log);
        Phase result = engine.run();
        Snapshot s = engine.snapshot();
        System.out.printf("  result=%s written=%.1f MB unreachable=%.1f MB leftovers=%d errors=%d%n",
                result, s.totalBytes / 1e6, s.unreachableBytes / 1e6,
                WipeEngine.listFillFiles(dir).length, s.errors);
        System.exit(result == Phase.DONE ? 0 : 1);
    }
}
