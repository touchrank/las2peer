package i5.las2peer.api.persistency;

/**
 * This exception is thrown if an storage operation fails.
 *
 */
public class EnvelopeOperationFailedException extends EnvelopeException {

	private static final long serialVersionUID = 1L;

	/**
	 * This constructor sets only the message for this exception and leaves the cause to {@code null}.
	 *
	 * @param message A message that describes the error.
	 */
	public EnvelopeOperationFailedException(String message) {
		super(message);
	}

	/**
	 * This constructor leaves the message {@code null} and sets only the cause for this exception. This is usually used
	 * to wrap other exception types and unify exception handling for the developer.
	 *
	 * @param cause An other exception that caused this one.
	 */
	public EnvelopeOperationFailedException(Throwable cause) {
		super(cause);
	}

	/**
	 * This constructor allows to set message and cause for this exception.
	 *
	 * @param message A message that describes the error.
	 * @param cause An other exception that caused this one.
	 */
	public EnvelopeOperationFailedException(String message, Throwable cause) {
		super(message, cause);
	}

}
