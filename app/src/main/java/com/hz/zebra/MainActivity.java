package com.hz.zebra;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.hardware.fingerprint.FingerprintManagerCompat;
import androidx.core.os.CancellationSignal;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.telecom.Call;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;


enum ErrorCode {
    NO_ERROR,
    UNKNOWN,
    INVALID_ARGS,
    FILE_NOT_FOUND
}

class Result<T> {
    public T value;
    public ErrorCode code;

    public Result(T value, ErrorCode code) {
        this.value = value;
        this.code = code;
    }

    public boolean isOk() {
        return code == ErrorCode.NO_ERROR;
    }

    public static <T> Result<T> ok(T value) {
        return new Result<T>(value, ErrorCode.NO_ERROR);
    }

    public static <T> Result<T> error(ErrorCode code) {
        return new Result<T>(null, code);
    }
}

class StreamUtils {
    public static Result<String> readStringFromStream(InputStream input) {
        if (input == null) {
            return Result.error(ErrorCode.INVALID_ARGS);
        }

        try {
            StringBuilder sb = new StringBuilder();
            byte[] buffer = new byte[1024];
            int byteRead = 0;
            while ((byteRead = input.read(buffer)) != -1) {
                sb.append(new String(buffer, 0, byteRead));
            }
            return Result.ok(sb.toString());
        } catch (Exception e) {
            Log.e("FileUtils", e.getMessage());
            return Result.error(ErrorCode.UNKNOWN);
        }
    }

    public static ErrorCode writeStringToStream(OutputStream output, String data) {
        if (output == null) {
            return ErrorCode.INVALID_ARGS;
        }

        try {
            output.write(data.getBytes());
            return ErrorCode.NO_ERROR;
        } catch (Exception e) {
            Log.e("FileUtils", e.getMessage());
            return ErrorCode.UNKNOWN;
        }
    }
}

class JsSettingsUtils {
    public static String generate(Map<String, String> map) {
        String before = "zebra-settings {";
        String after = "}";
        String insert = ":";
        String separator = ";";

        StringBuilder content = new StringBuilder();
        content.append(before);
        for (Map.Entry<String, String> entry : map.entrySet()) {
            content.append(entry.getKey());
            content.append(insert);
            content.append(entry.getValue());
            content.append(separator);
        }
        content.append(after);

        return content.toString();
    }
}

class KeyStoreWrapper {
    public static final String ALIAS_DEFAULT_KEY = "__ZEBRA_KEY__";
    public static final String SHARED_PREFERENCES_FILENAME = "__ZEBRA_PREFS__";

    private static final String PROVIDER_NAME = "AndroidKeyStore";

    private KeyStore mKeyStore;
    private SharedPreferences mSharedPreferences;

    public KeyStoreWrapper() {
        try {
            mKeyStore = KeyStore.getInstance(PROVIDER_NAME);
            mKeyStore.load(null);
        } catch (KeyStoreException | IOException | NoSuchAlgorithmException | CertificateException e) {
            e.printStackTrace();
        }
    }

    public boolean createSecretKey(String alias) {
        if (hasSecretKey(alias)) {
            return false;
        }

        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER_NAME);

            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT | KeyProperties.PURPOSE_ENCRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build();

            keyGenerator.init(spec);
            keyGenerator.generateKey();
            Log.w("KeyStore", "Secret key [" + alias + "] generated");
            return true;
        } catch (NoSuchAlgorithmException | NoSuchProviderException | InvalidAlgorithmParameterException e) {
            e.printStackTrace();
            Log.w("KeyStore", "Failed to generate secret key [" + alias + "]");
            return false;
        }
    }

    public SecretKey getSecretKey(String alias) {
        try {
            return (SecretKey) mKeyStore.getKey(alias, null);
        } catch (NoSuchAlgorithmException | UnrecoverableKeyException | KeyStoreException e) {
            e.printStackTrace();
            return null;
        }
    }

    public boolean hasSecretKey(String alias) {
        try {
            return alias != null && mKeyStore.containsAlias(alias);
        } catch (KeyStoreException e) {
            e.printStackTrace();
            return false;
        }
    }

    public void deleteSecretKey(String alias) {
        try {
            mKeyStore.deleteEntry(alias);
        } catch (KeyStoreException e) {
            e.printStackTrace();
        }
    }

    public boolean loadSharedPreferences(Context context, String secretKeyAlias) {
        if (hasSecretKey(secretKeyAlias)) {
            try {
                mSharedPreferences = EncryptedSharedPreferences.create(SHARED_PREFERENCES_FILENAME,
                        secretKeyAlias,
                        context,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
                return true;
            } catch (GeneralSecurityException | IOException e) {
                e.printStackTrace();
                return false;
            }
        }
        return false;
    }

    public boolean saveToSharedPreferences(String key, String value) {
        if (mSharedPreferences != null) {
            SharedPreferences.Editor editor = mSharedPreferences.edit();
            editor.putString(key, value);
            editor.apply();
            return true;
        }
        return false;
    }

    public String loadFromSharedPreferences(String key) {
        if (mSharedPreferences != null) {
            return mSharedPreferences.getString(key, null);
        }
        return null;
    }

    public void removeFromSharedPreferences(String key) {
        if (mSharedPreferences != null) {
            mSharedPreferences.edit().remove(key).apply();
        }
    }
}

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CODE_JS_IMPORT_FILE = 1;
    private static final int REQUEST_CODE_JS_EXPORT_FILE = 2;

    private WebView mMainView;
    private String mJsRequestedData;
    private ExecutorService mIOExecutor;
    private JsPlatform mJsPlatform;
    private KeyStoreWrapper mKeyStore;

    private static final String KEY_SUB_PWD = "__ZEBRA_SUB_PWD__";
    private static final String KEY_KEY = "__ZEBRA_KEY__";

    private void InitWebView() {
        // Enable javascript
        mMainView = (WebView) findViewById(R.id.main_view);
        WebSettings settings = mMainView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);

        // Set browser user-agent, this is used as config for our app
        Map<String, String> ua = new HashMap<String, String>();
        ua.put("platform", "android");
        settings.setUserAgentString(JsSettingsUtils.generate(ua));

        // Add our platform interface
        mJsPlatform = new JsPlatform();
        mMainView.addJavascriptInterface(mJsPlatform, "platformAndroid");

        // Disable scroll bar
        mMainView.setVerticalScrollBarEnabled(false);
        mMainView.setHorizontalScrollBarEnabled(false);

        // Disable zoom
        settings.setBuiltInZoomControls(false);
        settings.setSupportZoom(false);

        // Add WebViewClient to make it possible to jump between different html
        mMainView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });
    }

    private void InitKeyStore() {
        mKeyStore = new KeyStoreWrapper();
        if (!mKeyStore.hasSecretKey(KeyStoreWrapper.ALIAS_DEFAULT_KEY)) {
            mKeyStore.createSecretKey(KeyStoreWrapper.ALIAS_DEFAULT_KEY);
        }
        mKeyStore.loadSharedPreferences(getApplicationContext(), KeyStoreWrapper.ALIAS_DEFAULT_KEY);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Create IO executor
        mIOExecutor = Executors.newSingleThreadExecutor();
        InitWebView();
        InitKeyStore();
        // Load our web
        mMainView.loadUrl("file:///android_asset/web/index.html");
    }

    @Override
    protected void onDestroy() {
        if (mIOExecutor != null) {
            mIOExecutor.shutdown();
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CODE_JS_IMPORT_FILE:
                onJsFileChooserResult(requestCode, resultCode, data);
                break;
            case REQUEST_CODE_JS_EXPORT_FILE:
                onJsFileSaverResult(requestCode, resultCode, data);
            default:
                break;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void onJsFileSaverResult(int requestCode, int resultCode, final Intent data) {
        mJsPlatform.onFileExporterResult(true);
    }

    private void onJsFileChooserResult(int requestCode, int resultCode, final Intent data) {
        if (data == null || resultCode != RESULT_OK) {
            mJsRequestedData = null;
            mJsPlatform.onFileImporterResult();
            return;
        }

        assert (data.getData() != null);
        final Uri uri = data.getData();
        final ContentResolver contentResolver = getContentResolver();

        // Load file on IO thread.
        mIOExecutor.execute(new Runnable() {
            @Override
            public void run() {
                InputStream input = null;
                try {
                    input = contentResolver.openInputStream(uri);
                    if (input != null) {
                        Result<String> result = StreamUtils.readStringFromStream(input);
                        if (result.isOk()) {
                            mJsRequestedData = result.value;
                            // All WebView methods must be called on the same thread.
                            // So we call this on UI thread.
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    mJsPlatform.onFileImporterResult();
                                }
                            });
                        } else {
                            showToast(getErrorCodeString(result.code));
                        }
                    } else {
                        showToast(getErrorCodeString(ErrorCode.UNKNOWN));
                    }
                } catch (Exception e) {
                    if (e instanceof FileNotFoundException) {
                        showToast(getErrorCodeString(ErrorCode.FILE_NOT_FOUND));
                    } else {
                        showToast(getErrorCodeString(ErrorCode.UNKNOWN));
                    }
                } finally {
                    if (input != null) {
                        try {
                            input.close();
                        } catch (IOException e) {
                            Log.e("IO", e.getMessage());
                        }
                    }
                }
            }
        });
    }

    ///////////////////////////////////////////////////////
    //                      Utils                        //
    ///////////////////////////////////////////////////////

    public void showToast(final String message) {
        // Toast call only be used on main thread.
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void showAppSelector(Intent intent, int requestCode) {
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(Intent.createChooser(intent, getString(R.string.select_app)), requestCode);
        } else {
            showToast("No suitable app!");
        }
    }

    public String loadInternalFile(String name) {
        Log.w("File", "Trying to load internal file: " + name);
        try (InputStream input = openFileInput(name)) {
            Result<String> result = StreamUtils.readStringFromStream(input);
            if (result.isOk()) {
                Log.w("File", "Read from " + name + " done!");
                return result.value;
            } else {
                Log.e("File", "Read from " + name + " failed!");
                return null;
            }
        } catch (IOException e) {
            Log.e("File", e.getMessage());
            return null;
        }
    }

    public boolean storeInternalFile(String name, String data) {
        Log.w("File", "Trying to store internal file: " + name);
        try (OutputStream output = openFileOutput(name, Context.MODE_PRIVATE)) {
            if (StreamUtils.writeStringToStream(output, data) == ErrorCode.NO_ERROR) {
                Log.w("File", "Write to " + name + " done!");
                return true;
            } else {
                Log.e("File", "Write to " + name + " failed!");
                return false;
            }
        } catch (IOException e) {
            Log.e("File", e.getMessage());
            return false;
        }
    }

    public boolean deleteInternalFile(String name) {
        Log.w("File", "Trying to delete internal file: " + name);
        return deleteFile(name);
    }

    public String[] listInternalFiles() {
        return fileList();
    }

    public String getErrorCodeString(ErrorCode code) {
        Resources res = getResources();
        switch (code) {
            case FILE_NOT_FOUND:
                return res.getString(R.string.error_file_not_found);
            default:
                return res.getString(R.string.error_unknown);
        }
    }


    ///////////////////////////////////////////////////////
    //                   WebInterface                    //
    ///////////////////////////////////////////////////////

    class JsPlatform {
        private static final String TAG = "JsPlatform";

        public void onFileImporterResult() {
            mMainView.evaluateJavascript("platform._onFileImporterResult();", null);
        }

        public void onFileExporterResult(boolean success) {
            mMainView.evaluateJavascript("platform._onFileExporterResult(" + success + ");", null);
        }

        @JavascriptInterface
        public void showMessage(String message) {
            showToast(message);
        }

        @JavascriptInterface
        public void logMessage(String message) {
            Log.i(TAG, message);
        }

        @JavascriptInterface
        public void logError(String error) {
            Log.e(TAG, error);
        }

        @JavascriptInterface
        public void logWTF(String wtf) {
            Log.wtf(TAG, wtf);
        }

        @JavascriptInterface
        public String loadAssetFile(String name) {
            Log.w("JsPlatform", "Js trying to load internal file: " + name);
            return loadInternalFile(name);
        }

        @JavascriptInterface
        public boolean storeAssetFile(String name, String data) {
            Log.w("JsPlatform", "Js trying to store internal file: " + name);
            return storeInternalFile(name, data);
        }

        @JavascriptInterface
        public boolean deleteAssetFile(String name) {
            Log.w("JsPlatform", "Js trying to delete internal file: " + name);
            return deleteInternalFile(name);
        }

        @JavascriptInterface
        public String listAssetFiles() {
            try {
                JSONArray array = new JSONArray(listInternalFiles());
                return array.toString();
            } catch (JSONException e) {
                Log.e("JsPlatform", e.getMessage());
            }
            return "";
        }

        @JavascriptInterface
        public void showFileImporter(String type) {
            Intent intent = new Intent();
            intent.setAction(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(type);
            MainActivity.this.showAppSelector(intent, REQUEST_CODE_JS_IMPORT_FILE);
        }

        @JavascriptInterface
        public void showFileExporter(String name, String type) {
            Uri uri = FileProvider.getUriForFile(MainActivity.this, "com.hz.zebra.fileprovider", new File(getFilesDir() + "/" + name));

            Intent intent = new Intent();
            intent.setAction(Intent.ACTION_SEND);
            intent.setType(type);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            MainActivity.this.showAppSelector(intent, REQUEST_CODE_JS_EXPORT_FILE);
        }

        @JavascriptInterface
        public String getRequestedData() {
            String data = mJsRequestedData;
            mJsRequestedData = null;
            return data;
        }

        @JavascriptInterface
        public void saveSubPair(String subpwd, String key) {
            mKeyStore.saveToSharedPreferences(KEY_SUB_PWD, subpwd);
            mKeyStore.saveToSharedPreferences(KEY_KEY, key);
        }

        @JavascriptInterface
        public void clearSubPair() {
            mKeyStore.removeFromSharedPreferences(KEY_SUB_PWD);
            mKeyStore.removeFromSharedPreferences(KEY_KEY);
        }

        @JavascriptInterface
        public boolean hasSubPair() {
            return mKeyStore.loadFromSharedPreferences(KEY_SUB_PWD) != null;
        }

        @JavascriptInterface
        public String getSubPair() {
            /*
             * {
             *     "subpwd":"",
             *     "key":""
             * }
             */
            if(hasSubPair()){
                String subpwd = mKeyStore.loadFromSharedPreferences(KEY_SUB_PWD);
                String key = mKeyStore.loadFromSharedPreferences(KEY_KEY);
                String result = String.format("{\"subpwd\":\"%s\", \"key\":\"%s\"}", subpwd, key);
                Log.w("JsPlatform", result);
                return result;
            } else{
                return null;
            }
        }
    }
}
