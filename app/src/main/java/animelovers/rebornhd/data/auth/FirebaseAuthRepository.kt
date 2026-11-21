package animelovers.rebornhd.data.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import animelovers.rebornhd.R
import animelovers.rebornhd.util.Prefs
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FacebookAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Implementasi [AuthRepository] dengan Firebase Auth asli (v2.0.12).
 *
 * Prasyarat runtime (langkah console milik Faygo — BUKAN kode):
 * - Google: `default_web_client_id` di `google-services.json` (ada
 *   setelah SHA-1 keystore rilis didaftarkan + file diunduh ulang,
 *   langkah A2). Bila belum ada, masuk Google gagal dengan pesan
 *   jujur (`login_gagal`) — BUKAN crash, BUKAN bug kode.
 * - Facebook: App ID asli di `R.string.facebook_app_id` (langkah A3)
 *   + Client Token di `R.string.facebook_client_token` (langkah A1
 *   v2.0.13, meta-data di manifest). Client Token WAJIB sejak SDK
 *   13.2 — tanpanya auto-init SDK gagal dan sentuhan pertama ke
 *   LoginManager melempar (crash startup v2.0.12). Selama App ID
 *   masih placeholder `"0"`, masuk Facebook langsung gagal dengan
 *   pesan jujur tanpa menyentuh SDK.
 *
 * Keamanan: token Google/Facebook TIDAK PERNAH ditulis ke log, file
 * log, atau Prefs — hanya email + URL foto + nama provider yang
 * disimpan (kunci Prefs lama: auth_email/auth_photo/auth_provider).
 * App Secret TIDAK PERNAH ada di kode/repo ini.
 */
class FirebaseAuthRepository(context: Context) : AuthRepository {

    private val appContext = context.applicationContext
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val prefs = Prefs(appContext)

    private val callbackManager: CallbackManager = CallbackManager.Factory.create()
    private var pendingGoogle: CompletableDeferred<Pair<Int, Intent?>>? = null
    private var pendingFacebook: CompletableDeferred<LoginResult>? = null

    // v2.0.13 (seam hardening): callback Facebook TIDAK LAGI didaftarkan
    // di konstruktor. Alasannya: LoginManager.getInstance() menyentuh
    // Facebook SDK — bila SDK belum ter-init (mis. Client Token belum
    // ada di manifest, crash startup v2.0.12), konstruktor melempar dan
    // seluruh aplikasi mati saat Beranda pertama bertanya status login.
    // Callback kini didaftarkan SEKALI di ensureFacebookCallback(),
    // yang hanya dipanggil dari signInFacebook() — jalur eksplisit yang
    // SUDAH mengembalikan Result.failure (bukan crash) bila SDK gagal.
    // Perilaku login tidak berubah: callback tetap terdaftar SEBELUM
    // logInWithReadPermissions dipanggil, dan onActivityResult tetap
    // meneruskan hasil lewat CallbackManager yang sama.
    private var facebookCallbackRegistered = false

    private fun ensureFacebookCallback() {
        if (facebookCallbackRegistered) return
        LoginManager.getInstance().registerCallback(
            callbackManager,
            object : FacebookCallback<LoginResult> {
                override fun onSuccess(result: LoginResult) {
                    pendingFacebook?.complete(result)
                    pendingFacebook = null
                }

                override fun onCancel() {
                    pendingFacebook?.completeExceptionally(LoginCancelledException())
                    pendingFacebook = null
                }

                override fun onError(error: FacebookException) {
                    pendingFacebook?.completeExceptionally(error)
                    pendingFacebook = null
                }
            },
        )
        facebookCallbackRegistered = true
    }

    // ---------- Baca sesi (sumber kebenaran = Firebase) ----------

    /**
     * Selaraskan Prefs dengan `currentUser` Firebase (PRD §B7):
     * isi ulang bila Prefs kosong, bersihkan bila Prefs basi.
     * Murah (baca memori + paling banyak satu apply) — aman dipanggil
     * dari pembaca sesi mana pun.
     */
    private fun syncSession(): LoginSession.SignedInUser? {
        val firebaseUser = auth.currentUser
        val signedIn = firebaseUser?.let {
            LoginSession.fromUser(
                it.email,
                it.photoUrl?.toString(),
                it.providerData.map { info -> info.providerId },
            )
        }
        val saved = LoginSession.SavedAuth(
            prefs.authEmail, prefs.authPhotoUrl, prefs.authProvider,
        )
        when (val action = LoginSession.syncAction(signedIn, saved)) {
            is LoginSession.SyncAction.Save -> {
                prefs.authEmail = action.user.email
                prefs.authPhotoUrl = action.user.photoUrl
                prefs.authProvider = action.user.provider
            }
            LoginSession.SyncAction.ClearStale -> prefs.clearAuth()
            LoginSession.SyncAction.NoOp -> Unit
        }
        return signedIn
    }

    override fun isSignedIn(): Boolean = syncSession() != null

    override fun userEmail(): String = syncSession()?.email ?: ""

    override fun userPhotoUrl(): String = syncSession()?.photoUrl ?: ""

    override fun provider(): String = syncSession()?.provider ?: ""

    // ---------- Masuk Google ----------

    /** Web client ID dari google-services.json (string hasil generate
     *  plugin google-services). null bila belum ada (langkah A2 belum
     *  selesai) — pemanggil gagal dengan pesan jujur. */
    private fun resolveWebClientId(): String? {
        val resId = appContext.resources.getIdentifier(
            "default_web_client_id", "string", appContext.packageName,
        )
        if (resId == 0) return null
        return appContext.getString(resId).takeIf { it.isNotBlank() }
    }

    override suspend fun signInGoogle(activity: Activity): Result<Unit> {
        if (pendingGoogle != null) {
            return Result.failure(
                IllegalStateException("sign-in already in progress"),
            )
        }
        val webClientId = resolveWebClientId()
            ?: return Result.failure(IllegalStateException("no_web_client_id"))
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()
        val client = GoogleSignIn.getClient(appContext, gso)
        return try {
            val deferred = CompletableDeferred<Pair<Int, Intent?>>()
            pendingGoogle = deferred
            try {
                // Satu jalur hasil untuk kedua provider (lihat KDoc
                // AuthRepository) — startActivityForResult yang
                // deprecated tetap berfungsi penuh di minSdk 21–34.
                @Suppress("DEPRECATION")
                activity.startActivityForResult(
                    client.signInIntent,
                    LoginSession.RC_GOOGLE_SIGN_IN,
                )
            } catch (e: Exception) {
                pendingGoogle = null
                return Result.failure(e)
            }
            val (resultCode, data) = deferred.await()
            if (resultCode != Activity.RESULT_OK || data == null) {
                return Result.failure(LoginCancelledException())
            }
            val account = try {
                GoogleSignIn.getSignedInAccountFromIntent(data)
                    .getResult(ApiException::class.java)
            } catch (e: ApiException) {
                if (LoginSession.isGoogleUserCancelled(e.statusCode)) {
                    return Result.failure(LoginCancelledException())
                }
                return Result.failure(e)
            }
            val idToken = account.idToken
                ?: return Result.failure(IllegalStateException("no_id_token"))
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            signInWithCredential(credential).fold(
                onSuccess = { saveUserToPrefs(it); Result.success(Unit) },
                onFailure = { Result.failure(it) },
            )
        } catch (e: CancellationException) {
            pendingGoogle = null
            throw e
        } catch (e: Exception) {
            pendingGoogle = null
            Result.failure(e)
        }
    }

    // ---------- Masuk Facebook ----------

    override suspend fun signInFacebook(activity: Activity): Result<Unit> {
        if (pendingFacebook != null) {
            return Result.failure(
                IllegalStateException("sign-in already in progress"),
            )
        }
        // JANGAN sentuh SDK selama App ID masih placeholder — login
        // gagal dengan pesan jujur sampai langkah A3 selesai.
        val appId = appContext.getString(R.string.facebook_app_id)
        if (appId.isBlank() || appId == FACEBOOK_APP_ID_PLACEHOLDER) {
            return Result.failure(
                IllegalStateException("facebook_not_configured"),
            )
        }
        return try {
            val deferred = CompletableDeferred<LoginResult>()
            pendingFacebook = deferred
            try {
                ensureFacebookCallback()
                LoginManager.getInstance().logInWithReadPermissions(
                    activity,
                    listOf("email", "public_profile"),
                )
            } catch (e: Exception) {
                pendingFacebook = null
                return Result.failure(e)
            }
            val loginResult = deferred.await()
            val credential = FacebookAuthProvider.getCredential(
                loginResult.accessToken.token,
            )
            signInWithCredential(credential).fold(
                onSuccess = { saveUserToPrefs(it); Result.success(Unit) },
                onFailure = { Result.failure(it) },
            )
        } catch (e: CancellationException) {
            pendingFacebook = null
            throw e
        } catch (e: Exception) {
            pendingFacebook = null
            Result.failure(e)
        }
    }

    /** Teruskan hasil Activity ke CallbackManager Facebook dulu,
     *  lalu ke intent Google yang sedang ditunggu. */
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ): Boolean {
        if (callbackManager.onActivityResult(requestCode, resultCode, data)) {
            return true
        }
        if (requestCode == LoginSession.RC_GOOGLE_SIGN_IN) {
            val deferred = pendingGoogle ?: return false
            pendingGoogle = null
            deferred.complete(resultCode to data)
            return true
        }
        return false
    }

    // ---------- Keluar ----------

    override suspend fun signOut() {
        // Tiap langkah dibungkus runCatching — keluar TIDAK BOLEH
        // gagal di tengah jalan; Prefs selalu dibersihkan.
        runCatching { auth.signOut() }
        runCatching { LoginManager.getInstance().logOut() }
        runCatching {
            GoogleSignIn.getClient(
                appContext,
                GoogleSignInOptions.DEFAULT_SIGN_IN,
            ).signOut()
        }
        prefs.clearAuth()
        prefs.clearEcchi()
    }

    // ---------- Pembantu ----------

    /** Masuk Firebase dengan kredensial provider (Google/Facebook). */
    private suspend fun signInWithCredential(
        credential: AuthCredential,
    ): Result<FirebaseUser> {
        return try {
            val authResult = suspendCancellableCoroutine<AuthResult> { cont ->
                auth.signInWithCredential(credential).addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val result = task.result
                        if (result != null) {
                            cont.resume(result)
                        } else {
                            cont.resumeWithException(
                                IllegalStateException("sign-in failed"),
                            )
                        }
                    } else {
                        cont.resumeWithException(
                            task.exception
                                ?: IllegalStateException("sign-in failed"),
                        )
                    }
                }
            }
            val user = authResult.user
                ?: return Result.failure(IllegalStateException("no_user"))
            Result.success(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Simpan email + foto + provider ke Prefs (kunci lama). */
    private fun saveUserToPrefs(user: FirebaseUser) {
        val signedIn = LoginSession.fromUser(
            user.email,
            user.photoUrl?.toString(),
            user.providerData.map { it.providerId },
        )
        prefs.authEmail = signedIn.email
        prefs.authPhotoUrl = signedIn.photoUrl
        prefs.authProvider = signedIn.provider
    }

    companion object {
        /**
         * Placeholder App ID Facebook (strings.xml) — "0" = BELUM
         * DIISI (langkah A3 belum selesai). Dipakai angka "0" supaya
         * SDK yang mem-parsing App ID sebagai angka tidak jatuh;
         * nilai ini TIDAK PERNAH dipakai untuk login sungguhan
         * (dijaga di signInFacebook).
         */
        const val FACEBOOK_APP_ID_PLACEHOLDER = "0"
    }
}
