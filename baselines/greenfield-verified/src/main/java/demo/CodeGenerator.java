package demo;

/**
 * Produces candidate short codes for generated (non-alias) links.
 * Implementations must return codes that satisfy [A-Za-z0-9_-]{3,32}.
 */
public interface CodeGenerator {

    /** Returns a fresh candidate code. Callers handle uniqueness via the database. */
    String next();
}
