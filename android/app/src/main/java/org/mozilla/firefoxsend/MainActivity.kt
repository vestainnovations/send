package org.mozilla.firefoxsend

import android.content.ComponentName
import android.os.Bundle
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity

import mozilla.components.feature.session.SessionFeature
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.browser.session.SessionManager
import mozilla.components.browser.session.Session
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.concept.engine.EngineView
import mozilla.components.feature.prompts.PromptFeature
import org.json.JSONObject
import org.mozilla.geckoview.*


/*
internal class LoggingWebChromeClient : WebChromeClient() {
    override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
        Log.w("CONTENT", String.format("%s @ %d: %s",
                cm.message(), cm.lineNumber(), cm.sourceId()))
        return true
    }
}

// TODO Replace with geckoview equivalent
class WebAppInterface(private val mContext: MainActivity) {
    @JavascriptInterface
    fun beginOAuthFlow() {
        mContext.beginOAuthFlow();
    }

    @JavascriptInterface
    fun shareUrl(url: String) {
        mContext.shareUrl(url)
    }
}
*/
private const val REQUEST_CODE_PROMPT_PERMISSIONS = 2

class MainActivity : AppCompatActivity() {
    // private var mWebView: AdvancedWebView? = null
    companion object {
        var mToShare: String? = null
        var mToCall: String? = null
        var mSessionFeature: SessionFeature? = null
        var mGeckoRuntime: GeckoRuntime? = null
        var mGeckoEngine: GeckoEngine? = null
        var mSessionManager: SessionManager? = null
        var mEngineView: EngineView? = null
        var mPromptFeature: PromptFeature? = null
        var mPort: WebExtension.Port? = null
        var mInitialized = false
        var mWebExtensionRegistered = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if(!MainActivity.mInitialized) {
            MainActivity.mInitialized = true
            val builder = GeckoRuntimeSettings.Builder().consoleOutput(true)
            MainActivity.mGeckoRuntime = GeckoRuntime.create(applicationContext, builder.build())
            val settings = DefaultSettings()
            settings.remoteDebuggingEnabled = true
            settings.userAgentString = "Send Android"
            MainActivity.mGeckoEngine = GeckoEngine(applicationContext, settings, MainActivity.mGeckoRuntime!!)
            MainActivity.mSessionManager =
                SessionManager(MainActivity.mGeckoEngine!!)
            val sessionUseCases = SessionUseCases(MainActivity.mSessionManager!!)
            val sessionId = "Send Android"
            MainActivity.mEngineView = MainActivity.mGeckoEngine!!.createView(applicationContext)
            MainActivity.mSessionFeature = SessionFeature(
                MainActivity.mSessionManager!!,
                sessionUseCases,
                MainActivity.mEngineView!!,
                sessionId
            )


            val portDelegate = object: WebExtension.PortDelegate {
                override fun onPortMessage(source: WebExtension, message: Any, session: GeckoSession?) {
                    Log.e("DEBUG", "onPortMessage" + message)
                    if (message is JSONObject) {
                        if (message.get("cmd") == "shareUrl") {
                            val url = message.get("url")
                            if (url is String) {
                                val shareIntent = Intent()
                                shareIntent.action = Intent.ACTION_SEND
                                shareIntent.type = "text/plain"
                                shareIntent.putExtra(Intent.EXTRA_TEXT, url)
                                val chooser = Intent.createChooser(shareIntent, "")
                                chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(applicationContext, MainActivity::class.java)))
                                startActivity(chooser)
                                1                            }
                        }
                    }
                }

                override fun onDisconnect(source: WebExtension, port: WebExtension.Port) {
                    // ignored
                    Log.e("DEBUG", "onDisconnect")
                }
            }

            val messageDelegate = object : WebExtension.MessageDelegate {
                override fun onConnect(source: WebExtension, port: WebExtension.Port, session: GeckoSession?) {
                    Log.e("DEBUG", "onConnect")
                    MainActivity.mPort = port
                    port.setDelegate(portDelegate)
                    port.postMessage(JSONObject("{\"message\": \"helloworld\"}"))
                    if (MainActivity.mToShare != null) {
                        Log.e("DEBUG", "WE HAVE SOMETHING TO SHARE!")
                        shareSomething()
                    }

                }

                override fun onMessage(source: WebExtension, message: Any, session: GeckoSession?): GeckoResult<Any>? {
                    return GeckoResult.fromValue(Unit)
                }
            }

            Log.e("DEBUG", "REGISTERWEBEXTENSION")
            MainActivity.mGeckoRuntime!!.registerWebExtension(WebExtension(
                "resource://android/assets/send-android-comms-bridge/",
                "send-android-comms-bridge",
                messageDelegate)).then({
                MainActivity.mWebExtensionRegistered = true
                Log.e("DEBUG", "REGISTERCOMPLETE")

                GeckoResult.fromValue(Unit)
            })

            val initialSession = Session("http://172.20.10.2/android.html?5")
            MainActivity.mSessionManager!!.add(initialSession, selected = true)

            MainActivity.mPromptFeature = PromptFeature(
                activity = this,
                sessionManager = MainActivity.mSessionManager!!,
                fragmentManager = supportFragmentManager,
                onNeedToRequestPermissions = { permissions ->
                    Log.e("DEBUG","onNeedToRequestPermissions")
                    requestPermissions(permissions, REQUEST_CODE_PROMPT_PERMISSIONS)
                })
        }

        setContentView(R.layout.activity_main)
        MainActivity.mEngineView!!.render(MainActivity.mSessionManager!!.getOrCreateEngineSession())

        val intent = getIntent()
        val action = intent.getAction()
        val type = intent.getType()

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            if (type.equals("text/plain")) {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                Log.w("INTENT", "text/plain " + sharedText)
                MainActivity.mToShare = "data:text/plain;base64," + Base64.encodeToString(sharedText.toByteArray(), 16).trim()
                if (MainActivity.mWebExtensionRegistered) {
                    shareSomething()
                }
            } else if (type.startsWith("image/")) {
                val imageUri = intent.getParcelableExtra(Intent.EXTRA_STREAM) as Uri
                Log.w("INTENT", "image/ " + imageUri)
                MainActivity.mToShare = "data:text/plain;base64," + Base64.encodeToString(imageUri.path!!.toByteArray(), 16).trim()
                if (MainActivity.mWebExtensionRegistered) {
                    shareSomething()
                }
            }
        }
    }

    fun shareSomething() {
        Log.e("DEBUG", "shareSomething!!!")
        MainActivity.mPort!!.postMessage(JSONObject(
            "{\"cmd\": \"incomingShare\", \"url\": \"" + MainActivity.mToShare + "\"}"))

    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        Log.e("DEBUG", "ONREQUESTPERMISSIONSRESULT")
        when (requestCode) {
            REQUEST_CODE_PROMPT_PERMISSIONS -> MainActivity.mPromptFeature!!.onPermissionsResult(permissions, grantResults)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.e("DEBUG", "ONACTIVITYRESULT")
        MainActivity.mPromptFeature!!.onActivityResult(requestCode, resultCode, data)
    }

    override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? {
        if (name == EngineView::class.java.name) {
            val toReturn = MainActivity.mEngineView!!.asView()
            val parent = toReturn.parent
            if (parent is ViewGroup) {
                parent.removeView(toReturn)
            }
            return toReturn
        } else {
            return super.onCreateView(parent, name, context, attrs)
        }
    }

    override fun onStart() {
        super.onStart()
        if (MainActivity.mSessionFeature != null) {
            MainActivity.mSessionFeature!!.start()
        }
        if (MainActivity.mPromptFeature != null) {
            MainActivity.mPromptFeature!!.start()
        }
    }

    override fun onStop() {
        super.onStop()
        if (MainActivity.mSessionFeature != null) {
            MainActivity.mSessionFeature!!.stop()
        }
        if (MainActivity.mPromptFeature != null) {
            MainActivity.mPromptFeature!!.stop()
        }
    }
/*
    fun beginOAuthFlow() {
        Config.release().then(fun (value: Config): FxaResult<Unit> {
            mAccount = FirefoxAccount(value, "20f7931c9054d833", "https://send.firefox.com/fxa/android-redirect.html")
            mAccount?.beginOAuthFlow(arrayOf("profile", "https://identity.mozilla.com/apps/send"), true)?.then(fun (url: String): FxaResult<Unit> {
                Log.w("CONFIG", "GOT A URL " + url)
                this@MainActivity.runOnUiThread({
                    // mWebView!!.loadUrl(url)
                })
                return FxaResult.fromValue(Unit)
            })
            Log.w("CONFIG", "CREATED FIREFOXACCOUNT")
            return FxaResult.fromValue(Unit)
        })
    }

    @SuppressLint("NewApi")
    override fun onResume() {
        super.onResume()
        // mWebView!!.onResume()
        // ...
    }

    @SuppressLint("NewApi")
    override fun onPause() {
        // mWebView!!.onPause()
        // ...
        super.onPause()
    }

    override fun onDestroy() {
        // mWebView!!.onDestroy()
        // ...
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)
        // mWebView!!.onActivityResult(requestCode, resultCode, intent)
        // ...
    }

    override fun onBackPressed() {
        // if (!mWebView!!.onBackPressed()) {
            // return
        // }
        // ...
        super.onBackPressed()
    }

    fun onPageStarted(url: String, favicon: Bitmap?) {
        if (url.startsWith("https://send.firefox.com/fxa/android-redirect.html")) {
            // We load this here so the user doesn't see the android-redirect.html page
            // mWebView!!.loadUrl("file:///android_asset/android.html")

            val parsed = Uri.parse(url)
            val code = parsed.getQueryParameter("code")
            val state = parsed.getQueryParameter("state")

            code?.let { code ->
                state?.let { state ->
                    mAccount?.completeOAuthFlow(code, state)?.whenComplete { info ->
                        //displayAndPersistProfile(code, state)
                        val profile = mAccount?.getProfile(false)?.then(fun (profile: Profile): FxaResult<Unit> {
                            val accessToken = info.accessToken
                            val keys = info.keys
                            val avatar = profile.avatar
                            val displayName = profile.displayName
                            val email = profile.email
                            val uid = profile.uid
                            val toPass = "{\"accessToken\": \"${accessToken}\", \"keys\": '${keys}', \"avatar\": \"${avatar}\", \"displayName\": \"${displayName}\", \"email\": \"${email}\", \"uid\": \"${uid}\"}"
                            mToCall = "finishLogin(${toPass})"
                            this@MainActivity.runOnUiThread({
                                // Clear the history so that the user can't use the back button to see broken pages
                                // that were inserted into the history by the login process.
                                // mWebView!!.clearHistory()

                                // We also reload this here because we need to make sure onPageFinished runs after mToCall has been set.
                                // We can't guarantee that onPageFinished wasn't already called at this point.
                                // mWebView!!.loadUrl("file:///android_asset/android.html")
                            })


                            return FxaResult.fromValue(Unit)
                        })
                    }
                }
            }
        }
        Log.w("MAIN", "onPageStarted");
    }

    fun onPageFinished(url: String) {
        Log.w("MAIN", "onPageFinished")
        if (mToShare != null) {
            Log.w("INTENT", mToShare)

            // mWebView?.postWebMessage(WebMessage(mToShare), Uri.EMPTY)
            mToShare = null
        }
        if (mToCall != null) {
            this@MainActivity.runOnUiThread({
                // mWebView?.evaluateJavascript(mToCall, fun (value: String) {
                    // mToCall = null
                // })
            })
        }
    }

    fun onPageError(errorCode: Int, description: String, failingUrl: String) {
        Log.w("MAIN", "onPageError " + description)
    }

    fun onDownloadRequested(url: String, suggestedFilename: String, mimeType: String, contentLength: Long, contentDisposition: String, userAgent: String) {
        Log.w("MAIN", "onDownloadRequested")
    }

    fun onExternalPageRequest(url: String) {
        Log.w("MAIN", "onExternalPageRequest")
    }
*/
}
