package converter.core;

import java.util.concurrent.atomic.AtomicBoolean;

/** 一次转换任务的参数。 */
public record Options(String audioBitrate, boolean mergeImagesPdf, AtomicBoolean cancelled) {

    public static Options none() {
        return new Options("192k", false, new AtomicBoolean(false));
    }

    public boolean isCancelled() {
        return cancelled != null && cancelled.get();
    }
}
