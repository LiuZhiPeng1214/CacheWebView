package ren.yale.android.cachewebviewlib;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

import ren.yale.android.cachewebviewlib.config.CacheConfig;
import ren.yale.android.cachewebviewlib.utils.FileUtil;
import ren.yale.android.cachewebviewlib.utils.NetworkUtils;


/**
 * Created by yale on 2017/9/15.
 */

public class CacheWebView extends WebView {

    private static final String CACHE_NAME = "CacheWebView";
    private static final int CACHE_SIZE = 200*1024*1024;
    private String mAppCachePath = "";
    private CacheWebViewClient mCacheWebViewClient;
    private MyJavascriptInterface mInterface = new MyJavascriptInterface();
    private ArrayList<WVJBMessage> messageQueue = new ArrayList<>();
    private Map<String,WVJBResponseCallback> responseCallbacks = new HashMap<>();
    private Map<String,WVJHandler> messageHandlers =  new HashMap<>();
    private long uniqueId = 0;
    private String script;

    private WebViewCache mWebViewCache;

    public void injectJavascriptFile() {
        try {
            if (TextUtils.isEmpty(script)) {
                InputStream in = getResources().getAssets().open("WebViewJavascriptBridge.js");
                script = convertStreamToString(in);
            }
            executeJavascript(script);
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (messageQueue != null) {
            for (WVJBMessage message: messageQueue) {
                dispatchMessage(message);
            }
            messageQueue = null;
        }
    }

    private String convertStreamToString(InputStream is) {
        String s = "";
        try {
            Scanner scanner = new Scanner(is,"UTF-8").useDelimiter("\\A");
            if (scanner.hasNext()) s = scanner.next();
            is.close();
        }catch (IOException e) {
            e.printStackTrace();
        }
        return s;
    }

    public void flushMessageQueue() {
        String script = "WebViewJavascriptBridge._fetchQueue()";
        executeJavascript(script, new JavascriptCallback() {
            @Override
            public void onReceiveValue(String messageQueueString) {
                if (!TextUtils.isEmpty(messageQueueString)) {
                    processMessageQueue(messageQueueString);
                }
            }
        });
    }

    private void processMessageQueue(String messageQueueString) {
        if (TextUtils.isEmpty(messageQueueString)) {
            return;
        }
        try {
            JSONArray messages = new JSONArray(messageQueueString);
            for (int i = 0;i < messages.length(); i++) {
                JSONObject jo = messages.getJSONObject(i);
                WVJBMessage message = json2Message(jo);
                if (message.responseId != null) {
                    WVJBResponseCallback responseCallback = responseCallbacks.remove(message.responseId);
                    if (responseCallback != null) {
                        responseCallback.callback(message.responseData);
                    }
                }else {
                    WVJBResponseCallback responseCallback = null;
                    if (message.callbackId != null) {
                        final String callbackId = message.callbackId;
                        responseCallback = new WVJBResponseCallback() {
                            @Override
                            public void callback(Object data) {
                                WVJBMessage msg = new WVJBMessage();
                                msg.responseId = callbackId;
                                msg.responseData = data;
                                queueMessage(msg);
                            }
                        };
                    }

                    WVJHandler handler = messageHandlers.get(message.handlerName);
                    if (handler != null) {
                        handler.request(message.data,responseCallback);
                    }else {
                        Log.e(Constants.TAG, "No handler for message from JS:" + message.handlerName);
                    }
                }
            }
        }catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private WVJBMessage json2Message(JSONObject object) {
        WVJBMessage message = new WVJBMessage();
        try {
            if (object.has("callbackId")) {
                message.callbackId = object.getString("callbackId");
            }
            if (object.has("data")) {
                message.data = object.get("data");
            }
            if (object.has("handlerName")) {
                message.handlerName = object.getString("handlerName");
            }
            if (object.has("responseId")) {
                message.responseId = object.getString("responseId");
            }
            if (object.has("responseData")) {
                message.responseData = object.get("responseData");
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return message;
    }

    public interface WVJBResponseCallback {
        void callback(Object data);
    }

    public interface  WVJHandler {
        void request(Object data,WVJBResponseCallback callback);
    }

    public CacheWebView(Context context) {
        super(context);
        init();
    }

    public CacheWebView(Context context, AttributeSet attrs) {
        super(context,attrs);
        init();
    }

    public CacheWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    private void init(){
        initData();
        initSettings();
        addJavascriptInterface(mInterface,Constants.INTERFACE);
        initWebViewClient();
    }

    public void callHandler(String handlerName) {
        callHandler(handlerName,null,null);
    }

    public void callHandler(String handlerName, Object data) {
        callHandler(handlerName, data, null);
    }

    public void callHandler(String handlerName, Object data,
                            WVJBResponseCallback callback) {
        sendData(data, callback, handlerName);
    }

    private void registerHandler(String handlerName, WVJHandler handler) {
        if (TextUtils.isEmpty(handlerName) || handler == null)
            return;
        messageHandlers.put(handlerName, handler);
    }

    private void sendData(Object data, WVJBResponseCallback callback, String handlerName) {
        if (data == null && TextUtils.isEmpty(handlerName))
            return;
        WVJBMessage message = new WVJBMessage();
        if (data != null) {
            message.data = data;
        }

        if (callback != null) {
            String callbackId = "java_cb_" + (++uniqueId);
            responseCallbacks.put(callbackId,callback);
            message.callbackId = callbackId;
        }

        if (handlerName != null) {
            message.handlerName = handlerName;
        }
        queueMessage(message);
    }

    private void queueMessage(WVJBMessage message) {
        if (messageQueue != null) {
            messageQueue.add(message);
        }else {
            dispatchMessage(message);
        }
    }

    private void dispatchMessage(WVJBMessage message) {
        String messageJSON = doubleEscapeString(message2Json(message).toString());
        executeJavascript("WebViewJavascriptBridge._handleMessageFromJava('"
                + messageJSON + "');");
    }

    private void executeJavascript(String script) {
        executeJavascript(script,null);
    }

    private void executeJavascript(final String script,
                                   final JavascriptCallback callback) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            evaluateJavascript(script, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    if (callback != null) {
                        if (value != null && value.startsWith("\"")
                                && value.endsWith("\"")) {
                            value = value.substring(1, value.length() - 1)
                                    .replaceAll("\\\\", "");
                        }
                        callback.onReceiveValue(value);
                    }
                }
            });
        } else {
            if (callback != null) {
                mInterface.addCallback(++uniqueId + "", callback);
                post(new Runnable() {
                    @Override
                    public void run() {
                        loadUrl("javascript:window." + Constants.INTERFACE
                                + ".onResultForScript(" + uniqueId + "," + script + ")");
                    }
                });
            } else {
                post(new Runnable() {
                    @Override
                    public void run() {
                        loadUrl("javascript:" + script);
                    }
                });
            }
        }
    }


    private String doubleEscapeString(String javascript) {
        String result;
        result = javascript.replace("\\","\\\\");
        result = result.replace("\"","\\\"");
        result = result.replace("\'","\\\'");
        result = result.replace("\n", "\\n");
        result = result.replace("\r", "\\r");
        result = result.replace("\f", "\\f");
        return result;
    }

    private JSONObject message2Json(WVJBMessage message) {
        JSONObject object = new JSONObject();
        try {
            if (message.callbackId != null) {
                object.put("callbackId", message.callbackId);
            }
            if (message.data != null) {
                object.put("data", message.data);
            }
            if (message.handlerName != null) {
                object.put("handlerName", message.handlerName);
            }
            if (message.responseId != null) {
                object.put("responseId", message.responseId);
            }
            if (message.responseData != null) {
                object.put("responseData", message.responseData);
            }
        }catch (JSONException e) {
            e.printStackTrace();
        }
        return object;
    }
    private void initData() {

        mWebViewCache = new WebViewCache();
        File cacheFile = new File(getContext().getCacheDir(),CACHE_NAME);
        try {
            mWebViewCache.openCache(getContext(),cacheFile.getAbsolutePath(),CACHE_SIZE);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void setEncoding(String encoding){
        if (TextUtils.isEmpty(encoding)){
            encoding = "UTF-8";
        }
        mCacheWebViewClient.setEncoding(encoding);
    }
    public void setCacheInterceptor(CacheInterceptor interceptor){
        mCacheWebViewClient.setCacheInterceptor(interceptor);
    }

    public static CacheConfig getCacheConfig(){
        return CacheConfig.getInstance();
    }

    public WebViewCache getWebViewCache(){
        return mWebViewCache;
    }

    public void setWebViewClient(WebViewClient client){
        mCacheWebViewClient.setCustomWebViewClient(client);
    }

    private void initWebViewClient() {
        mCacheWebViewClient = new CacheWebViewClient(this);
       super.setWebViewClient(mCacheWebViewClient);
        mCacheWebViewClient.setUserAgent(this.getSettings().getUserAgentString());
        mCacheWebViewClient.setWebViewCache(mWebViewCache);
    }

    public void setCacheStrategy(WebViewCache.CacheStrategy cacheStrategy){
        mCacheWebViewClient.setCacheStrategy(cacheStrategy);
        if (cacheStrategy == WebViewCache.CacheStrategy.NO_CACHE){
            setWebViewDefaultNoCache();
        }else{
            setWebViewDefaultCacheMode();
        }
    }

    public static CacheWebView cacheWebView(Context context){
        return new CacheWebView(context);
    }
    public static void servicePreload(Context context,String url){
        servicePreload(context,url,null);
    }
    public static void servicePreload(Context context,String url,HashMap<String,String> headerMap){
        if (context==null||TextUtils.isEmpty(url)){
            return;
        }
        Intent intent = new Intent(context, CachePreLoadService.class);
        intent.putExtra(CachePreLoadService.KEY_URL,url);
        if (headerMap!=null){
            intent.putExtra(CachePreLoadService.KEY_URL_HEADER,headerMap);
        }
        context.startService(intent);
    }

    public void setEnableCache(boolean enableCache){
        mCacheWebViewClient.setEnableCache(enableCache);
    }
    public void loadUrl(String url){
        if (url.startsWith("http")){
            mCacheWebViewClient.addVisitUrl(url);
        }
        super.loadUrl(url);
    }
    public void loadUrl(String url, Map<String, String> additionalHttpHeaders) {
        mCacheWebViewClient.addVisitUrl(url);
        if (additionalHttpHeaders!=null){
            mCacheWebViewClient.addHeaderMap(url,additionalHttpHeaders);
            super.loadUrl(url,additionalHttpHeaders);
        }else{
            super.loadUrl(url);
        }

    }
    public void setBlockNetworkImage(boolean isBlock){
       mCacheWebViewClient.setBlockNetworkImage(isBlock);
    }

    private void initSettings(){
        WebSettings webSettings = this.getSettings();

        webSettings.setJavaScriptEnabled(true);

        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setSupportZoom(true);
        webSettings.setBuiltInZoomControls(false);
        webSettings.setDisplayZoomControls(false);

        webSettings.setDefaultTextEncodingName("UTF-8");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            webSettings.setAllowFileAccessFromFileURLs(true);
            webSettings.setAllowUniversalAccessFromFileURLs(true);
        }
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP){
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.setAcceptThirdPartyCookies(this,true);
        }
        setWebViewDefaultCacheMode();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webSettings.setMixedContentMode(
                    WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
        setCachePath();

    }
    private void setWebViewDefaultNoCache(){
        WebSettings webSettings = this.getSettings();
        webSettings.setCacheMode(
                WebSettings.LOAD_NO_CACHE);
    }
    private void setWebViewDefaultCacheMode(){
        WebSettings webSettings = this.getSettings();
        if (NetworkUtils.isConnected(this.getContext()) ){
            webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        } else {
            webSettings.setCacheMode(
                    WebSettings.LOAD_CACHE_ELSE_NETWORK);
        }
    }
    public String getUserAgent(){
        return  this.getSettings().getUserAgentString();
    }

    public void setUserAgent(String userAgent){
        WebSettings webSettings = this.getSettings();
        webSettings.setUserAgentString(userAgent);
        mCacheWebViewClient.setUserAgent(userAgent);
    }

    private void setCachePath(){

        File  cacheFile = new File(this.getContext().getCacheDir(),CACHE_NAME);
        String path = cacheFile.getAbsolutePath();
        mAppCachePath = path;

        File file = new File(path);
        if (!file.exists()){
            file.mkdirs();
        }

        WebSettings webSettings = this.getSettings();
        webSettings.setAppCacheEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setDatabasePath(path);
    }

    public void clearCache(){
        CacheWebViewLog.d("clearCache");
        this.stopLoading();
        clearCache(true);
        FileUtil.deleteDirs(mAppCachePath,false);
        mWebViewCache.clean();

    }

    public void destroy(){

        CacheWebViewLog.d("destroy");
        mCacheWebViewClient.clear();
        mWebViewCache.release();

        this.stopLoading();
        this.getSettings().setJavaScriptEnabled(false);
        this.clearHistory();
        this.removeAllViews();

        ViewParent viewParent = this.getParent();

        if (viewParent == null){
            super.destroy();
            return ;
        }
        ViewGroup parent = (ViewGroup) viewParent;
        parent.removeView(this);
        super.destroy();
    }

    @Override
    public void goBack() {
        if (canGoBack()){
            mCacheWebViewClient.clearLastUrl();
            super.goBack();
        }
    }

    public void evaluateJS(String strJsFunction){
        this.evaluateJS(strJsFunction,null);
    }
    public void evaluateJS(String strJsFunction,ValueCallback valueCallback){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT&&valueCallback!=null) {
            this.evaluateJavascript("javascript:"+strJsFunction, valueCallback);
        } else {
            this.loadUrl("javascript:"+strJsFunction);
        }
    }

    private class MyJavascriptInterface {
            Map<String, JavascriptCallback> map = new HashMap<>();

            public void addCallback(String key,JavascriptCallback callback) {
                map.put(key,callback);
            }

            @JavascriptInterface
            public void onResultForScript(String key,String value) {
                JavascriptCallback callback = map.remove(key);
                if (callback != null) {
                    callback.onReceiveValue(value);
                }
            }
    }

    public interface  JavascriptCallback {
        void onReceiveValue(String value);
    }
}
