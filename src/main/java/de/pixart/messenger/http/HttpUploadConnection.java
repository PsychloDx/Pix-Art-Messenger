package de.pixart.messenger.http;

import android.os.PowerManager;
import android.util.Log;
import android.util.Pair;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

import de.pixart.messenger.Config;
import de.pixart.messenger.entities.Account;
import de.pixart.messenger.entities.DownloadableFile;
import de.pixart.messenger.entities.Message;
import de.pixart.messenger.entities.Transferable;
import de.pixart.messenger.parser.IqParser;
import de.pixart.messenger.persistance.FileBackend;
import de.pixart.messenger.services.AbstractConnectionManager;
import de.pixart.messenger.services.XmppConnectionService;
import de.pixart.messenger.utils.CryptoHelper;
import de.pixart.messenger.utils.Namespace;
import de.pixart.messenger.utils.WakeLockHelper;
import de.pixart.messenger.xml.Element;
import de.pixart.messenger.xmpp.stanzas.IqPacket;
import rocks.xmpp.addr.Jid;

public class HttpUploadConnection implements Transferable {

    private static final List<String> WHITE_LISTED_HEADERS = Arrays.asList(
            "Authorization",
            "Cookie",
            "Expires"
    );

    private HttpConnectionManager mHttpConnectionManager;
    private XmppConnectionService mXmppConnectionService;

    private boolean canceled = false;
    private boolean delayed = false;
    private DownloadableFile file;
    private Message message;
    private String mime;
    private URL mGetUrl;
    private URL mPutUrl;
    private HashMap<String,String> mPutHeaders;
    private boolean mUseTor = false;

    private byte[] key = null;

    private long transmitted = 0;

    private InputStream mFileInputStream;

    public HttpUploadConnection(HttpConnectionManager httpConnectionManager) {
        this.mHttpConnectionManager = httpConnectionManager;
        this.mXmppConnectionService = httpConnectionManager.getXmppConnectionService();
        this.mUseTor = mXmppConnectionService.useTorToConnect();
    }

    @Override
    public boolean start() {
        return false;
    }

    @Override
    public int getStatus() {
        return STATUS_UPLOADING;
    }

    @Override
    public long getFileSize() {
        return file == null ? 0 : file.getExpectedSize();
    }

    @Override
    public int getProgress() {
        if (file == null) {
            return 0;
        }
        return (int) ((((double) transmitted) / file.getExpectedSize()) * 100);
    }

    @Override
    public void cancel() {
        this.canceled = true;
    }

    private void fail(String errorMessage) {
        mHttpConnectionManager.finishUploadConnection(this);
        message.setTransferable(null);
        mXmppConnectionService.markMessage(message, Message.STATUS_SEND_FAILED, errorMessage);
        FileBackend.close(mFileInputStream);
    }

    public void init(Message message, boolean delay) {
        this.message = message;
        final Account account = message.getConversation().getAccount();
        this.file = mXmppConnectionService.getFileBackend().getFile(message, false);
        if (message.getEncryption() == Message.ENCRYPTION_PGP || message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
            this.mime = "application/pgp-encrypted";
        } else {
            this.mime = this.file.getMimeType();
        }
        this.delayed = delay;
        if (Config.ENCRYPT_ON_HTTP_UPLOADED
                || message.getEncryption() == Message.ENCRYPTION_AXOLOTL
                || message.getEncryption() == Message.ENCRYPTION_OTR) {
            this.key = new byte[48]; // todo: change this to 44 for 12-byte IV instead of 16-byte at some point in future
            mXmppConnectionService.getRNG().nextBytes(this.key);
            this.file.setKeyAndIv(this.key);
        }
        Pair<InputStream, Integer> pair;
        try {
            pair = AbstractConnectionManager.createInputStream(file, true);
        } catch (FileNotFoundException e) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": could not find file to upload - " + e.getMessage());
            fail(e.getMessage());
            return;
        }
        this.file.setExpectedSize(pair.second);
        message.resetFileParams();
        this.mFileInputStream = pair.first;
        String http_upload_namespace = account.getXmppConnection().getFeatures().http_upload_namespace;
        Jid host = account.getXmppConnection().findDiscoItemByFeature(http_upload_namespace);
        IqPacket request = mXmppConnectionService.getIqGenerator().requestHttpUploadSlot(host, file, mime, http_upload_namespace);
        mXmppConnectionService.sendIqPacket(account, request, (a, packet) -> {
            if (packet.getType() == IqPacket.TYPE.RESULT) {
                Element slot = packet.findChild("slot", http_upload_namespace);
                if (slot != null) {
                    try {
                        final Element put = slot.findChild("put");
                        final Element get = slot.findChild("get");
                        final String putUrl;
                        final String getUrl;
                        if (http_upload_namespace == Namespace.HTTP_UPLOAD) {
                            putUrl = put == null ? null : put.getAttribute("url");
                            getUrl = get == null ? null : get.getAttribute("url");
                        } else {
                            putUrl = put == null ? null : put.getContent();
                            getUrl = get == null ? null : get.getContent();
                        }
                        if (getUrl != null && putUrl != null) {
                            this.mGetUrl = new URL(getUrl);
                            this.mPutUrl = new URL(putUrl);
                            this.mPutHeaders = new HashMap<>();
                            for (Element child : put.getChildren()) {
                                if ("header".equals(child.getName())) {
                                    final String name = child.getAttribute("name");
                                    final String value = child.getContent();
                                    if (WHITE_LISTED_HEADERS.contains(name) && value != null && !value.trim().contains("\n")) {
                                        this.mPutHeaders.put(name, value.trim());
                                    }
                                }
                            }
                            if (!canceled) {
                                new Thread(this::upload).start();
                            }
                            return;
                        }
                    } catch (MalformedURLException e) {
                        //fall through
                    }
                }
            }
            Log.d(Config.LOGTAG, account.getJid().toString() + ": invalid response to slot request " + packet);
            fail(IqParser.extractErrorMessage(packet));
        });
        message.setTransferable(this);
        mXmppConnectionService.markMessage(message, Message.STATUS_UNSEND);
    }

    private void upload() {
        OutputStream os = null;
        HttpURLConnection connection = null;
        PowerManager.WakeLock wakeLock = mHttpConnectionManager.createWakeLock("http_upload_" + message.getUuid());
        try {
            final int expectedFileSize = (int) file.getExpectedSize();
            final int readTimeout = (expectedFileSize / 2048) + Config.SOCKET_TIMEOUT; //assuming a minimum transfer speed of 16kbit/s
            wakeLock.acquire(readTimeout);
            Log.d(Config.LOGTAG, "uploading to " + mPutUrl.toString() + " w/ read timeout of " + readTimeout + "s");
            if (mUseTor) {
                connection = (HttpURLConnection) mPutUrl.openConnection(mHttpConnectionManager.getProxy());
            } else {
                connection = (HttpURLConnection) mPutUrl.openConnection();
            }
            if (connection instanceof HttpsURLConnection) {
                mHttpConnectionManager.setupTrustManager((HttpsURLConnection) connection, true);
            }
            connection.setUseCaches(false);
            connection.setRequestMethod("PUT");
            connection.setFixedLengthStreamingMode(expectedFileSize);
            connection.setRequestProperty("Content-Type", mime == null ? "application/octet-stream" : mime);
            connection.setRequestProperty("User-Agent", mXmppConnectionService.getIqGenerator().getIdentityName());
            if (mPutHeaders != null) {
                for (HashMap.Entry<String, String> entry : mPutHeaders.entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
            connection.setDoOutput(true);
            connection.setConnectTimeout(Config.SOCKET_TIMEOUT * 1000);
            connection.setReadTimeout(readTimeout * 1000);
            connection.connect();
            os = connection.getOutputStream();
            transmitted = 0;
            int count;
            byte[] buffer = new byte[4096];
            while (((count = mFileInputStream.read(buffer)) != -1) && !canceled) {
                transmitted += count;
                os.write(buffer, 0, count);
                mHttpConnectionManager.updateConversationUi(false);
            }
            os.flush();
            os.close();
            mFileInputStream.close();
            int code = connection.getResponseCode();
            if (code == 200 || code == 201) {
                Log.d(Config.LOGTAG, "finished uploading file");
                if (key != null) {
                    mGetUrl = CryptoHelper.toAesGcmUrl(new URL(mGetUrl.toString() + "#" + CryptoHelper.bytesToHex(key)));
                }
                mXmppConnectionService.getFileBackend().updateFileParams(message, mGetUrl);
                mXmppConnectionService.getFileBackend().updateMediaScanner(file);
                message.setTransferable(null);
                message.setCounterpart(message.getConversation().getJid().asBareJid());
                mXmppConnectionService.resendMessage(message, delayed);
            } else {
                Log.d(Config.LOGTAG, "http upload failed because response code was " + code);
                fail("http upload failed because response code was " + code);
            }
        } catch (IOException e) {
            e.printStackTrace();
            Log.d(Config.LOGTAG, "http upload failed " + e.getMessage());
            fail(e.getMessage());
        } finally {
            FileBackend.close(mFileInputStream);
            FileBackend.close(os);
            if (connection != null) {
                connection.disconnect();
            }
            WakeLockHelper.release(wakeLock);
        }
    }
}
