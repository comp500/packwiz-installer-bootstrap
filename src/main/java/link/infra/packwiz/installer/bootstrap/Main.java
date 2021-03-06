package link.infra.packwiz.installer.bootstrap;

import java.awt.EventQueue;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JOptionPane;
import javax.swing.ProgressMonitor;
import javax.swing.UIManager;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

public class Main {

	private static final String DEFAULT_UPDATE_URL = "https://api.github.com/repos/comp500/packwiz-installer/releases/latest";
	public static final String JAR_NAME = "packwiz-installer.jar";

	private String updateURL = DEFAULT_UPDATE_URL;
	private boolean skipUpdate = false;
	private boolean useGUI = true;
	private String jarPath = null;
	private String accessToken = null;

	public static void main(String[] args) {
		new Main(args); // Is this bad?
	}

	public Main(String[] args) {
		try {
			parseOptions(args);
		} catch (ParseException e) {
			showError(e, "There was an error parsing command line arguments:");
			System.exit(1);
		}
		
		if (jarPath == null) {
			jarPath = JAR_NAME;
		}

		if (useGUI) {
			EventQueue.invokeLater(() -> {
				try {
					UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
				} catch (Exception e) {
					// Ignore the exceptions, just continue using the ugly L&F
				}
			});
		}

		if (skipUpdate) {
			try {
				LoadJAR.start(args, jarPath);
			} catch (ClassNotFoundException e) {
				showError(e, "packwiz-installer cannot be found, or there was an error loading it:");
				System.exit(1);
			} catch (Exception e) {
				showError(e, "There was an error loading packwiz-installer:");
				System.exit(1);
			}
			return;
		}

		try {
			doUpdate();
		} catch (Exception e) {
			showError(e, "There was an error downloading packwiz-installer:");
		}

		try {
			LoadJAR.start(args, jarPath);
		} catch (Exception e) {
			showError(e, "There was an error loading packwiz-installer (did it download properly?):");
			System.exit(1);
		}
	}

	private void doUpdate() throws IOException, GithubException {
		String currVersion = LoadJAR.getVersion(jarPath);
		Release ghRelease = requestRelease();
		
		if (ghRelease == null) {
			return;
		}

		System.out.println("Current version is: " + currVersion);
		System.out.println("New version is: " + ghRelease.tagName);
		if (!ghRelease.tagName.equals(currVersion)) {
			System.out.println("Attempting to update...");
			RollbackHandler backup = new RollbackHandler(jarPath);

			try {
				downloadUpdate(ghRelease.downloadURL, ghRelease.assetURL, jarPath);
			} catch (InterruptedIOException e) {
				// User did this, don't show the error
				try {
					backup.rollback();
				} catch (IOException e1) {
					e1.printStackTrace();
				}
				return;
			} catch (IOException e) {
				showError(e, "Update download failed, attempting to rollback:");
				try {
					backup.rollback();
				} catch (IOException e1) {
					e1.printStackTrace();
				}
				return;
			}

			System.out.println("Update successful!");
		} else {
			System.out.println("Already up to date!");
		}
	}

	private void showError(Exception e, String message) {
		if (useGUI) {
			e.printStackTrace();
			try {
				EventQueue.invokeAndWait(() -> JOptionPane.showMessageDialog(null,
					message + "\n" + e.getClass().getCanonicalName() + ": " + e.getMessage(),
					"packwiz-installer-bootstrap", JOptionPane.ERROR_MESSAGE));
			} catch (InterruptedException | InvocationTargetException ex) {
				System.out.println("Unexpected interruption while showing error message");
				ex.printStackTrace();
			}
		} else {
			System.out.println(message);
			e.printStackTrace();
		}
	}

	private void parseOptions(String[] args) throws ParseException {
		Options options = new Options();
		options.addOption(null, "bootstrap-update-url", true, "Github API URL for checking for updates");
		options.addOption(null, "bootstrap-update-token", true, "Github API Access Token, for private repositories");
		options.addOption(null, "bootstrap-no-update", false, "Don't update packwiz-installer");
		options.addOption(null, "bootstrap-main-jar", true, "Location of the packwiz-installer JAR file");
		options.addOption("g", "no-gui", false, "Don't display a GUI to show update progress");
		options.addOption("h", "help", false, "Display this message");

		CommandLineParser parser = new DefaultParser();
		CommandLine cmd = parser.parse(options, filterArgs(args, options));

		if (cmd.hasOption("bootstrap-main-jar")) {
			jarPath = cmd.getOptionValue("bootstrap-main-jar");
		}

		if (cmd.hasOption("help")) {
			HelpFormatter formatter = new HelpFormatter();
			// Add options from packwiz-installer JAR, if it is present
			boolean jarLoaded = LoadJAR.addOptions(options, jarPath);
			formatter.printHelp("java -jar packwiz-installer-bootstrap.jar", options);
			if (!jarLoaded) {
				System.out.println("Options for packwiz-installer will be visible once it has been downloaded.");
			}
			System.exit(0);
		}

		if (cmd.hasOption("bootstrap-update-url")) {
			updateURL = cmd.getOptionValue("bootstrap-update-url");
		}
		
		if (cmd.hasOption("bootstrap-update-token")) {
			accessToken = cmd.getOptionValue("bootstrap-update-token");
		}

		if (cmd.hasOption("bootstrap-no-update")) {
			skipUpdate = true;
		}

		if (cmd.hasOption("no-gui")) {
			useGUI = false;
		}
	}

	// Remove invalid arguments, because Commons CLI chokes on invalid arguments
	// (that should be passed to packwiz-installer)
	private String[] filterArgs(String[] args, Options options) {
		List<String> argsList = new ArrayList<>(args.length);
		boolean prevOptWasArg = false;
		for (String arg : args) {
			if (arg.charAt(0) == '-' && options.hasOption(arg)) {
				if (options.getOption(arg).hasArg()) {
					prevOptWasArg = true;
				}
			} else {
				if (prevOptWasArg) {
					prevOptWasArg = false;
				} else {
					continue;
				}
			}
			argsList.add(arg);
		}

		return argsList.toArray(new String[0]);
	}

	private static class Release {
		String tagName = null;
		String downloadURL = null;
		String assetURL = null;
	}

	private static class GithubException extends Exception {
		private static final long serialVersionUID = 3843811090801607241L;

		public GithubException() {
			super("Invalid Github API response");
		}

		public GithubException(String message) {
			super("Invalid Github API response: " + message);
		}
	}
	
	static class ConnMonitorInputStream extends InputStream {
		private InputStream in = null;
		private int size = -1;
		private int bytesRead = 0;
		private final URLConnection conn;
		private ProgressMonitor mon;

		public ConnMonitorInputStream(URLConnection conn, String message, String note) {
			this.conn = conn;
			EventQueue.invokeLater(() -> {
				mon = new ProgressMonitor(null, message, note, 0, 1);
				mon.setMillisToDecideToPopup(1);
				mon.setMillisToPopup(1);
			});
		}
		
		private void setup() throws IOException {
			if (in == null) {
				try {
					size = conn.getContentLength();
					in = conn.getInputStream();
					EventQueue.invokeLater(() -> {
						mon.setProgress(0);
						if (size > -1) {
							mon.setMaximum(size);
						}
					});
				} catch (IOException e) {
					EventQueue.invokeLater(() -> mon.close());
					throw e;
				}
			}
		}

		public int available() {
			if (size > -1) {
				return (size - bytesRead);
			} else {
				return 1;
			}
		}

		private final AtomicBoolean wasCancelled = new AtomicBoolean();
		private long lastMillisUpdated = System.currentTimeMillis() - 110;
		
		private void setProgress() throws InterruptedIOException {
			// Update at most once every 100 ms
			if (System.currentTimeMillis() - lastMillisUpdated < 100) {
				return;
			}
			lastMillisUpdated = System.currentTimeMillis();
			final int progress = size > -1 ? bytesRead : -1;
			EventQueue.invokeLater(() -> {
				if (progress > -1) {
					mon.setProgress(progress);
				}
				wasCancelled.set(mon.isCanceled());
			});
			if (wasCancelled.get()) {
				throw new InterruptedIOException("Download cancelled!");
			}
		}

		public int read() throws IOException {
			setup();
			int b = in.read();
			if (b != -1) {
				bytesRead++;
				setProgress();
			}
			return b;
		}

		public int read(byte[] b) throws IOException {
			setup();
			int read = in.read(b);
			bytesRead += read;
			setProgress();
			return read;
		}

		public int read(byte[] b, int off, int len) throws IOException {
			setup();
			int read = in.read(b, off, len);
			bytesRead += read;
			setProgress();
			return read;
		}
		
		public void close() throws IOException {
			super.close();
			EventQueue.invokeLater(() -> mon.close());
			if (wasCancelled.get()) {
				throw new InterruptedIOException("Download cancelled!");
			}
		}
	}

	private Release requestRelease() throws IOException, GithubException {
		Release rel = new Release();

		URL url = new URL(updateURL);
		if (accessToken != null) {
			url = new URL(updateURL + "?access_token=" + accessToken);
		}
		URLConnection conn = url.openConnection();
		// 30 second read timeout
		conn.setReadTimeout(30 * 1000);
		InputStream in;
		if (useGUI) {
			in = new ConnMonitorInputStream(conn, "Checking for packwiz-installer updates...", null);
		} else {
			in = conn.getInputStream();
		}
		Reader streamReader = new InputStreamReader(in);
		JsonObject object;
		try {
			object = Json.parse(streamReader).asObject();
		} catch (InterruptedIOException e) {
			System.out.println("Update check cancelled!");
			return null;
		}
		streamReader.close();

		JsonValue tagName = object.get("tag_name");
		if (tagName == null || !tagName.isString()) {
			throw new GithubException("Tag name cannot be found");
		}
		rel.tagName = tagName.asString();

		JsonValue assets = object.get("assets");
		if (assets == null || !assets.isArray()) {
			throw new GithubException("Assets array cannot be found");
		}
		for (JsonValue assetValue : assets.asArray()) {
			if (!assetValue.isObject()) {
				throw new GithubException();
			}
			JsonObject asset = assetValue.asObject();
			JsonValue name = asset.get("name");
			if (name == null || !name.isString()) {
				throw new GithubException("Asset name cannot be found");
			}
			if (!name.asString().equalsIgnoreCase(JAR_NAME)) {
				continue;
			}
			JsonValue downloadURL = asset.get("browser_download_url");
			if (downloadURL == null || !downloadURL.isString()) {
				throw new GithubException("Asset Download URL cannot be found");
			}
			rel.downloadURL = downloadURL.asString();
			
			JsonValue assetURL = asset.get("url");
			if (assetURL == null || !assetURL.isString()) {
				throw new GithubException("Asset Download URL cannot be found");
			}
			rel.assetURL = assetURL.asString();
			break;
		}
		if (rel.tagName == null) {
			throw new GithubException("Latest release asset cannot be found");
		}

		return rel;
	}

	private void downloadUpdate(String downloadURL, String assetURL, String path) throws IOException {
		URL url = new URL(downloadURL);
		if (accessToken != null) {
			//url = new URL(downloadURL + "?access_token=" + accessToken);
			// Authenticated downloads use the assetURL
			url = new URL(assetURL + "?access_token=" + accessToken);
		}
		URLConnection conn = url.openConnection();
		conn.addRequestProperty("Accept", "application/octet-stream");
		// 30 second read timeout
		conn.setReadTimeout(30 * 1000);
		InputStream in;
		if (useGUI) {
			in = new ConnMonitorInputStream(conn, "Updating packwiz-installer...", null);
		} else {
			in = conn.getInputStream();
		}
		Files.copy(in, Paths.get(path), StandardCopyOption.REPLACE_EXISTING);
		in.close();
	}

}
