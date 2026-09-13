package converter.core;

/** 转换过程的消息与进度输出。 */
public interface Log {
    Log QUIET = new Log() {
        @Override public void info(String msg) { }
        @Override public void error(String msg) { }
        @Override public void progress(double fraction) { }
    };

    void info(String msg);

    void error(String msg);

    /** fraction 取值 0~1；小于 0 表示进度未知。 */
    void progress(double fraction);

    /** 开始处理一个新的来源文件。 */
    default void begin(String name) { }
}
