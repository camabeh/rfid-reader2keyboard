package nl.inventid.rfidReader2keyboard;

import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CardNotPresentException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import javax.smartcardio.TerminalFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.Lists;
import lombok.Setter;
import org.apache.commons.codec.binary.Hex;

/**
 * This little program handles the reading of the RFID miFare chips. An exit code of 0 means everything went well An
 * exit code of 1 means no suitable terminal was found An exit code of 2 means no type robot could be started
 */
public class Read {

	private static final String NO_CONNECT = "connect() failed";
	private static final String EMPTY_CODE = "Scanned code was empty";
	private static final String NO_CARD = "sun.security.smartcardio.PCSCException: SCARD_E_NO_SMARTCARD";
	private static final String READER_UNAVAILABLE =
			"sun.security.smartcardio.PCSCException: SCARD_E_READER_UNAVAILABLE";
	private static final String FAILED_CARD_TRANSACTION =
			"sun.security.smartcardio.PCSCException: SCARD_E_NOT_TRANSACTED";
	private static final CommandAPDU READ_COMMAND = new CommandAPDU(new byte[] { (byte) 0xFF, (byte) 0xCA, (byte) 0x00,
			(byte) 0x00, (byte) 0x00 });

	private static final List<String> TERMINAL_PREFERENCES = new ArrayList<>();
	private static final Map<String, Integer> errorMap = new HashMap<>();
	private static final String CARD_READ_FAILURE = "Card read failure";

	private static TerminalDetector detectorLoop;
	private static ErrorLogger errorLogger;
	private static ScheduledThreadPoolExecutor executorService;

	private final Reconnector reconnector;

	private CardTerminal terminal;

	@Setter
	private Instant lastScan;

	public static void main(String[] args) {
		System.out.println("Starting rfid-reader2keyboard");
		System.out.println("The following terminals were detected:");
		System.out.println(Read.listTerminals());

		System.out.println();
		System.out.println("inventid RFID capturing is currently active. Close this dialog to deactivate.");
		System.out.println(
				"The most likely reason you see this is in order to resolve any issue you ay have found. Please follow"
						+ " the instructions of inventid support and send these lines to the given email address");

		executorService = new ScheduledThreadPoolExecutor(3);
		errorLogger = new ErrorLogger();
		detectorLoop = new TerminalDetector();
		executorService.scheduleAtFixedRate(errorLogger, 10, 30, TimeUnit.SECONDS);
		executorService.scheduleAtFixedRate(detectorLoop, 10, 15, TimeUnit.SECONDS);

		Read reader = new Read(executorService);
		reader.loop();

		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				executorService.shutdownNow();
				System.out.println("inventid RFID capturing is now inactive. You can close this dialog");
			}
		});
	}

	/**
	 * Get the currently connected terminals
	 *
	 * @return the list of found terminals
	 */
	public static List<CardTerminal> listTerminals() {
		// show the list of available terminals
		TerminalFactory factory = TerminalFactory.getDefault();
		try {
			return factory.terminals().list();
		}
		catch (Exception e) {
			return Lists.newArrayList();
		}
	}

	public Read(ScheduledThreadPoolExecutor executorService) {
		TERMINAL_PREFERENCES.add("ACS ACR122U PICC Interface"); // Best match
		TERMINAL_PREFERENCES.add("ACR122"); // That'll do (Windows does not include the U)
		TERMINAL_PREFERENCES.add(""); // Fuck, attach with anything (SHOULD BE LAST)

		reconnector = new Reconnector(this, 5); // Reconnect every five seconds
		executorService.scheduleAtFixedRate(reconnector, 1, 1, TimeUnit.SECONDS);
	}

	/**
	 * Find and connect to a terminal, based on the preferences in TERMINAL_PREFERENCES
	 *
	 * @return a valid CardTerminal or halts the system if no match can be found
	 */
	public void findAndConnectToTerminal() {
		try {
			// show the list of available terminals
			TerminalFactory factory = TerminalFactory.getDefault();
			List<CardTerminal> terminals = factory.terminals().list();

			System.out.println("There are " + TERMINAL_PREFERENCES.size() + " possible terminal matches");
			System.out.println("There are " + terminals.size() + " terminals attached to this machine");

			for (int j = 0; j < TERMINAL_PREFERENCES.size(); j++) {
				String requiredTerminal = TERMINAL_PREFERENCES.get(j);
				System.out.println("Trying to attach to '" + requiredTerminal + "'");
				for (int i = 0; i < terminals.size(); i++) {
					if (terminals.get(i).getName().contains(requiredTerminal)) {
						CardTerminal newTerminal = terminals.get(i);
						System.out.println("Attached to '" + requiredTerminal + "'");
						terminal = newTerminal;
						return;
					}
				}
			}
		}
		catch (Exception e) {
			// Probably no reader found...
			System.err.println("Unable to connect to RFID reader");
			e.printStackTrace();
		}
		return;
	}

	/**
	 * Do the actual work of the program by looping over it and writing/exceptioning In case you are looking at this
	 * code and thinking "OMG why not just use the CardTerminal methods instead of catching?": There is a very good
	 * reason: javax.smartcardio is buggy as fuck, so on some platform the `waitForCardPresent` and `waitForCardAbsent`
	 * methods will not block, or block indefinitely under some conditions. Especially in combination with sleeping
	 * code, this is a significant nightmare! Therefore we simply try to read from the card, and handle all exceptions.
	 * In the exception handling, possibly we will reconnect to a terminal, if that is the best thing to do for
	 * stability
	 */
	public void loop() {

		findAndConnectToTerminal();

		if (terminal == null) {
			System.err.println("No terminal connected!");
		}
		Keyboard keyboard = new Keyboard();

		// Random String; no UID of any chip. Still true though
		String oldUID = "inventid bravo!";
		int i = 0;
		// Keep looping
		while (true) {
			try {
				// Connect to card and read
				Card card = terminal.connect("T=1");
				CardChannel channel = card.getBasicChannel();

				// Send data and retrieve output
				String uid = getCardUid(channel);

				if (!isNewCard(uid, oldUID, lastScan)) {
					continue;
				}

				System.out.println("This is a new card! " + uid);
				// Emulate a keyboard and "type" the uid, followed by a newline
				keyboard.type(uid);
				keyboard.type("\n");

				i++;
				oldUID = uid;
				lastScan = Instant.now();
				reconnector.setLastAction(lastScan);
				card.disconnect(false);

				System.out.println("ready for next card");
				System.out.println("Test run: " + i);
			}
			catch (CardException e) {
				// Something went wrong when scanning the card
				if (e.getMessage().equals(FAILED_CARD_TRANSACTION) || e.getMessage().equals(READER_UNAVAILABLE)) {
					logError(e.getMessage());
					findAndConnectToTerminal();
					continue;
				}
				// Card is not present while scanning
				if (e.getMessage().equals(NO_CARD) || e instanceof CardNotPresentException) {
					logError(e.getMessage());
					continue;
				}
				// Could not reliably connect to the reader (this can mean there is simply no card)
				if (e.getMessage().equals(NO_CONNECT) || e.getMessage().equals(CARD_READ_FAILURE)) {
					logError(e.getMessage());
					continue;
				}
				if (e.getMessage().equals(EMPTY_CODE)) {
					logError(e.getMessage());
					System.err.println("Empty code was read");
					continue;
				}
				System.err.println("Help something uncatched happened! This should not happen!");
				logError(e.getMessage());
				e.printStackTrace();
				System.out.println(e.getMessage());
				findAndConnectToTerminal();
			}
		}
	}

	/**
	 * Get the uid of a card
	 *
	 * @param channel the channel to transmit over
	 * @return a String with the value of the uid (not empty)
	 * @throws CardException in case of an error
	 */
	private String getCardUid(CardChannel channel) throws CardException {
		ResponseAPDU response = channel.transmit(READ_COMMAND);
		String uid = new String(Hex.encodeHex(response.getData())).toUpperCase();
		if (!new String(Hex.encodeHex(response.getBytes())).endsWith("9000")) {
			throw new CardException(CARD_READ_FAILURE);
		}
		if (uid.isEmpty()) {
			throw new CardException(EMPTY_CODE);
		}
		return uid;
	}

	/**
	 * @param newUid   the newly scanned UID
	 * @param oldUid   the previously scanned code
	 * @param lastScan the time of the last successful scan
	 * @return Return true if the card is different OR if the previous card was scanned over 1 second before
	 */
	private boolean isNewCard(String newUid, String oldUid, Instant lastScan) {
		return !newUid.equals(oldUid) || lastScan == null ||
				(lastScan != null && lastScan.plus(1, ChronoUnit.SECONDS).isBefore(Instant.now()));
	}

	/**
	 * Log an error by incrementing the value in the map by one
	 * @param errorCause the cause of the error
	 */
	private void logError(String errorCause) {
		Integer newValue = errorMap.getOrDefault(errorCause, 0) + 1;
		errorMap.put(errorCause, newValue);
	}

	/**
	 * This is a very stupid innerclass, which simply prints the errorMap of the main class every 60 seconds
	 */
	private static class ErrorLogger implements Runnable {
		public void run() {
			System.out.println("Error map: " + errorMap.entrySet());
		}
	}

	/**
	 * This is a very stupid innerclass, which simply prints the connected readers every 30 seconds
	 */
	private static class TerminalDetector implements Runnable {
		public void run() {
			System.out.println(Read.listTerminals());
		}
	}

	/**
	 * This inner class simply attempts to reconnect to a terminal in case there were no scan actions for a few seconds
	 * The JVM may lose the connection under such circumstances :(
	 */
	private static class Reconnector implements Runnable {

		private final Read read;

		@Setter
		private Instant lastAction;
		private int reconnectTime;

		public Reconnector(Read read, int reconnectTime) {
			this.read = read;
			this.reconnectTime = reconnectTime;
			System.out.println("Reconnector started");
		}

		@Override
		public void run() {
			Instant now = Instant.now();
			if (lastAction == null ||
					lastAction.plus(reconnectTime, ChronoUnit.SECONDS).isBefore(now)) {
				System.out.println("Reconnect due to lack of scan actions");
				read.findAndConnectToTerminal();
				read.setLastScan(now);
				this.setLastAction(now);
			}
		}
	}
}
