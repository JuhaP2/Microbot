package net.runelite.client.plugins.microbot.jagexlogin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.util.LinkBrowser;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Signs in to a Jagex account without the Jagex Launcher and writes the game session to
 * {@code ~/.runelite/credentials.properties}, which the game client reads on startup.
 * <p>
 * The flow mirrors the official launcher: an OAuth2 PKCE login in the system browser, a consent step
 * for the game client, then a game session and character lookup against the game-session API.
 */
@Slf4j
public final class JagexAccountLogin
{
	private static final String ORIGIN = "https://account.jagex.com";
	private static final String LAUNCHER_CLIENT_ID = "com_jagex_auth_desktop_launcher";
	private static final String LAUNCHER_REDIRECT = "https://secure.runescape.com/m=weblogin/launcher-redirect";
	private static final String CONSENT_CLIENT_ID = "1fddee4e-b100-4f4e-b2b0-097f9088f9d2";
	private static final String CONSENT_REDIRECT = "http://localhost";
	private static final String GAME_SESSION_API = "https://auth.jagex.com/game-session/v1";

	private static final File CREDENTIALS_FILE = new File(RuneLite.RUNELITE_DIR, "credentials.properties");
	private static final File PREFS_FILE = new File(RuneLite.RUNELITE_DIR, "microbot-jagex-login.properties");
	private static final String PREF_SKIP_PROMPT = "skipPrompt";

	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Gson GSON = new Gson();

	private final OkHttpClient http;

	private JagexAccountLogin(OkHttpClient http)
	{
		this.http = http;
	}

	/**
	 * Called during startup, before the game client is loaded. Keeps a valid saved session, otherwise
	 * offers a Jagex account login.
	 *
	 * @param force always run the login, e.g. to switch account
	 */
	public static void runIfNeeded(OkHttpClient http, boolean force)
	{
		if (GraphicsEnvironment.isHeadless() || Boolean.getBoolean("microbot.test.mode"))
		{
			return;
		}
		// Started from the Jagex Launcher, which already provides the session
		if (System.getenv("JX_SESSION_ID") != null && System.getenv("JX_CHARACTER_ID") != null)
		{
			return;
		}

		JagexAccountLogin login = new JagexAccountLogin(http);
		String reason;
		if (force)
		{
			reason = "Sign in to switch Jagex account.";
		}
		else if (CREDENTIALS_FILE.exists())
		{
			SessionState state = login.checkSavedSession();
			if (state != SessionState.EXPIRED)
			{
				return;
			}
			reason = "Your saved Jagex account session has expired.";
		}
		else
		{
			if (Boolean.parseBoolean(loadProperties(PREFS_FILE).getProperty(PREF_SKIP_PROMPT)))
			{
				return;
			}
			reason = "No saved Jagex account session was found.";
		}

		login.prompt(reason);
	}

	private enum SessionState
	{
		VALID,
		EXPIRED,
		UNKNOWN
	}

	private SessionState checkSavedSession()
	{
		String sessionId = loadProperties(CREDENTIALS_FILE).getProperty("JX_SESSION_ID");
		if (sessionId == null || sessionId.isEmpty())
		{
			return SessionState.EXPIRED;
		}
		Request request = new Request.Builder()
			.url(GAME_SESSION_API + "/accounts")
			.header("Authorization", "Bearer " + sessionId)
			.header("Accept", "application/json")
			.build();
		try (Response response = http.newCall(request).execute())
		{
			if (response.isSuccessful())
			{
				return SessionState.VALID;
			}
			if (response.code() == 401 || response.code() == 403)
			{
				return SessionState.EXPIRED;
			}
			log.warn("Unexpected status {} checking Jagex session", response.code());
		}
		catch (IOException e)
		{
			log.warn("Unable to check Jagex session: {}", e.getMessage());
		}
		// Don't throw the session away when Jagex can't be reached
		return SessionState.UNKNOWN;
	}

	private void prompt(String reason)
	{
		JCheckBox dontAsk = new JCheckBox("Don't ask again");
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.add(new JLabel(reason));
		panel.add(new JLabel("Sign in with your Jagex account now? Your browser will open for the login."));
		if (!CREDENTIALS_FILE.exists())
		{
			panel.add(dontAsk);
		}

		Object[] options = {"Sign in with Jagex account", "Use username/password"};
		int choice = JOptionPane.showOptionDialog(null, panel, "Microbot - Jagex account",
			JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
		if (choice != 0)
		{
			if (dontAsk.isSelected())
			{
				Properties prefs = loadProperties(PREFS_FILE);
				prefs.setProperty(PREF_SKIP_PROMPT, "true");
				storeProperties(PREFS_FILE, prefs, "Microbot Jagex account login preferences");
			}
			return;
		}

		while (true)
		{
			try
			{
				// Returns false when the user cancelled; either way we're done
				login();
				return;
			}
			catch (Exception e)
			{
				log.warn("Jagex account login failed", e);
				int retry = JOptionPane.showConfirmDialog(null,
					"Jagex account login failed:\n" + e.getMessage() + "\n\nTry again?",
					"Microbot - Jagex account", JOptionPane.YES_NO_OPTION, JOptionPane.ERROR_MESSAGE);
				if (retry != JOptionPane.YES_OPTION)
				{
					return;
				}
			}
		}
	}

	/**
	 * @return true when credentials were written, false when the user cancelled
	 */
	private boolean login() throws IOException
	{
		// Step 1: account login with PKCE
		String verifier = randomToken(48);
		String state = randomToken(16);
		Map<String, String> authParams = new LinkedHashMap<>();
		authParams.put("auth_method", "");
		authParams.put("login_type", "");
		authParams.put("flow", "launcher");
		authParams.put("response_type", "code");
		authParams.put("client_id", LAUNCHER_CLIENT_ID);
		authParams.put("redirect_uri", LAUNCHER_REDIRECT);
		authParams.put("code_challenge", base64Url(sha256(verifier)));
		authParams.put("code_challenge_method", "S256");
		authParams.put("prompt", "login");
		authParams.put("scope", "openid offline gamesso.token.create user.profile.read");
		authParams.put("state", state);
		LinkBrowser.browse(ORIGIN + "/oauth2/auth?" + query(authParams));

		String redirect = awaitRedirect(
			"<html>Log in to your Jagex account in the browser window that just opened.<br><br>"
				+ "When the login is done, the browser asks to open the Jagex Launcher: <b>cancel it</b>.<br>"
				+ "Then copy the whole address from the browser's address bar and paste it here.</html>",
			null);
		if (redirect == null)
		{
			return false;
		}
		Map<String, String> loginResult = parseRedirect(redirect);
		String code = loginResult.get("code");
		if (code == null)
		{
			throw new IOException("The pasted address does not contain a login code.");
		}
		if (loginResult.containsKey("state") && !state.equals(loginResult.get("state")))
		{
			throw new IOException("The pasted address belongs to a different login attempt. Please try again.");
		}

		JsonObject tokens = postForm(ORIGIN + "/oauth2/token", new FormBody.Builder()
			.add("grant_type", "authorization_code")
			.add("client_id", LAUNCHER_CLIENT_ID)
			.add("code", code)
			.add("code_verifier", verifier)
			.add("redirect_uri", LAUNCHER_REDIRECT)
			.build());
		String launcherIdToken = getString(tokens, "id_token");

		// Step 2: consent for the game client, redirected to http://localhost with the result in the URL fragment
		String nonce = randomToken(24);
		String consentState = randomToken(16);
		Map<String, String> consentParams = new LinkedHashMap<>();
		consentParams.put("id_token_hint", launcherIdToken);
		consentParams.put("nonce", nonce);
		consentParams.put("prompt", "consent");
		consentParams.put("redirect_uri", CONSENT_REDIRECT);
		consentParams.put("response_type", "id_token code");
		consentParams.put("state", consentState);
		consentParams.put("client_id", CONSENT_CLIENT_ID);
		consentParams.put("scope", "openid offline");

		CompletableFuture<String> callback = new CompletableFuture<>();
		HttpServer server = startCallbackServer(callback);
		String consentRedirect;
		try
		{
			LinkBrowser.browse(ORIGIN + "/oauth2/auth?" + query(consentParams));
			consentRedirect = awaitRedirect(
				"<html>Allow access in the browser window to finish signing in.<br><br>"
					+ (server != null
					? "This window closes by itself once it's done. If it doesn't, copy the address<br>"
					+ "from the browser's address bar (starts with http://localhost) and paste it here.</html>"
					: "Then copy the address from the browser's address bar (starts with http://localhost)<br>"
					+ "and paste it here.</html>"),
				callback);
		}
		finally
		{
			if (server != null)
			{
				server.stop(0);
			}
		}
		if (consentRedirect == null)
		{
			return false;
		}
		Map<String, String> consentResult = parseRedirect(consentRedirect);
		String idToken = consentResult.get("id_token");
		if (idToken == null)
		{
			throw new IOException("The consent step did not return an id_token.");
		}
		if (consentResult.containsKey("state") && !consentState.equals(consentResult.get("state")))
		{
			throw new IOException("The consent step belongs to a different login attempt. Please try again.");
		}
		validateNonce(idToken, nonce);

		// Step 3: game session and character
		JsonObject session = postJson(GAME_SESSION_API + "/sessions", "{\"idToken\":" + GSON.toJson(idToken) + "}");
		String sessionId = getString(session, "sessionId");
		JsonObject account = chooseAccount(getAccounts(sessionId));
		if (account == null)
		{
			return false;
		}

		Properties credentials = new Properties();
		credentials.setProperty("JX_CHARACTER_ID", getString(account, "accountId"));
		credentials.setProperty("JX_SESSION_ID", sessionId);
		credentials.setProperty("JX_DISPLAY_NAME", optString(account, "displayName"));
		credentials.setProperty("JX_REFRESH_TOKEN", "");
		credentials.setProperty("JX_ACCESS_TOKEN", "");
		storeProperties(CREDENTIALS_FILE, credentials, "Jagex account session written by Microbot - do not share");
		log.info("Saved Jagex account session for {}", optString(account, "displayName"));
		return true;
	}

	private JsonArray getAccounts(String sessionId) throws IOException
	{
		Request request = new Request.Builder()
			.url(GAME_SESSION_API + "/accounts")
			.header("Authorization", "Bearer " + sessionId)
			.header("Accept", "application/json")
			.build();
		try (Response response = http.newCall(request).execute())
		{
			String body = response.body() != null ? response.body().string() : "";
			if (!response.isSuccessful())
			{
				throw new IOException("Fetching characters failed (HTTP " + response.code() + ")");
			}
			JsonArray accounts = GSON.fromJson(body, JsonArray.class);
			if (accounts == null || accounts.size() == 0)
			{
				throw new IOException("This Jagex account has no game characters.");
			}
			return accounts;
		}
	}

	private static JsonObject chooseAccount(JsonArray accounts)
	{
		if (accounts.size() == 1)
		{
			return accounts.get(0).getAsJsonObject();
		}
		List<String> names = new ArrayList<>();
		for (JsonElement element : accounts)
		{
			String name = optString(element.getAsJsonObject(), "displayName");
			names.add(name.isEmpty() ? "(no name yet) " + optString(element.getAsJsonObject(), "accountId") : name);
		}
		Object selected = JOptionPane.showInputDialog(null, "Choose the character to play:", "Microbot - Jagex account",
			JOptionPane.QUESTION_MESSAGE, null, names.toArray(), names.get(0));
		if (selected == null)
		{
			return null;
		}
		return accounts.get(names.indexOf(selected)).getAsJsonObject();
	}

	/**
	 * Listens on http://localhost for the consent redirect. The tokens are in the URL fragment, which
	 * browsers don't send to the server, so the page forwards it with a second request.
	 */
	private static HttpServer startCallbackServer(CompletableFuture<String> callback)
	{
		HttpServer server;
		try
		{
			server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 80), 0);
		}
		catch (IOException e)
		{
			log.warn("Can't listen on http://localhost, falling back to pasting the address: {}", e.getMessage());
			return null;
		}

		server.createContext("/callback", exchange ->
		{
			String query = exchange.getRequestURI().getRawQuery();
			respond(exchange, 204, "");
			if (query != null && !query.isEmpty())
			{
				callback.complete("http://localhost/#" + query);
			}
		});
		server.createContext("/", exchange -> respond(exchange, 200,
			"<!doctype html><html><head><meta charset=\"utf-8\"><title>Microbot</title></head>"
				+ "<body style=\"font-family:sans-serif\"><p id=\"m\">Finishing sign-in...</p><script>"
				+ "var h=location.hash.substring(1);"
				+ "fetch('/callback?'+h).then(function(){document.getElementById('m').textContent="
				+ "'Signed in. You can close this tab and return to Microbot.';});"
				+ "</script></body></html>"));
		server.start();
		return server;
	}

	private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException
	{
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
		exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
		try (OutputStream out = exchange.getResponseBody())
		{
			if (status != 204)
			{
				out.write(bytes);
			}
		}
	}

	/**
	 * Shows a dialog where the user can paste the redirect address. When {@code auto} completes first,
	 * its value is used and the dialog closes.
	 *
	 * @return the redirect address, or null if cancelled
	 */
	private static String awaitRedirect(String message, CompletableFuture<String> auto)
	{
		String[] result = new String[1];
		JDialog dialog = new JDialog((java.awt.Frame) null, "Microbot - Jagex account", true);
		JTextField field = new JTextField(50);
		JButton ok = new JButton("Continue");
		JButton cancel = new JButton("Cancel");
		ok.addActionListener(e ->
		{
			if (!field.getText().trim().isEmpty())
			{
				result[0] = field.getText().trim();
				dialog.dispose();
			}
		});
		cancel.addActionListener(e -> dialog.dispose());

		JPanel buttons = new JPanel();
		buttons.add(ok);
		buttons.add(cancel);
		JPanel content = new JPanel(new BorderLayout(8, 8));
		content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		content.add(new JLabel(message), BorderLayout.NORTH);
		content.add(field, BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);
		dialog.setContentPane(content);
		dialog.getRootPane().setDefaultButton(ok);
		dialog.pack();
		dialog.setLocationRelativeTo(null);
		dialog.setAlwaysOnTop(true);

		if (auto != null)
		{
			auto.thenAccept(value -> SwingUtilities.invokeLater(() ->
			{
				if (dialog.isDisplayable())
				{
					result[0] = value;
					dialog.dispose();
				}
			}));
		}
		dialog.setVisible(true);
		return result[0];
	}

	/**
	 * Reads code/state/id_token from a redirect address: query string, URL fragment, or the
	 * launcher's {@code jagex:code=...,state=...} form.
	 */
	static Map<String, String> parseRedirect(String url)
	{
		Map<String, String> values = new LinkedHashMap<>();
		String text = url.trim();
		int start = text.indexOf('?');
		if (start < 0)
		{
			start = text.indexOf('#');
		}
		if (start < 0 && text.startsWith("jagex:"))
		{
			start = "jagex:".length() - 1;
		}
		String params = start >= 0 ? text.substring(start + 1) : text;
		for (String part : params.split("[&,#]"))
		{
			int eq = part.indexOf('=');
			if (eq > 0)
			{
				values.put(decode(part.substring(0, eq)), decode(part.substring(eq + 1)));
			}
		}
		return values;
	}

	private static void validateNonce(String idToken, String nonce) throws IOException
	{
		String[] sections = idToken.split("\\.");
		if (sections.length != 3)
		{
			throw new IOException("Malformed id_token");
		}
		String payload = new String(Base64.getUrlDecoder().decode(sections[1]), StandardCharsets.UTF_8);
		JsonObject claims = GSON.fromJson(payload, JsonObject.class);
		if (!nonce.equals(optString(claims, "nonce")))
		{
			throw new IOException("The id_token nonce does not match this login attempt.");
		}
	}

	private JsonObject postForm(String url, FormBody body) throws IOException
	{
		return execute(new Request.Builder().url(url).header("Accept", "application/json").post(body).build());
	}

	private JsonObject postJson(String url, String json) throws IOException
	{
		return execute(new Request.Builder().url(url).header("Accept", "application/json")
			.post(RequestBody.create(JSON, json)).build());
	}

	private JsonObject execute(Request request) throws IOException
	{
		try (Response response = http.newCall(request).execute())
		{
			String body = response.body() != null ? response.body().string() : "";
			if (!response.isSuccessful())
			{
				throw new IOException(request.url().encodedPath() + " failed (HTTP " + response.code() + "): " + body);
			}
			return GSON.fromJson(body, JsonObject.class);
		}
	}

	private static String getString(JsonObject object, String key) throws IOException
	{
		String value = optString(object, key);
		if (value.isEmpty())
		{
			throw new IOException("Missing '" + key + "' in Jagex response");
		}
		return value;
	}

	private static String optString(JsonObject object, String key)
	{
		JsonElement element = object == null ? null : object.get(key);
		return element == null || element.isJsonNull() ? "" : element.getAsString();
	}

	private static String query(Map<String, String> params)
	{
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> entry : params.entrySet())
		{
			if (sb.length() > 0)
			{
				sb.append('&');
			}
			sb.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
		}
		return sb.toString();
	}

	private static String encode(String value)
	{
		try
		{
			return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
		}
		catch (IOException e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static String decode(String value)
	{
		try
		{
			return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
		}
		catch (IOException | IllegalArgumentException e)
		{
			return value;
		}
	}

	private static String randomToken(int bytes)
	{
		byte[] data = new byte[bytes];
		RANDOM.nextBytes(data);
		return base64Url(data);
	}

	private static String base64Url(byte[] data)
	{
		return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
	}

	private static byte[] sha256(String value)
	{
		try
		{
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static Properties loadProperties(File file)
	{
		Properties properties = new Properties();
		if (file.exists())
		{
			try (InputStream in = new FileInputStream(file))
			{
				properties.load(in);
			}
			catch (IOException e)
			{
				log.warn("Unable to read {}: {}", file.getName(), e.getMessage());
			}
		}
		return properties;
	}

	private static void storeProperties(File file, Properties properties, String comment)
	{
		try
		{
			file.getParentFile().mkdirs();
			try (OutputStream out = new FileOutputStream(file))
			{
				properties.store(out, comment);
			}
		}
		catch (IOException e)
		{
			log.warn("Unable to write {}: {}", file.getName(), e.getMessage());
		}
	}
}
