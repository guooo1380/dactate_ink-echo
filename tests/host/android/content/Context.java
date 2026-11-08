package android.content;
/** Minimal host adapter, used only for the real SQLite integration test. */
public class Context {
    public final String database;
    public Context(String database) { this.database=database; }
}
