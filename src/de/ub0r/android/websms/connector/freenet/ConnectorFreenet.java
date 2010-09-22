/*
 * Copyright (C) 2010 Dirk Bliesener, Felix Bechstein
 * 
 * This file is part of WebSMS.
 * 
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * this program; If not, see <http://www.gnu.org/licenses/>.
 */
package de.ub0r.android.websms.connector.freenet;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.DefaultHttpClient;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import de.ub0r.android.websms.connector.common.Connector;
import de.ub0r.android.websms.connector.common.ConnectorCommand;
import de.ub0r.android.websms.connector.common.ConnectorSpec;
import de.ub0r.android.websms.connector.common.Log;
import de.ub0r.android.websms.connector.common.Utils;
import de.ub0r.android.websms.connector.common.WebSMSException;
import de.ub0r.android.websms.connector.common.ConnectorSpec.SubConnectorSpec;

/**
 * AsyncTask to manage IO freenet.de Email Office XML connector
 * 
 * @author drkbli
 */
public class ConnectorFreenet extends Connector {
	/** Tag for output. */
	private static final String TAG = "freenet";

	/** freenet.de Email Office XML connector URL. */
	private static final String URL_EMO = "http://storage.freenet.de/sync/"
			+ "remoteaccess/service/xml_emo_connector.php";

	/** secret identifier expected by the XML connector */
	private static final String SEC = "8GPRTK42ER1_";
	/** XML header template */
	private static final String XH = "<?xml version=\"1.0\" encoding=\"utf-8\"?>";
	/** server time command XML template */
	private static final String TIME = XH
			+ "<SMS_TIME>GetServerTime</SMS_TIME>";
	/** autentication string XML template */
	private static final String AUTH = "<Login><UserName>%s</UserName><AuthHash>%s</AuthHash></Login>";
	/** sms quota (ballance) command XML template */
	private static final String QUOTA = XH + "<SMS_QUOTA>" + AUTH
			+ "</SMS_QUOTA>";
	/** sms recipient XML template */
	private static final String TO = "<Recipient><Id>%d</Id><Phone>%s</Phone></Recipient>";
	/** sms command XML template */
	private static final String SMS = XH + "<SMS><SMS_ID>%d</SMS_ID>" + AUTH
			+ "<Recipients>%s</Recipients><Text><Line>%s</Line>"
			+ "</Text><Options><SenderNr>%s</SenderNr>"
			+ "<BlitzSMS>%d</BlitzSMS>" + "</Options></SMS>";
	/** http client to access the XML connector */
	private final DefaultHttpClient client = new DefaultHttpClient();
	/** http response Buffer */
	private final byte[] httpBuffer = new byte[64 * 1024];
	/** RegExp to extract result from SMS_time command's response */
	private final Pattern SMS_time = Pattern.compile("<SMS_time>([^<]+)<");
	/** RegExp to extract result from SMS_quota command's response */
	private final Pattern SMS_quota = Pattern.compile("<SMS_quota>([^<]+)<");
	/** RegExp to extract result from SMS command's response */
	private final Pattern StatusText = Pattern.compile("<StatusText>([^<]+)<");

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final ConnectorSpec initSpec(final Context context) {
		final String name = context.getString(R.string.connector_freenet_name);
		ConnectorSpec c = new ConnectorSpec(name);
		c.setAuthor(context.getString(R.string.connector_freenet_author));
		c.setBalance(null);
		c.setCapabilities(ConnectorSpec.CAPABILITIES_UPDATE
				| ConnectorSpec.CAPABILITIES_SEND
				| ConnectorSpec.CAPABILITIES_PREFS);
		c.addSubConnector(TAG, c.getName(),
				SubConnectorSpec.FEATURE_MULTIRECIPIENTS
						| SubConnectorSpec.FEATURE_FLASHSMS
						| SubConnectorSpec.FEATURE_CUSTOMSENDER);
		return c;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final ConnectorSpec updateSpec(final Context context,
			final ConnectorSpec connectorSpec) {
		final SharedPreferences p = PreferenceManager
				.getDefaultSharedPreferences(context);
		if (p.getBoolean(Preferences.PREFS_ENABLED, false)) {
			if (p.getString(Preferences.PREFS_USER, "").length() > 0
					&& p.getString(Preferences.PREFS_PASSWORD, "")// .
							.length() > 0) {
				connectorSpec.setReady();
			} else {
				connectorSpec.setStatus(ConnectorSpec.STATUS_ENABLED);
			}
		} else {
			connectorSpec.setStatus(ConnectorSpec.STATUS_INACTIVE);
		}
		return connectorSpec;
	}

	/**
	 * send xml command send to freenet XML sms connector and return XML
	 * response
	 * 
	 * @param context
	 *            sms connector context
	 * @param xml
	 *            command to send to freenet XML sms connector
	 * @return String contaning XML response
	 */
	private String sendXml(final Context context, final String xml) {
		String resXml = null;
		try {
			// POST UTF-8 xml command to server
			final HttpPost req = new HttpPost(URL_EMO);
			req.setHeader("content-type", "text/xml");
			final byte[] bytes = xml.getBytes("utf-8");
			req.setEntity(new ByteArrayEntity(bytes));
			final HttpResponse response = this.client.execute(req);
			// check server status
			final int resp = response.getStatusLine().getStatusCode();
			if (resp != HttpURLConnection.HTTP_OK) {
				throw new WebSMSException(context, R.string.error_http, " "
						+ resp);
			}
			// parse XML response into HashMap
			final InputStream in = response.getEntity().getContent();
			final int cnt = in.read(this.httpBuffer);
			if (cnt > 0) {
				resXml = new String(this.httpBuffer, 0, cnt, "utf-8");
			}
			if (null == resXml) {
				throw new WebSMSException(context, R.string.error_http,
						"no XML from server");
			}
			// else {
			// Log.d(TAG, resXml);
			// }
		} catch (Exception e) {
			Log.e(TAG, "sendXml", e);
			throw new WebSMSException(e.getMessage());
		}
		return resXml;
	}

	/**
	 * read sms time from server (needed for authentication and other subsequent
	 * commands )
	 * 
	 * @param context
	 *            sms connector context
	 * @return server (unix) timestamp
	 */
	private String sms_time(final Context context) {
		// Response:<SMS_TIME>...<SMS_time>1283337953</SMS_time></SMS_TIME>
		String time = "";
		final String dres = this.sendXml(context, TIME);
		if (null != dres) {
			final Matcher match = this.SMS_time.matcher(dres);
			if (match.find()) {
				time = match.group(1);
			}
		}
		return time;
	}

	/**
	 * Send data.
	 * 
	 * @param context
	 *            Context
	 * @param command
	 *            ConnectorCommand
	 */
	private void sendData(final Context context, final ConnectorCommand command) {
		try {
			// get server time and compute authHash
			final ConnectorSpec cs = this.getSpec(context);
			final SharedPreferences pref = PreferenceManager
					.getDefaultSharedPreferences(context);
			final String user = pref.getString(Preferences.PREFS_USER, "");
			final String pwd = pref.getString(Preferences.PREFS_PASSWORD, "");
			final String time = this.sms_time(context);
			final String authHash = time
					+ Utils.md5(Utils.md5(time + user) + SEC + Utils.md5(pwd));
			if (command.getType() == ConnectorCommand.TYPE_UPDATE) {
				// Response: <SMS_QUOTA>...<SMS_quota>120</SMS_quota>...
				// <SMS_maxlength>160</SMS_maxlength>...</SMS_QUOTA>
				final String dres = this.sendXml(context, String.format(QUOTA,
						user, authHash));
				String quota = "0";
				if (null != dres) {
					final Matcher match = this.SMS_quota.matcher(dres);
					if (match.find()) {
						quota = match.group(1);
					}
				}
				cs.setBalance(quota);
			} else if (command.getType() == ConnectorCommand.TYPE_SEND) {
				final String text = command.getText();
				if (text == null || text.length() == 0) {
					return;
				}
				String sender = command.getCustomSender();
				if (null == sender || 0 == sender.length()) {
					sender = Utils.international2oldformat(Utils.getSender(
							context, command.getDefSender()));
				}
				final String[] to = Utils.national2international(command
						.getDefPrefix(), command.getRecipients());
				// prepare sender elements
				final StringBuffer toBuf = new StringBuffer();
				for (int i = 0; i < to.length; i++) {
					toBuf.append(String.format(TO, i, to[i]));
				}
				final short id = (short) (Math.random() * Short.MAX_VALUE);
				final boolean flash = command.getFlashSMS();
				// Response: <SMS>...<StatusText>OK</StatusText>...</SMS>
				final String dres = this.sendXml(context, String.format(SMS,
						id, user, authHash, toBuf.toString(), text, sender,
						flash ? 1 : 0));
				String code = "Unknown";
				if (null != dres) {
					final Matcher match = this.StatusText.matcher(dres);
					if (match.find()) {
						code = match.group(1);
					}
				}
				if (!code.equalsIgnoreCase("OK")) {
					throw new WebSMSException(context, R.string.error_server,
							code);
				}
				final int smscnt = to.length
						* (int) Math.ceil(text.length() / 160.0);
				int bal = Integer.parseInt(cs.getBalance()) - smscnt;
				if (bal < 0) {
					bal = 0;
				}
				cs.setBalance("" + bal);
			}

		} catch (Exception e) {
			Log.e(TAG, "sendData", e);
			throw new WebSMSException(e.getMessage());
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected final void doUpdate(final Context context, final Intent intent) {
		this.sendData(context, new ConnectorCommand(intent));
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected final void doSend(final Context context, final Intent intent) {
		this.sendData(context, new ConnectorCommand(intent));
	}
}
