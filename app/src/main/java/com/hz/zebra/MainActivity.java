package com.hz.zebra;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CODE_JS_IMPORT_FILE = 1;
    private static final int REQUEST_CODE_JS_EXPORT_FILE = 2;

    private WebView mMainView;
    private String mJsRequestedData;
    private ExecutorService mIOExecutor;
    private JsPlatform mJsPlatform;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Create IO executor
        mIOExecutor = Executors.newSingleThreadExecutor();

        // Enable javascript
        mMainView = (WebView) findViewById(R.id.main_view);
        WebSettings settings = mMainView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setUserAgentString("platform:android;");

        mJsPlatform = new JsPlatform();
        mMainView.addJavascriptInterface(mJsPlatform, "PlatformAndroid");

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
    }


}
