package caeruleusTait.world.preview.domain.ui;

/**
 * Thrown when a config binding encounters a type mismatch or range violation.
 */
public class ConfigBindingException extends RuntimeException {

    public ConfigBindingException(String message) {
        super(message);
    }

    public ConfigBindingException(String message, Throwable cause) {
        super(message, cause);
    }
}
