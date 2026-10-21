package logmaster.core;

/** Thrown when a file changed underneath us in a way that requires starting over. */
public final class FileChangedException extends RuntimeException {
    public FileChangedException(String message) {
        super(message);
    }
}
